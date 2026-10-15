// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * GP-983: an installation whose catalog was {@code dev.geoportal.catalog} in the schema {@code ext_catalog}
 * keeps its data as {@code dev.geoportal.ext.catalog.geodata} in {@code ext_catalog_geodata}, with
 * {@code upgrade/adopt-catalog.sql}, and the kernel then finds every migration applied. The schema of the
 * kernel is reduced to the columns the script reads; the migrations are those of the extension, run by
 * Flyway as the kernel runs them (one schema, its own history, created when missing).
 */
@Tag("postgis")
@Tag("GP-983")
class AdoptCatalogTest {

    private static final String OLD = "dev.geoportal.catalog";
    private static final String NEW = "dev.geoportal.ext.catalog.geodata";
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000983");

    private static GenericContainer<?> postgres;
    private static PGSimpleDataSource source;

    @BeforeAll
    @SuppressWarnings("resource")
    static void start() {
        // GEOPORTAL_TEST_POSTGRES: a database of one's own (jdbc URL; user mosaikit, no password), instead of Docker.
        String given = System.getenv("GEOPORTAL_TEST_POSTGRES");
        if (given != null && !given.isBlank()) {
            source = new PGSimpleDataSource();
            source.setURL(given);
            source.setUser("mosaikit");
            return;
        }
        postgres = new GenericContainer<>(DockerImageName.parse("postgis/postgis:18-3.6"))
                .withEnv("POSTGRES_USER", "mosaikit")
                .withEnv("POSTGRES_PASSWORD", "gp983-test")
                .withEnv("POSTGRES_DB", "mosaikit")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        postgres.start();
        source = new PGSimpleDataSource();
        source.setURL("jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/mosaikit");
        source.setUser("mosaikit");
        source.setPassword("gp983-test");
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /** A database as the catalog of Geoportal left it: the kernel and the schema ext_catalog. */
    @BeforeEach
    void installationWithTheOldCatalog() throws Exception {
        sql("""
                drop schema if exists ext_catalog cascade;
                drop schema if exists ext_catalog_geodata cascade;
                drop schema if exists mk_kernel cascade;
                create schema mk_kernel;
                create function mk_kernel.current_organization() returns uuid language sql stable
                    as $$ select nullif(current_setting('mosaikit.organization', true), '')::uuid $$;
                create table mk_kernel.resource (id uuid primary key, extension_id varchar(255) not null,
                    type varchar(64) not null, ref_id varchar(255) not null, link varchar(500),
                    unique (extension_id, type, ref_id));
                create table mk_kernel.permission_grant (id uuid primary key, extension_id varchar(255) not null,
                    permission varchar(100) not null);
                create table mk_kernel.secret (id uuid primary key, extension_id varchar(255), name varchar(100));
                create table mk_kernel.quota_total (measure_id varchar(320) not null, period varchar(7) not null,
                    amount bigint not null);
                create table mk_kernel.service_identity (id uuid primary key, name varchar(100) not null,
                    apis varchar(2000) not null default '');
                create table mk_kernel.resource_grant (id uuid primary key, resource_id uuid not null
                    references mk_kernel.resource (id), level varchar(64) not null);
                create table mk_kernel.sharing_level (extension_id varchar(255) not null, type varchar(48) not null,
                    level varchar(64) not null, rank integer not null, primary key (extension_id, type, level));
                create view mk_kernel.resource_view as select * from mk_kernel.resource;
                """);
        // The catalog as it was: its migrations up to V11, the last one before GP-983.
        migrate("ext_catalog", "11");
        sql("""
                insert into ext_catalog.sample_load (organization_id, loaded_at, loaded) values ('%s', now(), 7);
                insert into mk_kernel.resource values
                    (gen_random_uuid(), '%s', 'dataset', '42', '/app/catalog?dataset=42'),
                    (gen_random_uuid(), '%s', 'service', '7', '/app/catalog'),
                    (gen_random_uuid(), 'dev.geoportal.maps', 'map', '1', '/app/maps?map=1'),
                    (gen_random_uuid(), 'io.github.mosaikit.ext.catalog', 'folder', '1', '/app/catalog?folder=1');
                insert into mk_kernel.resource_grant select gen_random_uuid(), id, 'download' from mk_kernel.resource
                    where type = 'dataset';
                insert into mk_kernel.resource_grant select gen_random_uuid(), id, 'view' from mk_kernel.resource
                    where type = 'service';
                insert into mk_kernel.sharing_level values ('%s', 'dataset', 'view', 0), ('%s', 'dataset', 'download', 1);
                insert into mk_kernel.permission_grant values (gen_random_uuid(), '%s', 'upload-data'),
                    (gen_random_uuid(), 'dev.geoportal.maps', 'edit-maps');
                insert into mk_kernel.secret values (gen_random_uuid(), '%s', 'geoserver'), (gen_random_uuid(), null, 'mail');
                insert into mk_kernel.quota_total values ('%s/data-requests', '2026-10', 12), ('mosaikit/storage', '', 5);
                insert into mk_kernel.service_identity values (gen_random_uuid(), 'gateway', 'catalog'),
                    (gen_random_uuid(), 'other', 'maps, catalog,dashboards'), (gen_random_uuid(), 'files', 'catalogs');
                """.formatted(ORG, OLD, OLD, OLD, OLD, OLD, OLD, OLD));
    }

    @Test
    void theDataOfTheCatalogBecomeThoseOfGeodataAndTheKernelFindsEveryMigrationApplied() throws Exception {
        adopt();

        assertFalse(exists("select 1 from pg_namespace where nspname = 'ext_catalog'"), "ext_catalog is free");
        assertEquals("7", value("select loaded from ext_catalog_geodata.sample_load where organization_id = '" + ORG + "'"));
        MigrateResult again = migrate("ext_catalog_geodata", null);
        // Those of the catalog are applied: only the migrations after V11, of ext-catalog-geodata, run.
        long own;
        int last;
        try (var names = Files.list(Path.of("db"))) {
            List<Integer> versions = names.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("V\\d+__.*\\.sql"))
                    .map(n -> Integer.parseInt(n.substring(1, n.indexOf("__")))).toList();
            own = versions.stream().filter(v -> v > 11).count();
            last = versions.stream().mapToInt(Integer::intValue).max().orElseThrow();
        }
        assertEquals(own, again.migrationsExecuted,
                "the migrations of the catalog are those of ext-catalog-geodata, which adds only its own");
        assertEquals(String.valueOf(last), value("select max(version::int) from ext_catalog_geodata.flyway_schema_history"
                + " where version is not null and success"));

        assertEquals("2", value("select count(*) from mk_kernel.resource where extension_id = '" + NEW + "'"));
        assertEquals("0", value("select count(*) from mk_kernel.resource where extension_id = '" + OLD + "'"));
        assertEquals("/app/geodata?dataset=42", value("select link from mk_kernel.resource where ref_id = '42'"));
        assertEquals("/app/geodata", value("select link from mk_kernel.resource where type = 'service'"));
        assertEquals("/app/catalog?folder=1", value("select link from mk_kernel.resource where type = 'folder'"),
                "the file manager of mosaikit-ext keeps its links");
        assertEquals("/app/maps?map=1", value("select link from mk_kernel.resource where type = 'map'"));
        assertEquals(NEW, value("select extension_id from mk_kernel.permission_grant where permission = 'upload-data'"));
        assertEquals("dev.geoportal.maps", value("select extension_id from mk_kernel.permission_grant where permission = 'edit-maps'"));
        assertEquals(NEW, value("select extension_id from mk_kernel.secret where name = 'geoserver'"));
        assertEquals("12", value("select amount from mk_kernel.quota_total where measure_id = '" + NEW + "/data-requests'"));
        assertEquals("5", value("select amount from mk_kernel.quota_total where measure_id = 'mosaikit/storage'"));
        assertEquals("catalog-geodata", value("select apis from mk_kernel.service_identity where name = 'gateway'"));
        assertEquals("maps,catalog-geodata,dashboards", value("select apis from mk_kernel.service_identity where name = 'other'"));
        assertEquals("catalogs", value("select apis from mk_kernel.service_identity where name = 'files'"));
        // ADR-0028: the level download of datasets is read.
        assertEquals("read", value("select g.level from mk_kernel.resource_grant g join mk_kernel.resource r"
                + " on r.id = g.resource_id where r.type = 'dataset'"));
        assertEquals("view", value("select g.level from mk_kernel.resource_grant g join mk_kernel.resource r"
                + " on r.id = g.resource_id where r.type = 'service'"));
        assertEquals("read", value("select level from mk_kernel.sharing_level where rank = 1"));

        // Run again, it changes nothing.
        adopt();
        assertEquals("2", value("select count(*) from mk_kernel.resource where extension_id = '" + NEW + "'"));
        assertEquals("catalog-geodata", value("select apis from mk_kernel.service_identity where name = 'gateway'"));
        assertEquals("7", value("select loaded from ext_catalog_geodata.sample_load"));
    }

    @Test
    void withGeodataAlreadyStartedItRefusesAndChangesNothing() throws Exception {
        migrate("ext_catalog_geodata", null);

        SQLException refused = assertThrows(SQLException.class, this::adopt);
        assertTrue(refused.getMessage().contains("both ext_catalog"), refused.getMessage());
        assertEquals("7", value("select loaded from ext_catalog.sample_load"));
        assertEquals("2", value("select count(*) from mk_kernel.resource where extension_id = '" + OLD + "'"));
        assertEquals("catalog", value("select apis from mk_kernel.service_identity where name = 'gateway'"));
    }

    @Test
    void theFileManagerOfMosaikitExtAndANewInstallationAreLeftAlone() throws Exception {
        // The schema ext_catalog of the file manager: another history.
        sql("""
                drop schema ext_catalog cascade;
                create schema ext_catalog;
                create table ext_catalog.flyway_schema_history (installed_rank int primary key, version varchar(50),
                    description varchar(200) not null, success boolean not null);
                insert into ext_catalog.flyway_schema_history values (1, '1', 'item', true);
                create table ext_catalog.item (id uuid primary key);
                """);
        adopt();
        assertTrue(exists("select 1 from pg_namespace where nspname = 'ext_catalog'"));
        assertFalse(exists("select 1 from pg_namespace where nspname = 'ext_catalog_geodata'"));
        assertEquals("2", value("select count(*) from mk_kernel.resource where extension_id = '" + OLD + "'"));

        // A new installation: no schema of the catalog, no kernel yet.
        sql("drop schema ext_catalog cascade; drop schema mk_kernel cascade;");
        adopt();
        assertFalse(exists("select 1 from pg_namespace where nspname = 'ext_catalog_geodata'"));
    }

    private MigrateResult migrate(String schema, String target) {
        var flyway = Flyway.configure()
                .dataSource(source)
                .schemas(schema)
                .createSchemas(true)
                .locations("filesystem:" + Path.of("db").toAbsolutePath());
        if (target != null) {
            flyway.target(target);
        }
        return flyway.load().migrate();
    }

    private void adopt() throws Exception {
        sql(Files.readString(Path.of("upgrade", "adopt-catalog.sql")));
    }

    private static void sql(String statements) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(statements);
        }
    }

    private static String value(String query) throws SQLException {
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(query)) {
            assertTrue(result.next(), query);
            return result.getString(1);
        }
    }

    private static boolean exists(String query) throws SQLException {
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(query)) {
            return result.next();
        }
    }
}
