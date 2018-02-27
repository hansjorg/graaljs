/*
 * Copyright (c) 2017, 2017, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.regex.tregex.matchers;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.regex.util.CompilationFinalBitSet;

/**
 * Specialized {@link BitSetMatcher} that exists simply because ascii bit set matchers occur often
 * and we can save one comparison when the high byte is {@code 0x00}.
 */
public final class NullHighByteBitSetMatcher extends ProfiledCharMatcher {

    private final CompilationFinalBitSet bitSet;

    NullHighByteBitSetMatcher(boolean inverse, CompilationFinalBitSet bitSet) {
        super(inverse);
        this.bitSet = bitSet;
    }

    @Override
    protected boolean matchChar(char c) {
        return bitSet.get(c);
    }

    @Override
    @CompilerDirectives.TruffleBoundary
    public String toString() {
        return modifiersToString() + "{ascii " + bitSet + "}";
    }
}
