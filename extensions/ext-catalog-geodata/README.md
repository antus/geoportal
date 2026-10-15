# ext-catalog-geodata

Manifest id `dev.geoportal.ext.catalog.geodata`, package `dev.geoportal:ext-catalog-geodata`, API
`/api/v1/ext/catalog-geodata`, schema `ext_catalog_geodata`, app **Geodata** (*Dati geografici*) at
`/app/geodata`.

The geospatial part of the catalog of Geoportal (GP-983, [ADR-0024](../../docs/adr/0024-geoportal-composes-mosaikit-ext.md),
[ADR-0027](../../docs/adr/0027-ext-catalog-geodata-its-own-id-api-and-schema.md)): the loading of geodata
into PostGIS and Cloud Optimized GeoTIFF, GeoServer and GeoFence, publishing and the lifecycle of
layers, data rules, the data gateway and AuthKey, the proxy of OGC services, extent and reference
systems, and the datasets of the organization (still a mock with demo datasets, until it reads
GeoServer and the archive).

## Composition

The files, folders and connections that any product needs are the extensions of
[mosaikit-ext](https://github.com/mosaikit/mosaikit-ext), which this one requires (`requires` of the
manifest, MK-132): the kernel starts it only next to them.

| Extension | Manifest id | Range | What |
|---|---|---|---|
| `ext-catalog` | `io.github.mosaikit.ext.catalog` | `^0.1` | the file manager, app **Catalog** at `/app/catalog`, API `catalog`, schema `ext_catalog` |
| `ext-catalog-connection` | `io.github.mosaikit.ext.catalog.connection` | `^0.1` | the framework of connections, app **Connections** |

Until they are published packages, they are built from the commit of mosaikit-ext pinned in
[`mosaikit-ext.json`](../../mosaikit-ext.json) at the root of the repository, by
[`tools/mosaikit-ext.mjs`](../../tools/mosaikit-ext.mjs) into `target/mosaikit-ext/packages` there:
once, then reused. The bench of the tests (`tests/global-setup.ts`), `npm run dev` and the
distributions install them next to this extension. `MOSAIKIT_EXT_SOURCE=<a clone of mosaikit-ext>`
builds them from a working tree instead; `MOSAIKIT_EXT_PACKAGES=<a directory of zips>` gives the tests
other packages. To follow mosaikit-ext, change the commit of `mosaikit-ext.json` (and the ranges of the
manifest when a version leaves them).

A file of a geodata format stored in the file manager is a dataset of the geodata, with its state,
its geospatial preview and the action Publish (below, *Geodata files of the catalog*). A PostGIS table
as a part of a PostgreSQL connection offering the use "map" waits for the connector of PostgreSQL
(EXT-040) and the uses that other extensions contribute to a part of a connection (EXT-018, EXT-020);
the external OGC services become the connector `ext-catalog-connection-ogc` (GP-957) on EXT-010.

## Geodata files of the catalog

The geodata are a kind of file of the catalog of mosaikit-ext (GP-983,
[ADR-0028](../../docs/adr/0028-geodata-files-of-the-catalog-and-their-levels.md)), through its contract
`file-kind` (EXT-105, MK-134), whose interfaces come in the library `ext-catalog-api` (`lib/`, listed in
`libraries` of the manifest by the build, MK-131):

- **Recognized** (`GeodataFileKind`) when a person stores a file in the catalog, or replaces it: the
  formats of *Uploaded data* below, by the extension of the name, and for the extensions that other files
  share by the first bytes: a zip whose first entry is a part of a Shapefile, a JSON that is GeoJSON, a
  CSV whose header names a location (latitude and longitude, x and y, WKT or GeoJSON), a GeoPackage or a
  TIFF by its signature. Other files stay plain files.
- **A dataset** (`uploaded_data.catalog_file`), made in a job of the organization a few seconds after the
  file is stored, owned by the organization and loaded as an upload is; replaced, it is loaded again;
  deleted, its table, its COG and its layers go with it; renamed or moved, it stays as it is. It keeps a
  copy of the content for the loading, which counts to the storage a second time (ADR-0028).
- **Its state on the file**, which the catalog shows: `uploaded` (*Non ancora caricato*, with why),
  `loading`, `loaded` (with the features and the reference system), `published`, `failed` (with why);
  recorded at every change, also when the loader sidecar ends or a layer is published or withdrawn.
- **Its levels from the file.** The dataset is a child of its file (`inherits: parent`, MK-136): `read`,
  `edit` and `manage` on the file or on a folder above it are the same levels on the dataset, and the
  GeoFence rules of its layers count them, and the owners of the file and of its folders as managers.
  `view` (the layer only) and `edit-data` (GP-036) are given on the dataset itself.
- **In the catalog**, the icon `web/dataset.svg`, the title *Dato geografico*, the preview
  `geodata-dataset-preview` (format, state, features, reference system, extent in WGS84 and the
  footprint: the extent, and the features of a GeoJSON up to 2 MB, read through the signed link of the
  file) and the action *Pubblica come servizio* (`geodata-dataset-publish`, GP-114: the name of the
  layer, then `POST publications`), all in `web/kind.js`.
- **The catalog owns the file**: the API of the geodata answers `409` to replacing or deleting the
  content of such a dataset. `GET uploads/files/{file}` is the dataset of a file of the catalog, for the
  preview and the action.

## Upgrading from the catalog of Geoportal

This extension was `dev.geoportal.catalog` (folder `extensions/catalog`, API `catalog`, schema
`ext_catalog`, app at `/app/catalog`) until GP-983. Its migrations in `db/` are the same files, unchanged,
so that an installation that had it keeps its data: [`upgrade/adopt-catalog.sql`](upgrade/adopt-catalog.sql),
run once before the first start with the new extensions, renames the schema `ext_catalog` to
`ext_catalog_geodata` with its Flyway history, and gives the rows of the kernel that named the old
extension (resources and grants, permissions, credentials, files, quotas, apps of organizations,
documents, notifications, the links of its resources and the API `catalog` of service identities) to the
new one. It runs in one statement, deletes nothing, changes nothing on a new installation, on one already
adopted or on one whose `ext_catalog` is the file manager, and refuses, changing nothing, when
`ext_catalog_geodata` already exists next to the old `ext_catalog` (this extension was started first: stop
Geoportal, restore the backup taken before, run the script, start). The server distribution runs it by
itself (service `adopt-catalog`); the installation and the Helm chart document the command. The audit log
keeps the names it was written with. The settings `geoportal.catalog.*` keep their names; settings that
name the extension, such as `mosaikit.quotas.limits."dev.geoportal.catalog/data-requests"` or the values
`mosaikit.extensions.dev.geoportal.catalog` of the chart, are changed by hand.

| Path | Content |
|---|---|
| `manifest.yaml` | What the extension is and brings: identity, frontend, contributions, backend, schema and actions |
| `docs/requirements/` | The requirements this extension implements alone (`GP-nnn.md`); those that span extensions are in `docs/requirements/` of the repository |
| `web/index.js` | The app, a custom element that the shell renders at `/app/geodata` |
| `web/uploads.js` | The uploaded data of the page: upload dialog, details, rename, replace, download, share, delete |
| `web/publications.js` | The services published on GeoServer (GP-114, GP-118): publishing dialog, services with their health, republish, repoint, unpublish, reconciliation |
| `web/data-rules.js` | The data rules of a published layer (GP-308): list, dialog with subject, area drawn on the map or WKT, row filter, hidden columns |
| `web/service-rules.js`, `ServiceRules`, `ServiceRuleResource` | Data rules on external services (GP-309): the layer unavailable to the subject, and why |
| `upload/RasterPublishing.java` | Rasters published as coverages of their COG (GP-309) |
| `web/services.js` | The external services of the page: register, layers from the capabilities, preview, proxy on and off |
| `src/main/java/dev/geoportal/ext/catalog/geodata/upload/` | Uploaded data (GP-936): REST resource, lifecycle, formats, samples; publishing (GP-114, GP-118) |
| `src/main/java/dev/geoportal/ext/catalog/geodata/proxy/` | The proxy of external services (GP-939): address checks, HTTP client, headers, targets |
| `src/main/java/dev/geoportal/ext/catalog/geodata/load/` | Loading (GP-937, GP-321, GP-324): readers of the formats, PostGIS writer, COG, reference systems |
| `src/main/resources/samples/` | The sample datasets, with their titles in `samples.json`; written by `src/samples/generate.py` |
| `src/main/java/dev/geoportal/ext/catalog/geodata/access/` | The GeoFence rules (GP-335): generated from the sharing, written as a whole, read back |
| `src/main/java/dev/geoportal/ext/catalog/geodata/editing/`, `web/editing.js` | The editing of layer data (GP-036): surveyors, layer assignments, WFS transactions |
| `src/` | Entity, repository and REST resource under `/api/v1/ext/catalog-geodata`; `geoserver/`, the governance of GeoServer |
| `db/` | Flyway migrations of the schema `ext_catalog_geodata`, run by the kernel at start; never edit an applied one (their checksums carry the data of the old catalog over, see Upgrading) |
| `upgrade/adopt-catalog.sql` | The adoption of the data of the old catalog `dev.geoportal.catalog`, and the level download renamed read (GP-983) |
| `upload/GeodataFileKind.java`, `upload/CatalogStates.java`, `web/kind.js` | The geodata files of the catalog of mosaikit-ext (GP-983, ADR-0028) |
| `tests/catalog-files.spec.ts` | The tests of the geodata files of the catalog (GP-983) |
| `tests/composition.ts`, `tests/composition.spec.ts` | The extensions of mosaikit-ext of the bench, and the tests of the composition (GP-983) |

## Build and test

A project of its own on the parent of Mosaikit extensions: Java 25, Maven and Node.js 24.

```bash
mvn package    # target/<name>-<version>.zip, the package to install
mvn verify     # also the tests of tests/, on a Mosaikit installation started for them
mvn verify -Ppostgis   # also the JUnit tests of the loading on a PostGIS in Docker (tag postgis)
npm run dev    # Mosaikit with this extension and those of mosaikit-ext it requires, until Ctrl+C
```

The bench suite loads into a real geodata database when it has one:

```bash
docker compose -p geoportal-f03-gp937 -f tests/geodata/compose.yaml up -d --wait
GEOPORTAL_TEST_GEODATA=1 npx playwright test
docker compose -p geoportal-f03-gp937 -f tests/geodata/compose.yaml down -v
```

With the GeoServer of the tests (`GEOPORTAL_TEST_GEOSERVER=1`, below) the same suite also publishes the
layer of every vector format and reads its features back through WFS, and `tests/publishing.spec.ts`
runs the publishing path (GP-114, GP-115, GP-118). With the loader sidecar:

```bash
docker compose -p geoportal-f03-gp937 -f tests/geodata/compose.yaml --profile loader up -d --wait --build
GEOPORTAL_TEST_LOADER=1 npx playwright test
docker compose -p geoportal-f03-gp937 -f tests/geodata/compose.yaml --profile loader down -v
```

`mvn test -Ppostgis` also builds the loader from its Dockerfile and loads through it, among others a
generated CSV of 200 MB with the loader limited to 384 MB of memory.

## Uploaded data

A file uploaded to the catalog is a dataset of the organization (GP-936). Its content is stored by the
file service of Mosaikit (`FileStorage`), never on the disk of the application, attached to a
registered resource of type `dataset`; the table `ext_catalog_geodata.uploaded_data` keeps what the catalog
adds:

- a **title** and a description, which people change, and a **technical name** (`[a-z][a-z0-9_]*`,
  unique in the organization, from the name of the file), which stays when the title changes: it is the
  name of the PostGIS table the dataset becomes with GP-937, and the layer name proposed when it is
  published (GP-114);
- the **format**, from the extension of the file: GeoJSON, Shapefile (zip), GeoPackage, GML, GPX, CSV,
  KML, KMZ, CZML and GeoTIFF; other files are refused with the reason (documents come with GP-350);
- the **state** of its lifecycle and its **version**, below; replacing the file makes a new version
  that is loaded again.

API under `/api/v1/ext/catalog-geodata/uploads`:

| Call | Needs |
|---|---|
| `GET /` the datasets the person sees, each with the level of the person | level `view` |
| `GET /space` storage used and limit of the organization, whether the person may upload, formats, largest file (`maxFile`, bytes) | signed in |
| `POST /?name=&title=&description=&crs=` the file as the body | permission `upload-data` |
| `GET /{id}` | `view` |
| `GET /{id}/content` the file | `download` |
| `PATCH /{id}` `{title, description}` | `edit` |
| `PUT /{id}/content?name=&crs=` a new file | `edit-data` |
| `POST /{id}/load?crs=` load the current file again | `edit-data` |
| `DELETE /{id}` the dataset, its file and its grants | `manage` |

The levels of `dataset` are those of the mockup (`geoportal:dataset/permissions`): view, read (formerly download),
edit, edit the data, manage; people share a dataset with a person, a team or the organization in the
sharing dialog of Mosaikit, which the page opens with the event `shell.share`. The permission
`upload-data` ("Caricare dati") is off until the administrators of the organization grant it.

**Quota.** The file of a dataset counts to the storage of the organization in the file service; an
upload that does not fit is refused with `403 Quota exceeded` before it is read when its size is known.
The table of a loaded vector counts too (`Quotas.reportSpace`, with its indexes), and the COG of a raster
is a file of the file service.

**Largest file.** The catalog accepts files up to the smaller of the two limits of the kernel,
`mosaikit.files.max-size` and `quarkus.http.limits.max-body-size` (10 MB each unless set; the
distributions of Geoportal set both to `1G`). The upload dialog says the maximum and refuses a larger file
before sending it; the API refuses it with `413` and a problem detail whose `maxFile` is the limit in
bytes. A body over the HTTP limit is cut off by the kernel before it reaches the catalog, and on the
server stack by the gateway, which answers with a problem detail too.

**Audit.** `dev.geoportal.ext.catalog.geodata:dataset.uploaded`, `.renamed`, `.replaced`, `.deleted`, `.downloaded`,
and `.upload-refused` (denied without the permission, failed beyond the quota or the largest file); `.loaded` (with reference
system, geometry, features, table, space and extent), `.load-failed` (code and reason) and
`.load-postponed` (the geodata database did not answer).

## Loading

The catalog loads the file of each dataset (GP-937, [ADR-0015](../../docs/adr/0015-vectors-written-directly-into-postgis.md),
[ADR-0019](../../docs/adr/0019-files-loaded-by-gdal-in-a-sidecar.md)) when it is uploaded, replaced or
loaded again:

- a **vector** (GeoJSON, Shapefile, GeoPackage, GML, GPX, CSV, KML, KMZ, CZML) becomes the table
  `<schema>.<technical name>` of the **geodata database**, in the schema of the organization
  `org_<the 32 hexadecimal digits of its identifier>`, the same name as its GeoServer workspace
  (GP-334); a column per attribute with its type (`bigint`, `double precision`, `boolean`, `date`,
  `text`), the primary key `fid`, the geometry `geom` with its type (multi when the file mixes single
  and multi, `Geometry` when it mixes kinds), its Z and its reference system, and a spatial index;
  the layers that publish the dataset (GP-114, below) follow it: republished under the same names
  when it is loaded again or renamed, withdrawn when it is no longer a table (failed, deleted,
  replaced by a raster); loading alone publishes nothing;
- a **raster** (GeoTIFF) becomes a **Cloud Optimized GeoTIFF** (DEFLATE, overviews), stored by the file
  service of Mosaikit next to the upload, never on the disk of the application.

**Who loads** ([ADR-0019](../../docs/adr/0019-files-loaded-by-gdal-in-a-sidecar.md)):

- **the loader sidecar**, GDAL in a container of the installation (`distribution/server/loader`), when
  the setting `geoportal.catalog.loader.url` names it: for GeoJSON, Shapefile, GeoPackage, GML, GPX,
  CSV, KML, KMZ and GeoTIFF. The request that stores the file submits a job with a signed link to it (two hours),
  the target (host, port and database as the services of the installation reach them,
  `geoportal.geodata.host`/`.port`/`.database`, the login of the credential `geodata`, sent with the job
  and never kept by the loader) and the options (declared EPSG code, the system a CSV of latitude and
  longitude or with a GeoJSON column defines, the chosen layer). The loader streams the file to its disk, `ogr2ogr` writes a
  table `<technical name>__l<mark>` with `-a_srs` of the code it identified or was given (never a
  guess), or `gdal_translate -of COG` makes the COG, which the catalog fetches and stores with
  `FileStorage` (Mosaikit has no signed upload links, so the COG comes back through the catalog). The
  dataset is `loading`, with its `progress`, until the catalog takes the outcome over: it checks the
  table (degrees, extent, geometry type) and renames it to the technical name, or records the refusal.
  `geoportal.catalog.loader.source-url` is Mosaikit as the loader reaches it (default
  `http://geoportal:8080`).
- **the readers of the catalog** otherwise, and always for CZML, which is not a format of GDAL: the
  catalog reads the formats itself and PostGIS parses every geometry, inside the request, with the
  whole file in memory.

**KML and KMZ** ([ADR-0026](../../docs/adr/0026-the-loader-image-reads-kml-with-libkml.md)). The image
of the loader has the LIBKML driver, so the loader reads KML and KMZ with their extended data: each
`Data` and `SimpleData` of a placemark becomes a column with its type, next to `name` and
`description` (and `id`, the identifier of the placemark, which an extended data named `id` fills).
The elements of time, display and style of a placemark (`TimeStamp`, `TimeSpan`, `altitudeMode`,
`tessellate`, `extrude`, `visibility`, `drawOrder`, the icon) are not loaded, as the readers of the
catalog do not load them; an extended data with one of those names is lost with them. LIBKML makes a
layer of each folder: a KML whose placemarks sit in several folders fails with `several-layers`, where
the readers of the catalog put every placemark in one table. A KMZ is read through its `doc.kml`, else
its first `.kml`. The readers of the catalog read the same extended data when there is no loader.

**Several layers.** A GeoPackage, a zip of Shapefiles or a file whose GDAL layers are several (such as
the waypoints, routes and tracks of a GPX) fails with `several-layers` and the list of
its layers (`layers`); the person chooses one with `layer=` (`POST /{id}/load?layer=`, or the list on the
page) and the dataset loads it (`layer`). To load another layer, upload the file again as another
dataset.

**States.** `uploaded` (stored, not loaded), `loading` (submitted to the loader, or inside the request
of the readers of the catalog),
`loaded` (a table or a COG, ready to be published), `published` (served by at least one GeoServer
layer, GP-114) and `failed`. With `uploaded` and `failed` come `stateCode` and `stateReason`:

| Code | State | Why |
|---|---|---|
| `crs-missing` | failed | the file states no reference system and none was declared |
| `crs-unknown` | failed | it names a system PostGIS does not know, or a .prj that matches no EPSG system |
| `crs-mismatch` | failed | coordinates outside the degrees of a geographic system: metres taken for degrees |
| `unreadable`, `empty`, `geometry-invalid` | failed | not the format it claims, no feature with a geometry, a geometry PostGIS refuses |
| `several-layers` | failed | a GeoPackage or zip with more than one layer |
| `quota` | failed | the storage of the organization has no room for the table or the COG |
| `geodata-missing` | uploaded | the organization has no credential `geodata` |
| `geodata-unavailable` | uploaded | the geodata database did not answer, or lacks PostGIS raster |
| `loader-unavailable`, `loader-lost`, `loader-error` | uploaded | the loader did not answer, restarted during the job, or failed for a reason that is not the file |

A failed loading leaves nothing in PostGIS: its transaction rolls back and the table of an earlier
version is dropped. The page shows the state and the reason in Italian, and offers to load the file
again with an EPSG code.

**Reference system (GP-324).** The one the file states (a `crs` member, a `srsName`, a `.prj`
identified by its `AUTHORITY` or by its name among the EPSG systems of PostGIS, a GeoPackage, a
GeoTIFF), else the one its format defines (WGS84 for GeoJSON without `crs`, KML, GPX, CZML, CSV
columns of latitude and longitude and a CSV column of GeoJSON, whose coordinates are WGS84 by RFC 7946,
when the CSV has no WKT column), else the code the person declares with `crs=`; never guessed. A
declared code does not replace the one the file states. GML with a URN or an `http://www.opengis.net/def/crs`
URI is read in the axis order of the authority (latitude first for EPSG:4326). `loaded.crs` is
`EPSG:<code>` and `loaded.crsSource` `file`, `format` or `declared`.

**Extent (GP-321).** Computed from the table when it is loaded: `loaded.nativeExtent` in the reference
system of the data, `loaded.extent` in WGS84 degrees `[west, south, east, north]` from every geometry
reprojected, or from the densified outline of a raster; `null` when it cannot be computed reliably.

**Settings and credentials**, shared with the governance of GeoServer (GP-334): the setting
`geoportal.geodata.jdbc-url` of the installation (default `jdbc:postgresql://postgres:5432/geodata`,
or from `geoportal.geodata.host`, `.port`, `.database`), and the credential `geodata` of the
organization (Administration, Credentials), `<user>:<password>` of the login that owns the geodata
database. The database needs PostGIS raster and `postgis.gdal_enabled_drivers = 'GTiff COG'`, as the
server distribution creates it.

**When a job ends.** An extension cannot run work for an organization without a person yet (MK-126):
the catalog takes the outcome of a job over in the next request that reads the dataset (the list or
`GET /{id}`) of a person who edits it (its owner, or a level `edit`, `edit-data` or `manage`), which the
page makes every two seconds while a dataset is loading, with the row locked so that it is done once.
That person stores the COG, attached to the resource of the dataset so that who reads it follows the
sharing; a person who only views or downloads the dataset sees it `loading` at 100% and takes nothing
over, since whoever stores a file keeps full access to it. A takeover in the background waits for
MK-126.

**Limits for now.** Without the loader, the readers of the catalog run inside the request and hold the
file in memory. On Kubernetes the chart of Geoportal sets `geoportal.catalog.loader.url` and
`.source-url` for the loader of the release (MK-129, see `distribution/cloud/README.md`).

## Samples and limits

**Samples.** Twenty-four datasets in ten formats (`src/main/resources/samples/`, synthetic data of invented
places written by `src/samples/generate.py`, see `src/samples/README.md`; titles in `samples.json`, with the
reference system declared for the three CSV that state none) are uploaded
and loaded once into each organization, the first time one of its administrators
opens the catalog, owned by that administrator and shared with the organization at `download`. The
setting `geoportal.catalog.samples=false` of the installation turns them off. They are not loaded at the
first start of the installation itself because an extension cannot act for an organization without a
request of a person yet.

The samples load in a transaction of their own: if they fail, the catalog still lists the data and the
next visit tries them again. Where nothing loads nor publishes, as on the portable (no master key for
the credentials, no GeoServer, no geodata database, no loader), they are stored and not loaded
(`geodata-missing`), like any upload there (`tests/portable.spec.ts`, issue #50). The catalog reads its
credentials (`Credentials`) each in a transaction of its own as well, because a read the kernel refuses
(an installation without a master key) would otherwise mark the transaction of the request for rollback,
even when the catalog goes on without the credential. Without a master key the events of a new
organization (`OrganizationEvents`) are audited as `geoserver.aligned` failed and not delivered again, as
without the credentials of GeoServer (issue #53). The page asks the shell for the permissions of the person
and probes GeoServer only for who holds `manage-geoserver`, so that the others get no refused request
(issue #54).

**Limits for now**, platform needs reported to Mosaikit: a file of the organization in the file service is
readable by every member through `/api/v1/files/dev.geoportal.ext.catalog.geodata`, whatever the sharing of its
dataset; and only the owner of a file or an administrator deletes it, so replacing or deleting a dataset
shared at `edit-data` or `manage` is refused to the others. GeoServer follows the sharing through the
GeoFence rules of the catalog (GP-335, below).

The tests of `src/test` (JUnit) run in `mvn test`; those that need GeoServer only with
`-Pgeoserver` (below).

## GeoServer (GP-334)

The catalog is the only author of GeoServer ([ADR-0014](../../docs/adr/0014-the-catalog-extension-owns-geoserver.md),
[ADR-0017](../../docs/adr/0017-geoserver-aligned-to-the-catalog.md)). Each organization has one
space, named from its identifier: `org_<32 hexadecimal digits>` is its GeoServer workspace (namespace
`urn:geoportal:organization:<id>`) and its schema in the database `geodata`, and the workspace has one
PostGIS store, `data`, on that schema. `OrganizationSpace.of(workspace)` gives the organization back,
for the gateway and the GeoFence rules.

The catalog keeps the layers and styles it publishes (`ext_catalog_geodata.geoserver_layer`,
`ext_catalog_geodata.geoserver_style`) and **aligns** GeoServer to them:

- missing objects are created: schema, workspace, store, styles, layers (in their declared reference
  system, `FORCE_DECLARED`);
- objects of the catalog changed by hand are brought back: title, description, table, reference
  system, enabled, default style, SLD, store parameters (all but the password, which GeoServer
  answers encrypted);
- withdrawn layers and styles are deleted, then forgotten;
- anything else in the workspace (stores, coverage stores, layers, styles) is **drift**: reported in
  the answer and in the audit log, never deleted.

Every step reads first and writes only what differs: a second run changes nothing, and a run
interrupted half-way converges at the next. A change GeoServer refuses (a table that does not exist)
is reported with its error, and the others go on.

### API

For people with the permission `manage-geoserver` (administrators of the organization hold it):

| Call | Does |
|---|---|
| `GET geoserver/alignment` | what an alignment would change, and the drift; changes nothing |
| `POST geoserver/alignment` | aligns; `{workspace, applied, aligned, failed, changes, drift}`; audited as `geoserver.aligned` |
| `GET geoserver/layers`, `PUT`/`DELETE geoserver/layers/{name}` | the layers: `{table, title, description, srs: "EPSG:<code>", style}` |
| `GET geoserver/styles`, `PUT`/`DELETE geoserver/styles/{name}` | the styles: `{sld}`; a style in use is not withdrawn (409) |
| `GET geoserver/reconciliation` | the reconciliation report (GP-118), below; changes nothing |
| `POST geoserver/reconciliation` | aligns, then reports what was aligned, what drifted and what needs a person |
| `POST geoserver/reconciliation/layers/{name}/adopt` | a layer of the workspace unknown to the catalog becomes one of its definitions (audited `layer.adopted`) |
| `DELETE geoserver/reconciliation/{layers\|styles}/{name}` | deletes from GeoServer a layer or style unknown to the catalog (audited `geoserver.drift-removed`) |

Changing a definition does not touch GeoServer: the next alignment does. Answers: 400 invalid name or
reference system, 404 unknown object, 409 missing credential or style in use, 502 GeoServer or the
database refused, with `code`, `action`, `cause` and `reference` (GP-115, below).

### Settings and credentials

Settings of the installation (addresses only; the defaults fit the server distribution):

| Setting | Default | |
|---|---|---|
| `geoportal.geoserver.url` | `http://geoserver:8080/geoserver` | GeoServer as the catalog reaches it |
| `geoportal.geodata.host`, `.port`, `.database` | `postgres`, `5432`, `geodata` | the geodata database as GeoServer reaches it |
| `geoportal.geodata.jdbc-url` | built from the three above | the same database as the catalog reaches it |

Credentials, stored once by a platform administrator in the **Installation credentials** of Mosaikit
(MK-127), meant for `dev.geoportal.ext.catalog.geodata`; an organization that stores one with the same name in
Administration, Credentials uses its own. The catalog reads them at each alignment with
`Secrets.resolve`, also in the handler of an event where no person is signed in, and never returns
them:

| Name | Value |
|---|---|
| `geoserver` | `<user>:<password>` of the GeoServer administrator (`gsadmin`, never `admin`) |
| `geodata` | `<user>:<password>` of the login of the stores on `geodata` (`geoserver` in the server distribution) |

### The life of an organization

The catalog subscribes to the events of Mosaikit (MK-085, `OrganizationEvents`); the handler runs a
few seconds after the change, in the organization and without a person:

| Event | Does |
|---|---|
| `organization.created` | aligns: the schema, the workspace and the store of the new organization, and its base GeoFence rules (GP-335), are made at once, not at its first alignment |
| `extension.enabled` (the catalog) | aligns: what the catalog publishes is served again |
| `extension.disabled` (the catalog) | **withdraws** the publications: every layer of the workspace is disabled and no longer advertised; nothing is deleted (workspace, store, styles, definitions, tables and rows stay); audited as `geoserver.withdrawn` with the layers |

Without the credentials nothing is changed and the outcome is in the audit log (`geoserver.aligned` or
`geoserver.withdrawn`, failed, with the event); when GeoServer or the geodata database do not answer,
or refuse the workspace or the store, the handler fails and Mosaikit delivers the event again later.
A layer GeoServer refuses is reported as in any alignment. Every step is idempotent: an event that
comes twice changes nothing the second time.

### Tests

- `mvn test -Pgeoserver`: `GeoServerAlignmentTest` (tag `GP-334`) starts PostGIS and the GeoServer
  of the server distribution in Docker (Testcontainers; the image `geoportal-server/geoserver:2.28.5`
  when Docker has it, otherwise built from `distribution/server/geoserver`), runs its
  `init/prepare.sh` and checks the alignment: workspaces per organization, WMS and WFS of a workspace
  only, projected layers, hand changes, drift, withdrawals, the withdrawal of the publications when
  the catalog is turned off, idempotence.
- `tests/geoserver.spec.ts` on the bench: the permission, the credentials, the definitions; with
  `GEOPORTAL_TEST_GEOSERVER=1` and `tests/geoserver/compose.yaml` up, also the whole path from the
  credentials of Mosaikit to GeoServer.
- `tests/organization-events.spec.ts` on the bench (with `GEOPORTAL_TEST_GEOSERVER=1`): credentials of
  the installation, an organization created through the API of Mosaikit gets its workspace, store and
  schema, and turning the catalog off and on withdraws and serves again its layers. The `geoserver`
  job of the CI runs it with `geoserver.spec.ts`; `OrganizationEventsTest` (in `mvn test`) checks the
  handler on its own.
- `PublishingRulesTest` (tags `GP-114`, `GP-115`, `GP-118`, in `mvn test`): the translation of styles,
  the gate, the reasons of GeoServer as text, the failures and the reconciliation report.
- `tests/publishing.spec.ts` on the bench (with `GEOPORTAL_TEST_GEOSERVER=1`): the publishing path in
  the page and in the API, failures, health, republish, repoint, unpublish and reconciliation on a real
  GeoServer; its last test stops the GeoServer container and starts it again.

GeoServer traps met here: `/rest/layers/<workspace>:<layer>` is also mapped by GeoWebCache (500
"Ambiguous handler"), so layers are read and changed under `/rest/workspaces/<workspace>/layers`;
GeoServer re-encodes an SLD unless it is stored with `raw=true`.

## Publishing (GP-114, GP-115, GP-118)

One path makes a loaded dataset a service ([ADR-0020](../../docs/adr/0020-publishing-is-a-step-and-layers-are-served-through-the-catalog.md)):
in the details of a loaded vector, **Pubblica** opens the publishing dialog, which shows the
destination (the GeoServer workspace of the organization), the layer name (the technical name of the
dataset, which the person may change) and the **style of Geoportal** (fill, opacity, stroke, width,
size and shape of the point symbol) next to the **SLD** GeoServer will receive, translated by the
catalog as the style changes. Publishing defines the layer and its style and aligns GeoServer at once
(ADR-0017); the dataset becomes `published` and the layer is listed under **Servizi pubblicati** with
its health and the addresses of its WMS, WFS and WMTS on the origin of Geoportal.

A layer serves the table of one dataset and owns one style, named as the layer, with the style of
Geoportal it was translated from (`ext_catalog_geodata.geoserver_layer.dataset_id`,
`ext_catalog_geodata.geoserver_style.source`). Mixed geometries get a rule per dimension. A raster is published as
a coverage of its COG (GP-309, below).

API under `/api/v1/ext/catalog-geodata/publications`:

| Call | Needs |
|---|---|
| `GET /` the publications whose dataset the person sees: name, dataset, style, health, `services` | level `view` |
| `GET /{name}` | `view` |
| `POST /style-translation` `{dataset, layer, style}`: the SLD of a style for the data of a dataset | `view` |
| `POST /` `{dataset, layer, style}`: publishes; 201 with the publication | permission `publish`, level `manage` |
| `PUT /{name}` `{style}`: republishes with the current data and, when given, a new style | `publish`, `manage` |
| `POST /{name}/repoint` `{dataset}`: the layer serves another dataset | `publish`, `manage` on both |
| `DELETE /{name}`: unpublishes | `publish`, `manage` |
| `POST /{name}/health`: asks GeoServer now | `view` |
| `GET, HEAD /{name}/ogc/{wms\|wfs\|ows\|gwc/service/wmts}?<query>`: the services of the layer | `view` |

The permission `publish` ("Pubblicare dati come servizi") is off until the administrators grant it.

**The gate.** The services are served by the catalog, never at the address of GeoServer, and only
for reading that one layer (`OgcGate`); everything else is refused with 400 before GeoServer is called:

- only the virtual services of that layer (`<workspace>/<layer>/wms`, `/wfs`, `/ows` with `service=WMS`
  or `WFS`, `/gwc/service/wmts`), never `/rest`, `/gwc/rest`, `/web` or another path; only GET and HEAD
  (a POST, such as a WFS Transaction in a body, has no route);
- only the read operations, spelled as the standards spell them: WMS GetCapabilities, GetMap,
  GetFeatureInfo, GetLegendGraphic, DescribeLayer; WFS GetCapabilities, DescribeFeatureType,
  GetFeature, GetPropertyValue; WMTS GetCapabilities, GetTile, GetFeatureInfo; a parameter given twice
  is refused, and so is a `service` other than the one of the address;
- only that layer: `layers`, `query_layers`, `layer`, `typeName(s)` name exactly it, qualified or not
  (another layer, a list with another, a group, `*` are refused); `featureId`/`resourceId` only of its
  features; no `storedquery_id`;
- nothing that makes GeoServer fetch an address or read another feature type: no `sld`, `sld_body`,
  `remote_ows_type`, `remote_ows_url`, and no filter with `querySingle`, `queryCollection` or
  `collectGeometries`.

The capabilities are those of the virtual service of the layer, which list only it. Answers stream back
with the headers of the proxy (`ProxyHeaders`) and text answers are rewritten so that every address of
GeoServer becomes the gate and the Authorization of the administrator, if echoed, is removed
(`AnswerRewriter`). The gate reaches GeoServer as its administrator, after checking the level of the
person on the dataset for each request and applying the services of the level as the GeoFence rules do:
`view` gives WMS and WMTS, the features of WFS need `download`; the client is kept a minute per
organization so that the audit of credentials is not read per tile. With the data gateway (GP-336, below)
people are redirected to it and the gate serves links.

**Health.** A publication is `ok` when GeoServer lists it in the capabilities of its WMS and answers a
WFS GetFeature from its table, `failed` otherwise with `layer-missing`, `data-error`,
`geoserver-unreachable` or `credential-missing`. The page asks every listed service when it opens and on
**Controlla ora**; a publication that turns failed is audited (`layer.health-failed`).

**Lifecycle without orphans (GP-118).**

- *Republish* (the dataset loaded again or replaced, or **Ripubblica** with a new style) keeps the name:
  the feature type is brought to the current table and reference system, its bounding boxes are
  recomputed, and the cached tiles of GeoWebCache are deleted (`masstruncate`).
- *Repoint* (**Ripunta**) makes the layer serve the table of another dataset, with the style of
  Geoportal translated again for its geometry; refused by GeoServer, the layer goes back to its dataset.
- *Unpublish* (**Ritira**) withdraws the layer and its own style; GeoServer deletes the tile layer of
  GeoWebCache with the layer; the table and the store stay, the dataset is `loaded`. Deleting a dataset
  unpublishes its layers.
- A publication GeoServer refuses is withdrawn and aligned away, so a created style does not stay behind.

**Reconciliation**, for those who hold `manage-geoserver` (the panel under the services): `aligned`
(objects of the catalog that match), `missing` (in the catalog, not in GeoServer), `changed` (by hand),
`withdrawn` (still in GeoServer), `unknown` (in the workspace, unknown to the catalog) and `failed`;
`needsPerson` counts the last two. **Confronta** reports, **Allinea** aligns and reports; an unknown
layer on the store of the organization can be adopted, an unknown layer or style removed, each by a
person.

**Failures (GP-115)** are answered where the action started, as a problem detail:

| Field | Content |
|---|---|
| `code` | `geoserver-unreachable`, `geoserver-refused`, `geodata-unreachable`, `credential-missing`, `name-in-use` (another layer of the catalog), `name-taken` (a layer or style of GeoServer unknown to the catalog), `not-loaded`, `not-vector` (a raster layer is not repointed), `invalid` |
| `action` | `retry` (may succeed later) or `correct` (change something first) |
| `cause` | what GeoServer answered, as text: the ServiceException of OGC or the body of the error, without markup, stack trace or class names, at most 300 characters |
| `reference` | `GP-` and eight hexadecimal digits, written to the log with the technical message and to the audit |

The dialog shows the message in Italian, the answer of GeoServer, what to do, the reference and
**Riprova**, and keeps the fields to correct.

**Audit.** `dev.geoportal.ext.catalog.geodata:layer.published`, `.republished`, `.repointed` (from, to),
`.unpublished`, `.publish-failed` (action, code, cause, reference), `.health-failed`, `.adopted`, and
`geoserver.drift-removed`.

**Limits for now.** Unpublishing removes the GeoFence rules of the layer at the rewrite that follows its
alignment, but not a catalog record, which does not exist yet (the pycsw catalog of ADR-0009), and the dialog cannot say which maps use a layer until the
maps are on GeoLibre and register what they use; indexing failures of the catalog (CSW / OGC API
Records) come with it.

## GeoFence rules (GP-335)

The catalog is the only author of the GeoFence rules of the workspace of each organization
([ADR-0021](../../docs/adr/0021-geofence-rules-written-as-a-whole-per-organization.md)): it generates them
from the sharing of the dataset of each published layer in Mosaikit, writes them as a whole, reads them back
and compares, and removes any rule of the workspace it did not write.

| Who | GeoFence rule |
|---|---|
| a person the dataset is shared with, its owner | a user rule with the user name of the person (their sign-in address) |
| a team | a role rule with the identifier of the team |
| the organization | a role rule with the identifier of the organization (its members, not its guests) |
| the public | a rule for everyone |
| the administrators of the organization | `<organization identifier>:admin`, the whole workspace |
| anybody else | the `DENY` of the workspace, the last rule |

`view` gives WMS and the tiles of GeoWebCache, `download` and above also WFS and WCS; WFS transactions and
locks are refused to everybody but the surveyors assigned to a layer (GP-036, below). A person limited by data rules (GP-308) gets user rules in front of all the
others: a `LIMIT` with the area, an `ALLOW` per service with the row filter and the hidden columns, a `DENY`
of the layer, so that no team or organization grant opens more. The roles are those the role service of
GeoServer reads from the published views of Mosaikit (GP-336, ADR-0016).

**Writing.** Every rule of the workspace is deleted and the set written in ascending priority in a band of
free priorities (no rule of another organization moves), each rule in the format GeoFence 2.28.5 keeps (an
area as JSON, columns as XML), read back and compared field by field; the cache of GeoServer is emptied. The
outcome (`written`, `unchanged`, `mismatch`, `failed`), the rules removed because the catalog had not written
them and the differences are kept in `ext_catalog_geodata.geofence_rule_set`, shown in the reconciliation and audited
as `dev.geoportal.ext.catalog.geodata:geofence.rewritten` (failed when GeoFence holds something else).

**When.** After every alignment (publish, republish, repoint, unpublish, the reconciliation with `POST`), on
`POST geoserver/rules`, and when the rules generated now differ from those last written, checked at most every
15 seconds per organization by the lists of uploads and publications and by the gate: that is how a share made
in the dialog of Mosaikit reaches GeoFence, since Mosaikit does not tell extensions about grants yet. A team
change needs no rewrite: GeoServer reads the members of a role.

**API**, for those who hold `manage-geoserver`: `GET geoserver/rules` (the generated rules, one line each,
what they cannot express, and a comparison with GeoFence), `POST geoserver/rules` (rewrite now); the
reconciliation report has `rules`, and its `needsPerson` counts the differences.

**Credential** `published-views`: `<user>:<password>` of a login that holds `mk_<database>_reader` (MK-123), to
read the user names of people and the members of teams; setting `geoportal.catalog.published-views.jdbc-url`,
by default the database of the installation. Without it, the grants to people are not written (the report
says so) and they are denied.

**Tests.** `RuleSetTest` (tag `GP-335`, in `mvn test`): the rules generated, their formats, the comparison,
the bands of priorities. `GeoFenceRulesTest` (tags `GP-335`, `geoserver`): on the GeoServer of the
distribution, with people and roles taken from headers, what WMS, WFS, GetFeatureInfo and the tiles answer
to each person with the generated rules, the read back, the traps of the formats, hand rules, bands;
The same rules through the gateway and through AuthKey, data rules included, are proved on the server stack by
`tests/gateway/gateway.e2e.ts` (GP-336). `tests/access-rules.spec.ts` on the bench
with GeoServer: sharing in Mosaikit becomes rules, unsharing removes them, a hand rule in the reconciliation
of the page.

## Data rules (GP-308)

A data rule limits what a subject, a person or a team, sees of a published dataset
([ADR-0008](../../docs/adr/0008-data-rules-distinct-from-permissions.md)): an **area** (a polygon in the
reference system of the data, drawn on the map of the layer or written as WKT), a **row filter** (CQL) and
**hidden columns**. Rules never open what the sharing does not; with no rule the sharing alone decides. Those
who manage the dataset (level `manage`) write them in the details of the publication, **Regole sui dati**.

- **Checked before they are kept.** The subject is a person of the organization (by e-mail address) or one of
  its teams (read from the published views of Mosaikit); PostGIS checks the area (polygonal, in the system of
  the data, repaired when it crosses itself, kept as `SRID=<code>;MULTIPOLYGON(...)`); GeoServer checks the
  row filter on the layer (`resultType=hits`); hidden columns are columns of the layer, never its geometry. At
  least one limit. Refusals: 400 `invalid`, `invalid-area`, `invalid-filter`, with the reason.
- **Composed.** All the rules that reach a person on a dataset, their own and those of their teams, apply
  together and the answer holds only what all allow: the areas intersect (PostGIS; nothing in common, nothing
  at all), the row filters join with `AND`, the hidden columns add up.
- **Enforced by GeoFence** (GP-335): the composed limits become user rules of the person in front of every
  role rule, a `LIMIT` with the area and an `ALLOW` per service of their level with the filter and the columns,
  so WMS GetMap and GetFeatureInfo, WFS and every client of GeoServer get the same answer. Every change writes
  the rules again. If the people a rule reaches cannot be named (no credential `published-views`), the layer is
  closed to everyone but the administrators until they can. Tiles of GeoWebCache are refused to limited people
  (WMS serves them the map). The gate of the catalog, which reaches GeoServer as its administrator, refuses a
  limited person with 403 `data-rules`: they get the layer through the gateway of GP-336.

API under `/api/v1/ext/catalog-geodata/data-rules` (level `manage` on the dataset):

| Call | Does |
|---|---|
| `GET ?layer=` | the rules of the dataset of the layer, its reference system and extent, its columns, the teams |
| `POST` `{layer, subject: {kind: person, email} or {kind: team, id}, description, area, cql, hidden}` | 201 `{rule, geofence}` |
| `PUT /{id}` the same | `{rule, geofence}` |
| `DELETE /{id}` | `{geofence}` |

`geofence` is the outcome of the rewrite of the GeoFence rules. **Audit**: `data-rule.created`, `.changed`,
`.deleted`. **Tests**: `DataRulesCompositionTest` (tag `GP-308`); the GP-308 tests of `GeoFenceRulesTest` on
GeoServer (area in GetMap, GetFeatureInfo and WFS; row filter; hidden column; two rules intersect; areas
checked by PostGIS); `tests/data-rules.spec.ts` on the bench with GeoServer (the page in Italian, checks,
GeoFence, the gate); through the gateway and AuthKey (QGIS), `tests/gateway/gateway.e2e.ts`.

## The data gateway (GP-336)

In the server distribution the geodata do not pass through Geoportal: nginx in front of GeoServer, on
the origin of Geoportal, asks the catalog for a go-ahead and passes the bytes
([ADR-0022](../../docs/adr/0022-the-gateway-asks-as-the-person.md), ADR-0006, ADR-0016). The catalog
knows it is there by `geoportal.gateway.enabled=true`.

| Address | Who | What |
|---|---|---|
| `/gw/ows/<organization>/<wms\|wfs\|wcs\|ows\|gwc/service/wmts>` | the session of Geoportal, or `authkey=<personal key>` | the workspace of the organization, as GeoServer answers for that person |
| `/gw/ows/<organization>/<layer>/<service>` | the same | one layer |
| `/gw/link/<secret>/<dataset>/<wms\|wfs\|ows\|gwc/service/wmts>` | a link of Geoportal to a dataset, no account | the one layer of the dataset, through the gate of the catalog |

- **Go-ahead**: `GET /api/v1/ext/catalog-geodata/gateway/check`, called by the gateway *as the person* (session
  cookie and `X-Mosaikit-Organization`, or the personal key as bearer), with `X-Gateway-Organization`,
  `X-Gateway-Workspace` and `X-Gateway-Served`: 200 with `user`, `organization` and `workspace`; 403
  when the session or the key acts for another organization, and `Quota exceeded` beyond the quota.
  Mosaikit refuses ended sessions, revoked keys and guests before the catalog. The gateway keeps the
  answer ten seconds.
- **AuthKey**: `GET /api/v1/ext/catalog-geodata/gateway/authkey?key=`, for the service identity of the gateway
  only (role `service`): the username of the person of a personal key, through `POST
  /api/v1/services/check` of Mosaikit (the identity needs the endpoint `check-key`), or an empty answer.
  The catalog reaches its kernel at `geoportal.catalog.kernel-url` (default
  `http://127.0.0.1:<quarkus.http.port>`).
- **Links**: `GET /api/v1/ext/catalog-geodata/shared-layers/{dataset}` (title, layer, extent) and
  `.../{dataset}/ogc/{path}` (the gate), `@PermitAll`: `Sharing` answers for that dataset first, so a link
  opens only its resource. Datasets accept links (`subjects` with `link`, `linkLevel: view`), and the page
  of a link (`/shared/<secret>`) shows the element `catalog-shared-dataset`: title, a preview and the
  addresses of WMS and WMTS through the gateway; the features of WFS need the level `read`, which a
  link does not give (GP-335).
- **Quota**: the measure `data-requests` (unit `requests`, a counter per month) counts what the gateway
  and the gate let through, for the person who asked; platform administrators set its limit per
  organization (`/api/v1/organizations/{slug}/quotas/dev.geoportal.ext.catalog.geodata/data-requests`) or for all
  (`mosaikit.quotas.limits."dev.geoportal.ext.catalog.geodata/data-requests"`).
- **People** see the addresses of the gateway in the services of a publication, with how to use them in
  QGIS; the gate of a layer (`/publications/{layer}/ogc/...`) answers them with a redirect there.

**Tests.** `src/test/.../gateway/GatewayTest.java` (counting and addresses) and
`tests/gateway/gateway.e2e.ts` on the server stack, every acceptance criterion of GP-336:

```bash
mvn install -DskipTests -Dskip.npm=true            # at the root: target/dist/geoportal-server
cd ../../target/dist/geoportal-server && cp ../../../extensions/ext-catalog-geodata/tests/gateway/test.env .env
docker compose -p geoportal-f04-gp336 -f compose.yaml -f ../../../extensions/ext-catalog-geodata/tests/gateway/compose.test.yaml up -d --build
cd ../../../extensions/ext-catalog-geodata && npx playwright test -c playwright.gateway.config.ts
docker compose -p geoportal-f04-gp336 down -v       # in target/dist/geoportal-server
```

The GeoFence rules are those of GP-335, generated from the sharing of the datasets: the test shares
nothing by hand in GeoServer.

## Editing of layer data (GP-036)

Who writes the data of a published layer ([ADR-0023](../../docs/adr/0023-layer-data-written-by-assigned-surveyors.md))
is a **surveyor**: a person who holds the permission `edit-layer-data` ("Modificare i dati dei livelli
assegnati") and to whom the layer is assigned. The catalog and GeoServer give the same answer.

**Profiles.** The permission is off until the administrators of the organization put it in a profile
(Administration, Permissions, MK-056). Two profiles make the jobs of GP-036:

| Profile | Permissions of the catalog | Does |
|---|---|---|
| Editor | `use-catalog`, `publish` (and those of maps and dashboards) | publishes, without `edit-layer-data`: never writes source data, whatever the level on a dataset |
| Rilevatore (surveyor) | `use-catalog`, `edit-layer-data` | edits the data of the layers assigned to them, and of no other layer |

The administrators of the organization hold every permission, but write the data of a layer only when it is
assigned to them too.

**Assignments** (`ext_catalog_geodata.layer_assignment`) are made by who manages the dataset of the layer, in the
details of the publication (**Rilevatori**: the people assigned, their level on the dataset, whether their
profile holds the permission; **Assegna**, **Togli**). A surveyor sees **Assegnato a te** on their layers.
Assigning and removing write the GeoFence rules again at once and are audited
(`dev.geoportal.ext.catalog.geodata:layer.assigned`, `layer.unassigned`): the next request of the surveyor already has the
new answer, without signing out.

**GeoFence.** For each surveyor of a layer, a user rule allows `TRANSACTION` and `LOCKFEATURE` of WFS on
that layer, in front of the denial of every write of the workspace. Mosaikit tells the catalog the permissions
of the person of the request only, so each request of an assigned person to the catalog records whether they
hold `edit-layer-data` (`holds`): their rule is written once a request found it, and removed when one finds it
gone. The page asks `GET editing` when it opens, and the go-ahead of the data gateway (GP-336, every ten seconds
of use per person) confirms it too, so a surveyor who edits from QGIS is confirmed at their first request.

A surveyor limited by data rules (GP-308) on a layer writes within them
([ADR-0025](../../docs/adr/0025-surveyors-limited-by-data-rules-write-within-them.md)): for `TRANSACTION` and
`LOCKFEATURE` a `LIMIT` with the area and an `ALLOW` whose `layerDetails` carry the row filter as `cqlFilterRead`
and `cqlFilterWrite`. GeoServer then refuses an insert outside the area or the filter and updates or deletes only
the features inside both. Rules that hide columns, or whose areas have nothing in common, give no write rule
(the report says so): GeoServer cannot keep writes within them.

**API** under `/api/v1/ext/catalog-geodata/editing`:

| Call | Needs |
|---|---|
| `GET /` whether the person holds the permission, and their assigned layers | signed in |
| `GET /people` the members of the organization a layer can be assigned to | level `manage` on a published dataset; credential `published-views` |
| `GET /layers/{name}/assignments` the surveyors of a layer | `manage` |
| `PUT`, `DELETE /layers/{name}/assignments/{account}` | `manage` |
| `POST /layers/{name}/transaction` a WFS `Transaction` (XML), forwarded to the WFS of that layer | `edit-layer-data` and the assignment |

A transaction is refused with 403 and `code` `permission` or `not-assigned` (audited `layer.edit-refused`), or,
for a surveyor limited by data rules, `data-rules-service` (they write within them through the WFS service of the
layer: the catalog forwards a transaction as the administrator of GeoServer, who has no limits) or `data-rules`
(their rules hide columns or leave nothing visible). `GET /` gives each layer `dataRules` with the same code, or
null, and the page says where and within what the surveyor edits. A transaction is refused
with 400 when it names another layer or workspace, has a `Native` action or a DTD; applied, it answers the
`TransactionResponse` of GeoServer (audited `layer.edited` with the count of each action). Replacing or loading
again the file of a published dataset (`PUT uploads/{id}/content`, `POST uploads/{id}/load`) also needs
`edit-layer-data`, and either the level `manage` or every layer it serves assigned.

**Tests.** `SurveyorRulesTest` and `WfsTransactionTest` (tag `GP-036`, in `mvn test`); `SurveyorEditingTest`
(tags `GP-036`, `geoserver`): WFS-T Insert and Update of a surveyor, of an editor and on another layer against
the GeoServer of the distribution, a removed assignment, and (tag `GP-308`) Insert, Update and Delete of a surveyor
limited by an area and a row filter. `tests/editing.spec.ts` on the bench: the
permission and the two profiles, the refusals of the API; with GeoServer, the whole path in the page in Italian.

## Rasters, and data rules where they cannot apply (GP-309)

**Rasters.** A loaded raster dataset (its Cloud Optimized GeoTIFF in the file service, GP-937) is published
with the same **Pubblica** and `POST publications` as a vector, without a style of Geoportal: the catalog
sends the COG to GeoServer (`PUT coveragestores/<layer>/file.geotiff`), which keeps it in its data directory
as the coverage store `<layer>` with one coverage and one layer of that name, drawn with the default raster
style. The layer is marked `raster` (`ext_catalog_geodata.geoserver_layer.kind`): the alignment leaves its coverage
store alone (it is not drift), its health is a small WMS image, and it has sharing, GeoFence rules and the gate
as any layer. Republishing sends the file again; repointing is refused (`not-vector`); unpublishing, or the
dataset deleted or no longer an image, deletes the coverage store with its file.

**Data rules on a raster** (GP-308) limit the area only: a row filter or hidden columns are refused (400). The
area becomes a GeoFence `LIMIT` with `spatialFilterType` `CLIP`, so the images GeoServer returns to the person
are cut to it (the areas of vectors keep `INTERSECT`, the features that touch the area). The rule is written as
JSON, the format that keeps its mode, and read back with it.

**External services.** A third-party server cannot be made to filter what it answers, so a data rule on a
layer of an external service (GP-939), or on the whole service, never filters: it makes the layer unavailable
to the person or team it names, and says why. Rules are kept in `ext_catalog_geodata.service_data_rule`; who manages the
service adds and removes them in the details of the service (**Regole sui dati**: layer or the whole service,
the e-mail of the person, an area or a row filter), and the person sees **Non disponibili per te** with the
reason, the layer greyed out and no preview.

| Call | Needs |
|---|---|
| `GET service-rules?service=` the rules of a service, each with `effect: unavailable` and its `message` | `manage` on the service |
| `POST service-rules` `{service, layer, subject: {kind, email \| id}, description, area, cql, hidden}` | `manage` |
| `DELETE service-rules/{id}` | `manage` |
| `GET service-rules/mine?service=` what the person may not use of the service, and why | `view` |

The proxy refuses, with 403 and `code` `data-rule-not-applicable`, the layer and the message, every request of
the subject that names the layer (`layers`, `query_layers`, `layer`, `typeName(s)`, `coverage(Id)`,
`identifier`, qualified or not, or a segment of the path), and every POST (the proxy does not read the body);
a rule on the whole service refuses everything, capabilities included. When the published views of Mosaikit
cannot be read, the service is refused to everybody with a rule (fail closed). What the person may not use is
kept 15 seconds per service, and computed again at once when a rule changes. A service used directly, without
the proxy, is reached by the browser and cannot be refused: the page hides its layers and says why. Audit:
`dev.geoportal.ext.catalog.geodata:service-rule.created`, `.deleted`.

**Tests.** `RasterRulesTest` and `ServiceRulesTest` (tag `GP-309`, in `mvn test`); `RasterClipTest` (tags
`GP-309`, `geoserver`): a COG coverage on the GeoServer of the distribution, the images WMS returns to the
person with an area rule (cut) and to the others (whole), the read back of the clipping rule, the coverage store
not drift. `tests/rasters.spec.ts` on the bench with GeoServer: publishing a raster through the catalog, the
rule in GeoFence, unpublishing. `tests/service-rules.spec.ts` on the bench with a stub WMS: the proxy refuses
the layer to the subject with the reason, the page says it in Italian.

## External services and their proxy

An external service is an OGC service of another server (WMS, WMTS, WFS, WCS) registered in the
catalog of the organization (GP-939), kept in `ext_catalog_geodata.external_service` and registered with
Mosaikit as a resource of type `service`, with the levels `view` (see it), `use` (call it, through the
proxy too) and `manage` (change, share, delete it). Many public map servers send no CORS headers, so a
page of Geoportal cannot read them: with the **proxy** on, the map asks
`/api/v1/ext/catalog-geodata/services/{id}/proxy?<query>` on the origin of Geoportal and the catalog forwards
the request to the registered address. The proxy is not a relay: the decisions are in
[ADR-0018](../../docs/adr/0018-the-proxy-reaches-only-registered-public-services.md).

API under `/api/v1/ext/catalog-geodata/services`:

| Call | Needs |
|---|---|
| `GET /` the services the person sees, each with `manage` and its `proxyUrl` | level `view` |
| `POST /` `{title, kind, url, proxy, crs, credential, authScheme}`; the answer has `access` (below) | permission `register-service` |
| `GET /{id}` | `view` |
| `PUT /{id}` the same fields | `manage` |
| `DELETE /{id}` the service and its grants | `manage` |
| `POST /{id}/access` checks again whether the browser reads it, and turns the proxy on if it cannot | `manage` |
| `GET, HEAD, POST /{id}/proxy[/path]?query` the service through the proxy | `use`, and the proxy on (`manage` also with it off, to check it) |
| `GET /{id}/features?typeName=&bbox=&count=` features of a WFS as GeoJSON | as the proxy |

What the proxy does:

- It forwards **only to the registered address** of that service: the path of a request goes below the
  registered path, its query follows the registered query (whose parameters cannot be overridden), and
  nothing in the request names another target. A path with `..`, an encoded slash or backslash, or a
  control character is refused.
- It reaches **only public addresses**, `http` or `https`: the name is resolved once per request, every
  address it gives is checked, and the connection goes to a checked address (no second resolution, so
  no DNS rebinding). Loopback, private, link-local, unique-local, carrier-grade NAT, reserved and
  multicast ranges, also inside IPv6 forms, are refused, and so is a name with one such address among
  others. Redirects are followed only on the origin of the service, three at most.
- **Methods**: GET, HEAD, and POST with an XML body or a form (the POST requests of OGC), at most
  `max-request-bytes`; other methods are refused.
- **Headers** cross by allow-list: nothing of the person reaches the service (no cookie, no
  Authorization, no headers of Mosaikit or of the gateway), nothing of the service that could act on
  the origin of Geoportal reaches the browser (no Set-Cookie, WWW-Authenticate, CORS, HSTS, Location).
  Every answer carries `X-Content-Type-Options: nosniff` and a sandboxing Content-Security-Policy. A 401
  of the service becomes 502, so that it never reads as the session of Geoportal ending.
- **Answers stream** to the browser, up to `max-response-bytes`; at most `concurrent-per-service`
  requests go to one service at a time.
- **Feature services**: a GetFeature through the proxy of a WFS gets the reference system declared for
  the service (`crs`, EPSG:4326 by default) when it names none, and `features` asks for features
  directly (WFS 1.1.0, GeoJSON, `srsName`, `maxFeatures`) without the GetCapabilities and
  DescribeFeatureType that servers behind a proxy often refuse.
- **Credentials**: a service may name a credential of Mosaikit (`credential`, with `authScheme` `basic`,
  value `user:password`, or `bearer`, a token), which the administrators keep in **Credentials**,
  meant for `dev.geoportal.ext.catalog.geodata` or for every extension. The proxy reads it on the server and sends
  it as `Authorization`; it never reaches the browser, and the API shows only its name. Attaching one,
  or moving a service that has one to another address, needs the permission `service-credentials`, so
  that nobody sends a credential of the organization to a server of their choice. Mosaikit audits
  every read of a credential, so the proxy keeps the header in memory for one minute per organization
  and credential: a replaced credential is used within a minute.
- **Text answers** (capabilities, XML, JSON, HTML) are rewritten on their way back (GP-334): every
  address of the service (same scheme, host and port) becomes the address of its proxy, so that a map
  that reads the capabilities asks the proxy for the maps too, never the service, which would refuse it
  without the credential; and whatever of the credential a service echoes (the token, `user:password`,
  the password) is replaced with `[credential removed]`. Images pass unchanged. A service shared at
  `use` thus gives its layers to another person, who never receives its credential in any answer
  (`tests/connections.spec.ts`).

**The proxy turns on by itself** (GP-297). Whoever registers a service should not have to know whether
its server sends CORS headers. A service saved without the proxy and without a credential (on
registration, or when its address or kind change) is checked from the server (`BrowserProbe`): the
catalog asks its capabilities as the page would, with the `Origin` of the shell, through the address
checks of the proxy, and looks at what the browser would look at:

- the answer must be **capabilities** (its root element `…Capabilities`); an error page, an exception
  report or an error status is `unreadable`, from the browser or the proxy, and the proxy is left as it
  was;
- **CORS**: exactly one `Access-Control-Allow-Origin`, `*` or the origin of the shell;
- **the policy of the shell** (MK-128): its `connect-src` must let scripts contact the origin of the
  service. The kernel serves the policy with every answer, so the catalog reads it from the kernel
  itself and keeps it 30 seconds; the platform administrators list the origins in **External origins**
  (or `mosaikit.content-security-policy.allowed-origins`). Without a policy every origin is allowed.

When both hold the browser reads the service directly and the proxy stays off; otherwise the proxy is
turned on and the answer says why: `access` is `{outcome: direct | proxy | unreadable, reasons: [cors,
csp], origin, proxyTurnedOn, detail, checkedAt}`, only in the answers of saving and checking. The page
shows it as a notice. The server reaches more than the browser, so it never asks "can I read it?" but
"would the browser be allowed to read what I read?". A service that a person asked with the proxy on,
or with a credential (which only the proxy sends), is not checked; the check never turns the proxy off,
which stays a choice of a person (**Spegni il proxy**). **Verifica l'accesso** in the detail checks a
service again.

Settings of the installation:

| Setting | Default | Meaning |
|---|---|---|
| `geoportal.catalog.proxy.allowed-networks` | none | literal networks in CIDR, comma separated, that the proxy may reach although internal, such as the map server of an intranet; the metadata addresses of cloud providers stay refused |
| `geoportal.catalog.proxy.connect-timeout-ms` | 10000 | wait for the connection |
| `geoportal.catalog.proxy.read-timeout-ms` | 30000 | wait for each read: 504 beyond it |
| `geoportal.catalog.proxy.max-response-bytes` | 104857600 | largest answer passed on |
| `geoportal.catalog.proxy.max-request-bytes` | 1048576 | largest body of a POST: 413 beyond it |
| `geoportal.catalog.proxy.concurrent-per-service` | 8 | requests to one service at a time; the others wait up to 20 s, then 503 |

**Audit.** `dev.geoportal.ext.catalog.geodata:service.registered`, `.changed` (with the fields changed and the proxy
before), `.removed`, and `.refused` / `.change-refused` for an address the proxy does not reach (denied);
with the outcome of the check of GP-297 (`access`, `reasons`, `proxyTurnedOn`) when there was one, and
`.access-checked` or `.proxy-turned-on` for a check on demand.
The calls through the proxy are not audited, a map makes hundreds.

**The page** lists the services under the datasets; a person with `register-service` registers one,
and choosing a service lists its layers from the capabilities (read from the browser, or through the
proxy when it is on) and shows a preview of a WMS layer through the same address the map uses. An
answer that is not capabilities, such as an error page with status 200, lists no layer.

**Limits for now**: the maps of Geoportal are not on GeoLibre yet, so the proxy address is used by the
preview of the catalog; the gateway (`/gw/…`, ADR-0006) can expose the same route when the maps move.
