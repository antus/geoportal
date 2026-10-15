// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Brings the Mosaikit snapshot of the local Maven repository up to the one published from the main
 * branch of Mosaikit (the pre-release "snapshot" of mosaikit/mosaikit, the same files the CI installs),
 * so that a build never packages an old kernel left in ~/.m2 by an earlier build.
 *
 * <pre>
 *   java distribution/tools/MosaikitSnapshot.java [local repository] [version]
 * </pre>
 *
 * Each file is downloaded only when the published one is newer than the local copy: a Mosaikit built
 * and installed locally after the last publication is kept. Without a network it only warns. The
 * distribution module runs it before unpacking the distributions of Mosaikit
 * (-Dmosaikit.snapshot.skip=true skips it).
 */
public class MosaikitSnapshot {

    private static final String PUBLISHED = "https://github.com/mosaikit/mosaikit/releases/download/snapshot/";

    /** A published file and where Maven keeps it. */
    record Item(String published, String artifact, String file, boolean api) {}

    public static void main(String[] args) throws Exception {
        Path repository = Path.of(args.length > 0 ? args[0] : System.getProperty("user.home") + "/.m2/repository");
        String version = args.length > 1 ? args[1] : "0.1.0-SNAPSHOT";
        if (!version.endsWith("-SNAPSHOT")) {
            System.out.println("Mosaikit " + version + " is a release: nothing to refresh");
            return;
        }
        List<Item> items = new ArrayList<>();
        items.add(new Item("mosaikit-parent-%s.pom", "mosaikit-parent", "mosaikit-parent-%s.pom", true));
        items.add(new Item("mosaikit-kernel-api-%s.pom", "mosaikit-kernel-api", "mosaikit-kernel-api-%s.pom", true));
        items.add(new Item("mosaikit-kernel-api-%s.jar", "mosaikit-kernel-api", "mosaikit-kernel-api-%s.jar", true));
        items.add(new Item("mosaikit-extension-parent-%s.pom", "mosaikit-extension-parent",
                "mosaikit-extension-parent-%s.pom", true));
        items.add(new Item("mosaikit-distributions-%s.pom", "mosaikit-distributions", "mosaikit-distributions-%s.pom", false));
        for (String classifier : List.of("installation", "portable-tools", "docker", "helm")) {
            items.add(new Item("mosaikit-" + classifier + "-%s.zip", "mosaikit-distributions",
                    "mosaikit-distributions-%s-" + classifier + ".zip", false));
        }

        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        int refreshed = 0;
        boolean api = false;
        for (Item item : items) {
            String name = item.published().formatted(version);
            Path local = repository.resolve("io/github/mosaikit").resolve(item.artifact()).resolve(version)
                    .resolve(item.file().formatted(version));
            Instant published;
            try {
                published = lastModified(http, PUBLISHED + name);
            } catch (IOException | InterruptedException e) {
                System.out.println("WARNING: the published Mosaikit snapshot cannot be reached (" + e.getMessage()
                        + "): the build uses the one in " + repository + ", which may be old");
                return;
            }
            if (Files.isRegularFile(local) && !Files.getLastModifiedTime(local).toInstant().isBefore(published)) {
                continue;
            }
            try {
                download(http, PUBLISHED + name, local);
            } catch (IOException | InterruptedException e) {
                System.out.println("WARNING: " + name + " of the published Mosaikit snapshot could not be downloaded ("
                        + e.getMessage() + "): the build uses the one in " + repository + ", which may be old");
                return;
            }
            System.out.println("Mosaikit snapshot: " + local.getFileName() + " of " + published);
            refreshed++;
            api |= item.api();
        }
        if (refreshed == 0) {
            System.out.println("Mosaikit snapshot " + version + ": the local one is up to date");
        } else if (api) {
            System.out.println("WARNING: the extension API of Mosaikit was refreshed now; the extensions built before"
                    + " this module used the previous one: build again (mvn install) to build them on the new one");
        }
    }

    private static Instant lastModified(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<Void> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) {
            throw new IOException(url + " answered " + response.statusCode());
        }
        String header = response.headers().firstValue("last-modified")
                .orElseThrow(() -> new IOException(url + " has no Last-Modified"));
        return ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    }

    private static void download(HttpClient http, String url, Path target) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<InputStream> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException(url + " answered " + response.statusCode());
        }
        try (InputStream in = response.body()) {
            Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
