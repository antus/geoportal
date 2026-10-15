// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.geoserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The REST API of GeoServer, as the catalog uses it (ADR-0014): workspaces with their namespace,
 * PostGIS stores, feature types and their layers, styles of a workspace. Only the catalog talks to
 * GeoServer, with the credentials of its administrator, which this class receives from the caller
 * (read from the credentials of Mosaikit) and never logs nor puts in an error message.
 *
 * <p>Every method is a single REST call. Reads answer empty for a 404; any other answer outside 2xx
 * is a {@link GeoServerException} with the status and the beginning of the body.
 */
public final class GeoServerClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String SLD = "application/vnd.ogc.sld+xml";
    private static final Pattern OGC_EXCEPTION = Pattern.compile(
            "(?s)<(?:\\w+:)?(?:ServiceException|ExceptionText)\\b[^>]*>(.*?)</(?:\\w+:)?(?:ServiceException|ExceptionText)>");
    /** The message of an error page of Tomcat: {@code <p><b>Message</b> ...</p>}. */
    private static final Pattern TOMCAT_MESSAGE = Pattern.compile("(?is)<b>\\s*Message\\s*</b>(.*?)</p>");
    private static final Pattern HTML_BODY = Pattern.compile("(?is)<body[^>]*>(.*?)</body>");
    private static final Pattern CDATA = Pattern.compile("(?s)<!\\[CDATA\\[(.*?)]]>");
    private static final Pattern SCRIPT = Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern JAVA_CLASS =
            Pattern.compile("\\b(?:[a-z][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error)\\b:?\\s*");

    private final String base;
    private final String authorization;
    private final HttpClient http;

    /**
     * @param base the address of GeoServer, such as {@code http://geoserver:8080/geoserver}
     * @param user the administrator of GeoServer (never {@code admin}, ADR-0016)
     * @param password its password; the array is not kept
     */
    public GeoServerClient(URI base, String user, char[] password) {
        String b = Objects.requireNonNull(base, "base").toString();
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        byte[] pair = (Objects.requireNonNull(user, "user") + ":" + new String(password)).getBytes(StandardCharsets.UTF_8);
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(pair);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    // Workspaces ------------------------------------------------------------------------------

    /** The names of every workspace. */
    public List<String> workspaces() {
        return names(get("/rest/workspaces.json"), "workspaces", "workspace");
    }

    public boolean hasWorkspace(String workspace) {
        return get("/rest/workspaces/" + path(workspace) + ".json").isPresent();
    }

    /** The URI of the namespace of a workspace, if the workspace exists. */
    public Optional<String> namespaceUri(String workspace) {
        return get("/rest/namespaces/" + path(workspace) + ".json").map(n -> n.path("namespace").path("uri").asText());
    }

    /** Creates a workspace together with its namespace (a namespace makes its workspace). */
    public void createWorkspace(String workspace, String namespaceUri) {
        send("POST", "/rest/namespaces", namespace(workspace, namespaceUri));
    }

    public void updateNamespace(String workspace, String namespaceUri) {
        send("PUT", "/rest/namespaces/" + path(workspace), namespace(workspace, namespaceUri));
    }

    /** Deletes a workspace with everything in it. */
    public void deleteWorkspace(String workspace) {
        send("DELETE", "/rest/workspaces/" + path(workspace) + "?recurse=true", null);
    }

    private static ObjectNode namespace(String prefix, String uri) {
        ObjectNode body = NODES.objectNode();
        body.putObject("namespace").put("prefix", prefix).put("uri", uri);
        return body;
    }

    // Stores ------------------------------------------------------------------------------------

    public List<String> dataStores(String workspace) {
        return names(get("/rest/workspaces/" + path(workspace) + "/datastores.json"), "dataStores", "dataStore");
    }

    /**
     * The connection parameters of a store, without the password (GeoServer answers it encrypted,
     * so it cannot be compared).
     */
    public Optional<Map<String, String>> dataStoreParameters(String workspace, String store) {
        return get("/rest/workspaces/" + path(workspace) + "/datastores/" + path(store) + ".json").map(s -> {
            Map<String, String> parameters = new TreeMap<>();
            JsonNode entries = s.path("dataStore").path("connectionParameters").path("entry");
            for (JsonNode e : entries.isArray() ? entries : NODES.arrayNode().add(entries)) {
                String key = e.path("@key").asText();
                if (!key.isEmpty() && !"passwd".equals(key)) {
                    parameters.put(key, e.path("$").asText());
                }
            }
            return parameters;
        });
    }

    /** Creates or replaces a store with its connection parameters, password included. */
    public void putDataStore(String workspace, String store, Map<String, String> parameters, boolean exists) {
        ObjectNode body = NODES.objectNode();
        ObjectNode ds = body.putObject("dataStore");
        ds.put("name", store).put("enabled", true);
        ArrayNode entries = ds.putObject("connectionParameters").putArray("entry");
        parameters.forEach((k, v) -> entries.addObject().put("@key", k).put("$", v));
        String collection = "/rest/workspaces/" + path(workspace) + "/datastores";
        if (exists) {
            send("PUT", collection + "/" + path(store), body);
        } else {
            send("POST", collection, body);
        }
    }

    public List<String> coverageStores(String workspace) {
        return names(get("/rest/workspaces/" + path(workspace) + "/coveragestores.json"), "coverageStores", "coverageStore");
    }

    // Coverages (GP-309) ----------------------------------------------------------------------

    /**
     * Publishes a GeoTIFF, such as the COG of a dataset, as the coverage store {@code store} of a workspace
     * with one coverage and its layer, both named as the store; GeoServer keeps the file in its data
     * directory. Sending it again replaces the file.
     */
    public void uploadCoverage(String workspace, String store, byte[] tiff) {
        String path = "/rest/workspaces/" + path(workspace) + "/coveragestores/" + path(store)
                + "/file.geotiff?configure=first&coverageName=" + path(store);
        check(call(request(path).PUT(HttpRequest.BodyPublishers.ofByteArray(tiff)).header("Content-Type", "image/tiff")
                .build()), "PUT", path);
    }

    /** Gives the coverage named as its store a title and a description. */
    public void describeCoverage(String workspace, String store, String title, String description) {
        ObjectNode body = NODES.objectNode();
        ObjectNode coverage = body.putObject("coverage");
        coverage.put("title", title);
        coverage.put("abstract", description == null ? "" : description);
        coverage.put("enabled", true);
        send("PUT", "/rest/workspaces/" + path(workspace) + "/coveragestores/" + path(store) + "/coverages/" + path(store),
                body);
    }

    /** Deletes a coverage store with its coverage, its layer and its file. */
    public void deleteCoverageStore(String workspace, String store) {
        send("DELETE", "/rest/workspaces/" + path(workspace) + "/coveragestores/" + path(store) + "?recurse=true&purge=all",
                null);
    }

    /** The native bounding box of the coverage named as its store: minx, miny, maxx, maxy. */
    public Optional<double[]> coverageBounds(String workspace, String store) {
        return get("/rest/workspaces/" + path(workspace) + "/coveragestores/" + path(store) + "/coverages/" + path(store)
                + ".json").map(n -> {
                    JsonNode b = n.path("coverage").path("nativeBoundingBox");
                    return new double[] {b.path("minx").asDouble(), b.path("miny").asDouble(), b.path("maxx").asDouble(),
                            b.path("maxy").asDouble()};
                });
    }

    public void deleteDataStore(String workspace, String store) {
        send("DELETE", "/rest/workspaces/" + path(workspace) + "/datastores/" + path(store) + "?recurse=true", null);
    }

    // Feature types and layers ----------------------------------------------------------------

    /** The feature types of a workspace, whatever their store. */
    public List<String> featureTypes(String workspace) {
        return names(get("/rest/workspaces/" + path(workspace) + "/featuretypes.json"), "featureTypes", "featureType");
    }

    /** The store of a feature type of a workspace, if it exists. */
    public Optional<FeatureType> featureType(String workspace, String name) {
        return get("/rest/workspaces/" + path(workspace) + "/featuretypes/" + path(name) + ".json").map(n -> {
            JsonNode ft = n.path("featureType");
            String storeName = ft.path("store").path("name").asText();
            int colon = storeName.indexOf(':');
            return new FeatureType(
                    ft.path("name").asText(),
                    ft.path("nativeName").asText(),
                    colon < 0 ? storeName : storeName.substring(colon + 1),
                    ft.path("title").asText(""),
                    ft.path("abstract").asText(""),
                    ft.path("srs").asText(""),
                    ft.path("projectionPolicy").asText(""),
                    ft.path("enabled").asBoolean(true),
                    ft.path("advertised").asBoolean(true));
        });
    }

    /** A feature type as GeoServer has it. */
    public record FeatureType(
            String name,
            String nativeName,
            String store,
            String title,
            String description,
            String srs,
            String projectionPolicy,
            boolean enabled,
            boolean advertised) {}

    /**
     * Publishes a table of a store as a feature type (and its layer), in the declared reference
     * system ({@code FORCE_DECLARED}: GeoServer never guesses it, ADR-0015).
     */
    public void createFeatureType(String workspace, String store, FeatureType wanted) {
        send("POST", "/rest/workspaces/" + path(workspace) + "/datastores/" + path(store) + "/featuretypes",
                featureTypeBody(wanted));
    }

    /** Brings a feature type to the given definition, recomputing its bounding boxes. */
    public void updateFeatureType(String workspace, String store, FeatureType wanted) {
        send("PUT", "/rest/workspaces/" + path(workspace) + "/datastores/" + path(store) + "/featuretypes/"
                + path(wanted.name()) + "?recalculate=nativebbox,latlonbbox", featureTypeBody(wanted));
    }

    /** Deletes a feature type and its layer. */
    public void deleteFeatureType(String workspace, String store, String name) {
        send("DELETE", "/rest/workspaces/" + path(workspace) + "/datastores/" + path(store) + "/featuretypes/"
                + path(name) + "?recurse=true", null);
    }

    private static ObjectNode featureTypeBody(FeatureType wanted) {
        ObjectNode body = NODES.objectNode();
        body.putObject("featureType")
                .put("name", wanted.name())
                .put("nativeName", wanted.nativeName())
                .put("title", wanted.title())
                .put("abstract", wanted.description())
                .put("srs", wanted.srs())
                .put("projectionPolicy", "FORCE_DECLARED")
                .put("enabled", wanted.enabled())
                .put("advertised", wanted.advertised());
        return body;
    }

    /** The default style of a layer of a workspace, as {@code workspace:name} or {@code name}. */
    public Optional<String> layerStyle(String workspace, String layer) {
        return get("/rest/workspaces/" + path(workspace) + "/layers/" + path(layer) + ".json").map(n -> {
            JsonNode style = n.path("layer").path("defaultStyle");
            String name = style.path("name").asText("");
            String ws = style.path("workspace").asText("");
            return ws.isEmpty() || name.contains(":") ? name : ws + ":" + name;
        });
    }

    /** Sets the default style of a layer to a style of the same workspace. */
    public void setLayerStyle(String workspace, String layer, String style) {
        ObjectNode body = NODES.objectNode();
        body.putObject("layer").putObject("defaultStyle").put("name", style).put("workspace", workspace);
        // Not /rest/layers/<workspace>:<layer>, which GeoWebCache also maps (500 "Ambiguous handler").
        send("PUT", "/rest/workspaces/" + path(workspace) + "/layers/" + path(layer), body);
    }

    // Styles ------------------------------------------------------------------------------------

    public List<String> styles(String workspace) {
        return names(get("/rest/workspaces/" + path(workspace) + "/styles.json"), "styles", "style");
    }

    /** The SLD of a style of a workspace, as GeoServer keeps it. */
    public Optional<String> styleBody(String workspace, String style) {
        return getText("/rest/workspaces/" + path(workspace) + "/styles/" + path(style) + ".sld");
    }

    /**
     * Creates a style. GeoServer parses the SLD (so an invalid one is refused) and keeps it re-encoded,
     * with names of its own; a second call stores it as written ({@code raw=true}), so that reading it
     * back gives the document Geoportal holds and an alignment can compare the two.
     */
    public void createStyle(String workspace, String style, String sld) {
        sendText("POST", "/rest/workspaces/" + path(workspace) + "/styles?name=" + query(style), sld);
        sendText("PUT", "/rest/workspaces/" + path(workspace) + "/styles/" + path(style) + "?raw=true", sld);
    }

    /** Replaces the SLD of a style: parsed first, then stored as written (see {@link #createStyle}). */
    public void updateStyle(String workspace, String style, String sld) {
        sendText("PUT", "/rest/workspaces/" + path(workspace) + "/styles/" + path(style), sld);
        sendText("PUT", "/rest/workspaces/" + path(workspace) + "/styles/" + path(style) + "?raw=true", sld);
    }

    public void deleteStyle(String workspace, String style) {
        send("DELETE", "/rest/workspaces/" + path(workspace) + "/styles/" + path(style) + "?purge=true", null);
    }

    /**
     * Recomputes the bounding boxes of a feature type from its table, after its data changed
     * (GP-118): an alignment rewrites a feature type only when its definition differs.
     */
    public void recalculate(String workspace, String store, String name) {
        ObjectNode body = NODES.objectNode();
        body.putObject("featureType").put("name", name).put("enabled", true);
        send("PUT", "/rest/workspaces/" + path(workspace) + "/datastores/" + path(store) + "/featuretypes/"
                + path(name) + "?recalculate=nativebbox,latlonbbox", body);
    }

    // Cached tiles (GeoWebCache) ------------------------------------------------------------------

    /**
     * Whether GeoWebCache has a tile layer for a layer of a workspace: the WMTS of the workspace
     * lists it. (The REST API of GeoWebCache is not mapped in the GeoServer of the distribution.)
     */
    public boolean hasTileLayer(String workspace, String layer) {
        HttpResponse<String> r = ogcText(path(workspace) + "/gwc/service/wmts", "service=WMTS&request=GetCapabilities");
        if (r.statusCode() == 404) {
            return false;
        }
        check(r, "GET", "/" + workspace + "/gwc/service/wmts");
        String body = r.body() == null ? "" : r.body();
        return body.contains("<ows:Identifier>" + workspace + ":" + layer + "</ows:Identifier>")
                || body.contains("<ows:Identifier>" + layer + "</ows:Identifier>");
    }

    /**
     * Deletes the cached tiles of a layer, after its data or its style changed (GP-118). The mass
     * truncation of GeoWebCache is used where its REST API answers; where it is not mapped (the
     * GeoServer of the distribution answers 404), the default style of the layer is moved to
     * {@code generic} and back, a change after which the integrated GeoWebCache deletes the tiles of
     * the layer drawn with its style.
     *
     * @param style the default style of the layer, {@code null} for none of its own
     */
    public void truncateTiles(String workspace, String layer, String style) {
        HttpResponse<String> r = call(request("/gwc/rest/masstruncate")
                .POST(HttpRequest.BodyPublishers.ofString("<truncateLayer><layerName>" + workspace + ":" + layer
                        + "</layerName></truncateLayer>", StandardCharsets.UTF_8))
                .header("Content-Type", "text/xml")
                .build());
        if (r.statusCode() / 100 == 2) {
            return;
        }
        if (r.statusCode() != 404) {
            check(r, "POST", "/gwc/rest/masstruncate");
        }
        if (style == null) {
            return;
        }
        ObjectNode generic = NODES.objectNode();
        generic.putObject("layer").putObject("defaultStyle").put("name", "generic");
        send("PUT", "/rest/workspaces/" + path(workspace) + "/layers/" + path(layer), generic);
        setLayerStyle(workspace, layer, style);
    }

    /** The names of the attributes of a feature type, as GeoServer reads them from its table. */
    public List<String> attributes(String workspace, String featureType) {
        return new ArrayList<>(bindings(workspace, featureType).keySet());
    }

    /** The attributes of a feature type with the Java class of each, such as {@code org.locationtech.jts.geom.Point}. */
    public Map<String, String> bindings(String workspace, String featureType) {
        Map<String, String> bindings = new java.util.LinkedHashMap<>();
        get("/rest/workspaces/" + path(workspace) + "/featuretypes/" + path(featureType) + ".json").ifPresent(n -> {
            JsonNode items = n.path("featureType").path("attributes").path("attribute");
            for (JsonNode item : items.isArray() ? items : NODES.arrayNode().add(items)) {
                if (item.hasNonNull("name")) {
                    bindings.put(item.get("name").asText(), item.path("binding").asText(""));
                }
            }
        });
        return bindings;
    }

    /** The bounding box of a feature type in its declared reference system: minx, miny, maxx, maxy. */
    public Optional<double[]> nativeBounds(String workspace, String featureType) {
        return get("/rest/workspaces/" + path(workspace) + "/featuretypes/" + path(featureType) + ".json").map(n -> {
            JsonNode b = n.path("featureType").path("nativeBoundingBox");
            return new double[] {b.path("minx").asDouble(), b.path("miny").asDouble(), b.path("maxx").asDouble(),
                    b.path("maxy").asDouble()};
        });
    }

    // GeoFence (GP-335) ---------------------------------------------------------------------------

    /** Every rule of GeoFence, of every workspace, as GeoFence answers them in JSON. */
    public List<JsonNode> geofenceRules() {
        List<JsonNode> rules = new ArrayList<>();
        get("/rest/geofence/rules.json").ifPresent(n -> n.path("rules").forEach(rules::add));
        return rules;
    }

    /**
     * Inserts a rule. GeoFence 2.28.5 keeps a different part of a rule per format (the proofs of GP-327):
     * a rule written as XML loses its area, one written as JSON loses its column list, and both answer
     * 201; the caller chooses the format that keeps the rule and reads it back.
     *
     * @param body the rule, {@code {"Rule":{...}}} or {@code <Rule>...</Rule>}
     * @param xml whether the body is XML
     */
    public void insertRule(String body, boolean xml) {
        String path = "/rest/geofence/rules";
        check(call(request(path)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", xml ? "application/xml" : "application/json")
                .build()), "POST", path);
    }

    public void deleteRule(long id) {
        send("DELETE", "/rest/geofence/rules/id/" + id, null);
    }

    /** GeoServer caches the decisions of GeoFence: emptied after every change of the rules. */
    public void invalidateRuleCache() {
        send("PUT", "/rest/ruleCache/invalidate", null);
    }

    // OGC services ------------------------------------------------------------------------------

    /**
     * Calls an OGC service of GeoServer, such as {@code <workspace>/<layer>/wms}, and returns the
     * answer as a stream; the caller closes it. Only for the gate of the catalog, which checked the
     * person, the service and the request first.
     */
    public HttpResponse<java.io.InputStream> ogc(String method, String servicePath, String rawQuery,
            Map<String, String> headers) {
        HttpRequest.Builder b = request("/" + servicePath + (rawQuery == null || rawQuery.isEmpty() ? "" : "?" + rawQuery))
                .method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(b::header);
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new GeoServerException(0, "GeoServer does not answer at " + base + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeoServerException(0, "Interrupted while calling GeoServer");
        }
    }

    /** The answer of a GET of an OGC service as text, with its status, for the health check. */
    public HttpResponse<String> ogcText(String servicePath, String rawQuery) {
        return call(request("/" + servicePath + "?" + rawQuery).GET().build());
    }

    /**
     * Sends a WFS transaction to a service of GeoServer, such as {@code <workspace>/<layer>/wfs}, and
     * returns its answer. Only for the editing of the catalog (GP-036), which checked the person and the
     * transaction first.
     */
    public HttpResponse<String> transaction(String servicePath, byte[] xml) {
        return call(request("/" + servicePath)
                .POST(HttpRequest.BodyPublishers.ofByteArray(xml))
                .header("Content-Type", "text/xml; charset=UTF-8")
                .build());
    }

    /** The address of GeoServer, as the catalog reaches it. */
    public String base() {
        return base;
    }

    /**
     * The Authorization header this client sends, only so that the gate can remove it from an answer
     * that would echo it ({@code AnswerRewriter}); never sent anywhere else.
     */
    public String authorization() {
        return authorization;
    }

    // Plumbing ----------------------------------------------------------------------------------

    /** Whether GeoServer answers with these credentials. */
    public String version() {
        return get("/rest/about/version.json")
                .map(v -> {
                    for (JsonNode r : v.path("about").path("resource")) {
                        if ("GeoServer".equals(r.path("@name").asText())) {
                            return r.path("Version").asText();
                        }
                    }
                    return "";
                })
                .orElseThrow(() -> new GeoServerException(404, "GeoServer answered 404 for its version"));
    }

    private Optional<JsonNode> get(String path) {
        HttpResponse<String> r = call(request(path).GET().header("Accept", "application/json").build());
        if (r.statusCode() == 404) {
            return Optional.empty();
        }
        check(r, "GET", path);
        try {
            return Optional.of(r.body().isBlank() ? NODES.objectNode() : JSON.readTree(r.body()));
        } catch (IOException e) {
            throw new GeoServerException(r.statusCode(), "GeoServer answered GET " + path + " with no JSON");
        }
    }

    private Optional<String> getText(String path) {
        HttpResponse<String> r = call(request(path).GET().build());
        if (r.statusCode() == 404) {
            return Optional.empty();
        }
        check(r, "GET", path);
        return Optional.of(r.body());
    }

    private void send(String method, String path, JsonNode body) {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8);
        HttpRequest.Builder b = request(path).method(method, publisher);
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        check(call(b.build()), method, path);
    }

    private void sendText(String method, String path, String sld) {
        check(call(request(path)
                .method(method, HttpRequest.BodyPublishers.ofString(sld, StandardCharsets.UTF_8))
                .header("Content-Type", SLD)
                .build()), method, path);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", authorization);
    }

    private HttpResponse<String> call(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GeoServerException(0, "GeoServer does not answer at " + base + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GeoServerException(0, "Interrupted while calling GeoServer");
        }
    }

    private static void check(HttpResponse<String> r, String method, String path) {
        if (r.statusCode() / 100 != 2) {
            String body = r.body() == null ? "" : r.body().strip();
            throw new GeoServerException(r.statusCode(), "GeoServer answered " + method + " " + path + " with "
                    + r.statusCode() + (body.isEmpty() ? "" : ": " + body.substring(0, Math.min(200, body.length()))),
                    reason(body), false);
        }
    }

    /**
     * What GeoServer said in an error answer, as text a person can read (GP-115): the text of an XML
     * or HTML answer (the ServiceException of OGC, the body of an error page) without markup, without
     * the lines of a Java stack trace and the names of exception classes, on one line, at most 300
     * characters; {@code null} when nothing readable is left.
     */
    public static String reason(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String text = body;
        if (text.stripLeading().startsWith("<")) {
            Matcher exception = OGC_EXCEPTION.matcher(text);
            Matcher tomcat = TOMCAT_MESSAGE.matcher(text);
            Matcher page = HTML_BODY.matcher(text);
            if (exception.find()) {
                text = exception.group(1);
            } else if (tomcat.find()) {
                text = tomcat.group(1);
            } else if (page.find()) {
                text = page.group(1);
            }
            text = CDATA.matcher(text).replaceAll("$1");
            text = SCRIPT.matcher(text).replaceAll(" ");
            text = TAG.matcher(text).replaceAll(" ")
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                    .replace("&amp;", "&");
        }
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\\R")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("at ") || l.startsWith("...")) {
                continue;
            }
            l = l.replaceFirst("^Caused by:\\s*", "");
            // A Java exception class before its message: "java.lang.IllegalArgumentException: x" is "x".
            l = JAVA_CLASS.matcher(l).replaceAll("").strip();
            if (!l.isEmpty() && kept.indexOf(l) < 0) {
                kept.append(kept.isEmpty() ? "" : " ").append(l);
            }
        }
        String readable = kept.toString().replaceAll("\\s+", " ").strip();
        if (readable.isEmpty()) {
            return null;
        }
        return readable.length() <= 300 ? readable : readable.substring(0, 297) + "...";
    }

    /**
     * The names in a list answer of GeoServer, such as {@code {"workspaces":{"workspace":[...]}}};
     * an empty list is answered as {@code {"workspaces":""}}, and a list of one may be an object.
     */
    private static List<String> names(Optional<JsonNode> answer, String outer, String inner) {
        List<String> names = new ArrayList<>();
        answer.ifPresent(a -> {
            JsonNode items = a.path(outer).path(inner);
            for (JsonNode item : items.isArray() ? items : NODES.arrayNode().add(items)) {
                if (item.hasNonNull("name")) {
                    names.add(item.get("name").asText());
                }
            }
        });
        return names;
    }

    private static String path(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String query(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
