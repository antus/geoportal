// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.access;

import dev.geoportal.ext.catalog.geodata.load.Geodata;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The areas of data rules (GP-308), checked and composed by PostGIS in the geodata database, so that the
 * catalog never parses a geometry itself: an area is a polygon or multipolygon in the reference system of
 * the data, kept as {@code SRID=<code>;MULTIPOLYGON(...)}, the form a GeoFence {@code LIMIT} rule keeps.
 */
@ApplicationScoped
public class Areas {

    /** At most this many intersections are kept in memory. */
    static final int KEEP = 500;

    private final Geodata geodata;
    private final Map<String, Optional<String>> intersections = new ConcurrentHashMap<>();

    public Areas(Geodata geodata) {
        this.geodata = geodata;
    }

    /** An area that is not acceptable, with the reason a person reads. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /**
     * The area as it is kept: valid, a multipolygon, in the reference system of the data.
     *
     * @param wkt WKT, or EWKT whose SRID must be {@code srid}
     * @throws Refused when it is not a polygon, empty, invalid even after repair, or in another system
     */
    public String normalize(String wkt, int srid) {
        try (Connection c = connection()) {
            return normalize(c, wkt, srid);
        } catch (SQLException e) {
            throw new Refused("The area cannot be checked: " + firstLine(e.getMessage()));
        }
    }

    /** {@link #normalize(String, int)} on a connection to a PostGIS database. */
    public static String normalize(Connection c, String wkt, int srid) {
        try {
            try (PreparedStatement s = c.prepareStatement("""
                    with input as (
                      select case when upper(?) like 'SRID=%' then ST_GeomFromEWKT(?) else ST_GeomFromText(?, ?) end g
                    )
                    select ST_SRID(g), GeometryType(g), ST_IsEmpty(g),
                           ST_AsEWKT(ST_Multi(ST_CollectionExtract(ST_MakeValid(g), 3))),
                           ST_IsEmpty(ST_CollectionExtract(ST_MakeValid(g), 3))
                    from input""")) {
                s.setString(1, wkt);
                s.setString(2, wkt);
                s.setString(3, wkt);
                s.setInt(4, srid);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    if (r.getInt(1) != srid) {
                        throw new Refused("The area is in EPSG:" + r.getInt(1) + "; the data are in EPSG:" + srid + ".");
                    }
                    String type = r.getString(2);
                    if (!"POLYGON".equals(type) && !"MULTIPOLYGON".equals(type)) {
                        throw new Refused("The area must be a polygon or a multipolygon, not " + type + ".");
                    }
                    if (r.getBoolean(3) || r.getBoolean(5)) {
                        throw new Refused("The area is empty.");
                    }
                    return r.getString(4);
                }
            }
        } catch (SQLException e) {
            throw new Refused("The area is not valid WKT: " + firstLine(e.getMessage()));
        }
    }

    /**
     * What all the areas have in common, or empty when they have nothing in common. One area is returned
     * as it is, without asking the database.
     */
    public Optional<String> intersection(List<String> areas) {
        if (areas.size() == 1) {
            return Optional.of(areas.getFirst());
        }
        String key = String.join("\n", areas.stream().sorted().toList());
        Optional<String> kept = intersections.get(key);
        if (kept != null) {
            return kept;
        }
        Optional<String> result;
        try (Connection c = connection()) {
            result = fold(c, areas);
        } catch (SQLException e) {
            throw new IllegalStateException("The areas cannot be composed: " + firstLine(e.getMessage()), e);
        }
        if (intersections.size() > KEEP) {
            intersections.clear();
        }
        intersections.put(key, result);
        return result;
    }

    /** What all the areas have in common, on a connection to a PostGIS database; empty when nothing. */
    public static Optional<String> fold(Connection c, List<String> areas) throws SQLException {
        String current = areas.getFirst();
        for (String next : areas.subList(1, areas.size())) {
            try (PreparedStatement s = c.prepareStatement("""
                    select ST_IsEmpty(x), ST_AsEWKT(ST_Multi(x)) from (
                      select ST_CollectionExtract(ST_Intersection(ST_GeomFromEWKT(?), ST_GeomFromEWKT(?)), 3) x
                    ) i""")) {
                s.setString(1, current);
                s.setString(2, next);
                try (ResultSet r = s.executeQuery()) {
                    r.next();
                    if (r.getBoolean(1)) {
                        return Optional.empty();
                    }
                    current = r.getString(2);
                }
            }
        }
        return Optional.of(current);
    }

    private Connection connection() throws SQLException {
        return geodata.connect().orElseThrow(() -> new IllegalStateException(
                "The organization has no credential " + Geodata.CREDENTIAL + " to check areas."));
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
