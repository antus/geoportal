# Geoportal on Kubernetes

The Helm chart `geoportal/` runs Geoportal on a cluster: the chart of Mosaikit (in
`geoportal/charts/mosaikit`) with the image of Geoportal, and GeoServer with GeoFence. PostgreSQL
18 with PostGIS and Keycloak are expected to run separately, for example with their own operators
or managed services, with the databases `mosaikit`, `keycloak`, `geoserver` (schema `geofence`) and
`geodata` (see `../geoportal-server/postgres/init-geoserver.sh`).

## Images

The images are built from `geoportal-server` and pushed to a registry of yours:

```bash
docker build -f geoportal-server/Dockerfile -t registry.example.org/geoportal:2.0.0-SNAPSHOT .
docker build -t registry.example.org/geoportal-geoserver:2.28.5 geoportal-server/geoserver
docker build -t registry.example.org/geoportal-loader:2.0.0-SNAPSHOT geoportal-server/loader
docker build -t registry.example.org/geoportal-gateway:1.27.5 geoportal-server/gateway
docker push registry.example.org/geoportal-gateway:1.27.5
docker push registry.example.org/geoportal:2.0.0-SNAPSHOT
docker push registry.example.org/geoportal-geoserver:2.28.5
docker push registry.example.org/geoportal-loader:2.0.0-SNAPSHOT
```

The image of Geoportal has the extensions already built into the kernel, so that the container
runs with a read-only root filesystem.

## Install

```bash
kubectl create secret generic geoportal-database \
  --from-literal=username=mosaikit --from-literal=password='<password>'
kubectl create secret generic geoportal-geoserver-database \
  --from-literal=username=geoserver --from-literal=password='<password>'
kubectl create secret generic geoportal-geoserver-admin --from-literal=password='<password>'
helm install geoportal ./geoportal \
  --set mosaikit.database.url=jdbc:postgresql://<host>:5432/mosaikit \
  --set geoserver.geofence.host=<host> \
  --set mosaikit.identity.keycloakUrl=https://auth.example.org
```

Every value is described in `geoportal/values.yaml`, and those of Mosaikit in
`geoportal/charts/mosaikit/values.yaml`.

## Loader

The chart runs the loader of the catalog (ADR-0019, `loader.enabled`): GDAL in a sidecar, a
`ClusterIP` service `<release>-loader` that a `NetworkPolicy` opens only to the pods of Geoportal, with
an `emptyDir` for the files being loaded. The catalog finds it through two of its settings, which the
chart of Mosaikit passes to the kernel as environment variables (MK-129, `extensions.<id>.settings`):

```yaml
mosaikit:
  extensions:
    dev.geoportal.ext.catalog.geodata:
      settings:
        geoportal.catalog.loader.url: http://<release>-loader:8090
        geoportal.catalog.loader.source-url: http://<release>-mosaikit
```

Values cannot be templates, so the defaults fit `helm install geoportal`. With another release name
set both in a values file; the chart checks that they name the loader and the kernel of the release and
stops with what to set otherwise. With `loader.enabled=false` set `geoportal.catalog.loader.url` to
`""`: the catalog then loads with its own readers.

## Data gateway

The chart runs the data gateway of GP-336 (`gateway.enabled`, [ADR-0022](../../docs/adr/0022-the-gateway-asks-as-the-person.md)):
nginx in front of Geoportal and GeoServer on one origin, so **point the Ingress of the installation at
the service `<release>-gateway`, port `http`**, not at the service of Mosaikit. People and QGIS read the
geodata at `/gw/ows/<organization>/...` (see `geoportal-server/README.md`). GeoServer accepts the name of
the person only from the gateway: on Kubernetes pods have no fixed address, so a `NetworkPolicy` lets
only the gateway reach GeoServer, and the cluster must enforce network policies (a CNI such as Calico or
Cilium); without them anything in the cluster could name any person to GeoServer.

```bash
kubectl create secret generic geoportal-published-views --from-literal=username=geoserver_reader --from-literal=password='<password>'
# after creating the service identity "gateway" in Geoportal (API catalog-geodata, endpoint check-key):
kubectl create secret generic geoportal-gateway-key --from-literal=key='mks_...'
helm upgrade geoportal ./geoportal --reuse-values --set gateway.serviceKeySecret=geoportal-gateway-key
```

The kernel needs `mosaikit.database.published-views.login`/`.password` (the same login),
`geoportal.gateway.enabled=true` and `geoportal.geoserver.url=http://<release>-gateway:8480/geoserver`;
set them in the values (MK-129, see *Settings of the extensions* below): the first two in
`mosaikit.kernel.extraSettings`, the password as a reference to the Secret `geoportal-published-views`,
the other two in `mosaikit.extensions.dev.geoportal.ext.catalog.geodata.settings`.

## Upload size

Uploads up to `mosaikit.files.maxSize` are accepted: `1G` unless set, the same maximum as the other
distributions of Geoportal. The chart of Mosaikit sets both limits of the kernel to it (the largest file,
`mosaikit.files.max-size`, and the largest request body, `quarkus.http.limits.max-body-size`), and the
gateway takes it too unless `gateway.maxBody` says otherwise. The catalog shows the maximum in its upload
dialog and refuses a larger file there; a larger request is answered by the gateway with `413` and a
problem detail that names the limit. **The Ingress must accept bodies as large**: ingress-nginx refuses
bodies over 1 MB unless told, with the annotation

```yaml
nginx.ingress.kubernetes.io/proxy-body-size: 1g
```

and other controllers have their own setting.

## Settings of the extensions

Every other setting of an extension of Geoportal goes in the same place, under the identifier of the
extension (`mosaikit.extensions.<id>.settings`), and those of the kernel in
`mosaikit.kernel.extraSettings`; a password, a token or a key only as a reference to a Secret:

```yaml
mosaikit:
  extensions:
    dev.geoportal.ext.catalog.geodata:
      settings:
        geoportal.gateway.enabled: "true"
        geoportal.geoserver.url: http://geoportal-gateway:8480/geoserver
        geoportal.geodata.jdbc-url:
          valueFrom:
            secretKeyRef: { name: geoportal-geodata, key: jdbc-url }
```

## Tests

`bash test/chart-test.sh <the chart with charts/mosaikit>` renders the chart with `helm template`
(GP-937): the settings of the catalog in the environment of the kernel, the checks on the release name
and on the loader turned off, secret settings by reference; and the upload size (issue #40), the same
for the kernel and the gateway. The CI runs it on the assembled chart,
`target/dist/geoportal-cloud/geoportal`.

## Upgrading a release that had the catalog of Geoportal

Until October 2026 the geodata were the extension `dev.geoportal.catalog`, with the schema `ext_catalog`;
they are now `dev.geoportal.ext.catalog.geodata`, with `ext_catalog_geodata`, and `ext_catalog` is the
file manager of mosaikit-ext (GP-983). Before `helm upgrade` to such a version, with the release scaled
to zero and after a backup, run `upgrade/adopt-catalog.sql` of the installation archive once against the
database of Geoportal, as its owner (`psql -v ON_ERROR_STOP=1 -f adopt-catalog.sql`), and move the
values of `mosaikit.extensions.dev.geoportal.catalog` to `mosaikit.extensions.dev.geoportal.ext.catalog.geodata`.
The script renames and never deletes; see the README of the installation.
