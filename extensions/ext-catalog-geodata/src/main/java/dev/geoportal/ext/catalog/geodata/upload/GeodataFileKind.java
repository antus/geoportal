// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.upload;

import dev.geoportal.ext.catalog.geodata.access.AccessRules;
import dev.geoportal.ext.catalog.geodata.load.Recognition;
import dev.mosaikit.kernel.api.quota.QuotaExceededException;
import io.github.mosaikit.ext.catalog.api.v1.CatalogFile;
import io.github.mosaikit.ext.catalog.api.v1.FileEvent;
import io.github.mosaikit.ext.catalog.api.v1.FileKind;
import io.github.mosaikit.ext.catalog.api.v1.FileSample;
import io.github.mosaikit.ext.catalog.api.v1.FileState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * GP-983, ADR-0028: geodata are a kind of file of the catalog of mosaikit-ext (EXT-105). A file of a
 * format the geodata load becomes a dataset: stored, it is copied and loaded into PostGIS or COG as an
 * upload is (GP-936, GP-937), with its state recorded on the file; replaced, it is loaded again;
 * deleted, its dataset goes with its table, its COG and its grants; shared differently, the GeoFence
 * rules of its published layers are written again. The catalog shows the preview, the states and the
 * action Publish that the frontend contributes to {@code catalog.file-kind}.
 */
@ApplicationScoped
public class GeodataFileKind implements FileKind {

    /** The name of the kind, as the catalog keeps it on the file: {@code <extension>/dataset}. */
    public static final String NAME = "dataset";

    private static final Logger LOG = Logger.getLogger(GeodataFileKind.class);
    private static final byte[] ZIP = {'P', 'K', 3, 4};
    private static final byte[] SQLITE = "SQLite format 3".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TIFF_LE = {'I', 'I', 42, 0};
    private static final byte[] TIFF_BE = {'M', 'M', 0, 42};
    private static final byte[] BIGTIFF_LE = {'I', 'I', 43, 0};
    private static final byte[] BIGTIFF_BE = {'M', 'M', 0, 43};
    /** The members of a zipped Shapefile, one of which names the first entry of the archive. */
    private static final Set<String> SHAPEFILE_PARTS = Set.of("shp", "shx", "dbf", "prj", "cpg", "sbn", "sbx", "qix", "xml");

    private final UploadedDataRepository uploads;
    private final UploadedDatasets datasets;
    private final CatalogStates states;
    private final DatasetPublishing publishing;
    private final AccessRules access;

    public GeodataFileKind(UploadedDataRepository uploads, UploadedDatasets datasets, CatalogStates states,
            DatasetPublishing publishing, AccessRules access) {
        this.uploads = uploads;
        this.datasets = datasets;
        this.states = states;
        this.publishing = publishing;
        this.access = access;
    }

    @Override
    public String extension() {
        return UploadedDatasets.EXTENSION;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * A file of a format the geodata load, by the extension of its name, and, for the extensions that
     * other files share, by its first bytes: a zip whose first entry is a part of a Shapefile, a JSON
     * that is GeoJSON, a CSV whose header names a location, a GeoPackage or a TIFF by their signature.
     */
    @Override
    public boolean recognizes(FileSample sample) {
        Optional<Format> format = Format.of(sample.name());
        if (format.isEmpty()) {
            return false;
        }
        String extension = sample.extension();
        return switch (format.get()) {
            case SHAPEFILE -> sample.startsWith(ZIP) && (sample.name().toLowerCase(Locale.ROOT).endsWith(".shp.zip")
                    || shapefileFirst(sample.head()));
            case GEOJSON -> extension.equals("geojson") || geojson(text(sample.head()));
            case CSV -> Recognition.csvLocates(firstLine(text(sample.head())));
            case GEOPACKAGE -> sample.startsWith(SQLITE);
            case GEOTIFF -> sample.startsWith(TIFF_LE) || sample.startsWith(TIFF_BE) || sample.startsWith(BIGTIFF_LE)
                    || sample.startsWith(BIGTIFF_BE);
            case KMZ -> sample.startsWith(ZIP);
            default -> true;
        };
    }

    @Override
    @Transactional
    public void changed(FileEvent event) {
        CatalogFile file = event.file();
        switch (event.change()) {
            case STORED, REPLACED, RECOGNIZED -> stored(file, event.change());
            // Its layers are withdrawn, its table dropped and its COG deleted with it (GP-118).
            case DELETED -> uploads.byCatalogFile(file.id()).ifPresent(datasets::delete);
            case SHARING_CHANGED -> uploads.byCatalogFile(file.id()).ifPresent(data -> {
                // The levels on the file and its folders reach the dataset (MK-136): its published layers get
                // the GeoFence rules of the new sharing now, not at the next check (GP-335).
                if (!publishing.layersOf(data.id()).isEmpty()) {
                    access.rewrite("sharing of the file " + file.name());
                }
            });
            case RENAMED, MOVED -> {
                // The dataset keeps its title, its technical name and its layers: only the file moved.
            }
        }
    }

    /** A file stored, replaced, or found again when the geodata came back: its dataset, loaded. */
    private void stored(CatalogFile file, FileEvent.Change change) {
        Optional<UploadedData> known = uploads.byCatalogFile(file.id());
        if (known.isPresent() && change == FileEvent.Change.RECOGNIZED) {
            states.record(file, known.get());
            return;
        }
        try (InputStream content = file.open()) {
            UploadedData data = known.isPresent()
                    ? datasets.replace(known.get(), file.name(), content, file.size(), null, null)
                    : datasets.fromCatalog(file.id(), file.name(), content, file.size());
            states.record(file, data);
        } catch (UploadedDatasets.UploadRefused | QuotaExceededException | UploadLimit.TooLarge e) {
            // The file stays a file of the catalog; the geodata say why it is not a dataset.
            file.record(FileState.of(UploadedData.FAILED, detail(e.getMessage())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            LOG.warnf("The geodata of the file %s are made again at the next event: %s", file.id(), e.toString());
            throw e;
        }
    }

    private static String detail(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > FileState.MAX_DETAIL ? message.substring(0, FileState.MAX_DETAIL - 1) + "…" : message;
    }

    /** Whether the first entry of a zip is a part of a Shapefile: its name is in its local header. */
    static boolean shapefileFirst(byte[] head) {
        if (head.length < 30) {
            return false;
        }
        int length = (head[26] & 0xff) | (head[27] & 0xff) << 8;
        if (30 + length > head.length) {
            return false;
        }
        String name = new String(head, 30, length, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SHAPEFILE_PARTS.contains(name.substring(dot + 1));
    }

    /** Whether a JSON looks like GeoJSON in its first bytes. */
    static boolean geojson(String text) {
        String compact = text.replaceAll("\\s+", "");
        return compact.contains("\"FeatureCollection\"") || compact.contains("\"type\":\"Feature\"")
                || compact.contains("\"coordinates\":");
    }

    private static String text(byte[] head) {
        return new String(head, StandardCharsets.UTF_8);
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return (end < 0 ? text : text.substring(0, end)).replace("\r", "");
    }
}
