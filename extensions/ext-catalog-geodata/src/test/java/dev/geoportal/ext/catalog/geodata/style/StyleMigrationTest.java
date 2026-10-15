// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.geoportal.ext.catalog.geodata.geoserver.Sld;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * GP-009: the migration V14 turns the styles the catalog kept before the grammar into documents of
 * geoportal/style/v1, on a PostgreSQL in Docker with the migrations of the extension run by Flyway as
 * the kernel runs them. Nothing is lost: each document is the one the catalog makes of the same style,
 * and draws the same; what is not such a style stays as it was.
 */
@Tag("postgis")
@Tag("GP-009")
class StyleMigrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000009");

    private static GenericContainer<?> postgres;
    private static PGSimpleDataSource source;

    @BeforeAll
    @SuppressWarnings("resource")
    static void start() throws SQLException {
        // GEOPORTAL_TEST_POSTGRES: a database of one's own (jdbc URL; user mosaikit, no password), instead of Docker.
        String given = System.getenv("GEOPORTAL_TEST_POSTGRES");
        source = new PGSimpleDataSource();
        if (given != null && !given.isBlank()) {
            source.setURL(given);
            source.setUser("mosaikit");
        } else {
            postgres = new GenericContainer<>(DockerImageName.parse("postgis/postgis:18-3.6"))
                    .withEnv("POSTGRES_USER", "mosaikit")
                    .withEnv("POSTGRES_PASSWORD", "gp009-test")
                    .withEnv("POSTGRES_DB", "mosaikit")
                    .withExposedPorts(5432)
                    .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
                            .withStartupTimeout(Duration.ofMinutes(2)));
            postgres.start();
            source.setURL("jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/mosaikit");
            source.setUser("mosaikit");
            source.setPassword("gp009-test");
        }
        // The kernel as far as the migrations of the extension read it.
        sql("""
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
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void theStylesKeptBeforeTheGrammarBecomeItsDocumentsAndNothingElseChanges() throws Exception {
        migrate("13");
        List<LegacyStyle> legacy = List.of(LegacyStyle.DEFAULT, new LegacyStyle("#ff0000", 1, "#000000", 1, 12, "square"),
                new LegacyStyle("#00ff00", 0.333, "#0000ff", 0, 64, "x"));
        for (int i = 0; i < legacy.size(); i++) {
            // As DatasetPublishing wrote them with Jackson before GP-009.
            style("legacy_" + i, JSON.writeValueAsString(legacy.get(i)));
        }
        String document = example();
        style("document", document);
        style("not_json", "{not json");
        style("other", "{\"fill\":\"#ff0000\"}");
        style("sld_only", null);

        migrate(null);

        for (int i = 0; i < legacy.size(); i++) {
            String migrated = source("legacy_" + i);
            StyleDocument doc = StyleDocument.parse(migrated);
            assertTrue(JSON.readTree(migrated).has("grammar"), migrated);
            assertEquals(StyleDocument.fromLegacy(legacy.get(i)), doc);
            for (String g : List.of("Point", "MultiLineString", "MultiPolygon", "Geometry")) {
                assertTrue(Sld.same(new LegacySld(legacy.get(i)).sld("s", g), doc.sld("s", g)), legacy.get(i) + " " + g);
            }
        }
        assertEquals(JSON.readTree(document), JSON.readTree(source("document")));
        assertEquals("{not json", source("not_json"));
        assertEquals("{\"fill\":\"#ff0000\"}", source("other"));
        assertNull(source("sld_only"));
    }

    private static String example() throws Exception {
        return JSON.writeValueAsString(JSON.readTree(Path.of("..", "..", "docs", "style", "examples",
                "point-graduated-scaled.json").toFile()));
    }

    private static void migrate(String target) {
        var flyway = Flyway.configure()
                .dataSource(source)
                .schemas("ext_catalog_geodata")
                .createSchemas(true)
                .locations("filesystem:" + Path.of("db").toAbsolutePath());
        if (target != null) {
            flyway.target(target);
        }
        flyway.load().migrate();
    }

    private static void style(String name, String json) throws SQLException {
        try (Connection c = source.getConnection(); PreparedStatement p = c.prepareStatement(
                "insert into ext_catalog_geodata.geoserver_style (id, organization_id, name, sld, retired, updated_at, source)"
                        + " values (?, ?, ?, '<sld/>', false, now(), ?)")) {
            p.setObject(1, UUID.randomUUID());
            p.setObject(2, ORG);
            p.setString(3, name);
            p.setString(4, json);
            p.executeUpdate();
        }
    }

    private static String source(String name) throws SQLException {
        try (Connection c = source.getConnection(); PreparedStatement p = c.prepareStatement(
                "select source from ext_catalog_geodata.geoserver_style where name = ?")) {
            p.setString(1, name);
            try (ResultSet r = p.executeQuery()) {
                assertTrue(r.next(), name);
                return r.getString(1);
            }
        }
    }

    private static void sql(String statements) throws SQLException {
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(statements);
        }
    }
}
