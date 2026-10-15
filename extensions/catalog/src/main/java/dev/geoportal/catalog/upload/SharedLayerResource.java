// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.upload;

import static dev.geoportal.catalog.upload.UploadedDatasets.EXTENSION;
import static dev.geoportal.catalog.upload.UploadedDatasets.ref;

import dev.geoportal.catalog.gateway.DataRequests;
import dev.geoportal.catalog.gateway.Gateway;
import dev.geoportal.catalog.geoserver.GeoServerGovernance;
import dev.geoportal.catalog.geoserver.OgcGate;
import dev.geoportal.catalog.geoserver.PublishedLayer;
import dev.mosaikit.kernel.api.quota.Quotas;
import dev.mosaikit.kernel.api.sharing.Sharing;
import jakarta.annotation.security.PermitAll;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The published layer of a dataset opened with a link of Mosaikit (GP-336, MK-055), for people
 * without an account: under /api/v1/ext/catalog/shared-layers/{dataset}, the title, extent and name of
 * the layer, and its OGC services through the gate of the catalog, which lets through only the read
 * requests of that one layer ({@link LayerGate}). The link names the dataset: every route asks
 * {@code Sharing} about that very dataset first, so a link opens only the resource it names and acts
 * on its organization only after that answer; a request with a session but no link is answered the same
 * way, by the level of the person.
 *
 * <p>The gateway serves the same services at {@code /gw/link/<secret>/<dataset>/<service>}, adding the
 * secret as the header {@code X-Mosaikit-Link} that map clients cannot send; every request counts one to
 * the measure {@value DataRequests#MEASURE} of the organization, and none passes beyond its limit.
 */
@Path("/api/v1/ext/catalog/shared-layers")
public class SharedLayerResource {

    /** What the gateway says the gate is, to name it in the answers: {@code <origin>/gw/link/<secret>/<dataset>}. */
    static final String GATEWAY_BASE = "X-Gateway-Base";

    private static final Pattern LINK_BASE = Pattern.compile("^https?://[^/\\s]+/gw/link/[A-Za-z0-9_%-]+/[0-9a-f-]{36}$");

    private final GeoServerGovernance geoserver;
    private final UploadedDataRepository uploads;
    private final Sharing sharing;
    private final Quotas quotas;

    public SharedLayerResource(GeoServerGovernance geoserver, UploadedDataRepository uploads, Sharing sharing,
            Quotas quotas) {
        this.geoserver = geoserver;
        this.uploads = uploads;
        this.sharing = sharing;
        this.quotas = quotas;
    }

    /**
     * A dataset opened with a link, as the page of the link shows it.
     *
     * @param extent west, south, east, north in WGS84 degrees, or {@code null}
     * @param gateway whether the gateway serves {@code /gw/link/...}
     */
    public record SharedLayer(UUID dataset, String title, String description, String layer, String geometryType,
            double[] extent, String level, boolean gateway) {}

    @GET
    @Path("{dataset}")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public SharedLayer one(@PathParam("dataset") UUID dataset) {
        PublishedLayer layer = layerOf(dataset);
        UploadedData data = uploads.byId(dataset).orElseThrow(NotFoundException::new);
        var e = data.wgs84();
        double[] extent = e == null ? null : new double[] {e.minX(), e.minY(), e.maxX(), e.maxY()};
        return new SharedLayer(dataset, data.title(), data.description(), layer.name(), data.geometryType(), extent,
                sharing.level(ref(dataset)).orElse("view"), Gateway.enabled());
    }

    @GET
    @Path("{dataset}/ogc/{path: .+}")
    @PermitAll
    @Transactional
    public Response ogc(@PathParam("dataset") UUID dataset, @Encoded @PathParam("path") String path,
            @Context UriInfo uri, @Context HttpHeaders headers) {
        return gate("GET", dataset, path, uri, headers);
    }

    @HEAD
    @Path("{dataset}/ogc/{path: .+}")
    @PermitAll
    @Transactional
    public Response ogcHead(@PathParam("dataset") UUID dataset, @Encoded @PathParam("path") String path,
            @Context UriInfo uri, @Context HttpHeaders headers) {
        return gate("HEAD", dataset, path, uri, headers);
    }

    private Response gate(String method, UUID dataset, String path, UriInfo uri, HttpHeaders headers) {
        PublishedLayer layer = layerOf(dataset);
        String rawQuery = uri.getRequestUri().getRawQuery();
        // As for people (GP-335): view is the map and its tiles, the features need download, which a
        // link (linkLevel view) does not give.
        if (OgcGate.service(path, rawQuery).equals("WFS")) {
            sharing.require(ref(dataset), "download");
        }
        DataRequests.count(quotas, EXTENSION);
        String base = headers.getHeaderString(GATEWAY_BASE);
        String proxy = base != null && LINK_BASE.matcher(base).matches() && base.endsWith("/" + dataset)
                ? base
                : uri.getBaseUri().resolve("api/v1/ext/catalog/shared-layers/" + dataset + "/ogc").toString();
        return LayerGate.forward(method, geoserver, layer, path, rawQuery, headers, proxy);
    }

    /** The published layer of a dataset the link (or the person) may view; 404 otherwise. */
    private PublishedLayer layerOf(UUID dataset) {
        // First: the link acts on the organization of the dataset only once Sharing answered for it.
        sharing.require(ref(dataset), "view");
        return geoserver.published().stream()
                .filter(l -> dataset.equals(l.datasetId()))
                .findFirst()
                .orElseThrow(NotFoundException::new);
    }
}
