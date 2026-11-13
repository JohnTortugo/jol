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
 * Object sizes for heap dump reports: from the dump itself for GraalVM Native Image heap dumps
 * (see {@link NativeImageHeap}), from the layouter for other heap dumps.
 */
public final class HeapDumpSizes {

    private final NativeImageHeap heap;
    private final Layouter layouter;

    /**
     * @param reader   the reader, after {@link HeapDumpReader#parse()}
     * @param layouter the layouter for heap dumps that do not carry their own sizes
     */
    public HeapDumpSizes(HeapDumpReader reader, Layouter layouter) {
        this.heap = reader.nativeImageHeap();
        this.layouter = layouter;
    }

    /** The Native Image sizes behind these sizes, or null for other heap dumps. */
    public NativeImageHeap nativeImageHeap() {
        return heap;
    }

    /** Size of one object of this class. */
    public long instanceSize(ClassData cd) {
        return (heap != null) ? heap.instanceSize(cd) : layouter.layout(cd).instanceSize();
    }

    /** Size of the {@code count} objects of this class that the heap dump has. */
    public long totalSize(ClassData cd, long count) {
        return (heap != null && heap.count(cd) == count) ? heap.totalSize(cd) : instanceSize(cd) * count;
    }

    @Override
    public String toString() {
        return (heap != null) ? heap.toString() : layouter.toString();
    }
}
