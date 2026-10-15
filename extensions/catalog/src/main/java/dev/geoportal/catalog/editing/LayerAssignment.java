// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.editing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A published layer assigned to a surveyor (GP-036): the surveyor writes its data when they also hold
 * the permission {@code edit-layer-data}, which the catalog learns at their requests ({@link #holds}).
 */
@Entity
@Table(name = "layer_assignment", schema = "ext_catalog")
public class LayerAssignment {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 63)
    private String layer;

    @Column(name = "account_id", nullable = false)
    private UUID account;

    @Column(nullable = false, length = 320)
    private String username;

    @Column(name = "assigned_by")
    private String assignedBy;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    private Boolean holds;

    @Column(name = "checked_at")
    private Instant checkedAt;

    /** Required by JPA. */
    protected LayerAssignment() {}

    public LayerAssignment(UUID organizationId, String layer, UUID account, String username, String assignedBy,
            Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.layer = Objects.requireNonNull(layer, "layer");
        this.account = Objects.requireNonNull(account, "account");
        this.username = Objects.requireNonNull(username, "username");
        this.assignedBy = assignedBy;
        this.assignedAt = Objects.requireNonNull(now, "now");
    }

    public UUID id() {
        return id;
    }

    public String layer() {
        return layer;
    }

    public UUID account() {
        return account;
    }

    /** The user name of the surveyor when the layer was assigned to them. */
    public String username() {
        return username;
    }

    /** Whether this is the assignment of a person, by the user name of the request. */
    public boolean of(String user) {
        return user != null && username.equalsIgnoreCase(user);
    }

    public String assignedBy() {
        return assignedBy;
    }

    public Instant assignedAt() {
        return assignedAt;
    }

    /** Whether the surveyor held {@code edit-layer-data} at their last request, null before their first. */
    public Boolean holds() {
        return holds;
    }

    public Instant checkedAt() {
        return checkedAt;
    }

    /** Whether the surveyor was seen holding the permission: only then GeoServer lets them write. */
    public boolean confirmed() {
        return Boolean.TRUE.equals(holds);
    }

    /** What the last request of the surveyor said of their permission; true when it changed. */
    public boolean checked(boolean holding, Instant at) {
        boolean changed = !Objects.equals(holds, holding);
        this.holds = holding;
        this.checkedAt = at;
        return changed;
    }
}
