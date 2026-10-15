<!-- SPDX-FileCopyrightText: 2026 Massimo Antonini -->
# 0025. Surveyors limited by data rules write within them

- Status: accepted
- Date: 2026-10-14
- Deciders: Massimo Antonini

## Context and problem statement

ADR-0023 gave a surveyor assigned to a layer (GP-036) the WFS transactions of that layer, but not to a
surveyor who also has data rules on it (GP-308): GeoFence was not known to keep writes within an area or a row
filter, so the rule was left out and the surveyor could not edit at all (issue #38). The maintainer's rule:
allow WFS-T within the area and the filter of the data rules if GeoFence applies them to writes (Insert,
Update, Delete), otherwise refuse with a clear reason.

## Evidence

Read in the sources of the versions the server distribution runs (GeoServer 2.28, GeoFence 3.8):

- **GeoFence** (`RuleReaderServiceImpl.resolveRuleset`, geofence 3.8.3): for each evaluation the rules are read
  in priority order; `LIMIT` rules are collected until the first `ALLOW` or `DENY`, which ends it.
  `buildAllowAccessInfo` intersects the areas of the collected limits and copies `cqlFilterRead` *and*
  `cqlFilterWrite` from the `layerDetails` of the `ALLOW`. So a `LIMIT` and an `ALLOW` scoped to the request
  `TRANSACTION`, in front of the denial of every write, give a transaction the area and both filters.
- **The GeoFence plugin of GeoServer** (`GeofenceAccessManager.buildResourceAccessLimits`, 2.28.0): for a
  feature type the write filter is `cqlFilterWrite`, and the area is joined with `AND` to the read *and* the
  write filter (`mergeFilter`); the result is a `VectorAccessLimits` with both.
- **GeoServer** (`SecuredFeatureStore`, 2.28.0), through which WFS-T writes: `addFeatures` refuses the whole
  insert when any feature does not pass the write filter ("At least one of the features inserted does not
  satisfy your write restrictions"); `modifyFeatures` and `removeFeatures` join the filter of the request with
  the write filter, so an update or a delete reaches only the features inside it.
- **Columns do not work for writes**: with a column list, `modifyFeatures` throws "write protected" for the
  columns the rule lists as writable (the test is inverted), and an insert must leave the hidden columns empty.
- **Measured** on the GeoServer of the distribution in the CI (`SurveyorEditingTest`, tags `GP-036`,
  `GP-308`): a surveyor limited by an area over five of ten points and the filter `nome <> 'p1'` inserts inside
  both; an insert outside the area, or inside it with `nome = 'p1'`, is refused; updates and deletes of p7
  (outside the area) and p1 (outside the filter) change nothing, those of p2 and p3 apply.

## Decision

- **Within an area and a row filter, the surveyor writes.** For each surveyor limited by data rules on a layer
  assigned to them, the rule set has, for `TRANSACTION` and for `LOCKFEATURE`, a user `LIMIT` with the composed
  area (when there is one) and a user `ALLOW` whose `layerDetails` carry the composed row filter as
  `cqlFilterRead` and `cqlFilterWrite`, in front of the denial of every write (ADR-0021). The catalog reads the
  write filter back from GeoFence and compares it as it does the other fields.
- **Hidden columns, or areas with nothing in common, stop writes.** Such a surveyor gets no write rule; the
  report of the rules says so, and the API answers `data-rules` with the reason.
- **The catalog does not forward their transactions.** `POST editing/layers/{name}/transaction` reaches
  GeoServer as its administrator, who has no limits; for a surveyor limited by data rules it answers 403
  `data-rules-service`: they write through the WFS service of the layer (the gateway, QGIS), where GeoServer
  applies the limits. `GET editing` gives each assigned layer `dataRules` (null, `data-rules-service`,
  `data-rules`), and the page tells the surveyor where and within what they edit. Replacing the file of a
  dataset served by such a layer is refused the same way.

## Consequences

- An update can move a feature out of the area or change it so that it no longer passes the filter: GeoServer
  checks the features an update reaches, not the values it writes. The surveyor then no longer sees nor edits
  that feature; a manager of the dataset does. Checking the new values would need GeoServer to apply the write
  filter after the update too.
- Editing through the catalog for a limited surveyor would need the catalog to reach GeoServer as the person
  (as the gateway does, ADR-0022) rather than as its administrator.
- Columns in data rules and editing do not mix until GeoServer's check of writable columns is fixed upstream.
