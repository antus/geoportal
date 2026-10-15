-- SPDX-FileCopyrightText: 2026 Massimo Antonini
--
-- PROOF STUB (GP-327, phase F01): the personal keys for QGIS of the proofs in prototypes/f01,
-- read by the AuthKey web service mapper through ProofStubResource (schema ext_proofstub). GP-336 replaces it.
--
-- No row-level security on purpose: GeoServer asks for the owner of a key without knowing the
-- organization, through a service account that acts on an organization of its own. Only the
-- hash of a key is kept.

create table personal_key (
    key_hash        varchar(64)  primary key,
    username        varchar(254) not null,
    organization_id uuid         not null,
    created_at      timestamptz  not null,
    revoked_at      timestamptz
);
