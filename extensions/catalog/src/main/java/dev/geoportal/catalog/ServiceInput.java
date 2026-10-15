// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog;

/**
 * An external service to register or change: title, kind (wms, wmts, wfs, wcs), address, whether
 * the map goes through the proxy, the reference system of its features (WFS), and the name of a
 * credential of Mosaikit with its scheme (basic or bearer) when the service needs one.
 */
public record ServiceInput(
        String title, String kind, String url, Boolean proxy, String crs, String credential, String authScheme) {}
