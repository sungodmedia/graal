/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package com.oracle.graal.pointsto.typestore;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.graal.pointsto.flow.ArrayElementsTypeFlow;
import com.oracle.graal.pointsto.flow.context.object.AnalysisObject;

/**
 * Store for array elements type flows. The read and write flows are separated.
 */
public class SplitArrayElementsTypeStore extends ArrayElementsTypeStore {

    /** The holder of the field flow (null for static fields). */
    private final ArrayElementsTypeFlow writeFlow;
    private final ArrayElementsTypeFlow readFlow;

    public SplitArrayElementsTypeStore(AnalysisObject object) {
        super(object);
        this.writeFlow = new ArrayElementsTypeFlow(object);
        this.readFlow = new ArrayElementsTypeFlow(object);
    }

    @Override
    public ArrayElementsTypeFlow writeFlow() {
        return writeFlow;
    }

    @Override
    public ArrayElementsTypeFlow readFlow() {
        return readFlow;
    }

    @Override
    public void init(BigBang bb) {
        this.writeFlow.addUse(bb, readFlow);
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append("SplitArrayElementsStore<").append(object).append(">");
        return str.toString();
    }

}
