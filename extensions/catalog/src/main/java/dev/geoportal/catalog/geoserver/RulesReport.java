// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.geoserver;

import java.time.Instant;
import java.util.List;

/**
 * The GeoFence rules of the workspace of an organization (GP-335), in the reconciliation report: how
 * many the catalog generates, whether GeoFence holds exactly them, and what it removed or could not
 * write.
 *
 * @param outcome {@code written} (rewritten and read back equal), {@code unchanged} (GeoFence already
 *     held them), {@code matching} (compared without writing, equal), {@code mismatch} (GeoFence holds
 *     something else, see {@code mismatches}), {@code failed} (GeoServer refused or did not answer, see
 *     {@code error})
 * @param rules how many rules the catalog generates for the workspace
 * @param writtenAt when the catalog last wrote them, or null
 * @param removed rules of the workspace that the catalog did not write (added or changed by hand),
 *     deleted by the rewrite
 * @param mismatches what GeoFence holds that differs from the generated set, one line each
 * @param warnings what the rules could not express, such as people whose name is unknown
 * @param error why the rules could not be written or read, or null
 */
public record RulesReport(
        String workspace,
        String outcome,
        int rules,
        Instant writtenAt,
        List<String> removed,
        List<String> mismatches,
        List<String> warnings,
        String error) {

    public RulesReport {
        removed = removed == null ? List.of() : List.copyOf(removed);
        mismatches = mismatches == null ? List.of() : List.copyOf(mismatches);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** How many findings need a person: rules GeoFence does not hold as generated, or a failure. */
    public int needsPerson() {
        return mismatches.size() + (error == null ? 0 : 1);
    }
}
