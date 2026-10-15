// SPDX-FileCopyrightText: 2026 Massimo Antonini

/**
 * Maps of Geoportal, a mock: the maps of the mockup of layout A, as cards. The texts follow the
 * language of the person (context.locale).
 */

const ITEMS = [
  {
    "title": "Rete di monitoraggio, Valverde",
    "description": "14 stazioni idro-pluviometriche, soglie e aree di allerta sul bacino.",
    "access": "space",
    "layers": 6,
    "updated": "12 min fa",
    "owner": "Elena Ricci",
    "live": true,
    "seed": 1
  },
  {
    "title": "Piano urbano della mobilità",
    "description": "Corsie preferenziali, ciclabili e nodi di interscambio del PUMS.",
    "access": "public",
    "layers": 14,
    "updated": "3 ore fa",
    "owner": "Sara Greco",
    "live": false,
    "seed": 2
  },
  {
    "title": "ZTL e varchi",
    "description": "Zone, fasce orarie, varchi attivi e transiti dell'ultima ora.",
    "access": "groups",
    "layers": 5,
    "updated": "Ieri",
    "owner": "Davide Conti",
    "live": false,
    "seed": 3
  },
  {
    "title": "Isocrone TPL, 30 minuti",
    "description": "Raggiungibilita' dei poli sanitari con il trasporto pubblico.",
    "access": "private",
    "layers": 4,
    "updated": "2 giorni fa",
    "owner": "Davide Conti",
    "live": false,
    "seed": 4
  },
  {
    "title": "Rischio idraulico, scenario 200 anni",
    "description": "Aree allagabili, edifici esposti e percorsi di evacuazione.",
    "access": "groups",
    "layers": 9,
    "updated": "5 giorni fa",
    "owner": "Luca Bianchi",
    "live": false,
    "seed": 5
  },
  {
    "title": "Centro storico in 3D",
    "description": "Edifici LOD2, ortofoto 2025 e cantieri attivi.",
    "access": "space",
    "layers": 7,
    "updated": "9 giorni fa",
    "owner": "Elena Ricci",
    "live": false,
    "seed": 6
  }
];

const TEXTS = {
  it: { ...{"title": "Mappe", "subtitle": "Le mappe dello spazio: livelli, stili e chi le può vedere."}, unit: (n) => `${n} livelli`, access: { private: 'Privato', groups: 'Gruppi', space: 'Spazio', public: 'Pubblico' }, live: 'In diretta' },
  en: { ...{"title": "Maps", "subtitle": "The maps of the space: layers, styles and who may see them."}, unit: (n) => `${n} layers`, access: { private: 'Private', groups: 'Groups', space: 'Space', public: 'Public' }, live: 'Live' },
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
    if (customElements.get('maps-app')) {
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
            card.innerHTML = `${thumbnail(item.seed, 'map')}<div class="body"><h2></h2><p class="muted"></p>
              <div class="meta"><span class="pill"></span><span class="count"></span><span class="upd"></span></div></div>`;
            card.querySelector('h2').textContent = item.title;
            card.querySelector('.body > p').textContent = item.description;
            card.querySelector('.pill').textContent = texts.access[item.access] ?? item.access;
            card.querySelector('.count').textContent = texts.unit(item.layers);
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
    customElements.define('maps-app', App);
  },
};

export default extension;
