// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.geoserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.geoportal.catalog.access.AccessRules;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * GP-309 against a real GeoServer with GeoFence: a raster published as a coverage from a Cloud Optimized
 * GeoTIFF, and a data rule with an area on it. The images WMS returns to the person the rule names are cut
 * to the area (GeoFence {@code LIMIT} in {@code CLIP} mode, read back unchanged); the others see the whole
 * image. The coverage store of the raster is not drift for the alignment. GeoServer takes the person and
 * their roles from headers here, as it will from the gateway of GP-336.
 *
 * <p>The raster: 100 by 100 pixels of 100 metres in EPSG:32633 from (290000, 4640000), every pixel 200; the
 * area of the rule is its western half.
 */
@Tag("GP-309")
@Tag("geoserver")
class RasterClipTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String WEST = "SRID=32633;MULTIPOLYGON(((289000 4639000,295000 4639000,295000 4651000,289000 4651000,289000 4639000)))";

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

    /** A COG made by GDAL in PostGIS, as the loader of the catalog makes them (GP-937). */
    static byte[] cog() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(stack.geodataJdbc());
        ds.setUser(GeoServerStack.DB_USER);
        ds.setPassword(GeoServerStack.DB_PASSWORD);
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("select ST_AsGDALRaster(ST_AddBand(ST_MakeEmptyRaster(100, 100, 290000,"
                        + " 4650000, 100, -100, 0, 0, 32633), '8BUI'::text, 200, 0), 'COG', ARRAY['COMPRESS=DEFLATE'])")) {
            r.next();
            return r.getBytes(1);
        }
    }

    @Test
    void anAreaRuleOnARasterCutsTheImagesOfItsSubjectAndOnlyTheirs() throws Exception {
        OrganizationSpace space = new OrganizationSpace(UUID.randomUUID());
        reconciler.apply(space, Desired.empty(stack.store()));
        String ws = space.workspace();
        geoserver.uploadCoverage(ws, "dem", cog());
        geoserver.describeCoverage(ws, "dem", "Rilievo", "");
        double[] bounds = geoserver.coverageBounds(ws, "dem").orElseThrow();
        assertEquals(290000, bounds[0], 1);
        assertEquals(4650000, bounds[3], 1);

        // The coverage store of a raster of the catalog is not drift; one unknown to the catalog is.
        Alignment known = reconciler.apply(space, new Desired(stack.store(), List.of(), List.of(), Set.of(), Set.of(),
                Set.of("dem")));
        assertTrue(known.drift().stream().noneMatch(d -> d.name().equals("dem")), known.drift().toString());
        Alignment unknown = reconciler.apply(space, Desired.empty(stack.store()));
        assertTrue(unknown.drift().stream().anyMatch(d -> d.kind().equals("coverage-store") && d.name().equals("dem")));
        assertTrue(geoserver.coverageStores(ws).contains("dem"), "drift is never deleted");

        String member = RuleSet.organizationRole(space.organization());
        Person dario = new Person("dario-" + space.organization() + "@example.test", member);
        Person elena = new Person("elena-" + space.organization() + "@example.test", member);
        PublishedLayer dem = new PublishedLayer(space.organization(),
                new LayerDefinition("dem", "dem", "Rilievo", "", "EPSG:32633", null), Instant.now());
        dem.raster(true);
        List<GeoFenceRule> rules = AccessRules.clipRasters(RuleSet.of(ws, space.organization(), List.of(
                new LayerAccess("dem", List.of(new Reach(Kind.ORGANIZATION, member, "download")),
                        List.of(new Limited(dario.name(), "download", new Limits(WEST, false, null, List.of(), Set.of())))))),
                List.of(dem));
        assertTrue(rules.stream().anyMatch(r -> r.access() == GeoFenceRule.Access.LIMIT && r.describe().contains("CLIP")
                || r.canonical().contains("spatialFilterType=CLIP")), rules.toString());
        RuleWriter.Outcome o = new RuleWriter(geoserver).rewrite(ws, rules, new HashSet<>(), null);
        assertEquals(List.of(), o.mismatches(), "GeoFence keeps the clipping mode of the area");

        BufferedImage d = map(ws, dario);
        BufferedImage e = map(ws, elena);
        assertTrue(alpha(d, 20, 50) > 0, "dario sees the western half");
        assertEquals(0, alpha(d, 80, 50), "dario does not see the eastern half: the image is cut to the area");
        assertTrue(alpha(e, 20, 50) > 0);
        assertTrue(alpha(e, 80, 50) > 0, "elena has no rule: the whole image");
        assertEquals(null, map(ws, Person.ANONYMOUS), "nobody else sees the raster");

        geoserver.deleteCoverageStore(ws, "dem");
        assertFalse(geoserver.coverageStores(ws).contains("dem"));
    }

    static int alpha(BufferedImage image, int x, int y) {
        return (image.getRGB(x, y) >>> 24) & 0xff;
    }

    /** The whole raster, 100 by 100 pixels, transparent where nothing is drawn; null when refused. */
    static BufferedImage map(String ws, Person p) throws Exception {
        HttpResponse<byte[]> r = HTTP.send(p.request(stack.url() + "/" + ws + "/wms?service=WMS&version=1.1.1&request=GetMap"
                + "&layers=" + ws + ":dem&styles=&srs=EPSG:32633&bbox=290000,4640000,300000,4650000&width=100&height=100"
                + "&format=image/png&transparent=true").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200 || !r.headers().firstValue("Content-Type").orElse("").startsWith("image/png")) {
            return null;
        }
        return ImageIO.read(new ByteArrayInputStream(r.body()));
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
    }
}
