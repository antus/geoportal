<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# 0028. Geodata are files of the catalog, and the levels on a file reach its dataset

- Status: accepted
- Date: 2026-10-15
- Deciders: Massimo Antonini

## Context and problem statement

GP-983 asks that a geodata dataset be a file of the file manager of mosaikit-ext (`ext-catalog`) with a
geospatial preview. `ext-catalog` lets other extensions add kinds of files (EXT-105, contract `file-kind`
of MK-134): it asks them whether they recognize a file, tells them when it is stored, replaced, moved,
deleted or shared differently, lets them record a state on it, and shows their icon, preview and
actions. The geodata had their own upload, and their own resource `dataset` with the levels `view`,
`download`, `edit`, `edit-data` and `manage`, from which the GeoFence rules of published layers are
written (GP-335). The files of the catalog have the levels `read`, `edit` and `manage`, which reach the
files inside a shared folder (MK-136). Two models of sharing on the same data would let a person read a
file in the catalog and not its layer, or the reverse, and would ask people to share twice.

## Decision

- **A kind of file.** `ext-catalog-geodata` implements `file-kind` as the kind `dataset`
  (`GeodataFileKind`): it recognizes the formats the geodata load, by the extension of the name and, for
  extensions that other files share, by their first bytes (a zip whose first entry is a part of a
  Shapefile, a JSON that is GeoJSON, a CSV whose header names a location, a GeoPackage or a TIFF by its
  signature). Stored, a file becomes a dataset of the geodata (`uploaded_data.catalog_file`), loaded into
  PostGIS or COG as an upload is; replaced, it is loaded again; deleted, its dataset goes with its table,
  its COG, its layers and its grants; shared differently, the GeoFence rules of its published layers are
  written again. The state of the dataset (`uploaded`, `loading`, `loaded`, `published`, `failed`, with
  why) is recorded on the file at every change, also after a loading of the sidecar or a publication.
  The frontend contributes the icon, the preview (`geodata-dataset-preview`: format, state, features,
  reference system, extent and a footprint drawn from the extent and, for a small GeoJSON, from its
  features) and the action Publish as a service (`geodata-dataset-publish`).
- **The dataset is a child of its file, and inherits its levels.** The resource `dataset` declares
  `inherits: parent`, and a dataset made from a file is registered with that file as its parent. The
  level `download` is renamed `read`: the same right (the features and the file), named as the level of
  the files, so that the kernel carries a level on the file or on a folder above it to the dataset by
  name (MK-136, `ResourceTypeEntry.carried`): `read` to `read`, `edit` to `edit`, `manage` to `manage`.
  The levels that only the geodata know stay on the dataset itself: `view` (the published layer, not its
  features nor its file, as for a link) and `edit-data` (writing the features of a published layer,
  GP-036). The GeoFence rules count the grants above the dataset (`Sharing.inheritedGrants`) and the
  owners of the file and of its folders as managers. People share in one place, the catalog; the
  sharing dialog of a dataset adds only what is particular to geodata.
- **The catalog owns the file.** The geodata answer `409` to replacing or deleting the content of a
  dataset made from a file, saying to do it in the catalog; the dataset follows the file.
- **A copy for the loading, for now.** The dataset keeps a copy of the content in the file service under
  the geodata, which the loading reads, in process or through a signed link for the loader sidecar
  (ADR-0019), as for an upload: the catalog gives its kinds the content only as a stream. The copy
  counts to the storage of the organization a second time. It goes when the catalog gives a kind a
  signed link to the content of a file of its kind (a need of mosaikit-ext).
- **Uploads to the geodata stay** until the app of the geodata becomes only the views of its layers and
  services: datasets uploaded there have no parent and are shared on their own, as before.
- **Existing grants.** `upgrade/adopt-catalog.sql` renames the grants at `download` on datasets to `read`
  before the first start with this version, since the kernel refuses an update that drops a level with
  grants on it.

## Consequences

- A folder shared in the catalog shares the geodata in it, their layers included, without a second step.
- A person with `read` on a file reads and downloads the features of its layer; `view` alone, for the
  layer only, is given on the dataset.
- The geodata files of an installation without `ext-catalog-geodata` are plain files of the catalog, with
  their content (EXT-105); that they show as files of an absent extension (MK-135) is for the catalog to
  do (a need of mosaikit-ext).
- The tables of a PostgreSQL connection offering the use "map" wait for the connector of PostgreSQL
  (EXT-040), the facts found by inspection (EXT-018) and the uses that other extensions contribute to a
  part of a connection (EXT-020).
