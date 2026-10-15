// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.load;

import static dev.geoportal.catalog.load.Geodatabase.SCHEMA;
import static dev.geoportal.catalog.load.Geodatabase.exists;
import static dev.geoportal.catalog.load.Geodatabase.one;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.geoportal.catalog.upload.Format;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * GP-937: the sample files of every vector format become tables of PostGIS with their features,
 * geometry type, Z and reference system; a file that cannot be loaded leaves no table behind.
 */
@Tag("postgis")
@Tag("GP-937")
class VectorLoadingTest {

    /** Where two of the five landmarks of the samples are, longitude and latitude. */
    private static final Map<String, double[]> LANDMARKS = Map.of(
            "Torre Civica", new double[] {12.381245, 42.105372},
            "Cattedrale di Monteluce", new double[] {10.874512, 44.612338});

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

    private Loader.Result load(String file, Format format, String table) throws Exception {
        return Loader.load(connection, format, Geodatabase.sample(file), SCHEMA, table, null);
    }

    @ParameterizedTest(name = "GP-937 {0} becomes a table of {2} features of type {3}")
    @CsvSource({
        "poi-esempio.geojson, GEOJSON, 5, Point",
        "poi-esempio.csv, CSV, 5, Point",
        "poi-esempio.gml, GML, 5, Point",
        "poi-esempio.kml, KML, 5, Point",
        "poi-esempio.kmz, KMZ, 5, Point",
        "poi-esempio.gpkg, GEOPACKAGE, 5, Point",
        "poi-esempio-shp.zip, SHAPEFILE, 5, Point",
        "poi-esempio.gpx, GPX, 5, Point",
        "poi-esempio.czml, CZML, 5, Point",
        "csv-bom-crlf.csv, CSV, 2, Point",
        "csv-semicolon-decimal.csv, CSV, 5, Point",
        "csv-tab.csv, CSV, 5, Point",
        "csv-geojson-col.csv, CSV, 2, Point",
        "quartieri_valverde.geojson, GEOJSON, -1, Polygon",
        "piste_ciclabili_valverde.geojson, GEOJSON, -1, ''",
        "rete_tpl_valverde.geojson, GEOJSON, -1, ''",
        "aree_protette_esempio.geojson, GEOJSON, -1, ''",
        "porti_esempio.geojson, GEOJSON, -1, Point",
        "boe_meteo.geojson, GEOJSON, -1, Point",
        "osservazioni_naturalistiche.geojson, GEOJSON, -1, Point",
    })
    void samples(String file, Format format, long features, String type) throws Exception {
        Loader.Result result = load(file, format, "sample");
        assertEquals("EPSG:4326", result.crs().code(), file);
        assertEquals(SCHEMA + ".sample", result.table());
        if (features >= 0) {
            assertEquals(features, result.features(), file);
        } else {
            assertTrue(result.features() > 0, file);
        }
        if (!type.isEmpty()) {
            assertEquals(type, result.geometryType(), file);
        }
        assertEquals(result.features(), ((Number) one(connection, "select count(*) from " + SCHEMA + ".sample")).longValue());
        assertEquals(4326, one(connection, "select Find_SRID(?, 'sample', 'geom')", SCHEMA));
        assertTrue(result.bytes() > 0);
        if (file.startsWith("poi-esempio")) {
            landmarksWhereTheyAre(file, "sample");
        }
    }

    /** The tower and the cathedral at their place, whatever the axis order of the file. */
    private void landmarksWhereTheyAre(String file, String table) throws Exception {
        String name = nameColumn(table);
        for (Map.Entry<String, double[]> m : LANDMARKS.entrySet()) {
            Object distance = one(connection, "select min(ST_Distance(geom::geography, ST_MakePoint(?, ?)::geography)) from "
                    + SCHEMA + "." + table + " where " + name + " = ?", m.getValue()[0], m.getValue()[1], m.getKey());
            assertNotNull(distance, file + ": " + m.getKey() + " missing");
            assertTrue(((Number) distance).doubleValue() < 1, file + ": " + m.getKey() + " is " + distance + " m away");
        }
    }

    private String nameColumn(String table) throws Exception {
        Object column = one(connection, "select column_name from information_schema.columns where table_schema = ? and table_name = ?"
                + " and column_name in ('nome', 'name') order by column_name desc limit 1", SCHEMA, table);
        return (String) column;
    }

    @ParameterizedTest(name = "GP-937 {0} states no system and loads with the one declared for the sample")
    @CsvSource({"csv-pipe-xy.csv, 5", "csv-wkt-point.csv, 2"})
    void declaredSamples(String file, long features) throws Exception {
        Loader.Result result = Loader.load(connection, Format.CSV, Geodatabase.sample(file), SCHEMA, "dichiarato", 4326);
        assertEquals(Crs.Source.DECLARED, result.crs().source());
        assertEquals("Point", result.geometryType());
        assertTrue(result.features() >= features, file);
    }

    @Test
    void gp937GpxKeepsElevationAsZAndMixesWaypointsAndTracks() throws Exception {
        String gpx = """
                <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
                  <wpt lat="42.10" lon="12.38"><ele>21</ele><name>Torre Civica</name></wpt>
                  <trk><name>Giro</name><trkseg><trkpt lat="42.10" lon="12.38"><ele>21</ele></trkpt>
                    <trkpt lat="42.11" lon="12.37"><ele>30</ele></trkpt></trkseg></trk>
                </gpx>""";
        Loader.Result result = Loader.load(connection, Format.GPX, gpx.getBytes(StandardCharsets.UTF_8), SCHEMA, "giro", null);
        assertEquals(2, result.features());
        assertTrue(result.z());
        assertEquals("Geometry", result.geometryType());
        assertEquals(30.0, ((Number) one(connection, "select ST_ZMax(geom) from " + SCHEMA + ".giro where kind = 'track'")).doubleValue());
    }

    @Test
    void gp937MixedGeometriesOfAWktCsvGoIntoOneTable() throws Exception {
        Loader.Result result = Loader.load(connection, Format.CSV, Geodatabase.sample("csv-wkt-polygon.csv"), SCHEMA, "wkt", 4326);
        assertEquals("Geometry", result.geometryType());
        assertEquals(2L, ((Number) one(connection, "select count(distinct GeometryType(geom)) from " + SCHEMA + ".wkt")).longValue());
    }

    @Test
    void gp937AttributesKeepTheirTypes() throws Exception {
        load("poi-esempio.gpkg", Format.GEOPACKAGE, "tipi");
        assertEquals("bigint", one(connection, "select data_type from information_schema.columns where table_schema = ?"
                + " and table_name = 'tipi' and column_name = 'anno'", SCHEMA));
        assertEquals("text", one(connection, "select data_type from information_schema.columns where table_schema = ?"
                + " and table_name = 'tipi' and column_name = 'nome'", SCHEMA));
        Loader.Result gpkg = Loader.load(connection, Format.GEOPACKAGE, Geodatabase.fixture("aree-utm33.gpkg"), SCHEMA, "aree", null);
        assertEquals("Polygon", gpkg.geometryType());
        assertEquals("boolean", one(connection, "select data_type from information_schema.columns where table_schema = ?"
                + " and table_name = 'aree' and column_name = 'aperto'", SCHEMA));
        assertEquals("date", one(connection, "select data_type from information_schema.columns where table_schema = ?"
                + " and table_name = 'aree' and column_name = 'dal'", SCHEMA));
        // The second row has a name longer than a page of SQLite: read across its overflow pages.
        assertTrue(((Number) one(connection, "select max(length(nome)) from " + SCHEMA + ".aree")).intValue() > 6000);
        assertEquals(1, ((Number) one(connection, "select ST_NumInteriorRings(geom) from " + SCHEMA + ".aree where aperto")).intValue());
    }

    @Test
    void gp937AFileThatCannotBeLoadedIsRefusedWithTheReasonAndLeavesNoTable() throws Exception {
        String broken = """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"n":1},"geometry":{"type":"Point","coordinates":[12.49,41.89]}},
                  {"type":"Feature","properties":{"n":2},"geometry":{"type":"Polygon","coordinates":[[[12.49]]]}}]}""";
        LoadRefused refused = assertThrows(LoadRefused.class, () -> Loader.load(connection, Format.GEOJSON,
                broken.getBytes(StandardCharsets.UTF_8), SCHEMA, "rotto", null));
        assertEquals(LoadRefused.Code.GEOMETRY_INVALID, refused.code());
        assertFalse(refused.getMessage().isBlank());
        connection.rollback();
        assertFalse(exists(connection, "rotto"));

        LoadRefused notJson = assertThrows(LoadRefused.class, () -> Loader.load(connection, Format.GEOJSON,
                "{not json".getBytes(StandardCharsets.UTF_8), SCHEMA, "rotto", null));
        assertEquals(LoadRefused.Code.UNREADABLE, notJson.code());
        LoadRefused layers = assertThrows(LoadRefused.class, () -> Loader.load(connection, Format.GEOPACKAGE,
                Geodatabase.fixture("due-livelli.gpkg"), SCHEMA, "rotto", null));
        assertEquals(LoadRefused.Code.SEVERAL_LAYERS, layers.code());
        assertTrue(layers.getMessage().contains("aree") && layers.getMessage().contains("altre"), layers.getMessage());
        LoadRefused empty = assertThrows(LoadRefused.class, () -> Loader.load(connection, Format.GEOJSON,
                "{\"type\":\"FeatureCollection\",\"features\":[]}".getBytes(StandardCharsets.UTF_8), SCHEMA, "rotto", null));
        assertEquals(LoadRefused.Code.EMPTY, empty.code());
        connection.rollback();
        assertFalse(exists(connection, "rotto"));
    }

    @Test
    void gp937ReplacingKeepsTheOldTableUntilTheNewOneIsWritten() throws Exception {
        load("poi-esempio.geojson", Format.GEOJSON, "versione");
        connection.commit();
        try {
            assertThrows(LoadRefused.class, () -> Loader.load(connection, Format.GEOJSON,
                    "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[1]}}".getBytes(StandardCharsets.UTF_8),
                    SCHEMA, "versione", null));
            connection.rollback();
            assertTrue(exists(connection, "versione"));
            assertEquals(5L, ((Number) one(connection, "select count(*) from " + SCHEMA + ".versione")).longValue());
        } finally {
            Loader.drop(connection, SCHEMA, "versione");
            connection.commit();
        }
    }
}
