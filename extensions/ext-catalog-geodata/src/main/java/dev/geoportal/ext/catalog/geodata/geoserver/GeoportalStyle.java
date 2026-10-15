// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.geoserver;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The style of a layer as Geoportal holds it (GP-114): the symbol a person chooses in the publishing
 * dialog, independent of the server that draws it. GeoServer receives its translation into SLD
 * ({@link #sld}), which the dialog shows next to it before publishing; the catalog keeps both, so
 * that a layer repointed to data of another geometry is translated again from the same choice.
 *
 * @param fill the color of areas and point symbols, {@code #rrggbb}
 * @param fillOpacity from 0 to 1
 * @param stroke the color of lines and outlines, {@code #rrggbb}
 * @param strokeWidth in pixels, from 0 to 20
 * @param pointSize the size of point symbols in pixels, from 1 to 64
 * @param pointShape circle, square, triangle, star, cross or x
 */
public record GeoportalStyle(
        String fill, double fillOpacity, String stroke, double strokeWidth, double pointSize, String pointShape) {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-f]{6}$");
    private static final Set<String> SHAPES = Set.of("circle", "square", "triangle", "star", "cross", "x");

    /** The style a dataset gets when the person does not choose one. */
    public static final GeoportalStyle DEFAULT = new GeoportalStyle("#2f80ed", 0.45, "#1b4f9c", 1.5, 8, "circle");

    public GeoportalStyle {
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
            throw new IllegalArgumentException(what + " goes from " + num(min) + " to " + num(max));
        }
    }

    /**
     * The SLD 1.0 of this style for a layer of the given geometry: a point, line or polygon
     * symbolizer, or the three of them with a rule each for a layer of mixed geometries ({@code
     * Geometry}), told apart by the dimension of each geometry.
     *
     * @param name the name of the style in the workspace
     * @param geometryType as the loading records it: Point, MultiLineString, Geometry, ...
     */
    public String sld(String name, String geometryType) {
        String kind = kind(geometryType);
        StringBuilder rules = new StringBuilder();
        if (kind.equals("mixed")) {
            rules.append(rule("points", 0, point()));
            rules.append(rule("lines", 1, line()));
            rules.append(rule("areas", 2, polygon()));
        } else {
            rules.append("      <Rule>\n        <Name>").append(kind).append("</Name>\n")
                    .append(switch (kind) {
                        case "point" -> point();
                        case "line" -> line();
                        default -> polygon();
                    })
                    .append("      </Rule>\n");
        }
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <StyledLayerDescriptor version="1.0.0" xmlns="http://www.opengis.net/sld" \
                xmlns:ogc="http://www.opengis.net/ogc" xmlns:xlink="http://www.w3.org/1999/xlink" \
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
                xsi:schemaLocation="http://www.opengis.net/sld http://schemas.opengis.net/sld/1.0.0/StyledLayerDescriptor.xsd">
                  <NamedLayer>
                    <Name>%1$s</Name>
                    <UserStyle>
                      <Name>%1$s</Name>
                      <Title>%1$s</Title>
                      <FeatureTypeStyle>
                %2$s      </FeatureTypeStyle>
                    </UserStyle>
                  </NamedLayer>
                </StyledLayerDescriptor>
                """.formatted(name, rules.toString().indent(4).stripTrailing() + "\n");
    }

    /** point, line, polygon or mixed, from the geometry type the loading recorded. */
    static String kind(String geometryType) {
        String g = geometryType == null ? "" : geometryType.toLowerCase(Locale.ROOT);
        if (g.contains("point")) {
            return "point";
        }
        if (g.contains("line") || g.contains("curve")) {
            return "line";
        }
        if (g.contains("polygon") || g.contains("surface")) {
            return "polygon";
        }
        return "mixed";
    }

    private String rule(String title, int dimension, String symbolizer) {
        return """
                      <Rule>
                        <Name>%s</Name>
                        <ogc:Filter>
                          <ogc:PropertyIsEqualTo>
                            <ogc:Function name="dimension"><ogc:PropertyName>geom</ogc:PropertyName></ogc:Function>
                            <ogc:Literal>%d</ogc:Literal>
                          </ogc:PropertyIsEqualTo>
                        </ogc:Filter>
                %s      </Rule>
                """.formatted(title, dimension, symbolizer);
    }

    private String point() {
        return """
                        <PointSymbolizer>
                          <Graphic>
                            <Mark>
                              <WellKnownName>%s</WellKnownName>
                              <Fill>
                                <CssParameter name="fill">%s</CssParameter>
                                <CssParameter name="fill-opacity">%s</CssParameter>
                              </Fill>
                              <Stroke>
                                <CssParameter name="stroke">%s</CssParameter>
                                <CssParameter name="stroke-width">%s</CssParameter>
                              </Stroke>
                            </Mark>
                            <Size>%s</Size>
                          </Graphic>
                        </PointSymbolizer>
                """.formatted(pointShape, fill, num(Math.max(fillOpacity, 0.05)), stroke, num(strokeWidth), num(pointSize));
    }

    private String line() {
        return """
                        <LineSymbolizer>
                          <Stroke>
                            <CssParameter name="stroke">%s</CssParameter>
                            <CssParameter name="stroke-width">%s</CssParameter>
                          </Stroke>
                        </LineSymbolizer>
                """.formatted(stroke, num(Math.max(strokeWidth, 0.5)));
    }

    private String polygon() {
        return """
                        <PolygonSymbolizer>
                          <Fill>
                            <CssParameter name="fill">%s</CssParameter>
                            <CssParameter name="fill-opacity">%s</CssParameter>
                          </Fill>
                          <Stroke>
                            <CssParameter name="stroke">%s</CssParameter>
                            <CssParameter name="stroke-width">%s</CssParameter>
                          </Stroke>
                        </PolygonSymbolizer>
                """.formatted(fill, num(fillOpacity), stroke, num(strokeWidth));
    }

    private static String num(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        String s = String.format(Locale.ROOT, "%.3f", value);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
