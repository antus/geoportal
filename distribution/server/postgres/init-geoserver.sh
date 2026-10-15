#!/bin/sh
# SPDX-FileCopyrightText: 2026 Massimo Antonini
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# First start of PostGIS: the user geoserver with its two databases, geoserver (the GeoFence rules,
# schema geofence) and geodata (the data of Geoportal, with PostGIS; with PostGIS raster too, whose
# GDAL converts uploaded GeoTIFF into Cloud Optimized GeoTIFF, GP-937).
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v password="$GEOSERVER_DB_PASSWORD" <<'SQL'
CREATE USER geoserver WITH PASSWORD :'password';
CREATE DATABASE geoserver OWNER geoserver;
CREATE DATABASE geodata OWNER geoserver;
SQL
for database in geoserver geodata; do
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$database" <<'SQL'
CREATE EXTENSION IF NOT EXISTS postgis;
SQL
done
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname geodata <<'SQL'
CREATE EXTENSION IF NOT EXISTS postgis_raster;
-- GDAL reads GeoTIFF and writes COG for the catalog; every other driver stays off.
ALTER DATABASE geodata SET postgis.gdal_enabled_drivers = 'GTiff COG';
SQL
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname geoserver <<'SQL'
CREATE SCHEMA IF NOT EXISTS geofence AUTHORIZATION geoserver;
SQL
