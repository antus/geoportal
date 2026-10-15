// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata;

import java.util.UUID;

/**
 * An external service as the API returns it. {@code proxyUrl} is the address the map uses when
 * {@code proxy} is on; {@code manage} says whether the person may change it. The credential appears
 * by name only. {@code access} is what the catalog found on whether the browser reads the service
 * directly (GP-297), in the answers of saving and checking a service only.
 */
public record ServiceView(
        UUID id,
        String title,
        String kind,
        String url,
        boolean proxy,
        String crs,
        String credential,
        String authScheme,
        boolean manage,
        String proxyUrl,
        AccessCheck access) {

    ServiceView with(AccessCheck check) {
        return new ServiceView(id, title, kind, url, proxy, crs, credential, authScheme, manage, proxyUrl, check);
    }
}
