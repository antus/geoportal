<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0009. RNDT metadata and a CSW catalog with pycsw

- Status: accepted
- Date: 2026-10-09
- Deciders: Massimo Antonini

## Context and problem statement

Public bodies must publish the metadata of their geodata in the national repertory (RNDT); tenders
ask for that, not for GeoNode.

## Decision

- The RNDT metadata editor lives in the data sheet of Geoportal; it fills what can be derived from
  the data (extent, reference system, format, dates) and asks only the rest. The values of the body
  (iPA code, contacts) are set once in the configuration of the organization.
- pycsw alone publishes the records as CSW and OGC API Records.
- Publishing a metadata record does not open the data: whoever finds it still needs the permission.

## Consequences

- The public catalog without account reads the same records.
