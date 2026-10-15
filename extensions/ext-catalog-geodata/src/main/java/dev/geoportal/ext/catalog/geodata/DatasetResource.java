// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import io.quarkus.security.Authenticated;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The catalog of the organization of the request, under /api/v1/ext/catalog-geodata. An organization that
 * has no dataset yet gets the demo catalog the first time it looks (a mock, until the catalog
 * reads the data of GeoServer and of the archive).
 */
@Path("/api/v1/ext/catalog-geodata/datasets")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class DatasetResource {

    private final Datasets datasets;
    private final CurrentOrganization organization;
    private final ObjectMapper json;

    public DatasetResource(Datasets datasets, CurrentOrganization organization, ObjectMapper json) {
        this.datasets = datasets;
        this.organization = organization;
        this.json = json;
    }

    /** The datasets, all of them or those whose title, theme or description contain the text. */
    @GET
    @Transactional
    public List<DatasetView> list(@QueryParam("q") String text, @QueryParam("theme") String theme) {
        if (datasets.count() == 0) {
            seed();
        }
        String wanted = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        return datasets.all().stream()
                .map(Dataset::view)
                .filter(d -> theme == null || theme.isBlank() || d.theme().equals(theme))
                .filter(d -> wanted.isEmpty() || matches(d, wanted))
                .toList();
    }

    @GET
    @Path("{id}")
    @Transactional
    public DatasetView one(@PathParam("id") UUID id) {
        return datasets.byId(id).map(Dataset::view).orElseThrow(NotFoundException::new);
    }

    private static boolean matches(DatasetView dataset, String text) {
        return (dataset.title() + " " + dataset.theme() + " " + dataset.description())
                .toLowerCase(Locale.ROOT)
                .contains(text);
    }

    private void seed() {
        UUID owner = organization.require();
        Instant now = Instant.now();
        for (DatasetView values : demo()) {
            datasets.insert(new Dataset(owner, values, now));
            now = now.plusMillis(1);
        }
    }

    private List<DatasetView> demo() {
        try (InputStream in = DatasetResource.class.getResourceAsStream("/demo-datasets.json")) {
            return json.readValue(in, new TypeReference<List<DatasetView>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("The demo catalog cannot be read", e);
        }
    }
}
