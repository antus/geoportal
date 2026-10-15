// SPDX-FileCopyrightText: 2026 Massimo Antonini
package dev.geoportal.ext.catalog.geodata.load;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A reader of SQLite database files, enough for GeoPackage (GP-937): the schema and the rows of the
 * tables with a rowid, read from the bytes of the file as the
 * <a href="https://www.sqlite.org/fileformat2.html">file format</a> describes them. No driver and no
 * native library: the catalog ships one JAR (Mosaikit loads one per extension).
 *
 * <p>Read only, and only the main file: a database whose last changes are still in a write-ahead log
 * that was not uploaded is read as it was before them.
 */
final class Sqlite {

    /** A table of the schema: its name, the page of the root of its b-tree and its columns. */
    record Table(String name, int rootPage, List<String> columns, List<String> types, int rowidColumn) {}

    /** A row: its rowid and the values of its columns, {@code null}, Long, Double, String or byte[]. */
    record Row(long rowid, Object[] values) {}

    private final byte[] file;
    private final int pageSize;
    private final int usable;
    private final Charset text;

    Sqlite(byte[] file) {
        if (file.length < 100 || !new String(file, 0, 15, StandardCharsets.US_ASCII).equals("SQLite format 3")) {
            throw new IllegalArgumentException("not an SQLite database");
        }
        this.file = file;
        int size = u16(16);
        this.pageSize = size == 1 ? 65536 : size;
        this.usable = pageSize - (file[20] & 0xFF);
        this.text = switch ((int) u32(56)) {
            case 2 -> StandardCharsets.UTF_16LE;
            case 3 -> StandardCharsets.UTF_16BE;
            default -> StandardCharsets.UTF_8;
        };
        if (pageSize < 512 || (pageSize & (pageSize - 1)) != 0) {
            throw new IllegalArgumentException("the page size " + pageSize + " is not valid");
        }
    }

    /** The tables of the schema, by name. */
    List<Table> tables() {
        List<Table> tables = new ArrayList<>();
        scan(1, row -> {
            Object[] v = row.values();
            if (v.length >= 5 && "table".equals(v[0]) && v[3] instanceof Long root && v[4] instanceof String sql) {
                Definition definition = Definition.parse(sql);
                if (!definition.withoutRowid()) {
                    tables.add(new Table((String) v[1], root.intValue(), definition.columns(), definition.types(),
                            definition.rowidColumn()));
                }
            }
        });
        return tables;
    }

    Table table(String name) {
        return tables().stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no table " + name));
    }

    /** Every row of a table, in rowid order; the alias of the rowid holds the rowid. */
    List<Row> rows(Table table) {
        List<Row> rows = new ArrayList<>();
        scan(table.rootPage(), row -> {
            Object[] values = new Object[table.columns().size()];
            System.arraycopy(row.values(), 0, values, 0, Math.min(values.length, row.values().length));
            if (table.rowidColumn() >= 0) {
                values[table.rowidColumn()] = row.rowid();
            }
            rows.add(new Row(row.rowid(), values));
        });
        return rows;
    }

    /** Walks the table b-tree rooted at a page, leaf cells in order. */
    private void scan(int root, Consumer<Row> each) {
        Set<Integer> seen = new HashSet<>();
        walk(root, each, seen);
    }

    private void walk(int page, Consumer<Row> each, Set<Integer> seen) {
        if (page < 1 || (long) (page - 1) * pageSize >= file.length || !seen.add(page)) {
            throw new IllegalArgumentException("the b-tree points to page " + page + ", which is not valid");
        }
        int base = (page - 1) * pageSize;
        int header = page == 1 ? base + 100 : base;
        int type = file[header] & 0xFF;
        int cells = u16(header + 3);
        switch (type) {
            case 0x05 -> {
                for (int i = 0; i < cells; i++) {
                    int cell = base + u16(header + 12 + 2 * i);
                    walk((int) u32(cell), each, seen);
                }
                walk((int) u32(header + 8), each, seen);
            }
            case 0x0D -> {
                for (int i = 0; i < cells; i++) {
                    int cell = base + u16(header + 8 + 2 * i);
                    long[] payload = varint(cell);
                    long[] rowid = varint(cell + (int) payload[1]);
                    int start = cell + (int) payload[1] + (int) rowid[1];
                    each.accept(new Row(rowid[0], record(payload(start, (int) payload[0]))));
                }
            }
            default -> throw new IllegalArgumentException("page " + page + " is not a page of a table");
        }
    }

    /** The payload of a cell, with its overflow pages. */
    private byte[] payload(int start, int size) {
        int max = usable - 35;
        if (size <= max) {
            return slice(start, size);
        }
        int min = ((usable - 12) * 32 / 255) - 23;
        int local = min + ((size - min) % (usable - 4));
        if (local > max) {
            local = min;
        }
        byte[] out = new byte[size];
        System.arraycopy(file, start, out, 0, local);
        int done = local;
        int next = (int) u32(start + local);
        Set<Integer> seen = new HashSet<>();
        while (done < size) {
            if (next < 1 || !seen.add(next) || (long) next * pageSize > file.length) {
                throw new IllegalArgumentException("an overflow chain is broken");
            }
            int base = (next - 1) * pageSize;
            int chunk = Math.min(usable - 4, size - done);
            System.arraycopy(file, base + 4, out, done, chunk);
            done += chunk;
            next = (int) u32(base);
        }
        return out;
    }

    private Object[] record(byte[] payload) {
        long[] headerSize = varint(payload, 0);
        int p = (int) headerSize[1];
        List<Long> types = new ArrayList<>();
        while (p < headerSize[0]) {
            long[] t = varint(payload, p);
            types.add(t[0]);
            p += (int) t[1];
        }
        int body = (int) headerSize[0];
        Object[] values = new Object[types.size()];
        for (int i = 0; i < values.length; i++) {
            long t = types.get(i);
            int length = length(t);
            values[i] = value(payload, body, t, length);
            body += length;
        }
        return values;
    }

    private Object value(byte[] b, int at, long type, int length) {
        if (type == 0) {
            return null;
        }
        if (type >= 1 && type <= 6) {
            long v = b[at];
            for (int i = 1; i < length; i++) {
                v = (v << 8) | (b[at + i] & 0xFF);
            }
            return v;
        }
        if (type == 7) {
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                bits = (bits << 8) | (b[at + i] & 0xFF);
            }
            return Double.longBitsToDouble(bits);
        }
        if (type == 8) {
            return 0L;
        }
        if (type == 9) {
            return 1L;
        }
        if (type >= 12 && type % 2 == 0) {
            byte[] blob = new byte[length];
            System.arraycopy(b, at, blob, 0, length);
            return blob;
        }
        if (type >= 13) {
            return new String(b, at, length, text);
        }
        throw new IllegalArgumentException("a record has the reserved type " + type);
    }

    private static int length(long type) {
        if (type >= 12) {
            return (int) ((type - (type % 2 == 0 ? 12 : 13)) / 2);
        }
        return switch ((int) type) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 3;
            case 4 -> 4;
            case 5 -> 6;
            case 6, 7 -> 8;
            default -> 0;
        };
    }

    private long[] varint(int at) {
        return varint(file, at);
    }

    /** A varint: its value and how many bytes it took. */
    private static long[] varint(byte[] b, int at) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int x = b[at + i] & 0xFF;
            v = (v << 7) | (x & 0x7F);
            if ((x & 0x80) == 0) {
                return new long[] {v, i + 1};
            }
        }
        return new long[] {(v << 8) | (b[at + 8] & 0xFF), 9};
    }

    private byte[] slice(int start, int size) {
        byte[] out = new byte[size];
        System.arraycopy(file, start, out, 0, size);
        return out;
    }

    private int u16(int at) {
        return ((file[at] & 0xFF) << 8) | (file[at + 1] & 0xFF);
    }

    private long u32(int at) {
        return ((long) (file[at] & 0xFF) << 24) | ((file[at + 1] & 0xFF) << 16) | ((file[at + 2] & 0xFF) << 8)
                | (file[at + 3] & 0xFF);
    }

    /** The columns of a CREATE TABLE statement, and which one is the alias of the rowid. */
    record Definition(List<String> columns, List<String> types, int rowidColumn, boolean withoutRowid) {

        private static final Set<String> CONSTRAINTS = Set.of("constraint", "primary", "unique", "check", "foreign");

        static Definition parse(String sql) {
            int open = sql.indexOf('(');
            int close = sql.lastIndexOf(')');
            if (open < 0 || close < open) {
                return new Definition(List.of(), List.of(), -1, false);
            }
            boolean withoutRowid = sql.substring(close + 1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
                    .contains("without rowid");
            List<String> columns = new ArrayList<>();
            List<String> types = new ArrayList<>();
            int rowid = -1;
            for (String part : split(sql.substring(open + 1, close))) {
                List<String> tokens = tokens(part);
                if (tokens.isEmpty() || CONSTRAINTS.contains(tokens.get(0).toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String type = tokens.size() > 1 ? tokens.get(1).toLowerCase(Locale.ROOT) : "";
                String rest = String.join(" ", tokens).toLowerCase(Locale.ROOT);
                if (type.equals("integer") && rest.contains("primary key") && !rest.contains("desc")) {
                    rowid = columns.size();
                }
                columns.add(unquote(tokens.get(0)));
                types.add(type);
            }
            return new Definition(columns, types, rowid, withoutRowid);
        }

        /** The parts between the commas that are not inside parentheses or quotes. */
        private static List<String> split(String body) {
            List<String> parts = new ArrayList<>();
            int depth = 0;
            char quote = 0;
            int start = 0;
            for (int i = 0; i < body.length(); i++) {
                char c = body.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '"' || c == '\'' || c == '`') {
                    quote = c;
                } else if (c == '[') {
                    quote = ']';
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parts.add(body.substring(start, i));
                    start = i + 1;
                }
            }
            parts.add(body.substring(start));
            return parts;
        }

        private static List<String> tokens(String part) {
            List<String> tokens = new ArrayList<>();
            String s = part.strip();
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                int end;
                if (c == '"' || c == '\'' || c == '`' || c == '[') {
                    char closing = c == '[' ? ']' : c;
                    end = s.indexOf(closing, i + 1);
                    end = end < 0 ? s.length() : end + 1;
                } else if (c == '(') {
                    end = s.indexOf(')', i);
                    end = end < 0 ? s.length() : end + 1;
                } else {
                    end = i;
                    while (end < s.length() && !Character.isWhitespace(s.charAt(end)) && s.charAt(end) != '(') {
                        end++;
                    }
                }
                tokens.add(s.substring(i, end));
                i = end;
            }
            return tokens;
        }

        static String unquote(String name) {
            if (name.length() >= 2) {
                char first = name.charAt(0);
                char last = name.charAt(name.length() - 1);
                if ((first == '"' && last == '"') || (first == '`' && last == '`') || (first == '\'' && last == '\'')
                        || (first == '[' && last == ']')) {
                    return name.substring(1, name.length() - 1);
                }
            }
            return name;
        }
    }
}
