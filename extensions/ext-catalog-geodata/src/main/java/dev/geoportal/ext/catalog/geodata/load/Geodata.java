// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.load;

import dev.geoportal.ext.catalog.geodata.Credentials;
import dev.geoportal.ext.catalog.geodata.geoserver.OrganizationSpace;
import dev.mosaikit.kernel.api.secrets.SecretValue;
import dev.mosaikit.kernel.api.secrets.SecretsUnavailableException;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The geodata database, where the catalog loads the data of each organization (GP-937): one PostGIS
 * database of the installation, a schema per organization named as its GeoServer workspace
 * ({@link OrganizationSpace}, GP-334), the login of the stores of GeoServer, which owns it.
 *
 * <p>The same settings and credential as the governance of GeoServer (GP-334): the setting
 * {@code geoportal.geodata.jdbc-url} of the installation, default built from {@code geoportal.geodata.host},
 * {@code .port} and {@code .database} ({@code postgres}, {@code 5432}, {@code geodata}), and the
 * credential {@value #CREDENTIAL} of the organization, {@code <user>:<password>}. Without that
 * credential the data are stored and not loaded, and the page says why.
 */
@ApplicationScoped
public class Geodata {

    public static final String EXTENSION = "dev.geoportal.ext.catalog.geodata";
    public static final String CREDENTIAL = "geodata";

    private final Credentials secrets;

    public Geodata(Credentials secrets) {
        this.secrets = secrets;
    }

    /** The schema of an organization in the geodata database. */
    public static String schema(UUID organization) {
        return new OrganizationSpace(organization).schema();
    }

    /**
     * A connection to the geodata database without auto-commit, with the credential of the
     * organization of the request, or of the installation when the organization has none (MK-127).
     *
     * @return empty when the organization has no credential {@value #CREDENTIAL}, or the installation
     *     no key for the credentials
     * @throws Unavailable when the database does not answer or refuses the login
     */
    public Optional<Connection> connect() {
        Optional<SecretValue> found;
        try {
            found = secrets.resolve(CREDENTIAL);
        } catch (SecretsUnavailableException e) {
            return Optional.empty();
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        char[] chars;
        try (SecretValue value = found.get()) {
            chars = value.chars();
        }
        try {
            int colon = indexOf(chars, ':');
            if (colon <= 0) {
                throw new Unavailable("The credential " + CREDENTIAL + " must be <user>:<password>.", null);
            }
            PGSimpleDataSource source = new PGSimpleDataSource();
            source.setURL(url());
            source.setUser(new String(chars, 0, colon));
            source.setPassword(new String(chars, colon + 1, chars.length - colon - 1));
            source.setApplicationName("geoportal-catalog");
            source.setConnectTimeout(10);
            Connection connection = source.getConnection();
            connection.setAutoCommit(false);
            return Optional.of(connection);
        } catch (SQLException e) {
            throw new Unavailable("The geodata database does not answer (" + e.getSQLState() + "): "
                    + PostgisWriter.firstLine(e.getMessage()), e);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * Where the loader sidecar writes a table of the schema of an organization (ADR-0019): the geodata
     * database as the services of the installation reach it ({@code geoportal.geodata.host}, {@code .port},
     * {@code .database}, as GeoServer), with the login of the credential {@value #CREDENTIAL}, read now
     * and handed to the job only.
     *
     * @return empty without the credential
     */
    public Optional<LoaderClient.Target> loaderTarget(String schema, String table) {
        Optional<SecretValue> found;
        try {
            found = secrets.resolve(CREDENTIAL);
        } catch (SecretsUnavailableException e) {
            return Optional.empty();
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }
        char[] chars;
        try (SecretValue value = found.get()) {
            chars = value.chars();
        }
        int colon = indexOf(chars, ':');
        if (colon <= 0) {
            Arrays.fill(chars, '\0');
            throw new Unavailable("The credential " + CREDENTIAL + " must be <user>:<password>.", null);
        }
        Config config = ConfigProvider.getConfig();
        LoaderClient.Target target = new LoaderClient.Target(setting(config, "geoportal.geodata.host", "postgres"),
                Integer.parseInt(setting(config, "geoportal.geodata.port", "5432")),
                setting(config, "geoportal.geodata.database", "geodata"), new String(chars, 0, colon),
                Arrays.copyOfRange(chars, colon + 1, chars.length), schema, table);
        Arrays.fill(chars, '\0');
        return Optional.of(target);
    }

    /** The loader sidecar, when the installation has one ({@code geoportal.catalog.loader.url}). */
    public static Optional<LoaderClient> loader() {
        return ConfigProvider.getConfig().getOptionalValue("geoportal.catalog.loader.url", String.class)
                .filter(s -> !s.isBlank())
                .map(url -> new LoaderClient(java.net.URI.create(url)));
    }

    /**
     * The address of Mosaikit as the loader reaches it, to which the signed links to the uploads are
     * relative: {@code geoportal.catalog.loader.source-url}, default {@code http://geoportal:8080}.
     */
    public static String loaderSource() {
        return setting(ConfigProvider.getConfig(), "geoportal.catalog.loader.source-url", "http://geoportal:8080")
                .replaceAll("/+$", "");
    }

    static String url() {
        Config config = ConfigProvider.getConfig();
        String host = setting(config, "geoportal.geodata.host", "postgres");
        String port = setting(config, "geoportal.geodata.port", "5432");
        String database = setting(config, "geoportal.geodata.database", "geodata");
        return setting(config, "geoportal.geodata.jdbc-url", "jdbc:postgresql://" + host + ":" + port + "/" + database);
    }

    private static String setting(Config config, String name, String fallback) {
        return config.getOptionalValue(name, String.class).filter(s -> !s.isBlank()).orElse(fallback);
    }

    private static int indexOf(char[] chars, char c) {
        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == c) {
                return i;
            }
        }
        return -1;
    }

    /** The geodata database cannot be reached: not a problem of the file, the loading is tried again later. */
    public static final class Unavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
