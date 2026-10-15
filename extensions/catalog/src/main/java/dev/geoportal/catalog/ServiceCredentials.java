// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog;

import dev.mosaikit.kernel.api.secrets.SecretValue;
import dev.mosaikit.kernel.api.secrets.Secrets;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Authorization header the proxy sends to a service that needs a credential: read from the
 * credentials of Mosaikit ({@link Secrets}) on the server, never from the browser, never returned to
 * it.
 *
 * <p>Mosaikit audits every read of a credential, and a map asks the proxy for dozens of tiles at a
 * time: the header is kept in memory for one minute per organization and credential, so that the
 * audit log shows the use of a credential, not each tile. A replaced credential is used within a
 * minute.
 */
@ApplicationScoped
public class ServiceCredentials {

    static final Duration KEEP = Duration.ofMinutes(1);

    private record Key(UUID organization, String name, String scheme) {}

    private record Kept(char[] header, Instant until) {}

    private final Credentials secrets;
    private final Clock clock;
    private final ConcurrentHashMap<Key, Kept> kept = new ConcurrentHashMap<>();

    public ServiceCredentials(Credentials secrets) {
        this.secrets = secrets;
        this.clock = Clock.systemUTC();
    }

    /**
     * The value of the Authorization header for the credential of the organization, or empty when
     * the organization has no credential with that name the catalog may read.
     */
    public Optional<String> header(UUID organization, String name, String scheme) {
        Key key = new Key(organization, name, scheme);
        Instant now = clock.instant();
        Kept current = kept.get(key);
        if (current == null || current.until().isBefore(now)) {
            Optional<SecretValue> secret = secrets.find(name);
            if (secret.isEmpty()) {
                kept.remove(key);
                return Optional.empty();
            }
            try (SecretValue value = secret.get()) {
                current = new Kept(build(value.chars(), scheme), now.plus(KEEP));
            }
            kept.put(key, current);
        }
        return Optional.of(new String(current.header()));
    }

    /** Forgets the headers kept for a credential, when the service using it changes. */
    public void forget(UUID organization, String name) {
        kept.keySet().removeIf(k -> k.organization().equals(organization) && k.name().equals(name));
    }

    private static char[] build(char[] value, String scheme) {
        try {
            if ("basic".equals(scheme)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(value));
                byte[] raw = new byte[bytes.remaining()];
                bytes.get(raw);
                String encoded = Base64.getEncoder().encodeToString(raw);
                Arrays.fill(raw, (byte) 0);
                return ("Basic " + encoded).toCharArray();
            }
            char[] header = new char[7 + value.length];
            "Bearer ".getChars(0, 7, header, 0);
            System.arraycopy(value, 0, header, 7, value.length);
            return header;
        } finally {
            Arrays.fill(value, '\0');
        }
    }
}
