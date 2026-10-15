// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata;

import static dev.geoportal.ext.catalog.geodata.CatalogExtension.ID;
import static dev.geoportal.ext.catalog.geodata.CatalogExtension.SERVICE;

import dev.geoportal.ext.catalog.geodata.proxy.AddressGuard;
import dev.geoportal.ext.catalog.geodata.proxy.BrowserAccess;
import dev.geoportal.ext.catalog.geodata.proxy.OgcRequests;
import dev.geoportal.ext.catalog.geodata.proxy.RefusedTarget;
import dev.mosaikit.kernel.api.audit.AuditLog;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import dev.mosaikit.kernel.api.permission.Permissions;
import dev.mosaikit.kernel.api.resource.ResourceRef;
import dev.mosaikit.kernel.api.resource.Resources;
import dev.mosaikit.kernel.api.sharing.Sharing;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The external services of the organization, under /api/v1/ext/catalog-geodata/services (GP-939): register,
 * list, change and remove. Registering needs the permission {@code register-service}; seeing,
 * using and managing a service need the levels {@code view}, {@code use} and {@code manage} on it.
 * Attaching a credential, or moving a service that has one to another address, needs the permission
 * {@code service-credentials}, so that nobody sends a credential of the organization to a server
 * of their choice. Every change is in the audit log of the organization.
 *
 * <p>GP-297: a service saved without the proxy and without a credential is checked from the server
 * ({@link BrowserProbe}); when only the proxy can read it, because the service does not allow the
 * origin of the shell (CORS) or the policy of the shell does not let the browser contact it (MK-128),
 * the proxy is turned on and the answer says why ({@code access}). {@code POST {id}/access} checks a
 * service again, on demand.
 */
@Path("/api/v1/ext/catalog-geodata/services")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ExternalServiceResource {

    static final Set<String> KINDS = Set.of("wms", "wmts", "wfs", "wcs");
    static final Set<String> SCHEMES = Set.of("basic", "bearer");
    static final String DEFAULT_CRS = "EPSG:4326";

    private final ExternalServices services;
    private final CurrentOrganization organization;
    private final Resources resources;
    private final Sharing sharing;
    private final Permissions permissions;
    private final AuditLog audit;
    private final ProxySettings settings;
    private final ServiceCredentials credentials;
    private final BrowserProbe probe;

    @Context
    HttpHeaders headers;

    @Context
    UriInfo uri;

    public ExternalServiceResource(
            ExternalServices services,
            CurrentOrganization organization,
            Resources resources,
            Sharing sharing,
            Permissions permissions,
            AuditLog audit,
            ProxySettings settings,
            ServiceCredentials credentials,
            BrowserProbe probe) {
        this.services = services;
        this.organization = organization;
        this.resources = resources;
        this.sharing = sharing;
        this.permissions = permissions;
        this.audit = audit;
        this.settings = settings;
        this.credentials = credentials;
        this.probe = probe;
    }

    /** The services the person may see. */
    @GET
    @Transactional
    public List<ServiceView> list() {
        Set<String> visible = sharing.visible(ID, SERVICE, "view");
        return services.all().stream()
                .filter(s -> visible.contains(s.id().toString()))
                .map(s -> s.view(sharing.allows(CatalogExtension.service(s.id()), "manage")))
                .toList();
    }

    @GET
    @Path("{id}")
    @Transactional
    public ServiceView one(@PathParam("id") UUID id) {
        ResourceRef ref = CatalogExtension.service(id);
        ExternalService service = services.byId(id).orElseThrow(NotFoundException::new);
        sharing.require(ref, "view");
        return service.view(sharing.allows(ref, "manage"));
    }

    @POST
    @Transactional
    public Response register(ServiceInput body) {
        permissions.require(ID, CatalogExtension.REGISTER_SERVICE);
        ServiceInput values = checked(body, null);
        AccessCheck check = needsCheck(values) ? check(values.url(), values.kind()) : null;
        if (check != null && BrowserAccess.PROXY.equals(check.outcome())) {
            values = withProxy(values);
            check = check.turnedOn();
        }
        ExternalService service = new ExternalService(organization.require(), values, Instant.now());
        services.insert(service);
        resources.register(CatalogExtension.service(service.id()), Resources.Details.of(service.title(), "/app/geodata"));
        Map<String, Object> details = details(service);
        access(details, check);
        audit.record(ID, new AuditLog.Entry(
                "service.registered", "service/" + service.id(), AuditLog.Outcome.SUCCEEDED, details));
        return Response.status(Response.Status.CREATED).entity(service.view(true).with(check)).build();
    }

    @PUT
    @Path("{id}")
    @Transactional
    public ServiceView change(@PathParam("id") UUID id, ServiceInput body) {
        ResourceRef ref = CatalogExtension.service(id);
        ExternalService service = services.byId(id).orElseThrow(NotFoundException::new);
        sharing.require(ref, "manage");
        ServiceInput values = checked(body, service);
        boolean moved = !values.url().equals(service.url()) || !values.kind().equals(service.kind());
        AccessCheck check = moved && needsCheck(values) ? check(values.url(), values.kind()) : null;
        if (check != null && BrowserAccess.PROXY.equals(check.outcome())) {
            values = withProxy(values);
            check = check.turnedOn();
        }
        List<String> changed = changes(service, values);
        boolean proxyBefore = service.proxy();
        String credentialBefore = service.credential();
        service.apply(values, Instant.now());
        services.update(service);
        resources.update(ref, Resources.Details.of(service.title(), "/app/geodata"));
        if (credentialBefore != null) {
            credentials.forget(organization.require(), credentialBefore);
        }
        Map<String, Object> details = details(service);
        details.put("changed", changed);
        details.put("proxyBefore", proxyBefore);
        access(details, check);
        audit.record(ID, new AuditLog.Entry("service.changed", "service/" + id, AuditLog.Outcome.SUCCEEDED, details));
        return service.view(true).with(check);
    }

    /**
     * Checks again whether the browser reads the service directly (GP-297), for those who manage it:
     * when only the proxy can, the proxy is turned on. It is never turned off here: whether to go
     * without the proxy stays a choice of a person.
     */
    @POST
    @Path("{id}/access")
    @Transactional
    public ServiceView checkAgain(@PathParam("id") UUID id) {
        ResourceRef ref = CatalogExtension.service(id);
        ExternalService service = services.byId(id).orElseThrow(NotFoundException::new);
        sharing.require(ref, "manage");
        AccessCheck check = check(service.url(), service.kind());
        Map<String, Object> details = details(service);
        access(details, check);
        if (BrowserAccess.PROXY.equals(check.outcome()) && !service.proxy()) {
            service.proxyOn(Instant.now());
            services.update(service);
            check = check.turnedOn();
            details.put("proxy", true);
            details.put("proxyTurnedOn", true);
            audit.record(ID, new AuditLog.Entry("service.proxy-turned-on", "service/" + id, AuditLog.Outcome.SUCCEEDED,
                    details));
        } else {
            audit.record(ID, new AuditLog.Entry("service.access-checked", "service/" + id, AuditLog.Outcome.SUCCEEDED,
                    details));
        }
        return service.view(true).with(check);
    }

    /** Only a service saved without the proxy and without a credential is checked. */
    private static boolean needsCheck(ServiceInput values) {
        return !Boolean.TRUE.equals(values.proxy()) && values.credential() == null;
    }

    private static ServiceInput withProxy(ServiceInput v) {
        return new ServiceInput(v.title(), v.kind(), v.url(), true, v.crs(), v.credential(), v.authScheme());
    }

    private AccessCheck check(String url, String kind) {
        return probe.check(url, kind, shellOrigin(), uri == null ? null : uri.getBaseUri());
    }

    /** The origin of the shell: that of the page that asked, else the address of the request. */
    private String shellOrigin() {
        String origin = headers == null ? null : headers.getHeaderString("Origin");
        if (origin != null && origin.matches("https?://[^/\\s]+")) {
            try {
                return BrowserAccess.origin(URI.create(origin));
            } catch (IllegalArgumentException e) {
                // the address of the request then
            }
        }
        return BrowserAccess.origin(uri.getBaseUri());
    }

    private static void access(Map<String, Object> details, AccessCheck check) {
        if (check != null) {
            details.put("access", check.outcome());
            details.put("reasons", check.reasons());
            details.put("proxyTurnedOn", check.proxyTurnedOn());
        }
    }

    @DELETE
    @Path("{id}")
    @Transactional
    public Response remove(@PathParam("id") UUID id) {
        ResourceRef ref = CatalogExtension.service(id);
        ExternalService service = services.byId(id).orElseThrow(NotFoundException::new);
        sharing.require(ref, "manage");
        services.delete(service);
        resources.remove(ref);
        audit.record(ID, new AuditLog.Entry(
                "service.removed", "service/" + id, AuditLog.Outcome.SUCCEEDED, details(service)));
        return Response.noContent().build();
    }

    /** The values to store, normalized, or 400 with the reason; 403 for a credential not allowed. */
    private ServiceInput checked(ServiceInput body, ExternalService previous) {
        if (body == null) {
            throw invalid("The service is missing.");
        }
        String title = body.title() == null ? "" : body.title().strip();
        if (title.isEmpty() || title.length() > 200) {
            throw invalid("The title must have 1 to 200 characters.");
        }
        String kind = body.kind() == null ? "" : body.kind().strip().toLowerCase(java.util.Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw invalid("The kind must be one of wms, wmts, wfs, wcs.");
        }
        URI uri;
        try {
            uri = AddressGuard.parse(body.url());
        } catch (RefusedTarget e) {
            throw refused(body, previous, e);
        }
        String crs = body.crs() == null || body.crs().isBlank() ? null : body.crs().strip();
        if (crs == null && kind.equals("wfs")) {
            crs = DEFAULT_CRS;
        }
        if (crs != null && !crs.matches(OgcRequests.CRS)) {
            throw invalid("The reference system is not valid, such as EPSG:4326.");
        }
        String credential = body.credential() == null || body.credential().isBlank() ? null : body.credential().strip();
        String scheme = null;
        if (credential != null) {
            if (!credential.matches("[a-z0-9][a-z0-9._-]{0,99}")) {
                throw invalid("The name of the credential is not valid.");
            }
            scheme = body.authScheme() == null ? "" : body.authScheme().strip().toLowerCase(java.util.Locale.ROOT);
            if (!SCHEMES.contains(scheme)) {
                throw invalid("The scheme of the credential must be basic or bearer.");
            }
            boolean newCredential = previous == null
                    || !credential.equals(previous.credential())
                    || !scheme.equals(previous.authScheme());
            boolean moved = previous != null && !uri.toString().equals(previous.url().strip());
            if (newCredential || moved) {
                permissions.require(ID, CatalogExtension.SERVICE_CREDENTIALS);
            }
        }
        // Resolved last, once the permissions are checked: a refused person learns nothing of the network.
        try {
            settings.guard().check(uri);
        } catch (RefusedTarget e) {
            throw refused(body, previous, e);
        }
        return new ServiceInput(title, kind, uri.toString(), Boolean.TRUE.equals(body.proxy()), crs, credential, scheme);
    }

    /** A refused address: audited, since registering internal addresses is what an attack looks like. */
    private WebApplicationException refused(ServiceInput body, ExternalService previous, RefusedTarget e) {
        audit.record(ID, new AuditLog.Entry(
                previous == null ? "service.refused" : "service.change-refused",
                previous == null ? null : "service/" + previous.id(),
                AuditLog.Outcome.DENIED,
                Map.of("host", host(body.url()), "reason", e.getMessage())));
        return invalid(e.getMessage());
    }

    private static List<String> changes(ExternalService before, ServiceInput after) {
        List<String> changed = new ArrayList<>();
        if (!before.title().equals(after.title())) {
            changed.add("title");
        }
        if (!before.kind().equals(after.kind())) {
            changed.add("kind");
        }
        if (!before.url().equals(after.url())) {
            changed.add("url");
        }
        if (before.proxy() != after.proxy()) {
            changed.add("proxy");
        }
        if (!Objects.equals(before.crs(), after.crs())) {
            changed.add("crs");
        }
        if (!Objects.equals(before.credential(), after.credential())
                || !Objects.equals(before.authScheme(), after.authScheme())) {
            changed.add("credential");
        }
        return changed;
    }

    /** What the audit log says of a service: never its full address, whose query may hold a key. */
    private static Map<String, Object> details(ExternalService service) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", service.title());
        details.put("kind", service.kind());
        details.put("host", host(service.url()));
        details.put("proxy", service.proxy());
        details.put("credential", service.credential() == null ? "none" : service.credential());
        return details;
    }

    private static String host(String url) {
        try {
            URI uri = new URI(url == null ? "" : url.strip());
            return uri.getHost() == null ? "?" : uri.getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    private static WebApplicationException invalid(String message) {
        return new WebApplicationException(Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("message", message))
                .build());
    }
}
