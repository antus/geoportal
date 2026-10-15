<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0003. GeoLibre as the embedded map engine

- Status: accepted
- Date: 2026-10-09
- Deciders: Massimo Antonini

## Context and problem statement

The old map engine, Mars3D 3.7.7 on Cesium 1.115, cannot be upgraded without a logo appearing on the
free edition, and binds the product to an old Cesium. GeoLibre (MIT, React, MapLibre, deck.gl,
Cesium 1.146) is released weekly, embeds only through an iframe with a typed client
(`@geolibre/embed`), and has a plugin registry. Three ways were compared: embedded (1), GeoLibre as
the maps application (2), GeoLibre compiled in as packages (3).

## Decision

- **Solution 1**: a pinned static build of GeoLibre is served by Geoportal on the **same origin**,
  embedded in an iframe in the map editor, the viewer and the map widget of dashboards, and driven
  with `@geolibre/embed`.
- What the embedding API does not cover is done by a thin **Geoportal plugin inside GeoLibre**:
  catalog source, saving the GeoLibre project as a Geoportal map with versions, reserved layers
  (403), the real-time layer (SSE of the gateway), the translator of the style grammar
  `geoportal/style/v1`, GeoFlow processes as tools.
- Geoportal generates the `deployment.json` of GeoLibre per organization and role.
- Mars3D is dropped, with its plugins and workarounds.

## Consequences

- Upgrading GeoLibre is changing a pinned image, never merging a fork.
- Iframe seams (focus, shortcuts, theme) need care; the plugin depends on the stability of the
  plugin API of GeoLibre and stays thin.
- 3D analyses that Mars3D offered are rewritten on CesiumJS only where saved maps use them.
