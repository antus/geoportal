// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.connection;

import java.util.Locale;
import java.util.UUID;

/**
 * GP-986: a table of a PostgreSQL connection as its resolution found it (EXT-026): the connection, the
 * id of the piece, the address of the source as the platform reaches it, the schema, the table and its
 * geometry column, the user of the connection and the name of its credential, and the GeoServer store
 * that serves the tables of that schema of that connection.
 */
public record Source(UUID connection, String part, String host, int port, String database, String schema,
        String table, String geometryColumn, String user, String sslMode, String credential, String store) {

    /**
     * The store of the tables of a schema of a connection: {@code conn_} and the connection, then the
     * schema when it is not {@code public}, at most 100 characters of lowercase letters, digits and '_'.
     */
    public static String storeOf(UUID connection, String schema) {
        String id = connection.toString().replace("-", "");
        String slug = schema.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        String name = "conn_" + id + ("public".equals(schema) || slug.isEmpty() ? "" : "_" + slug);
        if (!slug.equals(schema) && !"public".equals(schema)) {
            // Two schemas whose names make the same slug get their own stores.
            name += "_" + Integer.toHexString(schema.hashCode());
        }
        return name.length() > 100 ? name.substring(0, 100) : name;
    }
}
