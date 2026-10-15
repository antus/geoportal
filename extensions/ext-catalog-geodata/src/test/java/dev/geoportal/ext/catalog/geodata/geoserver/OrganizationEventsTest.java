// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.geoserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.geoportal.ext.catalog.geodata.geoserver.Alignment.Change;
import dev.mosaikit.kernel.api.audit.AuditLog;
import dev.mosaikit.kernel.api.events.PlatformEvent;
import dev.mosaikit.kernel.api.events.PlatformEvents;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** GP-334: what the catalog does with the events of an organization, GeoServer aside. */
@Tag("GP-334")
class OrganizationEventsTest {

    private static final UUID ORG = UUID.randomUUID();

    /** The governance, recording what it was asked and answering what the test says. */
    private static final class Governance extends GeoServerGovernance {
        final List<String> calls = new ArrayList<>();
        Alignment answer = new Alignment("ws", true, List.of(), List.of());
        RuntimeException failure;

        Governance() {
            super(null, null, null, null, null);
        }

        @Override
        public OrganizationSpace space() {
            return new OrganizationSpace(ORG);
        }

        @Override
        public Alignment align() {
            calls.add("align");
            if (failure != null) {
                throw failure;
            }
            return answer;
        }

        @Override
        public Alignment withdraw() {
            calls.add("withdraw");
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private final Governance governance = new Governance();
    private final List<AuditLog.Entry> audited = new ArrayList<>();
    private final OrganizationEvents events = new OrganizationEvents(governance, audit());

    private AuditLog audit() {
        return (AuditLog) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {AuditLog.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("record") && args != null && args.length == 2
                            && args[1] instanceof AuditLog.Entry entry) {
                        audited.add(entry);
                        return null;
                    }
                    if (method.getName().equals("toString")) {
                        return "audit";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static PlatformEvent event(String type, Map<String, Object> payload) {
        return new PlatformEvent(1, ORG, type, PlatformEvents.KERNEL, payload, Instant.now(), 1);
    }

    @Test
    void subscribesToTheLifeOfTheOrganizationAsTheCatalog() {
        assertEquals(GeoServerGovernance.EXTENSION, events.extension());
        assertEquals(java.util.Set.of("organization.created", "extension.enabled", "extension.disabled"), events.types());
    }

    @Test
    void aNewOrganizationIsAlignedAtOnceWhichCreatesItsWorkspace() {
        events.handle(event(PlatformEvents.ORGANIZATION_CREATED, Map.of("slug", "acme", "name", "Acme")));
        assertEquals(List.of("align"), governance.calls);
    }

    @Test
    void turningTheCatalogOffWithdrawsThePublicationsAndOnAlignsAgain() {
        events.handle(event(PlatformEvents.EXTENSION_DISABLED, Map.of("extension", "dev.geoportal.ext.catalog.geodata")));
        events.handle(event(PlatformEvents.EXTENSION_ENABLED, Map.of("extension", "dev.geoportal.ext.catalog.geodata")));
        assertEquals(List.of("withdraw", "align"), governance.calls);
    }

    @Test
    void anotherExtensionTurnedOffOrOnChangesNothing() {
        events.handle(event(PlatformEvents.EXTENSION_DISABLED, Map.of("extension", "dev.geoportal.maps")));
        events.handle(event(PlatformEvents.EXTENSION_ENABLED, Map.of("extension", "dev.geoportal.maps")));
        assertEquals(List.of(), governance.calls);
    }

    @Test
    void withoutTheCredentialsTheOutcomeIsAuditedAndNotRetried() {
        governance.failure = new GeoServerGovernance.MissingCredentialException("Store the credential geoserver");
        events.handle(event(PlatformEvents.ORGANIZATION_CREATED, Map.of("slug", "acme")));
        assertEquals(1, audited.size());
        assertEquals("geoserver.aligned", audited.get(0).action());
        assertEquals(AuditLog.Outcome.FAILED, audited.get(0).outcome());
        assertTrue(audited.get(0).details().toString().contains("organization.created"));
    }

    @Test
    void geoServerDownFailsTheHandlerSoThatTheEventComesAgain() {
        governance.failure = new GeoServerException(0, "GeoServer does not answer");
        assertThrows(GeoServerException.class,
                () -> events.handle(event(PlatformEvents.ORGANIZATION_CREATED, Map.of("slug", "acme"))));
        assertThrows(GeoServerException.class, () -> events.handle(
                event(PlatformEvents.EXTENSION_DISABLED, Map.of("extension", "dev.geoportal.ext.catalog.geodata"))));
    }

    @Test
    void aRefusedWorkspaceIsRetriedARefusedLayerIsOnlyReported() {
        governance.answer = new Alignment("ws", true,
                List.of(new Change("workspace", "ws", "create", "missing", "HTTP 500")), List.of());
        assertThrows(IllegalStateException.class,
                () -> events.handle(event(PlatformEvents.ORGANIZATION_CREATED, Map.of("slug", "acme"))));

        governance.answer = new Alignment("ws", true,
                List.of(new Change("layer", "roads", "create", "missing", "no table")), List.of());
        events.handle(event(PlatformEvents.ORGANIZATION_CREATED, Map.of("slug", "acme")));
    }
}
