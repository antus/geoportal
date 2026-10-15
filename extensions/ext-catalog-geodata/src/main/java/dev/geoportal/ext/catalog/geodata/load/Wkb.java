// SPDX-FileCopyrightText: 2026 Massimo Antonini
// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.geoportal.ext.catalog.geodata.load;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** A writer of ISO WKB, little endian, in 2D or with Z: the exact doubles of a file, for PostGIS. */
final class Wkb {

    static final int POINT = 1;
    static final int LINESTRING = 2;
    static final int POLYGON = 3;
    static final int MULTIPOINT = 4;
    static final int MULTILINESTRING = 5;
    static final int MULTIPOLYGON = 6;

    private final boolean z;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteBuffer number = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);

    Wkb(boolean z) {
        this.z = z;
    }

    Wkb header(int type) {
        out.write(1);
        return count(z ? type + 1000 : type);
    }

    Wkb count(int n) {
        number.clear();
        number.putInt(n);
        out.write(number.array(), 0, 4);
        return this;
    }

    Wkb point(double x, double y, double zValue) {
        put(x);
        put(y);
        if (z) {
            put(zValue);
        }
        return this;
    }

    private void put(double v) {
        number.clear();
        number.putDouble(v);
        out.write(number.array(), 0, 8);
    }

    byte[] bytes() {
        return out.toByteArray();
    }
}
