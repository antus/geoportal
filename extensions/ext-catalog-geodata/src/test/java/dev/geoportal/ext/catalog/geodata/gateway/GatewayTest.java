// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mosaikit.kernel.api.quota.Quota;
import dev.mosaikit.kernel.api.quota.QuotaExceededException;
import dev.mosaikit.kernel.api.quota.Quotas;
import dev.mosaikit.kernel.api.resource.ResourceRef;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-336: how the gateway counts the requests of an organization against its quota, and the addresses
 * of the gateway. The whole path, nginx, Mosaikit and GeoServer, is tests/gateway/gateway.e2e.ts on the
 * server stack.
 */
@Tag("GP-336")
class GatewayTest {

    private static final String EXTENSION = "dev.geoportal.ext.catalog.geodata";

    /** A counter with a limit, as the kernel keeps it: a consumption that does not fit counts nothing. */
    static final class Counter implements Quotas {
        final OptionalLong limit;
        long used;

        Counter(Long limit) {
            this.limit = limit == null ? OptionalLong.empty() : OptionalLong.of(limit);
        }

        @Override
        public Quota check(String extension, String measure, long amount) {
            assertEquals(EXTENSION, extension);
            assertEquals(DataRequests.MEASURE, measure);
            return new Quota(extension + "/" + measure, "requests", "2026-10", limit, used, amount);
        }

        @Override
        public Quota consume(String extension, String measure, long amount) {
            Quota q = check(extension, measure, amount);
            if (!q.fits()) {
                throw new QuotaExceededException("Quota exceeded", q);
            }
            used += amount;
            return q;
        }

        @Override
        public Quota set(String extension, String measure, long amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reportSpace(ResourceRef resource, long bytes) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void withoutALimitEveryRequestServedAndTheOneThatAsksAreCounted() {
        Counter q = new Counter(null);
        assertTrue(DataRequests.admit(q, EXTENSION, 41).isEmpty());
        assertEquals(42, q.used);
        assertTrue(DataRequests.admit(q, EXTENSION, 0).isEmpty());
        assertEquals(43, q.used);
    }

    @Test
    void theRequestsServedAreCountedAsFarAsTheLimitAndTheNextOneIsRefused() {
        Counter q = new Counter(10L);
        q.used = 7;
        // Five were served in the last ten seconds: three fit and stay counted, the one that asks does not.
        Optional<String> refused = DataRequests.admit(q, EXTENSION, 5);
        assertTrue(refused.isPresent());
        assertTrue(refused.get().contains("10 requests"), refused.get());
        assertEquals(10, q.used);
        assertTrue(DataRequests.admit(q, EXTENSION, 0).isPresent());
        assertEquals(10, q.used);
        // The gate counts one at a time and throws, as the kernel answers it.
        assertThrows(QuotaExceededException.class, () -> DataRequests.count(q, EXTENSION));
    }

    @Test
    void theLastRequestThatFitsPasses() {
        Counter q = new Counter(10L);
        q.used = 8;
        assertTrue(DataRequests.admit(q, EXTENSION, 1).isEmpty());
        assertEquals(10, q.used);
    }

    @Test
    void theServedHeaderIsANumberOrNothing() {
        assertEquals(0, DataRequests.served(null));
        assertEquals(0, DataRequests.served(" "));
        assertEquals(0, DataRequests.served("-3"));
        assertEquals(0, DataRequests.served("many"));
        assertEquals(12, DataRequests.served(" 12 "));
        Counter q = new Counter(null);
        DataRequests.admit(q, EXTENSION, Long.MAX_VALUE);
        assertEquals(DataRequests.MAX_SERVED + 1, q.used);
    }

    @Test
    void theAddressesOfALayerAreThoseOfTheGatewayOnTheOrigin() {
        assertEquals("/gw/ows/acme/roads/wms", Gateway.layerService("acme", "roads", "wms"));
        assertEquals("/gw/ows/acme/roads/gwc/service/wmts", Gateway.layerService("acme", "roads", "wmts"));
        assertThrows(IllegalArgumentException.class, () -> Gateway.layerService("../x", "roads", "wms"));
        assertEquals("/gw/link/abc_-9/0f8e/wfs", Gateway.linkService("abc_-9", "0f8e", "wfs"));
    }

    @Test
    void theGateSendsPeopleToTheGatewayWithTheSameQuery() {
        assertEquals(Optional.of("/gw/ows/acme/roads/wms?service=WMS&request=GetMap&bbox=1%2C2"),
                Gateway.redirect("acme", "roads", "wms", "service=WMS&request=GetMap&bbox=1%2C2"));
        assertEquals(Optional.of("/gw/ows/acme/roads/gwc/service/wmts"),
                Gateway.redirect("acme", "roads", "gwc/service/wmts", null));
        assertTrue(Gateway.redirect("acme", "roads", "rest/layers", "").isEmpty());
    }
}
