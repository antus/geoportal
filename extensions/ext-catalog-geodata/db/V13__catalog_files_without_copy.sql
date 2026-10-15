-- SPDX-FileCopyrightText: 2026 Massimo Antonini
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- GP-983, EXT-107: a dataset made from a file of the catalog reads its content through the catalog, with
-- a signed link for the loader, and keeps no copy of it: file_id is null for such a dataset. The copies
-- made before are not deleted here: each is deleted by ext-catalog-geodata once the catalog has told it
-- that the file is there and readable (RECOGNIZED, after this update), so that a misconfiguration never
-- leaves a dataset without its content.

alter table uploaded_data alter column file_id drop not null;

alter table uploaded_data add constraint uploaded_data_content
    check (file_id is not null or catalog_file is not null);
