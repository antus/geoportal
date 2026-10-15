// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.geoserver;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Where the geodata of an organization live: its GeoServer workspace, the PostGIS store of that
 * workspace and the schema of the geodata database the store points to. All three are named from the
 * identifier of the organization, which never changes, and not from its slug, which an administrator
 * may rename: {@code org_<the 32 hexadecimal digits of the identifier>}.
 *
 * <p>The name is a valid XML prefix (it does not start with a digit, as a UUID may), a PostgreSQL
 * identifier that needs no quotes, and reversible: {@link #of(String)} gives back the organization of
 * a workspace, for the gateway and the rules of GeoFence.
 */
public record OrganizationSpace(UUID organization) {

    /** The name of the PostGIS store in each workspace. */
    public static final String STORE = "data";

    private static final String PREFIX = "org_";
    private static final Pattern NAME = Pattern.compile("^org_[0-9a-f]{32}$");

    public OrganizationSpace {
        Objects.requireNonNull(organization, "organization");
    }

    /** The organization of a workspace or schema name, if it is one of these names. */
    public static Optional<OrganizationSpace> of(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        String hex = name.substring(PREFIX.length());
        long most = HexFormat.fromHexDigitsToLong(hex, 0, 16);
        long least = HexFormat.fromHexDigitsToLong(hex, 16, 32);
        return Optional.of(new OrganizationSpace(new UUID(most, least)));
    }

    /** The name of the workspace, and of the schema of the geodata database. */
    public String name() {
        return PREFIX + organization.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    public String workspace() {
        return name();
    }

    public String schema() {
        return name();
    }

    /** The URI of the namespace of the workspace, which names the features in WFS answers. */
    public String namespaceUri() {
        return "urn:geoportal:organization:" + organization;
    }
}
