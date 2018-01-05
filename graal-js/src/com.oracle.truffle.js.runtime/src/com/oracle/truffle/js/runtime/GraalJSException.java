/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.runtime;

import java.util.ArrayList;
import java.util.List;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.js.runtime.builtins.JSError;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.builtins.JSGlobalObject;
import com.oracle.truffle.js.runtime.builtins.JSProxy;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.Null;
import com.oracle.truffle.js.runtime.objects.PropertyDescriptor;
import com.oracle.truffle.js.runtime.objects.Undefined;

public abstract class GraalJSException extends RuntimeException implements TruffleException {
    private static final long serialVersionUID = -6624166672101791072L;
    private static final JSStackTraceElement[] EMPTY_STACK_TRACE = new JSStackTraceElement[0];
    private JSStackTraceElement[] jsStackTrace;
    private Node originatingNode;

    public GraalJSException(String message, Throwable cause) {
        super(message, cause);
    }

    public GraalJSException(String message) {
        super(message);
    }

    protected void init(Node node) {
        init(node, JSTruffleOptions.StackTraceLimit, Undefined.instance);
    }

    protected void init(Node node, int stackTraceLimit, DynamicObject skipFramesUpTo) {
        if (stackTraceLimit != 0) {
            jsStackTrace = getJSStackTrace(node, stackTraceLimit, skipFramesUpTo);
        } else {
            jsStackTrace = EMPTY_STACK_TRACE;
        }
        this.originatingNode = node;
    }

    @Override
    public Node getLocation() {
        return originatingNode;
    }

    @Override
    public Object getExceptionObject() {
        Object error = getErrorObject();
        return (error == null) ? null : JSRuntime.exportValue(error);
    }

    @Override
    public int getStackTraceElementLimit() {
        return JSTruffleOptions.StackTraceLimit;
    }

    /** Could still be null due to lazy initialization. */
    public abstract Object getErrorObject();

    /**
     * Eager access to the ErrorObject. Use only if you must get a non-null error object. Could
     * result in an error object from the wrong realm, thus non spec-compliant.
     */
    public abstract Object getErrorObjectEager(JSContext context);

    /**
     * Omit creating stack trace for JavaScript exceptions.
     */
    @SuppressWarnings("sync-override")
    @Override
    public final Throwable fillInStackTrace() {
        CompilerAsserts.neverPartOfCompilation("GraalJSException.fillInStackTrace");
        if (JSTruffleOptions.FillExceptionStack) {
            return super.fillInStackTrace();
        } else {
            return null;
        }
    }

    public JSStackTraceElement[] getJSStackTrace() {
        return jsStackTrace;
    }

    public void setJSStackTrace(JSStackTraceElement[] jsStackTrace) {
        this.jsStackTrace = jsStackTrace;
    }

    @TruffleBoundary
    public static JSStackTraceElement[] getJSStackTrace(Node originatingNode) {
        return getJSStackTrace(originatingNode, JSTruffleOptions.StackTraceLimit, Undefined.instance);
    }

    private static final int STACK_FRAME_SKIP = 0;
    private static final int STACK_FRAME_JS = 1;
    private static final int STACK_FRAME_FOREIGN = 2;

    private static int stackFrameType(Node callNode) {
        if (callNode == null) {
            return STACK_FRAME_SKIP;
        }
        SourceSection sourceSection = callNode.getEncapsulatingSourceSection();
        if (sourceSection == null) {
            return STACK_FRAME_SKIP;
        }
        if (JSFunction.isBuiltinSourceSection(sourceSection)) {
            return JSTruffleOptions.NashornCompatibilityMode ? STACK_FRAME_SKIP : STACK_FRAME_JS;
        }
        if (sourceSection.getSource().isInternal() || !sourceSection.isAvailable()) {
            return STACK_FRAME_SKIP;
        }
        if (JSRuntime.isJSRootNode(callNode.getRootNode())) {
            String sourceName = sourceSection.getSource().getName();
            if (sourceName.startsWith(JSRealm.INTERNAL_JS_FILE_NAME_PREFIX) || sourceName.equals(Evaluator.FUNCTION_SOURCE_NAME)) {
                return STACK_FRAME_SKIP;
            } else {
                return STACK_FRAME_JS;
            }
        } else {
            return STACK_FRAME_FOREIGN;
        }
    }

    @TruffleBoundary
    private static JSStackTraceElement[] getJSStackTrace(Node originatingNode, int stackTraceLimit, DynamicObject skipUpTo) {
        // Nashorn does not support skipping of frames
        DynamicObject skipFramesUpTo = JSTruffleOptions.NashornCompatibilityMode ? Undefined.instance : skipUpTo;
        if (stackTraceLimit > 0) {
            List<JSStackTraceElement> stackTrace = new ArrayList<>();
            Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<List<JSStackTraceElement>>() {
                private boolean inStrictMode;
                private boolean skippingFrames = (skipFramesUpTo != Undefined.instance);
                private boolean first = true;

                @Override
                public List<JSStackTraceElement> visitFrame(FrameInstance frameInstance) {
                    Node callNode = frameInstance.getCallNode();
                    if (first) {
                        assert callNode == null;
                        first = false;
                        callNode = originatingNode;
                    }
                    if (callNode == null) {
                        CallTarget callTarget = frameInstance.getCallTarget();
                        if (callTarget instanceof RootCallTarget) {
                            callNode = ((RootCallTarget) callTarget).getRootNode();
                        }
                    }
                    switch (stackFrameType(callNode)) {
                        case STACK_FRAME_JS:
                            if (JSRuntime.isJSFunctionRootNode(callNode.getRootNode())) {
                                Frame frame = frameInstance.getFrame(FrameAccess.READ_ONLY);
                                Object thisObj = JSArguments.getThisObject(frame.getArguments());
                                Object functionObj = JSArguments.getFunctionObject(frame.getArguments());
                                if (JSFunction.isJSFunction(functionObj)) {
                                    JSFunctionData functionData = JSFunction.getFunctionData((DynamicObject) functionObj);
                                    if (functionData.isStrict()) {
                                        inStrictMode = true;
                                    } else if (functionData.isBuiltin() && JSFunction.isStrictBuiltin((DynamicObject) functionObj)) {
                                        inStrictMode = true;
                                    }
                                    if (skippingFrames && functionObj == skipFramesUpTo) {
                                        skippingFrames = false;
                                        return null; // skip this frame as well
                                    }
                                    JSRealm realm = functionData.getContext().getRealm();
                                    if (functionObj == realm.getApplyFunctionObject() || functionObj == realm.getCallFunctionObject()) {
                                        return null; // skip Function.apply and Function.call
                                    }
                                }
                                if (!skippingFrames) {
                                    stackTrace.add(processJSFrame(callNode, thisObj, (DynamicObject) functionObj, inStrictMode));
                                }
                            }
                            break;
                        case STACK_FRAME_FOREIGN:
                            if (!skippingFrames) {
                                JSStackTraceElement elem = processForeignFrame(callNode, inStrictMode);
                                if (elem != null) {
                                    stackTrace.add(elem);
                                }
                            }
                            break;
                    }
                    if (stackTrace.size() < stackTraceLimit) {
                        return null;
                    } else {
                        return stackTrace;
                    }
                }
            });
            return stackTrace.toArray(EMPTY_STACK_TRACE);
        }

        return EMPTY_STACK_TRACE;
    }

    private static JSStackTraceElement processJSFrame(Node node, Object thisObj, DynamicObject functionObj, boolean inStrictMode) {
        RootNode rootNode = node.getRootNode();
        Node callNode = node;
        while (callNode.getSourceSection() == null) {
            callNode = callNode.getParent();
        }
        SourceSection callNodeSourceSection = callNode.getSourceSection();
        Source source = callNodeSourceSection.getSource();

        String fileName = getFileName(source);
        String functionName;
        if (JSFunction.isBuiltin(functionObj)) {
            functionName = JSFunction.getName(functionObj);
        } else {
            functionName = rootNode.getName();
        }
        boolean eval = false;
        if (functionName.startsWith(":")) {
            if (functionName.equals(JSFunction.PROGRAM_FUNCTION_NAME) && (source.getName().equals(Evaluator.EVAL_SOURCE_NAME) || source.getName().startsWith(Evaluator.EVAL_AT_SOURCE_NAME_PREFIX))) {
                functionName = "eval";
                eval = true;
            } else {
                functionName = "";
            }
        }
        SourceSection targetSourceSection = null;
        if (!JSTruffleOptions.NashornCompatibilityMode) { // for V8
            if (callNode instanceof JavaScriptFunctionCallNode) {
                Node target = ((JavaScriptFunctionCallNode) callNode).getTarget();
                targetSourceSection = target == null ? null : target.getSourceSection();
            }
        }

        return new JSStackTraceElement(fileName, functionName, callNodeSourceSection, thisObj, functionObj, targetSourceSection, inStrictMode, eval);
    }

    private static JSStackTraceElement processForeignFrame(Node node, boolean strict) {
        RootNode rootNode = node.getRootNode();
        SourceSection sourceSection = rootNode.getSourceSection();
        if (sourceSection == null) {
            // can happen around FastR root nodes, see GR-6604
            return null;
        }
        String fileName = getFileName(sourceSection.getSource());
        String functionName = rootNode.getName();
        Object thisObj = null;
        Object functionObj = null;

        return new JSStackTraceElement(fileName, functionName, sourceSection, thisObj, functionObj, null, strict, false);
    }

    private static String getPrimitiveConstructorName(Object thisObj) {
        assert JSRuntime.isJSPrimitive(thisObj);
        if (thisObj instanceof Boolean) {
            return "Boolean";
        } else if (JSRuntime.isNumber(thisObj)) {
            return "Number";
        } else if (JSRuntime.isString(thisObj)) {
            return "String";
        } else if (thisObj instanceof Symbol) {
            return "Symbol";
        }
        return null;
    }

    private static int correctColumnNumber(int columnNumber, SourceSection callNodeSourceSection, SourceSection targetSourceSection) {
        int correctNumber = columnNumber;
        String code = callNodeSourceSection.getCharacters().toString();

        // skip code for the target
        if (targetSourceSection != null) {
            String targetCode = targetSourceSection.getCharacters().toString();
            int index = code.indexOf(targetCode);
            if (index != -1) {
                index += targetCode.length();
                correctNumber += index;
                code = code.substring(index);
            }
        }

        // column number corresponds to the function invocation (left
        // parenthesis) unless it is preceded by an identifier (column
        // number is the beginning of the identified then)
        int index = code.indexOf('(');
        if (index != -1) {
            index--;
            int i = index;
            while (i >= 0 && Character.isWhitespace(code.charAt(i))) {
                i--;
            }
            if (i >= 0 && Character.isJavaIdentifierPart(code.charAt(i))) {
                do {
                    i--;
                } while (i >= 0 && Character.isJavaIdentifierPart(code.charAt(i)));
                index = i;
            }
            correctNumber += index + 1;
        }
        return correctNumber;
    }

    private static String getFileName(Source source) {
        return source != null ? source.getName() : "<unknown>";
    }

    public void printJSStackTrace() {
        System.err.println(getMessage());
        for (JSStackTraceElement jsste : jsStackTrace) {
            System.err.println(jsste);
        }
    }

    @TruffleBoundary
    public static void printJSStackTrace(Node originatingNode) {
        JSStackTraceElement[] jsstes = getJSStackTrace(originatingNode);
        for (JSStackTraceElement jsste : jsstes) {
            System.err.println(jsste);
        }
    }

    @TruffleBoundary
    private static StackTraceElement[] toStackTraceElements(JSStackTraceElement[] jsStack) {
        StackTraceElement[] ste = new StackTraceElement[jsStack.length];
        for (int i = 0; i < jsStack.length; i++) {
            JSStackTraceElement jsStackElement = jsStack[i];
            ste[i] = new StackTraceElement(JSError.correctMethodName(jsStackElement.getFunctionName()), "", jsStackElement.getFileName(), jsStackElement.getLineNumber());
        }
        return ste;
    }

    public static final class JSStackTraceElement {
        private final String fileName;
        private final String functionName;
        private final SourceSection sourceSection;
        private final Object thisObj;
        private final Object functionObj;
        private final SourceSection targetSourceSection;
        private final boolean strict;
        private final boolean eval;

        private JSStackTraceElement(String fileName, String functionName, SourceSection sourceSection, Object thisObj, Object functionObj, SourceSection targetSourceSection, boolean strict,
                        boolean eval) {
            CompilerAsserts.neverPartOfCompilation();
            this.fileName = fileName;
            this.functionName = functionName;
            this.sourceSection = sourceSection;
            this.thisObj = thisObj;
            this.functionObj = functionObj;
            this.targetSourceSection = targetSourceSection;
            this.strict = strict;
            this.eval = eval;
        }

        // This method is called from nashorn tests via java interop
        @TruffleBoundary
        public String getFileName() {
            if (fileName.startsWith(Evaluator.EVAL_AT_SOURCE_NAME_PREFIX)) {
                return Evaluator.EVAL_SOURCE_NAME;
            }
            return fileName;
        }

        // This method is called from nashorn tests via java interop
        public String getClassName() {
            return getTypeName(false);
        }

        public String getTypeName() {
            return getTypeName(true);
        }

        @TruffleBoundary
        public String getTypeName(boolean global) {
            if (JSTruffleOptions.NashornCompatibilityMode) {
                return "<" + fileName + ">";
            } else {
                if (global && JSGlobalObject.isJSGlobalObject(getThisOrGlobal())) {
                    return "global";
                }
                Object thisObject = getThis();
                if (!JSRuntime.isNullOrUndefined(thisObject) && !JSGlobalObject.isJSGlobalObject(thisObject)) {
                    if (JSObject.isDynamicObject(thisObject)) {
                        return JSRuntime.getConstructorName((DynamicObject) thisObject);
                    } else if (JSRuntime.isJSPrimitive(thisObject)) {
                        return getPrimitiveConstructorName(thisObject);
                    }
                }
                return null;
            }
        }

        public String getFunctionName() {
            return functionName;
        }

        // This method is called from nashorn tests via java interop
        @TruffleBoundary
        public String getMethodName() {
            if (JSTruffleOptions.NashornCompatibilityMode) {
                return JSError.correctMethodName(functionName);
            }
            if (JSRuntime.isNullOrUndefined(thisObj) || !JSObject.isJSObject(thisObj)) {
                return null;
            }
            if (!JSFunction.isJSFunction(functionObj)) {
                return null;
            }

            DynamicObject receiver = (DynamicObject) thisObj;
            DynamicObject function = (DynamicObject) functionObj;
            if (functionName != null && !functionName.isEmpty()) {
                String name = findMethodPropertyNameByFunctionName(receiver, functionName, function);
                if (name != null) {
                    return name;
                }
            }
            return findMethodPropertyName(receiver, function);
        }

        private static String findMethodPropertyNameByFunctionName(DynamicObject receiver, String functionName, DynamicObject functionObj) {
            String propertyName = functionName;
            boolean accessor = false;
            if (propertyName.startsWith("get ") || propertyName.startsWith("set ")) {
                propertyName = propertyName.substring(4);
                accessor = true;
            }
            if (propertyName.isEmpty()) {
                return null;
            }
            for (DynamicObject current = receiver; current != Null.instance && !JSProxy.isProxy(current); current = JSObject.getPrototype(current)) {
                PropertyDescriptor desc = JSObject.getOwnProperty(current, propertyName);
                if (desc != null) {
                    if (desc.isAccessorDescriptor() == accessor && (desc.getValue() == functionObj || desc.getGet() == functionObj || desc.getSet() == functionObj)) {
                        return propertyName;
                    }
                    break;
                }
            }
            return null;
        }

        private static String findMethodPropertyName(DynamicObject receiver, DynamicObject functionObj) {
            String name = null;
            for (DynamicObject current = receiver; current != Null.instance && !JSProxy.isProxy(current); current = JSObject.getPrototype(current)) {
                for (String key : JSObject.enumerableOwnNames(current)) {
                    PropertyDescriptor desc = JSObject.getOwnProperty(current, key);
                    if (desc.getValue() == functionObj || desc.getGet() == functionObj || desc.getSet() == functionObj) {
                        if (name == null) {
                            name = key;
                        } else {
                            return null; // method name is ambiguous
                        }
                    }
                }
            }
            return name;
        }

        // This method is called from nashorn tests via java interop
        @TruffleBoundary
        public int getLineNumber() {
            return sourceSection != null ? sourceSection.getStartLine() : -1;
        }

        @TruffleBoundary
        public String getLine() {
            int lineNumber = getLineNumber();
            if (sourceSection == null || sourceSection.getSource() == null || lineNumber <= 0) {
                return "<unknown>";
            }
            return sourceSection.getSource().getCharacters(lineNumber).toString();
        }

        @TruffleBoundary
        public int getColumnNumber() {
            if (sourceSection == null) {
                return -1;
            }
            int columnNumber = sourceSection.getStartColumn();
            if (!JSTruffleOptions.NashornCompatibilityMode && targetSourceSection != null) {
                // for V8
                columnNumber = correctColumnNumber(columnNumber, sourceSection, targetSourceSection);
            }
            return columnNumber;
        }

        public int getPosition() {
            return sourceSection != null ? sourceSection.getCharIndex() : -1;
        }

        public Object getThis() {
            return thisObj;
        }

        @TruffleBoundary
        public Object getThisOrGlobal() {
            if (thisObj == Undefined.instance && JSFunction.isJSFunction(functionObj) && !JSFunction.isStrict((DynamicObject) functionObj)) {
                return JSFunction.getRealm((DynamicObject) functionObj).getGlobalObject();
            }
            return thisObj;
        }

        public Object getFunction() {
            return functionObj;
        }

        public boolean isStrict() {
            return strict;
        }

        @TruffleBoundary
        public boolean isConstructor() {
            if (!JSRuntime.isNullOrUndefined(thisObj) && JSObject.isJSObject(thisObj)) {
                Object constructor = JSRuntime.getDataProperty((DynamicObject) thisObj, JSObject.CONSTRUCTOR);
                return constructor != null && constructor == functionObj;
            }
            return false;
        }

        public boolean isEval() {
            return eval;
        }

        @TruffleBoundary
        public String getEvalOrigin() {
            if (fileName.startsWith("<")) {
                return null;
            }
            return fileName;
        }

        @Override
        @TruffleBoundary
        public String toString() {
            StringBuilder builder = new StringBuilder();
            String className = getClassName();
            String methodName = JSError.correctMethodName(getFunctionName());
            if (methodName == null || methodName.isEmpty()) {
                String name = getMethodName();
                if (name == null) {
                    methodName = JSError.ANONYMOUS_FUNCTION_NAME_STACK_TRACE;
                } else {
                    methodName = name;
                }
            }
            boolean includeMethodName = className != null || !JSError.ANONYMOUS_FUNCTION_NAME_STACK_TRACE.equals(methodName);
            if (includeMethodName) {
                if (className != null) {
                    if (className.equals(methodName)) {
                        if (isConstructor()) {
                            builder.append("new ");
                        }
                    } else {
                        builder.append(className).append('.');
                    }
                }
                builder.append(methodName);
                builder.append(" (");
            }
            if (JSFunction.isBuiltinSourceSection(sourceSection)) {
                builder.append("native");
            } else {
                String evalOrigin = getEvalOrigin();
                String sourceName = evalOrigin != null ? evalOrigin : getFileName();
                builder.append(sourceName);
                builder.append(":");
                builder.append(getLineNumber());
                builder.append(":");
                builder.append(getColumnNumber());
            }
            if (includeMethodName) {
                builder.append(")");
            }
            return builder.toString();
        }
    }
}