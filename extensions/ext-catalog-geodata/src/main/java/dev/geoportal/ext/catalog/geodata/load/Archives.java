// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.load;

import dev.geoportal.ext.catalog.geodata.load.LoadRefused.Code;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The files of a zip archive (Shapefile, KMZ), in memory, with limits against archives that explode. */
final class Archives {

    /** At most this many bytes once uncompressed, and this many entries. */
    static final long MAX_BYTES = 1L << 30;
    static final int MAX_ENTRIES = 1000;

    private Archives() {}

    static Map<String, byte[]> unzip(byte[] archive) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().contains("__MACOSX/")) {
                    continue;
                }
                if (files.size() >= MAX_ENTRIES) {
                    throw new LoadRefused(Code.UNREADABLE, "The archive holds more than " + MAX_ENTRIES + " files.");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new LoadRefused(Code.UNREADABLE, "The archive is larger than 1 GB once uncompressed.");
                    }
                    out.write(buffer, 0, n);
                }
                files.put(entry.getName(), out.toByteArray());
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new LoadRefused(Code.UNREADABLE, "The file is not a valid zip archive: " + e.getMessage());
        }
        if (files.isEmpty()) {
            throw new LoadRefused(Code.UNREADABLE, "The file is not a zip archive, or it is empty.");
        }
        return files;
    }

    /** The KML of a KMZ: doc.kml, else the first .kml at the top, else the first .kml. */
    static byte[] kml(byte[] kmz) {
        Map<String, byte[]> files = unzip(kmz);
        byte[] first = null;
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            String name = e.getKey().toLowerCase(Locale.ROOT);
            if (name.equals("doc.kml")) {
                return e.getValue();
            }
            if (name.endsWith(".kml") && (first == null || !name.contains("/"))) {
                first = first == null || !name.contains("/") ? e.getValue() : first;
            }
        }
        if (first == null) {
            throw new LoadRefused(Code.UNREADABLE, "The KMZ archive holds no .kml file.");
        }
        return first;
    }
}
