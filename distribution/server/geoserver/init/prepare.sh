#!/bin/sh
# SPDX-FileCopyrightText: 2026 Massimo Antonini
#
# Prepares GeoServer for Geoportal. Idempotent: runs at every `docker compose up`.
#   - a GeoFence DENY rule at the bottom of the list, so that nothing is open unless a rule written
#     by Geoportal opens it (ADR-0005). An ALLOW here opened every workspace without a rule of its
#     own to anonymous requests, as the proofs of GP-327 measured.
#   - with the data gateway (GEOPORTAL_GATEWAY=true, GP-336), the proxy base URL from the headers the
#     gateway sends, so that the answers of GeoServer name the address people used (/gw/ows/... for
#     people and QGIS, /geoserver for the administration), never the internal one.
# Workspaces, stores, layers and styles are not made here: the catalog makes one workspace per
# organization, with its store on the schema of the organization in the database geodata (GP-334).
set -eu
# Behind the gateway GeoServer accepts only its connections: GEOSERVER_URL is then its administration door.
G="${GEOSERVER_URL:-http://geoserver:8080/geoserver}/rest"
A="${GEOSERVER_ADMIN_USER:?}:${GEOSERVER_ADMIN_PASSWORD:?}"
until curl -fs -u "$A" "$G/about/version.json" >/dev/null; do sleep 3; done

# The catch-all is looked for by what it is, not by its priority: GeoFence moves a rule down by one when
# another is inserted at its priority, so it may no longer be at 99999.
if ! curl -fs -u "$A" "$G/geofence/rules.json" | tr -d ' \n' | grep -o '{[^{}]*}' \
    | grep '"userName":null,"roleName":null' | grep '"workspace":null,"layer":null,"service":null,"request":null' \
    | grep -q '"access":"DENY"'; then
  curl -fs -u "$A" -XPOST -H 'Content-Type: application/json' "$G/geofence/rules" \
    -d '{"Rule":{"priority":99999,"access":"DENY"}}' >/dev/null
fi
curl -fs -u "$A" -XPUT "$G/ruleCache/invalidate" >/dev/null || true

if [ "${GEOPORTAL_GATEWAY:-false}" = true ]; then
  # The whole global settings, read and written back with the two changed: a PUT of only these two
  # would empty the others (the character set among them, and WFS stops answering JSON).
  PROXY='${X-Forwarded-Proto}://${X-Forwarded-Host}${X-Forwarded-Path}'
  curl -fs -u "$A" "$G/settings.xml" \
    | sed -e '/<proxyBaseUrl>/d' -e '/<useHeadersProxyURL>/d' \
          -e "s#<settings>#<settings><proxyBaseUrl>$PROXY</proxyBaseUrl><useHeadersProxyURL>true</useHeadersProxyURL>#" \
    > /tmp/settings.xml
  grep -q '<charset>' /tmp/settings.xml
  curl -fs -u "$A" -XPUT -H 'Content-Type: application/xml' --data-binary @/tmp/settings.xml "$G/settings" >/dev/null
  curl -fs -u "$A" "$G/settings.json" | grep -q '"useHeadersProxyURL":true'
fi
echo "GeoServer of Geoportal ready"
