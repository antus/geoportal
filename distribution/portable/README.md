# Geoportal portable

Runs on this computer without installing Java, PostgreSQL or containers, for one organization.

| | |
|---|---|
| **Start** | Windows: double-click `mosaikit.cmd`. Linux, macOS: `./mosaikit` |
| **Open** | <http://localhost:8080>. At the first start sign in as `admin` with the password in `data/initial-admin-password.txt` |
| **Stop** | Ctrl+C in the window of Geoportal (answer *N* if Windows asks to terminate the batch job). The database stops with it |
| **Backup** | Stop Geoportal and copy `data/`. To restore, put the copy back in place |
| **Settings** | `config/application.properties` |

The portable archive has no GeoServer and its PostgreSQL has no PostGIS: the catalog, the maps,
the dashboards and the processes work on their own data. For GeoServer and PostGIS use
`geoportal-server`.

## Upload size

Files up to 1 GB are accepted, as in the other distributions of Geoportal: `config/application.properties`
sets the largest file of the kernel (`mosaikit.files.max-size`) and its largest request body
(`quarkus.http.limits.max-body-size`) to `1G`. Change the two together: the smaller one wins. The catalog
shows the maximum in its upload dialog and refuses a larger file there, before sending it; the API answers
a larger one with `413`.

## Contents

| Path | Content |
|---|---|
| `mosaikit`, `mosaikit.cmd` | the launcher |
| `config/` | settings |
| `data/` | the database and the generated passwords, created at the first start |
| `extensions/` | the extensions of Geoportal, signed |
| `bin/` | the kernel, the Java runtime, PostgreSQL and their licenses |
