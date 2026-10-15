// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.geoserver;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What GeoServer must hold for an organization, as Geoportal knows it: the connection of its store,
 * the layers and styles it publishes, and those it withdrew, which the next alignment deletes.
 * Anything else found in the workspace is drift: reported, never deleted.
 *
 * @param rasters the raster layers the catalog publishes (GP-309): coverage stores named as them are not
 *     drift; they are published and withdrawn with their file, outside the alignment
 */
public record Desired(
        StoreConnection store,
        List<LayerDefinition> layers,
        List<StyleDefinition> styles,
        Set<String> retiredLayers,
        Set<String> retiredStyles,
        Set<String> rasters) {

    public Desired(StoreConnection store, List<LayerDefinition> layers, List<StyleDefinition> styles,
            Set<String> retiredLayers, Set<String> retiredStyles) {
        this(store, layers, styles, retiredLayers, retiredStyles, Set.of());
    }

    public Desired {
        Objects.requireNonNull(store, "store");
        layers = List.copyOf(layers);
        styles = List.copyOf(styles);
        retiredLayers = Set.copyOf(retiredLayers);
        retiredStyles = Set.copyOf(retiredStyles);
        rasters = rasters == null ? Set.of() : Set.copyOf(rasters);
    }

    /** Only the workspace and its store, nothing published yet. */
    public static Desired empty(StoreConnection store) {
        return new Desired(store, List.of(), List.of(), Set.of(), Set.of());
    }
}
