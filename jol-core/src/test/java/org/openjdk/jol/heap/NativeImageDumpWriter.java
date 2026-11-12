/*
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jol.heap;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes small HPROF files shaped like GraalVM Native Image heap dumps: object IDs are addresses,
 * a class ID is its hub address plus one, and every hub is a java.lang.Class instance whose
 * fields describe the class layout.
 */
class NativeImageDumpWriter {

    static final int OBJECT = 2, BYTE = 8, SHORT = 9, INT = 10;

    private final ByteArrayOutputStream strings = new ByteArrayOutputStream();
    private final ByteArrayOutputStream classes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream heap = new ByteArrayOutputStream();
    private final Map<String, Long> stringIds = new HashMap<>();
    private final List<long[]> loadClasses = new ArrayList<>();
    private final List<long[]> pendingHubs = new ArrayList<>();
    private final boolean hubFields;
    private long nextStringId = 1;
    private long classClassId;

    /**
     * @param hubFields whether java.lang.Class gets the Native Image hub fields
     */
    NativeImageDumpWriter(boolean hubFields) {
        this.hubFields = hubFields;
    }

    private long string(String s) throws IOException {
        Long id = stringIds.get(s);
        if (id == null) {
            id = nextStringId++;
            stringIds.put(s, id);
            DataOutputStream out = new DataOutputStream(strings);
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            out.writeByte(0x01);
            out.writeInt(0);
            out.writeInt(8 + b.length);
            out.writeLong(id);
            out.write(b);
        }
        return id;
    }

    /**
     * Declares a class and writes its hub object.
     *
     * @param fields name and HPROF type of each instance field, in pairs
     */
    long declareClass(String binaryName, long hubAddress, long superClassId, int recordSize,
                      int layoutEncoding, int monitorOffset, int identityHashOffset, Object... fields) throws IOException {
        long classId = hubAddress + 1;
        loadClasses.add(new long[]{classId, string(binaryName)});
        if (binaryName.equals("java/lang/Class")) {
            classClassId = classId;
        }
        DataOutputStream out = new DataOutputStream(classes);
        out.writeByte(0x20);
        out.writeLong(classId);
        out.writeInt(0);
        out.writeLong(superClassId);
        for (int i = 0; i < 5; i++) {
            out.writeLong(0); // loader, signers, protection domain, reserved
        }
        out.writeInt(recordSize);
        out.writeShort(0); // constant pool
        out.writeShort(0); // static fields
        out.writeShort(fields.length / 2);
        for (int i = 0; i < fields.length; i += 2) {
            out.writeLong(string((String) fields[i]));
            out.writeByte((Integer) fields[i + 1]);
        }
        pendingHubs.add(new long[]{hubAddress, layoutEncoding, monitorOffset, identityHashOffset});
        return classId;
    }

    /** The fields of java.lang.Class, as the hub data written by {@link #writeHubs()} lays them out. */
    Object[] classFields() {
        if (hubFields) {
            return new Object[]{"layoutEncoding", INT, "monitorOffset", INT, "identityHashOffset", INT, "typeCheckSlot", SHORT};
        }
        return new Object[]{"name", OBJECT, "classRedefinedCount", INT};
    }

    /** Writes the hubs as java.lang.Class instances; call after all classes are declared. */
    void writeHubs() throws IOException {
        for (long[] h : pendingHubs) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(b);
            if (hubFields) {
                d.writeInt((int) h[1]);
                d.writeInt((int) h[2]);
                d.writeInt((int) h[3]);
                d.writeShort(0);
            } else {
                d.writeLong(0);
                d.writeInt(0);
            }
            instance(h[0], classClassId, b.toByteArray());
        }
    }

    void instance(long address, long classId, byte[] data) throws IOException {
        DataOutputStream out = new DataOutputStream(heap);
        out.writeByte(0x21);
        out.writeLong(address);
        out.writeInt(0);
        out.writeLong(classId);
        out.writeInt(data.length);
        out.write(data);
    }

    void byteArray(long address, int length) throws IOException {
        DataOutputStream out = new DataOutputStream(heap);
        out.writeByte(0x23);
        out.writeLong(address);
        out.writeInt(0);
        out.writeInt(length);
        out.writeByte(BYTE);
        out.write(new byte[length]);
    }

    File write(File file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(file))) {
            out.write("JAVA PROFILE 1.0.2".getBytes(StandardCharsets.US_ASCII));
            out.writeByte(0);
            out.writeInt(8);
            out.writeLong(0);
            out.write(strings.toByteArray());
            int serial = 1;
            for (long[] lc : loadClasses) {
                out.writeByte(0x02);
                out.writeInt(0);
                out.writeInt(4 + 8 + 4 + 8);
                out.writeInt(serial++);
                out.writeLong(lc[0]);
                out.writeInt(0);
                out.writeLong(lc[1]);
            }
            byte[] segment = concat(classes.toByteArray(), heap.toByteArray());
            out.writeByte(0x1C);
            out.writeInt(0);
            out.writeInt(segment.length);
            out.write(segment);
            out.writeByte(0x2C);
            out.writeInt(0);
            out.writeInt(0);
        }
        return file;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    /** Layout encoding of a hybrid object with reference-free inline array elements. */
    static int hybridEncoding(int arrayBaseOffset, int indexShift) {
        return (0b100 << 29) | (arrayBaseOffset << 8) | indexShift;
    }

    /** Layout encoding of a primitive array. */
    static int primitiveArrayEncoding(int arrayBaseOffset, int indexShift) {
        return (0b111 << 29) | (arrayBaseOffset << 8) | indexShift;
    }

    /** Layout encoding of an object array. */
    static int objectArrayEncoding(int arrayBaseOffset, int indexShift) {
        return (0b101 << 29) | (arrayBaseOffset << 8) | indexShift;
    }
}
