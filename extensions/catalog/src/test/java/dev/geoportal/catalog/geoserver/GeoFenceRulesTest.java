// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.geoserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.catalog.access.AccessRules;
import dev.geoportal.catalog.access.Areas;
import dev.geoportal.catalog.access.DataRule;
import dev.geoportal.catalog.access.GeoFenceRule;
import dev.geoportal.catalog.access.RuleSet;
import dev.geoportal.catalog.access.RuleSet.Kind;
import dev.geoportal.catalog.access.RuleSet.LayerAccess;
import dev.geoportal.catalog.access.RuleSet.Limited;
import dev.geoportal.catalog.access.RuleSet.Limits;
import dev.geoportal.catalog.access.RuleSet.Reach;
import dev.geoportal.catalog.access.RuleWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-335 and GP-308 against a real GeoServer with GeoFence: the rule set generated from grants and data rules,
 * written by the catalog, and what WMS, WFS and the tiles of GeoWebCache answer to each person. GeoServer
 * takes the person and their roles from headers here ({@link GeoServerStack#withPeopleFromHeaders}), as
 * it will from the gateway of GP-336 with the roles of Mosaikit: the rules are the same.
 *
 * <p>Each test has an organization of its own with one layer, {@code parcels}: 100 points in EPSG:32633
 * on a grid of 10 by 10 kilometres from (290000, 4640000), half {@code attivo} and half {@code chiuso},
 * with a column {@code riservato}.
 */
@Tag("GP-335")
@Tag("geoserver")
class GeoFenceRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** The western half of the grid: x from 290000 to 294000, every row. */
    static final String WEST = "SRID=32633;MULTIPOLYGON(((289500 4639500,294500 4639500,294500 4649500,289500 4649500,289500 4639500)))";

    private static GeoServerStack stack;
    private static GeoServerClient geoserver;
    private static Reconciler reconciler;

    @BeforeAll
    static void start() {
        Assumptions.assumeTrue(GeoServerStack.distributionFound(), "the server distribution is next to the catalog");
        stack = new GeoServerStack().withPeopleFromHeaders().start();
        geoserver = stack.client();
        reconciler = new Reconciler(geoserver, stack.schemas());
    }

    @AfterAll
    static void stop() {
        if (stack != null) {
            stack.close();
        }
    }

    /** An organization with its workspace and the layer parcels. */
    record Org(UUID id, String workspace) {
        String member() {
            return RuleSet.organizationRole(id);
        }

        String admins() {
            return RuleSet.administratorsRole(id);
        }

        RuleWriter.Outcome write(List<LayerAccess> layers) {
            return write(layers, Set.of(), null);
        }

        RuleWriter.Outcome write(List<LayerAccess> layers, Set<String> previous, Long base) {
            return new RuleWriter(geoserver).rewrite(workspace, RuleSet.of(workspace, id, layers), previous, base);
        }

        List<GeoFenceRule> rules(List<LayerAccess> layers) {
            return RuleSet.of(workspace, id, layers);
        }
    }

    static Org organization() {
        OrganizationSpace space = new OrganizationSpace(UUID.randomUUID());
        reconciler.apply(space, Desired.empty(stack.store()));
        StringBuilder sql = new StringBuilder("create table " + space.schema()
                + ".parcels (fid serial primary key, nome text, stato text, riservato text, geom geometry(Point, 32633));");
        sql.append("insert into ").append(space.schema()).append(".parcels (nome, stato, riservato, geom) select 'p' || i,"
                + " case when i % 2 = 0 then 'attivo' else 'chiuso' end, 'segreto ' || i,"
                + " ST_SetSRID(ST_MakePoint(290000 + (i % 10) * 1000, 4640000 + (i / 10) * 1000), 32633)"
                + " from generate_series(0, 99) i;");
        stack.sql(sql.toString());
        Alignment a = reconciler.apply(space, new Desired(stack.store(),
                List.of(new LayerDefinition("parcels", "parcels", "Particelle", "", "EPSG:32633", null)), List.of(), Set.of(),
                Set.of()));
        assertFalse(a.failed(), () -> a.changes().toString());
        return new Org(space.organization(), space.workspace());
    }

    static LayerAccess parcels(Reach... reaches) {
        return new LayerAccess("parcels", List.of(reaches), List.of());
    }

    // Acceptance 1 -------------------------------------------------------------------------------

    @Test
    void aPersonWithoutAGrantGetsNothingAndGetsTheDataOnceSharedWithThemOrOneOfTheirGroups() throws Exception {
        Org org = organization();
        UUID team = UUID.randomUUID();
        Person anna = new Person("anna-" + org.id() + "@example.test", org.member());
        Person bruno = new Person("bruno-" + org.id() + "@example.test", org.member(), RuleSet.teamRole(team));
        Person carla = new Person("carla-" + org.id() + "@example.test", org.member());
        // A guest of the organization has no role of it (the role service of GP-336 leaves guests out).
        Person guest = new Person("guest-" + org.id() + "@example.test", "guest-of-a-team");

        org.write(List.of(parcels()));
        for (Person p : List.of(anna, bruno, carla, guest, Person.ANONYMOUS)) {
            assertEquals("denied", wfs(org, p).outcome(), p + " has no grant: no features");
            assertFalse(map(org, p), p + " has no grant: no map");
        }

        // Shared with anna (download), with the team of bruno (view): anna reads features, bruno sees the map only.
        org.write(List.of(parcels(new Reach(Kind.PERSON, anna.name(), "download"),
                new Reach(Kind.TEAM, RuleSet.teamRole(team), "view"))));
        assertEquals(100, wfs(org, anna).rows());
        assertTrue(map(org, anna));
        assertTrue(map(org, bruno), "the team of bruno sees the map");
        assertEquals("denied", wfs(org, bruno).outcome(), "view is not download: no features");
        assertEquals("denied", wfs(org, carla).outcome());
        assertFalse(map(org, carla));

        // Shared with the organization: every member, never a guest of the organization nor anonymous.
        org.write(List.of(parcels(new Reach(Kind.ORGANIZATION, org.member(), "download"))));
        assertEquals(100, wfs(org, carla).rows());
        assertEquals(100, wfs(org, bruno).rows());
        assertEquals("denied", wfs(org, guest).outcome());
        assertEquals("denied", wfs(org, Person.ANONYMOUS).outcome());

        // Unshared: nothing again; the administrators of the organization keep every layer.
        org.write(List.of(parcels()));
        assertEquals("denied", wfs(org, carla).outcome());
        assertEquals(100, wfs(org, new Person("admin-" + org.id() + "@example.test", org.admins())).rows());
    }

    @Test
    void nobodyWritesThroughWfsAndACachedTileIsNotServedToWhoMayNotSeeTheLayer() throws Exception {
        Org org = organization();
        Person anna = new Person("anna-" + org.id() + "@example.test", org.member());
        org.write(List.of(parcels(new Reach(Kind.PERSON, anna.name(), "manage"))));

        String insert = "<wfs:Transaction service=\"WFS\" version=\"1.1.0\" xmlns:wfs=\"http://www.opengis.net/wfs\""
                + " xmlns:gml=\"http://www.opengis.net/gml\" xmlns:o=\"urn:geoportal:organization:" + org.id() + "\">"
                + "<wfs:Insert><o:parcels><o:nome>nuova</o:nome><o:geom><gml:Point srsName=\"EPSG:32633\"><gml:pos>290000 4640000"
                + "</gml:pos></gml:Point></o:geom></o:parcels></wfs:Insert></wfs:Transaction>";
        HttpResponse<String> tx = HTTP.send(anna.request(stack.url() + "/" + org.workspace() + "/wfs")
                .header("Content-Type", "text/xml").POST(HttpRequest.BodyPublishers.ofString(insert)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertFalse(tx.body().contains("TransactionResponse"), tx.body());
        assertEquals(100, wfs(org, anna).rows(), "no feature was written");

        String tile = stack.url() + "/" + org.workspace() + "/gwc/service/wmts?service=WMTS&version=1.0.0&request=GetTile&layer="
                + org.workspace() + ":parcels&style=&tilematrixset=EPSG:4326&tilematrix=EPSG:4326:0&tilerow=0&tilecol=1"
                + "&format=image/png";
        HttpResponse<byte[]> first = HTTP.send(anna.request(tile).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, first.statusCode(), "anna gets the tile");
        HttpResponse<byte[]> again = HTTP.send(anna.request(tile).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals("HIT", again.headers().firstValue("geowebcache-cache-result").orElse(""), "the tile is cached");
        HttpResponse<byte[]> anonymous = HTTP.send(Person.ANONYMOUS.request(tile).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertTrue(anonymous.statusCode() >= 400, "the cached tile is not served to anonymous: " + anonymous.statusCode());
    }

    // Acceptance 2 -------------------------------------------------------------------------------

    @Test
    void aDataRuleWithAnAreaARowFilterAndHiddenColumnsChangesWfsAnswersWhateverGrantOpensTheLayer() throws Exception {
        Org org = organization();
        UUID team = UUID.randomUUID();
        Person dario = new Person("dario-" + org.id() + "@example.test", org.member(), RuleSet.teamRole(team));
        Person elena = new Person("elena-" + org.id() + "@example.test", org.member(), RuleSet.teamRole(team));
        List<String> columns = List.of("fid", "nome", "stato", "riservato", "geom");
        Limits area = new Limits(WEST, false, null, columns, Set.of());
        Limits all = new Limits(WEST, false, "stato = 'attivo'", columns, Set.of("riservato"));
        Reach teamDownload = new Reach(Kind.TEAM, RuleSet.teamRole(team), "download");
        Reach orgDownload = new Reach(Kind.ORGANIZATION, org.member(), "download");

        // An area only: the western half, 50 of 100.
        org.write(List.of(new LayerAccess("parcels", List.of(teamDownload, orgDownload),
                List.of(new Limited(dario.name(), "download", area)))));
        assertEquals(50, wfs(org, dario).rows());
        assertEquals(100, wfs(org, elena).rows(), "elena has no data rule");

        // Area, rows and a hidden column: the active parcels of the western half, without riservato,
        // although the team and the organization both open the whole layer to dario.
        org.write(List.of(new LayerAccess("parcels", List.of(teamDownload, orgDownload),
                List.of(new Limited(dario.name(), "download", all)))));
        Features d = wfs(org, dario);
        assertEquals(30, d.rows());
        assertFalse(d.columns().contains("riservato"), d.columns().toString());
        assertTrue(d.columns().contains("nome"));
        assertEquals(Set.of("attivo"), d.values("stato"));
        // The same limits in GetFeatureInfo of WMS: nothing outside the area, no hidden column inside.
        assertEquals(List.of(), featureInfo(org, dario, 297000, 4640000));
        assertEquals(Set.of("fid", "nome", "stato"), new TreeSet<>(featureInfo(org, dario, 290000, 4640000).getFirst()));
        Features e = wfs(org, elena);
        assertEquals(100, e.rows());
        assertTrue(e.columns().contains("riservato"));

        // A data rule on a person whose level is view: the map with its limits, no features.
        org.write(List.of(new LayerAccess("parcels", List.of(new Reach(Kind.PERSON, dario.name(), "view")),
                List.of(new Limited(dario.name(), "view", all)))));
        assertEquals("denied", wfs(org, dario).outcome());
        assertTrue(map(org, dario));
        // Areas that have nothing in common: nothing at all.
        org.write(List.of(new LayerAccess("parcels", List.of(orgDownload),
                List.of(new Limited(dario.name(), "download", new Limits(null, true, null, columns, Set.of()))))));
        assertEquals("denied", wfs(org, dario).outcome());
        assertFalse(map(org, dario));
    }

    // Acceptance 3 -------------------------------------------------------------------------------

    @Test
    void theRulesReadBackMatchIncludingColumnsAndAreasAndAMismatchIsReported() throws Exception {
        Org org = organization();
        Person dario = new Person("dario-" + org.id() + "@example.test", org.member());
        List<String> columns = List.of("fid", "nome", "stato", "riservato", "geom");
        List<LayerAccess> layers = List.of(new LayerAccess("parcels",
                List.of(new Reach(Kind.ORGANIZATION, org.member(), "download")),
                List.of(new Limited(dario.name(), "download", new Limits(WEST, false, "stato = 'attivo'", columns,
                        Set.of("riservato"))))));
        RuleWriter.Outcome o = org.write(layers);
        assertTrue(o.written());
        assertEquals(List.of(), o.mismatches(), "read back equal, areas and columns included");
        List<JsonNode> held = ours(org);
        assertEquals(o.rules(), held.size());
        assertTrue(held.stream().anyMatch(r -> r.path("limits").path("allowedArea").asText().contains("MULTIPOLYGON")),
                "the area is kept");
        assertTrue(held.stream().anyMatch(r -> r.path("layerDetails").path("attributes").size() == 5), "the columns are kept");
        assertTrue(held.stream().allMatch(r -> r.path("priority").asLong() >= o.base()
                && r.path("priority").asLong() < o.base() + o.rules()), "the band of the set");

        // Written again unchanged: nothing is written.
        assertFalse(org.write(layers, new java.util.HashSet<>(canonical(org.rules(layers))), o.base()).written());

        // The traps of GeoFence 2.28.5, reproduced by hand: the area rule written as XML loses its area, the
        // column rule written as JSON loses its columns. The comparison says so instead of ignoring it.
        JsonNode limit = held.stream().filter(r -> "LIMIT".equals(r.path("access").asText())).findFirst().orElseThrow();
        geoserver.deleteRule(limit.path("id").asLong());
        GeoFenceRule area = GeoFenceRule.read(limit);
        geoserver.insertRule(xmlOf(area, limit.path("priority").asLong()), true);
        JsonNode columnsRule = held.stream().filter(r -> r.path("layerDetails").path("attributes").size() > 0).findFirst()
                .orElseThrow();
        geoserver.deleteRule(columnsRule.path("id").asLong());
        GeoFenceRule allow = GeoFenceRule.read(columnsRule);
        geoserver.insertRule(jsonOf(allow, columnsRule.path("priority").asLong()), false);
        List<String> mismatches = new RuleWriter(geoserver).verify(org.workspace(), org.rules(layers));
        assertEquals(2, mismatches.size(), mismatches.toString());
        assertTrue(mismatches.stream().anyMatch(m -> m.contains("no area")), mismatches.toString());
        assertTrue(mismatches.stream().anyMatch(m -> m.contains("no column list")), mismatches.toString());

        // The next rewrite puts each rule in the format that keeps it.
        RuleWriter.Outcome fixed = org.write(layers, new java.util.HashSet<>(canonical(org.rules(layers))), o.base());
        assertTrue(fixed.written());
        assertEquals(List.of(), fixed.mismatches());
        assertEquals(30, wfs(org, dario).rows());
    }

    // Acceptance 4 -------------------------------------------------------------------------------

    @Test
    void aRuleAddedByHandDisappearsAtTheNextRewriteAndIsReported() throws Exception {
        Org org = organization();
        Person anna = new Person("anna-" + org.id() + "@example.test", org.member());
        List<LayerAccess> layers = List.of(parcels());
        RuleWriter.Outcome first = org.write(layers);
        assertEquals("denied", wfs(org, anna).outcome());

        // Someone opens the features of the layer to everybody, by hand, in front of the rules of the catalog.
        geoserver.insertRule("<Rule><priority>" + (first.base() - 1) + "</priority><workspace>" + org.workspace()
                + "</workspace><layer>parcels</layer><service>WFS</service><access>ALLOW</access></Rule>", true);
        geoserver.invalidateRuleCache();
        assertEquals(100, wfs(org, anna).rows(), "the hand rule opens the layer");
        assertFalse(new RuleWriter(geoserver).verify(org.workspace(), org.rules(layers)).isEmpty(), "and is a mismatch");

        RuleWriter.Outcome next = org.write(layers, new java.util.HashSet<>(canonical(org.rules(layers))), first.base());
        assertTrue(next.written());
        assertEquals(1, next.removed().size(), next.removed().toString());
        assertEquals("ALLOW everyone on parcels WFS", next.removed().getFirst());
        assertEquals(List.of(), next.mismatches());
        assertEquals("denied", wfs(org, anna).outcome(), "the hand rule is gone");
        assertTrue(ours(org).stream().noneMatch(r -> r.path("priority").asLong() == first.base() - 1));
    }

    @Test
    void writingTheRulesOfOneOrganizationMovesNoRuleOfAnotherNorTheCatchAll() throws Exception {
        Org alfa = organization();
        Org beta = organization();
        Person anna = new Person("anna-" + alfa.id() + "@example.test", alfa.member());
        RuleWriter.Outcome a = alfa.write(List.of(parcels(new Reach(Kind.PERSON, anna.name(), "download"))));
        List<Long> before = ours(alfa).stream().map(r -> r.path("priority").asLong()).toList();
        RuleWriter.Outcome b = beta.write(List.of(parcels(new Reach(Kind.ORGANIZATION, beta.member(), "view"))));
        assertTrue(b.base() != a.base(), "another band");
        assertEquals(before, ours(alfa).stream().map(r -> r.path("priority").asLong()).toList());
        // The rules of alfa written again, larger: they move to a free band and still move nothing.
        UUID team = UUID.randomUUID();
        RuleWriter.Outcome again = alfa.write(List.of(parcels(new Reach(Kind.PERSON, anna.name(), "download"),
                new Reach(Kind.TEAM, RuleSet.teamRole(team), "download"))), Set.of(), a.base());
        assertEquals(List.of(), again.mismatches());
        List<Long> catchAll = new ArrayList<>();
        for (JsonNode r : geoserver.geofenceRules()) {
            if (r.path("workspace").isNull() && r.path("roleName").isNull() && r.path("userName").isNull()) {
                catchAll.add(r.path("priority").asLong());
            }
        }
        assertEquals(List.of(99999L), catchAll, "the catch-all DENY of the distribution stays where it is");
        assertEquals(100, wfs(alfa, anna).rows());
    }

    // GP-308: data rules --------------------------------------------------------------------------

    /** The southern half of the grid: y from 4640000 to 4644000, every column. */
    static final String SOUTH = "SRID=32633;MULTIPOLYGON(((289500 4639500,299500 4639500,299500 4644500,289500 4644500,289500 4639500)))";

    static final List<String> COLUMNS = List.of("fid", "nome", "stato", "riservato", "geom");

    static DataRule dataRule(Org org, String kind, String area, String cql, String... hidden) {
        DataRule r = new DataRule(org.id(), UUID.randomUUID(), "manager@example.test", java.time.Instant.now());
        r.define(kind, UUID.randomUUID(), "", area, cql, List.of(hidden), java.time.Instant.now());
        return r;
    }

    /** The limits of rules composed as the catalog composes them, the areas intersected by PostGIS. */
    static Limits limits(List<DataRule> rules) throws Exception {
        AccessRules.Composed c = AccessRules.compose(rules);
        String area = null;
        boolean empty = false;
        if (!c.areas().isEmpty()) {
            try (Connection connection = DriverManager.getConnection(stack.geodataJdbc(), GeoServerStack.DB_USER,
                    GeoServerStack.DB_PASSWORD)) {
                Optional<String> common = Areas.fold(connection, c.areas());
                empty = common.isEmpty();
                area = common.orElse(null);
            }
        }
        return new Limits(area, empty, c.cql(), COLUMNS, c.hidden());
    }

    /** How many pixels of the right half of a GetMap of the whole grid are drawn, and of the left half. */
    static int[] drawn(Org org, Person p) throws IOException, InterruptedException {
        HttpResponse<byte[]> r = HTTP.send(p.request(stack.url() + "/" + org.workspace()
                + "/parcels/wms?service=WMS&version=1.1.1&request=GetMap&layers=parcels&styles=&srs=EPSG:32633"
                + "&bbox=289000,4639000,300000,4650000&width=110&height=110&format=image/png&transparent=true").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, r.statusCode());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(r.body()));
        int[] count = new int[2];
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    count[x < 55 ? 0 : 1]++;
                }
            }
        }
        return count;
    }

    @Test
    @Tag("GP-308")
    void aDataRuleWithAnAreaLimitsGetMapGetFeatureInfoAndWfsToThatArea() throws Exception {
        Org org = organization();
        Person dario = new Person("dario-" + org.id() + "@example.test", org.member());
        Person elena = new Person("elena-" + org.id() + "@example.test", org.member());
        org.write(List.of(new LayerAccess("parcels", List.of(new Reach(Kind.ORGANIZATION, org.member(), "download")),
                List.of(new Limited(dario.name(), "download", limits(List.of(dataRule(org, DataRule.PERSON, WEST, null))))))));
        int[] d = drawn(org, dario);
        int[] e = drawn(org, elena);
        assertTrue(d[0] > 0, "dario sees the western half on the map");
        assertEquals(0, d[1], "and nothing of the eastern half");
        assertTrue(e[1] > 0, "elena, without rules, sees the eastern half too");
        assertEquals(List.of(), featureInfo(org, dario, 297000, 4640000));
        assertEquals(1, featureInfo(org, dario, 290000, 4640000).size());
        assertEquals(1, featureInfo(org, elena, 297000, 4640000).size());
        assertEquals(50, wfs(org, dario).rows());
        assertEquals(100, wfs(org, elena).rows());
    }

    @Test
    @Tag("GP-308")
    void aRowFilterRemovesTheFeaturesThatDoNotMatchAndAHiddenColumnIsMissingFromWfsAndGetFeatureInfo() throws Exception {
        Org org = organization();
        UUID team = UUID.randomUUID();
        Person dario = new Person("dario-" + org.id() + "@example.test", org.member(), RuleSet.teamRole(team));
        org.write(List.of(new LayerAccess("parcels", List.of(new Reach(Kind.TEAM, RuleSet.teamRole(team), "download")),
                List.of(new Limited(dario.name(), "download",
                        limits(List.of(dataRule(org, DataRule.TEAM, null, "stato = 'attivo'", "riservato"))))))));
        Features f = wfs(org, dario);
        assertEquals(50, f.rows());
        assertEquals(Set.of("attivo"), f.values("stato"));
        assertFalse(f.columns().contains("riservato"));
        assertTrue(f.columns().contains("nome"));
        assertEquals(Set.of("fid", "nome", "stato"), new TreeSet<>(featureInfo(org, dario, 290000, 4640000).getFirst()));
        assertEquals(List.of(), featureInfo(org, dario, 291000, 4640000), "a closed parcel is filtered out of the map too");
    }

    @Test
    @Tag("GP-308")
    void whenTwoRulesApplyToTheSamePersonTheAnswerHoldsOnlyWhatBothAllow() throws Exception {
        Org org = organization();
        UUID team = UUID.randomUUID();
        Person dario = new Person("dario-" + org.id() + "@example.test", org.member(), RuleSet.teamRole(team));
        // A rule of the team of dario (west, active) and one of dario himself (south, without riservato).
        List<DataRule> both = List.of(dataRule(org, DataRule.TEAM, WEST, "stato = 'attivo'"),
                dataRule(org, DataRule.PERSON, SOUTH, null, "riservato"));
        org.write(List.of(new LayerAccess("parcels", List.of(new Reach(Kind.ORGANIZATION, org.member(), "download")),
                List.of(new Limited(dario.name(), "download", limits(both))))));
        Features f = wfs(org, dario);
        assertEquals(15, f.rows(), "5 columns by 5 rows of the south-west quarter, the active ones");
        assertEquals(Set.of("attivo"), f.values("stato"));
        assertFalse(f.columns().contains("riservato"));
        for (JsonNode feature : f.features()) {
            String name = feature.path("properties").path("nome").asText();
            int i = Integer.parseInt(name.substring(1));
            assertTrue(i % 10 <= 4 && i / 10 <= 4, name + " is in the south-west quarter");
        }
        // Areas with nothing in common: nothing at all.
        String east = "SRID=32633;MULTIPOLYGON(((295500 4639500,299500 4639500,299500 4649500,295500 4649500,295500 4639500)))";
        Limits none = limits(List.of(dataRule(org, DataRule.TEAM, WEST, null), dataRule(org, DataRule.PERSON, east, null)));
        assertTrue(none.empty());
        org.write(List.of(new LayerAccess("parcels", List.of(new Reach(Kind.ORGANIZATION, org.member(), "download")),
                List.of(new Limited(dario.name(), "download", none)))));
        assertEquals("denied", wfs(org, dario).outcome());
    }

    @Test
    @Tag("GP-308")
    void anAreaIsKeptAsAMultipolygonInTheSystemOfTheDataAndOtherSystemsOrShapesAreRefused() throws Exception {
        try (Connection c = DriverManager.getConnection(stack.geodataJdbc(), GeoServerStack.DB_USER, GeoServerStack.DB_PASSWORD)) {
            String kept = Areas.normalize(c, "POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))", 32633);
            assertTrue(kept.startsWith("SRID=32633;MULTIPOLYGON"), kept);
            assertTrue(Areas.normalize(c, "SRID=32633;POLYGON((0 0, 10 0, 10 10, 0 10, 0 0))", 32633).startsWith("SRID=32633;"));
            Assertions.assertThrows(Areas.Refused.class, () -> Areas.normalize(c, "SRID=4326;POLYGON((0 0,1 0,1 1,0 0))", 32633));
            Assertions.assertThrows(Areas.Refused.class, () -> Areas.normalize(c, "LINESTRING(0 0, 10 10)", 32633));
            Assertions.assertThrows(Areas.Refused.class, () -> Areas.normalize(c, "POLYGON((0 0, 10 0", 32633));
            // A self-crossing polygon is repaired, not refused.
            assertTrue(Areas.normalize(c, "POLYGON((0 0, 10 10, 10 0, 0 10, 0 0))", 32633).contains("MULTIPOLYGON"));
        }
    }

    // Helpers ------------------------------------------------------------------------------------

    static List<String> canonical(List<GeoFenceRule> rules) {
        return rules.stream().map(GeoFenceRule::canonical).toList();
    }

    /** The rule as XML, the format that drops an area (the trap of GeoFence 2.28.5). */
    static String xmlOf(GeoFenceRule r, long priority) {
        return "<Rule><priority>" + priority + "</priority><userName>" + r.user() + "</userName><workspace>" + r.workspace()
                + "</workspace><layer>" + r.layer() + "</layer><access>LIMIT</access><limits><allowedArea>" + r.area()
                + "</allowedArea><catalogMode>HIDE</catalogMode></limits></Rule>";
    }

    /** The rule as JSON, the format that drops a column list (the trap of GeoFence 2.28.5). */
    static String jsonOf(GeoFenceRule r, long priority) throws IOException {
        var rule = JSON.createObjectNode();
        var body = rule.putObject("Rule");
        body.put("priority", priority).put("userName", r.user()).put("workspace", r.workspace()).put("layer", r.layer())
                .put("service", r.service()).put("access", "ALLOW");
        var details = body.putObject("layerDetails");
        details.put("layerType", "VECTOR").put("cqlFilterRead", r.cqlRead());
        var attributes = details.putArray("attributes");
        r.attributes().forEach((n, a) -> attributes.addObject().put("name", n).put("accessType", a));
        return JSON.writeValueAsString(rule);
    }

    static List<JsonNode> ours(Org org) {
        return geoserver.geofenceRules().stream().filter(r -> org.workspace().equals(r.path("workspace").asText()))
                .sorted(java.util.Comparator.comparingLong(r -> r.path("priority").asLong())).toList();
    }

    /** A person as GeoServer receives them: a name and roles. */
    record Person(String name, String... roles) {
        static final Person ANONYMOUS = new Person(null);

        HttpRequest.Builder request(String url) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
            if (name != null) {
                b.header(GeoServerStack.USER_HEADER, name);
                b.header(GeoServerStack.ROLES_HEADER, String.join(";", roles));
            }
            return b;
        }

        @Override
        public String toString() {
            return name == null ? "anonymous" : name;
        }
    }

    record Features(String outcome, int rows, Set<String> columns, List<JsonNode> features) {
        Set<String> values(String column) {
            Set<String> values = new TreeSet<>();
            features.forEach(f -> values.add(f.path("properties").path(column).asText()));
            return values;
        }
    }

    static Features wfs(Org org, Person p) throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(p.request(stack.url() + "/" + org.workspace()
                + "/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=" + org.workspace()
                + ":parcels&outputFormat=application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200 || !r.body().strip().startsWith("{")) {
            return new Features("denied", 0, Set.of(), List.of());
        }
        JsonNode fc = JSON.readTree(r.body());
        List<JsonNode> features = new ArrayList<>();
        fc.path("features").forEach(features::add);
        Set<String> columns = new TreeSet<>();
        features.forEach(f -> f.path("properties").fieldNames().forEachRemaining(columns::add));
        return new Features("ok", features.size(), columns, features);
    }

    /** Whether GetMap of the whole grid answers an image. */
    static boolean map(Org org, Person p) throws IOException, InterruptedException {
        HttpResponse<byte[]> r = HTTP.send(p.request(stack.url() + "/" + org.workspace()
                + "/parcels/wms?service=WMS&version=1.1.1&request=GetMap&layers=parcels&styles=&srs=EPSG:32633"
                + "&bbox=289000,4639000,300000,4650000&width=64&height=64&format=image/png").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return r.statusCode() == 200 && r.headers().firstValue("Content-Type").orElse("").startsWith("image/png");
    }

    /** The columns of each feature GetFeatureInfo answers around a point. */
    static List<Set<String>> featureInfo(Org org, Person p, double x, double y) throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(p.request(stack.url() + "/" + org.workspace()
                + "/parcels/wms?service=WMS&version=1.1.1&request=GetFeatureInfo&layers=parcels&query_layers=parcels"
                + "&styles=&srs=EPSG:32633&bbox=" + (x - 50) + "," + (y - 50) + "," + (x + 50) + "," + (y + 50)
                + "&width=101&height=101&x=50&y=50&info_format=application/json&feature_count=5").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        List<Set<String>> out = new ArrayList<>();
        if (r.statusCode() != 200 || !r.body().strip().startsWith("{")) {
            return out;
        }
        for (JsonNode f : JSON.readTree(r.body()).path("features")) {
            Set<String> columns = new TreeSet<>();
            f.path("properties").fieldNames().forEachRemaining(columns::add);
            out.add(columns);
        }
        return out;
    }
}
