<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0002. Geoportal is a solution of Mosaikit extensions

- Status: accepted
- Date: 2026-10-12
- Deciders: Massimo Antonini

## Context and problem statement

The old Geoportal was one application: a Quarkus backend, a frontend of global scripts, its own
accounts, tenants, roles and administration. The plan of 9 October refactored it in place (React,
TypeScript, a shell of modules). A platform for applications made of extensions, Mosaikit, already
provides the generic half of that plan: organizations, identity with Keycloak, extensions with their
own backend and schema, the shell, the marketplace, the distributions.

## Decision

- Geoportal is built as Mosaikit extensions plus branding and distributions, in one repository
  (`antus/geoportal`): `extensions/` (catalog, maps, dashboards, processes, and those to come),
  `branding/`, `distribution/`.
- Everything generic is Mosaikit's and is asked of Mosaikit as a generic requirement (MK); everything
  geospatial or specific to Geoportal is Geoportal's (GP). A Geoportal requirement that needs both is
  split, and depends on the MK part.
- The old code base (`antus/geoportal-legacy`) is not carried over: features are rebuilt one at a
  time on the new architecture, reusing its knowledge, not its files.
- The React refactor of the old frontend (R-329, R-330, R-351) is dropped.

## Consequences

- Nothing of the old product counts as done in the new one: the backlog is reviewed and every
  still-wanted requirement is ported.
- Geoportal moves at the pace of Mosaikit where it needs the platform; the master plan phases the two.
- Each extension builds and tests alone on the bench of Mosaikit, in seconds.
