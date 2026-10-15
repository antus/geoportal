// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.upload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An uploaded dataset as the API returns it, with the level of the person on it (view, download,
 * edit, edit-data or manage), so that the page offers only what the backend will allow.
 */
public record UploadView(
        UUID id,
        String title,
        String description,
        String technicalName,
        String format,
        String formatTitle,
        String fileName,
        long size,
        String state,
        String stateCode,
        String stateReason,
        int version,
        boolean sample,
        Instant createdAt,
        Instant updatedAt,
        String level,
        Loaded loaded,
        Integer progress,
        String layer,
        List<Choice> layers) {

    /** A layer of a file with several, for the person to choose (GP-937). */
    public record Choice(String name, long features, String geometryType) {}

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * What the loading of the current version produced (GP-937), {@code null} until it is loaded.
     *
     * @param crs the reference system, {@code EPSG:n}, and where it comes from: {@code file},
     *     {@code format} or {@code declared} (GP-324)
     * @param extent west, south, east, north in WGS84 degrees, {@code null} when it cannot be computed
     *     reliably (GP-321)
     * @param nativeExtent the same in the reference system of the data
     * @param table {@code schema.table} in the geodata database, for a vector
     * @param cog whether the data are a Cloud Optimized GeoTIFF in the file service, for a raster
     */
    public record Loaded(String crs, String crsSource, String geometryType, boolean z, long features,
            double[] extent, double[] nativeExtent, String table, boolean cog, long space, Instant at) {}

    static UploadView of(UploadedData data, String level) {
        Format format = Format.byId(data.format());
        return new UploadView(data.id(), data.title(), data.description(), data.technicalName(), format.id(),
                format.title(), data.fileName(), data.size(), data.state(), data.stateCode(), data.stateReason(),
                data.version(), data.sample(), data.createdAt(), data.updatedAt(), level, loaded(data),
                data.loadProgress(), data.layer(), choices(data.layersJson()));
    }

    private static List<Choice> choices(String json) {
        if (json == null) {
            return List.of();
        }
        try {
            return List.of(JSON.readValue(json, Choice[].class));
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    private static Loaded loaded(UploadedData data) {
        if (data.loadedAt() == null) {
            return null;
        }
        return new Loaded(data.crs(), data.crsSource(), data.geometryType(), Boolean.TRUE.equals(data.hasZ()),
                data.features() == null ? 0 : data.features(), box(data.wgs84()), box(data.nativeExtent()),
                data.tableName(), data.cogFileId() != null, data.space(), data.loadedAt());
    }

    private static double[] box(dev.geoportal.ext.catalog.geodata.load.Extent e) {
        return e == null ? null : new double[] {e.minX(), e.minY(), e.maxX(), e.maxY()};
    }
}
