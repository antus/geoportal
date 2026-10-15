// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.proxy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The headers that cross the proxy, by allow-list in both directions. Nothing of the person travels
 * to the service (cookies, Authorization, the headers of Mosaikit and of the gateway, the address of
 * the browser), and nothing of the service reaches the browser that could act on the origin of
 * Geoportal (Set-Cookie, WWW-Authenticate, CORS, HSTS, Location, Refresh, Link, Content-Security
 * policies of the service). Hop-by-hop headers are never forwarded.
 */
public final class ProxyHeaders {

    /** Headers of the browser forwarded to the service. */
    static final Set<String> REQUEST = Set.of(
            "accept", "accept-language", "if-none-match", "if-modified-since", "range", "if-range");

    /** Headers of the service forwarded to the browser. */
    static final Set<String> RESPONSE = Set.of(
            "content-type", "content-encoding", "content-language", "content-range", "accept-ranges",
            "cache-control", "etag", "expires", "last-modified", "vary");

    /** What the proxy adds to every answer, so that what the service sends cannot act as a page. */
    public static final Map<String, String> HARDENING = Map.of(
            "X-Content-Type-Options", "nosniff",
            "Content-Security-Policy", "default-src 'none'; img-src data:; style-src 'unsafe-inline'; sandbox",
            "Cross-Origin-Resource-Policy", "same-origin",
            "Referrer-Policy", "no-referrer");

    private ProxyHeaders() {}

    /**
     * The headers for the service from those of the browser: the allowed ones, and Accept-Encoding
     * reduced to gzip or nothing, so that the proxy never has to understand another encoding.
     */
    public static Map<String, String> forService(Function<String, String> browser) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : REQUEST) {
            String value = browser.apply(name);
            if (value != null && !value.isBlank() && value.length() <= 1024 && safe(value)) {
                headers.put(name, value);
            }
        }
        String encoding = browser.apply("accept-encoding");
        headers.put("accept-encoding",
                encoding != null && encoding.toLowerCase(Locale.ROOT).contains("gzip") ? "gzip" : "identity");
        headers.put("user-agent", "Geoportal-proxy");
        return headers;
    }

    /** The headers for the browser from those of the service. */
    public static Map<String, String> forBrowser(Map<String, List<String>> service) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : RESPONSE) {
            List<String> values = service.get(name);
            if (values != null && !values.isEmpty() && safe(values.getFirst())) {
                headers.put(name, values.getFirst());
            }
        }
        String type = headers.get("content-type");
        if (type != null && !type.matches("[\\w.+-]+/[\\w.+-]+(\\s*;.*)?")) {
            headers.put("content-type", "application/octet-stream");
        }
        return headers;
    }

    private static boolean safe(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7f || c > 0xff) {
                return false;
            }
        }
        return true;
    }
}
