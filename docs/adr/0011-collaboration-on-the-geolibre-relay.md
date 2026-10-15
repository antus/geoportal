<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# 0011. Collaboration on the GeoLibre relay with a Geoportal identity

- Status: accepted
- Date: 2026-10-09
- Deciders: Massimo Antonini

## Context and problem statement

Co-editing a map needs presence, cursors and a shared snapshot; GeoLibre's relay provides them
(last write wins, snapshot up to 10 MB), with an identity that can be signed by the host.

## Decision

- Geoportal signs with HMAC the name, colour and mode of each participant only after checking the
  permission: view gives view-only, edit gives co-editing, the commenter role of links gives
  comments.
- The relay snapshot is a working copy: the document is saved in Geoportal, with versions.
- Comments are stored in Geoportal (anchored, with mentions and notifications), even though the
  session carries them. Dashboards and processes use the same protocol.

## Consequences

- Conflict-free editing waits for the CRDT announced for GeoLibre 2; last write wins until then.
- The relay is not clustered: several instances need session affinity per room.
