// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.load;

import dev.geoportal.catalog.upload.Format;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Loads the content of an uploaded file into the geodata database (GP-937, ADR-0015; the built-in fallback of ADR-0019):
 * vectors into a table of the schema of the organization, rasters converted to COG. On the connection
 * of the caller, which commits when the rest of the loading succeeded and rolls back otherwise: a
 * refused file leaves no table behind.
 */
public final class Loader {

    private Loader() {}

    /**
     * What a loading produced.
     *
     * @param table the table in PostGIS, {@code schema.table}, for a vector; {@code null} for a raster
     * @param cog the Cloud Optimized GeoTIFF of a raster, to be stored by the caller; {@code null} for a vector
     * @param geometryType {@code Point}, {@code MultiPolygon}, ..., {@code Geometry} when mixed; {@code Raster}
     * @param features the features of a vector, the pixels of a raster
     * @param bytes the space in PostGIS, for {@code Quotas.reportSpace}; 0 for a raster (its COG is a file)
     * @param wgs84 {@code null} when it cannot be computed reliably (GP-321)
     */
    public record Result(String table, byte[] cog, Crs crs, String geometryType, boolean z, long features,
            Extent nativeExtent, Extent wgs84, long bytes) {}

    public static Result load(Connection connection, Format format, byte[] content, String schema, String table,
            Integer declared) throws SQLException {
        return load(connection, format, content, schema, table, declared, null);
    }

    /** @param layer the layer of a file with several (GeoPackage, zip of Shapefiles), or {@code null} */
    public static Result load(Connection connection, Format format, byte[] content, String schema, String table,
            Integer declared, String layer) throws SQLException {
        PostgisWriter writer = new PostgisWriter(connection);
        if (!format.vector()) {
            RasterConverter.Converted cog = new RasterConverter(connection).convert(content, declared, writer);
            return new Result(null, cog.cog(), cog.crs(), "Raster", false, (long) cog.width() * cog.height(),
                    cog.nativeExtent(), cog.wgs84(), 0);
        }
        PostgisWriter.Written written = writer.write(schema, table, read(format, content, layer), declared);
        return new Result(schema + "." + table, null, written.crs(), written.geometryType(), written.z(),
                written.features(), written.nativeExtent(), written.wgs84(), written.bytes());
    }

    /**
     * Takes over the table that the loader sidecar wrote into the schema (ADR-0019) as the table of the
     * dataset, after checking it. On the connection of the caller, as {@link #load}.
     */
    public static Result adopt(Connection connection, String schema, String loaded, String table, Crs crs) throws SQLException {
        PostgisWriter.Written written = new PostgisWriter(connection).adopt(schema, loaded, table, crs);
        return new Result(schema + "." + table, null, written.crs(), written.geometryType(), written.z(),
                written.features(), written.nativeExtent(), written.wgs84(), written.bytes());
    }

    /**
     * The system a CSV defines by its columns (WGS84 for latitude and longitude, and for a GeoJSON
     * column), or {@code null}.
     */
    public static Integer formatCrs(Format format, String firstLine) {
        return format == Format.CSV && firstLine != null && CsvReader.wgs84(firstLine) ? Crs.WGS84 : null;
    }

    /** The features of a vector file with one layer. */
    static Features read(Format format, byte[] content) {
        return read(format, content, null);
    }

    /** The features of a vector file, by its format. */
    static Features read(Format format, byte[] content, String layer) {
        return switch (format) {
            case GEOJSON -> GeoJsonReader.read(content);
            case SHAPEFILE -> ShapefileReader.read(Archives.unzip(content), layer);
            case GEOPACKAGE -> GeoPackageReader.read(content, layer);
            case GML -> GmlReader.read(content);
            case GPX -> GpxReader.read(content);
            case CSV -> CsvReader.read(content);
            case KML -> KmlReader.read(content);
            case KMZ -> KmlReader.read(Archives.kml(content));
            case CZML -> CzmlReader.read(content);
            case GEOTIFF -> throw new IllegalArgumentException("A GeoTIFF is a raster");
        };
    }

    /** Creates the schema of an organization in the geodata database, if it does not exist. */
    public static void createSchema(Connection connection, String schema) throws SQLException {
        PostgisWriter.check(schema);
        try (Statement s = connection.createStatement()) {
            s.execute("create schema if not exists " + schema);
        }
    }

    /** Drops the table of a dataset, if there is one. */
    public static void drop(Connection connection, String schema, String table) throws SQLException {
        PostgisWriter.check(schema);
        PostgisWriter.check(table);
        try (Statement s = connection.createStatement()) {
            s.execute("drop table if exists " + schema + "." + table + " cascade");
        }
    }
}
