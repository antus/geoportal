// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.load;

import com.sun.net.httpserver.HttpServer;
import dev.geoportal.catalog.upload.Format;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * The loader sidecar of distribution/server/loader for the tests (ADR-0019): built from its Dockerfile,
 * on the network of the geodata database, with a memory limit of 384 MB so that a file larger than it
 * proves the streaming; the files it loads are served from the test through a small HTTP server, as
 * Mosaikit serves them through signed links.
 */
final class Sidecar {

    static final long MEMORY = 384L << 20;
    private static final Path LOADER = Path.of("..", "..", "distribution", "server", "loader").toAbsolutePath().normalize();
    private static GenericContainer<?> container;
    private static HttpServer files;
    private static final Map<String, Path> SERVED = new ConcurrentHashMap<>();

    private Sidecar() {}

    @SuppressWarnings("resource")
    static synchronized LoaderClient client() throws SQLException {
        Geodatabase.connect().close(); // the database first, on the network
        if (container == null) {
            try {
                files = HttpServer.create(new InetSocketAddress(0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            files.createContext("/", exchange -> {
                Path path = SERVED.get(exchange.getRequestURI().getPath().substring(1));
                if (path == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                exchange.sendResponseHeaders(200, Files.size(path));
                try (InputStream in = Files.newInputStream(path); OutputStream out = exchange.getResponseBody()) {
                    in.transferTo(out);
                }
            });
            files.start();
            Testcontainers.exposeHostPorts(files.getAddress().getPort());
            container = new GenericContainer<>(new ImageFromDockerfile("geoportal-server/loader-test", false)
                            .withFileFromPath(".", LOADER))
                    .withNetwork(Geodatabase.NETWORK)
                    .withExposedPorts(8090)
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(MEMORY).withMemorySwap(MEMORY))
                    .waitingFor(Wait.forHttp("/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));
            container.start();
        }
        return new LoaderClient(URI.create("http://" + container.getHost() + ":" + container.getMappedPort(8090)));
    }

    /** The address at which the loader downloads a file. */
    static String serve(Path file) {
        String name = UUID.randomUUID() + "-" + file.getFileName();
        SERVED.put(name, file);
        return "http://host.testcontainers.internal:" + files.getAddress().getPort() + "/" + name;
    }

    static String serve(String fileName, byte[] content) {
        try {
            Path dir = Files.createTempDirectory("gp937");
            Path file = dir.resolve(fileName);
            Files.write(file, content);
            file.toFile().deleteOnExit();
            return serve(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether the loader was killed for its memory. */
    static boolean oomKilled() {
        return Boolean.TRUE.equals(container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec()
                .getState().getOOMKilled());
    }

    /**
     * Loads a file through the loader as the catalog does: a job, its outcome taken over into the table
     * of the dataset, or the refusal it reports.
     */
    static Loader.Result load(Connection connection, LoaderClient loader, String source, String fileName, Format format,
            String table, Integer declared, String layer, Integer formatCrs) throws Exception {
        String loaded = table + "__l" + UUID.randomUUID().toString().substring(0, 8);
        LoaderClient.Target target = format.vector()
                ? new LoaderClient.Target("postgres", 5432, "geodata", "geoserver", "gp937-test-db".toCharArray(),
                        Geodatabase.SCHEMA, loaded)
                : null;
        if (format.vector()) {
            // On a connection of its own: the transaction of the test stays open, and is rolled back by it.
            try (Connection own = Geodatabase.connect(); var s = own.createStatement()) {
                s.execute("create schema if not exists " + Geodatabase.SCHEMA);
                own.commit();
            }
        }
        String job = loader.submit(new LoaderClient.Job(source, fileName, format.id(), declared, formatCrs, layer, target));
        long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
        LoaderClient.Status status;
        do {
            Thread.sleep(300);
            try {
                status = loader.status(job).orElseThrow();
            } catch (UncheckedIOException e) {
                status = null; // a busy loader may not answer at once: the catalog asks again too
            }
        } while ((status == null || !status.finished()) && System.nanoTime() < deadline);
        if (status == null) {
            throw new IllegalStateException("The loader did not answer about job " + job);
        }
        try {
            if ("failed".equals(status.state())) {
                throw new LoadRefused(LoadRefused.Code.valueOf(status.code().toUpperCase().replace('-', '_')), status.reason(),
                        status.layers().stream().map(LoaderClient.Layer::name).toList());
            }
            Crs crs = new Crs(Integer.parseInt(status.crs().substring(5)), Crs.Source.valueOf(status.crsSource().toUpperCase()));
            if (!format.vector()) {
                byte[] cog;
                try (InputStream in = loader.result(job)) {
                    cog = in.readAllBytes();
                }
                return new Loader.Result(null, cog, crs, "Raster", false, status.features(),
                        box(status.nativeExtent()), box(status.wgs84()), 0);
            }
            return Loader.adopt(connection, Geodatabase.SCHEMA, loaded, table, crs);
        } finally {
            loader.forget(job);
        }
    }

    private static Extent box(double[] b) {
        return b == null ? null : new Extent(b[0], b[1], b[2], b[3]);
    }
}
