// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.geoserver;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * GP-986, ADR-0030: the PostGIS store through which GeoServer reads the tables of one schema of a
 * PostgreSQL connection, where they are: the address of the source as GeoServer reaches it, the schema,
 * the user and the password of the credential of the connection, read from the secrets service at the
 * moment of the alignment. {@link #toString()} never shows the password, and the password is never
 * compared nor returned: GeoServer keeps it encrypted in its catalog.
 *
 * @param password {@code null} when the credential cannot be read now: the store is then neither
 *     created nor updated, and the layers on it fail their health check until it can
 * @param sslMode the SSL mode of the connection, as PostgreSQL names it ({@code disable}, {@code
 *     require}, ...), or {@code null} for the default of the driver
 */
public record ConnectionStore(String name, String host, int port, String database, String schema, String user,
        char[] password, String sslMode) {

    public ConnectionStore {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(user, "user");
    }

    /** Whether the credential could be read: only then is the store made or updated. */
    public boolean writable() {
        return password != null;
    }

    /** The parameters of the store, password included. */
    public Map<String, String> parameters() {
        Map<String, String> p = comparable();
        p.put("passwd", new String(password));
        return p;
    }

    /** The parameters compared with what GeoServer holds: all but the password. */
    public Map<String, String> comparable() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("dbtype", "postgis");
        p.put("host", host);
        p.put("port", Integer.toString(port));
        p.put("database", database);
        p.put("schema", schema);
        p.put("user", user);
        p.put("Expose primary keys", "true");
        if (sslMode != null && !sslMode.isBlank()) {
            p.put("SSL mode", sslMode.toUpperCase(Locale.ROOT).replace('-', '_'));
        }
        return p;
    }

    @Override
    public String toString() {
        return "ConnectionStore[" + name + ": " + user + "@" + host + ":" + port + "/" + database + "/" + schema + "]";
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ConnectionStore c && comparable().equals(c.comparable()) && name.equals(c.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, comparable());
    }
}
