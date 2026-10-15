// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, test } from 'node:test';
import {
  defaultStyle,
  fromPanel,
  kindOf,
  migrate,
  representable,
  retype,
  same,
  shapeImage,
  SHAPE_PIXELS,
  toMapLibre,
  toPanel,
  translate,
  validate,
} from '../web/style-grammar.js';

/**
 * GP-009 in the browser, without a bench (node --test): the checks of web/style-grammar.js against the
 * fixtures the Java tests read too, the change of type, the panel of the editor as a facade over the
 * document, and the MapLibre adapter on which GeoLibre draws.
 */

const ROOT = join(import.meta.dirname, '..');
const read = (path: string): any => JSON.parse(readFileSync(join(ROOT, path), 'utf8'));
const EXAMPLES = join(ROOT, '..', '..', 'docs', 'style', 'examples');
const examples = readdirSync(EXAMPLES).filter((f) => f.endsWith('.json'));
const example = (name: string): any => JSON.parse(readFileSync(join(EXAMPLES, name), 'utf8'));

describe('GP-009 the grammar geoportal/style/v1', () => {
  test('GP-009 the examples of the documentation are valid documents', () => {
    assert.ok(examples.length >= 5);
    for (const f of examples) {
      assert.deepEqual(validate(example(f)), [], f);
    }
    for (const type of ['point', 'line', 'polygon', 'mixed']) {
      assert.deepEqual(validate(defaultStyle(type)), [], type);
    }
  });

  test('GP-009 an invalid document is refused with where and why, as the catalog refuses it', () => {
    for (const c of read('src/test/resources/style/invalid.json')) {
      const problems = validate(c.document);
      assert.ok(
        problems.some((p: { path: string; code: string }) => p.path === c.path && p.code === c.code),
        `${c.why}: ${JSON.stringify(problems)}`,
      );
    }
  });

  test('GP-009 changing the type keeps what has the same meaning, as the catalog changes it', () => {
    for (const c of read('src/test/resources/style/retype.json')) {
      const changed = retype(c.from, c.to);
      assert.deepEqual(validate(changed), [], c.why);
      assert.ok(same(changed, c.expected), `${c.why}: ${JSON.stringify(changed)}`);
    }
  });

  test('GP-009 switching a point style to a line style keeps colour, opacity and label and maps point size to line width', () => {
    const point = {
      grammar: 'geoportal/style/v1',
      type: 'point',
      config: { shape: 'triangle' },
      style: { fill: '#e76f51', fillOpacity: 0.7, stroke: '#264653', strokeWidth: 1, size: 14 },
      label: { text: { field: 'nome' }, color: '#264653', size: 11 },
    };
    const line = retype(point, 'line');
    assert.equal(line.type, 'line');
    assert.equal(line.style.stroke, '#e76f51');
    assert.equal(line.style.strokeOpacity, 0.7);
    assert.equal(line.style.strokeWidth, 14);
    assert.deepEqual(line.label, point.label);
    // The same through the panel of the editor, as the type select changes it.
    const panel = toPanel(retype(fromPanel(toPanel(point), point), 'line'));
    assert.deepEqual(panel, {
      type: 'line', stroke: '#e76f51', strokeOpacity: 0.7, strokeWidth: 14, labelField: 'nome', labelColor: '#264653', labelSize: 11,
    });
  });

  test('GP-009 converting a style editor to document to editor gives back the same panel values', () => {
    const panels = [
      { type: 'point', shape: 'star', fill: '#ff8800', fillOpacity: 0.8, stroke: '#000000', strokeWidth: 2, size: 18, labelField: 'nome', labelColor: '#111111', labelSize: 13 },
      { type: 'line', stroke: '#c0392b', strokeOpacity: 0.9, strokeWidth: 3.5, labelField: '', labelColor: '#333333', labelSize: 12 },
      { type: 'polygon', fill: '#2a9d8f', fillOpacity: 0.45, stroke: '#555555', strokeWidth: 0.5, labelField: 'uso', labelColor: '#000000', labelSize: 10 },
      { type: 'mixed', shape: 'square', fill: '#ff0000', fillOpacity: 1, stroke: '#000000', strokeOpacity: 1, strokeWidth: 1, size: 12, labelField: '', labelColor: '#333333', labelSize: 12 },
    ];
    for (const p of panels) {
      const doc = fromPanel(p);
      assert.deepEqual(validate(doc), [], p.type);
      // The document travels as JSON (the catalog keeps it so) and comes back to the panel.
      assert.deepEqual(toPanel(JSON.parse(JSON.stringify(doc))), p, p.type);
      assert.ok(representable(doc));
    }
    // What the panel does not show stays: the legend, the halo, the dashes.
    const line = example('line-dashed-labelled.json');
    assert.ok(representable(line));
    assert.ok(same(fromPanel(toPanel(line), line), line));
    // A style driven by the data is more than the panel shows: the editor says so.
    assert.equal(representable(example('polygon-categorized.json')), false);
    assert.equal(representable(example('mixed.json')), true);
  });

  test('GP-009 a style exported as JSON and applied to another layer draws identically', () => {
    for (const f of examples) {
      const doc = example(f);
      const exported = JSON.stringify(doc, null, 2);
      const a = toMapLibre(doc, { id: 'a', source: 'a', geometry: doc.type });
      const b = toMapLibre(JSON.parse(exported), { id: 'b', source: 'b', geometry: doc.type });
      const strip = (r: { layers: any[]; images: string[] }) =>
        JSON.stringify({ ...r, layers: r.layers.map(({ id, source, ...rest }) => ({ ...rest, id: id.replace(/^[ab]:/, '') })) });
      assert.equal(strip(a), strip(b), f);
    }
  });

  test('GP-009 a style of before the grammar becomes the document the catalog migrates it into', () => {
    const cases = read('src/test/resources/style/legacy.json');
    for (const c of cases) {
      assert.ok(same(migrate(c.legacy), c.expected), c.why);
    }
    assert.ok(same(defaultStyle('mixed'), cases[1].expected), 'the default style is the one of before');
    assert.ok(same(migrate(defaultStyle('line')), defaultStyle('line')), 'a document stays as it is');
  });

  test('GP-009 the geometry type the loading records is a kind of symbol', () => {
    assert.equal(kindOf('MultiPoint'), 'point');
    assert.equal(kindOf('MultiLineString'), 'line');
    assert.equal(kindOf('Polygon'), 'polygon');
    assert.equal(kindOf('Geometry'), 'mixed');
  });
});

describe('GP-009 the MapLibre adapter', () => {
  test('GP-009 values driven by the data become expressions of MapLibre', () => {
    const { layers } = toMapLibre(example('polygon-categorized.json'), { id: 'uso', source: 's', geometry: 'polygon' });
    assert.deepEqual(layers.map((l) => [l.id, l.type]), [['uso:polygon', 'fill'], ['uso:outline', 'line']]);
    assert.deepEqual(layers[0]!.paint['fill-color'], [
      'case',
      ['==', ['get', 'uso'], 'residenziale'], '#f4a261',
      ['==', ['get', 'uso'], 'produttivo'], '#8d99ae',
      ['==', ['get', 'uso'], 'verde'], '#2a9d8f',
      '#dddddd',
    ]);

    const stations = toMapLibre(example('point-graduated-scaled.json'), { id: 'st', source: 's', sourceLayer: 'stazioni' });
    assert.deepEqual(stations.images, ['geoportal-shape-triangle']);
    const [point, label] = stations.layers;
    assert.equal(point!.type, 'symbol');
    assert.equal(point!['source-layer'], 'stazioni');
    assert.deepEqual(point!.paint['icon-color'], [
      'case', ['==', ['typeof', ['get', 'livello']], 'number'],
      ['step', ['get', 'livello'], '#2a9d8f', 1.5, '#e9c46a', 3, '#e76f51'],
      '#2a9d8f',
    ]);
    assert.deepEqual(point!.layout['icon-size'], ['*', ['interpolate', ['linear'], ['get', 'portata'], 0, 6, 500, 24], 1 / SHAPE_PIXELS]);
    assert.deepEqual(label!.layout['text-field'], ['concat', ['to-string', ['get', 'nome']], ' (', ['to-string', ['get', 'livello']], ' m)']);
  });

  test('GP-009 a line keeps its dashes, a mixed layer filters each geometry, circles are circles', () => {
    const line = toMapLibre(example('line-dashed-labelled.json'), { id: 'l', source: 's' });
    assert.deepEqual(line.layers[0]!.paint['line-dasharray'], [3, 1.5]);
    assert.equal(line.layers[1]!.layout['symbol-placement'], 'line');
    assert.equal(line.layers[1]!.paint['text-halo-width'], 1.5);

    const mixed = toMapLibre(defaultStyle('mixed'), { id: 'm', source: 's', geometry: 'mixed' });
    assert.deepEqual(mixed.layers.map((l) => l.type), ['circle', 'line', 'fill', 'line']);
    assert.deepEqual(mixed.layers[1]!.filter, ['match', ['geometry-type'], ['LineString', 'MultiLineString'], true, false]);
    assert.equal(mixed.layers[0]!.paint['circle-radius'], 4);
    // A point style on a layer of lines is drawn as the line it becomes.
    const asLine = toMapLibre(example('point-simple.json'), { id: 'p', source: 's', geometry: 'line' });
    assert.deepEqual(asLine.layers[0]!.paint, { 'line-color': '#2f80ed', 'line-opacity': 0.45, 'line-width': 8 });
  });

  test('GP-009 the images of the shapes are signed distance fields', () => {
    for (const shape of ['square', 'triangle', 'star', 'cross', 'x', 'circle']) {
      const image = shapeImage(shape);
      const alpha = (x: number, y: number) => image.data[(y * image.width + x) * 4 + 3]!;
      const c = Math.floor(image.width / 2);
      assert.ok(alpha(c, c) > 191, `${shape} inside`);
      assert.ok(alpha(0, 0) < 191, `${shape} outside`);
    }
  });

  test('GP-009 an invalid document is not translated', () => {
    assert.throws(() => translate({ grammar: 'geoportal/style/v1', type: 'line' }, 'maplibre', { id: 'x', source: 's' }), /not valid/);
    assert.throws(() => translate(defaultStyle('line'), 'deck', { id: 'x', source: 's' }), /No adapter/);
  });
});
