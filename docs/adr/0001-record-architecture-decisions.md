<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# 0001. Record architecture decisions

- Status: accepted
- Date: 2026-10-13
- Deciders: Massimo Antonini

## Context and problem statement

Geoportal took its architectural decisions in a long decision document (*Platform choices*,
7–9 October 2026) and in the notes of the old repository. A decision buried in a 100 KB page is hard to
find, to cite from a requirement, and to supersede without rewriting history.

## Decision

- Decisions are recorded here, one file per decision, in the format of the architecture decision
  records of Mosaikit (MADR): context, decision, consequences.
- Decisions that are generic (identity, roles, extensions, the shell, distributions) belong to
  Mosaikit and are recorded there; here only what is Geoportal's.
- A decision is changed by a new record that supersedes it, never by editing an accepted one.
- Requirements cite the records that shape them in their `decisions` field.

## Consequences

- The *Platform choices* document stays as the history of how the decisions were reached; these
  records are what is in force.
- The records 0002–0013 restate its decisions in force on 13 October 2026, adapted to Mosaikit.
