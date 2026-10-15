// SPDX-FileCopyrightText: 2026 Massimo Antonini

/**
 * Dashboards of Geoportal, a mock: the dashboards of the mockup of layout A, as cards. The texts follow the
 * language of the person (context.locale).
 */

const ITEMS = [
  {
    "title": "Sala operativa traffico",
    "description": "Flussi in diretta, incidenti, varchi e tempi di percorrenza.",
    "access": "groups",
    "widgets": 9,
    "updated": "5 min fa",
    "owner": "Davide Conti",
    "seed": 2
  },
  {
    "title": "Monitoraggio ZTL",
    "description": "Transiti per varco, violazioni e permessi in scadenza.",
    "access": "space",
    "widgets": 7,
    "updated": "Ieri",
    "owner": "Davide Conti",
    "seed": 3
  },
  {
    "title": "Qualità dell'aria",
    "description": "Centraline, superamenti e previsione a 48 ore.",
    "access": "public",
    "widgets": 6,
    "updated": "2 ore fa",
    "owner": "Sara Greco",
    "seed": 1
  },
  {
    "title": "Livelli del Valverde",
    "description": "Sei idrometri, soglie e confronto con la piena del 2008.",
    "access": "groups",
    "widgets": 5,
    "updated": "30 s fa",
    "owner": "Luca Bianchi",
    "seed": 5
  }
];

const TEXTS = {
  it: { ...{"title": "Dashboard", "subtitle": "Le dashboard dello spazio: indicatori, grafici e dati in diretta."}, unit: (n) => `${n} widget`, access: { private: 'Privato', groups: 'Gruppi', space: 'Spazio', public: 'Pubblico' }, live: 'In diretta' },
  en: { ...{"title": "Dashboards", "subtitle": "The dashboards of the space: indicators, charts and live data."}, unit: (n) => `${n} widgets`, access: { private: 'Private', groups: 'Groups', space: 'Space', public: 'Public' }, live: 'Live' },
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

function thumbnail(seed, kind) {
  let n = seed * 9301 + 49297;
  const next = () => ((n = (n * 9301 + 49297) % 233280) / 233280);
  const parts = [];
  if (kind === 'map') {
    parts.push('<rect width="300" height="130" fill="var(--mk-bg)"/>');
    for (let i = 0; i < 5; i++) {
      const y = 15 + next() * 100;
      parts.push(`<path d="M0 ${y.toFixed(0)} C 100 ${(y + 30 * (next() - 0.5)).toFixed(0)}, 200 ${(y + 30 * (next() - 0.5)).toFixed(0)}, 300 ${(y + 20 * (next() - 0.5)).toFixed(0)}" stroke="var(--mk-line)" stroke-width="2" fill="none"/>`);
    }
    for (let i = 0; i < 7; i++) {
      parts.push(`<circle cx="${(20 + next() * 260).toFixed(0)}" cy="${(15 + next() * 100).toFixed(0)}" r="4" fill="var(--mk-accent)"/>`);
    }
  } else {
    parts.push('<rect width="300" height="130" fill="var(--mk-bg)"/>');
    for (let i = 0; i < 3; i++) {
      parts.push(`<rect x="${10 + i * 97}" y="10" width="87" height="40" rx="4" fill="var(--mk-surface)" stroke="var(--mk-line)"/>`);
    }
    let x = 10;
    for (let i = 0; i < 12; i++) {
      const h = 10 + next() * 55;
      parts.push(`<rect x="${x}" y="${(120 - h).toFixed(0)}" width="16" height="${h.toFixed(0)}" fill="var(--mk-accent)" opacity=".75"/>`);
      x += 24;
    }
  }
  return `<svg viewBox="0 0 300 130" preserveAspectRatio="none" aria-hidden="true">${parts.join('')}</svg>`;
}

/** @type {import('@mosaikit/sdk').MosaikitExtension} */
const extension = {
  activate(context) {
    if (customElements.get('dashboards-app')) {
      return;
    }
    class App extends HTMLElement {
      connectedCallback() {
        const texts = TEXTS[context.locale] ?? TEXTS.en;
        const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
        root.innerHTML = `<style>${STYLE}</style>
          <div class="page">
            <div><h1></h1><p class="muted"></p></div>
            <div class="grid" role="list"></div>
          </div>`;
        root.querySelector('h1').textContent = texts.title;
        root.querySelector('p').textContent = texts.subtitle;
        root.querySelector('.grid').replaceChildren(
          ...ITEMS.map((item) => {
            const card = document.createElement('article');
            card.className = 'card';
            card.setAttribute('role', 'listitem');
            card.innerHTML = `${thumbnail(item.seed, 'dashboard')}<div class="body"><h2></h2><p class="muted"></p>
              <div class="meta"><span class="pill"></span><span class="count"></span><span class="upd"></span></div></div>`;
            card.querySelector('h2').textContent = item.title;
            card.querySelector('.body > p').textContent = item.description;
            card.querySelector('.pill').textContent = texts.access[item.access] ?? item.access;
            card.querySelector('.count').textContent = texts.unit(item.widgets);
            card.querySelector('.upd').textContent = `${item.owner} · ${item.updated}`;
            if (item.live) {
              const live = document.createElement('span');
              live.className = 'pill live';
              live.textContent = texts.live;
              card.querySelector('.meta').prepend(live);
            }
            return card;
          }),
        );
      }
    }
    customElements.define('dashboards-app', App);
  },
};

export default extension;
