// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.access;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A data rule (GP-308, ADR-0008): what a subject, a person or a team, sees of a dataset. Each limit is
 * optional: an area in the reference system of the data, a CQL filter of the rows, columns hidden.
 * Rules are distinct from permissions: they never open what the sharing does not, they only narrow it.
 */
@Entity
@Table(name = "data_rule", schema = "ext_catalog_geodata")
public class DataRule {

    public static final String PERSON = "person";
    public static final String TEAM = "team";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    @Column(name = "subject_kind", nullable = false, length = 10)
    private String subjectKind;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(columnDefinition = "text")
    private String area;

    @Column(length = 2000)
    private String cql;

    @Column(name = "hidden_columns", nullable = false, columnDefinition = "text")
    private String hiddenColumns;

    @Column(name = "created_by", length = 320)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected DataRule() {}

    public DataRule(UUID organizationId, UUID datasetId, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.datasetId = Objects.requireNonNull(datasetId, "datasetId");
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
        this.description = "";
        this.hiddenColumns = "[]";
    }

    /** Takes the subject and the limits, already checked. */
    public void define(String subjectKind, UUID subjectId, String description, String area, String cql,
            List<String> hidden, Instant now) {
        this.subjectKind = subjectKind;
        this.subjectId = subjectId;
        this.description = description == null ? "" : description;
        this.area = area;
        this.cql = cql;
        try {
            this.hiddenColumns = JSON.writeValueAsString(hidden == null ? List.of() : hidden);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID datasetId() {
        return datasetId;
    }

    public String subjectKind() {
        return subjectKind;
    }

    public UUID subjectId() {
        return subjectId;
    }

    public String description() {
        return description;
    }

    public String area() {
        return area;
    }

    public String cql() {
        return cql;
    }

    public List<String> hidden() {
        try {
            return JSON.readValue(hiddenColumns, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    public String createdBy() {
        return createdBy;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
