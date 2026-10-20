// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.access;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerClient;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerException;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The features and columns a person receives from the WFS service of a layer (GP-339): asked to GeoServer by
 * the catalog as its administrator, with the limits that GeoFence applies to the person (the areas of the
 * {@code LIMIT} rules, the row filter and the hidden columns of the {@code ALLOW} that decides, see
 * {@link Evaluation}), so that a data rule can be previewed before it is saved, and the diagnosis of a
 * person counts what their rules leave. GeoFence keeps the features that intersect an area, keeps the rows
 * that match the filter, and leaves the hidden columns out: the same filter in CQL gives the same answer,
 * which the GeoServer tests of GP-339 compare with the answer to the person.
 */
public final class Preview {

    /** At most this many features are shown. */
    public static final int SAMPLE = 10;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern MATCHED = Pattern.compile("numberMatched=\"(\\d+)\"");

    private Preview() {}

    /**
     * What WFS answers.
     *
     * @param total the features of the layer
     * @param matched the features the person receives
     * @param columns every column of the layer but the geometry, in order
     * @param visible the columns the person receives, in order
     * @param hidden the columns left out
     * @param sample the first features the person receives, by column
     */
    public record Answer(long total, long matched, List<String> columns, List<String> visible, List<String> hidden,
            List<Map<String, Object>> sample) {}

    /** The limits GeoFence applies, from the decision of {@link Evaluation}. */
    public record Limits(List<String> areas, String cql, Set<String> hidden) {
        public Limits {
            areas = areas == null ? List.of() : List.copyOf(areas);
            hidden = hidden == null ? Set.of() : Set.copyOf(hidden);
        }

        public static Limits of(Evaluation.Decision d) {
            return new Limits(d.areas(), d.cql(), d.hidden());
        }

        /** No limit: the whole layer. */
        public static final Limits NONE = new Limits(List.of(), null, Set.of());
    }

    /**
     * Asks WFS of the layer with the limits.
     *
     * @param bindings the attributes of the layer with their Java class, as {@link GeoServerClient#bindings} reads them
     */
    public static Answer of(GeoServerClient client, String workspace, String layer, Map<String, String> bindings,
            Limits limits) {
        String geometry = null;
        List<String> columns = new ArrayList<>();
        for (Map.Entry<String, String> b : bindings.entrySet()) {
            if (geometry(b.getValue())) {
                if (geometry == null) {
                    geometry = b.getKey();
                }
            } else {
                columns.add(b.getKey());
            }
        }
        List<String> visible = columns.stream().filter(c -> !limits.hidden().contains(c)).toList();
        List<String> hidden = columns.stream().filter(c -> limits.hidden().contains(c)).toList();
        String filter = filter(limits, geometry);
        long total = hits(client, workspace, layer, null);
        long matched = filter == null ? total : hits(client, workspace, layer, filter);
        List<Map<String, Object>> sample = matched == 0 ? List.of() : sample(client, workspace, layer, filter, visible, geometry);
        return new Answer(total, matched, columns, visible, hidden, sample);
    }

    /** How many features WFS of the layer answers with the limits, without the features. */
    public static long count(GeoServerClient client, String workspace, String layer, Map<String, String> bindings,
            Limits limits) {
        String geometry = bindings.entrySet().stream().filter(b -> geometry(b.getValue())).map(Map.Entry::getKey)
                .findFirst().orElse(null);
        return hits(client, workspace, layer, filter(limits, geometry));
    }

    /** The limits as one CQL filter for the administrator of GeoServer; null without limits of rows or area. */
    static String filter(Limits limits, String geometry) {
        List<String> parts = new ArrayList<>();
        if (limits.cql() != null && !limits.cql().isBlank()) {
            parts.add("(" + limits.cql() + ")");
        }
        for (String area : limits.areas()) {
            if (geometry == null) {
                throw new IllegalStateException("The layer has no geometry to limit by area");
            }
            // The area is kept in the reference system of the data, which GeoServer assumes for a literal.
            parts.add("INTERSECTS(\"" + geometry + "\", " + wkt(area) + ")");
        }
        return parts.isEmpty() ? null : String.join(" AND ", parts);
    }

    /** {@code SRID=<code>;WKT} without the SRID. */
    static String wkt(String ewkt) {
        int semicolon = ewkt.indexOf(';');
        return ewkt.regionMatches(true, 0, "SRID=", 0, 5) && semicolon > 0 ? ewkt.substring(semicolon + 1) : ewkt;
    }

    private static long hits(GeoServerClient client, String workspace, String layer, String filter) {
        HttpResponse<String> r = client.ogcText(workspace + "/" + layer + "/wfs", "service=WFS&version=2.0.0&request=GetFeature"
                + "&resultType=hits&typeNames=" + enc(workspace + ":" + layer) + (filter == null ? "" : "&cql_filter=" + enc(filter)));
        String body = r.body() == null ? "" : r.body();
        Matcher m = MATCHED.matcher(body);
        if (r.statusCode() != 200 || !m.find()) {
            String why = GeoServerClient.reason(body);
            throw new GeoServerException(r.statusCode(), "WFS of " + layer + " does not count the features"
                    + (why == null ? "" : ": " + why));
        }
        return Long.parseLong(m.group(1));
    }

    private static List<Map<String, Object>> sample(GeoServerClient client, String workspace, String layer, String filter,
            List<String> visible, String geometry) {
        List<String> properties = visible.isEmpty() && geometry != null ? List.of(geometry) : visible;
        HttpResponse<String> r = client.ogcText(workspace + "/" + layer + "/wfs", "service=WFS&version=2.0.0&request=GetFeature"
                + "&typeNames=" + enc(workspace + ":" + layer) + "&outputFormat=application/json&count=" + SAMPLE
                + (properties.isEmpty() ? "" : "&propertyName=" + enc(String.join(",", properties)))
                + (filter == null ? "" : "&cql_filter=" + enc(filter)));
        String body = r.body() == null ? "" : r.body();
        if (r.statusCode() != 200 || !body.strip().startsWith("{")) {
            String why = GeoServerClient.reason(body);
            throw new GeoServerException(r.statusCode(), "WFS of " + layer + " does not answer the features"
                    + (why == null ? "" : ": " + why));
        }
        List<Map<String, Object>> sample = new ArrayList<>();
        try {
            for (JsonNode f : JSON.readTree(body).path("features")) {
                // Only the visible columns, by name: nothing else of the answer reaches the page.
                Map<String, Object> row = new LinkedHashMap<>();
                for (String c : visible) {
                    JsonNode v = f.path("properties").get(c);
                    row.put(c, v == null || v.isNull() ? null : v.isNumber() ? v.numberValue() : v.asText());
                }
                sample.add(row);
            }
        } catch (IOException e) {
            throw new GeoServerException(r.statusCode(), "WFS of " + layer + " answers what is not GeoJSON");
        }
        return sample;
    }

    static boolean geometry(String binding) {
        return binding != null && (binding.contains(".geom.") || binding.endsWith("Geometry"));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
