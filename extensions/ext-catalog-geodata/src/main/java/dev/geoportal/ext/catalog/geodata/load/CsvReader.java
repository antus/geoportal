// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.load;

import dev.geoportal.ext.catalog.geodata.load.Features.Form;
import dev.geoportal.ext.catalog.geodata.load.Features.Geometry;
import dev.geoportal.ext.catalog.geodata.load.LoadRefused.Code;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSV with any of the separators {@code , ; TAB |}, quoted fields, a BOM and CRLF, and decimal commas
 * when the separator is not a comma. The geometry is a WKT column, a GeoJSON column, or two columns
 * of coordinates:
 *
 * <ul>
 *   <li>latitude and longitude ({@code lat}, {@code lon}, {@code latitudine}, ...): degrees, WGS84;
 *   <li>GeoJSON ({@code geojson}): WGS84 longitude and latitude, as RFC 7946 defines them;
 *   <li>x and y ({@code x}, {@code y}, {@code easting}, ...) and WKT: a CSV states no reference system,
 *       so it is declared by the person, unless the WKT is EWKT with its {@code SRID=n;}.
 * </ul>
 */
final class CsvReader {

    private static final Set<String> LATITUDE = Set.of("lat", "latitude", "latitudine", "latdd", "latdeg", "gpslat");
    private static final Set<String> LONGITUDE = Set.of("lon", "lng", "long", "longitude", "longitudine", "londd",
            "londeg", "gpslon", "gpslng");
    private static final Set<String> X = Set.of("x", "xcoord", "coordx", "xpos", "easting", "est");
    private static final Set<String> Y = Set.of("y", "ycoord", "coordy", "ypos", "northing", "nord");
    private static final Set<String> WKT = Set.of("wkt", "geom", "geometry", "geometria", "thegeom", "wktgeom",
            "geomwkt", "shape", "wktgeometry");
    private static final Set<String> GEOJSON = Set.of("geojson", "geomgeojson", "jsongeom");
    private static final Pattern EWKT = Pattern.compile("^\\s*SRID=(\\d+);(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DECIMAL_COMMA = Pattern.compile("^-?\\d+,\\d+$");

    private CsvReader() {}

    /**
     * Whether the format defines the system of a CSV as WGS84 degrees, from its first line, so that the
     * loader sidecar is told without reading the file: when it locates its rows by latitude and
     * longitude, or by a GeoJSON column (RFC 7946: GeoJSON coordinates are WGS84 longitude and
     * latitude), and has no WKT column, whose system a CSV never states. The same rule as {@link #read}.
     */
    static boolean wgs84(String firstLine) {
        String[] header = parse(firstLine, separator(firstLine)).stream().findFirst().orElse(new String[0]);
        boolean degrees = column(header, LATITUDE) >= 0 && column(header, LONGITUDE) >= 0;
        boolean plane = !degrees && column(header, X) >= 0 && column(header, Y) >= 0;
        return column(header, WKT) < 0 && (degrees || (column(header, GEOJSON) >= 0 && !plane));
    }

    static Features read(byte[] content) {
        String text = Text.decode(content);
        char separator = separator(text);
        List<String[]> rows = parse(text, separator);
        if (rows.isEmpty()) {
            throw new LoadRefused(Code.EMPTY, "The CSV file is empty.");
        }
        String[] header = rows.get(0);
        int wkt = column(header, WKT);
        int geojson = column(header, GEOJSON);
        int lat = column(header, LATITUDE);
        int lon = column(header, LONGITUDE);
        int x = column(header, X);
        int y = column(header, Y);
        boolean degrees = lat >= 0 && lon >= 0;
        boolean plane = !degrees && x >= 0 && y >= 0;
        if (wkt < 0 && geojson < 0 && !degrees && !plane) {
            throw new LoadRefused(Code.UNREADABLE, "The CSV file has no geometry: it needs columns of latitude and "
                    + "longitude, of x and y, or a column of WKT or GeoJSON. Columns: " + String.join(", ", header) + ".");
        }
        Set<Integer> geometryColumns = new java.util.HashSet<>(java.util.List.of(wkt, geojson));
        if (degrees || plane) {
            geometryColumns.add(degrees ? lat : y);
            geometryColumns.add(degrees ? lon : x);
        }
        Integer srid = null;
        boolean ewkt = false;
        List<Map<String, Object>> attributes = new ArrayList<>();
        List<Geometry> geometries = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            String[] cells = rows.get(r);
            Geometry geometry = null;
            if (wkt >= 0 && cell(cells, wkt) != null) {
                String w = cell(cells, wkt);
                Matcher m = EWKT.matcher(w);
                if (m.matches()) {
                    int s = Integer.parseInt(m.group(1));
                    if (srid != null && srid != s) {
                        throw new LoadRefused(Code.CRS_MISMATCH, "The rows of the CSV file state different reference systems: EPSG:"
                                + srid + " and EPSG:" + s + ".");
                    }
                    srid = s;
                    ewkt = true;
                    w = m.group(2);
                }
                geometry = Geometry.of(Form.WKT, w.strip());
            } else if (geojson >= 0 && cell(cells, geojson) != null) {
                geometry = Geometry.of(Form.GEOJSON, cell(cells, geojson));
            } else if (degrees || plane) {
                Double first = number(cell(cells, degrees ? lon : x));
                Double second = number(cell(cells, degrees ? lat : y));
                if (first != null && second != null) {
                    geometry = Geometry.of(Form.WKT, "POINT(" + first + " " + second + ")");
                }
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (int c = 0; c < header.length; c++) {
                if (geometryColumns.contains(c)) {
                    continue;
                }
                String cell = cell(cells, c);
                values.put(name(header, c), separator != ',' && cell != null && DECIMAL_COMMA.matcher(cell.strip()).matches()
                        ? Double.valueOf(cell.strip().replace(',', '.'))
                        : Text.guess(cell));
            }
            attributes.add(values);
            geometries.add(geometry);
        }
        Crs crs;
        if (ewkt) {
            crs = Crs.file(srid);
        } else if (wkt >= 0 || plane) {
            crs = null; // nothing in a CSV states the system of WKT or of x and y
        } else {
            crs = Crs.format(); // degrees of latitude and longitude, or GeoJSON
        }
        Features features = new Features(crs);
        for (int c = 0; c < header.length; c++) {
            if (!geometryColumns.contains(c)) {
                features.attribute(name(header, c), null);
            }
        }
        for (int i = 0; i < attributes.size(); i++) {
            features.add(attributes.get(i), geometries.get(i));
        }
        return features;
    }

    private static String name(String[] header, int c) {
        String name = header[c] == null ? "" : header[c].strip();
        return name.isEmpty() ? "column_" + (c + 1) : name;
    }

    private static String cell(String[] cells, int c) {
        if (c < 0 || c >= cells.length || cells[c] == null || cells[c].isBlank()) {
            return null;
        }
        return cells[c];
    }

    private static Double number(String cell) {
        if (cell == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(cell.strip().replace(',', '.'));
            return Double.isFinite(v) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int column(String[] header, Set<String> names) {
        for (int i = 0; i < header.length; i++) {
            if (names.contains(key(header[i]))) {
                return i;
            }
        }
        return -1;
    }

    private static String key(String header) {
        return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * GP-983: whether a first line of a CSV names the columns of a location the catalog loads: latitude
     * and longitude, x and y, a WKT or a GeoJSON column. How the geodata recognize a CSV among the files
     * of the catalog, where most CSV files are not geodata.
     */
    static boolean locates(String firstLine) {
        String line = firstLine.startsWith("﻿") ? firstLine.substring(1) : firstLine;
        String[] header = parse(line, separator(line)).stream().findFirst().orElse(new String[0]);
        return column(header, WKT) >= 0 || column(header, GEOJSON) >= 0
                || (column(header, LATITUDE) >= 0 && column(header, LONGITUDE) >= 0)
                || (column(header, X) >= 0 && column(header, Y) >= 0);
    }

    /** The separator of the first line, outside quotes. */
    static char separator(String text) {
        int[] counts = new int[4];
        char[] candidates = {',', ';', '\t', '|'};
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                break;
            } else if (!quoted) {
                for (int k = 0; k < candidates.length; k++) {
                    if (c == candidates[k]) {
                        counts[k]++;
                    }
                }
            }
        }
        int best = 0;
        for (int k = 1; k < candidates.length; k++) {
            if (counts[k] > counts[best]) {
                best = k;
            }
        }
        return candidates[best];
    }

    /** The rows, with quoted fields that may hold separators, quotes and new lines; blank rows dropped. */
    static List<String[]> parse(String text, char separator) {
        List<String[]> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == separator) {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                add(rows, row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            add(rows, row);
        }
        return rows;
    }

    private static void add(List<String[]> rows, List<String> row) {
        if (row.stream().anyMatch(s -> !s.isBlank())) {
            rows.add(row.toArray(String[]::new));
        }
    }
}
