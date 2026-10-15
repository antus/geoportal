<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# Architecture decision records

Decisions of Geoportal, in the format of the records of Mosaikit (MADR). Generic decisions
(identity, roles, extensions, shell, distributions) are recorded in Mosaikit. A decision is
changed by a new record that supersedes it, never by editing it.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | Accepted |
| [0002](0002-a-solution-on-mosaikit.md) | Geoportal is a solution of Mosaikit extensions | Accepted |
| [0003](0003-geolibre-map-engine.md) | GeoLibre as the embedded map engine | Accepted |
| [0004](0004-data-in-postgis-and-geoserver.md) | Data in PostGIS and GeoServer, loaded by Geoportal | Accepted |
| [0005](0005-geoportal-the-authority-on-data-permissions.md) | Geoportal decides, GeoServer and the gateway enforce | Accepted |
| [0006](0006-gateway-same-origin.md) | A gateway on the same origin for every data request | Accepted |
| [0007](0007-authkey-for-desktop-clients.md) | AuthKey for QGIS and desktop clients | Accepted |
| [0008](0008-data-rules-distinct-from-permissions.md) | Data rules are distinct from permissions | Accepted |
| [0009](0009-rndt-metadata-with-pycsw.md) | RNDT metadata and a CSW catalog with pycsw | Accepted |
| [0010](0010-geoflow-process-engine.md) | GeoFlow stays the process engine | Accepted |
| [0011](0011-collaboration-on-the-geolibre-relay.md) | Collaboration on the GeoLibre relay with a Geoportal identity | Accepted |
| [0012](0012-packagings-on-mosaikit-distributions.md) | Packagings built on the Mosaikit distributions | Accepted |
| [0013](0013-requirements-plan-and-mockups.md) | Requirements, master plan and mockups | Accepted |
| [0014](0014-the-catalog-extension-owns-geoserver.md) | The catalog extension owns GeoServer and GeoFence | Accepted |
| [0015](0015-vectors-written-directly-into-postgis.md) | Vectors are written directly into PostGIS, not through the Importer | Accepted |
| [0016](0016-geoserver-reads-roles-and-trusts-only-the-gateway.md) | GeoServer reads roles from Mosaikit and trusts only the gateway | Accepted |
| [0017](0017-geoserver-aligned-to-the-catalog.md) | GeoServer is aligned to the catalog, one space per organization | Accepted |
| [0018](0018-the-proxy-reaches-only-registered-public-services.md) | The proxy of external services reaches only registered public services | Accepted |
| [0019](0019-files-loaded-by-gdal-in-a-sidecar.md) | Files are loaded by GDAL in a sidecar; built-in readers are the fallback | Accepted |
| [0020](0020-publishing-is-a-step-and-layers-are-served-through-the-catalog.md) | Publishing is a step of its own, and published layers are served through the catalog | Accepted |
| [0021](0021-geofence-rules-written-as-a-whole-per-organization.md) | GeoFence rules are generated and written as a whole for each organization | Accepted |
| [0022](0022-the-gateway-asks-as-the-person.md) | The data gateway asks the catalog as the person, and the gate of the catalog remains for links | Accepted |
| [0023](0023-layer-data-written-by-assigned-surveyors.md) | The data of a layer are written by the surveyors assigned to it | Accepted |
| [0024](0024-geoportal-composes-mosaikit-ext.md) | Geoportal composes the common extensions of mosaikit-ext and keeps the geospatial ones | Accepted |
| [0025](0025-surveyors-limited-by-data-rules-write-within-them.md) | Surveyors limited by data rules write within them | Accepted |
| [0026](0026-the-loader-image-reads-kml-with-libkml.md) | The loader runs on the official GDAL image alpine-normal, which reads KML with LIBKML | Accepted |
| [0027](0027-ext-catalog-geodata-its-own-id-api-and-schema.md) | ext-catalog-geodata has its own id, API and schema, and adopts the data of the catalog | Accepted |
