// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-983, ADR-0027: the migrations already applied by installations stay byte for byte as they were
 * applied, comments and header lines included. The kernel runs them with Flyway, which keeps a checksum
 * of each file and refuses to start an installation whose applied migration changed; the adoption of the
 * old catalog relies on the same checksums. A change goes in a new migration; a licence goes in
 * REUSE.toml for these files.
 */
@Tag("GP-983")
class MigrationsUnchangedTest {

    /** The SHA-256 of each applied migration, with its line ends as LF. */
    private static final Map<String, String> APPLIED = new LinkedHashMap<>();

    static {
        APPLIED.put("V1__create_dataset.sql", "79dda63822185f6e336e0ac9392cf46db7bc858c643093b5d907eb838c25becb");
        APPLIED.put("V2__uploaded_data.sql", "3b21389864fc594b8445f52f35c9bbe02a27bc32243b15d8c5fb09e75277dbde");
        APPLIED.put("V3__geoserver_publication.sql", "2e78b9f6bb52f71f8954fc5bca6f3864bc8116b54e68638d2b1c6618e4c84356");
        APPLIED.put("V4__create_external_service.sql", "a871b91f2cd932e3910636a1c5d44e203a5d127cdb7492cf6f75bc52b4450a54");
        APPLIED.put("V5__loading.sql", "96b8b144ddbdef6a688fcde1824cc8c3f1c3c256ad634051141b6e7bbf4a2390");
        APPLIED.put("V6__loader.sql", "5615d398a635c5be39362e1e3aac3ab6bdb7661ed4653962eabfce0ff9bfa484");
        APPLIED.put("V7__publishing.sql", "7fe247281ccc2ed4f7a16d1807128dcaa00bc4369d5af2e733f7ecdc47697d2e");
        APPLIED.put("V8__geofence_rules.sql", "b066bb415cf9ed873d57bb9c9841938ca3d8747d5fce4e08c7b051dcd504940f");
        APPLIED.put("V9__data_rules.sql", "cd896ef19fcb12f6efa20a349332adb69e786e18ce8621e77e5ce2181e5314bd");
        APPLIED.put("V10__layer_assignments.sql", "3a8ab21285bd085a4feb493d6c56a0ff8fa4a6d44cdb501850d4e7588eeb084b");
        APPLIED.put("V11__rasters_and_service_rules.sql", "bfcde18184d3799ee8a7a02ddb6af9b81b609cba38d9bdcc797978c4b7cafe10");
        APPLIED.put("V12__catalog_files.sql", "a2ed1db8c42b160ac0d9adfe8d2b24131b73b9a5d8d335c984c23376667e9ea8");
        APPLIED.put("V13__catalog_files_without_copy.sql", "58d85bf7d1677a642f0766fab4ed9cc474f9e112843e22acdc3e7a0912b0dbc6");
    }

    @Test
    void theAppliedMigrationsKeepTheirBytes() throws IOException {
        for (Map.Entry<String, String> applied : APPLIED.entrySet()) {
            Path file = Path.of("db", applied.getKey());
            assertTrue(Files.exists(file), applied.getKey() + " is gone");
            String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            assertEquals(applied.getValue(), sha256(text), applied.getKey()
                    + " changed after installations applied it: put the change in a new migration");
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
