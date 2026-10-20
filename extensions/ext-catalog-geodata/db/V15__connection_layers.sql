-- SPDX-FileCopyrightText: 2026 Massimo Antonini
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- GP-986, ADR-0030: a table or view of a PostgreSQL connection of ext-catalog-connection mapped as a
-- layer, without copying it. Its dataset has no content of its own (source 'connection'); GeoServer reads
-- the table through a store of its own on the source, named in geoserver_layer.store.

alter table uploaded_data add column source varchar(20) not null default 'file';

alter table uploaded_data drop constraint uploaded_data_content;

alter table uploaded_data add constraint uploaded_data_content
    check (file_id is not null or catalog_file is not null or source = 'connection');

-- The connection and the table of a dataset of source 'connection': the reference of the source picker
-- (EXT-026), kept as it was chosen, and what the resolution of that reference found when the layer was
-- made or refreshed. Never a credential: only its name in the secrets service of the platform.
create table connection_layer (
    dataset_id      uuid primary key references uploaded_data (id) on delete cascade,
    organization_id uuid          not null default mk_kernel.current_organization(),
    reference       text          not null,
    connection_id   uuid          not null,
    part_id         varchar(1000) not null,
    host            varchar(255)  not null,
    port            integer       not null,
    database_name   varchar(255)  not null,
    schema_name     varchar(255)  not null,
    table_name      varchar(255)  not null,
    geometry_column varchar(255)  not null,
    db_user         varchar(255)  not null,
    ssl_mode        varchar(20),
    credential      varchar(255)  not null,
    store           varchar(100)  not null,
    resolved_at     timestamptz   not null
);

alter table connection_layer enable row level security;

create policy connection_layer_organization on connection_layer
    using (organization_id = mk_kernel.current_organization())
    with check (organization_id = mk_kernel.current_organization());

-- The store of a layer: null for the store of the organization, the store of its connection otherwise.
alter table geoserver_layer add column store varchar(100);
