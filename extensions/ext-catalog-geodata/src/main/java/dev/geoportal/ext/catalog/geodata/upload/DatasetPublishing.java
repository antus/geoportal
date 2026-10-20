// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.upload;

import static dev.geoportal.ext.catalog.geodata.upload.UploadedDatasets.EXTENSION;

import dev.geoportal.ext.catalog.geodata.geoserver.Alignment;
import dev.geoportal.ext.catalog.geodata.geoserver.Failure;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerException;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerGovernance;
import dev.geoportal.ext.catalog.geodata.geoserver.LayerDefinition;
import dev.geoportal.ext.catalog.geodata.geoserver.PublishedLayer;
import dev.geoportal.ext.catalog.geodata.geoserver.PublishedStyle;
import dev.geoportal.ext.catalog.geodata.geoserver.StyleDefinition;
import dev.geoportal.ext.catalog.geodata.style.StyleDocument;
import dev.mosaikit.kernel.api.audit.AuditLog;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The one path from a loaded dataset to a GeoServer service (GP-114), and the lifecycle of the
 * layers it publishes (GP-118): publish, republish, repoint, unpublish. Each is a change of the
 * definitions of the catalog followed at once by an alignment of GeoServer (ADR-0017), so that the
 * person learns in the same answer whether GeoServer took it, and why not (GP-115).
 *
 * <p>A layer serves the table of one dataset and has a style of its own, named as the layer and
 * translated from the style of Geoportal the person chose. Unpublishing withdraws both, and GeoServer
 * deletes the tile layer of GeoWebCache with the layer; republishing keeps the name, recomputes the
 * bounding boxes and forgets the cached tiles. A publication GeoServer refuses leaves nothing behind:
 * its definitions are withdrawn and aligned away.
 *
 * <p>The callers check permissions and sharing levels first; this class does what was allowed.
 */
@ApplicationScoped
public class DatasetPublishing {

    /** GP-983: the state of a dataset made from a file of the catalog, recorded on the file. */
    @jakarta.inject.Inject
    CatalogStates states;

    /** GP-986: the tables of connections that datasets map. */
    @jakarta.inject.Inject
    dev.geoportal.ext.catalog.geodata.connection.ConnectionLayerRepository connectionLayers;

    private static final Logger LOG = Logger.getLogger(DatasetPublishing.class);

    private final GeoServerGovernance geoserver;
    private final UploadedDataRepository uploads;
    private final AuditLog audit;
    @jakarta.inject.Inject
    RasterPublishing rasters;

    public DatasetPublishing(GeoServerGovernance geoserver, UploadedDataRepository uploads, AuditLog audit) {
        this.geoserver = geoserver;
        this.uploads = uploads;
        this.audit = audit;
    }

    /** The outcome of an action: the layer when it succeeded, or why it failed. */
    public record Outcome(PublishedLayer layer, Failure failure) {

        static Outcome done(PublishedLayer layer) {
            return new Outcome(layer, null);
        }

        static Outcome failed(Failure failure) {
            return new Outcome(null, failure);
        }
    }

    /** The SLD GeoServer would receive for a style of Geoportal on the data of a dataset. */
    public String translate(UploadedData data, String layer, StyleDocument style) {
        return style.sld(layer, data.geometryType());
    }

    /** The layers that serve a dataset. */
    public List<PublishedLayer> layersOf(UUID dataset) {
        return geoserver.published().stream().filter(l -> dataset.equals(l.datasetId())).toList();
    }

    // Publish ------------------------------------------------------------------------------------

    /**
     * Publishes a loaded vector dataset as a layer of the GeoServer workspace of the organization,
     * named {@code layer}, with the style of Geoportal translated into SLD.
     */
    public Outcome publish(UploadedData data, String layer, StyleDocument style) {
        Optional<Failure> refused = publishable(data);
        if (refused.isPresent()) {
            return Outcome.failed(refused.get());
        }
        if (layer == null || !LayerDefinition.NAME.matcher(layer).matches()) {
            return Outcome.failed(Failure.correct(400, "invalid", "The layer name is made of lowercase letters, digits"
                    + " and underscores, starting with a letter, at most 63 characters."));
        }
        Optional<PublishedLayer> existing = geoserver.layer(layer);
        if (existing.isPresent() && !data.id().equals(existing.get().datasetId())) {
            return Outcome.failed(Failure.correct(409, "name-in-use",
                    "The catalog already publishes a layer named " + layer + " for other data."));
        }
        if (existing.isPresent()) {
            return republish(data, existing.get(), style);
        }
        Optional<PublishedStyle> held = geoserver.style(layer);
        if (held.isPresent() && geoserver.published().stream().anyMatch(l -> layer.equals(l.definition().style()))) {
            return Outcome.failed(Failure.correct(409, "name-in-use",
                    "The catalog already keeps a style named " + layer + " for another layer."));
        }
        try {
            if (geoserver.unknownLayers().contains(layer)
                    || (held.isEmpty() && geoserver.client().styles(geoserver.space().workspace()).contains(layer))) {
                return Outcome.failed(Failure.correct(409, "name-taken", "GeoServer already has a layer or style named "
                        + layer + " that the catalog does not know: adopt or remove it in the reconciliation, or choose"
                        + " another name."));
            }
        } catch (RuntimeException e) {
            return Outcome.failed(failure(e, "publishing", data, layer));
        }

        Instant now = Instant.now();
        geoserver.keepTranslated(new StyleDefinition(layer, style.sld(layer, data.geometryType())), style.json());
        geoserver.publish(definition(data, layer, layer));
        PublishedLayer published = geoserver.layer(layer).orElseThrow();
        published.serves(data.id(), now);
        geoserver.update(published);

        Optional<Failure> failed = align(layer, "publishing", data);
        if (failed.isPresent()) {
            // Nothing half-made stays: the definitions are withdrawn and aligned away.
            geoserver.withdrawLayer(layer);
            geoserver.withdrawStyle(layer);
            try {
                geoserver.align();
            } catch (RuntimeException e) {
                LOG.warnf("[%s] The withdrawal of %s after a refused publication waits for the next alignment: %s",
                        failed.get().reference(), layer, e.getMessage());
            }
            return Outcome.failed(failed.get());
        }
        refresh(layer);
        geoserver.check(published);
        data.published(now);
        uploads.update(data);
        states.mirror(data);
        record("layer.published", layer, data, AuditLog.Outcome.SUCCEEDED, Map.of("style", style.json()));
        return Outcome.done(published);
    }

    /** Why a dataset cannot be published as it is, if it cannot. */
    private Optional<Failure> publishable(UploadedData data) {
        if (data.cogFileId() != null) {
            return Optional.of(Failure.correct(409, "not-vector", "Rasters are published when GeoServer serves"
                    + " Cloud Optimized GeoTIFF coverages; this dataset is an image."));
        }
        if (data.tableName() == null
                || !(UploadedData.LOADED.equals(data.state()) || UploadedData.PUBLISHED.equals(data.state()))) {
            return Optional.of(Failure.correct(409, "not-loaded",
                    "The dataset is not loaded into the geodata database: load it first."));
        }
        return Optional.empty();
    }

    // Republish ----------------------------------------------------------------------------------

    /**
     * Publishes a layer again with the current data of its dataset and, when given, a new style: same
     * name, so the maps that use it keep working; bounding boxes recomputed and cached tiles deleted.
     */
    public Outcome republish(UploadedData data, PublishedLayer layer, StyleDocument style) {
        Optional<Failure> refused = publishable(data);
        if (refused.isPresent()) {
            return Outcome.failed(refused.get());
        }
        String name = layer.name();
        String styleName = styleOf(layer);
        StyleDocument chosen = style != null ? style : source(styleName).orElse(StyleDocument.defaults());
        geoserver.keepTranslated(new StyleDefinition(styleName, chosen.sld(styleName, data.geometryType())), chosen.json());
        geoserver.publish(definition(data, name, styleName));
        PublishedLayer current = geoserver.layer(name).orElseThrow();
        current.serves(data.id(), Instant.now());
        geoserver.update(current);
        Optional<Failure> failed = align(name, "republishing", data);
        if (failed.isPresent()) {
            return Outcome.failed(failed.get());
        }
        refresh(name);
        geoserver.check(current);
        data.published(Instant.now());
        uploads.update(data);
        states.mirror(data);
        record("layer.republished", name, data, AuditLog.Outcome.SUCCEEDED, Map.of("style", chosen.json()));
        return Outcome.done(current);
    }

    // Repoint ------------------------------------------------------------------------------------

    /**
     * Points a layer at the table of another dataset: the name and the catalog entry stay, the style
     * of Geoportal is translated again for the geometry of the new data. Refused by GeoServer, the
     * layer goes back to its dataset.
     */
    public Outcome repoint(PublishedLayer layer, UploadedData from, UploadedData to) {
        Optional<Failure> refused = publishable(to);
        if (refused.isPresent()) {
            return Outcome.failed(refused.get());
        }
        String name = layer.name();
        String styleName = styleOf(layer);
        StyleDocument chosen = source(styleName).orElse(StyleDocument.defaults());
        geoserver.keepTranslated(new StyleDefinition(styleName, chosen.sld(styleName, to.geometryType())), chosen.json());
        geoserver.publish(definition(to, name, styleName));
        PublishedLayer current = geoserver.layer(name).orElseThrow();
        current.serves(to.id(), Instant.now());
        geoserver.update(current);
        Optional<Failure> failed = align(name, "repointing", to);
        if (failed.isPresent()) {
            if (from != null && from.tableName() != null) {
                geoserver.keepTranslated(new StyleDefinition(styleName, chosen.sld(styleName, from.geometryType())),
                        chosen.json());
                geoserver.publish(definition(from, name, styleName));
                PublishedLayer back = geoserver.layer(name).orElseThrow();
                back.serves(from.id(), Instant.now());
                geoserver.update(back);
                try {
                    geoserver.align();
                } catch (RuntimeException e) {
                    LOG.warnf("[%s] %s goes back to %s at the next alignment: %s", failed.get().reference(), name,
                            from.technicalName(), e.getMessage());
                }
            }
            return Outcome.failed(failed.get());
        }
        refresh(name);
        geoserver.check(current);
        Instant now = Instant.now();
        to.published(now);
        uploads.update(to);
        states.mirror(to);
        if (from != null && layersOf(from.id()).isEmpty()) {
            from.unpublished(now);
            uploads.update(from);
            states.mirror(from);
        }
        record("layer.repointed", name, to, AuditLog.Outcome.SUCCEEDED,
                Map.of("from", from == null ? "" : from.technicalName(), "to", to.technicalName()));
        return Outcome.done(current);
    }

    // Unpublish ----------------------------------------------------------------------------------

    /**
     * Withdraws a layer and its own style from GeoServer at once; GeoServer deletes the tile layer
     * of GeoWebCache with it. The table stays: the dataset is loaded, ready to be published again.
     */
    public Outcome unpublish(PublishedLayer layer, UploadedData data) {
        if (layer.raster()) {
            return rasters.unpublish(layer, data);
        }
        String name = layer.name();
        String styleName = layer.definition().style();
        geoserver.withdrawLayer(name);
        boolean ownStyle = styleName != null && styleName.equals(name)
                && geoserver.published().stream().noneMatch(l -> styleName.equals(l.definition().style()));
        if (ownStyle) {
            geoserver.withdrawStyle(styleName);
        }
        Optional<Failure> failed = align(name, "unpublishing", data);
        if (failed.isPresent()) {
            // The withdrawal stays defined: the next alignment completes it.
            return Outcome.failed(failed.get());
        }
        if (data != null && layersOf(data.id()).isEmpty()) {
            data.unpublished(Instant.now());
            uploads.update(data);
            states.mirror(data);
        }
        record("layer.unpublished", name, data, AuditLog.Outcome.SUCCEEDED, Map.of("style", ownStyle ? styleName : ""));
        return Outcome.done(null);
    }

    // When the data change ------------------------------------------------------------------------

    /**
     * The data of a dataset were loaded again, renamed, or are gone (GP-937 calls it): its layers
     * follow. A table loaded again is republished under the same names; a dataset that is no longer a
     * table withdraws its layers. GeoServer that does not answer does not fail the loading: the
     * definitions are kept and the next alignment applies them.
     */
    void dataChanged(UploadedData data) {
        List<PublishedLayer> layers = layersOf(data.id());
        if (layers.isEmpty()) {
            return;
        }
        if (layers.stream().anyMatch(PublishedLayer::raster)) {
            // GP-309: a raster layer publishes the new COG, or goes when the dataset is no longer an image.
            for (PublishedLayer layer : layers) {
                Outcome o = layer.raster() && RasterPublishing.raster(data) ? rasters.publish(data, layer.name())
                        : unpublish(layer, data);
                if (o.failure() != null) {
                    LOG.warnf("[%s] The layer %s of %s follows its data at the next alignment", o.failure().reference(),
                            layer.name(), data.technicalName());
                }
            }
            return;
        }
        if (data.tableName() == null) {
            for (PublishedLayer layer : layers) {
                Outcome o = unpublish(layer, data);
                if (o.failure() != null) {
                    LOG.warnf("[%s] The layer %s of %s is withdrawn at the next alignment", o.failure().reference(),
                            layer.name(), data.technicalName());
                }
            }
            data.unpublished(Instant.now());
            return;
        }
        for (PublishedLayer layer : layers) {
            try {
                Outcome o = republish(data, layer, null);
                if (o.failure() != null) {
                    LOG.warnf("[%s] The layer %s of %s is republished at the next alignment", o.failure().reference(),
                            layer.name(), data.technicalName());
                }
            } catch (RuntimeException e) {
                LOG.warnf("The layer %s of %s is republished at the next alignment: %s", layer.name(),
                        data.technicalName(), e.getMessage());
            }
        }
        data.published(Instant.now());
    }

    /** The dataset is deleted: its layers and their styles are withdrawn from GeoServer. */
    void datasetGone(UploadedData data) {
        for (PublishedLayer layer : layersOf(data.id())) {
            try {
                unpublish(layer, data);
            } catch (RuntimeException e) {
                LOG.warnf("The layer %s of a deleted dataset is withdrawn at the next alignment: %s", layer.name(),
                        e.getMessage());
            }
        }
    }

    // Plumbing -----------------------------------------------------------------------------------

    private LayerDefinition definition(UploadedData data, String name, String style) {
        String description = data.description().length() <= 1000
                ? data.description()
                : data.description().substring(0, 997) + "...";
        if (data.fromConnection()) {
            // GP-986: the table of the connection, in the store of that connection, read where it is.
            var source = connectionLayers.byDataset(data.id()).orElseThrow().source();
            return new LayerDefinition(name, source.table(), data.title(), description, data.crs(), style, source.store());
        }
        return new LayerDefinition(name, data.technicalName(), data.title(), description, data.crs(), style);
    }

    /** The style of a layer, which a layer published by the catalog always has: its own name otherwise. */
    private static String styleOf(PublishedLayer layer) {
        String style = layer.definition().style();
        return style == null ? layer.name() : style;
    }

    private Optional<StyleDocument> source(String style) {
        return geoserver.style(style).map(PublishedStyle::source).flatMap(DatasetPublishing::parse);
    }

    /**
     * The style document kept with a style, migrated when it was written before the grammar (GP-009);
     * empty for an SLD written as such, or a source that is not a style any more.
     */
    public static Optional<StyleDocument> parse(String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(StyleDocument.parse(json));
        } catch (IllegalArgumentException e) {
            LOG.warnf("A kept style is not a style of Geoportal, its SLD stays as it is: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Aligns GeoServer and tells whether the layer (and its style, and what they stand on: schema,
     * workspace, store) came through; a failure is audited with its reference.
     */
    private Optional<Failure> align(String layer, String what, UploadedData data) {
        Alignment alignment;
        try {
            alignment = geoserver.align();
        } catch (RuntimeException e) {
            return Optional.of(failure(e, what, data, layer));
        }
        for (Alignment.Change c : alignment.changes()) {
            boolean ours = c.name().equals(layer) && (c.kind().equals("layer") || c.kind().equals("style"));
            boolean ground = !c.kind().equals("layer") && !c.kind().equals("style");
            if (c.error() != null && (ours || ground)) {
                Failure f = Failure.refused(c, what + " " + layer);
                auditFailure(what, layer, data, f);
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    private Failure failure(RuntimeException e, String what, UploadedData data, String layer) {
        Failure f = Failure.of(e, what + " " + layer);
        auditFailure(what, layer, data, f);
        return f;
    }

    private void auditFailure(String what, String layer, UploadedData data, Failure f) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("action", what);
        details.put("code", f.code());
        details.put("cause", f.cause());
        details.put("reference", f.reference());
        record("layer.publish-failed", layer, data, AuditLog.Outcome.FAILED, details);
    }

    /** Bounding boxes and cached tiles; a failure here is logged, the layer is published all the same. */
    private void refresh(String layer) {
        try {
            geoserver.refresh(layer);
        } catch (GeoServerException | GeoServerGovernance.MissingCredentialException e) {
            Failure f = Failure.of(e, "refreshing " + layer);
            LOG.warnf("[%s] The bounding boxes or cached tiles of %s are refreshed at the next republication",
                    f.reference(), layer);
        }
    }

    private void record(String action, String layer, UploadedData data, AuditLog.Outcome outcome, Map<String, ?> more) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("layer", layer);
        if (data != null) {
            details.put("dataset", data.id().toString());
            details.put("title", data.title());
            details.put("table", data.technicalName());
        }
        details.putAll(more);
        audit.record(EXTENSION, new AuditLog.Entry(action, "layer/" + layer, outcome, details));
    }
}
