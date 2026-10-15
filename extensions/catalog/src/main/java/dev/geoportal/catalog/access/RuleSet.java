// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.access;

import dev.geoportal.catalog.access.GeoFenceRule.Access;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The GeoFence rule set of one organization (GP-335, ADR-0021), from who reaches each published layer
 * and the data rules that limit some people on it. A pure function: the same input gives the same
 * rules in the same order, which is their priority.
 *
 * <p>The order, from the first rule to the last:
 *
 * <ol>
 *   <li>for each layer, the surveyors assigned to it (GP-036): a user rule that allows the WFS
 *       transactions and locks of that layer, and nothing else; a surveyor limited by data rules on it
 *       (GP-308) writes within them (ADR-0025): a {@code LIMIT} with the area and an {@code ALLOW} with the
 *       row filter as write filter, for each of the two requests, or nothing when the limits hide columns;
 *   <li>no WFS transaction, lock or feature lock in the workspace for anybody else;
 *   <li>for each layer, the people limited by data rules, each in a block of user rules that ends with
 *       a {@code DENY} of the layer: a {@code LIMIT} with the area, then an {@code ALLOW} per service of
 *       their level with the row filter and the columns. GeoFence evaluates the rules once per role of
 *       the person and joins what the roles allow; user rules are in every one of those evaluations,
 *       so a limit written for the person holds whatever team or organization grant opens the layer
 *       to them, which a rule of a role would not;
 *   <li>the administrators of the organization, the whole workspace;
 *   <li>for each layer, the grants: people as user rules, teams and the organization as role rules,
 *       the public as a rule for everyone, an {@code ALLOW} per service of the level;
 *   <li>the base rule of the workspace: {@code DENY}.
 * </ol>
 *
 * <p>Levels map to services (ADR-0005): {@code view} gives WMS and the tiles of GeoWebCache (service
 * {@code GWC} for GeoFence, which GeoWebCache refuses when the person has limits, so the limits are
 * applied by WMS); any higher level gives WFS and WCS too.
 */
public final class RuleSet {

    /** The services of the level {@code view}. */
    static final List<String> VIEW = List.of("WMS", "GWC");

    /** The services of the levels that read the data: download and above. */
    static final List<String> DATA = List.of("WMS", "GWC", "WFS", "WCS");

    /** WFS requests that write or lock, refused in every workspace but to the surveyors of a layer. */
    static final List<String> WRITES = List.of("TRANSACTION", "LOCKFEATURE", "GETFEATUREWITHLOCK");

    /**
     * The WFS requests a surveyor assigned to a layer is allowed (GP-036): the transaction and the lock,
     * not {@code GETFEATUREWITHLOCK}, which reads features: what a surveyor reads follows the sharing.
     */
    static final List<String> EDITS = List.of("TRANSACTION", "LOCKFEATURE");

    /** The levels of a dataset, lowest first, as in manifest.yaml. */
    public static final List<String> LEVELS = List.of("view", "download", "edit", "edit-data", "manage");

    private RuleSet() {}

    /** Who a grant reaches. */
    public enum Kind {
        PERSON,
        TEAM,
        ORGANIZATION,
        PUBLIC
    }

    /**
     * A subject that reaches a layer at a level.
     *
     * @param subject the user name of a person, the identifier of a team, of the organization, or null
     *     for the public
     */
    public record Reach(Kind kind, String subject, String level) {
        public Reach {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(level, "level");
        }
    }

    /**
     * The limits of the data rules that apply to a person on a layer, already composed: what all of them
     * allow together.
     *
     * @param area the area, {@code SRID=<code>;MULTIPOLYGON(...)} in the reference system of the layer,
     *     null for no limit of area
     * @param empty whether the areas of the rules have nothing in common: the person sees nothing
     * @param cql the row filter, null for every row
     * @param columns every column of the layer, in its order
     * @param hidden the columns the person does not see
     */
    public record Limits(String area, boolean empty, String cql, List<String> columns, Set<String> hidden) {
        public Limits {
            columns = columns == null ? List.of() : List.copyOf(columns);
            hidden = hidden == null ? Set.of() : Set.copyOf(hidden);
        }

        public boolean none() {
            return area == null && !empty && cql == null && hidden.isEmpty();
        }
    }

    /**
     * A person limited by data rules on a layer.
     *
     * @param user the user name GeoServer receives for the person
     * @param level the level of the person on the dataset, from any grant, or null when they have none
     */
    public record Limited(String user, String level, Limits limits) {}

    /**
     * A published layer: who reaches it, who is limited on it, and who edits its data.
     *
     * @param reaches the grants of its dataset, the owner included
     * @param surveyors the user names of the surveyors assigned to it who hold the permission to edit
     *     the data of layers (GP-036): they get WFS transactions on it, nobody else does
     */
    public record LayerAccess(String layer, List<Reach> reaches, List<Limited> limited, List<String> surveyors) {
        public LayerAccess {
            reaches = reaches == null ? List.of() : List.copyOf(reaches);
            limited = limited == null ? List.of() : List.copyOf(limited);
            surveyors = surveyors == null ? List.of() : List.copyOf(surveyors);
        }

        public LayerAccess(String layer, List<Reach> reaches, List<Limited> limited) {
            this(layer, reaches, limited, List.of());
        }
    }

    /**
     * The role of the members of an organization who are not guests, administrators included (Mosaikit: a
     * grant to the organization): its identifier, as the role service of GP-336 names it.
     */
    public static String organizationRole(UUID organization) {
        return organization.toString();
    }

    /** The role of the administrators of an organization, who hold every level on its datasets: its identifier and {@code :admin}. */
    public static String administratorsRole(UUID organization) {
        return organization + ":admin";
    }

    /** The role of the people of a team (Mosaikit: a grant to a group): its identifier. */
    public static String teamRole(UUID team) {
        return team.toString();
    }

    /** The rules of the workspace of an organization, in the order of their priority. */
    public static List<GeoFenceRule> of(String workspace, UUID organization, List<LayerAccess> layers) {
        List<LayerAccess> sorted = layers.stream().sorted(Comparator.comparing(LayerAccess::layer)).toList();
        Rules rules = new Rules();
        for (LayerAccess layer : sorted) {
            for (String surveyor : editing(layer)) {
                for (String request : EDITS) {
                    rules.add(new GeoFenceRule(surveyor, null, workspace, layer.layer(), "WFS", request, Access.ALLOW,
                            null, null, null, null));
                }
            }
            // Surveyors limited by data rules write within them (ADR-0025): a LIMIT with the area and an ALLOW
            // with the row filter as read and write filter, for the transaction and the lock only, so that
            // the LIMIT of the area is collected before the ALLOW that ends the evaluation.
            for (Limited surveyor : editingWithin(layer)) {
                Limits l = surveyor.limits();
                for (String request : EDITS) {
                    if (l.area() != null) {
                        rules.add(new GeoFenceRule(surveyor.user(), null, workspace, layer.layer(), "WFS", request,
                                Access.LIMIT, l.area(), null, null, null));
                    }
                    rules.add(new GeoFenceRule(surveyor.user(), null, workspace, layer.layer(), "WFS", request,
                            Access.ALLOW, null, l.cql(), null, null).writing(l.cql()));
                }
            }
        }
        for (String request : WRITES) {
            rules.add(GeoFenceRule.deny(null, null, workspace, null, "WFS", request));
        }
        for (LayerAccess layer : sorted) {
            for (Limited person : layer.limited().stream().sorted(Comparator.comparing(Limited::user)).toList()) {
                if (person.level() == null || person.limits() == null || person.limits().none()) {
                    continue;
                }
                Limits l = person.limits();
                if (!l.empty()) {
                    if (l.area() != null) {
                        rules.add(new GeoFenceRule(person.user(), null, workspace, layer.layer(), null, null,
                                Access.LIMIT, l.area(), null, null, null));
                    }
                    Map<String, String> columns = new LinkedHashMap<>();
                    if (!l.hidden().isEmpty()) {
                        for (String c : l.columns()) {
                            columns.put(c, l.hidden().contains(c) ? "NONE" : "READONLY");
                        }
                        for (String c : l.hidden()) {
                            columns.putIfAbsent(c, "NONE");
                        }
                    }
                    for (String service : services(person.level())) {
                        rules.add(new GeoFenceRule(person.user(), null, workspace, layer.layer(), service, null,
                                Access.ALLOW, null, l.cql(), columns, null));
                    }
                }
                rules.add(GeoFenceRule.deny(person.user(), null, workspace, layer.layer(), null, null));
            }
        }
        rules.add(GeoFenceRule.allow(null, administratorsRole(organization), workspace, null, null));
        for (LayerAccess layer : sorted) {
            Set<String> limited = new HashSet<>();
            layer.limited().stream()
                    .filter(p -> p.level() != null && p.limits() != null && !p.limits().none())
                    .forEach(p -> limited.add(p.user()));
            Map<String, Reach> highest = new LinkedHashMap<>();
            for (Reach r : layer.reaches()) {
                if (r.kind() == Kind.PERSON && (r.subject() == null || limited.contains(r.subject()))) {
                    continue;
                }
                if (!LEVELS.contains(r.level())) {
                    continue;
                }
                String key = r.kind() + "|" + r.subject();
                highest.merge(key, r, (a, b) -> LEVELS.indexOf(b.level()) > LEVELS.indexOf(a.level()) ? b : a);
            }
            List<Reach> ordered = new ArrayList<>(highest.values());
            ordered.sort(Comparator.comparing(Reach::kind).thenComparing(r -> r.subject() == null ? "" : r.subject()));
            for (Reach r : ordered) {
                String user = r.kind() == Kind.PERSON ? r.subject() : null;
                String role = switch (r.kind()) {
                    case TEAM, ORGANIZATION -> r.subject();
                    default -> null;
                };
                for (String service : services(r.level())) {
                    rules.add(GeoFenceRule.allow(user, role, workspace, layer.layer(), service));
                }
            }
        }
        rules.add(GeoFenceRule.deny(null, null, workspace, null, null, null));
        return rules.list;
    }

    /**
     * The surveyors of a layer who get WFS transactions on it without limits, by name: not those limited by
     * data rules on it, who write within their limits ({@link #editingWithin}) or not at all.
     */
    static List<String> editing(LayerAccess layer) {
        Set<String> limited = new HashSet<>();
        layer.limited().stream()
                .filter(p -> p.limits() != null && !p.limits().none())
                .forEach(p -> limited.add(p.user()));
        return layer.surveyors().stream().filter(s -> s != null && !s.isBlank() && !limited.contains(s)).distinct()
                .sorted().toList();
    }

    /**
     * The surveyors of a layer limited by data rules on it whose limits GeoServer keeps writes within
     * ({@link #writableWithin}), in the order of their names.
     */
    static List<Limited> editingWithin(LayerAccess layer) {
        Set<String> surveyors = new HashSet<>(layer.surveyors());
        Map<String, Limited> within = new java.util.TreeMap<>();
        layer.limited().stream()
                .filter(p -> p.user() != null && surveyors.contains(p.user()) && p.limits() != null && !p.limits().none())
                .filter(p -> writableWithin(p.limits()))
                .forEach(p -> within.putIfAbsent(p.user(), p));
        return List.copyOf(within.values());
    }

    /**
     * Whether a surveyor with these limits writes within them through GeoServer (ADR-0025): the area and the
     * row filter become the write filter of GeoServer, which refuses an insert outside them and updates or
     * deletes only the features inside them. Hidden columns do not: GeoServer 2.28 refuses an update of
     * the columns a rule lets one write, and an insert must leave the hidden ones empty; and limits whose
     * areas have nothing in common leave nothing to write.
     */
    public static boolean writableWithin(Limits limits) {
        return limits != null && !limits.empty() && limits.hidden().isEmpty();
    }

    /** The services a level opens. */
    static List<String> services(String level) {
        return "view".equals(level) ? VIEW : DATA;
    }

    /** Keeps the first of rules GeoFence would take as duplicates. */
    private static final class Rules {
        final List<GeoFenceRule> list = new ArrayList<>();
        final Set<String> keys = new HashSet<>();

        void add(GeoFenceRule rule) {
            if (keys.add(rule.duplicateKey())) {
                list.add(rule);
            }
        }
    }
}
