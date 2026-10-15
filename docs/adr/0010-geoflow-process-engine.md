<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# 0010. GeoFlow stays the process engine

- Status: accepted
- Date: 2026-10-09
- Deciders: Massimo Antonini

## Context and problem statement

GeoLibre has a Model Builder: a linear graph that runs in the browser, without parameters,
conditions, loops, scheduling, events, streams or OGC services.

## Decision

- GeoFlow (LiteGraph editor, server-side DAG executor with parameters, branches, schedules and
  events, streams, AI, WPS and OGC API Processes) stays the process engine.
- The Model Builder stays inside the map editor for interactive analysis; "promote to process"
  imports a `.model.json` into GeoFlow.
- Heavy processing (GDAL, DuckDB, Sedona, SAM) moves to the GeoLibre sidecar, behind Geoportal,
  as GeoFlow nodes, after the first release.

## Consequences

- The process engine is rebuilt as the processes extension of Geoportal on Mosaikit.
