// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata;

import java.time.Instant;
import java.util.List;

/**
 * Whether the browser reads an external service directly (GP-297), as the catalog found when it was
 * saved or checked again.
 *
 * @param outcome {@code direct} (the browser reads it), {@code proxy} (only through the proxy) or
 *     {@code unreadable} (its server does not answer with capabilities)
 * @param reasons for {@code proxy}: {@code cors} (the service does not allow the origin of the shell)
 *     and {@code csp} (the policy of the shell does not let the browser contact it; MK-128)
 * @param origin the origin of the service, as an administrator would list it among the external
 *     origins of the platform
 * @param proxyTurnedOn whether this check turned the proxy on
 * @param detail what was wrong, in English, for the unreadable ones
 */
public record AccessCheck(
        String outcome, List<String> reasons, String origin, boolean proxyTurnedOn, String detail, Instant checkedAt) {

    public AccessCheck {
        reasons = List.copyOf(reasons);
    }

    AccessCheck turnedOn() {
        return new AccessCheck(outcome, reasons, origin, true, detail, checkedAt);
    }
}
