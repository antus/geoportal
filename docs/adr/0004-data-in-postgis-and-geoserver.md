<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# 0004. Data in PostGIS and GeoServer, loaded by Geoportal

- Status: accepted
- Date: 2026-10-09
- Deciders: Massimo Antonini

## Context and problem statement

The old product wrote uploaded files on its own disk and resynchronized disk and database at every
listing; GeoNode was an optional second data backend with its own permissions and accounts.

## Decision

- Vector data go into PostGIS, in the GeoServer workspace of the organization (one workspace per
  organization), and are served as WMS, WFS and vector tiles; raster data are COG, published on
  GeoServer as WMS and WCS; 3D Tiles and point clouds go to object storage.
- Geoportal loads them: with the GeoServer Importer or by writing PostGIS directly (decided per
  format by the F0 proofs), converting first with the GeoLibre sidecar where GeoServer cannot read the
  format. No file stays on the disk of the application.
- GeoNode is not a component: an external GeoNode is one more catalog Geoportal connects to.
- The same core (Geoportal, PostgreSQL with PostGIS, GeoServer with GeoFence and the Importer) in
  every packaging and in development; no H2.

## Consequences

- The catalog becomes one list of data of every kind, with *where it lives* and *who sees it*.
- GeoServer and PostGIS are GPL-2: the portable packaging carries their licence texts and where to
  find their sources.
