# SPDX-FileCopyrightText: 2026 Massimo Antonini
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Proof 3: GeoFence rules written over REST, with Mosaikit people and roles as subjects.

Writes the whole rule set as the catalog will (common.rule_set), re-reads what GeoFence kept, and
reads what WFS and WMS answer to each person through the gateway: a LIMIT rule with an area, a CQL
filter and a hidden column reduce the 200 rows to 80 and remove the column; the base rule of the
workspace is deny. It also re-measures the format bug of GeoFence 2.28.5 found in F0.
"""
from common import (ALL, AREA_DISTRICT_I, GS, GS_ADMIN, REDUCED, Proof, cookie_header, get, http, image,
                    invalidate_rules, main_of, summary, team_ids, wfs_url, wms_url, write_rules)


def format_bug(teams):
    """Writes an area rule as XML and a column rule as JSON (the wrong formats), with scratch
    subjects, reads back what GeoFence kept, deletes them. Run BEFORE write_rules: an insert shifts
    the priority of every rule after it, and a delete does not shift them back."""
    area_xml = ("<Rule><priority>7001</priority><roleName>PROBE_AREA</roleName>"
                f"<workspace>alfa</workspace><layer>particelle</layer><access>LIMIT</access>"
                f"<limits><allowedArea>{AREA_DISTRICT_I}</allowedArea><catalogMode>HIDE</catalogMode></limits></Rule>")
    columns_json = {"Rule": {"priority": 7002, "roleName": "PROBE_COLUMNS", "workspace": "alfa",
                             "layer": "particelle", "access": "ALLOW",
                             "layerDetails": {"layerType": "VECTOR", "cqlFilterRead": "stato = 'attivo'",
                                              "attributes": [{"name": "cf_titolare", "accessType": "NONE"},
                                                             {"name": "fid", "accessType": "READONLY"}]}}}
    s1 = http("POST", f"{GS}/rest/geofence/rules", GS_ADMIN, area_xml, ctype="application/xml").status
    s2 = http("POST", f"{GS}/rest/geofence/rules", GS_ADMIN, columns_json).status
    kept = {r.get("roleName"): r for r in get(f"{GS}/rest/geofence/rules.json", GS_ADMIN).json()["rules"]}
    area = (kept.get("PROBE_AREA", {}).get("limits") or {}).get("allowedArea")
    cols = (kept.get("PROBE_COLUMNS", {}).get("layerDetails") or {}).get("attributes") or []
    for role in ("PROBE_AREA", "PROBE_COLUMNS"):
        if role in kept:
            http("DELETE", f"{GS}/rest/geofence/rules/id/{kept[role]['id']}", GS_ADMIN)
    invalidate_rules()
    return (s1, bool(area)), (s2, len(cols))


def proof():
    p = Proof(3, "GeoFence rules by REST, with Mosaikit roles as subjects")
    teams = team_ids()
    (s_area, area_kept), (s_cols, cols_kept) = format_bug(teams)
    kept, seconds = write_rules(teams)
    p.case("rule set written, re-read and cache invalidated", sorted(kept),
           [1, 2, 3, 4, 5, 900, 901, 99999], measure=f"{len(kept)} rules in {seconds} s")
    p.case("re-read: rule 1 (JSON) kept its area", bool(kept[1]["limits"]["allowedArea"]), True)
    details = kept[2]["layerDetails"]
    p.case("re-read: rule 2 (XML) kept its CQL filter and its 7 columns",
           (details.get("cqlFilterRead"), len(details["attributes"])), ("stato = 'attivo'", 7))
    p.case("the subject is the Mosaikit team id (TEAM_<uuid>), not a GeoServer group",
           kept[1]["roleName"], f"TEAM_{teams['mobilita']}")
    p.case("GeoFence 2.28.5 still drops the area of a rule written as XML (answering 201)",
           (s_area, area_kept), (201, False))
    p.case("GeoFence 2.28.5 still drops the column list of a rule written as JSON (answering 201)",
           (s_cols, cols_kept), (201, 0))

    url = wfs_url("ows", "alfa", "particelle")
    dconti = summary(get(url, cookie_header("dconti@alfa.test")))
    p.case("dconti (mobilita): LIMIT area + CQL + hidden column -> 80 rows of 200, district I, active, "
           "no cf_titolare", dconti, REDUCED)
    p.case("sgreco (mobilita + urbanistica): rules of two teams add up -> all 200", summary(
        get(url, cookie_header("sgreco@alfa.test"))), ALL)
    pf = cookie_header("pfontana@alfa.test")
    p.case("pfontana (ambiente): view (WMS) yes", image(get(wms_url("ows", "alfa", "particelle"), pf)), "image")
    p.case("pfontana (ambiente): download (WFS) no", summary(get(url, pf))["outcome"], "denied")
    p.case("estraneo (member, no team): deny by default on the workspace",
           summary(get(url, cookie_header("estraneo@alfa.test")))["outcome"], "denied")
    p.case("anonymous through the key door without a key: deny by default",
           summary(get(wfs_url("key", "alfa", "particelle")))["outcome"], "denied")
    p.case("alfa members get nothing of beta (gateway 403 before GeoFence)",
           get(wfs_url("ows", "beta", "fermate"), cookie_header("sgreco@alfa.test")).status, 403)
    # A workspace WITHOUT its deny rule, behind the catch-all ALLOW of the server distribution.
    open_ws = get(f"{GS.replace('/geoserver', '')}/geoserver/aperto/wfs?service=WFS&version=2.0.0&request="
                  f"GetFeature&typeNames=aperto:fermate&outputFormat=application/json")
    leaked = summary(open_ws)
    p.note(f"risk: anonymous on a workspace that has no deny rule of its own gets "
           f"{leaked.get('rows', leaked['outcome'])} rows, because distribution/server/geoserver/init/"
           f"prepare.sh writes a catch-all ALLOW at priority 99999; the catalog must write the deny of "
           f"a workspace BEFORE publishing anything in it, or the catch-all should become DENY.")
    return p.verdict()


if __name__ == "__main__":
    main_of(proof)
