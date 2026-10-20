// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.ext.catalog.geodata.geoserver.ConnectionStore;
import dev.geoportal.ext.catalog.geodata.geoserver.LayerDefinition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-986, ADR-0030: how the geodata name the tables of a PostgreSQL connection and the GeoServer stores
 * that read them, and what of a reference of the source picker they send to the framework to resolve.
 */
@Tag("GP-986")
class ConnectionSourceTest {

    private static final UUID CONNECTION = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");

    @Test
    void theIdsOfThePiecesOfPostgresqlGiveTheSchemaAndTheTable() {
        assertEquals(List.of("rete", "fermate"), SourceProbe.names("rete/fermate"));
        assertEquals(List.of("a/b", "100%"), SourceProbe.names("a%2Fb/100%25"));
        assertThrows(IllegalArgumentException.class, () -> SourceProbe.names("rete//x"));
        assertThrows(IllegalArgumentException.class, () -> SourceProbe.names("rete/%41"));
    }

    @Test
    void aStorePerConnectionAndSchemaWithANameGeoServerAndTheLayersAccept() {
        String store = Source.storeOf(CONNECTION, "public");
        assertEquals("conn_0f0e0d0c0b0a49088706050403020100", store);
        assertTrue(LayerDefinition.STORE.matcher(store).matches());
        String other = Source.storeOf(CONNECTION, "Rete Urbana");
        assertTrue(other.startsWith(store + "_rete_urbana_"), other);
        assertTrue(LayerDefinition.STORE.matcher(other).matches());
        // A table keeps the name it has in its source, in the store of its connection.
        LayerDefinition layer = new LayerDefinition("fermate", "Fermate Bus", "Fermate", "", "EPSG:4326", null, store);
        assertEquals(store, layer.storeOrDefault());
        assertThrows(IllegalArgumentException.class,
                () -> new LayerDefinition("fermate", "Fermate Bus", "Fermate", "", "EPSG:4326", null, null));
    }

    @Test
    void theStoreSendsThePasswordOnlyWhenWritingAndNeverShowsIt() {
        ConnectionStore store = new ConnectionStore("conn_x", "postgres", 5432, "geodata", "rete", "lettore",
                "segreta".toCharArray(), "require");
        assertEquals("segreta", store.parameters().get("passwd"));
        assertFalse(store.comparable().containsKey("passwd"));
        assertEquals("REQUIRE", store.comparable().get("SSL mode"));
        assertFalse(store.toString().contains("segreta"));
        assertFalse(new ConnectionStore("conn_x", "h", 1, "d", "s", "u", null, null).writable());
    }

    @Test
    void theFrameworkResolvesTheConnectionAndThePieceOfAReferenceNothingElse() throws Exception {
        var reference = new ObjectMapper().readTree("""
                {"connection": {"extension": "io.github.mosaikit.ext.catalog.connection", "type": "connection",
                  "id": "%s"},
                 "part": {"id": "rete/fermate", "parents": ["rete"], "name": "fermate", "kind": "table"},
                 "uses": ["map"], "extra": "ignored"}""".formatted(CONNECTION));
        String request = ConnectionResolver.request(reference).toString();
        assertEquals("{\"references\":[{\"connection\":{\"extension\":\"io.github.mosaikit.ext.catalog.connection\","
                + "\"type\":\"connection\",\"id\":\"" + CONNECTION + "\"},\"part\":{\"id\":\"rete/fermate\","
                + "\"parents\":[\"rete\"]}}]}", request);
        assertThrows(IllegalArgumentException.class, () -> ConnectionResolver.request(new ObjectMapper().readTree(
                "{\"connection\": {\"extension\": \"org.example.other\", \"type\": \"connection\", \"id\": \"x\"},"
                        + " \"part\": {\"id\": \"a/b\"}}")));
    }
}
