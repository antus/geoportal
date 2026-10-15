#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Massimo Antonini
#
# Tests of the Helm chart of Geoportal with helm template (GP-937): the settings of the catalog reach
# the kernel as environment variables through the chart of Mosaikit (MK-129), and name the loader and
# the kernel of the release.
#
#   bash distribution/cloud/test/chart-test.sh target/dist/geoportal-cloud/geoportal
#
# The chart must hold the chart of Mosaikit in charts/ (mvn package of distribution does it). HELM
# chooses the helm binary.
set -euo pipefail

chart=${1:?the directory of the chart}
helm=${HELM:-helm}
failures=0

pass() { echo "ok   - $1"; }
fail() { echo "FAIL - $1"; failures=$((failures + 1)); }

# The env entry of the kernel named $2, as "NAME=value", from the rendering $1.
env_of() {
  awk -v want="$2" '
    /^kind: / { kind = $2 }
    kind == "Deployment" && $0 ~ "- name: " want "$" { found = 1; next }
    found && /value: / { sub(/^[^:]*: */, ""); gsub(/"/, ""); print want "=" $0; exit }
    found && /- name: / { exit }
  ' <<<"$1"
}

render() {
  "$helm" template "$@" 2>&1
}

name="GP-937 the loader of the release geoportal is the setting of the catalog in the kernel"
out=$(render geoportal "$chart") || { fail "$name: $out"; out=""; }
if [[ -n "$out" ]]; then
  url=$(env_of "$out" GEOPORTAL_CATALOG_LOADER_URL)
  source=$(env_of "$out" GEOPORTAL_CATALOG_LOADER_SOURCE_URL)
  if [[ "$url" == "GEOPORTAL_CATALOG_LOADER_URL=http://geoportal-loader:8090" \
     && "$source" == "GEOPORTAL_CATALOG_LOADER_SOURCE_URL=http://geoportal-mosaikit" ]] \
     && grep -q '^  name: geoportal-loader$' <<<"$out" \
     && grep -q '^  name: geoportal-mosaikit$' <<<"$out"; then
    pass "$name"
  else
    fail "$name: got '$url' '$source'"
  fi
fi

name="GP-937 another release name without the settings stops with what to set"
if out=$(render maps "$chart"); then
  fail "$name: rendered"
elif grep -q 'the loader of this release is http://maps-loader:8090' <<<"$out"; then
  pass "$name"
else
  fail "$name: $out"
fi

name="GP-937 another release name with its settings renders them"
values=$(mktemp)
cat >"$values" <<'YAML'
mosaikit:
  extensions:
    dev.geoportal.ext.catalog.geodata:
      settings:
        geoportal.catalog.loader.url: http://maps-loader:8090
        geoportal.catalog.loader.source-url: http://maps-mosaikit:80
YAML
if out=$(render maps "$chart" -f "$values") \
   && [[ "$(env_of "$out" GEOPORTAL_CATALOG_LOADER_URL)" == "GEOPORTAL_CATALOG_LOADER_URL=http://maps-loader:8090" ]] \
   && [[ "$(env_of "$out" GEOPORTAL_CATALOG_LOADER_SOURCE_URL)" == "GEOPORTAL_CATALOG_LOADER_SOURCE_URL=http://maps-mosaikit:80" ]]; then
  pass "$name"
else
  fail "$name: $out"
fi

name="GP-937 with the loader off the setting must go, and then the catalog has no loader"
if out=$(render geoportal "$chart" --set loader.enabled=false); then
  fail "$name: rendered with a loader address and no loader"
elif ! grep -q 'the loader is off' <<<"$out"; then
  fail "$name: $out"
else
  cat >"$values" <<'YAML'
loader:
  enabled: false
mosaikit:
  extensions:
    dev.geoportal.ext.catalog.geodata:
      settings:
        geoportal.catalog.loader.url: ""
YAML
  if out=$(render geoportal "$chart" -f "$values") \
     && [[ "$(env_of "$out" GEOPORTAL_CATALOG_LOADER_URL)" == "GEOPORTAL_CATALOG_LOADER_URL=" ]] \
     && ! grep -q 'name: geoportal-loader$' <<<"$out"; then
    pass "$name"
  else
    fail "$name: $out"
  fi
fi
rm -f "$values"

name="GP-937 a secret setting of the catalog is a reference to a Secret, never a value"
values=$(mktemp)
cat >"$values" <<'YAML'
mosaikit:
  extensions:
    dev.geoportal.ext.catalog.geodata:
      settings:
        geoportal.catalog.loader.url: http://geoportal-loader:8090
        geoportal.catalog.loader.source-url: http://geoportal-mosaikit
        geoportal.geodata.jdbc-url:
          valueFrom:
            secretKeyRef: { name: geoportal-geodata, key: jdbc-url }
YAML
if out=$(render geoportal "$chart" -f "$values") \
   && grep -A5 -- '- name: GEOPORTAL_GEODATA_JDBC_URL$' <<<"$out" | grep -q 'name: geoportal-geodata'; then
  pass "$name"
else
  fail "$name: $out"
fi
rm -f "$values"

# The env entry $2 of the Deployment whose name ends with $3, as "NAME=value", from the rendering $1.
env_in() {
  awk -v want="$2" -v deployment="$3" '
    /^kind: / { kind = $2; name = "" }
    kind == "Deployment" && /^  name: / { name = $2 }
    kind == "Deployment" && name ~ deployment "$" && $0 ~ "- name: " want "$" { found = 1; next }
    found && /value: / { sub(/^[^:]*: */, ""); gsub(/"/, ""); print want "=" $0; exit }
    found && /- name: / { exit }
  ' <<<"$1"
}

# The three limits on the way of an upload, as "files body gateway".
limits() {
  echo "$(env_in "$1" MOSAIKIT_FILES_MAX_SIZE -mosaikit) $(env_in "$1" QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE -mosaikit)"     "$(env_in "$1" GATEWAY_MAX_BODY -gateway)"
}

name="issue #40 the kernel and the gateway accept the same largest upload, 1G"
out=$(render geoportal "$chart") || { fail "$name: $out"; out=""; }
if [[ -n "$out" ]]; then
  got=$(limits "$out")
  if [[ "$got" == "MOSAIKIT_FILES_MAX_SIZE=1G QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE=1G GATEWAY_MAX_BODY=1G" ]]; then
    pass "$name"
  else
    fail "$name: got '$got'"
  fi
fi

name="issue #40 one value changes the largest upload of the kernel and of the gateway"
if out=$(render geoportal "$chart" --set mosaikit.files.maxSize=2G)    && [[ "$(limits "$out")" == "MOSAIKIT_FILES_MAX_SIZE=2G QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE=2G GATEWAY_MAX_BODY=2G" ]]; then
  pass "$name"
else
  fail "$name: got '$(limits "$out")'"
fi

if [[ $failures -gt 0 ]]; then
  echo "$failures failed"
  exit 1
fi
echo "all passed"
