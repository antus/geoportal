# SPDX-FileCopyrightText: 2026 Massimo Antonini
"""Proof 5: GeoServer reads the roles of a person from Mosaikit.

First choice: the JDBC role service on read-only views (gs_sec.*) in the Mosaikit database, read by
the login role gs_reader. Fallback: the REST role service against the proof stub extension. The
proof checks what row-level security does to the views, what gs_reader can and cannot read, the roles
GeoServer reports for each person against the teams Mosaikit reports, and how long a change made
through the Mosaikit API takes to reach GeoServer.
"""
import time

from common import (GS, GS_ADMIN, GW, PEOPLE, Proof, compose, cookie_header, get, http, main_of, person, psql,
                    rows, team_ids, wfs_url)

WINDOW_S = 150


def as_reader(sql):
    """SQL as gs_reader, the login of GeoServer (password authentication over TCP)."""
    r = compose("exec", "-T", "-e", "PGPASSWORD=f01-proof", "postgis", "psql", "-h", "127.0.0.1", "-U",
                "gs_reader", "-d", "mosaikit", "-At", "-c", sql, check=False)
    errors = [l for l in r.stderr.splitlines() if "ERROR" in l]
    return r.stdout.strip() if r.returncode == 0 else (errors[0] if errors else r.stderr.strip())[:120]


def geoserver_roles(service, email):
    a = get(f"{GS}/rest/security/roles/service/{service}/user/{email}.json", GS_ADMIN)
    return sorted(a.json().get("roles", [])) if a.status == 200 else f"HTTP {a.status} {a.body[:120]!r}"


def expected_roles(email, teams):
    org, _, member_of = PEOPLE[email]
    roles = {f"ORG_{org}_user"} | {f"TEAM_{teams[t]}" for t in member_of}
    if email == "gestore@alfa.test":
        roles |= {"ORG_alfa_admin"} | {f"TEAM_{i}" for i in teams.values()}
    return sorted(roles)


def proof():
    p = Proof(5, "the role service reads the roles of a person from Mosaikit")
    rls = psql("select c.relnamespace::regnamespace || '.' || c.relname from pg_class c where c.relrowsecurity "
               "and c.relnamespace::regnamespace::text in ('mk_kernel', 'ext_catalog') order by 1")
    p.note("tables under row-level security in the Mosaikit database: " + ", ".join(rls))
    used = ["mk_kernel.organization", "mk_kernel.organization_member", "mk_kernel.team", "mk_kernel.team_member",
            "mk_kernel.user_account"]
    p.case("the kernel tables the views read (accounts, memberships, teams) have NO row-level security; "
           "extension data does", ([t for t in used if t in rls], "ext_catalog.dataset" in rls), ([], True))
    p.case("gs_reader reads the views", as_reader("select count(*) > 0 from gs_sec.user_roles"), "t")
    p.case("gs_reader cannot read the kernel tables",
           "permission denied" in as_reader("select count(*) from mk_kernel.user_account"), True,
           measure=as_reader("select count(*) from mk_kernel.user_account"))
    p.case("gs_reader cannot read extension data", "permission denied" in as_reader(
        "select count(*) from ext_catalog.dataset"), True)
    # What the member role of Mosaikit sees through an RLS table without an organization: nothing.
    # (Why the views must not be security_invoker views on extension tables: GeoServer has no
    # organization to set, so a policy on organization_id would show it no row at all.)
    get(f"{GW}/api/v1/ext/catalog/datasets", person("dconti@alfa.test"))     # seeds the demo catalog
    seen = psql("set role mk_mosaikit_member; select count(*) from ext_catalog.dataset; reset role;")
    total = psql("select count(*) from ext_catalog.dataset")
    p.note(f"ext_catalog.dataset: {total[0] if total else '?'} rows for the owner, {seen[0] if seen else '?'} "
           f"for the member role without mosaikit.organization: RLS hides everything from a reader "
           f"without an organization; the role views stay on the kernel tables, which have no RLS.")

    teams = team_ids()
    for email in ("dconti@alfa.test", "sgreco@alfa.test", "pfontana@alfa.test", "gestore@alfa.test",
                  "lbianchi@beta.test"):
        p.case(f"JDBC role service: roles of {email} = its organization and teams in Mosaikit",
               geoserver_roles("gp", email), expected_roles(email, teams))
    p.case("JDBC role service: the administrator of GeoServer is gsadmin, Mosaikit's 'admin' is NOT ADMIN",
           ("ADMIN" in geoserver_roles("gp", "gsadmin"), "ADMIN" in geoserver_roles("gp", "admin")), (True, False))

    # The fallback, for the record: the same roles through the REST role service and the stub. The
    # REST role service RENAMES them: "ROLE_" + upper case (GeoServerRestRoleService.fixGeoServerRoles).
    for email in ("dconti@alfa.test", "sgreco@alfa.test"):
        p.case(f"REST role service (fallback): roles of {email}, as ROLE_<UPPER CASE>", geoserver_roles(
            "gprest", email), sorted("ROLE_" + r.upper() for r in expected_roles(email, teams)))

    # A change through the API of Mosaikit reaches GeoServer within the authentication cache.
    sg = cookie_header("sgreco@alfa.test")
    url = wfs_url("ows", "alfa", "particelle")
    before = rows(get(url, sg))
    a = http("DELETE", f"{GW}/api/v1/teams/{teams['urbanistica']}/members/sgreco@alfa.test",
             person("gestore@alfa.test"))
    t0, dt, now = time.time(), None, before
    while time.time() - t0 < WINDOW_S:
        now = rows(get(url, sg))
        if now == 80:
            dt = round(time.time() - t0)
            break
        time.sleep(2)
    p.case(f"sgreco removed from urbanistica through the Mosaikit API (HTTP {a.status}): 200 -> 80 rows "
           f"within the 60 s authentication cache", (before, now, dt is not None and dt <= 64), (200, 80, True),
           measure=f"{dt} s" if dt is not None else f"not within {WINDOW_S} s")
    # Put it back, and wait, so that the next run starts from the same state.
    http("PUT", f"{GW}/api/v1/teams/{teams['urbanistica']}/members/sgreco@alfa.test",
         person("gestore@alfa.test"), {"role": "member"})
    t1 = time.time()
    while rows(get(url, sg)) != 200 and time.time() - t1 < WINDOW_S:
        time.sleep(2)
    p.note(f"put back in urbanistica: all rows again after {round(time.time() - t1)} s (same window)")
    return p.verdict()


if __name__ == "__main__":
    main_of(proof)
