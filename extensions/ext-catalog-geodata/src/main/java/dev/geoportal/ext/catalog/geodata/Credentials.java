// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata;

import dev.mosaikit.kernel.api.secrets.SecretValue;
import dev.mosaikit.kernel.api.secrets.Secrets;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.Optional;

/**
 * The credentials of the catalog, read from {@link Secrets} each in a transaction of its own (issue #50).
 *
 * <p>The {@code Secrets} bean of the kernel is transactional: when it refuses a read with an exception,
 * such as {@link dev.mosaikit.kernel.api.secrets.SecretsUnavailableException} in an installation without
 * a master key (the portable), the transaction it ran in is marked for rollback. The catalog catches those
 * refusals and goes on without the credential ("stored, not loaded"), so they must not doom the
 * transaction of the request: here the read runs in its own, and only that one rolls back.
 */
@ApplicationScoped
public class Credentials {

    private final Secrets secrets;

    public Credentials(Secrets secrets) {
        this.secrets = secrets;
    }

    /** {@link Secrets#find} for the catalog, in a transaction of its own. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<SecretValue> find(String name) {
        return secrets.find(CatalogExtension.ID, name);
    }

    /** {@link Secrets#resolve} for the catalog, in a transaction of its own. */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Optional<SecretValue> resolve(String name) {
        return secrets.resolve(CatalogExtension.ID, name);
    }
}
