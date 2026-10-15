// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.upload;

import io.github.mosaikit.ext.catalog.api.v1.CatalogFile;
import io.github.mosaikit.ext.catalog.api.v1.CatalogFiles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * GP-983, EXT-107: the content of a dataset made from a file of the catalog, read through the catalog
 * (in a job of the organization or in a request of a person who reads the file), never copied: a stream
 * for the readers of the geodata, a signed link for the loader sidecar.
 */
@ApplicationScoped
public class CatalogContent {

    private final Instance<CatalogFiles> catalog;

    public CatalogContent(Instance<CatalogFiles> catalog) {
        this.catalog = catalog;
    }

    /** The file of the catalog of a dataset, when the catalog is there and the file can be read. */
    public Optional<CatalogFile> file(UploadedData data) {
        if (data.catalogFile() == null || !catalog.isResolvable()) {
            return Optional.empty();
        }
        return catalog.get().find(UploadedDatasets.EXTENSION, data.catalogFile());
    }

    private CatalogFile required(UploadedData data) {
        return file(data).orElseThrow(() -> new IllegalStateException(
                "The file of the catalog of the dataset " + data.title() + " cannot be read now."));
    }

    /** Its content; close the stream. */
    public InputStream open(UploadedData data) {
        return required(data).open();
    }

    /** The path of a signed link to its content, after the address of the installation. */
    public String link(UploadedData data, Duration validFor) {
        return required(data).link(validFor).path();
    }
}
