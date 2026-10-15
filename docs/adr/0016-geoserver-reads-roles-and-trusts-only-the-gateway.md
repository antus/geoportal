<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# 0016. GeoServer reads roles from Mosaikit and trusts only the gateway

- Status: accepted
- Date: 2026-10-13
- Deciders: Massimo Antonini

## Context and problem statement

GeoFence applies rules to roles, and GeoServer must know who is asking. The proofs of GP-327
(prototypes/f01, proofs 1, 2 and 5) measured both ways GeoServer can learn the roles of a person,
and how to make sure the name it receives comes from the gateway.

## Decision

- **Roles**: the JDBC role service of GeoServer reads read-only views of people, organizations,
  memberships and teams, through a database login that can read nothing else. The roles are the
  identifiers of organizations and teams as they are, so that GeoFence rules keep them. The REST role
  service works too but renames every role, which would force rewriting every rule: it is not used.
  The views become a published contract of Mosaikit (MK-123).
- **The name header** is accepted only from the gateway: GeoServer sits on a dedicated network with
  the gateway at a fixed address, and Tomcat refuses any other address (RemoteAddrValve), whatever
  the header; nginx always strips or overwrites it.
- **The administrator of GeoServer** is never called `admin`, which is the name of the bootstrap
  administrator of Mosaikit.
- **Timings**: a change of group reaches GeoServer within its authentication cache (60 s), and the
  decision of the gateway is cached 10 s.

## Consequences

- The gateway and AuthKey need a service identity in Mosaikit (MK-124), and sign-out must end the
  session on the server (MK-125), or a replayed cookie keeps opening the data until it expires.
