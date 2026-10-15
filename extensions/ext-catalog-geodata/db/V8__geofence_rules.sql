-- SPDX-FileCopyrightText: 2026 Massimo Antonini
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- GP-335: the GeoFence rule set the catalog last wrote for the workspace of each organization, to tell
-- the rules added by hand from its own, to write the set again only when it changed, and to report the
-- outcome in the reconciliation.

create table geofence_rule_set (
    organization_id uuid primary key default mk_kernel.current_organization(),
    -- SHA-256 of the generated set, in canonical form.
    fingerprint     varchar(64),
    -- The rules last written, one canonical line each, as a JSON array.
    rules           text          not null default '[]',
    -- The priority of the first rule in GeoFence.
    base_priority   bigint,
    written_at      timestamptz,
    -- written, unchanged, mismatch or failed, and what the last write found.
    outcome         varchar(20),
    removed         text          not null default '[]',
    mismatches      text          not null default '[]',
    warnings        text          not null default '[]',
    error           varchar(1000),
    checked_at      timestamptz
);

alter table geofence_rule_set enable row level security;

create policy geofence_rule_set_organization on geofence_rule_set
    using (organization_id = mk_kernel.current_organization())
    with check (organization_id = mk_kernel.current_organization());
