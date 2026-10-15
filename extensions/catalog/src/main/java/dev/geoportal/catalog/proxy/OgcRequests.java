// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.proxy;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * What the proxy adds to the requests of features (WFS): the reference system declared for the
 * service, so that the server never has to guess it and the client never has to ask.
 */
public final class OgcRequests {

    /** A reference system as OGC names it: EPSG:4326, urn:ogc:def:crs:EPSG::3857, CRS:84. */
    public static final String CRS = "[A-Za-z][A-Za-z0-9.:-]{1,59}";

    /** The most features a request of the proxy asks for. */
    public static final int MAX_FEATURES = 10_000;

    private OgcRequests() {}

    /** The query with srsName added, when it is a GetFeature that does not name one. */
    public static String declareCrs(String rawQuery, String crs) {
        if (rawQuery == null || crs == null || crs.isBlank()) {
            return rawQuery;
        }
        boolean getFeature = false;
        boolean named = false;
        for (String pair : rawQuery.split("&")) {
            String name = ProxyTarget.name(pair);
            int eq = pair.indexOf('=');
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            if (name.equals("request") && value.equalsIgnoreCase("GetFeature")) {
                getFeature = true;
            }
            if (name.equals("srsname")) {
                named = true;
            }
        }
        return getFeature && !named ? rawQuery + "&srsName=" + encode(crs) : rawQuery;
    }

    /**
     * A GetFeature built directly, without asking the capabilities nor the description of the
     * feature type first: the negotiation that servers behind a proxy or a virtual host often
     * break. WFS 1.1.0 with a singular typeName is answered by every server, GeoJSON as output, the
     * declared reference system, and a limit.
     */
    public static String features(String typeName, String bbox, int count, String crs) {
        StringBuilder query = new StringBuilder("service=WFS&version=1.1.0&request=GetFeature");
        query.append("&typeName=").append(encode(typeName));
        query.append("&outputFormat=").append(encode("application/json"));
        query.append("&srsName=").append(encode(crs));
        query.append("&maxFeatures=").append(Math.max(1, Math.min(count, MAX_FEATURES)));
        if (bbox != null && !bbox.isBlank()) {
            query.append("&bbox=").append(encode(bbox + "," + crs));
        }
        return query.toString();
    }

    /** Whether a bbox is four numbers, as the features request takes it. */
    public static boolean validBbox(String bbox) {
        return bbox == null || bbox.isBlank()
                || bbox.matches("-?\\d+(\\.\\d+)?(,-?\\d+(\\.\\d+)?){3}");
    }

    /** Whether a feature type name is a plain qualified name, such as topp:states. */
    public static boolean validTypeName(String typeName) {
        return typeName != null && typeName.matches("[A-Za-z_][\\w.-]*(:[A-Za-z_][\\w.-]*)?") && typeName.length() <= 200;
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
