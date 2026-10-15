// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog;

import dev.mosaikit.kernel.api.context.CurrentOrganization;
import dev.mosaikit.kernel.api.resource.ResourceRef;
import dev.mosaikit.kernel.api.sharing.Sharing;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Optional;
import java.util.UUID;

/**
 * Who may go through the proxy of a service (GP-939): a person with the level {@code use} on it,
 * while its proxy is on. While it is off, only those who manage it, to check whether it is needed
 * (GP-297). Read in a short transaction of its own, so that no transaction stays open while the
 * answer of the service streams.
 */
@ApplicationScoped
public class ServiceAccess {

    /** What the proxy needs of a service, read once per request. */
    public record Admitted(
            UUID id, UUID organization, String kind, String url, String crs, String credential, String authScheme) {}

    /** Why a request is refused: the service is not in the organization, the person may not use it, or the proxy is off. */
    public enum Refusal {
        NOT_FOUND,
        FORBIDDEN,
        PROXY_OFF
    }

    /** The service and the request admitted, or why not. */
    public record Decision(Admitted admitted, Refusal refusal) {}

    private final ExternalServices services;
    private final Sharing sharing;
    private final CurrentOrganization organization;

    public ServiceAccess(ExternalServices services, Sharing sharing, CurrentOrganization organization) {
        this.services = services;
        this.sharing = sharing;
        this.organization = organization;
    }

    @Transactional
    public Decision admit(UUID id) {
        Optional<ExternalService> found = services.byId(id);
        if (found.isEmpty()) {
            return new Decision(null, Refusal.NOT_FOUND);
        }
        ExternalService service = found.get();
        ResourceRef ref = CatalogExtension.service(id);
        if (!sharing.allows(ref, "use")) {
            return new Decision(null, Refusal.FORBIDDEN);
        }
        if (!service.proxy() && !sharing.allows(ref, "manage")) {
            return new Decision(null, Refusal.PROXY_OFF);
        }
        return new Decision(
                new Admitted(id, organization.require(), service.kind(), service.url(), service.crs(),
                        service.credential(), service.authScheme()),
                null);
    }
}
