// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.load;

import dev.geoportal.ext.catalog.geodata.load.Features.Form;
import dev.geoportal.ext.catalog.geodata.load.Features.Geometry;
import dev.geoportal.ext.catalog.geodata.load.LoadRefused.Code;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A zipped Shapefile: the .shp with its geometries (points, lines, polygons, multipoints, with Z or
 * M), the .dbf with its attributes (in the encoding of the .cpg, else UTF-8 or Windows-1252), and the
 * .prj with its reference system, which {@link Prj} identifies. Polygons are assembled from their
 * rings: clockwise rings are shells, the others holes of the shell that contains them.
 */
final class ShapefileReader {

    private ShapefileReader() {}

    static Features read(Map<String, byte[]> files) {
        return read(files, null);
    }

    /** @param chosen the name of the Shapefile to load, without .shp, among several; {@code null} when there is one */
    static Features read(Map<String, byte[]> files, String chosen) {
        java.util.List<String> bases = new java.util.ArrayList<>();
        for (String name : files.keySet()) {
            if (name.toLowerCase(Locale.ROOT).endsWith(".shp")) {
                bases.add(name.substring(0, name.length() - 4));
            }
        }
        String base = null;
        if (chosen != null) {
            base = bases.stream().filter(chosen::equals).findFirst().orElseThrow(() -> new LoadRefused(Code.SEVERAL_LAYERS,
                    "The archive has no Shapefile " + chosen + ".", bases));
        } else if (bases.size() > 1) {
            throw new LoadRefused(Code.SEVERAL_LAYERS, "The archive holds " + bases.size() + " Shapefiles ("
                    + String.join(", ", bases) + "): choose the one this dataset loads.", bases);
        } else if (!bases.isEmpty()) {
            base = bases.get(0);
        }
        if (base == null) {
            throw new LoadRefused(Code.UNREADABLE, "The archive holds no .shp file.");
        }
        byte[] shp = files.get(base + ".shp");
        byte[] dbf = find(files, base, ".dbf");
        byte[] prj = find(files, base, ".prj");
        byte[] cpg = find(files, base, ".cpg");
        Features features = new Features(null).prj(prj == null ? null : new String(prj, StandardCharsets.ISO_8859_1).strip());
        List<Geometry> geometries = geometries(shp);
        List<Map<String, Object>> rows = dbf == null ? List.of() : attributes(dbf, charset(cpg), features);
        for (int i = 0; i < geometries.size(); i++) {
            features.add(i < rows.size() ? rows.get(i) : new LinkedHashMap<>(), geometries.get(i));
        }
        return features;
    }

    private static byte[] find(Map<String, byte[]> files, String base, String extension) {
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (e.getKey().equalsIgnoreCase(base + extension)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static Charset charset(byte[] cpg) {
        if (cpg == null) {
            return null;
        }
        String name = new String(cpg, StandardCharsets.US_ASCII).strip();
        if (name.matches("\\d{3,4}")) {
            name = "windows-" + name;
        }
        try {
            return Charset.forName(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // .shp

    static List<Geometry> geometries(byte[] shp) {
        if (shp.length < 100 || ByteBuffer.wrap(shp).order(ByteOrder.BIG_ENDIAN).getInt(0) != 9994) {
            throw new LoadRefused(Code.UNREADABLE, "The .shp file is not a Shapefile.");
        }
        ByteBuffer b = ByteBuffer.wrap(shp).order(ByteOrder.LITTLE_ENDIAN);
        List<Geometry> out = new ArrayList<>();
        int at = 100;
        try {
            while (at + 8 <= shp.length) {
                int length = ByteBuffer.wrap(shp, at + 4, 4).order(ByteOrder.BIG_ENDIAN).getInt() * 2;
                int content = at + 8;
                out.add(shape(b, content, length));
                at = content + length;
            }
        } catch (IndexOutOfBoundsException e) {
            throw new LoadRefused(Code.UNREADABLE, "The .shp file is truncated.");
        }
        return out;
    }

    private static Geometry shape(ByteBuffer b, int at, int length) {
        int type = b.getInt(at);
        boolean z = type == 11 || type == 13 || type == 15 || type == 18;
        return switch (type) {
            case 0 -> null;
            case 1, 11, 21 -> {
                Wkb wkb = new Wkb(z);
                wkb.header(Wkb.POINT);
                wkb.point(b.getDouble(at + 4), b.getDouble(at + 12), z ? b.getDouble(at + 20) : 0);
                yield Geometry.of(Form.WKB, wkb.bytes());
            }
            case 8, 18, 28 -> {
                int n = b.getInt(at + 36);
                int xy = at + 40;
                int zs = xy + 16 * n + 16;
                Wkb wkb = new Wkb(z);
                wkb.header(Wkb.MULTIPOINT).count(n);
                for (int i = 0; i < n; i++) {
                    wkb.header(Wkb.POINT);
                    wkb.point(b.getDouble(xy + 16 * i), b.getDouble(xy + 16 * i + 8), z ? b.getDouble(zs + 8 * i) : 0);
                }
                yield Geometry.of(Form.WKB, wkb.bytes());
            }
            case 3, 13, 23, 5, 15, 25 -> {
                int parts = b.getInt(at + 36);
                int points = b.getInt(at + 40);
                int partIndex = at + 44;
                int xy = partIndex + 4 * parts;
                int zs = xy + 16 * points + 16;
                List<double[][]> rings = new ArrayList<>();
                for (int p = 0; p < parts; p++) {
                    int start = b.getInt(partIndex + 4 * p);
                    int end = p + 1 < parts ? b.getInt(partIndex + 4 * (p + 1)) : points;
                    double[][] ring = new double[end - start][];
                    for (int i = start; i < end; i++) {
                        ring[i - start] = new double[] {b.getDouble(xy + 16 * i), b.getDouble(xy + 16 * i + 8),
                            z ? b.getDouble(zs + 8 * i) : 0};
                    }
                    rings.add(ring);
                }
                boolean polygon = type == 5 || type == 15 || type == 25;
                yield Geometry.of(Form.WKB, polygon ? polygons(rings, z) : lines(rings, z));
            }
            default -> throw new LoadRefused(Code.UNREADABLE, "The Shapefile has shapes of type " + type
                    + " (MultiPatch), which the catalog does not load.");
        };
    }

    private static byte[] lines(List<double[][]> parts, boolean z) {
        Wkb wkb = new Wkb(z);
        wkb.header(Wkb.MULTILINESTRING).count(parts.size());
        for (double[][] part : parts) {
            wkb.header(Wkb.LINESTRING).count(part.length);
            for (double[] p : part) {
                wkb.point(p[0], p[1], p[2]);
            }
        }
        return wkb.bytes();
    }

    private static byte[] polygons(List<double[][]> rings, boolean z) {
        List<List<double[][]>> polygons = new ArrayList<>();
        List<double[][]> holes = new ArrayList<>();
        for (double[][] ring : rings) {
            if (area(ring) <= 0) {
                List<double[][]> polygon = new ArrayList<>();
                polygon.add(ring);
                polygons.add(polygon);
            } else {
                holes.add(ring);
            }
        }
        for (double[][] hole : holes) {
            List<double[][]> owner = null;
            for (List<double[][]> polygon : polygons) {
                if (inside(hole[0], polygon.get(0))) {
                    owner = polygon;
                    break;
                }
            }
            if (owner == null) {
                // A ring counter-clockwise outside every shell: a shell written the other way round.
                List<double[][]> polygon = new ArrayList<>();
                polygon.add(hole);
                polygons.add(polygon);
            } else {
                owner.add(hole);
            }
        }
        Wkb wkb = new Wkb(z);
        wkb.header(Wkb.MULTIPOLYGON).count(polygons.size());
        for (List<double[][]> polygon : polygons) {
            wkb.header(Wkb.POLYGON).count(polygon.size());
            for (double[][] ring : polygon) {
                wkb.count(ring.length);
                for (double[] p : ring) {
                    wkb.point(p[0], p[1], p[2]);
                }
            }
        }
        return wkb.bytes();
    }

    /** Twice the signed area: negative when clockwise. */
    private static double area(double[][] ring) {
        double sum = 0;
        for (int i = 0; i + 1 < ring.length; i++) {
            sum += ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1];
        }
        return sum;
    }

    private static boolean inside(double[] p, double[][] ring) {
        boolean in = false;
        for (int i = 0, j = ring.length - 1; i < ring.length; j = i++) {
            if ((ring[i][1] > p[1]) != (ring[j][1] > p[1])
                    && p[0] < (ring[j][0] - ring[i][0]) * (p[1] - ring[i][1]) / (ring[j][1] - ring[i][1]) + ring[i][0]) {
                in = !in;
            }
        }
        return in;
    }

    // .dbf

    static List<Map<String, Object>> attributes(byte[] dbf, Charset charset, Features features) {
        if (dbf.length < 32) {
            throw new LoadRefused(Code.UNREADABLE, "The .dbf file is truncated.");
        }
        ByteBuffer b = ByteBuffer.wrap(dbf).order(ByteOrder.LITTLE_ENDIAN);
        int records = b.getInt(4);
        int headerLength = Short.toUnsignedInt(b.getShort(8));
        int recordLength = Short.toUnsignedInt(b.getShort(10));
        List<String> names = new ArrayList<>();
        List<Character> types = new ArrayList<>();
        List<int[]> sizes = new ArrayList<>();
        for (int at = 32; at + 32 <= headerLength && dbf[at] != 0x0D; at += 32) {
            int end = at;
            while (end < at + 11 && dbf[end] != 0) {
                end++;
            }
            String name = new String(dbf, at, end - at, charset == null ? StandardCharsets.ISO_8859_1 : charset).strip();
            char type = (char) dbf[at + 11];
            names.add(name);
            types.add(type);
            sizes.add(new int[] {dbf[at + 16] & 0xFF, dbf[at + 17] & 0xFF});
            features.attribute(name, switch (type) {
                case 'L' -> Column.Type.BOOLEAN;
                case 'D' -> Column.Type.DATE;
                case 'N', 'F' -> (dbf[at + 17] & 0xFF) == 0 && (dbf[at + 16] & 0xFF) < 19 ? Column.Type.BIGINT : Column.Type.DOUBLE;
                case 'I' -> Column.Type.BIGINT;
                default -> Column.Type.TEXT;
            });
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int r = 0; r < records; r++) {
            int at = headerLength + r * recordLength;
            if (at + recordLength > dbf.length) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            int offset = at + 1;
            for (int f = 0; f < names.size(); f++) {
                int size = sizes.get(f)[0];
                row.put(names.get(f), value(dbf, offset, size, types.get(f), sizes.get(f)[1], charset));
                offset += size;
            }
            // Deleted records keep their place, so that they stay aligned with the shapes.
            rows.add(row);
        }
        return rows;
    }

    private static Object value(byte[] dbf, int at, int size, char type, int decimals, Charset charset) {
        if (type == 'I') {
            return (long) ByteBuffer.wrap(dbf, at, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }
        String raw = charset != null ? new String(dbf, at, size, charset) : Text.decode(java.util.Arrays.copyOfRange(dbf, at, at + size));
        String s = raw.strip();
        if (s.isEmpty() || s.chars().allMatch(c -> c == '*')) {
            return null;
        }
        try {
            return switch (type) {
                case 'N', 'F' -> decimals == 0 && size < 19 ? (Object) Long.parseLong(s) : (Object) Double.parseDouble(s);
                case 'L' -> "TtYy".indexOf(s.charAt(0)) >= 0 ? Boolean.TRUE : "FfNn".indexOf(s.charAt(0)) >= 0 ? Boolean.FALSE : null;
                case 'D' -> s.length() == 8 && !s.equals("00000000")
                        ? LocalDate.of(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6)),
                                Integer.parseInt(s.substring(6, 8)))
                        : null;
                default -> s;
            };
        } catch (RuntimeException e) {
            return type == 'N' || type == 'F' ? parseLoose(s) : null;
        }
    }

    private static Object parseLoose(String s) {
        try {
            return Double.parseDouble(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
