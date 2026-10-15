// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.geoserver;

import java.util.Objects;

/** A style that Geoportal keeps in the workspace of an organization, as SLD. */
public record StyleDefinition(String name, String sld) {

    public StyleDefinition {
        LayerDefinition.require(name, "style");
        Objects.requireNonNull(sld, "sld");
        if (sld.isBlank()) {
            throw new IllegalArgumentException("The SLD of the style is empty");
        }
    }
}
