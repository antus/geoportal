// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.proxy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/** A network in CIDR notation, such as {@code 10.20.0.0/16} or {@code fd12:3456::/48}. */
public record Network(byte[] address, int prefix) {

    public Network {
        Objects.requireNonNull(address, "address");
        if (address.length != 4 && address.length != 16) {
            throw new IllegalArgumentException("Not an IPv4 or IPv6 address");
        }
        if (prefix < 0 || prefix > address.length * 8) {
            throw new IllegalArgumentException("Prefix out of range");
        }
        address = address.clone();
    }

    /**
     * Parses {@code address/prefix}, or an address alone for that one address. Only literal
     * addresses are accepted: a name would be resolved, which is what the proxy must not trust.
     */
    public static Network parse(String text) {
        String value = Objects.requireNonNull(text, "text").strip();
        int slash = value.indexOf('/');
        String host = slash < 0 ? value : value.substring(0, slash);
        if (!host.matches("[0-9a-fA-F:.]+") || !(host.contains(":") || host.matches("\\d{1,3}(\\.\\d{1,3}){3}"))) {
            throw new IllegalArgumentException("Not a literal IP address: " + value);
        }
        byte[] bytes;
        try {
            bytes = InetAddress.getByName(host).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Not a literal IP address: " + value, e);
        }
        int prefix = slash < 0 ? bytes.length * 8 : Integer.parseInt(value.substring(slash + 1));
        return new Network(bytes, prefix);
    }

    @Override
    public byte[] address() {
        return address.clone();
    }

    /** Whether the address is in this network; IPv4 and IPv6 never match each other. */
    public boolean contains(InetAddress candidate) {
        return contains(candidate.getAddress());
    }

    boolean contains(byte[] candidate) {
        if (candidate.length != address.length) {
            return false;
        }
        int full = prefix / 8;
        for (int i = 0; i < full; i++) {
            if (candidate[i] != address[i]) {
                return false;
            }
        }
        int rest = prefix % 8;
        if (rest == 0) {
            return true;
        }
        int mask = 0xff << (8 - rest) & 0xff;
        return (candidate[full] & mask) == (address[full] & mask);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Network n && n.prefix == prefix && java.util.Arrays.equals(n.address, address);
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(address) * 31 + prefix;
    }

    @Override
    public String toString() {
        try {
            return InetAddress.getByAddress(address).getHostAddress() + "/" + prefix;
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
