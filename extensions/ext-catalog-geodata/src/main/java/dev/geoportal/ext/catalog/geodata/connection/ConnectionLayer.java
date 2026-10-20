// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.connection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * GP-986: the connection and the table of a dataset that maps a table of a PostgreSQL connection without
 * copying it: the reference the source picker gave (EXT-026), as it was chosen, and what its resolution
 * found when the layer was made or refreshed. Only the name of the credential of the connection, never
 * the credential.
 */
@Entity
@Table(name = "connection_layer", schema = "ext_catalog_geodata")
public class ConnectionLayer {

    @Id
    @Column(name = "dataset_id")
    private UUID datasetId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, columnDefinition = "text")
    private String reference;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "part_id", nullable = false, length = 1000)
    private String partId;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(name = "database_name", nullable = false, length = 255)
    private String database;

    @Column(name = "schema_name", nullable = false, length = 255)
    private String schema;

    @Column(name = "table_name", nullable = false, length = 255)
    private String table;

    @Column(name = "geometry_column", nullable = false, length = 255)
    private String geometryColumn;

    @Column(name = "db_user", nullable = false, length = 255)
    private String user;

    @Column(name = "ssl_mode", length = 20)
    private String sslMode;

    @Column(nullable = false, length = 255)
    private String credential;

    @Column(nullable = false, length = 100)
    private String store;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;

    /** Required by JPA. */
    protected ConnectionLayer() {}

    public ConnectionLayer(UUID datasetId, UUID organizationId, String reference, Source source, Instant now) {
        this.datasetId = Objects.requireNonNull(datasetId, "datasetId");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.reference = Objects.requireNonNull(reference, "reference");
        follow(source, now);
    }

    /** Takes what a new resolution of the reference found: the source may have moved. */
    public void follow(Source source, Instant now) {
        this.connectionId = source.connection();
        this.partId = source.part();
        this.host = source.host();
        this.port = source.port();
        this.database = source.database();
        this.schema = source.schema();
        this.table = source.table();
        this.geometryColumn = source.geometryColumn();
        this.user = source.user();
        this.sslMode = source.sslMode();
        this.credential = source.credential();
        this.store = source.store();
        this.resolvedAt = now;
    }

    /** What the layer reads, as a record. */
    public Source source() {
        return new Source(connectionId, partId, host, port, database, schema, table, geometryColumn, user, sslMode,
                credential, store);
    }

    public UUID datasetId() {
        return datasetId;
    }

    public String reference() {
        return reference;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }
}
