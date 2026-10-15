# Geoportal

Geoportal is a solution built on [Mosaikit](https://github.com/mosaikit/mosaikit). It brings
together the Geoportal extensions, the common extensions of
[mosaikit-ext](https://github.com/mosaikit/mosaikit-ext) it composes, its branding and the
distributions that ship them with the geospatial stack: PostGIS, GeoServer with GeoFence, and
Keycloak.

## Extensions

Geoportal composes the common extensions of mosaikit-ext and adds the geospatial ones
([ADR-0024](docs/adr/0024-geoportal-composes-mosaikit-ext.md)):

| Extension | From | What |
|---|---|---|
| `ext-catalog` | mosaikit-ext | file manager: folders, sub-folders, sharing |
| `ext-catalog-connection`, `ext-catalog-connection-<postgres\|mqtt\|kafka\|rest\|...>` | mosaikit-ext | connections to data sources and the generic connectors |
| `ext-dashboards`, `ext-processes` | mosaikit-ext | dashboards, process editor and executor |
| `ext-maps` | Geoportal | maps |
| `ext-catalog-geodata` | Geoportal | geodata: PostGIS/COG loading, GeoServer/GeoFence, publishing, data rules, gateway, OGC proxy |
| `ext-dashboards-maps` | Geoportal | the map widget of dashboards |
| `ext-processes-geodata` | Geoportal | geospatial process nodes |
| `ext-catalog-connection-<ogc\|csw\|arcgis\|tiles\|stac>` | Geoportal | geospatial connectors |

Folders and artifacts are named `ext-<extension>[-<sub>]`, manifest ids `dev.geoportal.ext.<extension>[.<sub>]`.
The geodata of the catalog are `extensions/ext-catalog-geodata` (GP-983), next to the file manager and
the connections of mosaikit-ext, which the builds, the benches and the distributions take from the
commit pinned in `mosaikit-ext.json` (built by `tools/mosaikit-ext.mjs` until they are published). Maps,
dashboards and processes are still the extensions of today, to be split and replaced one at a time. The generic requirements
of catalog, dashboards and processes are now `EXT-nnn` in mosaikit-ext; their GP files say where
they went (`moved-to`).

```
pom.xml            aggregator: extensions, branding, distributions
package.json       npm run dev: Mosaikit with every extension and the Geoportal theme
mosaikit-ext.json  the commit of mosaikit-ext whose extensions Geoportal composes (tools/mosaikit-ext.mjs)
extensions/        ext-catalog-geodata, maps, dashboards, processes, each with the requirements it implements alone
branding/          theme (the Geoportal colours)
distribution/      installation, portable, server (Docker Compose), cloud (Helm)
docs/requirements/ GP-nnn.md of the product as a whole, in the format of Mosaikit
docs/adr/          architecture decision records
docs/design/       the mockup of the Geoportal pages
```

Each extension is still a project of its own, whose parent is the Mosaikit extension parent. Each
one builds, tests and runs alone from its own directory (`mvn verify`, `npm run dev`). The root is
only an aggregator for working on the whole solution.

## Develop

You need Java 25, Maven 3.9, Node 24 and, for the server distribution, Docker.

| To | Run at the root |
|---|---|
| start Mosaikit with every extension and the theme, until Ctrl+C | `npm install` once, then `npm run dev` |
| build every extension and every distribution | `mvn install -DskipTests` |
| also run the tests of every extension | `mvn verify` |
| also build the portable archives | `mvn install -DskipTests -Pportable` |
| work on one extension only | `cd extensions/ext-catalog-geodata`, then `npm run dev` or `mvn verify` |

Until its first release, Geoportal builds on the latest snapshot of Mosaikit (`0.1.0-SNAPSHOT`), which
Maven keeps in the local repository (`~/.m2`) and never updates on its own. The build of the
distributions brings it up to the one published from the main branch of Mosaikit when the published
one is newer (`distribution/tools/MosaikitSnapshot.java`); a Mosaikit built and installed locally
after it is kept. When the extension API is among the files refreshed, the build says so: the
extensions built before it used the old one, so run the build again. To refresh before building,
or with no network to check: `java distribution/tools/MosaikitSnapshot.java`, and
`-Dmosaikit.snapshot.skip=true` to build on the local snapshot as it is.

The portable archives build on Linux, macOS and Windows. On Windows they need Git for Windows: the
build runs its bash (`%ProgramFiles%/Git/bin/bash.exe`, or `-Dportable.bash=<path>`), never the
bash of WSL.

`npm run dev` first packages the extensions (the Java code of the geodata needs it) and, once, those of
mosaikit-ext of `mosaikit-ext.json` (git, Maven). It then starts
an installation on an embedded PostgreSQL, with an organization whose people it prints in the
console. A change to a frontend shows at once in the open page. Java code and migrations are read
again at the next start.

## Distributions

`mvn install` writes them in `target/dist`, and their zips in `distribution/target`. Each one is the Mosaikit distribution of the
same kind, with the Geoportal extensions signed by a key of the build. The installation trusts that
key and accepts only signed packages (`mosaikit.extensions.signatures=required`). The theme is
`dev.geoportal.theme`.

| Distribution | Directory in `target/dist` | Zip in `distribution/target` | Contents |
|---|---|---|---|
| Installation | `geoportal` | `geoportal-installation-<v>.zip` | for a server or a PC with Java 25 and PostgreSQL 18 with PostGIS |
| Server | `geoportal-server` | `geoportal-server-<v>.zip` | Docker Compose: Geoportal, PostGIS, GeoServer (GeoFence, Importer, AuthKey), Keycloak |
| Cloud | `geoportal-cloud` | `geoportal-cloud-<v>.zip` | Helm chart: the Mosaikit chart with the Geoportal image, and GeoServer |
| Portable | `geoportal-portable` (`-Pportable`) | | runs without installing anything; no GeoServer and no PostGIS |

To try the server distribution:

```bash
mvn install -DskipTests
cd target/dist/geoportal-server
docker compose up -d --build
```

Then open <http://localhost:8080> and sign in as `admin` with `GEOPORTAL_ADMIN_PASSWORD` from
`.env`. The README of each distribution has the details.

The Mosaikit version comes from `mosaikit.version` in `distribution/pom.xml`, and from the parent
of each extension.

## Licence

Geoportal is free software under the GNU Affero General Public License, version 3 or any later
version (`AGPL-3.0-or-later`, [LICENSE](LICENSE)). Its source code is at
<https://github.com/antus/geoportal>.

It is built on [Mosaikit](https://github.com/mosaikit/mosaikit) and
[mosaikit-ext](https://github.com/mosaikit/mosaikit-ext), which are under the Mozilla Public License
2.0. The two combine: the MPL 2.0 lets its files be part of a larger work under the GNU GPL family
(its section 3.3), and the files of Mosaikit stay under the MPL 2.0. The distributions carry both
texts in `bin/licenses/`.

Every file states its licence, in an SPDX header or in [REUSE.toml](REUSE.toml), and the CI checks
it with `reuse lint` ([REUSE](https://reuse.software)). The exceptions: the sample datasets of the
catalog and the fixtures of the loading tests are synthetic data under CC0-1.0, and two configuration
files based on the ones of Apache Tomcat and of the nginx image keep their Apache-2.0 and
BSD-2-Clause parts. The texts are in [LICENSES](LICENSES).
