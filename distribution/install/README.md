# Geoportal

Installation for a server or a PC with **Java 25** and **PostgreSQL 18 with PostGIS**, without
containers. For Docker use `geoportal-server`, for Kubernetes `geoportal-cloud`; to try Geoportal
without installing anything use the portable archive.

Geoportal is [Mosaikit](https://github.com/mosaikit/mosaikit) with the extensions of Geoportal
(Catalog, Maps, Dashboards, Processes) and its theme, all in `extensions/`.

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
| `extensions/` | the extensions of Geoportal, signed |
| `bin/kernel/` | the kernel of Mosaikit, with the web interface |
