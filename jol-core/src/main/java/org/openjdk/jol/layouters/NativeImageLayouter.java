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
package org.openjdk.jol.layouters;

import org.openjdk.jol.heap.NativeImageHeap;
import org.openjdk.jol.heap.NativeImageHeap.HashMode;
import org.openjdk.jol.info.ClassData;
import org.openjdk.jol.info.FieldData;
import org.openjdk.jol.util.MathUtil;
import org.openjdk.jol.util.Multiset;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lays out the classes of a GraalVM Native Image heap dump as another image build would.
 * <p>
 * The rules follow the image builder (UniverseBuilder.layoutInstanceFields): references first,
 * then larger fields first, each into the smallest gap that fits, otherwise at the end; a
 * subclass starts from its superclass layout without the superclass lock and hash fields; the
 * lock field (as large as a reference) and the identity hash field (4 bytes) then take a gap
 * or go to the end. Which classes have a lock field is read from the dump. Layout that the
 * rules do not explain, like {@code @Contended} padding, carries over from the dump.
 */
public class NativeImageLayouter {

    /** Image build modes that change object layout. */
    public enum Mode {
        SERIAL(4, HashMode.OPTIONAL,
                "Serial GC, compressed references (default)"),
        SERIAL_HASH_FIELD_ALWAYS(4, HashMode.TYPE_SPECIFIC,
                "Serial GC, identity hash field in every object (-H:-OptionalIdentityHashCodes)"),
        SERIAL_FULL_REFERENCES(8, HashMode.TYPE_SPECIFIC,
                "Serial GC, 8-byte references (-H:-UseCompressedReferences)");

        private final int referenceSize;
        private final HashMode hashMode;
        private final String description;

        Mode(int referenceSize, HashMode hashMode, String description) {
            this.referenceSize = referenceSize;
            this.hashMode = hashMode;
            this.description = description;
        }

        public String description() {
            return description;
        }

        /** Offset of the first byte[] element: the hub, a hash field if every object has one, the length. */
        int arrayHeaderSize() {
            return referenceSize + ((hashMode == HashMode.TYPE_SPECIFIC) ? 8 : 4);
        }

        /** The mode of the image build that wrote a heap dump, or null if no mode matches. */
        public static Mode of(NativeImageHeap heap) {
            for (Mode m : values()) {
                if (m.referenceSize == heap.referenceSize() && m.hashMode == heap.hashMode() && m.arrayHeaderSize() == heap.arrayHeaderSize()) {
                    return m;
                }
            }
            return null;
        }
    }

    private static final int OBJECT_ALIGNMENT = 8;

    private final Mode mode;
    private final Mode dumpMode;
    private final NativeImageHeap heap;
    private final Map<String, Layout> layouts = new HashMap<>();
    private final Map<String, Layout> dumpLayouts;
    private Integer hubTypeCheckSlots; // found on first use; -1 if no count matches the dump

    private static final class Layout {
        final BitSet usedForSubclasses;
        final int size;
        final int sizeWithHashField;

        Layout(BitSet usedForSubclasses, int size, int sizeWithHashField) {
            this.usedForSubclasses = usedForSubclasses;
            this.size = size;
            this.sizeWithHashField = sizeWithHashField;
        }
    }

    /**
     * @param mode the image build mode to lay out objects for
     * @param heap the heap dump that tells which classes have lock fields, and how the dump lays them out
     */
    public NativeImageLayouter(Mode mode, NativeImageHeap heap) {
        this.mode = mode;
        this.heap = heap;
        this.dumpMode = Mode.of(heap);
        this.dumpLayouts = (dumpMode == mode || dumpMode == null) ? layouts : new HashMap<String, Layout>();
    }

    /** Size of an object of this class, without the optional identity hash field. */
    public long instanceSize(ClassData cd) {
        return sizes(cd)[0];
    }

    /** Size of an object of this class with the optional identity hash field, if the mode has one. */
    public long instanceSizeWithHashField(ClassData cd) {
        return sizes(cd)[1];
    }

    private long[] sizes(ClassData cd) {
        if (cd.isArray()) {
            int elementSize = NativeImageHeap.sizeOf(cd.arrayComponentType(), mode.referenceSize);
            long raw = MathUtil.align(mode.arrayHeaderSize(), elementSize) + cd.arrayLength() * elementSize;
            long size = MathUtil.align(raw, OBJECT_ALIGNMENT);
            long hashed = (mode.hashMode == HashMode.OPTIONAL) ? Math.max(size, MathUtil.align(MathUtil.align(raw, 4) + 4, OBJECT_ALIGNMENT)) : size;
            return new long[]{size, hashed};
        }
        NativeImageHeap.Hub hub = heap.hub(cd.name());
        if (hub != null && hub.isHybrid() && dumpMode != null) {
            long fixed = hybridFixedSize(cd, hub);
            return new long[]{fixed, fixed};
        }
        Layout l = layout(cd, mode, layouts);
        long delta = 0;
        if (hub != null && hub.isPureInstance() && dumpMode != null) {
            // What the rules do not explain in the dump (like @Contended padding) carries over.
            delta = Math.max(0, hub.instanceSize() - layout(cd, dumpMode, dumpLayouts).size);
        }
        return new long[]{l.size + delta, l.sizeWithHashField + delta};
    }

    /**
     * The fixed part of a hybrid object: everything before its inline array. A hub
     * (java.lang.Class) keeps its vtable length where arrays keep their length, then its type
     * check slots, then its fields; the vtable starts after the fields. The dump shows the least
     * number of type check slots; the layout of the dump picks the actual number from there.
     * Other hybrid classes move with the header and reference sizes.
     */
    private long hybridFixedSize(ClassData cd, NativeImageHeap.Hub hub) {
        int alignment = 1 << hub.arrayIndexShift();
        if (cd.name().equals("java.lang.Class")) {
            if (hubTypeCheckSlots == null) {
                hubTypeCheckSlots = -1;
                for (int n = Math.max(0, heap.typeCheckSlots()); n <= 1024; n++) {
                    if (hubFixedSize(cd, dumpMode, n, alignment) == hub.arrayBaseOffset()) {
                        hubTypeCheckSlots = n;
                        break;
                    }
                }
            }
            if (hubTypeCheckSlots >= 0) {
                return hubFixedSize(cd, mode, hubTypeCheckSlots, alignment);
            }
        }
        int refs = 0;
        for (FieldData fd : cd.fields()) {
            refs += fd.isPrimitive() ? 0 : 1;
        }
        long fixed = hub.arrayBaseOffset() + (mode.arrayHeaderSize() - dumpMode.arrayHeaderSize())
                + (long) refs * (mode.referenceSize - dumpMode.referenceSize);
        return MathUtil.align(fixed, alignment);
    }

    private static long hubFixedSize(ClassData cd, Mode m, int typeCheckSlots, int alignment) {
        BitSet used = new BitSet();
        int afterLength = m.arrayHeaderSize();
        used.set(0, afterLength);                               // hub, hash field if any, vtable length
        used.set(afterLength, afterLength + 2 * typeCheckSlots); // type check slots, 2 bytes each
        for (FieldData fd : sortedFields(cd.ownFields())) {
            place(used, NativeImageHeap.sizeOf(fd.typeClass(), m.referenceSize));
        }
        return MathUtil.align(used.length(), alignment);
    }

    /**
     * Bytes that the hash field of hybrid objects adds in a mode: every hub is an image heap object,
     * so with an optional hash field, each hub carries it after its vtable.
     */
    private static long hubHashField(Mode m) {
        return (m.hashMode == HashMode.OPTIONAL) ? 8 : 0;
    }

    /**
     * Footprint of the dumped objects in this mode. Objects that carry the optional identity hash
     * field in the dump carry it here too; hybrid objects keep their inline array part.
     */
    public long footprint(Multiset<ClassData> data) {
        long total = 0;
        for (ClassData cd : data.keys()) {
            long n = data.count(cd);
            if (n == 0) {
                continue;
            }
            NativeImageHeap.Hub hub = cd.isArray() ? null : heap.hub(cd.name());
            if (hub != null && hub.isHybrid()) {
                long extra = heap.hybridExtra(cd);
                if (cd.name().equals("java.lang.Class") && dumpMode != null) {
                    extra += heap.hybridSized(cd) * (hubHashField(mode) - hubHashField(dumpMode));
                }
                total += n * instanceSize(cd) + extra;
            } else if (mode.hashMode == HashMode.OPTIONAL) {
                long hashed = Math.min(n, heap.withHashField(cd));
                total += (n - hashed) * instanceSize(cd) + hashed * instanceSizeWithHashField(cd);
            } else {
                total += n * instanceSize(cd);
            }
        }
        return total;
    }

    private Layout layout(ClassData cd, Mode m, Map<String, Layout> cache) {
        Layout l = cache.get(cd.name());
        if (l != null) {
            return l;
        }
        BitSet used;
        ClassData superClass = cd.superClass();
        if (superClass != null) {
            used = (BitSet) layout(superClass, m, cache).usedForSubclasses.clone();
        } else {
            used = new BitSet();
            used.set(0, m.referenceSize); // the hub
        }
        for (FieldData fd : sortedFields(cd.ownFields())) {
            place(used, NativeImageHeap.sizeOf(fd.typeClass(), m.referenceSize));
        }
        BitSet usedForSubclasses = (BitSet) used.clone();

        NativeImageHeap.Hub hub = heap.hub(cd.name());
        if (hub != null && hub.hasMonitorField()) {
            place(used, m.referenceSize);
        }
        int afterFields = used.length();
        int hashOffset = place(used, 4);
        int size;
        int sizeWithHashField;
        if (m.hashMode == HashMode.OPTIONAL) {
            size = MathUtil.align(afterFields, OBJECT_ALIGNMENT);
            sizeWithHashField = Math.max(size, MathUtil.align(hashOffset + 4, OBJECT_ALIGNMENT));
        } else {
            size = MathUtil.align(Math.max(afterFields, hashOffset + 4), OBJECT_ALIGNMENT);
            sizeWithHashField = size;
        }
        l = new Layout(usedForSubclasses, size, sizeWithHashField);
        cache.put(cd.name(), l);
        return l;
    }

    /** Fields in the order the image builder places them: references first, then larger fields first. */
    private static List<FieldData> sortedFields(List<FieldData> fields) {
        List<FieldData> sorted = new ArrayList<>(fields);
        sorted.sort((f1, f2) -> Integer.compare(rank(f2), rank(f1)));
        return sorted;
    }

    private static int rank(FieldData fd) {
        return fd.isPrimitive() ? NativeImageHeap.sizeOf(fd.typeClass(), 0) : Integer.MAX_VALUE;
    }

    /**
     * Reserves {@code size} bytes in the smallest gap that fits at {@code size} alignment, or else
     * at the end; returns the offset.
     */
    private static int place(BitSet used, int size) {
        int end = used.length();
        int best = -1;
        int bestSize = -1;
        int offset = used.nextClearBit(0);
        while (offset < end) {
            int gap = used.nextSetBit(offset) - offset;
            int adjust = MathUtil.align(offset, size) - offset;
            if (gap >= adjust + size && (best == -1 || gap < bestSize)) {
                best = offset + adjust;
                bestSize = gap;
            }
            offset = used.nextClearBit(offset + gap);
        }
        if (best == -1) {
            best = MathUtil.align(end, size);
        }
        used.set(best, best + size);
        return best;
    }
}
