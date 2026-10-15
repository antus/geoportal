// SPDX-FileCopyrightText: 2026 Massimo Antonini

/**
 * The page of a link to a dataset (GP-336), also for people without an account: the shell places this
 * element in `/shared/<secret>` with the link as its credential (`request`). It shows the title of the
 * dataset, a preview of its published layer and the addresses of its WMS and WMTS through the
 * gateway (`/gw/link/<secret>/<dataset>/...`), which open only that layer, to view, until the link
 * expires or is revoked. The texts follow the language of the page.
 */

const API = '/api/v1/ext/catalog-geodata/shared-layers/';

const TEXTS = {
  it: {
    loading: 'Apertura del dato…',
    missing: 'Questo dato non è pubblicato come servizio: chi te l’ha condiviso può pubblicarlo dal catalogo.',
    error: (s) => `Il dato non si può aprire (${s}).`,
    preview: (t) => `Anteprima di «${t}»`,
    services: 'Indirizzi del servizio',
    servicesHint:
      'Aprono solo questo livello, in sola lettura, finché il collegamento vale. Chi ha l’indirizzo vede il dato: condividilo come il collegamento.',
    noGateway: 'Questa installazione non ha il gateway dei dati: gli indirizzi richiedono l’intestazione X-Mosaikit-Link.',
    copy: 'Copia',
    copied: 'Indirizzo copiato.',
    layer: 'Livello',
  },
  en: {
    loading: 'Opening the dataset…',
    missing: 'This dataset is not published as a service: whoever shared it can publish it from the catalog.',
    error: (s) => `The dataset cannot be opened (${s}).`,
    preview: (t) => `Preview of “${t}”`,
    services: 'Service addresses',
    servicesHint:
      'They open only this layer, read-only, while the link is valid. Whoever has the address sees the data: share it as you would the link.',
    noGateway: 'This installation has no data gateway: the addresses need the header X-Mosaikit-Link.',
    copy: 'Copy',
    copied: 'Address copied.',
    layer: 'Layer',
  },
};

const STYLE = `
  :host { display: block; }
  section { display: flex; flex-direction: column; gap: 12px; }
  h2 { margin: 0; font-size: 20px; overflow-wrap: anywhere; }
  .muted { color: var(--mk-muted); }
  .small { font-size: 13px; }
  figure { margin: 0; border: 1px solid var(--mk-line); border-radius: var(--mk-radius); overflow: hidden;
    background: var(--mk-surface); }
  figure img { display: block; width: 100%; height: auto; }
  ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
  li { display: grid; grid-template-columns: 56px minmax(0, 1fr) auto; gap: 8px; align-items: center; }
  code { font-family: var(--mk-mono, monospace); font-size: 12px; overflow-wrap: anywhere; }
  button { font: inherit; font-size: 13px; padding: 4px 10px; border: 1px solid var(--mk-line); background: none;
    color: var(--mk-fg); border-radius: var(--mk-radius); cursor: pointer; }
  button:focus-visible { outline: 2px solid var(--mk-focus); outline-offset: 2px; }
`;

/** The secret of the link of the page, from `/shared/<secret>`. */
function secretOfPage() {
  const match = /\/shared\/([^/?#]+)/.exec(location.pathname);
  return match ? decodeURIComponent(match[1]) : '';
}

/** A GetMap of the layer over its extent, as wide as the page allows. */
function previewQuery(layer, extent) {
  // A margin around the data, so that symbols on the edge show whole (and a point gets an area).
  const pad = Math.max(extent[2] - extent[0], extent[3] - extent[1], 0.001) * 0.1;
  const [w, s, e, n] = [extent[0] - pad, extent[1] - pad, extent[2] + pad, extent[3] + pad];
  const width = 640;
  const height = Math.max(160, Math.min(640, Math.round((width * (n - s)) / Math.max(e - w, 1e-9))));
  return new URLSearchParams({
    service: 'WMS',
    version: '1.1.1',
    request: 'GetMap',
    layers: layer,
    styles: '',
    srs: 'EPSG:4326',
    bbox: `${w},${s},${e},${n}`,
    width: String(width),
    height: String(height),
    format: 'image/png',
    transparent: 'true',
  }).toString();
}

export function defineShared(context) {
  if (customElements.get('catalog-shared-dataset')) {
    return;
  }
  const t = () => TEXTS[(context.locale ?? 'en').split('-')[0]] ?? TEXTS.en;

  class SharedDataset extends HTMLElement {
    connectedCallback() {
      const tx = t();
      this.root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
      this.root.innerHTML = `<style>${STYLE}</style><section aria-live="polite"><p class="muted">${tx.loading}</p></section>`;
      void this.load();
    }

    async load() {
      const tx = t();
      const section = this.root.querySelector('section');
      const id = this.resource?.id;
      const request = this.request ?? context.fetch;
      const response = id ? await request(API + encodeURIComponent(id)) : undefined;
      if (!response || !response.ok) {
        const p = document.createElement('p');
        p.setAttribute('role', 'alert');
        p.textContent = response && response.status === 404 ? tx.missing : tx.error(response ? response.status : '-');
        section.replaceChildren(p);
        return;
      }
      const shared = await response.json();
      const secret = secretOfPage();
      const base = `/gw/link/${encodeURIComponent(secret)}/${shared.dataset}/`;
      const services = {
        wms: `${base}wms?service=WMS&request=GetCapabilities`,
        wmts: `${base}gwc/service/wmts?service=WMTS&request=GetCapabilities`,
      };
      section.innerHTML = `<h2></h2><p class="muted small description"></p><p class="small"><span class="muted">${tx.layer}</span>
        <code class="layer"></code></p><figure hidden><img alt="" /></figure>
        <h3>${tx.services}</h3><p class="muted small">${shared.gateway ? tx.servicesHint : tx.noGateway}</p><ul></ul>
        <p class="muted small said" role="status"></p>`;
      section.querySelector('h2').textContent = shared.title;
      section.querySelector('.description').textContent = shared.description ?? '';
      section.querySelector('.layer').textContent = shared.layer;
      if (shared.gateway && secret && Array.isArray(shared.extent)) {
        const figure = section.querySelector('figure');
        const img = figure.querySelector('img');
        img.alt = tx.preview(shared.title);
        img.src = `${base}wms?${previewQuery(shared.layer, shared.extent)}`;
        figure.hidden = false;
      }
      section.querySelector('ul').replaceChildren(
        ...Object.entries(services).map(([kind, path]) => {
          const li = document.createElement('li');
          li.innerHTML = '<b></b><code></code><button type="button"></button>';
          const url = new URL(path, location.origin).toString();
          li.querySelector('b').textContent = kind.toUpperCase();
          li.querySelector('code').textContent = url;
          const copy = li.querySelector('button');
          copy.textContent = tx.copy;
          copy.setAttribute('aria-label', `${tx.copy} ${kind.toUpperCase()}`);
          copy.disabled = !shared.gateway;
          copy.addEventListener('click', async () => {
            try {
              await navigator.clipboard.writeText(url);
              section.querySelector('.said').textContent = tx.copied;
            } catch {
              // no clipboard: the address is on the page
            }
          });
          return li;
        }),
      );
    }
  }

  customElements.define('catalog-shared-dataset', SharedDataset);
}
