// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.ext.catalog.geodata.CatalogExtension;
import dev.geoportal.ext.catalog.geodata.connection.ConnectionLayer;
import dev.geoportal.ext.catalog.geodata.connection.Source;
import dev.geoportal.ext.catalog.geodata.geoserver.Failure;
import dev.mosaikit.kernel.api.audit.AuditLog;
import dev.mosaikit.kernel.api.permission.Permissions;
import dev.mosaikit.kernel.api.sharing.Sharing;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * GP-986: the layers of tables of connections, under {@code /api/v1/ext/catalog-geodata/connection-layers}.
 *
 * <ul>
 *   <li>{@code POST} {@code {reference, layer?, title?}}: maps the table of a reference of the source picker
 *       (EXT-026) as a layer, with the permission {@code publish}; 201 {@code {dataset, layer}}, or the
 *       failure: 404 for a connection the person may not use, 409 with its code for a table that cannot be
 *       mapped (no geometry, a credential the geodata may not read, a source that refuses, ...).</li>
 *   <li>{@code GET {dataset}}: the reference and the source it was resolved to (address, schema, table,
 *       column, the name of the credential; never the credential), level {@code view}.</li>
 *   <li>{@code POST {dataset}/refresh}: resolves the reference again as the person, level {@code manage}.</li>
 * </ul>
 */
@Path("/api/v1/ext/catalog-geodata/connection-layers")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class ConnectionLayerResource {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A request to map a table. */
    public record MapRequest(JsonNode reference, String layer, String title) {}

    /** What a dataset maps. */
    public record View(UUID dataset, JsonNode reference, String connection, String host, int port, String database,
            String schema, String table, String geometryColumn, String credential, String resolvedAt) {}

    private final ConnectionLayers layers;
    private final Permissions permissions;
    private final Sharing sharing;
    private final UploadedDataRepository uploads;
    private final AuditLog audit;

    public ConnectionLayerResource(ConnectionLayers layers, Permissions permissions, Sharing sharing,
            UploadedDataRepository uploads, AuditLog audit) {
        this.layers = layers;
        this.permissions = permissions;
        this.sharing = sharing;
        this.uploads = uploads;
        this.audit = audit;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response map(MapRequest request, @Context HttpHeaders headers) {
        permissions.require(CatalogExtension.ID, PublicationResource.PUBLISH);
        if (request == null || request.reference() == null || request.reference().isNull()) {
            return Failure.correct(400, "invalid", "Choose a table of a connection.").response();
        }
        ConnectionLayers.Mapped mapped = layers.map(request.reference(), request.layer(), request.title(), headers);
        if (mapped.failure() != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("code", mapped.failure().code());
            details.put("connection", request.reference().path("connection").path("id").asText(""));
            details.put("part", request.reference().path("part").path("id").asText(""));
            audit.record(CatalogExtension.ID, new AuditLog.Entry("connection-layer.refused", "connection-layer",
                    AuditLog.Outcome.FAILED, details));
            return mapped.failure().response();
        }
        audit.record(CatalogExtension.ID, new AuditLog.Entry("connection-layer.mapped", "layer/" + mapped.layer(),
                AuditLog.Outcome.SUCCEEDED, Map.of("dataset", mapped.dataset().toString(),
                        "connection", request.reference().path("connection").path("id").asText(""),
                        "part", request.reference().path("part").path("id").asText(""))));
        return Response.status(Response.Status.CREATED)
                .entity(Map.of("dataset", mapped.dataset(), "layer", mapped.layer()))
                .build();
    }

    @GET
    @Path("{dataset}")
    @Transactional
    public View one(@PathParam("dataset") UUID dataset) {
        sharing.require(CatalogExtension.dataset(dataset), "view");
        ConnectionLayer layer = layers.of(dataset).orElseThrow(NotFoundException::new);
        Source s = layer.source();
        JsonNode reference;
        try {
            reference = JSON.readTree(layer.reference());
        } catch (IOException e) {
            reference = null;
        }
        return new View(dataset, reference, s.connection().toString(), s.host(), s.port(), s.database(), s.schema(),
                s.table(), s.geometryColumn(), s.credential(), layer.resolvedAt().toString());
    }

    @POST
    @Path("{dataset}/refresh")
    @Transactional
    public Response refresh(@PathParam("dataset") UUID dataset, @Context HttpHeaders headers) {
        sharing.require(CatalogExtension.dataset(dataset), "manage");
        UploadedData data = uploads.byId(dataset).orElseThrow(NotFoundException::new);
        return layers.refresh(data, headers).map(Failure::response).orElseGet(() -> Response.ok(one(dataset)).build());
    }
}
