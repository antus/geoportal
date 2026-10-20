// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.upload;

import com.fasterxml.jackson.databind.JsonNode;
import dev.geoportal.ext.catalog.geodata.Credentials;
import dev.geoportal.ext.catalog.geodata.connection.ConnectionLayer;
import dev.geoportal.ext.catalog.geodata.connection.ConnectionLayerRepository;
import dev.geoportal.ext.catalog.geodata.connection.ConnectionResolver;
import dev.geoportal.ext.catalog.geodata.connection.Source;
import dev.geoportal.ext.catalog.geodata.connection.SourceProbe;
import dev.geoportal.ext.catalog.geodata.geoserver.Failure;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerGovernance;
import dev.geoportal.ext.catalog.geodata.geoserver.LayerDefinition;
import dev.geoportal.ext.catalog.geodata.style.StyleDocument;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import dev.mosaikit.kernel.api.secrets.SecretValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.HttpHeaders;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * GP-986, ADR-0030: a table or view of a PostgreSQL connection of ext-catalog-connection becomes a layer,
 * without copying it. The person chooses it in the source picker of the connections (EXT-026), for the use
 * "map" the geodata contribute (EXT-019); the reference is resolved as that person (they must be allowed to
 * use the connection), the table is asked about on its source, and the layer is published by GeoServer
 * from a store of its own on that source, with the password of the credential of the connection, which
 * only the geodata read, from the secrets service, and only GeoServer receives. The dataset of the layer
 * keeps the reference, so that the layer follows the connection when it is refreshed.
 */
@ApplicationScoped
public class ConnectionLayers {

    /** The connector of PostgreSQL of mosaikit-ext (EXT-040), the only one whose tables GeoServer reads now. */
    public static final String POSTGRES = "io.github.mosaikit.ext.catalog.connection.postgres";

    /** The kinds of pieces that the use "map" takes. */
    static final Set<String> KINDS = Set.of("table", "view");

    private final ConnectionResolver resolver;
    private final ConnectionLayerRepository connections;
    private final UploadedDatasets datasets;
    private final UploadedDataRepository uploads;
    private final DatasetPublishing publishing;
    private final GeoServerGovernance geoserver;
    private final Credentials credentials;
    private final CurrentOrganization organization;

    public ConnectionLayers(ConnectionResolver resolver, ConnectionLayerRepository connections, UploadedDatasets datasets,
            UploadedDataRepository uploads, DatasetPublishing publishing, GeoServerGovernance geoserver,
            Credentials credentials, CurrentOrganization organization) {
        this.resolver = resolver;
        this.connections = connections;
        this.datasets = datasets;
        this.uploads = uploads;
        this.publishing = publishing;
        this.geoserver = geoserver;
        this.credentials = credentials;
        this.organization = organization;
    }

    /** What a choice of the picker made: the dataset and its layer, or why not. */
    public record Mapped(UUID dataset, String layer, Failure failure) {}

    /**
     * Maps the table of a reference as a layer named {@code layer} (its technical name when blank). The
     * caller checked the permission to publish.
     */
    public Mapped map(JsonNode reference, String layer, String title, HttpHeaders headers) {
        Source source;
        try {
            source = resolve(reference, headers);
        } catch (Refused e) {
            return new Mapped(null, null, e.failure);
        }
        SourceProbe.Table table;
        char[] password;
        try {
            password = password(source.credential());
        } catch (Refused e) {
            return new Mapped(null, null, e.failure);
        }
        try {
            table = SourceProbe.probe(source, password);
        } catch (SourceProbe.Refused e) {
            return new Mapped(null, null, Failure.correct(409, "source-refused", e.getMessage()));
        } finally {
            Arrays.fill(password, '\0');
        }
        String name = title == null || title.isBlank() ? source.table() : title.strip();
        UploadedData data = datasets.fromConnection(source.table(), name, table.geometryType(), table.rows(),
                "EPSG:" + table.srid(), table.nativeExtent(), table.wgs84());
        connections.insert(new ConnectionLayer(data.id(), organization.require(), reference.toString(), source, Instant.now()));
        String chosen = layer == null || layer.isBlank() ? data.technicalName() : layer.strip();
        DatasetPublishing.Outcome outcome = publishing.publish(data, chosen, StyleDocument.defaults());
        if (outcome.failure() != null) {
            // Nothing half-made stays: the dataset goes, the source was never touched.
            connections.byDataset(data.id()).ifPresent(connections::delete);
            datasets.delete(data);
            return new Mapped(null, null, outcome.failure());
        }
        return new Mapped(data.id(), outcome.layer().name(), null);
    }

    /**
     * Resolves the reference of a dataset again, as the person of the request: the layer follows a new
     * address, schema or user of its connection; the GeoServer store follows at the alignment.
     */
    public Optional<Failure> refresh(UploadedData data, HttpHeaders headers) {
        Optional<ConnectionLayer> held = connections.byDataset(data.id());
        if (held.isEmpty()) {
            return Optional.of(Failure.correct(404, "not-found", "The dataset is not a table of a connection."));
        }
        Source source;
        try {
            source = resolve(new com.fasterxml.jackson.databind.ObjectMapper().readTree(held.get().reference()), headers);
        } catch (Refused e) {
            return Optional.of(e.failure);
        } catch (java.io.IOException e) {
            return Optional.of(Failure.correct(409, "invalid", "The reference of the dataset cannot be read."));
        }
        held.get().follow(source, Instant.now());
        connections.update(held.get());
        try {
            geoserver.align();
        } catch (RuntimeException e) {
            return Optional.of(Failure.of(e, "refreshing " + data.technicalName()));
        }
        return Optional.empty();
    }

    /** The connection layer of a dataset, if it is one. */
    public Optional<ConnectionLayer> of(UUID dataset) {
        return connections.byDataset(dataset);
    }

    /** What the resolution of a reference says of its table, checked for the use "map". */
    private Source resolve(JsonNode reference, HttpHeaders headers) {
        JsonNode resolved;
        try {
            resolved = resolver.resolve(reference, headers);
        } catch (IllegalArgumentException e) {
            throw new Refused(Failure.correct(400, "invalid", e.getMessage()));
        } catch (IllegalStateException e) {
            throw new Refused(Failure.correct(409, "connections-unavailable", e.getMessage()));
        }
        String status = resolved.path("status").asText("");
        switch (status) {
            case "available" -> {
                // below
            }
            case "forbidden", "deleted" -> throw new Refused(Failure.correct(404, "not-found",
                    "The connection does not exist, or you may not use it."));
            case "connector-absent" -> throw new Refused(Failure.correct(409, "connector-absent",
                    "The connector of the connection is not active in this organization."));
            case "part-missing" -> throw new Refused(Failure.correct(409, "part-missing",
                    "The connection no longer holds this table."));
            default -> throw new Refused(Failure.correct(409, status.isEmpty() ? "unresolved" : status,
                    "The source of the connection cannot be read now" + detail(resolved)));
        }
        JsonNode connection = resolved.path("connection");
        JsonNode part = resolved.path("part");
        if (!POSTGRES.equals(connection.path("connector").asText())) {
            throw new Refused(Failure.correct(409, "not-postgresql",
                    "Only the tables of PostgreSQL connections are mapped as layers for now."));
        }
        if (!KINDS.contains(part.path("kind").asText())) {
            throw new Refused(Failure.correct(409, "not-a-table", "Only a table or a view is mapped as a layer."));
        }
        JsonNode geometry = part.path("facts").path("geometry");
        String column = geometry.isArray() && !geometry.isEmpty()
                ? (geometry.get(0).isTextual() ? geometry.get(0).asText() : geometry.get(0).path("name").asText())
                : null;
        if (column == null || column.isBlank()) {
            throw new Refused(Failure.correct(409, "no-geometry", "The table has no geometry column to map."));
        }
        List<String> names;
        try {
            names = SourceProbe.names(part.path("id").asText());
        } catch (IllegalArgumentException e) {
            throw new Refused(Failure.correct(409, "not-a-table", e.getMessage()));
        }
        if (names.size() != 2) {
            throw new Refused(Failure.correct(409, "not-a-table", "Only a table of a schema is mapped as a layer."));
        }
        JsonNode settings = connection.path("settings");
        String credential = connection.path("credential").asText("");
        if (credential.isBlank()) {
            throw new Refused(Failure.correct(409, "credential-missing", "The connection names no credential."));
        }
        String host = settings.path("host").asText("");
        String database = settings.path("database").asText("");
        String user = settings.path("user").asText("");
        if (host.isBlank() || database.isBlank() || user.isBlank()) {
            throw new Refused(Failure.correct(409, "invalid", "The connection has no host, database or user."));
        }
        UUID id = UUID.fromString(connection.path("id").asText());
        String sslMode = settings.path("sslmode").asText("");
        return new Source(id, part.path("id").asText(), host, settings.path("port").asInt(5432), database, names.get(0),
                names.get(1), column, user, sslMode.isBlank() ? null : sslMode, credential,
                Source.storeOf(id, names.get(0)));
    }

    /** The password of the credential of a connection, which the geodata must be allowed to read (MK-062). */
    private char[] password(String credential) {
        Optional<SecretValue> value;
        try {
            value = credentials.resolve(credential);
        } catch (RuntimeException e) {
            throw new Refused(Failure.correct(409, "credential-unavailable", "The credential " + credential
                    + " of the connection cannot be read now."));
        }
        if (value.isEmpty()) {
            throw new Refused(Failure.correct(409, "credential-not-readable", "The credential " + credential + " of the"
                    + " connection is meant for another extension: an administrator means it for every extension, or"
                    + " for the geodata, so that GeoServer reads the tables of the connection."));
        }
        try (SecretValue secret = value.get()) {
            return secret.chars();
        }
    }

    private static String detail(JsonNode resolved) {
        String detail = resolved.path("detail").asText("");
        return detail.isBlank() ? "." : ": " + detail;
    }

    /** A refusal with its failure, for the person. */
    private static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Failure failure;

        Refused(Failure failure) {
            super(failure.toString());
            this.failure = failure;
        }
    }

    /** The name a layer of a table would take, for the dialog. */
    static String layerName(String table) {
        String slug = UploadedDatasets.slug(table);
        return LayerDefinition.NAME.matcher(slug).matches() ? slug : "t_" + slug;
    }
}
