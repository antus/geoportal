// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** GP-308: the data rules of one person on one dataset compose so that only what all of them allow is left. */
@Tag("GP-308")
class DataRulesCompositionTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DATASET = UUID.randomUUID();

    private static DataRule rule(String area, String cql, String... hidden) {
        DataRule r = new DataRule(ORG, DATASET, "manager@example.test", Instant.now());
        r.define(DataRule.TEAM, UUID.randomUUID(), "", area, cql, List.of(hidden), Instant.now());
        return r;
    }

    @Test
    void twoRulesIntersectTheirAreasJoinTheirFiltersWithAndAndHideEveryColumnEitherHides() {
        AccessRules.Composed c = AccessRules.compose(List.of(
                rule("SRID=32633;MULTIPOLYGON(((0 0,2 0,2 2,0 2,0 0)))", "stato = 'attivo'", "riservato"),
                rule("SRID=32633;MULTIPOLYGON(((1 1,3 1,3 3,1 3,1 1)))", "comune = 'Valverde'", "telefono")));
        assertEquals(2, c.areas().size(), "both areas, which the composition intersects");
        assertEquals("(stato = 'attivo') AND (comune = 'Valverde')", c.cql());
        assertEquals(Set.of("riservato", "telefono"), c.hidden());
    }

    @Test
    void aRuleWithoutALimitDoesNotWidenTheOthers() {
        AccessRules.Composed c = AccessRules.compose(List.of(rule(null, "stato = 'attivo'"), rule(null, null, "riservato")));
        assertTrue(c.areas().isEmpty());
        assertEquals("stato = 'attivo'", c.cql());
        assertEquals(Set.of("riservato"), c.hidden());
        AccessRules.Composed same = AccessRules.compose(List.of(rule(null, " stato = 'attivo' "), rule(null, "stato = 'attivo'")));
        assertEquals("stato = 'attivo'", same.cql(), "the same filter twice is one");
        assertNull(AccessRules.compose(List.of(rule(null, null, "x"))).cql());
    }

    @Test
    void theGeometryIsNotAColumnToHide() {
        assertTrue(DataRuleResource.geometry("org.locationtech.jts.geom.MultiPolygon"));
        assertTrue(DataRuleResource.geometry("org.locationtech.jts.geom.Geometry"));
        assertFalse(DataRuleResource.geometry("java.lang.String"));
    }
}
