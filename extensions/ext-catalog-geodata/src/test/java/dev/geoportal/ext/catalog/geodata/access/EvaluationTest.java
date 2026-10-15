// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.geoportal.ext.catalog.geodata.access.Diagnosis.Verdict;
import dev.geoportal.ext.catalog.geodata.access.Evaluation.Cause;
import dev.geoportal.ext.catalog.geodata.access.Evaluation.Decision;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.Kind;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.LayerAccess;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.Limited;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.Limits;
import dev.geoportal.ext.catalog.geodata.access.RuleSet.Reach;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-339 without GeoServer: GeoFence's decision read from the generated rule set, the finding of the diagnosis,
 * and the filter the preview asks WFS with. The GeoServer tests ({@code AccessDiagnosisTest}) compare the same
 * with what GeoServer answers.
 */
@Tag("GP-339")
class EvaluationTest {

    static final UUID ORG = UUID.randomUUID();
    static final UUID TEAM = UUID.randomUUID();
    static final String WS = "o_test";
    static final String AREA = "SRID=32633;MULTIPOLYGON(((0 0,1 0,1 1,0 1,0 0)))";
    static final List<String> COLUMNS = List.of("nome", "stato", "riservato", "geom");

    static List<GeoFenceRule> rules() {
        return RuleSet.of(WS, ORG, List.of(new LayerAccess("parcels",
                List.of(new Reach(Kind.TEAM, RuleSet.teamRole(TEAM), "read"), new Reach(Kind.PERSON, "vera@example.test", "view")),
                List.of(new Limited("dario@example.test", "read", new Limits(AREA, false, "stato = 'attivo'", COLUMNS, Set.of("riservato"))),
                        new Limited("gino@example.test", "read", new Limits(null, true, null, COLUMNS, Set.of())),
                        new Limited("luca@example.test", "view", new Limits(AREA, false, null, COLUMNS, Set.of())))),
                new LayerAccess("other", List.of(new Reach(Kind.PUBLIC, null, "view")), List.of())));
    }

    static Decision of(String user, String service, String... roles) {
        return Evaluation.of(rules(), user, List.of(roles), WS, "parcels", service);
    }

    @Test
    void aPersonWithoutAGrantIsDeniedByTheBaseRuleAndTheDiagnosisNamesTheLevelMissing() {
        Decision map = of("elena@example.test", "WMS", ORG.toString());
        Decision data = of("elena@example.test", "WFS", ORG.toString());
        assertFalse(map.allowed());
        assertEquals(Cause.NO_GRANT, map.cause());
        Diagnosis.Finding f = Diagnosis.of(true, false, map, data, 0, 100);
        assertEquals(Verdict.NO_GRANT, f.verdict());
        assertEquals("read", f.missing());
        assertEquals(Verdict.NOT_MEMBER, Diagnosis.of(false, false, map, data, 0, 100).verdict());
    }

    @Test
    void aGrantOfLevelViewGivesTheMapAndNotTheFeatures() {
        Decision map = of("vera@example.test", "WMS", ORG.toString());
        Decision data = of("vera@example.test", "WFS", ORG.toString());
        assertTrue(map.allowed());
        assertEquals(Cause.GRANT, map.cause());
        assertFalse(data.allowed());
        Diagnosis.Finding f = Diagnosis.of(true, false, map, data, 0, 100);
        assertEquals(Verdict.VIEW_ONLY, f.verdict());
        assertEquals("read", f.missing());
        // Limited by a data rule at the level view: the block allows the map only.
        Decision luca = of("luca@example.test", "WFS", ORG.toString(), TEAM.toString());
        assertEquals(Cause.DATA_RULES_DENY, luca.cause());
        assertEquals(Verdict.VIEW_ONLY,
                Diagnosis.of(true, false, of("luca@example.test", "WMS", ORG.toString()), luca, 0, 100).verdict());
    }

    @Test
    void aPersonLimitedByDataRulesGetsTheAreaTheFilterAndTheHiddenColumnsWhateverTeamOpensTheLayer() {
        Decision data = of("dario@example.test", "WFS", ORG.toString(), TEAM.toString());
        assertTrue(data.allowed());
        assertEquals(Cause.DATA_RULES, data.cause());
        assertEquals(List.of(AREA), data.areas());
        assertEquals("stato = 'attivo'", data.cql());
        assertEquals(Set.of("riservato"), data.hidden());
        assertEquals(Verdict.RULES_LIMIT, Diagnosis.of(true, false, data, data, 12, 100).verdict());
        assertEquals(Verdict.RULES_HIDE_ALL, Diagnosis.of(true, false, data, data, 0, 100).verdict());
        // The areas of gino's rules have nothing in common: his block is a DENY, on the map too.
        Decision gino = of("gino@example.test", "WMS", ORG.toString(), TEAM.toString());
        assertFalse(gino.allowed());
        assertEquals(Cause.DATA_RULES_DENY, gino.cause());
        assertEquals(Verdict.RULES_HIDE_ALL, Diagnosis.of(true, false, gino, gino, 0, 100).verdict());
    }

    @Test
    void administratorsAndTheMembersOfATeamWithoutRulesReceiveTheWholeLayer() {
        Decision admin = of("anna@example.test", "WFS", ORG.toString(), RuleSet.administratorsRole(ORG));
        assertEquals(Cause.ADMINISTRATOR, admin.cause());
        assertFalse(admin.limited());
        Decision ivo = of("ivo@example.test", "WFS", ORG.toString(), TEAM.toString());
        assertEquals(Cause.GRANT, ivo.cause());
        assertEquals(Verdict.FULL, Diagnosis.of(true, false, ivo, ivo, 100, 100).verdict());
        // A layer open to the public, read without any role.
        Decision anyone = Evaluation.of(rules(), null, List.of(), WS, "other", "WMS");
        assertTrue(anyone.allowed());
    }

    @Test
    void thePreviewAsksWfsWithTheFilterAndTheAreaInTheSystemOfTheData() {
        assertNull(Preview.filter(Preview.Limits.NONE, "geom"));
        assertEquals("(stato = 'attivo') AND INTERSECTS(\"geom\", MULTIPOLYGON(((0 0,1 0,1 1,0 1,0 0))))",
                Preview.filter(new Preview.Limits(List.of(AREA), "stato = 'attivo'", Set.of()), "geom"));
        assertEquals("POLYGON((0 0,1 0,1 1,0 0))", Preview.wkt("srid=4326;POLYGON((0 0,1 0,1 1,0 0))"));
    }
}
