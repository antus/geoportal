// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.style;

import com.fasterxml.jackson.databind.JsonNode;
import dev.geoportal.ext.catalog.geodata.style.InvalidStyleException.Problem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The translation of a style document into SLD 1.0 for GeoServer (GP-009), the renderer adapter of the
 * catalog; the browser has the one of MapLibre ({@code web/style-grammar.js}). A constant is a literal;
 * a value read from the data is a property; a scaled value is the function Interpolate; categories and
 * graduated classes become one rule per class, with the filter of the class and its label as the title
 * of the rule, which the legend of GeoServer shows. A layer of mixed geometries has the rules of each
 * geometry, told apart by the dimension of the geometry.
 *
 * <p>Two rules of drawing, as before the grammar: the fill of a point is at least 5% opaque, a line at
 * least half a pixel wide. A style without classes gives the SLD that GeoServer received before the
 * grammar, so that the layers published then are not rewritten.
 */
final class SldStyle {

    /** Beyond this, the classes of a style make an SLD GeoServer draws too slowly. */
    static final int MAX_RULES = 500;

    private static final Map<String, String> MIXED_RULES = Map.of("point", "points", "line", "lines", "polygon", "areas");
    private static final Map<String, Integer> DIMENSIONS = Map.of("point", 0, "line", 1, "polygon", 2);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private SldStyle() {}

    static String of(StyleDocument doc, String name, String kind) {
        List<String> kinds = kind.equals("mixed") ? StyleValidator.KINDS : List.of(kind);
        StringBuilder rules = new StringBuilder();
        for (String k : kinds) {
            JsonNode symbol = doc.symbol(k);
            List<Classifier> classifiers = classifiers(symbol.get("style"), doc.label());
            long count = 1;
            for (Classifier c : classifiers) {
                count *= c.classes();
            }
            if (count * kinds.size() > MAX_RULES) {
                throw new InvalidStyleException(List.of(new Problem("", "rules", "the classes of the style make "
                        + count * kinds.size() + " rules of SLD, at most " + MAX_RULES)));
            }
            String ruleName = kind.equals("mixed") ? MIXED_RULES.get(k) : k;
            int[] combo = new int[classifiers.size()];
            for (int n = 0; n < count; n++) {
                int rest = n;
                for (int i = classifiers.size() - 1; i >= 0; i--) {
                    combo[i] = rest % classifiers.get(i).classes();
                    rest /= classifiers.get(i).classes();
                }
                Map<String, Integer> chosen = new LinkedHashMap<>();
                List<String> conditions = new ArrayList<>();
                List<String> titles = new ArrayList<>();
                if (kind.equals("mixed")) {
                    conditions.add(dimension(k));
                }
                for (int i = 0; i < classifiers.size(); i++) {
                    Classifier c = classifiers.get(i);
                    chosen.put(c.key(), combo[i]);
                    conditions.add(c.condition(combo[i]));
                    String title = c.title(combo[i]);
                    if (title != null) {
                        titles.add(title);
                    }
                }
                Resolver r = new Resolver(chosen);
                rules.append("<Rule>\n<Name>").append(xml(classifiers.isEmpty() ? ruleName : ruleName + "-" + (n + 1)))
                        .append("</Name>\n");
                if (!titles.isEmpty()) {
                    rules.append("<Title>").append(xml(String.join(" · ", titles))).append("</Title>\n");
                }
                if (!conditions.isEmpty()) {
                    rules.append("<ogc:Filter>\n").append(all(conditions)).append("</ogc:Filter>\n");
                }
                rules.append(symbolizer(k, symbol, r));
                if (doc.label() != null) {
                    rules.append(text(k, doc.label(), r));
                }
                rules.append("</Rule>\n");
            }
        }
        JsonNode legend = doc.legend();
        String title = legend != null && legend.hasNonNull("title") && !legend.get("title").asText().isBlank()
                ? legend.get("title").asText() : name;
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <StyledLayerDescriptor version="1.0.0" xmlns="http://www.opengis.net/sld" \
                xmlns:ogc="http://www.opengis.net/ogc" xmlns:xlink="http://www.w3.org/1999/xlink" \
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
                xsi:schemaLocation="http://www.opengis.net/sld http://schemas.opengis.net/sld/1.0.0/StyledLayerDescriptor.xsd">
                <NamedLayer>
                <Name>%1$s</Name>
                <UserStyle>
                <Name>%1$s</Name>
                <Title>%2$s</Title>
                <FeatureTypeStyle>
                %3$s</FeatureTypeStyle>
                </UserStyle>
                </NamedLayer>
                </StyledLayerDescriptor>
                """.formatted(xml(name), xml(title), rules);
    }

    // Symbolizers ------------------------------------------------------------------------------------

    private static String symbolizer(String kind, JsonNode symbol, Resolver r) {
        JsonNode s = symbol.get("style");
        return switch (kind) {
            case "point" -> "<PointSymbolizer>\n<Graphic>\n<Mark>\n<WellKnownName>"
                    + xml(symbol.get("config").get("shape").asText()) + "</WellKnownName>\n<Fill>\n"
                    + css("fill", r.value(s.get("fill"), "color"))
                    + css("fill-opacity", r.atLeast(s.get("fillOpacity"), 0.05, "numeric"))
                    + "</Fill>\n<Stroke>\n"
                    + css("stroke", r.value(s.get("stroke"), "color"))
                    + css("stroke-width", r.value(s.get("strokeWidth"), "numeric"))
                    + "</Stroke>\n</Mark>\n<Size>" + r.value(s.get("size"), "numeric") + "</Size>\n</Graphic>\n"
                    + "</PointSymbolizer>\n";
            case "line" -> {
                StringBuilder b = new StringBuilder("<LineSymbolizer>\n<Stroke>\n")
                        .append(css("stroke", r.value(s.get("stroke"), "color")))
                        .append(css("stroke-width", r.atLeast(s.get("strokeWidth"), 0.5, "numeric")));
                JsonNode opacity = s.get("strokeOpacity");
                if (!(opacity.isNumber() && opacity.asDouble() == 1)) {
                    b.append(css("stroke-opacity", r.value(opacity, "numeric")));
                }
                JsonNode dash = symbol.get("config").get("dash");
                if (dash != null) {
                    List<String> parts = new ArrayList<>();
                    dash.forEach(d -> parts.add(StyleDocument.num(d.asDouble())));
                    b.append(css("stroke-dasharray", String.join(" ", parts)));
                }
                yield b.append("</Stroke>\n</LineSymbolizer>\n").toString();
            }
            default -> "<PolygonSymbolizer>\n<Fill>\n"
                    + css("fill", r.value(s.get("fill"), "color"))
                    + css("fill-opacity", r.value(s.get("fillOpacity"), "numeric"))
                    + "</Fill>\n<Stroke>\n"
                    + css("stroke", r.value(s.get("stroke"), "color"))
                    + css("stroke-width", r.value(s.get("strokeWidth"), "numeric"))
                    + "</Stroke>\n</PolygonSymbolizer>\n";
        };
    }

    private static String text(String kind, JsonNode label, Resolver r) {
        StringBuilder b = new StringBuilder("<TextSymbolizer>\n<Label>").append(labelText(label.get("text")))
                .append("</Label>\n<Font>\n")
                .append(css("font-family", "SansSerif"))
                .append(css("font-size", r.value(label.get("size"), "numeric")))
                .append("</Font>\n");
        if (kind.equals("line")) {
            b.append("<LabelPlacement>\n<LinePlacement/>\n</LabelPlacement>\n");
        }
        JsonNode halo = label.get("halo");
        if (halo != null) {
            b.append("<Halo>\n<Radius>").append(StyleDocument.num(halo.get("width").asDouble())).append("</Radius>\n<Fill>\n")
                    .append(css("fill", halo.get("color").asText().toLowerCase(java.util.Locale.ROOT)))
                    .append("</Fill>\n</Halo>\n");
        }
        return b.append("<Fill>\n").append(css("fill", r.value(label.get("color"), "color")))
                .append("</Fill>\n</TextSymbolizer>\n").toString();
    }

    /** A constant in CDATA (GeoServer keeps its spaces), an attribute, or a template of both. */
    private static String labelText(JsonNode t) {
        if (t.isTextual()) {
            return cdata(t.asText());
        }
        if (t.has("field")) {
            return property(t.get("field").asText());
        }
        StringBuilder b = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(t.get("template").asText());
        int at = 0;
        String template = t.get("template").asText();
        while (m.find()) {
            if (m.start() > at) {
                b.append(cdata(template.substring(at, m.start())));
            }
            b.append(property(m.group(1)));
            at = m.end();
        }
        if (at < template.length()) {
            b.append(cdata(template.substring(at)));
        }
        return b.toString();
    }

    // Values -----------------------------------------------------------------------------------------

    /** The values of one rule: the class each classifier is in, and how the others read the data. */
    private record Resolver(Map<String, Integer> chosen) {

        /** A value as the content of a parameter: literal, property, or function. */
        String value(JsonNode v, String method) {
            if (!v.isObject()) {
                return literal(v);
            }
            String field = v.get("field").asText();
            if (v.has("categories")) {
                int i = chosen.get(Classifier.of(v).key());
                JsonNode c = v.get("categories");
                return literal(i < c.size() ? c.get(i).get("value") : v.get("otherwise"));
            }
            if (v.has("graduated")) {
                return literal(v.get("graduated").get("values").get(chosen.get(Classifier.of(v).key())));
            }
            if (v.has("scaled")) {
                JsonNode s = v.get("scaled");
                return "<ogc:Function name=\"Interpolate\">" + property(field)
                        + lit(s.get("from").get(0)) + lit(s.get("to").get(0))
                        + lit(s.get("from").get(1)) + lit(s.get("to").get(1))
                        + "<ogc:Literal>linear</ogc:Literal><ogc:Literal>" + method + "</ogc:Literal></ogc:Function>";
            }
            return property(field);
        }

        /** A value with a lower bound when it is a constant, as the SLD before the grammar. */
        String atLeast(JsonNode v, double min, String method) {
            if (v.isNumber()) {
                return StyleDocument.num(Math.max(v.asDouble(), min));
            }
            return value(v, method);
        }

        private static String lit(JsonNode v) {
            return "<ogc:Literal>" + literal(v) + "</ogc:Literal>";
        }
    }

    private static String literal(JsonNode v) {
        if (v.isNumber()) {
            return StyleDocument.num(v.asDouble());
        }
        if (v.isTextual() && StyleValidator.COLOR.matcher(v.asText()).matches()) {
            return v.asText().toLowerCase(java.util.Locale.ROOT);
        }
        return xml(v.asText());
    }

    // Classes ----------------------------------------------------------------------------------------

    /** The values of a symbol and of its label that split the features into classes, one per distinct split. */
    private static List<Classifier> classifiers(JsonNode style, JsonNode label) {
        Map<String, Classifier> found = new LinkedHashMap<>();
        List<JsonNode> values = new ArrayList<>();
        style.forEach(values::add);
        if (label != null) {
            values.add(label.get("color"));
            values.add(label.get("size"));
        }
        for (JsonNode v : values) {
            if (v.isObject() && (v.has("categories") || v.has("graduated"))) {
                Classifier c = Classifier.of(v);
                found.putIfAbsent(c.key(), c);
            }
        }
        return List.copyOf(found.values());
    }

    /** Categories or graduated classes of a field: two values with the same ones share the rules. */
    private record Classifier(String key, String field, JsonNode categories, JsonNode graduated) {

        static Classifier of(JsonNode v) {
            String field = v.get("field").asText();
            if (v.has("categories")) {
                List<String> equals = new ArrayList<>();
                v.get("categories").forEach(c -> equals.add(c.get("equals").toString()));
                return new Classifier(field + " categories " + equals, field, v.get("categories"), null);
            }
            return new Classifier(field + " graduated " + v.get("graduated").get("breaks"), field, null,
                    v.get("graduated"));
        }

        int classes() {
            return categories != null ? categories.size() + 1 : graduated.get("breaks").size() + 1;
        }

        String condition(int i) {
            if (categories != null) {
                if (i < categories.size()) {
                    return equal(field, categories.get(i).get("equals"));
                }
                List<String> any = new ArrayList<>();
                categories.forEach(c -> any.add(equal(field, c.get("equals"))));
                return "<ogc:Not>\n" + (any.size() == 1 ? any.get(0) : "<ogc:Or>\n" + String.join("", any) + "</ogc:Or>\n")
                        + "</ogc:Not>\n";
            }
            JsonNode b = graduated.get("breaks");
            if (i == 0) {
                // A feature without a value takes the first class.
                return "<ogc:Not>\n" + compare("PropertyIsGreaterThanOrEqualTo", field, b.get(0)) + "</ogc:Not>\n";
            }
            if (i == b.size()) {
                return compare("PropertyIsGreaterThanOrEqualTo", field, b.get(i - 1));
            }
            return "<ogc:And>\n" + compare("PropertyIsGreaterThanOrEqualTo", field, b.get(i - 1))
                    + compare("PropertyIsLessThan", field, b.get(i)) + "</ogc:And>\n";
        }

        /** The text of a class in the legend: its label, or the value or range it stands for. */
        String title(int i) {
            if (categories != null) {
                if (i >= categories.size()) {
                    return null;
                }
                JsonNode c = categories.get(i);
                return c.hasNonNull("label") ? c.get("label").asText() : StyleValidator.scalar(c.get("equals"));
            }
            JsonNode labels = graduated.get("labels");
            if (labels != null) {
                return labels.get(i).asText();
            }
            JsonNode b = graduated.get("breaks");
            if (i == 0) {
                return "< " + StyleDocument.num(b.get(0).asDouble());
            }
            if (i == b.size()) {
                return "≥ " + StyleDocument.num(b.get(i - 1).asDouble());
            }
            return StyleDocument.num(b.get(i - 1).asDouble()) + " – " + StyleDocument.num(b.get(i).asDouble());
        }
    }

    // XML --------------------------------------------------------------------------------------------

    private static String all(List<String> conditions) {
        return conditions.size() == 1 ? conditions.get(0) : "<ogc:And>\n" + String.join("", conditions) + "</ogc:And>\n";
    }

    private static String dimension(String kind) {
        return "<ogc:PropertyIsEqualTo>\n<ogc:Function name=\"dimension\"><ogc:PropertyName>geom</ogc:PropertyName>"
                + "</ogc:Function>\n<ogc:Literal>" + DIMENSIONS.get(kind) + "</ogc:Literal>\n</ogc:PropertyIsEqualTo>\n";
    }

    private static String equal(String field, JsonNode value) {
        return "<ogc:PropertyIsEqualTo>\n" + property(field) + "\n<ogc:Literal>" + xml(StyleValidator.scalar(value))
                + "</ogc:Literal>\n</ogc:PropertyIsEqualTo>\n";
    }

    private static String compare(String operator, String field, JsonNode value) {
        return "<ogc:" + operator + ">\n" + property(field) + "\n<ogc:Literal>" + StyleDocument.num(value.asDouble())
                + "</ogc:Literal>\n</ogc:" + operator + ">\n";
    }

    private static String property(String field) {
        return "<ogc:PropertyName>" + xml(field) + "</ogc:PropertyName>";
    }

    private static String css(String name, String value) {
        return "<CssParameter name=\"" + name + "\">" + value + "</CssParameter>\n";
    }

    private static String cdata(String text) {
        return "<![CDATA[" + text.replace("]]>", "]]]]><![CDATA[>") + "]]>";
    }

    static String xml(String text) {
        StringBuilder b = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '&' -> b.append("&amp;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
