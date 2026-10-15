// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.geoserver;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the gate of the catalog lets through to the OGC services of a published layer (GP-114). The
 * gate reaches GeoServer as its administrator until the gateway and the GeoFence rules of Geoportal
 * (GP-335, GP-336) let GeoServer decide for each person, so it lets through only what reads that one
 * layer, and refuses everything else before GeoServer is called:
 *
 * <ul>
 *   <li>only the virtual services of the layer ({@code <workspace>/<layer>/wms}, {@code /wfs}, {@code
 *       /ows}, {@code /gwc/service/wmts}): never {@code /rest}, {@code /gwc/rest}, {@code /web} or
 *       another path;
 *   <li>only the read operations of each service, spelled as the standards spell them: WMS
 *       GetCapabilities, GetMap, GetFeatureInfo, GetLegendGraphic, DescribeLayer; WFS GetCapabilities,
 *       DescribeFeatureType, GetFeature, GetPropertyValue; WMTS GetCapabilities, GetTile,
 *       GetFeatureInfo; a parameter given twice is refused;
 *   <li>only that layer where a request names layers ({@code layers}, {@code query_layers}, {@code
 *       layer}, {@code typeName}, {@code typeNames}), qualified or not; features asked by identifier
 *       only of that layer; no stored query;
 *   <li>nothing that makes GeoServer fetch an address or read another feature type: no {@code sld},
 *       {@code sld_body}, {@code remote_ows_type}, {@code remote_ows_url}, and no filter calling
 *       {@code querySingle}, {@code queryCollection} or {@code collectGeometries}.
 * </ul>
 *
 * <p>Only GET and HEAD reach the gate: a POST (a Transaction in a body) has no route.
 */
public final class OgcGate {

    /** The service paths below {@code <workspace>/<layer>/}, and the service each one is. */
    public static final Map<String, String> SERVICES = Map.of(
            "wms", "WMS", "wfs", "WFS", "ows", "", "gwc/service/wmts", "WMTS");

    private static final Map<String, Set<String>> OPERATIONS = Map.of(
            "WMS", Set.of("GetCapabilities", "GetMap", "GetFeatureInfo", "GetLegendGraphic", "DescribeLayer"),
            "WFS", Set.of("GetCapabilities", "DescribeFeatureType", "GetFeature", "GetPropertyValue"),
            "WMTS", Set.of("GetCapabilities", "GetTile", "GetFeatureInfo"));

    private static final Set<String> LAYER_PARAMETERS = Set.of("layers", "query_layers", "layer", "typename", "typenames");

    private static final Set<String> FEATURE_IDS = Set.of("featureid", "resourceid");

    private static final Set<String> REFUSED_PARAMETERS = Set.of(
            "sld", "sld_body", "remote_ows_type", "remote_ows_url", "storedquery_id", "styles_url");

    private static final Set<String> FILTERS = Set.of("cql_filter", "filter", "sortby", "propertyname", "valuereference");

    /** Filter functions that read other feature types. */
    private static final Pattern CROSS_LAYER = Pattern.compile("(?i)query\\s*(single|collection)|collectgeometries");

    private OgcGate() {}

    /**
     * Why a request may not pass, or empty when it may.
     *
     * @param path the service path below the layer, such as {@code wms}
     * @param rawQuery the query of the request, encoded
     */
    public static Optional<String> refusal(String path, String rawQuery, String workspace, String layer) {
        if (path == null || !SERVICES.containsKey(path)) {
            return Optional.of("Only the WMS, WFS and WMTS of the layer are served here.");
        }
        Map<String, String> parameters = new HashMap<>();
        for (String pair : (rawQuery == null ? "" : rawQuery).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq)).strip().toLowerCase(Locale.ROOT);
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            if (key.isEmpty()) {
                continue;
            }
            if (parameters.put(key, value) != null) {
                return Optional.of("The parameter " + key + " is given more than once.");
            }
        }
        String service = SERVICES.get(path);
        String asked = parameters.getOrDefault("service", "").strip().toUpperCase(Locale.ROOT);
        if (service.isEmpty()) {
            if (!asked.equals("WMS") && !asked.equals("WFS")) {
                return Optional.of("Name the service, WMS or WFS.");
            }
            service = asked;
        } else if (!asked.isEmpty() && !asked.equals(service)) {
            return Optional.of("This address serves " + service + ", not " + asked + ".");
        }
        String request = parameters.get("request");
        if (request == null || request.isBlank()) {
            return Optional.of("The request parameter is required (GetCapabilities, GetMap, GetFeature, GetTile, ...).");
        }
        if (!OPERATIONS.get(service).contains(request)) {
            return Optional.of("Only the read requests of " + service + " are served here, as the standard spells them: "
                    + OPERATIONS.get(service).stream().sorted().toList() + ".");
        }
        for (Map.Entry<String, String> p : parameters.entrySet()) {
            String key = p.getKey();
            if (REFUSED_PARAMETERS.contains(key)) {
                return Optional.of("Styles or queries by reference are not accepted: the layer has its own style.");
            }
            if (LAYER_PARAMETERS.contains(key)) {
                for (String named : p.getValue().split(",", -1)) {
                    String n = named.strip();
                    if (!n.equals(layer) && !n.equals(workspace + ":" + layer)) {
                        return Optional.of("Only the layer " + layer + " is served here.");
                    }
                }
            }
            if (FEATURE_IDS.contains(key)) {
                for (String id : p.getValue().split(",", -1)) {
                    String i = id.strip();
                    if (!i.startsWith(layer + ".") && !i.startsWith(workspace + ":" + layer + ".")) {
                        return Optional.of("Only features of the layer " + layer + " are served here.");
                    }
                }
            }
            if (FILTERS.contains(key) && CROSS_LAYER.matcher(p.getValue()).find()) {
                return Optional.of("A filter may not read other layers.");
            }
        }
        return Optional.empty();
    }

    /**
     * The service a request is for, {@code WMS}, {@code WFS} or {@code WMTS}, from its path and, for
     * {@code ows}, its {@code service} parameter; for a request {@link #refusal} lets through.
     */
    public static String service(String path, String rawQuery) {
        String service = SERVICES.getOrDefault(path, "");
        if (!service.isEmpty()) {
            return service;
        }
        for (String pair : (rawQuery == null ? "" : rawQuery).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && decode(pair.substring(0, eq)).strip().equalsIgnoreCase("service")) {
                return decode(pair.substring(eq + 1)).strip().toUpperCase(Locale.ROOT);
            }
        }
        return "";
    }

    /** Whether a filter calls a function that reads other feature types. */
    public static boolean readsOtherLayers(String filter) {
        return filter != null && CROSS_LAYER.matcher(filter).find();
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
