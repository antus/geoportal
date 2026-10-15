// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.upload;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * The largest file the catalog accepts (issue #40): the smaller of the two limits of the kernel, the
 * largest file it stores ({@code mosaikit.files.max-size}) and the largest request body it reads
 * ({@code quarkus.http.limits.max-body-size}), both 10 MB unless the installation sets them. The
 * distributions of Geoportal set both to the same size (1G), and the gateway of the server stack
 * refuses larger bodies before they reach the kernel.
 */
public final class UploadLimit {

    /** The setting of the kernel for the largest file. */
    public static final String FILES_MAX_SIZE = "mosaikit.files.max-size";
    /** The setting of the kernel for the largest request body. */
    public static final String HTTP_MAX_BODY_SIZE = "quarkus.http.limits.max-body-size";
    /** The defaults of the kernel: 10M for files and 10240K (the same) for bodies. */
    static final long KERNEL_DEFAULT = 10L * 1024 * 1024;

    // As Quarkus reads a MemorySize: a number and an optional unit, in powers of 1024.
    private static final Pattern SIZE = Pattern.compile("^(\\d+)([bkmgtpezy]?)$");
    private static final String UNITS = "bkmgtpezy";

    private UploadLimit() {}

    /** The largest file the catalog accepts in this installation, in bytes. */
    public static long bytes() {
        return of(ConfigProvider.getConfig());
    }

    static long of(Config config) {
        return effective(config.getOptionalValue(FILES_MAX_SIZE, String.class),
                config.getOptionalValue(HTTP_MAX_BODY_SIZE, String.class));
    }

    /** The smaller of the two settings, each at the default of the kernel when unset or unreadable. */
    static long effective(Optional<String> files, Optional<String> body) {
        return Math.min(files.map(UploadLimit::parse).orElse(KERNEL_DEFAULT),
                body.map(UploadLimit::parse).orElse(KERNEL_DEFAULT));
    }

    /** A memory size such as {@code 1G}, {@code 512M} or {@code 10240K}, in bytes. */
    static long parse(String value) {
        Matcher m = SIZE.matcher(value.strip().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return KERNEL_DEFAULT;
        }
        long size = Long.parseLong(m.group(1));
        int power = m.group(2).isEmpty() ? 0 : UNITS.indexOf(m.group(2).charAt(0));
        for (int i = 0; i < power; i++) {
            if (size > Long.MAX_VALUE / 1024) {
                return Long.MAX_VALUE;
            }
            size *= 1024;
        }
        return size;
    }

    /** The size for people and messages: {@code 1 GB}, {@code 512 MB}, {@code 1536 KB}. */
    static String readable(long bytes) {
        String[] units = {"bytes", "KB", "MB", "GB", "TB"};
        int unit = 0;
        long value = bytes;
        while (unit < units.length - 1 && value >= 1024 && value % 1024 == 0) {
            value /= 1024;
            unit++;
        }
        return value + " " + units[unit];
    }

    /** A file larger than the catalog accepts. */
    public static final class TooLarge extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final long limit;

        public TooLarge(long limit) {
            super("The file is larger than the " + readable(limit) + " (" + limit
                    + " bytes) this installation accepts.");
            this.limit = limit;
        }

        public long limit() {
            return limit;
        }
    }
}
