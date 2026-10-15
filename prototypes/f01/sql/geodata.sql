-- SPDX-FileCopyrightText: 2026 Massimo Antonini
--
-- GP-327 F01: test data in the database geodata, one schema per organization (= GeoServer
-- workspace). 200 parcels in two districts of Valverde, with a reserved column (tax code of the
-- holder); 50 points in Monteluce for the second organization (both towns are invented). Run as the user geoserver.

create schema if not exists alfa;
drop table if exists alfa.particelle;
create table alfa.particelle (
    fid serial primary key, foglio int, particella int, municipio text, stato text,
    cf_titolare text, geom geometry(Point, 4326));
insert into alfa.particelle (foglio, particella, municipio, stato, cf_titolare, geom)
select (g % 40) + 1, g,
       case when g % 2 = 0 then 'I' else 'II' end,
       case when g % 5 = 0 then 'soppresso' else 'attivo' end,
       'RSSMRA' || lpad(g::text, 5, '0'),
       ST_SetSRID(ST_MakePoint(case when g % 2 = 0 then 12.37 else 12.42 end + (g % 10) * 0.001,
                               42.09 + (g / 10) * 0.0005), 4326)
from generate_series(1, 200) g;

create schema if not exists beta;
drop table if exists beta.fermate;
create table beta.fermate (fid serial primary key, nome text, geom geometry(Point, 4326));
insert into beta.fermate (nome, geom)
select 'Fermata ' || g, ST_SetSRID(ST_MakePoint(10.87 + g * 0.001, 44.61 + g * 0.0005), 4326)
from generate_series(1, 50) g;

-- Where the Importer writes (proof 6).
create schema if not exists imports;
