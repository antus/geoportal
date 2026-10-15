// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * External services of the catalog (GP-939): the OGC services of other servers registered by the
 * organization, their layers read from the capabilities, and a preview of a WMS layer. A service
 * whose server refuses calls from another origin is reached through the proxy of the catalog,
 * whose address is that of Geoportal, so the browser never calls the other server. The texts
 * follow the language of the person (context.locale).
 */

import { isUnavailable, rulesSection, unavailableNote, unavailableOf } from './service-rules.js';

const TEXTS = {
  it: {
    title: 'Servizi esterni',
    subtitle: 'Servizi OGC di altri server. Con il proxy, la mappa li raggiunge attraverso Geoportal.',
    register: 'Registra un servizio',
    columns: ['Servizio', 'Indirizzo', 'Proxy'],
    none: 'Nessun servizio registrato.',
    count: (n) => (n === 1 ? '1 servizio' : `${n} servizi`),
    error: (s) => `I servizi non si possono caricare (${s}).`,
    on: 'Attivo',
    off: 'Spento',
    form: {
      heading: 'Nuovo servizio',
      title: 'Titolo',
      kind: 'Tipo',
      url: 'Indirizzo',
      proxy: 'Usa il proxy di Geoportal',
      proxyHint: 'Per i server che non permettono le chiamate da un altro sito (CORS). Se il browser non può leggere il servizio, Geoportal accende il proxy da sé.',
      crs: 'Sistema di riferimento',
      save: 'Salva',
      cancel: 'Annulla',
      saved: (t) => `Servizio «${t}» registrato.`,
      failed: (m) => `Il servizio non è stato registrato: ${m}`,
    },
    detail: {
      layers: 'Livelli',
      reading: 'Lettura delle capacità…',
      none: 'Il servizio non ha restituito livelli.',
      unreadable: 'Le capacità del servizio non si possono leggere.',
      preview: 'Anteprima',
      previewOf: (l) => `Anteprima del livello ${l}`,
      viaProxy: 'Attraverso il proxy di Geoportal',
      direct: 'Direttamente dal server del servizio',
      proxyOn: 'Accendi il proxy',
      proxyOff: 'Spegni il proxy',
      remove: 'Elimina',
      close: 'Chiudi',
      check: 'Verifica l’accesso',
    },
    access: {
      reasons: {
        cors: 'il server del servizio non permette le chiamate da Geoportal (CORS)',
        csp: (o) => `l’indirizzo ${o} non è tra le origini esterne della piattaforma`,
      },
      turnedOn: (r) => `Il proxy è stato acceso: ${r}. I livelli si leggono attraverso Geoportal.`,
      needed: (r) => `Il servizio si legge solo attraverso il proxy: ${r}.`,
      direct: 'Il browser legge il servizio direttamente.',
      unreadable: 'Il server del servizio non risponde con le sue capacità: controlla l’indirizzo.',
      failed: (s) => `La verifica non è riuscita (${s}).`,
    },
  },
  en: {
    title: 'External services',
    subtitle: 'OGC services of other servers. With the proxy, the map reaches them through Geoportal.',
    register: 'Register a service',
    columns: ['Service', 'Address', 'Proxy'],
    none: 'No service registered.',
    count: (n) => (n === 1 ? '1 service' : `${n} services`),
    error: (s) => `The services cannot be loaded (${s}).`,
    on: 'On',
    off: 'Off',
    form: {
      heading: 'New service',
      title: 'Title',
      kind: 'Kind',
      url: 'Address',
      proxy: 'Use the proxy of Geoportal',
      proxyHint: 'For servers that do not allow calls from another site (CORS). When the browser cannot read the service, Geoportal turns the proxy on by itself.',
      crs: 'Reference system',
      save: 'Save',
      cancel: 'Cancel',
      saved: (t) => `Service “${t}” registered.`,
      failed: (m) => `The service was not registered: ${m}`,
    },
    detail: {
      layers: 'Layers',
      reading: 'Reading the capabilities…',
      none: 'The service returned no layer.',
      unreadable: 'The capabilities of the service cannot be read.',
      preview: 'Preview',
      previewOf: (l) => `Preview of the layer ${l}`,
      viaProxy: 'Through the proxy of Geoportal',
      direct: 'Directly from the server of the service',
      proxyOn: 'Turn the proxy on',
      proxyOff: 'Turn the proxy off',
      remove: 'Delete',
      close: 'Close',
      check: 'Check the access',
    },
    access: {
      reasons: {
        cors: 'the server of the service does not allow calls from Geoportal (CORS)',
        csp: (o) => `the address ${o} is not among the external origins of the platform`,
      },
      turnedOn: (r) => `The proxy was turned on: ${r}. The layers are read through Geoportal.`,
      needed: (r) => `The service can be read only through the proxy: ${r}.`,
      direct: 'The browser reads the service directly.',
      unreadable: 'The server of the service does not answer with its capabilities: check the address.',
      failed: (s) => `The check did not succeed (${s}).`,
    },
  },
};

const STYLE = `
  :host { display: block; }
  [hidden] { display: none !important; }
  section { display: flex; flex-direction: column; gap: 12px; }
  h2 { margin: 0; font-size: 20px; }
  h3 { margin: 0; font-size: 15px; }
  .muted { color: var(--mk-muted); }
  .bar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
  button { font: inherit; border: 1px solid var(--mk-line); background: none; color: var(--mk-fg);
    border-radius: var(--mk-radius); padding: 6px 12px; cursor: pointer; }
  button.primary { background: var(--mk-accent); border-color: var(--mk-accent); color: var(--mk-on-accent, #fff); }
  table { width: 100%; border-collapse: collapse; background: var(--mk-surface); border: 1px solid var(--mk-line);
    border-radius: var(--mk-radius); overflow: hidden; }
  th { text-align: left; font-size: 11px; text-transform: uppercase; letter-spacing: .06em; color: var(--mk-muted);
    padding: 10px 12px; border-bottom: 1px solid var(--mk-line); }
  td { padding: 10px 12px; border-top: 1px solid var(--mk-line); vertical-align: middle; overflow-wrap: anywhere; }
  tr.row { cursor: pointer; }
  tr.row:hover td, tr.row[aria-selected=true] td { background: var(--mk-bg); }
  .mono { font-family: var(--mk-font-mono, ui-monospace, monospace); font-size: 12px; }
  .pill { font-size: 12px; padding: 2px 8px; border-radius: 999px; border: 1px solid var(--mk-line); white-space: nowrap; }
  .pill.on { border-color: var(--mk-accent); color: var(--mk-accent); }
  form, .detail { background: var(--mk-surface); border: 1px solid var(--mk-line); border-radius: var(--mk-radius);
    padding: 16px; display: flex; flex-direction: column; gap: 10px; }
  label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
  label.check { flex-direction: row; align-items: center; gap: 8px; }
  input[type=text], input[type=url], select { font: inherit; padding: 7px 9px; border: 1px solid var(--mk-line);
    border-radius: var(--mk-radius); background: var(--mk-bg); color: var(--mk-fg); }
  ul.layers { margin: 0; padding: 0; list-style: none; display: flex; flex-wrap: wrap; gap: 6px; }
  ul.layers button[aria-pressed=true] { border-color: var(--mk-accent); color: var(--mk-accent); }
  ul.layers button.unavailable { text-decoration: line-through; }
  .unavailable[role=note] { border: 1px solid var(--mk-danger, #c0392b); border-radius: var(--mk-radius); padding: 8px 12px; }
  .unavailable[role=note] h3 { margin: 0 0 4px; font-size: 14px; }
  .service-rules form { display: flex; flex-wrap: wrap; gap: 8px; align-items: end; }
  .service-rules .failure { color: var(--mk-danger, #c0392b); width: 100%; }
  figure { margin: 0; display: flex; flex-direction: column; gap: 6px; }
  figure img { max-width: 100%; width: 512px; aspect-ratio: 2 / 1; background: var(--mk-bg); border: 1px solid var(--mk-line);
    border-radius: var(--mk-radius); object-fit: contain; }
  [role=alert] { margin: 0; }
`;

const KINDS = ['wms', 'wmts', 'wfs', 'wcs'];

/** The address of a request of the service: its registered one, or that of the proxy. */
export function serviceUrl(service, viaProxy, params) {
  const base = viaProxy ? service.proxyUrl : service.url;
  const query = new URLSearchParams(params).toString();
  return base + (base.includes('?') ? '&' : '?') + query;
}

/**
 * What the catalog found on whether the browser reads a service directly (GP-297), as a sentence; empty
 * when there is nothing to say.
 */
export function accessNotice(texts, access) {
  if (!access) {
    return '';
  }
  const reasons = (access.reasons ?? [])
    .map((r) => (r === 'csp' ? texts.reasons.csp(access.origin) : texts.reasons[r]))
    .filter(Boolean)
    .join('; ');
  if (access.outcome === 'proxy') {
    return access.proxyTurnedOn ? texts.turnedOn(reasons) : texts.needed(reasons);
  }
  return access.outcome === 'unreadable' ? texts.unreadable : texts.direct;
}

/** Reads capabilities: the document if it is capabilities, otherwise null (an error page is not). */
export function parseCapabilities(text) {
  if (!text) {
    return null;
  }
  const doc = new DOMParser().parseFromString(text, 'application/xml');
  const root = doc.documentElement;
  if (!root || doc.getElementsByTagName('parsererror').length > 0 || !/Capabilities$/.test(root.localName)) {
    return null;
  }
  return doc;
}

/** The layers of capabilities: name, title and, for WMS, the geographic extent. */
export function layersOf(doc, kind) {
  const child = (el, name) => [...el.children].find((c) => c.localName === name);
  const text = (el, name) => child(el, name)?.textContent.trim() ?? '';
  if (kind === 'wfs') {
    return [...doc.getElementsByTagNameNS('*', 'FeatureType')]
      .map((ft) => ({ name: text(ft, 'Name'), title: text(ft, 'Title') }))
      .filter((l) => l.name);
  }
  if (kind === 'wmts') {
    return [...doc.getElementsByTagNameNS('*', 'Layer')]
      .map((l) => ({ name: text(l, 'Identifier'), title: text(l, 'Title') }))
      .filter((l) => l.name);
  }
  if (kind === 'wcs') {
    return [...doc.getElementsByTagNameNS('*', 'CoverageSummary'), ...doc.getElementsByTagNameNS('*', 'CoverageOfferingBrief')]
      .map((c) => ({ name: text(c, 'CoverageId') || text(c, 'Identifier') || text(c, 'name'), title: text(c, 'Title') }))
      .filter((l) => l.name);
  }
  return [...doc.getElementsByTagNameNS('*', 'Layer')]
    .filter((l) => child(l, 'Name'))
    .map((l) => {
      const geo = child(l, 'EX_GeographicBoundingBox');
      const old = child(l, 'LatLonBoundingBox');
      let bbox = [-180, -90, 180, 90];
      if (geo) {
        bbox = ['westBoundLongitude', 'southBoundLatitude', 'eastBoundLongitude', 'northBoundLatitude'].map((n) =>
          Number(text(geo, n)),
        );
      } else if (old) {
        bbox = ['minx', 'miny', 'maxx', 'maxy'].map((n) => Number(old.getAttribute(n)));
      }
      return { name: text(l, 'Name'), title: text(l, 'Title'), bbox };
    });
}

/** The capabilities of a service, read from the browser directly or through the proxy, or null. */
export async function readCapabilities(context, service, viaProxy) {
  const url = serviceUrl(service, viaProxy, { SERVICE: service.kind.toUpperCase(), REQUEST: 'GetCapabilities' });
  try {
    // Directly: no credentials of Geoportal go to the other server.
    const response = viaProxy ? await context.fetch(url) : await fetch(url, { credentials: 'omit', mode: 'cors' });
    if (!response.ok) {
      return null;
    }
    return parseCapabilities(await response.text());
  } catch {
    return null;
  }
}

export function defineServices(context) {
  if (customElements.get('catalog-services')) {
    return;
  }
  const t = () => TEXTS[context.locale] ?? TEXTS.en;

  class Services extends HTMLElement {
    connectedCallback() {
      this.services = [];
      this.chosen = undefined;
      this.layers = [];
      this.layer = undefined;
      const texts = t();
      const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
      root.innerHTML = `
        <style>${STYLE}</style>
        <section aria-labelledby="services-title">
          <div><h2 id="services-title">${texts.title}</h2><p class="muted">${texts.subtitle}</p></div>
          <div class="bar"><button type="button" class="primary register" hidden>${texts.register}</button></div>
          <p class="notice" aria-live="polite"></p>
          <div class="form-slot"></div>
          <table><thead><tr>${texts.columns.map((c) => `<th>${c}</th>`).join('')}</tr></thead><tbody></tbody></table>
          <div class="detail-slot"></div>
        </section>`;
      this.root = root;
      root.querySelector('.register').addEventListener('click', () => this.openForm());
      void this.load();
      void this.permissions();
    }

    /** Shows Register only to those who hold the permission; the backend checks anyway. */
    async permissions() {
      const response = await context.fetch('/api/v1/shell/permissions');
      if (!response.ok) {
        return;
      }
      const answer = await response.json();
      const held = Array.isArray(answer) ? answer : (answer.permissions ?? []);
      this.root.querySelector('.register').hidden = !held.includes('dev.geoportal.ext.catalog.geodata:register-service');
    }

    notice(text) {
      this.root.querySelector('.notice').textContent = text;
    }

    async load() {
      const response = await context.fetch('/api/v1/ext/catalog-geodata/services');
      if (!response.ok) {
        this.notice(t().error(response.status));
        return;
      }
      this.services = await response.json();
      if (this.chosen) {
        this.chosen = this.services.find((s) => s.id === this.chosen.id);
      }
      this.render();
    }

    render() {
      const texts = t();
      const body = this.root.querySelector('tbody');
      if (this.services.length === 0) {
        const row = document.createElement('tr');
        row.innerHTML = `<td colspan="3" class="muted"></td>`;
        row.firstElementChild.textContent = texts.none;
        body.replaceChildren(row);
      } else {
        body.replaceChildren(...this.services.map((s) => this.row(s, texts)));
      }
      this.renderDetail();
    }

    row(s, texts) {
      const row = document.createElement('tr');
      row.className = 'row';
      row.tabIndex = 0;
      row.setAttribute('aria-selected', String(s.id === this.chosen?.id));
      row.innerHTML = `<td><b></b> <span class="pill"></span></td><td class="mono"></td><td><span class="pill proxy"></span></td>`;
      row.querySelector('b').textContent = s.title;
      row.querySelector('.pill').textContent = s.kind.toUpperCase();
      row.children[1].textContent = s.url;
      const proxy = row.querySelector('.proxy');
      proxy.textContent = s.proxy ? texts.on : texts.off;
      proxy.classList.toggle('on', s.proxy);
      const open = () => this.choose(s);
      row.addEventListener('click', open);
      row.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
          open();
        }
      });
      return row;
    }

    openForm() {
      const texts = t().form;
      const slot = this.root.querySelector('.form-slot');
      slot.innerHTML = `
        <form aria-labelledby="service-form-title">
          <h3 id="service-form-title">${texts.heading}</h3>
          <label>${texts.title}<input type="text" name="title" required maxlength="200" /></label>
          <label>${texts.kind}<select name="kind">${KINDS.map((k) => `<option value="${k}">${k.toUpperCase()}</option>`).join('')}</select></label>
          <label>${texts.url}<input type="url" name="url" required maxlength="2000" placeholder="https://" /></label>
          <label class="check"><input type="checkbox" name="proxy" /> ${texts.proxy}</label>
          <p class="muted">${texts.proxyHint}</p>
          <label class="crs" hidden>${texts.crs}<input type="text" name="crs" value="EPSG:4326" maxlength="60" /></label>
          <p role="alert" class="muted"></p>
          <div class="bar"><button type="submit" class="primary">${texts.save}</button><button type="button" class="cancel">${texts.cancel}</button></div>
        </form>`;
      const form = slot.querySelector('form');
      const kind = form.elements.namedItem('kind');
      kind.addEventListener('change', () => {
        form.querySelector('.crs').hidden = kind.value !== 'wfs';
      });
      form.querySelector('.cancel').addEventListener('click', () => slot.replaceChildren());
      form.addEventListener('submit', (event) => {
        event.preventDefault();
        void this.save(form);
      });
      form.elements.namedItem('title').focus();
    }

    async save(form) {
      const texts = t().form;
      const value = (name) => form.elements.namedItem(name).value.trim();
      const body = {
        title: value('title'),
        kind: value('kind'),
        url: value('url'),
        proxy: form.elements.namedItem('proxy').checked,
        crs: value('kind') === 'wfs' ? value('crs') : null,
      };
      const response = await context.fetch('/api/v1/ext/catalog-geodata/services', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!response.ok) {
        const answer = await response.json().catch(() => ({}));
        form.querySelector('[role=alert]').textContent = texts.failed(answer.message ?? answer.detail ?? response.status);
        return;
      }
      const saved = await response.json();
      this.root.querySelector('.form-slot').replaceChildren();
      // GP-297: the catalog checked whether the browser reads the service, and turned the proxy on if not.
      const access = saved.access && saved.access.outcome !== 'direct' ? accessNotice(t().access, saved.access) : '';
      this.notice(access ? `${texts.saved(saved.title)} ${access}` : texts.saved(saved.title));
      this.chosen = saved;
      await this.load();
      await this.afterSave(saved);
    }

    /** What happens once a service is saved: its layers are read, directly or through the proxy. */
    async afterSave(saved) {
      await this.choose(saved);
    }

    async choose(service) {
      this.chosen = service;
      this.layers = undefined;
      this.layer = undefined;
      this.rulesBox = undefined;
      // GP-309: what data rules make unavailable to the person, and why.
      this.unavailable = await unavailableOf(context, service);
      this.render();
      const doc = await readCapabilities(context, service, service.proxy);
      if (this.chosen?.id !== service.id) {
        return;
      }
      this.layers = doc ? layersOf(doc, service.kind) : null;
      this.layer = this.layers?.find((l) => !isUnavailable(this.unavailable ?? [], l.name)) ?? this.layers?.[0];
      this.renderDetail();
    }

    renderDetail() {
      const texts = t().detail;
      const slot = this.root.querySelector('.detail-slot');
      const s = this.chosen;
      if (!s) {
        slot.replaceChildren();
        return;
      }
      slot.innerHTML = `
        <div class="detail" role="region">
          <div class="bar"><h3></h3><span class="pill proxy"></span></div>
          <p class="mono"></p>
          <h3>${texts.layers}</h3>
          <div class="layers-slot"></div>
          <div class="preview-slot"></div>
          <div class="bar actions"><button type="button" class="close">${texts.close}</button></div>
        </div>`;
      const detail = slot.querySelector('.detail');
      detail.setAttribute('aria-label', s.title);
      detail.querySelector('h3').textContent = s.title;
      detail.querySelector('.proxy').textContent = s.proxy ? texts.viaProxy : texts.direct;
      detail.querySelector('p.mono').textContent = s.url;
      const note = unavailableNote(context, this.unavailable);
      if (note) {
        detail.querySelector('p.mono').after(note);
      }
      const layers = detail.querySelector('.layers-slot');
      if (this.layers === undefined) {
        layers.innerHTML = `<p class="muted"></p>`;
        layers.firstElementChild.textContent = texts.reading;
      } else if (this.layers === null || this.layers.length === 0) {
        layers.innerHTML = `<p class="muted"></p>`;
        layers.firstElementChild.textContent = this.layers === null ? texts.unreadable : texts.none;
      } else {
        const list = document.createElement('ul');
        list.className = 'layers';
        list.setAttribute('aria-label', texts.layers);
        list.replaceChildren(
          ...this.layers.map((l) => {
            const item = document.createElement('li');
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = l.title || l.name;
            button.title = l.name;
            button.setAttribute('aria-pressed', String(l === this.layer));
            if (isUnavailable(this.unavailable ?? [], l.name)) {
              button.disabled = true;
              button.classList.add('unavailable');
            }
            button.addEventListener('click', () => {
              this.layer = l;
              this.renderDetail();
            });
            item.append(button);
            return item;
          }),
        );
        layers.append(list);
      }
      if (s.kind === 'wms' && this.layer && !isUnavailable(this.unavailable ?? [], this.layer.name)) {
        const figure = document.createElement('figure');
        const img = document.createElement('img');
        const [minx, miny, maxx, maxy] = this.layer.bbox ?? [-180, -90, 180, 90];
        img.src = serviceUrl(s, s.proxy, {
          SERVICE: 'WMS',
          VERSION: '1.1.1',
          REQUEST: 'GetMap',
          LAYERS: this.layer.name,
          STYLES: '',
          SRS: 'EPSG:4326',
          BBOX: [minx, miny, maxx, maxy].join(','),
          WIDTH: '512',
          HEIGHT: '256',
          FORMAT: 'image/png',
          TRANSPARENT: 'true',
        });
        img.alt = texts.previewOf(this.layer.title || this.layer.name);
        const caption = document.createElement('figcaption');
        caption.className = 'muted';
        caption.textContent = `${texts.preview} · ${s.proxy ? texts.viaProxy : texts.direct}`;
        figure.append(img, caption);
        detail.querySelector('.preview-slot').append(figure);
      }
      if (s.manage && this.layers !== undefined) {
        // GP-309: the data rules of the service, kept while it stays open.
        if (this.rulesBox?.dataset.service !== s.id) {
          this.rulesBox = rulesSection(context, s, this.layers ?? [], (m) => void this.rulesChanged(s, m));
          this.rulesBox.dataset.service = s.id;
        }
        detail.querySelector('.preview-slot').after(this.rulesBox);
      }
      const actions = detail.querySelector('.actions');
      if (s.manage) {
        const toggle = document.createElement('button');
        toggle.type = 'button';
        toggle.textContent = s.proxy ? texts.proxyOff : texts.proxyOn;
        toggle.addEventListener('click', () => void this.setProxy(s, !s.proxy));
        const check = document.createElement('button');
        check.type = 'button';
        check.textContent = texts.check;
        check.addEventListener('click', () => void this.checkAccess(s));
        const remove = document.createElement('button');
        remove.type = 'button';
        remove.textContent = texts.remove;
        remove.addEventListener('click', () => void this.remove(s));
        actions.prepend(toggle, check, remove);
      }
      detail.querySelector('.close').addEventListener('click', () => {
        this.chosen = undefined;
        this.render();
      });
    }

    /** Checks again whether the browser reads the service (GP-297); the proxy is turned on if it cannot. */
    async checkAccess(s) {
      const texts = t().access;
      const response = await context.fetch(`/api/v1/ext/catalog-geodata/services/${s.id}/access`, { method: 'POST' });
      if (!response.ok) {
        this.notice(texts.failed(response.status));
        return;
      }
      const checked = await response.json();
      this.notice(accessNotice(texts, checked.access));
      if (checked.proxy !== s.proxy) {
        await this.load();
        await this.choose(checked);
      }
    }

    /** A rule was added or removed: says so, and reads again what is unavailable to the person. */
    async rulesChanged(s, message) {
      this.notice(message);
      this.unavailable = await unavailableOf(context, s);
      this.renderDetail();
    }

    async setProxy(s, proxy) {
      const response = await context.fetch(`/api/v1/ext/catalog-geodata/services/${s.id}`, {
        method: 'PUT',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ title: s.title, kind: s.kind, url: s.url, proxy, crs: s.crs,
          credential: s.credential, authScheme: s.authScheme }),
      });
      if (response.ok) {
        const saved = await response.json();
        await this.load();
        await this.choose(saved);
      }
      return response.ok;
    }

    async remove(s) {
      const response = await context.fetch(`/api/v1/ext/catalog-geodata/services/${s.id}`, { method: 'DELETE' });
      if (response.ok) {
        this.chosen = undefined;
        await this.load();
      }
    }
  }

  customElements.define('catalog-services', Services);
}
