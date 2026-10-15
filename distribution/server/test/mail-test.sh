#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Massimo Antonini
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# The mail of the server stack: with the profile "mail", a person who registers gets the link that
# confirms the address in the mail catcher, and can sign in once she opens it. Run in the folder of the
# stack (target/dist/geoportal-server), already up with its .env, by the CI after the test of the gateway:
#
#   bash test/mail-test.sh <compose project>
#
# Recreates Geoportal with the mail settings of the catcher and the confirmation on, as env.example
# describes them.
set -euo pipefail
PROJECT=${1:?the compose project}
setting() { { grep -E "^$1=" .env || true; } | tail -1 | cut -d= -f2-; }
GEOPORTAL_PORT=$(setting GEOPORTAL_PORT)
MAIL_CATCHER_PORT=$(setting MAIL_CATCHER_PORT)
GEOPORTAL_ADMIN_PASSWORD=$(setting GEOPORTAL_ADMIN_PASSWORD)
GW=http://localhost:${GEOPORTAL_PORT:-8080}
MAILS=http://127.0.0.1:${MAIL_CATCHER_PORT:-8025}
export COMPOSE_PROFILES=mail MAIL_HOST=mailcatcher MAIL_PORT=1025 MAIL_START_TLS=DISABLED GEOPORTAL_CONFIRM_EMAIL=true
docker compose -p "$PROJECT" -f compose.yaml up -d --no-build mailcatcher geoportal

until_ok() { # what, seconds, command...
  local what=$1 seconds=$2; shift 2
  for _ in $(seq 1 "$seconds"); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "FAIL: $what"; exit 1
}
until_ok 'the mail catcher' 60 curl -fs "$MAILS/api/v1/messages"
until_ok 'Geoportal through the gateway' 300 curl -fs "$GW/api/v1/accounts/registration-options"

run=$(date +%s)
org="mail$run"
email="ada.$run@$org.test"
admin="admin:${GEOPORTAL_ADMIN_PASSWORD}"
code=$(curl -s -o /dev/stderr -w '%{http_code}' -u "$admin" -H 'content-type: application/json' \
  -d "{\"slug\":\"$org\",\"name\":\"Mail $run\",\"selfRegistration\":true}" "$GW/api/v1/organizations")
[ "$code" = 201 ] || { echo "FAIL: organization $code"; exit 1; }
code=$(curl -s -o /dev/stderr -w '%{http_code}' -H 'content-type: application/json' \
  -d "{\"organization\":\"$org\",\"email\":\"$email\",\"displayName\":\"Ada\",\"password\":\"Mail-test-2026\"}" \
  "$GW/api/v1/accounts/registrations")
[ "$code" = 202 ] || { echo "FAIL: registration $code, not 202 (link sent)"; exit 1; }

# Not confirmed yet: no session.
code=$(curl -s -o /dev/null -w '%{http_code}' --data-urlencode "username=$email" --data-urlencode 'password=Mail-test-2026' \
  "$GW/api/v1/accounts/session")
[ "$code" != 200 ] || { echo "FAIL: signed in before confirming"; exit 1; }

until_ok "the mail to $email" 60 sh -c "curl -fs '$MAILS/api/v1/search?query=to:$email' | grep -q '\"messages_count\":[1-9]'"
id=$(curl -fs "$MAILS/api/v1/search?query=to:$email" | python3 -c 'import json,sys; print(json.load(sys.stdin)["messages"][0]["ID"])')
text=$(curl -fs "$MAILS/api/v1/message/$id" | python3 -c 'import json,sys; print(json.load(sys.stdin)["Text"])')
token=$(printf '%s' "$text" | grep -o 'confirm=[A-Za-z0-9_-]*' | head -1 | cut -d= -f2)
[ -n "$token" ] || { echo "FAIL: no link in the mail"; printf '%s\n' "$text"; exit 1; }
echo "the mail to $email carries the link"
code=$(curl -s -o /dev/stderr -w '%{http_code}' -H 'content-type: application/json' -d "{\"token\":\"$token\"}"   "$GW/api/v1/accounts/confirmations")
[ "$code" = 204 ] || { echo "FAIL: confirmation $code"; exit 1; }
code=$(curl -s -o /dev/null -w '%{http_code}' --data-urlencode "username=$email" --data-urlencode 'password=Mail-test-2026'   "$GW/api/v1/accounts/session")
[ "$code" = 200 ] || { echo "FAIL: no session after confirming ($code)"; exit 1; }
echo "OK: $email confirmed the address from the mail catcher and signed in"
