// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An external service registered in the catalog of an organization (GP-939): an OGC service of
 * another server, which the map reaches directly or through the proxy of the catalog. Who may see,
 * use or manage it is kept by the sharing of Mosaikit, as the resource type {@code service}.
 */
@Entity
@Table(name = "external_service", schema = "ext_catalog_geodata")
public class ExternalService {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 10)
    private String kind;

    @Column(nullable = false, length = 2000)
    private String url;

    @Column(nullable = false)
    private boolean proxy;

    @Column(length = 40)
    private String crs;

    @Column(length = 100)
    private String credential;

    @Column(name = "auth_scheme", length = 10)
    private String authScheme;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected ExternalService() {}

    public ExternalService(UUID organizationId, ServiceInput values, Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.createdAt = Objects.requireNonNull(now, "now");
        apply(values, now);
    }

    void apply(ServiceInput values, Instant now) {
        this.title = values.title().strip();
        this.kind = values.kind();
        this.url = values.url().strip();
        this.proxy = Boolean.TRUE.equals(values.proxy());
        this.crs = values.crs();
        this.credential = values.credential();
        this.authScheme = values.credential() == null ? null : values.authScheme();
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String kind() {
        return kind;
    }

    public String url() {
        return url;
    }

    public boolean proxy() {
        return proxy;
    }

    public String crs() {
        return crs;
    }

    public String credential() {
        return credential;
    }

    public String authScheme() {
        return authScheme;
    }

    /** The service as the API returns it: the name of its credential at most, never a value. */
    ServiceView view(boolean manage) {
        return new ServiceView(
                id, title, kind, url, proxy, crs, credential, authScheme, manage,
                "/api/v1/ext/catalog-geodata/services/" + id + "/proxy", null);
    }

    /** Turns the proxy on, when the browser cannot read the service directly (GP-297). */
    void proxyOn(Instant now) {
        this.proxy = true;
        this.updatedAt = now;
    }
}
