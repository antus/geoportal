// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.geoserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** GP-334 without GeoServer: the names of the space of an organization, and the comparison of styles. */
@Tag("GP-334")
class SpaceAndStyleTest {

    @Test
    void theSpaceIsNamedFromTheIdentifierOfTheOrganizationAndGivesItBack() {
        UUID id = UUID.fromString("0a1b2c3d-4e5f-6789-abcd-ef0123456789");
        OrganizationSpace space = new OrganizationSpace(id);
        assertEquals("org_0a1b2c3d4e5f6789abcdef0123456789", space.workspace());
        assertEquals(space.workspace(), space.schema());
        assertEquals("urn:geoportal:organization:" + id, space.namespaceUri());
        assertEquals(Optional.of(space), OrganizationSpace.of(space.workspace()));
        assertEquals(Optional.empty(), OrganizationSpace.of("geoportal"));
        assertEquals(Optional.empty(), OrganizationSpace.of("org_0A1B2C3D4E5F6789ABCDEF0123456789"));
        assertEquals(Optional.empty(), OrganizationSpace.of("org_x; drop schema public"));
    }

    @Test
    void namesAndReferenceSystemsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new LayerDefinition("Bad-Name", "t", "T", "", "EPSG:4326", null));
        assertThrows(IllegalArgumentException.class, () -> new LayerDefinition("ok", "t", "T", "", "4326", null));
        assertThrows(IllegalArgumentException.class, () -> new StyleDefinition("s", " "));
        assertEquals("", new LayerDefinition("ok", "t", "T", null, "EPSG:32633", "s").description());
    }

    @Test
    void stylesAreComparedAsXmlNotAsText() {
        String a = "<?xml version=\"1.0\"?><sld xmlns=\"http://www.opengis.net/sld\"><Name>a</Name><Size>8</Size></sld>";
        String b = "<sld xmlns=\"http://www.opengis.net/sld\">\n  <!-- layout -->\n  <Name> a </Name>\n  <Size>8</Size>\n</sld>\n";
        String c = "<sld xmlns=\"http://www.opengis.net/sld\"><Name>a</Name><Size>9</Size></sld>";
        assertTrue(Sld.same(a, b));
        assertFalse(Sld.same(a, c));
        assertFalse(Sld.same("not xml", "<a/>"));
    }

    @Test
    void theStoreConnectionNeverShowsItsPassword() {
        StoreConnection store = new StoreConnection("postgres", 5432, "geodata", "geoserver", "s3cret".toCharArray());
        assertFalse(store.toString().contains("s3cret"));
        OrganizationSpace space = new OrganizationSpace(UUID.randomUUID());
        assertFalse(store.comparable(space).containsKey("passwd"));
        assertEquals("s3cret", store.parameters(space).get("passwd"));
        assertEquals(space.schema(), store.parameters(space).get("schema"));
    }
}
