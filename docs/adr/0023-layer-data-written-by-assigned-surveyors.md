<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0023. The data of a layer are written by the surveyors assigned to it

- Status: accepted
- Date: 2026-10-14
- Deciders: Massimo Antonini

## Context and problem statement

ADR-0005 mapped the level `edit-data` of a dataset to WFS-T, and ADR-0021 denied WFS transactions to everybody
until editing came. GP-036 asks for two jobs: an editor, who publishes without touching source data, and a
surveyor, who edits only the layers assigned to them; the API of the catalog and GeoServer must give the same
answer. Mosaikit gives profiles and permissions (MK-056), but tells an extension the permissions of the person
of the request only: it cannot list who holds one, which a GeoFence rule needs.

## Decision

- **A permission and an assignment.** The catalog declares the permission `edit-layer-data`; an organization
  makes two profiles with it: *Editor* without it, *Rilevatore* (surveyor) with it. A layer is assigned to a
  person by who manages its dataset (owner, administrators, level `manage`), in `ext_catalog.layer_assignment`.
  Writing the data of a layer needs both, the permission and the assignment; the level `edit-data` no longer
  gives WFS-T, it governs the file of the dataset.
- **GeoFence.** For each assigned surveyor the rule set has a user rule that allows `TRANSACTION` and
  `LOCKFEATURE` of WFS on that layer, in front of the denial of every write; nobody else writes, administrators
  included. What a surveyor reads still follows the sharing. A surveyor limited by data rules on a layer gets
  no write rule on it, since GeoFence filters writes only through a write filter the catalog does not write
  yet.
- **The permission reaches GeoFence through the requests of the surveyor.** Since Mosaikit cannot say who
  holds a permission, the catalog records at each request of an assigned person whether they hold
  `edit-layer-data`; their write rule exists only once a request has found it, and goes when one finds it
  gone. Assigning and removing write the rules again at once, so a removal applies from the next request,
  without signing out.
- **The API of the catalog** refuses a transaction (`POST editing/layers/{name}/transaction`, forwarded to the
  service of the layer only) without the permission (`permission`) or the assignment (`not-assigned`), and
  refuses replacing or loading again the file of a published dataset without the permission, unless the
  person manages the dataset or has every layer it serves assigned.

## Consequences

- A surveyor newly assigned writes through GeoServer after their first request to the catalog (the page does
  it when it opens, and so does the go-ahead of the data gateway of GP-336, asked as the person); one whose
  profile loses the permission keeps it in GeoServer until their next request.
  A list of the holders of a permission in Mosaikit (a published view of effective permissions) would remove
  both delays: a platform need, reported.
- Assignments name layers: a dataset served by two layers can be edited through the assigned one only, and
  the assignments of a withdrawn layer make no rule until a layer with that name is published again.
