// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.load;

import dev.geoportal.catalog.load.Features.Feature;
import dev.geoportal.catalog.load.Features.Form;
import dev.geoportal.catalog.load.Features.Geometry;
import dev.geoportal.catalog.load.LoadRefused.Code;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Types;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Writes the features of a file into a table of PostGIS (GP-937, ADR-0015): one column per attribute,
 * the geometry in {@code geom} with its type, Z and reference system, a spatial index, and the extent
 * computed from the table in the native system and in WGS84 (GP-321). PostGIS parses every geometry,
 * whatever form the file has it in.
 *
 * <p>The reference system is the one the file states, else the one its format defines, else the one
 * the person declared; else the file is refused (GP-324). It never commits: the caller commits when
 * everything else of the loading succeeded, or rolls back, and no partial table stays.
 */
final class PostgisWriter {

    /** The name of the schema of an organization and of a table: an identifier that needs no quotes. */
    static final Pattern IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");
    private static final int BATCH = 500;
    /** The normalized names of the EPSG systems, by database: they do not change. */
    private static final Map<String, Map<String, List<int[]>>> EPSG_NAMES = new ConcurrentHashMap<>();

    private final Connection connection;

    PostgisWriter(Connection connection) {
        this.connection = connection;
    }

    /** What the table holds, once written. */
    record Written(Crs crs, String geometryType, boolean z, long features, Extent nativeExtent, Extent wgs84, long bytes) {}

    Written write(String schema, String table, Features features, Integer declared) throws SQLException {
        check(schema);
        check(table);
        Crs crs = crs(features, declared);
        List<Feature> located = features.list().stream().filter(f -> f.geometry() != null).toList();
        if (located.isEmpty()) {
            throw new LoadRefused(Code.EMPTY, "The file holds no feature with a geometry.");
        }
        List<Column> columns = columns(features);
        String qualified = schema + "." + table;
        try (Statement s = connection.createStatement()) {
            s.execute("create schema if not exists " + schema);
            s.execute("drop table if exists " + qualified + " cascade");
            StringBuilder create = new StringBuilder("create table " + qualified + " (fid bigserial primary key");
            for (Column c : columns) {
                create.append(", ").append(quote(c.name())).append(' ').append(c.type().sql());
            }
            create.append(", geom geometry)");
            s.execute(create.toString());
        }
        insert(qualified, columns, features.list(), crs.srid());
        normalize(qualified, crs.srid());
        try (Statement s = connection.createStatement()) {
            s.execute("create index " + table + "_geom on " + qualified + " using gist (geom)");
            s.execute("analyze " + qualified);
        }
        Extent nativeExtent = nativeExtent(qualified);
        if (geographic(crs.srid()) && nativeExtent != null && !nativeExtent.degrees()) {
            throw new LoadRefused(Code.CRS_MISMATCH, "The coordinates are not degrees of " + crs.code() + " (they reach "
                    + nativeExtent.minX() + ", " + nativeExtent.minY() + " – " + nativeExtent.maxX() + ", " + nativeExtent.maxY()
                    + "): the file is in a projected system it does not state. Declare its EPSG code.");
        }
        String[] type = geometryType(qualified);
        return new Written(crs, type[0], Boolean.parseBoolean(type[1]), count(qualified), nativeExtent,
                wgs84(qualified, crs.srid(), nativeExtent), size(qualified));
    }

    /**
     * Takes over a table that the loader sidecar wrote (ADR-0019): checks that its coordinates fit its
     * system, replaces the table of the dataset with it, and reads what it holds. Never commits; on a
     * refusal the caller rolls back and drops the loaded table.
     */
    Written adopt(String schema, String loaded, String table, Crs crs) throws SQLException {
        check(schema);
        check(loaded);
        check(table);
        if (!known(crs.srid())) {
            throw new LoadRefused(Code.CRS_UNKNOWN, crs.code() + " is not a reference system PostGIS knows.");
        }
        String source = schema + "." + loaded;
        Extent nativeExtent = nativeExtent(source);
        if (nativeExtent == null) {
            throw new LoadRefused(Code.EMPTY, "The file holds no feature with a geometry.");
        }
        if (geographic(crs.srid()) && !nativeExtent.degrees()) {
            throw new LoadRefused(Code.CRS_MISMATCH, "The coordinates are not degrees of " + crs.code() + " (they reach "
                    + nativeExtent.minX() + ", " + nativeExtent.minY() + " – " + nativeExtent.maxX() + ", " + nativeExtent.maxY()
                    + "): the file is in a projected system it does not state. Declare its EPSG code.");
        }
        String qualified = schema + "." + table;
        try (Statement s = connection.createStatement()) {
            s.execute("drop table if exists " + qualified + " cascade");
            s.execute("alter table " + source + " rename to " + table);
        }
        String[] type = geometryType(qualified);
        return new Written(crs, type[0], Boolean.parseBoolean(type[1]), count(qualified), nativeExtent,
                wgs84(qualified, crs.srid(), nativeExtent), size(qualified));
    }

    /** The system the file states, its format defines or the person declared; refused otherwise. */
    Crs crs(Features features, Integer declared) throws SQLException {
        Crs crs = features.crs();
        if (crs == null && features.prj() != null) {
            crs = Crs.file(Prj.identify(features.prj(), epsgNames()));
        }
        if (crs == null && declared != null) {
            crs = new Crs(declared, Crs.Source.DECLARED);
        }
        if (crs == null) {
            throw new LoadRefused(Code.CRS_MISSING,
                    "The file states no reference system: declare its EPSG code, for example EPSG:32632.");
        }
        if (!known(crs.srid())) {
            throw new LoadRefused(Code.CRS_UNKNOWN, crs.code() + " is not a reference system PostGIS knows.");
        }
        return crs;
    }

    private void insert(String qualified, List<Column> columns, List<Feature> features, int srid) throws SQLException {
        Map<String, PreparedStatement> statements = new HashMap<>();
        Map<String, Integer> pending = new HashMap<>();
        boolean northFirst = northFirst(srid);
        try {
            for (Feature feature : features) {
                Geometry g = feature.geometry();
                String key = g == null ? "none" : g.form() + (g.authorityOrder() && northFirst ? "+flip" : "");
                PreparedStatement p = statements.get(key);
                if (p == null) {
                    p = connection.prepareStatement(insertSql(qualified, columns, g, northFirst, srid));
                    statements.put(key, p);
                }
                int i = 1;
                for (Column c : columns) {
                    bind(p, i++, c.type(), feature.attributes().get(c.source()));
                }
                if (g != null) {
                    if (g.value() instanceof byte[] bytes) {
                        p.setBytes(i, bytes);
                    } else {
                        p.setString(i, (String) g.value());
                    }
                }
                p.addBatch();
                if (pending.merge(key, 1, Integer::sum) >= BATCH) {
                    p.executeBatch();
                    pending.put(key, 0);
                }
            }
            for (PreparedStatement p : statements.values()) {
                p.executeBatch();
            }
        } catch (SQLException e) {
            SQLException cause = e.getNextException() != null ? e.getNextException() : e;
            throw new LoadRefused(Code.GEOMETRY_INVALID, "A feature cannot be written: " + firstLine(cause.getMessage()), e);
        } finally {
            for (PreparedStatement p : statements.values()) {
                p.close();
            }
        }
    }

    private static String insertSql(String qualified, List<Column> columns, Geometry g, boolean northFirst, int srid) {
        StringBuilder names = new StringBuilder();
        StringBuilder values = new StringBuilder();
        for (Column c : columns) {
            names.append(quote(c.name())).append(", ");
            values.append("?, ");
        }
        String geometry = "null";
        if (g != null) {
            String parse = switch (g.form()) {
                case GEOJSON -> "ST_GeomFromGeoJSON(?::text)";
                case WKT -> "ST_GeomFromText(?::text)";
                case WKB -> "ST_GeomFromWKB(?::bytea)";
                case GML -> "ST_GeomFromGML(?::text)";
                case KML -> "ST_GeomFromKML(?::text)";
            };
            if (g.authorityOrder() && northFirst) {
                parse = "ST_FlipCoordinates(" + parse + ")";
            }
            geometry = "ST_SetSRID(" + parse + ", " + srid + ")";
        }
        return "insert into " + qualified + " (" + names + "geom) values (" + values + geometry + ")";
    }

    private static void bind(PreparedStatement p, int index, Column.Type type, Object value) throws SQLException {
        if (value == null) {
            p.setNull(index, switch (type) {
                case BOOLEAN -> Types.BOOLEAN;
                case BIGINT -> Types.BIGINT;
                case DOUBLE -> Types.DOUBLE;
                case DATE -> Types.DATE;
                case TEXT -> Types.VARCHAR;
            });
            return;
        }
        switch (type) {
            case BOOLEAN -> p.setBoolean(index, (Boolean) value);
            case BIGINT -> p.setLong(index, ((Number) value).longValue());
            case DOUBLE -> p.setDouble(index, ((Number) value).doubleValue());
            case DATE -> p.setDate(index, Date.valueOf((LocalDate) value));
            case TEXT -> p.setString(index, String.valueOf(value));
        }
    }

    /** One type of geometry for the column, multi when the file mixes single and multi, and Z or 2D. */
    private void normalize(String qualified, int srid) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("update " + qualified + " set geom = ST_CurveToLine(geom) where ST_HasArc(geom)");
            boolean z = false;
            boolean m = false;
            try (ResultSet r = s.executeQuery("select coalesce(bool_or(ST_Zmflag(geom) in (2, 3)), false),"
                    + " coalesce(bool_or(ST_Zmflag(geom) in (1, 3)), false) from " + qualified)) {
                r.next();
                z = r.getBoolean(1);
                m = r.getBoolean(2);
            }
            if (z) {
                s.executeUpdate("update " + qualified + " set geom = ST_Force3DZ(geom) where ST_Zmflag(geom) <> 2");
            } else if (m) {
                s.executeUpdate("update " + qualified + " set geom = ST_Force2D(geom) where ST_Zmflag(geom) <> 0");
            }
            Set<String> kinds = new HashSet<>();
            try (ResultSet r = s.executeQuery("select distinct upper(GeometryType(geom)) from " + qualified + " where geom is not null")) {
                while (r.next()) {
                    kinds.add(r.getString(1).replaceAll("M$", ""));
                }
            }
            String type = "Geometry";
            boolean multi = false;
            for (String[] family : new String[][] {{"POINT", "MULTIPOINT"}, {"LINESTRING", "MULTILINESTRING"},
                {"POLYGON", "MULTIPOLYGON"}}) {
                if (Set.of(family).containsAll(kinds)) {
                    multi = kinds.contains(family[1]);
                    type = multi ? family[1] : family[0];
                }
            }
            String typmod = type.equals("Geometry") ? "Geometry" : camel(type);
            s.execute("alter table " + qualified + " alter column geom type geometry(" + typmod + (z ? "Z" : "") + ", " + srid
                    + ") using " + (multi ? "ST_Multi(geom)" : "geom"));
        }
    }

    private static String camel(String type) {
        return switch (type) {
            case "POINT" -> "Point";
            case "MULTIPOINT" -> "MultiPoint";
            case "LINESTRING" -> "LineString";
            case "MULTILINESTRING" -> "MultiLineString";
            case "POLYGON" -> "Polygon";
            case "MULTIPOLYGON" -> "MultiPolygon";
            default -> "Geometry";
        };
    }

    /** The type of the column and whether it has Z, as PostGIS registered them. */
    private String[] geometryType(String qualified) throws SQLException {
        String[] parts = qualified.split("\\.");
        try (PreparedStatement p = connection.prepareStatement(
                "select type, coord_dimension from geometry_columns where f_table_schema = ? and f_table_name = ? and f_geometry_column = 'geom'")) {
            p.setString(1, parts[0]);
            p.setString(2, parts[1]);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return new String[] {camel(r.getString(1).toUpperCase(Locale.ROOT)), Boolean.toString(r.getInt(2) >= 3)};
            }
        }
    }

    private Extent nativeExtent(String qualified) throws SQLException {
        try (Statement s = connection.createStatement();
                ResultSet r = s.executeQuery("select ST_XMin(e), ST_YMin(e), ST_XMax(e), ST_YMax(e) from (select ST_Extent(geom) e from "
                        + qualified + ") x where e is not null")) {
            if (!r.next()) {
                return null;
            }
            Extent extent = new Extent(r.getDouble(1), r.getDouble(2), r.getDouble(3), r.getDouble(4));
            return extent.finite() ? extent : null;
        }
    }

    /**
     * The extent in WGS84, from every geometry reprojected rather than from the corners of the native
     * box, which a projection curves. None when the reprojection fails or does not give degrees: no box
     * rather than a wrong one (GP-321).
     */
    private Extent wgs84(String qualified, int srid, Extent nativeExtent) throws SQLException {
        if (nativeExtent == null) {
            return null;
        }
        if (srid == Crs.WGS84) {
            return nativeExtent.degrees() ? nativeExtent : null;
        }
        Savepoint savepoint = connection.setSavepoint();
        try (Statement s = connection.createStatement();
                ResultSet r = s.executeQuery("select ST_XMin(e), ST_YMin(e), ST_XMax(e), ST_YMax(e) from (select ST_Extent(ST_Transform(geom, 4326)) e from "
                        + qualified + ") x where e is not null")) {
            Extent extent = r.next() ? new Extent(r.getDouble(1), r.getDouble(2), r.getDouble(3), r.getDouble(4)) : null;
            connection.releaseSavepoint(savepoint);
            return extent != null && extent.degrees() ? extent : null;
        } catch (SQLException e) {
            connection.rollback(savepoint);
            return null;
        }
    }

    private long count(String qualified) throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("select count(*) from " + qualified)) {
            r.next();
            return r.getLong(1);
        }
    }

    /** The space of the table with its indexes and TOAST, for the storage quota of the organization. */
    long size(String qualified) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("select pg_total_relation_size(?::regclass)")) {
            p.setString(1, qualified);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    // Reference systems

    private boolean known(int srid) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("select 1 from spatial_ref_sys where srid = ?")) {
            p.setInt(1, srid);
            try (ResultSet r = p.executeQuery()) {
                return r.next();
            }
        }
    }

    boolean geographic(int srid) throws SQLException {
        return srtext(srid).startsWith("GEOGCS");
    }

    /**
     * Whether the authority puts north first: every geographic system of EPSG (latitude, longitude),
     * and the projected ones whose first axis points north.
     */
    boolean northFirst(int srid) throws SQLException {
        String text = srtext(srid);
        if (text.startsWith("GEOGCS")) {
            return true;
        }
        int axis = text.lastIndexOf("AXIS[");
        int first = text.lastIndexOf("AXIS[", axis - 1);
        if (first < 0 || !text.startsWith("PROJCS")) {
            return false;
        }
        String direction = text.substring(first, axis);
        return direction.contains("NORTH") || direction.contains("SOUTH");
    }

    private String srtext(int srid) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("select coalesce(srtext, '') from spatial_ref_sys where srid = ?")) {
            p.setInt(1, srid);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getString(1).strip() : "";
            }
        }
    }

    private Map<String, List<int[]>> epsgNames() throws SQLException {
        String key = connection.getMetaData().getURL();
        Map<String, List<int[]>> names = EPSG_NAMES.get(key);
        if (names != null) {
            return names;
        }
        Map<String, List<int[]>> index = new HashMap<>();
        try (Statement s = connection.createStatement();
                ResultSet r = s.executeQuery("select srid, srtext from spatial_ref_sys where auth_name = 'EPSG' and srtext is not null")) {
            while (r.next()) {
                int srid = r.getInt(1);
                Prj.root(r.getString(2)).ifPresent(root ->
                        index.computeIfAbsent(root.name(), k -> new ArrayList<>()).add(new int[] {srid, root.projected() ? 1 : 0}));
            }
        }
        EPSG_NAMES.put(key, index);
        return index;
    }

    // Columns

    /** The columns of the attributes: names PostgreSQL takes without surprises, types from the values. */
    static List<Column> columns(Features features) {
        Map<String, Column.Type> types = new LinkedHashMap<>();
        for (String name : features.names()) {
            types.put(name, features.declaredType(name));
        }
        for (Feature f : features.list()) {
            for (Map.Entry<String, Object> e : f.attributes().entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                Column.Type type = typeOf(e.getValue());
                Column.Type current = types.get(e.getKey());
                types.put(e.getKey(), current == null ? type : current.widen(type));
            }
        }
        List<Column> columns = new ArrayList<>();
        Set<String> taken = new HashSet<>(Set.of("fid", "geom"));
        for (Map.Entry<String, Column.Type> e : types.entrySet()) {
            String name = columnName(e.getKey());
            String unique = name;
            for (int n = 1; taken.contains(unique); n++) {
                String suffix = "_" + n;
                unique = (name.length() + suffix.length() > 63 ? name.substring(0, 63 - suffix.length()) : name) + suffix;
            }
            taken.add(unique);
            columns.add(new Column(e.getKey(), unique, e.getValue() == null ? Column.Type.TEXT : e.getValue()));
        }
        return columns;
    }

    private static Column.Type typeOf(Object value) {
        if (value instanceof Boolean) {
            return Column.Type.BOOLEAN;
        }
        if (value instanceof Long || value instanceof Integer) {
            return Column.Type.BIGINT;
        }
        if (value instanceof Number) {
            return Column.Type.DOUBLE;
        }
        if (value instanceof LocalDate) {
            return Column.Type.DATE;
        }
        return Column.Type.TEXT;
    }

    static String columnName(String source) {
        String ascii = Normalizer.normalize(source == null ? "" : source, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String name = ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (name.isEmpty()) {
            name = "column";
        }
        if (!Character.isLetter(name.charAt(0))) {
            name = "c_" + name;
        }
        return name.length() > 63 ? name.substring(0, 63) : name;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    static void check(String identifier) {
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("Not a name of a schema or table of the catalog: " + identifier);
        }
    }

    static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
