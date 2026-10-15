<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0027. ext-catalog-geodata: its own id, API and schema, adopting the data of the catalog

- Status: accepted
- Date: 2026-10-15
- Deciders: Massimo Antonini

## Context and problem statement

ADR-0024 splits the catalog of Geoportal: files and connections are `ext-catalog` and
`ext-catalog-connection` of mosaikit-ext, the geospatial part is `ext-catalog-geodata` here (GP-983). The
file manager of mosaikit-ext took the names the catalog of Geoportal had: the API `catalog`
(`/api/v1/ext/catalog`), the schema `ext_catalog` and the app `/app/catalog`. The kernel refuses two
extensions with one API name, runs the Flyway history of each schema separately, and an installation
that had the catalog keeps its data, its grants and its credentials under the id `dev.geoportal.catalog`.
What of the catalog of today is generic had to be decided too.

## Decision

- **Everything in `extensions/catalog` is geospatial**, so all of it moves to `extensions/ext-catalog-geodata`:
  uploaded geodata and their loading into PostGIS and COG, GeoServer and GeoFence, publishing, data
  rules, layer editing, the data gateway and AuthKey, the proxy of OGC services, extent and reference
  systems, the mock of the datasets. The generic file manager and connections are not duplicated here:
  they are those of mosaikit-ext. The upload and the listing of geodata stay in this extension until a
  dataset can be a file of the file manager through the contracts of MK-134; the registration of
  external OGC services stays until it can be the connector `ext-catalog-connection-ogc` on EXT-010.
- **Names.** Manifest id `dev.geoportal.ext.catalog.geodata`, Java package
  `dev.geoportal.ext.catalog.geodata`, artifact `dev.geoportal:ext-catalog-geodata`, API `catalog-geodata`
  (every path is `/api/v1/ext/catalog-geodata/...`, the paths named in ADR-0018, ADR-0020 and ADR-0022
  included), schema `ext_catalog_geodata`, app **Geodata** (*Dati geografici*) at `/app/geodata`. The
  settings `geoportal.catalog.*`, the events `catalog.*` of the frontend and the custom elements keep
  their names: nothing else uses them, and renaming them would only break configurations.
- **Required extensions.** The manifest `requires` `io.github.mosaikit.ext.catalog` and
  `io.github.mosaikit.ext.catalog.connection` at `^0.1` (MK-132): the kernel starts it only next to them.
- **The extensions of mosaikit-ext are built from a pinned commit** (`mosaikit-ext.json`,
  `tools/mosaikit-ext.mjs`) for the benches, `npm run dev` and the distributions, until mosaikit-ext
  publishes them (EXT-103); then the pin becomes their versions.
- **The data of the catalog are adopted, not migrated.** The migrations of `db/` stay byte for byte those
  of the catalog. `upgrade/adopt-catalog.sql` renames the schema `ext_catalog` to `ext_catalog_geodata`
  with its Flyway history, so the kernel finds them applied, and gives the rows of the kernel that name
  `dev.geoportal.catalog` to the new id (every column `extension_id` of `mk_kernel`, the measures, the
  links of its resources, the API of service identities). It is one statement, deletes nothing, changes
  nothing when there is nothing to adopt, and refuses when both schemas exist. The server distribution
  runs it before Geoportal starts (service `adopt-catalog`); the other distributions document it.

## Consequences

- The file manager of mosaikit-ext and the geodata run side by side in Phase A of GP-983, each with its
  app; the geodata join the file manager and the connections with MK-134, EXT-010 and MK-135.
- The adoption script writes tables of the kernel, which belong to Mosaikit: the generic need, an
  extension that takes over the id and the schema of a previous one, is for Mosaikit to answer
  (a requirement there), after which the script goes.
- A new version of mosaikit-ext reaches Geoportal only by changing the pin, in a pull request whose CI
  runs every suite of the geodata on it.
