# SPDX-FileCopyrightText: 2026 Massimo Antonini
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Writes the sample datasets of the catalog and the fixtures of its loading tests.

Every place, name and value is invented: the towns of Valverde, Monteluce, Borgo Alto and the others
of the Regione Esempio do not exist, and the coordinates are only plausible ones, so that the data look
right on a map. Nothing is downloaded and nothing comes from a third party, so the files carry no
licence of anyone else. The output is deterministic: run it twice and the files are the same byte for
byte.

    python extensions/ext-catalog-geodata/src/samples/generate.py

Standard library only (Python 3.10 or later). See README.md next to this file for what each file
tests.
"""

import io
import json
import math
import os
import random
import sqlite3
import struct
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLES = os.path.join(HERE, "..", "main", "resources", "samples")
FIXTURES = os.path.join(HERE, "..", "test", "resources", "load")

# A fixed date for the entries of the archives, so that they do not change at every run.
ZIP_DATE = (2026, 10, 14, 12, 0, 0)

# ---------------------------------------------------------------------------------------------------
# The world of the samples
# ---------------------------------------------------------------------------------------------------

# Five landmarks of five invented towns: id, name, town, category, year, longitude, latitude.
# The tests look for the first two by name and expect them within a metre of these coordinates.
POIS = [
    (1, "Torre Civica", "Valverde", "monumento", 92, 12.381245, 42.105372),
    (2, "Cattedrale di Monteluce", "Monteluce", "chiesa", 1386, 10.874512, 44.612338),
    (3, "Castello di Borgo Alto", "Borgo Alto", "castello", 1279, 15.103327, 40.917644),
    (4, "Ponte dei Mulini", "Pietrachiara", "ponte", 1345, 11.948761, 43.302185),
    (5, "Piazza del Mercato", "Lagofondo", "piazza", 1200, 12.913374, 45.802519),
]

# Three railway stations of Valverde (longitude, latitude), written in UTM zone 33N by the fixtures.
STATIONS = [
    ("Valverde Centrale", 12.3812, 42.1054, 10),
    ("Valverde Nord", 12.4107, 42.1149, 11),
    ("Valverde Sud", 12.3649, 42.0767, 12),
]

PLACES = [
    "Valverde", "Monteluce", "Borgo Alto", "Pietrachiara", "Lagofondo", "Roccaserena", "Castelfiore",
    "Poggio Lieto", "Colle Ombroso", "Selvascura", "Acquachiara", "Pratolungo", "Serralta",
    "Fontebella", "Vallerosa", "Campolargo", "Boscoverde", "Montefosco", "Rivabianca",
]

ROMAN = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV"]


# ---------------------------------------------------------------------------------------------------
# UTM zone 33N on WGS84 (Krueger series, Karney 2011): far below a millimetre in the zone
# ---------------------------------------------------------------------------------------------------

def utm33(lon, lat):
    a = 6378137.0
    f = 1 / 298.257223563
    k0 = 0.9996
    n = f / (2 - f)
    big_a = a / (1 + n) * (1 + n ** 2 / 4 + n ** 4 / 64 + n ** 6 / 256)
    alpha = [
        n / 2 - 2 / 3 * n ** 2 + 5 / 16 * n ** 3 + 41 / 180 * n ** 4 - 127 / 288 * n ** 5 + 7891 / 37800 * n ** 6,
        13 / 48 * n ** 2 - 3 / 5 * n ** 3 + 557 / 1440 * n ** 4 + 281 / 630 * n ** 5 - 1983433 / 1935360 * n ** 6,
        61 / 240 * n ** 3 - 103 / 140 * n ** 4 + 15061 / 26880 * n ** 5 + 167603 / 181440 * n ** 6,
        49561 / 161280 * n ** 4 - 179 / 168 * n ** 5 + 6601661 / 7257600 * n ** 6,
        34729 / 80640 * n ** 5 - 3418889 / 1995840 * n ** 6,
        212378941 / 319334400 * n ** 6,
    ]
    e = math.sqrt(f * (2 - f))
    phi = math.radians(lat)
    lam = math.radians(lon - 15.0)
    t = math.sinh(math.atanh(math.sin(phi)) - e * math.atanh(e * math.sin(phi)))
    xi1 = math.atan2(t, math.cos(lam))
    eta1 = math.atanh(math.sin(lam) / math.sqrt(1 + t * t))
    xi = xi1 + sum(alpha[j] * math.sin(2 * (j + 1) * xi1) * math.cosh(2 * (j + 1) * eta1) for j in range(6))
    eta = eta1 + sum(alpha[j] * math.cos(2 * (j + 1) * xi1) * math.sinh(2 * (j + 1) * eta1) for j in range(6))
    return round(500000 + k0 * big_a * eta, 3), round(k0 * big_a * xi, 3)


SHP_UTM33 = ('PROJCS["WGS_1984_UTM_Zone_33N",GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",'
              '6378137.0,298.257223563]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],'
              'PROJECTION["Transverse_Mercator"],PARAMETER["False_Easting",500000.0],PARAMETER["False_Northing",0.0],'
              'PARAMETER["Central_Meridian",15.0],PARAMETER["Scale_Factor",0.9996],PARAMETER["Latitude_Of_Origin",0.0],'
              'UNIT["Meter",1.0]]')
SHP_WGS84 = ('GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]],'
              'PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]]')
OGC_WGS84 = ('GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563,AUTHORITY["EPSG","7030"]],'
             'AUTHORITY["EPSG","6326"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],UNIT["degree",0.0174532925199433,'
             'AUTHORITY["EPSG","9122"]],AXIS["Latitude",NORTH],AXIS["Longitude",EAST],AUTHORITY["EPSG","4326"]]')


# ---------------------------------------------------------------------------------------------------
# Writing helpers
# ---------------------------------------------------------------------------------------------------

def write(folder, name, data):
    os.makedirs(folder, exist_ok=True)
    if isinstance(data, str):
        data = data.encode("utf-8")
    with open(os.path.join(folder, name), "wb") as out:
        out.write(data)


def crlf(lines):
    return "".join(line + "\r\n" for line in lines)


def num(value):
    """A coordinate as it is written: no trailing zeros, no exponent."""
    text = f"{value:.6f}".rstrip("0").rstrip(".")
    return text if text != "-0" else "0"


def zipped(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries:
            info = zipfile.ZipInfo(name, ZIP_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, data)
    return buffer.getvalue()


def feature_collection(features, **members):
    """GeoJSON with one feature per line, as GDAL writes it, and the members in front."""
    head = ",\n".join(f'"{k}": {json.dumps(v, ensure_ascii=False)}' for k, v in members.items())
    body = ",\n".join("    " + json.dumps(f, ensure_ascii=False) for f in features)
    return "{\n" + ("  " + head.replace("\n", "\n  ") + ",\n" if head else "") + '  "features": [\n' + body + "\n  ]\n}\n"


def feature(properties, geometry):
    return {"type": "Feature", "properties": properties, "geometry": geometry}


def r5(value):
    return round(value, 5)


# ---------------------------------------------------------------------------------------------------
# The five landmarks in every vector format
# ---------------------------------------------------------------------------------------------------

def poi_geojson():
    features = ",\n".join(
        f'    {{ "type": "Feature", "properties": {{ "id": {i}, "nome": "{n}", "citta": "{c}", "categoria": "{k}", '
        f'"anno": {y} }}, "geometry": {{ "type": "Point", "coordinates": [{x:.6f}, {la:.6f}] }} }}'
        for i, n, c, k, y, x, la in POIS)
    return ('{\n  "type": "FeatureCollection",\n  "name": "poi-esempio",\n'
            '  "crs": { "type": "name", "properties": { "name": "urn:ogc:def:crs:OGC:1.3:CRS84" } },\n'
            '  "features": [\n' + features + "\n  ]\n}\n")


def poi_csv():
    return crlf(["id,nome,citta,categoria,anno,lat,lon"]
                + [f"{i},{n},{c},{k},{y},{la:.6f},{x:.6f}" for i, n, c, k, y, x, la in POIS])


def bounds():
    xs = [p[5] for p in POIS]
    ys = [p[6] for p in POIS]
    return min(xs), min(ys), max(xs), max(ys)


def poi_gml():
    """GML 3.2 with the URN of EPSG:4326: latitude first."""
    minx, miny, maxx, maxy = bounds()
    out = ['<?xml version="1.0" encoding="utf-8" ?>',
           '<ogr:FeatureCollection',
           '     gml:id="aFeatureCollection"',
           '     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"',
           '     xsi:schemaLocation="http://ogr.maptools.org/ poi-esempio.xsd"',
           '     xmlns:ogr="http://ogr.maptools.org/"',
           '     xmlns:gml="http://www.opengis.net/gml/3.2">',
           f'  <gml:boundedBy><gml:Envelope srsName="urn:ogc:def:crs:EPSG::4326"><gml:lowerCorner>{num(miny)} {num(minx)}'
           f'</gml:lowerCorner><gml:upperCorner>{num(maxy)} {num(maxx)}</gml:upperCorner></gml:Envelope></gml:boundedBy>']
    for n, (i, name, town, kind, year, x, y) in enumerate(POIS):
        pos = f"{num(y)} {num(x)}"
        out += ['  <ogr:featureMember>',
                f'    <ogr:poi_esempio gml:id="poi_esempio.{n}">',
                f'      <gml:boundedBy><gml:Envelope srsName="urn:ogc:def:crs:EPSG::4326"><gml:lowerCorner>{pos}</gml:lowerCorner>'
                f'<gml:upperCorner>{pos}</gml:upperCorner></gml:Envelope></gml:boundedBy>',
                f'      <ogr:geometryProperty><gml:Point srsName="urn:ogc:def:crs:EPSG::4326" gml:id="poi_esempio.geom.{n}">'
                f'<gml:pos>{pos}</gml:pos></gml:Point></ogr:geometryProperty>',
                f'      <ogr:id>{i}</ogr:id>',
                f'      <ogr:nome>{name}</ogr:nome>',
                f'      <ogr:citta>{town}</ogr:citta>',
                f'      <ogr:categoria>{kind}</ogr:categoria>',
                f'      <ogr:anno>{year}</ogr:anno>',
                '    </ogr:poi_esempio>',
                '  </ogr:featureMember>']
    out.append('</ogr:FeatureCollection>')
    return "\n".join(out) + "\n"


def poi_kml():
    out = ['<?xml version="1.0" encoding="utf-8" ?>',
           '<kml xmlns="http://www.opengis.net/kml/2.2">',
           '<Document id="root_doc">',
           '<Schema name="poi_esempio" id="poi_esempio">',
           '\t<SimpleField name="id" type="int"></SimpleField>',
           '\t<SimpleField name="nome" type="string"></SimpleField>',
           '\t<SimpleField name="citta" type="string"></SimpleField>',
           '\t<SimpleField name="categoria" type="string"></SimpleField>',
           '\t<SimpleField name="anno" type="int"></SimpleField>',
           '</Schema>',
           '<Folder><name>poi_esempio</name>']
    for i, name, town, kind, year, x, y in POIS:
        out += ['  <Placemark>',
                '\t<ExtendedData><SchemaData schemaUrl="#poi_esempio">',
                f'\t\t<SimpleData name="id">{i}</SimpleData>',
                f'\t\t<SimpleData name="nome">{name}</SimpleData>',
                f'\t\t<SimpleData name="citta">{town}</SimpleData>',
                f'\t\t<SimpleData name="categoria">{kind}</SimpleData>',
                f'\t\t<SimpleData name="anno">{year}</SimpleData>',
                '\t</SchemaData></ExtendedData>',
                f'      <Point><coordinates>{num(x)},{num(y)}</coordinates></Point>',
                '  </Placemark>']
    out += ['</Folder>', '</Document></kml>']
    return "\n".join(out) + "\n"


def poi_gpx():
    minx, miny, maxx, maxy = bounds()
    out = ['<?xml version="1.0"?>',
           '<gpx version="1.1" creator="Geoportal samples" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
           'xmlns:ogr="http://osgeo.org/gdal" xmlns="http://www.topografix.com/GPX/1/1" '
           'xsi:schemaLocation="http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd">',
           '<metadata>',
           f'<bounds minlat="{miny:.15f}" minlon="{minx:.15f}" maxlat="{maxy:.15f}" maxlon="{maxx:.15f}"/>',
           '</metadata>']
    for i, name, town, kind, year, x, y in POIS:
        out += [f'<wpt lat="{num(y)}" lon="{num(x)}">',
                f'  <name>{name}</name>',
                '  <extensions>',
                f'    <ogr:citta>{town}</ogr:citta>',
                f'    <ogr:categoria>{kind}</ogr:categoria>',
                f'    <ogr:anno>{year}</ogr:anno>',
                '  </extensions>',
                '</wpt>']
    out.append('</gpx>')
    return "\n".join(out) + "\n"


def poi_czml():
    packets = ['  {\n    "id": "document",\n    "name": "poi-esempio",\n    "version": "1.0"\n  }']
    for i, name, town, kind, year, x, y in POIS:
        when = f"{year} d.C." if year < 1000 else str(year)
        packets.append(
            '  {\n'
            f'    "id": "poi-{i}",\n'
            f'    "name": "{name}",\n'
            f'    "description": "{town} — {kind} ({when})",\n'
            f'    "position": {{ "cartographicDegrees": [{x:.6f}, {y:.6f}, 0] }},\n'
            '    "point": { "color": { "rgba": [0, 229, 216, 255] }, "pixelSize": 12, "outlineColor": { "rgba": [255, 255, 255, 255] }, "outlineWidth": 2 },\n'
            f'    "label": {{ "text": "{name}", "font": "12pt sans-serif", "fillColor": {{ "rgba": [255, 255, 255, 255] }}, '
            '"pixelOffset": { "cartesian2": [0, -20] }, "verticalOrigin": "BOTTOM" }\n'
            '  }')
    return "[\n" + ",\n".join(packets) + "\n]\n"


# --- Shapefile -------------------------------------------------------------------------------------

def shapefile_points(points, fields, records, prj, base, cpg=None):
    """A zip of a point Shapefile. fields: (name, 'C' or 'N', length); records: tuples of values."""
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    box = struct.pack("<4d", min(xs), min(ys), max(xs), max(ys))

    def header(length_words):
        return (struct.pack(">7i", 9994, 0, 0, 0, 0, 0, length_words) + struct.pack("<2i", 1000, 1) + box
                + struct.pack("<4d", 0, 0, 0, 0))

    shp_records = b""
    shx_records = b""
    offset = 50
    for number, (x, y) in enumerate(points, 1):
        content = struct.pack("<i2d", 1, x, y)
        shp_records += struct.pack(">2i", number, len(content) // 2) + content
        shx_records += struct.pack(">2i", offset, len(content) // 2)
        offset += 4 + len(content) // 2
    shp = header(50 + len(shp_records) // 2) + shp_records
    shx = header(50 + len(shx_records) // 2) + shx_records

    record_size = 1 + sum(f[2] for f in fields)
    header_size = 32 + 32 * len(fields) + 1
    dbf = struct.pack("<4BIHH20x", 3, 126, 10, 14, len(records), header_size, record_size)
    dbf = dbf[:29] + bytes([0x57 if cpg is None else 0]) + dbf[30:]
    for name, kind, length in fields:
        dbf += name.encode("ascii").ljust(11, b"\0") + kind.encode("ascii") + b"\0" * 4 + bytes([length, 0]) + b"\0" * 14
    dbf += b"\r"
    for record in records:
        dbf += b" "
        for (name, kind, length), value in zip(fields, record):
            text = str(value).encode("utf-8")
            dbf += text.ljust(length) if kind == "C" else text.rjust(length)
    dbf += b"\x1a"

    entries = [(base + ".shp", shp), (base + ".shx", shx), (base + ".dbf", dbf)]
    if prj is not None:
        entries.append((base + ".prj", prj))
    if cpg is not None:
        entries.append((base + ".cpg", cpg))
    return zipped(entries)


# --- GeoPackage ------------------------------------------------------------------------------------

GPKG_SRS = """CREATE TABLE gpkg_spatial_ref_sys (srs_name TEXT NOT NULL, srs_id INTEGER NOT NULL PRIMARY KEY,
 organization TEXT NOT NULL, organization_coordsys_id INTEGER NOT NULL, definition TEXT NOT NULL, description TEXT)"""
GPKG_CONTENTS = """CREATE TABLE gpkg_contents (table_name TEXT NOT NULL PRIMARY KEY, data_type TEXT NOT NULL,
 identifier TEXT UNIQUE, description TEXT DEFAULT '', last_change DATETIME, min_x DOUBLE, min_y DOUBLE,
 max_x DOUBLE, max_y DOUBLE, srs_id INTEGER)"""
GPKG_COLUMNS = """CREATE TABLE gpkg_geometry_columns (table_name TEXT NOT NULL, column_name TEXT NOT NULL,
 geometry_type_name TEXT NOT NULL, srs_id INTEGER NOT NULL, z TINYINT NOT NULL, m TINYINT NOT NULL)"""


def gpkg_geometry(srs_id, wkb):
    """GeoPackage binary: magic, version 0, flags little endian without envelope, srs_id, WKB."""
    return b"GP\x00\x01" + struct.pack("<i", srs_id) + wkb


def wkb_point(x, y):
    return struct.pack("<BIdd", 1, 1, x, y)


def wkb_polygon(rings):
    out = struct.pack("<BII", 1, 3, len(rings))
    for ring in rings:
        out += struct.pack("<I", len(ring)) + b"".join(struct.pack("<dd", x, y) for x, y in ring)
    return out


def geopackage(srs, layers):
    """srs: (srs_id, organization, code, definition); layers: (name, type, create sql, rows, extent)."""
    handle, path = tempfile.mkstemp(suffix=".gpkg")
    os.close(handle)
    os.remove(path)
    try:
        db = sqlite3.connect(path)
        db.execute("PRAGMA page_size = 4096")
        db.execute("PRAGMA journal_mode = DELETE")
        db.execute("PRAGMA application_id = 1196444487")
        db.execute("PRAGMA user_version = 10200")
        db.execute(GPKG_SRS)
        db.execute(GPKG_CONTENTS)
        db.execute(GPKG_COLUMNS)
        db.execute("INSERT INTO gpkg_spatial_ref_sys VALUES ('Undefined cartesian SRS', -1, 'NONE', -1, 'undefined', NULL)")
        db.execute("INSERT INTO gpkg_spatial_ref_sys VALUES ('Undefined geographic SRS', 0, 'NONE', 0, 'undefined', NULL)")
        db.execute("INSERT INTO gpkg_spatial_ref_sys VALUES (?, ?, ?, ?, ?, NULL)", (srs[3].split('"')[1], *srs[:3], srs[3]))
        for name, kind, create, rows, extent in layers:
            db.execute(create)
            db.execute("INSERT INTO gpkg_contents VALUES (?, 'features', ?, '', '2026-10-14T12:00:00.000Z', ?, ?, ?, ?, ?)",
                       (name, name, *(extent or (None, None, None, None)), srs[0]))
            db.execute("INSERT INTO gpkg_geometry_columns VALUES (?, 'geom', ?, ?, 0, 0)", (name, kind, srs[0]))
            columns = len(rows[0])
            db.executemany(f'INSERT INTO "{name}" VALUES ({", ".join("?" * columns)})', rows)
        db.commit()
        db.close()
        with open(path, "rb") as f:
            return f.read()
    finally:
        if os.path.exists(path):
            os.remove(path)


def poi_gpkg():
    rows = [(i, gpkg_geometry(4326, wkb_point(x, y)), n, c, k, a) for i, n, c, k, a, x, y in POIS]
    create = ('CREATE TABLE "poi_esempio" ( "id" INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "geom" POINT, '
              '"nome" TEXT, "citta" TEXT, "categoria" TEXT, "anno" MEDIUMINT)')
    return geopackage((4326, "EPSG", 4326, OGC_WGS84), [("poi_esempio", "POINT", create, rows, bounds())])


# --- GeoTIFF ---------------------------------------------------------------------------------------

def relief_tiff():
    """100 by 100 pixels, RGB, EPSG:4326 from 6.6 E 47.1 N, pixels of 0.12 by 0.117 degrees: invented
    hills and an invented coast painted with a hypsometric ramp."""
    width = height = 100
    rng = random.Random(7)
    hills = [(rng.uniform(15, 85), rng.uniform(10, 90), rng.uniform(4, 10), rng.uniform(200, 1500)) for _ in range(14)]
    ramp = [(0, (110, 170, 120)), (200, (150, 190, 120)), (600, (220, 210, 140)), (1200, (190, 150, 100)),
            (2000, (150, 120, 100)), (2800, (245, 245, 245))]

    def colour(h):
        if h <= 0:
            return 70, 120, 190
        for (h0, c0), (h1, c1) in zip(ramp, ramp[1:]):
            if h <= h1:
                t = (h - h0) / (h1 - h0)
                return tuple(int(round(a + (b - a) * t)) for a, b in zip(c0, c1))
        return ramp[-1][1]

    pixels = bytearray()
    for row in range(height):
        for col in range(width):
            # A long island from north-west to south-east, the sea around it.
            d = abs((col - 50) * 0.8 + (row - 50) * 0.6) / 1.0 + abs((col - 50) * 0.6 - (row - 50) * 0.8) * 0.35
            coast = 30 + 6 * math.sin(row / 7.0) + 4 * math.cos(col / 5.0)
            h = sum(height_ * math.exp(-((col - cx) ** 2 + (row - cy) ** 2) / (2 * s * s)) for cx, cy, s, height_ in hills)
            h = h - 40 if d < coast else -1
            pixels += bytes(colour(h))

    entries = []
    extra = bytearray()
    data_offset = 8
    # Layout: header, image data, then the IFD and its values.
    image = bytes(pixels)
    ifd_offset = data_offset + len(image)
    ifd_count = 13
    values_offset = ifd_offset + 2 + ifd_count * 12 + 4

    def put(values_bytes):
        position = values_offset + len(extra)
        extra.extend(values_bytes)
        if len(extra) % 2:
            extra.append(0)
        return position

    bits = put(struct.pack("<3H", 8, 8, 8))
    scale = put(struct.pack("<3d", 0.12, 0.117, 0.0))
    tie = put(struct.pack("<6d", 0.0, 0.0, 0.0, 6.6, 47.1, 0.0))
    keys = put(struct.pack("<16H", 1, 1, 0, 3, 1024, 0, 1, 2, 1025, 0, 1, 1, 2048, 0, 1, 4326))

    def tag(code, kind, count, value):
        if kind == 3 and count == 1:
            return struct.pack("<HHIHH", code, kind, count, value, 0)
        return struct.pack("<HHII", code, kind, count, value)

    entries = [
        tag(256, 4, 1, width), tag(257, 4, 1, height), tag(258, 3, 3, bits), tag(259, 3, 1, 1),
        tag(262, 3, 1, 2), tag(273, 4, 1, data_offset), tag(277, 3, 1, 3), tag(278, 4, 1, height),
        tag(279, 4, 1, len(image)), tag(284, 3, 1, 1), tag(33550, 12, 3, scale), tag(33922, 12, 6, tie),
        tag(34735, 3, 16, keys),
    ]
    assert len(entries) == ifd_count
    return (b"II*\x00" + struct.pack("<I", ifd_offset) + image + struct.pack("<H", ifd_count) + b"".join(entries)
            + struct.pack("<I", 0) + bytes(extra))


# ---------------------------------------------------------------------------------------------------
# The larger synthetic layers
# ---------------------------------------------------------------------------------------------------

def neighbourhoods():
    """Fifteen neighbourhoods of Valverde: a 5 by 3 grid with shared, jittered vertices."""
    rng = random.Random(11)
    x0, y0, dx, dy = 12.30, 42.05, 0.032, 0.037
    grid = {}
    for i in range(6):
        for j in range(4):
            jitter = 0 if i in (0, 5) or j in (0, 3) else 1
            grid[i, j] = (x0 + i * dx + jitter * rng.uniform(-0.008, 0.008), y0 + j * dy + jitter * rng.uniform(-0.009, 0.009))

    def edge(a, b, seed):
        """The points between two vertices, the same whichever side walks it."""
        key = tuple(sorted([a, b]))
        r = random.Random(seed)
        (xa, ya), (xb, yb) = grid[key[0]], grid[key[1]]
        border = (key[0][0] == key[1][0] and key[0][0] in (0, 5)) or (key[0][1] == key[1][1] and key[0][1] in (0, 3))
        points = []
        for k in range(1, 4):
            t = k / 4
            wobble = 0 if border else r.uniform(-0.002, 0.002)
            points.append((xa + (xb - xa) * t + wobble, ya + (yb - ya) * t - wobble))
        return points if (a, b) == key else list(reversed(points))

    features = []
    number = 0
    for j in range(3):
        for i in range(5):
            corners = [(i, j), (i + 1, j), (i + 1, j + 1), (i, j + 1)]
            ring = []
            for a, b in zip(corners, corners[1:] + corners[:1]):
                seed = 1000 + min(a[0], b[0]) * 100 + min(a[1], b[1]) * 10 + (a[0] != b[0])
                ring.append(grid[a])
                ring += edge(a, b, seed)
            ring.append(ring[0])
            ring = [[r5(x), r5(y)] for x, y in ring]
            features.append(feature({"nome": f"Quartiere {ROMAN[number]}", "sigla": f"Q. {ROMAN[number]}",
                                     "codice": 5800 + number + 1, "abitanti": 8000 + (number * 3779) % 41000},
                                    {"type": "Polygon", "coordinates": [ring]}))
            number += 1
    return features


def walk(rng, x, y, steps, length):
    heading = rng.uniform(0, 2 * math.pi)
    line = [[r5(x), r5(y)]]
    for _ in range(steps):
        heading += rng.uniform(-0.6, 0.6)
        x += math.cos(heading) * length * rng.uniform(0.6, 1.4)
        y += math.sin(heading) * length * 0.75 * rng.uniform(0.6, 1.4)
        line.append([r5(x), r5(y)])
    return line


def cycleways():
    rng = random.Random(23)
    first = ["Ciclabile", "Pista ciclabile", "Percorso ciclopedonale", "Ciclovia"]
    second = ["del Lungolago", "delle Mura", "dei Mulini", "del Parco Grande", "della Stazione", "dei Giardini",
              "del Canale", "delle Fornaci", "del Borgo", "della Collina", "dei Pioppi", "del Mercato", "della Fiera",
              "delle Scuole", "dell'Ospedale", "del Torrente"]
    features = []
    for n in range(40):
        name = f"{first[n % 4]} {second[(n * 7) % 16]}" + ("" if n < 16 else f" {ROMAN[n % 15]}")
        parts = 1 if n % 9 == 0 or n >= 36 else rng.randint(2, 5)
        cx, cy = 12.38 + rng.uniform(-0.07, 0.07), 42.10 + rng.uniform(-0.045, 0.045)
        lines = [walk(rng, cx + rng.uniform(-0.01, 0.01), cy + rng.uniform(-0.01, 0.01), rng.randint(4, 12), 0.0025)
                 for _ in range(parts)]
        geometry = {"type": "LineString", "coordinates": lines[0]} if parts == 1 else {"type": "MultiLineString", "coordinates": lines}
        features.append(feature({"nome": name, "tipo": "pista ciclabile" if n % 3 else "percorso ciclopedonale", "tratti": parts},
                                geometry))
    return features


def transit():
    rng = random.Random(31)
    lines = [("A", "metropolitana", "#e4572e"), ("B", "metropolitana", "#2e86de"), ("C", "metropolitana", "#27ae60"),
             ("1", "tram", "#f1c40f"), ("2", "tram", "#8e44ad"), ("3", "tram", "#16a085"), ("5", "tram", "#d35400"),
             ("7", "tram", "#c0392b"), ("9", "tram", "#7f8c8d"), ("FV1", "ferrovia urbana", "#7e7bb4"),
             ("FV2", "ferrovia urbana", "#b07aa1"), ("FV3", "ferrovia urbana", "#4e79a7")]
    ends = ["Monteluce", "Borgo Alto", "Pratolungo", "Fontebella", "Serralta", "Vallerosa", "Campolargo",
            "Boscoverde", "Rivabianca", "Castelfiore", "Acquachiara", "Selvascura"]
    features = []
    for n, (line, mode, colour) in enumerate(lines):
        step = {"metropolitana": 0.006, "tram": 0.003, "ferrovia urbana": 0.012}[mode]
        main = walk(rng, 12.38 + rng.uniform(-0.01, 0.01), 42.10 + rng.uniform(-0.01, 0.01), rng.randint(10, 18), step)
        back = [[r5(x + 0.0004), r5(y + 0.0003)] for x, y in reversed(main)]
        features.append(feature({"linea": line, "modo": mode, "nome": f"{line} ({ends[n]})", "colore": colour},
                                {"type": "MultiLineString", "coordinates": [main, back]}))
    return features


def blob(rng, cx, cy, radius, points):
    ring = []
    for k in range(points):
        angle = 2 * math.pi * k / points
        r = radius * (0.65 + 0.35 * math.sin(3 * angle + rng.uniform(0, 1)) * rng.uniform(0.5, 1.0))
        ring.append([r5(cx + r * math.cos(angle) * 1.3), r5(cy + r * math.sin(angle))])
    ring.append(ring[0])
    return ring


def parks():
    rng = random.Random(41)
    kinds = ["Parco naturale", "Riserva naturale", "Parco regionale"]
    features = []
    for n, place in enumerate(PLACES):
        cx, cy = 10.6 + (n % 5) * 1.05 + rng.uniform(-0.25, 0.25), 40.9 + (n // 5) * 1.15 + rng.uniform(-0.25, 0.25)
        properties = {"nome": f"{kinds[n % 3]} di {place}", "tipo": kinds[n % 3].lower(), "codice": 9100 + n}
        if n % 4 == 1:
            properties["sito"] = f"https://parchi.esempio.example/{place.lower().replace(' ', '-')}"
        if n % 5 == 2:
            properties["sito"] = f"https://parchi.esempio.example/{place.lower().replace(' ', '-')}"
            properties["istituzione"] = 1990 + n
        features.append(feature(properties, {"type": "Polygon", "coordinates": [blob(rng, cx, cy, rng.uniform(0.08, 0.25), 24)]}))
    return features


def harbours():
    rng = random.Random(53)
    latin = PLACES + ["Capo Lucente", "Punta Esempio", "Cala Serena", "Scogliera Alta", "Baia dei Gabbiani",
                      "Sant'Elmo a Mare", "Lido Chiaro", "Isola Piana", "Torre del Faro", "Porto Fiorito"]
    accented = ["Calà Mirò", "Pòrt de l'Esempi", "Club Nàutic Esempi", "Marina dell'Àncora", "Čarobna Luka",
                "Portù Sant'Ìsola"]
    greek = ["Λιμάνι Πέτρας", "Όρμος Δάφνης", "Μαρίνα Ακτής", "Λιμάνι Νησίδας"]
    forms = ["Porto di", "Marina di", "Approdo di", "Darsena di", "Porticciolo di"]
    categories = ["porto", "marina", "approdo", "porto turistico", "porto peschereccio"]
    features = []
    for n in range(150):
        t = n / 150
        # A long invented coast, from the west of the basin to the east.
        x = -2.5 + 38 * t + rng.uniform(-0.4, 0.4)
        y = 38.5 + 4.5 * math.sin(t * 9.0) + 1.5 * math.cos(t * 23.0) + rng.uniform(-0.5, 0.5)
        if n % 25 == 0:
            name = greek[(n // 25) % len(greek)]
        elif n % 17 == 3:
            name = accented[(n // 17) % len(accented)]
        else:
            name = f"{forms[n % 5]} {latin[(n * 11) % len(latin)]}" + ("" if n < 60 else f" {ROMAN[n % 15]}")
        features.append(feature({"nome": name, "categoria": categories[(n * 3) % 5], "codice": 300000 + n * 37},
                                {"type": "Point", "coordinates": [r5(x), r5(min(max(y, 32.9), 46.1))]}))
    return features


def buoys():
    rng = random.Random(61)
    names = ["Boa Valverde", "Boa Capo Lucente", "Boa Punta Esempio", "Boa Cala Serena", "Boa Isola Piana",
             "Boa Scogliera Alta", "Boa Lido Chiaro", "Boa Porto Fiorito", "Boa Torre del Faro", "Boa Baia dei Gabbiani"]
    features = []
    for n, name in enumerate(names):
        x = 8.1 + n * 1.02 + rng.uniform(-0.2, 0.2)
        y = 37.5 + (n * 7 % 10) * 0.64 + rng.uniform(-0.1, 0.1)
        features.append(feature({"stazione": name, "rete": "Rete di boe di esempio", "rilevato": "2026-08-23T23:30",
                                 "altezza_onda_m": round(rng.uniform(0.02, 1.4), 2), "periodo_s": round(rng.uniform(2.5, 7.5), 2),
                                 "direzione_gradi": rng.randint(0, 359), "temp_superficie_c": round(rng.uniform(24, 30), 1)},
                                {"type": "Point", "coordinates": [round(x, 3), round(y, 3)]}))
    return features


def observations():
    rng = random.Random(71)
    species = [("Gypaetus barbatus", "uccelli", "Accipitridae"), ("Aquila chrysaetos", "uccelli", "Accipitridae"),
               ("Ciconia ciconia", "uccelli", "Ciconiidae"), ("Ardea alba", "uccelli", "Ardeidae"),
               ("Alcedo atthis", "uccelli", "Alcedinidae"), ("Canis lupus", "mammiferi", "Canidae"),
               ("Capreolus capreolus", "mammiferi", "Cervidae"), ("Sciurus vulgaris", "mammiferi", "Sciuridae"),
               ("Hystrix cristata", "mammiferi", "Hystricidae"), ("Meles meles", "mammiferi", "Mustelidae"),
               ("Bufo bufo", "anfibi", "Bufonidae"), ("Lissotriton vulgaris", "anfibi", "Salamandridae"),
               ("Salamandra salamandra", "anfibi", "Salamandridae"), ("Rana dalmatina", "anfibi", "Ranidae")]
    features = []
    for n in range(75):
        name, group, family = species[(n * 5) % len(species)]
        properties = {"specie": name, "gruppo": group, "famiglia": family,
                      "data": f"2026-{1 + n % 8:02d}-{1 + (n * 7) % 28:02d}", "luogo": PLACES[(n * 3) % len(PLACES)]}
        if n:
            properties["rilevatore"] = "Rilevamenti di esempio"
        properties["codice"] = 7000000 + n * 101
        features.append(feature(properties, {"type": "Point", "coordinates": [r5(rng.uniform(7.2, 16.4)), r5(rng.uniform(37.6, 46.3))]}))
    return features


# ---------------------------------------------------------------------------------------------------
# The test fixtures of the loading (src/test/resources/load)
# ---------------------------------------------------------------------------------------------------

def area_rings():
    """A square of 600 m around the castle of Valverde in UTM 33N, and a courtyard as its hole."""
    cx, cy = utm33(12.3790, 42.1040)
    outer = [(cx - 300, cy - 300), (cx + 300, cy - 300), (cx + 300, cy + 300), (cx - 300, cy + 300), (cx - 300, cy - 300)]
    hole = [(cx + 100, cy + 100), (cx + 100, cy + 200), (cx + 200, cy + 200), (cx + 200, cy + 100), (cx + 100, cy + 100)]
    return outer, hole


def areas_gpkg():
    outer, hole = area_rings()
    create = ('CREATE TABLE "aree" ( "fid" INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "geom" POLYGON, '
              '"nome" TEXT, "aperto" BOOLEAN, "dal" DATE)')
    long_name = "Area del castello di Valverde" + " con una descrizione molto lunga" * 220
    rows = [(1, gpkg_geometry(32633, wkb_polygon([outer, hole])), "Area del castello di Valverde", 1, "2026-10-13"),
            (2, gpkg_geometry(32633, wkb_polygon([outer])), long_name, 0, None)]
    return geopackage((32633, "EPSG", 32633, SHP_UTM33), [("aree", "POLYGON", create, rows, None)])


def two_layers_gpkg():
    outer, _ = area_rings()
    layers = []
    for name, value, open_ in (("aree", "A", 1), ("altre", "B", 0)):
        create = (f'CREATE TABLE "{name}" ( "fid" INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "geom" POLYGON, '
                  '"nome" TEXT, "aperto" BOOLEAN, "dal" DATE)')
        layers.append((name, "POLYGON", create, [(1, gpkg_geometry(32633, wkb_polygon([outer])), value, open_, None)], None))
    return geopackage((32633, "EPSG", 32633, SHP_UTM33), layers)


def stations_gml2():
    members = "".join(
        f'<gml:featureMember><ogr:stazioni><ogr:geometryProperty><gml:Point srsName="EPSG:4326"><gml:coordinates>{x},{y}'
        f'</gml:coordinates></gml:Point></ogr:geometryProperty><ogr:nome>{n}</ogr:nome></ogr:stazioni></gml:featureMember>\n'
        for n, x, y, _ in STATIONS)
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<ogr:FeatureCollection xmlns:ogr="http://ogr.maptools.org/" xmlns:gml="http://www.opengis.net/gml">\n'
            + members + '</ogr:FeatureCollection>\n')


def stations_gml_utm():
    members = ""
    for k, (n, x, y, _) in enumerate(STATIONS):
        e, north = utm33(x, y)
        members += (f'<wfs:member><t:stazione gml:id="s{k}"><t:nome>{n}</t:nome><t:geom><gml:Point gml:id="p{k}" '
                    f'srsName="urn:ogc:def:crs:EPSG::32633"><gml:pos>{e:.3f} {north:.3f}</gml:pos></gml:Point></t:geom>'
                    f'</t:stazione></wfs:member>\n')
    return ('<?xml version="1.0" encoding="UTF-8"?>\n<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0" '
            'xmlns:gml="http://www.opengis.net/gml/3.2" xmlns:t="urn:test">\n' + members + '</wfs:FeatureCollection>\n')


def stations_shp():
    points = [utm33(x, y) for _, x, y, _ in STATIONS]
    return shapefile_points(points, [("nome", "C", 20), ("binari", "N", 4)], [(n, b) for n, _, _, b in STATIONS],
                            SHP_UTM33, "stazioni", cpg="UTF-8")


# ---------------------------------------------------------------------------------------------------

def main():
    s = SAMPLES
    write(s, "poi-esempio.geojson", poi_geojson())
    write(s, "poi-esempio.csv", poi_csv())
    write(s, "poi-esempio.gml", poi_gml())
    kml = poi_kml()
    write(s, "poi-esempio.kml", kml)
    write(s, "poi-esempio.kmz", zipped([("doc.kml", kml.encode("utf-8"))]))
    write(s, "poi-esempio.gpkg", poi_gpkg())
    write(s, "poi-esempio-shp.zip", shapefile_points(
        [(x, y) for *_, x, y in POIS],
        [("id", "N", 9), ("nome", "C", 80), ("citta", "C", 80), ("categoria", "C", 80), ("anno", "N", 9)],
        [(i, n, c, k, a) for i, n, c, k, a, _, _ in POIS], SHP_WGS84, "poi-esempio"))
    write(s, "poi-esempio.gpx", poi_gpx())
    write(s, "poi-esempio.czml", poi_czml())

    # CSV edge cases, all with Windows line endings.
    write(s, "csv-bom-crlf.csv", "﻿" + crlf([
        "citta,regione,lat,lon",
        'Valdirà,"Regione Esempio, IT",43.512,11.734',
        "Sant'Alba di Monteluce,Regione Esempio,42.214,13.358"]))
    write(s, "csv-semicolon-decimal.csv", crlf(["nome;citta;latitudine;longitudine"] + [
        f"{n};{c};{num(y).replace('.', ',')};{num(x).replace('.', ',')}" for _, n, c, _, _, x, y in POIS]))
    write(s, "csv-tab.csv", crlf(["nome\tcitta\tlat\tlon"] + [f"{n}\t{c}\t{num(y)}\t{num(x)}" for _, n, c, _, _, x, y in POIS]))
    write(s, "csv-pipe-xy.csv", crlf(["nome|citta|y|x"] + [f"{n}|{c}|{num(y)}|{num(x)}" for _, n, c, _, _, x, y in POIS]))
    notes = ["Torre, I sec.", "Gotico", "Castello"]
    write(s, "csv-wkt-point.csv", crlf(["nome,note,geometry"] + [
        f'{n},"{notes[k]}","POINT ({x:.6f} {y:.6f})"' for k, (_, n, _, _, _, x, y) in enumerate(POIS[:3])]))
    quarter = neighbourhoods()[7]["geometry"]["coordinates"][0]
    path = walk(random.Random(5), 12.372, 42.096, 7, 0.0025)
    write(s, "csv-wkt-polygon.csv", crlf([
        "id,nome,tipo,wkt",
        '1,"Quartiere VIII di Valverde",quartiere,"POLYGON ((' + ", ".join(f"{x:.5f} {y:.5f}" for x, y in quarter) + '))"',
        '2,"Ciclabile del Lungolago",pista ciclabile,"LINESTRING (' + ", ".join(f"{x:.5f} {y:.5f}" for x, y in path) + ')"']))
    write(s, "csv-geojson-col.csv", crlf(["nome,geojson"] + [
        f'{c},"{{""type"":""Point"",""coordinates"":[{x:.6f},{y:.6f}]}}"' for _, _, c, _, _, x, y in POIS[:2]]))

    write(s, "rilievo-esempio.tif", relief_tiff())

    common = {"type": "FeatureCollection", "fonte": "Dati sintetici di Geoportal, generati da src/samples/generate.py",
              "licenza": "Nessuna licenza di terzi: luoghi e valori inventati", "crs": "EPSG:4326"}
    write(s, "quartieri_valverde.geojson", feature_collection(neighbourhoods(), **common,
          note="I quindici quartieri di Valverde, citta' inventata della Regione Esempio."))
    write(s, "piste_ciclabili_valverde.geojson", feature_collection(cycleways(), **common,
          note="Le piste ciclabili di Valverde: tratti singoli e percorsi in piu' tratti."))
    write(s, "rete_tpl_valverde.geojson", feature_collection(transit(), **common,
          note="Metropolitane, tram e ferrovie urbane di Valverde, andata e ritorno."))
    write(s, "aree_protette_esempio.geojson", feature_collection(parks(), **common,
          note="Parchi e riserve della Regione Esempio; non tutti hanno gli stessi attributi."))
    write(s, "porti_esempio.geojson", feature_collection(harbours(), **common,
          note="Porti e approdi di una costa inventata, con nomi accentati e in alfabeto greco."))
    write(s, "boe_meteo.geojson", feature_collection(buoys(), **common,
          note="Stato del mare su dieci boe inventate, una lettura ciascuna."))
    write(s, "osservazioni_naturalistiche.geojson", feature_collection(observations(), **common,
          note="Avvistamenti inventati di uccelli, mammiferi e anfibi; uno senza rilevatore."))

    f = FIXTURES
    write(f, "aree-utm33.gpkg", areas_gpkg())
    write(f, "due-livelli.gpkg", two_layers_gpkg())
    write(f, "stazioni-gml2.gml", stations_gml2())
    write(f, "stazioni-utm33.gml", stations_gml_utm())
    write(f, "stazioni-utm33.zip", stations_shp())

    for name, x, y, _ in STATIONS:
        print(f"{name}: {x}, {y} -> EPSG:32633 {utm33(x, y)}")
    print("extent of the landmarks:", bounds())


if __name__ == "__main__":
    main()
