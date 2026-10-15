// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.proxy;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether the browser could read an external service directly (GP-297): the answer to the
 * capabilities request must be capabilities, the service must allow the origin of the shell with
 * CORS, and the content security policy of the shell must let scripts contact the service (MK-128).
 * The server reaches more than the browser does, so it never asks "can I read it?" but "would the
 * browser be allowed to read what I read?".
 */
public final class BrowserAccess {

    /** The browser reads the service directly. */
    public static final String DIRECT = "direct";
    /** Only the server reads it: the map goes through the proxy. */
    public static final String PROXY = "proxy";
    /** Nobody reads capabilities from it. */
    public static final String UNREADABLE = "unreadable";

    /** Reasons for the proxy: the service does not allow the shell with CORS... */
    public static final String CORS = "cors";
    /** ...or the policy of the shell does not let the browser contact it. */
    public static final String CSP = "csp";

    private static final Pattern PROLOGUE = Pattern.compile(
            "\\A(?:\\s|<\\?.*?\\?>|<!--.*?-->|<!DOCTYPE(?:[^\\[>]|\\[.*?])*>)*", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ROOT = Pattern.compile("\\A<(?:[A-Za-z_][\\w.-]*:)?([A-Za-z_][\\w.-]*)[\\s/>]");

    private BrowserAccess() {}

    /**
     * Whether an answer with these {@code Access-Control-Allow-Origin} values may be read by a page of
     * {@code origin}, without credentials: exactly one value, {@code *} or that origin. Browsers
     * compare the origin exactly, and refuse two values.
     */
    public static boolean corsAllows(List<String> values, String origin) {
        if (values == null || values.size() != 1 || values.getFirst() == null) {
            return false;
        }
        String value = values.getFirst().strip();
        return value.equals("*") || value.equals(origin);
    }

    /**
     * Whether a text is OGC capabilities, the way the page reads it: its root element is
     * {@code ...Capabilities} (WMS_Capabilities, WMT_MS_Capabilities, WFS_Capabilities,
     * WCS_Capabilities, Capabilities of WMTS and WCS 2). An error page, an exception report or an
     * empty answer is not.
     */
    public static boolean isCapabilities(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String body = text.charAt(0) == '﻿' ? text.substring(1) : text;
        Matcher prologue = PROLOGUE.matcher(body);
        int start = prologue.lookingAt() ? prologue.end() : 0;
        Matcher root = ROOT.matcher(body.substring(start, Math.min(body.length(), start + 300)));
        return root.lookingAt() && root.group(1).endsWith("Capabilities");
    }

    /** The origin of an address as a browser writes it: lowercase, without the default port. */
    public static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains(":") && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        int port = uri.getPort();
        boolean standard = port < 0 || (scheme.equals("http") || scheme.equals("ws")) && port == 80
                || (scheme.equals("https") || scheme.equals("wss")) && port == 443;
        return scheme + "://" + host + (standard ? "" : ":" + port);
    }
}
