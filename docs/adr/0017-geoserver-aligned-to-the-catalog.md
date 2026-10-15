<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0017. GeoServer is aligned to the catalog, one space per organization

- Status: accepted
- Date: 2026-10-13
- Deciders: Massimo Antonini

## Context and problem statement

ADR-0014 makes the catalog the only author of GeoServer, but not how it writes: object by object as
people act, or by comparing what GeoServer holds with what the catalog holds. GeoServer can also be
changed by hand (its administration pages, its REST API), and objects can be left half-made by a
failed call. Names matter too: GeoFence rules (GP-335) and the gateway (GP-336) find the organization
from the workspace, and a slug can be renamed.

## Decision

- **One space per organization**, named from its identifier, never from its slug:
  `org_<32 hexadecimal digits>` is the GeoServer workspace, the schema of the database `geodata`
  and, with the namespace `urn:geoportal:organization:<id>`, the reverse map from a workspace to its
  organization (`OrganizationSpace`). Each workspace has one PostGIS store, `data`, on that schema.
- **The catalog keeps the definition** of the layers and styles it publishes (`ext_catalog`), and an
  **alignment** brings GeoServer to it: it reads every object first and writes only what differs, so
  a second run changes nothing and an interrupted one converges at the next. What was changed by hand
  in an object of the catalog is brought back; what the catalog withdrew is deleted.
- **Drift is reported, never deleted**: anything in a workspace the catalog does not know (another
  store, a layer or style made by hand) is listed in the answer of the alignment and in the audit
  log, and left in place, so that a mistake of the catalog cannot destroy data someone published.
- **Styles are stored as written** (`raw=true` after GeoServer has parsed them): GeoServer re-encodes
  an SLD with names of its own, which would make every comparison differ.
- **Credentials** of the GeoServer administrator and of the store login are read at each alignment
  from the credentials of Mosaikit (`Secrets`), never from configuration files; the settings of the
  installation hold only addresses.

## Consequences

- The alignment is the one entry point that GP-335 (rules) and GP-114 (publishing) extend: the rule
  set of an organization is rewritten the same way, and publishing is a definition followed by an
  alignment.
- Mosaikit reads credentials only in the organization of a request, so each organization stores the
  two credentials, and an alignment runs in a request of the organization (no background run, no
  workspace at the instant an organization is created). Installation-wide credentials and
  organization events for extension backends (MK-085) remove both limits.
