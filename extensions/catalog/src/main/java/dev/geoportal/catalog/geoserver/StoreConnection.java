// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.geoserver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How GeoServer reaches the geodata database: host and port as GeoServer sees them (inside the
 * installation), the database and the login of the stores. The password comes from the credentials
 * of Mosaikit at the moment of the alignment; {@link #toString()} never shows it.
 */
public record StoreConnection(String host, int port, String database, String user, char[] password) {

    public StoreConnection {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(password, "password");
    }

    /** The connection parameters of the PostGIS store of a space, password included. */
    Map<String, String> parameters(OrganizationSpace space) {
        Map<String, String> p = comparable(space);
        p.put("passwd", new String(password));
        return p;
    }

    /** The parameters that are compared with what GeoServer holds: all but the password. */
    Map<String, String> comparable(OrganizationSpace space) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("dbtype", "postgis");
        p.put("host", host);
        p.put("port", Integer.toString(port));
        p.put("database", database);
        p.put("schema", space.schema());
        p.put("user", user);
        p.put("Expose primary keys", "true");
        return p;
    }

    @Override
    public String toString() {
        return "StoreConnection[" + user + "@" + host + ":" + port + "/" + database + "]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StoreConnection c
                && host.equals(c.host)
                && port == c.port
                && database.equals(c.database)
                && user.equals(c.user);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, database, user);
    }
}
