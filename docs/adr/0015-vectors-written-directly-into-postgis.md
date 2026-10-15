<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0015. Vectors are written directly into PostGIS, not through the Importer

- Status: accepted; the reader is decided by ADR-0019
- Date: 2026-10-13
- Deciders: Massimo Antonini

## Context and problem statement

ADR-0004 left open how vector files reach PostGIS: the GeoServer Importer, or writes by Geoportal
itself. The proofs of GP-327 (prototypes/f01, proof 6) loaded eight real files with the Importer of
GeoServer 2.28.5: three came out right. A GeoJSON in EPSG:32633 was loaded as EPSG:4326 without any
error, any GeoPackage was taken for a raster, and CSV failed because the opencsv jar is missing. The
same three files written by Geoportal directly into PostGIS came out right.

## Decision

- The catalog extension reads vector files itself (GeoTools; superseded on this point by ADR-0019: GDAL in a sidecar, built-in readers as fallback) and writes them into the PostGIS
  database of the organization, keeping geometry type, Z and reference system; then it publishes the
  table on GeoServer by REST.
- The Importer is not used for vectors. It may be reconsidered for a format only with a proof on
  real files.
- Formats GeoTools cannot read are converted first by the sidecar (ADR-0010), then written the same
  way.

## Consequences

- One path for every vector format, whose result Geoportal checks before publishing.
- The reference system is never guessed silently: a file without one is refused or asks for it
  (GP-326).
