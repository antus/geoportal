// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.load;

import dev.geoportal.ext.catalog.geodata.load.Features.Form;
import dev.geoportal.ext.catalog.geodata.load.Features.Geometry;
import dev.geoportal.ext.catalog.geodata.load.LoadRefused.Code;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/**
 * KML, and KMZ through {@link Archives}: every Placemark of the document, in its folders, with its
 * name, description and extended data. Coordinates are WGS84 longitude, latitude and height by the
 * specification. The geometry is rewritten as plain KML for PostGIS, whatever version of KML the file
 * uses.
 */
final class KmlReader {

    private static final Set<String> GEOMETRIES = Set.of("Point", "LineString", "LinearRing", "Polygon", "MultiGeometry");
    private static final Set<String> PARTS = Set.of("Point", "LineString", "LinearRing", "Polygon", "MultiGeometry",
            "outerBoundaryIs", "innerBoundaryIs");

    private KmlReader() {}

    static Features read(byte[] content) {
        Element root = Text.xml(content).getDocumentElement();
        if (!"kml".equals(root.getLocalName())) {
            throw new LoadRefused(Code.UNREADABLE, "The file is not KML: its root is «" + root.getLocalName() + "».");
        }
        Features features = new Features(Crs.format());
        features.attribute("name", null).attribute("description", null);
        for (Element placemark : Text.descendants(root, "Placemark")) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("name", Text.text(Text.child(placemark, "name")));
            attributes.put("description", Text.text(Text.child(placemark, "description")));
            Element extended = Text.child(placemark, "ExtendedData");
            if (extended != null) {
                for (Element data : Text.descendants(extended, "Data")) {
                    attributes.put(data.getAttribute("name"), Text.guess(Text.text(Text.child(data, "value"))));
                }
                for (Element data : Text.descendants(extended, "SimpleData")) {
                    attributes.put(data.getAttribute("name"), Text.guess(Text.text(data)));
                }
            }
            Element geometry = null;
            for (Element child : Text.children(placemark)) {
                if (GEOMETRIES.contains(child.getLocalName())) {
                    geometry = child;
                    break;
                }
            }
            features.add(attributes, geometry == null ? null : Geometry.of(Form.KML, plain(geometry)));
        }
        return features;
    }

    /** The geometry as KML without namespaces, extrusion or altitude modes: what PostGIS reads. */
    static String plain(Element element) {
        StringBuilder out = new StringBuilder();
        write(element, out);
        return out.toString();
    }

    private static void write(Element element, StringBuilder out) {
        String name = element.getLocalName();
        out.append('<').append(name).append('>');
        if (name.equals("Point") || name.equals("LineString") || name.equals("LinearRing")) {
            Element coordinates = Text.child(element, "coordinates");
            out.append("<coordinates>").append(coordinates == null ? "" : escape(Text.text(coordinates)))
                    .append("</coordinates>");
        } else {
            for (Element child : Text.children(element)) {
                if (PARTS.contains(child.getLocalName())) {
                    write(child, out);
                }
            }
        }
        out.append("</").append(name).append('>');
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }
}
