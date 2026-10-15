// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.access;

import dev.geoportal.ext.catalog.geodata.access.Evaluation.Cause;
import dev.geoportal.ext.catalog.geodata.access.Evaluation.Decision;

/**
 * Why a person sees what they see of a published layer (GP-339): from the decisions GeoFence takes for them on
 * the map (WMS) and on the data (WFS for a vector, WCS for a raster), and from what WFS answers them, the one
 * finding a manager acts on. A pure function, so that the page and the GeoServer tests read the same.
 */
public final class Diagnosis {

    private Diagnosis() {}

    /** The finding. */
    public enum Verdict {
        /** The person is not a member of the organization with an active account: nothing reaches them. */
        NOT_MEMBER,
        /** No grant reaches the person: they need {@code view} for the map, {@code read} for the data. */
        NO_GRANT,
        /** The person sees the map and not the data: their level is {@code view}, they need {@code read}. */
        VIEW_ONLY,
        /** Data rules hide every feature from the person (or their areas have nothing in common). */
        RULES_HIDE_ALL,
        /** Data rules limit what the person receives: some features, some columns or an area. */
        RULES_LIMIT,
        /** The person receives the whole layer. */
        FULL
    }

    /** The finding, and the level of grant that would change it, if one would. */
    public record Finding(Verdict verdict, String missing) {}

    /**
     * The finding for a person.
     *
     * @param member whether the person is a member of the organization with an active account
     * @param raster whether the layer is a raster: the data are WCS, without features nor columns
     * @param map the decision of GeoFence on WMS
     * @param data the decision on WFS, or on WCS for a raster
     * @param matched the features WFS answers the person when the data are allowed, else ignored
     * @param total the features of the layer
     */
    public static Finding of(boolean member, boolean raster, Decision map, Decision data, long matched, long total) {
        if (!member && !map.allowed() && !data.allowed()) {
            return new Finding(Verdict.NOT_MEMBER, null);
        }
        if (!map.allowed() && !data.allowed()) {
            if (map.cause() == Cause.DATA_RULES_DENY || data.cause() == Cause.DATA_RULES_DENY) {
                return new Finding(Verdict.RULES_HIDE_ALL, null);
            }
            return new Finding(Verdict.NO_GRANT, raster ? "view" : "read");
        }
        if (!data.allowed()) {
            // The map without the data: a block of data rules whose level is view denies the data as the
            // grants of level view do.
            return new Finding(Verdict.VIEW_ONLY, "read");
        }
        if (!data.limited()) {
            return new Finding(Verdict.FULL, null);
        }
        if (!raster && matched == 0 && total > 0) {
            return new Finding(Verdict.RULES_HIDE_ALL, null);
        }
        return new Finding(Verdict.RULES_LIMIT, null);
    }
}
