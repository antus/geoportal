// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.geoserver;

import dev.geoportal.catalog.access.AccessRules;
import dev.geoportal.catalog.access.GeoFenceRule;
import dev.mosaikit.kernel.api.permission.Permissions;
import dev.mosaikit.kernel.api.secrets.SecretsUnavailableException;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * The GeoServer space of the organization, under /api/v1/ext/catalog/geoserver (GP-334): the layers
 * and styles the catalog publishes, and the alignment of GeoServer with them. For the people who hold
 * the permission {@code manage-geoserver} (the administrators of the organization hold it).
 *
 * <p>Changing a layer or style changes the definition only; GeoServer follows at the next alignment,
 * {@code POST alignment}, which also brings back what was changed in GeoServer by hand and reports what
 * the workspace holds that the catalog does not know. {@code GET alignment} tells the same without
 * changing anything.
 */
@Path("/api/v1/ext/catalog/geoserver")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class GeoServerResource {

    private static final String PERMISSION = "manage-geoserver";

    private final GeoServerGovernance governance;
    private final AccessRules rules;
    private final Permissions permissions;

    public GeoServerResource(GeoServerGovernance governance, AccessRules rules, Permissions permissions) {
        this.governance = governance;
        this.rules = rules;
        this.permissions = permissions;
    }

    /** The workspace of the organization, and what an alignment would change now. */
    @GET
    @Path("alignment")
    @Transactional
    public Response plan() {
        return answer(governance::plan);
    }

    @POST
    @Path("alignment")
    @Transactional
    public Response align() {
        return answer(governance::align);
    }

    @GET
    @Path("layers")
    @Transactional
    public Response layers() {
        return answer(governance::layers);
    }

    /** A layer as the catalog publishes it: a table of the schema of the organization. */
    public record LayerBody(String table, String title, String description, String srs, String style) {}

    @PUT
    @Path("layers/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response publish(@PathParam("name") String name, LayerBody body) {
        return answer(() -> governance.publish(new LayerDefinition(
                name, body.table() == null ? name : body.table(), body.title() == null ? name : body.title(),
                body.description(), body.srs(), body.style())));
    }

    @DELETE
    @Path("layers/{name}")
    @Transactional
    public Response withdrawLayer(@PathParam("name") String name) {
        return answer(() -> governance.withdrawLayer(name) ? null : notFound());
    }

    @GET
    @Path("styles")
    @Transactional
    public Response styles() {
        return answer(governance::styles);
    }

    public record StyleBody(String sld) {}

    @PUT
    @Path("styles/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response keep(@PathParam("name") String name, StyleBody body) {
        return answer(() -> governance.keep(new StyleDefinition(name, body == null ? null : body.sld())));
    }

    @DELETE
    @Path("styles/{name}")
    @Transactional
    public Response withdrawStyle(@PathParam("name") String name) {
        return answer(() -> governance.withdrawStyle(name) ? null : notFound());
    }

    /** The reconciliation report of the workspace (GP-118); changes nothing. */
    @GET
    @Path("reconciliation")
    @Transactional
    public Response reconciliation() {
        return answer(() -> governance.reconcile(false).withRules(rules.verify()));
    }

    /** Aligns, and reports what was aligned, what drifted and what needs a person (GP-118). */
    @POST
    @Path("reconciliation")
    @Transactional
    public Response reconcile() {
        // The alignment writes the GeoFence rules again (GP-335): the report says what that found.
        return answer(() -> governance.reconcile(true).withRules(rules.last()));
    }

    /** The GeoFence rules the catalog generates for the workspace, in the order of their priority (GP-335). */
    @GET
    @Path("rules")
    @Transactional
    public Response generatedRules() {
        return answer(() -> {
            AccessRules.Generated g = rules.generate();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("rules", g.rules().stream().map(GeoFenceRule::describe).toList());
            body.put("warnings", g.warnings());
            body.put("report", rules.verify());
            return body;
        });
    }

    /** Writes the GeoFence rules of the workspace again now, and reads them back (GP-335). */
    @POST
    @Path("rules")
    @Transactional
    public Response rewriteRules() {
        return answer(() -> rules.rewrite("person"));
    }

    /** Adopts a layer of the workspace that the catalog does not know: the alignments keep it from now. */
    @POST
    @Path("reconciliation/layers/{name}/adopt")
    @Transactional
    public Response adopt(@PathParam("name") String name) {
        return answer(() -> governance.adopt(name));
    }

    /** Deletes from GeoServer a layer or style that the catalog does not know. */
    @DELETE
    @Path("reconciliation/{kind: layers|styles}/{name}")
    @Transactional
    public Response removeUnknown(@PathParam("kind") String kind, @PathParam("name") String name) {
        return answer(() -> {
            governance.removeUnknown(kind.equals("layers") ? "layer" : "style", name);
            return null;
        });
    }

    private static Response notFound() {
        return Response.status(404).entity(Map.of("message", "Not published by the catalog")).build();
    }

    /** Checks the permission, then answers: 400 invalid, 409 conflict or missing credential, 502 GeoServer. */
    private Response answer(Supplier<?> action) {
        permissions.require(GeoServerGovernance.EXTENSION, PERMISSION);
        try {
            Object result = action.get();
            if (result instanceof Response r) {
                return r;
            }
            if (result == null) {
                return Response.noContent().build();
            }
            if (result instanceof Reconciliation r) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("workspace", r.workspace());
                body.put("applied", r.applied());
                body.put("aligned", r.aligned());
                body.put("missing", r.missing());
                body.put("changed", r.changed());
                body.put("withdrawn", r.withdrawn());
                body.put("unknown", r.unknown());
                body.put("failed", r.failed());
                body.put("rules", r.rules());
                body.put("needsPerson", r.needsPerson());
                return Response.ok(body).build();
            }
            if (result instanceof Alignment a) {
                return Response.ok(Map.of(
                        "workspace", a.workspace(),
                        "applied", a.applied(),
                        "aligned", a.aligned(),
                        "failed", a.failed(),
                        "changes", a.changes(),
                        "drift", a.drift())).build();
            }
            return Response.ok(result instanceof List<?> l ? l : result).build();
        } catch (SecretsUnavailableException e) {
            throw e; // 503 from the kernel
        } catch (NoSuchElementException e) {
            return error(404, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        } catch (IllegalStateException | GeoServerGovernance.MissingCredentialException e) {
            return error(409, e.getMessage());
        } catch (GeoServerException e) {
            // GP-115: what failed and what to do, with the reference of the log.
            Failure f = Failure.of(e, "aligning GeoServer");
            Map<String, Object> body = new LinkedHashMap<>(f.asMap());
            body.put("message", e.getMessage());
            return Response.status(502).entity(body).build();
        }
    }

    private static Response error(int status, String message) {
        return Response.status(status).entity(Map.of("message", message == null ? "" : message)).build();
    }
}
