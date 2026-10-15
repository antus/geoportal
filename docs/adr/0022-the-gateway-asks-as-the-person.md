<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0022. The data gateway asks the catalog as the person, and the gate of the catalog remains for links

- Status: accepted
- Date: 2026-10-14
- Deciders: Massimo Antonini

## Context and problem statement

GP-336 builds the gateway of ADR-0006 and ADR-0016: nginx with `auth_request` in front of GeoServer, the
session cookie for browsers, personal keys (AuthKey) for QGIS, links of Mosaikit, and the quota of the
organization. Mosaikit offers gateways a service identity (MK-124) with `POST /api/v1/services/check`,
which turns a session cookie or a personal key into a person, an organization and roles. But a call of a
service identity has no organization: the `Quotas` of Mosaikit (MK-076), the row-level security of the
catalog and the `Sharing` of links all act on the organization of the request, so a go-ahead asked as the
service could neither count nor refuse requests beyond the quota of the organization. The gate of the
catalog (ADR-0020) reaches GeoServer as its administrator; with the gateway GeoServer can decide for each
person through GeoFence, but a link has no person GeoServer could know.

## Decision

- **The go-ahead is asked as the person.** For `/gw/ows/<organization>/[<layer>/]<service>` nginx calls
  `GET /api/v1/ext/catalog/gateway/check` with the session cookie and `X-Mosaikit-Organization`, or with
  the personal key of a desktop client (`?authkey=`) as a bearer token. Mosaikit itself refuses an ended
  session (MK-125), a revoked key, a guest and another organization; the catalog checks that the session
  or key acts for the organization of the address, counts the request on the measure
  `dev.geoportal.catalog/data-requests` and answers the username and the workspace. A small script of
  nginx (njs) keeps the answer ten seconds per credential and organization, counts the requests it lets
  through meanwhile, and brings them to the next go-ahead, so that the quota counts every request
  without asking Geoportal for each tile.
- **The service identity is for the calls without a person**: the AuthKey filter of GeoServer asks
  `GET /api/v1/ext/catalog/gateway/authkey?key=` through the internal door of the gateway, which adds the
  key of the identity `gateway` (API `catalog`, endpoint `check-key`); the catalog asks
  `POST /api/v1/services/check` with it. The key is a secret of the installation (`.env`, a Kubernetes
  secret), never in the repository.
- **GeoServer knows people by name**: the gateway always overwrites `sec-username` (empty with a key,
  which GeoServer checks again by itself); Tomcat accepts connections only from the gateway; the roles
  come from the published views of Mosaikit (MK-123) through a reader login: the identifier of each
  organization for its members and administrators, `<organization>:admin` for its administrators, the
  identifier of each team for its owners and members. The GeoFence rules on those roles are GP-335's.
- **The gate of the catalog remains for links.** `/gw/link/<secret>/<dataset>/<service>` reaches
  `/api/v1/ext/catalog/shared-layers/<dataset>/ogc/...` with the secret as `X-Mosaikit-Link`; the gate asks
  `Sharing` about that dataset first, so a link opens only the layer of the resource it names, and keeps
  the protections of ADR-0020 (read requests of one layer, nothing by reference) and the services of the
  level of the link as GP-335 gives them: the map and its tiles, not the features of WFS. For people the gate
  answers with a redirect to the gateway when the installation has one (`geoportal.gateway.enabled`), so
  that the addresses people copied keep working; without the gateway (the bench of the tests) it serves
  as before.
- **The measure is requests**, a counter per month: what the gateway and the gate let through. Bytes
  would need the answers measured after they are sent; requests are known before.

## Consequences

- A request beyond the quota is refused within the ten seconds of the go-ahead, and the requests served
  in that window are still counted. A revoked key or an ended session closes the gateway within ten
  seconds; GeoServer's own authentication cache (60 s) only matters for requests that would bypass the
  gateway, which cannot reach it.
- Guests of an organization do not reach its geodata through the gateway, as they do not reach the
  backends of extensions.
- Every request of a link opens the link in Mosaikit and writes its use in the audit log: a map through
  a link writes one entry per tile. A cache of link openings, or an audit of the first use per period, is
  a matter for Mosaikit.
