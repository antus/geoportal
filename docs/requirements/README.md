<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# Requirements

One Markdown file per requirement, `GP-nnn.md`, with a YAML front matter and the description in the
body: the format of the requirements of Mosaikit
([`docs/requirements`](https://github.com/mosaikit/mosaikit/tree/main/docs/requirements)), so that one
plan, in the private repository `antus/master-plan`, phases both
([ADR-0013](../adr/0013-requirements-plan-and-mockups.md)).

## Where a requirement lives

- In the folder of an extension, `extensions/<name>/docs/requirements/GP-nnn.md`, when its acceptance
  is implemented entirely inside that extension (catalog, dashboards, maps, processes).
- Here, `docs/requirements/GP-nnn.md`, when it spans several extensions or concerns the product as a
  whole: distribution and packaging, compliance, branding, cross-cutting security, the pages of the
  console, and the areas that have no extension yet (monitoring, the assistant, BIM). A `moved`
  requirement stays here too: what it asks is carried on in another repository.

The id never changes when a requirement moves between folders; `check.py` reads every
`docs/requirements` folder of the repository (not `node_modules` nor `target`) and refuses an id found
twice. `requirements.json` and the schema stay here, and hold for all of them.

```markdown
---
id: GP-308
title: Data rules limit the area, rows and columns a subject sees
area: data-access
priority: P1
status: proposed
depends: [GP-335, MK-054]
ui: [geoportal:dataset/rules]
decisions: [ADR-0008]
estimate: [6, 9]
legacy: R-308
acceptance:
  - A rule with an area limits WMS GetFeatureInfo and WFS answers of the layer to that area.
  - A hidden column is missing from WFS answers, through the gateway and from QGIS alike.
---

Why it is wanted and what it covers.

## History

- geoportal-legacy: done in v1.4.0, wave 3.
- 2026-10-13: ported to the new architecture; the generic sharing model moved to MK-054.
```

- `requirement.schema.json` is a copy of the schema of Mosaikit: update it from there.
- `requirements.json` gives the prefix `GP`, the areas, and the products Geoportal builds on: `MK`
  (Mosaikit) and `EXT` (the common extensions of mosaikit-ext, ADR-0024).
- `python docs/requirements/check.py` checks the files: schema, name, prefix, area, body,
  dependencies and `moved-to` (also on Mosaikit and mosaikit-ext when they are cloned next to this
  repository, or found through `MOSAIKIT_REQUIREMENTS` and `MOSAIKIT_EXT_REQUIREMENTS`; the CI clones
  both and sets the two variables, and a variable that names no directory is an error), and that every
  requirement marked `done` is cited by a test (`test('GP-308 …')`, `@Tag("GP-308")`).

## Statuses

`proposed` (written, not yet in the plan), `planned` (in a phase of the master plan), `in-progress`,
`done` (its acceptance is verified by tests), `dropped` (no longer wanted, the body says why),
`moved` (carried on by `moved-to`: a generic requirement of Mosaikit, MK, or of the common
extensions of mosaikit-ext, EXT).

## Where they come from

`GP-nnn` keeps the number of `R-nnn` of the tracker of geoportal-legacy, reviewed on 13 October 2026:
what was done there was done in the old code, so every still-wanted requirement is `proposed` here
until it is built again; generic ones moved to Mosaikit; those tied to Mars3D, GeoNode, H2 or the old
code base were dropped. The history of each file says what it was and why it changed. New
requirements continue the numbering. The old tracker stays in `antus/geoportal-legacy` (`notes/backlog`, `notes/confronto`) as the
archive of the analysis and of the comparison with competitors.
