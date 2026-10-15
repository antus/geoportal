#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 Massimo Antonini
/**
 * The packages of the extensions of mosaikit-ext that Geoportal composes (ADR-0024, GP-983): until they
 * are published, built from the commit pinned in mosaikit-ext.json, once, into
 * target/mosaikit-ext/packages at the root of the repository.
 *
 *   node tools/mosaikit-ext.mjs                      prints the directory of the packages, building them when missing
 *   node tools/mosaikit-ext.mjs --bench -- <args>    then runs mosaikit-bench with <args> and the packages
 *
 * MOSAIKIT_EXT_SOURCE=<a clone of mosaikit-ext> builds them from that working tree instead, at every run
 * (to try a change of mosaikit-ext with Geoportal). Needs git, Maven and Java 25, and the parent of
 * Mosaikit extensions in the local Maven repository, as every extension of this repository does.
 */
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const WORK = join(ROOT, 'target', 'mosaikit-ext');
const PACKAGES = join(WORK, 'packages');
const STAMP = join(PACKAGES, 'built-from.txt');
const WINDOWS = process.platform === 'win32';

/**
 * Runs a command; on Windows through the shell, which alone starts the .cmd of Maven and npm, as one
 * quoted line.
 */
function spawn(command, args, options) {
  if (!WINDOWS) {
    return spawnSync(command, args, options);
  }
  const line = [command, ...args].map((part) => (/[\s"&|<>^]/.test(part) ? `"${part.replaceAll('"', '""')}"` : part));
  return spawnSync(line.join(' '), { ...options, shell: true });
}

function run(command, args, cwd) {
  const result = spawn(command, args, { cwd, stdio: ['ignore', 2, 2] }); // stdout holds only the directory
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} failed in ${cwd} (exit ${result.status ?? result.signal})`);
  }
}

/** The zips of the directory, by name. */
function zips(directory) {
  return existsSync(directory) ? readdirSync(directory).filter((name) => name.endsWith('.zip')).sort() : [];
}

/** The working tree of mosaikit-ext at the pinned commit: a shallow fetch of that commit only. */
function checkout(pin) {
  const source = join(WORK, 'src');
  if (!existsSync(join(source, '.git'))) {
    mkdirSync(source, { recursive: true });
    run('git', ['init', '-q'], source);
    run('git', ['remote', 'add', 'origin', pin.repository], source);
  }
  run('git', ['-c', 'advice.detachedHead=false', 'fetch', '-q', '--depth', '1', 'origin', pin.commit], source);
  run('git', ['-c', 'advice.detachedHead=false', 'checkout', '-q', '--force', pin.commit], source);
  return source;
}

export function packages() {
  const pin = JSON.parse(readFileSync(join(ROOT, 'mosaikit-ext.json'), 'utf8'));
  const local = process.env.MOSAIKIT_EXT_SOURCE ? resolve(process.env.MOSAIKIT_EXT_SOURCE) : undefined;
  const wanted = local ? `local ${local}` : `${pin.repository} ${pin.commit}`;
  const built = existsSync(STAMP) ? readFileSync(STAMP, 'utf8').trim() : '';
  if (!local && built === wanted && zips(PACKAGES).length === pin.extensions.length) {
    return PACKAGES;
  }
  const source = local ?? checkout(pin);
  rmSync(PACKAGES, { recursive: true, force: true });
  mkdirSync(PACKAGES, { recursive: true });
  for (const extension of pin.extensions) {
    const folder = join(source, extension);
    // The library of the contracts of the extension (MK-134), such as ext-catalog-api: installed into the
    // local Maven repository, where the extension and those of Geoportal that implement them find it.
    if (existsSync(join(folder, 'api', 'pom.xml'))) {
      run('mvn', ['-B', '-q', '-ntp', '-f', 'api', 'install', '-DskipTests'], folder);
    }
    process.stderr.write(`mosaikit-ext: building ${extension} (${local ?? pin.commit.slice(0, 12)})\n`);
    // Only the package: its tests run in the CI of mosaikit-ext.
    run('mvn', ['-B', '-q', '-ntp', 'package', '-DskipTests', '-Dskip.npm=true'], folder);
    const made = zips(join(folder, 'target'));
    if (made.length !== 1) {
      throw new Error(`${extension}: expected one package in ${join(folder, 'target')}, found ${made.length}`);
    }
    copyFileSync(join(folder, 'target', made[0]), join(PACKAGES, made[0]));
  }
  writeFileSync(STAMP, `${wanted}\n`);
  return PACKAGES;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const directory = packages();
    const at = process.argv.indexOf('--bench');
    if (at < 0) {
      process.stdout.write(`${directory}\n`);
    } else {
      const rest = process.argv.slice(at + 1);
      const args = [...(rest[0] === '--' ? rest.slice(1) : rest), ...zips(directory).map((name) => join(directory, name))];
      const bench = spawn('mosaikit-bench', args, { cwd: process.cwd(), stdio: 'inherit' });
      process.exit(bench.status ?? 1);
    }
  } catch (error) {
    process.stderr.write(`mosaikit-ext: ${error.message}\n`);
    process.exit(1);
  }
}
