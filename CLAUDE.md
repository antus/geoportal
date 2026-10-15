# CLAUDE.md

Guidance for Claude Code in this repository. Talk to the maintainer in Italian; write code,
comments, commits and documentation in English, and the user interface in Italian through i18n.

## What this is

Geoportal is a solution built on [Mosaikit](https://github.com/mosaikit/mosaikit): the extensions
of Geoportal, its branding, the distributions that ship them with the geospatial stack, and the
tracker of the product. Mosaikit is the platform (kernel, shell, SDK);
[mosaikit-ext](https://github.com/mosaikit/mosaikit-ext) holds the common extensions Geoportal
composes (`ext-catalog` file manager, `ext-catalog-connection` and its generic connectors,
`ext-dashboards`, `ext-processes`); everything geospatial and everything that is Geoportal lives
here (ADR-0024).

| Extension | Where | What |
|---|---|---|
| `ext-maps` | here | the maps (GeoLibre) |
| `ext-catalog-geodata` | here | geodata of the catalog: PostGIS/COG loading, GeoServer/GeoFence, publishing, data rules, gateway, OGC proxy, RNDT |
| `ext-dashboards-maps` | here | the map widget of the dashboards |
| `ext-processes-geodata` | here | the geospatial process nodes |
| `ext-catalog-connection-<ogc\|csw\|arcgis\|tiles\|stac>` | here | the geospatial connectors |
| `ext-catalog`, `ext-catalog-connection[-<source>]`, `ext-dashboards`, `ext-processes` | mosaikit-ext | files, connections, dashboards, processes |

Names: folders and artifacts `ext-<extension>[-<sub>]`, manifest ids `dev.geoportal.ext.<extension>[.<sub>]`
(`io.github.mosaikit.ext....` in mosaikit-ext). The geodata of the catalog are `ext-catalog-geodata`
(GP-983: API `catalog-geodata`, schema `ext_catalog_geodata`, app `/app/geodata`; it requires
`ext-catalog` and `ext-catalog-connection`); maps, dashboards and processes are still the extensions of
today. The extensions of mosaikit-ext come from the commit pinned in `mosaikit-ext.json`, built by
`tools/mosaikit-ext.mjs` into `target/mosaikit-ext/packages` for the benches, `npm run dev` and the
distributions, until they are published.

```
pom.xml            aggregator: the extensions, the branding, the distributions
package.json       npm run dev: Mosaikit with every extension and the Geoportal theme
mosaikit-ext.json  the commit of mosaikit-ext whose extensions Geoportal composes
tools/             mosaikit-ext.mjs builds those extensions from that commit (MOSAIKIT_EXT_SOURCE: a clone);
                   its pom.xml is the first module of the aggregator
extensions/        ext-catalog-geodata (Java backend, schema ext_catalog_geodata), maps, dashboards, processes;
                   each with docs/requirements/ for the requirements it implements alone
branding/theme     name, logo and colors of Geoportal (theme extension dev.geoportal.theme)
distribution/      installation, portable, server (Docker Compose), cloud (Helm)
docs/requirements/ GP-nnn.md of the product as a whole (format of Mosaikit), and check.py that checks them all
docs/adr/          architecture decision records
docs/design/       the mockup of the Geoportal pages, in the frame of Mosaikit
```

The previous code base is `antus/geoportal-legacy`, in maintenance only. Features are ported from
it one at a time; nothing of it is copied wholesale.

## Commands

| To | Run | Takes |
|---|---|---|
| work on one extension | in its folder: `npm run dev` (bench until Ctrl+C), `mvn verify` (build and tests) | 25–50 s |
| try the whole product | at the root: `npm install` once, then `npm run dev` | about a minute |
| build everything | `mvn install -DskipTests`, distributions in `target/dist` | about 2 min |
| test everything | `mvn verify` | about 3 min |
| portable archives | `mvn install -DskipTests -Pportable` | downloads Java and PostgreSQL once |
| the server stack | `cd target/dist/geoportal-server && docker compose up -d --build` | first time several minutes |
| check the requirements | `python docs/requirements/check.py` (`pip install pyyaml jsonschema`) | seconds |

Each extension is a project of its own on `io.github.mosaikit:mosaikit-extension-parent`; its tests
run with Playwright on the bench of `@mosaikit/testing`, a real Mosaikit installation on an
embedded PostgreSQL. Test what you changed; the CI runs everything.

## Rules

- **Names.** Never name the employer, companies, customers or tenders in this repository, in
  commits, or in anything published. Geoportal is the product of its owner; write in the first
  person when a text needs an author.
- **Licence.** No licence file and no `SPDX-License-Identifier` until the ownership is settled in
  writing: files carry only `SPDX-FileCopyrightText: 2026 Massimo Antonini`.
- **Commits** are signed off (`git commit -s`, DCO), small, pushed often. Never `git add -A` in a
  tree another session may share; never a bare `git stash`.
- **Mosaikit is public.** When Geoportal needs something of the platform, it becomes a generic MK
  requirement in Mosaikit (`docs/requirements/MK-xxx.md`), without Geoportal in it.
- **mosaikit-ext is public** (MPL-2.0, `C:\Sviluppo\mosaikit-ext`): what files, connections,
  dashboards and processes need without anything geospatial is an EXT requirement there
  (`ext-<name>/docs/requirements/EXT-nnn.md`, or `docs/requirements/` when it spans extensions), written without Geoportal or any product in it; Geoportal
  contributes its geospatial widgets, nodes and connectors from its own extensions. Pull requests
  to Mosaikit that touch neither `sdk/` nor `.github/` are merged by Claude once the CI is green;
  those touching `sdk/` or `.github/` always wait for the review of the maintainer.
- **Until a Mosaikit change is merged**, the `-latest` snapshot the extensions depend on does not
  have it: test with `MOSAIKIT_INSTALLATION=<mosaikit>/target/dist/mosaikit` and the packed
  `@mosaikit/testing` (`file:` in `package.json`), and put the snapshot URLs back before committing.
- **Documentation** is part of the change: the README of the extension or distribution, and the
  English documentation of Mosaikit when the platform changes.
- **Scratch files** go in `C:\temp\sviluppo`, never in `C:\Sviluppo`. Security settings (antivirus
  exclusions, firewall, credentials) are changed by the maintainer, never by Claude.
- **One end-to-end suite or bench at a time on a PC**: two embedded PostgreSQL and several JVMs
  together have stalled the suites. Stop benches (`npm run dev`) before `mvn verify`.
- **Docker**: the stack of `geoportal-server` is the compose project `geoportal-server` with
  images `geoportal-server/*`. Never `geoportal`: that name belongs to the development stack of
  geoportal-legacy, whose volumes hold real data.
- **Verify in a browser**, with Playwright, what people see; a passing API test is not a working
  page.

## Requirements and the plan

- **Requirements** are `GP-nnn.md`, one file each, in the format of the requirements of Mosaikit
  (front matter + body; `docs/requirements/README.md`). One whose acceptance is implemented entirely
  inside one extension lives in `extensions/<name>/docs/requirements/`; one that spans several
  extensions or concerns the product as a whole (distribution, packaging, compliance, branding,
  cross-cutting security) stays in `docs/requirements/`. `python docs/requirements/check.py` checks
  every `docs/requirements` folder of the repository. Generic needs are MK requirements in Mosaikit or EXT requirements in mosaikit-ext,
  cited in `depends` and checked against the clones next to this one. The generic requirements of
  catalog, dashboards and processes moved to mosaikit-ext on 14 October 2026: their GP files are
  `moved` with `moved-to: EXT-nnn`.
- **The plan** that phases Mosaikit and Geoportal is the private repository `antus/master-plan`,
  cloned next to this one: `python tools/plan.py` there checks that plan and requirements agree.
- **Opening** a requirement: before writing code its status goes to `in-progress` (and a line in its
  `## History`: since when, which way), and the plan check is run.
- **Closing** it: status `done` when the tests tagged with its id (`GP-nnn` in the title of a test,
  `@Tag("GP-nnn")`) pass; a line in its History; the check of this repository and of the plan pass.
- **Decisions** are ADRs in `docs/adr/`; requirements cite them in `decisions`.
- **Screens** are those of `docs/design/mockup/`, cited in `ui` as `geoportal:<screen>`.
- The old tracker (analysis, comparison with competitors) is archived in `antus/geoportal-legacy`,
  `notes/backlog` and `notes/confronto`: read it for context, do not plan from it.

## Two PCs

The development PC (more memory, a real GPU) runs the builds, the Docker stack, the suites and the
sessions that write code, from `C:\Sviluppo`; `workstation/setup-workstation.ps1` of
`antus/master-plan` prepares it.
The other one reviews, writes documents and works on geoportal-legacy, or opens the development PC
with VS Code Remote - SSH. The same branch is never worked on from both: GitHub is where they meet.
