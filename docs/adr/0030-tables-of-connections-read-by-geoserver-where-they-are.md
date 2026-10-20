<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
# 0030. Tables of connections are read by GeoServer where they are, with the credential of the connection

- Status: accepted
- Date: 2026-10-20
- Deciders: Massimo Antonini

## Context and problem statement

GP-986: a table or view with a geometry column of a PostgreSQL connection of `ext-catalog-connection`
offers the use "map"; choosing it makes a layer, reached through the gateway with the data rules applied,
without copying the table, and the layer keeps a link to its connection and table. The connection
framework keeps the connections and reads their credentials from the secrets service of the platform at
the moment of a call; no answer of its API contains a credential. GeoServer must read the table, so it
needs the address of the source and a user and password that the source accepts. Where the password
comes from, who reads it, and where it lives afterwards, had to be decided.

## Decision

- **The use "map" is a contribution of the geodata** to the point `catalog.connection.use` of the
  framework (EXT-019): kinds `table` and `view`, fact `geometry`. The framework offers it on the pieces
  whose facts include a geometry column, while `ext-catalog-geodata` is active; without it, the
  connection and its tables are those of any connection and "map" is not offered.
- **Choosing is the source picker** of the framework (EXT-026), opened by the app of the geodata with
  the use `map` (*Mappa una tabella di una connessione*). The picker returns a reference (the connection
  in the registry of resources, the piece by its ids), which the geodata keep with the dataset of the
  layer (`connection_layer.reference`): never a copy of the source.
- **The reference is resolved as the person**, on the server: the geodata call
  `POST /api/v1/ext/connections/references/resolve` of the kernel they run in, with the authentication
  and the organization of the request. So the framework decides whether the person may use the
  connection (level `use`), and gives the connection as it is now: its connector, its settings (host,
  port, database, user, SSL mode) and the name of its credential, never the credential. Only the
  connector of PostgreSQL of mosaikit-ext (EXT-040) is mapped for now.
- **The credential is read by the geodata, as the platform allows it.** The geodata read the credential
  of the connection by name from the secrets service (`Secrets.resolve`), at the moment they need it: to
  ask the source about the table when the layer is made (type and reference system of the geometry
  column, an estimate of its rows and its extent; read only) and at every alignment of GeoServer. A
  credential that the administrators meant for one extension is read by that extension only (MK-062):
  the administrators allow the geodata to map the tables of a connection by meaning its credential for
  every extension or for the geodata; otherwise mapping is refused with that explanation (409
  `credential-not-readable`). The value is never logged, audited, returned or kept by the geodata.
- **GeoServer keeps the password, encrypted, in a store of its own per source.** The alignment
  (ADR-0017) makes in the workspace of the organization a PostGIS store per connection and schema
  (`conn_<connection>[_<schema>]`), with the address as GeoServer reaches it (setting
  `geoportal.geoserver.connection-hosts` when GeoServer runs in another network than the platform), the
  schema, the user and the password, sent over the internal administration door of the gateway only.
  GeoServer encrypts the passwords of its stores in its catalog; its REST API returns them encrypted and
  only to its administrator, and the reconciliation compares every parameter but the password. A store
  no layer uses goes at the next alignment; a credential that can no longer be read leaves the store as
  it is and the layer fails its health check, saying why.
- **The layer is a layer of the geodata.** Its dataset (`uploaded_data.source = 'connection'`) is ready
  to publish at once, shared on its own (the owner is who mapped it), published like any vector:
  sharing, GeoFence rules, data rules, the gate and the gateway, health checks. It is not a child of
  the connection: the level `view` of a connection does not let a person read its data, while it would
  let them read the layer. Nothing is loaded, copied or counted to the storage; deleting the layer never
  touches the source. A refresh resolves the reference again as the person and the store follows a new
  address of the connection.
- **Read only.** The tables of connections are not edited through Geoportal: a layer on a store of a
  connection cannot be assigned to surveyors (GP-036), so GeoFence never allows writing it.

## Consequences

- GeoServer must reach the sources: in the server distribution it is on the internal networks only, so
  an external database needs a route that the administrator adds; the PostGIS of the stack is reached at
  once.
- The password of a mapped connection lives in two places, the secrets service and the catalog of
  GeoServer; changing it in Credentials reaches GeoServer at the next alignment.
- When the framework of connections offers a server contract to read a connection for another
  extension, the resolution through its HTTP API goes for that contract.
