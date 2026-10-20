// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.connection;

import dev.geoportal.ext.catalog.geodata.load.Extent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * GP-986: what a table of a PostgreSQL connection is, asked of the source itself, read only, when its
 * layer is made or refreshed: the type and the reference system of its geometry column (PostGIS
 * {@code geometry_columns}, or {@code geography_columns} in WGS84), an estimate of its rows and its extent.
 * Nothing of the table is copied.
 */
public final class SourceProbe {

    /** How long the source may take to answer, in seconds. */
    static final int TIMEOUT = 15;

    /** What the source says of the table. */
    public record Table(String geometryType, int srid, Long rows, Extent nativeExtent, Extent wgs84) {}

    /** The table cannot be mapped: the reason, for the person. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message);
        }
    }

    private static final Map<String, String> TYPES = Map.of(
            "POINT", "Point", "LINESTRING", "LineString", "POLYGON", "Polygon", "MULTIPOINT", "MultiPoint",
            "MULTILINESTRING", "MultiLineString", "MULTIPOLYGON", "MultiPolygon", "GEOMETRY", "Geometry",
            "GEOMETRYCOLLECTION", "GeometryCollection");

    private SourceProbe() {}

    /** The names of a piece of the connector of PostgreSQL (EXT-040): the schema and the relation of its id. */
    public static List<String> names(String id) {
        List<String> names = new ArrayList<>();
        if (id == null || id.isEmpty()) {
            return names;
        }
        for (String segment : id.split("/", -1)) {
            StringBuilder name = new StringBuilder();
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (c == '%' && segment.startsWith("%25", i)) {
                    name.append('%');
                    i += 2;
                } else if (c == '%' && segment.regionMatches(true, i, "%2F", 0, 3)) {
                    name.append('/');
                    i += 2;
                } else if (c == '%') {
                    throw new IllegalArgumentException("Not an id of a table of PostgreSQL: " + id);
                } else {
                    name.append(c);
                }
            }
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Not an id of a table of PostgreSQL: " + id);
            }
            names.add(name.toString());
        }
        return names;
    }

    /** Asks the source about a table, with the password of the credential of its connection. */
    public static Table probe(Source source, char[] password) {
        Properties properties = new Properties();
        properties.setProperty("user", source.user());
        properties.setProperty("password", new String(password));
        properties.setProperty("connectTimeout", Integer.toString(TIMEOUT));
        properties.setProperty("socketTimeout", Integer.toString(TIMEOUT * 4));
        properties.setProperty("readOnly", "true");
        properties.setProperty("ApplicationName", "Geoportal geodata");
        if (source.sslMode() != null && !source.sslMode().isBlank()) {
            properties.setProperty("sslmode", source.sslMode());
        }
        String url = "jdbc:postgresql://" + source.host() + ":" + source.port() + "/" + source.database();
        try (Connection connection = new org.postgresql.Driver().connect(url, properties)) {
            if (connection == null) {
                throw new Refused("The source of the connection cannot be reached.");
            }
            connection.setReadOnly(true);
            try (Statement s = connection.createStatement()) {
                s.execute("set statement_timeout = '" + (TIMEOUT * 4) + "s'");
            }
            String type;
            int srid;
            boolean geography = false;
            try (PreparedStatement p = connection.prepareStatement("""
                    select type, srid from geometry_columns
                    where f_table_schema = ? and f_table_name = ? and f_geometry_column = ?""")) {
                p.setString(1, source.schema());
                p.setString(2, source.table());
                p.setString(3, source.geometryColumn());
                try (ResultSet r = p.executeQuery()) {
                    if (r.next()) {
                        type = r.getString(1);
                        srid = r.getInt(2);
                    } else {
                        type = null;
                        srid = 0;
                    }
                }
            } catch (SQLException e) {
                throw new Refused("The source has no PostGIS that describes its geometries: " + e.getMessage());
            }
            if (type == null) {
                try (PreparedStatement p = connection.prepareStatement("""
                        select type, srid from geography_columns
                        where f_table_schema = ? and f_table_name = ? and f_geography_column = ?""")) {
                    p.setString(1, source.schema());
                    p.setString(2, source.table());
                    p.setString(3, source.geometryColumn());
                    try (ResultSet r = p.executeQuery()) {
                        if (r.next()) {
                            type = r.getString(1);
                            srid = r.getInt(2);
                            geography = true;
                        }
                    }
                }
            }
            if (type == null) {
                throw new Refused("The column " + source.geometryColumn() + " of " + source.schema() + "." + source.table()
                        + " is not a geometry PostGIS describes.");
            }
            if (srid <= 0) {
                throw new Refused("The geometries of " + source.schema() + "." + source.table() + " declare no reference"
                        + " system: give the column one (a SRID) in the database, then map it.");
            }
            Long rows = rows(connection, source);
            Extent[] extents = extents(connection, source, srid, geography);
            String geometryType = TYPES.getOrDefault(type.toUpperCase(Locale.ROOT).replaceAll("[ZM]+$", ""), "Geometry");
            return new Table(geometryType, srid, rows, extents[0], extents[1]);
        } catch (SQLException e) {
            throw new Refused("The source of the connection refused: " + e.getMessage());
        } finally {
            properties.remove("password");
        }
    }

    private static Long rows(Connection connection, Source source) {
        try (PreparedStatement p = connection.prepareStatement("""
                select c.reltuples::bigint from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = ? and c.relname = ?""")) {
            p.setString(1, source.schema());
            p.setString(2, source.table());
            try (ResultSet r = p.executeQuery()) {
                return r.next() && r.getLong(1) >= 0 ? r.getLong(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /** The extent in the system of the data and in WGS84, each null when the source cannot say. */
    private static Extent[] extents(Connection connection, Source source, int srid, boolean geography) {
        String column = quote(source.geometryColumn()) + (geography ? "::geometry" : "");
        String table = quote(source.schema()) + "." + quote(source.table());
        String sql = "select st_xmin(e), st_ymin(e), st_xmax(e), st_ymax(e), st_xmin(w), st_ymin(w), st_xmax(w), st_ymax(w)"
                + " from (select e, st_transform(st_setsrid(e::geometry, " + srid + "), 4326) w"
                + " from (select st_extent(" + column + ") e from " + table + ") x where e is not null) y";
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery(sql)) {
            if (!r.next()) {
                return new Extent[] {null, null};
            }
            return new Extent[] {new Extent(r.getDouble(1), r.getDouble(2), r.getDouble(3), r.getDouble(4)),
                new Extent(r.getDouble(5), r.getDouble(6), r.getDouble(7), r.getDouble(8))};
        } catch (SQLException e) {
            return new Extent[] {null, null};
        }
    }

    private static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
