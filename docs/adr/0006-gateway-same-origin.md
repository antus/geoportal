<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# 0006. A gateway on the same origin for every data request

- Status: accepted
- Date: 2026-10-09
- Deciders: Massimo Antonini

## Context and problem statement

The browser must carry the session to data requests that MapLibre, deck.gl and Cesium make by
themselves, and nothing secret may travel inside a map project shared through the collaboration
relay.

## Decision

- GeoLibre runs on the origin of Geoportal, so every request to `/gw/…` carries the session cookie
  by itself.
- Every data address in a project is a gateway address (`/gw/ows/…`, `/gw/tiles/…`, `/gw/live/…`,
  `/gw/obj/…`), never the real address of GeoServer, a bucket or a database.
- In front of GeoServer the gateway is nginx with `auth_request`: Geoportal gives the go-ahead (with a
  short cache) and nginx adds the name of the person and passes the bytes. nginx always strips the
  name header from incoming requests; GeoServer accepts it only from the gateway and is not exposed.
- Towards object storage the gateway gives short pre-signed URLs or proxies range requests; towards
  databases it runs the published view with the data rule and returns vector tiles or FlatGeobuf;
  towards streams it filters messages by area and fields before SSE.
- The `deployment.json` of GeoLibre allows no PostGIS host, turns off its database connectors and
  puts the sidecar behind Geoportal.

## Consequences

- A reserved layer answers 403 and the plugin shows it as reserved; in a shared session each
  participant opens the same addresses with their own cookie.
- An organization that forbids external services gets it with the Content-Security-Policy of the
  GeoLibre page.
