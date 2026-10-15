// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.access;

import dev.geoportal.ext.catalog.geodata.access.GeoFenceRule.Access;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What GeoFence decides for one person on one layer and one service, read from the rule set the catalog
 * generates (GP-339): the same rules, in the same order, that {@link RuleWriter} writes, evaluated as
 * GeoFence evaluates them. GeoFence takes the rules in the order of their priority that match the person
 * (their user name or one of their roles, or everyone), the workspace, the layer and the service; it
 * collects the {@code LIMIT} rules it meets and stops at the first {@code ALLOW} or {@code DENY}. A person
 * with several roles is evaluated once per role, user rules in every evaluation, and allowed when one of
 * them allows (ADR-0021).
 *
 * <p>The diagnosis of the access of a person and the preview of a data rule read the decision from here, so
 * that they name the rule GeoServer applies; the GeoServer tests of GP-339 prove that GeoServer answers the
 * same.
 */
public final class Evaluation {

    private Evaluation() {}

    /** What decided: the rule, its position in the set, and its kind. */
    public enum Cause {
        /** No rule of the layer reaches the person: the base {@code DENY} of the workspace. */
        NO_GRANT,
        /** A grant to the person, a team, the organization or the public allows. */
        GRANT,
        /** The administrators of the organization hold the whole workspace. */
        ADMINISTRATOR,
        /** The person is limited by data rules on the layer: the {@code ALLOW} of their block. */
        DATA_RULES,
        /** The person is limited by data rules, and their block denies the service: their level does not open it, or the areas of their rules have nothing in common. */
        DATA_RULES_DENY,
        /** Any other rule (a denial of writes, the catch-all of the installation). */
        OTHER
    }

    /**
     * The decision for one service.
     *
     * @param allowed whether GeoServer serves the person
     * @param cause what decided
     * @param rule the rule that decided, or null when no rule matched (GeoServer then denies)
     * @param areas the areas of the {@code LIMIT} rules met before it, as written
     * @param cql the row filter of the deciding {@code ALLOW}, or null
     * @param hidden the columns the deciding {@code ALLOW} hides
     */
    public record Decision(boolean allowed, Cause cause, GeoFenceRule rule, List<String> areas, String cql,
            Set<String> hidden) {
        public Decision {
            areas = areas == null ? List.of() : List.copyOf(areas);
            hidden = hidden == null ? Set.of() : Set.copyOf(hidden);
        }

        /** Whether the answer is limited: an area, a row filter or hidden columns. */
        public boolean limited() {
            return !areas.isEmpty() || cql != null || !hidden.isEmpty();
        }
    }

    /**
     * Evaluates the rules for a person on a layer of a workspace and a service.
     *
     * @param user the user name GeoServer receives for the person
     * @param roles the roles GeoServer reads for the person (ADR-0016)
     * @param service {@code WMS}, {@code WFS}, {@code WCS} or {@code GWC}
     */
    public static Decision of(List<GeoFenceRule> rules, String user, Collection<String> roles, String workspace, String layer,
            String service) {
        Objects.requireNonNull(rules, "rules");
        List<String> evaluated = roles == null || roles.isEmpty() ? java.util.Arrays.asList((String) null) : List.copyOf(roles);
        Decision denied = null;
        for (String role : evaluated) {
            Decision d = once(rules, user, role, workspace, layer, service);
            if (d.allowed()) {
                return d;
            }
            // The most telling denial: a block of data rules or the base deny over a rule of nobody.
            if (denied == null || rank(d.cause()) > rank(denied.cause())) {
                denied = d;
            }
        }
        return denied;
    }

    private static int rank(Cause cause) {
        return switch (cause) {
            case DATA_RULES_DENY -> 3;
            case NO_GRANT -> 2;
            case OTHER -> 1;
            default -> 0;
        };
    }

    private static Decision once(List<GeoFenceRule> rules, String user, String role, String workspace, String layer,
            String service) {
        List<String> areas = new ArrayList<>();
        for (GeoFenceRule r : rules) {
            if (!matches(r, user, role, workspace, layer, service)) {
                continue;
            }
            if (r.access() == Access.LIMIT) {
                if (r.area() != null) {
                    areas.add(r.area());
                }
                continue;
            }
            boolean allowed = r.access() == Access.ALLOW;
            Set<String> hidden = new TreeSet<>();
            for (Map.Entry<String, String> a : r.attributes().entrySet()) {
                if ("NONE".equals(a.getValue())) {
                    hidden.add(a.getKey());
                }
            }
            return new Decision(allowed, cause(r, user, workspace, areas), r, allowed ? areas : List.of(),
                    allowed ? r.cqlRead() : null, allowed ? hidden : Set.of());
        }
        return new Decision(false, Cause.NO_GRANT, null, List.of(), null, Set.of());
    }

    private static Cause cause(GeoFenceRule r, String user, String workspace, List<String> areas) {
        boolean own = r.user() != null && r.user().equalsIgnoreCase(user);
        if (r.access() == Access.DENY) {
            if (own && r.layer() != null) {
                return Cause.DATA_RULES_DENY;
            }
            if (r.user() == null && r.role() == null && r.layer() == null && r.service() == null && r.request() == null
                    && workspace.equals(r.workspace())) {
                return Cause.NO_GRANT;
            }
            return Cause.OTHER;
        }
        if (r.role() != null && r.role().endsWith(":admin") && r.layer() == null) {
            return Cause.ADMINISTRATOR;
        }
        // The ALLOW of the block of a person limited by data rules: an area met before it, a row filter, columns.
        if (own && r.layer() != null && (!areas.isEmpty() || r.cqlRead() != null || !r.attributes().isEmpty())) {
            return Cause.DATA_RULES;
        }
        return Cause.GRANT;
    }

    private static boolean matches(GeoFenceRule r, String user, String role, String workspace, String layer, String service) {
        if (r.user() != null && (user == null || !r.user().equalsIgnoreCase(user))) {
            return false;
        }
        if (r.role() != null && (role == null || !r.role().equals(role))) {
            return false;
        }
        if (!r.workspace().equals(workspace)) {
            return false;
        }
        if (r.layer() != null && !r.layer().equals(layer)) {
            return false;
        }
        if (r.service() != null && !r.service().equalsIgnoreCase(service)) {
            return false;
        }
        // The catalog writes requests only for the writes of WFS; reading never matches them.
        if (r.request() != null) {
            return false;
        }
        return true;
    }
}
