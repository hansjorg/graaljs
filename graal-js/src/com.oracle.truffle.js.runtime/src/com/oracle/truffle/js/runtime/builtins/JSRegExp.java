/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.truffle.js.runtime.builtins;

import static com.oracle.truffle.js.runtime.builtins.JSAbstractArray.arrayGetRegexResult;

import java.util.EnumSet;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.Message;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.object.LocationModifier;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.js.runtime.Boundaries;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.JavaScriptRootNode;
import com.oracle.truffle.js.runtime.RegexCompiler;
import com.oracle.truffle.js.runtime.objects.JSAttributes;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.JSShape;
import com.oracle.truffle.js.runtime.objects.PropertyProxy;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.truffleinterop.JSInteropNodeUtil;
import com.oracle.truffle.js.runtime.util.TRegexUtil;

public final class JSRegExp extends JSBuiltinObject implements JSConstructorFactory.Default {

    public static final JSRegExp INSTANCE = new JSRegExp();

    public static final String CLASS_NAME = "RegExp";
    public static final String PROTOTYPE_NAME = CLASS_NAME + ".prototype";
    public static final String MULTILINE = "multiline";
    public static final String GLOBAL = "global";
    public static final String IGNORE_CASE = "ignoreCase";
    public static final String STICKY = "sticky";
    public static final String UNICODE = "unicode";
    public static final String DOT_ALL = "dotAll";
    public static final String SOURCE = "source";
    public static final String FLAGS = "flags";
    public static final String LAST_INDEX = "lastIndex";
    public static final String INPUT = "input";

    private static final HiddenKey COMPILED_REGEX_ID = new HiddenKey("compiledRegex");
    private static final Property COMPILED_REGEX_PROPERTY;
    private static final Property LAST_INDEX_PROPERTY;

    private static final Property LAZY_INDEX_PROXY = JSObjectUtil.makeProxyProperty("index", new LazyRegexResultIndexProxyProperty(), JSAttributes.getDefault());

    private static final TRegexUtil.TRegexCompiledRegexSingleFlagAccessor STATIC_MULTILINE_ACCESSOR = TRegexUtil.TRegexCompiledRegexSingleFlagAccessor.create(TRegexUtil.Props.Flags.MULTILINE);
    private static final TRegexUtil.TRegexResultAccessor STATIC_RESULT_ACCESSOR = TRegexUtil.TRegexResultAccessor.create();

    /**
     * Since we cannot use nodes here, access to this property is special-cased in
     * {@code com.oracle.truffle.js.nodes.access.PropertyGetNode.LazyRegexResultIndexPropertyGetNode}
     * .
     */
    public static class LazyRegexResultIndexProxyProperty implements PropertyProxy {

        private final Node readStartArrayNode = TRegexUtil.createReadNode();
        private final Node readStartArrayElementNode = TRegexUtil.createReadNode();

        @TruffleBoundary
        @Override
        public Object get(DynamicObject object) {
            return TRegexUtil.readResultStartIndex(readStartArrayNode, readStartArrayElementNode, arrayGetRegexResult(object), 0);
        }

        @TruffleBoundary
        @Override
        public boolean set(DynamicObject object, Object value) {
            JSObjectUtil.defineDataProperty(object, "index", value, JSAttributes.getDefault());
            return true;
        }
    }

    static {
        Shape.Allocator allocator = JSShape.makeAllocator(JSObject.LAYOUT);
        COMPILED_REGEX_PROPERTY = JSObjectUtil.makeHiddenProperty(COMPILED_REGEX_ID, allocator.locationForType(TruffleObject.class, EnumSet.of(LocationModifier.NonNull)));
        // (GR-1991): could start out as an int location.
        LAST_INDEX_PROPERTY = JSObjectUtil.makeDataProperty(JSRegExp.LAST_INDEX, allocator.locationForType(Object.class, EnumSet.of(LocationModifier.NonNull)),
                        JSAttributes.notConfigurableNotEnumerableWritable());
    }

    private JSRegExp() {
    }

    public static TruffleObject getCompiledRegex(DynamicObject thisObj) {
        assert isJSRegExp(thisObj);
        return (TruffleObject) COMPILED_REGEX_PROPERTY.get(thisObj, isJSRegExp(thisObj));
    }

    public static Object getLastIndexRaw(DynamicObject thisObj) {
        assert isJSRegExp(thisObj);
        return LAST_INDEX_PROPERTY.get(thisObj, isJSRegExp(thisObj));
    }

    public static DynamicObject create(JSContext ctx, TruffleObject regex) {
        // (compiledRegex, lastIndex)
        DynamicObject regExp = JSObject.create(ctx, ctx.getRegExpFactory(), regex, 0);
        assert isJSRegExp(regExp);
        return regExp;
    }

    private static void initialize(DynamicObject regExp, TruffleObject regex) {
        COMPILED_REGEX_PROPERTY.setSafe(regExp, regex, null);
    }

    public static void updateCompilation(DynamicObject thisObj, TruffleObject regex) {
        assert isJSRegExp(thisObj) && regex != null;
        initialize(thisObj, regex);
    }

    /**
     * Format: '/' pattern '/' flags, flags may contain 'g' (global), 'i' (ignore case) and 'm'
     * (multiline).<br>
     * Example: <code>/ab*c/gi</code>
     */
    @TruffleBoundary
    public static String prototypeToString(DynamicObject thisObj) {
        TruffleObject regex = getCompiledRegex(thisObj);
        String pattern = (String) JSInteropNodeUtil.readRaw(regex, TRegexUtil.Props.CompiledRegex.PATTERN);
        if (pattern.length() == 0) {
            pattern = "(?:)";
        }
        String flags = (String) JSInteropNodeUtil.readRaw((TruffleObject) JSInteropNodeUtil.readRaw(regex, TRegexUtil.Props.CompiledRegex.FLAGS), TRegexUtil.Props.Flags.SOURCE);
        return "/" + pattern + '/' + flags;
    }

    // non-standard according to ES2015, 7.2.8 IsRegExp (@@match check missing)
    public static boolean isJSRegExp(Object obj) {
        return JSObject.isDynamicObject(obj) && isJSRegExp((DynamicObject) obj);
    }

    // non-standard according to ES2015, 7.2.8 IsRegExp (@@match check missing)
    public static boolean isJSRegExp(DynamicObject obj) {
        return isInstance(obj, INSTANCE);
    }

    private static DynamicObject createFlagsGetterFunction(JSRealm realm) {
        JSContext context = realm.getContext();
        CallTarget callTarget = Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(context.getLanguage(), null, null) {

            @Override
            public Object execute(VirtualFrame frame) {
                return getFlagsIntl(frame.getArguments()[0]);
            }

            @TruffleBoundary
            private Object getFlagsIntl(Object obj) {
                if (!JSRuntime.isObject(obj)) {
                    throw Errors.createTypeErrorNotAnObject(obj);
                }
                DynamicObject re = (DynamicObject) obj;
                StringBuilder sb = new StringBuilder(6);
                appendFlag(re, GLOBAL, sb, 'g');
                appendFlag(re, IGNORE_CASE, sb, 'i');
                appendFlag(re, MULTILINE, sb, 'm');
                if (context.getEcmaScriptVersion() >= 9) {
                    appendFlag(re, DOT_ALL, sb, 's');
                }
                appendFlag(re, UNICODE, sb, 'u');
                appendFlag(re, STICKY, sb, 'y');
                return Boundaries.builderToString(sb);
            }

            private void appendFlag(DynamicObject re, String name, StringBuilder sb, char chr) {
                Object value = JSObject.get(re, name);
                if (JSRuntime.toBoolean(value)) {
                    Boundaries.builderAppend(sb, chr);
                }
            }
        });
        DynamicObject flagsGetter = JSFunction.create(realm, JSFunctionData.createCallOnly(context, callTarget, 0, "get " + FLAGS));
        JSObject.preventExtensions(flagsGetter);
        return flagsGetter;
    }

    @Override
    public DynamicObject createPrototype(JSRealm realm, DynamicObject ctor) {
        JSContext ctx = realm.getContext();
        DynamicObject prototype = JSObject.create(realm, realm.getObjectPrototype(), ctx.getEcmaScriptVersion() < 6 ? JSRegExp.INSTANCE : JSUserObject.INSTANCE);

        if (ctx.getEcmaScriptVersion() < 6) {
            JSObjectUtil.putHiddenProperty(prototype, COMPILED_REGEX_PROPERTY, RegexCompiler.compile("", "", ctx));
            JSObjectUtil.putDataProperty(ctx, prototype, LAST_INDEX_PROPERTY, 0);
        }
        JSObjectUtil.putConstantAccessorProperty(ctx, prototype, FLAGS, createFlagsGetterFunction(realm), Undefined.instance, JSAttributes.configurableNotEnumerableNotWritable());

        putRegExpPropertyAccessor(realm, prototype, MULTILINE,
                        new CompiledRegexFlagPropertyAccessor(prototype, TRegexUtil.Props.Flags.MULTILINE, Undefined.instance));
        putRegExpPropertyAccessor(realm, prototype, GLOBAL,
                        new CompiledRegexFlagPropertyAccessor(prototype, TRegexUtil.Props.Flags.GLOBAL, Undefined.instance));
        putRegExpPropertyAccessor(realm, prototype, IGNORE_CASE,
                        new CompiledRegexFlagPropertyAccessor(prototype, TRegexUtil.Props.Flags.IGNORE_CASE, Undefined.instance));
        putRegExpPropertyAccessor(realm, prototype, SOURCE, new CompiledRegexPatternAccessor(prototype));

        if (ctx.getEcmaScriptVersion() >= 6) {
            putRegExpPropertyAccessor(realm, prototype, STICKY,
                            new CompiledRegexFlagPropertyAccessor(prototype, TRegexUtil.Props.Flags.STICKY, Undefined.instance));
            putRegExpPropertyAccessor(realm, prototype, UNICODE,
                            new CompiledRegexFlagPropertyAccessor(prototype, TRegexUtil.Props.Flags.UNICODE, Undefined.instance));
        }

        if (ctx.getEcmaScriptVersion() >= 9) {
            putRegExpPropertyAccessor(realm, prototype, DOT_ALL,
                            new CompiledRegexFlagPropertyAccessor(prototype, TRegexUtil.Props.Flags.DOT_ALL, Undefined.instance));
        }

        // ctor and functions
        JSObjectUtil.putConstructorProperty(ctx, prototype, ctor);
        JSObjectUtil.putFunctionsFromContainer(realm, prototype, PROTOTYPE_NAME);

        return prototype;
    }

    public static Shape makeInitialShape(JSContext ctx, DynamicObject thisObj) {
        assert JSShape.getProtoChildTree(thisObj.getShape(), INSTANCE) == null;
        // @formatter:off
        return JSObjectUtil.getProtoChildShape(thisObj, INSTANCE, ctx).
                        addProperty(COMPILED_REGEX_PROPERTY).
                        addProperty(LAST_INDEX_PROPERTY);
        // @formatter:on
    }

    public static Shape makeInitialShapeLazyArray(JSContext ctx, DynamicObject prototype) {
        Shape initialShape = JSObjectUtil.getProtoChildShape(prototype, JSArray.INSTANCE, ctx);
        initialShape = JSArray.addArrayProperties(initialShape);
        initialShape = initialShape.addProperty(JSAbstractArray.LAZY_REGEX_RESULT_PROPERTY);
        Shape.Allocator allocator = initialShape.allocator();
        final Property inputProperty = JSObjectUtil.makeDataProperty(JSRegExp.INPUT, allocator.locationForType(String.class, EnumSet.of(LocationModifier.NonNull)), JSAttributes.getDefault());
        initialShape = initialShape.addProperty(inputProperty);
        initialShape = initialShape.addProperty(JSArray.makeArrayLengthProxyProperty());
        initialShape = initialShape.addProperty(LAZY_INDEX_PROXY);
        return initialShape;
    }

    @Override
    public void fillConstructor(JSRealm realm, DynamicObject constructor) {
        putConstructorSpeciesGetter(realm, constructor);
        if (realm.getContext().isOptionRegexpStaticResult()) {
            Object defaultValue = "";

            RegExpPropertyGetter getInput = obj -> {
                TruffleObject result = realm.getContext().getRegexResult();
                return staticResultGetInput(defaultValue, result);
            };
            RegExpPropertyGetter getMultiline = obj -> {
                TruffleObject result = realm.getContext().getRegexResult();
                return staticResultGetMultiline(false, result);
            };
            RegExpPropertyGetter getLastMatch = obj -> {
                TruffleObject result = realm.getContext().getRegexResult();
                return staticResultGetLastMatch(defaultValue, result);
            };
            RegExpPropertyGetter getLastParen = obj -> {
                TruffleObject result = realm.getContext().getRegexResult();
                return staticResultGetLastParen(defaultValue, result);
            };
            RegExpPropertyGetter getLeftContext = obj -> {
                TruffleObject result = realm.getContext().getRegexResult();
                return staticResultGetLeftContext(defaultValue, result);
            };
            RegExpPropertyGetter getRightContext = obj -> {
                TruffleObject result = realm.getContext().getRegexResult();
                return staticResultGetRightContext(defaultValue, result);
            };

            putRegExpStaticResultPropertyAccessor(realm, constructor, "input", getInput);
            if (!JSTruffleOptions.NashornCompatibilityMode) {
                putRegExpStaticResultPropertyAccessor(realm, constructor, "$_", getInput);
            }

            if (JSTruffleOptions.NashornCompatibilityMode) {
                putRegExpStaticResultPropertyAccessor(realm, constructor, "multiline", getMultiline);
            }

            putRegExpStaticResultPropertyAccessor(realm, constructor, "lastMatch", getLastMatch);
            if (!JSTruffleOptions.NashornCompatibilityMode) {
                putRegExpStaticResultPropertyAccessor(realm, constructor, "$&", getLastMatch);
            }

            putRegExpStaticResultPropertyAccessor(realm, constructor, "lastParen", getLastParen);
            if (!JSTruffleOptions.NashornCompatibilityMode) {
                putRegExpStaticResultPropertyAccessor(realm, constructor, "$+", getLastParen);
            }

            putRegExpStaticResultPropertyAccessor(realm, constructor, "leftContext", getLeftContext);
            if (!JSTruffleOptions.NashornCompatibilityMode) {
                putRegExpStaticResultPropertyAccessor(realm, constructor, "$`", getLeftContext);
            }

            putRegExpStaticResultPropertyAccessor(realm, constructor, "rightContext", getRightContext);
            if (!JSTruffleOptions.NashornCompatibilityMode) {
                putRegExpStaticResultPropertyAccessor(realm, constructor, "$'", getRightContext);
            }

            for (int i = 1; i <= 9; i++) {
                putRegExpStaticResultPropertyWithIndexAccessor(realm, constructor, "$" + i, i);
            }
        }
    }

    @TruffleBoundary
    private static Object staticResultGetInput(Object defaultValue, TruffleObject result) {
        if (STATIC_RESULT_ACCESSOR.isMatch(result)) {
            return STATIC_RESULT_ACCESSOR.input(result);
        } else {
            return defaultValue;
        }
    }

    @TruffleBoundary
    private static Object staticResultGetMultiline(Object defaultValue, TruffleObject result) {
        if (!JSTruffleOptions.NashornCompatibilityMode && STATIC_RESULT_ACCESSOR.isMatch(result)) {
            return STATIC_MULTILINE_ACCESSOR.get(STATIC_RESULT_ACCESSOR.regex(result));
        } else {
            return defaultValue;
        }
    }

    @TruffleBoundary
    private static Object staticResultGetLastMatch(Object defaultValue, TruffleObject result) {
        if (STATIC_RESULT_ACCESSOR.isMatch(result)) {
            return Boundaries.substring(STATIC_RESULT_ACCESSOR.input(result), STATIC_RESULT_ACCESSOR.captureGroupStart(result, 0), STATIC_RESULT_ACCESSOR.captureGroupEnd(result, 0));
        } else {
            return defaultValue;
        }
    }

    @TruffleBoundary
    private static Object staticResultGetLastParen(Object defaultValue, TruffleObject result) {
        if (STATIC_RESULT_ACCESSOR.isMatch(result)) {
            int groupNumber = STATIC_RESULT_ACCESSOR.groupCount(result) - 1;
            if (groupNumber > 0) {
                int start = STATIC_RESULT_ACCESSOR.captureGroupStart(result, groupNumber);
                if (start >= 0) {
                    return Boundaries.substring(STATIC_RESULT_ACCESSOR.input(result), start, STATIC_RESULT_ACCESSOR.captureGroupEnd(result, groupNumber));
                }
            }
            return defaultValue;
        } else {
            return defaultValue;
        }
    }

    @TruffleBoundary
    private static Object staticResultGetLeftContext(Object defaultValue, TruffleObject result) {
        if (STATIC_RESULT_ACCESSOR.isMatch(result)) {
            int start = STATIC_RESULT_ACCESSOR.captureGroupStart(result, 0);
            return Boundaries.substring(STATIC_RESULT_ACCESSOR.input(result), 0, start);
        } else {
            return defaultValue;
        }
    }

    @TruffleBoundary
    private static Object staticResultGetRightContext(Object defaultValue, TruffleObject result) {
        if (STATIC_RESULT_ACCESSOR.isMatch(result)) {
            int end = STATIC_RESULT_ACCESSOR.captureGroupEnd(result, 0);
            return Boundaries.substring(STATIC_RESULT_ACCESSOR.input(result), end);
        } else {
            return defaultValue;
        }
    }

    private static void putRegExpStaticResultPropertyAccessor(JSRealm realm, DynamicObject prototype, String name, RegExpPropertyGetter getterImpl) {
        JSContext ctx = realm.getContext();
        DynamicObject getter = JSFunction.create(realm, JSFunctionData.createCallOnly(ctx, Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(ctx.getLanguage(), null, null) {

            @Override
            public Object execute(VirtualFrame frame) {
                Object obj = JSArguments.getThisObject(frame.getArguments());
                DynamicObject view = JSObject.castJSObject(obj);
                return getterImpl.get(view);
            }
        }), 0, "get " + name));
        DynamicObject setter = JSFunction.create(realm, JSFunctionData.createCallOnly(ctx, Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(ctx.getLanguage(), null, null) {

            @Override
            public Object execute(VirtualFrame frame) {
                return Undefined.instance;
            }
        }), 0, "set " + name));
        JSObjectUtil.putConstantAccessorProperty(ctx, prototype, name, getter, setter, getRegExpStaticResultPropertyAccessorJSAttributes());
    }

    private static void putRegExpStaticResultPropertyWithIndexAccessor(JSRealm realm, DynamicObject prototype, String name, int index) {
        JSContext ctx = realm.getContext();
        DynamicObject getter = JSFunction.create(realm, JSFunctionData.createCallOnly(ctx, Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(ctx.getLanguage(), null, null) {

            @Child TRegexUtil.TRegexResultAccessor resultAccessor = TRegexUtil.TRegexResultAccessor.create();
            final int thisIndex = index;

            @Override
            public Object execute(VirtualFrame frame) {
                TruffleObject result = realm.getContext().getRegexResult();
                if (resultAccessor.isMatch(result) && resultAccessor.groupCount(result) > index) {
                    int start = resultAccessor.captureGroupStart(result, thisIndex);
                    if (start >= 0) {
                        return Boundaries.substring(resultAccessor.input(result), start, resultAccessor.captureGroupEnd(result, thisIndex));
                    }
                }
                return "";
            }
        }), 0, "get " + name));
        DynamicObject setter = JSFunction.create(realm, JSFunctionData.createCallOnly(ctx, Truffle.getRuntime().createCallTarget(new JavaScriptRootNode(ctx.getLanguage(), null, null) {

            @Override
            public Object execute(VirtualFrame frame) {
                return Undefined.instance;
            }
        }), 0, "set " + name));
        JSObjectUtil.putConstantAccessorProperty(ctx, prototype, name, getter, setter, getRegExpStaticResultPropertyAccessorJSAttributes());
    }

    // https://github.com/tc39/proposal-regexp-legacy-features#additional-properties-of-the-regexp-constructor
    private static int getRegExpStaticResultPropertyAccessorJSAttributes() {
        return JSTruffleOptions.NashornCompatibilityMode ? JSAttributes.notConfigurableEnumerableWritable() : JSAttributes.configurableNotEnumerableWritable();
    }

    public static JSConstructor createConstructor(JSRealm realm) {
        return INSTANCE.createConstructorAndPrototype(realm);
    }

    @Override
    public String getClassName() {
        return CLASS_NAME;
    }

    @Override
    public String getClassName(DynamicObject object) {
        return getClassName();
    }

    @Override
    public String getBuiltinToStringTag(DynamicObject object) {
        return getClassName(object);
    }

    @Override
    @TruffleBoundary
    public String safeToString(DynamicObject obj) {
        if (JSTruffleOptions.NashornCompatibilityMode) {
            return "[RegExp " + prototypeToString(obj) + "]";
        } else {
            return prototypeToString(obj);
        }
    }

    interface RegExpPropertyGetter {
        Object get(DynamicObject obj);
    }

    private static class CompiledRegexPatternAccessor extends JavaScriptRootNode {

        private static final String DEFAULT_RETURN = "(?:)";

        private final ConditionProfile isObject = ConditionProfile.createBinaryProfile();
        private final ConditionProfile isRegExp = ConditionProfile.createBinaryProfile();
        private final Object regexPrototype;
        @Child Node readNode = Message.READ.createNode();

        CompiledRegexPatternAccessor(Object regexPrototype) {
            this.regexPrototype = regexPrototype;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object obj = JSArguments.getThisObject(frame.getArguments());
            if (isObject.profile(JSObject.isDynamicObject(obj))) {
                DynamicObject view = JSObject.castJSObject(obj);
                if (isRegExp.profile(isJSRegExp(view))) {
                    return escapeRegExpPattern(TRegexUtil.readPattern(readNode, getCompiledRegex(view)));
                } else if (obj == regexPrototype) {
                    return DEFAULT_RETURN;
                }
            }
            throw Errors.createTypeErrorObjectExpected();
        }
    }

    private static class CompiledRegexFlagPropertyAccessor extends JavaScriptRootNode {

        private final ConditionProfile isObject = ConditionProfile.createBinaryProfile();
        private final ConditionProfile isRegExp = ConditionProfile.createBinaryProfile();
        private final Object regexPrototype;
        private final Object defaultReturn;
        @Child TRegexUtil.TRegexCompiledRegexSingleFlagAccessor readNode;

        CompiledRegexFlagPropertyAccessor(Object regexPrototype, String flagName, Object defaultReturn) {
            this.regexPrototype = regexPrototype;
            this.defaultReturn = defaultReturn;
            readNode = TRegexUtil.TRegexCompiledRegexSingleFlagAccessor.create(flagName);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object obj = JSArguments.getThisObject(frame.getArguments());
            if (isObject.profile(JSObject.isDynamicObject(obj))) {
                DynamicObject view = JSObject.castJSObject(obj);
                if (isRegExp.profile(isJSRegExp(view))) {
                    return readNode.get(getCompiledRegex(view));
                } else if (obj == regexPrototype) {
                    return defaultReturn;
                }
            }
            throw Errors.createTypeErrorObjectExpected();
        }
    }

    private static void putRegExpPropertyAccessor(JSRealm realm, DynamicObject prototype, String name, JavaScriptRootNode accessor) {
        JSContext ctx = realm.getContext();
        DynamicObject getter = JSFunction.create(realm, JSFunctionData.createCallOnly(ctx, Truffle.getRuntime().createCallTarget(accessor), 0, "get " + name));
        JSObjectUtil.putConstantAccessorProperty(ctx, prototype, name, getter, Undefined.instance, JSAttributes.configurableNotEnumerable());
    }

    @TruffleBoundary
    private static Object escapeRegExpPattern(CharSequence pattern) {
        if (pattern.length() == 0) {
            return "(?:)";
        }
        int unescaped = unescapedRegExpCharCount(pattern);
        if (unescaped == 0) {
            return pattern;
        } else {
            return escapeRegExpPattern(pattern, unescaped);
        }
    }

    private static boolean isUnescapedRegExpCharAt(CharSequence pattern, int i) {
        switch (pattern.charAt(i)) {
            case '/':
                return (i == 0 || pattern.charAt(i - 1) != '\\');
            case '\n':
            case '\r':
            case '\u2028':
            case '\u2029':
                return true;
            default:
                return false;
        }
    }

    private static int unescapedRegExpCharCount(CharSequence pattern) {
        int unescaped = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (isUnescapedRegExpCharAt(pattern, i)) {
                unescaped++;
            }
        }
        return unescaped;
    }

    @TruffleBoundary
    private static Object escapeRegExpPattern(CharSequence pattern, int unescaped) {
        StringBuilder sb = new StringBuilder(pattern.length() + unescaped);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (isUnescapedRegExpCharAt(pattern, i)) {
                if (c != '/') {
                    switch (c) {
                        case '\n':
                            sb.append("\\n");
                            break;
                        case '\r':
                            sb.append("\\r");
                            break;
                        case '\u2028':
                            sb.append("\\u2028");
                            break;
                        case '\u2029':
                            sb.append("\\u2029");
                            break;
                        default:
                            Errors.shouldNotReachHere();
                    }
                    continue;
                } else {
                    sb.append('\\');
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}