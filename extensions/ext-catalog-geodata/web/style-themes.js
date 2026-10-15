// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * The thematic styles and the symbol types of the style editor (GP-973), and the sample of the
 * symbology a layer card shows (GP-006), on documents of geoportal/style/v1 (GP-009, ADR-0029).
 *
 * The grammar already says everything a thematic style is: a value driven by an attribute, with
 * {@code categories}, {@code graduated} or {@code scaled}. This module only builds those values (classes
 * from the distinct values or the statistics of an attribute, colours from a palette), edits one
 * property of one geometry of a document without touching the others, evaluates a value for a feature
 * as the renderers do, and reads back the classes and colours of a document for its legend.
 *
 * Pure functions on plain JSON, no DOM: the GeoLibre plugin of the maps (GP-340) bundles it as it is,
 * next to web/style-grammar.js.
 */

import { constantOf, KINDS, SHAPES, STYLE } from './style-grammar.js';

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const clone = (v) => (v === undefined ? undefined : JSON.parse(JSON.stringify(v)));

// Palettes ------------------------------------------------------------------------------------------

/**
 * The palettes of the editor: categorical ones repeat their colours, sequential and diverging ones are
 * interpolated between their anchors for as many classes as asked.
 */
export const PALETTES = {
  vivid: { kind: 'categorical', colors: ['#1f6fd1', '#e8590c', '#2b9348', '#c92a2a', '#7048e8', '#a0522d', '#d6336c', '#5c940d', '#1098ad', '#f08c00', '#495057', '#862e9c'] },
  soft: { kind: 'categorical', colors: ['#8ecae6', '#ffb703', '#90be6d', '#f4a261', '#cdb4db', '#e5989b', '#a8dadc', '#ffd6a5', '#b5e48c', '#bde0fe', '#ffc8dd', '#d8e2dc'] },
  earth: { kind: 'categorical', colors: ['#6b705c', '#bc6c25', '#606c38', '#9c6644', '#a5a58d', '#dda15e', '#283618', '#cb997e', '#7f5539', '#b7b7a4', '#8d99ae', '#ddbea9'] },
  blues: { kind: 'sequential', colors: ['#eaf2fb', '#5b9bd5', '#0b3d78'] },
  greens: { kind: 'sequential', colors: ['#edf8e9', '#5fb35f', '#0b5d1e'] },
  reds: { kind: 'sequential', colors: ['#fdeee8', '#e8684a', '#8a0f0f'] },
  heat: { kind: 'sequential', colors: ['#fff3b0', '#f9a03f', '#c1121f', '#5c0a0a'] },
  diverging: { kind: 'diverging', colors: ['#b2182b', '#f7f4f0', '#2166ac'] },
};

const hex = (n) => Math.round(Math.min(255, Math.max(0, n))).toString(16).padStart(2, '0');
const rgb = (c) => [1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16));

/** The colour at t (0 to 1) along the anchors, interpolated in RGB. */
function along(anchors, t) {
  const x = Math.min(1, Math.max(0, t)) * (anchors.length - 1);
  const i = Math.min(anchors.length - 2, Math.floor(x));
  const [a, b] = [rgb(anchors[i]), rgb(anchors[i + 1])];
  const f = x - i;
  return `#${a.map((v, k) => hex(v + (b[k] - v) * f)).join('')}`;
}

/** n colours of a palette, in order. */
export function paletteColors(id, n) {
  const p = PALETTES[id];
  if (!p) throw new Error(`Unknown palette ${id}`);
  if (p.kind === 'categorical') return Array.from({ length: n }, (_, i) => p.colors[i % p.colors.length]);
  if (n === 1) return [along(p.colors, 0.5)];
  return Array.from({ length: n }, (_, i) => along(p.colors, i / (n - 1)));
}

/** n numbers evenly from from to to, rounded to two decimals. */
export function rangeValues(from, to, n) {
  if (n === 1) return [round((from + to) / 2)];
  return Array.from({ length: n }, (_, i) => round(from + ((to - from) * i) / (n - 1)));
}

function round(n, digits = 2) {
  const f = 10 ** digits;
  return Math.round(n * f) / f;
}

// Classes -------------------------------------------------------------------------------------------

/** Distinct values in a stable order: false before true, numbers ascending, texts alphabetically. */
export function sortValues(values) {
  const rank = (v) => (typeof v === 'boolean' ? 0 : typeof v === 'number' ? 1 : 2);
  return [...values].sort((a, b) => rank(a) - rank(b) || (typeof a === 'string' ? a.localeCompare(b) : Number(a) - Number(b)));
}

/**
 * A categorized value: one class per distinct value, with the outputs in the same order.
 *
 * @param values the distinct values (texts, numbers, true or false), at most 100
 * @param outputs a colour or a number per value
 * @param otherwise what features with another value take
 */
export function categorize(field, values, outputs, otherwise) {
  if (values.length < 1 || values.length > 100) throw new Error('From 1 to 100 categories');
  return {
    field,
    categories: values.map((v, i) => ({ equals: v, value: outputs[i], label: String(v) })),
    otherwise,
  };
}

/** A graduated value: as many outputs as breaks plus one, with their labels. */
export function graduate(field, breaks, outputs, labels = undefined) {
  if (outputs.length !== breaks.length + 1) throw new Error('One output more than the breaks');
  const graduated = { breaks: [...breaks], values: [...outputs] };
  if (labels) graduated.labels = [...labels];
  return { field, graduated };
}

/** A value scaled linearly from [min, max] of the attribute to [from, to]. */
export function scale(field, min, max, from, to) {
  return { field, scaled: { from: [min, max], to: [from, to] } };
}

/** A number rounded to what tells the classes apart: three significant digits at least. */
function nice(n, span) {
  if (!Number.isFinite(n)) return n;
  const digits = Math.max(0, 2 - Math.floor(Math.log10(Math.abs(span) || 1)));
  return round(n, Math.min(digits, 6));
}

/** Breaks sorted, rounded, strictly increasing, within (min, max) when given. */
export function cleanBreaks(breaks, min = -Infinity, max = Infinity) {
  const span = Number.isFinite(max - min) ? max - min : Math.max(...breaks.map(Math.abs), 1);
  const out = [];
  for (const b of [...breaks].sort((x, y) => x - y)) {
    const r = nice(b, span);
    if (Number.isFinite(r) && r > min && r <= max && (out.length === 0 || r > out[out.length - 1])) out.push(r);
  }
  return out.slice(0, 50);
}

/** The breaks of n classes of equal width between min and max; none when min is max. */
export function equalBreaks(min, max, n) {
  if (!(max > min) || n < 2) return [];
  return cleanBreaks(Array.from({ length: n - 1 }, (_, i) => min + ((max - min) * (i + 1)) / n), min, max);
}

/** The labels of the classes of breaks: below the first, between two, from the last on. */
export function classLabels(breaks, locale = 'en') {
  const f = (n) => new Intl.NumberFormat(locale, { maximumFractionDigits: 6 }).format(n);
  return [
    `< ${f(breaks[0])}`,
    ...breaks.slice(1).map((b, i) => `${f(breaks[i])} – ${f(b)}`),
    `≥ ${f(breaks[breaks.length - 1])}`,
  ];
}

// Values for a feature ------------------------------------------------------------------------------

/**
 * The constant a value takes for the attributes of one feature, as MapLibre (toMapLibre) and the SLD
 * of the catalog draw it: categories by equality, graduated by step (a feature without a number takes
 * the first class), scaled linearly within its ends.
 */
export function evaluate(v, properties = {}) {
  if (!isObject(v)) return typeof v === 'string' ? v.toLowerCase() : v;
  const x = properties?.[v.field];
  if (v.categories) {
    const hit = v.categories.find((c) => c.equals === x) ?? v.categories.find((c) => x !== undefined && x !== null && String(c.equals) === String(x));
    return evaluate(hit ? hit.value : v.otherwise);
  }
  if (v.graduated) {
    const { breaks, values } = v.graduated;
    if (typeof x !== 'number') return evaluate(values[0]);
    let i = 0;
    while (i < breaks.length && x >= breaks[i]) i++;
    return evaluate(values[i]);
  }
  if (v.scaled) {
    const { from, to } = v.scaled;
    const [a, b] = to.map((o) => evaluate(o));
    if (typeof x !== 'number') return a;
    const t = Math.min(1, Math.max(0, (x - from[0]) / (from[1] - from[0])));
    if (typeof a === 'number') return a + (b - a) * t;
    return along([a, b], t);
  }
  return undefined;
}

/** The attribute a value is driven by, or null for a constant. */
export function boundTo(v) {
  return isObject(v) && typeof v.field === 'string' ? v.field : null;
}

/** How a value is driven: constant, categories, graduated or scaled. */
export function modeOf(v) {
  if (!isObject(v)) return 'constant';
  return ['categories', 'graduated', 'scaled'].find((k) => k in v) ?? 'constant';
}

// Editing one property of one geometry --------------------------------------------------------------

/** The properties of the style of each geometry and what each one is (color, opacity, width, size). */
export const PROPERTIES = STYLE;
/** The properties of a label that data can drive. */
export const LABEL_PROPERTIES = { labelColor: 'color', labelSize: 'textSize' };

/** The symbol ({config, style}) a document draws one geometry with, by reference: what to edit. */
function symbolIn(doc, kind) {
  if (doc.type === 'mixed') return doc.geometries[kind];
  if (kind !== doc.type) throw new Error(`A ${doc.type} style has no ${kind} symbol`);
  return doc;
}

/** The geometry whose symbol the editor edits: that of the type, or the one chosen in a mixed style. */
export function editedKind(doc, chosen) {
  return doc.type === 'mixed' ? (KINDS.includes(chosen) ? chosen : 'point') : doc.type;
}

/** The value of a property of a geometry (or labelColor, labelSize of the label). */
export function valueOf(doc, kind, property) {
  if (property in LABEL_PROPERTIES) return doc.label?.[property === 'labelColor' ? 'color' : 'size'];
  return symbolIn(doc, kind).style[property];
}

/** The document with a property of one geometry (or of the label) set to a value; the rest stays. */
export function withValue(doc, kind, property, value) {
  const out = clone(doc);
  if (property in LABEL_PROPERTIES) {
    if (!out.label) throw new Error('No label to style');
    out.label[property === 'labelColor' ? 'color' : 'size'] = clone(value);
  } else {
    symbolIn(out, kind).style[property] = clone(value);
  }
  return out;
}

/**
 * The document with the label of the panel: the attribute it shows (none removes it), its colour and
 * size when they are constants, its halo and what data drive kept.
 */
export function withLabel(doc, { field, color, size }) {
  const out = clone(doc);
  if (!field) {
    delete out.label;
    return out;
  }
  const before = out.label ?? {};
  out.label = { text: { field } };
  out.label.color = isObject(before.color) ? before.color : String(color).toLowerCase();
  out.label.size = isObject(before.size) ? before.size : size;
  if (before.halo) out.label.halo = before.halo;
  return out;
}

/** The document with the shape of the points (or another config) of one geometry. */
export function withConfig(doc, kind, config) {
  const out = clone(doc);
  symbolIn(out, kind).config = clone(config);
  return out;
}

/**
 * The symbol types of each geometry the grammar draws, MapLibre and the SLD of GeoServer alike: the
 * shapes of a point, a solid, dashed or dotted line, a filled polygon or its outline only.
 */
export const SYMBOL_TYPES = {
  point: SHAPES,
  line: ['solid', 'dashed', 'dotted'],
  polygon: ['filled', 'outline'],
};
const DASHES = { dashed: [8, 4], dotted: [1.5, 3] };
const FILLED_OPACITY = 0.45;

/** The symbol type of a geometry of a document. */
export function symbolTypeOf(doc, kind) {
  const s = symbolIn(doc, kind);
  if (kind === 'point') return s.config.shape;
  if (kind === 'line') {
    const d = s.config.dash;
    if (!d) return 'solid';
    return d[0] <= 2 ? 'dotted' : 'dashed';
  }
  return s.style.fillOpacity === 0 ? 'outline' : 'filled';
}

/** The document with another symbol type for a geometry: what has nothing to do with the type stays. */
export function withSymbolType(doc, kind, type) {
  if (!SYMBOL_TYPES[kind]?.includes(type)) throw new Error(`Unknown ${kind} symbol ${type}`);
  const out = clone(doc);
  const s = symbolIn(out, kind);
  if (kind === 'point') s.config = { ...s.config, shape: type };
  else if (kind === 'line') {
    s.config = { ...s.config };
    if (type === 'solid') delete s.config.dash;
    else s.config.dash = [...DASHES[type]];
  } else if (type === 'outline') s.style.fillOpacity = 0;
  else if (s.style.fillOpacity === 0) s.style.fillOpacity = FILLED_OPACITY;
  return out;
}

// The sample of a layer card (GP-006) ---------------------------------------------------------------

/** The colour properties of a geometry, the first that draws it first. */
const COLORS = { point: ['fill', 'stroke'], line: ['stroke'], polygon: ['fill', 'stroke'] };

/**
 * What a layer card shows of a document: the classes of its first colour driven by the data, or a
 * single sample of each geometry it draws.
 *
 * @returns {{kind: 'categories', field, colors, labels, otherwise} | {kind: 'graduated', field, colors, labels}
 *     | {kind: 'simple', symbols: {point?, line?, polygon?}}} where each symbol has constant colours
 */
export function sampleOf(doc, locale = 'en') {
  const kinds = doc.type === 'mixed' ? KINDS : [doc.type];
  for (const kind of kinds) {
    const s = symbolIn(doc, kind);
    for (const property of COLORS[kind]) {
      const v = s.style[property];
      if (!isObject(v)) continue;
      if (v.categories) {
        return {
          kind: 'categories',
          field: v.field,
          geometry: kind,
          colors: v.categories.map((c) => String(c.value).toLowerCase()),
          labels: v.categories.map((c) => c.label ?? String(c.equals)),
          otherwise: String(v.otherwise).toLowerCase(),
        };
      }
      if (v.graduated) {
        const g = v.graduated;
        return {
          kind: 'graduated',
          field: v.field,
          geometry: kind,
          colors: g.values.map((c) => String(c).toLowerCase()),
          labels: g.labels ?? classLabels(g.breaks, locale),
        };
      }
      if (v.scaled) {
        return {
          kind: 'graduated',
          field: v.field,
          geometry: kind,
          colors: v.scaled.to.map((c) => String(c).toLowerCase()),
          labels: v.scaled.from.map((n) => String(n)),
        };
      }
    }
  }
  const symbols = {};
  for (const kind of kinds) {
    const s = symbolIn(doc, kind);
    const symbol = { config: clone(s.config), style: {} };
    for (const [k, what] of Object.entries(STYLE[kind])) {
      const fallback = what === 'color' ? '#888888' : what === 'opacity' ? 1 : what === 'size' ? 8 : 1;
      symbol.style[k] = constantOf(s.style[k], fallback);
    }
    symbols[kind] = symbol;
  }
  return { kind: 'simple', symbols };
}
