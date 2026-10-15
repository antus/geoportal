// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * What a style looks like, drawn in the page: the miniature of a layer card (GP-006: a strip for a
 * graduated style, chips for a categorized one, a sample for a simple one, the GetLegendGraphic of the
 * map server for a layer it styles), the symbols of the menu of symbol types and of the live preview
 * of the style editor, and the preview map that draws a sample of the features of a dataset with a
 * style (GP-973). SVG built from documents of geoportal/style/v1; the maps of GP-340 draw the same
 * documents with MapLibre (toMapLibre of web/style-grammar.js) and reuse the miniature and legend.
 */

import { constantOf, KINDS, shapeOutline, symbolFor } from './style-grammar.js';
import { evaluate, sampleOf } from './style-themes.js';

const SVG = 'http://www.w3.org/2000/svg';

const TEXTS = {
  it: {
    classes: (field, labels) => `Classi di «${field}»: ${labels.join(', ')}`,
    categories: (field, labels) => `Categorie di «${field}»: ${labels.join(', ')}`,
    more: (n) => `e altre ${n}`,
    simple: 'Simbolo del livello',
    legend: (layer) => `Legenda di ${layer} dal server delle mappe`,
    noLegend: 'Legenda non disponibile',
  },
  en: {
    classes: (field, labels) => `Classes of “${field}”: ${labels.join(', ')}`,
    categories: (field, labels) => `Categories of “${field}”: ${labels.join(', ')}`,
    more: (n) => `and ${n} more`,
    simple: 'Symbol of the layer',
    legend: (layer) => `Legend of ${layer} from the map server`,
    noLegend: 'Legend not available',
  },
};
const texts = (locale) => TEXTS[String(locale ?? 'en').split('-')[0]] ?? TEXTS.en;

/** The CSS of the miniatures, for the shadow roots that show them. */
export const SYMBOLOGY_STYLE = `
  .symbology { display: inline-flex; align-items: center; gap: 2px; vertical-align: middle; max-width: 160px; }
  .symbology.graduated { gap: 0; border-radius: 3px; overflow: hidden; border: 1px solid var(--mk-line); }
  .symbology.graduated .class { width: 14px; height: 12px; }
  .symbology.categories .chip { width: 12px; height: 12px; border-radius: 50%; border: 1px solid rgb(0 0 0 / 20%); }
  .symbology .more { font-size: 11px; color: var(--mk-muted); margin-left: 3px; }
  .symbology.legend img { max-height: 40px; max-width: 160px; object-fit: contain; background: transparent; }
  .symbology.legend.large img { max-height: none; max-width: 100%; }
`;

/** A number as an SVG attribute. */
const n = (v) => String(Math.round(v * 100) / 100);

/** The path of a point shape of the grammar, centred on x, y, size pixels wide. */
export function shapePath(shape, x, y, size) {
  const r = size / 2;
  const outline = shapeOutline(shape);
  if (!outline) return `M${n(x - r)} ${n(y)}a${n(r)} ${n(r)} 0 1 0 ${n(2 * r)} 0a${n(r)} ${n(r)} 0 1 0 ${n(-2 * r)} 0Z`;
  return `M${outline.map(([px, py]) => `${n(x + px * r)} ${n(y + py * r)}`).join('L')}Z`;
}

/** Sets SVG attributes; a value of null is left out. */
function attrs(element, values) {
  for (const [k, v] of Object.entries(values)) {
    if (v !== null && v !== undefined) element.setAttribute(k, String(v));
  }
  return element;
}

const el = (name, values = {}) => attrs(document.createElementNS(SVG, name), values);

/** The paint of a symbol for the attributes of one feature (or its constants, without them). */
function paint(kind, symbol, properties) {
  const v = (k, fallback) => {
    const x = properties ? evaluate(symbol.style[k], properties) : constantOf(symbol.style[k], fallback);
    return x ?? fallback;
  };
  if (kind === 'line') {
    return { fill: 'none', stroke: v('stroke', '#444444'), 'stroke-opacity': v('strokeOpacity', 1), 'stroke-width': Math.max(0.5, v('strokeWidth', 1)),
      'stroke-dasharray': symbol.config.dash ? symbol.config.dash.join(' ') : null };
  }
  return { fill: v('fill', '#888888'), 'fill-opacity': kind === 'point' ? Math.max(0.05, v('fillOpacity', 1)) : v('fillOpacity', 1),
    stroke: v('stroke', '#444444'), 'stroke-width': v('strokeWidth', 1) };
}

/**
 * An SVG of one symbol: a point shape, a stretch of line, a small polygon; what the menu of symbol
 * types and the live preview show.
 *
 * @param kind point, line or polygon
 * @param symbol {config, style} of that geometry
 */
export function symbolSvg(kind, symbol, size = 28) {
  const svg = el('svg', { width: size, height: size, viewBox: '0 0 28 28', 'aria-hidden': 'true', 'data-kind': kind });
  const p = paint(kind, symbol);
  if (kind === 'point') {
    const s = Math.min(22, Math.max(6, constantOf(symbol.style.size, 8) * 1.4));
    svg.append(el('path', { d: shapePath(symbol.config.shape, 14, 14, s), 'data-shape': symbol.config.shape, ...p }));
  } else if (kind === 'line') {
    svg.append(el('path', { d: 'M3 22 C10 4 18 24 25 6', ...p, 'stroke-width': Math.min(6, p['stroke-width']) }));
  } else {
    svg.append(el('path', { d: 'M4 8 L16 3 L25 11 L22 24 L7 23 Z', ...p, 'stroke-width': Math.min(4, p['stroke-width']) }));
  }
  return svg;
}

/**
 * The miniature of the symbology of a document on a layer card (GP-006): a strip with the colours of
 * the classes of a graduated style in order, one chip per category colour up to limit (and how many
 * more), or a sample of the symbol of each geometry of a simple style.
 */
export function miniature(doc, { limit = 8, locale = 'en' } = {}) {
  const t = texts(locale);
  const sample = sampleOf(doc, locale);
  const box = document.createElement('span');
  box.setAttribute('role', 'img');
  if (sample.kind === 'graduated') {
    box.className = 'symbology graduated';
    box.setAttribute('aria-label', t.classes(sample.field, sample.labels));
    sample.colors.forEach((color, i) => {
      const c = document.createElement('span');
      c.className = 'class';
      c.style.background = color;
      c.dataset.color = color;
      c.title = sample.labels[i] ?? '';
      box.append(c);
    });
  } else if (sample.kind === 'categories') {
    box.className = 'symbology categories';
    box.setAttribute('aria-label', t.categories(sample.field, sample.labels));
    sample.colors.slice(0, limit).forEach((color, i) => {
      const c = document.createElement('span');
      c.className = 'chip';
      c.style.background = color;
      c.dataset.color = color;
      c.title = sample.labels[i] ?? '';
      box.append(c);
    });
    if (sample.colors.length > limit) {
      const more = document.createElement('span');
      more.className = 'more';
      more.textContent = `+${sample.colors.length - limit}`;
      more.title = t.more(sample.colors.length - limit);
      box.append(more);
    }
  } else {
    box.className = 'symbology simple';
    box.setAttribute('aria-label', t.simple);
    for (const kind of KINDS) {
      if (sample.symbols[kind]) box.append(symbolSvg(kind, sample.symbols[kind], 18));
    }
  }
  return box;
}

/**
 * The GetLegendGraphic of a layer from the map server, on a transparent background (GP-006), fetched
 * with the access of the person and shown when it arrives.
 *
 * @param fetcher the fetch of the shell (context.fetch), which carries the session and the organization
 * @param wms the address of the WMS of the layer, with or without a query
 * @param layer the name of the layer
 */
export function legendImage(fetcher, wms, layer, { locale = 'en', large = false } = {}) {
  const t = texts(locale);
  const box = document.createElement('span');
  box.className = `symbology legend${large ? ' large' : ''}`;
  const img = document.createElement('img');
  img.alt = t.legend(layer);
  box.append(img);
  const url = new URL(wms, location.origin);
  url.search = '';
  const query = {
    service: 'WMS', version: '1.1.1', request: 'GetLegendGraphic', format: 'image/png', layer, transparent: 'true',
    width: '16', height: '16', legend_options: 'fontAntiAliasing:true;fontSize:10;dx:3;forceLabels:on',
  };
  for (const [k, v] of Object.entries(query)) url.searchParams.set(k, v);
  box.dataset.src = url.pathname + url.search;
  void (async () => {
    try {
      const answer = await fetcher(box.dataset.src);
      if (!answer.ok || !(answer.headers.get('content-type') ?? '').startsWith('image/')) throw new Error(String(answer.status));
      img.src = URL.createObjectURL(await answer.blob());
      box.dataset.state = 'loaded';
    } catch {
      img.remove();
      box.textContent = t.noLegend;
      box.classList.add('muted');
      box.dataset.state = 'failed';
    }
  })();
  return box;
}

// The preview map -----------------------------------------------------------------------------------

/** Web Mercator, in degrees of longitude: x, y with y up. */
const mercator = ([lon, lat]) => {
  const l = Math.max(-85, Math.min(85, lat));
  return [lon, (Math.log(Math.tan(Math.PI / 4 + (l * Math.PI) / 360)) * 180) / Math.PI];
};

const KIND_OF = { Point: 'point', MultiPoint: 'point', LineString: 'line', MultiLineString: 'line', Polygon: 'polygon', MultiPolygon: 'polygon' };

/** The parts of a geometry, each {kind, coordinates}: collections are opened. */
function parts(geometry, out = []) {
  if (!geometry) return out;
  if (geometry.type === 'GeometryCollection') {
    for (const g of geometry.geometries ?? []) parts(g, out);
    return out;
  }
  const kind = KIND_OF[geometry.type];
  if (!kind) return out;
  if (geometry.type.startsWith('Multi')) for (const c of geometry.coordinates) out.push({ kind, coordinates: c });
  else out.push({ kind, coordinates: geometry.coordinates });
  return out;
}

/** The kinds a document draws, in the order a map draws them: polygons under lines under points. */
const ORDER = ['polygon', 'line', 'point'];

/**
 * Draws the features of a GeoJSON collection (WGS84) with a document into an SVG: the preview map of
 * the style editor (GP-973). Each feature takes the values its attributes give, as the map draws them.
 *
 * @param svg the SVG element, whose viewBox is drawn into
 * @param doc a valid document
 * @param collection a FeatureCollection
 */
export function drawPreview(svg, doc, collection) {
  const [, , width, height] = (svg.getAttribute('viewBox') ?? '0 0 320 200').split(/\s+/).map(Number);
  svg.replaceChildren();
  const features = (collection?.features ?? []).map((f) => ({ properties: f.properties ?? {}, parts: parts(f.geometry) }));
  const points = features.flatMap((f) => f.parts.flatMap((p) => flat(p)));
  if (points.length === 0) return 0;
  let [minX, maxX, minY, maxY] = [Infinity, -Infinity, Infinity, -Infinity];
  for (const c of points) {
    const [x, y] = mercator(c);
    [minX, maxX, minY, maxY] = [Math.min(minX, x), Math.max(maxX, x), Math.min(minY, y), Math.max(maxY, y)];
  }
  const pad = 16;
  const k = Math.min((width - 2 * pad) / (maxX - minX || 1e-9), (height - 2 * pad) / (maxY - minY || 1e-9));
  const scale = Number.isFinite(k) && maxX - minX + (maxY - minY) > 0 ? k : 1;
  const ox = (width - (maxX - minX) * scale) / 2;
  const oy = (height - (maxY - minY) * scale) / 2;
  const at = (c) => {
    const [x, y] = mercator(c);
    return [ox + (x - minX) * scale, height - (oy + (y - minY) * scale)];
  };
  const ring = (r) => `M${r.map((c) => at(c).map(n).join(' ')).join('L')}`;
  const symbols = Object.fromEntries(KINDS.map((kind) => [kind, symbolFor(doc, kind)]));
  let drawn = 0;
  for (const kind of ORDER) {
    const group = el('g', { 'data-kind': kind });
    for (const f of features) {
      for (const part of f.parts.filter((p) => p.kind === kind)) {
        const symbol = symbols[kind];
        const p = paint(kind, symbol, f.properties);
        let mark;
        if (kind === 'point') {
          const [x, y] = at(part.coordinates);
          const size = Math.max(1, evaluate(symbol.style.size, f.properties) ?? 8);
          mark = el('path', { d: shapePath(symbol.config.shape, x, y, size), 'data-shape': symbol.config.shape, ...p });
        } else if (kind === 'line') {
          mark = el('path', { d: ring(part.coordinates), ...p });
        } else {
          mark = el('path', { d: part.coordinates.map((r) => `${ring(r)}Z`).join(''), 'fill-rule': 'evenodd', ...p });
        }
        mark.setAttribute('class', 'feature');
        group.append(mark);
        drawn++;
      }
    }
    if (group.childNodes.length > 0) svg.append(group);
  }
  if (doc.label && typeof doc.label.text === 'object' && doc.label.text.field) {
    const group = el('g', { 'data-kind': 'label' });
    for (const f of features.slice(0, 200)) {
      const first = f.parts[0];
      if (!first) continue;
      const coords = flat(first);
      const [x, y] = at([coords.reduce((s, c) => s + c[0], 0) / coords.length, coords.reduce((s, c) => s + c[1], 0) / coords.length]);
      const text = el('text', {
        x: n(x), y: n(y - 6), 'text-anchor': 'middle', 'font-size': evaluate(doc.label.size, f.properties),
        fill: evaluate(doc.label.color, f.properties), stroke: doc.label.halo?.color ?? null, 'stroke-width': doc.label.halo?.width ?? null,
        'paint-order': 'stroke',
      });
      text.textContent = String(f.properties[doc.label.text.field] ?? '');
      group.append(text);
    }
    svg.append(group);
  }
  return drawn;
}

/** The positions of a part, flat. */
function flat(part) {
  if (part.kind === 'point') return [part.coordinates];
  if (part.kind === 'line') return part.coordinates;
  return part.coordinates.flat();
}
