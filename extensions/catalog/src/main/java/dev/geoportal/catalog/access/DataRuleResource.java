// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.access;

import static dev.geoportal.catalog.geoserver.GeoServerGovernance.EXTENSION;

import dev.geoportal.catalog.CatalogExtension;
import dev.geoportal.catalog.geoserver.Failure;
import dev.geoportal.catalog.geoserver.GeoServerClient;
import dev.geoportal.catalog.geoserver.GeoServerException;
import dev.geoportal.catalog.geoserver.GeoServerGovernance;
import dev.geoportal.catalog.geoserver.OgcGate;
import dev.geoportal.catalog.geoserver.PublishedLayer;
import dev.geoportal.catalog.geoserver.RulesReport;
import dev.mosaikit.kernel.api.audit.AuditLog;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import dev.mosaikit.kernel.api.resource.ResourceRef;
import dev.mosaikit.kernel.api.sharing.Sharing;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The data rules of a published layer (GP-308), under /api/v1/ext/catalog/data-rules: what a person or
 * a team sees of its dataset, by area, rows and columns. Those who manage the dataset (level
 * {@code manage}) read and change them; every change writes the GeoFence rules of the organization again
 * (GP-335), and is audited.
 *
 * <p>A rule is checked before it is kept: the subject is a person or a team of the organization, the
 * area a polygon in the reference system of the data (PostGIS checks it), the row filter a CQL filter
 * GeoServer accepts on the layer, the hidden columns columns of the layer other than its geometry.
 */
@Path("/api/v1/ext/catalog/data-rules")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class DataRuleResource {

    static final int MAX_CQL = 2000;

    private final DataRules rules;
    private final GeoServerGovernance geoserver;
    private final Sharing sharing;
    private final PublishedViews views;
    private final Areas areas;
    private final AccessRules access;
    private final AuditLog audit;
    private final CurrentOrganization organization;
    private final SecurityIdentity identity;

    public DataRuleResource(DataRules rules, GeoServerGovernance geoserver, Sharing sharing, PublishedViews views,
            Areas areas, AccessRules access, AuditLog audit, CurrentOrganization organization, SecurityIdentity identity) {
        this.rules = rules;
        this.geoserver = geoserver;
        this.sharing = sharing;
        this.views = views;
        this.areas = areas;
        this.access = access;
        this.audit = audit;
        this.organization = organization;
        this.identity = identity;
    }

    /** Who a rule is for: {@code person} with {@code id} or {@code email}, or {@code team} with {@code id}. */
    public record SubjectBody(String kind, UUID id, String email) {}

    /** A rule as the page sends it; every limit is optional, at least one is given. */
    public record RuleBody(String layer, SubjectBody subject, String description, String area, String cql,
            List<String> hidden) {}

    public record SubjectView(String kind, UUID id, String name) {}

    public record RuleView(UUID id, SubjectView subject, String description, String area, String cql, List<String> hidden,
            Instant updatedAt) {}

    public record Column(String name, boolean geometry) {}

    /**
     * The rules of a layer, with what the page needs to write one: the reference system and the extent
     * of the data (to draw an area), the columns, the teams of the organization.
     */
    @GET
    @Transactional
    public Response list(@QueryParam("layer") String name) {
        PublishedLayer layer = managed(name);
        try {
            GeoServerClient client = geoserver.client();
            String ws = geoserver.space().workspace();
            // A raster (GP-309) has no columns: its rules limit the area only.
            Map<String, String> bindings = layer.raster() ? Map.of() : client.bindings(ws, layer.name());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("layer", layer.name());
            body.put("dataset", layer.datasetId());
            body.put("kind", layer.raster() ? "raster" : "vector");
            body.put("srs", layer.definition().srs());
            body.put("bbox", (layer.raster() ? client.coverageBounds(ws, layer.name()) : client.nativeBounds(ws, layer.name()))
                    .orElse(null));
            body.put("columns", bindings.entrySet().stream().map(e -> new Column(e.getKey(), geometry(e.getValue()))).toList());
            Map<UUID, String> teams = views.teams(organization.require());
            body.put("teams", teams.entrySet().stream().map(e -> new SubjectView(DataRule.TEAM, e.getKey(), e.getValue())).toList());
            body.put("rules", views(rules.ofDataset(layer.datasetId()), teams));
            return Response.ok(body).build();
        } catch (GeoServerException | GeoServerGovernance.MissingCredentialException e) {
            return Failure.of(e, "reading the data rules of " + name).response();
        } catch (PublishedViews.Unavailable e) {
            return Failure.correct(409, "credential-missing", e.getMessage()).response();
        }
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response create(RuleBody body) {
        if (body == null) {
            return invalid("Name the layer, the subject and at least one limit.");
        }
        PublishedLayer layer = managed(body.layer());
        DataRule rule = new DataRule(organization.require(), layer.datasetId(), person(), Instant.now());
        return save(layer, rule, body, true);
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response change(@PathParam("id") UUID id, RuleBody body) {
        DataRule rule = rules.byId(id).orElseThrow(NotFoundException::new);
        PublishedLayer layer = managedDataset(rule.datasetId(), body == null ? null : body.layer());
        if (body == null) {
            return invalid("Name the subject and at least one limit.");
        }
        return save(layer, rule, body, false);
    }

    @DELETE
    @Path("{id}")
    @Transactional
    public Response delete(@PathParam("id") UUID id) {
        DataRule rule = rules.byId(id).orElseThrow(NotFoundException::new);
        sharing.require(CatalogExtension.dataset(rule.datasetId()), "manage");
        rules.delete(rule);
        record("data-rule.deleted", rule);
        RulesReport report = access.dataRulesChanged();
        return Response.ok(Map.of("geofence", report)).build();
    }

    // Checking and keeping ------------------------------------------------------------------------

    private Response save(PublishedLayer layer, DataRule rule, RuleBody body, boolean created) {
        try {
            Optional<SubjectView> subject = subject(body.subject());
            if (subject.isEmpty()) {
                return invalid("The rule is for a person of the organization (by e-mail address) or one of its teams.");
            }
            String description = body.description() == null ? "" : body.description().strip();
            if (description.length() > 500) {
                return invalid("The description has at most 500 characters.");
            }
            String area = blank(body.area()) ? null : areas.normalize(body.area().strip(), srid(layer));
            String cql = blank(body.cql()) ? null : body.cql().strip();
            if (layer.raster() && (cql != null || (body.hidden() != null && !body.hidden().isEmpty()))) {
                return invalid("A raster has no rows nor columns: its rules limit the area only.");
            }
            if (cql != null) {
                Optional<String> refused = checkCql(layer, cql);
                if (refused.isPresent()) {
                    return Failure.correct(400, "invalid-filter", refused.get()).response();
                }
            }
            List<String> hidden = body.hidden() == null ? List.of() : new ArrayList<>(new java.util.LinkedHashSet<>(body.hidden()));
            if (!hidden.isEmpty()) {
                Map<String, String> bindings = geoserver.client().bindings(geoserver.space().workspace(), layer.name());
                for (String c : hidden) {
                    if (!bindings.containsKey(c)) {
                        return invalid("The layer has no column " + c + ".");
                    }
                    if (geometry(bindings.get(c))) {
                        return invalid("The geometry cannot be hidden: limit the area instead.");
                    }
                }
            }
            if (area == null && cql == null && hidden.isEmpty()) {
                return invalid("Give at least one limit: an area, a row filter or hidden columns.");
            }
            rule.define(subject.get().kind(), subject.get().id(), description, area, cql, hidden, Instant.now());
            if (created) {
                rules.insert(rule);
            } else {
                rules.update(rule);
            }
            record(created ? "data-rule.created" : "data-rule.changed", rule);
            RulesReport report = access.dataRulesChanged();
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("rule", views(List.of(rule), views.teams(organization.require())).getFirst());
            answer.put("geofence", report);
            return Response.status(created ? 201 : 200).entity(answer).build();
        } catch (Areas.Refused e) {
            return Failure.correct(400, "invalid-area", e.getMessage()).response();
        } catch (GeoServerException | GeoServerGovernance.MissingCredentialException e) {
            return Failure.of(e, "checking a data rule of " + layer.name()).response();
        } catch (PublishedViews.Unavailable e) {
            return Failure.correct(409, "credential-missing", e.getMessage()).response();
        } catch (IllegalStateException e) {
            return Failure.correct(409, "credential-missing", e.getMessage()).response();
        }
    }

    /** Why GeoServer refuses a row filter on the layer, if it does. */
    private Optional<String> checkCql(PublishedLayer layer, String cql) {
        if (cql.length() > MAX_CQL) {
            return Optional.of("The row filter has at most " + MAX_CQL + " characters.");
        }
        if (OgcGate.readsOtherLayers(cql)) {
            return Optional.of("A row filter may not read other layers.");
        }
        String ws = geoserver.space().workspace();
        HttpResponse<String> r = geoserver.client().ogcText(ws + "/" + layer.name() + "/wfs",
                "service=WFS&version=2.0.0&request=GetFeature&resultType=hits&typeNames=" + ws + ":" + layer.name()
                        + "&cql_filter=" + URLEncoder.encode(cql, StandardCharsets.UTF_8));
        String text = r.body() == null ? "" : r.body();
        if (r.statusCode() == 200 && text.contains("numberMatched") && !text.contains("ExceptionReport")) {
            return Optional.empty();
        }
        String why = GeoServerClient.reason(text);
        return Optional.of("GeoServer does not accept the row filter" + (why == null ? "." : ": " + why));
    }

    private Optional<SubjectView> subject(SubjectBody s) {
        if (s == null || s.kind() == null) {
            return Optional.empty();
        }
        UUID org = organization.require();
        if (DataRule.TEAM.equals(s.kind())) {
            Map<UUID, String> teams = views.teams(org);
            return s.id() != null && teams.containsKey(s.id())
                    ? Optional.of(new SubjectView(DataRule.TEAM, s.id(), teams.get(s.id())))
                    : Optional.empty();
        }
        if (DataRule.PERSON.equals(s.kind())) {
            if (s.id() != null) {
                Map<UUID, String> names = views.usernames(Set.of(s.id()));
                return Optional.ofNullable(names.get(s.id()))
                        .flatMap(n -> views.member(org, n))
                        .map(id -> new SubjectView(DataRule.PERSON, id, names.get(id)));
            }
            if (!blank(s.email())) {
                String email = s.email().strip().toLowerCase(java.util.Locale.ROOT);
                return views.member(org, email).map(id -> new SubjectView(DataRule.PERSON, id, email));
            }
        }
        return Optional.empty();
    }

    private List<RuleView> views(List<DataRule> list, Map<UUID, String> teams) {
        Set<UUID> people = new HashSet<>();
        list.stream().filter(r -> DataRule.PERSON.equals(r.subjectKind())).forEach(r -> people.add(r.subjectId()));
        Map<UUID, String> names = people.isEmpty() ? Map.of() : views.usernames(people);
        return list.stream().map(r -> new RuleView(r.id(), new SubjectView(r.subjectKind(), r.subjectId(),
                DataRule.TEAM.equals(r.subjectKind()) ? teams.get(r.subjectId()) : names.get(r.subjectId())),
                r.description(), r.area(), r.cql(), r.hidden(), r.updatedAt())).toList();
    }

    /** A published layer of a dataset the person manages; 404 for a layer they do not see. */
    private PublishedLayer managed(String name) {
        if (blank(name)) {
            throw new NotFoundException();
        }
        PublishedLayer layer = geoserver.layer(name).filter(l -> l.datasetId() != null).orElseThrow(NotFoundException::new);
        sharing.require(CatalogExtension.dataset(layer.datasetId()), "manage");
        return layer;
    }

    /** A published layer of a dataset, the one named when given, which the person manages. */
    private PublishedLayer managedDataset(UUID dataset, String name) {
        ResourceRef ref = CatalogExtension.dataset(dataset);
        sharing.require(ref, "manage");
        List<PublishedLayer> of = geoserver.published().stream().filter(l -> dataset.equals(l.datasetId())).toList();
        return of.stream().filter(l -> name == null || l.name().equals(name)).findFirst()
                .or(() -> of.stream().findFirst())
                .orElseThrow(() -> new NotFoundException("The dataset of the rule is not published"));
    }

    private static int srid(PublishedLayer layer) {
        String srs = layer.definition().srs();
        return Integer.parseInt(srs.substring(srs.indexOf(':') + 1));
    }

    static boolean geometry(String binding) {
        return binding != null && (binding.contains(".geom.") || binding.endsWith("Geometry"));
    }

    private String person() {
        return identity.isAnonymous() ? null : identity.getPrincipal().getName();
    }

    private void record(String action, DataRule rule) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dataset", rule.datasetId().toString());
        details.put("subject", rule.subjectKind() + "/" + rule.subjectId());
        details.put("area", rule.area() != null);
        details.put("cql", rule.cql() == null ? "" : rule.cql());
        details.put("hidden", rule.hidden());
        audit.record(EXTENSION, new AuditLog.Entry(action, "data-rule/" + rule.id(), AuditLog.Outcome.SUCCEEDED, details));
    }

    private static Response invalid(String message) {
        return Failure.correct(400, "invalid", message).response();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
