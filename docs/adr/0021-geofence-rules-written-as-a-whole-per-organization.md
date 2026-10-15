<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0021. GeoFence rules are generated and written as a whole for each organization

- Status: accepted
- Date: 2026-10-14
- Deciders: Massimo Antonini

## Context and problem statement

ADR-0005 makes Geoportal the only author of the GeoFence rules and ADR-0014 gives them to the catalog;
GP-335 asks how grants of Mosaikit and data rules (GP-308) become rules, and how they are kept exact.
The proofs of GP-327 found the traps of GeoFence 2.28.5 (an area written as XML and a column list written
as JSON are dropped answering 201, an insert at a taken priority shifts every rule after it, a second rule
with the same subject and target is refused). Measuring GeoFence again for GP-335 found two more: rules with
the same subject, target, service and request collide whatever their access (an `ALLOW` and a `DENY`),
and GeoWebCache serves a cached tile to anybody unless its data security is on.

## Decision

- **Subjects.** A grant to a person is a user rule with their user name (the address they sign in with,
  read from the published views of Mosaikit, MK-123); a grant to a team is a role rule with the team
  identifier; a grant to the organization is a role rule with the organization identifier (its members
  that are not guests); its administrators hold the whole workspace through the role
  `<organization identifier>:admin`; the public is a rule for everyone. These are the roles the role service
  of GP-336 reads from the same views. The owner of a dataset is a person at the highest level. Links have
  no rule.
- **Levels to services** (ADR-0005): `view` gives WMS and the tiles (`GWC` for GeoFence); `download` and
  above also WFS and WCS. WFS transactions and locks are denied in every workspace until editing comes
  (since ADR-0023, to everybody but the surveyors assigned to a layer).
- **Data rules are user rules.** GeoFence evaluates the rules once per role of a person and joins what the
  roles allow, so a limit on a role would be undone by another role that opens the layer. A person limited
  by data rules gets a block of user rules in front of every role rule: a `LIMIT` with the area, an `ALLOW`
  per service of their level with the row filter and the columns, a `DENY` of the layer. User rules are part
  of every evaluation, so the limit holds whatever grant opens the layer. The limits of several rules are
  composed before writing (GP-308).
- **Order.** Write denials, limited people, administrators, grants, and the base `DENY` of the workspace.
- **Writing.** The rule set of an organization is rewritten as a whole: every rule of its workspace is
  deleted, the set is written in ascending priority in a band of free priorities (the previous one when
  still free, otherwise the first from 1000 in steps of 1000, below the catch-all of the installation), so
  no rule of another organization moves; an area goes as JSON, everything else as XML; the rules are read
  back and compared field by field, areas and columns included. A difference is reported in the
  reconciliation and audited as `geofence.rewritten` with outcome failed; a rule of the workspace that the
  catalog did not write is deleted and reported.
- **When.** After every alignment of GeoServer (publish, republish, repoint, unpublish, reconciliation),
  on request of an administrator, and when the generated set differs from the one last written, checked at
  most every 15 seconds per organization when people list or use the publications. Team changes need no
  rewrite: GeoServer reads the members of a role within its cache.
- **GeoWebCache** runs with its data security on (`gwc-gs.xml`, set by the image at every start).

## Consequences

- A share in the dialog of Mosaikit reaches GeoFence at the next check, within seconds when the catalog
  is in use, because Mosaikit does not notify extensions of grants yet (a platform need); a change of team
  membership reaches it within the authentication cache of GeoServer (ADR-0016), except for people limited
  by data rules through a team, whose user rules are rewritten at the next check.
- The catalog needs the credential `published-views` (a login with `mk_<database>_reader`) to name people;
  without it, grants to people are not written and the report says so: the default stays deny.
- People limited by data rules cannot use tiles of GeoWebCache (it refuses layers with limits); they get
  the map from WMS, with the limits.
