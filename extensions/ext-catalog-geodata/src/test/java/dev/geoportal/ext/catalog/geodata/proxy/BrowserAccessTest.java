// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** GP-297: what the browser would be allowed to read, decided from what the server received. */
@Tag("GP-297")
class BrowserAccessTest {

    private static final String SHELL = "https://geo.example.org";

    @Test
    void corsAllowsTheShellWithAStarOrItsOriginExactlyAndOneValueOnly() {
        assertTrue(BrowserAccess.corsAllows(List.of("*"), SHELL));
        assertTrue(BrowserAccess.corsAllows(List.of(" https://geo.example.org "), SHELL));
        assertFalse(BrowserAccess.corsAllows(null, SHELL), "no header: the browser refuses to read");
        assertFalse(BrowserAccess.corsAllows(List.of(), SHELL));
        assertFalse(BrowserAccess.corsAllows(List.of("https://other.example.org"), SHELL));
        assertFalse(BrowserAccess.corsAllows(List.of("https://GEO.example.org"), SHELL), "compared exactly");
        assertFalse(BrowserAccess.corsAllows(List.of("*", "*"), SHELL), "two values are refused");
        assertFalse(BrowserAccess.corsAllows(List.of("null"), SHELL));
    }

    @Test
    void onlyCapabilitiesCountAndAnErrorPageDoesNot() {
        assertTrue(BrowserAccess.isCapabilities("<?xml version=\"1.0\"?>\n<WMS_Capabilities version=\"1.3.0\"></WMS_Capabilities>"));
        assertTrue(BrowserAccess.isCapabilities("﻿<?xml version='1.0' encoding=\"UTF-8\"?><!-- served -->"
                + "<!DOCTYPE WMT_MS_Capabilities SYSTEM \"http://schemas.opengis.net/wms/1.1.1/capabilities.dtd\""
                + " [ <!ELEMENT VendorSpecificCapabilities EMPTY> ]>\n<WMT_MS_Capabilities version=\"1.1.1\">"));
        assertTrue(BrowserAccess.isCapabilities("<wfs:WFS_Capabilities xmlns:wfs=\"http://www.opengis.net/wfs/2.0\">"));
        assertTrue(BrowserAccess.isCapabilities("<Capabilities xmlns=\"http://www.opengis.net/wmts/1.0\">"));
        assertFalse(BrowserAccess.isCapabilities("<!DOCTYPE html><html><body>Service unavailable</body></html>"));
        assertFalse(BrowserAccess.isCapabilities("<ows:ExceptionReport><ows:Exception/></ows:ExceptionReport>"));
        assertFalse(BrowserAccess.isCapabilities("<ServiceExceptionReport>Capabilities not found</ServiceExceptionReport>"));
        assertFalse(BrowserAccess.isCapabilities("{\"capabilities\": true}"));
        assertFalse(BrowserAccess.isCapabilities(""));
        assertFalse(BrowserAccess.isCapabilities(null));
    }

    @Test
    void anOriginIsWrittenAsABrowserWritesIt() {
        assertEquals("https://maps.example.org", BrowserAccess.origin(URI.create("HTTPS://Maps.Example.org:443/wms?x=1")));
        assertEquals("http://127.0.0.1:8081", BrowserAccess.origin(URI.create("http://127.0.0.1:8081/geoserver/wms")));
        assertEquals("http://maps.example.org", BrowserAccess.origin(URI.create("http://maps.example.org:80/")));
    }

    @Test
    void withoutAPolicyTheBrowserContactsAnyOrigin() {
        assertFalse(ContentPolicy.NONE.present());
        assertTrue(ContentPolicy.NONE.allowsConnect(URI.create("https://maps.example.org/wms"), SHELL));
        assertTrue(ContentPolicy.of(List.of("img-src 'self'")).allowsConnect(URI.create("https://maps.example.org/"), SHELL));
    }

    @Test
    void thePolicyOfTheKernelAllowsOnlyItselfKeycloakAndTheListedOrigins() {
        // The policy of the kernel with two external origins (MK-128).
        ContentPolicy policy = ContentPolicy.of(List.of("default-src 'self'; style-src 'self' 'unsafe-inline';"
                + " img-src 'self' https://maps.example.org https://*.tiles.example.org;"
                + " connect-src 'self' https://sso.example.org https://maps.example.org https://*.tiles.example.org"
                + " http://127.0.0.1:8081; object-src 'none'; base-uri 'self'; form-action 'none'; frame-ancestors 'none'"));
        assertTrue(policy.present());
        assertTrue(policy.allowsConnect(URI.create("https://maps.example.org/geoserver/wms?SERVICE=WMS"), SHELL));
        assertTrue(policy.allowsConnect(URI.create("https://maps.example.org:443/wms"), SHELL));
        assertTrue(policy.allowsConnect(URI.create("https://a.tiles.example.org/wmts"), SHELL));
        assertTrue(policy.allowsConnect(URI.create("http://127.0.0.1:8081/wms"), SHELL));
        assertTrue(policy.allowsConnect(URI.create("https://geo.example.org/api"), SHELL), "'self'");
        assertFalse(policy.allowsConnect(URI.create("https://tiles.example.org/wmts"), SHELL), "*. is sub-domains only");
        assertFalse(policy.allowsConnect(URI.create("https://maps.example.org:8443/wms"), SHELL), "another port");
        assertFalse(policy.allowsConnect(URI.create("http://maps.example.org/wms"), SHELL), "https only");
        assertFalse(policy.allowsConnect(URI.create("http://127.0.0.1:8082/wms"), SHELL));
        assertFalse(policy.allowsConnect(URI.create("https://other.example.org/wms"), SHELL));
    }

    @Test
    void connectSrcFallsBackToDefaultSrcAndEveryPolicyMustAllow() {
        assertFalse(ContentPolicy.of(List.of("default-src 'self'")).allowsConnect(URI.create("https://maps.example.org/"), SHELL));
        assertTrue(ContentPolicy.of(List.of("default-src 'self'; connect-src *")).allowsConnect(
                URI.create("https://maps.example.org/"), SHELL));
        assertTrue(ContentPolicy.of(List.of("connect-src https:")).allowsConnect(URI.create("https://maps.example.org/"), SHELL));
        assertFalse(ContentPolicy.of(List.of("connect-src *", "connect-src 'self'")).allowsConnect(
                URI.create("https://maps.example.org/"), SHELL));
        assertFalse(ContentPolicy.of(List.of("connect-src *, connect-src 'none'")).allowsConnect(
                URI.create("https://maps.example.org/"), SHELL));
        assertTrue(ContentPolicy.of(List.of("connect-src maps.example.org:*")).allowsConnect(
                URI.create("https://maps.example.org:9443/"), SHELL));
    }
}
