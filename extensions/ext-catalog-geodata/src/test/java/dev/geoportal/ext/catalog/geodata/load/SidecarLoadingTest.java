// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.load;

import static dev.geoportal.ext.catalog.geodata.load.Geodatabase.SCHEMA;
import static dev.geoportal.ext.catalog.geodata.load.Geodatabase.exists;
import static dev.geoportal.ext.catalog.geodata.load.Geodatabase.one;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.geoportal.ext.catalog.geodata.upload.Format;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * GP-937, GP-321 and GP-324 through the loader sidecar (ADR-0019): GDAL loads the files into PostGIS
 * and converts rasters into COG, the catalog takes the tables over; the reference system is stated,
 * declared or refused, never guessed; a file with several layers asks which; a file larger than the
 * memory of the loader is streamed.
 */
@Tag("postgis")
@Tag("GP-937")
@Tag("GP-321")
@Tag("GP-324")
class SidecarLoadingTest {

    private static final Map<String, double[]> STATIONS = Map.of(
            "Valverde Centrale", new double[] {12.3812, 42.1054},
            "Valverde Nord", new double[] {12.4107, 42.1149},
            "Valverde Sud", new double[] {12.3649, 42.0767});

    private Connection connection;
    private LoaderClient loader;

    @BeforeEach
    void open() throws Exception {
        loader = Sidecar.client();
        connection = Geodatabase.connect();
    }

    @AfterEach
    void close() throws Exception {
        connection.rollback();
        connection.close();
    }

    private Loader.Result load(String fileName, byte[] content, Format format, String table, Integer declared, String layer)
            throws Exception {
        String head = new String(content, 0, Math.min(content.length, 4096), StandardCharsets.UTF_8);
        int nl = head.indexOf('\n');
        Integer formatCrs = Loader.formatCrs(format, (nl < 0 ? head : head.substring(0, nl)).replace("﻿", "").strip());
        return Sidecar.load(connection, loader, Sidecar.serve(fileName, content), fileName, format, table, declared, layer, formatCrs);
    }

    private void near(String table, String name, double lon, double lat) throws Exception {
        Object d = one(connection, "select min(ST_Distance(ST_Transform(geom, 4326)::geography, ST_MakePoint(?, ?)::geography)) from "
                + SCHEMA + "." + table + " where nome = ?", lon, lat, name);
        assertTrue(d != null && ((Number) d).doubleValue() < 1, name + " is " + d + " m away");
    }

    @ParameterizedTest(name = "GP-937 GDAL loads {0} into a table of 5 points in EPSG:4326")
    @CsvSource({"poi-esempio.geojson, GEOJSON", "poi-esempio.csv, CSV", "poi-esempio.gml, GML", "poi-esempio.gpkg, GEOPACKAGE",
        "poi-esempio-shp.zip, SHAPEFILE", "poi-esempio.gpx, GPX", "csv-semicolon-decimal.csv, CSV", "csv-tab.csv, CSV",
        "poi-esempio.kml, KML", "poi-esempio.kmz, KMZ"})
    void samples(String file, Format format) throws Exception {
        Loader.Result result = load(file, Geodatabase.sample(file), format, "gdal", null, null);
        assertEquals("EPSG:4326", result.crs().code(), file);
        assertEquals(5, result.features(), file);
        assertEquals("gdal", result.table().substring(result.table().indexOf('.') + 1));
        assertEquals(4326, one(connection, "select Find_SRID(?, 'gdal', 'geom')", SCHEMA));
        if (format != Format.GPX) {
            near("gdal", "Torre Civica", 12.381245, 42.105372);
        }
        assertNotNull(result.wgs84());
    }

    @ParameterizedTest(name = "GP-937 GDAL loads the extended data of {0} as columns, without the display elements of KML")
    @CsvSource({"poi-esempio.kml, KML", "poi-esempio.kmz, KMZ"})
    void gp937KmlKeepsItsExtendedData(String file, Format format) throws Exception {
        load(file, Geodatabase.sample(file), format, "gdal_kml", null, null);
        java.util.List<String> columns = new java.util.ArrayList<>();
        try (var p = connection.prepareStatement("select column_name, data_type from information_schema.columns"
                + " where table_schema = ? and table_name = 'gdal_kml' order by ordinal_position")) {
            p.setString(1, SCHEMA);
            try (var r = p.executeQuery()) {
                while (r.next()) {
                    columns.add(r.getString(1));
                    if (r.getString(1).equals("anno")) {
                        assertTrue(r.getString(2).contains("int"), "anno is " + r.getString(2));
                    }
                }
            }
        }
        assertTrue(columns.containsAll(java.util.List.of("nome", "citta", "categoria", "anno", "name", "description")), columns.toString());
        for (String display : java.util.List.of("tessellate", "extrude", "visibility", "altitudemode", "draworder", "icon", "timestamp")) {
            assertFalse(columns.contains(display), display + " in " + columns);
        }
        assertEquals("monumento", one(connection, "select categoria from " + SCHEMA + ".gdal_kml where nome = 'Torre Civica'"));
        assertEquals(1386L, ((Number) one(connection, "select anno from " + SCHEMA + ".gdal_kml where nome = ?",
                "Cattedrale di Monteluce")).longValue());
    }

    @Test
    void gp324ACsvWithAGeoJsonColumnIsInWgs84AsTheFormatDefines() throws Exception {
        Loader.Result result = load("csv-geojson-col.csv", Geodatabase.sample("csv-geojson-col.csv"), Format.CSV, "gdal_geojson_col",
                null, null);
        assertEquals("EPSG:4326", result.crs().code());
        assertEquals(Crs.Source.FORMAT, result.crs().source());
        assertEquals(2, result.features());
        near("gdal_geojson_col", "Valverde", 12.381245, 42.105372);
        near("gdal_geojson_col", "Monteluce", 10.874512, 44.612338);
    }

    @Test
    void gp937AGeoPackageWithSeveralLayersAsksWhichAndLoadsTheChosenOne() throws Exception {
        byte[] gpkg = Geodatabase.fixture("due-livelli.gpkg");
        LoadRefused refused = assertThrows(LoadRefused.class, () -> load("due-livelli.gpkg", gpkg, Format.GEOPACKAGE, "gdal_livelli", null, null));
        assertEquals(LoadRefused.Code.SEVERAL_LAYERS, refused.code());
        assertEquals(java.util.List.of("aree", "altre"), refused.layers());
        connection.rollback();
        assertFalse(exists(connection, "gdal_livelli"));
        Loader.Result chosen = load("due-livelli.gpkg", gpkg, Format.GEOPACKAGE, "gdal_livelli", null, "altre");
        assertEquals(1, chosen.features());
        assertEquals("EPSG:32633", chosen.crs().code());
        assertEquals("B", one(connection, "select nome from " + SCHEMA + ".gdal_livelli"));
    }

    @Test
    void gp324AProjectedGeoJsonKeepsItsSystem() throws Exception {
        StringBuilder json = new StringBuilder("{\"type\":\"FeatureCollection\",\"crs\":{\"type\":\"name\",\"properties\":"
                + "{\"name\":\"urn:ogc:def:crs:EPSG::32633\"}},\"features\":[");
        boolean first = true;
        for (Map.Entry<String, double[]> s : STATIONS.entrySet()) {
            Object x = one(connection, "select ST_X(ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 32633))", s.getValue()[0], s.getValue()[1]);
            Object y = one(connection, "select ST_Y(ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 32633))", s.getValue()[0], s.getValue()[1]);
            json.append(first ? "" : ",").append("{\"type\":\"Feature\",\"properties\":{\"nome\":\"").append(s.getKey())
                    .append("\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[").append(x).append(',').append(y).append("]}}");
            first = false;
        }
        Loader.Result result = load("utm.geojson", json.append("]}").toString().getBytes(StandardCharsets.UTF_8), Format.GEOJSON,
                "gdal_utm_json", null, null);
        assertEquals("EPSG:32633", result.crs().code());
        assertEquals(Crs.Source.FILE, result.crs().source());
        assertEquals(32633, one(connection, "select Find_SRID(?, 'gdal_utm_json', 'geom')", SCHEMA));
        for (Map.Entry<String, double[]> s : STATIONS.entrySet()) {
            near("gdal_utm_json", s.getKey(), s.getValue()[0], s.getValue()[1]);
        }
        // GP-321: the extent in degrees from the projected table
        assertEquals(12.3649, result.wgs84().minX(), 1e-4);
        assertEquals(42.1149, result.wgs84().maxY(), 1e-4);
    }

    @Test
    void gp324AShapefileWithAPrjOfItsDialectIsIdentifiedAndOneWithoutIsRefusedUntilDeclared() throws Exception {
        byte[] zip = Geodatabase.fixture("stazioni-utm33.zip");
        Loader.Result result = load("stazioni-utm33.zip", zip, Format.SHAPEFILE, "gdal_utm_shp", null, null);
        assertEquals("EPSG:32633", result.crs().code());
        for (Map.Entry<String, double[]> s : STATIONS.entrySet()) {
            near("gdal_utm_shp", s.getKey(), s.getValue()[0], s.getValue()[1]);
        }
        byte[] withoutPrj = withoutEntry(zip, ".prj");
        LoadRefused refused = assertThrows(LoadRefused.class, () -> load("noprj.zip", withoutPrj, Format.SHAPEFILE, "gdal_noprj", null, null));
        assertEquals(LoadRefused.Code.CRS_MISSING, refused.code());
        connection.rollback();
        assertFalse(exists(connection, "gdal_noprj"));
        Loader.Result declared = load("noprj.zip", withoutPrj, Format.SHAPEFILE, "gdal_noprj", 32633, null);
        assertEquals(Crs.Source.DECLARED, declared.crs().source());
        near("gdal_noprj", "Valverde Centrale", 12.3812, 42.1054);
        // A declared system does not replace the one the file states.
        assertEquals("EPSG:32633", load("stazioni-utm33.zip", zip, Format.SHAPEFILE, "gdal_dichiarato", 3003, null).crs().code());
    }

    @Test
    void gp324AGeoJsonInMetresWithoutSystemIsRefused() throws Exception {
        String json = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[283467.75,4664797.939]}}]}";
        LoadRefused refused = assertThrows(LoadRefused.class, () -> load("metri.geojson", json.getBytes(StandardCharsets.UTF_8),
                Format.GEOJSON, "gdal_metri", null, null));
        assertEquals(LoadRefused.Code.CRS_MISMATCH, refused.code());
    }

    @Test
    void gp937AGeoTiffBecomesACogWithItsExtentAndOneWithoutSystemIsRefused() throws Exception {
        Loader.Result result = load("rilievo-esempio.tif", Geodatabase.sample("rilievo-esempio.tif"), Format.GEOTIFF, "gdal_r", null, null);
        assertTrue(new String(result.cog(), 0, Math.min(2000, result.cog().length), StandardCharsets.ISO_8859_1)
                .contains("LAYOUT=IFDS_BEFORE_DATA"));
        assertTrue(result.wgs84().minX() > 5 && result.wgs84().maxX() < 20, result.wgs84().toString());
        byte[] noSrs;
        try (var p = connection.prepareStatement("select ST_AsGDALRaster(ST_AddBand(ST_MakeEmptyRaster(64, 32, 290000, 4643000, 100, -100,"
                + " 0, 0, 0), '8BUI'::text, 7, 0), 'GTiff')"); var r = p.executeQuery()) {
            r.next();
            noSrs = r.getBytes(1);
        }
        LoadRefused refused = assertThrows(LoadRefused.class, () -> load("nosrs.tif", noSrs, Format.GEOTIFF, "gdal_r", null, null));
        assertEquals(LoadRefused.Code.CRS_MISSING, refused.code());
        Loader.Result declared = load("nosrs.tif", noSrs, Format.GEOTIFF, "gdal_r", 32633, null);
        assertEquals("EPSG:32633", declared.crs().code());
        assertEquals(12.47, declared.wgs84().minX(), 0.03);
    }

    @Test
    void gp937AFileLargerThanTheMemoryOfTheLoaderIsStreamed() throws Exception {
        Path csv = Files.createTempFile("gp937-large", ".csv");
        long rows = 0;
        try (BufferedWriter out = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            out.write("id,nome,descrizione,lat,lon\n");
            String filler = "x".repeat(40);
            long written = 0;
            while (written < 200L << 20) {
                String line = rows + ",punto " + rows + "," + filler + "," + (41 + (rows % 1000) / 1000.0) + ","
                        + (12 + (rows / 1000 % 1000) / 1000.0) + "\n";
                out.write(line);
                written += line.length();
                rows++;
            }
        }
        try {
            long size = Files.size(csv);
            assertTrue(size > Sidecar.MEMORY / 2 && size >= 200L << 20, "a file of " + size + " bytes");
            Loader.Result result = Sidecar.load(connection, loader, Sidecar.serve(csv), "grande.csv", Format.CSV, "gdal_grande", null, null, 4326);
            assertEquals(rows, result.features());
            assertFalse(Sidecar.oomKilled());
            Extent e = result.wgs84();
            assertEquals(12, e.minX(), 1e-9);
            assertEquals(41, e.minY(), 1e-9);
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    private static byte[] withoutEntry(byte[] zip, String suffix) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip)); ZipOutputStream z = new ZipOutputStream(out)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (!e.getName().endsWith(suffix)) {
                    z.putNextEntry(new ZipEntry(e.getName()));
                    in.transferTo(z);
                    z.closeEntry();
                }
            }
        }
        return out.toByteArray();
    }
}
