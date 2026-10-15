// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { execFileSync } from 'node:child_process';
import { packagesIn } from '@mosaikit/testing';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/** The root of the repository of Geoportal, two folders above this extension. */
const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

/** The manifest ids of the extensions of mosaikit-ext this extension requires (GP-983). */
export const FILE_MANAGER = 'io.github.mosaikit.ext.catalog';
export const CONNECTIONS = 'io.github.mosaikit.ext.catalog.connection';
export const GEODATA = 'dev.geoportal.ext.catalog.geodata';

/**
 * The packages of the extensions of mosaikit-ext that the installation of the tests holds next to this
 * one: those of MOSAIKIT_EXT_PACKAGES (a directory of zips) when set, else those that
 * tools/mosaikit-ext.mjs builds from the pinned commit (once; then they are reused).
 */
export function mosaikitExt(): string[] {
  const given = process.env.MOSAIKIT_EXT_PACKAGES;
  const directory =
    given ??
    execFileSync(process.execPath, [join(ROOT, 'tools', 'mosaikit-ext.mjs')], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'inherit'],
    })
      .trim()
      .split(/\r?\n/)
      .at(-1)!;
  const found = packagesIn(directory);
  if (found.length === 0) {
    throw new Error(`No package of mosaikit-ext in ${directory}`);
  }
  return found;
}
