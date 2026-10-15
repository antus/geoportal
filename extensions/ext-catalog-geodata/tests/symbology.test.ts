// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { defaultStyle, KINDS, toMapLibre, validate } from '../web/style-grammar.js';
import {
  categorize,
  classLabels,
  cleanBreaks,
  equalBreaks,
  evaluate,
  graduate,
  PALETTES,
  paletteColors,
  rangeValues,
  sampleOf,
  scale,
  sortValues,
  SYMBOL_TYPES,
  symbolTypeOf,
  valueOf,
  withLabel,
  withSymbolType,
  withValue,
} from '../web/style-themes.js';

/**
 * GP-973 and GP-006 in the browser, without a bench (node --test): the symbol types of each geometry,
 * a style per geometry, the classes and palettes of the thematic styles of web/style-themes.js, and
 * the sample of the symbology a layer card shows. The dialog and the cards are proved on the bench
 * (tests/symbology.spec.ts).
 */

const COLOR = /^#[0-9a-f]{6}$/;

describe('GP-973 symbol types per geometry', () => {
  test('GP-973 every symbol type of each geometry makes a valid document, read back as that type', () => {
    for (const kind of KINDS) {
      for (const base of [defaultStyle(kind), defaultStyle('mixed')]) {
        for (const type of SYMBOL_TYPES[kind]) {
          const doc = withSymbolType(base, kind, type);
          assert.deepEqual(validate(doc), [], `${kind} ${type}`);
          assert.equal(symbolTypeOf(doc, kind), type);
        }
      }
    }
  });

  test('GP-973 choosing a symbol type changes what the renderers draw and keeps the colours', () => {
    const square = withSymbolType(withValue(defaultStyle('point'), 'point', 'fill', '#e76f51'), 'point', 'square');
    const [layer] = toMapLibre(square, { id: 'x', source: 's' }).layers;
    assert.equal(layer?.layout?.['icon-image'], 'geoportal-shape-square');
    assert.equal(layer?.paint?.['icon-color'], '#e76f51');
    const dashed = withSymbolType(defaultStyle('line'), 'line', 'dashed');
    assert.ok(toMapLibre(dashed, { id: 'x', source: 's' }).layers[0]?.paint?.['line-dasharray']);
    const outline = withSymbolType(defaultStyle('polygon'), 'polygon', 'outline');
    assert.equal(outline.style.fillOpacity, 0);
    assert.equal(withSymbolType(outline, 'polygon', 'filled').style.fillOpacity, 0.45);
  });

  test('GP-973 a layer with points and polygons has a different style for each geometry', () => {
    let doc = defaultStyle('mixed');
    doc = withValue(doc, 'point', 'fill', '#ff0000');
    doc = withSymbolType(doc, 'point', 'star');
    doc = withValue(doc, 'polygon', 'fill', '#00aa00');
    assert.deepEqual(validate(doc), []);
    assert.equal(valueOf(doc, 'point', 'fill'), '#ff0000');
    assert.equal(valueOf(doc, 'polygon', 'fill'), '#00aa00');
    assert.equal(doc.geometries.point.config.shape, 'star');
    const layers = toMapLibre(doc, { id: 'x', source: 's' }).layers;
    const point = layers.find((l: any) => l.id === 'x:point');
    const polygon = layers.find((l: any) => l.id === 'x:polygon');
    assert.equal(point.paint['icon-color'], '#ff0000');
    assert.equal(polygon.paint['fill-color'], '#00aa00');
    assert.deepEqual(polygon.filter, ['match', ['geometry-type'], ['Polygon', 'MultiPolygon'], true, false]);
  });

  test('GP-973 the label of the panel keeps its halo and what the data drive', () => {
    let doc = withLabel(defaultStyle('point'), { field: 'nome', color: '#111111', size: 12 });
    doc = withValue(doc, 'point', 'size', scale('abitanti', 0, 100, 4, 20));
    doc = { ...doc, label: { ...doc.label, halo: { color: '#ffffff', width: 1 } } };
    doc = withValue(doc, 'point', 'labelColor', categorize('tipo', ['a'], ['#ff0000'], '#000000'));
    const again = withLabel(doc, { field: 'sigla', color: '#222222', size: 14 });
    assert.deepEqual(again.label.text, { field: 'sigla' });
    assert.equal(again.label.halo.color, '#ffffff');
    assert.equal(again.label.color.field, 'tipo');
    assert.equal(again.label.size, 14);
    assert.deepEqual(validate(again), []);
    assert.equal(withLabel(again, { field: '', color: '#000000', size: 12 }).label, undefined);
  });
});

describe('GP-973 thematic styles', () => {
  test('GP-973 a categorized style has one class per distinct value with colours from the chosen palette', () => {
    const values = sortValues(['parco naturale', 'riserva', 'oasi', 'area marina']);
    assert.deepEqual(values, ['area marina', 'oasi', 'parco naturale', 'riserva']);
    for (const palette of Object.keys(PALETTES)) {
      const colors = paletteColors(palette, values.length);
      const fill = categorize('tipo', values, colors, '#bbbbbb');
      assert.equal(fill.categories.length, values.length);
      assert.deepEqual(fill.categories.map((c: any) => c.equals), values);
      assert.deepEqual(fill.categories.map((c: any) => c.value), colors);
      for (const c of colors) assert.match(c, COLOR);
      const doc = withValue(defaultStyle('polygon'), 'polygon', 'fill', fill);
      assert.deepEqual(validate(doc), [], palette);
      assert.equal(evaluate(fill, { tipo: 'oasi' }), colors[1]);
      assert.equal(evaluate(fill, { tipo: 'altro' }), '#bbbbbb');
    }
    // A categorical palette repeats its colours, a sequential one goes from its first to its last anchor.
    assert.equal(paletteColors('vivid', 13)[12], PALETTES.vivid.colors[0]);
    const blues = paletteColors('blues', 4);
    assert.equal(blues[0], PALETTES.blues.colors[0]);
    assert.equal(blues[3], PALETTES.blues.colors[2]);
    assert.equal(new Set(blues).size, 4);
  });

  test('GP-973 a graduated style splits a number into classes, drawn as the step of the renderers', () => {
    const breaks = equalBreaks(8000, 60000, 4);
    assert.deepEqual(breaks, [21000, 34000, 47000]);
    assert.deepEqual(cleanBreaks([5, 5, 12.3456, 1], 1, 20), [5, 12.3]);
    assert.deepEqual(equalBreaks(3, 3, 5), []);
    const colors = paletteColors('reds', breaks.length + 1);
    const fill = graduate('abitanti', breaks, colors, classLabels(breaks, 'it-IT'));
    const doc = withValue(defaultStyle('polygon'), 'polygon', 'fill', fill);
    assert.deepEqual(validate(doc), []);
    assert.deepEqual(fill.graduated.labels, ['< 21.000', '21.000 – 34.000', '34.000 – 47.000', '≥ 47.000']);
    assert.equal(evaluate(fill, { abitanti: 20999 }), colors[0]);
    assert.equal(evaluate(fill, { abitanti: 21000 }), colors[1]);
    assert.equal(evaluate(fill, { abitanti: 99999 }), colors[3]);
    assert.equal(evaluate(fill, {}), colors[0]);
    // Any property can be bound: the size of the points, proportional to the value.
    const size = scale('abitanti', 0, 100, 4, 20);
    assert.deepEqual(validate(withValue(defaultStyle('point'), 'point', 'size', size)), []);
    assert.equal(evaluate(size, { abitanti: 50 }), 12);
    assert.deepEqual(rangeValues(1, 8, 3), [1, 4.5, 8]);
  });
});

describe('GP-006 the sample of the symbology of a layer card', () => {
  test('GP-006 a graduated layer gives the colours of its classes in order', () => {
    const colors = paletteColors('greens', 5);
    const doc = withValue(defaultStyle('polygon'), 'polygon', 'fill', graduate('abitanti', [10, 20, 30, 40], colors));
    const sample = sampleOf(doc, 'it-IT');
    assert.equal(sample.kind, 'graduated');
    assert.deepEqual(sample.colors, colors);
    assert.deepEqual(sample.labels, ['< 10', '10 – 20', '20 – 30', '30 – 40', '≥ 40']);
  });

  test('GP-006 a categorized layer gives one colour per category, in the mixed style too', () => {
    const values = Array.from({ length: 12 }, (_, i) => `c${i}`);
    const colors = paletteColors('vivid', 12);
    const doc = withValue(defaultStyle('mixed'), 'line', 'stroke', categorize('tipo', values, colors, '#bbbbbb'));
    const sample = sampleOf(doc);
    assert.equal(sample.kind, 'categories');
    assert.deepEqual(sample.colors, colors);
    assert.deepEqual(sample.labels, values);
    assert.equal(sample.geometry, 'line');
  });

  test('GP-006 a simple layer gives a sample of the symbol of each geometry it draws', () => {
    const sample = sampleOf(withSymbolType(defaultStyle('point'), 'point', 'triangle'));
    assert.equal(sample.kind, 'simple');
    assert.deepEqual(Object.keys(sample.symbols), ['point']);
    assert.equal(sample.symbols.point.config.shape, 'triangle');
    assert.equal(sample.symbols.point.style.fill, '#2f80ed');
    // A number driven by the data with constant colours is still a simple sample.
    const sized = withValue(defaultStyle('mixed'), 'point', 'size', scale('n', 0, 1, 4, 20));
    const simple = sampleOf(sized);
    assert.equal(simple.kind, 'simple');
    assert.equal(simple.symbols.point.style.size, 4);
    assert.deepEqual(Object.keys(simple.symbols), ['point', 'line', 'polygon']);
  });
});
