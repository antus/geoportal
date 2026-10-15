// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Issue #40: the largest file is the smaller of the two limits of the kernel, read as Quarkus reads them. */
class UploadLimitTest {

    private static final long MB = 1024L * 1024;

    @Test
    void sizesAreReadInPowersOf1024AsTheKernelReadsThem() {
        assertEquals(1024L * MB, UploadLimit.parse("1G"));
        assertEquals(1024L * MB, UploadLimit.parse("1g"));
        assertEquals(512 * MB, UploadLimit.parse("512M"));
        assertEquals(10 * MB, UploadLimit.parse("10240K"));
        assertEquals(65_536, UploadLimit.parse(" 65536 "));
        assertEquals(2L * 1024 * 1024 * MB, UploadLimit.parse("2T"));
        assertEquals(UploadLimit.KERNEL_DEFAULT, UploadLimit.parse("lots"));
    }

    @Test
    void theSmallerLimitWinsAndAnUnsetOneIsTheDefaultOfTheKernel() {
        assertEquals(1024 * MB, UploadLimit.effective(Optional.of("1G"), Optional.of("1G")));
        assertEquals(512 * MB, UploadLimit.effective(Optional.of("1G"), Optional.of("512M")));
        assertEquals(512 * MB, UploadLimit.effective(Optional.of("512M"), Optional.of("1G")));
        // Raising only the files leaves the 10M body of the kernel: the issue this limit fixes.
        assertEquals(10 * MB, UploadLimit.effective(Optional.of("1G"), Optional.empty()));
        assertEquals(10 * MB, UploadLimit.effective(Optional.empty(), Optional.empty()));
    }

    @Test
    void theRefusalSaysTheLimitInEnglish() {
        UploadLimit.TooLarge refusal = new UploadLimit.TooLarge(1024 * MB);
        assertEquals(1024 * MB, refusal.limit());
        assertTrue(refusal.getMessage().startsWith("The file is larger than the 1 GB (1073741824 bytes)"),
                refusal.getMessage());
        assertEquals("64 KB", UploadLimit.readable(65_536));
        assertEquals("1500 bytes", UploadLimit.readable(1500));
    }
}
