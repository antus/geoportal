// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.editing;

import static dev.geoportal.ext.catalog.geodata.geoserver.GeoServerGovernance.EXTENSION;

import dev.geoportal.ext.catalog.geodata.CatalogExtension;
import dev.geoportal.ext.catalog.geodata.access.AccessRules;
import dev.geoportal.ext.catalog.geodata.access.PublishedViews;
import dev.geoportal.ext.catalog.geodata.access.RuleSet;
import dev.geoportal.ext.catalog.geodata.geoserver.GeoServerGovernance;
import dev.geoportal.ext.catalog.geodata.geoserver.PublishedLayer;
import dev.mosaikit.kernel.api.audit.AuditLog;
import dev.mosaikit.kernel.api.context.CurrentOrganization;
import dev.mosaikit.kernel.api.permission.AccessDeniedException;
import dev.mosaikit.kernel.api.permission.Permissions;
import dev.mosaikit.kernel.api.sharing.Sharing;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Who writes the data of a published layer (GP-036, ADR-0023): a surveyor, that is a person who holds the
 * permission {@value #PERMISSION} and to whom the layer is assigned. The same answer is given by the API
 * of the catalog, here, and by GeoServer, through the GeoFence rules that {@link AccessRules} generates
 * from the assignments: a WFS transaction is allowed to the assigned surveyors of a layer and refused to
 * everybody else.
 *
 * <p>Mosaikit tells an extension the permissions of the person of the request only, so the catalog learns
 * whether a surveyor holds the permission at each of their requests ({@link #confirm}); GeoServer lets them
 * write once it has seen it, and stops when a request finds it gone. Assigning or removing a layer writes
 * the rules again at once: the next request of the surveyor already finds the new answer, without signing
 * out.
 */
@ApplicationScoped
public class LayerEditing {

    /** The permission to edit the data of the layers assigned to the person. */
    public static final String PERMISSION = "edit-layer-data";

    private final LayerAssignments assignments;
    private final GeoServerGovernance geoserver;
    private final Sharing sharing;
    private final Permissions permissions;
    private final SecurityIdentity identity;
    private final PublishedViews views;
    private final AccessRules access;
    private final AuditLog audit;
    private final CurrentOrganization organization;

    public LayerEditing(LayerAssignments assignments, GeoServerGovernance geoserver, Sharing sharing,
            Permissions permissions, SecurityIdentity identity, PublishedViews views, AccessRules access, AuditLog audit,
            CurrentOrganization organization) {
        this.assignments = assignments;
        this.geoserver = geoserver;
        this.sharing = sharing;
        this.permissions = permissions;
        this.identity = identity;
        this.views = views;
        this.access = access;
        this.audit = audit;
        this.organization = organization;
    }

    /** Why a person may not write the data of a layer: a code and a reason. */
    public record Refusal(String code, String reason) {}

    /** The user name of the person of the request, as GeoServer receives it. */
    public String me() {
        return identity.isAnonymous() ? null : identity.getPrincipal().getName();
    }

    /** Whether the person of the request holds the permission {@value #PERMISSION}. */
    public boolean holds() {
        return permissions.has(EXTENSION, PERMISSION);
    }

    /**
     * Records whether the person of the request, if they have assignments, holds the permission, and
     * writes the GeoFence rules again when that changed.
     */
    public void confirm() {
        String me = me();
        if (me == null) {
            return;
        }
        List<LayerAssignment> mine = assignments.ofUsername(me);
        if (mine.isEmpty()) {
            return;
        }
        boolean holding = holds();
        Instant now = Instant.now();
        boolean changed = false;
        for (LayerAssignment a : mine) {
            changed |= a.checked(holding, now);
            assignments.update(a);
        }
        if (changed) {
            access.rewrite(holding ? "surveyor-confirmed" : "surveyor-permission-lost");
        }
    }

    /** The layers assigned to the person of the request, published ones only. */
    public List<LayerAssignment> mine() {
        String me = me();
        if (me == null) {
            return List.of();
        }
        return assignments.ofUsername(me).stream().filter(a -> geoserver.layer(a.layer()).isPresent()).toList();
    }

    /** The assignments of a layer. */
    public List<LayerAssignment> of(String layer) {
        return assignments.ofLayer(layer);
    }

    /**
     * Assigns a published layer to a member of the organization, and writes the rules again; nothing
     * changes when it already is.
     *
     * @throws NoSuchElementException when the account is not an active member of the organization
     */
    public LayerAssignment assign(PublishedLayer layer, UUID account) {
        if (layer.definition().store() != null) {
            // GP-986, ADR-0030: the table of a connection is read where it is, never written through GeoServer.
            throw new IllegalStateException("The layer " + layer.name() + " reads a table of a connection, which is"
                    + " not edited through Geoportal.");
        }
        Optional<LayerAssignment> existing = assignments.ofLayer(layer.name()).stream()
                .filter(a -> a.account().equals(account)).findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        PublishedViews.Member member = views.members().stream().filter(m -> m.account().equals(account)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Not a member of the organization: " + account));
        LayerAssignment a = new LayerAssignment(organization.require(), layer.name(), account, member.username(), me(),
                Instant.now());
        if (a.of(me())) {
            // Assigning oneself: the request already says whether the permission is held.
            a.checked(holds(), Instant.now());
        }
        assignments.insert(a);
        record("layer.assigned", layer.name(), member.username());
        access.rewrite("assignment");
        return a;
    }

    /** Removes the assignment of a layer to a person and writes the rules again at once; false when there was none. */
    public boolean unassign(String layer, UUID account) {
        Optional<LayerAssignment> existing = assignments.ofLayer(layer).stream()
                .filter(a -> a.account().equals(account)).findFirst();
        if (existing.isEmpty()) {
            return false;
        }
        assignments.delete(existing.get());
        record("layer.unassigned", layer, existing.get().username());
        access.rewrite("assignment");
        return true;
    }

    /**
     * Why the person of the request may not write the data of a layer, or empty when they may: they hold
     * the permission and the layer is assigned to them.
     */
    public Optional<Refusal> refusal(String layer) {
        if (!holds()) {
            return Optional.of(new Refusal("permission",
                    "Editing the data of layers needs the permission " + EXTENSION + ":" + PERMISSION + "."));
        }
        String me = me();
        boolean assigned = me != null && assignments.ofLayer(layer).stream().anyMatch(a -> a.of(me));
        if (!assigned) {
            return Optional.of(new Refusal("not-assigned", "The layer " + layer + " is not assigned to you."));
        }
        return dataRules(layer);
    }

    /** The code of a surveyor limited by data rules who writes within them through the WFS service of the layer. */
    public static final String WITHIN_DATA_RULES = "data-rules-service";

    /** The code of a surveyor whose data rules hide columns or leave nothing visible: they do not write. */
    public static final String DATA_RULES = "data-rules";

    /**
     * Why data rules on the person stop a transaction of the catalog on a layer (GP-308, ADR-0025), or empty
     * when none limits them. A surveyor whose rules have an area or a row filter only writes within them through
     * the WFS service of the layer, where GeoServer applies them as a write filter; the catalog forwards a
     * transaction as the administrator of GeoServer, who has no limits, so it refuses it rather than let it
     * write outside them. Rules that hide columns, or whose areas have nothing in common, stop writes
     * everywhere.
     */
    public Optional<Refusal> dataRules(String layer) {
        Optional<RuleSet.Limits> limits = access.limitsOf(layer, me());
        if (limits.isEmpty() || limits.get().none()) {
            return Optional.empty();
        }
        if (RuleSet.writableWithin(limits.get())) {
            return Optional.of(new Refusal(WITHIN_DATA_RULES, "Data rules limit you on the layer " + layer
                    + ": the catalog cannot keep a transaction within them. Send it to the WFS service of the layer"
                    + " (as a desktop client does), where GeoServer writes only within your area and row filter."));
        }
        return Optional.of(new Refusal(DATA_RULES, "Data rules hide columns of the layer " + layer
                + " from you, or leave nothing of it visible: GeoServer cannot keep writes within them, so you"
                + " cannot edit its data."));
    }

    /**
     * Refuses a write to the data of a dataset that is the source of published layers (its file replaced or
     * loaded again) unless the person holds the permission and either manages the dataset or has every
     * layer that serves it assigned. A dataset that no layer serves is not the source data of a layer.
     *
     * @throws AccessDeniedException when the write is refused; the kernel answers 403
     */
    public void requireSourceWrite(UUID dataset) {
        List<PublishedLayer> serving = geoserver.published().stream().filter(l -> dataset.equals(l.datasetId())).toList();
        if (serving.isEmpty()) {
            return;
        }
        if (!holds()) {
            throw new AccessDeniedException("The dataset is the source of the layer " + serving.getFirst().name()
                    + ": changing its data needs the permission " + EXTENSION + ":" + PERMISSION);
        }
        if (sharing.allows(CatalogExtension.dataset(dataset), "manage")) {
            return;
        }
        for (PublishedLayer l : serving) {
            Optional<Refusal> r = refusal(l.name());
            if (r.isPresent()) {
                throw new AccessDeniedException(r.get().reason());
            }
        }
    }

    /** Audits a refused write. */
    public void refused(String layer, Refusal refusal) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", refusal.code());
        audit.record(EXTENSION, new AuditLog.Entry("layer.edit-refused", "layer/" + layer, AuditLog.Outcome.DENIED,
                details));
    }

    /** Audits a transaction GeoServer applied. */
    public void edited(String layer, Map<String, Object> details) {
        audit.record(EXTENSION, new AuditLog.Entry("layer.edited", "layer/" + layer, AuditLog.Outcome.SUCCEEDED, details));
    }

    private void record(String action, String layer, String surveyor) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("surveyor", surveyor);
        audit.record(EXTENSION, new AuditLog.Entry(action, "layer/" + layer, AuditLog.Outcome.SUCCEEDED, details));
    }
}
