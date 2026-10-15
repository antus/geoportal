<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0029. Layer styles are documents of a JSON grammar, which the catalog keeps and translates

- Status: accepted
- Date: 2026-10-15
- Deciders: Massimo Antonini

## Context and problem statement

GP-009 asks that the style of every layer be a documented, portable JSON document, with the editor a
facade over it; GP-006, GP-973, GP-903 and the GeoLibre translator of GP-340 build on it (ADR-0003
names the grammar `geoportal/style/v1`). Until now the only styles were those of the publishing dialog
of the catalog (GP-114): six values for every geometry, kept as JSON next to the SLD they were
translated into (`geoserver_style.source`). The maps extension has no styles yet. The grammar has two
consumers in two languages: the catalog in Java (checks on save, SLD for GeoServer, a layer repointed
to data of another geometry) and the browser (the editor, and the GeoLibre plugin that draws with
MapLibre).

## Decision

- **One grammar, documented in the repository**: `docs/style/README.md` and its JSON Schema
  `docs/style/geoportal-style-v1.schema.json`, with examples. Blocks `type` (point, line, polygon,
  mixed), `config`, `style` (or `geometries` for mixed), `label`, `legend`; every paint is a constant or
  a value driven by a field (categories, graduated, scaled); a template is the expression of label
  texts. Strict: what the grammar does not know is refused, so that a document means one thing.
- **Two implementations, one set of fixtures.** The catalog checks and translates documents in Java
  (`dev.geoportal.ext.catalog.geodata.style`), the browser in `web/style-grammar.js`, a dependency-free
  module that the GeoLibre plugin bundles as it is. The fixtures of `src/test/resources/style` (invalid
  documents with where and why, changes of type, migrated styles) and the examples of the documentation
  are read by the tests of both, and checked against the schema, so that the three cannot drift. The
  schema is not evaluated at runtime: no validator library is added to the extension, and the
  implementations check what a schema cannot say (breaks in increasing order, one value per class).
- **Renderers are adapters.** SLD for GeoServer in the catalog; MapLibre in the browser (`adapters` of
  `style-grammar.js`), the one GeoLibre needs; another engine is another adapter. A document is drawn
  on the geometry of its layer: a style of another type is changed into it with the rules of the
  change of type, the same the editor uses.
- **The catalog keeps documents.** It checks a style when it is saved (400 `invalid-style`, with the
  JSON pointer of each problem), keeps it in `geoserver_style.source` and gives it back to the editor.
  The migration V14 turns the six values of before into mixed documents that keep each value and give
  the same SLD; the old form is still read and accepted from clients, migrated.
- **The grammar lives with the catalog for now.** The maps extension takes it from there when it
  draws layers (GP-340); if a third extension needs it in Java, it becomes a library of its own.

## Consequences

- A style copies between layers, maps and installations as a file, and the export of a map (GP-903)
  carries documents rather than editor state.
- Two implementations must change together; the shared fixtures make a difference fail in CI.
- The panel of the editor shows the constants of each type and a label; documents with values driven
  by the data are edited as JSON until the editor of GP-973 shows them.
- The layers published before keep their SLD: their documents translate into the same XML, so the
  alignment of GeoServer does not rewrite them.
