// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.load;

import static dev.geoportal.ext.catalog.geodata.load.Geodatabase.SCHEMA;
import static dev.geoportal.ext.catalog.geodata.load.Geodatabase.exists;
import static dev.geoportal.ext.catalog.geodata.load.Geodatabase.one;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.geoportal.ext.catalog.geodata.upload.Format;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-324: the reference system of every file is the one it states, kept in PostGIS, and the data land
 * at the right place; a file that states none is refused with a message naming the problem, or loaded
 * with the system the person declares; it is never guessed. The test is Valverde, an invented town of the
 * samples: its three stations written in EPSG:32633 must come back within a metre of their WGS84 position.
 */
@Tag("postgis")
@Tag("GP-324")
class ReferenceSystemTest {

    /** The three stations of Valverde: longitude, latitude. */
    private static final Map<String, double[]> STATIONS = Map.of(
            "Valverde Centrale", new double[] {12.3812, 42.1054},
            "Valverde Nord", new double[] {12.4107, 42.1149},
            "Valverde Sud", new double[] {12.3649, 42.0767});

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

    /** Every station within a metre of where it is, read back in WGS84 from the native system. */
    private void stationsInValverde(String table) throws Exception {
        for (Map.Entry<String, double[]> s : STATIONS.entrySet()) {
            Object distance = one(connection, "select ST_Distance(ST_Transform(geom, 4326)::geography, ST_MakePoint(?, ?)::geography)"
                    + " from " + SCHEMA + "." + table + " where nome = ?", s.getValue()[0], s.getValue()[1], s.getKey());
            assertTrue(distance != null && ((Number) distance).doubleValue() < 1, s.getKey() + " is " + distance + " m away");
        }
    }

    private String projectedGeoJson(String crsMember) throws Exception {
        StringBuilder json = new StringBuilder("{\"type\":\"FeatureCollection\"" + crsMember + ",\"features\":[");
        boolean first = true;
        for (Map.Entry<String, double[]> s : STATIONS.entrySet()) {
            Object x = one(connection, "select ST_X(ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 32633))", s.getValue()[0], s.getValue()[1]);
            Object y = one(connection, "select ST_Y(ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 32633))", s.getValue()[0], s.getValue()[1]);
            json.append(first ? "" : ",").append("{\"type\":\"Feature\",\"properties\":{\"nome\":\"").append(s.getKey())
                    .append("\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[").append(x).append(',').append(y).append("]}}");
            first = false;
        }
        return json.append("]}").toString();
    }

    @Test
    void gp324AProjectedGeoJsonKeepsItsSystemWhichTheImporterReadAs4326() throws Exception {
        String json = projectedGeoJson(",\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"urn:ogc:def:crs:EPSG::32633\"}}");
        Loader.Result result = Loader.load(connection, Format.GEOJSON, json.getBytes(StandardCharsets.UTF_8), SCHEMA, "utm_json", null);
        assertEquals("EPSG:32633", result.crs().code());
        assertEquals(Crs.Source.FILE, result.crs().source());
        assertEquals(32633, one(connection, "select Find_SRID(?, 'utm_json', 'geom')", SCHEMA));
        stationsInValverde("utm_json");
    }

    @Test
    void gp324AGeoJsonInMetresThatStatesNoSystemIsRefusedNotPlacedInDegrees() throws Exception {
        String json = projectedGeoJson("");
        LoadRefused refused = assertThrows(LoadRefused.class, () -> Loader.load(connection, Format.GEOJSON,
                json.getBytes(StandardCharsets.UTF_8), SCHEMA, "metri", null));
        assertEquals(LoadRefused.Code.CRS_MISMATCH, refused.code());
        assertTrue(refused.getMessage().contains("EPSG"), refused.getMessage());
        connection.rollback();
        assertFalse(exists(connection, "metri"));
    }

    @Test
    void gp324AShapefileWithAPrjOfItsDialectIsIdentifiedByName() throws Exception {
        Loader.Result result = Loader.load(connection, Format.SHAPEFILE, Geodatabase.fixture("stazioni-utm33.zip"), SCHEMA, "utm_shp", null);
        assertEquals("EPSG:32633", result.crs().code());
        assertEquals("Point", result.geometryType());
        stationsInValverde("utm_shp");
    }

    @Test
    void gp324AShapefileWithoutPrjIsRefusedUntilItsSystemIsDeclared() throws Exception {
        byte[] withoutPrj = withoutEntry(Geodatabase.fixture("stazioni-utm33.zip"), ".prj");
        LoadRefused refused = assertThrows(LoadRefused.class, () -> Loader.load(connection, Format.SHAPEFILE, withoutPrj, SCHEMA, "noprj", null));
        assertEquals(LoadRefused.Code.CRS_MISSING, refused.code());
        assertTrue(refused.getMessage().contains("reference system"), refused.getMessage());
        connection.rollback();
        assertFalse(exists(connection, "noprj"));

        Loader.Result declared = Loader.load(connection, Format.SHAPEFILE, withoutPrj, SCHEMA, "noprj", 32633);
        assertEquals(Crs.Source.DECLARED, declared.crs().source());
        stationsInValverde("noprj");
    }

    @Test
    void gp324ADeclaredSystemDoesNotReplaceTheOneTheFileStates() throws Exception {
        Loader.Result result = Loader.load(connection, Format.SHAPEFILE, Geodatabase.fixture("stazioni-utm33.zip"), SCHEMA, "dichiarato", 3003);
        assertEquals("EPSG:32633", result.crs().code());
        assertEquals(Crs.Source.FILE, result.crs().source());
    }

    @Test
    void gp324ACsvOfXAndYStatesNoSystemAndIsRefusedOrDeclared() throws Exception {
        StringBuilder csv = new StringBuilder("nome;x;y\n");
        for (Map.Entry<String, double[]> s : STATIONS.entrySet()) {
            Object x = one(connection, "select ST_X(ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 32633))", s.getValue()[0], s.getValue()[1]);
            Object y = one(connection, "select ST_Y(ST_Transform(ST_SetSRID(ST_MakePoint(?, ?), 4326), 32633))", s.getValue()[0], s.getValue()[1]);
            csv.append(s.getKey()).append(';').append(x.toString().replace('.', ',')).append(';').append(y.toString().replace('.', ',')).append('\n');
        }
        byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);
        LoadRefused refused = assertThrows(LoadRefused.class, () -> Loader.load(connection, Format.CSV, content, SCHEMA, "xy", null));
        assertEquals(LoadRefused.Code.CRS_MISSING, refused.code());
        connection.rollback();
        Loader.Result declared = Loader.load(connection, Format.CSV, content, SCHEMA, "xy", 32633);
        assertEquals("EPSG:32633", declared.crs().code());
        stationsInValverde("xy");
    }

    @Test
    void gp324AGeoPackageInUtmKeepsItsSystem() throws Exception {
        Loader.Result result = Loader.load(connection, Format.GEOPACKAGE, Geodatabase.fixture("aree-utm33.gpkg"), SCHEMA, "utm_gpkg", null);
        assertEquals("EPSG:32633", result.crs().code());
        Object castle = one(connection, "select ST_Contains(ST_Transform(geom, 4326), ST_SetSRID(ST_MakePoint(12.3790, 42.1040), 4326))"
                + " from " + SCHEMA + ".utm_gpkg where aperto");
        assertEquals(Boolean.TRUE, castle);
    }

    @Test
    void gp324GmlAxisOrderFollowsItsSrsName() throws Exception {
        // GML 3.2 with a URN of EPSG:4326 writes latitude first: the sample of the landmarks.
        Loader.load(connection, Format.GML, Geodatabase.sample("poi-esempio.gml"), SCHEMA, "gml_urn", null);
        Object x = one(connection, "select ST_X(geom) from " + SCHEMA + ".gml_urn where nome = 'Torre Civica'");
        assertEquals(12.381245, ((Number) x).doubleValue(), 1e-6);
        // GML 2 with EPSG:4326 writes longitude first.
        Loader.Result gml2 = Loader.load(connection, Format.GML, Geodatabase.fixture("stazioni-gml2.gml"), SCHEMA, "gml_lonlat", null);
        assertEquals("EPSG:4326", gml2.crs().code());
        stationsInValverde("gml_lonlat");
        // A projected URN puts easting first: nothing to swap.
        Loader.Result utm = Loader.load(connection, Format.GML, Geodatabase.fixture("stazioni-utm33.gml"), SCHEMA, "gml_utm", null);
        assertEquals("EPSG:32633", utm.crs().code());
        stationsInValverde("gml_utm");
    }

    @Test
    void gp324AnUnknownSystemIsRefusedWithItsName() throws Exception {
        String json = "{\"type\":\"FeatureCollection\",\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"urn:ogc:def:crs:EPSG::999999\"}},"
                + "\"features\":[{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Point\",\"coordinates\":[1,2]}}]}";
        LoadRefused refused = assertThrows(LoadRefused.class, () -> Loader.load(connection, Format.GEOJSON,
                json.getBytes(StandardCharsets.UTF_8), SCHEMA, "ignoto", null));
        assertEquals(LoadRefused.Code.CRS_UNKNOWN, refused.code());
        assertTrue(refused.getMessage().contains("EPSG:999999"), refused.getMessage());
        String named = json.replace("urn:ogc:def:crs:EPSG::999999", "Gauss-Boaga fuso strano");
        LoadRefused unnamed = assertThrows(LoadRefused.class, () -> Loader.load(connection, Format.GEOJSON,
                named.getBytes(StandardCharsets.UTF_8), SCHEMA, "ignoto", null));
        assertEquals(LoadRefused.Code.CRS_UNKNOWN, unnamed.code());
        assertTrue(unnamed.getMessage().contains("Gauss-Boaga fuso strano"), unnamed.getMessage());
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
