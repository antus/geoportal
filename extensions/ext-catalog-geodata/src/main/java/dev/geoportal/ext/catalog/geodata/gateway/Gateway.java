// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.gateway;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The data gateway of the installation (GP-336, ADR-0022): nginx in front of GeoServer, on the origin of
 * Geoportal. When it is there ({@code geoportal.gateway.enabled=true}, as in the server distribution),
 * the services of a published layer are its addresses, GeoServer decides for each person through
 * GeoFence, and the gate of the catalog answers people with a redirect to them; without it (the bench of
 * the tests, an installation without the server stack) the gate serves them as before.
 *
 * <p>Addresses, all on the origin of Geoportal:
 *
 * <ul>
 *   <li>{@code /gw/ows/<organization>/<service>} and {@code /gw/ows/<organization>/<layer>/<service>}:
 *       the services of the workspace of the organization, or of one layer, with the session of
 *       Mosaikit, or with {@code authkey=<personal key>} for desktop clients such as QGIS;
 *   <li>{@code /gw/link/<secret>/<dataset>/<service>}: the services of the one layer of a dataset
 *       shared with a link, through the gate of the catalog.
 * </ul>
 */
public final class Gateway {

    /** The setting that says the gateway is installed. */
    public static final String ENABLED = "geoportal.gateway.enabled";

    /** The services the gateway passes, after the organization or the layer. */
    public static final Map<String, String> SERVICES = Map.of(
            "wms", "service=WMS&request=GetCapabilities",
            "wfs", "service=WFS&request=GetCapabilities",
            "wmts", "service=WMTS&request=GetCapabilities");

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private Gateway() {}

    /** Whether the gateway is installed. */
    public static boolean enabled() {
        return ConfigProvider.getConfig().getOptionalValue(ENABLED, Boolean.class).orElse(false);
    }

    /** The path of a service of a layer through the gateway, such as {@code /gw/ows/acme/roads/wms}. */
    public static String layerService(String organization, String layer, String service) {
        if (organization == null || !SLUG.matcher(organization).matches()) {
            throw new IllegalArgumentException("not a slug: " + organization);
        }
        return "/gw/ows/" + organization + "/" + layer + "/" + path(service);
    }

    /** The path of a service of the layer of a dataset shared with a link. */
    public static String linkService(String secret, String dataset, String service) {
        return "/gw/link/" + URLEncoder.encode(secret, StandardCharsets.UTF_8) + "/" + dataset + "/" + path(service);
    }

    /**
     * The address the gate of the catalog redirects people to: the same service path and query, through
     * the gateway; empty for a path the gateway does not pass.
     */
    public static Optional<String> redirect(String organization, String layer, String servicePath, String rawQuery) {
        if (!servicePath.equals("wms") && !servicePath.equals("wfs") && !servicePath.equals("ows")
                && !servicePath.equals("gwc/service/wmts")) {
            return Optional.empty();
        }
        String base = "/gw/ows/" + organization + "/" + layer + "/" + servicePath;
        return Optional.of(rawQuery == null || rawQuery.isEmpty() ? base : base + "?" + rawQuery);
    }

    private static String path(String service) {
        return service.equals("wmts") ? "gwc/service/wmts" : service;
    }
}
