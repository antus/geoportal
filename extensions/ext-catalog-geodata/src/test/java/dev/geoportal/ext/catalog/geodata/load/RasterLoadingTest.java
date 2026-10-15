// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.load;

import static dev.geoportal.ext.catalog.geodata.load.Geodatabase.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.geoportal.ext.catalog.geodata.upload.Format;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-937 and GP-324 for rasters: a GeoTIFF becomes a Cloud Optimized GeoTIFF with the same
 * georeferencing and reference system; one that states no system is refused or declared.
 */
@Tag("postgis")
@Tag("GP-937")
@Tag("GP-324")
class RasterLoadingTest {

    private Connection connection;

    @BeforeEach
    void open() throws Exception {
        connection = Geodatabase.connect();
    }

    @AfterEach
    void close() throws Exception {
        connection.rollback();
        connection.close();
    }

    /** What GDAL reads back from a COG: SRID, size, extent, and whether its layout is a COG. */
    private Object[] readBack(byte[] cog) throws Exception {
        try (PreparedStatement p = connection.prepareStatement("select ST_SRID(r), ST_Width(r), ST_Height(r),"
                + " ST_XMin(ST_Envelope(r)), ST_YMin(ST_Envelope(r)), ST_XMax(ST_Envelope(r)), ST_YMax(ST_Envelope(r))"
                + " from (select ST_FromGDALRaster(?) r) x")) {
            p.setBytes(1, cog);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return new Object[] {r.getInt(1), r.getInt(2), r.getInt(3), r.getDouble(4), r.getDouble(5), r.getDouble(6), r.getDouble(7)};
            }
        }
    }

    /** A GeoTIFF made by GDAL, of 64 by 32 pixels, in a system and at a place. */
    private byte[] tiff(int srid, double x, double y, double pixel) throws Exception {
        try (PreparedStatement p = connection.prepareStatement("select ST_AsGDALRaster(ST_AddBand(ST_MakeEmptyRaster(64, 32, ?, ?, ?, ?, 0, 0, ?),"
                + " '8BUI'::text, 7, 0), 'GTiff')")) {
            p.setDouble(1, x);
            p.setDouble(2, y);
            p.setDouble(3, pixel);
            p.setDouble(4, -pixel);
            p.setInt(5, srid);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getBytes(1);
            }
        }
    }

    @Test
    void gp937TheSampleGeoTiffBecomesACogWithItsGeoreferencing() throws Exception {
        byte[] tif = Geodatabase.sample("rilievo-esempio.tif");
        Loader.Result result = Loader.load(connection, Format.GEOTIFF, tif, SCHEMA, "rilievo", null);
        assertNotNull(result.cog());
        assertTrue(result.cog()[0] == 'I' && result.cog()[1] == 'I' && result.cog()[2] == 42, "a little-endian TIFF");
        assertTrue(new String(result.cog(), 0, Math.min(result.cog().length, 2000), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("LAYOUT=IFDS_BEFORE_DATA"), "GDAL marks the layout of a COG in its first bytes");
        assertEquals("Raster", result.geometryType());
        assertEquals(0, result.bytes());
        Object[] back = readBack(result.cog());
        assertEquals(result.crs().srid(), back[0]);
        assertEquals(result.nativeExtent().minX(), (double) back[3], 1e-9);
        assertEquals(result.nativeExtent().maxY(), (double) back[6], 1e-9);
        assertNotNull(result.wgs84());
        // The box of the sample: 6.6 to 18.6 E, 35.4 to 47.1 N
        assertTrue(result.wgs84().minX() > 5 && result.wgs84().maxX() < 20, result.wgs84().toString());
        assertTrue(result.wgs84().minY() > 35 && result.wgs84().maxY() < 48, result.wgs84().toString());
    }

    @Test
    void gp324AProjectedGeoTiffKeepsItsSystemAndHasItsExtentInDegrees() throws Exception {
        byte[] tif = tiff(32633, 290000, 4643000, 100);
        Loader.Result result = Loader.load(connection, Format.GEOTIFF, tif, SCHEMA, "utm", null);
        assertEquals("EPSG:32633", result.crs().code());
        assertEquals(Crs.Source.FILE, result.crs().source());
        assertEquals(32633, readBack(result.cog())[0]);
        assertEquals(290000, result.nativeExtent().minX(), 1e-6);
        assertEquals(296400, result.nativeExtent().maxX(), 1e-6);
        // Zone 33 at 290 km east, 4643 km north: about 12.47 E, 41.88 N
        assertEquals(12.47, result.wgs84().minX(), 0.03);
        assertEquals(41.88, result.wgs84().minY(), 0.03);
    }

    @Test
    void gp324AGeoTiffWithoutSystemIsRefusedOrDeclared() throws Exception {
        byte[] tif = tiff(0, 290000, 4643000, 100);
        LoadRefused refused = assertThrows(LoadRefused.class, () -> Loader.load(connection, Format.GEOTIFF, tif, SCHEMA, "nosrs", null));
        assertEquals(LoadRefused.Code.CRS_MISSING, refused.code());
        connection.rollback();
        Loader.Result declared = Loader.load(connection, Format.GEOTIFF, tif, SCHEMA, "nosrs", 32633);
        assertEquals(Crs.Source.DECLARED, declared.crs().source());
        assertEquals(32633, readBack(declared.cog())[0]);
    }

    @Test
    void gp937AFileThatIsNotATiffIsRefused() throws Exception {
        LoadRefused refused = assertThrows(LoadRefused.class, () -> Loader.load(connection, Format.GEOTIFF,
                "not a tiff".getBytes(java.nio.charset.StandardCharsets.UTF_8), SCHEMA, "rotto", null));
        assertEquals(LoadRefused.Code.UNREADABLE, refused.code());
    }

    @Test
    void gp324ARasterThatSaysDegreesButIsInMetresIsRefused() throws Exception {
        byte[] tif = tiff(4326, 290000, 4643000, 100);
        LoadRefused refused = assertThrows(LoadRefused.class, () -> Loader.load(connection, Format.GEOTIFF, tif, SCHEMA, "x", null));
        assertEquals(LoadRefused.Code.CRS_MISMATCH, refused.code());
    }
}
