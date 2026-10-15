// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.geoserver;

/**
 * GeoServer was aligned with the catalog for the organization of the request (a CDI event, fired in the
 * request): the GeoFence rules follow the layers it publishes (GP-335).
 */
public record Aligned(OrganizationSpace space, Alignment alignment) {}
