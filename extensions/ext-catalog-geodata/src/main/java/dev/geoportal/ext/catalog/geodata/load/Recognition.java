// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.load;

/** GP-983: what the readers of the loading say of the first bytes of a file, to recognize geodata. */
public final class Recognition {

    private Recognition() {}

    /** Whether the first line of a CSV names the columns of a location (see {@code CsvReader}). */
    public static boolean csvLocates(String firstLine) {
        return CsvReader.locates(firstLine);
    }
}
