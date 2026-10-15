// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.catalog.access.RuleSet.Kind;
import dev.geoportal.catalog.access.RuleSet.LayerAccess;
import dev.geoportal.catalog.access.RuleSet.Limited;
import dev.geoportal.catalog.access.RuleSet.Limits;
import dev.geoportal.catalog.access.RuleSet.Reach;
import dev.geoportal.catalog.geoserver.LayerDefinition;
import dev.geoportal.catalog.geoserver.PublishedLayer;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-309: the area of a data rule on a raster clips the image (GeoFence {@code LIMIT} in {@code CLIP} mode),
 * while on a vector it keeps the features that intersect it; the rule is written in the format that keeps
 * its mode and reads back the same.
 */
@Tag("GP-309")
class RasterRulesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ORG = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String WS = "org_11111111222233334444555555555555";
    private static final String AREA = "SRID=32633;MULTIPOLYGON(((0 0,1 0,1 1,0 1,0 0)))";

    static PublishedLayer layer(String name, boolean raster) {
        PublishedLayer l = new PublishedLayer(ORG, new LayerDefinition(name, name, name, "", "EPSG:32633", null),
                Instant.now());
        l.raster(raster);
        return l;
    }

    @Test
    void theAreaOfARuleOnARasterClipsAndOnAVectorIntersects() throws Exception {
        Limits limits = new Limits(AREA, false, null, List.of(), Set.of());
        List<LayerAccess> access = List.of(
                new LayerAccess("dem", List.of(new Reach(Kind.ORGANIZATION, ORG.toString(), "download")),
                        List.of(new Limited("dario@example.test", "download", limits))),
                new LayerAccess("parcels", List.of(new Reach(Kind.ORGANIZATION, ORG.toString(), "download")),
                        List.of(new Limited("dario@example.test", "download", limits))));
        List<GeoFenceRule> rules = AccessRules.clipRasters(RuleSet.of(WS, ORG, access),
                List.of(layer("dem", true), layer("parcels", false)));
        GeoFenceRule dem = rules.stream().filter(r -> r.access() == GeoFenceRule.Access.LIMIT && "dem".equals(r.layer()))
                .findFirst().orElseThrow();
        GeoFenceRule parcels = rules.stream()
                .filter(r -> r.access() == GeoFenceRule.Access.LIMIT && "parcels".equals(r.layer())).findFirst().orElseThrow();
        assertTrue(!dem.writtenAsXml(), "an area is written as JSON, the format that keeps it");
        var written = JSON.readTree(dem.body(7));
        assertEquals("CLIP", written.path("Rule").path("limits").path("spatialFilterType").asText());
        assertTrue(JSON.readTree(parcels.body(8)).path("Rule").path("limits").path("spatialFilterType").isMissingNode());

        // Read back as GeoFence answers it, the clipping rule is the same rule.
        var answered = JSON.readTree("{\"priority\":7,\"userName\":\"dario@example.test\",\"workspace\":\"" + WS
                + "\",\"layer\":\"dem\",\"access\":\"LIMIT\",\"limits\":{\"allowedArea\":\"SRID=32633;MULTIPOLYGON (((0 0, 1 0,"
                + " 1 1, 0 1, 0 0)))\",\"spatialFilterType\":\"CLIP\",\"catalogMode\":\"HIDE\"}}");
        assertEquals(dem.canonical(), GeoFenceRule.read(answered).canonical());
        // A hand change to INTERSECT is a difference.
        var intersect = JSON.readTree(answered.toString().replace("CLIP", "INTERSECT"));
        assertTrue(!dem.canonical().equals(GeoFenceRule.read(intersect).canonical()));
    }

    @Test
    void withoutRastersTheRulesStayAsTheyAre() {
        List<GeoFenceRule> rules = RuleSet.of(WS, ORG, List.of(new LayerAccess("parcels", List.of(), List.of())));
        assertEquals(rules, AccessRules.clipRasters(rules, List.of(layer("parcels", false))));
        GeoFenceRule allow = rules.getFirst();
        assertEquals(allow, allow.clipped(), "only a LIMIT with an area clips");
    }
}
