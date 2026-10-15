// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * GP-983, ADR-0028: geodata as a kind of file of the catalog of mosaikit-ext (EXT-105). The catalog
 * creates these custom elements for a file of the kind and gives them the file in their property `file`
 * ({ id, name, contentType, size, fileKind, url() }, url() a signed link to its content):
 *
 * - `geodata-dataset-preview`, the geospatial preview: what the dataset made from the file is (format,
 *   state, features, reference system, extent) and its footprint, drawn from its extent and, for a
 *   GeoJSON, from its features;
 * - `geodata-dataset-publish`, the action Publish as a service (GP-114): the name of the layer, then the
 *   publication through the API of the geodata, after which the catalog shows the file again.
 *
 * The dataset is made in a job of the organization a few seconds after the file is stored: until then
 * the elements say so and ask again.
 */

const API = '/api/v1/ext/catalog-geodata';
/** A GeoJSON up to this size is drawn feature by feature; a larger one only by its extent. */
const DRAWN = 2 * 1024 * 1024;
const WAIT = 2000;
const TRIES = 30;

const TEXTS = {
  it: {
    preview: (name) => `Anteprima geografica di ${name}`,
    footprint: (name) => `Impronta di ${name}`,
    waiting: 'Il dato geografico si sta preparando…',
    missing: 'Questo file non è un dato geografico che puoi vedere.',
    error: (status) => `Il dato geografico non si può leggere (${status}).`,
    format: 'Formato',
    state: 'Stato',
    features: 'Oggetti',
    crs: 'Sistema di riferimento',
    extent: 'Estensione (WGS84)',
    noExtent: 'Estensione non ancora nota: il dato non è caricato.',
    states: { uploaded: 'Non ancora caricato', loading: 'In caricamento', loaded: 'Caricato', published: 'Pubblicato', failed: 'Non caricato' },
    layer: 'Nome del livello',
    publish: 'Pubblica',
    publishing: 'Pubblicazione…',
    published: (layer) => `Pubblicato come livello ${layer}.`,
    notLoaded: 'Il dato si pubblica quando è caricato.',
    refused: (detail) => `Non pubblicato: ${detail}`,
  },
  en: {
    preview: (name) => `Geospatial preview of ${name}`,
    footprint: (name) => `Footprint of ${name}`,
    waiting: 'The geodata are being prepared…',
    missing: 'This file is not geodata you can see.',
    error: (status) => `The geodata cannot be read (${status}).`,
    format: 'Format',
    state: 'State',
    features: 'Features',
    crs: 'Reference system',
    extent: 'Extent (WGS84)',
    noExtent: 'Extent not known yet: the data are not loaded.',
    states: { uploaded: 'Not loaded yet', loading: 'Loading', loaded: 'Loaded', published: 'Published', failed: 'Not loaded' },
    layer: 'Layer name',
    publish: 'Publish',
    publishing: 'Publishing…',
    published: (layer) => `Published as the layer ${layer}.`,
    notLoaded: 'The data are published once they are loaded.',
    refused: (detail) => `Not published: ${detail}`,
  },
};

const STYLE = `
  :host { display: block; }
  dl { display: grid; grid-template-columns: max-content 1fr; gap: 4px 12px; margin: 0 0 10px; }
  dt { color: var(--mk-muted, #666); font-size: 13px; }
  dd { margin: 0; }
  svg { width: 100%; max-width: 360px; height: auto; border: 1px solid var(--mk-line, #ddd);
    border-radius: var(--mk-radius, 6px); background: var(--mk-surface, #f7f8fa); }
  form { display: flex; gap: 8px; align-items: end; flex-wrap: wrap; }
  label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
  input { font: inherit; padding: 4px 8px; }
  button { font: inherit; padding: 4px 12px; cursor: pointer; }
`;

/** The dataset made from a file, asking again while it is being made or loaded. */
async function datasetOf(context, file, tries = TRIES) {
  for (let i = 0; ; i++) {
    const response = await context.fetch(`${API}/uploads/files/${encodeURIComponent(file.id)}`);
    const ready = response.ok ? await response.json() : null;
    const busy = response.status === 404 || ready?.state === 'loading';
    if (!busy || i >= tries - 1) {
      return { status: response.status, dataset: ready };
    }
    await new Promise((resolve) => setTimeout(resolve, WAIT));
  }
}

/** Every position of a GeoJSON geometry, flattened. */
function positions(geometry, into = []) {
  if (!geometry) {
    return into;
  }
  if (geometry.type === 'GeometryCollection') {
    (geometry.geometries ?? []).forEach((g) => positions(g, into));
    return into;
  }
  const walk = (c) => {
    if (typeof c?.[0] === 'number') {
      into.push(c);
    } else {
      (c ?? []).forEach(walk);
    }
  };
  walk(geometry.coordinates);
  return into;
}

/** The parts of a geometry to draw: lines and rings as lists of positions, points as single positions. */
function parts(geometry) {
  switch (geometry?.type) {
    case 'Point':
      return { points: [geometry.coordinates], paths: [] };
    case 'MultiPoint':
      return { points: geometry.coordinates, paths: [] };
    case 'LineString':
      return { points: [], paths: [geometry.coordinates] };
    case 'MultiLineString':
    case 'Polygon':
      return { points: [], paths: geometry.coordinates };
    case 'MultiPolygon':
      return { points: [], paths: geometry.coordinates.flat() };
    case 'GeometryCollection':
      return (geometry.geometries ?? []).map(parts).reduce((a, b) => ({ points: [...a.points, ...b.points], paths: [...a.paths, ...b.paths] }), { points: [], paths: [] });
    default:
      return { points: [], paths: [] };
  }
}

const SVG = 'http://www.w3.org/2000/svg';

/** The footprint: the extent as a frame and, when given, the features inside it. */
function footprint(label, extent, features) {
  const width = 360;
  const height = 220;
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', label);
  let [west, south, east, north] = extent;
  const spanX = Math.max(east - west, 1e-9);
  const spanY = Math.max(north - south, 1e-9);
  const pad = 12;
  const scale = Math.min((width - 2 * pad) / spanX, (height - 2 * pad) / spanY);
  const x = (lon) => pad + (lon - west) * scale + ((width - 2 * pad) - spanX * scale) / 2;
  const y = (lat) => height - pad - (lat - south) * scale - ((height - 2 * pad) - spanY * scale) / 2;
  const frame = document.createElementNS(SVG, 'rect');
  frame.setAttribute('x', String(x(west)));
  frame.setAttribute('y', String(y(north)));
  frame.setAttribute('width', String(spanX * scale));
  frame.setAttribute('height', String(spanY * scale));
  frame.setAttribute('fill', 'none');
  frame.setAttribute('stroke', '#444791');
  frame.setAttribute('stroke-dasharray', '4 3');
  frame.dataset.part = 'extent';
  svg.append(frame);
  for (const feature of features) {
    const { points, paths } = parts(feature.geometry);
    for (const path of paths) {
      const line = document.createElementNS(SVG, 'polyline');
      line.setAttribute('points', path.map((p) => `${x(p[0]).toFixed(1)},${y(p[1]).toFixed(1)}`).join(' '));
      line.setAttribute('fill', 'none');
      line.setAttribute('stroke', '#2f7d4f');
      line.setAttribute('stroke-width', '1.5');
      line.dataset.part = 'feature';
      svg.append(line);
    }
    for (const p of points) {
      const dot = document.createElementNS(SVG, 'circle');
      dot.setAttribute('cx', x(p[0]).toFixed(1));
      dot.setAttribute('cy', y(p[1]).toFixed(1));
      dot.setAttribute('r', '3');
      dot.setAttribute('fill', '#2f7d4f');
      dot.dataset.part = 'feature';
      svg.append(dot);
    }
  }
  return svg;
}

/** The features of a small GeoJSON, read through the signed link of the catalog; none for the others. */
async function featuresOf(file, dataset) {
  if (dataset?.format !== 'geojson' || (file.size ?? 0) > DRAWN) {
    return [];
  }
  try {
    const response = await fetch(await file.url(), { credentials: 'omit' });
    if (!response.ok) {
      return [];
    }
    const json = await response.json();
    if (json.type === 'FeatureCollection') {
      return json.features ?? [];
    }
    return json.type === 'Feature' ? [json] : [{ geometry: json }];
  } catch {
    return [];
  }
}

/** The extent of features in degrees, when the dataset has none yet. */
function extentOf(features) {
  const all = features.flatMap((f) => positions(f.geometry));
  if (all.length === 0) {
    return null;
  }
  const xs = all.map((p) => p[0]);
  const ys = all.map((p) => p[1]);
  return [Math.min(...xs), Math.min(...ys), Math.max(...xs), Math.max(...ys)];
}

export function defineKind(context) {
  if (customElements.get('geodata-dataset-preview')) {
    return;
  }
  const t = () => TEXTS[String(context.locale ?? 'en').split('-')[0]] ?? TEXTS.en;

  customElements.define(
    'geodata-dataset-preview',
    class extends HTMLElement {
      set file(file) {
        this.shown = file;
        void this.show();
      }

      async show() {
        const file = this.shown;
        const tx = t();
        const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
        const section = document.createElement('section');
        section.setAttribute('aria-label', tx.preview(file.name));
        section.setAttribute('aria-live', 'polite');
        const status = document.createElement('p');
        status.setAttribute('role', 'status');
        status.textContent = tx.waiting;
        section.append(status);
        root.replaceChildren(Object.assign(document.createElement('style'), { textContent: STYLE }), section);

        const { status: code, dataset } = await datasetOf(context, file);
        if (this.shown !== file) {
          return;
        }
        if (!dataset) {
          status.textContent = code === 404 ? tx.missing : tx.error(code);
          return;
        }
        const loaded = dataset.loaded;
        const facts = [
          [tx.format, dataset.formatTitle],
          [tx.state, dataset.stateReason && dataset.state !== 'loaded' && dataset.state !== 'published'
            ? `${tx.states[dataset.state] ?? dataset.state}: ${dataset.stateReason}` : tx.states[dataset.state] ?? dataset.state],
        ];
        if (loaded) {
          if (loaded.table) {
            facts.push([tx.features, String(loaded.features)]);
          }
          if (loaded.crs) {
            facts.push([tx.crs, loaded.crs]);
          }
          if (loaded.extent) {
            facts.push([tx.extent, loaded.extent.map((v) => v.toFixed(4)).join(', ')]);
          }
        }
        const dl = document.createElement('dl');
        for (const [term, value] of facts) {
          dl.append(Object.assign(document.createElement('dt'), { textContent: term }),
            Object.assign(document.createElement('dd'), { textContent: value }));
        }
        const features = await featuresOf(file, dataset);
        const extent = loaded?.extent ?? extentOf(features);
        const shown = [dl];
        if (extent) {
          shown.push(footprint(tx.footprint(file.name), extent, features));
        } else {
          shown.push(Object.assign(document.createElement('p'), { textContent: tx.noExtent }));
        }
        status.remove();
        section.append(...shown);
      }
    },
  );

  customElements.define(
    'geodata-dataset-publish',
    class extends HTMLElement {
      set file(file) {
        this.shown = file;
        void this.show();
      }

      async show() {
        const file = this.shown;
        const tx = t();
        const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
        const status = document.createElement('p');
        status.setAttribute('role', 'status');
        status.textContent = tx.waiting;
        root.replaceChildren(Object.assign(document.createElement('style'), { textContent: STYLE }), status);
        const { status: code, dataset } = await datasetOf(context, file);
        if (!dataset) {
          status.textContent = code === 404 ? tx.missing : tx.error(code);
          return;
        }
        if (dataset.state !== 'loaded' && dataset.state !== 'published') {
          status.textContent = tx.notLoaded;
          return;
        }
        status.textContent = '';
        const form = document.createElement('form');
        const label = document.createElement('label');
        const input = Object.assign(document.createElement('input'), { name: 'layer', value: dataset.technicalName, required: true });
        label.append(tx.layer, input);
        const button = Object.assign(document.createElement('button'), { type: 'submit', textContent: tx.publish });
        form.append(label, button);
        root.append(form);
        form.addEventListener('submit', async (event) => {
          event.preventDefault();
          button.disabled = true;
          status.textContent = tx.publishing;
          const response = await context.fetch(`${API}/publications`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ dataset: dataset.id, layer: input.value.trim() }),
          });
          const body = await response.json().catch(() => ({}));
          button.disabled = false;
          if (!response.ok) {
            status.textContent = tx.refused(body.detail ?? body.title ?? String(response.status));
            return;
          }
          status.textContent = tx.published(body.name ?? input.value.trim());
          // The catalog shows the file again, with the state the publication recorded on it.
          this.dispatchEvent(new CustomEvent('catalog-file-changed', { bubbles: true, composed: true }));
        });
      }
    },
  );
}
