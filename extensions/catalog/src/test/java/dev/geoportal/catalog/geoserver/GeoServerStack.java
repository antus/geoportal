// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.geoserver;

import com.github.dockerjava.api.exception.NotFoundException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The geospatial stack of the server distribution, in Docker, for the tests of GP-334: PostGIS with the
 * databases of {@code distribution/server/postgres/init-geoserver.sh}, the GeoServer image of
 * {@code distribution/server/geoserver} (GeoFence on PostgreSQL) and its {@code init/prepare.sh}, which
 * writes the GeoFence default DENY. The passwords are for the tests only.
 *
 * <p>The image is {@code geoportal-server/geoserver:2.28.5} when Docker has it (built by the server
 * distribution), otherwise it is built from the Dockerfile; {@code -Dgeoportal.test.geoserver-image}
 * chooses another one.
 */
final class GeoServerStack implements AutoCloseable {

    static final String ADMIN = "gsadmin";
    static final String ADMIN_PASSWORD = "gp334-test-admin";
    static final String DB_USER = "geoserver";
    static final String DB_PASSWORD = "gp334-test-db";

    private static final Path DISTRIBUTION = Path.of("..", "..", "distribution", "server").toAbsolutePath().normalize();
    private static final String IMAGE = System.getProperty("geoportal.test.geoserver-image", "geoportal-server/geoserver:2.28.5");

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> postgis;
    private final GenericContainer<?> geoserver;
    private boolean peopleFromHeaders;

    GeoServerStack() {
        postgis = new GenericContainer<>(DockerImageName.parse("postgis/postgis:18-3.6"))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withEnv("POSTGRES_USER", "mosaikit")
                .withEnv("POSTGRES_PASSWORD", "gp334-test")
                .withEnv("POSTGRES_DB", "mosaikit")
                .withEnv("GEOSERVER_DB_PASSWORD", DB_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(DISTRIBUTION.resolve("postgres/init-geoserver.sh"), 0755),
                        "/docker-entrypoint-initdb.d/30-init-geoserver.sh")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        Object image = image();
        geoserver = (image instanceof ImageFromDockerfile built
                        ? new GenericContainer<>(built)
                        : new GenericContainer<>((DockerImageName) image))
                .withNetwork(network)
                .withNetworkAliases("geoserver")
                .withEnv("GEOSERVER_ADMIN_USER", ADMIN)
                .withEnv("GEOSERVER_ADMIN_PASSWORD", ADMIN_PASSWORD)
                .withEnv("GEOFENCE_DB_HOST", "postgres")
                .withEnv("GEOFENCE_DB_NAME", "geoserver")
                .withEnv("GEOFENCE_DB_USER", DB_USER)
                .withEnv("GEOFENCE_DB_PASSWORD", DB_PASSWORD)
                .withEnv("SKIP_DEMO_DATA", "true")
                .withEnv("EXTRA_JAVA_OPTS", "-Xms256m -Xmx1g")
                .withExposedPorts(8080)
                .dependsOn(postgis)
                .waitingFor(Wait.forHttp("/geoserver/rest/about/version.json")
                        .withBasicCredentials(ADMIN, ADMIN_PASSWORD)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(5)));
    }

    private static Object image() {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(IMAGE).exec();
            return DockerImageName.parse(IMAGE);
        } catch (NotFoundException e) {
            return new ImageFromDockerfile(IMAGE, false).withFileFromPath(".", DISTRIBUTION.resolve("geoserver"));
        }
    }

    /**
     * GeoServer takes the person from the header {@value #USER_HEADER} and their roles from
     * {@value #ROLES_HEADER} (separated by {@code ;}), as the tests of the GeoFence rules of GP-335 need
     * before the gateway of GP-336 exists; GeoServer then listens on a fixed port, kept across the restart
     * that loads the filter. Never in a distribution: the header is trusted from anyone here.
     */
    GeoServerStack withPeopleFromHeaders() {
        peopleFromHeaders = true;
        geoserver.setPortBindings(List.of(freePort() + ":8080"));
        return this;
    }

    static final String USER_HEADER = "sec-username";
    static final String ROLES_HEADER = "sec-roles";

    private static final String HEADER_FILTER = """
            <requestHeaderAuthentication>
              <id>gp335-test-header</id>
              <name>testheader</name>
              <className>org.geoserver.security.filter.GeoServerRequestHeaderAuthenticationFilter</className>
              <roleSource class="org.geoserver.security.config.PreAuthenticatedUserNameFilterConfig$PreAuthenticatedUserNameRoleSource">Header</roleSource>
              <rolesHeaderAttribute>sec-roles</rolesHeaderAttribute>
              <principalHeaderAttribute>sec-username</principalHeaderAttribute>
            </requestHeaderAuthentication>
            """;

    private void peopleFromHeaders() {
        try {
            String script = """
                    set -e; cd /opt/geoserver_data/security; mkdir -p filter/testheader
                    cat > filter/testheader/config.xml <<'XML'
                    %sXML
                    grep -q '<filter>testheader</filter>' config.xml || perl -0pi -e \
                      's#(<filters name="default"[^>]*>\\s*)<filter>basic</filter>#$1<filter>testheader</filter>\\n      <filter>basic</filter>#' config.xml
                    grep -c '<filter>testheader</filter>' config.xml
                    """.formatted(HEADER_FILTER);
            var r = geoserver.execInContainer("bash", "-c", script);
            if (!r.getStdout().strip().endsWith("1")) {
                throw new IllegalStateException("The header filter is not in the chain: " + r.getStdout() + r.getStderr());
            }
            DockerClientFactory.instance().client().restartContainerCmd(geoserver.getContainerId()).withTimeout(30).exec();
            waitForGeoServer();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void waitForGeoServer() throws InterruptedException {
        var http = java.net.http.HttpClient.newHttpClient();
        String auth = java.util.Base64.getEncoder().encodeToString((ADMIN + ":" + ADMIN_PASSWORD).getBytes());
        long until = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (System.nanoTime() < until) {
            try {
                var r = http.send(java.net.http.HttpRequest.newBuilder(URI.create(url() + "/rest/about/version.json"))
                        .header("Authorization", "Basic " + auth).timeout(Duration.ofSeconds(10)).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() == 200) {
                    return;
                }
            } catch (java.io.IOException e) {
                // not yet
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("GeoServer did not come back after the restart");
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    GeoServerStack start() {
        postgis.start();
        geoserver.start();
        if (peopleFromHeaders) {
            peopleFromHeaders();
        }
        // The init container of the distribution: the GeoFence default DENY.
        try (GenericContainer<?> init = new GenericContainer<>(DockerImageName.parse("curlimages/curl:8.16.0"))
                .withNetwork(network)
                .withEnv("GEOSERVER_ADMIN_USER", ADMIN)
                .withEnv("GEOSERVER_ADMIN_PASSWORD", ADMIN_PASSWORD)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(DISTRIBUTION.resolve("geoserver/init/prepare.sh"), 0644), "/prepare.sh")
                .withCreateContainerCmdModifier(c -> c.withEntrypoint("sh", "/prepare.sh"))
                .withLogConsumer(frame -> System.out.print("prepare.sh: " + frame.getUtf8String()))
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(3)))) {
            init.start();
            if (!init.getLogs().contains("GeoServer of Geoportal ready")) {
                throw new IllegalStateException("prepare.sh did not finish: " + init.getLogs());
            }
        }
        return this;
    }

    /** GeoServer as the catalog reaches it in the tests. */
    URI url() {
        return URI.create("http://" + geoserver.getHost() + ":" + geoserver.getMappedPort(8080) + "/geoserver");
    }

    /** The geodata database as the catalog reaches it in the tests. */
    String geodataJdbc() {
        return "jdbc:postgresql://" + postgis.getHost() + ":" + postgis.getMappedPort(5432) + "/geodata";
    }

    /** The geodata database as GeoServer reaches it, with the login of the stores. */
    StoreConnection store() {
        return new StoreConnection("postgres", 5432, "geodata", DB_USER, DB_PASSWORD.toCharArray());
    }

    GeoServerClient client() {
        return new GeoServerClient(url(), ADMIN, ADMIN_PASSWORD.toCharArray());
    }

    JdbcGeodataSchemas schemas() {
        return new JdbcGeodataSchemas(geodataJdbc(), DB_USER, DB_PASSWORD.toCharArray());
    }

    /** Runs SQL on the geodata database as the login of the stores, as the loader of the catalog does. */
    void sql(String sql) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(geodataJdbc());
        ds.setUser(DB_USER);
        ds.setPassword(DB_PASSWORD);
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Longitude and latitude of the first geometry of a table, computed by PostGIS. */
    double[] lonLat(String table) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(geodataJdbc());
        ds.setUser(DB_USER);
        ds.setPassword(DB_PASSWORD);
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("select ST_X(g), ST_Y(g) from (select ST_Transform(geom, 4326) g from "
                        + table + " order by id limit 1) t")) {
            r.next();
            return new double[] {r.getDouble(1), r.getDouble(2)};
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean distributionFound() {
        return Files.isRegularFile(DISTRIBUTION.resolve("geoserver/Dockerfile"));
    }

    @Override
    public void close() {
        geoserver.stop();
        postgis.stop();
        network.close();
    }
}
