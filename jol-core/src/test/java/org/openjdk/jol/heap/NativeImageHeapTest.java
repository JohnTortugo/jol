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

import java.io.File;

import static org.openjdk.jol.heap.NativeImageDumpWriter.*;

public class NativeImageHeapTest {

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
        NativeImageDumpWriter w = new NativeImageDumpWriter(hubFields);
        long object = w.declareClass("java/lang/Object", 0x10000, 0, 8, 8, 4, 8);
        w.declareClass("java/lang/Class", 0x10028, object, 24, hybridEncoding(24, 3), 0, 0, w.classFields());
        w.declareClass("[B", 0x10050, object, 0, primitiveArrayEncoding(8, 0), 0, 0);
        w.declareClass("[Ljava/lang/Object;", 0x10078, object, 0, objectArrayEncoding(8, 2), 0, 0);
        long foo = w.declareClass("Foo", 0x100a0, object, 16, 16, 0, 16, "ref", OBJECT, "a", INT, "b", INT);
        // @Contended class with one long field, padded by 128 bytes on both sides.
        w.declareClass("Padded", 0x100c8, object, 280, 280, 272, 276, "value", 11);
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
        HeapDumpSizes sizes = HeapDumpSizes.of(reader, null);
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
        NativeImageLayouter hashAlways = new NativeImageLayouter(Mode.SERIAL_HASH_FIELD_ALWAYS, heap);
        Assert.assertEquals(24, hashAlways.instanceSize(foo));
        Assert.assertEquals(24, hashAlways.instanceSize(bytes));
        Assert.assertEquals(24, hashAlways.instanceSize(refs));
        Assert.assertEquals(280, hashAlways.instanceSize(padded));
        Assert.assertEquals(32, hashAlways.instanceSize(hub));

        // Header 8, ref 8, two ints, hash 4: 28, aligned to 32.
        NativeImageLayouter fullReferences = new NativeImageLayouter(Mode.SERIAL_FULL_REFERENCES, heap);
        Assert.assertEquals(32, fullReferences.instanceSize(foo));
        Assert.assertEquals(24, fullReferences.instanceSize(bytes));
        Assert.assertEquals(40, fullReferences.instanceSize(refs));
        Assert.assertEquals(288, fullReferences.instanceSize(padded));

        // Mark word 4, hub 4, ref 4, two ints: 20, aligned to 24; no hash field.
        NativeImageLayouter g1 = new NativeImageLayouter(Mode.G1, heap);
        Assert.assertEquals(24, g1.instanceSize(foo));
        Assert.assertEquals(24, g1.instanceSizeWithHashField(foo));
        Assert.assertEquals(24, g1.instanceSize(bytes));
    }
}
