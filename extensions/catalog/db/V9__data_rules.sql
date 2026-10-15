-- SPDX-FileCopyrightText: 2026 Massimo Antonini
--
-- GP-308: data rules. A rule limits what a subject (a person or a team) sees of a dataset: an area in
-- the reference system of its data, a CQL filter of the rows, hidden columns. Rules that apply to the
-- same person on the same dataset intersect; GeoFence enforces them (GP-335).

create table data_rule (
    id              uuid primary key,
    organization_id uuid          not null default mk_kernel.current_organization(),
    dataset_id      uuid          not null,
    -- person or team, and the identifier of the account or of the team.
    subject_kind    varchar(10)   not null check (subject_kind in ('person', 'team')),
    subject_id      uuid          not null,
    description     varchar(500)  not null default '',
    -- SRID=<code>;MULTIPOLYGON(...) in the reference system of the dataset, or null for no area.
    area            text,
    cql             varchar(2000),
    -- The hidden columns, as a JSON array.
    hidden_columns  text          not null default '[]',
    created_by      varchar(320),
    created_at      timestamptz   not null,
    updated_at      timestamptz   not null
);

create index data_rule_dataset on data_rule (dataset_id);

alter table data_rule enable row level security;

create policy data_rule_organization on data_rule
    using (organization_id = mk_kernel.current_organization())
    with check (organization_id = mk_kernel.current_organization());
