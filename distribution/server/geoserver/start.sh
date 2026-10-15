#!/bin/bash
# SPDX-FileCopyrightText: 2026 Massimo Antonini
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Writes the database settings of GeoFence from the environment, turns on the data security of
# GeoWebCache, puts GeoServer behind the data gateway when the installation has one
# (gateway/configure.sh, GP-336), then starts GeoServer as the image does.
set -euo pipefail
: "${GEOFENCE_DB_HOST:?}" "${GEOFENCE_DB_NAME:?}" "${GEOFENCE_DB_USER:?}" "${GEOFENCE_DB_PASSWORD:?}"
mkdir -p "${GEOSERVER_DATA_DIR}/geofence"
envsubst < /opt/geoportal/geofence-datasource-ovr.properties \
  > "${GEOSERVER_DATA_DIR}/geofence/geofence-datasource-ovr.properties"
# GeoWebCache asks GeoFence for every tile (GP-335): with its data security off, the default, a tile
# already cached is served to anybody, whatever the rules say (measured on 2.28.5).
GWC="${GEOSERVER_DATA_DIR}/gwc-gs.xml"
if [ -f "$GWC" ]; then
  sed -i 's#<securityEnabled>false</securityEnabled>#<securityEnabled>true</securityEnabled>#' "$GWC"
else
  cp /opt/geoportal/gwc-gs.xml "$GWC"
fi
if ! grep -q '<securityEnabled>true</securityEnabled>' "$GWC"; then
  echo "GeoWebCache data security is not on in $GWC: refusing to start" >&2
  exit 1
fi
bash /opt/geoportal/gateway/configure.sh
if [ -n "${GEOPORTAL_GATEWAY_ADDRESS:-}" ] && [ "${GEOPORTAL_GATEWAY_ADDRESS}" != network-policy ]; then
  # The address of the gateway for Tomcat's RemoteAddrValve (a regular expression), which the image
  # writes into server.xml at start.
  GATEWAY_ADDRESS_PATTERN=$(printf '%s' "${GEOPORTAL_GATEWAY_ADDRESS}" | sed 's/[.]/\\./g')
  export GATEWAY_ADDRESS_PATTERN
fi
exec bash /opt/startup.sh
