<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# Proof stub (GP-327, phase F01)

**PROOF STUB.** A small Mosaikit extension, `dev.geoportal.proofstub`, with the endpoints that the
gateway and GeoServer call in the proofs of `prototypes/f01`. It lives here, not in
`extensions/catalog`, so the catalog stays exactly as it is; GP-334, GP-335 and GP-336 replace it.
It is not a module of the root `pom.xml` and goes into no distribution.

| Path | What |
|---|---|
| `manifest.yaml` | id `dev.geoportal.proofstub`, API `/api/v1/ext/proofstub`, schema `ext_proofstub` |
| `src/.../ProofStubResource.java` | `GET gateway-check` (nginx `auth_request`), `POST keys`, `DELETE keys/{key}` (personal keys), `GET authkey?key=` (AuthKey mapper), `GET roles/api/roles\|adminRole\|users` (REST role service) |
| `db/V1__personal_key.sql` | the table of the hashes of the personal keys, without row-level security |

The service account GeoServer's calls run as is the setting `geoportal.proofstub.service-account`
(in `../mosaikit/application.properties`).

## Build

```bash
mvn -q package -DskipTests      # target/proofstub-0.1.0.zip; run.py --up does it
```
