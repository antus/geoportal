// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.style;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.geoportal.ext.catalog.geodata.style.InvalidStyleException.Problem;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * A style of Geoportal (GP-009): a valid document of the grammar {@code geoportal/style/v1}, with the
 * blocks type, config, style, label and legend, and values driven by the data. Documented in {@code
 * docs/style/README.md}, with its JSON Schema. The catalog keeps it next to the SLD it translates it
 * into ({@link #sld}), and translates it again when the layer changes data; the editor of the
 * publishing dialog is a facade over it.
 *
 * <p>Immutable: {@link #json()} and {@link #tree()} give copies.
 */
public final class StyleDocument {

    public static final String GRAMMAR = "geoportal/style/v1";

    static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectNode tree;

    private StyleDocument(ObjectNode tree) {
        this.tree = tree;
    }

    /**
     * A document of the grammar, checked; a style written before the grammar (the six values of GP-114,
     * {@code fill, fillOpacity, stroke, strokeWidth, pointSize, pointShape}) is migrated into it.
     *
     * @throws InvalidStyleException with what is wrong
     */
    public static StyleDocument of(JsonNode node) {
        if (legacy(node)) {
            try {
                return fromLegacy(JSON.treeToValue(node, LegacyStyle.class));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                throw new InvalidStyleException(List.of(new Problem("", "legacy",
                        "a style of the six values of before the grammar, not valid: " + reason(e))));
            }
        }
        List<Problem> problems = StyleValidator.problems(node);
        if (!problems.isEmpty()) {
            throw new InvalidStyleException(problems);
        }
        return new StyleDocument(((ObjectNode) node).deepCopy());
    }

    /** A document from its JSON text. */
    public static StyleDocument parse(String json) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new InvalidStyleException(List.of(new Problem("", "json", "not JSON: " + e.getOriginalMessage())));
        }
        return of(node);
    }

    /** Whether a JSON object is a style of before the grammar: no grammar, and the values of GP-114. */
    static boolean legacy(JsonNode node) {
        return node != null && node.isObject() && !node.has("grammar") && node.has("fill") && node.has("pointShape");
    }

    /**
     * The document of a style of before the grammar: mixed, so that each of its six values is kept and
     * each geometry is drawn as it was (the SLD is the same).
     */
    public static StyleDocument fromLegacy(LegacyStyle s) {
        ObjectNode doc = JSON.createObjectNode().put("grammar", GRAMMAR).put("type", "mixed");
        ObjectNode g = doc.putObject("geometries");
        ObjectNode point = g.putObject("point");
        point.putObject("config").put("shape", s.pointShape());
        point.putObject("style").put("fill", s.fill()).put("fillOpacity", s.fillOpacity()).put("stroke", s.stroke())
                .put("strokeWidth", s.strokeWidth()).put("size", s.pointSize());
        ObjectNode line = g.putObject("line");
        line.putObject("config");
        line.putObject("style").put("stroke", s.stroke()).put("strokeOpacity", 1).put("strokeWidth", s.strokeWidth());
        ObjectNode polygon = g.putObject("polygon");
        polygon.putObject("config");
        polygon.putObject("style").put("fill", s.fill()).put("fillOpacity", s.fillOpacity()).put("stroke", s.stroke())
                .put("strokeWidth", s.strokeWidth());
        return of(normalized(doc));
    }

    /** The style a layer gets when nobody chose one: the blue of before the grammar, for every geometry. */
    public static StyleDocument defaults() {
        return fromLegacy(LegacyStyle.DEFAULT);
    }

    public String type() {
        return tree.get("type").asText();
    }

    /** A copy of the document. */
    public ObjectNode tree() {
        return tree.deepCopy();
    }

    /** The document as JSON, as the catalog keeps it. */
    public String json() {
        try {
            return JSON.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The label block, or null. */
    JsonNode label() {
        return tree.get("label");
    }

    /** The legend block, or null. */
    JsonNode legend() {
        return tree.get("legend");
    }

    /**
     * The SLD 1.0 of this style for a layer of the given geometry, as GeoServer receives it: the
     * symbolizer of the geometry of the data, or a rule per geometry for a layer of mixed geometries.
     *
     * @param name the name of the style in the workspace
     * @param geometryType as the loading records it: Point, MultiLineString, Geometry, ...
     */
    public String sld(String name, String geometryType) {
        return SldStyle.of(this, name, kind(geometryType));
    }

    /** point, line, polygon or mixed, from the geometry type the loading recorded. */
    public static String kind(String geometryType) {
        String g = geometryType == null ? "" : geometryType.toLowerCase(Locale.ROOT);
        if (g.contains("point")) {
            return "point";
        }
        if (g.contains("line") || g.contains("curve")) {
            return "line";
        }
        if (g.contains("polygon") || g.contains("surface")) {
            return "polygon";
        }
        return "mixed";
    }

    // Types ------------------------------------------------------------------------------------------

    /**
     * The document with another type: colour, opacity, label and legend stay, a point size becomes a
     * line width and back; to mixed, each geometry gets the symbol of its own; from mixed, the symbol of
     * the geometry asked for.
     */
    public StyleDocument retype(String type) {
        if (!StyleValidator.TYPES.contains(type)) {
            throw new IllegalArgumentException("Unknown type " + type);
        }
        ObjectNode out = JSON.createObjectNode();
        if (tree.has("$schema")) {
            out.set("$schema", tree.get("$schema").deepCopy());
        }
        out.put("grammar", GRAMMAR).put("type", type);
        if (type.equals("mixed")) {
            ObjectNode g = out.putObject("geometries");
            for (String k : StyleValidator.KINDS) {
                g.set(k, symbol(k));
            }
        } else {
            ObjectNode s = symbol(type);
            out.set("config", s.get("config"));
            out.set("style", s.get("style"));
        }
        if (tree.has("label")) {
            out.set("label", tree.get("label").deepCopy());
        }
        if (tree.has("legend")) {
            out.set("legend", tree.get("legend").deepCopy());
        }
        return of(out);
    }

    /** The symbol ({config, style}) that draws the features of one geometry kind with this document. */
    public ObjectNode symbol(String kind) {
        if (type().equals("mixed")) {
            return ((ObjectNode) tree.get("geometries").get(kind)).deepCopy();
        }
        return retypeSymbol(type(), kind, tree);
    }

    private static ObjectNode retypeSymbol(String from, String to, JsonNode s) {
        ObjectNode out = JSON.createObjectNode();
        if (from.equals(to)) {
            out.set("config", s.get("config").deepCopy());
            out.set("style", s.get("style").deepCopy());
            return out;
        }
        JsonNode st = s.get("style");
        // The colour, the opacity and the weight of a symbol: what its type draws first.
        JsonNode color = st.get(from.equals("line") ? "stroke" : "fill").deepCopy();
        JsonNode opacity = st.get(from.equals("line") ? "strokeOpacity" : "fillOpacity").deepCopy();
        JsonNode weight = st.get(from.equals("point") ? "size" : "strokeWidth").deepCopy();
        ObjectNode style = JSON.createObjectNode();
        switch (to) {
            case "line" -> {
                out.putObject("config");
                style.set("stroke", color);
                style.set("strokeOpacity", opacity);
                style.set("strokeWidth", mapNumbers(weight, n -> Math.min(64, Math.max(0, n))));
            }
            case "point" -> {
                out.putObject("config").put("shape", "circle");
                style.set("fill", color);
                style.set("fillOpacity", opacity);
                if (from.equals("line")) {
                    style.set("stroke", color.deepCopy());
                    style.put("strokeWidth", 1);
                    style.set("size", mapNumbers(weight, n -> Math.min(64, Math.max(1, n))));
                } else {
                    style.set("stroke", st.get("stroke").deepCopy());
                    style.set("strokeWidth", st.get("strokeWidth").deepCopy());
                    style.put("size", 8);
                }
            }
            default -> {
                out.putObject("config");
                style.set("fill", color);
                style.set("fillOpacity", opacity);
                if (from.equals("line")) {
                    style.set("stroke", color.deepCopy());
                    style.set("strokeWidth", weight);
                } else {
                    style.set("stroke", st.get("stroke").deepCopy());
                    style.set("strokeWidth", st.get("strokeWidth").deepCopy());
                }
            }
        }
        out.set("style", style);
        return out;
    }

    /** Applies fn to every number a value can give: the constant, or each output of its driver. */
    private static JsonNode mapNumbers(JsonNode v, DoubleUnaryOperator fn) {
        if (v.isNumber()) {
            return number(fn.applyAsDouble(v.asDouble()));
        }
        if (!v.isObject()) {
            return v;
        }
        ObjectNode r = ((ObjectNode) v).deepCopy();
        if (r.has("categories")) {
            for (JsonNode c : r.get("categories")) {
                ((ObjectNode) c).set("value", mapNumbers(c.get("value"), fn));
            }
            r.set("otherwise", mapNumbers(r.get("otherwise"), fn));
        }
        if (r.has("graduated")) {
            ArrayNode values = (ArrayNode) r.get("graduated").get("values");
            for (int i = 0; i < values.size(); i++) {
                values.set(i, mapNumbers(values.get(i), fn));
            }
        }
        if (r.has("scaled")) {
            ArrayNode to = (ArrayNode) r.get("scaled").get("to");
            for (int i = 0; i < to.size(); i++) {
                to.set(i, mapNumbers(to.get(i), fn));
            }
        }
        return r;
    }

    // Numbers ----------------------------------------------------------------------------------------

    /** A number as JSON: whole numbers without decimals, as JavaScript writes them. */
    static JsonNode number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15
                ? JSON.getNodeFactory().numberNode((long) value)
                : JSON.getNodeFactory().numberNode(value);
    }

    /** The numbers of a tree written as JavaScript writes them (8, not 8.0), so that JSON compares alike. */
    static JsonNode normalized(JsonNode node) {
        if (node.isNumber()) {
            return number(node.asDouble());
        }
        if (node.isObject()) {
            ObjectNode o = JSON.createObjectNode();
            node.properties().forEach(e -> o.set(e.getKey(), normalized(e.getValue())));
            return o;
        }
        if (node.isArray()) {
            ArrayNode a = JSON.createArrayNode();
            node.forEach(x -> a.add(normalized(x)));
            return a;
        }
        return node.deepCopy();
    }

    /** A number as SLD and messages write it: 8, 0.45, 1.5. */
    static String num(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        String s = String.format(Locale.ROOT, "%.6f", value);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private static String reason(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StyleDocument d && normalized(d.tree).equals(normalized(tree));
    }

    @Override
    public int hashCode() {
        return normalized(tree).hashCode();
    }

    @Override
    public String toString() {
        return json();
    }
}
