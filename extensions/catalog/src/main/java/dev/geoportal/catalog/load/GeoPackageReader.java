// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.catalog.load;

import dev.geoportal.catalog.load.Features.Form;
import dev.geoportal.catalog.load.Features.Geometry;
import dev.geoportal.catalog.load.LoadRefused.Code;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GeoPackage: the table of features of the file, its geometry column (GeoPackage binary, WKB with a
 * header) and its reference system from {@code gpkg_spatial_ref_sys}, by its EPSG code or, for
 * another organization, by its WKT definition. One dataset is one table: a file with several tables
 * of features is refused with their names. The Importer of GeoServer took any GeoPackage for a raster
 * (prototypes/f01, proof 6).
 */
final class GeoPackageReader {

    private GeoPackageReader() {}

    static Features read(byte[] content) {
        return read(content, null);
    }

    /** @param chosen the table of features to load, among several; {@code null} when there is one */
    static Features read(byte[] content, String chosen) {
        Sqlite db;
        try {
            db = new Sqlite(content);
        } catch (IllegalArgumentException e) {
            throw new LoadRefused(Code.UNREADABLE, "The file is not a GeoPackage: " + e.getMessage() + ".");
        }
        try {
            return read(db, chosen);
        } catch (LoadRefused e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LoadRefused(Code.UNREADABLE, "The GeoPackage cannot be read: " + e.getMessage(), e);
        }
    }

    private static Features read(Sqlite db, String chosen) {
        List<Sqlite.Row> contents = db.rows(db.table("gpkg_contents"));
        Sqlite.Table contentsTable = db.table("gpkg_contents");
        int nameAt = contentsTable.columns().indexOf("table_name");
        int typeAt = contentsTable.columns().indexOf("data_type");
        List<String> layers = new ArrayList<>();
        boolean tiles = false;
        for (Sqlite.Row row : contents) {
            String type = String.valueOf(row.values()[typeAt]);
            if (type.equals("features")) {
                layers.add((String) row.values()[nameAt]);
            } else if (type.equals("tiles") || type.equals("2d-gridded-coverage")) {
                tiles = true;
            }
        }
        if (layers.isEmpty()) {
            throw new LoadRefused(tiles ? Code.UNREADABLE : Code.EMPTY, tiles
                    ? "The GeoPackage holds tiles, not features: rasters are loaded from GeoTIFF."
                    : "The GeoPackage holds no table of features.");
        }
        String layer;
        if (chosen != null) {
            layer = layers.stream().filter(chosen::equals).findFirst().orElseThrow(() -> new LoadRefused(Code.SEVERAL_LAYERS,
                    "The GeoPackage has no table of features " + chosen + ".", layers));
        } else if (layers.size() > 1) {
            throw new LoadRefused(Code.SEVERAL_LAYERS, "The GeoPackage holds " + layers.size() + " tables of features ("
                    + String.join(", ", layers) + "): choose the one this dataset loads.", layers);
        } else {
            layer = layers.get(0);
        }

        Sqlite.Table geometryColumns = db.table("gpkg_geometry_columns");
        String column = null;
        long srsId = 0;
        for (Sqlite.Row row : db.rows(geometryColumns)) {
            if (layer.equalsIgnoreCase(String.valueOf(row.values()[geometryColumns.columns().indexOf("table_name")]))) {
                column = (String) row.values()[geometryColumns.columns().indexOf("column_name")];
                srsId = ((Number) row.values()[geometryColumns.columns().indexOf("srs_id")]).longValue();
            }
        }
        if (column == null) {
            throw new LoadRefused(Code.UNREADABLE, "The GeoPackage does not say which column of " + layer + " is the geometry.");
        }

        Features features = reference(db, srsId);
        Sqlite.Table table = db.table(layer);
        int geometryAt = indexOf(table.columns(), column);
        for (int c = 0; c < table.columns().size(); c++) {
            if (c != geometryAt) {
                features.attribute(table.columns().get(c), type(table.types().get(c)));
            }
        }
        for (Sqlite.Row row : db.rows(table)) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            for (int c = 0; c < table.columns().size(); c++) {
                if (c != geometryAt) {
                    attributes.put(table.columns().get(c), value(row.values()[c], table.types().get(c)));
                }
            }
            Object blob = row.values()[geometryAt];
            features.add(attributes, blob instanceof byte[] bytes ? geometry(bytes) : null);
        }
        return features;
    }

    /** The reference system of an srs_id: EPSG by code, another organization by its definition. */
    private static Features reference(Sqlite db, long srsId) {
        Sqlite.Table srs = db.table("gpkg_spatial_ref_sys");
        for (Sqlite.Row row : db.rows(srs)) {
            Object[] v = row.values();
            if (((Number) v[srs.columns().indexOf("srs_id")]).longValue() != srsId) {
                continue;
            }
            String organization = String.valueOf(v[srs.columns().indexOf("organization")]).toUpperCase(Locale.ROOT);
            long code = ((Number) v[srs.columns().indexOf("organization_coordsys_id")]).longValue();
            if (organization.equals("EPSG") && code > 0) {
                return new Features(Crs.file((int) code));
            }
            Object definition = v[srs.columns().indexOf("definition")];
            if (srsId <= 0 || definition == null || "undefined".equalsIgnoreCase(String.valueOf(definition).strip())) {
                return new Features(null); // undefined Cartesian or geographic: the file states no system
            }
            return new Features(null).prj(String.valueOf(definition));
        }
        throw new LoadRefused(Code.CRS_UNKNOWN, "The GeoPackage names the reference system " + srsId
                + ", which its gpkg_spatial_ref_sys does not define.");
    }

    private static int indexOf(List<String> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        throw new LoadRefused(Code.UNREADABLE, "The table of the GeoPackage has no column " + name + ".");
    }

    private static Column.Type type(String declared) {
        String t = declared.toUpperCase(Locale.ROOT);
        if (t.equals("BOOLEAN")) {
            return Column.Type.BOOLEAN;
        }
        if (t.contains("INT")) {
            return Column.Type.BIGINT;
        }
        if (t.equals("REAL") || t.equals("DOUBLE") || t.equals("FLOAT")) {
            return Column.Type.DOUBLE;
        }
        if (t.equals("DATE")) {
            return Column.Type.DATE;
        }
        return null; // TEXT, DATETIME, BLOB, ...: from the values
    }

    private static Object value(Object value, String declared) {
        String t = declared.toUpperCase(Locale.ROOT);
        if (value instanceof Long l && t.equals("BOOLEAN")) {
            return l != 0;
        }
        if (value instanceof String s && t.equals("DATE")) {
            try {
                return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
            } catch (RuntimeException e) {
                return s;
            }
        }
        if (value instanceof byte[]) {
            return null; // binary attributes are not loaded
        }
        return value;
    }

    /** The WKB inside a GeoPackage binary geometry, {@code null} for an empty one. */
    static Geometry geometry(byte[] blob) {
        if (blob.length < 8 || blob[0] != 'G' || blob[1] != 'P') {
            throw new LoadRefused(Code.GEOMETRY_INVALID, "A geometry of the GeoPackage is not GeoPackage binary.");
        }
        int flags = blob[3] & 0xFF;
        if ((flags & 0x20) != 0) {
            throw new LoadRefused(Code.GEOMETRY_INVALID, "A geometry of the GeoPackage is of an extended type the catalog does not read.");
        }
        if ((flags & 0x10) != 0) {
            return null;
        }
        int envelope = switch ((flags >> 1) & 0x07) {
            case 0 -> 0;
            case 1 -> 32;
            case 2, 3 -> 48;
            case 4 -> 64;
            default -> throw new LoadRefused(Code.GEOMETRY_INVALID, "A geometry of the GeoPackage has an invalid envelope.");
        };
        int start = 8 + envelope;
        byte[] wkb = new byte[blob.length - start];
        System.arraycopy(blob, start, wkb, 0, wkb.length);
        return Geometry.of(Form.WKB, wkb);
    }
}
