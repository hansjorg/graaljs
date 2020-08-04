/*
 * Copyright (c) 2018, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.js.nodes.control;

import java.util.Set;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.InstrumentableNode;
import com.oracle.truffle.api.instrumentation.Tag;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.js.nodes.JSNodeUtil;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.instrumentation.JSTaggedExecutionNode;
import com.oracle.truffle.js.nodes.instrumentation.JSTags;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowBlockTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowBranchTag;
import com.oracle.truffle.js.nodes.instrumentation.JSTags.ControlFlowRootTag;

/**
 * Switch.
 *
 * <pre>
 * <b>switch</b> (switchExpression) {
 * <b>case</b> caseExpression: [statements];
 * <b>default</b>: [statements]
 * }
 * </pre>
 */
@NodeInfo(shortName = "switch")
public final class SwitchNode extends StatementNode {

    @Children private final JavaScriptNode[] caseExpressions;
    /**
     * jumptable[i] has the index of the first statement that should be executed if
     * caseExpression[i] equals switchExpression. jumptable[jumptable.length-1] is always the
     * statement index of the default case.
     */
    @CompilationFinal(dimensions = 1) private final int[] jumptable;
    @Children private final JavaScriptNode[] statements;
    private final boolean ordered;

    private SwitchNode(JavaScriptNode[] caseExpressions, int[] jumptable, JavaScriptNode[] statements) {
        this.caseExpressions = caseExpressions;
        this.jumptable = jumptable;
        assert caseExpressions.length == jumptable.length - 1;
        this.statements = statements;
        this.ordered = isMonotonicallyIncreasing(jumptable);
    }

    private static boolean isMonotonicallyIncreasing(int[] table) {
        for (int i = 0; i < table.length - 1; i++) {
            int start = table[i];
            int end = table[i + 1];
            if (start > end) {
                return false;
            }
        }
        return true;
    }

    public static SwitchNode create(JavaScriptNode[] caseExpressions, int[] jumptable, JavaScriptNode[] statements) {
        return new SwitchNode(caseExpressions, jumptable, statements);
    }

    @Override
    public boolean hasTag(Class<? extends Tag> tag) {
        if (tag == ControlFlowRootTag.class) {
            return true;
        }
        return super.hasTag(tag);
    }

    @Override
    public Object getNodeObject() {
        return JSTags.createNodeObjectDescriptor("type", ControlFlowRootTag.Type.Conditional.name());
    }

    @Override
    public InstrumentableNode materializeInstrumentableNodes(Set<Class<? extends Tag>> materializedTags) {
        if (materializedTags.contains(ControlFlowRootTag.class) && needsMaterialization()) {
            JavaScriptNode[] newCaseExpressions = new JavaScriptNode[caseExpressions.length];
            boolean wasChanged = false;
            for (int i = 0; i < caseExpressions.length; i++) {
                InstrumentableNode materialized = caseExpressions[i].materializeInstrumentableNodes(materializedTags);
                newCaseExpressions[i] = JSTaggedExecutionNode.createForInput((JavaScriptNode) materialized, ControlFlowBranchTag.class,
                                JSTags.createNodeObjectDescriptor("type", ControlFlowBranchTag.Type.Condition.name()), materializedTags);
                if (newCaseExpressions[i] != caseExpressions[i]) {
                    wasChanged = true;
                }
            }
            JavaScriptNode[] newStatements = new JavaScriptNode[statements.length];
            for (int i = 0; i < statements.length; i++) {
                InstrumentableNode materialized = statements[i].materializeInstrumentableNodes(materializedTags);
                newStatements[i] = JSTaggedExecutionNode.createFor((JavaScriptNode) materialized, ControlFlowBlockTag.class, materializedTags);
                if (newStatements[i] != statements[i]) {
                    wasChanged = true;
                }
            }
            if (!wasChanged) {
                return this;
            } else {
                // clone expressions and statements that were not cloned by materialization
                for (int i = 0; i < caseExpressions.length; i++) {
                    if (newCaseExpressions[i] == caseExpressions[i]) {
                        newCaseExpressions[i] = cloneUninitialized(caseExpressions[i], materializedTags);
                    }
                }
                for (int i = 0; i < statements.length; i++) {
                    if (newStatements[i] == statements[i]) {
                        newStatements[i] = cloneUninitialized(statements[i], materializedTags);
                    }
                }
            }
            SwitchNode materialized = SwitchNode.create(newCaseExpressions, jumptable, newStatements);
            transferSourceSectionAndTags(this, materialized);
            return materialized;
        } else {
            return this;
        }
    }

    private boolean needsMaterialization() {
        boolean needsMaterialization = false;
        for (int i = 0; i < caseExpressions.length && !needsMaterialization; i++) {
            if (!JSNodeUtil.isTaggedNode(caseExpressions[i])) {
                needsMaterialization = true;
            }
        }
        for (int i = 0; i < statements.length && !needsMaterialization; i++) {
            if (!JSNodeUtil.isTaggedNode(statements[i])) {
                needsMaterialization = true;
            }
        }
        return needsMaterialization;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (ordered) {
            return executeOrdered(frame);
        } else {
            return executeDefault(frame);
        }
    }

    private Object executeDefault(VirtualFrame frame) {
        int statementStartIndex = identifyTargetCase(frame);
        return executeStatements(frame, statementStartIndex);
    }

    @ExplodeLoop
    private int identifyTargetCase(VirtualFrame frame) {
        int i;
        for (i = 0; i < caseExpressions.length; i++) {
            if (executeConditionAsBoolean(frame, caseExpressions[i])) {
                break;
            }
        }
        int statementStartIndex = jumptable[i];
        CompilerAsserts.partialEvaluationConstant(statementStartIndex);
        return statementStartIndex;
    }

    @ExplodeLoop
    private Object executeStatements(VirtualFrame frame, int statementStartIndex) {
        Object result = EMPTY;
        for (int statementIndex = 0; statementIndex < statements.length; statementIndex++) {
            if (statementIndex >= statementStartIndex) {
                result = statements[statementIndex].execute(frame);
            }
        }
        return result;
    }

    @ExplodeLoop
    private Object executeOrdered(VirtualFrame frame) {
        final JavaScriptNode[] caseExpressionsLocal = caseExpressions;
        final JavaScriptNode[] statementsLocal = statements;
        final int[] jumptableLocal = jumptable;

        boolean caseFound = false;
        Object result = EMPTY;

        int jumptableIdx;
        for (jumptableIdx = 0; jumptableIdx < caseExpressionsLocal.length; jumptableIdx++) {
            if (caseFound || executeConditionAsBoolean(frame, caseExpressionsLocal[jumptableIdx])) {
                caseFound = true;
            }

            if (caseFound) {
                int statementStartIndex = jumptableLocal[jumptableIdx];
                int statementEndIndex = jumptableLocal[jumptableIdx + 1];
                CompilerAsserts.partialEvaluationConstant(statementStartIndex);
                CompilerAsserts.partialEvaluationConstant(statementEndIndex);
                if (statementStartIndex != statementEndIndex) {
                    // Optional hack to clear any conditional value out of the frame state.
                    // Helps the compiler see the value is always true here, so it won't attempt to
                    // emit extra code to compute it and keep it alive only for the frame state.
                    caseFound = true;

                    for (int statementIndex = statementStartIndex; statementIndex < statementEndIndex; statementIndex++) {
                        result = statementsLocal[statementIndex].execute(frame);
                    }
                }
            }
        }

        // default case
        int statementStartIndex = jumptableLocal[jumptableIdx];
        CompilerAsserts.partialEvaluationConstant(statementStartIndex);
        for (int statementIndex = statementStartIndex; statementIndex < statementsLocal.length; statementIndex++) {
            result = statementsLocal[statementIndex].execute(frame);
        }
        return result;
    }

    @Override
    protected JavaScriptNode copyUninitialized(Set<Class<? extends Tag>> materializedTags) {
        return create(cloneUninitialized(caseExpressions, materializedTags), jumptable, cloneUninitialized(statements, materializedTags));
    }
}
