// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata;

import dev.geoportal.ext.catalog.geodata.proxy.AddressGuard;
import dev.geoportal.ext.catalog.geodata.proxy.Forwarder;
import dev.geoportal.ext.catalog.geodata.proxy.Network;
import dev.geoportal.ext.catalog.geodata.proxy.UpstreamClient;
import jakarta.enterprise.context.ApplicationScoped;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import javax.net.ssl.SSLContext;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The settings of the installation for the proxy of external services, and what is built from them.
 *
 * <ul>
 *   <li>{@code geoportal.catalog.proxy.allowed-networks}: literal networks (CIDR, comma separated)
 *       that the proxy may reach although internal, such as the map server of the intranet; empty by
 *       default. The metadata addresses of cloud providers stay refused.
 *   <li>{@code geoportal.catalog.proxy.connect-timeout-ms} (10000) and {@code read-timeout-ms}
 *       (30000): how long the proxy waits for the connection and for each read.
 *   <li>{@code geoportal.catalog.proxy.max-response-bytes} (104857600): the largest answer passed on.
 *   <li>{@code geoportal.catalog.proxy.max-request-bytes} (1048576): the largest body of a POST.
 *   <li>{@code geoportal.catalog.proxy.concurrent-per-service} (8): requests to one service at a
 *       time; the others wait up to 20 s, then get 503.
 * </ul>
 */
@ApplicationScoped
public class ProxySettings {

    private final Forwarder forwarder;
    private final AddressGuard guard;
    private final UpstreamClient probe;
    private final int maxRequestBytes;
    private final int concurrent;
    private final ConcurrentHashMap<UUID, Semaphore> slots = new ConcurrentHashMap<>();

    public ProxySettings(
            @ConfigProperty(name = "geoportal.catalog.proxy.allowed-networks", defaultValue = " ") String allowed,
            @ConfigProperty(name = "geoportal.catalog.proxy.connect-timeout-ms", defaultValue = "10000") int connect,
            @ConfigProperty(name = "geoportal.catalog.proxy.read-timeout-ms", defaultValue = "30000") int read,
            @ConfigProperty(name = "geoportal.catalog.proxy.max-response-bytes", defaultValue = "104857600") long maxResponse,
            @ConfigProperty(name = "geoportal.catalog.proxy.max-request-bytes", defaultValue = "1048576") int maxRequest,
            @ConfigProperty(name = "geoportal.catalog.proxy.concurrent-per-service", defaultValue = "8") int concurrent) {
        List<Network> networks = Arrays.stream(allowed.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(Network::parse)
                .toList();
        this.guard = AddressGuard.system(networks);
        SSLContext tls;
        try {
            tls = SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No TLS in this Java", e);
        }
        this.forwarder = new Forwarder(guard, new UpstreamClient(connect, read, tls.getSocketFactory()), maxResponse);
        // The check of GP-297 runs while a person waits for the service to be saved: shorter waits.
        this.probe = new UpstreamClient(Math.min(connect, 5000), Math.min(read, 10000), tls.getSocketFactory());
        this.maxRequestBytes = maxRequest;
        this.concurrent = Math.max(1, concurrent);
    }

    public Forwarder forwarder() {
        return forwarder;
    }

    public AddressGuard guard() {
        return guard;
    }

    /** The client of the check whether the browser reads a service directly (GP-297). */
    public UpstreamClient probe() {
        return probe;
    }

    public int maxRequestBytes() {
        return maxRequestBytes;
    }

    /** The slots of one service: at most so many requests to it at a time. */
    public Semaphore slots(UUID service) {
        return slots.computeIfAbsent(service, id -> new Semaphore(concurrent));
    }
}
