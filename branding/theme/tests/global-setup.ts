// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
import { Bench, packagesIn } from '@mosaikit/testing';

/**
 * Starts Mosaikit with the package of this project (mvn package) on an embedded PostgreSQL, with an
 * organization and two people, and stops it after the tests. The installation is downloaded once;
 * MOSAIKIT_INSTALLATION chooses another one, such as a build of Mosaikit.
 */
export default async function globalSetup(): Promise<() => Promise<void>> {
  const extensions = packagesIn('target');
  if (extensions.length === 0) {
    throw new Error('Build first: mvn package');
  }
  const bench = await Bench.start({
    work: '.mosaikit-bench',
    extensions,
    // The packages are not signed by a publisher: their frontends run like verified ones.
    settings: {
      'mosaikit.extensions.unverified-frontends': 'module',
      // The theme of the installation, as in the distributions of Geoportal.
      'mosaikit.ui.theme': 'dev.geoportal.theme',
    },
  });
  bench.publish();
  return () => bench.stop();
}
