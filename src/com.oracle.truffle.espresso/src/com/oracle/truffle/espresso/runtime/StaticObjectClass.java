/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.espresso.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.espresso.impl.Klass;

public final class StaticObjectClass extends StaticObjectImpl {
    @CompilerDirectives.CompilationFinal
    private Klass mirror;

    public void setMirror(Klass mirror) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        assert this.mirror == null;
        this.mirror = mirror;
    }

    public Klass getMirror() {
        assert this.mirror != null;
        return this.mirror;
    }

    public StaticObjectClass(Klass klass) {
        super(klass);
    }

    public StaticObjectClass(Klass klass, boolean isStatic) {
        super(klass, isStatic);
    }

    @Override
    public String toString() {
        return "class " + mirror.getName();
    }
}
