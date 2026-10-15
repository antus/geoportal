// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.geoportal.ext.catalog.geodata.upload.Format;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * GP-324: the system the format of a CSV defines, which the catalog tells the loader sidecar from the
 * first line of the file, is the one the readers of the catalog give the same file: WGS84 for columns of
 * latitude and longitude and for a GeoJSON column (RFC 7946), none for WKT or x and y.
 */
@Tag("GP-324")
class FormatCrsTest {

    @ParameterizedTest(name = "GP-324 a CSV headed «{0}» is in WGS84 by its format: {1}")
    @CsvSource(delimiter = '|', value = {
        "nome,geojson | true",
        "nome;geojson | true",
        "nome,lat,lon | true",
        "nome;latitudine;longitudine | true",
        "nome,lat,lon,geojson | true",
        "nome,x,y | false",
        "nome,x,y,geojson | false",
        "nome,wkt | false",
        "nome,wkt,geojson | false",
        "nome,lat,lon,wkt | false",
        "nome,citta | false",
    })
    void csvHeader(String header, boolean wgs84) {
        assertEquals(wgs84 ? Crs.WGS84 : null, Loader.formatCrs(Format.CSV, header), header);
    }

    @Test
    void gp324TheSampleWithAGeoJsonColumnIsInWgs84ForTheLoaderAsForTheReaders() {
        String csv = "nome,geojson\r\nValverde,\"{\"\"type\"\":\"\"Point\"\",\"\"coordinates\"\":[12.381245,42.105372]}\"\r\n";
        assertEquals(Crs.WGS84, Loader.formatCrs(Format.CSV, "nome,geojson"));
        Features features = CsvReader.read(csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(Crs.format(), features.crs());
    }

    @Test
    void gp324OnlyACsvHasACrsFromItsColumns() {
        assertNull(Loader.formatCrs(Format.GEOJSON, "nome,geojson"));
        assertNull(Loader.formatCrs(Format.CSV, null));
    }
}
