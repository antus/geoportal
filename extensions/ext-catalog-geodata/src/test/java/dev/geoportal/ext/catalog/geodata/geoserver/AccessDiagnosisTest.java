// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.geoserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.ext.catalog.geodata.access.AccessRules;
import dev.geoportal.ext.catalog.geodata.access.Areas;
import dev.geoportal.ext.catalog.geodata.access.DataRule;
import dev.geoportal.ext.catalog.geodata.access.Diagnosis;
import dev.geoportal.ext.catalog.geodata.access.Diagnosis.Verdict;
import dev.geoportal.ext.catalog.geodata.access.Evaluation;
import dev.geoportal.ext.catalog.geodata.access.Evaluation.Cause;
import dev.geoportal.ext.catalog.geodata.access.GeoFenceRule;
import dev.geoportal.ext.catalog.geodata.access.Preview;
import dev.geoportal.ext.catalog.geodata.access.RuleSet;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.Kind;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.LayerAccess;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.Limited;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.Limits;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.Reach;
import dev.geoportal.ext.catalog.geodata.access.RuleWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-339 against a real GeoServer with GeoFence: the preview of a data rule and the diagnosis of the access of
 * a person read GeoFence's decision from the rule set the catalog writes ({@link Evaluation}) and ask WFS with
 * its limits ({@link Preview}); here the same rule set is written, and what WMS and WFS answer to the person is
 * compared with what the preview and the diagnosis say. GeoServer takes the person and their roles from
 * headers, as from the gateway of GP-336.
 *
 * <p>Each test has an organization of its own with one layer, {@code parcels}: 100 points in EPSG:32633 on a
 * grid of 10 by 10 kilometres from (290000, 4640000), half {@code attivo} and half {@code chiuso}, with a
 * column {@code riservato}.
 */
@Tag("GP-339")
@Tag("geoserver")
class AccessDiagnosisTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** The western half of the grid: x from 290000 to 294000. */
    static final String WEST = "SRID=32633;MULTIPOLYGON(((289500 4639500,294500 4639500,294500 4649500,289500 4649500,289500 4639500)))";

    /** The eastern half of the grid: x from 295000 to 299000, nothing in common with the western one. */
    static final String EAST = "SRID=32633;MULTIPOLYGON(((294700 4639500,299500 4639500,299500 4649500,294700 4649500,294700 4639500)))";

    static final List<String> COLUMNS = List.of("fid", "nome", "stato", "riservato", "geom");

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

    record Org(UUID id, String workspace) {
        String member() {
            return RuleSet.organizationRole(id);
        }

        List<GeoFenceRule> write(List<LayerAccess> layers) {
            List<GeoFenceRule> rules = RuleSet.of(workspace, id, layers);
            RuleWriter.Outcome o = new RuleWriter(geoserver).rewrite(workspace, rules, Set.of(), null);
            assertEquals(List.of(), o.mismatches());
            return rules;
        }
    }

    static Org organization() {
        OrganizationSpace space = new OrganizationSpace(UUID.randomUUID());
        reconciler.apply(space, Desired.empty(stack.store()));
        stack.sql("create table " + space.schema()
                + ".parcels (fid serial primary key, nome text, stato text, riservato text, geom geometry(Point, 32633));"
                + "insert into " + space.schema() + ".parcels (nome, stato, riservato, geom) select 'p' || i,"
                + " case when i % 2 = 0 then 'attivo' else 'chiuso' end, 'segreto ' || i,"
                + " ST_SetSRID(ST_MakePoint(290000 + (i % 10) * 1000, 4640000 + (i / 10) * 1000), 32633)"
                + " from generate_series(0, 99) i;");
        Alignment a = reconciler.apply(space, new Desired(stack.store(),
                List.of(new LayerDefinition("parcels", "parcels", "Particelle", "", "EPSG:32633", null)), List.of(), Set.of(),
                Set.of()));
        assertFalse(a.failed(), () -> a.changes().toString());
        return new Org(space.organization(), space.workspace());
    }

    static DataRule rule(Org org, String kind, String area, String cql, String... hidden) {
        DataRule r = new DataRule(org.id(), UUID.randomUUID(), "manager@example.test", Instant.now());
        r.define(kind, UUID.randomUUID(), "", area, cql, List.of(hidden), Instant.now());
        return r;
    }

    /** The limits of rules composed as the catalog composes them, the areas intersected by PostGIS. */
    static Limits limits(DataRule... rules) throws Exception {
        AccessRules.Composed c = AccessRules.compose(List.of(rules));
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

    // Acceptance 2: the preview -------------------------------------------------------------------

    @Test
    void thePreviewOfARuleGivesTheFeaturesAndColumnsThePersonThenReceivesFromWfs() throws Exception {
        Org org = organization();
        UUID team = UUID.randomUUID();
        Person dario = new Person("dario-" + org.id() + "@example.test", org.member());
        Person carla = new Person("carla-" + org.id() + "@example.test", org.member(), RuleSet.teamRole(team));
        // Dario: the western half, active rows, without the reserved column. Carla, through her team: the
        // eastern half, and a personal rule that hides the reserved column too.
        List<GeoFenceRule> rules = org.write(List.of(new LayerAccess("parcels",
                List.of(new Reach(Kind.ORGANIZATION, org.member(), "read")),
                List.of(new Limited(dario.name(), "read", limits(rule(org, DataRule.PERSON, WEST, "stato = 'attivo'", "riservato"))),
                        new Limited(carla.name(), "read", limits(rule(org, DataRule.TEAM, EAST, null),
                                rule(org, DataRule.PERSON, null, null, "riservato")))))));

        for (Person p : List.of(dario, carla)) {
            Evaluation.Decision wfs = Evaluation.of(rules, p.name(), List.of(p.roles()), org.workspace(), "parcels", "WFS");
            assertTrue(wfs.allowed(), p + " reads the features");
            assertEquals(Cause.DATA_RULES, wfs.cause());
            Preview.Answer preview = Preview.of(geoserver, org.workspace(), "parcels",
                    geoserver.bindings(org.workspace(), "parcels"), Preview.Limits.of(wfs));
            Features received = wfs(org, p);
            assertEquals("ok", received.outcome());
            assertEquals(received.rows(), preview.matched(), "the preview counts what WFS answers " + p);
            assertEquals(100, preview.total());
            assertEquals(received.columns(), new TreeSet<>(preview.visible()), "the preview has the columns WFS answers " + p);
            assertEquals(List.of("riservato"), preview.hidden());
            Set<String> names = received.values("nome");
            preview.sample().forEach(row -> assertTrue(names.contains(String.valueOf(row.get("nome"))),
                    "the sample holds only features " + p + " receives: " + row));
            assertFalse(preview.sample().isEmpty());
            preview.sample().forEach(row -> assertFalse(row.containsKey("riservato")));
        }
        assertEquals(30, wfs(org, dario).rows(), "the active rows of the western half");
        assertEquals(50, wfs(org, carla).rows());

        // A person without rules: the preview is the whole layer, as WFS answers it.
        Person elena = new Person("elena-" + org.id() + "@example.test", org.member());
        Evaluation.Decision free = Evaluation.of(rules, elena.name(), List.of(elena.roles()), org.workspace(), "parcels", "WFS");
        assertEquals(Cause.GRANT, free.cause());
        Preview.Answer all = Preview.of(geoserver, org.workspace(), "parcels", geoserver.bindings(org.workspace(), "parcels"),
                Preview.Limits.of(free));
        assertEquals(wfs(org, elena).rows(), all.matched());
        assertEquals(wfs(org, elena).columns(), new TreeSet<>(all.visible()));
    }

    // Acceptance 3: the diagnosis -----------------------------------------------------------------

    @Test
    void theDiagnosisNamesTheMissingGrantOrTheRulesThatHideTheDataAsWmsAndWfsAnswerThePerson() throws Exception {
        Org org = organization();
        UUID team = UUID.randomUUID();
        UUID viewers = UUID.randomUUID();
        Person admin = new Person("admin-" + org.id() + "@example.test", org.member(), RuleSet.administratorsRole(org.id()));
        Person elena = new Person("elena-" + org.id() + "@example.test", "guest-of-a-team");
        Person fabio = new Person("fabio-" + org.id() + "@example.test", org.member(), RuleSet.teamRole(viewers));
        Person gino = new Person("gino-" + org.id() + "@example.test", org.member(), RuleSet.teamRole(team));
        Person hana = new Person("hana-" + org.id() + "@example.test", org.member(), RuleSet.teamRole(team));
        Person ivo = new Person("ivo-" + org.id() + "@example.test", org.member(), RuleSet.teamRole(team));
        Person dario = new Person("dario-" + org.id() + "@example.test", org.member(), RuleSet.teamRole(team));
        Person luca = new Person("luca-" + org.id() + "@example.test", org.member(), RuleSet.teamRole(viewers));
        List<GeoFenceRule> rules = org.write(List.of(new LayerAccess("parcels",
                List.of(new Reach(Kind.TEAM, RuleSet.teamRole(team), "read"), new Reach(Kind.TEAM, RuleSet.teamRole(viewers), "view")),
                List.of(new Limited(gino.name(), "read", limits(rule(org, DataRule.PERSON, WEST, null), rule(org, DataRule.TEAM, EAST, null))),
                        new Limited(hana.name(), "read", limits(rule(org, DataRule.PERSON, null, "stato = 'nessuno'"))),
                        new Limited(dario.name(), "read", limits(rule(org, DataRule.PERSON, WEST, null, "riservato"))),
                        new Limited(luca.name(), "view", limits(rule(org, DataRule.PERSON, WEST, null)))))));

        Map<Person, Verdict> expected = new java.util.LinkedHashMap<>();
        expected.put(elena, Verdict.NO_GRANT);
        expected.put(fabio, Verdict.VIEW_ONLY);
        expected.put(luca, Verdict.VIEW_ONLY);
        expected.put(gino, Verdict.RULES_HIDE_ALL);
        expected.put(hana, Verdict.RULES_HIDE_ALL);
        expected.put(dario, Verdict.RULES_LIMIT);
        expected.put(ivo, Verdict.FULL);
        expected.put(admin, Verdict.FULL);
        for (Map.Entry<Person, Verdict> e : expected.entrySet()) {
            Person p = e.getKey();
            Evaluation.Decision map = Evaluation.of(rules, p.name(), List.of(p.roles()), org.workspace(), "parcels", "WMS");
            Evaluation.Decision data = Evaluation.of(rules, p.name(), List.of(p.roles()), org.workspace(), "parcels", "WFS");
            assertEquals(map(org, p), map.allowed(), "the diagnosis of the map of " + p + " is the answer of WMS");
            Features received = wfs(org, p);
            assertEquals("ok".equals(received.outcome()), data.allowed(), "the diagnosis of the data of " + p + " is the answer of WFS");
            Preview.Answer answer = data.allowed()
                    ? Preview.of(geoserver, org.workspace(), "parcels", geoserver.bindings(org.workspace(), "parcels"),
                            Preview.Limits.of(data))
                    : null;
            if (answer != null) {
                assertEquals(received.rows(), answer.matched(), "the features the diagnosis counts for " + p);
                if (received.rows() > 0) {
                    assertEquals(received.columns(), new TreeSet<>(answer.visible()), "the columns of " + p);
                }
            }
            Diagnosis.Finding finding = Diagnosis.of(!p.name().startsWith("elena"), false, map, data,
                    answer == null ? 0 : answer.matched(), 100);
            assertEquals(e.getValue(), finding.verdict(), "the finding for " + p);
        }
        // The grant that is missing is named: read for the data, which fabio and luca see only on the map.
        Evaluation.Decision none = Evaluation.of(rules, elena.name(), List.of(elena.roles()), org.workspace(), "parcels", "WMS");
        assertEquals(Cause.NO_GRANT, none.cause());
        assertEquals("read", Diagnosis.of(true, false, none, none, 0, 100).missing());
        assertEquals(Verdict.NOT_MEMBER, Diagnosis.of(false, false, none, none, 0, 100).verdict());
        Evaluation.Decision lucaData = Evaluation.of(rules, luca.name(), List.of(luca.roles()), org.workspace(), "parcels", "WFS");
        assertEquals(Cause.DATA_RULES_DENY, lucaData.cause(), "luca's block of data rules has no WFS at the level view");
        // Gino: the areas of his two rules have nothing in common, and GeoServer gives him nothing at all.
        assertEquals(Cause.DATA_RULES_DENY,
                Evaluation.of(rules, gino.name(), List.of(gino.roles()), org.workspace(), "parcels", "WMS").cause());
        // The rule that hides by itself: hana's filter leaves nothing, dario's area half of the layer.
        Map<String, String> bindings = geoserver.bindings(org.workspace(), "parcels");
        assertEquals(0, Preview.count(geoserver, org.workspace(), "parcels", bindings,
                new Preview.Limits(List.of(), "stato = 'nessuno'", Set.of())));
        assertEquals(50, Preview.count(geoserver, org.workspace(), "parcels", bindings,
                new Preview.Limits(List.of(WEST), null, Set.of())));
        assertEquals(Cause.ADMINISTRATOR,
                Evaluation.of(rules, admin.name(), List.of(admin.roles()), org.workspace(), "parcels", "WFS").cause());
    }

    // Helpers ------------------------------------------------------------------------------------

    /** A person as GeoServer receives them: a name and roles. */
    record Person(String name, String... roles) {
        HttpRequest.Builder request(String url) {
            return HttpRequest.newBuilder(URI.create(url))
                    .header(GeoServerStack.USER_HEADER, name)
                    .header(GeoServerStack.ROLES_HEADER, String.join(";", roles));
        }

        @Override
        public String toString() {
            return name;
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
}
