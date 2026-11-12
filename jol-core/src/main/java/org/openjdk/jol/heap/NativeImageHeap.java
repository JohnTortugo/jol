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

import org.openjdk.jol.info.ClassData;
import org.openjdk.jol.util.MathUtil;
import org.openjdk.jol.util.Multiset;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact object sizes for heap dumps written by GraalVM Native Image.
 * <p>
 * A Native Image heap dump is a regular HPROF file, with three properties that make exact
 * object sizes possible:
 * <ul>
 *     <li>Every {@code java.lang.Class} object is a hub, and its fields {@code layoutEncoding},
 *     {@code monitorOffset} and {@code identityHashOffset} describe the layout of its class.</li>
 *     <li>The instance size in a class record is the size of an object without its optional
 *     identity hash field. Hybrid objects, like the hubs with their inline vtables, record only
 *     their fixed part.</li>
 *     <li>Object IDs are addresses, and objects are written in address order. The distance to the
 *     next object therefore shows whether an object carries the identity hash field, which the GC
 *     adds when it moves a hashed object, and which every image heap object has.</li>
 * </ul>
 * Objects whose distance to the next object matches no rule, like the last object in a heap
 * chunk, keep their class size, which is a lower bound.
 */
public final class NativeImageHeap {

    /** Where Native Image keeps identity hash codes. */
    public enum HashMode {
        /** In a field that is added only to objects that need it (Serial and Epsilon GC, compressed references). */
        OPTIONAL,
        /** In a field that every object has (-H:-OptionalIdentityHashCodes, or no compressed references). */
        TYPE_SPECIFIC,
        /** In the object header (G1 GC). */
        OBJECT_HEADER,
    }

    /** The layout facts of one class, read from its hub. */
    public static final class Hub {
        private final int layoutEncoding;
        private final int monitorOffset;
        private final int identityHashOffset;

        Hub(int layoutEncoding, int monitorOffset, int identityHashOffset) {
            this.layoutEncoding = layoutEncoding;
            this.monitorOffset = monitorOffset;
            this.identityHashOffset = identityHashOffset;
        }

        public boolean isPureInstance() {
            return layoutEncoding > 3;
        }

        public boolean isArrayLike() {
            return layoutEncoding < 0;
        }

        public boolean isHybrid() {
            return isArrayLike() && (layoutEncoding & (0b101 << 29)) == (0b100 << 29);
        }

        /** Size of an instance without the optional identity hash field. */
        public int instanceSize() {
            if (!isPureInstance()) {
                throw new IllegalStateException("Not a pure instance class");
            }
            return layoutEncoding;
        }

        public int arrayBaseOffset() {
            return (layoutEncoding >> 8) & 0xfff;
        }

        public int arrayIndexShift() {
            return layoutEncoding & 0xff;
        }

        public boolean hasMonitorField() {
            return monitorOffset != 0;
        }

        public int identityHashOffset() {
            return identityHashOffset;
        }
    }

    private static final class Sizes {
        long count;
        long bytes;
        long withHashField;
        long uncertain;
        long hybridExtra;
        long hybridSized;
        long minSize = Long.MAX_VALUE;
        long maxSize = Long.MIN_VALUE;

        void add(long size, long n) {
            count += n;
            bytes += size * n;
            minSize = Math.min(minSize, size);
            maxSize = Math.max(maxSize, size);
        }
    }

    /** The variable part of a hybrid object (vtable and trailing hash field) is at most this large. */
    private static final long MAX_HYBRID_EXTRA = 64 * 1024;

    private static final int OBJECT_ALIGNMENT = 8;

    private final int referenceSize;
    private final HashMode hashMode;
    private final int arrayHeaderSize;
    private final int typeCheckSlots;
    private final Map<String, Hub> hubsByName;
    private final Map<String, ClassData> classesByName;
    private final Map<ClassData, Sizes> sizes;
    private final Sizes total;

    private NativeImageHeap(int referenceSize, HashMode hashMode, int arrayHeaderSize, int typeCheckSlots,
                            Map<String, Hub> hubsByName, Map<String, ClassData> classesByName,
                            Map<ClassData, Sizes> sizes, Sizes total) {
        this.referenceSize = referenceSize;
        this.hashMode = hashMode;
        this.arrayHeaderSize = arrayHeaderSize;
        this.typeCheckSlots = typeCheckSlots;
        this.hubsByName = hubsByName;
        this.classesByName = classesByName;
        this.sizes = sizes;
        this.total = total;
    }

    /** Size of a reference field, in bytes. */
    public int referenceSize() {
        return referenceSize;
    }

    public HashMode hashMode() {
        return hashMode;
    }

    /** Offset of the first byte[] element: the hub, the array length, and a header hash if any. */
    public int arrayHeaderSize() {
        return arrayHeaderSize;
    }

    /**
     * The least number of type check slots in each hub: one more than the highest slot that a
     * hub uses. The image builder may reserve more.
     */
    public int typeCheckSlots() {
        return typeCheckSlots;
    }

    /** The hub of a class, by its human-readable name, or null. */
    public Hub hub(String className) {
        return hubsByName.get(className);
    }

    /** Number of dumped objects of this class (for arrays: of this length). */
    public long count(ClassData cd) {
        Sizes s = sizes.get(cd);
        return (s == null) ? 0 : s.count;
    }

    /** Exact total size of the dumped objects of this class (for arrays: of this length). */
    public long totalSize(ClassData cd) {
        Sizes s = sizes.get(cd);
        return (s == null) ? 0 : s.bytes;
    }

    /** Number of dumped objects of this class that carry the optional identity hash field. */
    public long withHashField(ClassData cd) {
        Sizes s = sizes.get(cd);
        return (s == null) ? 0 : s.withHashField;
    }

    /** Bytes beyond the fixed part, summed over all dumped hybrid objects of this class. */
    public long hybridExtra(ClassData cd) {
        Sizes s = sizes.get(cd);
        return (s == null) ? 0 : s.hybridExtra;
    }

    /** Number of dumped hybrid objects of this class whose full size was found. */
    public long hybridSized(ClassData cd) {
        Sizes s = sizes.get(cd);
        return (s == null) ? 0 : s.hybridSized;
    }

    /**
     * Size of one object of this class: exact when all dumped objects of the class have the same
     * size, the rounded average otherwise. Classes without dumped objects get their allocation
     * size, without the optional identity hash field.
     */
    public long instanceSize(ClassData cd) {
        Sizes s = sizes.get(cd);
        if (s == null && !cd.isArray()) {
            ClassData byName = classesByName.get(cd.name());
            s = (byName == null) ? null : sizes.get(byName);
        }
        if (s != null && s.count > 0) {
            return (s.bytes + s.count / 2) / s.count;
        }
        return allocationSize(cd);
    }

    /** Size of an object of this class at allocation: without the optional identity hash field. */
    public long allocationSize(ClassData cd) {
        if (cd.isArray()) {
            return MathUtil.align(arrayUnalignedSize(cd.arrayClass(), cd.arrayComponentType(), cd.arrayLength()), OBJECT_ALIGNMENT);
        }
        Hub hub = hubsByName.get(cd.name());
        if (hub != null && hub.isPureInstance()) {
            return hub.instanceSize();
        }
        if (hub != null && hub.isArrayLike()) {
            return hub.arrayBaseOffset();
        }
        return 0;
    }

    private long arrayUnalignedSize(String arrayClass, String componentType, long length) {
        Hub hub = hubsByName.get(arrayClass);
        if (hub != null && hub.isArrayLike() && !hub.isHybrid()) {
            return hub.arrayBaseOffset() + (length << hub.arrayIndexShift());
        }
        int elementSize = elementSize(componentType);
        return MathUtil.align(arrayHeaderSize, elementSize) + length * elementSize;
    }

    private int elementSize(String componentType) {
        switch (componentType) {
            case "boolean":
            case "byte":
                return 1;
            case "char":
            case "short":
                return 2;
            case "int":
            case "float":
                return 4;
            case "long":
            case "double":
                return 8;
            default:
                return referenceSize;
        }
    }

    public long totalObjects() {
        return total.count;
    }

    public long totalBytes() {
        return total.bytes;
    }

    /** Objects that carry the optional identity hash field. */
    public long objectsWithHashField() {
        return total.withHashField;
    }

    /** Objects that could be larger than their class size, but their size could not be read. */
    public long uncertainObjects() {
        return total.uncertain;
    }

    /** A one-line description of the layout found in this dump. */
    public String description() {
        String hash;
        switch (hashMode) {
            case OPTIONAL:
                hash = "identity hash field only when needed";
                break;
            case TYPE_SPECIFIC:
                hash = "identity hash field in every object";
                break;
            default:
                hash = "identity hash in the object header";
                break;
        }
        return "Native Image heap dump (" + referenceSize + "-byte references, " + hash + ")";
    }

    /** What a reader of a report on this dump needs to know about its sizes. */
    public List<String> notes() {
        List<String> notes = new ArrayList<>();
        notes.add("Object sizes are exact: they come from the class layouts in the dump, and from the distance");
        notes.add("between objects, which shows which objects carry an identity hash field.");
        if (hashMode == HashMode.OPTIONAL) {
            notes.add(String.format("%,d of %,d objects carry an identity hash field: image heap objects, and hashed objects", total.withHashField, total.count));
            notes.add("that the GC has moved.");
        }
        if (total.uncertain > 0) {
            notes.add(String.format("%,d objects could not be sized from the dump; they count with their class size, a lower bound.", total.uncertain));
        }
        for (Sizes s : sizes.values()) {
            if (s.minSize != s.maxSize) {
                notes.add("Where the objects of a class differ in size, the size of one object is their average.");
                break;
            }
        }
        return notes;
    }

    /**
     * Collects what the heap dump reader sees, and resolves it into exact sizes at the end.
     */
    static final class Builder {
        private final long classClassId;
        private final int layoutEncodingOffset;
        private final int monitorOffsetOffset;
        private final int identityHashOffsetOffset;
        private final int typeCheckSlotOffset;
        private final int classInstanceBytes;

        private final Map<Long, byte[]> hubData = new HashMap<>();
        private final Map<Object, Multiset<Long>> gaps = new HashMap<>();
        private int maxTypeCheckSlot = -1;

        private long lastId;
        private Object lastKey;

        Builder(long classClassId, int layoutEncodingOffset, int monitorOffsetOffset, int identityHashOffsetOffset,
                int typeCheckSlotOffset, int classInstanceBytes) {
            this.classClassId = classClassId;
            this.layoutEncodingOffset = layoutEncodingOffset;
            this.monitorOffsetOffset = monitorOffsetOffset;
            this.identityHashOffsetOffset = identityHashOffsetOffset;
            this.typeCheckSlotOffset = typeCheckSlotOffset;
            this.classInstanceBytes = classInstanceBytes;
        }

        /**
         * Builds a collector if the fields of java.lang.Class show a Native Image hub.
         *
         * @param fieldOffsets offsets of the int and short fields of java.lang.Class within its instance data
         */
        static Builder forClassRecord(long classClassId, Map<String, Integer> fieldOffsets, int classInstanceBytes) {
            Integer le = fieldOffsets.get("layoutEncoding");
            Integer mo = fieldOffsets.get("monitorOffset");
            Integer iho = fieldOffsets.get("identityHashOffset");
            Integer tcs = fieldOffsets.get("typeCheckSlot");
            if (le == null || mo == null || iho == null) {
                return null;
            }
            return new Builder(classClassId, le, mo, iho, (tcs == null) ? -1 : tcs, classInstanceBytes);
        }

        long classClassId() {
            return classClassId;
        }

        void hub(long id, byte[] data) {
            if (data.length >= classInstanceBytes) {
                hubData.put(id, data);
                if (typeCheckSlotOffset >= 0) {
                    maxTypeCheckSlot = Math.max(maxTypeCheckSlot, ByteBuffer.wrap(data).getShort(typeCheckSlotOffset));
                }
            }
        }

        /**
         * Records an object in file order.
         *
         * @param key the class ID (a Long) for instances, the ClassData for arrays
         */
        void object(long id, Object key) {
            if (lastKey != null) {
                addGap(lastKey, id - lastId);
            }
            lastId = id;
            lastKey = key;
        }

        private void addGap(Object key, long gap) {
            Multiset<Long> ms = gaps.get(key);
            if (ms == null) {
                ms = new Multiset<>();
                gaps.put(key, ms);
            }
            ms.add(gap);
        }

        private Hub decodeHub(long classId) {
            // Native Image writes each class record with the ID of its hub plus one, because the
            // hub is also dumped as an instance of java.lang.Class (see HeapDumpWriter.writeClassId).
            byte[] data = hubData.get(classId - 1);
            if (data == null) {
                return null;
            }
            ByteBuffer bb = ByteBuffer.wrap(data);
            return new Hub(bb.getInt(layoutEncodingOffset), bb.getInt(monitorOffsetOffset), bb.getInt(identityHashOffsetOffset));
        }

        NativeImageHeap build(Map<Long, ClassData> classDatas, Map<Long, String> classNames, Map<Long, Integer> recordSizes) {
            if (lastKey != null) {
                addGap(lastKey, -1L); // the last object: nothing to measure against
                lastKey = null;
            }

            Map<Long, Hub> hubsById = new HashMap<>();
            Map<String, Hub> hubsByName = new HashMap<>();
            for (Map.Entry<Long, String> e : classNames.entrySet()) {
                Hub hub = decodeHub(e.getKey());
                if (hub != null) {
                    hubsById.put(e.getKey(), hub);
                    hubsByName.put(e.getValue(), hub);
                }
            }

            Hub objectArray = hubsByName.get("java.lang.Object[]");
            int referenceSize = (objectArray != null && objectArray.isArrayLike()) ? (1 << objectArray.arrayIndexShift()) : 4;

            Hub byteArray = hubsByName.get("byte[]");
            int arrayHeaderSize = (byteArray != null && byteArray.isArrayLike()) ? byteArray.arrayBaseOffset() : referenceSize + 4;
            HashMode hashMode;
            if (arrayHeaderSize == referenceSize + 4) {
                hashMode = HashMode.OPTIONAL;           // hub, array length
            } else if (byteArray != null && byteArray.identityHashOffset() == referenceSize) {
                hashMode = HashMode.TYPE_SPECIFIC;      // hub, identity hash, array length
            } else {
                hashMode = HashMode.OBJECT_HEADER;      // mark word with identity hash, hub, array length
            }

            Map<String, ClassData> classesByName = new HashMap<>();
            for (ClassData cd : classDatas.values()) {
                classesByName.put(cd.name(), cd);
            }

            NativeImageHeap heap = new NativeImageHeap(referenceSize, hashMode, arrayHeaderSize, maxTypeCheckSlot + 1, hubsByName, classesByName,
                    new HashMap<ClassData, Sizes>(), new Sizes());

            for (Map.Entry<Object, Multiset<Long>> e : gaps.entrySet()) {
                Object key = e.getKey();
                ClassData cd;
                long base;
                long withHash = -1;
                boolean hybrid = false;
                if (key instanceof Long) {
                    long classId = (Long) key;
                    cd = classDatas.get(classId);
                    if (cd == null) {
                        continue;
                    }
                    Hub hub = hubsById.get(classId);
                    Integer recordSize = recordSizes.get(classId);
                    if (hub != null && hub.isPureInstance()) {
                        base = hub.instanceSize();
                        if (hashMode == HashMode.OPTIONAL && hub.identityHashOffset() + 4 > base) {
                            withHash = MathUtil.align(hub.identityHashOffset() + 4, OBJECT_ALIGNMENT);
                        }
                    } else {
                        base = (recordSize == null) ? 0 : recordSize;
                        hybrid = (hub != null && hub.isHybrid());
                    }
                } else {
                    cd = (ClassData) key;
                    long raw = heap.arrayUnalignedSize(cd.arrayClass(), cd.arrayComponentType(), cd.arrayLength());
                    base = MathUtil.align(raw, OBJECT_ALIGNMENT);
                    if (hashMode == HashMode.OPTIONAL) {
                        long hashed = MathUtil.align(MathUtil.align(raw, 4) + 4, OBJECT_ALIGNMENT);
                        if (hashed > base) {
                            withHash = hashed;
                        }
                    }
                }

                Sizes s = heap.sizes.get(cd);
                if (s == null) {
                    s = new Sizes();
                    heap.sizes.put(cd, s);
                }
                Multiset<Long> ms = e.getValue();
                for (Long gap : ms.keys()) {
                    long n = ms.count(gap);
                    long size;
                    if (gap == base) {
                        size = base;
                    } else if (withHash > 0 && gap == withHash) {
                        size = withHash;
                        s.withHashField += n;
                    } else if (hybrid && gap > base && gap - base <= MAX_HYBRID_EXTRA) {
                        size = gap;
                        s.hybridExtra += (gap - base) * n;
                        s.hybridSized += n;
                    } else {
                        size = base;
                        if (withHash > 0 || hybrid) {
                            s.uncertain += n;
                        }
                    }
                    s.add(size, n);
                }
            }

            for (Sizes s : heap.sizes.values()) {
                heap.total.count += s.count;
                heap.total.bytes += s.bytes;
                heap.total.withHashField += s.withHashField;
                heap.total.uncertain += s.uncertain;
                heap.total.hybridExtra += s.hybridExtra;
            }
            return heap;
        }
    }
}
