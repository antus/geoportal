// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.upload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mosaikit.ext.catalog.api.v1.FileSample;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * GP-983: which files of the catalog are geodata (the kind {@code dataset}, EXT-105), from their name, their
 * media type and their first bytes, as the catalog asks while a person stores a file.
 */
@Tag("GP-983")
class GeodataFileKindTest {

    private final GeodataFileKind kind = new GeodataFileKind(null, null, null, null, null);

    @Test
    void theKindIsDatasetOfTheGeodata() {
        assertEquals("dev.geoportal.ext.catalog.geodata", kind.extension());
        assertEquals("dataset", kind.name());
    }

    @Test
    void theSampleFilesOfEveryFormatAreGeodata() throws IOException {
        for (String name : new String[] {"poi-esempio.geojson", "poi-esempio-shp.zip", "poi-esempio.gpkg", "poi-esempio.gml",
            "poi-esempio.gpx", "poi-esempio.csv", "poi-esempio.kml", "poi-esempio.kmz", "poi-esempio.czml",
            "rilievo-esempio.tif", "csv-wkt-point.csv", "csv-geojson-col.csv", "csv-bom-crlf.csv"}) {
            assertTrue(kind.recognizes(sample(name, sampleBytes(name))), name);
        }
    }

    @Test
    void filesThatOnlyShareAnExtensionWithGeodataAreNot() throws IOException {
        assertFalse(kind.recognizes(sample("report.pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII))));
        assertFalse(kind.recognizes(sample("notes.txt", "Remember the meeting\n".getBytes(StandardCharsets.UTF_8))));
        assertFalse(kind.recognizes(sample("budget.csv", "voce;importo\naffitto;1200\n".getBytes(StandardCharsets.UTF_8))));
        assertFalse(kind.recognizes(sample("settings.json", "{\"theme\": \"dark\", \"size\": 3}".getBytes(StandardCharsets.UTF_8))));
        assertFalse(kind.recognizes(sample("photos.zip", zip("holiday.jpg"))));
        assertFalse(kind.recognizes(sample("scan.tif", "not a tiff".getBytes(StandardCharsets.US_ASCII))));
        assertFalse(kind.recognizes(sample("fake.gpkg", "hello".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void aJsonThatIsGeojsonAndAZipOfAShapefileAre() throws IOException {
        assertTrue(kind.recognizes(sample("punti.json",
                "{ \"type\": \"FeatureCollection\", \"features\": [] }".getBytes(StandardCharsets.UTF_8))));
        assertTrue(kind.recognizes(sample("strade.zip", zip("strade.shp"))));
        assertTrue(kind.recognizes(sample("strade.zip", zip("strade.dbf"))));
        assertTrue(kind.recognizes(sample("stazioni.csv", "id,nome,lat,lon\n1,A,42.1,12.5\n".getBytes(StandardCharsets.UTF_8))));
        assertTrue(kind.recognizes(sample("punti.csv", "id;x;y\n1;500000;4600000\n".getBytes(StandardCharsets.UTF_8))));
    }

    private static FileSample sample(String name, byte[] content) {
        return new FileSample(name, "application/octet-stream", content.length, content);
    }

    private static byte[] sampleBytes(String name) throws IOException {
        try (InputStream in = GeodataFileKindTest.class.getResourceAsStream("/samples/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Missing sample " + name);
            }
            return in.readNBytes(FileSample.HEAD);
        }
    }

    private static byte[] zip(String entry) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(new byte[64]);
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
