<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0026. The loader runs on the official GDAL image alpine-normal, which reads KML with LIBKML

- Status: accepted
- Date: 2026-10-14
- Deciders: Massimo Antonini

## Context and problem statement

The loader sidecar of ADR-0019 ran on the official image `ghcr.io/osgeo/gdal:ubuntu-small-3.11.4`. Its
only KML driver is the built-in `KML`, which reads the name and the description of a placemark and
drops its extended data (`Data`, `SimpleData`), the attributes of nearly every real KML. KML and KMZ
therefore stayed with the readers of the catalog, with their limits (whole file in memory, inside the
request). CZML is not a format of GDAL at all.

The official GDAL images come in flavours. On 14 October 2026, at GDAL 3.11.4, `ogrinfo --formats`
in each of them gives:

| Image | Size (uncompressed) | KML drivers | Python bindings |
|---|---|---|---|
| `ubuntu-small-3.11.4` | 339 MB | `KML` | yes |
| `alpine-normal-3.11.4` | 439 MB | `LIBKML`, `KML` | yes |
| `ubuntu-full-3.11.4` | 2.1 GB | `LIBKML`, `KML` | yes |

`alpine-normal` also has every driver the loader uses (PostgreSQL, CSV, GeoJSON, GPKG, ESRI Shapefile,
GML, GPX, GTiff, COG).

## Decision

- The loader is built on `ghcr.io/osgeo/gdal:alpine-normal-3.11.4`, pinned by digest: the smallest
  official image with LIBKML, 100 MB more than `ubuntu-small`, where `ubuntu-full` would cost 1.7 GB
  more for drivers the loader does not use. No image of our own with GDAL built from source.
- KML and KMZ go through the loader, opened with LIBKML only: each extended data becomes a column; the
  elements of time, display and style of a placemark that LIBKML turns into columns (`timestamp`,
  `begin`, `end`, `altitudeMode`, `tessellate`, `extrude`, `visibility`, `drawOrder`, `icon`) are not
  loaded, as the readers of the catalog do not load them.
- CZML stays with the readers of the catalog: GDAL has no driver for it.

## Consequences

- KML and KMZ are streamed and loaded outside the request, like the other formats of the loader, and
  keep their attributes.
- LIBKML makes a layer of each folder: a KML with placemarks in several folders asks which layer to
  load (`several-layers`), where the readers of the catalog load every placemark into one table.
- An extended data named like one of the display elements is not loaded; one named `id` fills the
  identifier column of the placemark.
- The loader image is Alpine (musl): the user of the loader is created with `addgroup`/`adduser`.
- A later version of GDAL is chosen in the same flavour, checking again that LIBKML is in it.
