// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.geoportal.ext.catalog.geodata.geoserver.Sld;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * GP-009 in the catalog: the documents of geoportal/style/v1 checked as their JSON Schema and the editor
 * check them (the fixtures of src/test/resources/style are read by tests/style.test.ts too), the change
 * of type, the styles of before the grammar migrated without losing anything, and the translation into
 * SLD, which draws a style alike on every layer it is applied to.
 */
@Tag("GP-009")
class StyleGrammarTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path DOCS = Path.of("..", "..", "docs", "style");
    private static final List<String> GEOMETRIES = List.of("Point", "MultiLineString", "MultiPolygon", "Geometry");

    private static Schema schema;

    @BeforeAll
    static void schema() throws IOException {
        schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(JSON.readTree(DOCS.resolve("geoportal-style-v1.schema.json").toFile()));
    }

    @Test
    void theExamplesOfTheDocumentationAreValidDocumentsThatTranslateForEveryGeometry() throws Exception {
        List<Path> examples = examples();
        assertTrue(examples.size() >= 5);
        for (Path f : examples) {
            JsonNode doc = JSON.readTree(f.toFile());
            assertEquals(List.of(), schema.validate(doc).stream().map(Object::toString).toList(), f.toString());
            StyleDocument style = StyleDocument.of(doc);
            for (String g : GEOMETRIES) {
                xml(style.sld("esempio", g));
            }
        }
        for (String type : List.of("point", "line", "polygon", "mixed")) {
            assertEquals(List.of(), schema.validate(StyleDocument.defaults().retype(type).tree()).stream()
                    .map(Object::toString).toList());
        }
    }

    @Test
    void anInvalidDocumentIsRefusedWithWhereAndWhyAsItsSchemaAndTheEditorRefuseIt() throws Exception {
        for (JsonNode c : JSON.readTree(resource("invalid.json"))) {
            String why = c.get("why").asText();
            InvalidStyleException e = assertThrows(InvalidStyleException.class, () -> StyleDocument.of(c.get("document")), why);
            assertTrue(e.problems().stream().anyMatch(p -> p.path().equals(c.get("path").asText())
                    && p.code().equals(c.get("code").asText())), why + ": " + e.problems());
            assertTrue(e.getMessage().contains(c.get("path").asText()), why);
            // The rules a schema cannot say are those of the implementations only.
            assertEquals(c.get("schema").asBoolean(), !schema.validate(c.get("document")).isEmpty(), why);
        }
        assertThrows(InvalidStyleException.class, () -> StyleDocument.parse("{not json"));
    }

    @Test
    void changingTheTypeKeepsWhatHasTheSameMeaningAsTheEditorChangesIt() throws Exception {
        for (JsonNode c : JSON.readTree(resource("retype.json"))) {
            StyleDocument changed = StyleDocument.of(c.get("from")).retype(c.get("to").asText());
            assertEquals(StyleDocument.of(c.get("expected")), changed, c.get("why").asText());
        }
    }

    @Test
    void switchingAPointStyleToALineStyleKeepsColourOpacityAndLabelAndMapsPointSizeToLineWidth() throws Exception {
        StyleDocument point = StyleDocument.parse("""
                {"grammar": "geoportal/style/v1", "type": "point", "config": {"shape": "triangle"},
                 "style": {"fill": "#e76f51", "fillOpacity": 0.7, "stroke": "#264653", "strokeWidth": 1, "size": 14},
                 "label": {"text": {"field": "nome"}, "color": "#264653", "size": 11}}""");
        ObjectNode line = point.retype("line").tree();
        assertEquals("line", line.get("type").asText());
        assertEquals("#e76f51", line.at("/style/stroke").asText());
        assertEquals(0.7, line.at("/style/strokeOpacity").asDouble());
        assertEquals(14, line.at("/style/strokeWidth").asDouble());
        assertEquals(point.tree().get("label"), line.get("label"));
        // GeoServer draws it as such: the line of the colour, opacity and width of the point, labelled.
        String sld = point.retype("line").sld("linee", "MultiLineString");
        assertTrue(sld.contains("<CssParameter name=\"stroke\">#e76f51</CssParameter>"), sld);
        assertTrue(sld.contains("<CssParameter name=\"stroke-opacity\">0.7</CssParameter>"), sld);
        assertTrue(sld.contains("<CssParameter name=\"stroke-width\">14</CssParameter>"), sld);
        assertTrue(sld.contains("<Label><ogc:PropertyName>nome</ogc:PropertyName></Label>"), sld);
        // A point style on a layer of lines is drawn as the line it becomes.
        assertTrue(Sld.same(sld.replace("linee", "x"), point.sld("x", "LineString")));
    }

    @Test
    void aStyleExportedAsJsonAndAppliedToAnotherLayerDrawsIdentically() throws Exception {
        for (Path f : examples()) {
            StyleDocument style = StyleDocument.of(JSON.readTree(f.toFile()));
            // Exported as JSON, imported on another layer, of another installation: the same document.
            StyleDocument imported = StyleDocument.parse(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(style.tree()));
            assertEquals(style, imported, f.toString());
            for (String g : GEOMETRIES) {
                assertTrue(Sld.same(style.sld("a", g).replace("<Name>a</Name>", "<Name>b</Name>")
                        .replace("<Title>a</Title>", "<Title>b</Title>"), imported.sld("b", g)), f + " on " + g);
            }
        }
    }

    @Test
    void theStylesOfBeforeTheGrammarBecomeDocumentsThatKeepEachValueAndDrawTheSame() throws Exception {
        List<LegacyStyle> styles = List.of(
                LegacyStyle.DEFAULT,
                new LegacyStyle("#ff0000", 1, "#000000", 1, 12, "square"),
                new LegacyStyle("#ABCDEF", 0, "#123456", 0, 1, "x"),
                new LegacyStyle("#00ff00", 0.333, "#0000ff", 20, 64, "star"));
        for (LegacyStyle s : styles) {
            StyleDocument doc = StyleDocument.fromLegacy(s);
            assertEquals("mixed", doc.type());
            ObjectNode t = doc.tree();
            assertEquals(s.pointShape(), t.at("/geometries/point/config/shape").asText());
            assertEquals(s.fill(), t.at("/geometries/point/style/fill").asText());
            assertEquals(s.fillOpacity(), t.at("/geometries/point/style/fillOpacity").asDouble());
            assertEquals(s.stroke(), t.at("/geometries/point/style/stroke").asText());
            assertEquals(s.strokeWidth(), t.at("/geometries/point/style/strokeWidth").asDouble());
            assertEquals(s.pointSize(), t.at("/geometries/point/style/size").asDouble());
            for (String g : List.of("Point", "MultiPoint", "LineString", "MultiLineString", "Polygon", "MultiPolygon",
                    "Geometry", "GeometryCollection")) {
                assertTrue(Sld.same(new LegacySld(s).sld("stile", g), doc.sld("stile", g)), s + " on " + g);
            }
        }
        // As the catalog kept them, and as old clients still send them.
        StyleDocument kept = StyleDocument.parse("{\"fill\":\"#ff0000\",\"fillOpacity\":1.0,\"stroke\":\"#000000\","
                + "\"strokeWidth\":1.0,\"pointSize\":12.0,\"pointShape\":\"square\"}");
        assertEquals(StyleDocument.fromLegacy(styles.get(1)), kept);
        assertEquals(StyleDocument.fromLegacy(LegacyStyle.DEFAULT), StyleDocument.defaults());
        // As the editor migrates them too.
        for (JsonNode c : JSON.readTree(resource("legacy.json"))) {
            assertEquals(StyleDocument.of(c.get("expected")), StyleDocument.of(c.get("legacy")), c.get("why").asText());
        }
        assertThrows(InvalidStyleException.class, () -> StyleDocument.parse("{\"fill\":\"red\",\"pointShape\":\"circle\"}"));
    }

    @Test
    void theDocumentTheCatalogKeepsGivesBackTheSameStyle() throws Exception {
        for (Path f : examples()) {
            StyleDocument style = StyleDocument.of(JSON.readTree(f.toFile()));
            assertEquals(style, StyleDocument.parse(style.json()));
            assertEquals(JSON.readTree(f.toFile()), JSON.readTree(style.json()), "nothing added, nothing lost");
        }
    }

    // SLD --------------------------------------------------------------------------------------------

    @Test
    void categoriesBecomeOneRulePerClassWithItsFilterAndItsLabelInTheLegend() throws Exception {
        Document d = xml(example("polygon-categorized.json").sld("uso_suolo", "MultiPolygon"));
        NodeList rules = d.getElementsByTagName("Rule");
        assertEquals(4, rules.getLength(), "three categories and the others");
        assertEquals("Uso del suolo", text(d, "Title"));
        Element first = (Element) rules.item(0);
        assertEquals("Residenziale", first.getElementsByTagName("Title").item(0).getTextContent());
        assertEquals("residenziale", first.getElementsByTagName("ogc:Literal").item(0).getTextContent());
        assertTrue(first.getTextContent().contains("#f4a261"));
        Element other = (Element) rules.item(3);
        assertEquals(1, other.getElementsByTagName("ogc:Not").getLength());
        assertEquals(3, other.getElementsByTagName("ogc:PropertyIsEqualTo").getLength());
        assertTrue(other.getTextContent().contains("#dddddd"));
    }

    @Test
    void graduatedClassesScaledValuesAndTemplatesBecomeFiltersFunctionsAndLabels() throws Exception {
        String sld = example("point-graduated-scaled.json").sld("stazioni", "Point");
        Document d = xml(sld);
        NodeList rules = d.getElementsByTagName("Rule");
        assertEquals(3, rules.getLength());
        assertEquals("Sotto la soglia", ((Element) rules.item(0)).getElementsByTagName("Title").item(0).getTextContent());
        // A feature without a value takes the first class.
        assertEquals(1, ((Element) rules.item(0)).getElementsByTagName("ogc:Not").getLength());
        assertEquals(1, ((Element) rules.item(1)).getElementsByTagName("ogc:PropertyIsLessThan").getLength());
        assertTrue(sld.contains("<ogc:Function name=\"Interpolate\"><ogc:PropertyName>portata</ogc:PropertyName>"
                + "<ogc:Literal>0</ogc:Literal><ogc:Literal>6</ogc:Literal><ogc:Literal>500</ogc:Literal>"
                + "<ogc:Literal>24</ogc:Literal><ogc:Literal>linear</ogc:Literal><ogc:Literal>numeric</ogc:Literal>"
                + "</ogc:Function>"), sld);
        assertTrue(sld.contains("<Label><ogc:PropertyName>nome</ogc:PropertyName><![CDATA[ (]]>"
                + "<ogc:PropertyName>livello</ogc:PropertyName><![CDATA[ m)]]></Label>"), sld);
        assertEquals(3, d.getElementsByTagName("TextSymbolizer").getLength());
    }

    @Test
    void aMixedLayerHasTheRulesOfEachGeometryAndTooManyClassesAreRefused() throws Exception {
        StyleDocument categorized = example("polygon-categorized.json");
        Document d = xml(categorized.sld("misto", "Geometry"));
        assertEquals(12, d.getElementsByTagName("Rule").getLength(), "four classes for each of three geometries");
        assertEquals(12, d.getElementsByTagName("ogc:And").getLength(), "the dimension and the class");

        ObjectNode many = JSON.createObjectNode().put("grammar", StyleDocument.GRAMMAR).put("type", "polygon");
        many.putObject("config");
        ObjectNode style = many.putObject("style");
        ObjectNode fill = style.putObject("fill").put("field", "a");
        fill.putArray("categories");
        for (int i = 0; i < 100; i++) {
            fill.withArray("categories").addObject().put("equals", i).put("value", "#000000");
        }
        fill.put("otherwise", "#ffffff");
        ObjectNode size = style.putObject("strokeWidth").put("field", "b");
        size.putObject("graduated").putArray("breaks").add(1).add(2).add(3).add(4).add(5);
        size.withObject("graduated").putArray("values").add(1).add(2).add(3).add(4).add(5).add(6);
        style.put("fillOpacity", 1).put("stroke", "#000000");
        InvalidStyleException e = assertThrows(InvalidStyleException.class,
                () -> StyleDocument.of(many).sld("troppi", "Polygon"));
        assertEquals("rules", e.problems().get(0).code());
    }

    @Test
    void nothingOfTheDocumentReachesTheSldButEscapedText() throws Exception {
        StyleDocument style = StyleDocument.parse("""
                {"grammar": "geoportal/style/v1", "type": "polygon", "config": {},
                 "style": {"fill": {"field": "k", "categories": [{"equals": "<a>&'\\"", "value": "#000000",
                   "label": "</Title><script>"}], "otherwise": "#ffffff"},
                   "fillOpacity": 1, "stroke": "#000000", "strokeWidth": 1},
                 "label": {"text": "]]><x>", "color": "#000000", "size": 10},
                 "legend": {"title": "<b>"}}""");
        String sld = style.sld("x", "Polygon");
        Document d = xml(sld);
        assertFalse(sld.contains("<script>"));
        assertEquals(0, d.getElementsByTagName("x").getLength());
        assertEquals(0, d.getElementsByTagName("b").getLength());
        assertEquals("]]><x>", d.getElementsByTagName("Label").item(0).getTextContent());
        assertNotEquals(-1, sld.indexOf("&lt;a&gt;&amp;&apos;&quot;"));
    }

    // -----------------------------------------------------------------------------------------------

    private static List<Path> examples() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(DOCS.resolve("examples"))) {
            files.filter(p -> p.toString().endsWith(".json")).sorted().forEach(out::add);
        }
        return out;
    }

    private static StyleDocument example(String name) throws IOException {
        return StyleDocument.of(JSON.readTree(DOCS.resolve("examples").resolve(name).toFile()));
    }

    private static java.io.File resource(String name) {
        return Path.of("src", "test", "resources", "style", name).toFile();
    }

    private static Document xml(String sld) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        return f.newDocumentBuilder().parse(new InputSource(new StringReader(sld)));
    }

    private static String text(Document d, String tag) {
        return d.getElementsByTagName(tag).item(0).getTextContent();
    }
}
