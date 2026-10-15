-- SPDX-FileCopyrightText: 2026 Massimo Antonini
--
-- GP-327 F01: what GeoServer reads of Mosaikit, in the Mosaikit database, once the kernel has
-- migrated it. Run as the owner of the database (here the superuser of the container).
--
-- * Schema gs_sec holds views with the names and columns of GeoServer's JDBC role and user/group
--   services; nothing else of Mosaikit is visible to GeoServer.
-- * The login role gs_reader may only read those views: no table of mk_kernel, no extension schema.
-- * The views run with the rights of their owner (security_invoker off, the default), who owns
--   the kernel tables and so is not subject to row-level security: GeoServer sees the people of
--   EVERY organization, which it needs, since workspaces are organizations. Through them gs_reader
--   reads usernames and role names only, never the data of an organization.
-- * Role names: ORG_<slug>_<user|admin|guest> for the membership of an organization, TEAM_<id>
--   for a team (owners and members, not guests). The id, not the name: a team can be renamed, a
--   GeoFence rule must not silently stop matching.
-- * The administrator of GeoServer is "gsadmin", a name no Mosaikit account can have (accounts
--   are e-mail addresses, except Mosaikit's own bootstrap administrator, who is "admin": with the
--   F0 mapping 'admin' -> ADMIN, Mosaikit's administrator would have become GeoServer's).

do $$ begin
    if not exists (select 1 from pg_roles where rolname = 'gs_reader') then
        create role gs_reader login password 'f01-proof';
    end if;
end $$;

create schema if not exists gs_sec;
revoke all on schema gs_sec from public;

create or replace view gs_sec.user_roles as
    select a.username::text as username,
           ('ORG_' || o.slug || '_' || replace(r.role, 'organization-', ''))::text as rolename
    from mk_kernel.user_account a
    join mk_kernel.organization_member m on m.account_id = a.id
    join mk_kernel.organization o on o.id = m.organization_id
    cross join lateral regexp_split_to_table(m.roles, ',') as r(role)
    union
    select a.username::text, ('TEAM_' || t.id)::text
    from mk_kernel.team_member tm
    join mk_kernel.team t on t.id = tm.team_id and t.kind = 'team'
    join mk_kernel.user_account a on a.id = tm.account_id
    where tm.role in ('owner', 'member')
    union
    select 'gsadmin', 'ADMIN';

create or replace view gs_sec.roles as
    select distinct rolename as name, null::text as parent from gs_sec.user_roles;
create or replace view gs_sec.role_props as
    select null::text as rolename, null::text as propname, null::text as propvalue where false;
create or replace view gs_sec.group_roles as
    select null::text as groupname, null::text as rolename where false;

create or replace view gs_sec.users as
    select username::text as name, null::text as password, 'Y'::char(1) as enabled
    from mk_kernel.user_account;
create or replace view gs_sec.user_props as
    select null::text as username, null::text as propname, null::text as propvalue where false;
create or replace view gs_sec.groups as
    select null::text as name, 'Y'::char(1) as enabled where false;
create or replace view gs_sec.group_members as
    select null::text as groupname, null::text as username where false;

grant usage on schema gs_sec to gs_reader;
grant select on all tables in schema gs_sec to gs_reader;
