// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.geoserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.catalog.access.RuleSet;
import dev.geoportal.catalog.access.RuleSet.Kind;
import dev.geoportal.catalog.access.RuleSet.LayerAccess;
import dev.geoportal.catalog.access.RuleSet.Limited;
import dev.geoportal.catalog.access.RuleSet.Limits;
import dev.geoportal.catalog.access.RuleSet.Reach;
import dev.geoportal.catalog.access.RuleWriter;
import dev.geoportal.catalog.editing.WfsTransaction;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-036 against a real GeoServer with GeoFence: WFS-T Insert and Update of a surveyor on the layer
 * assigned to them are applied, the same transactions of an editor and of the surveyor on another layer
 * of the organization answer an access error, and removing the assignment refuses the next transaction
 * once the rules are written again, without anything else changing for the person. GeoServer takes the
 * person and their roles from headers here ({@link GeoServerStack#withPeopleFromHeaders}), as it will from
 * the gateway of GP-336.
 *
 * <p>Each test has an organization with two layers, {@code parcels} and {@code roads}, of 10 points in
 * EPSG:32633, shared with the organization at {@code download}.
 */
@Tag("GP-036")
@Tag("geoserver")
class SurveyorEditingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern TOTAL = Pattern.compile("total(Inserted|Updated|Deleted)>\\s*(\\d+)\\s*<");

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

    record Org(OrganizationSpace space) {
        UUID id() {
            return space.organization();
        }

        String workspace() {
            return space.workspace();
        }

        String member() {
            return RuleSet.organizationRole(id());
        }

        RuleWriter.Outcome write(List<String> parcelsSurveyors, List<String> roadsSurveyors, Reach... extra) {
            return write(parcelsSurveyors, List.of(), roadsSurveyors, extra);
        }

        RuleWriter.Outcome write(List<String> parcelsSurveyors, List<Limited> parcelsLimited, List<String> roadsSurveyors,
                Reach... extra) {
            List<Reach> reaches = new java.util.ArrayList<>(List.of(new Reach(Kind.ORGANIZATION, member(), "download")));
            reaches.addAll(List.of(extra));
            List<LayerAccess> layers = List.of(new LayerAccess("parcels", reaches, parcelsLimited, parcelsSurveyors),
                    new LayerAccess("roads", reaches, List.of(), roadsSurveyors));
            RuleWriter.Outcome o = new RuleWriter(geoserver).rewrite(workspace(), RuleSet.of(workspace(), id(), layers),
                    new HashSet<>(), null);
            assertEquals(List.of(), o.mismatches(), "GeoFence holds the rules written");
            return o;
        }
    }

    static Org organization() {
        OrganizationSpace space = new OrganizationSpace(UUID.randomUUID());
        reconciler.apply(space, Desired.empty(stack.store()));
        StringBuilder sql = new StringBuilder();
        for (String table : List.of("parcels", "roads")) {
            sql.append("create table ").append(space.schema()).append('.').append(table)
                    .append(" (fid serial primary key, nome text, geom geometry(Point, 32633));")
                    .append("insert into ").append(space.schema()).append('.').append(table)
                    .append(" (nome, geom) select 'p' || i, ST_SetSRID(ST_MakePoint(290000 + i * 1000, 4640000), 32633)")
                    .append(" from generate_series(0, 9) i;");
        }
        stack.sql(sql.toString());
        Alignment a = reconciler.apply(space, new Desired(stack.store(), List.of(
                new LayerDefinition("parcels", "parcels", "Particelle", "", "EPSG:32633", null),
                new LayerDefinition("roads", "roads", "Strade", "", "EPSG:32633", null)), List.of(), Set.of(), Set.of()));
        assertFalse(a.failed(), () -> a.changes().toString());
        return new Org(space);
    }

    // Acceptance 1 and 2 -------------------------------------------------------------------------

    @Test
    void aSurveyorInsertsAndUpdatesTheirLayerAndIsRefusedOnAnotherWhileAnEditorWritesNothing() throws Exception {
        Org org = organization();
        Person sara = new Person("sara-" + org.id() + "@example.test", org.member());
        Person enzo = new Person("enzo-" + org.id() + "@example.test", org.member());
        // enzo, an editor, manages the datasets; sara, a surveyor, has parcels assigned.
        org.write(List.of(sara.name()), List.of(), new Reach(Kind.PERSON, enzo.name(), "manage"));

        HttpResponse<String> insert = sara.transaction(org, insert(org, "parcels", "rilevata"));
        assertEquals(200, insert.statusCode(), insert.body());
        assertEquals(1, total(insert.body(), "Inserted"), insert.body());
        HttpResponse<String> update = sara.transaction(org, update(org, "parcels", "nome = 'p1'", "verificata"));
        assertEquals(1, total(update.body(), "Updated"), update.body());
        assertEquals(11, count(org, sara, "parcels", null));
        assertEquals(1, count(org, sara, "parcels", "nome='verificata'"));
        assertEquals(1, count(org, sara, "parcels", "nome='rilevata'"));

        // The surveyor on a layer of the same organization that is not assigned to them.
        HttpResponse<String> other = sara.transaction(org, insert(org, "roads", "abusiva"));
        assertRefused(other);
        assertRefused(sara.transaction(org, update(org, "roads", "nome = 'p1'", "abusiva")));
        assertEquals(10, count(org, sara, "roads", null));
        assertEquals(0, count(org, sara, "roads", "nome='abusiva'"));

        // The editor, who manages the datasets, on both layers; and someone who has no role at all.
        assertRefused(enzo.transaction(org, insert(org, "parcels", "dell'editor")));
        assertRefused(enzo.transaction(org, update(org, "parcels", "nome = 'p2'", "dell'editor")));
        assertRefused(enzo.transaction(org, insert(org, "roads", "dell'editor")));
        assertRefused(Person.ANONYMOUS.transaction(org, insert(org, "parcels", "anonima")));
        assertEquals(0, count(org, sara, "parcels", "nome='dell''editor'"));
        assertEquals(11, count(org, enzo, "parcels", null), "the editor still reads what is shared with them");
    }

    // Acceptance 3 -------------------------------------------------------------------------------

    @Test
    void removingTheAssignmentRefusesTheNextTransactionOfTheSameSession() throws Exception {
        Org org = organization();
        Person sara = new Person("sara-" + org.id() + "@example.test", org.member());
        org.write(List.of(sara.name()), List.of(sara.name()));
        assertEquals(1, total(sara.transaction(org, insert(org, "roads", "prima")).body(), "Inserted"));

        // The assignment of roads is removed: the catalog writes the rules again, nothing else changes.
        org.write(List.of(sara.name()), List.of());
        assertRefused(sara.transaction(org, insert(org, "roads", "dopo")));
        assertEquals(1, total(sara.transaction(org, insert(org, "parcels", "ancora")).body(), "Inserted"),
                "parcels is still assigned");
        // Assigned again: writes again.
        org.write(List.of(sara.name()), List.of(sara.name()));
        assertEquals(1, total(sara.transaction(org, insert(org, "roads", "di nuovo")).body(), "Inserted"));
    }

    // Data rules (GP-308, ADR-0025) -----------------------------------------------------------------

    /**
     * The evidence of ADR-0025: a surveyor limited by a data rule (an area over the first five points and a row
     * filter that excludes p1) writes through GeoFence within both. An insert outside the area or the filter is
     * refused whole; an update or a delete reaches only the features inside both, whatever its own filter.
     */
    @Test
    @Tag("GP-308")
    void aSurveyorLimitedByADataRuleWritesOnlyWithinItsAreaAndRowFilter() throws Exception {
        Org org = organization();
        Person sara = new Person("sara-" + org.id() + "@example.test", org.member());
        Person admin = new Person("admin-" + org.id() + "@example.test", RuleSet.administratorsRole(org.id()));
        String area = "SRID=32633;MULTIPOLYGON(((289500 4639000,294500 4639000,294500 4641000,289500 4641000,289500 4639000)))";
        Limits limits = new Limits(area, false, "nome <> 'p1'", List.of("fid", "nome", "geom"), Set.of());
        RuleWriter.Outcome o = org.write(List.of(sara.name()), List.of(new Limited(sara.name(), "download", limits)), List.of());
        assertTrue(o.written(), o.toString());

        // Inserts: inside both is applied; outside the area, or inside it but outside the filter, is refused.
        HttpResponse<String> inside = sara.transaction(org, insertAt(org, "parcels", "dentro", "291500 4640500"));
        assertEquals(1, total(inside.body(), "Inserted"), inside.body());
        assertRefused(sara.transaction(org, insertAt(org, "parcels", "fuori", "297500 4640500")));
        assertRefused(sara.transaction(org, insertAt(org, "parcels", "p1", "291500 4640600")));
        assertEquals(0, count(org, admin, "parcels", "nome='fuori'"));
        assertEquals(1, count(org, admin, "parcels", "nome='p1'"), "only the original p1");

        // Updates: p7 is outside the area, p1 outside the filter; p2 is inside both.
        sara.transaction(org, update(org, "parcels", "nome = 'p7'", "mossa"));
        sara.transaction(org, update(org, "parcels", "nome = 'p1'", "mossa"));
        HttpResponse<String> updated = sara.transaction(org, update(org, "parcels", "nome = 'p2'", "verificata"));
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals(0, count(org, admin, "parcels", "nome='mossa'"), "nothing outside the rule changed");
        assertEquals(1, count(org, admin, "parcels", "nome='verificata'"));

        // Deletes: the same.
        sara.transaction(org, delete(org, "parcels", "p8"));
        sara.transaction(org, delete(org, "parcels", "p1"));
        sara.transaction(org, delete(org, "parcels", "p3"));
        assertEquals(1, count(org, admin, "parcels", "nome='p8'"));
        assertEquals(1, count(org, admin, "parcels", "nome='p1'"));
        assertEquals(0, count(org, admin, "parcels", "nome='p3'"));
        assertEquals(10, count(org, admin, "parcels", null), "10 points, one inserted, one deleted");

        // Still refused on the layer not assigned to them.
        assertRefused(sara.transaction(org, insertAt(org, "roads", "dentro", "291500 4640500")));
    }

    // The path of the catalog ---------------------------------------------------------------------

    @Test
    void theTransactionTheCatalogChecksReachesTheServiceOfTheLayer() throws Exception {
        Org org = organization();
        org.write(List.of(), List.of());
        byte[] body = insert(org, "parcels", "dal catalogo").getBytes(StandardCharsets.UTF_8);
        WfsTransaction.Summary summary = WfsTransaction.check(body, "parcels", org.workspace(), org.space().namespaceUri());
        assertEquals(1, summary.actions().get("insert"));
        HttpResponse<String> answer = geoserver.transaction(org.workspace() + "/parcels/wfs", body);
        assertEquals(200, answer.statusCode(), answer.body());
        assertTrue(WfsTransaction.failure(answer.body()).isEmpty(), answer.body());
        assertEquals(1, total(answer.body(), "Inserted"));
        // The service of a layer knows no other layer: a transaction that names roads fails there too.
        HttpResponse<String> other = geoserver.transaction(org.workspace() + "/parcels/wfs",
                insert(org, "roads", "altrove").getBytes(StandardCharsets.UTF_8));
        assertTrue(WfsTransaction.failure(other.body()).isPresent() || other.statusCode() >= 400, other.body());
    }

    // Helpers ------------------------------------------------------------------------------------

    static String insert(Org org, String layer, String nome) {
        return "<wfs:Transaction service=\"WFS\" version=\"1.1.0\" xmlns:wfs=\"http://www.opengis.net/wfs\""
                + " xmlns:gml=\"http://www.opengis.net/gml\" xmlns:o=\"" + org.space().namespaceUri() + "\">"
                + "<wfs:Insert><o:" + layer + "><o:nome>" + nome + "</o:nome><o:geom><gml:Point srsName=\"EPSG:32633\">"
                + "<gml:pos>295500 4640500</gml:pos></gml:Point></o:geom></o:" + layer + "></wfs:Insert></wfs:Transaction>";
    }

    static String insertAt(Org org, String layer, String nome, String pos) {
        return insert(org, layer, nome).replace("295500 4640500", pos);
    }

    static String delete(Org org, String layer, String nome) {
        return "<wfs:Transaction service=\"WFS\" version=\"1.1.0\" xmlns:wfs=\"http://www.opengis.net/wfs\""
                + " xmlns:ogc=\"http://www.opengis.net/ogc\" xmlns:o=\"" + org.space().namespaceUri() + "\">"
                + "<wfs:Delete typeName=\"o:" + layer + "\"><ogc:Filter><ogc:PropertyIsEqualTo><ogc:PropertyName>nome"
                + "</ogc:PropertyName><ogc:Literal>" + nome + "</ogc:Literal></ogc:PropertyIsEqualTo></ogc:Filter>"
                + "</wfs:Delete></wfs:Transaction>";
    }

    static String update(Org org, String layer, String cql, String nome) {
        String value = cql.substring(cql.indexOf('\'') + 1, cql.lastIndexOf('\''));
        return "<wfs:Transaction service=\"WFS\" version=\"1.1.0\" xmlns:wfs=\"http://www.opengis.net/wfs\""
                + " xmlns:ogc=\"http://www.opengis.net/ogc\" xmlns:o=\"" + org.space().namespaceUri() + "\">"
                + "<wfs:Update typeName=\"o:" + layer + "\"><wfs:Property><wfs:Name>nome</wfs:Name><wfs:Value>" + nome
                + "</wfs:Value></wfs:Property><ogc:Filter><ogc:PropertyIsEqualTo><ogc:PropertyName>nome</ogc:PropertyName>"
                + "<ogc:Literal>" + value + "</ogc:Literal></ogc:PropertyIsEqualTo></ogc:Filter></wfs:Update></wfs:Transaction>";
    }

    static int total(String answer, String what) {
        Matcher m = TOTAL.matcher(answer);
        while (m.find()) {
            if (m.group(1).equals(what)) {
                return Integer.parseInt(m.group(2));
            }
        }
        return -1;
    }

    /** An access error: no transaction summary, an exception of OGC or a refusal of HTTP. */
    static void assertRefused(HttpResponse<String> r) {
        assertFalse(total(r.body(), "Inserted") > 0 || total(r.body(), "Updated") > 0, r.body());
        assertTrue(r.statusCode() >= 400 || r.body().contains("Exception"), r.statusCode() + " " + r.body());
    }

    static int count(Org org, Person p, String layer, String cql) throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(p.request(stack.url() + "/" + org.workspace()
                + "/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=" + org.workspace() + ":" + layer
                + "&outputFormat=application/json"
                + (cql == null ? "" : "&cql_filter=" + java.net.URLEncoder.encode(cql, StandardCharsets.UTF_8))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        JsonNode fc = JSON.readTree(r.body());
        return fc.path("features").size();
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

        HttpResponse<String> transaction(Org org, String xml) throws IOException, InterruptedException {
            return HTTP.send(request(stack.url() + "/" + org.workspace() + "/wfs").header("Content-Type", "text/xml")
                    .POST(HttpRequest.BodyPublishers.ofString(xml)).build(), HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public String toString() {
            return name == null ? "anonymous" : name;
        }
    }
}
