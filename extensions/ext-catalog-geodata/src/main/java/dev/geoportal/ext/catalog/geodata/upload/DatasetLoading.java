// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.upload;

import static dev.geoportal.ext.catalog.geodata.upload.UploadedDatasets.EXTENSION;
import static dev.geoportal.ext.catalog.geodata.upload.UploadedDatasets.TYPE;
import static dev.geoportal.ext.catalog.geodata.upload.UploadedDatasets.ref;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.ext.catalog.geodata.load.Crs;
import dev.geoportal.ext.catalog.geodata.load.Extent;
import dev.geoportal.ext.catalog.geodata.load.Geodata;
import dev.geoportal.ext.catalog.geodata.load.LoaderClient;
import dev.geoportal.ext.catalog.geodata.load.LoadRefused;
import dev.geoportal.ext.catalog.geodata.load.Loader;
import dev.mosaikit.kernel.api.audit.AuditLog;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import dev.mosaikit.kernel.api.files.FileContent;
import dev.mosaikit.kernel.api.files.FileStorage;
import dev.mosaikit.kernel.api.files.StoredFile;
import dev.mosaikit.kernel.api.quota.QuotaExceededException;
import dev.mosaikit.kernel.api.quota.Quotas;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Loads the current version of an uploaded dataset (GP-937): a vector into a table of the schema of the
 * organization in the geodata database, named by the technical name of the dataset; a raster into a
 * Cloud Optimized GeoTIFF stored by the file service of Mosaikit. The extent goes into the record of
 * the dataset (GP-321), with the reference system and where it comes from (GP-324); the space of the
 * table counts to the storage of the organization ({@code Quotas.reportSpace}); every outcome is in the
 * audit log. Loading publishes nothing (GP-114): the layers that already publish the dataset are
 * republished with the new table, or withdrawn when the dataset is no longer a table.
 *
 * <p><strong>Who loads.</strong> The loader sidecar (ADR-0019) when the installation has one
 * ({@code geoportal.catalog.loader.url}) and the format is one it loads ({@link Format#gdal()}): the
 * request that stores the file submits a job with a signed link to it, and the dataset is
 * {@code loading} until the job ends. Otherwise the readers of the catalog load it inside that request.
 *
 * <p><strong>When a job ends.</strong> An extension cannot run work for an organization without a person
 * yet (MK-126), so the catalog takes the outcome of a job over in the next request that reads the
 * dataset ({@link #advance}): the page asks every few seconds while a dataset is loading. The row is
 * locked while it is taken over, so that two requests do not do it twice.
 *
 * <p><strong>Outcomes.</strong> A problem of the file ({@link LoadRefused}, or the quota) makes the
 * dataset {@code failed} with a code and the reason, and nothing of it stays in PostGIS: the
 * transaction of the geodata database rolls back and an earlier table of the dataset is dropped. A
 * geodata database that is not configured or does not answer leaves it {@code uploaded}, with the code
 * that says so, to be loaded again later.
 */
@ApplicationScoped
public class DatasetLoading {

    private static final Logger LOG = Logger.getLogger(DatasetLoading.class);

    private final Geodata geodata;
    private final FileStorage files;
    private final Quotas quotas;
    private final AuditLog audit;
    private final CurrentOrganization organization;
    private final DatasetPublishing publishing;
    private final EntityManager entities;

    public DatasetLoading(Geodata geodata, FileStorage files, Quotas quotas, AuditLog audit,
            CurrentOrganization organization, DatasetPublishing publishing, EntityManager entities) {
        this.geodata = geodata;
        this.files = files;
        this.quotas = quotas;
        this.audit = audit;
        this.organization = organization;
        this.publishing = publishing;
        this.entities = entities;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    /** How long the signed link to the upload works for the loader. */
    private static final Duration LINK = Duration.ofHours(2);

    /**
     * Loads the current file of a dataset.
     *
     * @param declared the EPSG code the person declares, used only when the file states no system (GP-326)
     * @param previousCog the COG of an earlier version, deleted once this one is loaded or refused
     * @param layer the layer of a file with several, chosen by the person, or {@code null}
     */
    void load(UploadedData data, Integer declared, UUID previousCog, String layer) {
        Instant now = Instant.now();
        Format format = Format.byId(data.format());
        String schema = Geodata.schema(organization.require());
        Optional<Connection> opened;
        try {
            opened = geodata.connect();
        } catch (Geodata.Unavailable e) {
            data.waiting("geodata-unavailable", e.getMessage(), now);
            record("dataset.load-postponed", data, AuditLog.Outcome.FAILED, Map.of("code", "geodata-unavailable"));
            return;
        }
        if (opened.isEmpty()) {
            data.waiting("geodata-missing", "The organization has no credential «" + Geodata.CREDENTIAL
                    + "» for the geodata database: the file is stored, not loaded.", now);
            return;
        }
        data.loading(now);
        Optional<LoaderClient> loader = Geodata.loader();
        if (loader.isPresent() && format.gdal()) {
            // The schema of the organization, where the loader writes; the alignment of GP-334 makes it too.
            try (Connection connection = opened.get()) {
                Loader.createSchema(connection, schema);
                connection.commit();
            } catch (SQLException e) {
                data.waiting("geodata-unavailable", "The geodata database failed: " + e.getMessage(), Instant.now());
                return;
            }
            submit(loader.get(), data, format, schema, declared, previousCog, layer);
            return;
        }
        try (Connection connection = opened.get()) {
            UUID cog = null;
            try {
                Loader.Result result = Loader.load(connection, format, content(data), schema, data.technicalName(), declared, layer);
                if (result.cog() != null) {
                    Loader.drop(connection, schema, data.technicalName()); // the table of an earlier vector version
                    cog = storeCog(data, result.cog());
                }
                quotas.reportSpace(ref(data.id()), result.bytes());
                connection.commit();
                if (previousCog != null) {
                    deleteFile(previousCog);
                }
                data.loaded(result, cog, Instant.now());
                layer(data);
                record("dataset.loaded", data, AuditLog.Outcome.SUCCEEDED, loaded(data));
            } catch (LoadRefused e) {
                data.layers(layer, e.layers().isEmpty() ? null : layersJson(e.layers().stream()
                        .map(n -> new LoaderClient.Layer(n, 0, null)).toList()));
                refuse(connection, data, schema, previousCog, cog, e.code().id(), e.getMessage());
            } catch (QuotaExceededException e) {
                refuse(connection, data, schema, previousCog, cog, LoadRefused.Code.QUOTA.id(),
                        "The storage of the organization has no room for the loaded data: " + e.getMessage());
            }
        } catch (SQLException e) {
            LOG.warnf("Loading %s into the geodata database failed: %s", data.id(), e.getMessage());
            data.waiting("geodata-unavailable", "The geodata database failed while loading: " + e.getMessage(), Instant.now());
            record("dataset.load-postponed", data, AuditLog.Outcome.FAILED, Map.of("code", "geodata-unavailable"));
        } catch (IllegalStateException e) {
            data.waiting("geodata-unavailable", e.getMessage(), Instant.now());
            record("dataset.load-postponed", data, AuditLog.Outcome.FAILED, Map.of("code", "geodata-unavailable"));
        }
    }

    // The loader sidecar -----------------------------------------------------------------------------

    /** Submits the file to the loader: a signed link to it, the target and the options. */
    private void submit(LoaderClient loader, UploadedData data, Format format, String schema, Integer declared,
            UUID previousCog, String layer) {
        String table = loadTable(data);
        try {
            LoaderClient.Target target = null;
            if (format.vector()) {
                target = geodata.loaderTarget(schema, table).orElseThrow(() -> new Geodata.Unavailable(
                        "The organization has no credential " + Geodata.CREDENTIAL + ".", null));
            }
            String source = Geodata.loaderSource() + files.link(EXTENSION, data.fileId(), LINK).path();
            String job = loader.submit(new LoaderClient.Job(source, data.fileName(), format.id(), declared,
                    Loader.formatCrs(format, firstLine(data)), layer, target));
            if (target != null) {
                java.util.Arrays.fill(target.password(), '\0');
            }
            if (previousCog != null) {
                deleteFile(previousCog);
            }
            data.submitted(job, table, layer, Instant.now());
            record("dataset.load-started", data, AuditLog.Outcome.SUCCEEDED, Map.of("job", job));
        } catch (RuntimeException e) {
            LOG.warnf("The loader did not take the job of %s: %s", data.id(), e.getMessage());
            data.waiting("loader-unavailable", "The loader does not answer: " + e.getMessage(), Instant.now());
            record("dataset.load-postponed", data, AuditLog.Outcome.FAILED, Map.of("code", "loader-unavailable"));
        }
    }

    /**
     * Takes over the outcome of the job of a loading dataset, if it ended: the table the loader wrote
     * becomes the table of the dataset, or the COG is stored, or the dataset fails with the reason.
     * Joins the transaction of the request; the caller saves the dataset.
     *
     * <p>The person of the request stores the COG and keeps full access to that file (MK-041), so only a
     * person who edits the dataset takes the outcome over ({@code takeOver}); for the others the dataset
     * stays {@code loading}, at 100%, until such a person reads it. A takeover in the background waits
     * for MK-126. The COG is attached to the resource of the dataset, so who else reads it follows the
     * sharing of the dataset.
     *
     * @return whether the dataset changed
     */
    boolean advance(UploadedData data, boolean takeOver) {
        if (!UploadedData.LOADING.equals(data.state()) || data.loadJob() == null) {
            return false;
        }
        Object locked = entities.createNativeQuery("select load_job from ext_catalog_geodata.uploaded_data where id = ?1 for update")
                .setParameter(1, data.id())
                .getResultList().stream().findFirst().orElse(null);
        if (!data.loadJob().equals(locked)) {
            return false; // another request took it over, or the row is not saved yet
        }
        Optional<LoaderClient> loader = Geodata.loader();
        if (loader.isEmpty()) {
            data.waiting("loader-unavailable", "The installation has no loader any more: load the file again.", Instant.now());
            return true;
        }
        String job = data.loadJob();
        Optional<LoaderClient.Status> found;
        try {
            found = loader.get().status(job);
        } catch (RuntimeException e) {
            return false; // asked again at the next reading
        }
        if (found.isEmpty()) {
            data.waiting("loader-lost", "The loader forgot the job (it restarted): load the file again.", Instant.now());
            return true;
        }
        LoaderClient.Status status = found.get();
        if (!status.finished() || !takeOver) {
            data.progress(status.progress());
            return true;
        }
        String schema = Geodata.schema(organization.require());
        try {
            Optional<Connection> opened = geodata.connect();
            if (opened.isEmpty()) {
                data.waiting("geodata-missing", "The organization has no credential \u00ab" + Geodata.CREDENTIAL + "\u00bb.", Instant.now());
                return true;
            }
            try (Connection connection = opened.get()) {
                if ("failed".equals(status.state())) {
                    failedJob(connection, data, schema, status);
                } else {
                    doneJob(loader.get(), connection, data, schema, status);
                }
            }
        } catch (SQLException | Geodata.Unavailable e) {
            LOG.warnf("Taking over the loading of %s failed: %s", data.id(), e.getMessage());
            return false;
        }
        loader.get().forget(job);
        return true;
    }

    private void failedJob(Connection connection, UploadedData data, String schema, LoaderClient.Status status)
            throws SQLException {
        Loader.drop(connection, schema, data.loadTable());
        connection.commit();
        if ("loader-error".equals(status.code())) {
            data.waiting("loader-error", status.reason(), Instant.now());
            record("dataset.load-postponed", data, AuditLog.Outcome.FAILED, Map.of("code", "loader-error"));
            return;
        }
        data.layers(status.layer(), status.layers().size() > 1 ? layersJson(status.layers()) : null);
        refuse(connection, data, schema, null, null, status.code() == null ? "unreadable" : status.code(), status.reason());
    }

    private void doneJob(LoaderClient loader, Connection connection, UploadedData data, String schema,
            LoaderClient.Status status) throws SQLException {
        Crs crs = new Crs(Integer.parseInt(status.crs().substring(5)),
                Crs.Source.valueOf(status.crsSource().toUpperCase(java.util.Locale.ROOT)));
        UUID cog = null;
        try {
            Loader.Result result;
            if (data.loadTable() != null && Format.byId(data.format()).vector()) {
                result = Loader.adopt(connection, schema, data.loadTable(), data.technicalName(), crs);
            } else {
                Loader.drop(connection, schema, data.technicalName()); // the table of an earlier vector version
                try (InputStream in = loader.result(data.loadJob())) {
                    cog = storeCog(data, in);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                result = new Loader.Result(null, null, crs, "Raster", false, status.features(), extent(status.nativeExtent()),
                        extent(status.wgs84()), 0);
            }
            quotas.reportSpace(ref(data.id()), result.bytes());
            connection.commit();
            data.layers(status.layer(), status.layers().size() > 1 ? layersJson(status.layers()) : null);
            data.loaded(result, cog, Instant.now());
            layer(data);
            record("dataset.loaded", data, AuditLog.Outcome.SUCCEEDED, loaded(data));
        } catch (LoadRefused e) {
            connection.rollback();
            Loader.drop(connection, schema, data.loadTable());
            refuse(connection, data, schema, null, cog, e.code().id(), e.getMessage());
        } catch (QuotaExceededException e) {
            connection.rollback();
            Loader.drop(connection, schema, data.loadTable());
            refuse(connection, data, schema, null, cog, LoadRefused.Code.QUOTA.id(),
                    "The storage of the organization has no room for the loaded data: " + e.getMessage());
        }
    }

    /** The table the loader writes: the technical name and a mark of the job, at most 63 characters. */
    private static String loadTable(UploadedData data) {
        String mark = "__l" + UUID.randomUUID().toString().substring(0, 8);
        String name = data.technicalName();
        return (name.length() + mark.length() > 63 ? name.substring(0, 63 - mark.length()) : name) + mark;
    }

    /** The first line of the file, for the loader to know what a CSV defines; read, not the whole file. */
    private String firstLine(UploadedData data) {
        if (!"csv".equals(data.format())) {
            return null;
        }
        try (FileContent content = files.read(EXTENSION, data.fileId())) {
            byte[] head = content.stream().readNBytes(65536);
            String text = new String(head, StandardCharsets.UTF_8).replace("\uFEFF", "");
            int end = text.indexOf('\n');
            return (end < 0 ? text : text.substring(0, end)).replace("\r", "");
        } catch (IOException e) {
            return null;
        }
    }

    private static Extent extent(double[] box) {
        return box == null ? null : new Extent(box[0], box[1], box[2], box[3]);
    }

    private static String layersJson(List<LoaderClient.Layer> layers) {
        try {
            return JSON.writeValueAsString(layers);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * The layers that publish the dataset follow its data (GP-114, GP-118): republished under the same
     * names with the table loaded again, or withdrawn when the dataset is not a table any more (a
     * raster, failed). Publishing itself is a step of its own (DatasetPublishing).
     */
    void layer(UploadedData data) {
        publishing.dataChanged(data);
    }

    /** Removes what the dataset has in the geodata database, when it is deleted. */
    void unload(UploadedData data) {
        publishing.datasetGone(data);
        if (data.loadJob() != null) {
            Geodata.loader().ifPresent(l -> l.forget(data.loadJob()));
        }
        if (data.cogFileId() != null) {
            deleteFile(data.cogFileId());
        }
        if (data.tableName() == null && data.loadTable() == null) {
            return;
        }
        try {
            Optional<Connection> opened = geodata.connect();
            if (opened.isPresent()) {
                try (Connection connection = opened.get()) {
                    Loader.drop(connection, Geodata.schema(organization.require()), data.technicalName());
                    if (data.loadTable() != null) {
                        Loader.drop(connection, Geodata.schema(organization.require()), data.loadTable());
                    }
                    connection.commit();
                }
            }
        } catch (SQLException | Geodata.Unavailable e) {
            LOG.warnf("The table %s of a deleted dataset stays in the geodata database: %s", data.tableName(), e.getMessage());
        }
    }

    private void refuse(Connection connection, UploadedData data, String schema, UUID previousCog, UUID cog,
            String code, String reason) throws SQLException {
        connection.rollback();
        Loader.drop(connection, schema, data.technicalName());
        connection.commit();
        quotas.reportSpace(ref(data.id()), 0);
        for (UUID file : new UUID[] {previousCog, cog}) {
            if (file != null) {
                deleteFile(file);
            }
        }
        data.failed(code, reason, Instant.now());
        layer(data);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", code);
        details.put("reason", data.stateReason());
        record("dataset.load-failed", data, AuditLog.Outcome.FAILED, details);
    }

    private byte[] content(UploadedData data) {
        try (FileContent content = files.read(EXTENSION, data.fileId())) {
            return content.stream().readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("The file of the dataset cannot be read", e);
        }
    }

    private UUID storeCog(UploadedData data, byte[] cog) {
        return storeCog(data, new ByteArrayInputStream(cog));
    }

    private UUID storeCog(UploadedData data, InputStream cog) {
        try {
            StoredFile stored = files.store(EXTENSION,
                    FileStorage.NewFile.of("uploads/" + data.id() + "/v" + data.version() + "/" + data.technicalName() + ".cog.tif",
                            "image/tiff").attachedTo(ref(data.id())),
                    cog);
            return stored.id();
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new LoadRefused(LoadRefused.Code.QUOTA, "The Cloud Optimized GeoTIFF cannot be stored: " + e.getMessage(), e);
        }
    }

    private void deleteFile(UUID id) {
        try {
            files.delete(EXTENSION, id);
        } catch (NoSuchElementException | SecurityException e) {
            LOG.debugf("The COG %s was not deleted: %s", id, e.getMessage());
        }
    }

    private static Map<String, Object> loaded(UploadedData data) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("crs", data.crs());
        details.put("crsSource", data.crsSource());
        details.put("geometryType", data.geometryType());
        details.put("features", data.features());
        details.put("table", data.tableName());
        details.put("space", data.space());
        Extent e = data.wgs84();
        details.put("extent", e == null ? null : List.of(e.minX(), e.minY(), e.maxX(), e.maxY()));
        return details;
    }

    private void record(String action, UploadedData data, AuditLog.Outcome outcome, Map<String, ?> more) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", data.title());
        details.put("technicalName", data.technicalName());
        details.put("format", data.format());
        details.put("version", data.version());
        details.putAll(more);
        audit.record(EXTENSION, new AuditLog.Entry(action, TYPE + "/" + data.id(), outcome, details));
    }
}
