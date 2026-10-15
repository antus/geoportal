// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata;

import dev.mosaikit.kernel.api.resource.ResourceRef;
import java.util.UUID;

/** The names the catalog gives Mosaikit: its identifier, its resource types and permissions. */
public final class CatalogExtension {

    /** The identifier of the extension, as in manifest.yaml. */
    public static final String ID = "dev.geoportal.ext.catalog.geodata";

    /** The resource type of uploaded datasets. */
    public static final String DATASET = "dataset";

    /** The resource type of external services. */
    public static final String SERVICE = "service";

    /** The permission to register external services. */
    public static final String REGISTER_SERVICE = "register-service";

    /** The permission to have the proxy send a credential of the organization to a service. */
    public static final String SERVICE_CREDENTIALS = "service-credentials";

    private CatalogExtension() {}

    public static ResourceRef dataset(UUID id) {
        return new ResourceRef(ID, DATASET, id.toString());
    }

    public static ResourceRef service(UUID id) {
        return new ResourceRef(ID, SERVICE, id.toString());
    }
}
