# SPDX-FileCopyrightText: 2026 Massimo Antonini
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Proof 1: GeoServer takes the person's name from a header only when the gateway sends it.

The name travels in `sec-username` (GeoServer's request header filter). The proof sends that header
from every place it could come from and reads what GeoServer answers (rows of a WFS GetFeature),
not what the configuration says.
"""
from common import (GS, REDUCED, Proof, compose, cookie_header, get, main_of, summary, wfs_url)

FORGED = {"sec-username": "sgreco@alfa.test"}   # sgreco sees all 200 rows
WFS_PATH = ("/geoserver/alfa/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=alfa:particelle"
            "&outputFormat=application/json")


def from_container(network, ip=None):
    """A request with the forged header from a throw-away container on a Docker network of the stack."""
    args = ["run", "--rm", "--network", f"geoportal-f01_{network}"]
    if ip:
        args += ["--ip", ip]
    r = __import__("subprocess").run(
        ["docker", *args, "curlimages/curl:8.16.0", "-s", "-o", "/dev/null", "-w", "%{http_code}",
         "-H", "sec-username: sgreco@alfa.test", f"http://geoserver:8080{WFS_PATH}"],
        capture_output=True, text=True)
    return r.stdout.strip() or r.stderr.strip()[:120]


def proof():
    p = Proof(1, "the user header is accepted only from the gateway")
    p.case("gateway, no session, forged sec-username: refused by the gateway",
           get(wfs_url("ows", "alfa", "particelle"), FORGED).status, 401)
    p.case("gateway, session of dconti + forged sec-username of sgreco: dconti's rows, not sgreco's",
           summary(get(wfs_url("ows", "alfa", "particelle"), {**cookie_header("dconti@alfa.test"), **FORGED})),
           REDUCED)
    p.case("gateway, QGIS door (no session, no key) + forged sec-username: anonymous, denied",
           summary(get(wfs_url("key", "alfa", "particelle"), FORGED))["outcome"], "denied")
    p.case("administration door 127.0.0.1:18480 + forged sec-username: stripped, anonymous, denied",
           summary(get(f"{GS.replace('/geoserver', '')}{WFS_PATH}", FORGED))["outcome"], "denied")
    published = compose("port", "geoserver", "8080", check=False)
    p.case("GeoServer publishes no port on the host",
           published.stdout.strip() if published.stdout.strip() not in ("", ":0") else "none", "none")
    p.case("container on the db network (same network as GeoServer) + forged header: Tomcat 403",
           from_container("db"), "403")
    p.case("container on the gs network, another address than the gateway's + forged header: Tomcat 403",
           from_container("gs", "10.231.0.99"), "403")
    # Control: the same request from the gateway's own address is accepted. Proves that the 403
    # above comes from the address check, not from a broken GeoServer.
    r = compose("exec", "-T", "gateway", "wget", "-q", "-O", "-", "--header", "sec-username: sgreco@alfa.test",
                f"http://geoserver:8080{WFS_PATH}", check=False)
    got = "data" if '"features"' in r.stdout else f"no data ({r.stderr.strip()[:80]})"
    p.case("control: the same header from the gateway's address IS taken (so only the gateway may send it)",
           got, "data")
    p.note("GeoServer itself cannot tell the gateway from anyone: the header filter has no address "
           "check, and GeoFence reads the caller's address from X-Forwarded-For. The Tomcat "
           "RemoteAddrValve (geoserver/server.xml) is what enforces it.")
    return p.verdict()


if __name__ == "__main__":
    main_of(proof)
