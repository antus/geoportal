// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.proxy;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where a request through the proxy goes: always the registered address of the service, with a path
 * below it and the query of the browser. The browser never names a target: there is no parameter
 * with an address, and a path that would leave the registered one ({@code ..}, an encoded slash, a
 * scheme, a second host) is refused.
 */
public final class ProxyTarget {

    private static final String SEGMENT = "[A-Za-z0-9._~!$&'()*+,;=:@%-]+";

    private ProxyTarget() {}

    /**
     * The address for the service: the registered one, the path below it when given, and the query
     * of the registered address followed by that of the browser, whose parameters with a name the
     * registered query already has are dropped (the registered address wins).
     */
    public static URI build(URI registered, String rawPath, String rawQuery) throws RefusedTarget {
        String basePath = registered.getRawPath() == null || registered.getRawPath().isEmpty()
                ? "/" : registered.getRawPath();
        String path = basePath;
        if (rawPath != null && !rawPath.isEmpty()) {
            path = (basePath.endsWith("/") ? basePath : basePath + "/") + checkedPath(rawPath);
        }
        String query = merge(registered.getRawQuery(), rawQuery);
        URI target;
        try {
            target = new URI(registered.getScheme() + "://" + registered.getRawAuthority() + path
                    + (query == null ? "" : "?" + query));
        } catch (URISyntaxException e) {
            throw new RefusedTarget("The address of the request is not valid.");
        }
        if (!sameOrigin(target, registered) || !target.normalize().getRawPath().startsWith(stripSlash(basePath))) {
            throw new RefusedTarget("The request leaves the registered address of the service.");
        }
        return target;
    }

    /** Whether an address has the scheme, host and port of the registered one. */
    public static boolean sameOrigin(URI candidate, URI registered) {
        if (candidate.getScheme() == null || candidate.getHost() == null) {
            return false;
        }
        return candidate.getScheme().equalsIgnoreCase(registered.getScheme())
                && candidate.getHost().equalsIgnoreCase(registered.getHost())
                && AddressGuard.port(candidate) == AddressGuard.port(registered)
                && candidate.getRawUserInfo() == null;
    }

    /**
     * Resolves the Location of a redirect of the service and returns it only when it stays on the
     * origin of the service.
     */
    public static URI redirect(URI current, String location, URI registered) throws RefusedTarget {
        URI next;
        try {
            next = current.resolve(new URI(location.strip()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new RefusedTarget("The service redirected to an address that is not valid.");
        }
        if (!sameOrigin(next, registered) || next.getRawFragment() != null) {
            throw new RefusedTarget("The service redirected outside its registered address, which the proxy does not follow.");
        }
        return next.normalize();
    }

    private static String checkedPath(String rawPath) throws RefusedTarget {
        if (rawPath.length() > 1000 || rawPath.startsWith("/") || rawPath.contains("\\")) {
            throw new RefusedTarget("The path of the request is not valid.");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : rawPath.split("/", -1)) {
            if (!segment.matches(SEGMENT)) {
                throw new RefusedTarget("The path of the request is not valid.");
            }
            String decoded;
            try {
                decoded = URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new RefusedTarget("The path of the request is not valid.");
            }
            if (decoded.equals(".") || decoded.equals("..") || decoded.contains("/") || decoded.contains("\\")
                    || decoded.contains("%") || decoded.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                throw new RefusedTarget("The path of the request is not valid.");
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    private static String merge(String registered, String browser) {
        if (browser == null || browser.isEmpty()) {
            return registered == null || registered.isEmpty() ? null : registered;
        }
        if (registered == null || registered.isEmpty()) {
            return browser;
        }
        Set<String> taken = new HashSet<>();
        for (String pair : registered.split("&")) {
            taken.add(name(pair));
        }
        StringBuilder query = new StringBuilder(registered);
        for (String pair : browser.split("&")) {
            if (!pair.isEmpty() && !taken.contains(name(pair))) {
                query.append('&').append(pair);
            }
        }
        return query.toString();
    }

    static String name(String pair) {
        int eq = pair.indexOf('=');
        String raw = eq < 0 ? pair : pair.substring(0, eq);
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return raw.toLowerCase(Locale.ROOT);
        }
    }

    private static String stripSlash(String path) {
        return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }
}
