-- SPDX-FileCopyrightText: 2026 Massimo Antonini
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- GP-036: the layers assigned to each surveyor. A surveyor writes the data of a published layer, through
-- the catalog and through WFS-T of GeoServer, only when the layer is assigned to them and they hold the
-- permission edit-layer-data; the GeoFence rules of the organization are generated from these rows.
-- (V9 is left to the data rules of GP-308.)

create table layer_assignment (
    id              uuid primary key,
    organization_id uuid          not null default mk_kernel.current_organization(),
    -- The name of the published layer (ext_catalog.geoserver_layer.name).
    layer           varchar(63)   not null,
    -- The account of the surveyor in Mosaikit, and its user name when it was assigned: the name GeoServer
    -- receives and the request of the surveyor carries (the rules read the current one by account).
    account_id      uuid          not null,
    username        varchar(320)  not null,
    -- The user name of who assigned it.
    assigned_by     varchar(320),
    assigned_at     timestamptz   not null,
    -- Whether the surveyor held edit-layer-data at their last request to the catalog: null until then.
    -- Mosaikit tells an extension the permissions of the person of the request only.
    holds           boolean,
    checked_at      timestamptz,
    unique (organization_id, layer, account_id)
);

create index layer_assignment_username on layer_assignment (organization_id, lower(username));

alter table layer_assignment enable row level security;

create policy layer_assignment_organization on layer_assignment
    using (organization_id = mk_kernel.current_organization())
    with check (organization_id = mk_kernel.current_organization());
