// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.geoserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.ext.catalog.geodata.geoserver.Alignment.Change;
import dev.geoportal.ext.catalog.geodata.geoserver.Alignment.Drift;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-334 against a real GeoServer: the image and the init script of the server distribution, GeoFence
 * on PostgreSQL with its default DENY, PostGIS with the databases of the distribution. Each test works
 * on organizations of its own, so that the tests do not depend on each other.
 */
@Tag("GP-334")
@Tag("geoserver")
class GeoServerAlignmentTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static GeoServerStack stack;
    private static GeoServerClient geoserver;
    private static Reconciler reconciler;

    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(GeoServerStack.distributionFound(), "the server distribution is next to the catalog");
        stack = new GeoServerStack().start();
        geoserver = stack.client();
        reconciler = new Reconciler(geoserver, stack.schemas());
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    @Test
    void anOrganizationGetsItsWorkspaceStoreAndSchemaNamedFromItsIdentifierAndASecondRunChangesNothing() {
        OrganizationSpace space = new OrganizationSpace(UUID.randomUUID());
        Desired desired = Desired.empty(stack.store());

        Alignment planned = reconciler.plan(space, desired);
        assertFalse(planned.applied());
        assertEquals(List.of("schema", "workspace", "store"), kinds(planned));
        assertFalse(geoserver.hasWorkspace(space.workspace()), "planning changes nothing");

        Alignment first = reconciler.apply(space, desired);
        assertTrue(first.applied());
        assertFalse(first.failed(), () -> first.changes().toString());
        assertEquals(List.of("schema", "workspace", "store"), kinds(first));
        assertEquals("org_" + space.organization().toString().replace("-", ""), space.workspace());
        assertEquals(space, OrganizationSpace.of(space.workspace()).orElseThrow());
        assertTrue(geoserver.hasWorkspace(space.workspace()));
        assertEquals(space.namespaceUri(), geoserver.namespaceUri(space.workspace()).orElseThrow());
        assertEquals(space.schema(), geoserver.dataStoreParameters(space.workspace(), "data").orElseThrow().get("schema"));

        Alignment second = reconciler.apply(space, desired);
        assertTrue(second.aligned(), () -> "idempotent: " + second.changes());
        assertEquals(List.of(), second.drift());
        assertEquals(1, geoserver.workspaces().stream().filter(space.workspace()::equals).count());
        assertEquals(List.of("data"), geoserver.dataStores(space.workspace()));
    }

    @Test
    void aDatasetIsAnsweredByWmsAndWfsOfTheWorkspaceOfItsOrganizationOnly() throws Exception {
        OrganizationSpace alfa = new OrganizationSpace(UUID.randomUUID());
        OrganizationSpace beta = new OrganizationSpace(UUID.randomUUID());
        reconciler.apply(alfa, Desired.empty(stack.store()));
        reconciler.apply(beta, Desired.empty(stack.store()));
        stack.sql(points(alfa.schema(), "parcels", 4326, "POINT(12.3812 42.1054)", "POINT(12.38 42.1)"));
        stack.sql(points(beta.schema(), "stops", 4326, "POINT(10.8745 44.6123)"));

        Alignment a = reconciler.apply(alfa, publishing(stack.store(), layer("parcels", "EPSG:4326", null)));
        Alignment b = reconciler.apply(beta, publishing(stack.store(), layer("stops", "EPSG:4326", null)));
        assertFalse(a.failed(), () -> a.changes().toString());
        assertFalse(b.failed(), () -> b.changes().toString());

        String capabilities = get(alfa.workspace() + "/wms?service=WMS&version=1.3.0&request=GetCapabilities");
        assertTrue(capabilities.contains("<Name>parcels</Name>"), capabilities);
        assertFalse(capabilities.contains("stops"), "the layer of another organization is not listed");
        assertFalse(capabilities.contains(beta.workspace()));

        JsonNode features = JSON.readTree(get(alfa.workspace()
                + "/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=parcels&outputFormat=application/json"));
        assertEquals(2, features.path("features").size());

        String foreign = get(alfa.workspace() + "/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames="
                + beta.workspace() + ":stops&outputFormat=application/json");
        assertTrue(foreign.contains("ExceptionReport") || foreign.contains("exceptions"),
                "the workspace of alfa does not serve the layers of beta: " + foreign);
        assertFalse(foreign.contains("10.87"));
    }

    @Test
    void aProjectedLayerIsPublishedInItsDeclaredReferenceSystemAndWfsReturnsTheSourceCoordinates() throws Exception {
        OrganizationSpace space = new OrganizationSpace(UUID.randomUUID());
        reconciler.apply(space, Desired.empty(stack.store()));
        // The station of Valverde, the invented town of the samples, in UTM 33N, as a projected file declares it.
        stack.sql(points(space.schema(), "stations", 32633, "POINT(283467.75 4664797.94)"));

        Alignment a = reconciler.apply(space, publishing(stack.store(), layer("stations", "EPSG:32633", null)));
        assertFalse(a.failed(), () -> a.changes().toString());
        assertEquals("EPSG:32633", geoserver.featureType(space.workspace(), "stations").orElseThrow().srs());

        JsonNode native_ = JSON.readTree(get(space.workspace() + "/wfs?service=WFS&version=2.0.0&request=GetFeature"
                + "&typeNames=stations&outputFormat=application/json&srsName=EPSG:32633"));
        JsonNode xy = native_.path("features").get(0).path("geometry").path("coordinates");
        assertEquals(283467.75, xy.get(0).asDouble(), 0.01);
        assertEquals(4664797.94, xy.get(1).asDouble(), 0.01);

        // Reprojected by GeoServer: the same place PostGIS computes (GeoJSON is longitude first).
        JsonNode wgs84 = JSON.readTree(get(space.workspace() + "/wfs?service=WFS&version=2.0.0&request=GetFeature"
                + "&typeNames=stations&outputFormat=application/json&srsName=EPSG:4326"));
        JsonNode ll = wgs84.path("features").get(0).path("geometry").path("coordinates");
        double[] expected = stack.lonLat(space.schema() + ".stations");
        assertEquals(expected[0], ll.get(0).asDouble(), 1e-3);
        assertEquals(expected[1], ll.get(1).asDouble(), 1e-3);
        assertEquals(42.1, expected[1], 0.05, "the station of Valverde");
    }

    @Test
    void layersAndStylesChangedByHandAreBroughtBackToTheDefinitionOfGeoportal() {
        OrganizationSpace space = new OrganizationSpace(UUID.randomUUID());
        reconciler.apply(space, Desired.empty(stack.store()));
        stack.sql(points(space.schema(), "wells", 4326, "POINT(12.4 41.8)"));
        StyleDefinition red = new StyleDefinition("wells_style", sld("#cc0000"));
        Desired desired = new Desired(stack.store(), List.of(layer("wells", "EPSG:4326", "wells_style")),
                List.of(red), Set.of(), Set.of());
        Alignment first = reconciler.apply(space, desired);
        assertFalse(first.failed(), () -> first.changes().toString());
        Alignment again = reconciler.apply(space, desired);
        assertTrue(again.aligned(), () -> "a style kept in GeoServer's layout is not rewritten: " + again.changes()
                + " / " + geoserver.styleBody(space.workspace(), "wells_style").orElseThrow());

        // By hand in GeoServer: another title, another default style, another SLD.
        String ws = space.workspace();
        geoserver.updateFeatureType(ws, "data", new GeoServerClient.FeatureType(
                "wells", "wells", "data", "Changed by hand", "", "EPSG:4326", "FORCE_DECLARED", true, true));
        geoserver.createStyle(ws, "by_hand", sld("#00ff00"));
        geoserver.setLayerStyle(ws, "wells", "by_hand");
        geoserver.updateStyle(ws, "wells_style", sld("#0000ff"));

        Alignment plan = reconciler.plan(space, desired);
        assertEquals(List.of("style wells_style update", "layer wells update", "layer wells update"),
                plan.changes().stream().map(c -> c.kind() + " " + c.name() + " " + c.action()).toList());
        assertEquals("title", plan.changes().get(1).reason());

        Alignment back = reconciler.apply(space, desired);
        assertFalse(back.failed(), () -> back.changes().toString());
        assertEquals("Wells", geoserver.featureType(ws, "wells").orElseThrow().title());
        assertEquals(ws + ":wells_style", geoserver.layerStyle(ws, "wells").orElseThrow());
        assertTrue(Sld.same(geoserver.styleBody(ws, "wells_style").orElseThrow(), red.sld()));
        assertEquals(List.of(new Drift("style", "by_hand")), back.drift(), "the style made by hand is reported");
        assertTrue(reconciler.apply(space, desired).aligned());
    }

    @Test
    void whatGeoportalDoesNotKnowIsReportedAndNeverDeleted() {
        OrganizationSpace space = new OrganizationSpace(UUID.randomUUID());
        reconciler.apply(space, Desired.empty(stack.store()));
        stack.sql(points(space.schema(), "known", 4326, "POINT(12.4 41.8)"));
        stack.sql(points(space.schema(), "unknown", 4326, "POINT(12.4 41.8)"));
        Desired desired = publishing(stack.store(), layer("known", "EPSG:4326", null));
        reconciler.apply(space, desired);

        String ws = space.workspace();
        geoserver.createFeatureType(ws, "data", new GeoServerClient.FeatureType(
                "unknown", "unknown", "data", "By hand", "", "EPSG:4326", "FORCE_DECLARED", true, true));
        geoserver.createStyle(ws, "stray", sld("#123456"));
        geoserver.putDataStore(ws, "other", stack.store().parameters(space), false);

        Alignment a = reconciler.apply(space, desired);
        assertTrue(a.aligned(), () -> a.changes().toString());
        assertEquals(List.of(new Drift("store", "other"), new Drift("layer", "unknown"), new Drift("style", "stray")),
                a.drift());
        assertTrue(geoserver.featureType(ws, "unknown").isPresent(), "drift is never deleted");
        assertTrue(geoserver.styles(ws).contains("stray"));
        assertTrue(geoserver.dataStores(ws).contains("other"));
    }

    @Test
    void withdrawnLayersAndStylesAreDeletedOnceAndAnUnknownTableIsReportedWithoutStoppingTheRest() {
        OrganizationSpace space = new OrganizationSpace(UUID.randomUUID());
        reconciler.apply(space, Desired.empty(stack.store()));
        stack.sql(points(space.schema(), "roads", 4326, "POINT(12.4 41.8)"));
        StyleDefinition style = new StyleDefinition("roads_style", sld("#333333"));
        reconciler.apply(space, new Desired(stack.store(), List.of(layer("roads", "EPSG:4326", "roads_style")),
                List.of(style), Set.of(), Set.of()));

        Desired withdrawn = new Desired(stack.store(), List.of(layer("missing_table", "EPSG:4326", null)), List.of(),
                Set.of("roads"), Set.of("roads_style"));
        Alignment a = reconciler.apply(space, withdrawn);
        Change missing = a.changes().get(0);
        assertEquals("missing_table", missing.name());
        assertTrue(missing.error() != null, "GeoServer refuses a table that does not exist");
        assertFalse(missing.error().contains(GeoServerStack.ADMIN_PASSWORD));
        assertEquals(List.of("layer roads delete", "style roads_style delete"), a.changes().subList(1, 3).stream()
                .map(c -> c.kind() + " " + c.name() + " " + c.action()).toList());
        assertNull(a.changes().get(1).error());
        assertTrue(geoserver.featureType(space.workspace(), "roads").isEmpty());
        assertFalse(geoserver.styles(space.workspace()).contains("roads_style"));

        Alignment again = reconciler.apply(space, publishing(stack.store()));
        assertTrue(again.aligned(), () -> again.changes().toString());
    }

    @Test
    void turningTheCatalogOffWithdrawsThePublicationsWithoutDeletingAnythingAndTheNextAlignmentServesThemAgain()
            throws Exception {
        OrganizationSpace space = new OrganizationSpace(UUID.randomUUID());
        String ws = space.workspace();
        reconciler.apply(space, Desired.empty(stack.store()));
        stack.sql(points(space.schema(), "wells", 4326, "POINT(12.4 41.8)", "POINT(12.5 41.9)"));
        StyleDefinition style = new StyleDefinition("wells_style", sld("#0055aa"));
        Desired published = new Desired(stack.store(), List.of(layer("wells", "EPSG:4326", "wells_style")),
                List.of(style), Set.of(), Set.of());
        assertFalse(reconciler.apply(space, published).failed());
        assertTrue(get(ws + "/wms?service=WMS&version=1.3.0&request=GetCapabilities").contains("<Name>wells</Name>"));

        Alignment withdrawn = reconciler.withdraw(space);
        assertFalse(withdrawn.failed(), () -> withdrawn.changes().toString());
        assertEquals(List.of("layer wells disable"), withdrawn.changes().stream()
                .map(c -> c.kind() + " " + c.name() + " " + c.action()).toList());
        assertFalse(geoserver.featureType(ws, "wells").orElseThrow().enabled());
        assertFalse(get(ws + "/wms?service=WMS&version=1.3.0&request=GetCapabilities").contains("<Name>wells</Name>"),
                "a withdrawn layer is not served");
        assertFalse(get(ws + "/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=wells"
                + "&outputFormat=application/json").contains("12.4"));
        // Nothing is deleted: workspace, store, style, layer definition and the table with its rows.
        assertTrue(geoserver.hasWorkspace(ws));
        assertEquals(List.of("data"), geoserver.dataStores(ws));
        assertTrue(geoserver.styles(ws).contains("wells_style"));
        assertTrue(geoserver.featureType(ws, "wells").isPresent());
        stack.sql("do $$ begin if (select count(*) from " + space.schema() + ".wells) <> 2 then"
                + " raise exception 'rows lost'; end if; end $$;");
        assertTrue(reconciler.withdraw(space).aligned(), "a second withdrawal changes nothing");

        // Turned on again: the alignment enables the layers of the catalog.
        Alignment back = reconciler.apply(space, published);
        assertFalse(back.failed(), () -> back.changes().toString());
        assertTrue(geoserver.featureType(ws, "wells").orElseThrow().enabled());
        JsonNode features = JSON.readTree(get(ws
                + "/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=wells&outputFormat=application/json"));
        assertEquals(2, features.path("features").size());
    }

    @Test
    void withdrawingAnOrganizationWithoutAWorkspaceChangesNothing() {
        OrganizationSpace space = new OrganizationSpace(UUID.randomUUID());
        assertTrue(reconciler.withdraw(space).aligned());
        assertFalse(geoserver.hasWorkspace(space.workspace()));
    }

    @Test
    void aWrongPasswordIsRefusedWithoutShowingIt() {
        GeoServerClient wrong = new GeoServerClient(stack.url(), GeoServerStack.ADMIN, "not-the-password".toCharArray());
        GeoServerException e = assertThrows(GeoServerException.class, wrong::workspaces);
        assertEquals(401, e.status());
        assertFalse(e.getMessage().contains("not-the-password"));
        assertTrue(stack.client().version().startsWith("2.28"));
    }

    // ---------------------------------------------------------------------------------------------

    private static Desired publishing(StoreConnection store, LayerDefinition... layers) {
        return new Desired(store, List.of(layers), List.of(), Set.of(), Set.of());
    }

    private static LayerDefinition layer(String name, String srs, String style) {
        String title = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return new LayerDefinition(name, name, title, "", srs, style);
    }

    private static List<String> kinds(Alignment a) {
        return a.changes().stream().map(Change::kind).toList();
    }

    private static String points(String schema, String table, int srid, String... wkt) {
        StringBuilder sql = new StringBuilder("create table " + schema + "." + table
                + " (id serial primary key, name text, geom geometry(Point, " + srid + "));");
        for (int i = 0; i < wkt.length; i++) {
            sql.append("insert into ").append(schema).append('.').append(table)
                    .append(" (name, geom) values ('").append(table).append(' ').append(i)
                    .append("', ST_GeomFromText('").append(wkt[i]).append("', ").append(srid).append("));");
        }
        return sql.toString();
    }

    private static String sld(String color) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <StyledLayerDescriptor version="1.0.0" xmlns="http://www.opengis.net/sld"
                    xmlns:ogc="http://www.opengis.net/ogc" xmlns:xlink="http://www.w3.org/1999/xlink"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.opengis.net/sld StyledLayerDescriptor.xsd">
                  <NamedLayer>
                    <Name>points</Name>
                    <UserStyle>
                      <Title>Points</Title>
                      <FeatureTypeStyle>
                        <Rule>
                          <PointSymbolizer>
                            <Graphic>
                              <Mark>
                                <WellKnownName>circle</WellKnownName>
                                <Fill><CssParameter name="fill">%s</CssParameter></Fill>
                              </Mark>
                              <Size>8</Size>
                            </Graphic>
                          </PointSymbolizer>
                        </Rule>
                      </FeatureTypeStyle>
                    </UserStyle>
                  </NamedLayer>
                </StyledLayerDescriptor>
                """.formatted(color);
    }

    /** An OGC request to GeoServer as its administrator (GeoFence denies everybody else by default). */
    private static String get(String path) throws IOException, InterruptedException {
        String auth = Base64.getEncoder().encodeToString((GeoServerStack.ADMIN + ":" + GeoServerStack.ADMIN_PASSWORD)
                .getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(stack.url() + "/" + path))
                .header("Authorization", "Basic " + auth).GET().build(), HttpResponse.BodyHandlers.ofString());
        return r.body();
    }
}
