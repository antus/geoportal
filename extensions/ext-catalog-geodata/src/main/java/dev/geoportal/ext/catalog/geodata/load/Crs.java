// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.load;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reference system of a dataset (GP-324): an EPSG code and where it comes from. It is never
 * guessed: either the file states it, or its format defines it (GeoJSON, KML, GPX and CZML are
 * WGS84 longitude/latitude by specification), or the person declared it for a file that states none
 * (GP-326).
 *
 * @param srid the EPSG code, which is also the SRID of PostGIS
 * @param source where it comes from
 */
public record Crs(int srid, Source source) {

    /** Where a reference system comes from. */
    public enum Source {
        /** Stated by the file: a {@code crs} member, a {@code srsName}, a .prj, a GeoPackage, a GeoTIFF. */
        FILE,
        /** Defined by the specification of the format. */
        FORMAT,
        /** Declared by the person, for a file that states none. */
        DECLARED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** WGS84 longitude/latitude, as GeoJSON, KML, GPX and CZML define their coordinates. */
    static final int WGS84 = 4326;

    public Crs {
        if (srid <= 0) {
            throw new IllegalArgumentException("not an EPSG code: " + srid);
        }
    }

    static Crs format() {
        return new Crs(WGS84, Source.FORMAT);
    }

    static Crs file(int srid) {
        return new Crs(srid, Source.FILE);
    }

    public String code() {
        return "EPSG:" + srid;
    }

    /**
     * A name of a reference system as files and services write it, and whether its coordinates come in
     * the order of the authority (latitude first for EPSG:4326) or as longitude/easting first.
     */
    record Name(int srid, boolean authorityOrder) {}

    private static final Pattern URN = Pattern.compile("^urn:(?:x-)?ogc:def:crs:epsg:[0-9.]*:(\\d+)$");
    private static final Pattern HTTP = Pattern.compile("^https?://www\\.opengis\\.net/def/crs/epsg/[0-9.]+/(\\d+)$");
    private static final Pattern GML_SRS = Pattern.compile("^https?://www\\.opengis\\.net/gml/srs/epsg\\.xml#(\\d+)$");
    private static final Pattern SHORT = Pattern.compile("^epsg:+(\\d+)$");
    private static final Pattern CRS84 = Pattern.compile(
            "^(?:urn:ogc:def:crs:ogc:[0-9.]*:crs84|https?://www\\.opengis\\.net/def/crs/ogc/[0-9.]+/crs84|(?:ogc:)?crs84)$");

    /**
     * Reads a name such as {@code EPSG:32633}, {@code urn:ogc:def:crs:EPSG::4326},
     * {@code http://www.opengis.net/def/crs/EPSG/0/4326} or {@code CRS84}. The URN and URI forms follow
     * the axis order of the authority; {@code EPSG:n} and the old GML form are longitude first.
     */
    static Optional<Name> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String s = text.strip().toLowerCase(Locale.ROOT);
        if (CRS84.matcher(s).matches()) {
            return Optional.of(new Name(WGS84, false));
        }
        Matcher m = URN.matcher(s);
        if (m.matches()) {
            return code(m.group(1), true);
        }
        m = HTTP.matcher(s);
        if (m.matches()) {
            return code(m.group(1), true);
        }
        m = GML_SRS.matcher(s);
        if (m.matches()) {
            return code(m.group(1), false);
        }
        m = SHORT.matcher(s);
        if (m.matches()) {
            return code(m.group(1), false);
        }
        return Optional.empty();
    }

    /** A code the person declares: {@code EPSG:32632} or {@code 32632}. */
    public static Optional<Integer> declared(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String s = text.strip();
        if (s.matches("\\d{4,6}")) {
            return Optional.of(Integer.parseInt(s));
        }
        return parse(s).map(Name::srid);
    }

    private static Optional<Name> code(String digits, boolean authorityOrder) {
        try {
            int srid = Integer.parseInt(digits);
            return srid > 0 ? Optional.of(new Name(srid, authorityOrder)) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
