// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The client of the loader sidecar (ADR-0019, distribution/server/loader): GDAL loads a file given by a
 * signed link into a table of the geodata database, or converts it into a COG that the catalog fetches.
 * The credentials of the target go with each job and are not kept by the sidecar.
 */
public final class LoaderClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI base;
    private final HttpClient http;

    public LoaderClient(URI base) {
        this.base = URI.create(base.toString().replaceAll("/+$", "") + "/");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Where the loader writes a vector: the database as the loader reaches it, and the table. */
    public record Target(String host, int port, String database, String user, char[] password, String schema, String table) {

        @Override
        public String toString() {
            return "Target[" + user + "@" + host + ":" + port + "/" + database + " " + schema + "." + table + "]";
        }
    }

    /**
     * A file to load.
     *
     * @param format the id of the format in the catalog ({@code geopackage}, {@code geotiff}, ...)
     * @param crs the EPSG code the person declared, or {@code null}
     * @param formatCrs the system the format defines when the file states none (CSV of latitude and
     *     longitude), or {@code null}
     * @param layer the layer of a file with several, or {@code null}
     * @param target where a vector goes; {@code null} for a raster
     */
    public record Job(String source, String fileName, String format, Integer crs, Integer formatCrs, String layer, Target target) {}

    /** A layer of a file with several, for the person to choose. */
    public record Layer(String name, long features, String geometryType) {}

    /** What the loader says of a job. */
    public record Status(String state, int progress, String code, String reason, List<Layer> layers, String layer,
            String crs, String crsSource, long features, double[] nativeExtent, double[] wgs84, JsonNode raw) {

        public boolean finished() {
            return "done".equals(state) || "failed".equals(state);
        }
    }

    /** Submits a job; the answer is its identifier. */
    public String submit(Job job) {
        ObjectNode body = JSON.createObjectNode();
        body.put("source", job.source());
        body.put("fileName", job.fileName());
        body.put("format", job.format());
        if (job.crs() != null) {
            body.put("crs", "EPSG:" + job.crs());
        }
        if (job.formatCrs() != null) {
            body.put("formatCrs", "EPSG:" + job.formatCrs());
        }
        if (job.layer() != null) {
            body.put("layer", job.layer());
        }
        if (job.target() != null) {
            Target t = job.target();
            ObjectNode target = body.putObject("target");
            target.put("host", t.host()).put("port", t.port()).put("database", t.database()).put("user", t.user())
                    .put("password", new String(t.password())).put("schema", t.schema()).put("table", t.table());
        }
        HttpResponse<String> answer = send(HttpRequest.newBuilder(base.resolve("jobs"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())), HttpResponse.BodyHandlers.ofString());
        body.remove("target");
        if (answer.statusCode() != 202) {
            throw new IllegalStateException("The loader refused the job: " + answer.statusCode() + " " + answer.body());
        }
        return tree(answer.body()).path("id").asText();
    }

    /** The status of a job, or empty when the loader does not know it (restarted, forgotten). */
    public Optional<Status> status(String id) {
        HttpResponse<String> answer = send(HttpRequest.newBuilder(base.resolve("jobs/" + id)).timeout(Duration.ofSeconds(15)).GET(),
                HttpResponse.BodyHandlers.ofString());
        if (answer.statusCode() == 404) {
            return Optional.empty();
        }
        if (answer.statusCode() != 200) {
            throw new IllegalStateException("The loader answered " + answer.statusCode());
        }
        JsonNode s = tree(answer.body());
        List<Layer> layers = new ArrayList<>();
        for (JsonNode l : s.path("layers")) {
            layers.add(new Layer(l.path("name").asText(), l.path("features").asLong(), l.path("geometryType").asText(null)));
        }
        return Optional.of(new Status(s.path("state").asText(), s.path("progress").asInt(), s.path("code").asText(null),
                s.path("reason").asText(null), layers, s.path("layer").asText(null), s.path("crs").asText(null),
                s.path("source").asText(null), s.path("features").asLong(), box(s.path("extent").path("native")),
                box(s.path("extent").path("wgs84")), s));
    }

    /** The COG of a finished raster job, streamed; the caller closes it. */
    public InputStream result(String id) {
        HttpResponse<InputStream> answer = send(HttpRequest.newBuilder(base.resolve("jobs/" + id + "/result")).GET(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (answer.statusCode() != 200) {
            try (InputStream in = answer.body()) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException e) {
                // nothing to read
            }
            throw new IllegalStateException("The loader has no result for the job: " + answer.statusCode());
        }
        return answer.body();
    }

    /** Forgets a job and its files in the loader. */
    public void forget(String id) {
        try {
            send(HttpRequest.newBuilder(base.resolve("jobs/" + id)).timeout(Duration.ofSeconds(15)).DELETE(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (RuntimeException e) {
            // the loader forgets old jobs by itself
        }
    }

    private <T> HttpResponse<T> send(HttpRequest.Builder request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request.build(), handler);
        } catch (IOException e) {
            throw new UncheckedIOException("The loader does not answer: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the loader", e);
        }
    }

    private static JsonNode tree(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException("The loader answered something that is not JSON", e);
        }
    }

    private static double[] box(JsonNode node) {
        if (node == null || !node.isArray() || node.size() != 4) {
            return null;
        }
        return new double[] {node.get(0).asDouble(), node.get(1).asDouble(), node.get(2).asDouble(), node.get(3).asDouble()};
    }
}
