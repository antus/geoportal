# Geoportal

Installation for a server or a PC with **Java 25** and **PostgreSQL 18 with PostGIS**, without
containers. For Docker use `geoportal-server`, for Kubernetes `geoportal-cloud`; to try Geoportal
without installing anything use the portable archive.

Geoportal is [Mosaikit](https://github.com/mosaikit/mosaikit) with the extensions of Geoportal
(Geodata, Maps, Dashboards, Processes), the extensions of
[mosaikit-ext](https://github.com/mosaikit/mosaikit-ext) they build on (the file manager Catalog and
Connections) and its theme, all in `extensions/`.

## Start

1. Create a PostgreSQL database and a user, for example with Docker:

   ```bash
   docker run -d --name geoportal-pg -p 5432:5432 -e POSTGRES_USER=mosaikit \
     -e POSTGRES_PASSWORD=mosaikit -e POSTGRES_DB=mosaikit postgis/postgis:18-3.6
   ```

2. Edit `config/application.properties`: database URL, user and password, and the password of the
   first administrator.
3. Start: `./mosaikit` (Linux, macOS, Git Bash) or `mosaikit.cmd` (Windows). The first start
   builds the kernel with the extensions, which takes a few seconds.
4. Open <http://localhost:8080> and sign in as `admin`.

Stop with Ctrl+C. The launcher uses the Java of `JAVA_HOME`, or `java` on the path.

## Upgrading an installation that had the catalog of Geoportal

Until October 2026 the geodata were the extension `dev.geoportal.catalog`, with the schema `ext_catalog`
in the database. They are now `dev.geoportal.ext.catalog.geodata`, with the schema `ext_catalog_geodata`,
and `ext_catalog` belongs to the file manager of mosaikit-ext (GP-983). Before the first start with the
new extensions, with Geoportal stopped and after a backup of the database, run once as the owner of the
database:

```bash
psql -v ON_ERROR_STOP=1 -h localhost -U mosaikit -d mosaikit -f upgrade/adopt-catalog.sql
```

It renames the schema and gives the rows of the kernel that named the catalog (grants, permissions,
credentials, files, quotas, the API `catalog` of service identities) to the new extension; it deletes
nothing, changes nothing on a new installation, and can be run again. Then change by hand the settings
that name the old extension, such as `mosaikit.quotas.limits."dev.geoportal.catalog/data-requests"` in
`config/application.properties`. The settings `geoportal.catalog.*` keep their names.

## Upload size

Files up to 1 GB are accepted, as in the other distributions of Geoportal: `config/application.properties`
sets the largest file of the kernel (`mosaikit.files.max-size`) and its largest request body
(`quarkus.http.limits.max-body-size`) to `1G`. Change the two together: the smaller one wins. The catalog
shows the maximum in its upload dialog and refuses a larger file there, before sending it; the API answers
a larger one with `413`. A proxy in front of Geoportal must accept bodies as large (nginx:
`client_max_body_size 1g`).

## Contents

| Path | Content |
|---|---|
| `mosaikit`, `mosaikit.cmd` | the launcher |
| `config/` | settings; `config/trusted-keys` holds the key that signed the extensions |
| `extensions/` | the extensions of Geoportal and of mosaikit-ext, signed |
| `upgrade/adopt-catalog.sql` | the adoption of the data of the old catalog (above) |
| `bin/kernel/` | the kernel of Mosaikit, with the web interface |
