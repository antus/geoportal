// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.upload;

import io.github.mosaikit.ext.catalog.api.v1.CatalogFile;
import io.github.mosaikit.ext.catalog.api.v1.CatalogFiles;
import io.github.mosaikit.ext.catalog.api.v1.FileState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * GP-983: the state of a dataset made from a file of the catalog, recorded on that file, which the
 * catalog shows in its list and in the detail of the file (EXT-105): {@code uploaded} (not loaded yet,
 * with why), {@code loading}, {@code loaded}, {@code published} or {@code failed} (with why).
 */
@ApplicationScoped
public class CatalogStates {

    private static final Logger LOG = Logger.getLogger(CatalogStates.class);

    private final Instance<CatalogFiles> catalog;

    public CatalogStates(Instance<CatalogFiles> catalog) {
        this.catalog = catalog;
    }

    /** The state of a dataset as its file shows it. */
    static FileState of(UploadedData data) {
        String detail = switch (data.state()) {
            case UploadedData.LOADED, UploadedData.PUBLISHED -> loaded(data);
            default -> data.stateReason();
        };
        if (detail != null && detail.length() > FileState.MAX_DETAIL) {
            detail = detail.substring(0, FileState.MAX_DETAIL - 1) + "…";
        }
        return FileState.of(data.state(), detail);
    }

    private static String loaded(UploadedData data) {
        StringBuilder what = new StringBuilder();
        if (data.features() != null && data.tableName() != null) {
            what.append(data.features() == 1 ? "1 feature" : data.features() + " features");
        } else if (data.cogFileId() != null) {
            what.append("Cloud Optimized GeoTIFF");
        }
        if (data.crs() != null) {
            what.append(what.isEmpty() ? "" : ", ").append(data.crs());
        }
        return what.isEmpty() ? null : what.toString();
    }

    /** Records the state on the file the catalog told the kind about (in the job of its event). */
    void record(CatalogFile file, UploadedData data) {
        file.record(of(data));
    }

    /**
     * Records the state of a dataset on its file after a change made outside an event of the catalog:
     * the end of a loading, a publication. Nothing for a dataset uploaded to the geodata themselves.
     */
    public void mirror(UploadedData data) {
        if (data.catalogFile() == null || !catalog.isResolvable()) {
            return;
        }
        try {
            Optional<CatalogFile> file = catalog.get().find(UploadedDatasets.EXTENSION, data.catalogFile());
            file.ifPresent(f -> f.record(of(data)));
        } catch (RuntimeException e) {
            // The state of the file is what people read in the catalog, never a reason to fail the change.
            LOG.debugf("The state of the file %s is recorded at its next event: %s", data.catalogFile(), e.toString());
        }
    }
}
