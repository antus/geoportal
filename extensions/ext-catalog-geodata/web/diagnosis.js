// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * Why a person cannot see the data of a layer (GP-339): the manager of the dataset chooses a person and a
 * published layer, and the page says what GeoServer answers them on the map (WMS) and on the data (WFS, or
 * WCS for a raster), the grant that is missing or the data rules that hide the data, each with what it hides
 * by itself. The answer is read from the rules GeoFence holds (GET /api/v1/ext/catalog-geodata/access-diagnosis).
 * The person and the layer stay in the address of the page (MK-099), so that a copied address gives the same
 * diagnosis. The texts follow the language of the person.
 */

const API = '/api/v1/ext/catalog-geodata/access-diagnosis';

const TEXTS = {
  it: {
    title: 'Perché non vede i dati',
    hint: 'Scegli una persona e un livello pubblicato: la pagina dice che cosa le risponde GeoServer, sulla mappa e sui dati, e perché.',
    person: 'Persona (e-mail)',
    layer: 'Livello',
    check: 'Verifica',
    checking: 'Verifica in corso…',
    noLayers: 'Non gestisci alcun livello pubblicato: la verifica è per chi gestisce il dato.',
    loadFailed: (s) => `I livelli non si possono leggere (${s}).`,
    failed: (s) => `La verifica non si è potuta fare (${s}).`,
    verdicts: {
      'not-member': (p) => `${p} non è un membro attivo dell’organizzazione: GeoServer non gli dà nulla.`,
      'no-grant': (p) => `Manca il permesso: nessuna condivisione del dato raggiunge ${p}.`,
      'view-only': (p) => `${p} vede la mappa ma non i dati: il suo livello è Vedere.`,
      'rules-hide-all': (p) => `Le regole sui dati nascondono a ${p} tutti gli elementi.`,
      'rules-limit': (p) => `${p} riceve i dati, limitati dalle regole sui dati.`,
      full: (p) => `${p} riceve tutto il livello.`,
    },
    missing: {
      view: 'Per vedere la mappa serve il livello Vedere: condividi il dato con la persona o con un suo gruppo.',
      read: 'Per ricevere gli elementi da WFS serve il livello Leggere e scaricare: condividi il dato con la persona o con un suo gruppo a quel livello.',
    },
    services: 'Che cosa risponde GeoServer',
    service: { WMS: 'Mappa (WMS)', WFS: 'Dati (WFS)', WCS: 'Dati (WCS)' },
    allowed: 'sì',
    denied: 'no',
    causes: {
      grant: 'per una condivisione del dato',
      administrator: 'come amministratore dell’organizzazione',
      'data-rules': 'con i limiti delle regole sui dati',
      'data-rules-deny': 'negato dalle regole sui dati',
      'no-grant': 'nessun permesso',
      other: 'negato',
    },
    level: 'Livello sul dato',
    levels: { view: 'Vedere', read: 'Leggere e scaricare', edit: 'Modificare', 'edit-data': 'Modificare i dati', manage: 'Gestire' },
    noLevel: 'nessuno',
    administrator: 'Amministratore dell’organizzazione',
    features: 'Elementi ricevuti',
    of: (n, total) => `${n} di ${total}`,
    columns: 'Colonne ricevute',
    without: (c) => ` · senza ${c}`,
    rules: 'Regole sui dati che valgono per la persona',
    noRules: 'Nessuna regola sui dati vale per la persona su questo livello.',
    forPerson: 'Per la persona',
    forTeam: (n) => `Per il gruppo ${n}`,
    area: 'area limitata',
    rows: (c) => `righe: ${c}`,
    hidden: (c) => `colonne nascoste: ${c}`,
    alone: (n, total) => `da sola lascia ${n} elementi di ${total}`,
    hides: 'Nasconde dati',
    notInForce: 'Non si applica: la persona non raggiunge il livello con un permesso.',
    geofence: {
      failed: 'Attenzione: le ultime regole non si sono potute scrivere in GeoFence; vedi la riconciliazione.',
      mismatch: 'Attenzione: GeoFence ha regole diverse da quelle generate; vedi la riconciliazione.',
    },
    errors: {
      'credential-missing': 'Manca una credenziale del catalogo (geoserver, geodata o published-views): chiedi a un amministratore.',
    },
  },
  en: {
    title: 'Why they cannot see the data',
    hint: 'Choose a person and a published layer: the page says what GeoServer answers them, on the map and on the data, and why.',
    person: 'Person (e-mail)',
    layer: 'Layer',
    check: 'Check',
    checking: 'Checking…',
    noLayers: 'You manage no published layer: the check is for who manages the data.',
    loadFailed: (s) => `The layers cannot be read (${s}).`,
    failed: (s) => `The check could not be made (${s}).`,
    verdicts: {
      'not-member': (p) => `${p} is not an active member of the organization: GeoServer gives them nothing.`,
      'no-grant': (p) => `A permission is missing: no share of the data reaches ${p}.`,
      'view-only': (p) => `${p} sees the map and not the data: their level is View.`,
      'rules-hide-all': (p) => `Data rules hide every feature from ${p}.`,
      'rules-limit': (p) => `${p} receives the data, limited by data rules.`,
      full: (p) => `${p} receives the whole layer.`,
    },
    missing: {
      view: 'The map needs the level View: share the data with the person or one of their teams.',
      read: 'The features from WFS need the level Read and download: share the data at that level with the person or one of their teams.',
    },
    services: 'What GeoServer answers',
    service: { WMS: 'Map (WMS)', WFS: 'Data (WFS)', WCS: 'Data (WCS)' },
    allowed: 'yes',
    denied: 'no',
    causes: {
      grant: 'through a share of the data',
      administrator: 'as an administrator of the organization',
      'data-rules': 'within the limits of data rules',
      'data-rules-deny': 'denied by data rules',
      'no-grant': 'no permission',
      other: 'denied',
    },
    level: 'Level on the data',
    levels: { view: 'View', read: 'Read and download', edit: 'Edit', 'edit-data': 'Edit the data', manage: 'Manage' },
    noLevel: 'none',
    administrator: 'Administrator of the organization',
    features: 'Features received',
    of: (n, total) => `${n} of ${total}`,
    columns: 'Columns received',
    without: (c) => ` · without ${c}`,
    rules: 'Data rules that apply to the person',
    noRules: 'No data rule applies to the person on this layer.',
    forPerson: 'For the person',
    forTeam: (n) => `For the team ${n}`,
    area: 'limited area',
    rows: (c) => `rows: ${c}`,
    hidden: (c) => `hidden columns: ${c}`,
    alone: (n, total) => `by itself it leaves ${n} features of ${total}`,
    hides: 'Hides data',
    notInForce: 'Not applied: the person does not reach the layer through a permission.',
    geofence: {
      failed: 'Warning: the last rules could not be written into GeoFence; see the reconciliation.',
      mismatch: 'Warning: GeoFence holds other rules than those generated; see the reconciliation.',
    },
    errors: {
      'credential-missing': 'A credential of the catalog is missing (geoserver, geodata or published-views): ask an administrator.',
    },
  },
};

const STYLE = `
  :host { display: block; }
  [hidden] { display: none !important; }
  .diag { display: flex; flex-direction: column; gap: 14px; max-width: 920px; }
  h2 { margin: 0; font-size: 20px; }
  h3 { margin: 0; font-size: 15px; }
  .muted { color: var(--mk-muted); }
  form { display: flex; flex-wrap: wrap; gap: 10px; align-items: flex-end; }
  label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--mk-muted); }
  input, select { font: inherit; padding: 7px 9px; border: 1px solid var(--mk-line); border-radius: var(--mk-radius);
    background: var(--mk-bg); color: var(--mk-fg); min-width: 240px; }
  button { font: inherit; padding: 7px 14px; border-radius: var(--mk-radius); border: 1px solid var(--mk-accent);
    background: var(--mk-accent); color: var(--mk-on-accent, #fff); cursor: pointer; }
  .result { display: flex; flex-direction: column; gap: 12px; }
  .verdict { font-size: 17px; font-weight: 600; margin: 0; }
  .card { border: 1px solid var(--mk-line); border-radius: var(--mk-radius); background: var(--mk-surface); padding: 12px 14px;
    display: flex; flex-direction: column; gap: 8px; }
  dl { display: grid; grid-template-columns: max-content 1fr; gap: 6px 14px; margin: 0; }
  dt { color: var(--mk-muted); font-size: 13px; }
  dd { margin: 0; }
  ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
  li { border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 8px 10px; }
  li.hides { border-color: var(--mk-accent); }
  .tag { font-size: 12px; padding: 1px 8px; border-radius: 999px; border: 1px solid var(--mk-accent); color: var(--mk-accent);
    margin-left: 6px; }
  .warn { color: var(--mk-danger, #c0392b); }
`;

const language = (context) => (context.locale ?? 'en').split('-')[0];

/** The person and the layer kept in the state of the app: `/accesso?layer=...&person=...`. */
export const DIAGNOSIS_STATE = '/accesso';

export function stateOf(layer, person) {
  const query = new URLSearchParams();
  if (layer) {
    query.set('layer', layer);
  }
  if (person) {
    query.set('person', person);
  }
  const text = query.toString();
  return text ? `${DIAGNOSIS_STATE}?${text}` : DIAGNOSIS_STATE;
}

export function choiceOf(state) {
  const [path, query = ''] = (state ?? '').split('?');
  if (path !== DIAGNOSIS_STATE) {
    return undefined;
  }
  const q = new URLSearchParams(query);
  return { layer: q.get('layer') ?? '', person: q.get('person') ?? '' };
}

export function defineDiagnosis(context) {
  if (customElements.get('geodata-access-diagnosis')) {
    return;
  }
  class AccessDiagnosis extends HTMLElement {
    connectedCallback() {
      const t = TEXTS[language(context)] ?? TEXTS.en;
      const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
      root.innerHTML = `<style>${STYLE}</style><section class="diag">
        <h2></h2><p class="muted hint"></p>
        <form><label><span class="l-person"></span><input name="person" type="email" list="people" autocomplete="off" required></label>
          <datalist id="people"></datalist>
          <label><span class="l-layer"></span><select name="layer" required></select></label>
          <button type="submit"></button></form>
        <p class="status muted" role="status" aria-live="polite"></p>
        <div class="result"></div></section>`;
      const q = (s) => root.querySelector(s);
      q('h2').textContent = t.title;
      q('.hint').textContent = t.hint;
      q('.l-person').textContent = t.person;
      q('.l-layer').textContent = t.layer;
      q('button').textContent = t.check;
      q('section').setAttribute('aria-label', t.title);
      q('form').addEventListener('submit', (event) => {
        event.preventDefault();
        const layer = q('select').value;
        const person = q('input').value.trim();
        context.address?.write(stateOf(layer, person));
        void this.diagnose(layer, person);
      });
      void this.load();
    }

    /** Called by the app when its state changes, such as with the back button. */
    choose(choice) {
      if (!choice || !this.shadowRoot || !this.loaded) {
        this.pending = choice;
        return;
      }
      const q = (s) => this.shadowRoot.querySelector(s);
      q('input').value = choice.person;
      if (choice.layer) {
        q('select').value = choice.layer;
      }
      if (choice.layer && choice.person) {
        void this.diagnose(choice.layer, choice.person);
      }
    }

    async load() {
      const t = TEXTS[language(context)] ?? TEXTS.en;
      const q = (s) => this.shadowRoot.querySelector(s);
      const response = await context.fetch(`${API}/choices`);
      if (!response.ok) {
        const problem = await response.json().catch(() => ({}));
        q('.status').textContent = t.errors[problem.code] ?? t.loadFailed(response.status);
        return;
      }
      const choices = await response.json();
      if (choices.layers.length === 0) {
        q('.status').textContent = t.noLayers;
        q('form').hidden = true;
        return;
      }
      q('select').replaceChildren(...choices.layers.map((l) => {
        const o = document.createElement('option');
        o.value = l.name;
        o.textContent = l.title && l.title !== l.name ? `${l.title} (${l.name})` : l.name;
        return o;
      }));
      q('datalist').replaceChildren(...choices.people.map((p) => {
        const o = document.createElement('option');
        o.value = p.email;
        o.textContent = p.name;
        return o;
      }));
      this.loaded = true;
      const pending = this.pending ?? choiceOf(context.address?.read());
      this.pending = undefined;
      this.choose(pending);
    }

    async diagnose(layer, person) {
      const t = TEXTS[language(context)] ?? TEXTS.en;
      const q = (s) => this.shadowRoot.querySelector(s);
      q('.status').textContent = t.checking;
      q('.result').replaceChildren();
      const response = await context.fetch(`${API}?${new URLSearchParams({ layer, person })}`);
      if (!response.ok) {
        const problem = await response.json().catch(() => ({}));
        q('.status').textContent = [t.errors[problem.code] ?? t.failed(response.status), problem.detail].filter(Boolean).join(' ');
        return;
      }
      q('.status').textContent = '';
      this.render(await response.json(), t);
    }

    render(d, t) {
      const result = this.shadowRoot.querySelector('.result');
      const el = (tag, text, cls) => {
        const e = document.createElement(tag);
        if (text !== undefined) {
          e.textContent = text;
        }
        if (cls) {
          e.className = cls;
        }
        return e;
      };
      const parts = [];
      const verdict = el('p', (t.verdicts[d.verdict] ?? t.verdicts.full)(d.person), 'verdict');
      verdict.dataset.verdict = d.verdict;
      parts.push(verdict);
      if (d.missing) {
        parts.push(el('p', t.missing[d.missing]));
      }
      if (d.geofence === 'failed' || d.geofence === 'mismatch') {
        parts.push(el('p', t.geofence[d.geofence], 'warn'));
      }

      const services = el('section', undefined, 'card');
      services.setAttribute('aria-label', t.services);
      services.append(el('h3', t.services));
      const dl = el('dl');
      const pair = (label, value) => dl.append(el('dt', label), el('dd', value));
      for (const s of d.services) {
        pair(t.service[s.service] ?? s.service, `${s.allowed ? t.allowed : t.denied} · ${t.causes[s.cause] ?? t.causes.other}`);
      }
      pair(t.level, d.level ? t.levels[d.level] ?? d.level : t.noLevel);
      if (d.administrator) {
        pair(t.administrator, t.allowed);
      }
      if (d.answer) {
        pair(t.features, t.of(d.answer.matched, d.answer.total));
        pair(t.columns, t.of(d.answer.visible.length, d.answer.columns.length)
          + (d.answer.hidden.length ? t.without(d.answer.hidden.join(', ')) : ''));
      }
      services.append(dl);
      parts.push(services);

      const rules = el('section', undefined, 'card');
      rules.setAttribute('aria-label', t.rules);
      rules.append(el('h3', t.rules));
      if (d.rules.length === 0) {
        rules.append(el('p', t.noRules, 'muted'));
      } else {
        const list = el('ul');
        for (const r of d.rules) {
          const li = el('li', undefined, r.hides ? 'hides' : '');
          const who = el('b', r.via === 'team' ? t.forTeam(r.subject) : t.forPerson);
          li.append(who);
          if (r.hides) {
            li.append(el('span', t.hides, 'tag'));
          }
          const what = [r.area ? t.area : '', r.cql ? t.rows(r.cql) : '', r.hidden.length ? t.hidden(r.hidden.join(', ')) : '']
            .filter(Boolean).join(' · ');
          li.append(el('div', what, 'muted'));
          if (r.description) {
            li.append(el('div', r.description, 'muted'));
          }
          if (r.alone !== null && r.alone !== undefined && d.answer) {
            li.append(el('div', t.alone(r.alone, d.answer.total)));
          }
          const inForce = d.services.some((s) => s.cause === 'data-rules' || s.cause === 'data-rules-deny');
          if (!inForce) {
            li.append(el('div', t.notInForce, 'muted'));
          }
          list.append(li);
        }
        rules.append(list);
      }
      parts.push(rules);
      result.replaceChildren(...parts);
    }
  }
  customElements.define('geodata-access-diagnosis', AccessDiagnosis);
}
