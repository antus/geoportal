// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.upload;

import dev.geoportal.ext.catalog.geodata.geoserver.Failure;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerClient;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerException;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerGovernance;
import dev.geoportal.ext.catalog.geodata.geoserver.OgcGate;
import dev.geoportal.ext.catalog.geodata.geoserver.PublishedLayer;
import dev.geoportal.ext.catalog.geodata.proxy.AnswerRewriter;
import dev.geoportal.ext.catalog.geodata.proxy.ProxyHeaders;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * The gate of the catalog to the OGC services of one published layer (GP-114, ADR-0020): it reaches
 * GeoServer as its administrator, so it lets through only what {@link OgcGate} allows, the read
 * requests of that one layer, and the answers name the gate, never GeoServer. With the data gateway
 * (GP-336) people reach GeoServer through the gateway with their own name, and the gate remains for
 * links, which have no person GeoServer could know.
 */
final class LayerGate {

    private LayerGate() {}

    /**
     * Forwards a request that the caller allowed (the level {@code view} of the person or of the link on
     * the dataset of the layer) to the service of the layer.
     *
     * @param path the service path below the layer, such as {@code wms}
     * @param rawQuery the query of the request, encoded
     * @param proxy the absolute address of the gate for this layer, which the answers name
     */
    static Response forward(String method, GeoServerGovernance geoserver, PublishedLayer layer, String path,
            String rawQuery, HttpHeaders headers, String proxy) {
        String ws = geoserver.space().workspace();
        String name = layer.name();
        Optional<String> refusal = OgcGate.refusal(path, rawQuery, ws, name);
        if (refusal.isPresent()) {
            return Failure.correct(400, "invalid", refusal.get()).response();
        }
        GeoServerClient client;
        HttpResponse<InputStream> answer;
        try {
            client = geoserver.client();
            answer = client.ogc(method, ws + "/" + name + "/" + path, rawQuery,
                    ProxyHeaders.forService(headers::getHeaderString));
        } catch (GeoServerException | GeoServerGovernance.MissingCredentialException e) {
            return Failure.of(e, "serving " + name).response();
        }
        Response.ResponseBuilder b = Response.status(answer.statusCode() == 401 ? 502 : answer.statusCode());
        ProxyHeaders.forBrowser(answer.headers().map()).forEach(b::header);
        ProxyHeaders.HARDENING.forEach(b::header);
        InputStream body = answer.body();
        if (method.equals("HEAD")) {
            try {
                body.close();
            } catch (IOException e) {
                // nothing to read
            }
            return b.build();
        }
        StreamingOutput stream = output -> {
            try (body) {
                body.transferTo(output);
            }
        };
        return AnswerRewriter.apply(b.entity(stream).build(), URI.create(client.base() + "/" + ws + "/" + name), proxy,
                client.authorization());
    }
}
