<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0005. Geoportal decides, GeoServer and the gateway enforce

- Status: accepted
- Date: 2026-10-09
- Deciders: Massimo Antonini

## Context and problem statement

Data are reached from the browser (GeoLibre, dashboards), from desktop clients (QGIS) and by
processes. GeoLibre cannot authenticate data requests, and its client-side checks are bypassed by a
modified client: permissions cannot live in the map engine.

## Decision

- Geoportal is the only authority on who sees what; it enforces through GeoServer with
  **GeoFence**, through its **gateway** and through object storage that only the gateway reaches.
- GeoFence rules are written only by Geoportal, through its REST API: at every change of a
  permission, group or data rule the whole rule set of the organization is recomputed and written;
  a periodic check rewrites it when it differs. The base rule of an organization's workspace is
  **deny**.
- GeoServer has no users of its own besides the service account Geoportal uses: it receives the
  name of the person from the gateway (header filter) or from AuthKey, and reads the roles from
  Geoportal (JDBC role service on read-only views, REST role service as fallback).
- Sharing levels map to services: view = WMS/WMTS/tiles, download or use = also WFS/WCS read,
  edit data = WFS-T, manage = nothing more on GeoServer.

## Consequences

- The generic model of sharing (subjects, levels, groups, profiles) is Mosaikit's; mapping it to
  GeoFence is Geoportal's.
- A change of group is effective within the cache time of GeoServer, declared and tested.
