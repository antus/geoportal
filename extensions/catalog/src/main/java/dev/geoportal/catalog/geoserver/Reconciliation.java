// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.geoserver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The reconciliation report of the GeoServer workspace of an organization (GP-118), from an
 * alignment planned or applied: what already matches the catalog, what the catalog holds and
 * GeoServer misses, what was changed by hand, what the catalog withdrew and GeoServer still has,
 * what GeoServer holds and the catalog does not know, and what failed. The last two need a person:
 * drift is never deleted by an alignment, and a refused change needs a correction.
 *
 * <p>When {@code applied}, the missing objects were created, the changed ones brought back and the
 * withdrawn ones deleted, except those in {@code failed}.
 */
public record Reconciliation(
        String workspace,
        boolean applied,
        List<Item> aligned,
        List<Item> missing,
        List<Item> changed,
        List<Item> withdrawn,
        List<Item> unknown,
        List<Item> failed,
        RulesReport rules) {

    /**
     * An object of the workspace.
     *
     * @param kind schema, workspace, store, coverage-store, style or layer
     * @param reason why it is listed: the fields that differ, missing, withdrawn
     * @param cause what GeoServer answered when it refused, or {@code null}
     */
    public record Item(String kind, String name, String reason, String cause) {}

    /**
     * How many objects need a person: unknown to the catalog, or refused by GeoServer, and the GeoFence
     * rules GeoFence does not hold as generated (GP-335).
     */
    public int needsPerson() {
        return unknown.size() + failed.size() + (rules == null ? 0 : rules.needsPerson());
    }

    /** The same report with the GeoFence rules of the workspace (GP-335). */
    public Reconciliation withRules(RulesReport report) {
        return new Reconciliation(workspace, applied, aligned, missing, changed, withdrawn, unknown, failed, report);
    }

    static Reconciliation of(Alignment alignment, List<String> layers, List<String> styles) {
        List<Item> missing = new ArrayList<>();
        List<Item> changed = new ArrayList<>();
        List<Item> withdrawn = new ArrayList<>();
        List<Item> failed = new ArrayList<>();
        Set<String> touched = new HashSet<>();
        for (Alignment.Change c : alignment.changes()) {
            touched.add(c.kind() + "/" + c.name());
            Item item = new Item(c.kind(), c.name(), c.reason(), c.cause());
            if (c.error() != null) {
                failed.add(new Item(c.kind(), c.name(), c.action() + ": " + c.reason(),
                        c.cause() != null ? c.cause() : GeoServerClient.reason(c.error())));
            } else if (c.action().equals("create")) {
                missing.add(item);
            } else if (c.action().equals("delete")) {
                withdrawn.add(item);
            } else {
                changed.add(item);
            }
        }
        List<Item> aligned = new ArrayList<>();
        styles.stream().filter(s -> !touched.contains("style/" + s)).forEach(s -> aligned.add(new Item("style", s, null, null)));
        layers.stream().filter(l -> !touched.contains("layer/" + l)).forEach(l -> aligned.add(new Item("layer", l, null, null)));
        List<Item> unknown = alignment.drift().stream().map(d -> new Item(d.kind(), d.name(), "unknown", null)).toList();
        return new Reconciliation(alignment.workspace(), alignment.applied(), aligned, missing, changed, withdrawn,
                unknown, failed, null);
    }
}
