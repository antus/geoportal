// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** GP-939: the proxy forwards only to the registered address of the service. */
@Tag("GP-939")
class ProxyTargetTest {

    private static final URI WMS = URI.create("https://maps.example.org/geoserver/ows?map=base.map");

    @Test
    void GP_939_the_query_of_the_browser_goes_to_the_registered_address() throws Exception {
        assertEquals(
                URI.create("https://maps.example.org/geoserver/ows?map=base.map&SERVICE=WMS&REQUEST=GetMap"),
                ProxyTarget.build(WMS, null, "SERVICE=WMS&REQUEST=GetMap"));
    }

    @Test
    void GP_939_parameters_of_the_registered_address_cannot_be_overridden() throws Exception {
        assertEquals(
                URI.create("https://maps.example.org/geoserver/ows?map=base.map&x=1"),
                ProxyTarget.build(WMS, null, "MAP=/etc/other.map&x=1"));
    }

    @Test
    void GP_939_a_path_below_the_registered_one_is_allowed() throws Exception {
        assertEquals(
                URI.create("https://maps.example.org/geoserver/ows/gwc/rest/wmts/a:b/0/1/2.png?map=base.map"),
                ProxyTarget.build(WMS, "gwc/rest/wmts/a:b/0/1/2.png", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "..", "../admin", "a/../../admin", "%2e%2e/admin", "%2E%2E", "a%2Fb", "a%5cb", "/etc/passwd",
        "http:%2F%2F169.254.169.254", "a\\b", "a//b", "a/%00", "a b", "x/%25"
    })
    void GP_939_a_path_that_would_leave_the_registered_address_is_refused(String path) {
        assertThrows(RefusedTarget.class, () -> ProxyTarget.build(WMS, path, null));
    }

    @Test
    void GP_939_a_query_that_is_not_valid_is_refused() {
        assertThrows(RefusedTarget.class, () -> ProxyTarget.build(WMS, null, "a=b c"));
    }

    @Test
    void GP_939_redirects_are_followed_only_on_the_origin_of_the_service() throws Exception {
        URI current = URI.create("https://maps.example.org/geoserver/ows?x=1");
        assertEquals(URI.create("https://maps.example.org/geoserver/wms?x=1"),
                ProxyTarget.redirect(current, "/geoserver/wms?x=1", WMS));
        Map<String, String> elsewhere = new HashMap<>();
        elsewhere.put("metadata", "http://169.254.169.254/latest/meta-data/iam/security-credentials/");
        elsewhere.put("another port", "https://maps.example.org:8443/geoserver/ows");
        elsewhere.put("plain http", "http://maps.example.org/geoserver/ows");
        elsewhere.put("protocol relative", "//evil.example.org/");
        elsewhere.put("user info", "https://maps.example.org@evil.example.org/");
        elsewhere.put("other scheme", "file:///etc/passwd");
        elsewhere.forEach((why, location) -> assertThrows(RefusedTarget.class,
                () -> ProxyTarget.redirect(current, location, WMS), why));
    }

    @Test
    void GP_939_a_get_feature_declares_the_reference_system() {
        assertEquals("service=WFS&request=GetFeature&typeName=a:b&srsName=EPSG%3A3857",
                OgcRequests.declareCrs("service=WFS&request=GetFeature&typeName=a:b", "EPSG:3857"));
        assertEquals("service=WFS&request=GetFeature&SRSNAME=EPSG:4326",
                OgcRequests.declareCrs("service=WFS&request=GetFeature&SRSNAME=EPSG:4326", "EPSG:3857"));
        assertEquals("service=WFS&request=GetCapabilities",
                OgcRequests.declareCrs("service=WFS&request=GetCapabilities", "EPSG:3857"));
    }
}
