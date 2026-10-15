-- SPDX-FileCopyrightText: 2026 Massimo Antonini
-- SPDX-License-Identifier: AGPL-3.0-or-later
--
-- GP-009: the styles of Geoportal that the catalog keeps next to their SLD (geoserver_style.source)
-- become documents of the grammar geoportal/style/v1 (docs/style/README.md). A style of the six values
-- of GP-114 (fill, fillOpacity, stroke, strokeWidth, pointSize, pointShape) becomes a mixed document
-- that keeps each of them and draws each geometry as before: the SLD it translates into is the same
-- (StyleMigrationTest). Anything else stays as it is: a source that is not JSON, or already a document.

do $$
declare
    r record;
    j jsonb;
begin
    for r in select id, source from geoserver_style where source is not null loop
        begin
            j := r.source::jsonb;
        exception when others then
            continue;
        end;
        if jsonb_typeof(j) = 'object' and not j ? 'grammar'
                and j ?& array['fill', 'fillOpacity', 'stroke', 'strokeWidth', 'pointSize', 'pointShape'] then
            update geoserver_style
               set source = jsonb_build_object(
                       'grammar', 'geoportal/style/v1',
                       'type', 'mixed',
                       'geometries', jsonb_build_object(
                           'point', jsonb_build_object(
                               'config', jsonb_build_object('shape', j -> 'pointShape'),
                               'style', jsonb_build_object('fill', j -> 'fill', 'fillOpacity', j -> 'fillOpacity',
                                   'stroke', j -> 'stroke', 'strokeWidth', j -> 'strokeWidth', 'size', j -> 'pointSize')),
                           'line', jsonb_build_object(
                               'config', '{}'::jsonb,
                               'style', jsonb_build_object('stroke', j -> 'stroke', 'strokeOpacity', 1,
                                   'strokeWidth', j -> 'strokeWidth')),
                           'polygon', jsonb_build_object(
                               'config', '{}'::jsonb,
                               'style', jsonb_build_object('fill', j -> 'fill', 'fillOpacity', j -> 'fillOpacity',
                                   'stroke', j -> 'stroke', 'strokeWidth', j -> 'strokeWidth'))))::text
             where id = r.id;
        end if;
    end loop;
end
$$;
