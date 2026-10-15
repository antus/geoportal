-- SPDX-FileCopyrightText: 2026 Massimo Antonini
--
-- GP-983: an installation that had the catalog of Geoportal as the extension dev.geoportal.catalog, with
-- the schema ext_catalog, adopts its data as dev.geoportal.ext.catalog.geodata, with the schema
-- ext_catalog_geodata, before the first start with the new extensions. ext_catalog is the schema of the
-- file manager of mosaikit-ext from then on: the kernel would refuse to start with the history of the
-- old catalog in it.
--
--   psql -v ON_ERROR_STOP=1 -d <database of Mosaikit> -U <its owner> -f adopt-catalog.sql
--
-- What it does, in one statement, so all of it or none of it:
--   1. renames the schema ext_catalog to ext_catalog_geodata, with its tables, its data and its Flyway
--      history (the migrations of ext-catalog-geodata are those of the catalog, unchanged: the kernel
--      finds them applied);
--   2. gives the rows of the kernel that name dev.geoportal.catalog (resources and their grants,
--      permissions, credentials, files, quotas, apps of organizations, documents, notifications) to
--      dev.geoportal.ext.catalog.geodata: every column extension_id of the schema mk_kernel, the measures
--      dev.geoportal.catalog/<measure>, the links /app/catalog of its resources, and the API catalog of
--      the service identities, which becomes catalog-geodata.
-- It deletes nothing. It changes nothing on a new installation, on one already adopted, or on one whose
-- ext_catalog is the file manager of mosaikit-ext; run again, it changes nothing. It refuses, changing
-- nothing, when ext_catalog_geodata exists next to the old ext_catalog (ext-catalog-geodata was started
-- before this script: see the README of the extension). The audit log keeps the names it was written
-- with. Settings in files (application.properties, values of Helm) are changed by hand: see the README.
do $adopt$
declare
    old_id constant text := 'dev.geoportal.catalog';
    new_id constant text := 'dev.geoportal.ext.catalog.geodata';
    target record;
    changed bigint;
    catalog boolean := false;
begin
    -- Dynamic: a statement naming a table that does not exist fails when it is planned.
    if to_regclass('ext_catalog.flyway_schema_history') is not null then
        execute 'select exists (select 1 from ext_catalog.flyway_schema_history'
            ' where version = ''1'' and description = ''create dataset'' and success)' into catalog;
    end if;
    if not catalog then
        raise notice 'GP-983: no catalog of Geoportal in the schema ext_catalog, nothing to adopt';
        return;
    end if;
    if to_regnamespace('ext_catalog_geodata') is not null then
        raise exception 'GP-983: both ext_catalog (the catalog of Geoportal) and ext_catalog_geodata exist; '
            'nothing changed. ext-catalog-geodata was started before this script: see the README of the extension';
    end if;

    alter schema ext_catalog rename to ext_catalog_geodata;
    raise notice 'GP-983: schema ext_catalog renamed to ext_catalog_geodata';

    if to_regnamespace('mk_kernel') is null then
        return;
    end if;
    for target in
        select c.table_name from information_schema.columns c
        join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name
        where c.table_schema = 'mk_kernel' and c.column_name = 'extension_id' and t.table_type = 'BASE TABLE'
        order by c.table_name
    loop
        execute format('update mk_kernel.%I set extension_id = $1 where extension_id = $2', target.table_name)
            using new_id, old_id;
        get diagnostics changed = row_count;
        if changed > 0 then
            raise notice 'GP-983: mk_kernel.%: % rows of % given to %', target.table_name, changed, old_id, new_id;
        end if;
    end loop;
    for target in
        select c.table_name from information_schema.columns c
        join information_schema.tables t on t.table_schema = c.table_schema and t.table_name = c.table_name
        where c.table_schema = 'mk_kernel' and c.column_name = 'measure_id' and t.table_type = 'BASE TABLE'
        order by c.table_name
    loop
        execute format('update mk_kernel.%I set measure_id = $1 || substr(measure_id, $3) where measure_id like $2',
                       target.table_name)
            using new_id || '/', old_id || '/%', length(old_id) + 2;
        get diagnostics changed = row_count;
        if changed > 0 then
            raise notice 'GP-983: mk_kernel.%: % measures renamed', target.table_name, changed;
        end if;
    end loop;
    if to_regclass('mk_kernel.resource') is not null then
        update mk_kernel.resource set link = '/app/geodata' || substr(link, length('/app/catalog') + 1)
        where extension_id = new_id and (link = '/app/catalog' or link like '/app/catalog?%');
    end if;
    if to_regclass('mk_kernel.service_identity') is not null then
        update mk_kernel.service_identity
        set apis = array_to_string(array(
                select case when trim(api) = 'catalog' then 'catalog-geodata' else trim(api) end
                from unnest(string_to_array(apis, ',')) as api), ',')
        where 'catalog' = any (select trim(api) from unnest(string_to_array(apis, ',')) as api);
        get diagnostics changed = row_count;
        if changed > 0 then
            raise notice 'GP-983: % service identities given the API catalog-geodata for catalog', changed;
        end if;
    end if;
end
$adopt$;
