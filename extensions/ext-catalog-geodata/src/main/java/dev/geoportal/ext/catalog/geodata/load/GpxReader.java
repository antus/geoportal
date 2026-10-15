// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.load;

import dev.geoportal.ext.catalog.geodata.load.Features.Form;
import dev.geoportal.ext.catalog.geodata.load.Features.Geometry;
import dev.geoportal.ext.catalog.geodata.load.LoadRefused.Code;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/**
 * GPX 1.0 and 1.1: waypoints become points, routes lines and tracks multilines, in one table with
 * the column {@code kind}; the elevation is the Z. Coordinates are WGS84 by the specification.
 */
final class GpxReader {

    private static final Set<String> TEXTS = Set.of("name", "desc", "cmt", "type", "time", "sym", "src");

    private GpxReader() {}

    static Features read(byte[] content) {
        Element root = Text.xml(content).getDocumentElement();
        if (!"gpx".equals(root.getLocalName())) {
            throw new LoadRefused(Code.UNREADABLE, "The file is not GPX: its root is «" + root.getLocalName() + "».");
        }
        Features features = new Features(Crs.format());
        features.attribute("kind", null).attribute("name", null);
        for (Element wpt : Text.children(root)) {
            switch (wpt.getLocalName()) {
                case "wpt" -> {
                    String point = point(wpt);
                    features.add(attributes("waypoint", wpt), point == null ? null : wkt("POINT", "(" + point + ")", wpt));
                }
                case "rte" -> {
                    String line = line(Text.children(wpt).stream().filter(e -> e.getLocalName().equals("rtept")).toList());
                    features.add(attributes("route", wpt), line == null ? null : Geometry.of(Form.WKT, "LINESTRING" + z(line) + line));
                }
                case "trk" -> {
                    List<String> lines = new ArrayList<>();
                    for (Element segment : Text.children(wpt)) {
                        if (segment.getLocalName().equals("trkseg")) {
                            String line = line(Text.children(segment).stream()
                                    .filter(e -> e.getLocalName().equals("trkpt")).toList());
                            if (line != null) {
                                lines.add(line);
                            }
                        }
                    }
                    features.add(attributes("track", wpt), lines.isEmpty() ? null
                            : Geometry.of(Form.WKT, "MULTILINESTRING" + z(lines.get(0)) + "(" + String.join(",", lines) + ")"));
                }
                default -> {
                    // metadata, extensions of the file
                }
            }
        }
        return features;
    }

    private static Map<String, Object> attributes(String kind, Element element) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", kind);
        for (Element child : Text.children(element)) {
            String name = child.getLocalName();
            if (TEXTS.contains(name)) {
                values.put(name, Text.guess(Text.text(child)));
            } else if (name.equals("extensions")) {
                for (Element extension : Text.children(child)) {
                    if (Text.children(extension).isEmpty()) {
                        values.put(extension.getLocalName(), Text.guess(Text.text(extension)));
                    }
                }
            }
        }
        return values;
    }

    private static Geometry wkt(String type, String coordinates, Element point) {
        return Geometry.of(Form.WKT, type + (Text.child(point, "ele") != null ? " Z" : "") + coordinates);
    }

    /** {@code lon lat [ele]}, or {@code null} without coordinates. */
    private static String point(Element point) {
        try {
            double lat = Double.parseDouble(point.getAttribute("lat").strip());
            double lon = Double.parseDouble(point.getAttribute("lon").strip());
            String ele = Text.text(Text.child(point, "ele"));
            return lon + " " + lat + (ele != null ? " " + Double.parseDouble(ele) : "");
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code (p, p, ...)} with Z when the first point has it, all with the same dimension. */
    private static String line(List<Element> points) {
        if (points.size() < 2) {
            return null;
        }
        boolean z = Text.child(points.get(0), "ele") != null;
        List<String> coordinates = new ArrayList<>();
        for (Element p : points) {
            String c = point(p);
            if (c == null) {
                throw new LoadRefused(Code.UNREADABLE, "A point of the file has no valid lat and lon.");
            }
            String[] parts = c.split(" ");
            coordinates.add(parts[0] + " " + parts[1] + (z ? " " + (parts.length > 2 ? parts[2] : "0") : ""));
        }
        return "(" + String.join(",", coordinates) + ")";
    }

    private static String z(String line) {
        return line.substring(1, line.indexOf(',')).split(" ").length > 2 ? " Z" : "";
    }
}
