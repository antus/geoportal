// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.load;

/** A column of a loaded table: its name in PostGIS and its type. */
record Column(String source, String name, Type type) {

    /** The types the catalog gives to attributes; anything else is text. */
    enum Type {
        BOOLEAN("boolean"), BIGINT("bigint"), DOUBLE("double precision"), DATE("date"), TEXT("text");

        private final String sql;

        Type(String sql) {
            this.sql = sql;
        }

        String sql() {
            return sql;
        }

        /** The narrowest type that holds both. */
        Type widen(Type other) {
            if (other == null || other == this) {
                return this;
            }
            if ((this == BIGINT && other == DOUBLE) || (this == DOUBLE && other == BIGINT)) {
                return DOUBLE;
            }
            return TEXT;
        }
    }
}
