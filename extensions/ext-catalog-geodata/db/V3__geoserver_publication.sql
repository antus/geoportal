-- SPDX-FileCopyrightText: 2026 Massimo Antonini
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- GP-334: the layers and styles that the catalog publishes in the GeoServer workspace of each
-- organization. These rows are the definition GeoServer is aligned to: a layer or style changed in
-- GeoServer by hand is brought back to them, and anything in the workspace without a row is drift.
-- A withdrawn row stays, retired, until the alignment has deleted its object from GeoServer.

create table geoserver_style (
    id              uuid primary key,
    organization_id uuid          not null default mk_kernel.current_organization(),
    name            varchar(63)   not null,
    sld             text          not null,
    retired         boolean       not null default false,
    updated_at      timestamptz   not null,
    unique (organization_id, name)
);

create table geoserver_layer (
    id              uuid primary key,
    organization_id uuid          not null default mk_kernel.current_organization(),
    name            varchar(63)   not null,
    table_name      varchar(63)   not null,
    title           varchar(200)  not null,
    description     varchar(1000) not null,
    srs             varchar(20)   not null,
    style           varchar(63),
    retired         boolean       not null default false,
    updated_at      timestamptz   not null,
    unique (organization_id, name)
);

alter table geoserver_style enable row level security;
alter table geoserver_layer enable row level security;

create policy geoserver_style_organization on geoserver_style
    using (organization_id = mk_kernel.current_organization())
    with check (organization_id = mk_kernel.current_organization());

create policy geoserver_layer_organization on geoserver_layer
    using (organization_id = mk_kernel.current_organization())
    with check (organization_id = mk_kernel.current_organization());
