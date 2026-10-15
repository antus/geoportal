// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.upload;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The formats of the files the catalog accepts as data: those that GP-937 loads into PostGIS
 * (vectors) or as Cloud Optimized GeoTIFF (rasters). Other files, such as documents, are refused
 * here; documents come with GP-350.
 */
public enum Format {
    GEOJSON("geojson", "GeoJSON", "application/geo+json", true, "geojson", "json"),
    SHAPEFILE("shapefile", "Shapefile", "application/zip", true, "zip"),
    GEOPACKAGE("geopackage", "GeoPackage", "application/geopackage+sqlite3", true, "gpkg"),
    GML("gml", "GML", "application/gml+xml", true, "gml"),
    GPX("gpx", "GPX", "application/gpx+xml", true, "gpx"),
    CSV("csv", "CSV", "text/csv", true, "csv", "tsv", "txt"),
    KML("kml", "KML", "application/vnd.google-earth.kml+xml", true, "kml"),
    KMZ("kmz", "KMZ", "application/vnd.google-earth.kmz", true, "kmz"),
    CZML("czml", "CZML", "application/json", true, "czml"),
    GEOTIFF("geotiff", "GeoTIFF", "image/tiff", false, "tif", "tiff");

    private final String id;
    private final String title;
    private final String contentType;
    private final boolean vector;
    private final List<String> extensions;

    Format(String id, String title, String contentType, boolean vector, String... extensions) {
        this.id = id;
        this.title = title;
        this.contentType = contentType;
        this.vector = vector;
        this.extensions = List.of(extensions);
    }

    /** The format of a file, from the extension of its name. */
    public static Optional<Format> of(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (Format format : values()) {
            if (format.extensions.contains(extension)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /** The extensions of every accepted format, for the message of a refusal. */
    public static String accepted() {
        return String.join(", ", java.util.Arrays.stream(values())
                .flatMap(f -> f.extensions.stream())
                .map(e -> "." + e)
                .toList());
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String contentType() {
        return contentType;
    }

    /**
     * Whether the loader sidecar loads it (ADR-0019): every format but CZML, which is not a format of
     * GDAL and stays with the readers of the catalog. KML and KMZ go through the LIBKML driver of the
     * image of the loader, which keeps their extended data (ADR-0026).
     */
    public boolean gdal() {
        return this != CZML;
    }

    /** Loaded into PostGIS when true, as a COG otherwise (GP-937). */
    public boolean vector() {
        return vector;
    }

    /** The format of an id as stored, GeoJSON for an unknown one. */
    static Format byId(String id) {
        for (Format format : values()) {
            if (format.id.equals(id)) {
                return format;
            }
        }
        return GEOJSON;
    }
}
