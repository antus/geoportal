// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.geoserver;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A layer the catalog publishes in the workspace of its organization (GP-334). */
@Entity
@Table(name = "geoserver_layer", schema = "ext_catalog_geodata")
public class PublishedLayer {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 63)
    private String name;

    @Column(name = "table_name", nullable = false, length = 63)
    private String tableName;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(nullable = false, length = 20)
    private String srs;

    @Column(length = 63)
    private String style;

    @Column(nullable = false)
    private boolean retired;

    /** {@code vector}, a feature type on the store of the organization, or {@code raster}, a coverage (GP-309). */
    @Column(nullable = false, length = 10)
    private String kind = "vector";

    /** The uploaded dataset whose table it serves (GP-114), or null. */
    @Column(name = "dataset_id")
    private UUID datasetId;

    @Column(length = 20)
    private String health;

    @Column(name = "health_code", length = 40)
    private String healthCode;

    @Column(name = "health_reason", length = 1000)
    private String healthReason;

    @Column(name = "health_checked_at")
    private Instant healthCheckedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected PublishedLayer() {}

    public PublishedLayer(UUID organizationId, LayerDefinition definition, Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.name = definition.name();
        define(definition, now);
    }

    /** Takes a new definition; a retired layer published again is no longer retired. */
    public void define(LayerDefinition definition, Instant now) {
        this.tableName = definition.table();
        this.title = definition.title();
        this.description = definition.description();
        this.srs = definition.srs();
        this.style = definition.style();
        this.retired = false;
        this.updatedAt = now;
    }

    public void retire(Instant now) {
        this.retired = true;
        this.datasetId = null;
        this.publishedAt = null;
        this.health = null;
        this.healthCode = null;
        this.healthReason = null;
        this.healthCheckedAt = null;
        this.updatedAt = now;
    }

    /** Serves the table of a dataset (GP-114), published now unless it was already. */
    public void serves(UUID dataset, Instant now) {
        this.datasetId = dataset;
        if (this.publishedAt == null) {
            this.publishedAt = now;
        }
    }

    /** The last health check: {@code ok} or {@code failed}, with the code and reason of a failure. */
    public void checked(String health, String code, String reason, Instant at) {
        this.health = health;
        this.healthCode = code;
        this.healthReason = reason == null || reason.length() <= 1000 ? reason : reason.substring(0, 997) + "...";
        this.healthCheckedAt = at;
    }

    /** Whether the layer is a raster: a coverage in a coverage store named as the layer (GP-309). */
    public boolean raster() {
        return "raster".equals(kind);
    }

    /** Makes the layer a raster or a vector. */
    public void raster(boolean raster) {
        this.kind = raster ? "raster" : "vector";
    }

    public UUID datasetId() {
        return datasetId;
    }

    public String health() {
        return health;
    }

    public String healthCode() {
        return healthCode;
    }

    public String healthReason() {
        return healthReason;
    }

    public Instant healthCheckedAt() {
        return healthCheckedAt;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public String name() {
        return name;
    }

    public boolean retired() {
        return retired;
    }

    public LayerDefinition definition() {
        return new LayerDefinition(name, tableName, title, description, srs, style);
    }
}
