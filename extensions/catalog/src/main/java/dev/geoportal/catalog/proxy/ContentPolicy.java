// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.proxy;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The content security policy that the shell is served with, as far as GP-297 needs it: whether a
 * script of the shell may contact an origin ({@code connect-src}, else {@code default-src}). Without a
 * policy the browser contacts any origin. Several policies (several headers, or one joined with
 * commas) must all allow it, as in the browser. The source expressions of CSP level 3 that matter
 * here: {@code *}, {@code 'self'}, {@code 'none'}, schemes ({@code https:}) and hosts with an
 * optional scheme, a leading {@code *.} and a port or {@code *}; a path in a source is ignored.
 */
public final class ContentPolicy {

    /** No policy: the shell is served without one. */
    public static final ContentPolicy NONE = new ContentPolicy(List.of());

    private final List<List<String>> policies;

    private ContentPolicy(List<List<String>> policies) {
        this.policies = policies;
    }

    /** The policy of these header values; empty or missing values are no policy. */
    public static ContentPolicy of(List<String> headers) {
        List<List<String>> policies = new ArrayList<>();
        if (headers != null) {
            for (String header : headers) {
                if (header == null) {
                    continue;
                }
                for (String policy : header.split(",")) {
                    if (!policy.isBlank()) {
                        sources(policy).ifPresent(policies::add);
                    }
                }
            }
        }
        return policies.isEmpty() ? NONE : new ContentPolicy(policies);
    }

    /** Whether the shell is served with a policy. */
    public boolean present() {
        return !policies.isEmpty();
    }

    /** The sources of connect-src, else of default-src; empty when neither restricts. */
    private static Optional<List<String>> sources(String policy) {
        List<String> fallback = null;
        for (String directive : policy.split(";")) {
            String[] parts = directive.strip().split("\\s+");
            if (parts.length == 0 || parts[0].isEmpty()) {
                continue;
            }
            String name = parts[0].toLowerCase(Locale.ROOT);
            List<String> values = Arrays.asList(parts).subList(1, parts.length);
            if (name.equals("connect-src")) {
                return Optional.of(values);
            }
            if (name.equals("default-src") && fallback == null) {
                fallback = values;
            }
        }
        return Optional.ofNullable(fallback);
    }

    /**
     * Whether a script of a page of {@code self} may contact {@code target}.
     *
     * @param target an address of the service
     * @param self the origin of the shell, such as {@code https://geo.example.org}
     */
    public boolean allowsConnect(URI target, String self) {
        for (List<String> sources : policies) {
            if (sources.stream().noneMatch(source -> matches(source, target, self))) {
                return false;
            }
        }
        return true;
    }

    static boolean matches(String source, URI target, String self) {
        String scheme = target.getScheme().toLowerCase(Locale.ROOT);
        String host = target.getHost().toLowerCase(Locale.ROOT);
        int port = AddressGuard.port(target);
        String s = source.strip();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.equals("'none'")) {
            return false;
        }
        if (lower.equals("'self'")) {
            return BrowserAccess.origin(target).equals(self);
        }
        if (lower.startsWith("'")) {
            return false; // nonces, hashes and keywords say nothing of addresses
        }
        if (s.equals("*")) {
            return scheme.equals("http") || scheme.equals("https") || scheme.equals("ws") || scheme.equals("wss");
        }
        if (lower.matches("[a-z][a-z0-9+.-]*:")) {
            String only = lower.substring(0, lower.length() - 1);
            return only.equals(scheme) || only.equals("http") && scheme.equals("https")
                    || only.equals("ws") && scheme.equals("wss");
        }
        String rest = lower;
        String sourceScheme = null;
        int sep = rest.indexOf("://");
        if (sep >= 0) {
            sourceScheme = rest.substring(0, sep);
            rest = rest.substring(sep + 3);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        String sourceHost = rest;
        String sourcePort = null;
        int colon = rest.lastIndexOf(':');
        if (colon >= 0 && !rest.endsWith("]")) {
            sourceHost = rest.substring(0, colon);
            sourcePort = rest.substring(colon + 1);
        }
        if (sourceScheme == null) {
            sourceScheme = self.substring(0, self.indexOf(':')).toLowerCase(Locale.ROOT);
        }
        boolean schemeMatches = sourceScheme.equals(scheme) || sourceScheme.equals("http") && scheme.equals("https")
                || sourceScheme.equals("ws") && scheme.equals("wss");
        if (!schemeMatches) {
            return false;
        }
        boolean hostMatches = sourceHost.startsWith("*.")
                ? host.endsWith(sourceHost.substring(1))
                : sourceHost.equals(host);
        if (!hostMatches) {
            return false;
        }
        if ("*".equals(sourcePort)) {
            return true;
        }
        if (sourcePort == null) {
            int standard = sourceScheme.equals("https") || sourceScheme.equals("wss") ? 443 : 80;
            return port == standard || standard == 80 && port == 443 && !sourceScheme.equals(scheme);
        }
        try {
            return Integer.parseInt(sourcePort) == port;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
