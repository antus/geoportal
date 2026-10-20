// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.geoserver;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A layer that Geoportal publishes in the workspace of an organization: a table of the schema of the
 * organization, served in its declared reference system, with its title, description and, when
 * Geoportal chose one, its default style (a style of the same workspace). GP-986: or a table of a
 * PostgreSQL connection, in the store of that connection ({@code store}), read where it is.
 *
 * @param style the name of a style of the workspace, or {@code null} for the default of GeoServer
 * @param store the store of a connection (GP-986), or {@code null} for the store of the organization
 */
public record LayerDefinition(String name, String table, String title, String description, String srs, String style,
        String store) {

    /** Layer, table and style names: lowercase, digits and underscores, as PostgreSQL keeps them. */
    public static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    /** The names of the stores of connections (GP-986). */
    public static final Pattern STORE = Pattern.compile("^conn_[a-z0-9_]{1,95}$");

    private static final Pattern SRS = Pattern.compile("^EPSG:[0-9]{4,6}$");

    public LayerDefinition {
        require(name, "layer");
        if (store == null) {
            require(table, "table");
        } else {
            if (!STORE.matcher(store).matches()) {
                throw new IllegalArgumentException("Invalid store of a connection: " + store);
            }
            // A table of a source keeps the name it has there, any name PostgreSQL allows.
            if (table == null || table.isBlank() || table.length() > 63) {
                throw new IllegalArgumentException("Invalid table name: 1 to 63 characters");
            }
        }
        Objects.requireNonNull(title, "title");
        description = description == null ? "" : description;
        if (srs == null || !SRS.matcher(srs).matches()) {
            throw new IllegalArgumentException("The reference system must be EPSG:<code>, not " + srs);
        }
        if (style != null) {
            require(style, "style");
        }
    }

    /** A layer on the store of the organization. */
    public LayerDefinition(String name, String table, String title, String description, String srs, String style) {
        this(name, table, title, description, srs, style, null);
    }

    /** The store of the layer: that of its connection, or {@code data}, that of the organization. */
    public String storeOrDefault() {
        return store == null ? OrganizationSpace.STORE : store;
    }

    static void require(String name, String what) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid " + what + " name: lowercase letters, digits and underscores,"
                    + " starting with a letter, at most 63 characters");
        }
    }
}
