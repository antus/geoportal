-- SPDX-FileCopyrightText: 2026 Massimo Antonini
--
-- Loading by the GDAL sidecar (ADR-0019, GP-937): the job of the loader while a dataset is loading,
-- the table it writes before the catalog takes it over, its progress, and the layer of a file with
-- several, chosen by the person among those the loader listed.

alter table uploaded_data
    add column load_job      varchar(64),
    add column load_table    varchar(63),
    add column load_progress integer,
    add column layer         varchar(255),
    -- The layers of a file with several, as JSON [{"name", "features", "geometryType"}], to choose from.
    add column layers        text;
