// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog;

import static dev.geoportal.catalog.CatalogExtension.ID;

import dev.geoportal.catalog.access.PublishedViews;
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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Data rules on external services (GP-309), under /api/v1/ext/catalog/service-rules: who manages a service
 * reads, adds and removes them; each person reads what they may not use of a service, and why. A rule on
 * a layer of an external service is never a filter, since the server is not Geoportal's: it makes the
 * layer unavailable to its subject, and the proxy refuses it ({@link ServiceRules}).
 */
@Path("/api/v1/ext/catalog/service-rules")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class ServiceRuleResource {

    /** What makes a rule on an external service: no filter, the layer unavailable. */
    public static final String EFFECT = "unavailable";

    private final ServiceDataRules rules;
    private final ExternalServices services;
    private final ServiceRules check;
    private final PublishedViews views;
    private final Sharing sharing;
    private final AuditLog audit;
    private final CurrentOrganization organization;
    private final SecurityIdentity identity;

    public ServiceRuleResource(ServiceDataRules rules, ExternalServices services, ServiceRules check,
            PublishedViews views, Sharing sharing, AuditLog audit, CurrentOrganization organization,
            SecurityIdentity identity) {
        this.rules = rules;
        this.services = services;
        this.check = check;
        this.views = views;
        this.sharing = sharing;
        this.audit = audit;
        this.organization = organization;
        this.identity = identity;
    }

    public record SubjectBody(String kind, UUID id, String email) {}

    public record RuleBody(UUID service, String layer, SubjectBody subject, String description, String area, String cql,
            List<String> hidden) {}

    public record SubjectView(String kind, UUID id, String name) {}

    /**
     * A rule, with what it does here.
     *
     * @param effect always {@value #EFFECT}: the server cannot apply the limits
     * @param message why the layer is unavailable to the subject
     */
    public record RuleView(UUID id, UUID service, String layer, SubjectView subject, String description, String area,
            String cql, List<String> hidden, String effect, String message, Instant createdAt) {}

    /** What a person may not use of a service, each with why. */
    public record Unavailable(String layer, String message) {}

    /** The rules of a service (level manage on it). */
    @GET
    @Transactional
    public List<RuleView> list(@QueryParam("service") UUID service) {
        managed(service);
        return views(rules.ofService(service));
    }

    /** What the person may not use of a service (level view on it), and why. */
    @GET
    @Path("mine")
    @Transactional
    public List<Unavailable> mine(@QueryParam("service") UUID service) {
        if (service == null) {
            throw new NotFoundException();
        }
        services.byId(service).orElseThrow(NotFoundException::new);
        sharing.require(CatalogExtension.service(service), "view");
        ServiceRules.Unavailable u = check.unavailable(service);
        List<Unavailable> out = new ArrayList<>();
        if (u.whole()) {
            out.add(new Unavailable(null, ServiceRules.message(null)));
        }
        u.layers().forEach(l -> out.add(new Unavailable(l, ServiceRules.message(l))));
        return out;
    }

    /**
     * Adds a rule on a layer of a service, or on the whole service without {@code layer}: at least one limit
     * (an area, a row filter, hidden columns), for a person (by e-mail address or account) or a team of the
     * organization. The answer says that the layer is now unavailable to the subject, and why.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response create(RuleBody body) {
        if (body == null || body.service() == null) {
            return problem(400, "invalid", "Name the service, the subject and at least one limit.");
        }
        managed(body.service());
        String layer = body.layer() == null || body.layer().isBlank() ? null : body.layer().strip();
        if (layer != null && (layer.length() > 255 || !layer.matches("[\\p{L}\\p{N}_.:\\-]+"))) {
            return problem(400, "invalid", "The layer is named as the service names it: letters, digits, _ . : -.");
        }
        String area = blank(body.area()) ? null : body.area().strip();
        String cql = blank(body.cql()) ? null : body.cql().strip();
        List<String> hidden = body.hidden() == null ? List.of() : body.hidden().stream().filter(h -> !blank(h)).toList();
        if (area == null && cql == null && hidden.isEmpty()) {
            return problem(400, "invalid", "Give at least one limit: an area, a row filter or hidden columns.");
        }
        if ((area != null && area.length() > 100_000) || (cql != null && cql.length() > 2000)) {
            return problem(400, "invalid", "The area or the row filter is too long.");
        }
        String description = body.description() == null ? "" : body.description().strip();
        if (description.length() > 500) {
            return problem(400, "invalid", "The description has at most 500 characters.");
        }
        Optional<SubjectView> subject;
        try {
            subject = subject(body.subject());
        } catch (PublishedViews.Unavailable e) {
            return problem(409, "credential-missing", e.getMessage());
        }
        if (subject.isEmpty()) {
            return problem(400, "invalid", "The rule is for a person of the organization (by e-mail address) or one of its teams.");
        }
        ServiceDataRule rule = new ServiceDataRule(organization.require(), body.service(), layer, subject.get().kind(),
                subject.get().id(), description, area, cql, hidden, me(), Instant.now());
        rules.insert(rule);
        check.changed();
        record("service-rule.created", rule);
        return Response.status(201).entity(views(List.of(rule)).getFirst()).build();
    }

    @DELETE
    @Path("{id}")
    @Transactional
    public Response delete(@PathParam("id") UUID id) {
        ServiceDataRule rule = rules.byId(id).orElseThrow(NotFoundException::new);
        managed(rule.serviceId());
        rules.delete(rule);
        check.changed();
        record("service-rule.deleted", rule);
        return Response.noContent().build();
    }

    // Plumbing -----------------------------------------------------------------------------------

    private void managed(UUID service) {
        if (service == null) {
            throw new NotFoundException();
        }
        services.byId(service).orElseThrow(NotFoundException::new);
        ResourceRef ref = CatalogExtension.service(service);
        sharing.require(ref, "view");
        sharing.require(ref, "manage");
    }

    private Optional<SubjectView> subject(SubjectBody s) {
        if (s == null || s.kind() == null) {
            return Optional.empty();
        }
        UUID org = organization.require();
        if (ServiceDataRule.TEAM.equals(s.kind())) {
            Map<UUID, String> teams = views.teams(org);
            return s.id() != null && teams.containsKey(s.id())
                    ? Optional.of(new SubjectView(ServiceDataRule.TEAM, s.id(), teams.get(s.id())))
                    : Optional.empty();
        }
        if (ServiceDataRule.PERSON.equals(s.kind())) {
            if (s.id() != null) {
                Map<UUID, String> names = views.usernames(Set.of(s.id()));
                return Optional.ofNullable(names.get(s.id())).flatMap(n -> views.member(org, n))
                        .map(id -> new SubjectView(ServiceDataRule.PERSON, id, names.get(id)));
            }
            if (!blank(s.email())) {
                String email = s.email().strip().toLowerCase(Locale.ROOT);
                return views.member(org, email).map(id -> new SubjectView(ServiceDataRule.PERSON, id, email));
            }
        }
        return Optional.empty();
    }

    private List<RuleView> views(List<ServiceDataRule> list) {
        Set<UUID> people = new HashSet<>();
        list.stream().filter(r -> ServiceDataRule.PERSON.equals(r.subjectKind())).forEach(r -> people.add(r.subjectId()));
        Map<UUID, String> names = Map.of();
        Map<UUID, String> teams = Map.of();
        try {
            names = people.isEmpty() ? Map.of() : views.usernames(people);
            teams = list.stream().anyMatch(r -> ServiceDataRule.TEAM.equals(r.subjectKind()))
                    ? views.teams(organization.require()) : Map.of();
        } catch (PublishedViews.Unavailable e) {
            // the subjects are shown by identifier
        }
        Map<UUID, String> n = names;
        Map<UUID, String> t = teams;
        return list.stream().map(r -> new RuleView(r.id(), r.serviceId(), r.layer(),
                new SubjectView(r.subjectKind(), r.subjectId(),
                        ServiceDataRule.TEAM.equals(r.subjectKind()) ? t.get(r.subjectId()) : n.get(r.subjectId())),
                r.description(), r.area(), r.cql(), r.hidden(), EFFECT, ServiceRules.message(r.layer()), r.createdAt()))
                .toList();
    }

    private String me() {
        return identity.isAnonymous() ? null : identity.getPrincipal().getName();
    }

    private void record(String action, ServiceDataRule rule) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("service", rule.serviceId().toString());
        details.put("layer", rule.layer() == null ? "" : rule.layer());
        details.put("subject", rule.subjectKind() + "/" + rule.subjectId());
        details.put("effect", EFFECT);
        audit.record(ID, new AuditLog.Entry(action, "service/" + rule.serviceId(), AuditLog.Outcome.SUCCEEDED, details));
    }

    private static Response problem(int status, String code, String detail) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("title", status == 409 ? "Conflict" : "Bad Request");
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("code", code);
        return Response.status(status).type("application/problem+json").entity(problem).build();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
