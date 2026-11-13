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

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.openjdk.jol.info.ClassData;
import org.openjdk.jol.layouters.NativeImageLayouter;
import org.openjdk.jol.layouters.NativeImageLayouter.Mode;
import org.openjdk.jol.util.Multiset;

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

public class NativeImageHeapTest {

    private static final int OBJECT = 2, SHORT = 9, INT = 10, LONG = 11;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private HeapDumpReader reader;
    private Multiset<ClassData> data;

    /**
     * A dump as a Serial GC build with compressed references writes it. Every hub is 40 bytes:
     * 24 fixed, one 8-byte vtable entry, and the 8 bytes the identity hash field adds.
     * Foo is 16 bytes, 24 with its identity hash field, which goes at offset 16.
     */
    private NativeImageHeap read(boolean hubFields) throws Exception {
        DumpWriter w = new DumpWriter(hubFields);
        long object = w.declareClass("java/lang/Object", 0x10000, 0, 8, 8, 4, 8);
        w.declareClass("java/lang/Class", 0x10028, object, 24, hybridEncoding(24, 3), 0, 0, w.classFields());
        w.declareClass("[B", 0x10050, object, 0, arrayEncoding(0b111, 8, 0), 0, 0);
        w.declareClass("[Ljava/lang/Object;", 0x10078, object, 0, arrayEncoding(0b101, 8, 2), 0, 0);
        long foo = w.declareClass("Foo", 0x100a0, object, 16, 16, 0, 16, "ref", OBJECT, "a", INT, "b", INT);
        // One long field and 264 bytes the layout rules do not explain, like @Contended padding.
        w.declareClass("Padded", 0x100c8, object, 280, 280, 272, 276, "value", LONG);
        w.writeHubs();

        w.instance(0x100f0, foo, new byte[16]); // 16 bytes
        w.instance(0x10100, foo, new byte[16]); // 24 bytes: carries the identity hash field
        w.instance(0x10118, foo, new byte[16]); // 16 bytes
        w.byteArray(0x10128, 5);                // 16 bytes
        w.byteArray(0x10138, 5);                // 24 bytes: carries the identity hash field
        w.byteArray(0x10150, 5);                // the last object: nothing to measure against

        File file = w.write(tmp.newFile("ni.hprof"));
        reader = new HeapDumpReader(file, null, null);
        data = reader.parse();
        return reader.nativeImageHeap();
    }

    private ClassData classData(String name) {
        for (ClassData cd : data.keys()) {
            if (!cd.isArray() && cd.name().equals(name)) {
                return cd;
            }
        }
        throw new AssertionError("No class " + name);
    }

    @Test
    public void ignoresOtherDumps() throws Exception {
        Assert.assertNull(read(false));
    }

    @Test
    public void detectsTheLayout() throws Exception {
        NativeImageHeap heap = read(true);
        Assert.assertNotNull(heap);
        Assert.assertEquals(4, heap.referenceSize());
        Assert.assertEquals(NativeImageHeap.HashMode.OPTIONAL, heap.hashMode());
        Assert.assertEquals(8, heap.arrayHeaderSize());
        Assert.assertEquals(1, heap.typeCheckSlots());
        Assert.assertEquals(Mode.SERIAL, Mode.of(heap));
    }

    @Test
    public void sizesEveryObject() throws Exception {
        NativeImageHeap heap = read(true);
        Assert.assertEquals(12, heap.totalObjects());
        Assert.assertEquals(6 * 40 + 16 + 24 + 16 + 16 + 24 + 16, heap.totalBytes());
        Assert.assertEquals(2, heap.objectsWithHashField());
        Assert.assertEquals(1, heap.uncertainObjects());

        ClassData foo = classData("Foo");
        Assert.assertEquals(3, heap.count(foo));
        Assert.assertEquals(56, heap.totalSize(foo));
        Assert.assertEquals(1, heap.withHashField(foo));
        Assert.assertEquals(19, heap.instanceSize(foo));
        Assert.assertEquals(16, heap.allocationSize(foo));

        ClassData bytes = new ClassData("byte[]", "byte", 5);
        Assert.assertEquals(56, heap.totalSize(bytes));
        Assert.assertEquals(16, heap.allocationSize(bytes));

        ClassData hub = classData("java.lang.Class");
        Assert.assertEquals(6 * 40, heap.totalSize(hub));
        Assert.assertEquals(6 * 16, heap.hybridExtra(hub));
        Assert.assertEquals(6, heap.hybridSized(hub));
    }

    @Test
    public void sizesClassesWithoutObjects() throws Exception {
        NativeImageHeap heap = read(true);
        Assert.assertEquals(24, heap.instanceSize(new ClassData("java.lang.Object[]", "Object", 3)));
        Assert.assertEquals(8, heap.instanceSize(new ClassData("long[]", "long", 0)));
        Assert.assertEquals(280, heap.instanceSize(new ClassData("Padded")));
    }

    @Test
    public void reportSizesUseTheDump() throws Exception {
        NativeImageHeap heap = read(true);
        HeapDumpSizes sizes = new HeapDumpSizes(reader, null);
        Assert.assertSame(heap, sizes.nativeImageHeap());
        long total = 0;
        for (ClassData cd : data.keys()) {
            total += sizes.totalSize(cd, data.count(cd));
        }
        Assert.assertEquals(heap.totalBytes(), total);
    }

    @Test
    public void laysOutClassesInOtherModes() throws Exception {
        NativeImageHeap heap = read(true);
        ClassData foo = classData("Foo");
        ClassData bytes = new ClassData("byte[]", "byte", 5);
        ClassData refs = new ClassData("java.lang.Object[]", "Object", 3);
        ClassData padded = classData("Padded");
        ClassData hub = classData("java.lang.Class");

        NativeImageLayouter serial = new NativeImageLayouter(Mode.SERIAL, heap);
        Assert.assertEquals(16, serial.instanceSize(foo));
        Assert.assertEquals(24, serial.instanceSizeWithHashField(foo));
        Assert.assertEquals(16, serial.instanceSize(bytes));
        Assert.assertEquals(24, serial.instanceSizeWithHashField(bytes));
        Assert.assertEquals(24, serial.instanceSize(refs));
        Assert.assertEquals(280, serial.instanceSize(padded));
        Assert.assertEquals(24, serial.instanceSize(hub));
        Assert.assertEquals(heap.totalBytes(), serial.footprint(data));

        // Header 4, ref 4, two ints, hash 4: 20, aligned to 24. Arrays: hub, hash, length.
        // Padded: header 4, lock 4, long 8, hash 4: 24 with alignment, and the 264 bytes.
        NativeImageLayouter hashAlways = new NativeImageLayouter(Mode.SERIAL_HASH_FIELD_ALWAYS, heap);
        Assert.assertEquals(24, hashAlways.instanceSize(foo));
        Assert.assertEquals(24, hashAlways.instanceSize(bytes));
        Assert.assertEquals(24, hashAlways.instanceSize(refs));
        Assert.assertEquals(288, hashAlways.instanceSize(padded));
        Assert.assertEquals(32, hashAlways.instanceSize(hub));

        // Header 8, ref 8, two ints, hash 4: 28, aligned to 32.
        NativeImageLayouter fullReferences = new NativeImageLayouter(Mode.SERIAL_FULL_REFERENCES, heap);
        Assert.assertEquals(32, fullReferences.instanceSize(foo));
        Assert.assertEquals(24, fullReferences.instanceSize(bytes));
        Assert.assertEquals(40, fullReferences.instanceSize(refs));
        Assert.assertEquals(296, fullReferences.instanceSize(padded));
    }

    /** Layout encoding of a hybrid object with reference-free inline array elements. */
    private static int hybridEncoding(int arrayBaseOffset, int indexShift) {
        return arrayEncoding(0b100, arrayBaseOffset, indexShift);
    }

    /** Layout encoding of an array: tag 0b111 for primitive arrays, 0b101 for object arrays. */
    private static int arrayEncoding(int tag, int arrayBaseOffset, int indexShift) {
        return (tag << 29) | (arrayBaseOffset << 8) | indexShift;
    }

    /**
     * Writes small HPROF files shaped like GraalVM Native Image heap dumps: object IDs are
     * addresses, a class ID is its hub address plus one, and every hub is a java.lang.Class
     * instance whose fields describe the class layout.
     */
    private static final class DumpWriter {
        private final ByteArrayOutputStream strings = new ByteArrayOutputStream();
        private final ByteArrayOutputStream classes = new ByteArrayOutputStream();
        private final ByteArrayOutputStream heap = new ByteArrayOutputStream();
        private final Map<String, Long> stringIds = new HashMap<>();
        private final List<long[]> loadClasses = new ArrayList<>();
        private final List<long[]> pendingHubs = new ArrayList<>();
        private final boolean hubFields;
        private long classClassId;

        /** @param hubFields whether java.lang.Class gets the Native Image hub fields */
        DumpWriter(boolean hubFields) {
            this.hubFields = hubFields;
        }

        private long string(String s) throws IOException {
            Long id = stringIds.get(s);
            if (id == null) {
                id = stringIds.size() + 1L;
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
         * Declares a class, and its hub for {@link #writeHubs()}.
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

        /** The fields of java.lang.Class, as {@link #writeHubs()} lays them out. */
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
            out.writeByte(8); // byte
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
                out.writeByte(0x1C);
                out.writeInt(0);
                out.writeInt(classes.size() + heap.size());
                classes.writeTo(out);
                heap.writeTo(out);
                out.writeByte(0x2C);
                out.writeInt(0);
                out.writeInt(0);
            }
            return file;
        }
    }
}
