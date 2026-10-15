// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.load;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What the style editor reads of the table of a loaded dataset (GP-973): its attributes, the distinct
 * values of one with how many features have each, its range and quantiles when it is a number, and a
 * sample of its features in WGS84 for the preview map. Read only, with the connection of the caller.
 *
 * <p>The names of the schema and the table are those the catalog gave; an attribute is read only when
 * it is a column of the table whose name the style grammar accepts as a field, quoted all the same.
 */
public final class DatasetValues {

    /** The attributes the grammar can name: letters, digits and underscores, not a digit first. */
    static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");

    /** The most distinct values given, as many as the categories of a style. */
    public static final int MAX_VALUES = 100;

    /** The most features of a sample, and the default. */
    public static final int MAX_SAMPLE = 2000;

    private static final List<String> NUMERIC = List.of("smallint", "integer", "bigint", "real", "double precision", "numeric");

    private DatasetValues() {}

    /** An attribute: its name, its type in PostgreSQL, and whether it is a number. */
    public record Attribute(String name, String type, boolean numeric) {}

    /** A distinct value and how many features have it. */
    public record Count(Object value, long count) {}

    /**
     * The values of an attribute.
     *
     * @param features the features with a value
     * @param values the distinct values, the most frequent first, at most {@value #MAX_VALUES}
     * @param more whether there are other distinct values
     * @param min the least number, for a number
     * @param max the greatest number, for a number
     * @param quantiles the values that split the features into {@code classes} groups of the same size,
     *     {@code classes - 1} of them, for a number
     */
    public record Values(String field, String type, boolean numeric, long features, List<Count> values, boolean more,
            Double min, Double max, List<Double> quantiles) {}

    /** The attributes of the table, in the order of its columns, without its key and its geometry. */
    public static List<Attribute> attributes(Connection c, String schema, String table) throws SQLException {
        List<Attribute> out = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("select column_name, data_type from information_schema.columns"
                + " where table_schema = ? and table_name = ? and column_name not in ('fid', 'geom') order by ordinal_position")) {
            s.setString(1, schema);
            s.setString(2, table);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    String name = r.getString(1);
                    String type = r.getString(2);
                    if (FIELD.matcher(name).matches() && !type.equals("USER-DEFINED")) {
                        out.add(new Attribute(name, type, NUMERIC.contains(type)));
                    }
                }
            }
        }
        return out;
    }

    /**
     * The values of one attribute.
     *
     * @param classes the number of classes the quantiles split a number into, from 2 to 50
     * @return empty when the table has no such attribute
     */
    public static Optional<Values> values(Connection c, String schema, String table, String field, int classes)
            throws SQLException {
        Optional<Attribute> found = attributes(c, schema, table).stream().filter(a -> a.name().equals(field)).findFirst();
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Attribute a = found.get();
        String column = quote(a.name());
        String from = qualified(schema, table);
        List<Count> values = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("select " + column + ", count(*) from " + from + " where " + column
                + " is not null group by 1 order by 2 desc, 1 limit " + (MAX_VALUES + 1))) {
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    values.add(new Count(scalar(r.getObject(1)), r.getLong(2)));
                }
            }
        }
        boolean more = values.size() > MAX_VALUES;
        if (more) {
            values = values.subList(0, MAX_VALUES);
        }
        long features;
        Double min = null;
        Double max = null;
        List<Double> quantiles = List.of();
        int n = Math.max(2, Math.min(50, classes));
        if (a.numeric()) {
            StringBuilder fractions = new StringBuilder();
            for (int i = 1; i < n; i++) {
                fractions.append(i > 1 ? "," : "").append((double) i / n);
            }
            try (PreparedStatement s = c.prepareStatement("select count(" + column + "), min(" + column + ")::float8, max("
                    + column + ")::float8, percentile_cont(array[" + fractions + "]::float8[]) within group (order by "
                    + column + "::float8) from " + from);
                    ResultSet r = s.executeQuery()) {
                r.next();
                features = r.getLong(1);
                min = (Double) r.getObject(2);
                max = (Double) r.getObject(3);
                Array q = r.getArray(4);
                if (q != null) {
                    quantiles = new ArrayList<>();
                    for (Object o : (Object[]) q.getArray()) {
                        quantiles.add(((Number) o).doubleValue());
                    }
                }
            }
        } else {
            try (PreparedStatement s = c.prepareStatement("select count(" + column + ") from " + from);
                    ResultSet r = s.executeQuery()) {
                r.next();
                features = r.getLong(1);
            }
        }
        return Optional.of(new Values(a.name(), a.type(), a.numeric(), features, values, more, min, max, quantiles));
    }

    /**
     * A sample of the features, a GeoJSON FeatureCollection in WGS84 (six decimals, two dimensions)
     * with their attributes, the first {@code limit} of the table.
     */
    public static String sample(Connection c, String schema, String table, int limit) throws SQLException {
        int n = Math.max(1, Math.min(MAX_SAMPLE, limit));
        String sql = "select json_build_object('type', 'FeatureCollection', 'features', coalesce(json_agg(f), '[]'::json))::text"
                + " from (select json_build_object('type', 'Feature', 'geometry',"
                + " ST_AsGeoJSON(ST_Force2D(case when ST_SRID(geom) in (0, 4326) then geom else ST_Transform(geom, 4326) end), 6)::json,"
                + " 'properties', to_jsonb(t) - 'geom' - 'fid') f from " + qualified(schema, table) + " t"
                + " where geom is not null order by fid limit " + n + ") s";
        try (PreparedStatement s = c.prepareStatement(sql); ResultSet r = s.executeQuery()) {
            r.next();
            return r.getString(1);
        }
    }

    private static String qualified(String schema, String table) {
        PostgisWriter.check(schema);
        PostgisWriter.check(table);
        return schema + "." + table;
    }

    private static String quote(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** A value as JSON says it: a number, true or false, or text (dates and the rest). */
    private static Object scalar(Object v) {
        if (v instanceof Number || v instanceof Boolean || v instanceof String) {
            return v;
        }
        return String.valueOf(v);
    }
}
