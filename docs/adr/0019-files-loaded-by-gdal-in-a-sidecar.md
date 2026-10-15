<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0019. Files are loaded by GDAL in a sidecar; built-in readers are the fallback

- Status: accepted
- Date: 2026-10-13
- Deciders: Massimo Antonini

## Context and problem statement

ADR-0015 decided that Geoportal writes vectors into PostGIS itself, and named GeoTools as the reader.
A Mosaikit extension ships one JAR whose dependencies the kernel provides: Mosaikit loads one JAR per
extension and an extension cannot bring libraries. GeoTools would have to be shaded into the catalog,
some fifty megabytes with its own registries, EPSG database and image I/O, and it still does not write
Cloud Optimized GeoTIFF.

The first implementation of GP-937 therefore reads the formats in the catalog itself: the containers
(JSON, XML, CSV, zip, Shapefile, dBase, the SQLite file of a GeoPackage) in plain Java, the geometries
parsed by PostGIS, rasters converted by the GDAL of PostGIS raster. It works for the samples, but it
holds the whole file in memory, runs inside the request that stores the file, loads one layer per file,
and every new format is code to write and maintain. ADR-0010 already foresaw a GDAL sidecar for the
formats Geoportal cannot read.

## Decision

- Geographic files are loaded by **GDAL in a sidecar** of the installation: `ogr2ogr` writes vectors
  into the schema of the organization in PostGIS, `gdal_translate -of COG` makes Cloud Optimized
  GeoTIFF from rasters. The sidecar is a small service on an official GDAL image, reachable only from
  the internal network; the catalog gives it, per job, a signed link to the upload, the target and its
  credentials (from Mosaikit `Secrets`, never stored by the sidecar) and the options (declared
  reference system, layers).
- The reference system is never guessed: the sidecar assigns one (`-a_srs`) only when the person
  declared it for a file that states none, and the catalog refuses a file whose system is unknown.
- The catalog uses the sidecar when the installation configures it (`geoportal.catalog.loader.url`).
  Otherwise, and in the portable packaging until the sidecar exists there, the **built-in readers** of
  the first implementation load the file: they are the fallback, not a second way to grow.

## Consequences

- Every format GDAL reads is loaded, and files with several layers (GeoPackage, zip) can become one
  dataset per layer.
- Large files are streamed by the sidecar from the signed link, never held whole in the memory of
  Geoportal.
- Loading runs outside the request that stores the file: the dataset is `loading` until the sidecar
  reports its progress and result to the catalog.
- One more container in the server and cloud distributions, pinned by digest; its image carries GDAL
  and its licence.
- The built-in readers stay for the portable packaging and as the fallback when no sidecar is
  configured, with their limits: whole file in memory, inside the request, one layer per file.
- ADR-0015 stands: Geoportal writes vectors into PostGIS directly, not through the Importer; the
  writer is GDAL instead of GeoTools.
