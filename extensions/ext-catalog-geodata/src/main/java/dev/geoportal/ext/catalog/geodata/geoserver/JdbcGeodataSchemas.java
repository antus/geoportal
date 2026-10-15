// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.geoserver;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The schemas of the geodata database through JDBC, with the login of the stores (which owns the
 * database). A connection per call: an alignment asks twice at most.
 */
public final class JdbcGeodataSchemas implements GeodataSchemas {

    private final PGSimpleDataSource database;

    /**
     * @param url the JDBC address of the geodata database as the catalog reaches it
     * @param password the array is not kept
     */
    public JdbcGeodataSchemas(String url, String user, char[] password) {
        this.database = new PGSimpleDataSource();
        database.setURL(Objects.requireNonNull(url, "url"));
        database.setUser(user);
        database.setPassword(new String(password));
    }

    @Override
    public boolean exists(String schema) {
        try (Connection c = database.getConnection();
                PreparedStatement s = c.prepareStatement("select 1 from pg_namespace where nspname = ?")) {
            s.setString(1, schema);
            try (ResultSet r = s.executeQuery()) {
                return r.next();
            }
        } catch (SQLException e) {
            throw GeoServerException.geodata("The geodata database does not answer: " + e.getSQLState());
        }
    }

    @Override
    public void create(String schema) {
        if (!OrganizationSpace.of(schema).isPresent()) {
            throw new IllegalArgumentException("Not the schema of an organization: " + schema);
        }
        try (Connection c = database.getConnection();
                Statement s = c.createStatement()) {
            // The name is checked above: org_ and 32 hexadecimal digits, nothing to quote.
            s.execute("create schema if not exists " + schema);
        } catch (SQLException e) {
            throw GeoServerException.geodata("The schema " + schema + " cannot be created: " + e.getMessage());
        }
    }
}
