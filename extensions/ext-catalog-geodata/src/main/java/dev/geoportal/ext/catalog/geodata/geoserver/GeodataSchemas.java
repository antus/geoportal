// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.geoserver;

/** The schemas of the geodata database, one per organization, that the stores point to. */
public interface GeodataSchemas {

    boolean exists(String schema);

    /** Creates the schema if it does not exist. */
    void create(String schema);

    /** For the runs that never touch a schema, such as the withdrawal of the publications. */
    GeodataSchemas NONE = new GeodataSchemas() {
        @Override
        public boolean exists(String schema) {
            throw new UnsupportedOperationException("no geodata database here");
        }

        @Override
        public void create(String schema) {
            throw new UnsupportedOperationException("no geodata database here");
        }
    };
}
