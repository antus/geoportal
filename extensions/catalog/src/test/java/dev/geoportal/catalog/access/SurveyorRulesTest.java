// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.geoportal.catalog.access.RuleSet.Kind;
import dev.geoportal.catalog.access.RuleSet.LayerAccess;
import dev.geoportal.catalog.access.RuleSet.Limited;
import dev.geoportal.catalog.access.RuleSet.Limits;
import dev.geoportal.catalog.access.RuleSet.Reach;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-036: the GeoFence rules of the surveyors assigned to a layer. A surveyor gets the WFS transaction and
 * lock of their layer in front of the denial of every write; an editor, whatever their level on the
 * dataset, and a surveyor on a layer not assigned to them meet that denial.
 */
@Tag("GP-036")
class SurveyorRulesTest {

    private static final UUID ORG = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String WS = "org_11111111222233334444555555555555";
    private static final String SURVEYOR = "sara@example.test";
    private static final String EDITOR = "enzo@example.test";

    private static List<String> described(List<GeoFenceRule> rules) {
        return rules.stream().map(GeoFenceRule::describe).toList();
    }

    @Test
    void anAssignedSurveyorWritesTheirLayerOnlyAndAnEditorWritesNothing() {
        List<GeoFenceRule> rules = RuleSet.of(WS, ORG, List.of(
                new LayerAccess("parcels", List.of(new Reach(Kind.PERSON, EDITOR, "manage"),
                        new Reach(Kind.PERSON, SURVEYOR, "download")), List.of(), List.of(SURVEYOR)),
                new LayerAccess("roads", List.of(new Reach(Kind.PERSON, SURVEYOR, "edit-data")), List.of(), List.of())));
        List<String> d = described(rules);
        assertEquals(List.of(
                "ALLOW user " + SURVEYOR + " on parcels WFS TRANSACTION",
                "ALLOW user " + SURVEYOR + " on parcels WFS LOCKFEATURE",
                "DENY everyone on " + WS + " WFS TRANSACTION",
                "DENY everyone on " + WS + " WFS LOCKFEATURE",
                "DENY everyone on " + WS + " WFS GETFEATUREWITHLOCK"), d.subList(0, 5));
        assertTrue(d.stream().noneMatch(r -> r.contains(EDITOR) && r.contains("TRANSACTION")), d.toString());
        assertTrue(d.stream().noneMatch(r -> r.contains("roads") && r.contains("TRANSACTION")),
                "edit-data on roads without the assignment writes nothing: " + d);
        assertEquals("DENY everyone on " + WS, d.getLast());
    }

    @Test
    void withoutSurveyorsTheSetIsTheOneOfTheSharingAlone() {
        List<LayerAccess> layers = List.of(new LayerAccess("parcels",
                List.of(new Reach(Kind.ORGANIZATION, RuleSet.organizationRole(ORG), "download")), List.of()));
        List<LayerAccess> same = List.of(new LayerAccess("parcels",
                List.of(new Reach(Kind.ORGANIZATION, RuleSet.organizationRole(ORG), "download")), List.of(), List.of()));
        assertEquals(GeoFenceRule.canonical(RuleSet.of(WS, ORG, layers)), GeoFenceRule.canonical(RuleSet.of(WS, ORG, same)));
    }

    @Test
    void removingAnAssignmentChangesTheSetSoTheNextCheckWritesItAgain() {
        List<GeoFenceRule> with = RuleSet.of(WS, ORG, List.of(new LayerAccess("parcels", List.of(), List.of(),
                List.of(SURVEYOR))));
        List<GeoFenceRule> without = RuleSet.of(WS, ORG, List.of(new LayerAccess("parcels", List.of(), List.of(),
                List.of())));
        assertTrue(!AccessRules.fingerprint(GeoFenceRule.canonical(with))
                .equals(AccessRules.fingerprint(GeoFenceRule.canonical(without))));
        assertEquals(with.size() - 2, without.size());
    }

    @Test
    @Tag("GP-308")
    void aSurveyorLimitedByDataRulesWritesWithinTheirAreaAndRowFilter() {
        String square = "SRID=32633;MULTIPOLYGON(((0 0,1 0,1 1,0 1,0 0)))";
        Limits limits = new Limits(square, false, "nome <> 'p1'", List.of("fid", "nome", "geom"), Set.of());
        LayerAccess layer = new LayerAccess("parcels", List.of(new Reach(Kind.PERSON, SURVEYOR, "download")),
                List.of(new Limited(SURVEYOR, "download", limits)), List.of(SURVEYOR, "other@example.test"));
        assertEquals(List.of("other@example.test"), RuleSet.editing(layer));
        assertEquals(List.of(SURVEYOR), RuleSet.editingWithin(layer).stream().map(Limited::user).toList());
        List<GeoFenceRule> rules = RuleSet.of(WS, ORG, List.of(layer));
        List<String> d = described(rules);
        // In front of the denial of every write: for each request the LIMIT of the area, collected by GeoFence
        // before the ALLOW that carries the row filter as read and write filter.
        assertEquals(List.of(
                "ALLOW user other@example.test on parcels WFS TRANSACTION",
                "ALLOW user other@example.test on parcels WFS LOCKFEATURE",
                "LIMIT user " + SURVEYOR + " on parcels WFS TRANSACTION with an area",
                "ALLOW user " + SURVEYOR + " on parcels WFS TRANSACTION rows nome <> 'p1' writing rows nome <> 'p1'",
                "LIMIT user " + SURVEYOR + " on parcels WFS LOCKFEATURE with an area",
                "ALLOW user " + SURVEYOR + " on parcels WFS LOCKFEATURE rows nome <> 'p1' writing rows nome <> 'p1'",
                "DENY everyone on " + WS + " WFS TRANSACTION"), d.subList(0, 7));
        GeoFenceRule write = rules.get(3);
        assertEquals("nome <> 'p1'", write.cqlWrite());
        assertTrue(write.xml(10).contains("<cqlFilterWrite>nome &lt;&gt; &apos;p1&apos;</cqlFilterWrite>"), write.xml(10));
        // The rule read back from GeoFence is the same rule.
        String json = "{\"priority\":10,\"userName\":\"" + SURVEYOR + "\",\"workspace\":\"" + WS + "\",\"layer\":\"parcels\","
                + "\"service\":\"WFS\",\"request\":\"TRANSACTION\",\"access\":\"ALLOW\",\"layerDetails\":{\"layerType\":\"VECTOR\","
                + "\"cqlFilterRead\":\"nome <> 'p1'\",\"cqlFilterWrite\":\"nome <> 'p1'\",\"attributes\":[]}}";
        assertEquals(write.canonical(), GeoFenceRule.read(read(json)).canonical());
    }

    @Test
    @Tag("GP-308")
    void anAreaAloneLimitsTheWritesWithoutAFilterAndHiddenColumnsOrNothingInCommonStopThem() {
        String square = "SRID=32633;MULTIPOLYGON(((0 0,1 0,1 1,0 1,0 0)))";
        LayerAccess area = new LayerAccess("parcels", List.of(),
                List.of(new Limited(SURVEYOR, null, new Limits(square, false, null, List.of(), Set.of()))), List.of(SURVEYOR));
        List<String> d = described(RuleSet.of(WS, ORG, List.of(area)));
        assertEquals("LIMIT user " + SURVEYOR + " on parcels WFS TRANSACTION with an area", d.get(0));
        assertEquals("ALLOW user " + SURVEYOR + " on parcels WFS TRANSACTION", d.get(1));

        for (Limits stopping : List.of(new Limits(null, false, null, List.of("fid", "nome"), Set.of("nome")),
                new Limits(null, true, null, List.of(), Set.of()))) {
            assertTrue(!RuleSet.writableWithin(stopping));
            LayerAccess layer = new LayerAccess("parcels", List.of(new Reach(Kind.PERSON, SURVEYOR, "download")),
                    List.of(new Limited(SURVEYOR, "download", stopping)), List.of(SURVEYOR));
            assertEquals(List.of(), RuleSet.editingWithin(layer));
            List<String> rules = described(RuleSet.of(WS, ORG, List.of(layer)));
            assertTrue(rules.stream().noneMatch(r -> r.startsWith("ALLOW user " + SURVEYOR + " on parcels WFS TRANSACTION")),
                    rules.toString());
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode read(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void surveyorsAreOrderedAndCountedOnce() {
        LayerAccess layer = new LayerAccess("parcels", List.of(), List.of(), List.of("b@example.test", "a@example.test",
                "b@example.test", " "));
        assertEquals(List.of("a@example.test", "b@example.test"), RuleSet.editing(layer));
    }
}
