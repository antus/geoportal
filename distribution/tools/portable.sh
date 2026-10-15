#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Massimo Antonini
#
# Runs the build of the portable archives of Mosaikit (portable/build.sh of its portable-tools) on the
# installation of Geoportal, from mvn install -Pportable. On Windows it runs in Git Bash, never in the
# bash of WSL (the pom picks it), and turns the Windows paths Maven gives into the ones of Git Bash:
# with C:\ paths sha256sum escapes the name and the checksums never match, and GNU tar reads C: as a
# host.
#
#   portable.sh <portable tools> <platforms> <output>
#
# The PORTABLE_* variables are those of build.sh. The licence of Mosaikit, which build.sh copies from
# two directories above itself, is put there from the release of Mosaikit when it is missing.
set -euo pipefail
unix() { if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"; else printf '%s' "$1"; fi; }

tools="$(unix "${1:?usage: $0 <portable tools> <platforms> <output>}")"
platforms="${2:?the platforms}"
output="$(unix "${3:?the output directory}")"
for variable in PORTABLE_INSTALLATION PORTABLE_SETTINGS PORTABLE_README PORTABLE_CACHE; do
  if [[ -n "${!variable:-}" ]]; then
    export "$variable=$(unix "${!variable}")"
  fi
done

licence="$tools/../../LICENSE"
if [[ ! -f "$licence" ]]; then
  cache="${PORTABLE_CACHE:-$tools/../../portable-cache}"
  mkdir -p "$cache"
  if [[ ! -f "$cache/Mosaikit-LICENSE" ]]; then
    curl --fail --silent --show-error --location --output "$cache/Mosaikit-LICENSE.part" \
      https://raw.githubusercontent.com/mosaikit/mosaikit/main/LICENSE
    grep -q '^Mozilla Public License Version 2.0' "$cache/Mosaikit-LICENSE.part" \
      || { echo "the licence of Mosaikit is not the MPL 2.0 text" >&2; exit 1; }
    mv "$cache/Mosaikit-LICENSE.part" "$cache/Mosaikit-LICENSE"
  fi
  cp "$cache/Mosaikit-LICENSE" "$licence"
fi

exec bash "$tools/build.sh" "$platforms" "$output"
