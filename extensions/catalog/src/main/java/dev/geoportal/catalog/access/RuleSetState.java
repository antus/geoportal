// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** The GeoFence rule set the catalog last wrote for its organization, and what the write found (GP-335). */
@Entity
@Table(name = "geofence_rule_set", schema = "ext_catalog")
public class RuleSetState {

    @Id
    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(length = 64)
    private String fingerprint;

    @Column(nullable = false, columnDefinition = "text")
    private String rules = "[]";

    @Column(name = "base_priority")
    private Long basePriority;

    @Column(name = "written_at")
    private Instant writtenAt;

    @Column(length = 20)
    private String outcome;

    @Column(nullable = false, columnDefinition = "text")
    private String removed = "[]";

    @Column(nullable = false, columnDefinition = "text")
    private String mismatches = "[]";

    @Column(nullable = false, columnDefinition = "text")
    private String warnings = "[]";

    @Column(length = 1000)
    private String error;

    @Column(name = "checked_at")
    private Instant checkedAt;

    /** Required by JPA. */
    protected RuleSetState() {}

    RuleSetState(UUID organizationId) {
        this.organizationId = organizationId;
    }

    /** After a write, or a write that found GeoFence already holding the set. */
    void written(String fingerprint, String rules, long base, String outcome, String removed, String mismatches,
            String warnings, Instant at, boolean wrote) {
        this.fingerprint = fingerprint;
        this.rules = rules;
        this.basePriority = base;
        this.outcome = outcome;
        this.removed = removed;
        this.mismatches = mismatches;
        this.warnings = warnings;
        this.error = null;
        this.checkedAt = at;
        if (wrote || this.writtenAt == null) {
            this.writtenAt = at;
        }
    }

    /** After a write GeoServer refused: the fingerprint is forgotten, so the next check writes again. */
    void failed(String error, String warnings, Instant at) {
        this.fingerprint = null;
        this.outcome = "failed";
        this.error = error == null ? null : error.substring(0, Math.min(1000, error.length()));
        this.warnings = warnings;
        this.checkedAt = at;
    }

    UUID organizationId() {
        return organizationId;
    }

    String fingerprint() {
        return fingerprint;
    }

    String rules() {
        return rules;
    }

    Long basePriority() {
        return basePriority;
    }

    Instant writtenAt() {
        return writtenAt;
    }

    String outcome() {
        return outcome;
    }

    String removed() {
        return removed;
    }

    String mismatches() {
        return mismatches;
    }

    String warnings() {
        return warnings;
    }

    String error() {
        return error;
    }
}
