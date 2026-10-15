// SPDX-FileCopyrightText: 2026 Massimo Antonini
import { BENCH_PEOPLE, Bench, freePort, packagesIn } from '@mosaikit/testing';
import { MANAGER } from './people.js';

/**
 * Starts Mosaikit with the package of this project (mvn package) on an embedded PostgreSQL, with an
 * organization and three people, and stops it after the tests. The installation is downloaded once;
 * MOSAIKIT_INSTALLATION chooses another one, such as a build of Mosaikit.
 */
export default async function globalSetup(): Promise<() => Promise<void>> {
  const extensions = packagesIn('target');
  if (extensions.length === 0) {
    throw new Error('Build first: mvn package');
  }
  // With GEOPORTAL_TEST_LOADER=1, the loader of tests/geodata/compose.yaml (profile loader) downloads the
  // uploads from the bench through signed links: the bench listens on a port known in advance.
  const loader = process.env.GEOPORTAL_TEST_LOADER;
  const port = loader ? await freePort() : undefined;
  const bench = await Bench.start({
    work: '.mosaikit-bench',
    extensions,
    ...(port ? { port } : {}),
    // The third person becomes an administrator of the organization in the tests that need one.
    people: [...BENCH_PEOPLE, MANAGER],
    settings: {
      // The packages are not signed by a publisher: their frontends run like verified ones.
      'mosaikit.extensions.unverified-frontends': 'module',
      // GP-939: the stub services of the tests listen on the loopback, which the proxy refuses
      // unless the installation allows it.
      'geoportal.catalog.proxy.allowed-networks': '127.0.0.1/32',
      // A master key for the credentials of the tests (GP-334 reads those of GeoServer, GP-939 those
      // of external services, GP-937 that of the geodata database); not a secret.
      'mosaikit.secrets.master-key': 'Z2VvcG9ydGFsLWNhdGFsb2ctdGVzdHMtb25seS1rZXk=',
      // With GEOPORTAL_TEST_GEOSERVER=1, the GeoServer and PostGIS of tests/geoserver/compose.yaml.
      ...(process.env.GEOPORTAL_TEST_GEOSERVER
        ? {
            'geoportal.geoserver.url': 'http://127.0.0.1:18680/geoserver',
            'geoportal.geodata.jdbc-url': 'jdbc:postgresql://127.0.0.1:15434/geodata',
          }
        : { 'geoportal.geoserver.url': 'http://127.0.0.1:9/geoserver' }),
      // With GEOPORTAL_TEST_GEODATA=1 and no GeoServer, the lighter PostGIS of tests/geodata/compose.yaml (GP-937).
      ...(process.env.GEOPORTAL_TEST_GEODATA && !process.env.GEOPORTAL_TEST_GEOSERVER
        ? { 'geoportal.geodata.jdbc-url': 'jdbc:postgresql://127.0.0.1:15437/geodata' }
        : {}),
      ...(loader
        ? {
            'geoportal.geodata.jdbc-url': 'jdbc:postgresql://127.0.0.1:15437/geodata',
            'geoportal.catalog.loader.url': 'http://127.0.0.1:18090',
            'geoportal.catalog.loader.source-url': `http://host.docker.internal:${port}`,
          }
        : {}),
    },
    // For the tests that restart the installation with other settings (the samples of GP-936).
    controlPort: Number(process.env.CATALOG_BENCH_CONTROL_PORT ?? 47936),
  });
  bench.publish();
  return () => bench.stop();
}
