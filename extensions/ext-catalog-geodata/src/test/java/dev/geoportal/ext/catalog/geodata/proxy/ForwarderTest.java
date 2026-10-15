// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-939: the proxy against stub servers on the loopback, which these tests allow as the network of
 * an intranet: what reaches the service, what comes back, and the attempts to reach something else.
 */
@Tag("GP-939")
class ForwarderTest {

    /** What the stub service received: method, path and query, headers. */
    record Seen(String method, String target, Map<String, List<String>> headers) {}

    private HttpServer service;
    private HttpServer other;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger otherHits = new AtomicInteger();
    private URI registered;

    @BeforeEach
    void start() throws IOException {
        service = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        service.createContext("/", this::answer);
        service.start();
        other = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        other.createContext("/", exchange -> {
            otherHits.incrementAndGet();
            send(exchange, 200, "text/plain", "secret of another server");
        });
        other.start();
        registered = URI.create("http://127.0.0.1:" + service.getAddress().getPort() + "/ows?map=base.map");
    }

    @AfterEach
    void stop() {
        service.stop(0);
        other.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                Map.copyOf(exchange.getRequestHeaders())));
        String query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
        if (query.contains("to=metadata")) {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            send(exchange, 302, "text/plain", "");
        } else if (query.contains("to=other")) {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + other.getAddress().getPort() + "/");
            send(exchange, 302, "text/plain", "");
        } else if (query.contains("to=same")) {
            exchange.getResponseHeaders().add("Location", "/ows?map=base.map&moved=1");
            send(exchange, 301, "text/plain", "");
        } else if (query.contains("to=loop")) {
            exchange.getResponseHeaders().add("Location", "/ows?to=loop");
            send(exchange, 302, "text/plain", "");
        } else if (query.contains("auth=no")) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"x\"");
            send(exchange, 401, "text/plain", "no");
        } else if (query.contains("big=1")) {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, 0); // chunked
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[4096]);
            }
        } else if (query.contains("slow=1")) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            send(exchange, 200, "text/plain", "late");
        } else {
            exchange.getResponseHeaders().add("Set-Cookie", "session=stolen");
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Strict-Transport-Security", "max-age=1");
            exchange.getResponseHeaders().add("Cache-Control", "public, max-age=60");
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, 0); // chunked
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("PNG-".getBytes(StandardCharsets.US_ASCII));
                out.write(query.getBytes(StandardCharsets.US_ASCII));
            }
        }
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Forwarder forwarder(AddressGuard.Resolver resolver, long max, int readTimeout) throws Exception {
        AddressGuard guard = new AddressGuard(resolver, List.of(Network.parse("127.0.0.1/32")));
        return new Forwarder(guard, new UpstreamClient(2000, readTimeout, SSLContext.getDefault().getSocketFactory()), max);
    }

    private Forwarder forwarder() throws Exception {
        return forwarder(InetAddress::getAllByName, 1_000_000, 5000);
    }

    private Response get(Forwarder forwarder, String query) throws Exception {
        URI target = ProxyTarget.build(registered, null, query);
        Map<String, String> headers = ProxyHeaders.forService(Map.of(
                "accept", "image/png",
                "cookie", "mosaikit-session=abc",
                "authorization", "Basic cGVyc29uOnB3",
                "x-mosaikit-organization", "example",
                "x-forwarded-for", "10.0.0.1",
                "accept-encoding", "gzip, br")::get);
        return forwarder.forward("GET", registered, target, headers, null, () -> {});
    }

    private static String body(Response response) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ((StreamingOutput) response.getEntity()).write(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void GP_939_a_tile_comes_back_through_the_proxy_with_the_query_of_the_map() throws Exception {
        Response response = get(forwarder(), "SERVICE=WMS&REQUEST=GetMap&LAYERS=a&BBOX=0,0,1,1");
        assertEquals(200, response.getStatus());
        assertEquals("PNG-map=base.map&SERVICE=WMS&REQUEST=GetMap&LAYERS=a&BBOX=0,0,1,1", body(response));
        assertEquals("image/png", response.getHeaderString("content-type"));
        assertEquals("public, max-age=60", response.getHeaderString("cache-control"));
        assertEquals("/ows?map=base.map&SERVICE=WMS&REQUEST=GetMap&LAYERS=a&BBOX=0,0,1,1", seen.getFirst().target());
    }

    @Test
    void GP_939_nothing_of_the_person_reaches_the_service() throws Exception {
        body(get(forwarder(), "SERVICE=WMS"));
        Map<String, List<String>> headers = seen.getFirst().headers();
        for (String name : List.of("Cookie", "Authorization", "X-mosaikit-organization", "X-forwarded-for")) {
            assertNull(headers.get(name), name);
        }
        assertEquals(List.of("image/png"), headers.get("Accept"));
        assertEquals(List.of("gzip"), headers.get("Accept-encoding"));
        assertEquals(List.of("close"), headers.get("Connection"));
    }

    @Test
    void GP_939_nothing_of_the_service_acts_on_the_origin_of_geoportal() throws Exception {
        Response response = get(forwarder(), "SERVICE=WMS");
        for (String name : List.of("Set-Cookie", "Access-Control-Allow-Origin", "Strict-Transport-Security")) {
            assertNull(response.getHeaderString(name), name);
        }
        assertEquals("nosniff", response.getHeaderString("X-Content-Type-Options"));
        assertTrue(response.getHeaderString("Content-Security-Policy").contains("sandbox"));
    }

    @Test
    void GP_939_a_redirect_to_the_metadata_address_is_refused() throws Exception {
        Response response = get(forwarder(), "to=metadata");
        assertEquals(502, response.getStatus());
        assertTrue(response.getEntity().toString().contains("outside its registered address"));
    }

    @Test
    void GP_939_a_redirect_to_another_server_is_refused_even_on_an_allowed_network() throws Exception {
        Response response = get(forwarder(), "to=other");
        assertEquals(502, response.getStatus());
        assertEquals(0, otherHits.get());
    }

    @Test
    void GP_939_a_redirect_on_the_origin_of_the_service_is_followed_a_few_times_at_most() throws Exception {
        Response moved = get(forwarder(), "to=same");
        assertEquals(200, moved.getStatus());
        assertEquals("PNG-map=base.map&moved=1", body(moved));
        seen.clear();
        assertEquals(502, get(forwarder(), "to=loop").getStatus());
        assertEquals(Forwarder.MAX_REDIRECTS + 1, seen.size());
    }

    @Test
    void GP_939_the_name_is_resolved_once_and_the_checked_address_is_the_one_connected_to() throws Exception {
        // A rebinding name: the check sees 127.0.0.1, a second resolution would give the metadata address.
        AtomicInteger resolutions = new AtomicInteger();
        AddressGuard.Resolver rebinding = host -> new InetAddress[] {
            InetAddress.getByName(resolutions.getAndIncrement() == 0 ? "127.0.0.1" : "169.254.169.254")
        };
        registered = URI.create("http://rebind.example.org:" + service.getAddress().getPort() + "/ows");
        Response response = get(forwarder(rebinding, 1_000_000, 5000), "to=same");
        assertEquals(200, response.getStatus());
        assertEquals(1, resolutions.get());
        assertEquals(List.of("rebind.example.org:" + service.getAddress().getPort()), seen.getFirst().headers().get("Host"));
    }

    @Test
    void GP_939_a_name_that_resolves_to_127_0_0_1_is_not_reached_without_the_setting() throws Exception {
        AddressGuard closed = new AddressGuard(host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")}, List.of());
        Forwarder forwarder = new Forwarder(closed,
                new UpstreamClient(2000, 2000, SSLContext.getDefault().getSocketFactory()), 1000);
        registered = URI.create("http://evil.example.org:" + service.getAddress().getPort() + "/ows");
        Response response = get(forwarder, "SERVICE=WMS");
        assertEquals(502, response.getStatus());
        assertTrue(seen.isEmpty());
    }

    @Test
    void GP_939_the_credentials_asked_by_the_service_are_not_passed_to_the_browser() throws Exception {
        Response response = get(forwarder(), "auth=no");
        assertEquals(502, response.getStatus());
        assertNull(response.getHeaderString("WWW-Authenticate"));
    }

    @Test
    void GP_939_an_answer_larger_than_the_limit_is_cut() throws Exception {
        Response response = get(forwarder(InetAddress::getAllByName, 1000, 5000), "big=1");
        assertEquals(200, response.getStatus());
        assertThrows(IOException.class, () -> body(response));
    }

    @Test
    void GP_939_a_service_that_does_not_answer_in_time_gives_504() throws Exception {
        Response response = get(forwarder(InetAddress::getAllByName, 1000, 300), "slow=1");
        assertEquals(504, response.getStatus());
    }

    @Test
    void GP_939_the_slot_of_the_service_is_released_once_the_answer_is_sent() throws Exception {
        AtomicInteger released = new AtomicInteger();
        URI target = ProxyTarget.build(registered, null, "SERVICE=WMS");
        Response response = forwarder().forward("GET", registered, target, Map.of(), null, released::incrementAndGet);
        assertEquals(0, released.get());
        body(response);
        assertEquals(1, released.get());
        forwarder().forward("GET", registered, ProxyTarget.build(registered, null, "to=metadata"), Map.of(), null,
                released::incrementAndGet);
        assertEquals(2, released.get());
        assertFalse(seen.isEmpty());
    }
}
