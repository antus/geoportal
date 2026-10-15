// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A dataset of the catalog of an organization: what it is, where it lives and who may see it. */
@Entity
@Table(name = "dataset", schema = "ext_catalog_geodata")
public class Dataset {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 200)
    private String title;

    /** file, ogc, stream, db, proc or cat. */
    @Column(nullable = false, length = 20)
    private String kind;

    @Column(nullable = false, length = 60)
    private String format;

    @Column(nullable = false, length = 60)
    private String theme;

    /** Where the data is served from: geoserver, geoportal, remote or objects. */
    @Column(nullable = false, length = 20)
    private String host;

    /** Who may see it: private, groups, space or public. */
    @Column(nullable = false, length = 20)
    private String access;

    @Column(nullable = false, length = 60)
    private String updated;

    @Column(nullable = false, length = 60)
    private String size;

    @Column(nullable = false, length = 120)
    private String owner;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Required by JPA. */
    protected Dataset() {}

    public Dataset(UUID organizationId, DatasetView values, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.title = values.title();
        this.kind = values.kind();
        this.format = values.format();
        this.theme = values.theme();
        this.host = values.host();
        this.access = values.access();
        this.updated = values.updated();
        this.size = values.size();
        this.owner = values.owner();
        this.description = values.description();
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    DatasetView view() {
        return new DatasetView(
                id, title, kind, format, theme, host, access, updated, size, owner, description);
    }
}
