// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.proxy;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What keeps the proxy from becoming a way into the internal network (server-side request forgery).
 *
 * <ul>
 *   <li>Only {@code http} and {@code https} addresses with a host, without user information or
 *       fragment, are registered or reached.
 *   <li>The name is resolved <b>once</b> per request, every address it resolves to is checked, and
 *       the proxy connects to a checked address, never resolving again: a name that answers a public
 *       address to the check and an internal one to the connection (DNS rebinding) gets nowhere.
 *   <li>Loopback, private, link-local, unique-local, carrier-grade NAT, multicast, reserved and
 *       documentation ranges are refused, also when written inside an IPv6 address (mapped,
 *       NAT64, 6to4, Teredo), unless the installation allows a network that contains them.
 *   <li>The metadata addresses of cloud providers are refused always, whatever the installation
 *       allows: no map service lives there, and they hand out the credentials of the machine.
 *   <li>If one of the addresses of a name is refused, the name is refused: no picking the good one.
 * </ul>
 */
public final class AddressGuard {

    /** Resolves a name; the system resolver in the product, a fixed answer in the tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** The metadata services of cloud providers, never reached. */
    private static final List<Network> METADATA = List.of(
            Network.parse("169.254.169.254/32"),
            Network.parse("169.254.170.2/32"),
            Network.parse("100.100.100.200/32"),
            Network.parse("fd00:ec2::254/128"));

    /** Ranges that are not the public internet, besides what InetAddress already tells. */
    private static final List<Network> INTERNAL = List.of(
            Network.parse("0.0.0.0/8"),
            Network.parse("10.0.0.0/8"),
            Network.parse("100.64.0.0/10"),
            Network.parse("127.0.0.0/8"),
            Network.parse("169.254.0.0/16"),
            Network.parse("172.16.0.0/12"),
            Network.parse("192.0.0.0/24"),
            Network.parse("192.0.2.0/24"),
            Network.parse("192.88.99.0/24"),
            Network.parse("192.168.0.0/16"),
            Network.parse("198.18.0.0/15"),
            Network.parse("198.51.100.0/24"),
            Network.parse("203.0.113.0/24"),
            Network.parse("224.0.0.0/4"),
            Network.parse("240.0.0.0/4"),
            Network.parse("::/128"),
            Network.parse("::1/128"),
            Network.parse("::/96"),
            Network.parse("64:ff9b:1::/48"),
            Network.parse("100::/64"),
            Network.parse("2001:db8::/32"),
            Network.parse("fc00::/7"),
            Network.parse("fe80::/10"),
            Network.parse("fec0::/10"),
            Network.parse("ff00::/8"));

    /** ::ffff:0:0/96, built from bytes: Java reads the literal as the IPv4 address 0.0.0.0. */
    private static final Network MAPPED =
            new Network(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 0, 0, 0, 0}, 96);
    private static final Network NAT64 = Network.parse("64:ff9b::/96");
    private static final Network SIX_TO_FOUR = Network.parse("2002::/16");
    private static final Network TEREDO = Network.parse("2001::/32");

    private final Resolver resolver;
    private final List<Network> allowed;

    public AddressGuard(Resolver resolver, List<Network> allowed) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.allowed = List.copyOf(allowed);
    }

    /** The guard with the resolver of the system and the networks the installation allows. */
    public static AddressGuard system(List<Network> allowed) {
        return new AddressGuard(InetAddress::getAllByName, allowed);
    }

    /**
     * Checks the form of an address to register or to reach: http or https, a host, no user
     * information (a password in an address ends in logs and answers), no fragment.
     */
    public static URI parse(String url) throws RefusedTarget {
        if (url == null || url.isBlank() || url.length() > 2000) {
            throw new RefusedTarget("The address is empty or too long.");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new RefusedTarget("The address is not valid.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new RefusedTarget("Only http and https addresses are allowed.");
        }
        if (uri.getRawUserInfo() != null) {
            throw new RefusedTarget("The address must not contain a user name or password: use a credential.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new RefusedTarget("The address has no host.");
        }
        if (uri.getRawFragment() != null) {
            throw new RefusedTarget("The address must not contain a fragment.");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new RefusedTarget("The port of the address is not valid.");
        }
        return uri.normalize();
    }

    /** The port of an address, explicit or the default of its scheme. */
    public static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** The host of an address without the brackets of an IPv6 literal. */
    public static String host(URI uri) {
        String host = uri.getHost();
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    /**
     * Resolves the host of the address once and returns its addresses, every one of them allowed, in
     * the order of the resolver: the proxy connects to these and resolves nothing else.
     */
    public List<InetAddress> check(URI uri) throws RefusedTarget {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host(uri));
        } catch (UnknownHostException e) {
            throw new RefusedTarget("The host of the service cannot be resolved.");
        }
        if (addresses == null || addresses.length == 0) {
            throw new RefusedTarget("The host of the service cannot be resolved.");
        }
        List<InetAddress> checked = new ArrayList<>(addresses.length);
        for (InetAddress address : addresses) {
            if (!allowed(address)) {
                throw new RefusedTarget(
                        "The host of the service resolves to an internal or reserved address, which the proxy does not reach.");
            }
            checked.add(address);
        }
        return List.copyOf(checked);
    }

    /** Whether the proxy may connect to the address. */
    public boolean allowed(InetAddress address) {
        if (metadata(address)) {
            return false;
        }
        if (allowed.stream().anyMatch(n -> n.contains(address))) {
            return true;
        }
        return !internal(address);
    }

    static boolean metadata(InetAddress address) {
        for (InetAddress candidate : forms(address)) {
            if (METADATA.stream().anyMatch(n -> n.contains(candidate))) {
                return true;
            }
        }
        return false;
    }

    static boolean internal(InetAddress address) {
        for (InetAddress candidate : forms(address)) {
            if (candidate.isAnyLocalAddress()
                    || candidate.isLoopbackAddress()
                    || candidate.isLinkLocalAddress()
                    || candidate.isSiteLocalAddress()
                    || candidate.isMulticastAddress()
                    || INTERNAL.stream().anyMatch(n -> n.contains(candidate))) {
                return true;
            }
        }
        // Teredo hides the client address obfuscated: nothing a map service needs.
        return address instanceof Inet6Address && TEREDO.contains(address);
    }

    /** The address itself and the IPv4 address it carries, when it is an IPv6 form of one. */
    private static List<InetAddress> forms(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return List.of(address);
        }
        byte[] bytes = address.getAddress();
        byte[] v4 = null;
        if (MAPPED.contains(bytes) || NAT64.contains(bytes)) {
            v4 = Arrays.copyOfRange(bytes, 12, 16);
        } else if (SIX_TO_FOUR.contains(bytes)) {
            v4 = Arrays.copyOfRange(bytes, 2, 6);
        }
        if (v4 == null) {
            return List.of(address);
        }
        try {
            return List.of(address, Inet4Address.getByAddress(v4));
        } catch (UnknownHostException e) {
            return List.of(address);
        }
    }
}
