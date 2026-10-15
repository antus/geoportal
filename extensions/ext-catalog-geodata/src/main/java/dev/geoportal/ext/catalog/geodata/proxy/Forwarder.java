// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.proxy;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends one request of the browser to the service and streams the answer back: the address checked
 * once, redirects followed only on the origin of the service (at most three), the credentials of
 * the service never asked of the browser, answers larger than the limit cut, the headers filtered
 * both ways.
 */
public final class Forwarder {

    static final int MAX_REDIRECTS = 3;

    private final AddressGuard guard;
    private final UpstreamClient client;
    private final long maxResponseBytes;

    public Forwarder(AddressGuard guard, UpstreamClient client, long maxResponseBytes) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.client = Objects.requireNonNull(client, "client");
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * Forwards the request and answers for the browser. {@code release} runs once, when the answer
     * has been sent or refused, so that the caller can hold a slot of the service until then.
     */
    public Response forward(
            String method, URI registered, URI target, Map<String, String> headers, byte[] body, Runnable release) {
        AtomicBoolean released = new AtomicBoolean();
        Runnable done = () -> {
            if (released.compareAndSet(false, true)) {
                release.run();
            }
        };
        try {
            Response response = send(method, registered, target, headers, body, done);
            if (!(response.getEntity() instanceof StreamingOutput)) {
                done.run();
            }
            return response;
        } catch (RuntimeException e) {
            done.run();
            throw e;
        }
    }

    private Response send(
            String method, URI registered, URI target, Map<String, String> headers, byte[] body, Runnable done) {
        List<InetAddress> addresses;
        try {
            addresses = guard.check(target);
        } catch (RefusedTarget e) {
            return error(502, e.getMessage());
        }
        URI current = target;
        String verb = method;
        byte[] payload = body;
        for (int hop = 0; ; hop++) {
            UpstreamClient.Answer answer;
            try {
                answer = client.send(new UpstreamClient.Request(verb, current, headers, payload), addresses);
            } catch (SocketTimeoutException e) {
                return error(504, "The service did not answer in time.");
            } catch (IOException e) {
                return error(502, "The service cannot be reached.");
            } catch (IllegalArgumentException e) {
                return error(400, "The request cannot be forwarded.");
            }
            int status = answer.status();
            String location = answer.header("location");
            if (status >= 300 && status < 400 && status != 304 && location != null) {
                answer.close();
                if (hop >= MAX_REDIRECTS) {
                    return error(502, "The service redirected too many times.");
                }
                try {
                    // Same scheme, host and port: the addresses already checked are still the right ones.
                    current = ProxyTarget.redirect(current, location, registered);
                } catch (RefusedTarget e) {
                    return error(502, e.getMessage());
                }
                if (status == 303 || (status == 301 || status == 302) && verb.equals("POST")) {
                    verb = "GET";
                    payload = null;
                }
                continue;
            }
            if (status == 401 || status == 407) {
                // Passed on, it would read as the session of Geoportal ending, and invite a password prompt.
                answer.close();
                return error(502, "The service refused the request for lack of valid credentials.");
            }
            if (answer.length() > maxResponseBytes) {
                answer.close();
                return error(502, "The answer of the service is larger than the proxy allows.");
            }
            Response.ResponseBuilder builder = Response.status(status);
            ProxyHeaders.forBrowser(answer.headers()).forEach(builder::header);
            ProxyHeaders.HARDENING.forEach(builder::header);
            if (answer.length() >= 0) {
                builder.header("Content-Length", answer.length());
            }
            if (verb.equals("HEAD") || status == 204 || status == 304) {
                answer.close();
                return builder.build();
            }
            long limit = maxResponseBytes;
            StreamingOutput stream = out -> {
                try (answer; InputStream in = answer.body()) {
                    byte[] buffer = new byte[16 * 1024];
                    long sent = 0;
                    for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                        sent += n;
                        if (sent > limit) {
                            throw new IOException("The answer of the service is larger than the proxy allows");
                        }
                        out.write(buffer, 0, n);
                    }
                    out.flush();
                } finally {
                    done.run();
                }
            };
            return builder.entity(stream).build();
        }
    }

    /** An answer of the proxy itself, as JSON with a message. */
    public static Response error(int status, String message) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("message", message))
                .build();
    }
}
