<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0012. Packagings built on the Mosaikit distributions

- Status: accepted
- Date: 2026-10-12
- Deciders: Massimo Antonini

## Context and problem statement

Geoportal ships as portable, server and cloud packagings with the same core. Mosaikit publishes its
own installation, portable builder, Docker Compose and Helm chart as Maven artifacts.

## Decision

- Each Geoportal packaging is the Mosaikit one of the same kind with the Geoportal extensions,
  signed by a key of the build, and the Geoportal theme: built by `mvn install` in `target/dist`.
- **Server**: Docker Compose with PostGIS, GeoServer (GeoFence on PostgreSQL, Importer, AuthKey) and
  Keycloak, compose project `geoportal-server`. **Cloud**: the Helm chart of Mosaikit as a subchart,
  with GeoServer. **Portable**: the Mosaikit portable builder.
- Single tenant is a profile, not another code base.

## Consequences

- The portable packaging lacks GeoServer and PostGIS today (the embedded PostgreSQL of Mosaikit has
  no PostGIS): closing that gap is a requirement of its own.
- Nginx, pycsw, object storage, the relay and the sidecar join the server packaging as the
  requirements that need them are built.
