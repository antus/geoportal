// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.gateway;

import dev.geoportal.catalog.CatalogExtension;
import dev.geoportal.catalog.geoserver.OrganizationSpace;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import dev.mosaikit.kernel.api.context.ServiceCaller;
import dev.mosaikit.kernel.api.quota.Quotas;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What the data gateway asks the catalog (GP-336, ADR-0022), under /api/v1/ext/catalog/gateway.
 *
 * <ul>
 *   <li>{@code GET check}: the go-ahead of a request to the geodata of an organization, asked by nginx
 *       ({@code auth_request}) at most every ten seconds per person and organization. The gateway calls
 *       it <em>as the person</em>: with the session cookie and {@code X-Mosaikit-Organization}, or with the
 *       personal access key of a desktop client, so that Mosaikit itself refuses an ended session, a
 *       revoked key, a guest or a person of another organization, and the request has the organization
 *       on which the quota is counted. It answers who the person is, as GeoServer knows people, and the
 *       workspace of the organization; 403 when the session or key acts for another organization, or
 *       when the quota of the organization is used up.
 *   <li>{@code GET authkey?key=}: the web service mapper of the AuthKey filter of GeoServer, through the
 *       internal door of the gateway, which adds the key of the service identity of the gateway: the
 *       username of the person of a personal key, or nothing, which leaves the request anonymous.
 * </ul>
 */
@Path("/api/v1/ext/catalog/gateway")
public class GatewayResource {

    /** The organization in the address of the gateway, which the session or the key must act for. */
    static final String ORGANIZATION = "X-Gateway-Organization";

    /** The workspace in the address, when it names one (the addresses GeoServer writes in its answers). */
    static final String WORKSPACE = "X-Gateway-Workspace";

    /** The requests the gateway served since its last go-ahead for this person and organization. */
    static final String SERVED = "X-Gateway-Served";

    private final CurrentOrganization organization;
    private final SecurityIdentity identity;
    private final Quotas quotas;
    private final KeyCheck keys;
    @jakarta.inject.Inject
    dev.geoportal.catalog.editing.LayerEditing editing;

    public GatewayResource(CurrentOrganization organization, SecurityIdentity identity, Quotas quotas, KeyCheck keys) {
        this.organization = organization;
        this.identity = identity;
        this.quotas = quotas;
        this.keys = keys;
    }

    /**
     * The go-ahead.
     *
     * @param user the username of the person, which the gateway sends to GeoServer
     * @param organization the identifier of the organization
     * @param workspace the GeoServer workspace of the organization
     */
    public record GoAhead(String user, String organization, String workspace) {}

    @GET
    @Path("check")
    @Authenticated
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response check(@HeaderParam(ORGANIZATION) String asked, @HeaderParam(WORKSPACE) String workspace,
            @HeaderParam(SERVED) String served) {
        if (ServiceCaller.isService(identity.getRoles())) {
            return refuse("A service identity has no data of its own: the gateway asks as the person.");
        }
        UUID org = organization.require();
        String slug = organization.slug().orElse("");
        if (asked == null || !asked.equals(slug)) {
            return refuse("This session or key acts for another organization than the one in the address.");
        }
        OrganizationSpace space = new OrganizationSpace(org);
        if (workspace != null && !workspace.isBlank() && !workspace.equals(space.workspace())) {
            return refuse("The workspace in the address is not the one of the organization.");
        }
        Optional<String> beyond = DataRequests.admit(quotas, CatalogExtension.ID, DataRequests.served(served));
        if (beyond.isPresent()) {
            return problem("Quota exceeded", beyond.get());
        }
        // GP-036: a surveyor's permission to edit layer data reaches GeoFence at the go-ahead of their requests.
        try {
            editing.confirm();
        } catch (RuntimeException e) {
            // never the cause of a refused request: the next go-ahead confirms it
        }
        return Response.ok(new GoAhead(identity.getPrincipal().getName(), org.toString(), space.workspace())).build();
    }

    @GET
    @Path("authkey")
    @RolesAllowed(ServiceCaller.ROLE)
    @Produces(MediaType.TEXT_PLAIN)
    public Response authkey(@QueryParam("key") String key, @HeaderParam("Authorization") String authorization) {
        if (key == null || key.isBlank() || authorization == null) {
            return Response.ok("").build();
        }
        return Response.ok(keys.person(key.strip(), authorization).orElse("")).build();
    }

    private static Response refuse(String detail) {
        return problem("Forbidden", detail);
    }

    private static Response problem(String title, String detail) {
        return Response.status(403)
                .type("application/problem+json")
                .entity(Map.of("type", "about:blank", "title", title, "status", 403, "detail", detail,
                        "errors", List.of()))
                .build();
    }
}
