// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.editing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-036: what the catalog forwards to GeoServer as the transaction of a surveyor: insert, update and
 * delete on the layer of the request, nothing on another layer or workspace, no native action, no DTD.
 */
@Tag("GP-036")
class WfsTransactionTest {

    static final String WS = "org_11111111222233334444555555555555";
    static final String NS = "urn:geoportal:organization:11111111-2222-3333-4444-555555555555";

    static byte[] xml(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static String tx(String body) {
        return "<wfs:Transaction service=\"WFS\" version=\"1.1.0\" xmlns:wfs=\"http://www.opengis.net/wfs\""
                + " xmlns:ogc=\"http://www.opengis.net/ogc\" xmlns:gml=\"http://www.opengis.net/gml\" xmlns:o=\"" + NS + "\">"
                + body + "</wfs:Transaction>";
    }

    static final String INSERT = "<wfs:Insert><o:parcels><o:nome>nuova</o:nome></o:parcels></wfs:Insert>";
    static final String UPDATE = "<wfs:Update typeName=\"o:parcels\"><wfs:Property><wfs:Name>nome</wfs:Name>"
            + "<wfs:Value>x</wfs:Value></wfs:Property><ogc:Filter><ogc:FeatureId fid=\"parcels.1\"/></ogc:Filter></wfs:Update>";
    static final String DELETE = "<wfs:Delete typeName=\"" + WS + ":parcels\"><ogc:Filter><ogc:FeatureId fid=\"parcels.2\"/>"
            + "</ogc:Filter></wfs:Delete>";

    @Test
    void insertUpdateAndDeleteOnTheLayerAreForwarded() throws Exception {
        WfsTransaction.Summary s = WfsTransaction.check(xml(tx(INSERT + UPDATE + DELETE)), "parcels", WS, NS);
        assertEquals(Map.of("insert", 1, "update", 1, "delete", 1), s.actions());
        // WFS 2.0 and an unqualified name.
        String v2 = "<wfs:Transaction service=\"WFS\" version=\"2.0.0\" xmlns:wfs=\"http://www.opengis.net/wfs/2.0\">"
                + "<wfs:Delete typeName=\"parcels\"/></wfs:Transaction>";
        assertEquals(Map.of("delete", 1), WfsTransaction.check(xml(v2), "parcels", WS, NS).actions());
    }

    @Test
    void anotherLayerWorkspaceOrNamespaceIsRefused() {
        assertRefused(tx("<wfs:Insert><o:roads><o:nome>x</o:nome></o:roads></wfs:Insert>"), "roads");
        assertRefused(tx(INSERT + "<wfs:Delete typeName=\"o:roads\"/>"), "roads");
        assertRefused(tx("<wfs:Delete typeName=\"org_99999999999999999999999999999999:parcels\"/>"), "another workspace");
        assertRefused(tx("<wfs:Insert><x:parcels xmlns:x=\"urn:other\"/></wfs:Insert>"), "another namespace");
        assertRefused(tx("<wfs:Update typeName=\"x:parcels\" xmlns:x=\"urn:other\"/>"), "another namespace");
    }

    @Test
    void whatIsNotATransactionOfFeaturesIsRefused() {
        assertRefused(tx("<wfs:Native vendorId=\"x\" safeToIgnore=\"false\">drop table</wfs:Native>"), "Native");
        assertRefused(tx(""), "no action");
        assertRefused(tx("<wfs:Insert/>"), "without a feature");
        assertRefused(tx("<wfs:Update/>"), "without typeName");
        assertRefused("<wfs:GetFeature xmlns:wfs=\"http://www.opengis.net/wfs\"/>", "Only a WFS Transaction");
        assertRefused("not xml", "not well-formed");
        assertRefused("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + tx(INSERT), "DTD");
        WfsTransaction.Refused empty = assertThrows(WfsTransaction.Refused.class,
                () -> WfsTransaction.check(new byte[0], "parcels", WS, NS));
        assertTrue(empty.getMessage().contains("body"));
    }

    @Test
    void theFailureOfGeoServerIsReadFromItsAnswer() {
        assertTrue(WfsTransaction.failure("<wfs:TransactionResponse><wfs:TransactionSummary><wfs:totalInserted>1"
                + "</wfs:totalInserted></wfs:TransactionSummary></wfs:TransactionResponse>").isEmpty());
        assertEquals("Feature type parcels is not available",
                WfsTransaction.failure("<ows:ExceptionReport><ows:Exception><ows:ExceptionText>Feature type parcels is not"
                        + " available</ows:ExceptionText></ows:Exception></ows:ExceptionReport>").orElseThrow());
    }

    static void assertRefused(String body, String why) {
        WfsTransaction.Refused e = assertThrows(WfsTransaction.Refused.class,
                () -> WfsTransaction.check(xml(body), "parcels", WS, NS), body);
        assertTrue(e.getMessage().contains(why), e.getMessage());
    }
}
