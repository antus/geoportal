// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.load;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The geodata database of the server distribution, in Docker, for the tests of the loading: PostGIS
 * 18-3.6 with {@code distribution/server/postgres/init-geoserver.sh}, which creates the database
 * {@code geodata} with PostGIS and PostGIS raster, owned by the login of GeoServer. Started once for
 * all the tests of a run; the passwords are for the tests only.
 */
final class Geodatabase {

    static final String SCHEMA = "org_00000000000000000000000000000937";

    private static final Path DISTRIBUTION = Path.of("..", "..", "distribution", "server").toAbsolutePath().normalize();
    private static GenericContainer<?> container;
    /** The network of the database, where the loader sidecar reaches it as {@code postgres}. */
    static final Network NETWORK = Network.newNetwork();

    private Geodatabase() {}

    @SuppressWarnings("resource")
    static synchronized Connection connect() throws SQLException {
        if (container == null) {
            container = new GenericContainer<>(DockerImageName.parse("postgis/postgis:18-3.6"))
                    .withNetwork(NETWORK)
                    .withNetworkAliases("postgres")
                    .withEnv("POSTGRES_USER", "mosaikit")
                    .withEnv("POSTGRES_PASSWORD", "gp937-test")
                    .withEnv("POSTGRES_DB", "mosaikit")
                    .withEnv("GEOSERVER_DB_PASSWORD", "gp937-test-db")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(DISTRIBUTION.resolve("postgres/init-geoserver.sh"), 0755),
                            "/docker-entrypoint-initdb.d/30-init-geoserver.sh")
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
                            .withStartupTimeout(Duration.ofMinutes(2)));
            container.start();
        }
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL("jdbc:postgresql://" + container.getHost() + ":" + container.getMappedPort(5432) + "/geodata");
        source.setUser("geoserver");
        source.setPassword("gp937-test-db");
        Connection connection = source.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    static byte[] sample(String name) {
        return resource("/samples/" + name);
    }

    static byte[] fixture(String name) {
        return resource("/load/" + name);
    }

    private static byte[] resource(String path) {
        try (InputStream in = Geodatabase.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One value of a query with parameters. */
    static Object one(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                p.setObject(i + 1, parameters[i]);
            }
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getObject(1) : null;
            }
        }
    }

    static boolean exists(Connection c, String table) throws SQLException {
        return one(c, "select to_regclass(?)", SCHEMA + "." + table) != null;
    }
}
