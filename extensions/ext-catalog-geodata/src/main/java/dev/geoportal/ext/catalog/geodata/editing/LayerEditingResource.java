// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.editing;

import dev.geoportal.ext.catalog.geodata.CatalogExtension;
import dev.geoportal.ext.catalog.geodata.access.PublishedViews;
import dev.geoportal.ext.catalog.geodata.geoserver.Failure;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerClient;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerException;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerGovernance;
import dev.geoportal.ext.catalog.geodata.geoserver.OrganizationSpace;
import dev.geoportal.ext.catalog.geodata.geoserver.PublishedLayer;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The editing of the data of published layers (GP-036), under /api/v1/ext/catalog-geodata/editing: the layers
 * assigned to the person, the surveyors of a layer and their assignment, and the WFS transactions of a
 * surveyor on a layer assigned to them, which the catalog checks and forwards to GeoServer.
 *
 * <p>Assignments are made by who manages the dataset of the layer (its owner, the administrators of the
 * organization, a level {@code manage}); a transaction needs the permission
 * {@value LayerEditing#PERMISSION} and the assignment of the layer, the same answer GeoFence gives to a
 * WFS transaction sent to GeoServer directly.
 */
@Path("/api/v1/ext/catalog-geodata/editing")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class LayerEditingResource {

    private final LayerEditing editing;
    private final GeoServerGovernance geoserver;
    private final Sharing sharing;
    private final PublishedViews views;

    public LayerEditingResource(LayerEditing editing, GeoServerGovernance geoserver, Sharing sharing,
            PublishedViews views) {
        this.editing = editing;
        this.geoserver = geoserver;
        this.sharing = sharing;
        this.views = views;
    }

    /**
     * A layer assigned to the person of the request.
     *
     * @param editable whether the person may write the data of the layer now
     * @param dataRules null when no data rule limits the person on the layer; {@value LayerEditing#WITHIN_DATA_RULES}
     *     when they write within their area and row filter through the WFS service of the layer only;
     *     {@value LayerEditing#DATA_RULES} when their rules stop them writing (ADR-0025)
     */
    public record AssignedLayer(String layer, String title, UUID dataset, Instant assignedAt, boolean editable,
            String dataRules) {}

    /** What the person edits: whether they hold the permission, and their layers. */
    public record Mine(boolean holds, List<AssignedLayer> layers) {}

    /**
     * A surveyor of a layer.
     *
     * @param level the level of the surveyor on the dataset of the layer, or null
     * @param holds whether their last request found them holding the permission, null before their first
     */
    public record Surveyor(UUID account, String username, String displayName, String level, Boolean holds,
            Instant assignedAt, String assignedBy) {}

    /** A person a layer can be assigned to. */
    public record Person(UUID account, String username, String displayName) {}

    /** The layers assigned to the person and whether they may edit them; tells the catalog whether they hold the permission. */
    @GET
    @Transactional
    public Mine mine() {
        editing.confirm();
        boolean holds = editing.holds();
        List<LayerAssignment> mine = editing.mine();
        List<AssignedLayer> layers = mine.stream().map(a -> {
            PublishedLayer l = geoserver.layer(a.layer()).orElseThrow();
            String limited = editing.dataRules(a.layer()).map(LayerEditing.Refusal::code).orElse(null);
            return new AssignedLayer(a.layer(), l.definition().title(), l.datasetId(), a.assignedAt(),
                    holds && !LayerEditing.DATA_RULES.equals(limited), limited);
        }).toList();
        return new Mine(holds, layers);
    }

    /** The members of the organization a layer can be assigned to, for who manages a published layer. */
    @GET
    @Path("people")
    @Transactional
    public Response people() {
        boolean manages = geoserver.published().stream()
                .anyMatch(l -> l.datasetId() != null && sharing.allows(CatalogExtension.dataset(l.datasetId()), "manage"));
        if (!manages) {
            return Response.status(403).build();
        }
        try {
            return Response.ok(views.members().stream().map(m -> new Person(m.account(), m.username(), m.displayName()))
                    .toList()).build();
        } catch (PublishedViews.Unavailable e) {
            return Failure.correct(409, "credential-missing", e.getMessage()).response();
        }
    }

    /** The surveyors of a layer (level manage on its dataset). */
    @GET
    @Path("layers/{name}/assignments")
    @Transactional
    public List<Surveyor> assignments(@PathParam("name") String name) {
        PublishedLayer layer = managed(name);
        return surveyors(layer);
    }

    /** Assigns the layer to a member of the organization (level manage on its dataset); the rules follow at once. */
    @PUT
    @Path("layers/{name}/assignments/{account}")
    @Transactional
    public Response assign(@PathParam("name") String name, @PathParam("account") UUID account) {
        PublishedLayer layer = managed(name);
        try {
            editing.assign(layer, account);
        } catch (NoSuchElementException e) {
            return Failure.correct(400, "invalid", "The person is not an active member of the organization.").response();
        } catch (PublishedViews.Unavailable e) {
            return Failure.correct(409, "credential-missing", e.getMessage()).response();
        }
        return Response.ok(surveyors(layer)).build();
    }

    /** Removes the assignment (level manage on the dataset); the next request of the surveyor is refused. */
    @DELETE
    @Path("layers/{name}/assignments/{account}")
    @Transactional
    public Response unassign(@PathParam("name") String name, @PathParam("account") UUID account) {
        managed(name);
        if (!editing.unassign(name, account)) {
            throw new NotFoundException();
        }
        return Response.noContent().build();
    }

    /**
     * A WFS transaction of the person on a layer (insert, update, delete), forwarded to GeoServer when they
     * hold the permission and the layer is assigned to them; the answer of GeoServer comes back as XML. A
     * surveyor limited by data rules on the layer is refused here (ADR-0025): they write within them through
     * the WFS service of the layer.
     */
    @POST
    @Path("layers/{name}/transaction")
    @Consumes({MediaType.TEXT_XML, MediaType.APPLICATION_XML})
    @Produces({MediaType.TEXT_XML, MediaType.APPLICATION_JSON})
    @Transactional
    public Response transaction(@PathParam("name") String name, byte[] body) {
        PublishedLayer layer = visible(name);
        editing.confirm();
        Optional<LayerEditing.Refusal> refusal = editing.refusal(name);
        if (refusal.isPresent()) {
            editing.refused(name, refusal.get());
            return problem(403, refusal.get().code(), refusal.get().reason());
        }
        OrganizationSpace space = geoserver.space();
        WfsTransaction.Summary summary;
        try {
            summary = WfsTransaction.check(body, layer.name(), space.workspace(), space.namespaceUri());
        } catch (WfsTransaction.Refused e) {
            return Failure.correct(400, "invalid", e.getMessage()).response();
        }
        HttpResponse<String> answer;
        try {
            GeoServerClient client = geoserver.client();
            answer = client.transaction(space.workspace() + "/" + layer.name() + "/wfs", body);
        } catch (GeoServerException | GeoServerGovernance.MissingCredentialException e) {
            return Failure.of(e, "editing " + name).response();
        }
        Optional<String> failed = answer.statusCode() / 100 == 2 ? WfsTransaction.failure(answer.body())
                : Optional.of("GeoServer answered " + answer.statusCode());
        if (failed.isPresent()) {
            return Failure.refusedBy("GeoServer did not apply the transaction.", failed.get()).response();
        }
        Map<String, Object> details = new LinkedHashMap<>(summary.actions());
        details.put("surveyor", editing.me());
        editing.edited(name, details);
        return Response.ok(answer.body(), MediaType.TEXT_XML_TYPE).build();
    }

    // Plumbing -----------------------------------------------------------------------------------

    private List<Surveyor> surveyors(PublishedLayer layer) {
        List<LayerAssignment> of = editing.of(layer.name());
        Map<UUID, PublishedViews.Member> members = new HashMap<>();
        if (!of.isEmpty()) {
            try {
                members = views.members().stream().collect(Collectors.toMap(PublishedViews.Member::account,
                        Function.identity(), (a, b) -> a));
            } catch (PublishedViews.Unavailable e) {
                // names as they were when assigned
            }
        }
        Map<UUID, PublishedViews.Member> known = members;
        return of.stream().map(a -> {
            PublishedViews.Member m = known.get(a.account());
            String level = sharing.level(a.account(), CatalogExtension.dataset(layer.datasetId())).orElse(null);
            return new Surveyor(a.account(), m == null ? a.username() : m.username(), m == null ? null : m.displayName(),
                    level, a.holds(), a.assignedAt(), a.assignedBy());
        }).toList();
    }

    /** A published layer whose dataset the person sees; 404 otherwise, as if it did not exist. */
    private PublishedLayer visible(String name) {
        PublishedLayer layer = geoserver.layer(name).filter(l -> l.datasetId() != null).orElseThrow(NotFoundException::new);
        sharing.require(CatalogExtension.dataset(layer.datasetId()), "view");
        return layer;
    }

    private PublishedLayer managed(String name) {
        PublishedLayer layer = visible(name);
        sharing.require(CatalogExtension.dataset(layer.datasetId()), "manage");
        return layer;
    }

    private static Response problem(int status, String code, String detail) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("title", status == 403 ? "Forbidden" : "Error");
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("code", code);
        return Response.status(status).type("application/problem+json").entity(problem).build();
    }
}
