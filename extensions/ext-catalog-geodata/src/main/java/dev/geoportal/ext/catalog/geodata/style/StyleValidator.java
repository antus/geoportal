// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.style;

import com.fasterxml.jackson.databind.JsonNode;
import dev.geoportal.ext.catalog.geodata.style.InvalidStyleException.Problem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The checks of a document of geoportal/style/v1: those of its JSON Schema
 * ({@code docs/style/geoportal-style-v1.schema.json}) and those a schema cannot say (breaks in increasing
 * order, as many values as classes, categories told apart, from lower than to). The editor of the
 * catalog does the same in {@code web/style-grammar.js}, with the same codes; the fixtures of the tests
 * keep the three in step.
 */
final class StyleValidator {

    static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    static final Pattern FIELD = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");
    static final Pattern TEMPLATE = Pattern.compile("^([^{}]|\\{[A-Za-z_][A-Za-z0-9_]{0,62}\\})*$");
    static final List<String> TYPES = List.of("point", "line", "polygon", "mixed");
    static final List<String> KINDS = List.of("point", "line", "polygon");
    static final List<String> SHAPES = List.of("circle", "square", "triangle", "star", "cross", "x");

    /** The ranges of the numbers a value may take, by what the value is. */
    static final Map<String, double[]> NUMBERS = Map.of(
            "opacity", new double[] {0, 1},
            "width", new double[] {0, 64},
            "size", new double[] {1, 64},
            "textSize", new double[] {6, 72});

    /** The properties of the style block of each type, and what each one is. */
    static final Map<String, Map<String, String>> STYLE = Map.of(
            "point", ordered("fill", "color", "fillOpacity", "opacity", "stroke", "color", "strokeWidth", "width",
                    "size", "size"),
            "line", ordered("stroke", "color", "strokeOpacity", "opacity", "strokeWidth", "width"),
            "polygon", ordered("fill", "color", "fillOpacity", "opacity", "stroke", "color", "strokeWidth", "width"));

    private final List<Problem> out = new ArrayList<>();

    private StyleValidator() {}

    static List<Problem> problems(JsonNode doc) {
        StyleValidator v = new StyleValidator();
        v.document(doc);
        return v.out;
    }

    private void document(JsonNode doc) {
        if (doc == null || !doc.isObject()) {
            add("", "object", "a style is a JSON object");
            return;
        }
        keys(doc, List.of("$schema", "grammar", "type", "config", "style", "geometries", "label", "legend"), "");
        if (!StyleDocument.GRAMMAR.equals(doc.path("grammar").asText(null)) || !doc.path("grammar").isTextual()) {
            add("/grammar", "grammar", "the grammar is " + StyleDocument.GRAMMAR);
        }
        String type = doc.path("type").isTextual() ? doc.get("type").asText() : null;
        if (!TYPES.contains(type)) {
            add("/type", "enum", "one of " + TYPES);
            return;
        }
        if (doc.has("$schema") && !doc.get("$schema").isTextual()) {
            add("/$schema", "string", "a text");
        }
        if (type.equals("mixed")) {
            for (String k : List.of("config", "style")) {
                if (doc.has(k)) {
                    add("/" + k, "unknown", "not allowed here");
                }
            }
            JsonNode g = doc.get("geometries");
            if (g == null || !g.isObject()) {
                add("/geometries", "required", "required");
            } else {
                keys(g, KINDS, "/geometries");
                for (String k : KINDS) {
                    JsonNode s = g.get(k);
                    if (s == null || !s.isObject()) {
                        add("/geometries/" + k, "required", "required");
                    } else {
                        keys(s, List.of("config", "style"), "/geometries/" + k);
                        symbol(k, s, "/geometries/" + k);
                    }
                }
            }
        } else {
            if (doc.has("geometries")) {
                add("/geometries", "unknown", "not allowed here");
            }
            symbol(type, doc, "");
        }
        if (doc.has("label")) {
            label(doc.get("label"), "/label");
        }
        if (doc.has("legend")) {
            JsonNode l = doc.get("legend");
            if (!l.isObject()) {
                add("/legend", "object", "an object");
            } else {
                keys(l, List.of("title", "show"), "/legend");
                if (l.has("title")) {
                    text(l.get("title"), 0, 200, "/legend/title");
                }
                if (l.has("show") && !l.get("show").isBoolean()) {
                    add("/legend/show", "boolean", "true or false");
                }
            }
        }
    }

    private void symbol(String kind, JsonNode s, String path) {
        JsonNode config = s.get("config");
        if (config == null || !config.isObject()) {
            add(path + "/config", "required", "required");
        } else if (kind.equals("point")) {
            keys(config, List.of("shape"), path + "/config");
            if (!config.path("shape").isTextual() || !SHAPES.contains(config.get("shape").asText())) {
                add(path + "/config/shape", "enum", "one of " + SHAPES);
            }
        } else if (kind.equals("line")) {
            keys(config, List.of("dash"), path + "/config");
            if (config.has("dash")) {
                JsonNode d = config.get("dash");
                if (!d.isArray() || d.size() < 2 || d.size() > 8) {
                    add(path + "/config/dash", "dash", "from 2 to 8 numbers, dash and gap alternately");
                } else {
                    for (int i = 0; i < d.size(); i++) {
                        number(d.get(i), 0.5, 64, path + "/config/dash/" + i);
                    }
                }
            }
        } else {
            keys(config, List.of(), path + "/config");
        }
        JsonNode style = s.get("style");
        if (style == null || !style.isObject()) {
            add(path + "/style", "required", "required");
            return;
        }
        Map<String, String> props = STYLE.get(kind);
        keys(style, List.copyOf(props.keySet()), path + "/style");
        props.forEach((name, what) -> {
            if (!style.has(name)) {
                add(path + "/style/" + name, "required", "required");
            } else {
                value(style.get(name), what, path + "/style/" + name);
            }
        });
    }

    private void label(JsonNode l, String path) {
        if (!l.isObject()) {
            add(path, "object", "an object");
            return;
        }
        keys(l, List.of("text", "color", "size", "halo"), path);
        JsonNode t = l.get("text");
        if (t != null && t.isTextual()) {
            text(t, 1, 200, path + "/text");
        } else if (t != null && t.isObject() && t.has("field")) {
            keys(t, List.of("field"), path + "/text");
            field(t.get("field"), path + "/text/field");
        } else if (t != null && t.isObject() && t.has("template")) {
            keys(t, List.of("template"), path + "/text");
            JsonNode x = t.get("template");
            if (!x.isTextual() || x.asText().isEmpty() || x.asText().length() > 200
                    || !TEMPLATE.matcher(x.asText()).matches()) {
                add(path + "/text/template", "template", "a text up to 200 characters whose {field} name attributes");
            }
        } else {
            add(path + "/text", "text", "a text, {\"field\": ...} or {\"template\": ...}");
        }
        for (String[] p : new String[][] {{"color", "color"}, {"size", "textSize"}}) {
            if (!l.has(p[0])) {
                add(path + "/" + p[0], "required", "required");
            } else {
                value(l.get(p[0]), p[1], path + "/" + p[0]);
            }
        }
        if (l.has("halo")) {
            JsonNode h = l.get("halo");
            if (!h.isObject()) {
                add(path + "/halo", "object", "an object");
            } else {
                keys(h, List.of("color", "width"), path + "/halo");
                constant(h.get("color"), "color", path + "/halo/color");
                number(h.get("width"), 0, 10, path + "/halo/width");
            }
        }
    }

    private void value(JsonNode v, String what, String path) {
        if (!v.isObject()) {
            constant(v, what, path);
            return;
        }
        keys(v, List.of("field", "categories", "otherwise", "graduated", "scaled"), path);
        field(v.get("field"), path + "/field");
        int drivers = (v.has("categories") ? 1 : 0) + (v.has("graduated") ? 1 : 0) + (v.has("scaled") ? 1 : 0);
        if (drivers > 1) {
            add(path, "driver", "one of categories, graduated and scaled");
        }
        if (v.has("otherwise") != v.has("categories")) {
            add(path, "driver", "categories and otherwise go together");
        }
        if (v.has("categories")) {
            JsonNode c = v.get("categories");
            if (!c.isArray() || c.isEmpty() || c.size() > 100) {
                add(path + "/categories", "count", "from 1 to 100 categories");
            } else {
                Set<String> seen = new HashSet<>();
                for (int i = 0; i < c.size(); i++) {
                    String p = path + "/categories/" + i;
                    JsonNode item = c.get(i);
                    if (!item.isObject()) {
                        add(p, "object", "an object");
                        continue;
                    }
                    keys(item, List.of("equals", "value", "label"), p);
                    JsonNode eq = item.get("equals");
                    if (eq == null || !(eq.isTextual() || eq.isNumber() || eq.isBoolean())) {
                        add(p + "/equals", "scalar", "a text, a number or true or false");
                    } else if (!seen.add(kindOf(eq) + ":" + scalar(eq))) {
                        add(p + "/equals", "duplicate", "another category has the same value");
                    }
                    if (!item.has("value")) {
                        add(p + "/value", "required", "required");
                    } else {
                        constant(item.get("value"), what, p + "/value");
                    }
                    if (item.has("label")) {
                        text(item.get("label"), 0, 100, p + "/label");
                    }
                }
            }
            if (v.has("otherwise")) {
                constant(v.get("otherwise"), what, path + "/otherwise");
            }
        }
        if (v.has("graduated")) {
            graduated(v.get("graduated"), what, path + "/graduated");
        }
        if (v.has("scaled")) {
            JsonNode s = v.get("scaled");
            String p = path + "/scaled";
            if (!s.isObject()) {
                add(p, "object", "an object");
            } else {
                keys(s, List.of("from", "to"), p);
                JsonNode f = s.get("from");
                if (f == null || !f.isArray() || f.size() != 2 || !f.get(0).isNumber() || !f.get(1).isNumber()
                        || !(f.get(0).asDouble() < f.get(1).asDouble())) {
                    add(p + "/from", "from", "two numbers, the first lower");
                }
                JsonNode to = s.get("to");
                if (to == null || !to.isArray() || to.size() != 2) {
                    add(p + "/to", "count", "two values");
                } else {
                    constant(to.get(0), what, p + "/to/0");
                    constant(to.get(1), what, p + "/to/1");
                }
            }
        }
    }

    private void graduated(JsonNode g, String what, String p) {
        if (!g.isObject()) {
            add(p, "object", "an object");
            return;
        }
        keys(g, List.of("breaks", "values", "labels"), p);
        JsonNode b = g.get("breaks");
        boolean numbers = b != null && b.isArray() && !b.isEmpty() && b.size() <= 50;
        if (numbers) {
            for (int i = 0; i < b.size(); i++) {
                numbers &= b.get(i).isNumber() && Double.isFinite(b.get(i).asDouble());
            }
        }
        if (!numbers) {
            add(p + "/breaks", "breaks", "from 1 to 50 numbers in increasing order");
        } else {
            for (int i = 1; i < b.size(); i++) {
                if (b.get(i).asDouble() <= b.get(i - 1).asDouble()) {
                    add(p + "/breaks", "breaks", "from 1 to 50 numbers in increasing order");
                    break;
                }
            }
        }
        int count = b != null && b.isArray() ? b.size() + 1 : 0;
        JsonNode values = g.get("values");
        if (values == null || !values.isArray() || values.size() != count) {
            add(p + "/values", "count", "one value per class: " + count);
        } else {
            for (int i = 0; i < values.size(); i++) {
                constant(values.get(i), what, p + "/values/" + i);
            }
        }
        if (g.has("labels")) {
            JsonNode labels = g.get("labels");
            if (!labels.isArray() || labels.size() != count) {
                add(p + "/labels", "count", "one label per class: " + count);
            } else {
                for (int i = 0; i < labels.size(); i++) {
                    text(labels.get(i), 0, 100, p + "/labels/" + i);
                }
            }
        }
    }

    private void constant(JsonNode v, String what, String path) {
        if (what.equals("color")) {
            if (v == null || !v.isTextual() || !COLOR.matcher(v.asText()).matches()) {
                add(path, "color", "a colour #rrggbb");
            }
            return;
        }
        double[] range = NUMBERS.get(what);
        number(v, range[0], range[1], path);
    }

    private void number(JsonNode v, double min, double max, String path) {
        if (v == null || !v.isNumber() || !Double.isFinite(v.asDouble()) || v.asDouble() < min || v.asDouble() > max) {
            add(path, "number", "a number from " + StyleDocument.num(min) + " to " + StyleDocument.num(max));
        }
    }

    private void field(JsonNode f, String path) {
        if (f == null || !f.isTextual() || !FIELD.matcher(f.asText()).matches()) {
            add(path, "field", "the name of an attribute: letters, digits and _, not starting with a digit");
        }
    }

    private void text(JsonNode t, int min, int max, String path) {
        if (t == null || !t.isTextual() || t.asText().length() < min || t.asText().length() > max) {
            add(path, "string", "a text up to " + max + " characters");
        }
    }

    private void keys(JsonNode object, List<String> allowed, String path) {
        for (Iterator<String> i = object.fieldNames(); i.hasNext(); ) {
            String k = i.next();
            if (!allowed.contains(k)) {
                add(path + "/" + k, "unknown", "not allowed here");
            }
        }
    }

    private void add(String path, String code, String message) {
        out.add(new Problem(path, code, message));
    }

    /** As JavaScript tells values apart: string, number, boolean. */
    private static String kindOf(JsonNode v) {
        return v.isTextual() ? "string" : v.isNumber() ? "number" : "boolean";
    }

    static String scalar(JsonNode v) {
        return v.isNumber() ? StyleDocument.num(v.asDouble()) : v.asText();
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }
}
