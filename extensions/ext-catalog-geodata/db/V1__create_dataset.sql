-- SPDX-FileCopyrightText: 2026 Massimo Antonini
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- Schema ext_catalog, owned by the extension and migrated by the kernel at start. Each dataset
-- belongs to an organization, and row-level security shows each request only its own.

create table dataset (
    id              uuid primary key,
    organization_id uuid          not null default mk_kernel.current_organization(),
    title           varchar(200)  not null,
    kind            varchar(20)   not null,
    format          varchar(60)   not null,
    theme           varchar(60)   not null,
    host            varchar(20)   not null,
    access          varchar(20)   not null,
    updated         varchar(60)   not null,
    size            varchar(60)   not null,
    owner           varchar(120)  not null,
    description     varchar(1000) not null,
    created_at      timestamptz   not null
);

create index dataset_organization on dataset (organization_id);

alter table dataset enable row level security;

create policy dataset_organization on dataset
    using (organization_id = mk_kernel.current_organization())
    with check (organization_id = mk_kernel.current_organization());
