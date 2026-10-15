// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.load;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a reader found in a vector file (GP-937): the reference system the file declares, if any, the
 * names of the attributes in the order of the file, and the features. Geometries stay in the form the
 * file has them (GeoJSON, WKT, WKB, GML, KML), and PostGIS parses them: one parser for every path.
 */
final class Features {

    /** The form of a geometry, which names the PostGIS function that reads it. */
    enum Form {
        GEOJSON, WKT, WKB, GML, KML
    }

    /**
     * A geometry as the file has it.
     *
     * @param authorityOrder the coordinates follow the axis order of the authority of the reference
     *     system (latitude first for EPSG:4326, as GML 3 with a URN writes them): they are swapped when
     *     that order puts north first
     */
    record Geometry(Form form, Object value, boolean authorityOrder) {

        Geometry {
            Objects.requireNonNull(form, "form");
            Objects.requireNonNull(value, "value");
        }

        static Geometry of(Form form, Object value) {
            return new Geometry(form, value, false);
        }
    }

    /** A feature: its attributes, by name, and its geometry, {@code null} when it has none. */
    record Feature(Map<String, Object> attributes, Geometry geometry) {}

    private final Crs crs;
    private String prj;
    private final List<String> names = new ArrayList<>();
    private final Map<String, Column.Type> declared = new LinkedHashMap<>();
    private final List<Feature> features = new ArrayList<>();

    /** @param crs what the file declares, {@code null} when it declares nothing */
    Features(Crs crs) {
        this.crs = crs;
    }

    /** Names an attribute, with its type when the file declares one ({@code null} otherwise). */
    Features attribute(String name, Column.Type type) {
        if (!names.contains(name)) {
            names.add(name);
        }
        if (type != null) {
            declared.put(name, type);
        }
        return this;
    }

    Features add(Map<String, Object> attributes, Geometry geometry) {
        attributes.keySet().forEach(n -> attribute(n, null));
        features.add(new Feature(attributes, geometry));
        return this;
    }

    Crs crs() {
        return crs;
    }

    /** The WKT of a .prj that states the system without an EPSG code, for {@link Prj} to identify. */
    Features prj(String wkt) {
        this.prj = wkt;
        return this;
    }

    String prj() {
        return prj;
    }

    List<String> names() {
        return names;
    }

    Column.Type declaredType(String name) {
        return declared.get(name);
    }

    List<Feature> list() {
        return features;
    }

    /** The same features with another declared reference system. */
    Features withCrs(Crs other) {
        Features copy = new Features(other);
        copy.prj = other == null ? prj : null;
        copy.names.addAll(names);
        copy.declared.putAll(declared);
        copy.features.addAll(features);
        return copy;
    }
}
