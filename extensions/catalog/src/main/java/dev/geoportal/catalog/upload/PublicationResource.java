// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.upload;

import static dev.geoportal.catalog.upload.UploadedDatasets.EXTENSION;
import static dev.geoportal.catalog.upload.UploadedDatasets.TYPE;
import static dev.geoportal.catalog.upload.UploadedDatasets.ref;

import dev.geoportal.catalog.access.AccessRules;
import dev.geoportal.catalog.geoserver.Failure;
import dev.geoportal.catalog.geoserver.GeoServerException;
import dev.geoportal.catalog.geoserver.GeoServerGovernance;
import dev.geoportal.catalog.geoserver.GeoportalStyle;
import dev.geoportal.catalog.geoserver.OgcGate;
import dev.geoportal.catalog.geoserver.PublishedLayer;
import dev.geoportal.catalog.gateway.DataRequests;
import dev.geoportal.catalog.gateway.Gateway;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import dev.mosaikit.kernel.api.permission.Permissions;
import dev.mosaikit.kernel.api.quota.Quotas;
import dev.mosaikit.kernel.api.resource.ResourceRef;
import dev.mosaikit.kernel.api.sharing.Sharing;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The services the catalog publishes on GeoServer from uploaded datasets (GP-114, GP-118), under
 * /api/v1/ext/catalog/publications: the list with the health of each, the translation of a style,
 * publish, republish, repoint, unpublish, the health check, and the OGC services of each layer through
 * the gate of the catalog (never the address of GeoServer).
 *
 * <p>Publishing, republishing, repointing and unpublishing need the permission {@code publish} and the
 * level {@code manage} on the datasets involved; seeing a publication, checking it and using its
 * services need {@code view} on its dataset. A failure answers a problem detail with {@code code},
 * {@code action} ({@code retry} or {@code correct}), {@code cause} (what GeoServer said) and {@code
 * reference} (the same failure in the log), GP-115.
 */
@Path("/api/v1/ext/catalog/publications")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class PublicationResource {

    static final String PUBLISH = "publish";
    static final String API = "/api/v1/ext/catalog/publications/";

    private final DatasetPublishing publishing;
    private final GeoServerGovernance geoserver;
    private final UploadedDataRepository uploads;
    private final Sharing sharing;
    private final Permissions permissions;
    private final AccessRules access;
    private final CurrentOrganization organization;
    private final Quotas quotas;
    private final io.quarkus.security.identity.SecurityIdentity identity;
    @jakarta.inject.Inject
    RasterPublishing rasters;

    public PublicationResource(DatasetPublishing publishing, GeoServerGovernance geoserver,
            UploadedDataRepository uploads, Sharing sharing, Permissions permissions, AccessRules access,
            CurrentOrganization organization, Quotas quotas, io.quarkus.security.identity.SecurityIdentity identity) {
        this.identity = identity;
        this.publishing = publishing;
        this.geoserver = geoserver;
        this.uploads = uploads;
        this.sharing = sharing;
        this.permissions = permissions;
        this.access = access;
        this.organization = organization;
        this.quotas = quotas;
    }

    /** The health of a publication: ok or failed, why, and when it was checked. */
    public record Health(String status, String code, String reason, Instant checkedAt) {}

    /**
     * A published layer as a service of the catalog.
     *
     * @param services the OGC services of the layer on the origin of Geoportal: wms, wfs, wmts
     * @param gateway whether they are addresses of the data gateway (GP-336), which desktop clients open
     *     with {@code authkey=<personal key>}
     * @param level the level of the person on the dataset
     * @param canPublish whether the person may republish, repoint and unpublish it
     */
    public record PublicationView(
            String name,
            String title,
            UUID dataset,
            String datasetTitle,
            String datasetName,
            String geometryType,
            String style,
            GeoportalStyle styleSource,
            Health health,
            Instant publishedAt,
            Map<String, String> services,
            boolean gateway,
            String level,
            boolean canPublish) {}

    /** What the publishing dialog sends: the dataset, the name of the layer, the style of Geoportal. */
    public record PublishBody(UUID dataset, String layer, GeoportalStyle style) {}

    /** A new style for a published layer, or none to publish its current data again. */
    public record RepublishBody(GeoportalStyle style) {}

    public record RepointBody(UUID dataset) {}

    /** The publications whose dataset the person sees, by name. */
    @GET
    @Transactional
    public List<PublicationView> list() {
        // A dataset shared or unshared in the dialog of Mosaikit becomes GeoFence rules here (GP-335).
        access.ensureCurrent();
        Set<UUID> visible = sharing.visible(EXTENSION, TYPE, "view").stream().map(UUID::fromString)
                .collect(Collectors.toSet());
        List<PublishedLayer> layers = geoserver.published().stream()
                .filter(l -> l.datasetId() != null && visible.contains(l.datasetId()))
                .toList();
        if (layers.isEmpty()) {
            return List.of();
        }
        Map<UUID, UploadedData> data = uploads.byIds(layers.stream().map(PublishedLayer::datasetId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(UploadedData::id, d -> d));
        boolean canPublish = permissions.has(EXTENSION, PUBLISH);
        return layers.stream().filter(l -> data.containsKey(l.datasetId()))
                .map(l -> view(l, data.get(l.datasetId()), canPublish)).toList();
    }

    @GET
    @Path("{name}")
    @Transactional
    public Response one(@PathParam("name") String name) {
        PublishedLayer layer = visible(name);
        UploadedData data = uploads.byId(layer.datasetId()).orElseThrow(jakarta.ws.rs.NotFoundException::new);
        return Response.ok(view(layer, data, permissions.has(EXTENSION, PUBLISH))).build();
    }

    /**
     * The SLD GeoServer would receive for a style of Geoportal on a dataset, for the publishing dialog
     * to show next to the style before confirming (GP-114).
     */
    @POST
    @Path("style-translation")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response translate(PublishBody body) {
        if (body == null || body.dataset() == null) {
            return Failure.correct(400, "invalid", "Name the dataset.").response();
        }
        sharing.require(ref(body.dataset()), "view");
        UploadedData data = find(body.dataset());
        GeoportalStyle style = body.style() == null ? GeoportalStyle.DEFAULT : body.style();
        String layer = body.layer() == null || body.layer().isBlank() ? data.technicalName() : body.layer();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("geometryType", data.geometryType());
        answer.put("style", style);
        // A raster (GP-309) is drawn with the default raster style of GeoServer.
        answer.put("sld", RasterPublishing.raster(data) ? null : publishing.translate(data, layer, style));
        return Response.ok(answer).build();
    }

    /** Publishes a loaded dataset (permission publish, level manage): 201 with the publication. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response publish(PublishBody body) {
        permissions.require(EXTENSION, PUBLISH);
        if (body == null || body.dataset() == null) {
            return Failure.correct(400, "invalid", "Name the dataset to publish.").response();
        }
        sharing.require(ref(body.dataset()), "manage");
        UploadedData data = find(body.dataset());
        GeoportalStyle style = body.style() == null ? GeoportalStyle.DEFAULT : body.style();
        String layer = body.layer() == null || body.layer().isBlank() ? data.technicalName() : body.layer().strip();
        if (RasterPublishing.raster(data)) {
            return answer(() -> rasters.publish(data, layer), data, Response.Status.CREATED);
        }
        return answer(() -> publishing.publish(data, layer, style), data, Response.Status.CREATED);
    }

    /** Publishes the layer again with the current data and, when given, a new style. */
    @PUT
    @Path("{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response republish(@PathParam("name") String name, RepublishBody body) {
        permissions.require(EXTENSION, PUBLISH);
        PublishedLayer layer = managed(name);
        UploadedData data = find(layer.datasetId());
        if (layer.raster()) {
            return answer(() -> rasters.publish(data, layer.name()), data, Response.Status.OK);
        }
        return answer(() -> publishing.republish(data, layer, body == null ? null : body.style()), data, Response.Status.OK);
    }

    /** Points the layer at another dataset (level manage on both). */
    @POST
    @Path("{name}/repoint")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response repoint(@PathParam("name") String name, RepointBody body) {
        permissions.require(EXTENSION, PUBLISH);
        PublishedLayer layer = managed(name);
        if (body == null || body.dataset() == null) {
            return Failure.correct(400, "invalid", "Name the dataset the layer serves from now.").response();
        }
        sharing.require(ref(body.dataset()), "manage");
        UploadedData from = uploads.byId(layer.datasetId()).orElse(null);
        UploadedData to = find(body.dataset());
        if (layer.raster() || RasterPublishing.raster(to)) {
            return Failure.correct(409, "not-vector", "Raster layers are not repointed: publish the image as a layer"
                    + " of its own.").response();
        }
        return answer(() -> publishing.repoint(layer, from, to), to, Response.Status.OK);
    }

    /** Withdraws the layer and its style from GeoServer; the dataset stays loaded. */
    @DELETE
    @Path("{name}")
    @Transactional
    public Response unpublish(@PathParam("name") String name) {
        permissions.require(EXTENSION, PUBLISH);
        PublishedLayer layer = managed(name);
        UploadedData data = uploads.byId(layer.datasetId()).orElse(null);
        DatasetPublishing.Outcome outcome;
        try {
            outcome = layer.raster() ? rasters.unpublish(layer, data) : publishing.unpublish(layer, data);
        } catch (GeoServerException | GeoServerGovernance.MissingCredentialException e) {
            return Failure.of(e, "unpublishing " + name).response();
        }
        return outcome.failure() != null ? outcome.failure().response() : Response.noContent().build();
    }

    /** Asks GeoServer whether it answers for the layer now (level view). */
    @POST
    @Path("{name}/health")
    @Transactional
    public Response check(@PathParam("name") String name) {
        PublishedLayer layer = visible(name);
        UploadedData data = find(layer.datasetId());
        geoserver.check(layer);
        return Response.ok(view(layer, data, permissions.has(EXTENSION, PUBLISH))).build();
    }

    // The OGC services of a layer, through the gate of the catalog --------------------------------

    @GET
    @Path("{name}/ogc/{path: .+}")
    @Transactional
    public Response ogc(@PathParam("name") String name, @Encoded @PathParam("path") String path,
            @Context UriInfo uri, @Context HttpHeaders headers) {
        return gate("GET", name, path, uri, headers);
    }

    @HEAD
    @Path("{name}/ogc/{path: .+}")
    @Transactional
    public Response ogcHead(@PathParam("name") String name, @Encoded @PathParam("path") String path,
            @Context UriInfo uri, @Context HttpHeaders headers) {
        return gate("HEAD", name, path, uri, headers);
    }

    /**
     * Forwards a read request to the service of one layer of GeoServer, after the level of the person
     * on its dataset and the checks of {@link LayerGate}; the answer names the gate, never GeoServer.
     * With the data gateway (GP-336) the person is sent there instead, where GeoServer answers for them
     * by name: the addresses people copied keep working.
     */
    private Response gate(String method, String name, String path, UriInfo uri, HttpHeaders headers) {
        PublishedLayer layer = visible(name);
        String rawQuery = uri.getRequestUri().getRawQuery();
        if (Gateway.enabled()) {
            Optional<String> to = organization.slug().flatMap(slug -> Gateway.redirect(slug, name, path, rawQuery));
            if (to.isPresent()) {
                return Response.temporaryRedirect(URI.create(to.get())).build();
            }
        }
        // The services of the level, as the GeoFence rules give them (GP-335, ADR-0005): view is the map
        // and its tiles, the features need download.
        if (OgcGate.service(path, rawQuery).equals("WFS")) {
            sharing.require(ref(layer.datasetId()), "download");
        }
        access.ensureCurrent();
        // The gate reaches GeoServer as its administrator and cannot apply data rules (GP-308): a person
        // they limit gets the layer from GeoServer with the rules, through the gateway (GP-336), not here.
        if (identity.getPrincipal() != null && access.limits(name, identity.getPrincipal().getName())) {
            return Failure.correct(403, "data-rules", "Data rules limit what you see of this layer: it is served to"
                    + " you only where they apply, through the data gateway of Geoportal.").response();
        }
        DataRequests.count(quotas, EXTENSION);
        String proxy = uri.getBaseUri().resolve(API.substring(1) + name + "/ogc").toString();
        return LayerGate.forward(method, geoserver, layer, path, rawQuery, headers, proxy);
    }

    // Plumbing -----------------------------------------------------------------------------------

    private Response answer(java.util.function.Supplier<DatasetPublishing.Outcome> action, UploadedData data,
            Response.Status success) {
        DatasetPublishing.Outcome outcome;
        try {
            outcome = action.get();
        } catch (GeoServerException | GeoServerGovernance.MissingCredentialException | IllegalArgumentException e) {
            return Failure.of(e, "publishing").response();
        }
        if (outcome.failure() != null) {
            return outcome.failure().response();
        }
        UploadedData current = uploads.byId(data.id()).orElse(data);
        return Response.status(success).entity(view(outcome.layer(), current, true)).build();
    }

    /** A published layer whose dataset the person sees; 404 otherwise, as if it did not exist. */
    private PublishedLayer visible(String name) {
        // Layers without a dataset (defined through the GeoServer API of GP-334) are not services here.
        PublishedLayer layer = geoserver.layer(name).filter(l -> l.datasetId() != null)
                .orElseThrow(jakarta.ws.rs.NotFoundException::new);
        sharing.require(ref(layer.datasetId()), "view");
        return layer;
    }

    /** A published layer whose dataset the person manages. */
    private PublishedLayer managed(String name) {
        PublishedLayer layer = visible(name);
        sharing.require(ref(layer.datasetId()), "manage");
        return layer;
    }

    private UploadedData find(UUID id) {
        return uploads.byId(id).orElseThrow(jakarta.ws.rs.NotFoundException::new);
    }

    private PublicationView view(PublishedLayer layer, UploadedData data, boolean canPublish) {
        ResourceRef ref = ref(data.id());
        String level = sharing.level(ref).orElse("view");
        Map<String, String> services = new LinkedHashMap<>();
        boolean gateway = Gateway.enabled() && organization.slug().isPresent();
        for (String kind : List.of("wms", "wfs", "wmts")) {
            String query = Gateway.SERVICES.get(kind);
            services.put(kind, gateway
                    ? Gateway.layerService(organization.slug().get(), layer.name(), kind) + "?" + query
                    : API + layer.name() + "/ogc/" + (kind.equals("wmts") ? "gwc/service/wmts" : kind) + "?" + query);
        }
        String style = layer.definition().style();
        GeoportalStyle source = style == null ? null
                : geoserver.style(style).flatMap(s -> DatasetPublishing.parse(s.source())).orElse(null);
        Health health = new Health(layer.health() == null ? "unknown" : layer.health(), layer.healthCode(),
                layer.healthReason(), layer.healthCheckedAt());
        return new PublicationView(layer.name(), layer.definition().title(), data.id(), data.title(),
                data.technicalName(), data.geometryType(), style, source, health, layer.publishedAt(), services, gateway,
                level, canPublish && "manage".equals(level));
    }
}
