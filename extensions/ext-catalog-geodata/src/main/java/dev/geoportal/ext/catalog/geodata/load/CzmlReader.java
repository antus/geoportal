// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.ext.catalog.geodata.load.Features.Form;
import dev.geoportal.ext.catalog.geodata.load.Features.Geometry;
import dev.geoportal.ext.catalog.geodata.load.LoadRefused.Code;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CZML, the document of Cesium: each packet with a position, a polyline or a polygon becomes a
 * feature with its id, name and description. Positions in cartographic degrees or radians are WGS84
 * by the specification; Cartesian positions (metres from the centre of the Earth) are converted to
 * WGS84 longitude, latitude and height. A position sampled in time becomes the line of its samples.
 */
final class CzmlReader {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double A = 6378137.0;
    private static final double F = 1 / 298.257223563;
    private static final double E2 = F * (2 - F);

    private CzmlReader() {}

    static Features read(byte[] content) {
        JsonNode root;
        try {
            root = JSON.readTree(Text.decode(content));
        } catch (IOException e) {
            throw new LoadRefused(Code.UNREADABLE, "The file is not valid JSON: " + e.getMessage());
        }
        if (root == null || !root.isArray()) {
            throw new LoadRefused(Code.UNREADABLE, "The file is not CZML: a CZML document is an array of packets.");
        }
        Features features = new Features(Crs.format());
        features.attribute("czml_id", null).attribute("name", null).attribute("description", null);
        for (JsonNode packet : root) {
            if ("document".equals(packet.path("id").asText())) {
                continue;
            }
            String wkt = null;
            if (packet.has("polygon")) {
                List<double[]> ring = points(positions(packet.path("polygon").path("positions")), false);
                if (ring.size() >= 3) {
                    if (!same(ring.get(0), ring.get(ring.size() - 1))) {
                        ring.add(ring.get(0));
                    }
                    wkt = "POLYGON Z((" + join(ring) + "))";
                }
            } else if (packet.has("polyline")) {
                List<double[]> line = points(positions(packet.path("polyline").path("positions")), false);
                if (line.size() >= 2) {
                    wkt = "LINESTRING Z(" + join(line) + ")";
                }
            } else if (packet.has("position")) {
                JsonNode position = packet.get("position");
                if (position.isArray() && !position.isEmpty() && position.get(0).isObject()) {
                    position = position.get(0);
                }
                Coordinates c = coordinates(position);
                if (c != null) {
                    // A constant position has three values; a sampled one, time and three values each.
                    List<double[]> points = points(c, c.values().size() != 3);
                    if (points.size() == 1) {
                        wkt = "POINT Z(" + join(points) + ")";
                    } else if (points.size() > 1) {
                        wkt = "LINESTRING Z(" + join(points) + ")";
                    }
                }
            }
            if (wkt == null) {
                continue;
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("czml_id", packet.path("id").asText(null));
            attributes.put("name", text(packet.get("name")));
            attributes.put("description", text(packet.get("description")));
            features.add(attributes, Geometry.of(Form.WKT, wkt));
        }
        return features;
    }

    /** The values of a position and their kind. */
    private record Coordinates(String kind, List<Double> values) {}

    private static Coordinates positions(JsonNode positions) {
        if (positions.isArray() && !positions.isEmpty() && positions.get(0).isObject()) {
            positions = positions.get(0);
        }
        return coordinates(positions);
    }

    private static Coordinates coordinates(JsonNode node) {
        for (String kind : new String[] {"cartographicDegrees", "cartographicRadians", "cartesian"}) {
            JsonNode values = node.get(kind);
            if (values != null && values.isArray()) {
                List<Double> list = new ArrayList<>();
                for (JsonNode v : values) {
                    if (!v.isNumber()) {
                        return null; // ISO dates as times are not supported
                    }
                    list.add(v.doubleValue());
                }
                return new Coordinates(kind, list);
            }
        }
        return null;
    }

    /** The points, longitude latitude height, dropping the time of sampled positions. */
    private static List<double[]> points(Coordinates c, boolean sampled) {
        List<double[]> points = new ArrayList<>();
        if (c == null) {
            return points;
        }
        int stride = sampled ? 4 : 3;
        List<Double> v = c.values();
        for (int i = 0; i + stride <= v.size(); i += stride) {
            int o = sampled ? i + 1 : i;
            double[] p = {v.get(o), v.get(o + 1), v.get(o + 2)};
            points.add(switch (c.kind()) {
                case "cartographicRadians" -> new double[] {Math.toDegrees(p[0]), Math.toDegrees(p[1]), p[2]};
                case "cartesian" -> geodetic(p[0], p[1], p[2]);
                default -> p;
            });
        }
        return points;
    }

    /** WGS84 longitude, latitude and height of a point in metres from the centre of the Earth. */
    static double[] geodetic(double x, double y, double z) {
        double lon = Math.atan2(y, x);
        double p = Math.hypot(x, y);
        double lat = Math.atan2(z, p * (1 - E2));
        double h = 0;
        for (int i = 0; i < 10; i++) {
            double sin = Math.sin(lat);
            double n = A / Math.sqrt(1 - E2 * sin * sin);
            h = p / Math.cos(lat) - n;
            lat = Math.atan2(z, p * (1 - E2 * n / (n + h)));
        }
        return new double[] {Math.toDegrees(lon), Math.toDegrees(lat), h};
    }

    private static boolean same(double[] a, double[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    private static String join(List<double[]> points) {
        List<String> parts = new ArrayList<>();
        for (double[] p : points) {
            parts.add(p[0] + " " + p[1] + " " + p[2]);
        }
        return String.join(",", parts);
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject() && node.has("string")) {
            return node.get("string").asText();
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }
}
