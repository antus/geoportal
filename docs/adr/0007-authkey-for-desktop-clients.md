<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0007. AuthKey for QGIS and desktop clients

- Status: accepted
- Date: 2026-10-09
- Deciders: Massimo Antonini

## Context and problem statement

Desktop clients such as QGIS go to GeoServer directly and cannot carry the session of the browser.

## Decision

- Desktop clients use a personal key in the URL (GeoServer AuthKey extension), created and revoked
  from the profile of the person ("token for QGIS").
- GeoServer checks the key by asking Geoportal (web service mapper) and applies the same GeoFence
  rules to the same user.

## Consequences

- A revoked key stops working when the cache of GeoServer expires.
- These accesses are, for now, only in the logs of GeoServer, not in the activity log.
