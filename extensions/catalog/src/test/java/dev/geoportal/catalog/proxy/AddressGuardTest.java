// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.proxy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** GP-939: the proxy reaches registered public addresses only, never the internal network. */
@Tag("GP-939")
class AddressGuardTest {

    private static AddressGuard answering(String... addresses) {
        return new AddressGuard(host -> {
            InetAddress[] result = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                result[i] = InetAddress.getByName(addresses[i]);
            }
            return result;
        }, List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "file:///etc/passwd", "ftp://example.org/", "gopher://example.org/", "jar:http://example.org/a.jar!/",
        "javascript:alert(1)", "//example.org/wms", "http://user:secret@example.org/wms",
        "http://example.org/wms#x", "http:///wms", "http://example.org:0/wms", ""
    })
    void GP_939_only_http_and_https_addresses_with_a_host_are_accepted(String url) {
        assertThrows(RefusedTarget.class, () -> AddressGuard.parse(url));
    }

    @Test
    void GP_939_a_public_https_address_is_accepted() throws Exception {
        URI uri = AddressGuard.parse("https://maps.example.org/ows?map=a.map");
        assertEquals(List.of(InetAddress.getByName("93.184.216.34")),
                answering("93.184.216.34").check(uri));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "127.0.0.1", "127.8.9.10", "0.0.0.0", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
        "169.254.169.254", "169.254.10.10", "100.64.0.1", "224.0.0.1", "255.255.255.255", "198.18.0.1",
        "::1", "::", "fe80::1", "fc00::1", "fd00:ec2::254", "::ffff:127.0.0.1", "64:ff9b::7f00:1",
        "2002:7f00:1::1", "2001:0:4136:e378:8000:63bf:3fff:fdd2", "::127.0.0.1", "ff02::1"
    })
    void GP_939_a_name_that_resolves_to_an_internal_address_is_refused(String address) throws Exception {
        URI uri = AddressGuard.parse("http://innocent.example.org/wms");
        RefusedTarget refused = assertThrows(RefusedTarget.class, () -> answering(address).check(uri));
        // The answer says why without telling the address the name resolved to.
        assertFalse(refused.getMessage().contains(address));
    }

    @Test
    void GP_939_dns_that_answers_127_0_0_1_is_refused() throws Exception {
        assertThrows(RefusedTarget.class,
                () -> answering("127.0.0.1").check(AddressGuard.parse("http://rebind.example.org:8080/geoserver/wms")));
        assertThrows(RefusedTarget.class,
                () -> AddressGuard.system(List.of()).check(AddressGuard.parse("http://localhost/wms")));
        assertThrows(RefusedTarget.class,
                () -> AddressGuard.system(List.of()).check(AddressGuard.parse("http://127.1/wms")));
        assertThrows(RefusedTarget.class,
                () -> AddressGuard.system(List.of()).check(AddressGuard.parse("http://[::1]/wms")));
    }

    @Test
    void GP_939_one_internal_address_among_public_ones_refuses_the_name() {
        assertThrows(RefusedTarget.class,
                () -> answering("93.184.216.34", "10.0.0.5").check(AddressGuard.parse("http://mixed.example.org/")));
    }

    @Test
    void GP_939_the_installation_may_allow_an_internal_network_but_never_the_metadata_address() throws Exception {
        AddressGuard intranet = new AddressGuard(InetAddress::getAllByName,
                List.of(Network.parse("10.20.0.0/16"), Network.parse("169.254.0.0/16"), Network.parse("127.0.0.1")));
        assertTrue(intranet.allowed(InetAddress.getByName("10.20.3.4")));
        assertTrue(intranet.allowed(InetAddress.getByName("127.0.0.1")));
        assertFalse(intranet.allowed(InetAddress.getByName("10.21.3.4")));
        assertFalse(intranet.allowed(InetAddress.getByName("127.0.0.2")));
        assertFalse(intranet.allowed(InetAddress.getByName("169.254.169.254")));
        assertTrue(intranet.allowed(InetAddress.getByName("169.254.1.1")));
    }

    @Test
    void GP_939_a_name_that_does_not_resolve_is_refused() {
        AddressGuard guard = new AddressGuard(host -> {
            throw new UnknownHostException(host);
        }, List.of());
        assertThrows(RefusedTarget.class, () -> guard.check(AddressGuard.parse("http://nowhere.example.org/")));
    }

    @Test
    void GP_939_networks_are_literal_addresses_only() {
        assertThrows(IllegalArgumentException.class, () -> Network.parse("intranet.example.org/24"));
        assertDoesNotThrow(() -> Network.parse("fd12:3456::/48"));
        assertTrue(Network.parse("10.0.0.0/8").contains(new byte[] {10, 9, 8, 7}));
    }
}
