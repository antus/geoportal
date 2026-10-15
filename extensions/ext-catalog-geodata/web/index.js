// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * Geodata of the catalog of Geoportal (GP-983): the datasets of the organization, searchable and filtered by theme, with
 * the details of the one chosen. The data come from the API of the extension; the texts follow the
 * language of the person (context.locale). Under them, the data uploaded to the organization
 * (uploads.js, GP-936), the services published from them on GeoServer (publications.js, GP-114) and
 * the external services with their proxy (services.js, GP-939). The page of a link to a dataset is
 * shared.js (GP-336). A second view of the app says why a person cannot see the data of a layer
 * (diagnosis.js, GP-339), and the home page of the console shows the launcher of the Geoportal apps
 * (launcher.js, GP-339).
 */

import { choiceOf, defineDiagnosis, DIAGNOSIS_STATE } from './diagnosis.js';
import { defineKind } from './kind.js';
import { defineLauncher } from './launcher.js';
import { definePublications } from './publications.js';
import { defineServices } from './services.js';
import { defineShared } from './shared.js';
import { defineUploads } from './uploads.js';

const TEXTS = {
  it: {
    title: 'Dati geografici',
    views: 'Viste dei dati geografici',
    data: 'Dati',
    access: 'Perché non vede i dati',
    subtitle: 'I dati dello spazio: file, servizi, flussi in tempo reale, database ed elaborazioni.',
    search: 'Cerca nei dati geografici',
    themes: 'Temi',
    all: 'Tutti i temi',
    count: (n) => (n === 1 ? '1 dataset' : `${n} dataset`),
    none: 'Nessun dataset corrisponde alla ricerca.',
    error: (s) => `I dati geografici non si possono caricare (${s}).`,
    columns: ['Dataset', 'Tema', 'Accesso', 'Aggiornato'],
    owner: 'Responsabile',
    size: 'Dimensione',
    host: 'Servito da',
    format: 'Formato',
    close: 'Chiudi',
    kinds: { file: 'File', ogc: 'Servizio OGC', stream: 'Tempo reale', db: 'Database', proc: 'Elaborazione', cat: 'Catalogo' },
    access: { private: 'Privato', groups: 'Gruppi', space: 'Spazio', public: 'Pubblico' },
    hosts: { geoserver: 'GeoServer', geoportal: 'Geoportal', remoto: 'Remoto', oggetti: 'Archivio oggetti' },
  },
  en: {
    title: 'Geodata',
    views: 'Views of the geodata',
    data: 'Data',
    access: 'Why they cannot see the data',
    subtitle: 'The data of the space: files, services, real-time streams, databases and processes.',
    search: 'Search the geodata',
    themes: 'Themes',
    all: 'All themes',
    count: (n) => (n === 1 ? '1 dataset' : `${n} datasets`),
    none: 'No dataset matches the search.',
    error: (s) => `The geodata cannot be loaded (${s}).`,
    columns: ['Dataset', 'Theme', 'Access', 'Updated'],
    owner: 'Owner',
    size: 'Size',
    host: 'Served by',
    format: 'Format',
    close: 'Close',
    kinds: { file: 'File', ogc: 'OGC service', stream: 'Real time', db: 'Database', proc: 'Process', cat: 'Catalog' },
    access: { private: 'Private', groups: 'Groups', space: 'Space', public: 'Public' },
    hosts: { geoserver: 'GeoServer', geoportal: 'Geoportal', remoto: 'Remote', oggetti: 'Object storage' },
  },
};

const STYLE = `
  :host { display: block; }
  .page { padding: 24px 28px; display: flex; flex-direction: column; gap: 16px; }
  h1 { margin: 0; font-size: 26px; }
  .muted { color: var(--mk-muted); }
  .bar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
  input[type=search] { flex: 1 1 240px; font: inherit; padding: 8px 10px; border: 1px solid var(--mk-line);
    border-radius: var(--mk-radius); background: var(--mk-bg); color: var(--mk-fg); }
  .chip { font: inherit; font-size: 13px; padding: 4px 12px; border-radius: 999px; border: 1px solid var(--mk-line);
    background: none; color: var(--mk-fg); cursor: pointer; }
  .chip[aria-pressed=true] { border-color: var(--mk-accent); color: var(--mk-accent); }
  .wrap { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; }
  .wrap.open { grid-template-columns: minmax(0, 1fr) minmax(260px, 360px); }
  table { width: 100%; border-collapse: collapse; background: var(--mk-surface); border: 1px solid var(--mk-line);
    border-radius: var(--mk-radius); overflow: hidden; }
  th { text-align: left; font-size: 11px; text-transform: uppercase; letter-spacing: .06em; color: var(--mk-muted);
    padding: 10px 12px; border-bottom: 1px solid var(--mk-line); }
  td { padding: 10px 12px; border-top: 1px solid var(--mk-line); vertical-align: middle; }
  tr.row { cursor: pointer; }
  tr.row:hover td, tr.row[aria-selected=true] td { background: var(--mk-bg); }
  .t { display: flex; flex-direction: column; gap: 2px; }
  .k { font-size: 12px; color: var(--mk-muted); }
  .pill { font-size: 12px; padding: 2px 8px; border-radius: 999px; border: 1px solid var(--mk-line); white-space: nowrap; }
  aside[hidden] { display: none; }
  aside { background: var(--mk-surface); border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 16px;
    display: flex; flex-direction: column; gap: 10px; align-self: start; }
  aside h2 { margin: 0; font-size: 17px; }
  dl { display: grid; grid-template-columns: max-content 1fr; gap: 6px 14px; margin: 0; }
  dt { color: var(--mk-muted); font-size: 13px; }
  dd { margin: 0; }
  nav.views { display: flex; gap: 4px; border-bottom: 1px solid var(--mk-line); }
  nav.views button { font: inherit; border: none; background: none; color: var(--mk-muted); padding: 8px 12px; cursor: pointer;
    border-bottom: 2px solid transparent; margin-bottom: -1px; }
  nav.views button[aria-selected=true] { color: var(--mk-fg); border-bottom-color: var(--mk-accent); }
  .view[hidden] { display: none; }
  button.close { align-self: flex-end; font: inherit; border: 1px solid var(--mk-line); background: none;
    color: var(--mk-fg); border-radius: var(--mk-radius); padding: 4px 10px; cursor: pointer; }
`;

/** @type {import('@mosaikit/sdk').MosaikitExtension} */
const extension = {
  activate(context) {
    // GP-983: the preview and the action Publish of the geodata files of the catalog of mosaikit-ext.
    defineKind(context);
    // GP-339: the launcher of the Geoportal apps, a widget of the home page of the console.
    defineLauncher(context);
    if (customElements.get('catalog-app')) {
      return;
    }
    defineUploads(context);
    // The services published on GeoServer from uploaded data (GP-114, GP-118).
    definePublications(context);
    const t = () => TEXTS[context.locale] ?? TEXTS.en;
    // The external services of the organization and their proxy (GP-939), below the datasets.
    defineServices(context);
    // The page of a link to a dataset, also for people without an account (GP-336).
    defineShared(context);
    // GP-339: why a person cannot see the data of a layer.
    defineDiagnosis(context);

    class Catalog extends HTMLElement {
      connectedCallback() {
        this.datasets = [];
        this.theme = '';
        this.text = '';
        this.chosen = undefined;
        const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
        const texts = t();
        root.innerHTML = `
          <style>${STYLE}</style>
          <div class="page">
            <div><h1>${texts.title}</h1><p class="muted">${texts.subtitle}</p></div>
            <nav class="views" role="tablist" aria-label="${texts.views}">
              <button type="button" role="tab" data-view="data">${texts.data}</button>
              <button type="button" role="tab" data-view="access">${texts.access}</button></nav>
            <div class="view" data-view="access" role="tabpanel" aria-label="${texts.access}" hidden>
              <geodata-access-diagnosis></geodata-access-diagnosis></div>
            <div class="view" data-view="data" role="tabpanel" aria-label="${texts.data}">
            <div class="bar"><input type="search" aria-label="${texts.search}" placeholder="${texts.search}" /></div>
            <div class="bar themes" role="group" aria-label="${texts.themes}"></div>
            <p role="status" aria-live="polite" class="muted"></p>
            <div class="wrap"><table><thead><tr>${texts.columns.map((c) => `<th>${c}</th>`).join('')}</tr></thead>
              <tbody></tbody></table><aside hidden></aside></div>
            <catalog-uploads></catalog-uploads>
            <catalog-publications></catalog-publications>
            <catalog-services></catalog-services>
            </div>
          </div>`;
        this.root = root;
        // GP-339: the view of the access, kept in the address (MK-099): /accesso?layer=…&person=….
        for (const tab of root.querySelectorAll('nav.views button')) {
          tab.addEventListener('click', () => {
            const view = tab.dataset.view;
            context.address?.write(view === 'access' ? DIAGNOSIS_STATE : '');
            this.show(view);
          });
        }
        const follow = (state) => {
          const choice = choiceOf(state);
          this.show(choice ? 'access' : 'data');
          if (choice) {
            root.querySelector('geodata-access-diagnosis').choose(choice);
          }
        };
        this.show(choiceOf(context.address?.read()) ? 'access' : 'data');
        this.stopAddress = context.address?.subscribe(follow);
        root.querySelector('input').addEventListener('input', (event) => {
          this.text = event.target.value.trim().toLowerCase();
          this.render();
        });
        void this.load();
      }

      disconnectedCallback() {
        this.stopAddress?.();
        this.stopAddress = undefined;
      }

      show(view) {
        for (const tab of this.root.querySelectorAll('nav.views button')) {
          tab.setAttribute('aria-selected', String(tab.dataset.view === view));
        }
        for (const panel of this.root.querySelectorAll('.view')) {
          panel.hidden = panel.dataset.view !== view;
        }
      }

      async load() {
        const response = await context.fetch('/api/v1/ext/catalog-geodata/datasets');
        if (!response.ok) {
          this.root.querySelector('[role=status]').textContent = t().error(response.status);
          return;
        }
        this.datasets = await response.json();
        this.render();
      }

      visible() {
        return this.datasets.filter(
          (d) =>
            (!this.theme || d.theme === this.theme) &&
            (!this.text || `${d.title} ${d.theme} ${d.description}`.toLowerCase().includes(this.text)),
        );
      }

      render() {
        const texts = t();
        const themes = [...new Set(this.datasets.map((d) => d.theme))].sort();
        this.root.querySelector('.themes').replaceChildren(
          ...['', ...themes].map((theme) => {
            const chip = document.createElement('button');
            chip.className = 'chip';
            chip.textContent = theme || texts.all;
            chip.setAttribute('aria-pressed', String(theme === this.theme));
            chip.addEventListener('click', () => {
              this.theme = theme;
              this.render();
            });
            return chip;
          }),
        );
        const shown = this.visible();
        this.root.querySelector('[role=status]').textContent =
          shown.length === 0 ? texts.none : texts.count(shown.length);
        this.root.querySelector('tbody').replaceChildren(...shown.map((d) => this.row(d, texts)));
        this.renderDetail();
      }

      row(d, texts) {
        const row = document.createElement('tr');
        row.className = 'row';
        row.tabIndex = 0;
        row.setAttribute('aria-selected', String(d.id === this.chosen?.id));
        row.innerHTML = `
          <td><div class="t"><b></b><span class="k"></span></div></td>
          <td></td><td><span class="pill"></span></td><td class="muted"></td>`;
        row.querySelector('b').textContent = d.title;
        row.querySelector('.k').textContent = `${texts.kinds[d.kind] ?? d.kind} · ${d.format}`;
        row.children[1].textContent = d.theme;
        row.querySelector('.pill').textContent = texts.access[d.access] ?? d.access;
        row.children[3].textContent = d.updated;
        const open = () => {
          this.chosen = d;
          this.render();
        };
        row.addEventListener('click', open);
        row.addEventListener('keydown', (event) => {
          if (event.key === 'Enter') {
            open();
          }
        });
        return row;
      }

      renderDetail() {
        const texts = t();
        const aside = this.root.querySelector('aside');
        aside.hidden = !this.chosen;
        this.root.querySelector('.wrap').classList.toggle('open', Boolean(this.chosen));
        if (!this.chosen) {
          return;
        }
        const d = this.chosen;
        aside.setAttribute('aria-label', d.title);
        aside.innerHTML = `<button class="close">${texts.close}</button><h2></h2><p class="muted"></p><dl></dl>`;
        aside.querySelector('h2').textContent = d.title;
        aside.querySelector('p').textContent = d.description;
        aside.querySelector('dl').replaceChildren(
          ...[
            [texts.format, `${texts.kinds[d.kind] ?? d.kind} · ${d.format}`],
            [texts.host, texts.hosts[d.host] ?? d.host],
            [texts.size, d.size],
            [texts.owner, d.owner],
          ].flatMap(([label, value]) => {
            const dt = document.createElement('dt');
            dt.textContent = label;
            const dd = document.createElement('dd');
            dd.textContent = value;
            return [dt, dd];
          }),
        );
        aside.querySelector('.close').addEventListener('click', () => {
          this.chosen = undefined;
          this.render();
        });
      }
    }

    customElements.define('catalog-app', Catalog);
  },
};

export default extension;
