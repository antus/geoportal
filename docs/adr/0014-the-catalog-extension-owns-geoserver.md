<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# 0014. The catalog extension owns GeoServer and GeoFence

- Status: accepted
- Date: 2026-10-13
- Deciders: Massimo Antonini

## Context and problem statement

GeoServer, its workspaces and the GeoFence rules are Geoportal's (ADR-0004, ADR-0005), but nothing said
which extension governs them. Maps, dashboards and processes all read geodata; if each talked to
GeoServer by itself there would be several authors of the same workspaces and rules, which is the
divergence ADR-0005 forbids.

## Decision

- The **catalog extension** is the only one that talks to GeoServer and writes GeoFence rules: it
  loads data into PostGIS and GeoServer, governs workspaces, stores, layers and styles, generates the
  rule set of each organization from the grants of Mosaikit and the data rules, and answers the
  `auth_request` of the gateway.
- The other extensions (maps, dashboards, processes) ask the catalog, through its API and its
  extension points, for the addresses of the data a person may use; they never hold the credentials
  of GeoServer.
- **Non-geographic files** of the catalog (reports, acts, scanned documents) are stored in the file
  service of Mosaikit (MK-041), not in GeoServer; the catalog registers them as resources (MK-054),
  so that they are shared with the model of Mosaikit (MK-055) and downloaded through links signed
  after the check (MK-093). GeoFence does not apply to them: it protects only what GeoServer serves.

## Consequences

- One extension to install for the data, and one place where a permission becomes a GeoFence rule.
- The catalog becomes the largest extension; its API is the contract the others depend on, and is
  versioned as such.
- A document and a dataset are shared in the same dialog with the same subjects and levels, but are
  enforced by different executors: the file service for documents, GeoFence and the gateway for
  geodata.
