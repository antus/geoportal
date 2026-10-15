<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0024. Geoportal composes mosaikit-ext

- Status: accepted
- Date: 2026-10-14
- Deciders: Massimo Antonini

## Context and problem statement

Geoportal has four extensions: catalog, maps, dashboards, processes. Only maps and part of the catalog
are geospatial. Files and folders, connections to databases and streams, dashboards and the process
editor are what any product on Mosaikit needs, and keeping them inside Geoportal means writing them
again for the next product, or never letting them out of a private repository. The requirements had
the same mix: about a hundred GP requirements of the areas catalog, dashboards and processes describe
nothing geospatial.

## Decision

- **A public repository of common extensions.** `mosaikit/mosaikit-ext`, MPL-2.0 like Mosaikit, holds
  `ext-catalog` (a file manager: folders, sub-folders, sharing, stored through the file service of the
  kernel, filesystem in the portable and S3 on a server), `ext-catalog-connection` (the framework of
  connections) with one connector per generic source (`ext-catalog-connection-postgres`, `-mysql`,
  `-sqlserver`, `-oracle`, `-rest`, `-websocket`, `-sse`, `-mqtt`, `-kafka`, and the warehouse and
  lakehouse ones), `ext-dashboards` and `ext-processes`. Its requirements are `EXT-nnn` in
  `docs/requirements`, in the format of Mosaikit. It never names Geoportal.
- **Geoportal keeps the geospatial parts**: `ext-maps`; `ext-catalog-geodata` (loading into PostGIS
  and COG, GeoServer and GeoFence, publishing, data rules, the gateway, the proxy for OGC services,
  RNDT metadata; GP-983); `ext-dashboards-maps` (the map widget of the dashboards; GP-984);
  `ext-processes-geodata` (the geospatial process nodes; GP-985); and the geospatial connectors
  `ext-catalog-connection-<ogc|csw|arcgis|tiles|stac>` (GP-957, GP-061, GP-313).
- **Naming.** Folders and artifacts `ext-<extension>[-<sub>]...`; manifest ids reverse-DNS with the
  same segments: `io.github.mosaikit.ext.catalog.connection.kafka` in mosaikit-ext,
  `dev.geoportal.ext.catalog.geodata` in Geoportal.
- **Independent extensions.** Each extension declares the others it requires and those it only uses
  when present, and talks to them only through published contracts; a reference to a resource of an
  absent extension degrades into a placeholder. These are platform requirements of Mosaikit: MK-131
  (libraries inside extensions), MK-132 (required and optional dependencies), MK-133 (presence of an
  extension), MK-134 (contracts between extensions), MK-135 (references between resources degrade
  gracefully).
- **Requirements move.** A generic GP requirement becomes an EXT requirement with the same status and
  the GP file becomes `moved`, `moved-to: EXT-nnn`. A requirement with a generic and a geospatial part
  is split: the generic part is a new EXT requirement, the GP one keeps the geospatial part and
  depends on it. GP requirements may depend on EXT ones; `requirements.json` lists `EXT` among the
  products Geoportal builds on, and `check.py` checks EXT ids against a clone of mosaikit-ext next to
  this repository.
- **No code moves yet.** The extensions of this repository stay as they are until the extensions of
  mosaikit-ext and the platform requirements above exist; then the catalog is split and the
  dashboards and processes are replaced by those of mosaikit-ext, one at a time.

## Consequences

- 49 GP requirements moved to mosaikit-ext and 7 were split; the master plan cites the new ids.
- The common extensions are public under MPL-2.0 from the start, while Geoportal stays without a
  licence until its ownership is settled (CLAUDE.md). Settled on 15 October 2026: Geoportal is
  AGPL-3.0-or-later (README, Licence).
- A Geoportal installation is a composition: Mosaikit, the extensions of mosaikit-ext it needs, and
  its own. The distributions will list both.
- Monitoring and alerts, the assistant, BIM and the public catalog stay in Geoportal for now; some of
  them may become common extensions later by the same path.
