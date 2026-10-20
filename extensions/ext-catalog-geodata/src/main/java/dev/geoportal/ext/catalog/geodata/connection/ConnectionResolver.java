// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.HttpHeaders;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * GP-986: a reference of the source picker of ext-catalog-connection (EXT-026), resolved as the person of
 * the request would resolve it, with {@code POST /api/v1/ext/connections/references/resolve} of the
 * framework, called on the kernel that runs the geodata with the authentication of the request (its
 * session or its key) and its organization. So the framework decides, as for any editor, whether the
 * person may use the connection, and gives the address of the source as it is now; the geodata never
 * see the credential of the connection, only its name.
 *
 * <p>Setting: {@code geoportal.catalog.kernel-url}, the kernel as the geodata reach it, by default
 * {@code http://127.0.0.1:<quarkus.http.port>}.
 */
@ApplicationScoped
public class ConnectionResolver {

    /** The identifier of the framework of connections, which owns the references. */
    public static final String FRAMEWORK = "io.github.mosaikit.ext.catalog.connection";

    static final String PATH = "/api/v1/ext/connections/references/resolve";

    /** The headers of the request that carry who asks and for which organization. */
    private static final List<String> FORWARDED = List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.COOKIE,
            "X-Mosaikit-Organization", "X-Requested-With", "X-CSRF-Token", "X-XSRF-TOKEN");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    /** A reference as the picker returns it, reduced to what the framework resolves: the connection and the piece. */
    public static ObjectNode request(JsonNode reference) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode one = mapper.createObjectNode();
        JsonNode connection = reference.path("connection");
        JsonNode part = reference.path("part");
        if (!FRAMEWORK.equals(connection.path("extension").asText()) || !"connection".equals(connection.path("type").asText())
                || connection.path("id").asText().isBlank() || part.path("id").asText().isBlank()) {
            throw new IllegalArgumentException("The reference names a connection of " + FRAMEWORK + " and a piece of it.");
        }
        one.putObject("connection").put("extension", FRAMEWORK).put("type", "connection")
                .put("id", connection.path("id").asText());
        ObjectNode piece = one.putObject("part").put("id", part.path("id").asText());
        ArrayNode parents = piece.putArray("parents");
        part.path("parents").forEach(p -> parents.add(p.asText()));
        ObjectNode body = mapper.createObjectNode();
        body.putArray("references").add(one);
        return body;
    }

    /**
     * The reference as it is now, for the person of the request: {@code status} ({@code available},
     * {@code deleted}, {@code forbidden}, {@code connector-absent}, {@code part-missing}, ...), the
     * {@code connection} with its settings and the name of its credential, and the {@code part} with its
     * facts.
     *
     * @throws IllegalStateException when the framework cannot be asked
     */
    public JsonNode resolve(JsonNode reference, HttpHeaders headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(kernel() + PATH))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        for (String name : FORWARDED) {
            String value = headers.getHeaderString(name);
            if (value != null && !value.isBlank()) {
                request.header(name, value);
            }
        }
        try {
            HttpResponse<String> answer = http.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request(reference)))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (answer.statusCode() == 404) {
                throw new IllegalStateException("The connections are not active in this organization.");
            }
            if (answer.statusCode() != 200) {
                throw new IllegalStateException("The connections answered " + answer.statusCode() + " to the resolution of"
                        + " the reference.");
            }
            return json.readTree(answer.body()).path("references").path(0);
        } catch (IOException e) {
            throw new IllegalStateException("The connections cannot be asked now: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
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
