// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.geoserver;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A layer that Geoportal publishes in the workspace of an organization: a table of the schema of the
 * organization, served in its declared reference system, with its title, description and, when
 * Geoportal chose one, its default style (a style of the same workspace).
 *
 * @param style the name of a style of the workspace, or {@code null} for the default of GeoServer
 */
public record LayerDefinition(String name, String table, String title, String description, String srs, String style) {

    /** Layer, table and style names: lowercase, digits and underscores, as PostgreSQL keeps them. */
    public static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    private static final Pattern SRS = Pattern.compile("^EPSG:[0-9]{4,6}$");

    public LayerDefinition {
        require(name, "layer");
        require(table, "table");
        Objects.requireNonNull(title, "title");
        description = description == null ? "" : description;
        if (srs == null || !SRS.matcher(srs).matches()) {
            throw new IllegalArgumentException("The reference system must be EPSG:<code>, not " + srs);
        }
        if (style != null) {
            require(style, "style");
        }
    }

    static void require(String name, String what) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid " + what + " name: lowercase letters, digits and underscores,"
                    + " starting with a letter, at most 63 characters");
        }
    }
}
