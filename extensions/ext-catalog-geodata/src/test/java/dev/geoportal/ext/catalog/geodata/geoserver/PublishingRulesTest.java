// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.geoserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.geoportal.ext.catalog.geodata.style.LegacyStyle;
import dev.geoportal.ext.catalog.geodata.style.StyleDocument;
import java.io.StringReader;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * GP-114, GP-115 and GP-118 without GeoServer: the translation of a style of Geoportal into SLD, what
 * the gate of a published layer lets through, the reason of a GeoServer error as a person reads it,
 * and the reconciliation report.
 */
class PublishingRulesTest {

    private static final String WS = "org_0a1b2c3d4e5f6789abcdef0123456789";

    @Test
    @Tag("GP-114")
    void aStyleOfGeoportalBecomesTheSymbolizerOfTheGeometryOfTheData() throws Exception {
        StyleDocument style = StyleDocument.fromLegacy(new LegacyStyle("#FF0000", 0.5, "#00ff00", 2, 10, "square"));
        String points = style.sld("stazioni", "Point");
        Document d = parse(points);
        assertEquals("StyledLayerDescriptor", d.getDocumentElement().getTagName());
        assertEquals(1, d.getElementsByTagName("PointSymbolizer").getLength());
        assertTrue(points.contains("<WellKnownName>square</WellKnownName>"));
        assertTrue(points.contains("<CssParameter name=\"fill\">#ff0000</CssParameter>"), "colors are kept lowercase");
        assertTrue(points.contains("<Size>10</Size>"));
        assertTrue(points.contains("<Name>stazioni</Name>"));

        Document lines = parse(style.sld("linee", "MultiLineString"));
        assertEquals(1, lines.getElementsByTagName("LineSymbolizer").getLength());
        assertEquals(0, lines.getElementsByTagName("PolygonSymbolizer").getLength());

        Document areas = parse(style.sld("aree", "MultiPolygon"));
        assertEquals(1, areas.getElementsByTagName("PolygonSymbolizer").getLength());

        // Mixed geometries: one rule per dimension.
        Document mixed = parse(style.sld("misto", "Geometry"));
        assertEquals(3, mixed.getElementsByTagName("Rule").getLength());
        assertEquals(3, mixed.getElementsByTagName("ogc:Filter").getLength());
    }

    @Test
    @Tag("GP-114")
    void aStyleOfGeoportalIsChecked() {
        assertThrows(IllegalArgumentException.class, () -> new LegacyStyle("red", 0.5, "#000000", 1, 8, "circle"));
        assertThrows(IllegalArgumentException.class, () -> new LegacyStyle("#ff0000", 2, "#000000", 1, 8, "circle"));
        assertThrows(IllegalArgumentException.class, () -> new LegacyStyle("#ff0000", 0.5, "#000000", 99, 8, "circle"));
        assertThrows(IllegalArgumentException.class, () -> new LegacyStyle("#ff0000", 0.5, "#000000", 1, 8, "heart"));
        // Nothing of the input reaches the SLD but checked colors, numbers and shapes.
        assertFalse(StyleDocument.defaults().sld("a", "Point").contains("<script"));
    }

    @Test
    @Tag("GP-114")
    void theGateServesOnlyReadRequestsOfItsOwnLayer() {
        assertTrue(OgcGate.refusal("wms", "service=WMS&request=GetCapabilities", WS, "stazioni").isEmpty());
        assertTrue(OgcGate.refusal("wms", "SERVICE=WMS&REQUEST=GetMap&LAYERS=" + WS + "%3Astazioni&BBOX=0,0,1,1", WS,
                "stazioni").isEmpty());
        assertTrue(OgcGate.refusal("wfs", "request=GetFeature&typeNames=stazioni", WS, "stazioni").isEmpty());
        assertTrue(OgcGate.refusal("gwc/service/wmts", "request=GetTile&layer=" + WS + ":stazioni", WS, "stazioni")
                .isEmpty());

        assertTrue(OgcGate.refusal("rest", "request=GetCapabilities", WS, "stazioni").isPresent(), "not a service");
        assertTrue(OgcGate.refusal("wms", "request=GetMap&layers=stazioni,altro", WS, "stazioni").isPresent());
        assertTrue(OgcGate.refusal("wms", "request=GetMap&layers=org_other:stazioni", WS, "stazioni").isPresent());
        assertTrue(OgcGate.refusal("wfs", "request=Transaction", WS, "stazioni").isPresent(), "no writes");
        assertTrue(OgcGate.refusal("wms", "request=GetMap&layers=stazioni&sld=http://evil/x.sld", WS, "stazioni")
                .isPresent(), "GeoServer never fetches an address of the request");
        assertTrue(OgcGate.refusal("wms", "service=WMS", WS, "stazioni").isPresent(), "request is required");
    }

    @Test
    @Tag("GP-114")
    void theGateRefusesEverythingButReadingItsLayer() {
        String l = "stazioni";
        java.util.function.Predicate<String[]> refused = a -> OgcGate.refusal(a[0], a[1], WS, l).isPresent();
        String[][] passing = {
            {"wms", "service=WMS&request=GetLegendGraphic&layer=stazioni&format=image/png"},
            {"wms", "request=DescribeLayer&layers=stazioni"},
            {"wms", "request=GetFeatureInfo&layers=stazioni&query_layers=" + WS + ":stazioni"},
            {"wfs", "request=GetPropertyValue&typeNames=stazioni&valueReference=nome"},
            {"wfs", "request=GetFeature&typeNames=stazioni&resourceId=stazioni.1&cql_filter=nome%3D%27a%27"},
            {"ows", "service=WFS&request=DescribeFeatureType&typeName=stazioni"},
            {"gwc/service/wmts", "service=WMTS&request=GetFeatureInfo&layer=stazioni"},
        };
        for (String[] p : passing) {
            assertFalse(refused.test(p), p[1]);
        }
        String[][] refusing = {
            {"wfs", "request=LockFeature&typeNames=stazioni"},
            {"wfs", "request=GetFeatureWithLock&typeNames=stazioni"},
            {"wfs", "request=Transaction"},
            {"ows", "service=WPS&request=Execute"},
            {"ows", "request=GetCapabilities"},
            {"wms", "service=WFS&request=GetFeature&typeNames=stazioni"},
            {"wms", "request=getmap&layers=stazioni"},
            {"wms", "request=GETMAP&layers=stazioni"},
            {"wms", "request=GetMap&request=GetMap&layers=stazioni"},
            {"wms", "request=GetMap&REQUEST=Transaction&layers=stazioni"},
            {"wms", "request=GetMap&layers=stazioni&SLD_BODY=%3CStyledLayerDescriptor%2F%3E"},
            {"wms", "request=GetMap&layers=stazioni&REMOTE_OWS_URL=http://evil/wfs&REMOTE_OWS_TYPE=WFS"},
            {"wms", "request=GetMap&layers=*"},
            {"wms", "request=GetMap&layers=a_group"},
            {"wms", "request=GetMap&layers="},
            {"wms", "request=GetFeatureInfo&layers=stazioni&query_layers=altro"},
            {"wfs", "request=GetFeature&typeNames=stazioni,altro"},
            {"wfs", "request=GetFeature&typeName=altro"},
            {"wfs", "request=GetFeature&resourceId=altro.1"},
            {"wfs", "request=GetFeature&STOREDQUERY_ID=urn:ogc:def:query:OGC-WFS::GetFeatureById&ID=altro.1"},
            {"wfs", "request=GetFeature&typeNames=stazioni&CQL_FILTER=INTERSECTS(geom,querySingle('altro','geom','INCLUDE'))"},
            {"wfs", "request=GetFeature&typeNames=stazioni&cql_filter=intersects(geom,%20collectGeometries(queryCollection('altro','geom','INCLUDE')))"},
            {"gwc/service/wmts", "request=GetTile&layer=" + WS + ":altro"},
            {"gwc/rest", "request=GetCapabilities"},
            {"web", "request=GetCapabilities"},
            {"wms/../../rest", "request=GetCapabilities"},
        };
        for (String[] r : refusing) {
            assertTrue(refused.test(r), r[0] + "?" + r[1]);
        }
    }

    @Test
    @Tag("GP-115")
    void whatGeoServerSaysIsReadableTextWithoutMarkupOrStackTrace() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?><ServiceExceptionReport version="1.3.0"
                 xmlns="http://www.opengis.net/ogc"><ServiceException code="LayerNotDefined" locator="layers">
                Could not find layer org_x:stazioni
                </ServiceException></ServiceExceptionReport>""";
        assertEquals("Could not find layer org_x:stazioni", GeoServerClient.reason(xml));

        String trace = """
                java.lang.IllegalArgumentException: Trying to create new feature type inside the store, but no attributes were specified
                \tat org.geoserver.catalog.CatalogBuilder.buildFeatureType(CatalogBuilder.java:1234)
                \tat org.geoserver.rest.catalog.FeatureTypeController.featureTypePost(FeatureTypeController.java:99)
                Caused by: org.geotools.api.data.DataSourceException: Schema 'gp_x' does not exist.
                \t... 42 more""";
        String reason = GeoServerClient.reason(trace);
        assertEquals("Trying to create new feature type inside the store, but no attributes were specified"
                + " Schema 'gp_x' does not exist.", reason);
        assertFalse(reason.contains("at org."));
        assertFalse(reason.contains("Exception"));

        String html = "<html><head><style>p{}</style></head><body><h1>HTTP 500</h1><p>Store &quot;data&quot; is"
                + " broken</p></body></html>";
        assertEquals("HTTP 500 Store \"data\" is broken", GeoServerClient.reason(html));
        String tomcat = "<!doctype html><html lang=\"en\"><head><title>HTTP Status 400 – Bad Request</title><style>b{}</style>"
                + "</head><body><h1>HTTP Status 400 – Bad Request</h1><hr class=\"line\" /><p><b>Type</b> Status Report</p>"
                + "<p><b>Message</b> Trying to create new feature type inside the store, but no attributes were specified</p>"
                + "<p><b>Description</b> The server cannot or will not process the request</p></body></html>";
        assertEquals("Trying to create new feature type inside the store, but no attributes were specified",
                GeoServerClient.reason(tomcat));
        assertNull(GeoServerClient.reason("  "));
        assertTrue(GeoServerClient.reason("x".repeat(1000)).length() <= 300);
    }

    @Test
    @Tag("GP-115")
    void aFailureSaysWhatToDoAndCarriesTheReferenceOfTheLog() {
        Failure down = Failure.of(new GeoServerException(0, "GeoServer does not answer at http://gs:8080"), "publishing x");
        assertEquals("geoserver-unreachable", down.code());
        assertEquals(Failure.RETRY, down.action());
        assertEquals(503, down.status());
        assertTrue(down.reference().matches("GP-[0-9A-F]{8}"));
        assertFalse(down.detail().contains("gs:8080"), "the address of GeoServer stays in the log");

        Alignment.Change refused = new Alignment.Change("layer", "stazioni", "create", "missing",
                "GeoServer answered POST /rest/... with 500: boom", "Schema 'gp_x' does not exist.");
        Failure f = Failure.refused(refused, "publishing stazioni");
        assertEquals("geoserver-refused", f.code());
        assertEquals(Failure.CORRECT, f.action());
        assertEquals("Schema 'gp_x' does not exist.", f.cause());
        assertEquals(502, f.status());
        assertEquals(502, f.response().getStatus());
    }

    @Test
    @Tag("GP-118")
    void theReconciliationTellsWhatMatchesWhatIsMissingWhatDriftedAndWhatNeedsAPerson() {
        Alignment a = new Alignment(WS, false, List.of(
                new Alignment.Change("layer", "missing_one", "create", "missing", null),
                new Alignment.Change("layer", "by_hand", "update", "title", null),
                new Alignment.Change("style", "old", "delete", "withdrawn", null),
                new Alignment.Change("layer", "broken", "create", "missing", "GeoServer answered ...", "no table")),
                List.of(new Alignment.Drift("layer", "stray"), new Alignment.Drift("style", "stray_style")));
        Reconciliation r = Reconciliation.of(a, List.of("missing_one", "by_hand", "fine", "broken"), List.of("fine_style"));
        assertEquals(List.of("fine_style", "fine"), r.aligned().stream().map(Reconciliation.Item::name).toList());
        assertEquals(List.of("missing_one"), r.missing().stream().map(Reconciliation.Item::name).toList());
        assertEquals(List.of("by_hand"), r.changed().stream().map(Reconciliation.Item::name).toList());
        assertEquals(List.of("old"), r.withdrawn().stream().map(Reconciliation.Item::name).toList());
        assertEquals(List.of("stray", "stray_style"), r.unknown().stream().map(Reconciliation.Item::name).toList());
        assertEquals("no table", r.failed().getFirst().cause());
        assertEquals(3, r.needsPerson());
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }
}
