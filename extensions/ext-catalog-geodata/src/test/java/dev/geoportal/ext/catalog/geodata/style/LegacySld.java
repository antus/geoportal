// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.style;

import java.util.Locale;

/**
 * The SLD the catalog gave GeoServer for a style before the grammar (GP-114), kept as it was: the
 * tests check that the document a style is migrated into draws the same.
 */
final class LegacySld {

    private final String fill;
    private final double fillOpacity;
    private final String stroke;
    private final double strokeWidth;
    private final double pointSize;
    private final String pointShape;

    LegacySld(LegacyStyle s) {
        this.fill = s.fill();
        this.fillOpacity = s.fillOpacity();
        this.stroke = s.stroke();
        this.strokeWidth = s.strokeWidth();
        this.pointSize = s.pointSize();
        this.pointShape = s.pointShape();
    }

    /**
     * The SLD 1.0 of this style for a layer of the given geometry: a point, line or polygon
     * symbolizer, or the three of them with a rule each for a layer of mixed geometries ({@code
     * Geometry}), told apart by the dimension of each geometry.
     *
     * @param name the name of the style in the workspace
     * @param geometryType as the loading records it: Point, MultiLineString, Geometry, ...
     */
    String sld(String name, String geometryType) {
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
