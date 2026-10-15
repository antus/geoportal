-- SPDX-FileCopyrightText: 2026 Massimo Antonini
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- External services registered in the catalog (GP-939): an OGC service of another server, reached
-- from the browser directly or through the proxy of the catalog. Each belongs to an organization,
-- and row-level security shows each request only its own. Who may see, use or manage one is kept by
-- the sharing of Mosaikit (resource type "service").

create table external_service (
    id              uuid primary key,
    organization_id uuid          not null default mk_kernel.current_organization(),
    title           varchar(200)  not null,
    -- wms, wmts, wfs or wcs.
    kind            varchar(10)   not null,
    -- The address as registered: http or https, no user information, no fragment.
    url             varchar(2000) not null,
    -- Whether the map reaches it through the proxy of the catalog.
    proxy           boolean       not null default false,
    -- The reference system the proxy declares in the requests of features (WFS).
    crs             varchar(40),
    -- The name of a credential of Mosaikit sent to the service by the proxy, and how.
    credential      varchar(100),
    auth_scheme     varchar(10),
    created_at      timestamptz   not null,
    updated_at      timestamptz   not null
);

create index external_service_organization on external_service (organization_id);

alter table external_service enable row level security;

create policy external_service_organization on external_service
    using (organization_id = mk_kernel.current_organization())
    with check (organization_id = mk_kernel.current_organization());
