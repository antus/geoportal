# SPDX-FileCopyrightText: 2026 Massimo Antonini
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Proof 6: the GeoServer Importer loads vector files into PostGIS with names, types and reference
system right.

Uploads each file of samples.py to the Importer (target: workspace alfa, PostGIS store "imports"),
runs it, then reads what GeoServer SERVES, not the state of the task: rows (WFS), position of the
first feature reprojected to 4326 (inside Valverde, the invented town of the samples, or the metres were taken for degrees), column names
and types (DescribeFeatureType), native reference system (REST) and the geometry column in PostGIS.
"""
import json
import os
import time
import uuid

from common import GS, GS_ADMIN, SCRATCH, Proof, get, http, main_of, psql
from samples import COLUMNS, make

VALVERDE = (12.30, 42.05, 12.50, 42.15)
KINDS = {"int": "int", "integer": "int", "long": "int", "short": "int",
         "number": "number", "double": "number", "float": "number", "decimal": "number",
         "string": "string", "date": "date", "date-time": "date", "datetime": "date", "timestamp": "date",
         "boolean": "boolean"}
# What a format can carry: GeoJSON has no date type (a reader may still recognise ISO dates), CSV has
# no types at all.
EXPECTED = {
    "geojson": {"codice": "int", "nome": "string", "quota": "number", "attivata": ("date", "string"),
                "attiva": "boolean"},
    "zip": {"codice": "int", "nome": "string", "quota": "number", "attivata": "date", "attiva": "boolean"},
    "gpkg": {"codice": "int", "nome": "string", "quota": "number", "attivata": "date", "attiva": "boolean"},
    "csv": {"codice": "int", "nome": "string", "quota": "number", "attivata": ("date", "string"),
            "attiva": ("boolean", "string")},
}


def reset_store():
    http("DELETE", f"{GS}/rest/workspaces/alfa/datastores/imports?recurse=true", GS_ADMIN)
    psql("drop schema if exists imports cascade; create schema imports;", db="geodata", user="geoserver")
    from setup import store
    s = store("alfa", "imports")
    s["dataStore"]["name"] = "imports"
    a = http("POST", f"{GS}/rest/workspaces/alfa/datastores", GS_ADMIN, s)
    assert a.status == 201, a.body[:200]


def multipart(path):
    b = uuid.uuid4().hex
    with open(path, "rb") as f:
        data = f.read()
    body = (f'--{b}\r\nContent-Disposition: form-data; name="filedata"; filename="{os.path.basename(path)}"\r\n'
            f"Content-Type: application/octet-stream\r\n\r\n").encode() + data + f"\r\n--{b}--\r\n".encode()
    return body, f"multipart/form-data; boundary={b}"


def run_import(path):
    t0 = time.time()
    a = http("POST", f"{GS}/rest/imports", GS_ADMIN, {"import": {
        "targetWorkspace": {"workspace": {"name": "alfa"}}, "targetStore": {"dataStore": {"name": "imports"}}}})
    if a.status != 201:
        return {"error": f"import not created ({a.status})"}
    iid = a.json()["import"]["id"]
    body, ctype = multipart(path)
    a = http("POST", f"{GS}/rest/imports/{iid}/tasks", GS_ADMIN, body, ctype=ctype)
    if a.status not in (200, 201):
        return {"error": f"file refused: HTTP {a.status} {a.body[:150].decode('utf-8', 'replace')}"}
    tasks = get(f"{GS}/rest/imports/{iid}/tasks", GS_ADMIN).json()["tasks"]
    if not tasks:
        return {"error": "no task: format not recognised"}
    before = [t["state"] for t in tasks]
    http("POST", f"{GS}/rest/imports/{iid}", GS_ADMIN)
    for _ in range(120):
        state = get(f"{GS}/rest/imports/{iid}", GS_ADMIN).json()["import"]["state"]
        if state not in ("RUNNING", "PENDING", "INIT"):
            break
        time.sleep(1)
    layers, after, errors = [], [], []
    for t in get(f"{GS}/rest/imports/{iid}/tasks", GS_ADMIN).json()["tasks"]:
        task = get(f"{GS}/rest/imports/{iid}/tasks/{t['id']}", GS_ADMIN).json()["task"]
        after.append(task["state"])
        if task.get("errorMessage"):
            errors.append(task["errorMessage"][:150])
        if task["state"] == "COMPLETE":
            layers.append(get(f"{GS}/rest/imports/{iid}/tasks/{t['id']}/layer", GS_ADMIN).json()["layer"]["name"])
    return {"seconds": round(time.time() - t0, 1), "states": f"{before} -> {after}", "layers": layers,
            "errors": errors}


def served(layer):
    """What GeoServer serves of an imported layer."""
    out = {}
    a = get(f"{GS}/alfa/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=alfa:{layer}"
            f"&outputFormat=application/json&srsName=EPSG:4326", GS_ADMIN)
    try:
        fc = a.json()
    except ValueError:
        return {"error": f"WFS {a.status}"}
    feats = fc["features"]
    out["rows"] = len(feats)
    geom = feats[0]["geometry"] if feats else None
    if geom:
        first = geom["coordinates"]
        while isinstance(first[0], list):
            first = first[0]
        out["in_place"] = VALVERDE[0] <= first[0] <= VALVERDE[2] and VALVERDE[1] <= first[1] <= VALVERDE[3]
        out["first"] = [round(c, 4) for c in first[:2]]
        out["geometry"] = geom["type"]
    d = get(f"{GS}/alfa/wfs?service=WFS&version=2.0.0&request=DescribeFeatureType&typeNames=alfa:{layer}"
            f"&outputFormat=application/json", GS_ADMIN)
    try:
        props = d.json()["featureTypes"][0]["properties"]
        out["columns"] = {p["name"]: p.get("localType", p.get("type")) for p in props
                          if not p.get("type", "").startswith("gml:")}
    except (ValueError, KeyError, IndexError):
        out["columns"] = {}
    ft = get(f"{GS}/rest/workspaces/alfa/datastores/imports/featuretypes/{layer}.json", GS_ADMIN)
    out["srs"] = ft.json()["featureType"].get("srs") if ft.status == 200 else None
    pg = psql(f"select srid, type from geometry_columns where f_table_schema = 'imports' "
              f"and f_table_name = '{layer}'", db="geodata", user="geoserver")
    out["postgis"] = pg[0] if pg else None
    return out


def judge(path, rows, epsg, geom, result):
    """Right or wrong, and why, for one file."""
    if not result.get("layers"):
        return False, result.get("error") or "; ".join(result.get("errors", [])) or result.get("states", "")
    s = served(result["layers"][0])
    why = []
    if s.get("rows") != rows:
        why.append(f"rows {s.get('rows')} (expected {rows})")
    if not s.get("in_place"):
        why.append(f"first point {s.get('first')} is not in Valverde (metres taken for degrees?)")
    if s.get("srs") != f"EPSG:{epsg}":
        why.append(f"srs {s.get('srs')} (expected EPSG:{epsg})")
    if not s.get("postgis") or not s["postgis"].startswith(f"{epsg}|"):
        why.append(f"PostGIS geometry {s.get('postgis')} (expected SRID {epsg})")
    lower = {k.lower(): v for k, v in s.get("columns", {}).items()}
    expected = EXPECTED[os.path.splitext(path)[1].lstrip(".")]
    for name, kind in expected.items():
        got = KINDS.get(str(lower.get(name)).lower(), lower.get(name))
        if name not in lower:
            why.append(f"column {name} missing")
        elif got not in (kind if isinstance(kind, tuple) else (kind,)):
            why.append(f"{name} is {lower[name]} (expected {kind})")
    names = sorted(k for k in s.get("columns", {}) if k.lower() in COLUMNS)
    detail = f"{s.get('rows')} rows, srs {s.get('srs')}, PostGIS {s.get('postgis')}, columns {names}"
    return not why, ("; ".join(why) + " | " if why else "") + detail


def direct(name, table, epsg, geom_kind, rows_sql):
    """The other way (F0 decision): the application writes PostGIS and publishes with one REST call."""
    t0 = time.time()
    psql(f"drop table if exists imports.{table}; create table imports.{table} (fid serial primary key, codice int, "
         f"nome text, quota double precision, attivata date, attiva boolean, geom geometry({geom_kind}, {epsg})); "
         f"insert into imports.{table} (codice, nome, quota, attivata, attiva, geom) values {rows_sql};",
         db="geodata", user="geoserver")
    a = http("POST", f"{GS}/rest/workspaces/alfa/datastores/imports/featuretypes", GS_ADMIN,
             {"featureType": {"name": table, "srs": f"EPSG:{epsg}", "projectionPolicy": "FORCE_DECLARED"}})
    s = served(table) if a.status == 201 else {"error": a.status}
    return s, round(time.time() - t0, 1)


def direct_baseline(p):
    """The three kinds of file the Importer gets wrong, written straight into PostGIS."""
    from samples import DISTRICTS, STATIONS, square, utm33
    v = lambda s, g: (f"({s['codice']}, '{s['nome']}', {s['quota']}, '{s['attivata']}', {str(s['attiva']).lower()}, {g})")
    projected = ", ".join(v(s, "ST_SetSRID(ST_MakePoint({}, {}), 32633)".format(*utm33(s["lon"], s["lat"])))
                          for s in STATIONS)
    polygons = ", ".join(v(s, "ST_GeomFromText('POLYGON((" + ",".join(
        "{} {}".format(*utm33(*pt)) for pt in square(s["lon"], s["lat"])) + "))', 32633)") for s in DISTRICTS)
    latlon = ", ".join(v(s, f"ST_SetSRID(ST_MakePoint({s['lon']}, {s['lat']}), 4326)") for s in STATIONS)
    for name, table, epsg, kind, rows_sql, rows in (
            ("GeoJSON with crs 32633", "direct_geojson_32633", 32633, "Point", projected, 12),
            ("GeoPackage polygons 32633", "direct_gpkg_32633", 32633, "Polygon", polygons, 6),
            ("CSV lat/lon", "direct_csv", 4326, "Point", latlon, 12)):
        s, seconds = direct(name, table, epsg, kind, rows_sql)
        p.case(f"direct PostGIS write + REST publish, {name}",
               (s.get("rows"), s.get("in_place"), s.get("srs"), (s.get("postgis") or "").split("|")[0]),
               (rows, True, f"EPSG:{epsg}", str(epsg)), measure=f"{seconds} s")


def proof():
    p = Proof(6, "the Importer loads vector files into PostGIS")
    reset_store()
    files = make(os.path.join(SCRATCH, "samples"))
    report = []
    for path, rows, epsg, geom in files:
        result = run_import(path)
        ok, why = judge(path, rows, epsg, geom, result)
        report.append({"file": os.path.basename(path), "ok": ok, "why": why, **result})
        p.case(f"{os.path.basename(path):26} -> {why}", ok, True,
               measure=f"{result.get('seconds', '-')} s, {result.get('states', result.get('error', ''))}")
    good = sum(r["ok"] for r in report)
    p.note(f"Importer: {good} of {len(report)} files loaded right")
    with open(os.path.join(SCRATCH, "importer.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    importer_holds = p.verdict()
    # For comparison, not part of the verdict on the Importer.
    baseline = Proof("6b", "the same data written directly into PostGIS (the decision of F0)")
    direct_baseline(baseline)
    baseline.verdict()
    return importer_holds


if __name__ == "__main__":
    main_of(proof)
