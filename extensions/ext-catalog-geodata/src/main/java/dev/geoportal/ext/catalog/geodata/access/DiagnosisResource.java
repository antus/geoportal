// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.access;

import dev.geoportal.ext.catalog.geodata.CatalogExtension;
import dev.geoportal.ext.catalog.geodata.geoserver.Failure;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerClient;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerException;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerGovernance;
import dev.geoportal.ext.catalog.geodata.geoserver.PublishedLayer;
import dev.geoportal.ext.catalog.geodata.geoserver.RulesReport;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import dev.mosaikit.kernel.api.resource.ResourceRef;
import dev.mosaikit.kernel.api.sharing.Sharing;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Why a person cannot see the data of a layer (GP-339), under /api/v1/ext/catalog-geodata/access-diagnosis: for
 * a person and a published layer, what GeoFence decides for them on the map and on the data, read from the
 * rule set the catalog writes ({@link Evaluation}), what WFS answers them ({@link Preview}), and the finding
 * ({@link Diagnosis}): the grant that is missing, or the data rules that hide the data, each with what it
 * hides by itself. Those who manage the dataset of the layer ask it; the rules are brought up to date first,
 * so that the answer is the one GeoServer gives.
 */
@Path("/api/v1/ext/catalog-geodata/access-diagnosis")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class DiagnosisResource {

    private final GeoServerGovernance geoserver;
    private final AccessRules access;
    private final DataRules rules;
    private final Sharing sharing;
    private final PublishedViews views;
    private final CurrentOrganization organization;

    public DiagnosisResource(GeoServerGovernance geoserver, AccessRules access, DataRules rules, Sharing sharing,
            PublishedViews views, CurrentOrganization organization) {
        this.geoserver = geoserver;
        this.access = access;
        this.rules = rules;
        this.sharing = sharing;
        this.views = views;
        this.organization = organization;
    }

    public record LayerChoice(String name, String title, String kind) {}

    public record PersonChoice(String email, String name) {}

    /** What the page offers to choose: the published layers the person manages, and the members of the organization. */
    @GET
    @Path("choices")
    @Transactional
    public Response choices() {
        List<LayerChoice> layers = geoserver.published().stream()
                .filter(l -> l.datasetId() != null && sharing.allows(CatalogExtension.dataset(l.datasetId()), "manage"))
                .map(l -> new LayerChoice(l.name(), l.definition().title(), l.raster() ? "raster" : "vector")).toList();
        if (layers.isEmpty()) {
            return Response.ok(Map.of("layers", layers, "people", List.of())).build();
        }
        try {
            List<PersonChoice> people = views.members().stream()
                    .map(m -> new PersonChoice(m.username(), m.displayName() == null ? m.username() : m.displayName())).toList();
            return Response.ok(Map.of("layers", layers, "people", people)).build();
        } catch (PublishedViews.Unavailable e) {
            return Failure.correct(409, "credential-missing", e.getMessage()).response();
        }
    }

    /** One data rule that applies to the person, and what it hides by itself. */
    public record RuleFinding(UUID id, String via, String subject, String description, boolean area, String cql,
            List<String> hidden, Long alone, boolean hides) {}

    public record ServiceDecision(String service, boolean allowed, String cause) {}

    @GET
    @Transactional
    public Response diagnose(@QueryParam("layer") String name, @QueryParam("person") String person) {
        if (name == null || name.isBlank()) {
            throw new NotFoundException();
        }
        PublishedLayer layer = geoserver.layer(name).filter(l -> l.datasetId() != null).orElseThrow(NotFoundException::new);
        ResourceRef ref = CatalogExtension.dataset(layer.datasetId());
        sharing.require(ref, "manage");
        if (person == null || person.isBlank()) {
            return Failure.correct(400, "invalid", "Name the person by e-mail address.").response();
        }
        String email = person.strip().toLowerCase(Locale.ROOT);
        UUID org = organization.require();
        try {
            // The rules GeoFence holds are those generated now, as when people use the publications.
            access.ensureCurrent();
            Optional<UUID> account = views.member(org, email);
            Set<String> roles = account.isEmpty() ? Set.of() : views.roles(org, email);
            String ws = geoserver.space().workspace();
            List<GeoFenceRule> set = access.generate().rules();
            String dataService = layer.raster() ? "WCS" : "WFS";
            Evaluation.Decision map = Evaluation.of(set, email, roles, ws, layer.name(), "WMS");
            Evaluation.Decision data = Evaluation.of(set, email, roles, ws, layer.name(), dataService);
            GeoServerClient client = geoserver.client();
            Map<String, String> bindings = layer.raster() ? Map.of() : client.bindings(ws, layer.name());
            Preview.Answer answer = !layer.raster() && data.allowed()
                    ? Preview.of(client, ws, layer.name(), bindings, Preview.Limits.of(data))
                    : null;
            long total = answer != null ? answer.total() : 0;
            Diagnosis.Finding finding = Diagnosis.of(account.isPresent(), layer.raster(), map, data,
                    answer == null ? 0 : answer.matched(), total);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("layer", layer.name());
            body.put("title", layer.definition().title());
            body.put("kind", layer.raster() ? "raster" : "vector");
            body.put("person", email);
            body.put("member", account.isPresent());
            body.put("administrator", roles.contains(RuleSet.administratorsRole(org)));
            body.put("level", account.flatMap(a -> sharing.level(a, ref)).orElse(null));
            body.put("verdict", code(finding.verdict().name()));
            body.put("missing", finding.missing());
            body.put("services", List.of(decision("WMS", map), decision(dataService, data)));
            body.put("answer", answer);
            body.put("rules", account.isEmpty() ? List.of()
                    : findings(layer, account.get(), roles, answer, bindings, ws, client, map.cause() == Evaluation.Cause.DATA_RULES
                            || data.cause() == Evaluation.Cause.DATA_RULES
                            || map.cause() == Evaluation.Cause.DATA_RULES_DENY
                            || data.cause() == Evaluation.Cause.DATA_RULES_DENY));
            RulesReport last = access.last();
            body.put("geofence", last.outcome());
            return Response.ok(body).build();
        } catch (GeoServerException | GeoServerGovernance.MissingCredentialException e) {
            return Failure.of(e, "diagnosing the access to " + name).response();
        } catch (PublishedViews.Unavailable e) {
            return Failure.correct(409, "credential-missing", e.getMessage()).response();
        }
    }

    /**
     * The data rules of the layer that apply to the person, directly or through one of their teams; when they
     * are in force (the person reaches the layer by a grant, so their block of user rules is written), what
     * each hides by itself: the features it leaves of the layer, and its hidden columns.
     */
    private List<RuleFinding> findings(PublishedLayer layer, UUID account, Set<String> roles, Preview.Answer answer,
            Map<String, String> bindings, String ws, GeoServerClient client, boolean inForce) {
        Map<UUID, String> teams = views.teams(organization.require());
        List<RuleFinding> out = new ArrayList<>();
        for (DataRule r : rules.ofDataset(layer.datasetId())) {
            String via;
            String subject;
            if (DataRule.TEAM.equals(r.subjectKind())) {
                if (!roles.contains(RuleSet.teamRole(r.subjectId()))) {
                    continue;
                }
                via = DataRule.TEAM;
                subject = teams.getOrDefault(r.subjectId(), r.subjectId().toString());
            } else {
                if (!r.subjectId().equals(account)) {
                    continue;
                }
                via = DataRule.PERSON;
                subject = null;
            }
            Long alone = null;
            boolean hides = inForce && (r.area() != null || r.cql() != null || !r.hidden().isEmpty());
            if (inForce && answer != null && (r.area() != null || r.cql() != null)) {
                alone = Preview.count(client, ws, layer.name(), bindings,
                        new Preview.Limits(r.area() == null ? List.of() : List.of(r.area()), r.cql(), Set.of()));
                hides = alone < answer.total() || !r.hidden().isEmpty();
            }
            out.add(new RuleFinding(r.id(), via, subject, r.description(), r.area() != null, r.cql(), r.hidden(), alone,
                    hides));
        }
        return out;
    }

    private static ServiceDecision decision(String service, Evaluation.Decision d) {
        return new ServiceDecision(service, d.allowed(), code(d.cause().name()));
    }

    private static String code(String name) {
        return name.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
