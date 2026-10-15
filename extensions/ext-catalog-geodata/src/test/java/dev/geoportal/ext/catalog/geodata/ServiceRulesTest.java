// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-309: a data rule on a layer of an external service, which the server cannot apply, makes the layer
 * unavailable to its subject: the proxy refuses every request that names it, and says why.
 */
@Tag("GP-309")
class ServiceRulesTest {

    static final ServiceRules.Unavailable ROADS = new ServiceRules.Unavailable(false, Set.of("ns:roads"));

    @Test
    void aRequestThatNamesTheLayerIsRefusedWithTheReason() {
        for (String query : List.of("service=WMS&request=GetMap&layers=ns:roads&bbox=0,0,1,1",
                "SERVICE=WMS&REQUEST=GetMap&LAYERS=parcels,roads", "service=WMS&request=GetFeatureInfo&query_layers=roads",
                "service=WFS&request=GetFeature&typeNames=ns%3Aroads", "service=WMTS&request=GetTile&layer=roads",
                "service=WMS&request=GetLegendGraphic&LAYER=ROADS", "service=WCS&request=GetCoverage&coverageId=roads")) {
            var r = ServiceRules.refusal(ROADS, "GET", null, query);
            assertTrue(r.isPresent(), query);
            assertEquals("ns:roads", r.get().layer());
            assertTrue(r.get().message().contains("cannot apply it"), r.get().message());
        }
        // A RESTful WMTS names the layer in its path.
        assertTrue(ServiceRules.refusal(ROADS, "GET", "tile/1.0.0/roads/default/g/0/0/0.png", null).isPresent());
    }

    @Test
    void otherLayersAndTheCapabilitiesPass() {
        for (String query : List.of("service=WMS&request=GetCapabilities", "service=WMS&request=GetMap&layers=parcels",
                "service=WFS&request=GetFeature&typeName=other:roads", "service=WMS&request=GetMap&layers=roadside")) {
            assertTrue(ServiceRules.refusal(ROADS, "GET", null, query).isEmpty(), query);
        }
        assertTrue(ServiceRules.refusal(ServiceRules.Unavailable.NONE, "POST", null, "layers=roads").isEmpty());
    }

    @Test
    void aRuleOnTheWholeServiceRefusesEverythingAndAPostIsRefusedUnread() {
        var whole = new ServiceRules.Unavailable(true, Set.of());
        var r = ServiceRules.refusal(whole, "GET", null, "service=WMS&request=GetCapabilities");
        assertTrue(r.isPresent());
        assertEquals(null, r.get().layer());
        assertTrue(r.get().message().startsWith("A data rule limits what you see of this service"));
        assertTrue(ServiceRules.refusal(ROADS, "POST", null, "service=WFS").isPresent(),
                "the proxy does not read the body: a POST is refused to who has a rule on the service");
    }

    @Test
    void namesAreComparedQualifiedOrNot() {
        assertTrue(ServiceRules.same("roads", "ns:roads"));
        assertTrue(ServiceRules.same("ns:roads", "roads"));
        assertTrue(ServiceRules.same("NS:Roads", "ns:roads"));
        assertTrue(!ServiceRules.same("other:roads", "ns:roads"));
        assertEquals(List.of("a", "b", "x"), ServiceRules.named("x", "layers=a,b&styles=c"));
    }
}
