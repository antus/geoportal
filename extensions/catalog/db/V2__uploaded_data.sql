-- SPDX-FileCopyrightText: 2026 Massimo Antonini
--
-- Uploaded data (GP-936): a file uploaded to the catalog is a resource of the organization. The
-- content is in the file service of Mosaikit, never here; this table keeps what the catalog adds:
-- the readable title, the technical name of the table and layer that GP-937 loads it into, the
-- state of its lifecycle and its version.

create table uploaded_data (
    id              uuid primary key,
    organization_id uuid          not null default mk_kernel.current_organization(),
    title           varchar(200)  not null,
    description     varchar(2000) not null default '',
    -- The stable name of the PostGIS table and of the GeoServer layer: kept when the title changes.
    technical_name  varchar(63)   not null,
    format          varchar(20)   not null,
    -- The current file in the file service of Mosaikit, with what the catalog shows of it.
    file_id         uuid          not null,
    file_name       varchar(255)  not null,
    content_type    varchar(255)  not null,
    size            bigint        not null,
    -- uploaded (stored, not loaded yet); GP-937 adds loading, published and failed.
    state           varchar(20)   not null,
    state_reason    varchar(1000),
    version         integer       not null,
    sample          boolean       not null default false,
    created_at      timestamptz   not null,
    updated_at      timestamptz   not null,
    constraint uploaded_data_technical_name unique (organization_id, technical_name)
);

alter table uploaded_data enable row level security;

create policy uploaded_data_organization on uploaded_data
    using (organization_id = mk_kernel.current_organization())
    with check (organization_id = mk_kernel.current_organization());

-- The organizations whose sample datasets were loaded, so that they are loaded once.
create table sample_load (
    organization_id uuid        primary key default mk_kernel.current_organization(),
    loaded_at       timestamptz not null,
    loaded          integer     not null
);

alter table sample_load enable row level security;

create policy sample_load_organization on sample_load
    using (organization_id = mk_kernel.current_organization())
    with check (organization_id = mk_kernel.current_organization());
