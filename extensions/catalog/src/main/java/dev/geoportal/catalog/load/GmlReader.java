// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.load;

import dev.geoportal.catalog.load.Features.Form;
import dev.geoportal.catalog.load.Features.Geometry;
import dev.geoportal.catalog.load.LoadRefused.Code;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * GML 2, 3 and 3.2 feature collections (also WFS answers): each member is a feature, its simple
 * properties are attributes and its first geometry property is the geometry. The reference system
 * is the {@code srsName} of the geometries or of the collection; a URN or an HTTP URI puts the
 * coordinates in the order of the authority (latitude first for EPSG:4326), which the writer swaps
 * back. The geometry goes to PostGIS without {@code srsName}, so that PostGIS swaps nothing itself.
 */
final class GmlReader {

    private static final Set<String> MEMBERS = Set.of("featureMember", "member");
    private static final Set<String> GEOMETRIES = Set.of("Point", "LineString", "LinearRing", "Polygon", "MultiPoint",
            "MultiLineString", "MultiPolygon", "MultiGeometry", "MultiCurve", "MultiSurface", "Curve", "Surface",
            "CompositeCurve", "CompositeSurface", "Box", "Envelope");
    private static final Pattern SRS_NAME = Pattern.compile("\\s+srsName=\"[^\"]*\"");

    private GmlReader() {}

    static Features read(byte[] content) {
        Element root = Text.xml(content).getDocumentElement();
        List<Element> members = new ArrayList<>();
        collect(root, members);
        if (members.isEmpty() && !isGml(root)) {
            throw new LoadRefused(Code.UNREADABLE, "The file is not a GML feature collection: no featureMember or member.");
        }
        String srsName = null;
        List<Map<String, Object>> attributes = new ArrayList<>();
        List<Element> geometries = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Element feature : members) {
            Map<String, Object> values = new LinkedHashMap<>();
            Element geometry = null;
            for (Element property : Text.children(feature)) {
                String name = property.getLocalName();
                if (name.equals("boundedBy") || name.equals("location") && isGml(property)) {
                    continue;
                }
                Element first = Text.children(property).stream().findFirst().orElse(null);
                if (first != null && isGml(first) && GEOMETRIES.contains(first.getLocalName())) {
                    if (geometry == null) {
                        geometry = first;
                    }
                    continue;
                }
                if (!names.contains(name)) {
                    names.add(name);
                }
                values.put(name, Text.guess(Text.text(property)));
            }
            if (geometry != null) {
                String srs = srsName(geometry);
                if (srs != null) {
                    if (srsName != null && !srsName.equals(srs)) {
                        throw new LoadRefused(Code.CRS_MISMATCH, "The geometries of the file are in different reference systems: "
                                + srsName + " and " + srs + ".");
                    }
                    srsName = srs;
                }
            }
            attributes.add(values);
            geometries.add(geometry);
        }
        if (srsName == null) {
            srsName = collectionSrsName(root);
        }
        Crs crs = null;
        boolean authorityOrder = false;
        if (srsName != null) {
            String named = srsName;
            Crs.Name name = Crs.parse(srsName).orElseThrow(() -> new LoadRefused(Code.CRS_UNKNOWN,
                    "The reference system named by the file is not known: " + named + "."));
            crs = Crs.file(name.srid());
            authorityOrder = name.authorityOrder();
        }
        Features features = new Features(crs);
        names.forEach(n -> features.attribute(n, null));
        for (int i = 0; i < attributes.size(); i++) {
            Element g = geometries.get(i);
            features.add(attributes.get(i), g == null ? null
                    : new Geometry(Form.GML, SRS_NAME.matcher(Text.serialize(g)).replaceAll(""), authorityOrder));
        }
        return features;
    }

    private static void collect(Element element, List<Element> members) {
        for (Element child : Text.children(element)) {
            String name = child.getLocalName();
            if (MEMBERS.contains(name)) {
                Text.children(child).stream().findFirst().ifPresent(members::add);
            } else if (name.equals("featureMembers")) {
                members.addAll(Text.children(child));
            } else if (name.endsWith("FeatureCollection") || name.equals("additionalObjects")) {
                collect(child, members);
            }
        }
    }

    private static boolean isGml(Element e) {
        String ns = e.getNamespaceURI();
        return ns != null && ns.startsWith("http://www.opengis.net/gml");
    }

    /** The srsName of a geometry, of one of its parts, or of the geometry it is part of. */
    private static String srsName(Element geometry) {
        if (geometry.hasAttribute("srsName")) {
            return geometry.getAttribute("srsName");
        }
        for (Node n = geometry.getParentNode(); n instanceof Element e; n = n.getParentNode()) {
            if (e.hasAttribute("srsName")) {
                return e.getAttribute("srsName");
            }
        }
        var nodes = geometry.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            if (e.hasAttribute("srsName")) {
                return e.getAttribute("srsName");
            }
        }
        return null;
    }

    private static String collectionSrsName(Element root) {
        Element bounded = Text.child(root, "boundedBy");
        if (bounded == null) {
            return null;
        }
        for (Element e : Text.children(bounded)) {
            if (e.hasAttribute("srsName")) {
                return e.getAttribute("srsName");
            }
        }
        return null;
    }
}
