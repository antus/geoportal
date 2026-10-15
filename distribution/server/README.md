# Geoportal with Docker Compose

Geoportal, PostgreSQL 18 with PostGIS, GeoServer (with GeoFence, Importer and AuthKey), Keycloak and the
data gateway in front of Geoportal and GeoServer, configured to work together.

## Start

```bash
cd geoportal-server
docker compose up -d --build
```

Change the passwords in `.env` first: they are used only when the databases and the
administrators are created. The first start takes a few minutes: it builds the image of
GeoServer (downloading its extensions once) and the one of Geoportal from `../geoportal`.

| Service | Address | Sign in |
|---|---|---|
| Geoportal, with its geodata at `/gw/` | <http://localhost:8080> (`GEOPORTAL_PORT`) | `admin` with `GEOPORTAL_ADMIN_PASSWORD` |
| Keycloak | <http://localhost:8180> (`KEYCLOAK_PORT`; change `KEYCLOAK_PUBLIC_URL` with it) | `admin` with `KEYCLOAK_ADMIN_PASSWORD` |
| GeoServer | <http://127.0.0.1:8280/geoserver> (`GEOSERVER_PORT`, this computer only) | `gsadmin` with `GEOSERVER_ADMIN_PASSWORD` |

A rebuild of Geoportal refreshes the other files of this folder but keeps `.env`, so the folder can
stay in use across builds; `docker compose up -d --build` then brings the new image.

## Databases

| Database | User | Content |
|---|---|---|
| `mosaikit` | `mosaikit` | Geoportal: organizations, people, and a schema per extension (`ext_catalog`, …) |
| `keycloak` | `keycloak` | Keycloak |
| `geoserver` | `geoserver` | the GeoFence rules (schema `geofence`) |
| `geodata` | `geoserver` | the data of Geoportal, with PostGIS and PostGIS raster: a schema per organization, `org_<identifier>`, where the catalog loads uploaded vectors (GP-937), the store `data` of its workspace; GDAL may read GeoTIFF and write COG there (`postgis.gdal_enabled_drivers`) |

## GeoServer

Its administrator is `gsadmin` (`GEOSERVER_ADMIN_USER`), never `admin`, which is the administrator of
Geoportal. At every start `geoserver-init` writes the GeoFence rule that denies everything not opened
by a rule of Geoportal, and GeoServer starts with the data security of GeoWebCache on (`gwc-gs.xml`):
without it a cached tile is served to anybody, whatever the rules. The rules of each workspace are written
by the catalog only, as a whole ([ADR-0021](../../docs/adr/0021-geofence-rules-written-as-a-whole-per-organization.md)):
a rule added by hand in a workspace is removed at the next rewrite. Workspaces, stores, layers and
styles are made by the catalog only: one workspace per organization, aligned to the catalog (see the
README of the catalog extension); do not change them by hand, the next alignment brings them back.

The catalog reads three credentials. `geoserver` with `gsadmin:<GEOSERVER_ADMIN_PASSWORD>` and
`geodata` with `geoserver:<GEOSERVER_DB_PASSWORD>` are stored once by a platform administrator in the
**Installation credentials** of Mosaikit, meant for the extension `dev.geoportal.catalog` (an
organization that stores one with the same name in Administration, Credentials uses its own); with them
in place, the workspace of each organization is made a few seconds after the organization is created.
`published-views`, stored by the administrators of each organization, with `<user>:<password>` of a login of the
database `mosaikit` that holds the role `mk_mosaikit_reader` (made by Geoportal at every start for its
published views, MK-123), with which the GeoFence rules name people (GP-335); the login of the role
service of GeoServer fits: `geoserver_reader:<GEOSERVER_READER_PASSWORD>`. The addresses fit this stack
by default. Credentials are encrypted with `GEOPORTAL_MASTER_KEY` of `.env` (32 random bytes in base64,
`openssl rand -base64 32`), which also signs the links the loader downloads uploads with: set it before
the first start, and keep it.

An installation created before the gateway: add `GEOPORTAL_MASTER_KEY` and `GEOSERVER_READER_PASSWORD`
to `.env` (see `env.example`), and recreate the volume `loader-work` (`docker compose down`, `docker volume
rm geoportal-server_loader-work`), which earlier images created owned by root.

An installation created before October 2026 has the administrator `admin` in its GeoServer volume and
a workspace `geoportal` that nothing uses any more: change the administrator in the security pages of
GeoServer, or start from new volumes.

## Data gateway

`gateway` is nginx in front of Geoportal and GeoServer, on one origin (GP-336,
[ADR-0022](../../docs/adr/0022-the-gateway-asks-as-the-person.md), ADR-0006, ADR-0016); it is the only
service with a public port. GeoServer has no port: Tomcat accepts connections only from the address of
the gateway on the network `geoserver` (`GEOSERVER_NETWORK`.10), and the gateway always overwrites the
header that names the person (`sec-username`). Its doors:

| Door | Port | What |
|---|---|---|
| people | `GEOPORTAL_PORT` | Geoportal, and the geodata at `/gw/ows/<organization>/[<layer>/]<wms\|wfs\|wcs\|ows\|gwc/service/wmts>` and `/gw/link/<secret>/<dataset>/<service>` |
| administration | `127.0.0.1:GEOSERVER_PORT` | GeoServer's pages and REST API, for `gsadmin`; the catalog reaches it as `http://gateway:8480/geoserver` |
| internal | none | GeoServer's calls to Geoportal for the personal keys of QGIS |

For each data request the gateway asks Geoportal whether the session (or the personal key) may read the
geodata of that organization, keeps the answer ten seconds, and passes the request to GeoServer with the
name of the person; GeoServer reads the teams and organizations of people from the published views of
Geoportal through the login `geoserver_reader` (`GEOSERVER_READER_PASSWORD`, created by Geoportal at
start) and applies the GeoFence rules. Only read requests pass (GetCapabilities, GetMap, GetFeature,
GetTile, ...), and nothing by reference (`sld=`, `remote_ows_url=`). Without a session the answer is
401; signed out, a revoked key or another organization stop within ten seconds.

The requests to the geodata count to the quota **Requests to the geodata** of the organization (a
counter per month): a platform administrator sets its limit in the quotas of the organization, and
beyond it the gateway answers 403 *Quota exceeded*. The log of the gateway (`docker compose logs
gateway`) says who read what: `person=` and `organization=` on every data request.

### QGIS

QGIS (or any desktop client) reaches the same addresses with a personal access key instead of the
session:

1. Once per installation, a platform administrator creates the service identity of the gateway: in the
   shell, **Service identities**, **New**: name `gateway`, API `catalog`, endpoint `check-key`. Copy its
   key (`mks_...`, shown once) into `.env` as `GATEWAY_SERVICE_KEY`, then `docker compose up -d gateway`.
   The key is a secret: it stays in `.env`, never in a repository.
2. Each person creates a personal access key in their settings (**Access keys**), for the organization
   whose data they read; read-only is enough.
3. In QGIS, **Layer**, **Add Layer**, **Add WMS/WMTS Layer** (or WFS), **New**, with the URL
   `http://<host>:<port>/gw/ows/<organization>/wms?authkey=<the personal key>` (`/wfs?authkey=` for WFS).
   The catalog shows these addresses in the services of each published layer.

QGIS then sees the layers the person sees in Geoportal: GeoServer asks Geoportal, through the internal
door of the gateway, whose key it is. A revoked key is refused by the gateway within ten seconds, and by
GeoServer within its authentication cache (60 s). The addresses in the answers of GeoServer are those of
the gateway, with the key, so QGIS follows them as they are. Behind a proxy with HTTPS, the proxy must
send `X-Forwarded-Proto: https`.

## Loader

`loader` is GDAL in a sidecar ([ADR-0019](../../docs/adr/0019-files-loaded-by-gdal-in-a-sidecar.md)),
built from `loader/` on the official GDAL image `alpine-normal` pinned by digest, the smallest official
flavour with the LIBKML driver that keeps the extended data of KML and KMZ
([ADR-0026](../../docs/adr/0026-the-loader-image-reads-kml-with-libkml.md)): it loads the files uploaded to the
catalog into PostGIS (`ogr2ogr`) and converts rasters into COG (`gdal_translate`). It has no port and
sits on the network `loader`, which is internal: only Geoportal calls it, and it reaches only
Geoportal (the uploads, through signed links) and PostGIS. The credentials of the database come with
each job and are never stored; the files being loaded are in the volume `loader-work` and are deleted
when the catalog has taken the job over, or after an hour. The loader streams the uploads, never
holding them in memory.

## Upload size

Uploads up to `GEOPORTAL_MAX_UPLOAD` are accepted: `1G` unless set in `.env`, the same maximum as the
other distributions of Geoportal. The one value sets the three limits on the way, which must agree: the
gateway (`client_max_body_size`), the request bodies of the kernel (`quarkus.http.limits.max-body-size`)
and its files (`mosaikit.files.max-size`). The catalog shows the maximum in its upload dialog and refuses
a larger file there, before sending it; a larger request is answered by the gateway with `413` and a
problem detail (`application/problem+json`) that names the limit. A proxy in front of the gateway, such
as one with HTTPS, must accept bodies as large (nginx: `client_max_body_size`).

## Mail

People who register confirm their address with a link sent by mail before they can sign in
(`GEOPORTAL_CONFIRM_EMAIL`, `true` by default). Geoportal sends it through the mail server of `.env`:

| Setting | Default | What |
|---|---|---|
| `MAIL_HOST`, `MAIL_PORT` | none, `587` | the SMTP server |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | none | its account, if it asks for one |
| `MAIL_START_TLS` | `REQUIRED` | `DISABLED`, `OPTIONAL` or `REQUIRED` (STARTTLS) |
| `MAIL_TLS` | `false` | `true` for a server that speaks TLS from the start (usually port 465) |
| `MAIL_FROM` | `Geoportal <no-reply@localhost>` | the sender: an address the server may send for |
| `GEOPORTAL_PUBLIC_URL` | the address of the request | the address of Geoportal in the links, such as `https://geo.example.org` |
| `GEOPORTAL_CONFIRM_EMAIL` | `true` | `false`: people sign in right after registering, with no mail |

Without a mail server the link never arrives: Geoportal waits for the server at most 15 seconds, the
registration stays unconfirmed and the person can ask for the link again once the server is set.

For demonstrations and reviews the stack has a mail catcher, [Mailpit](https://mailpit.axllent.org/),
in the Compose profile `mail`: it accepts every mail and delivers none, and shows them at
<http://127.0.0.1:8025> (`MAIL_CATCHER_PORT`, this computer only). Turn it on in `.env`:

```bash
COMPOSE_PROFILES=mail
MAIL_HOST=mailcatcher
MAIL_PORT=1025
MAIL_START_TLS=DISABLED
```

then `docker compose up -d`. Never for real people: their mails would stay in the catcher.

## Everyday use

| To | Run |
|---|---|
| see the logs | `docker compose logs -f geoportal` |
| stop | `docker compose down` (the data stay in the volumes `geoportal-server_postgres-data`, `geoportal-server_geoserver-data` and `geoportal-server_geoportal-files`) |
| delete everything, data included | `docker compose down -v` |
| back up the data | `docker compose exec postgres pg_dumpall -U mosaikit > backup.sql` |

The extensions are built into the image of Geoportal and signed by its build
(`MOSAIKIT_EXTENSIONS_SIGNATURES=required`). To add one, copy its package into
`../geoportal/extensions/` and rebuild the image.

## Production

This setup is for development, demonstrations and small installations on one machine. For
production put a reverse proxy with HTTPS in front of the gateway and Keycloak, run Keycloak with
`start` instead of `start-dev`, keep `.env` out of version control and back up the volumes. On
Kubernetes use `geoportal-cloud`.
