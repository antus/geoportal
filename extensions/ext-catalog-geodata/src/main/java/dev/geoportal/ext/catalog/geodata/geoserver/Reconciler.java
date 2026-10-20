// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.geoserver;

import dev.geoportal.ext.catalog.geodata.geoserver.Alignment.Change;
import dev.geoportal.ext.catalog.geodata.geoserver.Alignment.Drift;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerClient.FeatureType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Brings the GeoServer space of an organization to what Geoportal holds (ADR-0014): the schema of the
 * organization in the geodata database, its workspace with its namespace, its PostGIS store, the
 * styles and the layers Geoportal publishes. Geoportal is the only author: whatever was changed by
 * hand in an object Geoportal publishes is brought back; whatever Geoportal withdrew is deleted;
 * whatever else the workspace holds is reported as drift and left alone.
 *
 * <p>Idempotent: a second run right after the first finds nothing to change. Every step reads first and
 * writes only what differs, so a run interrupted half-way converges at the next one, and nothing is
 * created twice.
 *
 * <p>A change that GeoServer refuses (a table that does not exist, an invalid SLD) is reported with its
 * error and the run goes on with the others, so that one broken layer does not hold back the rest.
 * The workspace and the store come first: when they fail nothing else is tried.
 */
public final class Reconciler {

    /** The prefix of the stores of connections (GP-986), which the alignment owns as it owns its store. */
    public static final String CONNECTION_STORE = "conn_";

    private final GeoServerClient geoserver;
    private final GeodataSchemas schemas;

    public Reconciler(GeoServerClient geoserver, GeodataSchemas schemas) {
        this.geoserver = Objects.requireNonNull(geoserver, "geoserver");
        this.schemas = Objects.requireNonNull(schemas, "schemas");
    }

    /** What an alignment would change, without changing anything. */
    public Alignment plan(OrganizationSpace space, Desired desired) {
        return run(space, desired, false);
    }

    /** Aligns GeoServer, and tells what changed and what drift remains. */
    public Alignment apply(OrganizationSpace space, Desired desired) {
        return run(space, desired, true);
    }

    /**
     * Withdraws the publications of an organization that turned the catalog off (GP-334): every layer
     * of its workspace is disabled and no longer advertised, so GeoServer serves none of them. Nothing
     * is deleted: the workspace, the store, the styles, the definitions of the catalog and the tables
     * of the schema stay, and the next alignment, when the catalog is turned on again, enables the
     * layers of the catalog again. A layer GeoServer refuses to change is reported with its error.
     */
    public Alignment withdraw(OrganizationSpace space) {
        Run run = new Run(space, true);
        String ws = space.workspace();
        if (!geoserver.hasWorkspace(ws)) {
            return run.done(List.of());
        }
        for (String name : sorted(geoserver.featureTypes(ws))) {
            Optional<FeatureType> held = geoserver.featureType(ws, name);
            if (held.isEmpty() || (!held.get().enabled() && !held.get().advertised())) {
                continue;
            }
            FeatureType h = held.get();
            FeatureType off = new FeatureType(h.name(), h.nativeName(), h.store(), h.title(), h.description(), h.srs(),
                    h.projectionPolicy().isEmpty() ? "FORCE_DECLARED" : h.projectionPolicy(), false, false);
            run.change("layer", name, "disable", "catalog turned off",
                    () -> geoserver.updateFeatureType(ws, h.store(), off));
        }
        return run.done(List.of());
    }

    private Alignment run(OrganizationSpace space, Desired desired, boolean apply) {
        Run run = new Run(space, apply);
        String ws = space.workspace();

        // The schema of the organization in the geodata database.
        if (!schemas.exists(space.schema())) {
            run.change("schema", space.schema(), "create", "missing", () -> schemas.create(space.schema()));
        }

        // The workspace, with the namespace that names its features.
        boolean workspace = geoserver.hasWorkspace(ws);
        if (!workspace) {
            if (!run.change("workspace", ws, "create", "missing",
                    () -> geoserver.createWorkspace(ws, space.namespaceUri()))) {
                return run.done(List.of());
            }
        } else {
            String uri = geoserver.namespaceUri(ws).orElse("");
            if (!uri.equals(space.namespaceUri())) {
                run.change("workspace", ws, "update", "namespace " + uri,
                        () -> geoserver.updateNamespace(ws, space.namespaceUri()));
            }
        }
        if (!apply && !workspace) {
            // Nothing exists yet: everything is to be created.
            run.planned("store", OrganizationSpace.STORE, "create", "missing");
            desired.styles().forEach(s -> run.planned("style", s.name(), "create", "missing"));
            desired.layers().forEach(l -> run.planned("layer", l.name(), "create", "missing"));
            return run.done(List.of());
        }

        // The PostGIS store on the schema of the organization.
        Optional<Map<String, String>> store = geoserver.dataStoreParameters(ws, OrganizationSpace.STORE);
        Map<String, String> wanted = desired.store().comparable(space);
        if (store.isEmpty()) {
            if (!run.change("store", OrganizationSpace.STORE, "create", "missing", () -> geoserver.putDataStore(
                    ws, OrganizationSpace.STORE, desired.store().parameters(space), false))) {
                return run.done(List.of());
            }
        } else {
            String differs = differences(wanted, store.get());
            if (!differs.isEmpty()) {
                run.change("store", OrganizationSpace.STORE, "update", differs, () -> geoserver.putDataStore(
                        ws, OrganizationSpace.STORE, desired.store().parameters(space), true));
            }
        }

        // GP-986: the stores of the connections whose tables the layers serve, on the sources themselves.
        Set<String> stores = new HashSet<>(geoserver.dataStores(ws));
        Set<String> readyStores = new HashSet<>();
        for (String name : sorted(desired.connections().keySet())) {
            ConnectionStore connection = desired.connections().get(name);
            if (!stores.contains(name)) {
                if (connection.writable() && run.change("store", name, "create", "missing",
                        () -> geoserver.putDataStore(ws, name, connection.parameters(), false))) {
                    readyStores.add(name);
                }
                continue;
            }
            readyStores.add(name);
            Optional<Map<String, String>> heldConnection = geoserver.dataStoreParameters(ws, name);
            String differs = heldConnection.map(h -> differences(connection.comparable(), h)).orElse("");
            if (!differs.isEmpty() && connection.writable()) {
                run.change("store", name, "update", differs,
                        () -> geoserver.putDataStore(ws, name, connection.parameters(), true));
            }
        }

        // Styles before layers, which name them.
        Set<String> styles = new HashSet<>(geoserver.styles(ws));
        for (StyleDefinition style : desired.styles()) {
            if (!styles.contains(style.name())) {
                run.change("style", style.name(), "create", "missing",
                        () -> geoserver.createStyle(ws, style.name(), style.sld()));
            } else {
                String held = geoserver.styleBody(ws, style.name()).orElse("");
                if (!Sld.same(held, style.sld())) {
                    run.change("style", style.name(), "update", "SLD differs",
                            () -> geoserver.updateStyle(ws, style.name(), style.sld()));
                }
            }
        }

        // Layers.
        Set<String> types = new HashSet<>(geoserver.featureTypes(ws));
        for (LayerDefinition layer : desired.layers()) {
            String into = layer.storeOrDefault();
            if (layer.store() != null && !readyStores.contains(into)) {
                // The store of its connection could not be made (its credential cannot be read now).
                run.planned("layer", layer.name(), "create", "store " + into + " missing");
                continue;
            }
            FeatureType target = new FeatureType(layer.name(), layer.table(), into, layer.title(),
                    layer.description(), layer.srs(), "FORCE_DECLARED", true, true);
            Optional<FeatureType> held = types.contains(layer.name())
                    ? geoserver.featureType(ws, layer.name())
                    : Optional.empty();
            boolean exists;
            if (held.isEmpty()) {
                exists = run.change("layer", layer.name(), "create", "missing",
                        () -> geoserver.createFeatureType(ws, into, target));
            } else if (!held.get().store().equals(into)) {
                // Moved to another store by hand, or to the store of another source: back where it belongs.
                exists = run.change("layer", layer.name(), "update", "store " + held.get().store(), () -> {
                    geoserver.deleteFeatureType(ws, held.get().store(), layer.name());
                    geoserver.createFeatureType(ws, into, target);
                });
            } else {
                String differs = differences(target, held.get());
                exists = differs.isEmpty() || run.change("layer", layer.name(), "update", differs,
                        () -> geoserver.updateFeatureType(ws, into, target));
            }
            if (layer.style() != null && (exists || !apply)) {
                String style = ws + ":" + layer.style();
                String current = held.isEmpty() && !apply ? "" : geoserver.layerStyle(ws, layer.name()).orElse("");
                if (!style.equals(current)) {
                    run.change("layer", layer.name(), "update", "default style " + (current.isEmpty() ? "-" : current),
                            () -> geoserver.setLayerStyle(ws, layer.name(), layer.style()));
                }
            }
        }

        // What Geoportal withdrew: layers first, then the styles they may have used.
        for (String name : sorted(desired.retiredLayers())) {
            if (types.contains(name)) {
                Optional<FeatureType> held = geoserver.featureType(ws, name);
                String from = held.map(FeatureType::store).orElse(OrganizationSpace.STORE);
                run.change("layer", name, "delete", "withdrawn", () -> geoserver.deleteFeatureType(ws, from, name));
            }
        }
        for (String name : sorted(desired.retiredStyles())) {
            if (styles.contains(name)) {
                run.change("style", name, "delete", "withdrawn", () -> geoserver.deleteStyle(ws, name));
            }
        }

        // GP-986: the store of a connection that no layer uses any more goes (its layers went above).
        for (String name : sorted(stores)) {
            if (name.startsWith(CONNECTION_STORE) && !desired.connections().containsKey(name)) {
                run.change("store", name, "delete", "no layer", () -> geoserver.deleteDataStore(ws, name));
            }
        }

        // Drift: what the workspace holds that Geoportal does not know.
        List<Drift> drift = new ArrayList<>();
        Set<String> knownLayers = new HashSet<>(desired.retiredLayers());
        desired.layers().forEach(l -> knownLayers.add(l.name()));
        Set<String> knownStyles = new HashSet<>(desired.retiredStyles());
        desired.styles().forEach(s -> knownStyles.add(s.name()));
        for (String s : sorted(geoserver.dataStores(ws))) {
            if (!s.equals(OrganizationSpace.STORE) && !desired.connections().containsKey(s)
                    && !s.startsWith(CONNECTION_STORE)) {
                drift.add(new Drift("store", s));
            }
        }
        for (String s : sorted(geoserver.coverageStores(ws))) {
            if (!desired.rasters().contains(s)) {
                drift.add(new Drift("coverage-store", s));
            }
        }
        for (String t : sorted(geoserver.featureTypes(ws))) {
            if (!knownLayers.contains(t)) {
                drift.add(new Drift("layer", t));
            }
        }
        for (String s : sorted(geoserver.styles(ws))) {
            if (!knownStyles.contains(s)) {
                drift.add(new Drift("style", s));
            }
        }
        return run.done(drift);
    }

    private static String differences(Map<String, String> wanted, Map<String, String> held) {
        StringJoiner differs = new StringJoiner(", ");
        wanted.forEach((k, v) -> {
            if (!v.equals(held.get(k))) {
                differs.add(k);
            }
        });
        return differs.toString();
    }

    private static String differences(FeatureType wanted, FeatureType held) {
        StringJoiner differs = new StringJoiner(", ");
        if (!wanted.nativeName().equals(held.nativeName())) {
            differs.add("table");
        }
        if (!wanted.title().equals(held.title())) {
            differs.add("title");
        }
        if (!wanted.description().equals(held.description())) {
            differs.add("description");
        }
        if (!wanted.srs().equals(held.srs())) {
            differs.add("srs");
        }
        if (!wanted.projectionPolicy().equals(held.projectionPolicy())) {
            differs.add("projection policy");
        }
        if (!held.enabled()) {
            differs.add("enabled");
        }
        if (!held.advertised()) {
            differs.add("advertised");
        }
        return differs.toString();
    }

    private static List<String> sorted(Iterable<String> names) {
        Set<String> s = new LinkedHashSet<>();
        names.forEach(s::add);
        return s.stream().sorted().toList();
    }

    /** One alignment: the changes in the order they were found, made at once when applying. */
    private static final class Run {

        private final OrganizationSpace space;
        private final boolean apply;
        private final List<Change> changes = new ArrayList<>();

        Run(OrganizationSpace space, boolean apply) {
            this.space = space;
            this.apply = apply;
        }

        void planned(String kind, String name, String action, String reason) {
            changes.add(new Change(kind, name, action, reason, null));
        }

        /** Records a change and, when applying, makes it; whether it succeeded (or was only planned). */
        boolean change(String kind, String name, String action, String reason, Runnable step) {
            if (!apply) {
                planned(kind, name, action, reason);
                return true;
            }
            try {
                step.run();
                changes.add(new Change(kind, name, action, reason, null));
                return true;
            } catch (RuntimeException e) {
                changes.add(new Change(kind, name, action, reason, e.getMessage(),
                        e instanceof GeoServerException g ? g.reason() : null));
                return false;
            }
        }

        Alignment done(List<Drift> drift) {
            return new Alignment(space.workspace(), apply, changes, drift);
        }
    }
}
