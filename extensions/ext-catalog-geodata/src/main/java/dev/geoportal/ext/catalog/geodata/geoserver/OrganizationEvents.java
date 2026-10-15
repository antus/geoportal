// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.geoserver;

import dev.mosaikit.kernel.api.audit.AuditLog;
import dev.mosaikit.kernel.api.events.PlatformEvent;
import dev.mosaikit.kernel.api.events.PlatformEventSubscriber;
import dev.mosaikit.kernel.api.events.PlatformEvents;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The GeoServer space of an organization follows its life (GP-334, MK-085): when the organization is
 * created, or turns the catalog on again, the catalog aligns GeoServer for it, which creates its
 * schema, workspace and store and publishes what the catalog holds; when it turns the catalog off, its
 * publications are withdrawn (disabled, never deleted: {@link Reconciler#withdraw}).
 *
 * <p>The handler runs a few seconds after the change, in the organization of the event and without a
 * person, so the credentials of GeoServer are those of the installation unless the organization stored
 * its own ({@link GeoServerGovernance}). Every step is idempotent, as an event may come twice:
 *
 * <ul>
 *   <li>without the credentials nothing can be done, and retrying would not help: the outcome is
 *       written to the audit log of the organization and the first alignment does the work later;
 *   <li>when GeoServer or the geodata database do not answer, or refuse the workspace or the store,
 *       the handler fails and Mosaikit delivers the event again later;
 *   <li>a layer or style that GeoServer refuses is reported by the alignment, as in any other.
 * </ul>
 */
@ApplicationScoped
public class OrganizationEvents implements PlatformEventSubscriber {

    private static final Logger LOG = Logger.getLogger(OrganizationEvents.class);

    private final GeoServerGovernance governance;
    private final AuditLog audit;

    public OrganizationEvents(GeoServerGovernance governance, AuditLog audit) {
        this.governance = governance;
        this.audit = audit;
    }

    @Override
    public String extension() {
        return GeoServerGovernance.EXTENSION;
    }

    @Override
    public Set<String> types() {
        return Set.of(PlatformEvents.ORGANIZATION_CREATED, PlatformEvents.EXTENSION_ENABLED,
                PlatformEvents.EXTENSION_DISABLED);
    }

    @Override
    public void handle(PlatformEvent event) {
        switch (event.type()) {
            case PlatformEvents.ORGANIZATION_CREATED, PlatformEvents.EXTENSION_ENABLED -> {
                if (aboutAnotherExtension(event)) {
                    return;
                }
                prepare(event);
            }
            case PlatformEvents.EXTENSION_DISABLED -> {
                if (aboutAnotherExtension(event)) {
                    return;
                }
                withdraw(event);
            }
            default -> {
                // Not subscribed.
            }
        }
    }

    private void prepare(PlatformEvent event) {
        Alignment result;
        try {
            result = governance.align();
        } catch (GeoServerGovernance.MissingCredentialException e) {
            pending(event, "geoserver.aligned", e);
            return;
        }
        // The workspace and the store come first; when they failed, nothing else was tried: again later.
        result.changes().stream()
                .filter(c -> c.error() != null)
                .filter(c -> "schema".equals(c.kind()) || "workspace".equals(c.kind()) || "store".equals(c.kind()))
                .findFirst()
                .ifPresent(c -> {
                    throw new IllegalStateException("GeoServer did not " + c.action() + " the " + c.kind() + " "
                            + c.name() + " of the organization: " + c.error());
                });
        LOG.infof("GeoServer aligned for the organization %s after %s: %d changes", event.organization(),
                event.type(), result.changes().size());
    }

    private void withdraw(PlatformEvent event) {
        try {
            Alignment result = governance.withdraw();
            LOG.infof("Publications of the organization %s withdrawn from GeoServer: %d layers", event.organization(),
                    result.changes().size());
        } catch (GeoServerGovernance.MissingCredentialException e) {
            pending(event, "geoserver.withdrawn", e);
        }
    }

    /** Nothing can be done without the credentials of GeoServer: said in the audit log, not retried. */
    private void pending(PlatformEvent event, String action, RuntimeException e) {
        audit.record(GeoServerGovernance.EXTENSION, new AuditLog.Entry(action,
                "workspace/" + governance.space().workspace(), AuditLog.Outcome.FAILED,
                Map.of("event", event.type(), "error", e.getMessage())));
        LOG.warnf("GeoServer not changed for the organization %s after %s: %s", event.organization(), event.type(),
                e.getMessage());
    }

    /** {@code extension.enabled} and {@code .disabled} name the extension they are about. */
    private static boolean aboutAnotherExtension(PlatformEvent event) {
        String extension = event.text("extension");
        return extension != null && !GeoServerGovernance.EXTENSION.equals(extension);
    }
}
