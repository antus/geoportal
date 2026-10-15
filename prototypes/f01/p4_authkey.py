# SPDX-FileCopyrightText: 2026 Massimo Antonini
"""Proof 4: AuthKey with the web service mapper on an endpoint of the proof stub extension.

A person creates a personal key in Mosaikit (POST /api/v1/ext/proofstub/keys). QGIS sends it as
?authkey= to the key door of the gateway (/gw/key/<workspace>/<service>), which strips any user
header. GeoServer's AuthKey filter asks the proof stub for the NAME of the owner (through the internal
door of the gateway, which adds the service account's credentials) and reads user and roles from the
same views as the gateway path. The proof compares the rows with those of the session path, then
revokes the key and measures how long it keeps working.
"""
import re
import time

from common import (GW, REDUCED, Proof, cookie_header, get, http, image, invalidate_rules, main_of, person,
                    summary, wfs_url, wms_url)

WINDOW_S = 150


def new_key(email):
    a = http("POST", f"{GW}/api/v1/ext/proofstub/keys", person(email))
    assert a.status == 200, (a.status, a.body[:300])
    return a.json()["key"]


def fids(a):
    return sorted(f["properties"].get("fid", f.get("id")) for f in a.json()["features"]) if a.status == 200 \
        and "json" in a.type else None


def proof():
    p = Proof(4, "AuthKey with the web service mapper on the proof stub extension")
    k_dconti, k_sgreco = new_key("dconti@alfa.test"), new_key("sgreco@alfa.test")
    # Decisions are cached by GeoFence per user name: start from an empty cache, so that the key
    # path is not helped by a decision taken for the session path (F0).
    invalidate_rules()
    by_key = get(wfs_url("key", "alfa", "particelle", f"&authkey={k_dconti}"))
    by_session = get(wfs_url("ows", "alfa", "particelle"), cookie_header("dconti@alfa.test"))
    p.case("dconti with his key (as QGIS): the same summary as through the gateway session",
           summary(by_key), summary(by_session))
    p.case("dconti with his key: exactly the same features (fid by fid) as with the session",
           fids(by_key) == fids(by_session) and fids(by_key) is not None, True,
           measure=f"{len(fids(by_key) or [])} features")
    p.case("dconti with his key: the rows of his team", summary(by_key), REDUCED)
    invalidate_rules()
    p.case("sgreco with her key: the roles of two teams from this path too", summary(
        get(wfs_url("key", "alfa", "particelle", f"&authkey={k_sgreco}")))["rows"], 200)
    p.case("dconti's key, WMS GetMap (QGIS view)", image(
        get(wms_url("key", "alfa", "particelle", f"&authkey={k_dconti}"))), "image")
    p.case("unknown key: anonymous, denied", summary(
        get(wfs_url("key", "alfa", "particelle", "&authkey=not-a-key")))["outcome"], "denied")
    p.case("a key on the session door is not a session: 401", get(
        wfs_url("ows", "alfa", "particelle", f"&authkey={k_dconti}")).status, 401)
    # What QGIS will follow next: the addresses inside the capabilities.
    caps = get(f"{GW}/gw/key/alfa/wfs?service=WFS&version=2.0.0&request=GetCapabilities&authkey={k_dconti}")
    hrefs = sorted(set(re.findall(r'xlink:href="([^"?]+)', caps.body.decode("utf-8", "replace"))))
    p.note(f"capabilities through the key door advertise {hrefs[:2]}: not the gateway path /gw/key/alfa/wfs, "
           f"so QGIS would follow addresses that do not exist outside the gateway (proxy base URL to set, "
           f"per door, in GP-336)")

    # Revocation: the key stops working when both caches expire (mapper 30 s, authentication 60 s).
    t_revoke = time.time()
    a = http("DELETE", f"{GW}/api/v1/ext/proofstub/keys/{k_dconti}", person("dconti@alfa.test"))
    still, dt = True, None
    while time.time() - t_revoke < WINDOW_S:
        r = summary(get(wfs_url("key", "alfa", "particelle", f"&authkey={k_dconti}")))
        if r["outcome"] == "denied":
            still, dt = False, round(time.time() - t_revoke)
            break
        time.sleep(2)
    p.case(f"revoked key (HTTP {a.status}) stops working within 60 s", (still, dt is not None and dt <= 64),
           (False, True), measure=f"stopped after {dt} s" if dt is not None else f"still working after {WINDOW_S} s")
    return p.verdict()


if __name__ == "__main__":
    main_of(proof)
