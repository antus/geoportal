# SPDX-FileCopyrightText: 2026 Massimo Antonini
"""GP-327 F01: what the proofs share — addresses, people, HTTP, SQL, the rule set, the report.

Standard library only. The stack is compose.yaml (project geoportal-f01) plus a Mosaikit
installation with the catalog and the proof stub extensions listening on 18890 (see RESULT.md).
"""
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = "geoportal-f01"
GW = "http://localhost:18801"                    # the gateway: the only door for people
GS = "http://127.0.0.1:18480/geoserver"          # GeoServer administration, through the gateway
MK = "http://localhost:18890"                    # the kernel, directly (only to wait for it)
SCRATCH = os.environ.get("F01_SCRATCH", r"C:\temp\sviluppo\f01\scratch")


def basic(user, password):
    return "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()


GS_ADMIN = {"Authorization": basic("gsadmin", "f01-proof")}
MK_ADMIN = {"Authorization": basic("admin", "f01-admin-password")}
PASSWORD = "f01-person-password"
SERVICE = ("geoserver@f01-proof.test", "f01-service-password")

ORGANIZATIONS = {"alfa": "Alfa (F01)", "beta": "Beta (F01)", "f01-system": "Servizi (F01)"}
# person -> (organization, display name, teams)
PEOPLE = {
    "gestore@alfa.test": ("alfa", "Gestore Alfa", []),
    "dconti@alfa.test": ("alfa", "Davide Conti", ["mobilita"]),
    "sgreco@alfa.test": ("alfa", "Sara Greco", ["mobilita", "urbanistica"]),
    "pfontana@alfa.test": ("alfa", "Paolo Fontana", ["ambiente"]),
    "estraneo@alfa.test": ("alfa", "Ernesto Straneo", []),
    "lbianchi@beta.test": ("beta", "Laura Bianchi", []),
    SERVICE[0]: ("f01-system", "GeoServer (service account)", []),
}
TEAMS = ["mobilita", "urbanistica", "ambiente"]


# --- HTTP ----------------------------------------------------------------------------------------
class Answer:
    def __init__(self, status, headers, body):
        self.status, self.headers, self.body = status, headers, body

    @property
    def type(self):
        return (self.headers.get("Content-Type") or "").split(";")[0]

    def json(self):
        return json.loads(self.body)


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def http(method, url, headers=None, body=None, ctype=None, timeout=120):
    h = dict(headers or {})
    if isinstance(body, (dict, list)):
        body, ctype = json.dumps(body).encode(), "application/json"
    elif isinstance(body, str):
        body = body.encode()
    if ctype:
        h["Content-Type"] = ctype
    req = urllib.request.Request(url, data=body, headers=h, method=method)
    try:
        with _OPENER.open(req, timeout=timeout) as r:
            return Answer(r.status, r.headers, r.read())
    except urllib.error.HTTPError as e:
        return Answer(e.code, e.headers, e.read())
    except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
        return Answer(0, {}, str(e).encode())


def get(url, headers=None, **kw):
    return http("GET", url, headers, **kw)


def wait_for(url, headers=None, seconds=300, ok=(200,)):
    t0 = time.time()
    while time.time() - t0 < seconds:
        if get(url, headers, timeout=10).status in ok:
            return round(time.time() - t0, 1)
        time.sleep(2)
    raise SystemExit(f"{url} does not answer after {seconds} s")


# --- Mosaikit ------------------------------------------------------------------------------------
_COOKIES = {}


def session(email, fresh=False):
    """The session cookie of a person, signed in through the gateway as the shell does."""
    if email in _COOKIES and not fresh:
        return _COOKIES[email]
    form = urllib.parse.urlencode({"username": email, "password": PASSWORD})
    a = http("POST", f"{GW}/api/v1/accounts/session", body=form, ctype="application/x-www-form-urlencoded")
    cookie = None
    for k, v in a.headers.items():
        if k.lower() == "set-cookie" and v.startswith("mosaikit-session="):
            cookie = v.split(";")[0].split("=", 1)[1]
    if not cookie:
        raise SystemExit(f"sign-in of {email} failed: {a.status} {a.body[:200]!r}")
    _COOKIES[email] = cookie
    return cookie


def cookie_header(email):
    return {"Cookie": f"mosaikit-session={session(email)}"}


def person(email):
    """HTTP Basic of a person, for API calls (the kernel accepts it as the shell's session)."""
    return {"Authorization": basic(email, PASSWORD)}


def team_ids():
    a = get(f"{GW}/api/v1/teams", person("gestore@alfa.test"))
    return {t["name"]: t["id"] for t in a.json() if t.get("kind", "team") == "team"}


# --- Docker and SQL --------------------------------------------------------------------------------
def compose(*args, check=True, capture=True, stdin=None):
    return subprocess.run(["docker", "compose", "-p", PROJECT, *args], cwd=HERE, check=check,
                          capture_output=capture, text=True, input=stdin, encoding="utf-8")


def psql(sql, db="mosaikit", user="mosaikit", check=True):
    """Runs SQL in the PostGIS container; returns the rows, fields separated by |."""
    r = compose("exec", "-T", "postgis", "psql", "-v", "ON_ERROR_STOP=1", "-q", "-At", "-U", user,
                "-d", db, check=False, stdin=sql)
    if check and r.returncode:
        raise RuntimeError(f"psql ({db} as {user}): {r.stderr.strip()}")
    return [l for l in r.stdout.splitlines() if l] if not r.returncode else r.stderr.strip()


# --- OGC through the gateway ------------------------------------------------------------------------
def wfs_url(door, ws, layer, extra=""):
    return (f"{GW}/gw/{door}/{ws}/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames={ws}:{layer}"
            f"&outputFormat=application/json{extra}")


def wms_url(door, ws, layer, extra=""):
    return (f"{GW}/gw/{door}/{ws}/wms?service=WMS&version=1.3.0&request=GetMap&layers={ws}:{layer}&styles="
            f"&crs=EPSG:4326&bbox=42.08,12.36,42.12,12.44&width=256&height=256&format=image/png{extra}")


def summary(a):
    """What a WFS answer contains: rows, districts, states, the reserved column; or 'denied'."""
    if a.status != 200 or "json" not in a.type:
        return {"outcome": "denied", "http": a.status}
    f = a.json()["features"]
    return {"outcome": "data", "rows": len(f),
            "municipi": sorted({x["properties"].get("municipio") for x in f} - {None}),
            "stati": sorted({x["properties"].get("stato") for x in f} - {None}),
            "cf": any("cf_titolare" in x["properties"] for x in f)}


def rows(a):
    s = summary(a)
    return s["rows"] if s["outcome"] == "data" else "denied"


def image(a):
    return "image" if a.status == 200 and a.type.startswith("image/") else f"denied ({a.status} {a.type})"


ALL = {"outcome": "data", "rows": 200, "municipi": ["I", "II"], "stati": ["attivo", "soppresso"], "cf": True}
REDUCED = {"outcome": "data", "rows": 80, "municipi": ["I"], "stati": ["attivo"], "cf": False}


# --- GeoFence: the rule set, as the catalog will write it -----------------------------------------
AREA_DISTRICT_I = "SRID=4326;MULTIPOLYGON(((12.36 42.08,12.39 42.08,12.39 42.2,12.36 42.2,12.36 42.08)))"
COLUMNS_VISIBLE = ("fid", "foglio", "particella", "municipio", "stato", "geom")


def rule_set(teams):
    """The rules of the proofs. GeoFence 2.28.5 drops a different part of a rule per format (F0):
    the column list in JSON, the area in XML; so each rule goes in the format that keeps it."""
    mob, urb, amb = (f"TEAM_{teams[t]}" for t in TEAMS)
    col = lambda name, access: f"<attribute><name>{name}</name><accessType>{access}</accessType></attribute>"
    return [
        # Mobility: district I only (area, JSON) ...
        ("json", {"Rule": {"priority": 1, "roleName": mob, "workspace": "alfa", "layer": "particelle",
                           "access": "LIMIT", "limits": {"allowedArea": AREA_DISTRICT_I, "catalogMode": "HIDE"}}}),
        # ... active parcels only (CQL) and without the tax code (hidden column) (XML).
        ("xml", f"<Rule><priority>2</priority><roleName>{mob}</roleName><workspace>alfa</workspace>"
                f"<layer>particelle</layer><access>ALLOW</access><layerDetails><layerType>VECTOR</layerType>"
                f"<cqlFilterRead>stato = 'attivo'</cqlFilterRead>" + col("cf_titolare", "NONE")
                + "".join(col(c, "READONLY") for c in COLUMNS_VISIBLE) + "</layerDetails></Rule>"),
        # Urban planning: the whole workspace.
        ("xml", f"<Rule><priority>3</priority><roleName>{urb}</roleName><workspace>alfa</workspace>"
                f"<access>ALLOW</access></Rule>"),
        # Environment: view (WMS) yes, download (WFS) no.
        ("xml", f"<Rule><priority>4</priority><roleName>{amb}</roleName><workspace>alfa</workspace>"
                f"<service>WMS</service><access>ALLOW</access></Rule>"),
        # Monteluce, the second organization: every member of it.
        ("xml", "<Rule><priority>5</priority><roleName>ORG_beta_user</roleName><workspace>beta</workspace>"
                "<access>ALLOW</access></Rule>"),
        # The base rule of every organization's workspace: deny (ADR-0005).
        ("xml", "<Rule><priority>900</priority><workspace>alfa</workspace><access>DENY</access></Rule>"),
        ("xml", "<Rule><priority>901</priority><workspace>beta</workspace><access>DENY</access></Rule>"),
        # The catch-all of distribution/server/geoserver/init/prepare.sh, kept to prove that the
        # workspace rules above hold in front of it.
        ("json", {"Rule": {"priority": 99999, "access": "ALLOW"}}),
    ]


def write_rules(teams):
    """Deletes every rule, writes the set, re-reads it and invalidates the cache. Returns what was
    re-read, by priority, and the seconds it took."""
    t0 = time.time()
    for r in get(f"{GS}/rest/geofence/rules.json", GS_ADMIN).json()["rules"]:
        http("DELETE", f"{GS}/rest/geofence/rules/id/{r['id']}", GS_ADMIN)
    for fmt, rule in rule_set(teams):
        a = http("POST", f"{GS}/rest/geofence/rules", GS_ADMIN, rule,
                 ctype=None if fmt == "json" else "application/xml")
        if a.status != 201:
            raise RuntimeError(f"rule not written: {a.status} {a.body[:200]!r}")
    kept = {r["priority"]: r for r in get(f"{GS}/rest/geofence/rules.json", GS_ADMIN).json()["rules"]}
    http("PUT", f"{GS}/rest/ruleCache/invalidate", GS_ADMIN)
    return kept, round(time.time() - t0, 2)


def invalidate_rules():
    http("PUT", f"{GS}/rest/ruleCache/invalidate", GS_ADMIN)


# --- The report ------------------------------------------------------------------------------------
class Proof:
    """Collects the cases of a proof and prints measure, outcome and verdict."""

    def __init__(self, number, title):
        self.number, self.title, self.cases, self.notes = number, title, [], []
        print(f"\n=== Proof {number}: {title}")

    def case(self, what, got, expected=None, ok=None, measure=""):
        if ok is None:
            ok = got == expected
        self.cases.append((what, ok))
        line = f"{'ok ' if ok else 'NO '} {what}"
        if measure:
            line += f"  [{measure}]"
        if not ok:
            line += f"\n      expected {expected}\n      got      {got}"
        elif expected is None:
            line += f"  -> {got}"
        print(line, flush=True)
        return ok

    def note(self, text):
        self.notes.append(text)
        print(f"    note: {text}", flush=True)

    def verdict(self):
        good = sum(ok for _, ok in self.cases)
        total = len(self.cases)
        holds = good == total and total > 0
        print(f"--- Proof {self.number}: {good}/{total} as expected -> {'HOLDS' if holds else 'DOES NOT HOLD'}")
        return holds


def main_of(proof_function):
    """Runs one proof as a script: exit code 0 when it holds."""
    sys.exit(0 if proof_function() else 1)
