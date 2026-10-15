// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.access;

import com.fasterxml.jackson.databind.JsonNode;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerClient;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Writes the rule set of one workspace into GeoFence (GP-335) the only way the proofs of GP-327 found
 * reliable on GeoFence 2.28.5: every rule of the workspace is deleted, the whole set is written again in
 * ascending priority, each rule in the format that keeps it, and read back.
 *
 * <ul>
 *   <li>{@code POST /rest/geofence/rules} at a priority that another rule holds shifts that rule and
 *       every rule after it down by one, of every workspace; at a free priority it shifts nothing. The
 *       set is written in a band of free priorities: the one it had, when still free, otherwise the
 *       first free one from 1000 in steps of 1000, below the catch-all {@code DENY} of the installation.
 *   <li>A rule with the same subject, workspace, layer, service and request as another is refused with
 *       500 "Duplicate Rule"; the set never holds two ({@link RuleSet}).
 *   <li>An area written as XML and a column list written as JSON are dropped, answering 201: an area
 *       goes as JSON, everything else as XML, and the read back compares areas and columns too.
 * </ul>
 *
 * <p>Rules of the workspace that the catalog did not write (added or changed by hand) are deleted with
 * the others and reported.
 */
public final class RuleWriter {

    /** Where bands start, and their step. */
    static final long STEP = 1000;

    private final GeoServerClient client;

    public RuleWriter(GeoServerClient client) {
        this.client = client;
    }

    /**
     * What a write did.
     *
     * @param written whether the rules were written; false when GeoFence already held exactly them
     * @param base the priority of the first rule
     * @param removed the rules of the workspace found in GeoFence that the catalog had not written (by
     *     hand, or by another hand), deleted
     * @param mismatches what GeoFence holds after writing that differs from what was written, one line
     *     each; empty when the read back matches
     */
    public record Outcome(boolean written, long base, int rules, List<String> removed, List<String> mismatches) {}

    /** A rule as GeoFence holds it. */
    record Held(long id, long priority, GeoFenceRule rule) {}

    /**
     * Writes the rule set of a workspace.
     *
     * @param previous the rules the catalog wrote last time, in canonical form, to tell those added by
     *     hand; empty when unknown
     * @param previousBase the priority where the set started last time, or null
     */
    public Outcome rewrite(String workspace, List<GeoFenceRule> desired, Set<String> previous, Long previousBase) {
        List<Held> all = read();
        List<Held> ours = all.stream().filter(h -> workspace.equals(h.rule().workspace())).toList();
        List<String> wanted = GeoFenceRule.canonical(desired);
        if (GeoFenceRule.canonical(ours.stream().map(Held::rule).toList()).equals(wanted)) {
            return new Outcome(false, ours.isEmpty() ? 0 : ours.get(0).priority(), desired.size(), List.of(), List.of());
        }
        Set<String> written = new HashSet<>(previous);
        List<String> removed = new ArrayList<>();
        for (Held h : ours) {
            String c = h.rule().canonical();
            if (!written.contains(c) && !(previous.isEmpty() && wanted.contains(c))) {
                removed.add(h.rule().describe());
            }
        }
        for (Held h : ours) {
            client.deleteRule(h.id());
        }
        Set<Long> occupied = new TreeSet<>();
        all.stream().filter(h -> !workspace.equals(h.rule().workspace())).forEach(h -> occupied.add(h.priority()));
        long base = base(occupied, previousBase, desired.size(), ceiling(all));
        for (int i = 0; i < desired.size(); i++) {
            GeoFenceRule r = desired.get(i);
            client.insertRule(r.body(base + i), r.writtenAsXml());
        }
        List<String> mismatches = verify(workspace, desired);
        client.invalidateRuleCache();
        return new Outcome(true, base, desired.size(), removed, mismatches);
    }

    /**
     * Compares the rules GeoFence holds for a workspace with a set, in order: one line for each rule that
     * differs, is missing or is in excess.
     */
    public List<String> verify(String workspace, List<GeoFenceRule> desired) {
        List<GeoFenceRule> held = read().stream().filter(h -> workspace.equals(h.rule().workspace()))
                .map(Held::rule).toList();
        return compare(desired, held);
    }

    /**
     * The differences between the generated rules and those GeoFence holds: a rule GeoFence holds
     * changed (the same subject, target and access, other limits), a rule missing, a rule the catalog did
     * not write, and, when GeoFence holds exactly the rules, any rule out of order.
     */
    static List<String> compare(List<GeoFenceRule> desired, List<GeoFenceRule> held) {
        List<String> mismatches = new ArrayList<>();
        List<GeoFenceRule> missing = new ArrayList<>();
        List<GeoFenceRule> extra = new ArrayList<>(held);
        for (GeoFenceRule want : desired) {
            GeoFenceRule same = extra.stream().filter(h -> h.canonical().equals(want.canonical())).findFirst().orElse(null);
            if (same != null) {
                extra.remove(same);
            } else {
                missing.add(want);
            }
        }
        for (GeoFenceRule want : missing) {
            GeoFenceRule changed = extra.stream().filter(h -> target(h).equals(target(want))).findFirst().orElse(null);
            if (changed != null) {
                extra.remove(changed);
                mismatches.add(want.describe() + ": GeoFence holds " + difference(want, changed));
            } else {
                mismatches.add("missing in GeoFence: " + want.describe());
            }
        }
        for (GeoFenceRule got : extra) {
            mismatches.add("not written by the catalog: " + got.describe());
        }
        if (mismatches.isEmpty()) {
            for (int i = 0; i < desired.size(); i++) {
                if (!desired.get(i).canonical().equals(held.get(i).canonical())) {
                    mismatches.add("#" + (i + 1) + " out of order: " + desired.get(i).describe() + " is in the place of "
                            + held.get(i).describe());
                    break;
                }
            }
        }
        return mismatches;
    }

    /** Who, where and the access of a rule, without its limits. */
    private static String target(GeoFenceRule r) {
        return r.duplicateKey() + "|" + r.access();
    }

    /** What differs between a rule written and the one read back, field by field. */
    private static String difference(GeoFenceRule want, GeoFenceRule got) {
        List<String> parts = new ArrayList<>();
        if (want.area() != null && got.area() == null) {
            parts.add("no area");
        } else if (want.area() != null && !GeoFenceRule.wkt(want.area()).equals(GeoFenceRule.wkt(got.area()))) {
            parts.add("another area");
        }
        if (!want.attributes().equals(got.attributes()) && !new java.util.TreeMap<>(want.attributes())
                .equals(new java.util.TreeMap<>(got.attributes()))) {
            parts.add(got.attributes().isEmpty() ? "no column list" : "the columns " + got.attributes());
        }
        if (want.cqlRead() != null && !want.cqlRead().strip().equals(got.cqlRead() == null ? null : got.cqlRead().strip())) {
            parts.add(got.cqlRead() == null ? "no row filter" : "the row filter " + got.cqlRead());
        }
        if (!got.extras().isEmpty()) {
            parts.add("also " + got.extras());
        }
        if (parts.isEmpty()) {
            parts.add(got.describe());
        }
        return String.join(", ", parts);
    }

    private List<Held> read() {
        List<Held> held = new ArrayList<>();
        for (JsonNode n : client.geofenceRules()) {
            held.add(new Held(n.path("id").asLong(), n.path("priority").asLong(), GeoFenceRule.read(n)));
        }
        held.sort(Comparator.comparingLong(Held::priority).thenComparingLong(Held::id));
        return held;
    }

    /**
     * The priority of the catch-all of the installation (a {@code DENY} for everyone, everywhere): the
     * rules of a workspace must come before it.
     */
    static long ceiling(List<Held> all) {
        return all.stream()
                .filter(h -> h.rule().workspace().isEmpty() && h.rule().layer() == null && h.rule().user() == null
                        && h.rule().role() == null && h.rule().service() == null
                        && h.rule().access() == GeoFenceRule.Access.DENY)
                .mapToLong(Held::priority).min().orElse(Long.MAX_VALUE);
    }

    /**
     * The first priority of a band of {@code count} free priorities below {@code ceiling}: the previous
     * one when still free, otherwise the first from {@link #STEP} in steps of it. When no band is free, 1:
     * GeoFence then shifts the rules after it, which keeps their order.
     */
    static long base(Set<Long> occupied, Long previous, int count, long ceiling) {
        if (previous != null && previous > 0 && free(occupied, previous, count, ceiling)) {
            return previous;
        }
        for (long p = STEP; p + count < ceiling && p < Long.MAX_VALUE - STEP; p += STEP) {
            if (free(occupied, p, count, ceiling)) {
                return p;
            }
        }
        return 1;
    }

    private static boolean free(Set<Long> occupied, long from, int count, long ceiling) {
        if (from + count >= ceiling) {
            return false;
        }
        for (long p : occupied) {
            if (p >= from && p < from + count) {
                return false;
            }
        }
        return true;
    }
}
