<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# 0020. Publishing is a step of its own, and published layers are served through the catalog

- Status: accepted
- Date: 2026-10-13
- Deciders: Massimo Antonini

## Context and problem statement

The first implementation of GP-937 defined a GeoServer layer for every loaded vector, named as its
table, which the next alignment published: every upload became a service without anyone choosing its
name or style, and nothing listed it as a service. GP-114 asks for one publishing path (destination,
layer name, the style translated for GeoServer, a service in the catalog with its health) and GP-118
for a lifecycle without orphans. The addresses of the service must be those of Geoportal, never the
internal one of GeoServer, but the gateway that lets GeoServer decide for each person through GeoFence
(GP-335, GP-336, ADR-0016) does not exist yet.

## Decision

- **Publishing is a step.** Loading makes a dataset `loaded`; a person with the permission `publish`
  and the level `manage` on it publishes it with a layer name and a style of Geoportal, and only then
  is it `published`. The layers already defined by loading are kept as the publications of their
  datasets.
- **Each action aligns at once.** Publish, republish, repoint and unpublish change the definitions of
  the catalog and run the alignment of ADR-0017 in the same request, so that the answer says whether
  GeoServer took it. A publication GeoServer refuses is withdrawn and aligned away; the failure is
  explained with a code, what to do, what GeoServer said and a reference written to the log (GP-115).
- **A layer owns its style**, named as the layer and translated into SLD from the style of Geoportal,
  which the catalog keeps to translate it again when the layer is repointed to data of another
  geometry. Unpublishing withdraws both; republishing recomputes the bounding boxes and deletes the
  cached tiles of GeoWebCache.
- **The services of a layer are served by the catalog** at
  `/api/v1/ext/catalog/publications/{layer}/ogc/{wms|wfs|ows|gwc/service/wmts}`, after the level `view`
  of the person on the dataset, as a gate: only the virtual services of that layer, only read requests,
  only that layer when a request names layers, no style by reference; the answers name the gate, never
  GeoServer. Until the gateway exists, the gate reaches GeoServer as its administrator; the gateway
  (`/gw/…`, ADR-0006) replaces it without changing the addresses people copied, which stay on the
  origin of Geoportal.

## Consequences

- An upload is not a service until someone publishes it; the samples are loaded, not published.
- The gate checks the sharing of the dataset for every request, a tile included; desktop clients
  cannot use it until AuthKey (ADR-0007) and the gateway, because it takes the session of Mosaikit.
- Removing GeoFence rules and catalog records on unpublishing comes with GP-335 and the pycsw catalog
  (ADR-0009); the maps that use a layer can be shown before unpublishing once the maps are on GeoLibre.
