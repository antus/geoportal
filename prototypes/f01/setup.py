# SPDX-FileCopyrightText: 2026 Massimo Antonini
"""GP-327 F01: brings a fresh stack (compose + Mosaikit with the catalog and the proof stub) to the state
of the proofs.

Idempotent. What it does, in order:
  1. Mosaikit, through its API: organizations alfa, beta and f01-system, people with a password,
     the manager of alfa, the teams mobilita, urbanistica, ambiente and their members.
  2. The database: read-only views gs_sec.* for GeoServer (sql/gs_sec.sql), test data
     (sql/geodata.sql).
  3. GeoServer security: JDBC role and user services on the views, the REST role service (fallback),
     the header filter and the AuthKey filter in the default chain, a closed layers.properties.
  4. Workspaces, stores and layers; the GeoFence rule set.
"""
import os
import sys
import time

from common import (GS, GS_ADMIN, GW, HERE, MK, MK_ADMIN, ORGANIZATIONS, PASSWORD, PEOPLE, TEAMS, compose,
                    get, http, person, psql, team_ids, wait_for, write_rules)


def mosaikit():
    wait_for(f"{GW}/api/v1/system/info")
    existing = {o["slug"] for o in get(f"{GW}/api/v1/organizations", MK_ADMIN).json()}
    for slug, name in ORGANIZATIONS.items():
        if slug not in existing:
            a = http("POST", f"{GW}/api/v1/organizations", MK_ADMIN,
                     {"slug": slug, "name": name, "selfRegistration": True})
            assert a.status == 201, (slug, a.status, a.body[:300])
    for email, (org, name, _) in PEOPLE.items():
        if get(f"{GW}/api/v1/accounts/session", person(email)).status != 200:
            password = PASSWORD if not email.startswith("geoserver@") else "f01-service-password"
            a = http("POST", f"{GW}/api/v1/accounts/registrations", body={
                "organization": org, "email": email, "displayName": name, "password": password})
            assert a.status in (200, 201, 204), (email, a.status, a.body[:300])
    # The manager of alfa creates the teams (and is their owner).
    a = http("PUT", f"{GW}/api/v1/organizations/alfa/members/gestore@alfa.test", MK_ADMIN,
             {"roles": ["organization-admin", "organization-user"]})
    assert a.status == 200, a.body[:300]
    teams = team_ids()
    for t in TEAMS:
        if t not in teams:
            a = http("POST", f"{GW}/api/v1/teams", person("gestore@alfa.test"),
                     {"name": t, "description": f"Team {t} (F01)", "visibility": "private"})
            assert a.status == 201, (t, a.status, a.body[:300])
    teams = team_ids()
    for email, (_, _, member_of) in PEOPLE.items():
        for t in member_of:
            a = http("PUT", f"{GW}/api/v1/teams/{teams[t]}/members/{email}", person("gestore@alfa.test"),
                     {"role": "member"})
            assert a.status == 200, (email, t, a.status, a.body[:300])
    print(f"Mosaikit: {len(ORGANIZATIONS)} organizations, {len(PEOPLE)} people, teams {teams}")
    return teams


def database():
    with open(os.path.join(HERE, "sql", "gs_sec.sql"), encoding="utf-8") as f:
        psql(f.read())
    with open(os.path.join(HERE, "sql", "geodata.sql"), encoding="utf-8") as f:
        psql(f.read(), db="geodata", user="geoserver")
    print("database: views gs_sec.* and test data ready")


SECURITY_SCRIPT = r"""
set -e; cd /opt/geoserver_data/security
J=$(ls /usr/local/tomcat/webapps/geoserver/WEB-INF/lib/gs-sec-jdbc-*.jar)
unzip -o -q -j $J org/geoserver/security/jdbc/rolesdml.xml org/geoserver/security/jdbc/rolesddl.xml -d role/gp
unzip -o -q -j $J org/geoserver/security/jdbc/usersdml.xml org/geoserver/security/jdbc/usersddl.xml -d usergroup/gpusers
sed -i "s#<roleServiceName>default</roleServiceName>#<roleServiceName>gp</roleServiceName>#" config.xml
grep -q "<filter>hdr</filter>" config.xml || perl -0pi -e 's#(<filters name="default"[^>]*>\s*)<filter>basic</filter>#$1<filter>hdr</filter>\n      <filter>authkey</filter>\n      <filter>basic</filter>#' config.xml
grep -q "<key>" config.xml || perl -0pi -e "s#(GeoServerTokenBasedRememberMeServices</className>)#\$1\n    <key>$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')</key>#" config.xml
printf '# GP-327 F01: if GeoFence is not loaded, close instead of open.\n*.*.r=ADMIN\n*.*.w=ADMIN\nmode=HIDE\n' > layers.properties
grep -c "<filter>hdr</filter>" config.xml
"""


def geoserver_security():
    wait_for(f"{GS}/rest/about/version.json", GS_ADMIN)
    if compose("exec", "-T", "geoserver", "test", "-f", "/opt/geoserver_data/security/filter/hdr/config.xml",
               check=False).returncode == 0:
        print("GeoServer security: already configured")
        return
    for part in ("role/gp", "role/gprest", "usergroup/gpusers", "filter/hdr", "filter/authkey"):
        parent = os.path.dirname(part)
        compose("exec", "-T", "geoserver", "mkdir", "-p", f"/opt/geoserver_data/security/{parent}")
        compose("cp", os.path.join(HERE, "geoserver", "security", *part.split("/")),
                f"geoserver:/opt/geoserver_data/security/{parent}/")
    r = compose("exec", "-T", "geoserver", "bash", "-c", SECURITY_SCRIPT)
    assert r.stdout.strip().endswith("1"), r.stdout + r.stderr
    compose("restart", "geoserver")
    t = wait_for(f"{GS}/rest/about/version.json", GS_ADMIN)
    print(f"GeoServer security: configured, restarted in {t} s")


def store(ws, schema):
    return {"dataStore": {"name": "data", "connectionParameters": {"entry": [
        {"@key": "dbtype", "$": "postgis"}, {"@key": "host", "$": "postgis"}, {"@key": "port", "$": "5432"},
        {"@key": "database", "$": "geodata"}, {"@key": "schema", "$": schema},
        {"@key": "user", "$": "geoserver"}, {"@key": "passwd", "$": "f01-proof"},
        {"@key": "Expose primary keys", "$": "true"}]}}}


def catalog():
    # alfa and beta are organizations; "aperto" is a workspace WITHOUT its deny rule, to show
    # what the catch-all ALLOW of the distribution does to it.
    for ws, schema, layer in (("alfa", "alfa", "particelle"), ("beta", "beta", "fermate"),
                              ("aperto", "beta", "fermate")):
        if get(f"{GS}/rest/workspaces/{ws}.json", GS_ADMIN).status != 200:
            http("POST", f"{GS}/rest/workspaces", GS_ADMIN, {"workspace": {"name": ws}})
        if get(f"{GS}/rest/workspaces/{ws}/datastores/data.json", GS_ADMIN).status != 200:
            http("POST", f"{GS}/rest/workspaces/{ws}/datastores", GS_ADMIN, store(ws, schema))
        if get(f"{GS}/rest/workspaces/{ws}/datastores/data/featuretypes/{layer}.json", GS_ADMIN).status != 200:
            a = http("POST", f"{GS}/rest/workspaces/{ws}/datastores/data/featuretypes", GS_ADMIN,
                     {"featureType": {"name": layer, "srs": "EPSG:4326"}})
            assert a.status == 201, (ws, layer, a.status, a.body[:300])
    # The store of the Importer (proof 6).
    if get(f"{GS}/rest/workspaces/alfa/datastores/imports.json", GS_ADMIN).status != 200:
        s = store("alfa", "imports")
        s["dataStore"]["name"] = "imports"
        http("POST", f"{GS}/rest/workspaces/alfa/datastores", GS_ADMIN, s)
    print("GeoServer catalog: workspaces alfa, beta, aperto; stores and layers ready")


def main():
    t0 = time.time()
    teams = mosaikit()
    database()
    geoserver_security()
    catalog()
    kept, seconds = write_rules(teams)
    print(f"GeoFence: {len(kept)} rules written and re-read in {seconds} s")
    print(f"setup done in {round(time.time() - t0)} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
