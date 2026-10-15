# SPDX-FileCopyrightText: 2026 Massimo Antonini
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
The loader of Geoportal (ADR-0019): GDAL in a sidecar loads the files uploaded to the catalog.

A job is a file to load, given by the catalog of Geoportal:

  POST /jobs      {"source": "<signed link to the upload>", "fileName": "...", "format": "geopackage",
                   "target": {"host", "port", "database", "user", "password", "schema", "table"},
                   "crs": "EPSG:n" | null,         declared by the person, for a file that states none
                   "formatCrs": "EPSG:n" | null,   defined by the format (CSV of latitude and longitude)
                   "layer": "name" | null}         the layer of a file with several
                  -> 202 {"id"}
  GET  /jobs/{id} -> {"state": "queued|running|done|failed", "progress", "code", "reason", "layers",
                      "layer", "crs", "source", "features", "raster", "extent"}
  GET  /jobs/{id}/result   the Cloud Optimized GeoTIFF of a raster, streamed
  DELETE /jobs/{id}        forgets the job and its files
  GET  /health

The file is streamed from the signed link to a working directory, never held in memory; vectors are
written by ogr2ogr straight into the table the catalog names (which it then checks and renames),
rasters converted by gdal_translate into COG, which the catalog fetches and stores in the file
service of Mosaikit. The credentials of the target come with each job and live only in its memory and
in the environment of its ogr2ogr: nothing is stored. The reference system is never guessed: the one
the file states, else the declared one, else the one of the format; otherwise the job fails with
crs-missing. KML and KMZ are read by LIBKML, with their extended data (ADR-0026); CZML is not a format
of GDAL and stays with the readers of the catalog. Reachable only on the internal network of the
installation.
"""
import json
import os
import re
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from osgeo import gdal, ogr, osr

gdal.UseExceptions()
ogr.UseExceptions()
osr.UseExceptions()

PORT = int(os.environ.get("LOADER_PORT", "8090"))
WORK = os.environ.get("LOADER_WORKDIR", "/tmp/loader")
WORKERS = int(os.environ.get("LOADER_WORKERS", "2"))
TTL = int(os.environ.get("LOADER_RESULT_TTL", "3600"))
CHUNK = 1 << 20

CSV_X = "lon,lng,long,longitude,longitudine,x,xcoord,coordx,easting,est"
CSV_Y = "lat,latitude,latitudine,y,ycoord,coordy,northing,nord"
CSV_GEOMETRY = "wkt,geom,geometry,geometria,the_geom,thegeom,shape,wkt_geom,geojson"
# A warning of GDAL about a geometry it could not read: the file is refused rather than loaded with
# features that lost their geometry.
GEOMETRY_WARNING = re.compile(r"coord|geometr|polygon|ring|linestring|point|wkt", re.IGNORECASE)
# Layers of GDAL that repeat what other layers hold: the points of routes and tracks of a GPX.
DERIVED = {"route_points", "track_points"}
# KML and KMZ are read by the LIBKML driver, which keeps the extended data (Data and SimpleData) as
# columns (ADR-0026). It also makes a column of each element of the time, the display and the style of
# a placemark, empty or at its default in nearly every file: those are not loaded, as the readers of the
# catalog do not load them. Its id, name and description stay.
LIBKML_DISPLAY = {"timestamp", "begin", "end", "altitudemode", "tessellate", "extrude", "visibility", "draworder", "icon"}

jobs = {}
lock = threading.Lock()
pool = ThreadPoolExecutor(max_workers=WORKERS)


class Refused(Exception):
    """A file that cannot be loaded, for what it contains: a code for the catalog and the reason."""

    def __init__(self, code, reason, **more):
        super().__init__(reason)
        self.code = code
        self.reason = reason
        self.more = more


def update(job_id, **values):
    with lock:
        if job_id in jobs:
            jobs[job_id].update(values)


def public(job):
    return {k: v for k, v in job.items() if not k.startswith("_")}


# Reference systems -----------------------------------------------------------------------------


def epsg(srs):
    """The EPSG code of a spatial reference: its authority, else the best match of PROJ, else None."""
    if srs is None:
        return None
    candidate = srs.Clone()
    try:
        candidate.AutoIdentifyEPSG()
    except RuntimeError:
        pass
    for node in (None, "PROJCS", "GEOGCS"):
        if candidate.GetAuthorityName(node) == "EPSG" and candidate.GetAuthorityCode(node):
            if node is None or (node == "PROJCS") == bool(candidate.IsProjected()):
                return int(candidate.GetAuthorityCode(node))
    try:
        for match, confidence in srs.FindMatches():
            if confidence >= 90 and match.GetAuthorityName(None) == "EPSG":
                return int(match.GetAuthorityCode(None))
    except RuntimeError:
        pass
    return None


def declared(text):
    if not text:
        return None
    m = re.fullmatch(r"(?:EPSG:)?(\d{4,6})", text.strip(), re.IGNORECASE)
    if not m:
        raise Refused("crs-unknown", f"{text} is not an EPSG code.")
    return int(m.group(1))


def choose_crs(srs, spec, what):
    """The system of the data: stated by the file, else declared, else defined by the format."""
    stated = epsg(srs) if srs is not None else None
    if stated:
        return stated, "file"
    if srs is not None and not spec.get("crs"):
        name = srs.GetName() or srs.ExportToWkt()[:120]
        raise Refused("crs-unknown", f"The reference system of the {what} is not one the loader recognizes: {name}. "
                      "Declare its EPSG code.")
    if spec.get("crs"):
        return declared(spec["crs"]), "declared"
    if spec.get("formatCrs"):
        return declared(spec["formatCrs"]), "format"
    raise Refused("crs-missing", f"The {what} states no reference system: declare its EPSG code, for example EPSG:32632.")


# Files -----------------------------------------------------------------------------------------


def download(job_id, spec, folder):
    name = os.path.basename(spec.get("fileName") or "upload") or "upload"
    path = os.path.join(folder, name)
    request = urllib.request.Request(spec["source"], headers={"User-Agent": "geoportal-loader"})
    size = 0
    # The catalog submits the job inside the request that stores the file: until that request ends the
    # file is not found, so a 404 is tried again for a while.
    for attempt in range(30):
        try:
            answer = urllib.request.urlopen(request, timeout=60)
            break
        except urllib.error.HTTPError as e:
            if e.code != 404 or attempt == 29:
                raise
            time.sleep(1)
    with answer, open(path, "wb") as out:
        total = int(answer.headers.get("Content-Length") or 0)
        while True:
            chunk = answer.read(CHUNK)
            if not chunk:
                break
            out.write(chunk)
            size += len(chunk)
            if total:
                update(job_id, progress=int(20 * size / total))
    return path


def vector_source(spec, path):
    """The name GDAL opens: zip archives through /vsizip/, the KML of a KMZ."""
    fmt = spec.get("format")
    if fmt == "shapefile":
        return "/vsizip/" + path
    if fmt == "kmz":
        with zipfile.ZipFile(path) as z:
            names = [n for n in z.namelist() if n.lower().endswith(".kml")]
        if not names:
            raise Refused("unreadable", "The KMZ archive holds no .kml file.")
        names.sort(key=lambda n: (n.lower() != "doc.kml", "/" in n, n))
        return f"/vsizip/{path}/{names[0]}"
    return path


def separator(path):
    with open(path, "rb") as f:
        first = f.readline().decode("utf-8", "replace")
    counts = {name: first.count(char) for name, char in
              (("COMMA", ","), ("SEMICOLON", ";"), ("TAB", "\t"), ("PIPE", "|"))}
    return max(counts, key=counts.get)


def drivers(spec):
    """The drivers that may open the file: LIBKML for KML and KMZ, any otherwise."""
    return ["LIBKML"] if spec.get("format") in ("kml", "kmz") else None


def selected(layer, spec):
    """The columns to load, when not all of them: those of a KML without its display elements."""
    if drivers(spec) is None:
        return None
    definition = layer.GetLayerDefn()
    names = [definition.GetFieldDefn(i).GetName() for i in range(definition.GetFieldCount())]
    return [n for n in names if n.lower() not in LIBKML_DISPLAY]


def open_options(spec, path):
    if spec.get("format") != "csv":
        return []
    return [f"X_POSSIBLE_NAMES={CSV_X}", f"Y_POSSIBLE_NAMES={CSV_Y}", f"GEOM_POSSIBLE_NAMES={CSV_GEOMETRY}",
            "KEEP_GEOM_COLUMNS=NO", "AUTODETECT_TYPE=YES", "EMPTY_STRING_AS_NULL=YES",
            f"SEPARATOR={separator(path)}"]


def layers_of(dataset, spec):
    found = []
    for i in range(dataset.GetLayerCount()):
        layer = dataset.GetLayerByIndex(i)
        count = layer.GetFeatureCount()
        if count == 0 or layer.GetName() in DERIVED or layer.GetGeomType() == ogr.wkbNone:
            continue
        found.append({"name": layer.GetName(), "features": count,
                      "geometryType": ogr.GeometryTypeToName(layer.GetGeomType())})
    return found


# Loading ---------------------------------------------------------------------------------------


def load_vector(job_id, spec, path):
    source = vector_source(spec, path)
    options = open_options(spec, path)
    warnings = []

    def handler(level, number, message):
        if level == gdal.CE_Warning and GEOMETRY_WARNING.search(message or ""):
            warnings.append(message)

    gdal.PushErrorHandler(handler)
    try:
        more = {"allowed_drivers": drivers(spec)} if drivers(spec) else {}
        dataset = gdal.OpenEx(source, gdal.OF_VECTOR | gdal.OF_READONLY, open_options=options, **more)
        layers = layers_of(dataset, spec)
    except RuntimeError as e:
        raise Refused("unreadable", f"GDAL cannot read the file as {spec.get('format')}: {e}")
    finally:
        gdal.PopErrorHandler()
    if warnings:
        raise Refused("geometry-invalid", f"A geometry of the file cannot be read: {warnings[0]}")
    if not layers:
        raise Refused("empty", "The file holds no feature with a geometry.")
    wanted = spec.get("layer")
    if wanted:
        chosen = next((l for l in layers if l["name"] == wanted), None)
        if chosen is None:
            raise Refused("several-layers", f"The file has no layer {wanted}.", layers=layers)
    elif len(layers) > 1:
        raise Refused("several-layers", f"The file holds {len(layers)} layers ("
                      + ", ".join(l["name"] for l in layers) + "): choose the one this dataset loads.", layers=layers)
    else:
        chosen = layers[0]
    layer = dataset.GetLayerByName(chosen["name"])
    code, source_of_crs = choose_crs(layer.GetSpatialRef(), spec, "file")
    fields = selected(layer, spec)
    dataset = None

    target = spec["target"]
    for key in ("schema", "table"):
        if not re.fullmatch(r"[a-z_][a-z0-9_]{0,62}", target.get(key, "")):
            raise ValueError(f"invalid {key}")
    pg = f"PG:host={target['host']} port={target['port']} dbname={target['database']} user={target['user']}"
    command = ["ogr2ogr", "-f", "PostgreSQL", pg, source, chosen["name"],
               "-nln", f"{target['schema']}.{target['table']}",
               "-lco", "GEOMETRY_NAME=geom", "-lco", "FID=fid", "-lco", "SPATIAL_INDEX=GIST",
               "-lco", "LAUNDER=YES", "-lco", "PRECISION=NO", "-lco", "OVERWRITE=YES",
               "-a_srs", f"EPSG:{code}", "-gt", "65536", "-progress", "--config", "PG_USE_COPY", "YES"]
    for option in options:
        command[len(command):] = ["-oo", option]
    if spec.get("format") == "shapefile":
        command[len(command):] = ["-nlt", "PROMOTE_TO_MULTI"]
    for driver in drivers(spec) or []:
        command[len(command):] = ["-if", driver]
    if fields:
        command[len(command):] = ["-select", ",".join('"' + f.replace('"', '') + '"' for f in fields)]
    env = dict(os.environ, PGPASSWORD=target["password"], PGCONNECT_TIMEOUT="10")
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
    errors = []

    def read_errors():
        for line in process.stderr:
            errors.append(line.decode("utf-8", "replace").strip())

    reader = threading.Thread(target=read_errors, daemon=True)
    reader.start()
    buffer = ""
    while True:
        char = process.stdout.read(1)
        if not char:
            break
        buffer += char.decode("ascii", "replace")
        m = re.findall(r"(\d+)\.\.\.", buffer)
        if m:
            update(job_id, progress=20 + int(int(m[-1]) * 0.8))
            buffer = buffer[-16:]
    process.wait()
    reader.join(timeout=5)
    failures = [e for e in errors if e.startswith("ERROR")]
    warned = [e for e in errors if e.startswith("Warning") and GEOMETRY_WARNING.search(e)]
    if warned and not failures:
        raise Refused("geometry-invalid", f"A geometry of the file cannot be read: {warned[0]}")
    if process.returncode != 0 or failures:
        reason = (failures or errors or ["ogr2ogr failed"])[0]
        if "connect" in reason.lower() or "password" in reason.lower():
            raise RuntimeError(reason)  # the database, not the file
        raise Refused("geometry-invalid" if "geometr" in reason.lower() else "unreadable", reason)
    return {"layer": chosen["name"], "layers": layers, "crs": f"EPSG:{code}", "source": source_of_crs,
            "features": chosen["features"]}


def load_raster(job_id, spec, path, folder):
    try:
        info = gdal.Info(path, format="json")
    except RuntimeError as e:
        raise Refused("unreadable", f"GDAL cannot read the file as an image: {e}")
    if "geoTransform" not in info:
        raise Refused("unreadable", "The image has no georeferencing: it says nowhere where its pixels are.")
    dataset = gdal.Open(path)
    srs = dataset.GetSpatialRef()
    code, source_of_crs = choose_crs(srs, spec, "image")
    srs_used = osr.SpatialReference()
    srs_used.ImportFromEPSG(code)
    dataset = None
    output = os.path.join(folder, "result.cog.tif")

    def progress(done, message, data):
        update(job_id, progress=20 + int(done * 80))
        return 1

    try:
        gdal.Translate(output, path, format="COG", outputSRS=f"EPSG:{code}",
                       creationOptions=["COMPRESS=DEFLATE", "BIGTIFF=IF_SAFER", "OVERVIEWS=AUTO"], callback=progress)
        out = gdal.Info(output, format="json")
    except RuntimeError as e:
        raise Refused("unreadable", f"The image cannot be converted into a Cloud Optimized GeoTIFF: {e}")
    corners = out["cornerCoordinates"]
    xs = [c[0] for c in corners.values()]
    ys = [c[1] for c in corners.values()]
    native = [min(xs), min(ys), max(xs), max(ys)]
    if srs_used.IsGeographic() and not (-180 <= native[0] <= native[2] <= 180 and -90 <= native[1] <= native[3] <= 90):
        raise Refused("crs-mismatch", f"The image does not lie within degrees of EPSG:{code}: it is in a projected "
                      "system it does not state. Declare its EPSG code.")
    wgs84 = None
    polygon = out.get("wgs84Extent", {}).get("coordinates")
    if polygon:
        lon = [p[0] for p in polygon[0]]
        lat = [p[1] for p in polygon[0]]
        box = [min(lon), min(lat), max(lon), max(lat)]
        if -180 <= box[0] <= box[2] <= 180 and -90 <= box[1] <= box[3] <= 90:
            wgs84 = box
    return {"crs": f"EPSG:{code}", "source": source_of_crs, "raster": {
        "width": out["size"][0], "height": out["size"][1], "bands": len(out.get("bands", [])),
        "size": os.path.getsize(output)}, "extent": {"native": native, "wgs84": wgs84},
        "features": out["size"][0] * out["size"][1], "_result": output}


def run(job_id, spec):
    folder = os.path.join(WORK, job_id)
    os.makedirs(folder, exist_ok=True)
    update(job_id, state="running", progress=0)
    try:
        path = download(job_id, spec, folder)
        if spec.get("format") == "geotiff":
            result = load_raster(job_id, spec, path, folder)
        else:
            result = load_vector(job_id, spec, path)
        os.remove(path)
        update(job_id, state="done", progress=100, **result)
    except Refused as e:
        update(job_id, state="failed", code=e.code, reason=e.reason, **e.more)
    except Exception as e:  # the source, the database, the disk: not the file
        update(job_id, state="failed", code="loader-error", reason=f"{type(e).__name__}: {e}")
    finally:
        spec.get("target", {}).pop("password", None)
        update(job_id, finished=time.time())


def forget(job_id):
    with lock:
        jobs.pop(job_id, None)
    shutil.rmtree(os.path.join(WORK, job_id), ignore_errors=True)


def sweep():
    while True:
        time.sleep(60)
        now = time.time()
        with lock:
            old = [k for k, j in jobs.items() if j.get("finished") and now - j["finished"] > TTL]
        for job_id in old:
            forget(job_id)


# HTTP ------------------------------------------------------------------------------------------


class Handler(BaseHTTPRequestHandler):
    server_version = "geoportal-loader"

    def log_message(self, fmt, *args):  # no query strings (signed links) in the log
        print(f"{self.command} {self.path.split('?')[0]} {args[1] if len(args) > 1 else ''}", flush=True)

    def answer(self, status, body=None):
        data = json.dumps(body).encode() if body is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def job(self):
        m = re.fullmatch(r"/jobs/([0-9a-f-]{36})(/result)?", self.path)
        if not m:
            return None, None
        with lock:
            return jobs.get(m.group(1)), m.group(2)

    def do_GET(self):
        if self.path == "/health":
            return self.answer(200, {"status": "up", "gdal": gdal.__version__})
        job, result = self.job()
        if job is None:
            return self.answer(404, {"detail": "no such job"})
        if not result:
            return self.answer(200, public(job))
        path = job.get("_result")
        if job.get("state") != "done" or not path or not os.path.exists(path):
            return self.answer(404, {"detail": "no result"})
        self.send_response(200)
        self.send_header("Content-Type", "image/tiff")
        self.send_header("Content-Length", str(os.path.getsize(path)))
        self.end_headers()
        with open(path, "rb") as f:
            shutil.copyfileobj(f, self.wfile, CHUNK)

    def do_POST(self):
        if self.path != "/jobs":
            return self.answer(404, {"detail": "not found"})
        try:
            spec = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)))
            if not spec.get("source") or (spec.get("format") != "geotiff" and not spec.get("target")):
                raise ValueError("source and target are required")
        except (ValueError, TypeError) as e:
            return self.answer(400, {"detail": str(e)})
        job_id = str(uuid.uuid4())
        with lock:
            jobs[job_id] = {"id": job_id, "state": "queued", "progress": 0, "format": spec.get("format")}
        pool.submit(run, job_id, spec)
        self.answer(202, {"id": job_id})

    def do_DELETE(self):
        job, _ = self.job()
        if job is None:
            return self.answer(404, {"detail": "no such job"})
        forget(job["id"])
        self.answer(204)


if __name__ == "__main__":
    os.makedirs(WORK, exist_ok=True)
    threading.Thread(target=sweep, daemon=True).start()
    print(f"geoportal-loader on :{PORT}, GDAL {gdal.__version__}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
