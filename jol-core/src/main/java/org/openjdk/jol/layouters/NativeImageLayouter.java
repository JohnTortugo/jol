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

import org.openjdk.jol.datamodel.DataModel;
import org.openjdk.jol.heap.NativeImageHeap;
import org.openjdk.jol.heap.NativeImageHeap.HashMode;
import org.openjdk.jol.info.ClassData;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.info.FieldData;
import org.openjdk.jol.info.FieldLayout;
import org.openjdk.jol.util.MathUtil;
import org.openjdk.jol.util.Multiset;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Lays out the classes of a GraalVM Native Image heap dump as another image build would.
 * <p>
 * The rules follow the image builder's field layout (UniverseBuilder.layoutInstanceFields):
 * fields are placed in JavaKind order, references first, each into the smallest gap that fits,
 * otherwise at the end; a subclass starts from its superclass layout without the superclass
 * lock and hash fields; the lock field (as large as a reference) and the identity hash field
 * (4 bytes) then take a gap or go to the end. Which classes have a lock field is read from the
 * dump. Layout the rules do not explain, like {@code @Contended} padding, is carried over from
 * the dump as a per-class difference.
 */
public class NativeImageLayouter implements Layouter {

    /** Image build modes that change object layout. */
    public enum Mode {
        SERIAL(4, 4, HashMode.OPTIONAL,
                "Serial GC, compressed references (default)"),
        SERIAL_HASH_FIELD_ALWAYS(4, 4, HashMode.TYPE_SPECIFIC,
                "Serial GC, identity hash field in every object (-H:-OptionalIdentityHashCodes)"),
        SERIAL_FULL_REFERENCES(8, 8, HashMode.TYPE_SPECIFIC,
                "Serial GC, 8-byte references (-H:-UseCompressedReferences)"),
        G1(4, 8, HashMode.OBJECT_HEADER,
                "G1 GC, identity hash in the object header (--gc=G1, Oracle GraalVM)");

        private final int referenceSize;
        private final int headerSize;
        private final HashMode hashMode;
        private final String description;

        Mode(int referenceSize, int headerSize, HashMode hashMode, String description) {
            this.referenceSize = referenceSize;
            this.headerSize = headerSize;
            this.hashMode = hashMode;
            this.description = description;
        }

        public int referenceSize() {
            return referenceSize;
        }

        public HashMode hashMode() {
            return hashMode;
        }

        public String description() {
            return description;
        }

        /** Offset of the first element of a byte array. */
        int arrayHeaderSize() {
            // Serial: hub, length. With a hash field in every object: hub, hash, length. G1: mark word, hub, length.
            return headerSize + ((hashMode == HashMode.TYPE_SPECIFIC) ? 8 : 4);
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

    /** Bytes of padding around @Contended fields and classes (-H:ContendedPaddingWidth). */
    private static final int CONTENDED_PADDING = 128;

    private final Mode mode;
    private final Mode dumpMode;
    private final NativeImageHeap heap;
    private final DataModel model;
    private final Map<String, Layout> layouts = new HashMap<>();
    private final Map<String, Layout> dumpLayouts;
    private final Map<String, Boolean> contended = new HashMap<>();
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
        this.model = new Model(mode);
        this.dumpLayouts = (dumpMode == mode || dumpMode == null) ? layouts : new HashMap<String, Layout>();
    }

    public Mode mode() {
        return mode;
    }

    @Override
    public ClassLayout layout(ClassData cd) {
        return ClassLayout.create(cd, new TreeSet<FieldLayout>(), model, instanceSize(cd), false);
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
            int elementSize = sizeOf(cd.arrayComponentType(), mode.referenceSize);
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
            refs += isReference(fd.typeClass()) ? 1 : 0;
        }
        long fixed = hub.arrayBaseOffset() + (mode.arrayHeaderSize() - dumpMode.arrayHeaderSize())
                + (long) refs * (mode.referenceSize - dumpMode.referenceSize);
        return MathUtil.align(fixed, alignment);
    }

    private static long hubFixedSize(ClassData cd, Mode m, int typeCheckSlots, int alignment) {
        BitSet used = new BitSet();
        int afterLength = m.arrayHeaderSize();
        used.set(0, afterLength);                               // header, header hash if any, vtable length
        used.set(afterLength, afterLength + 2 * typeCheckSlots); // type check slots, 2 bytes each
        for (FieldData fd : sortedFields(cd.ownFields())) {
            place(used, sizeOf(fd.typeClass(), m.referenceSize), 0);
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
        if (l == null) {
            l = layout(cd, m, isContended(cd), cache);
            cache.put(cd.name(), l);
        }
        return l;
    }

    /**
     * Whether the class is @Contended: the dump does not show annotations, but a class that is
     * larger than its fields by both paddings is one.
     */
    private boolean isContended(ClassData cd) {
        Boolean b = contended.get(cd.name());
        if (b == null) {
            b = false;
            NativeImageHeap.Hub hub = heap.hub(cd.name());
            if (dumpMode != null && hub != null && hub.isPureInstance()) {
                Layout plain = layout(cd, dumpMode, false, new HashMap<String, Layout>());
                b = hub.instanceSize() - plain.size >= 2 * CONTENDED_PADDING;
            }
            contended.put(cd.name(), b);
        }
        return b;
    }

    private Layout layout(ClassData cd, Mode m, boolean isContended, Map<String, Layout> cache) {
        BitSet used;
        ClassData superClass = cd.superClass();
        if (superClass != null) {
            used = (BitSet) layout(superClass, m, cache).usedForSubclasses.clone();
        } else {
            used = new BitSet();
            used.set(0, m.headerSize);
        }

        List<FieldData> own = sortedFields(cd.ownFields());
        if (isContended) {
            // All fields form one group, padded on both sides; subclass fields stay out of it.
            used.set(used.length(), used.length() + CONTENDED_PADDING);
            int first = used.length();
            for (FieldData fd : own) {
                place(used, sizeOf(fd.typeClass(), m.referenceSize), first);
            }
            used.set(first, used.length());
            used.set(used.length(), used.length() + CONTENDED_PADDING);
        } else {
            for (FieldData fd : own) {
                place(used, sizeOf(fd.typeClass(), m.referenceSize), 0);
            }
        }
        BitSet usedForSubclasses = (BitSet) used.clone();

        NativeImageHeap.Hub hub = heap.hub(cd.name());
        if (hub != null && hub.hasMonitorField()) {
            place(used, m.referenceSize, 0);
        }

        int afterFields = used.length();
        int size;
        int sizeWithHashField;
        switch (m.hashMode) {
            case OPTIONAL: {
                int offset = place(used, 4, 0);
                size = MathUtil.align(afterFields, OBJECT_ALIGNMENT);
                sizeWithHashField = Math.max(size, MathUtil.align(offset + 4, OBJECT_ALIGNMENT));
                break;
            }
            case TYPE_SPECIFIC: {
                int offset = place(used, 4, 0);
                size = MathUtil.align(Math.max(afterFields, offset + 4), OBJECT_ALIGNMENT);
                sizeWithHashField = size;
                break;
            }
            default:
                size = MathUtil.align(afterFields, OBJECT_ALIGNMENT);
                sizeWithHashField = size;
                break;
        }
        return new Layout(usedForSubclasses, size, sizeWithHashField);
    }

    /** Fields in the order the image builder places them: by JavaKind in reverse, references first; stable. */
    private static List<FieldData> sortedFields(List<FieldData> fields) {
        List<FieldData> sorted = new ArrayList<>(fields);
        sorted.sort((f1, f2) -> Integer.compare(kindOrder(f2.typeClass()), kindOrder(f1.typeClass())));
        return sorted;
    }

    /**
     * Reserves {@code size} bytes in the smallest gap at or after {@code minOffset} that fits at
     * {@code size} alignment, or else at the end; returns the offset.
     */
    private static int place(BitSet used, int size, int minOffset) {
        int end = used.length();
        int best = -1;
        int bestSize = -1;
        int offset = used.nextClearBit(minOffset);
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

    private static boolean isReference(String type) {
        return kindOrder(type) == 8;
    }

    private static int kindOrder(String type) {
        switch (type) {
            case "boolean":
                return 0;
            case "byte":
                return 1;
            case "short":
                return 2;
            case "char":
                return 3;
            case "int":
                return 4;
            case "float":
                return 5;
            case "long":
                return 6;
            case "double":
                return 7;
            default:
                return 8;
        }
    }

    private static int sizeOf(String type, int referenceSize) {
        switch (type) {
            case "boolean":
            case "byte":
                return 1;
            case "short":
            case "char":
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

    @Override
    public String toString() {
        return "Native Image Layout Simulation (" + mode.description + ")";
    }

    private static final class Model implements DataModel {
        private final Mode mode;

        Model(Mode mode) {
            this.mode = mode;
        }

        @Override
        public int markHeaderSize() {
            // Only the G1 header has a word besides the hub: it keeps the identity hash.
            return mode.headerSize - mode.referenceSize;
        }

        @Override
        public int classHeaderSize() {
            return mode.headerSize - markHeaderSize();
        }

        @Override
        public int arrayLengthHeaderSize() {
            return mode.arrayHeaderSize() - mode.headerSize;
        }

        @Override
        public int headerSize() {
            return mode.headerSize;
        }

        @Override
        public int arrayHeaderSize() {
            return mode.arrayHeaderSize();
        }

        @Override
        public int sizeOf(String klass) {
            return NativeImageLayouter.sizeOf(klass, mode.referenceSize);
        }

        @Override
        public int objectAlignment() {
            return OBJECT_ALIGNMENT;
        }

        @Override
        public int addressSize() {
            return 8;
        }

        @Override
        public String toString() {
            return mode.description;
        }
    }
}
