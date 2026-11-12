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
import org.openjdk.jol.layouters.Layouter;

/**
 * Object sizes for heap dump reports. For HotSpot heap dumps, the sizes come from a layouter
 * that simulates the object layout. For GraalVM Native Image heap dumps, they come from the
 * dump itself, see {@link NativeImageHeap}.
 */
public abstract class HeapDumpSizes {

    /**
     * Sizes for a parsed heap dump.
     *
     * @param reader   the reader, after {@link HeapDumpReader#parse()}
     * @param layouter the layouter for heap dumps that do not carry their own sizes
     */
    public static HeapDumpSizes of(HeapDumpReader reader, Layouter layouter) {
        NativeImageHeap heap = reader.nativeImageHeap();
        return (heap != null) ? new NativeImageSizes(heap) : new LayouterSizes(layouter);
    }

    /** Size of one object of this class. */
    public abstract long instanceSize(ClassData cd);

    /** Size of the {@code count} objects of this class that the heap dump has. */
    public abstract long totalSize(ClassData cd, long count);

    /** The Native Image sizes behind these sizes, or null for other heap dumps. */
    public NativeImageHeap nativeImageHeap() {
        return null;
    }

    private static final class LayouterSizes extends HeapDumpSizes {
        private final Layouter layouter;

        LayouterSizes(Layouter layouter) {
            this.layouter = layouter;
        }

        @Override
        public long instanceSize(ClassData cd) {
            return layouter.layout(cd).instanceSize();
        }

        @Override
        public long totalSize(ClassData cd, long count) {
            return instanceSize(cd) * count;
        }

        @Override
        public String toString() {
            return layouter.toString();
        }
    }

    private static final class NativeImageSizes extends HeapDumpSizes {
        private final NativeImageHeap heap;

        NativeImageSizes(NativeImageHeap heap) {
            this.heap = heap;
        }

        @Override
        public long instanceSize(ClassData cd) {
            return heap.instanceSize(cd);
        }

        @Override
        public long totalSize(ClassData cd, long count) {
            if (heap.count(cd) == count) {
                return heap.totalSize(cd);
            }
            return heap.instanceSize(cd) * count;
        }

        @Override
        public NativeImageHeap nativeImageHeap() {
            return heap;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(heap.description());
            for (String note : heap.notes()) {
                sb.append(System.lineSeparator()).append("  ").append(note);
            }
            return sb.toString();
        }
    }
}
