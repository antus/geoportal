// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.load;

import static dev.geoportal.ext.catalog.geodata.load.Geodatabase.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.ext.catalog.geodata.upload.Format;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-973: what the style editor reads of a loaded table, against PostGIS: the attributes, the distinct
 * values of one from which a categorized style makes one class per value, the range and quantiles of
 * a number for a graduated one, and a sample of the features in WGS84 for the preview map.
 */
@Tag("postgis")
@Tag("GP-973")
class DatasetValuesTest {

    private Connection connection;

    @BeforeEach
    void open() throws Exception {
        connection = Geodatabase.connect();
        Loader.createSchema(connection, SCHEMA);
    }

    @AfterEach
    void close() throws Exception {
        connection.rollback();
        connection.close();
    }

    private void load(String file, String table) throws Exception {
        Loader.load(connection, Format.GEOJSON, Geodatabase.sample(file), SCHEMA, table, null);
    }

    @Test
    void attributesAndDistinctValuesOfACategorizedStyle() throws Exception {
        load("aree_protette_esempio.geojson", "aree");
        List<DatasetValues.Attribute> attributes = DatasetValues.attributes(connection, SCHEMA, "aree");
        assertEquals(List.of("nome", "tipo", "codice"), attributes.stream().map(DatasetValues.Attribute::name).limit(3).toList());
        assertFalse(attributes.get(1).numeric());
        assertTrue(attributes.get(2).numeric());

        DatasetValues.Values tipo = DatasetValues.values(connection, SCHEMA, "aree", "tipo", 5).orElseThrow();
        assertEquals(19, tipo.features());
        assertFalse(tipo.more());
        // One entry per distinct value, with how many features have it, the most frequent first.
        Map<Object, Long> counts = tipo.values().stream().collect(Collectors.toMap(DatasetValues.Count::value, DatasetValues.Count::count));
        assertEquals(19, counts.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(counts.size(), tipo.values().size());
        assertTrue(counts.containsKey("parco naturale"));
        for (int i = 1; i < tipo.values().size(); i++) {
            assertTrue(tipo.values().get(i - 1).count() >= tipo.values().get(i).count());
        }
        // Only the columns of the table: neither another name nor an injection.
        assertTrue(DatasetValues.values(connection, SCHEMA, "aree", "geom", 5).isEmpty());
        assertTrue(DatasetValues.values(connection, SCHEMA, "aree", "tipo\"; drop table x; --", 5).isEmpty());
    }

    @Test
    void rangeAndQuantilesOfAGraduatedStyle() throws Exception {
        load("quartieri_valverde.geojson", "quartieri");
        DatasetValues.Values abitanti = DatasetValues.values(connection, SCHEMA, "quartieri", "abitanti", 4).orElseThrow();
        assertTrue(abitanti.numeric());
        assertEquals(15, abitanti.features());
        assertEquals(8000.0, abitanti.min());
        assertEquals(3, abitanti.quantiles().size());
        double before = abitanti.min();
        for (double q : abitanti.quantiles()) {
            assertTrue(q >= before && q <= abitanti.max(), abitanti.toString());
            before = q;
        }
    }

    @Test
    void sampleInWgs84() throws Exception {
        load("quartieri_valverde.geojson", "quartieri");
        JsonNode sample = new ObjectMapper().readTree(DatasetValues.sample(connection, SCHEMA, "quartieri", 10));
        assertEquals("FeatureCollection", sample.path("type").asText());
        assertEquals(10, sample.path("features").size());
        JsonNode first = sample.path("features").get(0);
        assertEquals("Quartiere I", first.path("properties").path("nome").asText());
        assertFalse(first.path("properties").has("geom"));
        assertFalse(first.path("properties").has("fid"));
        // The first position of the first ring, however deep (a polygon may be loaded as a multipolygon).
        JsonNode position = first.path("geometry").path("coordinates");
        while (position.get(0).isArray()) {
            position = position.get(0);
        }
        assertEquals(12.3, position.get(0).asDouble(), 1e-6);
        assertEquals(42.05, position.get(1).asDouble(), 1e-6);
    }
}
