<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# Sample datasets

`generate.py` writes the 24 sample datasets of the catalog (GP-936) into
`src/main/resources/samples/` and the fixtures of the loading tests into `src/test/resources/load/`.
Every place, name and value in them is invented: Valverde, Monteluce, Borgo Alto, Pietrachiara,
Lagofondo and the other towns of the Regione Esempio do not exist, and the coordinates are only
plausible ones, so that the data look right on a map. Nothing is downloaded, so no file carries the
licence or the attribution of anyone else.

```bash
python extensions/ext-catalog-geodata/src/samples/generate.py
```

Standard library only (Python 3.10 or later). The output is deterministic: a second run writes the
same bytes, so `git status` shows no change unless the script changed. `samples.json` (titles and
descriptions shown in the catalog) is kept by hand next to the files.

## What each file tests

| File | Format | What it exercises |
|---|---|---|
| `poi-esempio.geojson`, `.csv`, `.kml`, `.kmz`, `.gpkg`, `-shp.zip`, `.gpx`, `.czml` | all vector formats | five landmarks; the tests find Torre Civica and Cattedrale di Monteluce within a metre of their coordinates, and the extent of the five |
| `poi-esempio.gml` | GML 3.2 | the URN of EPSG:4326, so latitude first: the axis swap |
| `poi-esempio.gpkg` | GeoPackage | typed columns (`anno` MEDIUMINT becomes `bigint`, `nome` text) |
| `csv-bom-crlf.csv` | CSV | UTF-8 BOM, CRLF, an accented name, a comma inside quotes |
| `csv-semicolon-decimal.csv` | CSV | semicolon separator and decimal comma, as exported by a spreadsheet in Italian |
| `csv-tab.csv`, `csv-pipe-xy.csv` | CSV | tab and pipe separators; x and y columns that state no system (declared EPSG:4326) |
| `csv-wkt-point.csv`, `csv-wkt-polygon.csv` | CSV | a WKT column; points, and a polygon with a line in one table |
| `csv-geojson-col.csv` | CSV | a geometry written as GeoJSON inside a column: WGS84 by RFC 7946, loaded without a declared system |
| `rilievo-esempio.tif` | GeoTIFF | 100 × 100 RGB in EPSG:4326 (6.6 to 18.6 E, 35.4 to 47.1 N), an invented island in a hypsometric ramp; becomes a COG |
| `quartieri_valverde.geojson` | GeoJSON | 15 polygons whose borders match exactly |
| `piste_ciclabili_valverde.geojson` | GeoJSON | LineString and MultiLineString in one dataset |
| `rete_tpl_valverde.geojson` | GeoJSON | MultiLineString with a colour per line |
| `aree_protette_esempio.geojson` | GeoJSON | polygons whose features do not all have the same attributes |
| `porti_esempio.geojson` | GeoJSON | 150 points, names with accents and in the Greek alphabet |
| `boe_meteo.geojson` | GeoJSON | decimal measures and a timestamp |
| `osservazioni_naturalistiche.geojson` | GeoJSON | 75 points for a density map; one feature lacks an attribute |

Fixtures of `src/test/resources/load/`:

| File | What it exercises |
|---|---|
| `stazioni-utm33.zip` | Shapefile in EPSG:32633 with the shapefile dialect of WKT in its `.prj` (`WGS_1984_UTM_Zone_33N`), a `.cpg`; the tests also drop its `.prj` |
| `stazioni-utm33.gml`, `stazioni-gml2.gml` | GML with a projected URN (easting first) and GML 2 in EPSG:4326 (longitude first) |
| `aree-utm33.gpkg` | GeoPackage in EPSG:32633: a polygon with a hole, BOOLEAN and DATE columns, a name longer than a page of SQLite |
| `due-livelli.gpkg` | GeoPackage with two tables of features, `aree` and `altre` |

The three stations of Valverde are projected to UTM zone 33N by the script itself (Krüger series,
well below a millimetre); the JUnit tests compare them with PostGIS within a metre.
