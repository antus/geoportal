# SPDX-FileCopyrightText: 2026 Massimo Antonini
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Proof 2: nginx asks the proof stub extension whether the Mosaikit session may read the workspace.

`auth_request` calls GET /api/v1/ext/proofstub/gateway-check with the session cookie and
X-Mosaikit-Organization = workspace. The kernel refuses a request without a session (401) and one of
a person who is not a member of that organization (403); the stub answers 200 with the name, which
nginx passes to GeoServer. The go-ahead is cached for 10 s per session and workspace.
"""
import time

from common import (ALL, GW, MK_ADMIN, REDUCED, Proof, cookie_header, get, http, image, main_of, rows, session,
                    summary, wfs_url, wms_url)

CACHE_S = 10


def proof():
    p = Proof(2, "nginx auth_request on the Mosaikit session")
    sg = cookie_header("sgreco@alfa.test")
    a = get(wms_url("ows", "alfa", "particelle"), sg)
    p.case("sgreco (session): WMS GetMap passes, bytes are an image", image(a), "image",
           measure=f"{len(a.body)} bytes")
    p.case("sgreco (session): WFS, all 200 rows", summary(get(wfs_url("ows", "alfa", "particelle"), sg)), ALL)
    p.case("dconti (session): WFS, the rows of his team", summary(
        get(wfs_url("ows", "alfa", "particelle"), cookie_header("dconti@alfa.test"))), REDUCED)
    p.case("no session: 401 from the gateway", get(wms_url("ows", "alfa", "particelle")).status, 401)
    p.case("forged session cookie: 401", get(wms_url("ows", "alfa", "particelle"),
                                             {"Cookie": "mosaikit-session=forged-value"}).status, 401)
    p.case("HTTP Basic of a real person instead of the session: ignored, 401 (only the cookie counts)",
           get(wms_url("ows", "alfa", "particelle"),
               {"Authorization": __import__("common").basic("sgreco@alfa.test", "f01-person-password")}).status, 401)
    lb = cookie_header("lbianchi@beta.test")
    p.case("lbianchi (member of beta only) on the alfa workspace: 403", get(
        wms_url("ows", "alfa", "particelle"), lb).status, 403)
    p.case("lbianchi on her own workspace beta: 50 rows", rows(get(wfs_url("ows", "beta", "fermate"), lb)), 50)
    est = get(wfs_url("ows", "alfa", "particelle"), cookie_header("estraneo@alfa.test"))
    p.case("estraneo (member of alfa, in no team): the gateway lets him through, GeoFence gives nothing",
           (est.headers.get("X-Gp-Check-Cache") is not None, summary(est)["outcome"]), (True, "denied"))

    # The short cache: the first go-ahead asks the kernel, the next ones within 10 s do not.
    fresh = session("pfontana@alfa.test", fresh=True)
    h = {"Cookie": f"mosaikit-session={fresh}"}
    timings, states = [], []
    for _ in range(5):
        t0 = time.perf_counter()
        a = get(wms_url("ows", "alfa", "particelle"), h)
        timings.append(round((time.perf_counter() - t0) * 1000))
        states.append(a.headers.get("X-Gp-Check-Cache"))
    p.case("go-ahead cache: first call asks the kernel, the next four come from the cache", states,
           ["MISS", "HIT", "HIT", "HIT", "HIT"], measure=f"GetMap ms {timings}")

    # Removing a person from the organization closes the gateway within the cache time.
    est_h = cookie_header("estraneo@alfa.test")
    get(wms_url("ows", "alfa", "particelle"), est_h)        # warm the cache: 200
    a = http("DELETE", f"{GW}/api/v1/organizations/alfa/members/estraneo@alfa.test", MK_ADMIN)
    t0 = time.time()
    status = None
    while time.time() - t0 < CACHE_S + 20:
        status = get(wms_url("ows", "alfa", "particelle"), est_h).status
        if status == 403:
            break
        time.sleep(1)
    dt = round(time.time() - t0, 1)
    p.case(f"estraneo removed from alfa (API {a.status}): the gateway refuses him within the {CACHE_S} s cache",
           (status, dt <= CACHE_S + 2), (403, True), measure=f"{dt} s")
    http("PUT", f"{GW}/api/v1/organizations/alfa/members/estraneo@alfa.test", MK_ADMIN,
         {"roles": ["organization-user"]})
    # Signing out does not end a stolen cookie: the session of Mosaikit is an encrypted cookie
    # (Quarkus form authentication), with no server-side state to revoke.
    cookie = session("dconti@alfa.test", fresh=True)
    out = http("DELETE", f"{GW}/api/v1/accounts/session", {"Cookie": f"mosaikit-session={cookie}"})
    time.sleep(CACHE_S + 1)
    after = get(wms_url("ows", "alfa", "particelle"), {"Cookie": f"mosaikit-session={cookie}"})
    p.note(f"sign-out (HTTP {out.status}) then the SAME cookie replayed after the cache: gateway answers "
           f"{after.status} - " + ("the cookie still works until it expires (no server-side revocation)"
                                   if after.status == 200 else "the cookie is refused"))
    return p.verdict()


if __name__ == "__main__":
    main_of(proof)
