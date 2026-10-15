-- SPDX-FileCopyrightText: 2026 Massimo Antonini
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- GP-983: a dataset made from a file of the catalog of mosaikit-ext (a kind of file, EXT-105) names
-- that file, which is its parent among the resources of the kernel; null for a dataset uploaded to the
-- geodata themselves. One dataset per file.

alter table uploaded_data add column catalog_file uuid;

create unique index uploaded_data_catalog_file on uploaded_data (organization_id, catalog_file)
    where catalog_file is not null;
