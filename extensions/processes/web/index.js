// SPDX-FileCopyrightText: 2026 Massimo Antonini

/**
 * Processes of Geoportal, a mock: the processes of the mockup of layout A, with what starts them and
 * how their last run ended. The texts follow the language of the person (context.locale).
 */

const ITEMS = [
  {
    "title": "Isocrone TPL, 30 minuti",
    "trigger": "Ogni notte alle 02:00",
    "last": "Riuscito · 02:04",
    "status": "ok",
    "nodes": 7,
    "updated": "Ieri"
  },
  {
    "title": "Allerta piena Valverde",
    "trigger": "A ogni lettura sopra soglia",
    "last": "Riuscito · 08:41",
    "status": "ok",
    "nodes": 9,
    "updated": "3 giorni fa"
  },
  {
    "title": "Catasto in GeoPackage",
    "trigger": "A mano",
    "last": "Fallito · ieri 16:12",
    "status": "bad",
    "nodes": 5,
    "updated": "Ieri"
  },
  {
    "title": "NDVI dei parchi",
    "trigger": "Ogni lunedi'",
    "last": "In corso · 61%",
    "status": "run",
    "nodes": 6,
    "updated": "Oggi"
  },
  {
    "title": "Report settimanale ZTL",
    "trigger": "Ogni venerdi' alle 18:00",
    "last": "Riuscito · ven 18:02",
    "status": "ok",
    "nodes": 8,
    "updated": "1 settimana fa"
  }
];

const TEXTS = {
  it: {
    title: 'Processi',
    subtitle: 'Le elaborazioni dello spazio: quando partono e com\'e\' andata l\'ultima esecuzione.',
    columns: ['Processo', 'Avvio', 'Ultima esecuzione', 'Nodi', 'Modificato'],
    status: { ok: 'Riuscito', bad: 'Fallito', run: 'In corso' },
  },
  en: {
    title: 'Processes',
    subtitle: 'The processes of the space: when they start and how their last run went.',
    columns: ['Process', 'Trigger', 'Last run', 'Nodes', 'Changed'],
    status: { ok: 'Succeeded', bad: 'Failed', run: 'Running' },
  },
};

const STYLE = `
  :host { display: block; }
  .page { padding: 24px 28px; display: flex; flex-direction: column; gap: 16px; }
  h1 { margin: 0; font-size: 26px; }
  .muted { color: var(--mk-muted); }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 16px; }
  .card { background: var(--mk-surface); border: 1px solid var(--mk-line); border-radius: var(--mk-radius);
    overflow: hidden; display: flex; flex-direction: column; }
  .card svg { display: block; width: 100%; height: 130px; }
  .body { padding: 12px 14px; display: flex; flex-direction: column; gap: 6px; }
  .body h2 { margin: 0; font-size: 16px; }
  .meta { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; font-size: 12px; color: var(--mk-muted); }
  .pill { font-size: 12px; padding: 2px 8px; border-radius: 999px; border: 1px solid var(--mk-line); color: var(--mk-fg); }
  .live { border-color: var(--mk-accent); color: var(--mk-accent); }
  table { width: 100%; border-collapse: collapse; background: var(--mk-surface); border: 1px solid var(--mk-line);
    border-radius: var(--mk-radius); overflow: hidden; }
  th { text-align: left; font-size: 11px; text-transform: uppercase; letter-spacing: .06em; color: var(--mk-muted);
    padding: 10px 12px; border-bottom: 1px solid var(--mk-line); }
  td { padding: 10px 12px; border-top: 1px solid var(--mk-line); }
  .dot { display: inline-block; width: 9px; height: 9px; border-radius: 50%; margin-right: 6px; background: var(--mk-muted); }
  .dot.ok { background: #0c8a5f; } .dot.bad { background: #c93a3a; } .dot.run { background: var(--mk-accent); }
`;

/** @type {import('@mosaikit/sdk').MosaikitExtension} */
const extension = {
  activate(context) {
    if (customElements.get('processes-app')) {
      return;
    }
    class App extends HTMLElement {
      connectedCallback() {
        const texts = TEXTS[context.locale] ?? TEXTS.en;
        const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
        root.innerHTML = `<style>${STYLE}</style>
          <div class="page">
            <div><h1></h1><p class="muted"></p></div>
            <table><thead><tr>${texts.columns.map((c) => `<th>${c}</th>`).join('')}</tr></thead><tbody></tbody></table>
          </div>`;
        root.querySelector('h1').textContent = texts.title;
        root.querySelector('p').textContent = texts.subtitle;
        root.querySelector('tbody').replaceChildren(
          ...ITEMS.map((item) => {
            const row = document.createElement('tr');
            row.innerHTML = '<td><b></b></td><td></td><td><span class="dot"></span><span class="last"></span></td><td></td><td class="muted"></td>';
            row.querySelector('b').textContent = item.title;
            row.children[1].textContent = item.trigger;
            row.querySelector('.dot').classList.add(item.status);
            row.querySelector('.dot').setAttribute('title', texts.status[item.status] ?? item.status);
            row.querySelector('.last').textContent = item.last;
            row.children[3].textContent = String(item.nodes);
            row.children[4].textContent = item.updated;
            return row;
          }),
        );
      }
    }
    customElements.define('processes-app', App);
  },
};

export default extension;
