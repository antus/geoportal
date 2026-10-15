<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# F01 (GP-327): the platform proofs on the Mosaikit stack

The October proofs (F0, in the legacy code base) showed that GeoServer can run without users of
its own. Every integration point has changed since: the session is Mosaikit's, roles live in kernel
tables next to row-level security, the AuthKey mapper and the `auth_request` must call an extension.
This folder repeats the proofs on the Mosaikit stack, one script per proof.

| # | Proof | Verdict | Measure |
|---|---|---|---|
| 1 | GeoServer takes the user header only from the gateway | **yes** | 8/8 cases; Tomcat refuses every other address with 403 |
| 2 | nginx `auth_request` on the Mosaikit session | **yes** | 11/11; go-ahead ~90–300 ms cold, ~50 ms cached; removal from the organization effective in 9.5 s |
| 3 | GeoFence rules by REST, Mosaikit roles as subjects | **yes** | 13/13; 200 rows → 80, column gone, deny by default; 8 rules written and re-read in 0.4–1.7 s |
| 4 | AuthKey with the web service mapper on the proof stub | **yes** | 8/8; same 80 features fid by fid as the session; revoked key stops in 59–60 s |
| 5 | Role service reads the roles from Mosaikit | **yes, JDBC** | 13/13; a team change through the API reaches GeoServer in 60–63 s |
| 6 | Importer loads vector files into PostGIS | **no** | 3 of 8 files right; direct PostGIS writes 3/3 on the files it gets wrong |

Two full runs, the first from empty volumes (`run.py --up`): same results, 278 s for the six proofs.
Rerun from nothing on 13 October 2026, 13:00–13:06, after the endpoints of the proofs moved out of
the catalog into the proof stub extension (`python run.py --up --down`: build of both packages,
stack up, setup in 37 s, the six proofs in 234 s, stack and volumes deleted): the same verdicts and
counts, removal from the organization 9.3 s, revoked key 60 s, team change 61–62 s.
Memory with everything up: GeoServer 821 MB, PostGIS 152 MB, nginx 12 MB, Mosaikit kernel 317 MB
(`-Xmx512m`).

## The stack

| Piece | What | Where |
|---|---|---|
| `compose.yaml` | project `geoportal-f01`: PostGIS 18-3.6 (databases `mosaikit`, `geoserver`, `geodata`), GeoServer 2.28.5 built from `distribution/server/geoserver`, nginx 1.27 | gateway `18801`, GeoServer admin `127.0.0.1:18480` (through nginx), PostGIS `127.0.0.1:15433` |
| Mosaikit | a stable installation (`F01_MOSAIKIT`, default `C:\temp\sviluppo\f01\mosaikit`) with the packages of the catalog and of the proof stub, and `mosaikit/application.properties` | `18890`, on the host |
| `extensions/catalog` | the catalog as it is on `main`, untouched by the proofs (proof 5 reads the row-level security of `ext_catalog.dataset`) | `/api/v1/ext/catalog/…` |
| `proof-stub/` | PROOF STUB, an extension of its own (`dev.geoportal.proofstub`, schema `ext_proofstub`): `ProofStubResource` and `V1__personal_key.sql`, the gateway go-ahead, personal keys, AuthKey mapper, REST role endpoints; never in a distribution | `/api/v1/ext/proofstub/…` |
| `nginx/default.conf.template` | three doors: people (`:80`), administration (`:8480`), GeoServer's calls to Mosaikit (`:8081`, internal) | |
| `geoserver/server.xml` | Tomcat's `server.xml` of the image plus a `RemoteAddrValve` | |
| `geoserver/security/` | JDBC role and user services (`gp`, `gpusers`), REST role service (`gprest`), header filter (`hdr`), AuthKey filter (`authkey`) | |
| `sql/gs_sec.sql` | read-only views for GeoServer in the Mosaikit database, login `gs_reader` | |
| `sql/geodata.sql` | 200 parcels in Valverde (with a reserved column), 50 stops in Monteluce, both invented towns | |
| `setup.py`, `stack.py`, `run.py`, `p1…p6`, `samples.py`, `common.py` | setup, start/stop, the proofs, the Importer samples | |

People: `gestore` (manager of alfa, owner of the teams), `dconti` (mobilita), `sgreco` (mobilita +
urbanistica), `pfontana` (ambiente), `estraneo` (member of alfa, no team), all `@alfa.test`;
`lbianchi@beta.test`; the service account `geoserver@f01-proof.test` in its own organization.

### Run it

```bash
cd prototypes/f01
python run.py --up --down        # from nothing to the table above and back to nothing
# or step by step: python stack.py up; python setup.py; python run.py [1..6]; python stack.py down
```

`stack.py up` builds both packages (`mvn -q clean package -DskipTests -Dskip.npm=true` in
`extensions/catalog` and in `proof-stub`) and copies them into the installation; `F01_SKIP_BUILD=1`
reuses those already in their `target/`. `-Dskip.npm=true`: the `npm install` of the catalog's build
fetches the `@mosaikit/testing` snapshot, whose release asset answered 404; nothing of npm is needed
to package an extension.

## 1. Header authentication only from the gateway: yes

**Measure** (`p1_header.py`): `sec-username: sgreco` (who sees all 200 rows) forged from every place
it could come from.

| From | Answer |
|---|---|
| gateway, no session | 401 from the gateway |
| gateway, session of dconti + forged header | dconti's 80 rows: nginx overwrites the header |
| gateway, QGIS door, no key | anonymous, denied |
| administration door | header stripped, anonymous, denied |
| host | GeoServer publishes no port |
| a container on the network GeoServer shares with PostGIS | **403 from Tomcat** |
| a container on the gateway's network, another address | **403 from Tomcat** |
| control: the gateway's own address | taken (so the 403 above is the address check) |

**Decision.** GeoServer cannot tell the gateway from anyone else: its request header filter has no
address check, and GeoFence takes the caller's address from `X-Forwarded-For`, which the caller
writes. In F0 the guarantee was only that GeoServer's port was bound to `127.0.0.1`, which leaves
every local process and every container on its network free to impersonate anybody. Now Tomcat
enforces it: a `RemoteAddrValve` in `server.xml` lets in only the gateway's fixed address on a
dedicated network (`10.231.0.10` on `gs`) and the container itself (health check). All HTTP to
GeoServer, administration included, goes through nginx.

**Gotchas.** The image copies `context.xml` from `/opt/config_overrides` only when
`POSTGRES_JNDI_ENABLED=true`; `server.xml` is always copied, so the valve goes there. Both files
pass through `envsubst`: no dollar signs in them.

## 2. nginx `auth_request` on the Mosaikit session: yes

**Measure** (`p2_auth_request.py`): data at `/gw/ows/<workspace>/<service>`; nginx calls
`GET /api/v1/ext/proofstub/gateway-check` with the session cookie and
`X-Mosaikit-Organization: <workspace>`.

- sgreco: GetMap returns a PNG, WFS 200 rows; dconti 80 rows.
- No session, a forged cookie, or HTTP Basic of a real person instead of the cookie: 401.
- lbianchi (beta) on alfa: 403 from the kernel (not a member: no organization for the request);
  on beta: 50 rows.
- estraneo (alfa, no team): the gateway lets him through, GeoFence gives nothing. The two layers
  are distinct: the gateway decides the organization, GeoFence the data.
- Cache 10 s per session and workspace: first GetMap 200–350 ms, then ~50 ms (`X-Gp-Check-Cache:
  HIT`). Removing estraneo from alfa through the API closes the gateway in 9.5 s.

**Decision.** The organization of the workspace is passed as `X-Mosaikit-Organization`, so the
kernel itself refuses non-members, guests (its `GuestAccessFilter`) and anonymous requests before the
extension runs; the endpoint only compares the workspace with the organization and returns the
name. Only the session cookie is forwarded (no `Authorization`), and the cache key is the cookie
value extracted by a `map` (`$cookie_mosaikit-session` is not a valid nginx variable).

**Gotcha, for F04.** Signing out (`DELETE /api/v1/accounts/session`) does not end the session: the
cookie is Quarkus form authentication, encrypted and stateless, and the same cookie replayed after
sign-out still opens the data (200) until it expires (8 h without activity). Whoever copies a cookie
keeps the data. A server-side session revocation is a Mosaikit matter (an MK requirement).

## 3. GeoFence rules by REST: yes

**Measure** (`p3_geofence.py`): the rule set of `common.rule_set`, written after deleting every rule,
re-read, cache invalidated.

- Subjects are Mosaikit roles: `TEAM_<team id>` and `ORG_<slug>_<user|admin|guest>`.
- dconti (mobilita): a LIMIT rule with an area + an ALLOW rule with CQL `stato = 'attivo'` and
  `cf_titolare` at `NONE` → 80 rows of 200, district I only, active only, no `cf_titolare`.
- sgreco (two teams): the rules add up, 200 rows. pfontana: WMS yes, WFS no.
- Deny by default: a `DENY` rule on each organization's workspace (priority 900+) stops estraneo and
  anonymous, in front of the catch-all `ALLOW` that the server distribution writes at 99999.

**Gotchas** (re-measured on 2.28.5, as in F0, plus two new):

- A rule written as XML loses its area, one written as JSON loses its columns, and both answer 201:
  each rule in the format that keeps it, and re-read after writing.
- `PUT /rest/ruleCache/invalidate` after every change.
- **New:** `POST /rest/geofence/rules` *inserts*: every rule at that priority or after it moves down by
  one, and a delete does not move them back (the catch-all went from 99999 to 100000 after two test
  rules). The catalog must not identify its rules by priority; rewriting the whole set from empty,
  in ascending order, gives exact priorities.
- **New:** a second rule with the same subject, workspace, layer and access is refused with 500
  "Duplicate Rule", even at another priority.
- **Risk, for F03:** a workspace without its own deny rule is open to anonymous through the catch-all
  `ALLOW` of `distribution/server/geoserver/init/prepare.sh` (measured: 50 rows on `aperto`). Either
  the catalog writes the deny of a workspace before it publishes anything in it, or the catch-all
  becomes `DENY` (with the service account as `ADMIN`). The distribution also lacks the
  closed `layers.properties` of F0 (the safety net when GeoFence is not loaded); `setup.py` writes it.

## 4. AuthKey with the web service mapper on the proof stub: yes

**Measure** (`p4_authkey.py`): dconti creates a key (`POST /api/v1/ext/proofstub/keys`) and reads
`/gw/key/alfa/wfs?authkey=<key>` as QGIS would. GeoFence's cache is emptied first, so the key path
is not helped by a decision taken for the session path.

- Same summary and the same 80 features, fid by fid, as through the session; sgreco's key gives 200
  rows (two teams); WMS GetMap with the key gives a PNG.
- Unknown key: anonymous, denied. A key on the session door: 401.
- Revoked key: stops in 59–60 s (GeoServer's authentication cache, 60 s in the image; the mapper's
  own cache is 30 s).

**Decision.** The mapper returns only the name (`searchUser` `^\s*(\S+)\s*$`, so an empty answer stays
anonymous); user and roles come from the same views as the gateway path (JDBC user service
`gpusers`, role service `gp`): one source of roles per name, as F0 required.

**Gotchas.**

- The web service mapper cannot send credentials, and every `/api/v1/ext/*` of Mosaikit requires an
  authenticated person acting on an organization. The mapper therefore calls an internal door of
  the gateway (`:8081`, not published), which adds the HTTP Basic of a service account. Mosaikit has
  no service accounts: here it is a person in an organization of its own, recognised by the stub by
  name (`geoportal.proofstub.service-account`). F04 needs a real service identity (MK
  requirement) or a key endpoint outside `/api/v1/ext/`.
- The key table cannot be under per-organization row-level security: GeoServer asks for the owner of a
  key without knowing the organization. The stub keeps a SHA-256 of the key, without RLS.
- **For F04:** the capabilities through the key door advertise `http://geoserver/geoserver/alfa/wfs`,
  not `/gw/key/alfa/wfs`: QGIS would follow addresses that do not exist outside the gateway. The
  proxy base URL must be set per door (headers `X-Forwarded-*` and GeoServer's "use headers for proxy
  URL", or a fixed base per workspace).

## 5. Role service: yes, JDBC on read-only views

**Measure** (`p5_roles.py`).

- Row-level security in the Mosaikit database is on `ext_catalog.dataset` and
  `mk_kernel.extension_document` only. The tables the roles come from (`user_account`,
  `organization`, `organization_member`, `team`, `team_member`) have none. The kernel isolates
  organizations by running requests as the role `mk_mosaikit_member` with `mosaikit.organization`
  set; a reader without an organization sees no row of an RLS table (measured: 14 datasets for the
  owner, 0 for the member role).
- The views in `gs_sec` (`sql/gs_sec.sql`) are owned by the database owner (definer rights, the
  default), so they read the kernel tables whatever the policies, for every organization: GeoServer
  needs all of them, a workspace being an organization. `gs_reader` may read those views and nothing
  else: `permission denied for schema mk_kernel`, and denied on `ext_catalog`.
- GeoServer reports for each person exactly the organization and teams Mosaikit has (5 people
  checked). GeoServer's administrator is `gsadmin`; Mosaikit's `admin` gets no `ADMIN`.
- sgreco removed from urbanistica through `DELETE /api/v1/teams/{id}/members/…`: 200 → 80 rows in
  60–63 s (the 60 s authentication cache plus the 2 s polling step); put back, the same.
- The REST role service (fallback) also works through the stub, but **renames every role**:
  `ROLE_` + upper case (`ROLE_TEAM_D126B662-…`), so GeoFence rules written for one service do not
  match the other.

**Decision.** JDBC role service on read-only definer views in the Mosaikit database, the same views for
the user service of AuthKey. The REST role service is not needed; switching to it later would mean
rewriting every rule with the renamed roles.

**Gotchas.**

- Do not make the views `security_invoker`, and do not put role views on extension tables with
  RLS: GeoServer has no organization to set and would see nothing.
- The views read the kernel's internal tables, which are not a published contract of Mosaikit (MK-020
  publishes views of extensions, not of the kernel). For F03: ask Mosaikit for published, stable
  views of people, organizations, memberships and teams (an MK requirement), and point `gs_sec` at
  them.
- F0 mapped `'admin' → ADMIN`. Mosaikit's bootstrap administrator is literally `admin`, so that
  mapping would make Mosaikit's administrator GeoServer's administrator through the gateway. Give
  GeoServer an administrator name no Mosaikit account can have (here `gsadmin`; accounts are e-mail
  addresses).
- REST role service, two traps: it keeps only the host of `baseUrl` (it asks `/api/users/<name>` at the
  root), and it sends `Authorization: ApiKey <key>`, which Mosaikit does not accept, so the
  gateway's internal door checks the key and swaps in the service account.
- 60 s is the floor for any permission change by these paths. If F04 wants immediate revocation,
  the gateway path can pass roles in a header (`roleSource=Header`, no cache), as F0 noted.

## 6. Importer: no, 3 of 8

**Measure** (`p6_importer.py`): eight files generated by `samples.py` (stations in Valverde, districts as
polygons; integer, text, decimal, date and boolean columns), uploaded to the Importer with target the
PostGIS store `alfa:imports`, then read back from what GeoServer serves.

| File | Importer | Why |
|---|---|---|
| GeoJSON, EPSG:4326 | **right** | 12 rows, 4326, types right (ISO dates even become dates) |
| GeoJSON with `crs` EPSG:32633 | wrong, silently | imported as 4326: metres taken for degrees, the first point at (288809, 4639029) "degrees" |
| Shapefile 4326 (zip) | **right** | 12 rows, 4326; column names keep the case of the DBF (`CODICE`) |
| Shapefile 32633 (zip) | **right** | 12 rows, 32633 in PostGIS |
| GeoPackage, points 4326 | refused, 500 | the Importer takes any `.gpkg` for a raster mosaic: `NoSuchElementException` in `GeoPackageReader` |
| GeoPackage, polygons 32633 | refused, 500 | same |
| CSV with lat/lon columns | refused, 500 | `NoClassDefFoundError: com/opencsv/ICSVParser` (the jar is missing in 2.28.5) |
| CSV with WKT | refused, 500 | same |

The same three kinds of data the Importer gets wrong, written straight into PostGIS with the right
SRID and published with one REST call (`FORCE_DECLARED`): 3/3 right.

**Decision.** F0 stands: **Geoportal writes vectors into PostGIS itself** and publishes them by REST,
for every format. The Importer would be acceptable only for zipped shapefiles and GeoJSON in 4326, and
a GeoJSON in another reference system is loaded wrong without an error, which no upload path can
afford. Converting first and writing PostGIS directly gives one path, with the reference-system
check of GP-326 in front of it.

## What F03 and F04 must know

**F03 (geodata in PostGIS and GeoServer)**

1. Vectors: write PostGIS directly, then publish (`featureType` with the declared SRS); do not use
   the Importer for vectors.
2. Write the `DENY` of an organization's workspace when the workspace is created, before anything is
   published in it, or change the distribution's catch-all `ALLOW` at 99999 to `DENY`; add the
   closed `layers.properties` to the server distribution.
3. Role views on the kernel's internal tables work, but need a published contract from Mosaikit
   (MK requirement: stable views of people, organizations, memberships, teams).
4. GeoServer reachable only from the gateway: dedicated network, fixed address, Tomcat
   `RemoteAddrValve` in the image's `server.xml`; administration through the gateway too.
5. GeoServer's administrator must not be called `admin`.

**F04 (permissions on the data)**

1. GeoFence REST: rewrite a whole organization's set from empty in ascending priority, each rule in
   the format that keeps it (area in JSON, columns in XML), re-read, invalidate; inserts shift
   priorities and duplicate subjects are refused.
2. Subjects: `TEAM_<id>` and `ORG_<slug>_<role>` from the JDBC role service; with the REST role
   service names would become `ROLE_<UPPER>`.
3. Revocation is 60 s by every path (authentication cache), 10 s more at the gateway for removal
   from an organization (go-ahead cache).
4. AuthKey and the gateway's go-ahead need a service identity in Mosaikit, or endpoints outside
   `/api/v1/ext/` (which is always authenticated and always acts on an organization); keys cannot
   be under per-organization RLS.
5. A Mosaikit session cookie survives sign-out: server-side revocation is needed before data
   access is opened widely (MK requirement).
6. QGIS: set the proxy base URL of the key door, or the capabilities send QGIS to
   `http://geoserver/...`.

> Follow-up: the catch-all `ALLOW` of `distribution/server/geoserver/init/prepare.sh` found by proof 3
> was changed to `DENY` on `main` on 13 October 2026 (commit "fix(server): GeoFence denies by default").
