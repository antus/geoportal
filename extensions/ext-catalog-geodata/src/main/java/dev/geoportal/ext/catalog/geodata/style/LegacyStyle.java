// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.style;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The style of a layer as the catalog kept it before the grammar (GP-114): six values for every
 * geometry. Read only to migrate it into a document of geoportal/style/v1 ({@link
 * StyleDocument#fromLegacy}), from the rows written before the migration V14 and from clients that still
 * send it; nothing is written in this form any more.
 *
 * @param fill the color of areas and point symbols, {@code #rrggbb}
 * @param fillOpacity from 0 to 1
 * @param stroke the color of lines and outlines, {@code #rrggbb}
 * @param strokeWidth in pixels, from 0 to 20
 * @param pointSize the size of point symbols in pixels, from 1 to 64
 * @param pointShape circle, square, triangle, star, cross or x
 */
public record LegacyStyle(
        String fill, double fillOpacity, String stroke, double strokeWidth, double pointSize, String pointShape) {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-f]{6}$");
    private static final Set<String> SHAPES = Set.of("circle", "square", "triangle", "star", "cross", "x");

    /** The style a dataset got when the person did not choose one. */
    public static final LegacyStyle DEFAULT = new LegacyStyle("#2f80ed", 0.45, "#1b4f9c", 1.5, 8, "circle");

    public LegacyStyle {
        fill = color(fill, "fill");
        stroke = color(stroke, "stroke");
        range(fillOpacity, 0, 1, "fillOpacity");
        range(strokeWidth, 0, 20, "strokeWidth");
        range(pointSize, 1, 64, "pointSize");
        pointShape = pointShape == null ? "circle" : pointShape.strip().toLowerCase(Locale.ROOT);
        if (!SHAPES.contains(pointShape)) {
            throw new IllegalArgumentException("The point shape is one of " + SHAPES.stream().sorted().toList());
        }
    }

    private static String color(String value, String what) {
        String c = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!COLOR.matcher(c).matches()) {
            throw new IllegalArgumentException("The " + what + " color is written #rrggbb, not " + value);
        }
        return c;
    }

    private static void range(double value, double min, double max, String what) {
        if (Double.isNaN(value) || value < min || value > max) {
            throw new IllegalArgumentException(what + " goes from " + StyleDocument.num(min) + " to "
                    + StyleDocument.num(max));
        }
    }
}
