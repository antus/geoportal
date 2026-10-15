// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.geoserver;

import java.util.List;

/**
 * The outcome of comparing GeoServer with what Geoportal holds for an organization: the changes
 * that bring GeoServer back to the definition of Geoportal (planned, or made when {@code applied}),
 * and the drift, what the workspace holds that Geoportal does not know, which is reported and never
 * deleted.
 *
 * @param workspace the workspace of the organization
 * @param applied whether the changes were made, or only planned
 * @param changes what was (or would be) created, updated or deleted, in order
 * @param drift what the workspace holds that Geoportal does not know
 */
public record Alignment(String workspace, boolean applied, List<Change> changes, List<Drift> drift) {

    public Alignment {
        changes = List.copyOf(changes);
        drift = List.copyOf(drift);
    }

    /** Whether GeoServer already matched Geoportal: nothing to change. */
    public boolean aligned() {
        return changes.isEmpty();
    }

    /** Whether an applied change failed. */
    public boolean failed() {
        return changes.stream().anyMatch(c -> c.error() != null);
    }

    /**
     * A change of an object of GeoServer, or of the schema of the organization.
     *
     * @param kind {@code schema}, {@code workspace}, {@code store}, {@code style} or {@code layer}
     * @param action {@code create}, {@code update} or {@code delete}; {@code disable} when the catalog was
     *     turned off ({@link Reconciler#withdraw})
     * @param reason why, such as the fields that differ
     * @param error why it failed, when it was applied and refused, or {@code null}
     * @param cause what GeoServer answered when it refused, as readable text, or {@code null} (GP-115)
     */
    public record Change(String kind, String name, String action, String reason, String error, String cause) {

        public Change(String kind, String name, String action, String reason, String error) {
            this(kind, name, action, reason, error, null);
        }
    }

    /**
     * An object of the workspace that Geoportal does not know.
     *
     * @param kind {@code store}, {@code coverage-store}, {@code layer} or {@code style}
     */
    public record Drift(String kind, String name) {}
}
