/*
 * Copyright (c) 2012, 2017, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.nodes.access;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.runtime.JSContext;

public class WritePropertyNode extends JSTargetableNode implements WriteNode {

    @Child protected JavaScriptNode targetNode;
    @Child protected JavaScriptNode rhsNode;
    @Child protected PropertySetNode cache;

    @CompilationFinal private byte valueState;
    private static final byte VALUE_INT = 1;
    private static final byte VALUE_DOUBLE = 2;
    private static final byte VALUE_OBJECT = 3;

    protected WritePropertyNode(JavaScriptNode target, JavaScriptNode rhs, Object propertyKey, boolean isGlobal, JSContext context, boolean isStrict) {
        this.targetNode = target;
        this.rhsNode = rhs;
        this.cache = PropertySetNode.create(propertyKey, isGlobal, context, isStrict);
    }

    public static WritePropertyNode create(JavaScriptNode target, Object propertyKey, JavaScriptNode rhs, JSContext ctx, boolean isStrict) {
        return create(target, propertyKey, rhs, false, ctx, isStrict);
    }

    public static WritePropertyNode create(JavaScriptNode target, Object propertyKey, JavaScriptNode rhs, boolean isGlobal, JSContext ctx, boolean isStrict) {
        return new WritePropertyNode(target, rhs, propertyKey, isGlobal, ctx, isStrict);
    }

    @Override
    public final JavaScriptNode getTarget() {
        return targetNode;
    }

    @Override
    public final JavaScriptNode getRhs() {
        return rhsNode;
    }

    public final Object executeWithValue(Object obj, Object value) {
        cache.setValue(obj, value);
        return value;
    }

    public final int executeIntWithValue(Object obj, int value) {
        cache.setValueInt(obj, value);
        return value;
    }

    public final double executeDoubleWithValue(Object obj, double value) {
        cache.setValueDouble(obj, value);
        return value;
    }

    private Object executeEvaluated(Object obj, Object value, Object receiver) {
        cache.setValue(obj, value, receiver);
        return value;
    }

    private int executeIntEvaluated(Object obj, int value, Object receiver) {
        cache.setValueInt(obj, value, receiver);
        return value;
    }

    private double executeDoubleEvaluated(Object obj, double value, Object receiver) {
        cache.setValueDouble(obj, value, receiver);
        return value;
    }

    @Override
    public final Object execute(VirtualFrame frame) {
        Object target = evaluateTarget(frame);
        Object receiver = evaluateReceiver(frame, target);
        Object value = rhsNode.execute(frame);
        return executeEvaluated(target, value, receiver);
    }

    @Override
    public final int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        Object target = evaluateTarget(frame);
        Object receiver = evaluateReceiver(frame, target);
        try {
            int value = rhsNode.executeInt(frame);
            return executeIntEvaluated(target, value, receiver);
        } catch (UnexpectedResultException e) {
            executeEvaluated(target, e.getResult(), receiver);
            throw e;
        }
    }

    @Override
    public final double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        Object target = evaluateTarget(frame);
        Object receiver = evaluateReceiver(frame, target);
        try {
            double value = rhsNode.executeDouble(frame);
            return executeDoubleEvaluated(target, value, receiver);
        } catch (UnexpectedResultException e) {
            executeEvaluated(target, e.getResult(), receiver);
            throw e;
        }
    }

    @Override
    public final void executeVoid(VirtualFrame frame) {
        Object target = evaluateTarget(frame);
        Object receiver = evaluateReceiver(frame, target);
        if (valueState == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            executeAndSpecialize(frame, target, receiver);
            return;
        }
        if (valueState == VALUE_INT) {
            try {
                int value = rhsNode.executeInt(frame);
                executeIntEvaluated(target, value, receiver);
            } catch (UnexpectedResultException e) {
                valueState = VALUE_OBJECT;
                executeEvaluated(target, e.getResult(), receiver);
            }
        } else if (valueState == VALUE_DOUBLE) {
            try {
                double value = rhsNode.executeDouble(frame);
                executeDoubleEvaluated(target, value, receiver);
            } catch (UnexpectedResultException e) {
                valueState = VALUE_OBJECT;
                executeEvaluated(target, e.getResult(), receiver);
            }
        } else {
            assert valueState == VALUE_OBJECT;
            Object value = rhsNode.execute(frame);
            executeEvaluated(target, value, receiver);
        }
    }

    private void executeAndSpecialize(VirtualFrame frame, Object target, Object receiver) {
        CompilerAsserts.neverPartOfCompilation();
        Object value = rhsNode.execute(frame);
        if (value instanceof Integer) {
            valueState = VALUE_INT;
            executeIntEvaluated(target, (int) value, receiver);
        } else if (value instanceof Double) {
            valueState = VALUE_DOUBLE;
            executeDoubleEvaluated(target, (double) value, receiver);
        } else {
            valueState = VALUE_OBJECT;
            executeEvaluated(target, value, receiver);
        }
    }

    @Override
    public final Object executeWrite(VirtualFrame frame, Object value) {
        Object target = evaluateTarget(frame);
        Object receiver = evaluateReceiver(frame, target);
        return executeEvaluated(target, value, receiver);
    }

    @Override
    public final Object executeWithTarget(VirtualFrame frame, Object target) {
        Object value = rhsNode.execute(frame);
        return executeEvaluated(target, value, target);
    }

    @Override
    public final Object evaluateTarget(VirtualFrame frame) {
        return targetNode.execute(frame);
    }

    public final Object evaluateReceiver(VirtualFrame frame, Object target) {
        if (!(targetNode instanceof SuperPropertyReferenceNode)) {
            return target;
        } else {
            return ((SuperPropertyReferenceNode) targetNode).getThisValue().execute(frame);
        }
    }

    @Override
    protected JavaScriptNode copyUninitialized() {
        return create(cloneUninitialized(targetNode), cache.getKey(), cloneUninitialized(rhsNode), cache.isGlobal(), cache.getContext(), cache.isStrict());
    }

    @Override
    public boolean isResultAlwaysOfType(Class<?> clazz) {
        return getRhs().isResultAlwaysOfType(clazz);
    }
}