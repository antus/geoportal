// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.upload;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mosaikit.kernel.api.quota.Quota;
import dev.mosaikit.kernel.api.sharing.Sharing;
import dev.mosaikit.kernel.api.sharing.Subject;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * The sample datasets (GP-936): more than twenty files in every format the catalog loads, uploaded
 * once into each organization the first time one of its administrators opens the uploaded data, and
 * shared with the whole organization to view and download. The setting
 * {@code geoportal.catalog.samples=false} turns them off.
 *
 * <p>An administrator rather than anyone: the samples are owned by the person whose request uploads
 * them (an extension cannot act for the organization without a person yet), and an administrator
 * holds every level of the organization anyway.
 */
@ApplicationScoped
public class Samples {

    /** The setting that turns the samples off. */
    public static final String SETTING = "geoportal.catalog.samples";

    private static final Logger LOG = Logger.getLogger(Samples.class);
    private static final String ADMINISTRATOR = "organization-admin";

    private final UploadedDatasets datasets;
    private final Sharing sharing;
    private final SecurityIdentity identity;
    private final EntityManager entities;
    private final ObjectMapper json;

    public Samples(UploadedDatasets datasets, Sharing sharing, SecurityIdentity identity,
            EntityManager entities, ObjectMapper json) {
        this.datasets = datasets;
        this.sharing = sharing;
        this.identity = identity;
        this.entities = entities;
        this.json = json;
    }

    /**
     * One sample: the file in the resources of the extension, with its title and source, and the
     * reference system of those that state none (CSV with x and y or WKT), declared as a person would.
     */
    public record Sample(String file, String title, String description, String source, String crs) {}

    static boolean enabled() {
        return ConfigProvider.getConfig().getOptionalValue(SETTING, Boolean.class).orElse(true);
    }

    /**
     * Uploads the samples when they are on, the person is an administrator of the organization, and
     * the organization never had them.
     *
     * <p>In a transaction of its own (issue #50): a failure rolls back the samples and their claim only,
     * so that the request that opened the catalog still answers and the next visit tries them again.
     * Where nothing publishes nor loads (the portable: no GeoServer, no geodata database, no loader),
     * the samples are stored and not loaded, as any upload there.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void loadIfDue() {
        if (!enabled() || !identity.hasRole(ADMINISTRATOR)) {
            return;
        }
        int claimed = entities.createNativeQuery(
                        "insert into ext_catalog_geodata.sample_load (loaded_at, loaded) values (now(), 0)"
                                + " on conflict (organization_id) do nothing")
                .executeUpdate();
        if (claimed == 0) {
            return;
        }
        List<Sample> samples = list();
        long total = samples.stream().mapToLong(this::size).sum();
        Quota quota = datasets.space();
        if (quota.limit().isPresent() && quota.used() + total > quota.limit().getAsLong()) {
            LOG.infof("The sample datasets (%d bytes) do not fit the storage of the organization: not loaded.", total);
            return;
        }
        int loaded = 0;
        for (Sample sample : samples) {
            try (InputStream content = open(sample.file())) {
                String description = sample.description() + " Fonte: " + sample.source() + ".";
                UploadedData data = datasets.upload(sample.file(), sample.title(), description, content, -1, true, sample.crs());
                sharing.grant(UploadedDatasets.ref(data.id()), Subject.organization(), "read");
                loaded++;
            } catch (IOException e) {
                throw new UncheckedIOException("The sample " + sample.file() + " cannot be read", e);
            }
        }
        entities.createNativeQuery("update ext_catalog_geodata.sample_load set loaded = ?1")
                .setParameter(1, loaded)
                .executeUpdate();
        LOG.infof("Loaded %d sample datasets into the organization.", loaded);
    }

    List<Sample> list() {
        try (InputStream in = open("samples.json")) {
            return json.readValue(in, new TypeReference<List<Sample>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException("The list of the sample datasets cannot be read", e);
        }
    }

    private long size(Sample sample) {
        try (InputStream in = open(sample.file())) {
            return in.transferTo(java.io.OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("The sample " + sample.file() + " cannot be read", e);
        }
    }

    private static InputStream open(String name) throws IOException {
        InputStream in = Samples.class.getResourceAsStream("/samples/" + name);
        if (in == null) {
            throw new IOException("Missing /samples/" + name);
        }
        return in;
    }
}
