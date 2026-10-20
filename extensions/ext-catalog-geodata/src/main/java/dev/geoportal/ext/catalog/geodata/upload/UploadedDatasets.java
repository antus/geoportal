// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.upload;

import dev.mosaikit.kernel.api.audit.AuditLog;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import dev.mosaikit.kernel.api.files.FileStorage;
import dev.mosaikit.kernel.api.files.StoredFile;
import dev.mosaikit.kernel.api.quota.Quota;
import dev.mosaikit.kernel.api.quota.QuotaExceededException;
import dev.mosaikit.kernel.api.quota.Quotas;
import dev.mosaikit.kernel.api.resource.ResourceRef;
import dev.mosaikit.kernel.api.resource.Resources;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.InputStream;
import java.text.Normalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The lifecycle of uploaded data (GP-936): upload, rename, replace and delete. The content goes to
 * the file service of Mosaikit, attached to the registered resource, so that it counts to the storage
 * of the organization; the catalog keeps title, technical name, state and version.
 *
 * <p>The callers check permissions and sharing levels first; this class does what was allowed.
 */
@ApplicationScoped
public class UploadedDatasets {

    /** GP-983: the state of a dataset made from a file of the catalog, recorded on the file. */
    @jakarta.inject.Inject
    CatalogStates states;

    public static final String EXTENSION = "dev.geoportal.ext.catalog.geodata";
    public static final String TYPE = "dataset";
    /** The storage of the organization, as Quotas names it. */
    static final String STORAGE = "storage";

    private static final int MAX_TECHNICAL_NAME = 50;

    private final UploadedDataRepository uploads;
    private final FileStorage files;
    private final Resources resources;
    private final Quotas quotas;
    private final AuditLog audit;
    private final CurrentOrganization organization;
    private final DatasetLoading loading;

    public UploadedDatasets(UploadedDataRepository uploads, FileStorage files, Resources resources,
            Quotas quotas, AuditLog audit, CurrentOrganization organization, DatasetLoading loading) {
        this.uploads = uploads;
        this.files = files;
        this.resources = resources;
        this.quotas = quotas;
        this.audit = audit;
        this.organization = organization;
        this.loading = loading;
    }

    static ResourceRef ref(UUID id) {
        return new ResourceRef(EXTENSION, TYPE, id.toString());
    }

    static String link(UUID id) {
        return "/app/geodata?dataset=" + id;
    }

    /**
     * Stores a new dataset and registers it as a resource of the organization, owned by the person
     * of the request.
     *
     * @param length the size of the content when known, to refuse it before reading it; -1 otherwise
     * @param crs the reference system the person declares for a file that states none (GP-324), or
     *     {@code null}
     */
    public UploadedData upload(String fileName, String title, String description, InputStream content,
            long length, boolean sample, String crs) {
        return upload(fileName, title, description, content, length, sample, crs, null);
    }

    /** @param layer the layer of a file with several, or {@code null} */
    public UploadedData upload(String fileName, String title, String description, InputStream content,
            long length, boolean sample, String crs, String layer) {
        String name = fileName(fileName);
        Format format = format(name);
        Integer declared = declared(crs);
        fits(length);
        UploadedData data = create(name, title, description, sample, null);
        data.holds(store(data.id(), 1, name, format, content), format, Instant.now());
        return created(data, declared, layer);
    }

    /**
     * GP-983: the dataset of a file of the catalog of mosaikit-ext, made when the catalog tells the kind
     * of geodata files that it was stored (ADR-0028). The dataset is a resource whose parent is the file,
     * so that the levels on the file and on its folders reach it. Its content is the file, read through
     * the catalog (EXT-107): the geodata keep no copy, and the storage counts the file once.
     */
    public UploadedData fromCatalog(UUID file, String fileName, String contentType, long size) {
        String name = fileName(fileName);
        Format format = format(name);
        UploadedData data = create(name, null, "", false, file);
        data.follows(name, contentType, size, format, Instant.now());
        return created(data, null, null);
    }

    /** GP-983: the file of the catalog of a dataset has a new content (or name): loaded again, still without a copy. */
    public UploadedData followCatalog(UploadedData data, String fileName, String contentType, long size) {
        String name = fileName(fileName);
        Format format = format(name);
        UUID previousCopy = data.fileId();
        UUID previousCog = data.cogFileId();
        data.follows(name, contentType, size, format, Instant.now());
        if (previousCopy != null) {
            files.delete(EXTENSION, previousCopy);
        }
        record("dataset.replaced", data, Map.of());
        loading.load(data, null, previousCog, null);
        uploads.update(data);
        states.mirror(data);
        return data;
    }

    /**
     * GP-983: deletes the copy of the content that a dataset made from a file of the catalog kept before
     * EXT-107, once the catalog has shown that it serves the file: never on a guess, so a catalog that is
     * missing or misconfigured leaves the copy where it is. True when a copy was deleted.
     */
    public boolean dropCopy(UploadedData data, boolean catalogServesIt) {
        if (data.catalogFile() == null || data.fileId() == null || !catalogServesIt) {
            return false;
        }
        UUID copy = data.fileId();
        data.copyDropped();
        uploads.update(data);
        files.delete(EXTENSION, copy);
        record("dataset.copy-deleted", data, Map.of("file", copy.toString()));
        return true;
    }

    /**
     * GP-986, ADR-0030: the dataset of a table of a PostgreSQL connection, read where it is: registered as
     * a resource of the person, shared on its own, ready to publish; nothing is stored nor loaded.
     */
    UploadedData fromConnection(String table, String title, String geometryType, Long rows, String crs,
            dev.geoportal.ext.catalog.geodata.load.Extent nativeExtent, dev.geoportal.ext.catalog.geodata.load.Extent wgs84) {
        UploadedData data = create(table, title, "", false, null);
        data.mapped(table, table, geometryType, rows, crs, nativeExtent, wgs84, Instant.now());
        record("dataset.mapped", data, Map.of());
        uploads.insert(data);
        return data;
    }

    /** The reference of a file of the catalog of mosaikit-ext, the parent of its dataset. */
    public static ResourceRef catalogFile(UUID file) {
        return new ResourceRef(CATALOG, "file", file.toString());
    }

    /** The identifier of the file manager of mosaikit-ext (ext-catalog). */
    public static final String CATALOG = "io.github.mosaikit.ext.catalog";

    private UploadedData create(String name, String title, String description, boolean sample, UUID catalogFile) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String readable = title == null || title.isBlank() ? titleOf(name) : title.strip();
        UploadedData data = new UploadedData(id, organization.require(), readable,
                description == null ? "" : description.strip(), technicalName(name), sample, now);
        resources.register(ref(id), new Resources.Details(readable, link(id),
                catalogFile == null ? null : catalogFile(catalogFile)));
        data.madeFrom(catalogFile);
        return data;
    }

    private UploadedData created(UploadedData data, Integer declared, String layer) {
        record("dataset.uploaded", data, Map.of());
        loading.load(data, declared, null, blank(layer));
        uploads.insert(data);
        return data;
    }

    /** Changes the title and the description; the technical name stays. */
    public UploadedData describe(UploadedData data, String title, String description) {
        String before = data.title();
        String readable = title == null || title.isBlank() ? before : title.strip();
        data.describe(readable, description == null ? data.description() : description.strip(), Instant.now());
        resources.update(ref(data.id()), new Resources.Details(readable, link(data.id()),
                data.catalogFile() == null ? null : catalogFile(data.catalogFile())));
        if (data.tableName() != null) {
            loading.layer(data); // the layer takes the new title
        }
        uploads.update(data);
        states.mirror(data);
        record("dataset.renamed", data, Map.of("before", before));
        return data;
    }

    /** Replaces the content with a new file: a new version, loaded again (GP-937). */
    public UploadedData replace(UploadedData data, String fileName, InputStream content, long length, String crs,
            String layer) {
        String name = fileName(fileName);
        Format format = format(name);
        Integer declared = declared(crs);
        fits(length);
        UUID previous = data.fileId();
        UUID previousCog = data.cogFileId();
        data.holds(store(data.id(), data.version() + 1, name, format, content), format, Instant.now());
        files.delete(EXTENSION, previous);
        record("dataset.replaced", data, Map.of());
        loading.load(data, declared, previousCog, blank(layer));
        uploads.update(data);
        states.mirror(data);
        return data;
    }

    /**
     * Loads the current file again (GP-937): after the geodata database was configured or came back,
     * or with the reference system the person now declares for a file that states none.
     */
    public UploadedData reload(UploadedData data, String crs, String layer) {
        Integer declared = declared(crs);
        loading.load(data, declared, data.cogFileId(), blank(layer));
        uploads.update(data);
        states.mirror(data);
        return data;
    }

    private static String blank(String layer) {
        return layer == null || layer.isBlank() ? null : layer.strip();
    }

    /**
     * Follows the job of the loader for a loading dataset, and saves it (GP-937, ADR-0019): its progress
     * for anyone who reads it, its outcome only when {@code takeOver} (a person who edits the dataset).
     */
    public void advance(UploadedData data, boolean takeOver) {
        if (loading.advance(data, takeOver)) {
            uploads.update(data);
            states.mirror(data);
        }
    }

    /** The EPSG code a person declares, {@code null} for none; refused when it is not one. */
    static Integer declared(String crs) {
        if (crs == null || crs.isBlank()) {
            return null;
        }
        return dev.geoportal.ext.catalog.geodata.load.Crs.declared(crs).orElseThrow(() -> new UploadRefused(
                "«" + crs.strip() + "» is not an EPSG code: write it as EPSG:32632 or 32632."));
    }

    /** Deletes the dataset, its file and its registration with the grants it had. */
    public void delete(UploadedData data) {
        loading.unload(data);
        if (data.fileId() != null) {
            files.delete(EXTENSION, data.fileId());
        }
        resources.remove(ref(data.id()));
        uploads.delete(data);
        record("dataset.deleted", data, Map.of());
    }

    /** What the storage of the organization holds, and its limit when there is one. */
    public Quota space() {
        return quotas.check(EXTENSION, STORAGE, 0);
    }

    /** Refuses early, without reading it, a content the storage of the organization has no room for. */
    void fits(long length) {
        if (length <= 0) {
            return;
        }
        long limit = UploadLimit.bytes();
        if (length > limit) {
            throw new UploadLimit.TooLarge(limit);
        }
        Quota quota = quotas.check(EXTENSION, STORAGE, length);
        if (!quota.fits()) {
            throw new QuotaExceededException(
                    "The storage of the organization has no room for " + length + " more bytes.", quota);
        }
    }

    private StoredFile store(UUID id, int version, String name, Format format, InputStream content) {
        try {
            return files.store(EXTENSION,
                    FileStorage.NewFile.of("uploads/" + id + "/v" + version + "/" + name, format.contentType())
                            .attachedTo(ref(id)),
                    content);
        } catch (IllegalArgumentException e) {
            // The file service refuses a content beyond mosaikit.files.max-size while reading it, when its
            // size was not announced (issue #40).
            if (e.getCause() != null && e.getCause().getClass().getSimpleName().equals("ContentTooLargeException")) {
                throw new UploadLimit.TooLarge(UploadLimit.bytes());
            }
            throw e;
        } catch (IllegalStateException e) {
            // The file service refuses a content beyond the storage quota when it knows its size.
            if (e.getCause() != null && e.getCause().getClass().getSimpleName().equals("QuotaExceededException")) {
                throw new QuotaExceededException(e.getMessage(), space());
            }
            throw e;
        }
    }

    private void record(String action, UploadedData data, Map<String, ?> more) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", data.title());
        details.put("technicalName", data.technicalName());
        details.put("format", data.format());
        details.put("size", data.size());
        details.put("version", data.version());
        details.putAll(more);
        audit.record(EXTENSION, new AuditLog.Entry(action, TYPE + "/" + data.id(), AuditLog.Outcome.SUCCEEDED, details));
    }

    /** The name of the file without the folders a browser may send. */
    static String fileName(String fileName) {
        String name = fileName == null ? "" : fileName.strip();
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.length() > 200) {
            throw new UploadRefused("The file needs a name of 1 to 200 characters.");
        }
        return name;
    }

    static Format format(String name) {
        return Format.of(name).orElseThrow(() -> new UploadRefused(
                "The format of " + name + " is not one of the data the catalog loads: " + Format.accepted() + "."));
    }

    /** A readable title from the name of a file: «piste_ciclabili.geojson» is «Piste ciclabili». */
    static String titleOf(String name) {
        int dot = name.lastIndexOf('.');
        String base = (dot > 0 ? name.substring(0, dot) : name).replaceAll("[_\\-.]+", " ").strip();
        if (base.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    /**
     * The technical name of the table and layer of the dataset, unique in the organization: lowercase
     * ASCII letters, digits and '_', starting with a letter, from the name of the file.
     */
    String technicalName(String name) {
        String base = slug(name);
        List<String> taken = uploads.technicalNamesLike(base + "%");
        if (!taken.contains(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String candidate = base + "_" + n;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
    }

    static String slug(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ascii = Normalizer.normalize(base, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty() || !Character.isLetter(slug.charAt(0))) {
            slug = "d_" + slug;
        }
        slug = slug.length() > MAX_TECHNICAL_NAME ? slug.substring(0, MAX_TECHNICAL_NAME) : slug;
        return slug.replaceAll("_+$", "");
    }

    /** An upload the catalog refuses for what it is: answered with 400 and the reason. */
    public static final class UploadRefused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UploadRefused(String message) {
            super(message);
        }
    }
}
