-- SPDX-FileCopyrightText: 2026 Massimo Antonini
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Loading of uploaded data (GP-937, GP-321, GP-324): what the loading of the current version produced,
-- or why it did not. A vector is a table of the schema of the organization in the geodata database, a
-- raster a Cloud Optimized GeoTIFF in the file service of Mosaikit.

alter table uploaded_data
    -- Why the state is what it is, for the page: crs-missing, crs-unknown, crs-mismatch, unreadable,
    -- empty, geometry-invalid, several-layers, quota, geodata-missing, geodata-unavailable.
    add column state_code     varchar(40),
    -- The reference system (EPSG:n) and where it comes from: file, format or declared (GP-324).
    add column crs            varchar(20),
    add column crs_source     varchar(20),
    -- Point, MultiPolygon, ..., Geometry when mixed, Raster.
    add column geometry_type  varchar(30),
    add column has_z          boolean,
    -- The features of a vector, the pixels of a raster.
    add column features       bigint,
    -- The extent in the reference system of the data, and in WGS84 degrees when it is reliable (GP-321).
    add column native_min_x   double precision,
    add column native_min_y   double precision,
    add column native_max_x   double precision,
    add column native_max_y   double precision,
    add column wgs84_west     double precision,
    add column wgs84_south    double precision,
    add column wgs84_east     double precision,
    add column wgs84_north    double precision,
    -- schema.table in the geodata database, for a vector.
    add column table_name     varchar(130),
    -- The COG in the file service, for a raster.
    add column cog_file_id    uuid,
    -- The space in PostGIS, reported to the storage quota of the organization.
    add column space          bigint not null default 0,
    add column loaded_at      timestamptz;
