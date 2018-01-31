/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.regex;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.regex.tregex.parser.Counter;

/**
 * This profile is used for tracking statistics about a compiled regular expression, such as the
 * amount of times the expression was executed and the amount of matches that were found. The
 * profiling information is used by TRegex for deciding whether a regular expression should match
 * capture groups in a lazy or eager way.
 *
 * @see com.oracle.truffle.regex.tregex.nodes.TRegexExecRootNode
 * @see com.oracle.truffle.regex.tregex.nodes.TRegexLazyCaptureGroupsRootNode
 */
public final class RegexProfile {

    private final Counter.ThreadSafeCounter calls = new Counter.ThreadSafeCounter();
    private final Counter.ThreadSafeCounter matches = new Counter.ThreadSafeCounter();
    private final Counter.ThreadSafeCounter captureGroupAccesses = new Counter.ThreadSafeCounter();
    private double avgMatchedPortionOfSearchSpace = 0;

    /**
     * Increase the number of times the regular expression was executed by one.
     */
    public void incCalls() {
        calls.inc();
    }

    /**
     * Increase the number of times a match for the regular expression was found by one.
     */
    public void incMatches() {
        matches.inc();
    }

    /**
     * Increase the number of times any capture groups of a match result were queried by one.
     */
    public void incCaptureGroupAccesses() {
        captureGroupAccesses.inc();
    }

    /**
     * Update the average matched portion of the searched part of the input.
     * 
     * @param matchedPortion the length of capture group zero divided by the amount of characters
     *            the regular expression matcher traversed while searching for the match.
     */
    public void addMatchedPortionOfSearchSpace(double matchedPortion) {
        assert captureGroupAccesses.getCount() > 0;
        avgMatchedPortionOfSearchSpace += (matchedPortion - avgMatchedPortionOfSearchSpace) / captureGroupAccesses.getCount();
    }

    /**
     * Check if the profiling information gathered so far is sufficient for making a decision.
     * 
     * @return <code>true</code> if the number of times the regular expression was called is
     *         divisible by 4096.
     */
    public boolean atEvaluationTripPoint() {
        // evaluate profile after every 800 calls
        return calls.getCount() > 0 && (calls.getCount() % 800) == 0;
    }

    private double matchRatio() {
        assert calls.getCount() > 0;
        return (double) matches.getCount() / calls.getCount();
    }

    private double cgAccessRatio() {
        assert matches.getCount() > 0;
        return (double) captureGroupAccesses.getCount() / matches.getCount();
    }

    /**
     * Decides whether the capture groups of the regular expression should be matched in an eager
     * manner.
     * 
     * @return <code>true</code> if:
     *         <ul>
     *         <li>most searches led to a match</li>
     *         <li>the capture groups of most search results were queried</li>
     *         <li>the match often covered a big part of the part of the input string that had to be
     *         traversed in order to find it</li>
     *         </ul>
     */
    public boolean shouldUseEagerMatching() {
        return matchRatio() > 0.5 && cgAccessRatio() > 0.5 && avgMatchedPortionOfSearchSpace > 0.4;
    }

    @CompilerDirectives.TruffleBoundary
    @Override
    public String toString() {
        return String.format("calls: %d, matches: %d (%.2f%%), cg accesses: %d (%.2f%%), avg matched portion of search space: %.2f%%",
                        calls.getCount(), matches.getCount(), matchRatio() * 100, captureGroupAccesses.getCount(), cgAccessRatio() * 100, avgMatchedPortionOfSearchSpace * 100);
    }
}
