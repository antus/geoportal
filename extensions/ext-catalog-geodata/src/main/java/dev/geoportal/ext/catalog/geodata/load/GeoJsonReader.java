// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.load;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.ext.catalog.geodata.load.Features.Form;
import dev.geoportal.ext.catalog.geodata.load.Features.Geometry;
import dev.geoportal.ext.catalog.geodata.load.LoadRefused.Code;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GeoJSON: a FeatureCollection, a Feature or a geometry. RFC 7946 coordinates are WGS84
 * longitude/latitude; a file of the 2008 specification may name another system in its {@code crs}
 * member, which is kept: the Importer of GeoServer ignored it (prototypes/f01, proof 6).
 */
final class GeoJsonReader {

    private static final ObjectMapper JSON = new ObjectMapper();

    private GeoJsonReader() {}

    static Features read(byte[] content) {
        JsonNode root;
        try {
            root = JSON.readTree(Text.decode(content));
        } catch (JacksonException e) {
            throw new LoadRefused(Code.UNREADABLE, "The file is not valid JSON: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new LoadRefused(Code.UNREADABLE, "The file cannot be read: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new LoadRefused(Code.UNREADABLE, "The file is not a GeoJSON object.");
        }
        Features features = new Features(crs(root.get("crs")));
        String type = root.path("type").asText("");
        switch (type) {
            case "FeatureCollection" -> {
                for (JsonNode feature : root.path("features")) {
                    feature(features, feature);
                }
            }
            case "Feature" -> feature(features, root);
            case "Point", "MultiPoint", "LineString", "MultiLineString", "Polygon", "MultiPolygon", "GeometryCollection" ->
                features.add(new LinkedHashMap<>(), Geometry.of(Form.GEOJSON, root.toString()));
            default -> throw new LoadRefused(Code.UNREADABLE, "The file is not GeoJSON: its type is «" + type + "».");
        }
        return features;
    }

    private static void feature(Features features, JsonNode feature) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (feature.hasNonNull("id")) {
            attributes.put("id", value(feature.get("id")));
        }
        Iterator<Map.Entry<String, JsonNode>> properties = feature.path("properties").properties().iterator();
        while (properties.hasNext()) {
            Map.Entry<String, JsonNode> p = properties.next();
            attributes.put(p.getKey(), value(p.getValue()));
        }
        JsonNode geometry = feature.get("geometry");
        features.add(attributes, geometry == null || geometry.isNull() ? null : Geometry.of(Form.GEOJSON, geometry.toString()));
    }

    /**
     * The reference system of a {@code crs} member: named, as EPSG of the 2008 drafts, or a bare name.
     * Its coordinates are longitude first whatever the name: GeoJSON never followed the axis order of
     * the authority.
     */
    static Crs crs(JsonNode crs) {
        if (crs == null || crs.isNull()) {
            return Crs.format();
        }
        JsonNode properties = crs.path("properties");
        String name = crs.isTextual() ? crs.textValue() // "crs": "EPSG:4326", as some producers write it
                : properties.has("name") ? properties.path("name").asText()
                : properties.has("code") ? "EPSG:" + properties.path("code").asText() : null;
        return Crs.parse(name)
                .map(n -> Crs.file(n.srid()))
                .orElseThrow(() -> new LoadRefused(Code.CRS_UNKNOWN,
                        "The reference system named by the file is not known: " + (name == null ? crs.toString() : name) + "."));
    }

    static Object value(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.longValue();
        }
        if (node.isNumber()) {
            return node.doubleValue();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        return node.toString();
    }
}
