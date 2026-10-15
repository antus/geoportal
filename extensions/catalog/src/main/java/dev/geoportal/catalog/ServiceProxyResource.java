// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog;

import dev.geoportal.catalog.proxy.AddressGuard;
import dev.geoportal.catalog.proxy.AnswerRewriter;
import dev.geoportal.catalog.proxy.Forwarder;
import dev.geoportal.catalog.proxy.OgcRequests;
import dev.geoportal.catalog.proxy.ProxyHeaders;
import dev.geoportal.catalog.proxy.ProxyTarget;
import dev.geoportal.catalog.proxy.RefusedTarget;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The proxy of a registered external service (GP-939), for servers that refuse calls from another
 * origin: {@code /api/v1/ext/catalog/services/{id}/proxy}, followed by a path below the registered
 * address and the query of the request (GetCapabilities, GetMap, GetTile, GetFeature, ...). The
 * request goes only to the registered address of that service, after the checks of {@link
 * AddressGuard}; only GET, HEAD and the POST of OGC (XML or form) pass. {@code features} asks a WFS
 * for features directly, with the declared reference system.
 *
 * <p>Each call is not audited (a map makes hundreds); the changes of the services are.
 */
@Path("/api/v1/ext/catalog/services/{id}")
@Authenticated
public class ServiceProxyResource {

    private final ServiceAccess access;
    private final ProxySettings settings;
    private final ServiceCredentials credentials;
    @jakarta.inject.Inject
    ServiceRules serviceRules;

    public ServiceProxyResource(ServiceAccess access, ProxySettings settings, ServiceCredentials credentials) {
        this.access = access;
        this.settings = settings;
        this.credentials = credentials;
    }

    @GET
    @Path("proxy")
    public Response get(@PathParam("id") UUID id, @Context UriInfo uri, @Context HttpHeaders headers) {
        return forward("GET", id, null, uri, headers, null);
    }

    @HEAD
    @Path("proxy")
    public Response head(@PathParam("id") UUID id, @Context UriInfo uri, @Context HttpHeaders headers) {
        return forward("HEAD", id, null, uri, headers, null);
    }

    @POST
    @Path("proxy")
    @Consumes(MediaType.WILDCARD)
    public Response post(@PathParam("id") UUID id, @Context UriInfo uri, @Context HttpHeaders headers, InputStream body) {
        return forward("POST", id, null, uri, headers, body);
    }

    @GET
    @Path("proxy/{path: .+}")
    public Response getPath(
            @PathParam("id") UUID id, @Encoded @PathParam("path") String path, @Context UriInfo uri, @Context HttpHeaders headers) {
        return forward("GET", id, path, uri, headers, null);
    }

    @HEAD
    @Path("proxy/{path: .+}")
    public Response headPath(
            @PathParam("id") UUID id, @Encoded @PathParam("path") String path, @Context UriInfo uri, @Context HttpHeaders headers) {
        return forward("HEAD", id, path, uri, headers, null);
    }

    @POST
    @Path("proxy/{path: .+}")
    @Consumes(MediaType.WILDCARD)
    public Response postPath(
            @PathParam("id") UUID id,
            @Encoded @PathParam("path") String path,
            @Context UriInfo uri,
            @Context HttpHeaders headers,
            InputStream body) {
        return forward("POST", id, path, uri, headers, body);
    }

    /**
     * Features of a WFS as GeoJSON, asked directly with GetFeature in the reference system declared
     * for the service, without the GetCapabilities and DescribeFeatureType that a client would
     * otherwise send first and that servers behind a proxy often refuse.
     */
    @GET
    @Path("features")
    public Response features(
            @PathParam("id") UUID id,
            @QueryParam("typeName") String typeName,
            @QueryParam("bbox") String bbox,
            @QueryParam("count") Integer count,
            @Context UriInfo uri,
            @Context HttpHeaders headers) {
        if (!OgcRequests.validTypeName(typeName) || !OgcRequests.validBbox(bbox)) {
            return Forwarder.error(400, "typeName is required, and bbox must be four numbers.");
        }
        int limit = count == null ? 1000 : count;
        return forward("GET", id, null, uri, headers, null, typeName, bbox, limit);
    }

    private Response forward(String method, UUID id, String path, UriInfo uri, HttpHeaders headers, InputStream body) {
        return forward(method, id, path, uri, headers, body, null, null, 0);
    }

    private Response forward(
            String method,
            UUID id,
            String path,
            UriInfo uri,
            HttpHeaders headers,
            InputStream body,
            String typeName,
            String bbox,
            int count) {
        ServiceAccess.Decision decision = access.admit(id);
        if (decision.refusal() != null) {
            return switch (decision.refusal()) {
                case NOT_FOUND -> Forwarder.error(404, "No such service in the organization.");
                case FORBIDDEN -> Forwarder.error(403, "You may not use this service.");
                case PROXY_OFF -> Forwarder.error(409, "The proxy of this service is off.");
            };
        }
        ServiceAccess.Admitted service = decision.admitted();
        // GP-309: a data rule this service cannot apply makes the layer unavailable to its subject.
        java.util.Optional<ServiceRules.Refusal> ruled = ServiceRules.refusal(serviceRules.unavailable(id), method, path,
                typeName != null ? "typeName=" + java.net.URLEncoder.encode(typeName, java.nio.charset.StandardCharsets.UTF_8)
                        : uri.getRequestUri().getRawQuery());
        if (ruled.isPresent()) {
            Map<String, Object> refusal = new java.util.LinkedHashMap<>();
            refusal.put("message", ruled.get().message());
            refusal.put("code", "data-rule-not-applicable");
            refusal.put("layer", ruled.get().layer());
            return Response.status(403).type(MediaType.APPLICATION_JSON_TYPE).entity(refusal).build();
        }
        String rawQuery = typeName != null ? null : uri.getRequestUri().getRawQuery();
        if (typeName != null) {
            if (!service.kind().equals("wfs")) {
                return Forwarder.error(400, "Features are asked of WFS services only.");
            }
            rawQuery = OgcRequests.features(typeName, bbox, count, crs(service));
        } else if (service.kind().equals("wfs")) {
            rawQuery = OgcRequests.declareCrs(rawQuery, crs(service));
        }
        URI registered;
        URI target;
        try {
            registered = AddressGuard.parse(service.url());
            target = ProxyTarget.build(registered, path, rawQuery);
        } catch (RefusedTarget e) {
            return Forwarder.error(400, e.getMessage());
        }
        byte[] payload = null;
        Map<String, String> forwarded = ProxyHeaders.forService(name -> headers.getHeaderString(name));
        if (method.equals("POST")) {
            String type = headers.getHeaderString("content-type");
            String bare = type == null ? "" : type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            if (!bare.equals("text/xml") && !bare.equals("application/xml") && !bare.endsWith("+xml")
                    && !bare.equals("application/x-www-form-urlencoded")) {
                return Forwarder.error(415, "Only OGC requests in XML or as a form are forwarded with POST.");
            }
            try {
                payload = read(body, settings.maxRequestBytes());
            } catch (IOException e) {
                return Forwarder.error(400, "The body of the request cannot be read.");
            }
            if (payload == null) {
                return Forwarder.error(413, "The body of the request is larger than the proxy allows.");
            }
            forwarded.put("content-type", type);
        }
        if (service.credential() != null) {
            try {
                var header = credentials.header(service.organization(), service.credential(), service.authScheme());
                if (header.isEmpty()) {
                    return Forwarder.error(502, "The credential of the service is missing in the organization.");
                }
                forwarded.put("authorization", header.get());
            } catch (IllegalStateException e) {
                return Forwarder.error(503, "The credentials of the organization cannot be read.");
            }
        }
        Semaphore slots = settings.slots(id);
        boolean acquired;
        try {
            acquired = slots.tryAcquire(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            return Forwarder.error(503, "Too many requests to this service at once.");
        }
        Response answer = settings.forwarder().forward(method, registered, target, forwarded, payload, slots::release);
        // GP-334: capabilities name the proxy, not the service, and never carry the credential.
        String proxy = uri.getBaseUri().resolve("api/v1/ext/catalog/services/" + id + "/proxy").toString();
        return AnswerRewriter.apply(answer, registered, proxy, forwarded.get("authorization"));
    }

    private static String crs(ServiceAccess.Admitted service) {
        return service.crs() == null ? ExternalServiceResource.DEFAULT_CRS : service.crs();
    }

    /** The body, or null when it is larger than the limit. */
    private static byte[] read(InputStream in, int limit) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
            if (out.size() + n > limit) {
                return null;
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
