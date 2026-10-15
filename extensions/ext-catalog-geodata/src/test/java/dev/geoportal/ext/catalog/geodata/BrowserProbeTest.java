// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.geoportal.ext.catalog.geodata.proxy.BrowserAccess;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-297 against stub services on the loopback: the check asks the capabilities as the page would and
 * reads the CORS headers of the answer and the policy of the shell, served here by another stub.
 */
@Tag("GP-297")
class BrowserProbeTest {

    private static final String CAPABILITIES = "<?xml version=\"1.0\"?><WMS_Capabilities version=\"1.3.0\">"
            + "<Capability><Layer><Name>roads</Name></Layer></Capability></WMS_Capabilities>";

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<String> origins = new ArrayList<>();
    private BrowserProbe probe;

    @BeforeEach
    void start() {
        probe = new BrowserProbe(new ProxySettings("127.0.0.1/32", 2000, 5000, 1_000_000, 1000, 2));
    }

    @AfterEach
    void stop() {
        servers.forEach(s -> s.stop(0));
    }

    /** A service that answers its capabilities, with CORS or without, and remembers the Origin. */
    private String service(boolean cors, String body, int status) throws IOException {
        return serve(exchange -> {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            origins.add(origin);
            if (cors && origin != null) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
            }
            exchange.getResponseHeaders().add("Content-Type", "text/xml");
            send(exchange, status, body);
        });
    }

    /** The shell: every answer carries its policy, or none. */
    private URI shell(String policy) throws IOException {
        return URI.create(serve(exchange -> {
            if (policy != null) {
                exchange.getResponseHeaders().add("Content-Security-Policy", policy);
            }
            send(exchange, 200, "<!doctype html>");
        }) + "/");
    }

    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private String serve(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", handler::handle);
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void aServiceWithCorsAndAnOriginTheShellAllowsIsReadDirectly() throws IOException {
        String url = service(true, CAPABILITIES, 200);
        URI shell = shell("default-src 'self'; connect-src 'self' " + url);
        AccessCheck check = probe.check(url + "/wms", "wms", BrowserAccess.origin(shell), shell);
        assertEquals(BrowserAccess.DIRECT, check.outcome(), () -> String.valueOf(check));
        assertEquals(List.of(), check.reasons());
        assertEquals(url, check.origin());
        assertFalse(check.proxyTurnedOn());
        assertEquals(BrowserAccess.origin(shell), origins.getFirst(), "asked with the Origin of the shell");
    }

    @Test
    void withoutCorsOnlyTheProxyReadsIt() throws IOException {
        String url = service(false, CAPABILITIES, 200);
        URI shell = shell(null);
        AccessCheck check = probe.check(url + "/wms", "wms", BrowserAccess.origin(shell), shell);
        assertEquals(BrowserAccess.PROXY, check.outcome());
        assertEquals(List.of(BrowserAccess.CORS), check.reasons());
    }

    @Test
    void anOriginTheShellDoesNotListNeedsTheProxyEvenWithCors() throws IOException {
        String url = service(true, CAPABILITIES, 200);
        URI shell = shell("default-src 'self'; connect-src 'self' https://sso.example.org");
        AccessCheck check = probe.check(url + "/wms", "wms", BrowserAccess.origin(shell), shell);
        assertEquals(BrowserAccess.PROXY, check.outcome());
        assertEquals(List.of(BrowserAccess.CSP), check.reasons());

        String bare = service(false, CAPABILITIES, 200);
        AccessCheck both = probe.check(bare + "/wms", "wms", BrowserAccess.origin(shell), shell);
        assertEquals(List.of(BrowserAccess.CORS, BrowserAccess.CSP), both.reasons());
    }

    @Test
    void anErrorPageIsReadableNeitherFromTheBrowserNorFromTheProxy() throws IOException {
        URI shell = shell(null);
        String page = service(true, "<!DOCTYPE html><html><body>Down for maintenance</body></html>", 200);
        AccessCheck check = probe.check(page + "/wms", "wms", BrowserAccess.origin(shell), shell);
        assertEquals(BrowserAccess.UNREADABLE, check.outcome());
        assertNotNull(check.detail());

        String failing = service(true, CAPABILITIES, 503);
        assertEquals(BrowserAccess.UNREADABLE, probe.check(failing + "/wms", "wms", BrowserAccess.origin(shell), shell).outcome());
        assertEquals(BrowserAccess.UNREADABLE,
                probe.check("http://127.0.0.1:9/wms", "wms", BrowserAccess.origin(shell), shell).outcome());
    }

    @Test
    void anAddressTheProxyMayNotReachIsNotAsked() {
        ProxySettings strict = new ProxySettings(" ", 2000, 5000, 1_000_000, 1000, 2);
        AccessCheck check = new BrowserProbe(strict).check("http://127.0.0.1:9/wms", "wms", "https://geo.example.org",
                URI.create("http://127.0.0.1:9/"));
        assertEquals(BrowserAccess.UNREADABLE, check.outcome());
    }
}
