<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0013. Requirements, master plan and mockups

- Status: accepted
- Date: 2026-10-13
- Deciders: Massimo Antonini

## Context and problem statement

The old tracker kept the state of requirements in a TSV and their definition in a 3,500-line
Python file; the decision document kept a phase plan; the mockup kept the interface. Mosaikit keeps
its own requirements. Nothing related them, and the tracker's *done* referred to the old code.

## Decision

- Requirements are one Markdown file each, `docs/requirements/GP-nnn.md`, with the YAML front matter of
  the Mosaikit format (`requirement.schema.json`, copied from Mosaikit); `GP-nnn` keeps the number of
  the old `R-nnn`. `python docs/requirements/check.py` checks them.
- The plan that phases Mosaikit, Geoportal and future products lives in the private repository
  `antus/master-plan`, which cites requirements by id and reads their status.
- The mockup of Geoportal (`docs/design/mockup/`) holds only its pages, in the frame of Mosaikit, whose
  generic pages are in the mockup of Mosaikit; requirements cite screens in their `ui` field.
- The old tracker stays in `antus/geoportal-legacy` (`notes/backlog`, `notes/confronto`) as the archive of the analysis and the comparison with
  competitors.

## Consequences

- Two sessions never edit the same file to change two requirements.
- A requirement is done when the tests tagged with its id pass.
