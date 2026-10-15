// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.upload;

import dev.geoportal.catalog.load.Extent;
import dev.geoportal.catalog.load.Loader.Result;
import dev.mosaikit.kernel.api.files.StoredFile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A dataset uploaded to the catalog (GP-936): the readable title and description, the technical name
 * of the table and layer it becomes (GP-937), the state of its lifecycle and the file of Mosaikit
 * that holds its content.
 */
@Entity
@Table(name = "uploaded_data", schema = "ext_catalog")
public class UploadedData {

    /** Stored in the file service, not loaded into PostGIS or as COG yet (or waiting for the geodata database). */
    public static final String UPLOADED = "uploaded";
    /** Being loaded, inside the request that stored it (GP-937). */
    public static final String LOADING = "loading";
    /** Loaded: a table of PostGIS or a COG, ready for GeoServer to publish (GP-334). */
    public static final String LOADED = "loaded";
    /** Published as a layer by GeoServer (GP-334). */
    public static final String PUBLISHED = "published";
    /** Not loaded, for a problem of the file: the code and the reason say which. */
    public static final String FAILED = "failed";

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(name = "technical_name", nullable = false, length = 63)
    private String technicalName;

    @Column(nullable = false, length = 20)
    private String format;

    @Column(name = "file_id", nullable = false)
    private UUID fileId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 255)
    private String contentType;

    @Column(nullable = false)
    private long size;

    @Column(nullable = false, length = 20)
    private String state;

    @Column(name = "state_reason", length = 1000)
    private String stateReason;

    @Column(nullable = false)
    private int version;

    @Column(name = "state_code", length = 40)
    private String stateCode;

    @Column(length = 20)
    private String crs;

    @Column(name = "crs_source", length = 20)
    private String crsSource;

    @Column(name = "geometry_type", length = 30)
    private String geometryType;

    @Column(name = "has_z")
    private Boolean hasZ;

    private Long features;

    @Column(name = "native_min_x")
    private Double nativeMinX;

    @Column(name = "native_min_y")
    private Double nativeMinY;

    @Column(name = "native_max_x")
    private Double nativeMaxX;

    @Column(name = "native_max_y")
    private Double nativeMaxY;

    @Column(name = "wgs84_west")
    private Double west;

    @Column(name = "wgs84_south")
    private Double south;

    @Column(name = "wgs84_east")
    private Double east;

    @Column(name = "wgs84_north")
    private Double north;

    @Column(name = "table_name", length = 130)
    private String tableName;

    @Column(name = "cog_file_id")
    private UUID cogFileId;

    @Column(nullable = false)
    private long space;

    @Column(name = "loaded_at")
    private Instant loadedAt;

    @Column(name = "load_job", length = 64)
    private String loadJob;

    @Column(name = "load_table", length = 63)
    private String loadTable;

    @Column(name = "load_progress")
    private Integer loadProgress;

    @Column(length = 255)
    private String layer;

    @Column(columnDefinition = "text")
    private String layers;

    @Column(nullable = false)
    private boolean sample;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected UploadedData() {}

    UploadedData(UUID id, UUID organizationId, String title, String description, String technicalName,
            boolean sample, Instant now) {
        this.id = Objects.requireNonNull(id, "id");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId");
        this.title = title;
        this.description = description;
        this.technicalName = technicalName;
        this.sample = sample;
        this.state = UPLOADED;
        this.version = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** The file that now holds the content: a new version, to be loaded again. */
    void holds(StoredFile file, Format format, Instant now) {
        this.fileId = file.id();
        this.fileName = file.name().substring(file.name().lastIndexOf('/') + 1);
        this.contentType = file.contentType();
        this.size = file.size();
        this.format = format.id();
        this.version++;
        this.layer = null;
        this.layers = null;
        waiting(null, null, now);
        forget();
    }

    /** Not loaded yet: just stored, or waiting for the geodata database (with the code and reason). */
    void waiting(String code, String reason, Instant now) {
        jobDone();
        this.state = UPLOADED;
        this.stateCode = code;
        this.stateReason = reason;
        this.updatedAt = now;
    }

    void loading(Instant now) {
        this.state = LOADING;
        this.stateCode = null;
        this.stateReason = null;
        this.layers = null;
        this.updatedAt = now;
    }

    /** Loading by the loader sidecar (ADR-0019): its job, and the table it writes before it is taken over. */
    void submitted(String job, String table, String chosenLayer, Instant now) {
        loading(now);
        this.loadJob = job;
        this.loadTable = table;
        this.loadProgress = 0;
        this.layer = chosenLayer;
    }

    void progress(int percent) {
        this.loadProgress = percent;
    }

    /** The job of the loader is over, whatever its outcome. */
    void jobDone() {
        this.loadJob = null;
        this.loadTable = null;
        this.loadProgress = null;
    }

    /** The layer the loader loaded, and the layers of a file with several to choose from (JSON). */
    void layers(String chosenLayer, String json) {
        this.layer = chosenLayer;
        this.layers = json;
    }

    /** What the loading of the current version produced. */
    void loaded(Result result, UUID cog, Instant now) {
        jobDone();
        this.state = LOADED;
        this.stateCode = null;
        this.stateReason = null;
        this.crs = result.crs().code();
        this.crsSource = result.crs().source().id();
        this.geometryType = result.geometryType();
        this.hasZ = result.z();
        this.features = result.features();
        Extent n = result.nativeExtent();
        this.nativeMinX = n == null ? null : n.minX();
        this.nativeMinY = n == null ? null : n.minY();
        this.nativeMaxX = n == null ? null : n.maxX();
        this.nativeMaxY = n == null ? null : n.maxY();
        Extent w = result.wgs84();
        this.west = w == null ? null : w.minX();
        this.south = w == null ? null : w.minY();
        this.east = w == null ? null : w.maxX();
        this.north = w == null ? null : w.maxY();
        this.tableName = result.table();
        this.cogFileId = cog;
        this.space = result.bytes();
        this.loadedAt = now;
        this.updatedAt = now;
    }

    /** Not loaded, for a problem of the file; nothing of it stays in PostGIS. */
    void failed(String code, String reason, Instant now) {
        jobDone();
        this.state = FAILED;
        this.stateCode = code;
        this.stateReason = reason == null || reason.length() <= 1000 ? reason : reason.substring(0, 997) + "...";
        forget();
        this.updatedAt = now;
    }

    /** Forgets what a loading produced; the caller removed the table and the COG. */
    private void forget() {
        this.crs = null;
        this.crsSource = null;
        this.geometryType = null;
        this.hasZ = null;
        this.features = null;
        this.nativeMinX = null;
        this.nativeMinY = null;
        this.nativeMaxX = null;
        this.nativeMaxY = null;
        this.west = null;
        this.south = null;
        this.east = null;
        this.north = null;
        this.tableName = null;
        this.cogFileId = null;
        this.space = 0;
        this.loadedAt = null;
    }

    /** Served by at least one GeoServer layer (GP-114); only a loaded dataset is published. */
    void published(Instant now) {
        if (LOADED.equals(state) || PUBLISHED.equals(state)) {
            this.state = PUBLISHED;
            this.updatedAt = now;
        }
    }

    /** No layer serves it any more (GP-118): loaded, ready to be published again. */
    void unpublished(Instant now) {
        if (PUBLISHED.equals(state)) {
            this.state = LOADED;
            this.updatedAt = now;
        }
    }

    void describe(String title, String description, Instant now) {
        this.title = title;
        this.description = description;
        this.updatedAt = now;
    }

    UUID id() {
        return id;
    }

    String title() {
        return title;
    }

    String description() {
        return description;
    }

    String technicalName() {
        return technicalName;
    }

    UUID fileId() {
        return fileId;
    }

    String fileName() {
        return fileName;
    }

    String contentType() {
        return contentType;
    }

    long size() {
        return size;
    }

    String format() {
        return format;
    }

    String state() {
        return state;
    }

    String stateReason() {
        return stateReason;
    }

    int version() {
        return version;
    }

    String stateCode() {
        return stateCode;
    }

    String crs() {
        return crs;
    }

    String crsSource() {
        return crsSource;
    }

    String geometryType() {
        return geometryType;
    }

    Boolean hasZ() {
        return hasZ;
    }

    Long features() {
        return features;
    }

    /** The extent in the reference system of the data, or {@code null}. */
    Extent nativeExtent() {
        return nativeMinX == null ? null : new Extent(nativeMinX, nativeMinY, nativeMaxX, nativeMaxY);
    }

    /** The extent in WGS84 degrees, or {@code null} when it could not be computed reliably. */
    Extent wgs84() {
        return west == null ? null : new Extent(west, south, east, north);
    }

    String tableName() {
        return tableName;
    }

    UUID cogFileId() {
        return cogFileId;
    }

    long space() {
        return space;
    }

    Instant loadedAt() {
        return loadedAt;
    }

    String loadJob() {
        return loadJob;
    }

    String loadTable() {
        return loadTable;
    }

    Integer loadProgress() {
        return loadProgress;
    }

    String layer() {
        return layer;
    }

    String layersJson() {
        return layers;
    }

    boolean sample() {
        return sample;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
