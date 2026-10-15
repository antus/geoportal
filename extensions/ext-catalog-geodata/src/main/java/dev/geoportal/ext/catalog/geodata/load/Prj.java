// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.load;

import dev.geoportal.ext.catalog.geodata.load.LoadRefused.Code;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The EPSG code of a reference system written as WKT, as in a .prj or a GeoPackage: the
 * {@code AUTHORITY} of its root when it has one, else its name, compared with the names of the EPSG
 * systems PostGIS knows (the .prj of a shapefile writes {@code WGS_1984_UTM_Zone_33N} for
 * {@code WGS 84 / UTM zone 33N}). A name that matches nothing, or several systems of different kinds,
 * is refused: the system is never guessed (ADR-0015).
 */
final class Prj {

    private static final Pattern ROOT = Pattern.compile("^\\s*(PROJCS|GEOGCS|PROJCRS|GEOGCRS|GEODCRS|COMPD_CS|COMPOUNDCRS)\\s*\\[\\s*\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTHORITY = Pattern.compile(
            "(?:AUTHORITY\\s*\\[\\s*\"EPSG\"\\s*,\\s*\"?(\\d+)\"?\\s*\\]|ID\\s*\\[\\s*\"EPSG\"\\s*,\\s*(\\d+)\\s*\\])\\s*\\]\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** Names of the shapefile dialect of WKT that the normalization alone does not bring to the EPSG name. */
    private static final Map<String, Integer> SHAPEFILE_NAMES = Map.ofEntries(
            Map.entry("wgs84webmercatorauxiliarysphere", 3857),
            Map.entry("wgs84webmercator", 3857),
            Map.entry("montemarioitaly1", 3003),
            Map.entry("montemarioitaly2", 3004),
            Map.entry("montemariorome", 4806),
            Map.entry("etrs89", 4258),
            Map.entry("rdn2008", 6706),
            Map.entry("igm95", 4670));

    private Prj() {}

    /** The kind and name of the root of a WKT, normalized. */
    record Root(boolean projected, String name) {}

    static Optional<Root> root(String wkt) {
        Matcher m = ROOT.matcher(wkt);
        if (!m.find()) {
            return Optional.empty();
        }
        String kind = m.group(1).toUpperCase(Locale.ROOT);
        return Optional.of(new Root(kind.startsWith("PROJ"), normalize(m.group(2))));
    }

    /**
     * @param epsg the normalized names of the EPSG systems of PostGIS, each with the codes that bear it
     *     and whether they are projected ({@link #index})
     */
    static int identify(String wkt, Map<String, List<int[]>> epsg) {
        Matcher authority = AUTHORITY.matcher(wkt.strip());
        if (authority.find()) {
            return Integer.parseInt(authority.group(1) != null ? authority.group(1) : authority.group(2));
        }
        Root root = root(wkt).orElseThrow(() -> new LoadRefused(Code.CRS_UNKNOWN,
                "The reference system of the file is not WKT that the catalog reads: " + abbreviate(wkt) + "."));
        Integer alias = SHAPEFILE_NAMES.get(root.name());
        if (alias != null) {
            return alias;
        }
        List<int[]> candidates = epsg.getOrDefault(root.name(), List.of()).stream()
                .filter(c -> (c[1] == 1) == root.projected())
                .toList();
        if (candidates.isEmpty()) {
            throw new LoadRefused(Code.CRS_UNKNOWN, "The reference system of the file is not one the catalog recognizes: "
                    + abbreviate(wkt) + ". Declare its EPSG code.");
        }
        // Several systems with the same name (WGS 84 in 2D and 3D): the first code is the 2D one.
        return candidates.stream().mapToInt(c -> c[0]).min().getAsInt();
    }

    /** {@code WGS_1984_UTM_Zone_33N} and {@code WGS 84 / UTM zone 33N} both become {@code wgs84utmzone33n}. */
    static String normalize(String name) {
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (s.startsWith("gcs")) {
            s = s.substring(3);
        }
        s = s.replace("wgs1984", "wgs84").replace("etrs1989", "etrs89").replace("nad1983", "nad83")
                .replace("nad1927", "nad27").replace("ed1950", "ed50");
        return s;
    }

    static String abbreviate(String wkt) {
        String s = wkt.strip();
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }
}
