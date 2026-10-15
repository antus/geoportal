// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * The style grammar geoportal/style/v1 (GP-009), in the browser: the checks of a style document, the
 * change of its type, the panel of the style editor as a facade over the document, and the adapter
 * that translates a document into the layers of MapLibre, on which GeoLibre draws (GP-340). The
 * reference is docs/style/README.md of the repository, the JSON Schema next to it; the catalog checks
 * and translates the same documents into SLD in Java (dev.geoportal.ext.catalog.geodata.style), and the
 * fixtures of src/test/resources/style keep the two in step.
 *
 * Pure functions on plain JSON, no DOM: the GeoLibre plugin of the maps can bundle this file as it is.
 */

export const GRAMMAR = 'geoportal/style/v1';
export const TYPES = ['point', 'line', 'polygon', 'mixed'];
export const KINDS = ['point', 'line', 'polygon'];
export const SHAPES = ['circle', 'square', 'triangle', 'star', 'cross', 'x'];

const COLOR = /^#[0-9a-fA-F]{6}$/;
const FIELD = /^[A-Za-z_][A-Za-z0-9_]{0,62}$/;
const TEMPLATE = /^([^{}]|\{[A-Za-z_][A-Za-z0-9_]{0,62}\})*$/;
/** The ranges of the numbers a value may take, by what the value is. */
const NUMBERS = { opacity: [0, 1], width: [0, 64], size: [1, 64], textSize: [6, 72] };

/** The properties of the style block of each type, and what each one is. */
const STYLE = {
  point: { fill: 'color', fillOpacity: 'opacity', stroke: 'color', strokeWidth: 'width', size: 'size' },
  line: { stroke: 'color', strokeOpacity: 'opacity', strokeWidth: 'width' },
  polygon: { fill: 'color', fillOpacity: 'opacity', stroke: 'color', strokeWidth: 'width' },
};

/** The style a layer gets when nobody chose one: blue, as before the grammar (GP-114). */
const DEFAULTS = {
  point: { config: { shape: 'circle' }, style: { fill: '#2f80ed', fillOpacity: 0.45, stroke: '#1b4f9c', strokeWidth: 1.5, size: 8 } },
  line: { config: {}, style: { stroke: '#1b4f9c', strokeOpacity: 1, strokeWidth: 1.5 } },
  polygon: { config: {}, style: { fill: '#2f80ed', fillOpacity: 0.45, stroke: '#1b4f9c', strokeWidth: 1.5 } },
};
export const LABEL_DEFAULTS = { color: '#333333', size: 12 };

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const clone = (v) => (v === undefined ? undefined : JSON.parse(JSON.stringify(v)));

/** point, line, polygon or mixed, from the geometry type the loading recorded (Point, MultiLineString, ...). */
export function kindOf(geometryType) {
  const g = String(geometryType ?? '').toLowerCase();
  if (g.includes('point')) return 'point';
  if (g.includes('line') || g.includes('curve')) return 'line';
  if (g.includes('polygon') || g.includes('surface')) return 'polygon';
  return 'mixed';
}

/** The default document of a type. */
export function defaultStyle(type = 'mixed') {
  if (type === 'mixed') {
    return { grammar: GRAMMAR, type, geometries: clone({ point: DEFAULTS.point, line: DEFAULTS.line, polygon: DEFAULTS.polygon }) };
  }
  return { grammar: GRAMMAR, type, ...clone(DEFAULTS[type]) };
}

/**
 * A style written before the grammar (the six values of GP-114: fill, fillOpacity, stroke, strokeWidth,
 * pointSize, pointShape) as a document: mixed, so that each value is kept and each geometry is drawn as
 * it was, as the catalog migrates it. Any other value is given back as it is.
 */
export function migrate(style) {
  if (!isObject(style) || 'grammar' in style || !('fill' in style) || !('pointShape' in style)) return style;
  const { fill, fillOpacity, stroke, strokeWidth, pointSize, pointShape } = style;
  const lower = (c) => (typeof c === 'string' ? c.toLowerCase() : c);
  return {
    grammar: GRAMMAR,
    type: 'mixed',
    geometries: {
      point: { config: { shape: pointShape }, style: { fill: lower(fill), fillOpacity, stroke: lower(stroke), strokeWidth, size: pointSize } },
      line: { config: {}, style: { stroke: lower(stroke), strokeOpacity: 1, strokeWidth } },
      polygon: { config: {}, style: { fill: lower(fill), fillOpacity, stroke: lower(stroke), strokeWidth } },
    },
  };
}

// Checks --------------------------------------------------------------------------------------------

/**
 * The problems of a document, empty when it is valid: each {path, code, ...params}, path a JSON
 * pointer. The same checks as the JSON Schema, and those a schema cannot say (breaks in increasing
 * order, as many values as classes, categories told apart, from lower than to).
 */
export function validate(doc) {
  const out = [];
  if (!isObject(doc)) {
    out.push({ path: '', code: 'object' });
    return out;
  }
  keys(doc, ['$schema', 'grammar', 'type', 'config', 'style', 'geometries', 'label', 'legend'], '', out);
  if (doc.grammar !== GRAMMAR) out.push({ path: '/grammar', code: 'grammar' });
  if (!TYPES.includes(doc.type)) {
    out.push({ path: '/type', code: 'enum', allowed: TYPES });
    return out;
  }
  if (doc.$schema !== undefined && typeof doc.$schema !== 'string') out.push({ path: '/$schema', code: 'string' });
  if (doc.type === 'mixed') {
    for (const k of ['config', 'style']) if (k in doc) out.push({ path: `/${k}`, code: 'unknown' });
    if (!isObject(doc.geometries)) {
      out.push({ path: '/geometries', code: 'required' });
    } else {
      keys(doc.geometries, KINDS, '/geometries', out);
      for (const k of KINDS) {
        if (!isObject(doc.geometries[k])) out.push({ path: `/geometries/${k}`, code: 'required' });
        else {
          keys(doc.geometries[k], ['config', 'style'], `/geometries/${k}`, out);
          symbol(k, doc.geometries[k], `/geometries/${k}`, out);
        }
      }
    }
  } else {
    if ('geometries' in doc) out.push({ path: '/geometries', code: 'unknown' });
    symbol(doc.type, doc, '', out);
  }
  if ('label' in doc) label(doc.label, '/label', out);
  if ('legend' in doc) {
    if (!isObject(doc.legend)) out.push({ path: '/legend', code: 'object' });
    else {
      keys(doc.legend, ['title', 'show'], '/legend', out);
      if ('title' in doc.legend) text(doc.legend.title, 200, '/legend/title', out);
      if ('show' in doc.legend && typeof doc.legend.show !== 'boolean') out.push({ path: '/legend/show', code: 'boolean' });
    }
  }
  return out;
}

function keys(object, allowed, path, out) {
  for (const k of Object.keys(object)) if (!allowed.includes(k)) out.push({ path: `${path}/${k}`, code: 'unknown' });
}

function text(v, max, path, out, min = 0) {
  if (typeof v !== 'string' || v.length < min || v.length > max) out.push({ path, code: 'string', max });
}

function symbol(kind, s, path, out) {
  const config = s.config;
  if (!isObject(config)) out.push({ path: `${path}/config`, code: 'required' });
  else if (kind === 'point') {
    keys(config, ['shape'], `${path}/config`, out);
    if (!SHAPES.includes(config.shape)) out.push({ path: `${path}/config/shape`, code: 'enum', allowed: SHAPES });
  } else if (kind === 'line') {
    keys(config, ['dash'], `${path}/config`, out);
    if ('dash' in config) {
      const d = config.dash;
      if (!Array.isArray(d) || d.length < 2 || d.length > 8) out.push({ path: `${path}/config/dash`, code: 'dash' });
      else d.forEach((n, i) => constant(n, [0.5, 64], `${path}/config/dash/${i}`, out));
    }
  } else {
    keys(config, [], `${path}/config`, out);
  }
  if (!isObject(s.style)) {
    out.push({ path: `${path}/style`, code: 'required' });
    return;
  }
  const props = STYLE[kind];
  keys(s.style, Object.keys(props), `${path}/style`, out);
  for (const [name, what] of Object.entries(props)) {
    if (!(name in s.style)) out.push({ path: `${path}/style/${name}`, code: 'required' });
    else value(s.style[name], what, `${path}/style/${name}`, out);
  }
}

function label(l, path, out) {
  if (!isObject(l)) {
    out.push({ path, code: 'object' });
    return;
  }
  keys(l, ['text', 'color', 'size', 'halo'], path, out);
  const t = l.text;
  if (typeof t === 'string') text(t, 200, `${path}/text`, out, 1);
  else if (isObject(t) && 'field' in t) {
    keys(t, ['field'], `${path}/text`, out);
    field(t.field, `${path}/text/field`, out);
  } else if (isObject(t) && 'template' in t) {
    keys(t, ['template'], `${path}/text`, out);
    if (typeof t.template !== 'string' || t.template.length < 1 || t.template.length > 200 || !TEMPLATE.test(t.template)) {
      out.push({ path: `${path}/text/template`, code: 'template' });
    }
  } else out.push({ path: `${path}/text`, code: 'text' });
  for (const [name, what] of [['color', 'color'], ['size', 'textSize']]) {
    if (!(name in l)) out.push({ path: `${path}/${name}`, code: 'required' });
    else value(l[name], what, `${path}/${name}`, out);
  }
  if ('halo' in l) {
    if (!isObject(l.halo)) out.push({ path: `${path}/halo`, code: 'object' });
    else {
      keys(l.halo, ['color', 'width'], `${path}/halo`, out);
      constant(l.halo.color, 'color', `${path}/halo/color`, out);
      constant(l.halo.width, [0, 10], `${path}/halo/width`, out);
    }
  }
}

function field(f, path, out) {
  if (typeof f !== 'string' || !FIELD.test(f)) out.push({ path, code: 'field' });
}

/** A constant: a colour, or a number in a range ([min, max] or the name of one). */
function constant(v, what, path, out) {
  if (what === 'color') {
    if (typeof v !== 'string' || !COLOR.test(v)) out.push({ path, code: 'color' });
    return;
  }
  const [min, max] = Array.isArray(what) ? what : NUMBERS[what];
  if (typeof v !== 'number' || !Number.isFinite(v) || v < min || v > max) out.push({ path, code: 'number', min, max });
}

function value(v, what, path, out) {
  if (!isObject(v)) {
    constant(v, what, path, out);
    return;
  }
  keys(v, ['field', 'categories', 'otherwise', 'graduated', 'scaled'], path, out);
  field(v.field, `${path}/field`, out);
  const drivers = ['categories', 'graduated', 'scaled'].filter((k) => k in v);
  if (drivers.length > 1) out.push({ path, code: 'driver' });
  if ('otherwise' in v !== 'categories' in v) out.push({ path, code: 'driver' });
  if ('categories' in v) {
    const c = v.categories;
    if (!Array.isArray(c) || c.length < 1 || c.length > 100) out.push({ path: `${path}/categories`, code: 'count' });
    else {
      const seen = new Set();
      c.forEach((item, i) => {
        const p = `${path}/categories/${i}`;
        if (!isObject(item)) {
          out.push({ path: p, code: 'object' });
          return;
        }
        keys(item, ['equals', 'value', 'label'], p, out);
        if (!['string', 'number', 'boolean'].includes(typeof item.equals)) out.push({ path: `${p}/equals`, code: 'scalar' });
        const key = `${typeof item.equals}:${String(item.equals)}`;
        if (seen.has(key)) out.push({ path: `${p}/equals`, code: 'duplicate' });
        seen.add(key);
        if (!('value' in item)) out.push({ path: `${p}/value`, code: 'required' });
        else constant(item.value, what, `${p}/value`, out);
        if ('label' in item) text(item.label, 100, `${p}/label`, out);
      });
    }
    if ('otherwise' in v) constant(v.otherwise, what, `${path}/otherwise`, out);
  }
  if ('graduated' in v) {
    const g = v.graduated;
    const p = `${path}/graduated`;
    if (!isObject(g)) out.push({ path: p, code: 'object' });
    else {
      keys(g, ['breaks', 'values', 'labels'], p, out);
      const b = g.breaks;
      if (!Array.isArray(b) || b.length < 1 || b.length > 50 || b.some((n) => typeof n !== 'number' || !Number.isFinite(n))) {
        out.push({ path: `${p}/breaks`, code: 'breaks' });
      } else if (b.some((n, i) => i > 0 && n <= b[i - 1])) out.push({ path: `${p}/breaks`, code: 'breaks' });
      const count = Array.isArray(b) ? b.length + 1 : 0;
      if (!Array.isArray(g.values) || g.values.length !== count) out.push({ path: `${p}/values`, code: 'count', count });
      else g.values.forEach((x, i) => constant(x, what, `${p}/values/${i}`, out));
      if ('labels' in g) {
        if (!Array.isArray(g.labels) || g.labels.length !== count) out.push({ path: `${p}/labels`, code: 'count', count });
        else g.labels.forEach((x, i) => text(x, 100, `${p}/labels/${i}`, out));
      }
    }
  }
  if ('scaled' in v) {
    const s = v.scaled;
    const p = `${path}/scaled`;
    if (!isObject(s)) out.push({ path: p, code: 'object' });
    else {
      keys(s, ['from', 'to'], p, out);
      const f = s.from;
      if (!Array.isArray(f) || f.length !== 2 || f.some((n) => typeof n !== 'number' || !Number.isFinite(n)) || !(f[0] < f[1])) {
        out.push({ path: `${p}/from`, code: 'from' });
      }
      if (!Array.isArray(s.to) || s.to.length !== 2) out.push({ path: `${p}/to`, code: 'count', count: 2 });
      else s.to.forEach((x, i) => constant(x, what, `${p}/to/${i}`, out));
    }
  }
}

// Types ---------------------------------------------------------------------------------------------

/** Applies fn to every number a value can give: the constant, or each output of its driver. */
function mapNumbers(v, fn) {
  if (typeof v === 'number') return fn(v);
  if (!isObject(v)) return v;
  const r = clone(v);
  if (r.categories) {
    r.categories = r.categories.map((c) => ({ ...c, value: fn(c.value) }));
    r.otherwise = fn(r.otherwise);
  }
  if (r.graduated) r.graduated.values = r.graduated.values.map(fn);
  if (r.scaled) r.scaled.to = r.scaled.to.map(fn);
  return r;
}
const clamp = (min, max) => (v) => mapNumbers(v, (n) => Math.min(max, Math.max(min, n)));

/** The symbol (config and style) of a single type, changed into another type: what has the same meaning stays. */
function retypeSymbol(from, to, s) {
  if (from === to) return { config: clone(s.config), style: clone(s.style) };
  const st = s.style;
  // The colour, the opacity and the weight of a symbol: what its type draws first.
  const color = from === 'line' ? st.stroke : st.fill;
  const opacity = from === 'line' ? st.strokeOpacity : st.fillOpacity;
  const weight = from === 'point' ? st.size : st.strokeWidth;
  if (to === 'line') {
    return { config: {}, style: { stroke: clone(color), strokeOpacity: clone(opacity), strokeWidth: clamp(0, 64)(clone(weight)) } };
  }
  if (to === 'point') {
    const outline = from === 'line' ? { stroke: clone(color), strokeWidth: 1 } : { stroke: clone(st.stroke), strokeWidth: clone(st.strokeWidth) };
    const size = from === 'line' ? clamp(1, 64)(clone(weight)) : DEFAULTS.point.style.size;
    return { config: { shape: 'circle' }, style: { fill: clone(color), fillOpacity: clone(opacity), ...outline, size } };
  }
  // A polygon.
  const outline = from === 'line' ? { stroke: clone(color), strokeWidth: clone(weight) } : { stroke: clone(st.stroke), strokeWidth: clone(st.strokeWidth) };
  return { config: {}, style: { fill: clone(color), fillOpacity: clone(opacity), ...outline } };
}

/**
 * The document with another type: colour, opacity, label and legend stay, a point size becomes a line
 * width and back; a mixed document keeps the symbol of each geometry, and a mixed one becomes the
 * symbol of the geometry asked for.
 */
export function retype(doc, type) {
  if (!TYPES.includes(type)) throw new Error(`Unknown type ${type}`);
  const out = { grammar: GRAMMAR, type };
  if (doc.$schema !== undefined) out.$schema = doc.$schema;
  if (type === 'mixed') {
    out.geometries = doc.type === 'mixed' ? clone(doc.geometries)
      : Object.fromEntries(KINDS.map((k) => [k, retypeSymbol(doc.type, k, doc)]));
  } else {
    Object.assign(out, doc.type === 'mixed' ? clone(doc.geometries[type]) : retypeSymbol(doc.type, type, doc));
  }
  if (doc.label !== undefined) out.label = clone(doc.label);
  if (doc.legend !== undefined) out.legend = clone(doc.legend);
  return out;
}

/** The symbol ({config, style}) that draws the features of one geometry kind with this document. */
export function symbolFor(doc, kind) {
  if (doc.type === 'mixed') return clone(doc.geometries[kind]);
  return retypeSymbol(doc.type, kind, doc);
}

// The editor panel ----------------------------------------------------------------------------------

/** The fields of the panel of the editor, by type, after type; the label fields come last. */
export const PANEL = {
  point: ['shape', 'fill', 'fillOpacity', 'stroke', 'strokeWidth', 'size'],
  line: ['stroke', 'strokeOpacity', 'strokeWidth'],
  polygon: ['fill', 'fillOpacity', 'stroke', 'strokeWidth'],
  mixed: ['shape', 'fill', 'fillOpacity', 'stroke', 'strokeOpacity', 'strokeWidth', 'size'],
};
export const LABEL_PANEL = ['labelField', 'labelColor', 'labelSize'];

/** The constant a value shows in the panel and in previews: itself, or the first output of its driver. */
export function constantOf(v, fallback) {
  return shown(v, fallback);
}

function shown(v, fallback) {
  if (!isObject(v)) return typeof v === 'string' ? v.toLowerCase() : v;
  if (v.categories) return shown(v.otherwise, fallback);
  if (v.graduated) return shown(v.graduated.values[0], fallback);
  if (v.scaled) return shown(v.scaled.to[0], fallback);
  return fallback;
}

/** The values of the panel of the editor for a document: {type, the fields of PANEL[type], labelField, labelColor, labelSize}. */
export function toPanel(doc) {
  const p = { type: doc.type };
  const main = doc.type === 'mixed' ? doc.geometries.point : doc;
  const line = doc.type === 'mixed' ? doc.geometries.line : doc;
  const defaults = { ...DEFAULTS.point.style, ...DEFAULTS.line.style, shape: 'circle' };
  for (const k of PANEL[doc.type]) {
    if (k === 'shape') p.shape = main.config.shape;
    else if (k === 'strokeOpacity') p.strokeOpacity = shown(line.style.strokeOpacity, defaults.strokeOpacity);
    else p[k] = shown(main.style[k], defaults[k]);
  }
  const l = doc.label;
  p.labelField = l && isObject(l.text) && typeof l.text.field === 'string' ? l.text.field : '';
  p.labelColor = l ? shown(l.color, LABEL_DEFAULTS.color) : LABEL_DEFAULTS.color;
  p.labelSize = l ? shown(l.size, LABEL_DEFAULTS.size) : LABEL_DEFAULTS.size;
  return p;
}

/**
 * The document of the values of the panel. What the panel does not show is taken from base when it
 * still applies: the legend, the halo of the label, the dashes of a line.
 */
export function fromPanel(p, base = undefined) {
  const color = (v) => String(v).toLowerCase();
  const point = () => ({
    config: { shape: p.shape },
    style: { fill: color(p.fill), fillOpacity: p.fillOpacity, stroke: color(p.stroke), strokeWidth: p.strokeWidth, size: p.size },
  });
  const polygon = () => ({ config: {}, style: { fill: color(p.fill), fillOpacity: p.fillOpacity, stroke: color(p.stroke), strokeWidth: p.strokeWidth } });
  const line = () => {
    const before = base?.type === 'line' ? base.config : base?.type === 'mixed' ? base.geometries?.line?.config : undefined;
    return { config: before?.dash ? { dash: clone(before.dash) } : {}, style: { stroke: color(p.stroke), strokeOpacity: p.strokeOpacity, strokeWidth: p.strokeWidth } };
  };
  const doc = { grammar: GRAMMAR, type: p.type };
  if (p.type === 'mixed') doc.geometries = { point: point(), line: line(), polygon: polygon() };
  else Object.assign(doc, { point, line, polygon }[p.type]());
  if (p.labelField) {
    doc.label = { text: { field: p.labelField }, color: color(p.labelColor), size: p.labelSize };
    if (base?.label?.halo) doc.label.halo = clone(base.label.halo);
  }
  if (base?.legend !== undefined) doc.legend = clone(base.legend);
  return doc;
}

/** Whether the panel shows all of a document, so that editing it in the panel loses nothing. */
export function representable(doc) {
  return same(fromPanel(toPanel(doc), doc), doc);
}

/** Two documents say the same: equal JSON, colours in any case, keys in any order; $schema does not count. */
export function same(a, b) {
  const norm = (v) => {
    if (typeof v === 'string' && COLOR.test(v)) return v.toLowerCase();
    if (Array.isArray(v)) return v.map(norm);
    if (isObject(v)) {
      return Object.fromEntries(Object.keys(v).filter((k) => k !== '$schema').sort().map((k) => [k, norm(v[k])]));
    }
    return v;
  };
  return JSON.stringify(norm(a)) === JSON.stringify(norm(b));
}

// Renderer adapters ---------------------------------------------------------------------------------

/** The size in pixels of the drawing of a shape in the images of shapeImage, without its buffer. */
export const SHAPE_PIXELS = 32;
const SHAPE_BUFFER = 8;

/** A value as an expression of MapLibre: a constant, or the data through get, case, step, interpolate. */
function expr(v) {
  if (!isObject(v)) return typeof v === 'string' ? v.toLowerCase() : v;
  const get = ['get', v.field];
  if (v.categories) {
    return ['case', ...v.categories.flatMap((c) => [['==', get, c.equals], expr(c.value)]), expr(v.otherwise)];
  }
  if (v.graduated) {
    const { breaks, values } = v.graduated;
    const step = ['step', get, expr(values[0]), ...breaks.flatMap((b, i) => [b, expr(values[i + 1])])];
    // A feature without a number takes the first class, as the filters of the SLD give it.
    return ['case', ['==', ['typeof', get], 'number'], step, expr(values[0])];
  }
  if (v.scaled) {
    const { from, to } = v.scaled;
    return ['interpolate', ['linear'], get, from[0], expr(to[0]), from[1], expr(to[1])];
  }
  return get;
}

/** A constant with a lower bound, as the SLD of the catalog draws it; an expression as it is. */
const atLeast = (v, min) => (typeof v === 'number' ? Math.max(v, min) : expr(v));
const scaledBy = (v, factor) => (typeof v === 'number' ? v * factor : ['*', expr(v), factor]);

function labelText(t) {
  if (typeof t === 'string') return t;
  if (typeof t.field === 'string') return ['to-string', ['get', t.field]];
  const parts = [];
  for (const m of t.template.matchAll(/\{([A-Za-z_][A-Za-z0-9_]*)\}|[^{}]+/g)) {
    parts.push(m[1] ? ['to-string', ['get', m[1]]] : m[0]);
  }
  return ['concat', ...parts];
}

const GEOMETRY_TYPES = { point: ['Point', 'MultiPoint'], line: ['LineString', 'MultiLineString'], polygon: ['Polygon', 'MultiPolygon'] };

/**
 * The MapLibre adapter: the layers of a MapLibre style that draw a document on a source, and the
 * names of the images of shapes they use (add each with map.addImage(name, shapeImage(shape), {sdf: true})).
 *
 * @param doc a valid document
 * @param options {id, source, sourceLayer (vector tiles), geometry: point, line, polygon or mixed}
 * @returns {{layers: object[], images: string[]}}
 */
export function toMapLibre(doc, options) {
  const { id, source, sourceLayer } = options;
  const geometry = options.geometry ?? doc.type;
  const kinds = geometry === 'mixed' ? KINDS : [geometry];
  const layers = [];
  const images = new Set();
  const base = (suffix, type, kind) => {
    const layer = { id: `${id}:${suffix}`, type, source };
    if (sourceLayer) layer['source-layer'] = sourceLayer;
    if (geometry === 'mixed' && kind) layer.filter = ['match', ['geometry-type'], GEOMETRY_TYPES[kind], true, false];
    return layer;
  };
  for (const kind of kinds) {
    const { config, style: s } = symbolFor(doc, kind);
    if (kind === 'point' && config.shape === 'circle') {
      layers.push({
        ...base('point', 'circle', kind),
        paint: {
          'circle-color': expr(s.fill),
          'circle-opacity': atLeast(s.fillOpacity, 0.05),
          'circle-radius': scaledBy(s.size, 0.5),
          'circle-stroke-color': expr(s.stroke),
          'circle-stroke-width': expr(s.strokeWidth),
        },
      });
    } else if (kind === 'point') {
      const image = `geoportal-shape-${config.shape}`;
      images.add(image);
      layers.push({
        ...base('point', 'symbol', kind),
        layout: { 'icon-image': image, 'icon-size': scaledBy(s.size, 1 / SHAPE_PIXELS), 'icon-allow-overlap': true },
        paint: {
          'icon-color': expr(s.fill),
          'icon-opacity': atLeast(s.fillOpacity, 0.05),
          'icon-halo-color': expr(s.stroke),
          'icon-halo-width': expr(s.strokeWidth),
        },
      });
    } else if (kind === 'line') {
      const paint = { 'line-color': expr(s.stroke), 'line-opacity': expr(s.strokeOpacity), 'line-width': atLeast(s.strokeWidth, 0.5) };
      if (config.dash) {
        // MapLibre counts dashes in line widths, the grammar in pixels.
        const width = typeof s.strokeWidth === 'number' ? Math.max(s.strokeWidth, 0.5) : 1;
        paint['line-dasharray'] = config.dash.map((n) => n / width);
      }
      layers.push({ ...base('line', 'line', kind), paint });
    } else {
      layers.push({ ...base('polygon', 'fill', kind), paint: { 'fill-color': expr(s.fill), 'fill-opacity': expr(s.fillOpacity) } });
      layers.push({ ...base('outline', 'line', kind), paint: { 'line-color': expr(s.stroke), 'line-width': expr(s.strokeWidth) } });
    }
  }
  if (doc.label) {
    const l = doc.label;
    const layer = {
      ...base('label', 'symbol'),
      layout: { 'text-field': labelText(l.text), 'text-size': expr(l.size), 'symbol-placement': geometry === 'line' ? 'line' : 'point' },
      paint: { 'text-color': expr(l.color) },
    };
    if (l.halo) Object.assign(layer.paint, { 'text-halo-color': l.halo.color.toLowerCase(), 'text-halo-width': l.halo.width });
    layers.push(layer);
  }
  return { layers, images: [...images] };
}

/** The vertices of each shape, in a square from -1 to 1, y down. */
function outline(shape) {
  const polar = (n, r, turn = 0) => Array.from({ length: n }, (_, i) => {
    const a = -Math.PI / 2 + turn + (i * 2 * Math.PI) / n;
    return [Math.cos(a) * r(i), Math.sin(a) * r(i)];
  });
  const plus = (w) => [[-w, -1], [w, -1], [w, -w], [1, -w], [1, w], [w, w], [w, 1], [-w, 1], [-w, w], [-1, w], [-1, -w], [-w, -w]];
  switch (shape) {
    case 'square': return [[-1, -1], [1, -1], [1, 1], [-1, 1]];
    case 'triangle': return polar(3, () => 1);
    case 'star': return polar(10, (i) => (i % 2 === 0 ? 1 : 0.4));
    case 'cross': return plus(0.25);
    case 'x': return plus(0.25).map(([x, y]) => [(x - y) * Math.SQRT1_2, (x + y) * Math.SQRT1_2]);
    default: return null;
  }
}

/**
 * The image of a shape for the symbols of MapLibre, as a signed distance field (sdf: true), so that
 * icon-color and icon-halo-color paint it: {width, height, data} with the field in the alpha channel.
 */
export function shapeImage(shape) {
  const size = SHAPE_PIXELS + 2 * SHAPE_BUFFER;
  const data = new Uint8ClampedArray(size * size * 4);
  const polygon = outline(shape);
  const half = SHAPE_PIXELS / 2;
  for (let py = 0; py < size; py++) {
    for (let px = 0; px < size; px++) {
      const x = (px + 0.5 - size / 2) / half;
      const y = (py + 0.5 - size / 2) / half;
      let d;
      if (!polygon) d = Math.hypot(x, y) - 1;
      else {
        let inside = false;
        let min = Infinity;
        for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
          const [xi, yi] = polygon[i];
          const [xj, yj] = polygon[j];
          if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
          const dx = xj - xi;
          const dy = yj - yi;
          const t = Math.max(0, Math.min(1, ((x - xi) * dx + (y - yi) * dy) / (dx * dx + dy * dy)));
          min = Math.min(min, Math.hypot(x - xi - t * dx, y - yi - t * dy));
        }
        d = inside ? -min : min;
      }
      // As the SDF of MapLibre: 191 on the edge, one step of 255/8 per pixel.
      const alpha = Math.round(255 - 255 * ((d * half) / SHAPE_BUFFER + 0.25));
      const o = (py * size + px) * 4;
      data[o] = data[o + 1] = data[o + 2] = 255;
      data[o + 3] = Math.max(0, Math.min(255, alpha));
    }
  }
  return { width: size, height: size, data };
}

/** The renderer adapters of the grammar in the browser, by engine; the SLD of GeoServer is translated by the catalog. */
export const adapters = { maplibre: toMapLibre };

/** Translates a document for an engine of adapters. */
export function translate(doc, engine, options) {
  const adapter = adapters[engine];
  if (!adapter) throw new Error(`No adapter for ${engine}`);
  const problems = validate(doc);
  if (problems.length > 0) throw new Error(`The style is not valid: ${problems.map((p) => `${p.path || '/'} ${p.code}`).join(', ')}`);
  return adapter(doc, options);
}
