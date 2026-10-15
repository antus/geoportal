// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Asks Mosaikit whose a personal access key is (MK-060), with {@code POST /api/v1/services/check} of the
 * service identities (MK-124), for the AuthKey filter of GeoServer (GP-336, ADR-0007). The catalog calls
 * the kernel it runs in, with the key of the service identity that called it: the gateway, given the
 * API {@code catalog-geodata} and the endpoint {@code check-key}; it has no credential of its own.
 *
 * <p>Setting: {@code geoportal.catalog.kernel-url}, the kernel as the catalog reaches it, by default
 * {@code http://127.0.0.1:<quarkus.http.port>}.
 */
@ApplicationScoped
public class KeyCheck {

    private static final Logger LOG = Logger.getLogger(KeyCheck.class);
    static final String PATH = "/api/v1/services/check";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * The name of the person of a key, as GeoServer knows people: their username; empty when the key
     * opens nothing (unknown, revoked, expired, a suspended person).
     *
     * @param authorization the Authorization header of the service identity that asks
     */
    public Optional<String> person(String key, String authorization) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(kernel() + PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.createObjectNode().put("key", key).toString()))
                .build();
        HttpResponse<String> answer;
        try {
            answer = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            LOG.warnf("The check of a personal key failed: %s", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
        if (answer.statusCode() == 401) {
            return Optional.empty();
        }
        if (answer.statusCode() != 200) {
            // 403: the service identity was not given check-key; the key stays anonymous.
            LOG.warnf("The check of a personal key answered %d: give the service identity of the gateway the "
                    + "endpoint check-key", answer.statusCode());
            return Optional.empty();
        }
        try {
            JsonNode body = json.readTree(answer.body());
            String username = body.path("account").path("username").asText("");
            return username.isBlank() ? Optional.empty() : Optional.of(username);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static String kernel() {
        Config config = ConfigProvider.getConfig();
        return config.getOptionalValue("geoportal.catalog.kernel-url", String.class)
                .map(u -> u.endsWith("/") ? u.substring(0, u.length() - 1) : u)
                .orElseGet(() -> "http://127.0.0.1:"
                        + config.getOptionalValue("quarkus.http.port", Integer.class).orElse(8080));
    }
}
