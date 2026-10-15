<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# 0018. The proxy of external services reaches only registered public services

- Status: accepted
- Date: 2026-10-13
- Deciders: Massimo Antonini

## Context and problem statement

Many public map services send no CORS headers, so a page of Geoportal cannot read them, and the
Content-Security-Policy of the shell of Mosaikit (`connect-src 'self'`) blocks other origins anyway.
GP-939 asks for a proxy on the server. A proxy that fetches what the browser asks is the classic way
into the internal network (server-side request forgery): the metadata service of the cloud, GeoServer
and PostgreSQL behind the gateway, the admin pages of the intranet. The legacy proxy checked that the
target had the host of the registered address, but resolved the name twice (DNS rebinding), followed
any redirect and passed every header back.

## Decision

- **Targets by identifier, never by address.** The proxy is `/api/v1/ext/catalog/services/{id}/proxy`
  of a service registered by the organization; the request gives a path below the registered one and a
  query, which follows the registered query without overriding it. There is no parameter with an
  address.
- **Only public http(s) addresses.** The name is resolved once per request; if any of its addresses
  is loopback, private, link-local, unique-local, CGNAT, reserved or multicast (also inside IPv6 forms:
  mapped, NAT64, 6to4, Teredo) the request is refused; otherwise the proxy connects to one of the
  checked addresses with its own HTTP/1.1 client, sending the name as Host and SNI and checking it
  against the certificate. The clients of the JDK resolve by themselves, so they cannot be used.
- **The installation may open internal networks** (`geoportal.catalog.proxy.allowed-networks`, literal
  CIDR only), for the map server of an intranet. The **metadata addresses** of cloud providers stay
  refused whatever it says.
- **Redirects** are followed only on the origin of the service, three at most; anything else is 502.
- **Methods, sizes and time** are limited: GET, HEAD and the POST of OGC (XML or form), request and
  answer sizes, connection and read timeouts, a few concurrent requests per service.
- **Headers by allow-list both ways**: nothing of the person reaches the service, nothing of the
  service that can act on the origin of Geoportal reaches the browser; answers are sandboxed by CSP.
- **Access is that of the resource**: `use` on the service to go through the proxy, `manage` to
  change it, and the proxy off refuses those who only use it.
- **Credentials of a service** come from the credentials of Mosaikit on the server; attaching one, or
  moving a service that has one, needs a permission of its own, so that registering a service does not
  let anyone send a credential of the organization to a server of their choice. The header is kept one
  minute in memory, because Mosaikit audits every read and a map asks for tiles by the dozen.
- **Audit**: registrations, changes, removals and refused addresses; not the calls.

## Consequences

- A refused target never says what the name resolved to, so the proxy cannot be used to map the
  internal network.
- Services of an intranet need an explicit setting of the installation, which is the point.
- A credential replaced by the administrators is used by the proxy within a minute. Auditing reads
  per period instead of per read is a generic need of Mosaikit, to be raised there if it matters.
- The proxy streams answers itself, so the gateway (`/gw/…`, ADR-0006) can expose the same route when
  the maps move to GeoLibre, without a second implementation.
