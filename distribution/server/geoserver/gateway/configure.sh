#!/bin/bash
# SPDX-FileCopyrightText: 2026 Massimo Antonini
#
# GeoServer behind the data gateway of Geoportal (GP-336, ADR-0016, ADR-0022), at every start, before
# GeoServer starts; idempotent. Only when GEOPORTAL_GATEWAY_ADDRESS is set (the server distribution):
# without it GeoServer stays as the image makes it, as for the tests of the catalog.
#
#   GEOPORTAL_GATEWAY_ADDRESS   the fixed address of the gateway on the network it shares with GeoServer:
#                               Tomcat accepts connections from it (and from the container) only;
#                               "network-policy" on Kubernetes, where pods have no fixed address and a
#                               NetworkPolicy lets only the gateway in (no valve then)
#   GEOPORTAL_GATEWAY_INTERNAL  the internal door of the gateway, for the AuthKey mapper
#   GEOPORTAL_READER_URL, GEOPORTAL_READER_USER, GEOPORTAL_READER_PASSWORD
#                               the JDBC address of the database of Mosaikit and its reader login, which
#                               reads the published views mk_published and nothing else (MK-123)
#
# What it does:
#   - server.xml of Tomcat with a RemoteAddrValve: the name header comes only from the gateway;
#   - the role service "geoportal" (JDBC on mk_published), active, and the user service of the same name;
#   - the filters geoportal-header (sec-username) and geoportal-authkey (?authkey=, the web service mapper
#     on the catalog) at the head of the default chain;
#   - a random key for remember-me, and a closed layers.properties in case GeoFence is not loaded.
set -euo pipefail
[ -n "${GEOPORTAL_GATEWAY_ADDRESS:-}" ] || exit 0
: "${GEOPORTAL_READER_URL:?}" "${GEOPORTAL_READER_USER:?}" "${GEOPORTAL_READER_PASSWORD:?}"
: "${GEOPORTAL_GATEWAY_INTERNAL:?}" "${GEOSERVER_ADMIN_USER:?}"
HERE=/opt/geoportal/gateway
SEC="${GEOSERVER_DATA_DIR}/security"
LIB="${CATALINA_HOME}/webapps/geoserver/WEB-INF/lib"

# Tomcat: the image's server.xml with one valve more; the image copies it with envsubst at start, which
# fills GATEWAY_ADDRESS_PATTERN (exported by start.sh).
if [ "${GEOPORTAL_GATEWAY_ADDRESS}" != network-policy ]; then
  mkdir -p "${CONFIG_OVERRIDES_DIR}"
  VALVE='<!-- GP-336: only the gateway (and the container itself) may talk to GeoServer. -->'
  VALVE="${VALVE}"'<Valve className="org.apache.catalina.valves.RemoteAddrValve" allow="${GATEWAY_ADDRESS_PATTERN}|127\.0\.0\.1|0:0:0:0:0:0:0:1" />'
  VALVE="${VALVE}" perl -0pe 's#(<Valve className="org.apache.catalina.valves.AccessLogValve")#$ENV{VALVE}\n                $1#' \
    "${CONFIG_DIR}/server.xml" > "${CONFIG_OVERRIDES_DIR}/server.xml"
  grep -q RemoteAddrValve "${CONFIG_OVERRIDES_DIR}/server.xml"
fi

# The security folder, as the image makes it at the first start.
[ -d "${SEC}" ] || cp -r "${CATALINA_HOME}/webapps/geoserver/data/security" "${GEOSERVER_DATA_DIR}/"
mkdir -p "${SEC}/role/geoportal" "${SEC}/usergroup/geoportal" "${SEC}/filter/geoportal-header" \
  "${SEC}/filter/geoportal-authkey"

# Values in XML: & < > " escaped; the name of the administrator in SQL: ' doubled.
xml() { printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' -e 's/"/\&quot;/g'; }
fill() { # fill <template> <target> <variable>=<value>...
  local template=$1 target=$2 names=""
  shift 2
  for pair in "$@"; do
    export "${pair%%=*}=${pair#*=}"
    names="${names} \$${pair%%=*}"
  done
  envsubst "${names}" < "${HERE}/${template}" > "${target}"
}
READER=(
  "GEOPORTAL_READER_URL=$(xml "${GEOPORTAL_READER_URL}")"
  "GEOPORTAL_READER_USER=$(xml "${GEOPORTAL_READER_USER}")"
  "GEOPORTAL_READER_PASSWORD=$(xml "${GEOPORTAL_READER_PASSWORD}")"
)
ADMIN_SQL=$(printf '%s' "${GEOSERVER_ADMIN_USER}" | sed "s/'/''/g")
INTERNAL=$(xml "${GEOPORTAL_GATEWAY_INTERNAL}")
(
  fill role-config.xml "${SEC}/role/geoportal/config.xml" "${READER[@]}"
  fill users-config.xml "${SEC}/usergroup/geoportal/config.xml" "${READER[@]}"
  fill rolesdml.xml "${SEC}/role/geoportal/rolesdml.xml" "GEOSERVER_ADMIN_USER=$(xml "${ADMIN_SQL}")"
  fill authkey-filter.xml "${SEC}/filter/geoportal-authkey/config.xml" "GATEWAY_INTERNAL_URL=${INTERNAL}"
)
cp "${HERE}/usersdml.xml" "${SEC}/usergroup/geoportal/usersdml.xml"
cp "${HERE}/header-filter.xml" "${SEC}/filter/geoportal-header/config.xml"
# The DDL files GeoServer expects next to the DML (never run: creatingTables is false).
J=$(ls "${LIB}"/gs-sec-jdbc-*.jar | head -1)
unzip -o -q -j "$J" org/geoserver/security/jdbc/rolesddl.xml -d "${SEC}/role/geoportal"
unzip -o -q -j "$J" org/geoserver/security/jdbc/usersddl.xml -d "${SEC}/usergroup/geoportal"

# The active role service, and the two filters first in the default chain (services: WMS, WFS, ...).
CFG="${SEC}/config.xml"
sed -i 's#<roleServiceName>[^<]*</roleServiceName>#<roleServiceName>geoportal</roleServiceName>#' "${CFG}"
if ! grep -q '<filter>geoportal-header</filter>' "${CFG}"; then
  perl -0pi -e 's#(<filters name="default"[^>]*>\s*)#$1<filter>geoportal-header</filter>\n      <filter>geoportal-authkey</filter>\n      #' "${CFG}"
fi
grep -q '<filter>geoportal-header</filter>' "${CFG}"
# Remember-me with a key of this installation, not the "geoserver" of the image.
if grep -q '<key>geoserver</key>' "${CFG}"; then
  sed -i "s#<key>geoserver</key>#<key>$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')</key>#" "${CFG}"
fi
# If GeoFence were not loaded, close instead of open (the image's default lets everybody read).
printf '# GP-336: GeoFence decides; without it, only the administrator.\n*.*.r=ADMIN\n*.*.w=ADMIN\nmode=HIDE\n' \
  > "${SEC}/layers.properties"
echo "GeoServer behind the gateway ${GEOPORTAL_GATEWAY_ADDRESS}: header and AuthKey filters, roles from Mosaikit"
