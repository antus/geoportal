# SPDX-FileCopyrightText: 2026 Massimo Antonini
#
# GeoServer of Geoportal, with its extensions INSIDE the image, downloaded once at build time:
# downloaded at start (INSTALL_EXTENSIONS) a corrupted zip once started GeoServer without GeoFence,
# and without GeoFence GeoServer opens everything to everybody.
#   importer                  uploads of files into stores
#   authkey                   access with the key of a person, for the services Geoportal hands out
#   geofence-server-postgres  the access rules, kept in PostgreSQL
FROM docker.osgeo.org/geoserver:2.28.5
ARG GS_VERSION=2.28.5
RUN set -e; cd /tmp; \
    for e in importer authkey geofence-server-postgres; do \
      wget -q "https://sourceforge.net/projects/geoserver/files/GeoServer/${GS_VERSION}/extensions/geoserver-${GS_VERSION}-${e}-plugin.zip/download" -O "$e.zip"; \
      unzip -t -q "$e.zip"; \
      unzip -o -q -j "$e.zip" "*.jar" -d "$CATALINA_HOME/webapps/geoserver/WEB-INF/lib/"; \
      rm "$e.zip"; \
    done; \
    ls "$CATALINA_HOME/webapps/geoserver/WEB-INF/lib/" | grep -E "geofence|importer|authkey"

# Authentication cache of 60 s instead of 600 (see authcache/applicationSecurityContext.xml). The
# redefined bean must still exist with that name: if an upgrade renames it, the build stops here
# instead of starting with ten minutes of revocation delay.
COPY authcache/applicationSecurityContext.xml /tmp/authcache/applicationSecurityContext.xml
RUN unzip -p "$CATALINA_HOME"/webapps/geoserver/WEB-INF/lib/gs-main-*.jar applicationContext.xml \
      | grep -q 'id="authCacheLifecycleHandler"' \
    && cd /tmp/authcache \
    && "$JAVA_HOME/bin/jar" cf "$CATALINA_HOME/webapps/geoserver/WEB-INF/lib/gs-geoportal-authcache.jar" applicationSecurityContext.xml \
    && rm -rf /tmp/authcache

# GeoFence on PostgreSQL, with the settings of the environment (GEOFENCE_DB_*). They must be there
# at the FIRST start: without them GeoFence looks for H2, which the PostgreSQL variant lacks, and
# GeoServer does not start.
COPY geofence-datasource-ovr.properties /opt/geoportal/geofence-datasource-ovr.properties
# GeoWebCache with its data security on (GP-335): start.sh writes it at the first start, or turns it on.
COPY gwc-gs.xml /opt/geoportal/gwc-gs.xml
COPY start.sh /opt/geoportal/start.sh
# Behind the data gateway (GP-336): the name header only from the gateway, roles from Mosaikit, AuthKey.
COPY gateway/ /opt/geoportal/gateway/
ENTRYPOINT ["bash", "/opt/geoportal/start.sh"]
