// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.load;

import static dev.geoportal.catalog.load.Geodatabase.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.geoportal.catalog.upload.Format;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-321: the extent of a source is computed when it is loaded, from the table, in its own system and
 * in WGS84 degrees; when it cannot be computed reliably there is none rather than a wrong one.
 */
@Tag("postgis")
@Tag("GP-321")
class ExtentTest {

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

    @Test
    void gp321TheExtentOfAVectorIsComputedAtLoadInDegrees() throws Exception {
        Loader.Result result = Loader.load(connection, Format.GEOJSON, Geodatabase.sample("poi-esempio.geojson"), SCHEMA, "poi", null);
        Extent e = result.wgs84();
        assertNotNull(e);
        // From the cathedral of Monteluce (10.87) to the castle of Borgo Alto (15.10, 40.92) and the square of Lagofondo (45.80).
        assertEquals(10.874512, e.minX(), 1e-6);
        assertEquals(45.802519, e.maxY(), 1e-6);
        assertEquals(15.103327, e.maxX(), 1e-6);
        assertEquals(40.917644, e.minY(), 1e-6);
        assertEquals(e, result.nativeExtent());
    }

    @Test
    void gp321TheExtentOfAProjectedSourceIsInItsSystemAndInDegrees() throws Exception {
        Loader.Result result = Loader.load(connection, Format.SHAPEFILE, Geodatabase.fixture("stazioni-utm33.zip"), SCHEMA, "utm", null);
        Extent n = result.nativeExtent();
        assertTrue(n.minX() > 281000 && n.maxX() < 287000 && n.minY() > 4661000 && n.maxY() < 4666000, n.toString());
        Extent e = result.wgs84();
        // Valverde Sud (12.3649, 42.0767) to Valverde Nord (12.4107, 42.1149).
        assertEquals(12.3649, e.minX(), 1e-4);
        assertEquals(42.0767, e.minY(), 1e-4);
        assertEquals(12.4107, e.maxX(), 1e-4);
        assertEquals(42.1149, e.maxY(), 1e-4);
    }

    @Test
    void gp321ASourceWhoseExtentCannotBeComputedReliablyHasNone() throws Exception {
        // Metres of UTM zone 33 far outside the zone: PROJ refuses to bring them back to degrees.
        String json = "{\"type\":\"FeatureCollection\",\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"EPSG:32633\"}},"
                + "\"features\":[{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Point\",\"coordinates\":[1e12,1e12]}}]}";
        Loader.Result result = Loader.load(connection, Format.GEOJSON, json.getBytes(StandardCharsets.UTF_8), SCHEMA, "fuori", null);
        assertNotNull(result.nativeExtent());
        assertNull(result.wgs84(), "no box rather than a wrong one");
    }
}
