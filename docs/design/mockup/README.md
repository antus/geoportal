<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# Geoportal mockup

A static, clickable mockup of the pages of Geoportal: the four extensions (catalog, maps,
dashboards, processes), the public catalog, the dialogs that upload and connect data, and the
geodata sections of the configuration. The interface is in Italian, the language of the product.

The pages sit in the frame of Mosaikit, with the theme and the logo of Geoportal, so that they are
seen in context. The generic pages of the frame (overviews of the organization and of the
platform, people, modules, usage, logs, personal data, notifications, sign-in…) and the generic dialogs
(share, invite, roles) are in the
[mockup of Mosaikit](https://github.com/mosaikit/mosaikit/tree/main/docs/design/mockup): here their
sidebar entries are greyed and a click says which Mosaikit screen they are. The body of the sample
data is the fictitious «Comune di Esempio».

## How to open it

Open [`index.html`](index.html) with a double click: no build and no server are needed (it also
works from any static server). The only external resource is a font.

At the top, the review bar, which is not part of the product:

- **Pubblico** opens the public catalog; the four roles are Amministratore, Gestore, Utente and
  Consultazione (a User with the «Consultazione e modifica» profile).
- The **screen id** of what is on screen (click it to copy it).
- **Segna verificata** and **Indice**: the index lists every screen and dialog of the role, with
  its id, and keeps a count of the reviewed ones in this browser.
- **Note**: what the screen is for and what the role sees.

Files: `index.html`, `mockup.css`, `kit.js` (icons, drawn maps, page frames), `data.js` (sample
data), `screens.js` (pages and dialogs of Geoportal), `shell.js` (the frame of Mosaikit, review
bar, events), `img/` (the two Geoportal logos).

## Screen ids

Every screen has a stable id, `geoportal:<screen>`; a tab or a section adds `/<tab>`. Dialogs are
`geoportal:dlg-<name>`. A requirement cites the screens it realizes in its `ui` field, for example
`ui: [geoportal:dataset/rules]`; a requirement that also needs a generic screen cites the Mosaikit
one (`mosaikit:…`). Ids do not change when labels do.

Roles: A administrator, M manager (gestore), U user, C user with the «Consultazione e modifica»
profile; «public» is anyone, without an account.

| Id | Title | Roles | What it shows |
|---|---|---|---|
| `geoportal:home` | Panoramica | U C | What Geoportal adds to the Overview of Mosaikit: tiles of the four apps, work to pick up again, active alerts. |
| `geoportal:catalog/data` | Catalogo · dati | M U C | One catalog for six kinds of data, with where each lives, who sees it, rules, RNDT state; a detail panel. |
| `geoportal:catalog/connections` | Catalogo · connessioni | M U | Connections (databases, streams, processing, catalogs) with their health and the data born from them. |
| `geoportal:dataset/overview` | Scheda del dato · panoramica | M U C | Description, home, licence, reference system, distributions. |
| `geoportal:dataset/metadata` | Scheda del dato · metadati RNDT | M U C | Fields to fill and fields derived by Geoportal, CSW publication, public catalog request. |
| `geoportal:dataset/permissions` | Scheda del dato · permessi | M U C | Five levels (view, download, edit, edit the data, manage) for owner, groups, body, link, public. |
| `geoportal:dataset/rules` | Scheda del dato · regole sul dato | M U C | Rules by area, rows and columns, enforced by GeoFence or by the gateway. |
| `geoportal:dataset/used` | Scheda del dato · dove è usato | M U C | Maps, dashboards and processes that use the data, and where it comes from. |
| `geoportal:dataset/versions` | Scheda del dato · versioni | M U C | Replacements of the data, restored or downloaded. |
| `geoportal:conn/details` | Scheda della connessione · dettagli | M U | Address, credentials (never sent to the browser), health. |
| `geoportal:conn/derived` | Scheda della connessione · dati che ne derivano | M U | The data made from tables, views or topics of the connection. |
| `geoportal:conn/permissions` | Scheda della connessione · permessi | M U | Who may use and who may manage the connection, and its owner. |
| `geoportal:maps` | Mappe | M U C | Map cards with access, who is working on them now, favourites. |
| `geoportal:map-edit` | Editor di mappa | M U | GeoLibre in the frame: layers with their permissions, style, shared session, 2D and 3D. |
| `geoportal:map-view` | Vista di mappa | M U C | The map in view, with comments and the Modifica/Vista switch for whoever may edit. |
| `geoportal:dashboards` | Cruscotti | M U C | Dashboard cards. |
| `geoportal:dash-view` | Cruscotto | M U C | Indicators, charts, embedded map that filters the other widgets, period. |
| `geoportal:dash-edit` | Editor di cruscotto | M U | Widget palette, canvas, what passes between dashboard and map. |
| `geoportal:processes` | Processi | M U C | GeoFlow processes with trigger, last run and nodes. |
| `geoportal:process-view` | Vista di processo | M U C | The read-only graph and the last run. |
| `geoportal:process-edit` | Editor di processo | M U | Node palette (sidecar conversions, GeoLibre models), canvas, node settings. |
| `geoportal:runs` | Esecuzioni | M U C | Runs with outcome and cause, and the log of a failed one. |
| `geoportal:pubcat` | Catalogo pubblico | public | Search, filters, a page per data, map or document with RNDT metadata, downloads and services. |
| `geoportal:config/catalog` | Configurazione · catalogo pubblico | M | CSW and OGC API Records addresses, RNDT values of the body, review before publication. |
| `geoportal:config/keys` | Configurazione · chiavi dei servizi di mappa | A M U C | Keys of outside map services at the level of the role (platform, body, person). |
| `geoportal:config/maps` | Configurazione · mappe | M | Where new maps start, base map, units. |
| `geoportal:dlg-create` | Crea · voci di Geoportal | M U | The entries Geoportal adds to the Create dialog of Mosaikit. |
| `geoportal:dlg-upload` | Carica dati | M U | Upload with layers, steps of publication, who sees it, and a failing upload. |
| `geoportal:dlg-connect` | Nuova connessione | M U | The kinds of source that can be connected. |
| `geoportal:dlg-rule` | Regola sul dato | M U | A rule for a group or a link: area, rows, hidden columns, with a preview. |
| `geoportal:dlg-add-data` | Aggiungi dati | M U | Adding layers to a map from the catalog and other sources. |
| `geoportal:dlg-access` | Chiedi l’accesso | M U C | Asking for access to a restricted data seen in a map. |
| `geoportal:dlg-versions` | Versioni | M U C | Versions of a map or dashboard. |
| `geoportal:dlg-search` | Ricerca · risultati di Geoportal | A M U C | The maps, dashboards, processes and data Geoportal contributes to the search of Mosaikit. |
