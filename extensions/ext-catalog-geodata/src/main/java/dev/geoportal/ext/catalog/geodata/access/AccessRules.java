// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.access;

import static dev.geoportal.ext.catalog.geodata.geoserver.GeoServerGovernance.EXTENSION;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.ext.catalog.geodata.CatalogExtension;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.Kind;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.LayerAccess;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.Limited;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.Limits;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.Reach;
import dev.geoportal.ext.catalog.geodata.editing.LayerAssignment;
import dev.geoportal.ext.catalog.geodata.editing.LayerAssignments;
import dev.geoportal.ext.catalog.geodata.geoserver.Aligned;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerException;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerGovernance;
import dev.geoportal.ext.catalog.geodata.geoserver.OrganizationSpace;
import dev.geoportal.ext.catalog.geodata.geoserver.PublishedLayer;
import dev.geoportal.ext.catalog.geodata.geoserver.RulesReport;
import dev.mosaikit.kernel.api.audit.AuditLog;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import dev.mosaikit.kernel.api.resource.Resource;
import dev.mosaikit.kernel.api.resource.ResourceRef;
import dev.mosaikit.kernel.api.resource.Resources;
import dev.mosaikit.kernel.api.sharing.Grant;
import dev.mosaikit.kernel.api.sharing.Sharing;
import dev.mosaikit.kernel.api.sharing.Subject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * The GeoFence rules of the organization of the request (GP-335, ADR-0021): generated from the sharing
 * of the dataset of each published layer in Mosaikit, written into GeoFence as a whole, read back and
 * compared, with the outcome kept for the reconciliation report and written to the audit log.
 *
 * <p>When the rules are written again:
 *
 * <ul>
 *   <li>after every alignment of GeoServer, which every publish, republish, repoint and unpublish makes,
 *       and the reconciliation with {@code POST};
 *   <li>when the generated set differs from the one last written, checked at most every
 *       {@link #CHECK_EVERY} per organization when people list or use the publications: a dataset shared
 *       or unshared in the dialog of Mosaikit, or a person whose account changed. Mosaikit does not tell
 *       extensions about grants yet (a platform need), so this is when a share becomes a rule;
 *   <li>never because a team changed: rules name teams by role, and GeoServer reads the members of a
 *       role from Mosaikit within its cache (ADR-0016).
 * </ul>
 *
 * <p>A failure never fails the action that caused the write: GeoFence keeps denying what has no rule,
 * and the failure is reported.
 */
@ApplicationScoped
public class AccessRules {

    private static final Logger LOG = Logger.getLogger(AccessRules.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> LINES = new TypeReference<>() {};

    /** How often a request checks that the generated set is the one written. */
    static final Duration CHECK_EVERY = Duration.ofSeconds(15);

    private final GeoServerGovernance geoserver;
    private final Sharing sharing;
    private final Resources resources;
    private final PublishedViews views;
    private final RuleSetStates states;
    private final AuditLog audit;
    private final CurrentOrganization organization;
    private final DataRules dataRules;
    private final Areas areas;
    private final LayerAssignments assignments;
    private final Map<UUID, Instant> checked = new ConcurrentHashMap<>();
    private final Map<UUID, Limitations> limitedPeople = new ConcurrentHashMap<>();

    public AccessRules(GeoServerGovernance geoserver, Sharing sharing, Resources resources, PublishedViews views,
            RuleSetStates states, AuditLog audit, CurrentOrganization organization, DataRules dataRules, Areas areas,
            LayerAssignments assignments) {
        this.dataRules = dataRules;
        this.areas = areas;
        this.assignments = assignments;
        this.geoserver = geoserver;
        this.sharing = sharing;
        this.resources = resources;
        this.views = views;
        this.states = states;
        this.audit = audit;
        this.organization = organization;
    }

    /** The generated rules, and what they could not express. */
    public record Generated(List<GeoFenceRule> rules, List<String> warnings) {}

    // Generating ----------------------------------------------------------------------------------

    /** The rule set of the organization of the request, from the published layers, their sharing and the data rules. */
    public Generated generate() {
        return generate(java.util.function.UnaryOperator.identity(), true);
    }

    /**
     * The rule set the organization would have with a data rule written or changed and not saved yet (GP-339):
     * for the preview of the rule, never written nor kept.
     *
     * @param draft the rule as the page sends it, not saved
     * @param replaces the rule it changes, or null for a new one
     */
    public Generated withDraft(DataRule draft, UUID replaces) {
        return generate(all -> {
            List<DataRule> list = new ArrayList<>(all.stream().filter(r -> !r.id().equals(replaces)).toList());
            list.add(draft);
            return list;
        }, false);
    }

    /**
     * The rule set from the data rules as {@code adjust} gives them.
     *
     * @param remember whether who data rules limit is kept for the gate ({@link #limitsOf}): only for the rules
     *     that are saved
     */
    private Generated generate(java.util.function.UnaryOperator<List<DataRule>> adjust, boolean remember) {
        OrganizationSpace space = geoserver.space();
        List<String> warnings = new ArrayList<>();
        List<PublishedLayer> layers = geoserver.published();
        Map<UUID, List<Reach>> reaches = new HashMap<>();
        Map<UUID, List<Grant>> grants = new HashMap<>();
        Map<UUID, Set<UUID>> owners = new HashMap<>();
        Set<UUID> people = new HashSet<>();
        for (PublishedLayer layer : layers) {
            UUID dataset = layer.datasetId();
            if (dataset == null || grants.containsKey(dataset)) {
                continue;
            }
            ResourceRef ref = CatalogExtension.dataset(dataset);
            // GP-983, ADR-0028: a dataset made from a file of the catalog inherits the levels on the file and
            // on the folders above it (MK-136), and their owners manage it as the owner of the dataset does.
            List<Grant> of = new ArrayList<>(sharing.grants(ref));
            of.addAll(sharing.inheritedGrants(ref));
            grants.put(dataset, of);
            Set<UUID> managing = new HashSet<>();
            above(ref).forEach(r -> {
                if (r.owner() != null) {
                    managing.add(r.owner());
                }
            });
            owners.put(dataset, managing);
            people.addAll(managing);
            of.stream().map(Grant::subject).filter(s -> s instanceof Subject.Person)
                    .forEach(s -> people.add(((Subject.Person) s).account()));
        }
        // GP-308: the people the data rules apply to, directly or through a team.
        Map<UUID, List<DataRule>> rules = new HashMap<>();
        for (DataRule r : adjust.apply(dataRules.all())) {
            if (grants.containsKey(r.datasetId())) {
                rules.computeIfAbsent(r.datasetId(), k -> new ArrayList<>()).add(r);
            }
        }
        Map<UUID, Set<UUID>> members = Map.of();
        String unnamed = null;
        if (!rules.isEmpty()) {
            Set<UUID> teams = new HashSet<>();
            for (List<DataRule> list : rules.values()) {
                for (DataRule r : list) {
                    if (DataRule.TEAM.equals(r.subjectKind())) {
                        teams.add(r.subjectId());
                    } else {
                        people.add(r.subjectId());
                    }
                }
            }
            try {
                members = views.teamMembers(teams);
                members.values().forEach(people::addAll);
            } catch (PublishedViews.Unavailable e) {
                unnamed = e.getMessage();
            }
        }
        // The surveyors of each layer who hold the permission to edit its data (GP-036).
        Map<String, List<UUID>> surveyors = new HashMap<>();
        Set<String> publishedNames = new HashSet<>();
        layers.forEach(l -> publishedNames.add(l.name()));
        for (LayerAssignment a : assignments.all()) {
            if (publishedNames.contains(a.layer()) && a.confirmed()) {
                surveyors.computeIfAbsent(a.layer(), k -> new ArrayList<>()).add(a.account());
                people.add(a.account());
            }
        }
        Map<UUID, String> names = names(people, warnings);
        if (unnamed == null && !people.isEmpty() && names.isEmpty()) {
            unnamed = "the published views of Mosaikit cannot be read";
        }
        for (Map.Entry<UUID, List<Grant>> e : grants.entrySet()) {
            List<Reach> list = new ArrayList<>();
            for (UUID owner : owners.getOrDefault(e.getKey(), Set.of())) {
                if (names.containsKey(owner)) {
                    list.add(new Reach(Kind.PERSON, names.get(owner), RuleSet.LEVELS.getLast()));
                }
            }
            for (Grant g : e.getValue()) {
                Reach r = reach(g, space.organization(), names);
                if (r != null) {
                    list.add(r);
                }
            }
            reaches.put(e.getKey(), list);
        }
        List<LayerAccess> access = new ArrayList<>();
        Map<String, Map<String, Limits>> limitedNames = new HashMap<>();
        for (PublishedLayer layer : layers) {
            UUID dataset = layer.datasetId();
            List<Reach> of = dataset == null ? List.of() : reaches.getOrDefault(dataset, List.of());
            List<DataRule> onLayer = dataset == null ? List.of() : rules.getOrDefault(dataset, List.of());
            List<Limited> limited = List.of();
            if (!onLayer.isEmpty()) {
                try {
                    if (unnamed != null) {
                        throw new IllegalStateException(unnamed);
                    }
                    limited = limited(space, layer, onLayer, members, names);
                } catch (RuntimeException ex) {
                    // Fail closed: the people the rules limit cannot be told apart from the others, so only
                    // the administrators keep the layer until the rules can be written.
                    warnings.add("The layer " + layer.name() + " is closed to everyone but the administrators: its data"
                            + " rules cannot be applied (" + ex.getMessage() + ").");
                    of = List.of();
                }
            }
            Map<String, Limits> limitedHere = new HashMap<>();
            limited.forEach(l -> limitedHere.put(l.user().toLowerCase(Locale.ROOT), l.limits()));
            limitedNames.put(layer.name(), limitedHere);
            List<String> editing = surveyors.getOrDefault(layer.name(), List.of()).stream()
                    .filter(names::containsKey).map(names::get).toList();
            LayerAccess one = new LayerAccess(layer.name(), of, limited, editing);
            if (RuleSet.editing(one).size() + RuleSet.editingWithin(one).size() < editing.size()) {
                warnings.add("A surveyor limited by data rules on " + layer.name() + " does not edit it: their rules"
                        + " hide columns or leave nothing visible, which GeoServer cannot keep writes within.");
            }
            access.add(one);
        }
        if (remember) {
            limitedPeople.put(space.organization(), new Limitations(limitedNames, Instant.now()));
        }
        return new Generated(clipRasters(RuleSet.of(space.workspace(), space.organization(), access), layers), warnings);
    }

    /**
     * The areas of data rules on rasters cut the image (GP-309): their {@code LIMIT} rules clip, while those
     * of vectors keep the features that intersect the area.
     */
    public static List<GeoFenceRule> clipRasters(List<GeoFenceRule> rules, List<PublishedLayer> layers) {
        Set<String> rasters = new HashSet<>();
        layers.stream().filter(PublishedLayer::raster).forEach(l -> rasters.add(l.name()));
        if (rasters.isEmpty()) {
            return rules;
        }
        return rules.stream().map(r -> r.layer() != null && rasters.contains(r.layer()) ? r.clipped() : r).toList();
    }

    /**
     * The people limited by data rules on a layer (GP-308): for each person the rules reach, directly or
     * through a team, what all of them allow together, at the level the person has on the dataset.
     */
    private List<Limited> limited(OrganizationSpace space, PublishedLayer layer, List<DataRule> rules,
            Map<UUID, Set<UUID>> members, Map<UUID, String> names) {
        Map<UUID, List<DataRule>> byPerson = new HashMap<>();
        for (DataRule r : rules) {
            Set<UUID> reached = DataRule.TEAM.equals(r.subjectKind())
                    ? members.getOrDefault(r.subjectId(), Set.of())
                    : Set.of(r.subjectId());
            reached.forEach(p -> byPerson.computeIfAbsent(p, k -> new ArrayList<>()).add(r));
        }
        if (byPerson.isEmpty()) {
            return List.of();
        }
        // A raster has no columns, and its rules only an area (GP-309).
        List<String> columns = layer.raster() ? List.of() : geoserver.client().attributes(space.workspace(), layer.name());
        ResourceRef ref = CatalogExtension.dataset(layer.datasetId());
        List<Limited> limited = new ArrayList<>();
        for (Map.Entry<UUID, List<DataRule>> e : byPerson.entrySet()) {
            String user = names.get(e.getKey());
            if (user == null) {
                continue; // no active account: no grant reaches them either
            }
            Composed c = compose(e.getValue());
            String area = null;
            boolean empty = false;
            if (!c.areas().isEmpty()) {
                Optional<String> common = areas.intersection(c.areas());
                empty = common.isEmpty();
                area = common.orElse(null);
            }
            String level = sharing.level(e.getKey(), ref).orElse(null);
            limited.add(new Limited(user, level, layer.raster()
                    ? new RuleSet.Limits(area, empty, null, columns, Set.of())
                    : new RuleSet.Limits(area, empty, c.cql(), columns, c.hidden())));
        }
        return limited;
    }

    /**
     * The limits of the data rules that apply to one person on one dataset, composed so that the answer
     * holds only what all of them allow (GP-308): every area (they intersect), every row filter joined
     * with {@code AND}, every hidden column.
     */
    public record Composed(List<String> areas, String cql, Set<String> hidden) {}

    public static Composed compose(List<DataRule> rules) {
        Set<String> areas = new TreeSet<>();
        Set<String> filters = new LinkedHashSet<>();
        Set<String> hidden = new TreeSet<>();
        for (DataRule r : rules) {
            if (r.area() != null) {
                areas.add(r.area());
            }
            if (r.cql() != null && !r.cql().isBlank()) {
                filters.add(r.cql().strip());
            }
            hidden.addAll(r.hidden());
        }
        String cql = filters.isEmpty() ? null
                : filters.size() == 1 ? filters.iterator().next()
                : "(" + String.join(") AND (", filters) + ")";
        return new Composed(List.copyOf(areas), cql, hidden);
    }

    /** The data rules changed (GP-308): the rules are written again now. */
    public RulesReport dataRulesChanged() {
        limitedPeople.remove(organization.require());
        return rewrite("data-rule");
    }

    /** Who data rules limit on each layer and how (user names in lower case), from the last generation, for the gate (GP-308). */
    private record Limitations(Map<String, Map<String, Limits>> byLayer, Instant at) {}

    /**
     * Whether data rules limit a person on a layer. The gate of the catalog reaches GeoServer as its
     * administrator, so it cannot apply them, and refuses those people (deny where the rules cannot apply,
     * GP-309). From the last generation of the rules, at most {@link #CHECK_EVERY} old.
     */
    public boolean limits(String layer, String user) {
        return limitsOf(layer, user).isPresent();
    }

    /**
     * The limits of the data rules on a person on a layer, composed, or empty when none limits them; from the
     * last generation of the rules, at most {@link #CHECK_EVERY} old.
     */
    public Optional<Limits> limitsOf(String layer, String user) {
        if (user == null) {
            return Optional.empty();
        }
        UUID org = organization.require();
        Limitations l = limitedPeople.get(org);
        if (l == null || l.at().plus(CHECK_EVERY).isBefore(Instant.now())) {
            generate();
            l = limitedPeople.get(org);
        }
        return Optional.ofNullable(l.byLayer().getOrDefault(layer, Map.of()).get(user.toLowerCase(Locale.ROOT)));
    }

    private Map<UUID, String> names(Set<UUID> people, List<String> warnings) {
        if (people.isEmpty()) {
            return Map.of();
        }
        try {
            Map<UUID, String> names = views.usernames(people);
            int missing = people.size() - names.size();
            if (missing > 0) {
                warnings.add(missing + (missing == 1 ? " person with a grant has" : " people with a grant have")
                        + " no active account: no rule for them.");
            }
            return names;
        } catch (PublishedViews.Unavailable e) {
            warnings.add("No rule for the " + people.size() + (people.size() == 1 ? " person" : " people")
                    + " reached by name (owners and grants to people): " + e.getMessage());
            return Map.of();
        }
    }

    private static Reach reach(Grant g, UUID organization, Map<UUID, String> names) {
        return switch (g.subject()) {
            case Subject.Person p -> names.containsKey(p.account()) ? new Reach(Kind.PERSON, names.get(p.account()), g.level()) : null;
            case Subject.Group t -> new Reach(Kind.TEAM, RuleSet.teamRole(t.team()), g.level());
            case Subject.Organization o -> new Reach(Kind.ORGANIZATION, RuleSet.organizationRole(organization), g.level());
            case Subject.Public p -> new Reach(Kind.PUBLIC, null, g.level());
            // A link opens the resource in Mosaikit; GeoServer has no such subject (GP-038).
            case Subject.Link l -> null;
        };
    }

    // Writing -------------------------------------------------------------------------------------

    /**
     * A resource and the resources above it, through their parents (MK-054): for a dataset made from a file
     * of the catalog, the file and its folders. Stops at a resource the registry does not answer for.
     */
    private List<Resource> above(ResourceRef ref) {
        List<Resource> found = new ArrayList<>();
        Set<ResourceRef> seen = new HashSet<>();
        ResourceRef next = ref;
        while (next != null && seen.add(next) && found.size() < 64) {
            Optional<Resource> resource;
            try {
                resource = resources.find(next);
            } catch (RuntimeException e) {
                break;
            }
            if (resource.isEmpty()) {
                break;
            }
            found.add(resource.get());
            next = resource.get().parent();
        }
        return found;
    }

    /** Every alignment applied is followed by the rules. */
    void aligned(@Observes Aligned event) {
        try {
            rewrite("alignment");
        } catch (RuntimeException e) {
            // Never the cause of a failed publication: the rules are written again at the next check.
            LOG.warnf("The GeoFence rules of %s are written at the next check: %s", event.space().workspace(), e.toString());
        }
    }

    /**
     * Writes the rule set of the organization into GeoFence now, reads it back and compares; never
     * throws: the outcome is in the report, in the audit log and in the state of the organization.
     */
    public RulesReport rewrite(String reason) {
        UUID org = organization.require();
        String workspace = geoserver.space().workspace();
        RuleSetState state = state(org);
        Instant now = Instant.now();
        Generated generated;
        try {
            generated = generate();
        } catch (RuntimeException e) {
            return failed(state, workspace, reason, "The rules cannot be generated: " + e.getMessage(), List.of(), now);
        }
        List<String> canonical = GeoFenceRule.canonical(generated.rules());
        try {
            RuleWriter writer = new RuleWriter(geoserver.client());
            RuleWriter.Outcome o = writer.rewrite(workspace, generated.rules(), new HashSet<>(read(state.rules())),
                    state.basePriority());
            String outcome = !o.mismatches().isEmpty() ? "mismatch" : o.written() ? "written" : "unchanged";
            // A mismatch keeps the fingerprint: writing the same set again would find the same, and the
            // reconciliation reports it until a person looks.
            state.written(fingerprint(canonical), json(canonical), o.base(), outcome,
                    json(o.removed()), json(o.mismatches()), json(generated.warnings()), now, o.written());
            states.update(state);
            checked.put(org, now);
            if (o.written() || !o.mismatches().isEmpty()) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("reason", reason);
                details.put("rules", o.rules());
                details.put("removed", first(o.removed()));
                details.put("mismatches", first(o.mismatches()));
                details.put("warnings", first(generated.warnings()));
                audit.record(EXTENSION, new AuditLog.Entry("geofence.rewritten", "workspace/" + workspace,
                        o.mismatches().isEmpty() ? AuditLog.Outcome.SUCCEEDED : AuditLog.Outcome.FAILED, details));
            }
            if (!o.mismatches().isEmpty()) {
                LOG.warnf("GeoFence holds other rules than those written for %s: %s", workspace, o.mismatches());
            }
            return report(state, workspace, generated.rules().size());
        } catch (GeoServerException | GeoServerGovernance.MissingCredentialException e) {
            return failed(state, workspace, reason, e.getMessage(), generated.warnings(), now);
        } catch (RuntimeException e) {
            return failed(state, workspace, reason, e.toString(), generated.warnings(), now);
        }
    }

    /**
     * Compares what GeoFence holds with the rules generated now, without writing: the reconciliation
     * report without {@code apply}.
     */
    public RulesReport verify() {
        UUID org = organization.require();
        String workspace = geoserver.space().workspace();
        RuleSetState state = state(org);
        try {
            Generated generated = generate();
            List<String> mismatches = new RuleWriter(geoserver.client()).verify(workspace, generated.rules());
            return new RulesReport(workspace, mismatches.isEmpty() ? "matching" : "mismatch", generated.rules().size(),
                    state.writtenAt(), read(state.removed()), mismatches, generated.warnings(), null);
        } catch (RuntimeException e) {
            return new RulesReport(workspace, "failed", 0, state.writtenAt(), List.of(), List.of(), List.of(),
                    e.getMessage());
        }
    }

    /** What the last write found, as the reconciliation reports it after aligning. */
    public RulesReport last() {
        UUID org = organization.require();
        RuleSetState state = state(org);
        return report(state, geoserver.space().workspace(), read(state.rules()).size());
    }

    /**
     * Writes the rules again when the set generated now differs from the one last written, at most every
     * {@link #CHECK_EVERY} per organization: how a share or unshare in the dialog of Mosaikit reaches
     * GeoFence. Never throws.
     */
    public void ensureCurrent() {
        UUID org = organization.require();
        Instant now = Instant.now();
        Instant last = checked.get(org);
        if (last != null && last.plus(CHECK_EVERY).isAfter(now)) {
            return;
        }
        checked.put(org, now);
        try {
            if (geoserver.published().isEmpty() && states.mine().isEmpty()) {
                return;
            }
            String fingerprint = fingerprint(GeoFenceRule.canonical(generate().rules()));
            if (!fingerprint.equals(state(org).fingerprint())) {
                rewrite("sharing");
            }
        } catch (RuntimeException e) {
            LOG.warnf("The GeoFence rules of %s are checked again later: %s", org, e.getMessage());
        }
    }

    // Plumbing ------------------------------------------------------------------------------------

    private RulesReport failed(RuleSetState state, String workspace, String reason, String error, List<String> warnings,
            Instant now) {
        boolean already = "failed".equals(state.outcome());
        state.failed(error, json(warnings), now);
        states.update(state);
        if (!already) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("reason", reason);
            details.put("error", error == null ? "" : error);
            audit.record(EXTENSION, new AuditLog.Entry("geofence.rewritten", "workspace/" + workspace,
                    AuditLog.Outcome.FAILED, details));
        }
        LOG.warnf("The GeoFence rules of %s are not written: %s", workspace, error);
        return report(state, workspace, 0);
    }

    private RulesReport report(RuleSetState state, String workspace, int rules) {
        return new RulesReport(workspace, state.outcome() == null ? "unknown" : state.outcome(), rules,
                state.writtenAt(), read(state.removed()), read(state.mismatches()), read(state.warnings()),
                state.error());
    }

    private RuleSetState state(UUID org) {
        List<RuleSetState> mine = states.mine();
        if (!mine.isEmpty()) {
            return mine.getFirst();
        }
        RuleSetState state = new RuleSetState(org);
        states.insert(state);
        return state;
    }

    private static List<String> first(List<String> lines) {
        return lines.size() > 20 ? lines.subList(0, 20) : lines;
    }

    static String fingerprint(List<String> canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String line : canonical) {
                digest.update(line.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String json(List<String> lines) {
        try {
            return JSON.writeValueAsString(lines);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> read(String json) {
        try {
            return json == null ? List.of() : JSON.readValue(json, LINES);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
