// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.geoserver;

import dev.mosaikit.kernel.api.audit.AuditLog;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import dev.mosaikit.kernel.api.secrets.SecretValue;
import dev.geoportal.ext.catalog.geodata.Credentials;
import dev.mosaikit.kernel.api.secrets.Secrets;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The GeoServer of the organization of the request, governed by the catalog (GP-334, ADR-0014): reads
 * what the catalog publishes, the credentials of GeoServer and of the geodata database from the
 * credentials of Mosaikit, and aligns. Callers hold the transaction of the request.
 *
 * <p>Settings of the installation (none of them secret):
 *
 * <ul>
 *   <li>{@code geoportal.geoserver.url}: GeoServer as the catalog reaches it, default
 *       {@code http://geoserver:8080/geoserver};
 *   <li>{@code geoportal.geodata.host}, {@code .port}, {@code .database}: the geodata database as
 *       GeoServer reaches it, default {@code postgres}, {@code 5432}, {@code geodata};
 *   <li>{@code geoportal.geodata.jdbc-url}: the same database as the catalog reaches it, default built
 *       from the three above.
 * </ul>
 *
 * <p>Credentials, each {@code <user>:<password>}: {@value #GEOSERVER_CREDENTIAL}, the administrator of
 * GeoServer; {@value #GEODATA_CREDENTIAL}, the login of the stores on the geodata database. They are
 * read with {@link Secrets#resolve}: the credential of the organization when it stored one, otherwise
 * that of the installation (MK-127), so that the alignment also runs in the handler of an organization
 * event, without a person ({@link OrganizationEvents}).
 */
@ApplicationScoped
public class GeoServerGovernance {

    public static final String EXTENSION = "dev.geoportal.ext.catalog.geodata";
    public static final String GEOSERVER_CREDENTIAL = "geoserver";
    public static final String GEODATA_CREDENTIAL = "geodata";

    private final Publications publications;
    private final CurrentOrganization organization;
    private final Credentials secrets;
    /** GP-986: the stores of the connections whose tables the layers serve. */
    @jakarta.inject.Inject
    dev.geoportal.ext.catalog.geodata.connection.ConnectionStores connectionStores;
    private final AuditLog audit;
    private final Event<Aligned> aligned;
    /** One alignment at a time per organization, in this process. */
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();
    /** The client of each organization for the calls outside an alignment, kept a minute (see {@link #client()}). */
    private final Map<UUID, Kept> clients = new ConcurrentHashMap<>();

    private record Kept(GeoServerClient client, Instant until) {}

    /** How long the client of an organization is kept: a replaced credential is used within it. */
    static final Duration KEEP = Duration.ofMinutes(1);

    public GeoServerGovernance(Publications publications, CurrentOrganization organization, Credentials secrets, AuditLog audit,
            Event<Aligned> aligned) {
        this.publications = publications;
        this.organization = organization;
        this.secrets = secrets;
        this.audit = audit;
        this.aligned = aligned;
    }

    /** The space of the organization of the request. */
    public OrganizationSpace space() {
        return new OrganizationSpace(organization.require());
    }

    /** What an alignment would change now, and the drift. */
    public Alignment plan() {
        return run(false);
    }

    /** Aligns GeoServer with the catalog; recorded in the audit log of the organization. */
    public Alignment align() {
        return run(true);
    }

    /**
     * The reconciliation report (GP-118): what matches, what GeoServer misses, what drifted, what
     * needs a person; with {@code apply}, after aligning.
     */
    public Reconciliation reconcile(boolean apply) {
        Alignment alignment = run(apply);
        return Reconciliation.of(alignment, layers().stream().map(LayerDefinition::name).toList(),
                styles().stream().map(StyleDefinition::name).toList());
    }

    private Alignment run(boolean apply) {
        OrganizationSpace space = space();
        synchronized (locks.computeIfAbsent(space.organization(), k -> new Object())) {
            List<PublishedLayer> layers = publications.layers();
            List<PublishedStyle> styles = publications.styles();
            Alignment result;
            Map<String, ConnectionStore> connections = Map.of();
            try (Credential gs = credential(GEOSERVER_CREDENTIAL);
                    Credential db = credential(GEODATA_CREDENTIAL)) {
                Config config = ConfigProvider.getConfig();
                String url = geoserverUrl();
                String host = setting(config, "geoportal.geodata.host", "postgres");
                int port = Integer.parseInt(setting(config, "geoportal.geodata.port", "5432"));
                String database = setting(config, "geoportal.geodata.database", "geodata");
                String jdbc = setting(config, "geoportal.geodata.jdbc-url",
                        "jdbc:postgresql://" + host + ":" + port + "/" + database);
                // GP-986: the stores of the connections whose tables the layers serve.
                connections = connectionStores.stores(layers.stream()
                        .filter(l -> !l.retired() && l.definition().store() != null)
                        .map(l -> l.definition().store()).collect(java.util.stream.Collectors.toSet()));
                Desired desired = new Desired(
                        new StoreConnection(host, port, database, db.user, db.password),
                        // Rasters are coverages, published with their file (GP-309): not feature types.
                        layers.stream().filter(l -> !l.retired() && !l.raster()).map(PublishedLayer::definition).toList(),
                        styles.stream().filter(s -> !s.retired()).map(PublishedStyle::definition).toList(),
                        names(layers.stream().filter(PublishedLayer::retired).map(PublishedLayer::name).toList()),
                        names(styles.stream().filter(PublishedStyle::retired).map(PublishedStyle::name).toList()),
                        names(layers.stream().filter(l -> !l.retired() && l.raster()).map(PublishedLayer::name).toList()),
                        connections);
                Reconciler reconciler = new Reconciler(
                        new GeoServerClient(URI.create(url), gs.user, gs.password),
                        new JdbcGeodataSchemas(jdbc, db.user, db.password));
                result = apply ? reconciler.apply(space, desired) : reconciler.plan(space, desired);
            } catch (GeoServerException e) {
                if (apply) {
                    audit.record(EXTENSION, new AuditLog.Entry("geoserver.aligned", "workspace/" + space.workspace(),
                            AuditLog.Outcome.FAILED, Map.of("error", e.getMessage())));
                }
                throw e;
            } finally {
                connections.values().forEach(c -> {
                    if (c.password() != null) {
                        Arrays.fill(c.password(), '\0');
                    }
                });
            }
            if (apply) {
                forgetDeleted(result, layers, styles);
                audit.record(EXTENSION, new AuditLog.Entry("geoserver.aligned", "workspace/" + space.workspace(),
                        result.failed() ? AuditLog.Outcome.FAILED : AuditLog.Outcome.SUCCEEDED, details(result)));
                // The GeoFence rules follow the layers (GP-335): their observer never fails the alignment.
                aligned.fire(new Aligned(space, result));
            }
            return result;
        }
    }

    /**
     * Withdraws the publications of the organization, which turned the catalog off (GP-334): its
     * layers are disabled in GeoServer, nothing is deleted (see {@link Reconciler#withdraw}). Recorded
     * in the audit log of the organization as {@code geoserver.withdrawn}, with the layers.
     */
    public Alignment withdraw() {
        OrganizationSpace space = space();
        synchronized (locks.computeIfAbsent(space.organization(), k -> new Object())) {
            clients.remove(space.organization());
            Alignment result;
            try (Credential gs = credential(GEOSERVER_CREDENTIAL)) {
                GeoServerClient client = new GeoServerClient(URI.create(geoserverUrl()), gs.user, gs.password);
                result = new Reconciler(client, GeodataSchemas.NONE).withdraw(space);
            } catch (GeoServerException e) {
                audit.record(EXTENSION, new AuditLog.Entry("geoserver.withdrawn", "workspace/" + space.workspace(),
                        AuditLog.Outcome.FAILED, Map.of("error", e.getMessage())));
                throw e;
            }
            audit.record(EXTENSION, new AuditLog.Entry("geoserver.withdrawn", "workspace/" + space.workspace(),
                    result.failed() ? AuditLog.Outcome.FAILED : AuditLog.Outcome.SUCCEEDED, details(result)));
            return result;
        }
    }

    /** Retired rows whose object GeoServer no longer holds are done with. */
    private void forgetDeleted(Alignment result, List<PublishedLayer> layers, List<PublishedStyle> styles) {
        Set<String> failedLayers = new HashSet<>();
        Set<String> failedStyles = new HashSet<>();
        for (Alignment.Change c : result.changes()) {
            if (c.error() != null && !"layer".equals(c.kind()) && !"style".equals(c.kind())) {
                return; // the run stopped before the layers and styles: nothing was deleted
            }
            if (c.error() != null && "delete".equals(c.action())) {
                ("layer".equals(c.kind()) ? failedLayers : failedStyles).add(c.name());
            }
        }
        layers.stream()
                .filter(l -> l.retired() && !failedLayers.contains(l.name()))
                .forEach(publications::delete);
        styles.stream()
                .filter(s -> s.retired() && !failedStyles.contains(s.name()))
                .forEach(publications::delete);
    }

    private static Map<String, Object> details(Alignment result) {
        Map<String, Object> details = new LinkedHashMap<>();
        List<String> changes = new ArrayList<>();
        for (Alignment.Change c : result.changes()) {
            changes.add(c.action() + " " + c.kind() + " " + c.name() + (c.error() == null ? "" : " (failed)"));
        }
        List<String> drift = new ArrayList<>();
        result.drift().forEach(d -> drift.add(d.kind() + " " + d.name()));
        details.put("changes", changes.size() > 50 ? changes.subList(0, 50) : changes);
        details.put("drift", drift.size() > 50 ? drift.subList(0, 50) : drift);
        return details;
    }

    private static Set<String> names(List<String> names) {
        return new HashSet<>(names);
    }

    private static String geoserverUrl() {
        return setting(ConfigProvider.getConfig(), "geoportal.geoserver.url", "http://geoserver:8080/geoserver");
    }

    private static String setting(Config config, String name, String fallback) {
        return config.getOptionalValue(name, String.class).filter(s -> !s.isBlank()).orElse(fallback);
    }

    private Credential credential(String name) {
        try (SecretValue value = secrets.resolve(name).orElseThrow()) {
            char[] chars = value.chars();
            int colon = -1;
            for (int i = 0; i < chars.length; i++) {
                if (chars[i] == ':') {
                    colon = i;
                    break;
                }
            }
            if (colon <= 0) {
                Arrays.fill(chars, '\0');
                throw new MissingCredentialException("The credential " + name + " must be <user>:<password>.");
            }
            Credential c = new Credential(new String(chars, 0, colon), Arrays.copyOfRange(chars, colon + 1, chars.length));
            Arrays.fill(chars, '\0');
            return c;
        } catch (NoSuchElementException e) {
            throw new MissingCredentialException("Store the credential " + name + " (<user>:<password>) for the catalog in"
                    + " the credentials of the installation, or of the organization.");
        }
    }

    /** A user and password read from a credential, overwritten when closed. */
    private static final class Credential implements AutoCloseable {
        final String user;
        final char[] password;

        Credential(String user, char[] password) {
            this.user = user;
            this.password = password;
        }

        @Override
        public void close() {
            Arrays.fill(password, '\0');
        }

        @Override
        public String toString() {
            return "Credential[" + user + "]";
        }
    }

    /** The organization lacks a credential the alignment needs, or it is malformed. */
    public static final class MissingCredentialException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        MissingCredentialException(String message) {
            super(message);
        }
    }

    // Definitions ---------------------------------------------------------------------------------

    /** Publishes a layer, or changes its definition; GeoServer follows at the next alignment. */
    public LayerDefinition publish(LayerDefinition definition) {
        if (definition.style() != null
                && publications.style(definition.style()).filter(s -> !s.retired()).isEmpty()) {
            throw new IllegalArgumentException("The catalog has no style " + definition.style());
        }
        Instant now = Instant.now();
        publications.layer(definition.name()).ifPresentOrElse(l -> {
            l.define(definition, now);
            publications.update(l);
        }, () -> publications.insert(new PublishedLayer(organization.require(), definition, now)));
        return definition;
    }

    /** Withdraws a layer: the next alignment deletes it from GeoServer. */
    public boolean withdrawLayer(String name) {
        return publications.layer(name).filter(l -> !l.retired()).map(l -> {
            l.retire(Instant.now());
            publications.update(l);
            return true;
        }).orElse(false);
    }

    public StyleDefinition keep(StyleDefinition definition) {
        Instant now = Instant.now();
        publications.style(definition.name()).ifPresentOrElse(s -> {
            s.define(definition, now);
            publications.update(s);
        }, () -> publications.insert(new PublishedStyle(organization.require(), definition, now)));
        return definition;
    }

    /** Withdraws a style no published layer uses. */
    public boolean withdrawStyle(String name) {
        boolean used = publications.layers().stream()
                .anyMatch(l -> !l.retired() && name.equals(l.definition().style()));
        if (used) {
            throw new IllegalStateException("The style " + name + " is the style of a published layer");
        }
        return publications.style(name).filter(s -> !s.retired()).map(s -> {
            s.retire(Instant.now());
            publications.update(s);
            return true;
        }).orElse(false);
    }

    public List<LayerDefinition> layers() {
        return publications.layers().stream().filter(l -> !l.retired()).map(PublishedLayer::definition).toList();
    }

    public List<StyleDefinition> styles() {
        return publications.styles().stream().filter(s -> !s.retired()).map(PublishedStyle::definition).toList();
    }
    // Outside the alignment: GeoServer for one layer (GP-114, GP-118) -----------------------------

    /**
     * A client of GeoServer for the organization of the request, for the calls that are not an
     * alignment: the health of a layer, its cached tiles, its OGC services through the gate of the
     * catalog. Mosaikit audits every read of a credential and a map asks for tiles by the dozen, so
     * the client is kept a minute per organization.
     */
    public GeoServerClient client() {
        UUID org = organization.require();
        Instant now = Instant.now();
        Kept kept = clients.get(org);
        if (kept == null || kept.until().isBefore(now)) {
            try (Credential gs = credential(GEOSERVER_CREDENTIAL)) {
                kept = new Kept(new GeoServerClient(URI.create(geoserverUrl()), gs.user, gs.password), now.plus(KEEP));
            }
            clients.put(org, kept);
        }
        return kept.client();
    }

    /** The layer the catalog publishes with that name, if it is not withdrawn. */
    public Optional<PublishedLayer> layer(String name) {
        return publications.layer(name).filter(l -> !l.retired());
    }

    /** Every layer the catalog publishes, withdrawn ones excluded. */
    public List<PublishedLayer> published() {
        return publications.layers().stream().filter(l -> !l.retired()).toList();
    }

    public Optional<PublishedStyle> style(String name) {
        return publications.style(name).filter(s -> !s.retired());
    }

    public void update(PublishedLayer layer) {
        publications.update(layer);
    }

    /** Keeps a style translated from a style of Geoportal, with that style (GP-114). */
    public StyleDefinition keepTranslated(StyleDefinition definition, String source) {
        keep(definition);
        publications.style(definition.name()).ifPresent(s -> {
            s.translatedFrom(source);
            publications.update(s);
        });
        return definition;
    }

    /**
     * After the data or the style of a layer changed (GP-118): GeoServer recomputes its bounding
     * boxes from the table, and GeoWebCache forgets its cached tiles.
     */
    public void refresh(String name) {
        GeoServerClient c = client();
        String ws = space().workspace();
        c.recalculate(ws, OrganizationSpace.STORE, name);
        c.truncateTiles(ws, name, publications.layer(name).map(l -> l.definition().style()).orElse(null));
    }

    /** Whether GeoWebCache still has a tile layer for a layer of the workspace (the tests check orphans). */
    public boolean hasTileLayer(String name) {
        return client().hasTileLayer(space().workspace(), name);
    }

    /** The names of the layers of the workspace that the catalog does not know (drift). */
    public Set<String> unknownLayers() {
        Set<String> known = new HashSet<>();
        publications.layers().forEach(l -> known.add(l.name()));
        Set<String> unknown = new HashSet<>(client().featureTypes(space().workspace()));
        unknown.removeAll(known);
        return unknown;
    }

    /**
     * The health of a published layer (GP-114): GeoServer must list it in the capabilities of its
     * WMS and answer a WFS GetFeature from its table. The outcome is kept with the layer; a layer
     * that turns failed is written to the audit log.
     */
    public PublishedLayer check(PublishedLayer layer) {
        String ws = space().workspace();
        String name = layer.name();
        String status = "ok";
        String code = null;
        String reason = null;
        try {
            GeoServerClient c = client();
            HttpResponse<String> caps = c.ogcText(ws + "/" + name + "/wms",
                    "service=WMS&version=1.3.0&request=GetCapabilities");
            String body = caps.body() == null ? "" : caps.body();
            if (caps.statusCode() != 200 || !(body.contains("<Name>" + name + "</Name>")
                    || body.contains("<Name>" + ws + ":" + name + "</Name>"))) {
                status = "failed";
                code = "layer-missing";
                reason = Optional.ofNullable(GeoServerClient.reason(body)).orElse("HTTP " + caps.statusCode());
            } else if (layer.raster()) {
                // A coverage (GP-309): a small image of its WMS.
                HttpResponse<String> map = c.ogcText(ws + "/" + name + "/wms",
                        "service=WMS&version=1.1.1&request=GetMap&layers=" + name + "&styles=&srs=" + layer.definition().srs()
                                + "&bbox=" + c.coverageBounds(ws, name).map(b -> b[0] + "," + b[1] + "," + b[2] + "," + b[3])
                                        .orElse("-180,-90,180,90")
                                + "&width=8&height=8&format=image/png");
                String type = map.headers().firstValue("Content-Type").orElse("");
                if (map.statusCode() != 200 || !type.startsWith("image/")) {
                    status = "failed";
                    code = "data-error";
                    reason = Optional.ofNullable(GeoServerClient.reason(map.body())).orElse("HTTP " + map.statusCode());
                }
            } else {
                HttpResponse<String> wfs = c.ogcText(ws + "/" + name + "/wfs",
                        "service=WFS&version=2.0.0&request=GetFeature&count=1&outputFormat=application/json&typeNames="
                                + ws + ":" + name);
                String features = wfs.body() == null ? "" : wfs.body().strip();
                if (wfs.statusCode() != 200 || !features.startsWith("{")) {
                    status = "failed";
                    code = "data-error";
                    reason = Optional.ofNullable(GeoServerClient.reason(features)).orElse("HTTP " + wfs.statusCode());
                }
            }
        } catch (GeoServerException e) {
            status = "failed";
            code = "geoserver-unreachable";
            reason = "GeoServer does not answer.";
        } catch (MissingCredentialException e) {
            status = "failed";
            code = "credential-missing";
            reason = e.getMessage();
        }
        boolean turned = "failed".equals(status) && !"failed".equals(layer.health());
        layer.checked(status, code, reason, Instant.now());
        publications.update(layer);
        if (turned) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("code", code);
            details.put("reason", reason);
            audit.record(EXTENSION, new AuditLog.Entry("layer.health-failed", "layer/" + name,
                    AuditLog.Outcome.FAILED, details));
        }
        return layer;
    }

    /**
     * Adopts a layer of the workspace that the catalog does not know (GP-118): its definition is
     * taken from GeoServer as it is, so that the next alignments keep it. Only a layer on the store of
     * the organization can be adopted.
     */
    public LayerDefinition adopt(String name) {
        if (publications.layer(name).isPresent()) {
            throw new IllegalStateException("The catalog already knows the layer " + name);
        }
        String ws = space().workspace();
        GeoServerClient.FeatureType ft = client().featureType(ws, name)
                .orElseThrow(() -> new NoSuchElementException("GeoServer has no layer " + name + " in the workspace"));
        if (!ft.store().equals(OrganizationSpace.STORE)) {
            throw new IllegalStateException("The layer " + name + " is on the store " + ft.store()
                    + ", not on the store of the organization: it cannot be adopted.");
        }
        String title = ft.title().isBlank() ? name : ft.title();
        String description = ft.description().length() <= 1000 ? ft.description() : ft.description().substring(0, 1000);
        LayerDefinition definition = new LayerDefinition(name, ft.nativeName(), title, description, ft.srs(), null);
        publish(definition);
        audit.record(EXTENSION, new AuditLog.Entry("layer.adopted", "layer/" + name, AuditLog.Outcome.SUCCEEDED,
                Map.of("table", ft.nativeName(), "srs", ft.srs())));
        return definition;
    }

    /**
     * Deletes from GeoServer a layer or style that the catalog does not know (GP-118): drift is never
     * deleted by an alignment, only by a person who asks for it.
     *
     * @param kind {@code layer} or {@code style}
     */
    public void removeUnknown(String kind, String name) {
        String ws = space().workspace();
        GeoServerClient c = client();
        if ("layer".equals(kind)) {
            if (publications.layer(name).isPresent()) {
                throw new IllegalStateException("The layer " + name + " is published by the catalog: unpublish it instead.");
            }
            GeoServerClient.FeatureType ft = c.featureType(ws, name)
                    .orElseThrow(() -> new NoSuchElementException("GeoServer has no layer " + name + " in the workspace"));
            c.deleteFeatureType(ws, ft.store(), name);
        } else if ("style".equals(kind)) {
            if (publications.style(name).isPresent()) {
                throw new IllegalStateException("The style " + name + " is kept by the catalog.");
            }
            if (!c.styles(ws).contains(name)) {
                throw new NoSuchElementException("GeoServer has no style " + name + " in the workspace");
            }
            c.deleteStyle(ws, name);
        } else {
            throw new IllegalArgumentException("Only layers and styles unknown to the catalog are removed here.");
        }
        audit.record(EXTENSION, new AuditLog.Entry("geoserver.drift-removed", kind + "/" + name,
                AuditLog.Outcome.SUCCEEDED, Map.of("workspace", ws)));
    }
}
