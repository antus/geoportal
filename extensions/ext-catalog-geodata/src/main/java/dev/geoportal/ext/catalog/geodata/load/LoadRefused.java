// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.load;

import java.util.Objects;

/**
 * A file the catalog cannot load, for what it contains (GP-937): the dataset goes to the state
 * {@code failed} with the reason, and nothing of it stays in PostGIS. The code names the problem for
 * the page, which says it in the language of the person; the message says it in English, with the
 * details, for the API and the audit log.
 */
public final class LoadRefused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The problems a file can have. */
    public enum Code {
        /** The file states no reference system and none was declared (GP-324). */
        CRS_MISSING,
        /** The file states a reference system the catalog does not know. */
        CRS_UNKNOWN,
        /** The coordinates do not fit the reference system: degrees expected, metres found. */
        CRS_MISMATCH,
        /** The file cannot be read as its format. */
        UNREADABLE,
        /** The file holds no feature with a geometry. */
        EMPTY,
        /** A geometry PostGIS cannot read. */
        GEOMETRY_INVALID,
        /** The file holds more than one layer: the person chooses the one the dataset loads. */
        SEVERAL_LAYERS,
        /** The space of the organization has no room for the loaded data. */
        QUOTA;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    private final Code code;
    private final java.util.List<String> layers;

    public LoadRefused(Code code, String message) {
        this(code, message, java.util.List.of());
    }

    /** A file with several layers, which the person chooses among. */
    public LoadRefused(Code code, String message, java.util.List<String> layers) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.layers = java.util.List.copyOf(layers);
    }

    public LoadRefused(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.layers = java.util.List.of();
    }

    /** The layers of a file with several, for {@link Code#SEVERAL_LAYERS}; empty otherwise. */
    public java.util.List<String> layers() {
        return layers;
    }

    public Code code() {
        return code;
    }
}
