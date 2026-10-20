// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { choiceOf, stateOf } from '../web/diagnosis.js';
import { launcherTiles } from '../web/launcher.js';

/**
 * GP-339 without a bench (node --test): the tiles of the launcher of the Geoportal apps from the apps of the
 * app bar of the person and the extensions active in the organization, and the state of the page of the
 * diagnosis in the address. The pages are proved on the bench (tests/console.spec.ts).
 */

const app = (extensionId: string, appId: string) => ({ extensionId, appId });

describe('GP-339 launcher', () => {
  test('GP-339 a tile for each Geoportal app the person may open, in the order catalog, maps, dashboards, processes', () => {
    const visible = [
      app('io.github.mosaikit.ext.processes', 'processes'),
      app('dev.geoportal.maps', 'maps'),
      app('io.github.mosaikit.ext.catalog', 'catalog'),
      app('dev.geoportal.ext.catalog.geodata', 'geodata'),
      app('org.example.notes', 'notes'),
      app('dev.geoportal.dashboards', 'dashboards'),
    ];
    const tiles = launcherTiles(visible, () => true, new Map([['dev.geoportal.maps/maps', '/app/mappe']]));
    assert.deepEqual(tiles.map((t) => [t.id, t.extension, t.route]), [
      ['catalog', 'io.github.mosaikit.ext.catalog', '/app/catalog'],
      ['maps', 'dev.geoportal.maps', '/app/mappe'],
      ['dashboards', 'dev.geoportal.dashboards', '/app/dashboards'],
      ['processes', 'io.github.mosaikit.ext.processes', '/app/processes'],
    ]);
  });

  test('GP-339 nothing the person may not open: apps not in their app bar, or of extensions not active', () => {
    assert.deepEqual(launcherTiles([app('org.example.notes', 'notes')], () => true), []);
    // Dashboards and processes appear only when their extension is present (MK-133).
    const visible = [app('dev.geoportal.dashboards', 'dashboards'), app('dev.geoportal.processes', 'processes')];
    assert.deepEqual(launcherTiles(visible, (id) => id !== 'dev.geoportal.processes').map((t) => t.id), ['dashboards']);
    // The catalog restricted for the person: the geodata app gives the tile when that one is shown.
    assert.deepEqual(launcherTiles([app('dev.geoportal.ext.catalog.geodata', 'geodata')], () => true).map((t) => t.route),
      ['/app/geodata']);
  });
});

describe('GP-339 diagnosis', () => {
  test('GP-339 the person and the layer of the diagnosis are kept in the address', () => {
    const state = stateOf('stazioni', 'anna@example.test');
    assert.equal(state, '/accesso?layer=stazioni&person=anna%40example.test');
    assert.deepEqual(choiceOf(state), { layer: 'stazioni', person: 'anna@example.test' });
    assert.deepEqual(choiceOf('/accesso'), { layer: '', person: '' });
    assert.equal(choiceOf(''), undefined);
    assert.equal(choiceOf('/altro'), undefined);
  });
});
