// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata;

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
 * A data rule on a layer of an external service, or on the whole service (GP-309): what a person or a team
 * should see of it. Another server cannot be made to filter what it answers, so the rule is never applied
 * as a filter: the layer is unavailable to its subject, who is told why. The limits are kept to say what
 * the rule asked for.
 */
@Entity
@Table(name = "service_data_rule", schema = "ext_catalog_geodata")
public class ServiceDataRule {

    public static final String PERSON = "person";
    public static final String TEAM = "team";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(length = 255)
    private String layer;

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

    /** Required by JPA. */
    protected ServiceDataRule() {}

    public ServiceDataRule(UUID organizationId, UUID serviceId, String layer, String subjectKind, UUID subjectId,
            String description, String area, String cql, List<String> hidden, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.serviceId = Objects.requireNonNull(serviceId, "serviceId");
        this.layer = layer;
        this.subjectKind = Objects.requireNonNull(subjectKind, "subjectKind");
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId");
        this.description = description == null ? "" : description;
        this.area = area;
        this.cql = cql;
        try {
            this.hiddenColumns = JSON.writeValueAsString(hidden == null ? List.of() : hidden);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        this.createdBy = createdBy;
        this.createdAt = Objects.requireNonNull(now, "now");
    }

    public UUID id() {
        return id;
    }

    public UUID serviceId() {
        return serviceId;
    }

    /** The layer as the service names it, or null for every layer of the service. */
    public String layer() {
        return layer;
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

    public Instant createdAt() {
        return createdAt;
    }
}
