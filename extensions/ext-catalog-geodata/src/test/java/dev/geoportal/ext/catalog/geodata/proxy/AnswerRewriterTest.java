// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** GP-334, criterion 4: what the proxy changes in the text answers of a service. */
@Tag("GP-334")
class AnswerRewriterTest {

    private static final URI REGISTERED = URI.create("https://maps.example.org/geoserver/ows?map=reti");
    private static final String PROXY = "https://geo.example.test/api/v1/ext/catalog-geodata/services/42/proxy";

    private static String rewrite(String body, String authorization) {
        return new String(AnswerRewriter.rewrite(body.getBytes(StandardCharsets.UTF_8), REGISTERED, PROXY,
                AnswerRewriter.secrets(authorization)), StandardCharsets.UTF_8);
    }

    @Test
    void theAddressesOfTheServiceBecomeThoseOfTheProxyAndOthersStay() {
        String xml = """
                <a xlink:href="https://maps.example.org/geoserver/ows?SERVICE=WMS&amp;"/>
                <b xlink:href="https://MAPS.example.org:443/geoserver/wms?"/>
                <c xlink:href="https://maps.example.org/geoserver/ows/sub/path?x=1"/>
                <d xlink:href="http://maps.example.org/geoserver/ows?"/>
                <e xlink:href="https://maps.example.org:8443/geoserver/ows?"/>
                <f xlink:href="https://other.example.org/geoserver/ows?"/>""";
        String out = rewrite(xml, null);
        assertTrue(out.contains("<a xlink:href=\"" + PROXY + "?SERVICE=WMS&amp;\"/>"), out);
        assertTrue(out.contains("<b xlink:href=\"" + PROXY + "?\"/>"), out);
        assertTrue(out.contains("<c xlink:href=\"" + PROXY + "/sub/path?x=1\"/>"), out);
        // Another scheme or port is another server: left alone.
        assertTrue(out.contains("<d xlink:href=\"http://maps.example.org/geoserver/ows?\"/>"), out);
        assertTrue(out.contains("<e xlink:href=\"https://maps.example.org:8443/geoserver/ows?\"/>"), out);
        assertTrue(out.contains("<f xlink:href=\"https://other.example.org/geoserver/ows?\"/>"), out);
    }

    @Test
    void anythingOfTheCredentialTheServiceEchoesIsRemoved() {
        String basic = "Basic " + Base64.getEncoder().encodeToString("reader:pässword-1".getBytes(StandardCharsets.UTF_8));
        String out = rewrite("got " + basic + " for reader:pässword-1, password pässword-1 ok", basic);
        assertFalse(out.contains("pässword-1"), out);
        assertFalse(out.contains(basic.substring(6)), out);
        assertEquals("got Basic [credential removed] for [credential removed], password [credential removed] ok", out);

        String bearer = rewrite("token abc.def.ghi here", "Bearer abc.def.ghi");
        assertEquals("token [credential removed] here", bearer);
    }

    @Test
    void onlyTextIsRewritten() {
        assertTrue(AnswerRewriter.textual("text/xml; charset=UTF-8"));
        assertTrue(AnswerRewriter.textual("application/vnd.ogc.wms_xml"));
        assertTrue(AnswerRewriter.textual("application/json"));
        assertTrue(AnswerRewriter.textual("text/html"));
        assertFalse(AnswerRewriter.textual("image/png"));
        assertFalse(AnswerRewriter.textual(null));
    }
}
