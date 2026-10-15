// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.load;

/** A bounding box: west, south, east, north, in the units of its reference system (GP-321). */
public record Extent(double minX, double minY, double maxX, double maxY) {

    /** Whether it is a box of WGS84 degrees: finite, ordered, within ±180 and ±90. */
    public boolean degrees() {
        return finite() && minX >= -180 && maxX <= 180 && minY >= -90 && maxY <= 90;
    }

    public boolean finite() {
        return Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(maxX) && Double.isFinite(maxY)
                && minX <= maxX && minY <= maxY;
    }
}
