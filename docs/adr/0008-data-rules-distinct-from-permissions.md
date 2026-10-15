<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0008. Data rules are distinct from permissions

- Status: accepted
- Date: 2026-10-09
- Deciders: Massimo Antonini

## Context and problem statement

A permission says whether a subject reaches a resource; organizations also need to say how much of
a dataset a subject sees.

## Decision

- A **data rule** is its own object: an area, a row filter (CQL) and hidden columns, for a subject
  on a dataset.
- On data in GeoServer it becomes a GeoFence LIMIT rule; on streams and databases the gateway
  applies it.
- It is checked on what the engine returns (WMS GetFeatureInfo, WFS answers), from the browser and
  from QGIS alike.

## Consequences

- Rules are composed and previewed "as that subject" before saving, and their application is
  audited as a fact (rule, subject, how much it removed), not row by row.
