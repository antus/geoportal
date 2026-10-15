// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.catalog.access.RuleSet.Kind;
import dev.geoportal.catalog.access.RuleSet.LayerAccess;
import dev.geoportal.catalog.access.RuleSet.Limited;
import dev.geoportal.catalog.access.RuleSet.Limits;
import dev.geoportal.catalog.access.RuleSet.Reach;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** GP-335: the rule set generated from grants and data rules, and how it is written and compared. */
@Tag("GP-335")
class RuleSetTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ORG = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TEAM = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String WS = "org_11111111222233334444555555555555";
    private static final String AREA = "SRID=32633;MULTIPOLYGON(((0 0,1 0,1 1,0 1,0 0)))";

    private static List<String> described(List<GeoFenceRule> rules) {
        return rules.stream().map(GeoFenceRule::describe).toList();
    }

    @Test
    void grantsBecomeUserAndRoleRulesPerServiceOfTheLevelBetweenTheWriteDenialsAndTheBaseDeny() {
        List<GeoFenceRule> rules = RuleSet.of(WS, ORG, List.of(new LayerAccess("parcels", List.of(
                new Reach(Kind.PERSON, "anna@example.test", "view"),
                new Reach(Kind.PERSON, "anna@example.test", "download"),
                new Reach(Kind.TEAM, RuleSet.teamRole(TEAM), "view"),
                new Reach(Kind.ORGANIZATION, RuleSet.organizationRole(ORG), "edit"),
                new Reach(Kind.PUBLIC, null, "view")), List.of())));
        assertEquals(List.of(
                "DENY everyone on " + WS + " WFS TRANSACTION",
                "DENY everyone on " + WS + " WFS LOCKFEATURE",
                "DENY everyone on " + WS + " WFS GETFEATUREWITHLOCK",
                "ALLOW role " + ORG + ":admin on " + WS,
                "ALLOW user anna@example.test on parcels WMS",
                "ALLOW user anna@example.test on parcels GWC",
                "ALLOW user anna@example.test on parcels WFS",
                "ALLOW user anna@example.test on parcels WCS",
                "ALLOW role " + TEAM + " on parcels WMS",
                "ALLOW role " + TEAM + " on parcels GWC",
                "ALLOW role " + ORG + " on parcels WMS",
                "ALLOW role " + ORG + " on parcels GWC",
                "ALLOW role " + ORG + " on parcels WFS",
                "ALLOW role " + ORG + " on parcels WCS",
                "ALLOW everyone on parcels WMS",
                "ALLOW everyone on parcels GWC",
                "DENY everyone on " + WS), described(rules));
        Set<String> keys = new HashSet<>();
        rules.forEach(r -> assertTrue(keys.add(r.duplicateKey()), "GeoFence would refuse a duplicate: " + r.describe()));
    }

    @Test
    void aPersonLimitedByDataRulesHasABlockOfUserRulesInFrontOfEveryRoleRule() {
        Limits limits = new Limits(AREA, false, "stato = 'attivo'", List.of("fid", "nome", "riservato", "geom"),
                Set.of("riservato"));
        List<GeoFenceRule> rules = RuleSet.of(WS, ORG, List.of(new LayerAccess("parcels", List.of(
                new Reach(Kind.PERSON, "dario@example.test", "download"),
                new Reach(Kind.TEAM, RuleSet.teamRole(TEAM), "download")),
                List.of(new Limited("dario@example.test", "download", limits)))));
        List<String> d = described(rules);
        assertEquals("LIMIT user dario@example.test on parcels with an area", d.get(3));
        assertEquals("ALLOW user dario@example.test on parcels WMS rows stato = 'attivo' hiding 1 column", d.get(4));
        assertEquals("DENY user dario@example.test on parcels", d.get(8));
        assertEquals("ALLOW role " + ORG + ":admin on " + WS, d.get(9));
        assertFalse(d.contains("ALLOW user dario@example.test on parcels WMS"), "no unlimited rule of the person");
        GeoFenceRule allow = rules.get(4);
        assertEquals(List.of("fid", "nome", "riservato", "geom"), List.copyOf(allow.attributes().keySet()));
        assertEquals("NONE", allow.attributes().get("riservato"));
        assertEquals("READONLY", allow.attributes().get("nome"));

        // Areas with nothing in common: only the DENY of the person.
        List<GeoFenceRule> empty = RuleSet.of(WS, ORG, List.of(new LayerAccess("parcels", List.of(),
                List.of(new Limited("dario@example.test", "view", new Limits(null, true, null, List.of(), Set.of()))))));
        assertEquals("DENY user dario@example.test on parcels", described(empty).get(3));
        // No level: no rule of the person at all, the base DENY decides.
        List<GeoFenceRule> none = RuleSet.of(WS, ORG, List.of(new LayerAccess("parcels", List.of(),
                List.of(new Limited("dario@example.test", null, limits)))));
        assertTrue(described(none).stream().noneMatch(s -> s.contains("dario")));
    }

    @Test
    void eachRuleIsWrittenInTheFormatThatKeepsItAndReadBackTheSame() throws Exception {
        GeoFenceRule limit = new GeoFenceRule("dario@example.test", null, WS, "parcels", null, null,
                GeoFenceRule.Access.LIMIT, AREA, null, null, null);
        assertFalse(limit.writtenAsXml(), "an area goes as JSON");
        assertEquals("{\"Rule\":{\"priority\":7,\"userName\":\"dario@example.test\",\"workspace\":\"" + WS
                + "\",\"layer\":\"parcels\",\"access\":\"LIMIT\",\"limits\":{\"allowedArea\":\"" + AREA
                + "\",\"catalogMode\":\"HIDE\"}}}", limit.body(7));
        GeoFenceRule columns = new GeoFenceRule("dario@example.test", null, WS, "parcels", "WFS", null,
                GeoFenceRule.Access.ALLOW, null, "nome <> 'x' & 1", java.util.Map.of("riservato", "NONE"), null);
        assertTrue(columns.writtenAsXml(), "columns go as XML");
        assertTrue(columns.body(8).contains("<cqlFilterRead>nome &lt;&gt; &apos;x&apos; &amp; 1</cqlFilterRead>"));
        assertTrue(columns.body(8).contains("<attribute><name>riservato</name><accessType>NONE</accessType></attribute>"));

        // As GeoFence answers them: another spacing of the area, the columns in another order, the defaults.
        String readLimit = "{\"id\":3,\"priority\":7,\"userName\":\"dario@example.test\",\"roleName\":null,\"workspace\":\""
                + WS + "\",\"layer\":\"parcels\",\"service\":null,\"request\":null,\"access\":\"LIMIT\",\"limits\":"
                + "{\"allowedArea\":\"SRID=32633;MULTIPOLYGON (((0 0, 1.0 0, 1 1, 0 1, 0 0)))\",\"catalogMode\":\"HIDE\","
                + "\"spatialFilterType\":\"INTERSECT\"},\"layerDetails\":null}";
        assertEquals(limit.canonical(), GeoFenceRule.read(JSON.readTree(readLimit)).canonical());
        String lost = readLimit.replace("\"allowedArea\":\"SRID=32633;MULTIPOLYGON (((0 0, 1.0 0, 1 1, 0 1, 0 0)))\"",
                "\"allowedArea\":null");
        List<String> mismatch = RuleWriter.compare(List.of(limit), List.of(GeoFenceRule.read(JSON.readTree(lost))));
        assertEquals(1, mismatch.size());
        assertTrue(mismatch.getFirst().contains("no area"), mismatch.toString());

        String readColumns = "{\"id\":4,\"priority\":8,\"userName\":\"dario@example.test\",\"workspace\":\"" + WS
                + "\",\"layer\":\"parcels\",\"service\":\"WFS\",\"access\":\"ALLOW\",\"layerDetails\":{\"layerType\":"
                + "\"VECTOR\",\"cqlFilterRead\":\"nome <> 'x' & 1\",\"spatialFilterType\":\"INTERSECT\",\"allowedStyles\":[],"
                + "\"attributes\":[{\"name\":\"riservato\",\"dataType\":null,\"accessType\":\"NONE\"}]}}";
        assertEquals(columns.canonical(), GeoFenceRule.read(JSON.readTree(readColumns)).canonical());
        String noColumns = readColumns.replace("[{\"name\":\"riservato\",\"dataType\":null,\"accessType\":\"NONE\"}]", "[]");
        assertTrue(RuleWriter.compare(List.of(columns), List.of(GeoFenceRule.read(JSON.readTree(noColumns))))
                .getFirst().contains("no column list"));
        // A rule changed by hand (an address range) never matches.
        String byHand = readColumns.replace("\"service\":\"WFS\"", "\"service\":\"WFS\",\"addressRange\":\"10.0.0.0/8\"");
        assertFalse(RuleWriter.compare(List.of(columns), List.of(GeoFenceRule.read(JSON.readTree(byHand)))).isEmpty());
        // Missing and excess rules.
        assertTrue(RuleWriter.compare(List.of(limit, columns), List.of(limit)).getFirst().contains("missing in GeoFence"));
        assertTrue(RuleWriter.compare(List.of(), List.of(limit)).getFirst().contains("not written by the catalog"));
    }

    @Test
    void theRulesGoInABandOfFreePrioritiesBelowTheCatchAll() {
        assertEquals(1000, RuleWriter.base(Set.of(99999L), null, 10, 99999));
        assertEquals(3000, RuleWriter.base(Set.of(1000L, 1005L, 2000L, 99999L), null, 10, 99999));
        assertEquals(1000, RuleWriter.base(Set.of(1000L, 1005L, 2000L, 99999L), 1000L, 0, 99999), "an empty set");
        assertEquals(4000, RuleWriter.base(Set.of(4100L, 99999L), 4000L, 50, 99999), "the previous band, still free");
        assertEquals(1000, RuleWriter.base(Set.of(4100L, 99999L), 4000L, 150, 99999), "the previous band, now too small");
        assertEquals(1, RuleWriter.base(Set.of(), null, 10, 1005), "no free band below the ceiling");
    }

    @Test
    void theFingerprintChangesWithTheRules() {
        List<String> a = List.of("ALLOW x", "DENY y");
        assertEquals(AccessRules.fingerprint(a), AccessRules.fingerprint(List.of("ALLOW x", "DENY y")));
        assertFalse(AccessRules.fingerprint(a).equals(AccessRules.fingerprint(List.of("DENY y", "ALLOW x"))));
    }
}
