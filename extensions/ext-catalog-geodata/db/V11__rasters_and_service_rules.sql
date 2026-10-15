-- SPDX-FileCopyrightText: 2026 Massimo Antonini
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- GP-309: data rules on rasters and on external services. (V10 is the layer assignments of GP-036.)
--
-- A published layer is a vector (a feature type on the store of the organization) or a raster (a
-- coverage, the COG of a dataset, in a coverage store named as the layer).

alter table geoserver_layer add column kind varchar(10) not null default 'vector'
    check (kind in ('vector', 'raster'));

-- A data rule on a layer of an external service (GP-939), or on the whole service when layer is null. The
-- proxy cannot filter what another server answers, so such a rule makes the layer unavailable to its
-- subject, with the reason; its limits are kept to say what it asked for.
create table service_data_rule (
    id              uuid primary key,
    organization_id uuid          not null default mk_kernel.current_organization(),
    service_id      uuid          not null,
    -- The name of the layer as the service names it, or null for every layer of the service.
    layer           varchar(255),
    -- person or team, and the identifier of the account or of the team.
    subject_kind    varchar(10)   not null check (subject_kind in ('person', 'team')),
    subject_id      uuid          not null,
    description     varchar(500)  not null default '',
    area            text,
    cql             varchar(2000),
    hidden_columns  text          not null default '[]',
    created_by      varchar(320),
    created_at      timestamptz   not null
);

create index service_data_rule_service on service_data_rule (service_id);

alter table service_data_rule enable row level security;

create policy service_data_rule_organization on service_data_rule
    using (organization_id = mk_kernel.current_organization())
    with check (organization_id = mk_kernel.current_organization());
