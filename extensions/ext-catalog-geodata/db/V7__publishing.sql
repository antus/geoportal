-- SPDX-FileCopyrightText: 2026 Massimo Antonini
--
-- GP-114, GP-118: a layer published from an uploaded dataset, with the style the person chose and the
-- last health check of the service GeoServer answers for it.

alter table geoserver_layer
    -- The uploaded dataset whose table the layer serves; null for layers defined through the API of
    -- GP-334 or adopted from GeoServer without a dataset.
    add column dataset_id        uuid,
    -- ok or failed, why, and when the catalog asked GeoServer for the layer last.
    add column health            varchar(20),
    add column health_code       varchar(40),
    add column health_reason     varchar(1000),
    add column health_checked_at timestamptz,
    add column published_at      timestamptz;

create index geoserver_layer_dataset on geoserver_layer (dataset_id);

alter table geoserver_style
    -- The style of Geoportal (JSON) the SLD was translated from, when the catalog translated it.
    add column source text;

-- Until GP-114 the loading of GP-937 defined a layer for each loaded vector, named as its table: those
-- layers are the publications of their datasets from now on.
update geoserver_layer l
   set dataset_id = u.id, published_at = l.updated_at
  from uploaded_data u
 where u.organization_id = l.organization_id
   and u.technical_name = l.table_name
   and u.table_name is not null
   and not l.retired;

update uploaded_data u
   set state = 'published'
 where u.state = 'loaded'
   and exists (select 1 from geoserver_layer l where l.dataset_id = u.id and not l.retired);
