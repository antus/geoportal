// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.load;

import dev.geoportal.catalog.load.LoadRefused.Code;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Converts a GeoTIFF into a Cloud Optimized GeoTIFF (GP-937) with the GDAL that PostGIS raster links
 * (ADR-0019): the geodata database reads the file, keeps its georeferencing and reference system,
 * writes it as COG with DEFLATE compression and overviews, and gives its extent. Nothing is stored in
 * the database: the COG goes to the file service of Mosaikit.
 */
final class RasterConverter {

    private final Connection connection;

    RasterConverter(Connection connection) {
        this.connection = connection;
    }

    /** A COG and what it covers. */
    record Converted(byte[] cog, Crs crs, int width, int height, int bands, Extent nativeExtent, Extent wgs84) {}

    Converted convert(byte[] tiff, Integer declared, PostgisWriter systems) throws SQLException {
        drivers();
        int srid;
        int width;
        int height;
        int bands;
        boolean georeferenced;
        Savepoint savepoint = new Savepoint(connection);
        try (PreparedStatement p = connection.prepareStatement(
                "select ST_SRID(r), ST_Width(r), ST_Height(r), ST_NumBands(r),"
                        + " not (ST_ScaleX(r) = 1 and abs(ST_ScaleY(r)) = 1 and ST_UpperLeftX(r) = 0 and ST_UpperLeftY(r) = 0)"
                        + " from (select ST_FromGDALRaster(?) r) x")) {
            p.setBytes(1, tiff);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                srid = r.getInt(1);
                width = r.getInt(2);
                height = r.getInt(3);
                bands = r.getInt(4);
                georeferenced = r.getBoolean(5);
            }
            savepoint.release();
        } catch (SQLException e) {
            savepoint.rollback();
            throw new LoadRefused(Code.UNREADABLE, "The file is not a GeoTIFF GDAL reads: " + PostgisWriter.firstLine(e.getMessage()), e);
        }
        if (!georeferenced) {
            throw new LoadRefused(Code.UNREADABLE, "The TIFF has no georeferencing: it says nowhere where its pixels are.");
        }
        Crs crs;
        if (srid > 0) {
            crs = Crs.file(srid);
        } else if (declared != null) {
            crs = new Crs(declared, Crs.Source.DECLARED);
        } else {
            throw new LoadRefused(Code.CRS_MISSING,
                    "The GeoTIFF states no reference system PostGIS knows: declare its EPSG code, for example EPSG:32632.");
        }
        Features none = new Features(crs);
        systems.crs(none, null); // a code PostGIS knows
        String raster = "ST_SetSRID(ST_FromGDALRaster(?), " + crs.srid() + ")";
        byte[] cog;
        Extent nativeExtent;
        try (PreparedStatement p = connection.prepareStatement(
                "with x as (select " + raster + " r) select ST_AsGDALRaster(r, 'COG', ARRAY['COMPRESS=DEFLATE']),"
                        + " ST_XMin(e), ST_YMin(e), ST_XMax(e), ST_YMax(e) from (select r, ST_Envelope(r)::geometry e from x) y")) {
            p.setBytes(1, tiff);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                cog = r.getBytes(1);
                nativeExtent = new Extent(r.getDouble(2), r.getDouble(3), r.getDouble(4), r.getDouble(5));
            }
        }
        if (systems.geographic(crs.srid()) && !nativeExtent.degrees()) {
            throw new LoadRefused(Code.CRS_MISMATCH, "The image does not lie within degrees of " + crs.code()
                    + ": it is in a projected system it does not state. Declare its EPSG code.");
        }
        return new Converted(cog, crs, width, height, bands, nativeExtent, wgs84(nativeExtent, crs.srid()));
    }

    /**
     * GDAL in PostGIS reads and writes no format unless the database enables it: the installation sets
     * {@code postgis.gdal_enabled_drivers} on the geodata database (distribution/server/postgres), which
     * a login that is not a superuser cannot change.
     */
    private void drivers() throws SQLException {
        String enabled;
        try (Statement s = connection.createStatement();
                ResultSet r = s.executeQuery("select postgis_gdal_version(), current_setting('postgis.gdal_enabled_drivers', true)")) {
            r.next();
            enabled = r.getString(2) == null ? "" : " " + r.getString(2) + " ";
        } catch (SQLException e) {
            throw new IllegalStateException("The geodata database has no PostGIS raster: " + PostgisWriter.firstLine(e.getMessage()), e);
        }
        if (!enabled.contains(" GTiff ") || !enabled.contains(" COG ")) {
            throw new IllegalStateException("The geodata database does not enable the GDAL drivers GTiff and COG"
                    + " (ALTER DATABASE geodata SET postgis.gdal_enabled_drivers = 'GTiff COG').");
        }
    }

    /** The extent in WGS84, from the outline of the image densified before reprojecting; none if it fails. */
    private Extent wgs84(Extent e, int srid) throws SQLException {
        if (srid == Crs.WGS84) {
            return e.degrees() ? e : null;
        }
        Savepoint savepoint = new Savepoint(connection);
        try (PreparedStatement p = connection.prepareStatement(
                "select ST_XMin(g), ST_YMin(g), ST_XMax(g), ST_YMax(g) from (select ST_Transform(ST_Segmentize("
                        + "ST_MakeEnvelope(?, ?, ?, ?, " + srid + "), greatest(? - ?, ? - ?) / 32), 4326) g) x")) {
            p.setDouble(1, e.minX());
            p.setDouble(2, e.minY());
            p.setDouble(3, e.maxX());
            p.setDouble(4, e.maxY());
            p.setDouble(5, e.maxX());
            p.setDouble(6, e.minX());
            p.setDouble(7, e.maxY());
            p.setDouble(8, e.minY());
            try (ResultSet r = p.executeQuery()) {
                r.next();
                Extent out = new Extent(r.getDouble(1), r.getDouble(2), r.getDouble(3), r.getDouble(4));
                savepoint.release();
                return out.degrees() ? out : null;
            }
        } catch (SQLException ex) {
            savepoint.rollback();
            return null;
        }
    }

    /** A savepoint that is released or rolled back once. */
    private static final class Savepoint {
        private final Connection connection;
        private final java.sql.Savepoint savepoint;

        Savepoint(Connection connection) throws SQLException {
            this.connection = connection;
            this.savepoint = connection.setSavepoint();
        }

        void release() throws SQLException {
            connection.releaseSavepoint(savepoint);
        }

        void rollback() throws SQLException {
            connection.rollback(savepoint);
        }
    }
}
