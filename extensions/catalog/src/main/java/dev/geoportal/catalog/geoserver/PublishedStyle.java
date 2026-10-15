// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.geoserver;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A style the catalog keeps in the workspace of its organization (GP-334). */
@Entity
@Table(name = "geoserver_style", schema = "ext_catalog")
public class PublishedStyle {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 63)
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String sld;

    /** The style of Geoportal it was translated from (JSON), or null for an SLD written as such. */
    @Column(columnDefinition = "text")
    private String source;

    @Column(nullable = false)
    private boolean retired;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected PublishedStyle() {}

    public PublishedStyle(UUID organizationId, StyleDefinition definition, Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.name = definition.name();
        define(definition, now);
    }

    public void define(StyleDefinition definition, Instant now) {
        this.sld = definition.sld();
        this.source = null;
        this.retired = false;
        this.updatedAt = now;
    }

    public void retire(Instant now) {
        this.retired = true;
        this.updatedAt = now;
    }

    /** Keeps the style of Geoportal the SLD was translated from (GP-114). */
    public void translatedFrom(String source) {
        this.source = source;
    }

    public String source() {
        return source;
    }

    public String name() {
        return name;
    }

    public boolean retired() {
        return retired;
    }

    public StyleDefinition definition() {
        return new StyleDefinition(name, sld);
    }
}
