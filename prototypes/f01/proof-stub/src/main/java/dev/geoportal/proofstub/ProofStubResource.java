// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.proofstub;

import io.quarkus.security.identity.SecurityIdentity;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * PROOF STUB (GP-327, phase F01): the minimal endpoints that the gateway and GeoServer call in the
 * proofs of {@code prototypes/f01}, in an extension of its own ({@code dev.geoportal.proofstub}) so that
 * the catalog stays as it is. It is not the design of the catalog: GP-334, GP-335 and GP-336 replace
 * it. Every endpoint is under {@code /api/v1/ext/proofstub/}, where the kernel lets in
 * only authenticated requests that act on one organization.
 *
 * <ul>
 *   <li>{@code GET gateway-check}: the {@code auth_request} of nginx. The organization of the
 *       request is the workspace (nginx sends it as {@code X-Mosaikit-Organization}); a person who
 *       is not a member gets 403 from the kernel before this method; a member gets 200 with the
 *       user name in {@code X-Geoportal-User}, which nginx passes to GeoServer.
 *   <li>{@code POST keys}, {@code DELETE keys/{key}}: personal keys for QGIS (AuthKey).
 *   <li>{@code GET authkey?key=}: the AuthKey web service mapper of GeoServer; the user name in
 *       plain text, or an empty body for an unknown or revoked key. Only the service account.
 *   <li>{@code GET roles/api/roles|adminRole|users}: the REST role service of GeoServer, as the
 *       fallback of the JDBC role service. Only the service account.
 * </ul>
 */
@Path("/api/v1/ext/proofstub")
public class ProofStubResource {

    /** The roles of every person, as GeoServer names them: the same query as the views of the proof. */
    static final String ROLES_SQL = """
            select a.username, 'ORG_' || o.slug || '_' || replace(r.role, 'organization-', '')
            from mk_kernel.user_account a
            join mk_kernel.organization_member m on m.account_id = a.id
            join mk_kernel.organization o on o.id = m.organization_id
            cross join lateral regexp_split_to_table(m.roles, ',') as r(role)
            union
            select a.username, 'TEAM_' || t.id
            from mk_kernel.team_member tm
            join mk_kernel.team t on t.id = tm.team_id and t.kind = 'team'
            join mk_kernel.user_account a on a.id = tm.account_id
            where tm.role in ('owner', 'member')
            order by 1, 2
            """;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecurityIdentity identity;
    private final CurrentOrganization organization;
    private final EntityManager entities;
    private final String serviceAccount;

    public ProofStubResource(
            SecurityIdentity identity,
            CurrentOrganization organization,
            EntityManager entities,
            @ConfigProperty(name = "geoportal.proofstub.service-account", defaultValue = "")
                    String serviceAccount) {
        this.identity = identity;
        this.organization = organization;
        this.entities = entities;
        this.serviceAccount = serviceAccount.toLowerCase(Locale.ROOT);
    }

    /** The go-ahead of the gateway: the person is a member of the organization owning the workspace. */
    @GET
    @Path("gateway-check")
    public Response gatewayCheck(@HeaderParam("X-Geoportal-Workspace") String workspace) {
        Optional<String> slug = organization.slug();
        if (workspace == null || slug.isEmpty() || !slug.get().equals(workspace)) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return Response.ok().header("X-Geoportal-User", name()).build();
    }

    /** A new personal key of the person of the request, shown once. */
    @POST
    @Path("keys")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, String> createKey() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        entities.createNativeQuery("insert into ext_proofstub.personal_key (key_hash, username, organization_id,"
                        + " created_at) values (?1, ?2, ?3, ?4)")
                .setParameter(1, hash(key))
                .setParameter(2, name())
                .setParameter(3, organization.require())
                .setParameter(4, Instant.now())
                .executeUpdate();
        return Map.of("key", key, "username", name());
    }

    /** Revokes a key of the person of the request. */
    @DELETE
    @Path("keys/{key}")
    @Transactional
    public Response revokeKey(@PathParam("key") String key) {
        int changed = entities.createNativeQuery("update ext_proofstub.personal_key set revoked_at = ?1"
                        + " where key_hash = ?2 and username = ?3 and revoked_at is null")
                .setParameter(1, Instant.now())
                .setParameter(2, hash(key))
                .setParameter(3, name())
                .executeUpdate();
        return Response.status(changed == 1 ? 204 : 404).build();
    }

    /** The AuthKey web service mapper: the name of the owner of a live key, or nothing. */
    @GET
    @Path("authkey")
    @Produces(MediaType.TEXT_PLAIN)
    @Transactional
    public Response authkey(@QueryParam("key") String key) {
        if (!service()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        if (key == null || key.isBlank()) {
            return Response.ok("").build();
        }
        List<?> found = entities.createNativeQuery("select username from ext_proofstub.personal_key"
                        + " where key_hash = ?1 and revoked_at is null")
                .setParameter(1, hash(key.strip()))
                .getResultList();
        return Response.ok(found.isEmpty() ? "" : (String) found.getFirst()).build();
    }

    /** REST role service: every role. */
    @GET
    @Path("roles/api/roles")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response roles() {
        if (!service()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        List<String> names = new ArrayList<>(roleRows().stream().map(r -> r[1]).distinct().sorted().toList());
        names.add("ADMIN");
        return Response.ok(Map.of("groups", names)).build();
    }

    /** REST role service: the administrator role. */
    @GET
    @Path("roles/api/adminRole")
    @Produces(MediaType.APPLICATION_JSON)
    public Response adminRole() {
        if (!service()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        return Response.ok(Map.of("adminRole", "ADMIN")).build();
    }

    /** REST role service: every person with their roles (GeoServer filters by user name). */
    @GET
    @Path("roles/api/users")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response users() {
        return users(null);
    }

    /** REST role service: one person with their roles (GeoServer appends /{username}). */
    @GET
    @Path("roles/api/users/{username}")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response user(@PathParam("username") String username) {
        return users(username);
    }

    private Response users(String only) {
        if (!service()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        Map<String, List<String>> byUser = new LinkedHashMap<>();
        for (String[] row : roleRows()) {
            if (only == null || only.equals(row[0])) {
                byUser.computeIfAbsent(row[0], k -> new ArrayList<>()).add(row[1]);
            }
        }
        // The administrator of GeoServer: a name no Mosaikit account can have (not "admin").
        if (only == null || only.equals("gsadmin")) {
            byUser.computeIfAbsent("gsadmin", k -> new ArrayList<>()).add("ADMIN");
        }
        List<Map<String, Object>> users = byUser.entrySet().stream()
                .map(e -> Map.<String, Object>of("username", e.getKey(), "groups", e.getValue()))
                .toList();
        return Response.ok(Map.of("users", users)).build();
    }

    private List<String[]> roleRows() {
        List<?> rows = entities.createNativeQuery(ROLES_SQL).getResultList();
        return rows.stream()
                .map(row -> (Object[]) row)
                .map(row -> new String[] {(String) row[0], (String) row[1]})
                .toList();
    }

    private String name() {
        return identity.getPrincipal().getName();
    }

    private boolean service() {
        return !serviceAccount.isEmpty() && serviceAccount.equals(name().toLowerCase(Locale.ROOT));
    }

    private static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
