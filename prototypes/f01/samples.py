# SPDX-FileCopyrightText: 2026 Massimo Antonini
"""GP-327 F01: small, realistic vector files whose truth is known, for the Importer proof.

Twelve monitoring stations in Valverde, an invented town (points) and six districts (polygons), with an integer, a text, a
decimal, a date and a boolean column, written in the formats people upload: GeoJSON (RFC 7946 and
the old form with a projected "crs"), zipped Shapefile, GeoPackage, CSV with coordinate columns and
CSV with WKT. Standard library only: the formats are written by hand, which also documents them.

    python samples.py <directory>
Returns, from python, the list of files with what each must give: rows, columns, EPSG code.
"""
import datetime
import io
import json
import math
import os
import sqlite3
import struct
import sys
import zipfile

COLUMNS = {"codice": "int", "nome": "string", "quota": "double", "attivata": "date", "attiva": "boolean"}
NAMES = ["Centro", "Stazione", "Borgo", "Prato", "Fornaci", "Colle", "Mulini", "Porta Sud",
         "Giardini", "Fiera", "Canale", "Mercato"]
STATIONS = [{"codice": 101 + i, "nome": n, "quota": round(20.5 + i * 3.25, 2),
             "attivata": datetime.date(2019, 1 + i, 1 + i).isoformat(), "attiva": i % 3 != 0,
             "lon": round(12.355 + (i % 4) * 0.02, 6), "lat": round(42.075 + (i // 4) * 0.02, 6)}
            for i, n in enumerate(NAMES)]
DISTRICTS = [{"codice": 1 + i, "nome": f"Zona {i + 1}", "quota": 30.0 + i, "attivata": "2020-06-01",
              "attiva": True, "lon": 12.36 + (i % 3) * 0.03, "lat": 42.08 + (i // 3) * 0.03}
             for i in range(6)]

WKT_32633 = ('PROJCS["WGS 84 / UTM zone 33N",GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,'
             '298.257223563]],PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]],PROJECTION["Transverse_'
             'Mercator"],PARAMETER["latitude_of_origin",0],PARAMETER["central_meridian",15],PARAMETER["scale_factor"'
             ',0.9996],PARAMETER["false_easting",500000],PARAMETER["false_northing",0],UNIT["metre",1]]')
WKT_4326 = ('GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],PRIMEM["Greenwich",0],'
            'UNIT["degree",0.0174532925199433]]')


def utm33(lon, lat):
    """WGS84 -> UTM zone 33N (EPSG:32633), the usual series to the centimetre."""
    a, f, k0 = 6378137.0, 1 / 298.257223563, 0.9996
    e2 = f * (2 - f)
    ep2 = e2 / (1 - e2)
    phi, lam, lam0 = math.radians(lat), math.radians(lon), math.radians(15)
    n = a / math.sqrt(1 - e2 * math.sin(phi) ** 2)
    t, c = math.tan(phi) ** 2, ep2 * math.cos(phi) ** 2
    aa = math.cos(phi) * (lam - lam0)
    m = a * ((1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256) * phi
             - (3 * e2 / 8 + 3 * e2 ** 2 / 32 + 45 * e2 ** 3 / 1024) * math.sin(2 * phi)
             + (15 * e2 ** 2 / 256 + 45 * e2 ** 3 / 1024) * math.sin(4 * phi)
             - (35 * e2 ** 3 / 3072) * math.sin(6 * phi))
    x = k0 * n * (aa + (1 - t + c) * aa ** 3 / 6 + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * aa ** 5 / 120) + 500000
    y = k0 * (m + n * math.tan(phi) * (aa * aa / 2 + (5 - t + 9 * c + 4 * c * c) * aa ** 4 / 24
                                       + (61 - 58 * t + t * t + 600 * c - 330 * ep2) * aa ** 6 / 720))
    return round(x, 2), round(y, 2)


def square(lon, lat, half=0.005):
    return [(lon - half, lat - half), (lon + half, lat - half), (lon + half, lat + half), (lon - half, lat + half),
            (lon - half, lat - half)]


def props(s):
    return {k: s[k] for k in COLUMNS}


# --- GeoJSON --------------------------------------------------------------------------------------
def geojson(path, projected):
    feats = []
    for s in STATIONS:
        xy = utm33(s["lon"], s["lat"]) if projected else (s["lon"], s["lat"])
        feats.append({"type": "Feature", "properties": props(s), "geometry": {"type": "Point", "coordinates": xy}})
    doc = {"type": "FeatureCollection", "features": feats}
    if projected:
        doc["crs"] = {"type": "name", "properties": {"name": "urn:ogc:def:crs:EPSG::32633"}}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False)


# --- Shapefile (points) ---------------------------------------------------------------------------
def shapefile_zip(path, projected):
    pts = [utm33(s["lon"], s["lat"]) if projected else (s["lon"], s["lat"]) for s in STATIONS]
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    bbox = (min(xs), min(ys), max(xs), max(ys))

    def header(length_words):
        return (struct.pack(">7i", 9994, 0, 0, 0, 0, 0, length_words) + struct.pack("<2i", 1000, 1)
                + struct.pack("<4d", *bbox) + struct.pack("<4d", 0, 0, 0, 0))

    shp, shx, offset = io.BytesIO(), io.BytesIO(), 50
    records = b""
    index = b""
    for i, (x, y) in enumerate(pts):
        content = struct.pack("<i2d", 1, x, y)
        records += struct.pack(">2i", i + 1, len(content) // 2) + content
        index += struct.pack(">2i", offset, len(content) // 2)
        offset += (8 + len(content)) // 2
    shp.write(header(50 + len(records) // 2) + records)
    shx.write(header(50 + len(index) // 2) + index)
    # dBase III: N for numbers, C for text, D for dates, L for booleans.
    fields = [("CODICE", "N", 10, 0), ("NOME", "C", 40, 0), ("QUOTA", "N", 12, 2), ("ATTIVATA", "D", 8, 0),
              ("ATTIVA", "L", 1, 0)]
    record_len = 1 + sum(f[2] for f in fields)
    dbf = io.BytesIO()
    dbf.write(struct.pack("<4BIHH20x", 3, 126, 10, 13, len(STATIONS), 32 + 32 * len(fields) + 1, record_len))
    for name, kind, size, dec in fields:
        dbf.write(struct.pack("<11sc4xBB14x", name.encode(), kind.encode(), size, dec))
    dbf.write(b"\r")
    for s in STATIONS:
        row = b" " + str(s["codice"]).rjust(10).encode() + s["nome"].ljust(40).encode("latin-1") \
              + f"{s['quota']:.2f}".rjust(12).encode() + s["attivata"].replace("-", "").encode() \
              + (b"T" if s["attiva"] else b"F")
        dbf.write(row)
    dbf.write(b"\x1a")
    base = os.path.splitext(os.path.basename(path))[0].replace("_shp", "")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(f"{base}.shp", shp.getvalue())
        z.writestr(f"{base}.shx", shx.getvalue())
        z.writestr(f"{base}.dbf", dbf.getvalue())
        z.writestr(f"{base}.prj", WKT_32633 if projected else WKT_4326)
        z.writestr(f"{base}.cpg", "UTF-8")


# --- GeoPackage ------------------------------------------------------------------------------------
def gpkg_geometry(srs_id, wkb):
    return b"GP" + bytes([0, 1]) + struct.pack("<i", srs_id) + wkb   # no envelope, little endian


def wkb_point(x, y):
    return struct.pack("<BIdd", 1, 1, x, y)


def wkb_polygon(ring):
    return struct.pack("<BII", 1, 3, 1) + struct.pack("<I", len(ring)) + b"".join(struct.pack("<dd", *p) for p in ring)


def geopackage(path, table, polygons, srs):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    db.execute("PRAGMA application_id = 1196444487")   # 'GPKG'
    db.execute("PRAGMA user_version = 10300")
    db.executescript("""
        create table gpkg_spatial_ref_sys (srs_name text not null, srs_id integer primary key, organization text not null,
            organization_coordsys_id integer not null, definition text not null, description text);
        create table gpkg_contents (table_name text primary key, data_type text not null, identifier text unique,
            description text default '', last_change datetime not null default (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
            min_x double, min_y double, max_x double, max_y double, srs_id integer);
        create table gpkg_geometry_columns (table_name text not null, column_name text not null,
            geometry_type_name text not null, srs_id integer not null, z tinyint not null, m tinyint not null,
            primary key (table_name, column_name));""")
    db.executemany("insert into gpkg_spatial_ref_sys values (?,?,?,?,?,?)", [
        ("Undefined cartesian SRS", -1, "NONE", -1, "undefined", None),
        ("Undefined geographic SRS", 0, "NONE", 0, "undefined", None),
        ("WGS 84 geodetic", 4326, "EPSG", 4326, WKT_4326, None),
        ("WGS 84 / UTM zone 33N", 32633, "EPSG", 32633, WKT_32633, None)])
    kind = "POLYGON" if polygons else "POINT"
    db.execute(f"create table {table} (fid integer primary key autoincrement, geom {kind}, codice integer, "
               f"nome text, quota double, attivata date, attiva boolean)")
    source = DISTRICTS if polygons else STATIONS
    project = (lambda lon, lat: utm33(lon, lat)) if srs == 32633 else (lambda lon, lat: (lon, lat))
    coords = []
    for s in source:
        if polygons:
            ring = [project(*p) for p in square(s["lon"], s["lat"])]
            coords += ring
            geom = gpkg_geometry(srs, wkb_polygon(ring))
        else:
            xy = project(s["lon"], s["lat"])
            coords.append(xy)
            geom = gpkg_geometry(srs, wkb_point(*xy))
        db.execute(f"insert into {table} (geom, codice, nome, quota, attivata, attiva) values (?,?,?,?,?,?)",
                   (geom, s["codice"], s["nome"], s["quota"], s["attivata"], 1 if s["attiva"] else 0))
    xs, ys = [c[0] for c in coords], [c[1] for c in coords]
    db.execute("insert into gpkg_contents (table_name, data_type, identifier, min_x, min_y, max_x, max_y, srs_id) "
               "values (?, 'features', ?, ?, ?, ?, ?, ?)", (table, table, min(xs), min(ys), max(xs), max(ys), srs))
    db.execute("insert into gpkg_geometry_columns values (?, 'geom', ?, ?, 0, 0)", (table, kind, srs))
    db.commit()
    db.close()


# --- CSV ---------------------------------------------------------------------------------------------
def csv_latlon(path):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write("codice,nome,quota,attivata,attiva,lat,lon\n")
        for s in STATIONS:
            f.write(f"{s['codice']},{s['nome']},{s['quota']},{s['attivata']},{str(s['attiva']).lower()},"
                    f"{s['lat']},{s['lon']}\n")


def csv_wkt(path):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write("codice,nome,quota,attivata,attiva,wkt\n")
        for s in STATIONS:
            f.write(f"{s['codice']},{s['nome']},{s['quota']},{s['attivata']},{str(s['attiva']).lower()},"
                    f"\"POINT ({s['lon']} {s['lat']})\"\n")


def make(directory):
    """Writes the files; returns (file, rows, epsg, geometry) for each."""
    os.makedirs(directory, exist_ok=True)
    out = []

    def add(name, writer, rows, epsg, geom):
        path = os.path.join(directory, name)
        writer(path)
        out.append((path, rows, epsg, geom))

    add("stazioni_4326.geojson", lambda p: geojson(p, False), 12, 4326, "Point")
    add("stazioni_32633.geojson", lambda p: geojson(p, True), 12, 32633, "Point")
    add("stazioni_4326_shp.zip", lambda p: shapefile_zip(p, False), 12, 4326, "Point")
    add("stazioni_32633_shp.zip", lambda p: shapefile_zip(p, True), 12, 32633, "Point")
    add("stazioni_4326.gpkg", lambda p: geopackage(p, "stazioni_gpkg", False, 4326), 12, 4326, "Point")
    add("zone_32633.gpkg", lambda p: geopackage(p, "zone_gpkg", True, 32633), 6, 32633, "Polygon")
    add("stazioni_latlon.csv", csv_latlon, 12, 4326, "Point")
    add("stazioni_wkt.csv", csv_wkt, 12, 4326, "Point")
    return out


if __name__ == "__main__":
    for row in make(sys.argv[1] if len(sys.argv) > 1 else "samples"):
        print(*row)
