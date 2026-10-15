// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.gateway;

import dev.mosaikit.kernel.api.quota.Quota;
import dev.mosaikit.kernel.api.quota.Quotas;
import java.util.Optional;

/**
 * The measure {@value #MEASURE} of the catalog (GP-336, MK-076): the requests to the geodata of an
 * organization that the gateway let through in a calendar month, counted for the person who made
 * them. Platform administrators set its limit per organization; beyond it the gateway refuses.
 *
 * <p>The gateway asks the catalog for a go-ahead at most every ten seconds per person and
 * organization, and counts by itself the requests it lets through in between: each go-ahead brings
 * the requests served since the previous one ({@code served}) and counts one more, the request that
 * asked. The requests served are counted as far as the limit allows, since they were already served;
 * the new one is refused when nothing is left. The gate of the catalog, which every request reaches,
 * counts one each time.
 */
public final class DataRequests {

    /** The identifier of the measure in the manifest. */
    public static final String MEASURE = "data-requests";

    /** The most the gateway may bring at once: more in ten seconds is not a person with a map. */
    static final long MAX_SERVED = 100_000;

    private DataRequests() {}

    /**
     * Counts the requests the gateway served since its last go-ahead, as far as the limit allows, and
     * then the request that asks, if it fits.
     *
     * @return why the request that asks is refused, or empty when it was counted; what was served is
     *     counted either way, so the caller answers a refusal without throwing (which would roll back
     *     the count)
     */
    public static Optional<String> admit(Quotas quotas, String extension, long served) {
        long pending = Math.min(Math.max(served, 0), MAX_SERVED);
        if (pending > 0) {
            Quota now = quotas.check(extension, MEASURE, pending);
            long take = now.remaining().isPresent() ? Math.min(pending, now.remaining().getAsLong()) : pending;
            if (take > 0) {
                quotas.consume(extension, MEASURE, take);
            }
        }
        Quota next = quotas.check(extension, MEASURE, 1);
        if (!next.fits()) {
            return Optional.of("The organization may use " + next.limit().getAsLong() + " " + next.unit()
                    + " of data a month, and used " + next.used() + " in " + next.period() + ".");
        }
        quotas.consume(extension, MEASURE, 1);
        return Optional.empty();
    }

    /**
     * Counts one request of the gate of the catalog.
     *
     * @throws dev.mosaikit.kernel.api.quota.QuotaExceededException when it does not fit
     */
    public static void count(Quotas quotas, String extension) {
        quotas.consume(extension, MEASURE, 1);
    }

    /** The served count the gateway sends, or 0 when it is missing or not a number. */
    public static long served(String header) {
        if (header == null || header.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(header.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
