// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.upload;

import static dev.geoportal.ext.catalog.geodata.upload.UploadedDatasets.EXTENSION;

import dev.geoportal.ext.catalog.geodata.geoserver.Failure;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerClient;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerException;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerGovernance;
import dev.geoportal.ext.catalog.geodata.geoserver.LayerDefinition;
import dev.geoportal.ext.catalog.geodata.geoserver.PublishedLayer;
import dev.mosaikit.kernel.api.audit.AuditLog;
import dev.mosaikit.kernel.api.files.FileContent;
import dev.mosaikit.kernel.api.files.FileStorage;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * The publication of a raster dataset (GP-309): its Cloud Optimized GeoTIFF becomes a coverage of the
 * GeoServer workspace of the organization, in a coverage store named as the layer, with one coverage and
 * one layer of that name. GeoServer keeps the file it receives in its data directory; the COG stays in the
 * file service of Mosaikit. The layer is a published layer of the catalog like a vector (sharing, GeoFence
 * rules, the gate, health), marked {@code raster}; the alignment leaves its coverage store alone, and the
 * data rules on it limit the area, which GeoFence clips.
 *
 * <p>The minimum for the data rules of GP-309: no style of Geoportal (the default raster style of
 * GeoServer), republishing is publishing the file again, repointing is refused.
 */
@ApplicationScoped
public class RasterPublishing {

    /** GP-983: the state of a dataset made from a file of the catalog, recorded on the file. */
    @jakarta.inject.Inject
    CatalogStates states;

    private static final Logger LOG = Logger.getLogger(RasterPublishing.class);

    private final GeoServerGovernance geoserver;
    private final UploadedDataRepository uploads;
    private final FileStorage files;
    private final AuditLog audit;

    public RasterPublishing(GeoServerGovernance geoserver, UploadedDataRepository uploads, FileStorage files,
            AuditLog audit) {
        this.geoserver = geoserver;
        this.uploads = uploads;
        this.files = files;
        this.audit = audit;
    }

    /** Whether a dataset is a raster, published by this class. */
    public static boolean raster(UploadedData data) {
        return data.cogFileId() != null;
    }

    /** Publishes the COG of a loaded raster dataset as the layer {@code layer}, or publishes its file again. */
    public DatasetPublishing.Outcome publish(UploadedData data, String layer) {
        if (!raster(data) || !(UploadedData.LOADED.equals(data.state()) || UploadedData.PUBLISHED.equals(data.state()))) {
            return DatasetPublishing.Outcome.failed(Failure.correct(409, "not-loaded",
                    "The image is not loaded as a Cloud Optimized GeoTIFF: load it first."));
        }
        if (layer == null || !LayerDefinition.NAME.matcher(layer).matches()) {
            return DatasetPublishing.Outcome.failed(Failure.correct(400, "invalid", "The layer name is made of lowercase"
                    + " letters, digits and underscores, starting with a letter, at most 63 characters."));
        }
        Optional<PublishedLayer> existing = geoserver.layer(layer);
        if (existing.isPresent() && !data.id().equals(existing.get().datasetId())) {
            return DatasetPublishing.Outcome.failed(Failure.correct(409, "name-in-use",
                    "The catalog already publishes a layer named " + layer + " for other data."));
        }
        String ws = geoserver.space().workspace();
        GeoServerClient client;
        try {
            client = geoserver.client();
            if (existing.isEmpty() && (geoserver.unknownLayers().contains(layer)
                    || client.coverageStores(ws).contains(layer))) {
                return DatasetPublishing.Outcome.failed(Failure.correct(409, "name-taken", "GeoServer already has a"
                        + " layer or a coverage store named " + layer + " that the catalog does not know: choose another"
                        + " name, or remove it in the reconciliation."));
            }
            byte[] cog;
            try (FileContent content = files.read(EXTENSION, data.cogFileId())) {
                cog = content.stream().readAllBytes();
            }
            client.uploadCoverage(ws, layer, cog);
            client.describeCoverage(ws, layer, data.title(), data.description());
        } catch (GeoServerException | GeoServerGovernance.MissingCredentialException e) {
            Failure f = Failure.of(e, "publishing " + layer);
            record("layer.publish-failed", layer, data, AuditLog.Outcome.FAILED, Map.of("code", f.code(),
                    "reference", f.reference()));
            return DatasetPublishing.Outcome.failed(f);
        } catch (IOException e) {
            return DatasetPublishing.Outcome.failed(Failure.correct(409, "not-loaded",
                    "The image of the dataset cannot be read from the file service: load it again."));
        }
        Instant now = Instant.now();
        geoserver.publish(new LayerDefinition(layer, layer, title(data), description(data), data.crs(), null));
        PublishedLayer published = geoserver.layer(layer).orElseThrow();
        published.raster(true);
        published.serves(data.id(), now);
        geoserver.update(published);
        try {
            // The GeoFence rules follow the alignment (GP-335), which leaves the coverage alone.
            geoserver.align();
        } catch (RuntimeException e) {
            LOG.warnf("The rules of the raster %s are written at the next check: %s", layer, e.getMessage());
        }
        geoserver.check(published);
        data.published(now);
        uploads.update(data);
        states.mirror(data);
        record(existing.isPresent() ? "layer.republished" : "layer.published", layer, data, AuditLog.Outcome.SUCCEEDED,
                Map.of("kind", "raster"));
        return DatasetPublishing.Outcome.done(published);
    }

    /** Withdraws a raster layer: its coverage store and its file in GeoServer go, the COG stays. */
    public DatasetPublishing.Outcome unpublish(PublishedLayer layer, UploadedData data) {
        String name = layer.name();
        try {
            GeoServerClient client = geoserver.client();
            String ws = geoserver.space().workspace();
            if (client.coverageStores(ws).contains(name)) {
                client.deleteCoverageStore(ws, name);
            }
        } catch (GeoServerException | GeoServerGovernance.MissingCredentialException e) {
            return DatasetPublishing.Outcome.failed(Failure.of(e, "unpublishing " + name));
        }
        geoserver.withdrawLayer(name);
        try {
            geoserver.align();
        } catch (RuntimeException e) {
            LOG.warnf("The withdrawal of the raster %s is completed at the next alignment: %s", name, e.getMessage());
        }
        if (data != null && geoserver.published().stream().noneMatch(l -> data.id().equals(l.datasetId()))) {
            data.unpublished(Instant.now());
            uploads.update(data);
            states.mirror(data);
        }
        record("layer.unpublished", name, data, AuditLog.Outcome.SUCCEEDED, Map.of("kind", "raster"));
        return DatasetPublishing.Outcome.done(layer);
    }

    private static String title(UploadedData data) {
        String t = data.title() == null || data.title().isBlank() ? data.technicalName() : data.title();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private static String description(UploadedData data) {
        String d = data.description() == null ? "" : data.description();
        return d.length() > 1000 ? d.substring(0, 1000) : d;
    }

    private void record(String action, String layer, UploadedData data, AuditLog.Outcome outcome, Map<String, ?> more) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("layer", layer);
        if (data != null) {
            details.put("dataset", data.id().toString());
            details.put("title", data.title());
        }
        details.putAll(more);
        audit.record(EXTENSION, new AuditLog.Entry(action, "layer/" + layer, outcome, details));
    }
}
