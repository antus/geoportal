// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata;

import dev.geoportal.ext.catalog.geodata.proxy.BrowserAccess;
import dev.geoportal.ext.catalog.geodata.proxy.ContentPolicy;
import dev.geoportal.ext.catalog.geodata.proxy.ProxyTarget;
import dev.geoportal.ext.catalog.geodata.proxy.RefusedTarget;
import dev.geoportal.ext.catalog.geodata.proxy.UpstreamClient;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Checks from the server whether the browser could read an external service directly (GP-297): it
 * asks the capabilities as the page would, with the {@code Origin} of the shell, and looks at what the
 * browser would look at, the CORS headers of the answer and the content security policy the shell is
 * served with (MK-128). The address is checked by the guard of the proxy, as every call of the proxy.
 *
 * <p>The policy is read from the answers of the kernel itself, which carry it, and kept 30 seconds
 * (the kernel applies a change of its external origins within 30 seconds too).
 */
@ApplicationScoped
public class BrowserProbe {

    /** At most this much of the capabilities is read: their root element is at the start. */
    static final int MAX_BYTES = 512 * 1024;

    private final ProxySettings settings;
    private final Clock clock;
    private final HttpClient local = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private volatile Kept policy;

    private record Kept(ContentPolicy policy, Instant until) {}

    public BrowserProbe(ProxySettings settings) {
        this.settings = settings;
        this.clock = Clock.systemUTC();
    }

    /**
     * What the browser of a page of {@code shellOrigin} could do with the capabilities of the
     * service.
     *
     * @param base the address of the request, to read the policy of the shell when the kernel does
     *     not answer on its own port
     */
    public AccessCheck check(String url, String kind, String shellOrigin, URI base) {
        Instant now = clock.instant();
        URI registered;
        URI target;
        try {
            registered = URI.create(url);
            target = ProxyTarget.build(registered, null,
                    "SERVICE=" + kind.toUpperCase(Locale.ROOT) + "&REQUEST=GetCapabilities");
        } catch (RefusedTarget | IllegalArgumentException e) {
            return unreadable(null, "The address of the service is not valid.", now);
        }
        String origin = BrowserAccess.origin(registered);
        List<InetAddress> addresses;
        try {
            addresses = settings.guard().check(target);
        } catch (RefusedTarget e) {
            return unreadable(origin, e.getMessage(), now);
        }
        URI current = target;
        for (int hop = 0; hop <= 3; hop++) {
            UpstreamClient.Answer answer;
            try {
                answer = settings.probe().send(new UpstreamClient.Request("GET", current,
                        Map.of("Origin", shellOrigin, "Accept", "*/*"), null), addresses);
            } catch (IOException | IllegalArgumentException e) {
                return unreadable(origin, "The service cannot be reached.", now);
            }
            try (answer) {
                int status = answer.status();
                String location = answer.header("location");
                if (status >= 300 && status < 400 && location != null) {
                    try {
                        current = ProxyTarget.redirect(current, location, registered);
                    } catch (RefusedTarget e) {
                        return unreadable(origin, e.getMessage(), now);
                    }
                    continue;
                }
                if (status < 200 || status >= 300) {
                    return unreadable(origin, "The service answered HTTP " + status + ".", now);
                }
                if (!BrowserAccess.isCapabilities(read(answer.body()))) {
                    return unreadable(origin, "The service did not answer with capabilities.", now);
                }
                List<String> reasons = new ArrayList<>();
                if (!BrowserAccess.corsAllows(answer.headers().get("access-control-allow-origin"), shellOrigin)) {
                    reasons.add(BrowserAccess.CORS);
                }
                if (!policy(base).allowsConnect(current, shellOrigin)) {
                    reasons.add(BrowserAccess.CSP);
                }
                return new AccessCheck(reasons.isEmpty() ? BrowserAccess.DIRECT : BrowserAccess.PROXY, reasons, origin,
                        false, null, now);
            } catch (IOException e) {
                return unreadable(origin, "The answer of the service cannot be read.", now);
            }
        }
        return unreadable(origin, "The service redirected too many times.", now);
    }

    private static AccessCheck unreadable(String origin, String detail, Instant now) {
        return new AccessCheck(BrowserAccess.UNREADABLE, List.of(), origin, false, detail, now);
    }

    private static String read(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_BYTES);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** The policy the shell is served with now. */
    ContentPolicy policy(URI base) {
        Kept kept = policy;
        Instant now = clock.instant();
        if (kept != null && kept.until().isAfter(now)) {
            return kept.policy();
        }
        ContentPolicy read = readPolicy(base);
        policy = new Kept(read, now.plusSeconds(30));
        return read;
    }

    private ContentPolicy readPolicy(URI base) {
        List<URI> candidates = new ArrayList<>();
        try {
            ConfigProvider.getConfig().getOptionalValue("quarkus.http.port", Integer.class)
                    .filter(port -> port > 0)
                    .ifPresent(port -> candidates.add(URI.create("http://127.0.0.1:" + port + "/")));
        } catch (RuntimeException e) {
            // no configuration here: the address of the request only
        }
        Optional.ofNullable(base).map(b -> b.resolve("/")).ifPresent(candidates::add);
        for (URI uri : candidates) {
            try {
                HttpResponse<Void> answer = local.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET()
                        .build(), HttpResponse.BodyHandlers.discarding());
                return ContentPolicy.of(answer.headers().allValues("content-security-policy"));
            } catch (IOException e) {
                // the next address
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Not known: the browser may be blocked, so the proxy is the safe side.
        return ContentPolicy.of(List.of("connect-src 'self'"));
    }
}
