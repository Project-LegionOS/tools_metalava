/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.metalava.doclava1;

import com.android.tools.lint.checks.infrastructure.ClassNameKt;
import com.android.tools.metalava.model.AnnotationItem;
import com.android.tools.metalava.model.TypeParameterList;
import com.android.tools.metalava.model.text.TextClassItem;
import com.android.tools.metalava.model.text.TextConstructorItem;
import com.android.tools.metalava.model.text.TextFieldItem;
import com.android.tools.metalava.model.text.TextMethodItem;
import com.android.tools.metalava.model.text.TextPackageItem;
import com.android.tools.metalava.model.text.TextParameterItem;
import com.android.tools.metalava.model.text.TextParameterItemKt;
import com.android.tools.metalava.model.text.TextPropertyItem;
import com.android.tools.metalava.model.text.TextTypeItem;
import com.android.tools.metalava.model.text.TextTypeParameterList;
import com.google.common.base.Charsets;
import com.google.common.io.Files;
import kotlin.Pair;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.android.tools.metalava.ConstantsKt.ANDROIDX_NOTNULL;
import static com.android.tools.metalava.ConstantsKt.ANDROIDX_NULLABLE;
import static com.android.tools.metalava.ConstantsKt.JAVA_LANG_ANNOTATION;
import static com.android.tools.metalava.ConstantsKt.JAVA_LANG_ENUM;
import static com.android.tools.metalava.ConstantsKt.JAVA_LANG_STRING;
import static com.android.tools.metalava.model.FieldItemKt.javaUnescapeString;

//
// Copied from doclava1, but adapted to metalava's code model (plus tweaks to handle
// metalava's richer files, e.g. annotations)
//
public class ApiFile {
    public static TextCodebase parseApi(File file,
                                        boolean kotlinStyleNulls,
                                        boolean supportsStagedNullability) throws ApiParseException {
        try {
            String apiText = Files.asCharSource(file, Charsets.UTF_8).read();
            return parseApi(file.getPath(), apiText, kotlinStyleNulls, supportsStagedNullability);
        } catch (IOException ex) {
            throw new ApiParseException("Error reading API file", ex);
        }
    }

    public static TextCodebase parseApi(String filename, String apiText,
                                        boolean kotlinStyleNulls,
                                        boolean supportsStagedNullability) throws ApiParseException {
        if (apiText.contains("/*")) {
            apiText = ClassNameKt.stripComments(apiText, false); // line comments are used to stash field constants
        }

        final Tokenizer tokenizer = new Tokenizer(filename, apiText.toCharArray());
        final TextCodebase api = new TextCodebase();
        api.setSupportsStagedNullability(supportsStagedNullability);
        api.setKotlinStyleNulls(kotlinStyleNulls);

        while (true) {
            String token = tokenizer.getToken();
            if (token == null) {
                break;
            }
            if ("package".equals(token)) {
                parsePackage(api, tokenizer);
            } else {
                throw new ApiParseException("expected package got " + token, tokenizer);
            }
        }

        api.postProcess();

        return api;
    }

    private static void parsePackage(TextCodebase api, Tokenizer tokenizer)
        throws ApiParseException {
        String token;
        String name;
        TextPackageItem pkg;

        token = tokenizer.requireToken();
        assertIdent(tokenizer, token);
        name = token;
        pkg = new TextPackageItem(api, name, tokenizer.pos());
        token = tokenizer.requireToken();
        if (!"{".equals(token)) {
            throw new ApiParseException("expected '{' got " + token, tokenizer);
        }
        while (true) {
            token = tokenizer.requireToken();
            if ("}".equals(token)) {
                break;
            } else {
                parseClass(api, pkg, tokenizer, token);
            }
        }
        api.addPackage(pkg);
    }

    private static void parseClass(TextCodebase api, TextPackageItem pkg, Tokenizer tokenizer, String token)
        throws ApiParseException {
        boolean isPublic = false;
        boolean isProtected = false;
        boolean isPrivate = false;
        boolean internal = false;
        boolean isStatic = false;
        boolean isFinal = false;
        boolean isAbstract = false;
        boolean isDeprecated = false;
        boolean isInterface = false;
        boolean isAnnotation = false;
        boolean isEnum = false;
        boolean sealed = false;
        String name;
        String qualifiedName;
        String ext = null;
        TextClassItem cl;

        // Metalava: including annotations in file now
        List<String> annotations = null;
        Pair<String, List<String>> result = getAnnotations(tokenizer, token);
        if (result != null) {
            token = result.component1();
            annotations = result.component2();
        }

        processModifiers:
        while (true) {
            switch (token) {
                case "public":
                    isPublic = true;
                    token = tokenizer.requireToken();
                    break;
                case "protected":
                    isProtected = true;
                    token = tokenizer.requireToken();
                    break;
                case "private":
                    isPrivate = true;
                    token = tokenizer.requireToken();
                    break;
                case "internal":
                    internal = true;
                    token = tokenizer.requireToken();
                    break;
                case "static":
                    isStatic = true;
                    token = tokenizer.requireToken();
                    break;
                case "final":
                    isFinal = true;
                    token = tokenizer.requireToken();
                    break;
                case "abstract":
                    isAbstract = true;
                    token = tokenizer.requireToken();
                    break;
                case "deprecated":
                    isDeprecated = true;
                    token = tokenizer.requireToken();
                    break;
                case "sealed":
                    sealed = true;
                    token = tokenizer.requireToken();
                    break;
                default:
                    break processModifiers;
            }
        }

        if ("class".equals(token)) {
            token = tokenizer.requireToken();
        } else if ("interface".equals(token)) {
            isInterface = true;
            token = tokenizer.requireToken();
        } else if ("@interface".equals(token)) {
            // Annotation
            isAbstract = true;
            isAnnotation = true;
            token = tokenizer.requireToken();
        } else if ("enum".equals(token)) {
            isEnum = true;
            isFinal = true;
            ext = JAVA_LANG_ENUM;
            token = tokenizer.requireToken();
        } else {
            throw new ApiParseException("missing class or interface. got: " + token, tokenizer);
        }
        assertIdent(tokenizer, token);
        name = token;
        qualifiedName = qualifiedName(pkg.name(), name);
        final TextTypeItem typeInfo = api.obtainTypeFromString(qualifiedName);
        // Simple type info excludes the package name (but includes enclosing class names)

        String rawName = name;
        int variableIndex = rawName.indexOf('<');
        if (variableIndex != -1) {
            rawName = rawName.substring(0, variableIndex);
        }

        token = tokenizer.requireToken();

        cl = new TextClassItem(api, tokenizer.pos(), isPublic, isProtected,
            isPrivate, internal, isStatic, isInterface, isAbstract, isEnum, isAnnotation,
            isFinal, sealed, typeInfo.toErasedTypeString(), typeInfo.qualifiedTypeName(),
            rawName, annotations);
        cl.setContainingPackage(pkg);
        cl.setTypeInfo(typeInfo);
        cl.setDeprecated(isDeprecated);
        if ("extends".equals(token)) {
            token = tokenizer.requireToken();
            assertIdent(tokenizer, token);
            ext = token;
            token = tokenizer.requireToken();
        }
        // Resolve superclass after done parsing
        api.mapClassToSuper(cl, ext);
        if ("implements".equals(token) || "extends".equals(token) ||
                isInterface && ext != null && !token.equals("{")) {
            if (!token.equals("implements") && !token.equals("extends")) {
                api.mapClassToInterface(cl, token);
            }
            while (true) {
                token = tokenizer.requireToken();
                if ("{".equals(token)) {
                    break;
                } else {
                    /// TODO
                    if (!",".equals(token)) {
                        api.mapClassToInterface(cl, token);
                    }
                }
            }
        }
        if (JAVA_LANG_ENUM.equals(ext)) {
            cl.setIsEnum(true);
        } else if (isAnnotation) {
            api.mapClassToInterface(cl, JAVA_LANG_ANNOTATION);
        } else if (api.implementsInterface(cl, JAVA_LANG_ANNOTATION)) {
            cl.setIsAnnotationType(true);
        }
        if (!"{".equals(token)) {
            throw new ApiParseException("expected {, was " + token, tokenizer);
        }
        token = tokenizer.requireToken();
        while (true) {
            if ("}".equals(token)) {
                break;
            } else if ("ctor".equals(token)) {
                token = tokenizer.requireToken();
                parseConstructor(api, tokenizer, cl, token);
            } else if ("method".equals(token)) {
                token = tokenizer.requireToken();
                parseMethod(api, tokenizer, cl, token);
            } else if ("field".equals(token)) {
                token = tokenizer.requireToken();
                parseField(api, tokenizer, cl, token, false);
            } else if ("enum_constant".equals(token)) {
                token = tokenizer.requireToken();
                parseField(api, tokenizer, cl, token, true);
            } else if ("property".equals(token)) {
                token = tokenizer.requireToken();
                parseProperty(api, tokenizer, cl, token);
            } else {
                throw new ApiParseException("expected ctor, enum_constant, field or method", tokenizer);
            }
            token = tokenizer.requireToken();
        }
        pkg.addClass(cl);
    }

    private static Pair<String, List<String>> processKotlinTypeSuffix(TextCodebase api, String type, List<String> annotations) throws ApiParseException {
        if (api.getKotlinStyleNulls()) {
            if (type.endsWith("?")) {
                type = type.substring(0, type.length() - 1);
                annotations = mergeAnnotations(annotations, ANDROIDX_NULLABLE);
            } else if (type.endsWith("!")) {
                type = type.substring(0, type.length() - 1);
            } else if (!type.endsWith("!")) {
                if (!TextTypeItem.Companion.isPrimitive(type)) { // Don't add nullness on primitive types like void
                    annotations = mergeAnnotations(annotations, ANDROIDX_NOTNULL);
                }
            }
        } else if (type.endsWith("?") || type.endsWith("!")) {
            throw new ApiParseException("Did you forget to supply --input-kotlin-nulls? Found Kotlin-style null type suffix when parser was not configured " +
                "to interpret signature file that way: " + type);
        }
        //noinspection unchecked
        return new Pair<>(type, annotations);
    }

    private static Pair<String, List<String>> getAnnotations(Tokenizer tokenizer, String token) throws ApiParseException {
        List<String> annotations = null;

        while (true) {
            if (token.startsWith("@")) {
                // Annotation
                String annotation = token;
                if (annotation.indexOf('.') == -1) {
                    // Restore annotations that were shortened on export
                    annotation = AnnotationItem.Companion.unshortenAnnotation(annotation);
                }
                token = tokenizer.requireToken();
                if (token.equals("(")) {
                    // Annotation arguments
                    int start = tokenizer.offset() - 1;
                    while (!token.equals(")")) {
                        token = tokenizer.requireToken();
                    }
                    annotation += tokenizer.getStringFromOffset(start);
                    token = tokenizer.requireToken();
                }
                if (annotations == null) {
                    annotations = new ArrayList<>();
                }
                annotations.add(annotation);
            } else {
                break;
            }
        }

        if (annotations != null) {
            //noinspection unchecked
            return new Pair<>(token, annotations);
        } else {
            return null;
        }
    }

    private static void parseConstructor(TextCodebase api, Tokenizer tokenizer, TextClassItem cl, String token)
        throws ApiParseException {
        boolean isPublic = false;
        boolean isProtected = false;
        boolean isPrivate = false;
        boolean isInternal = false;
        boolean isDeprecated = false;
        String name;
        TextConstructorItem method;

        // Metalava: including annotations in file now
        List<String> annotations = null;
        Pair<String, List<String>> result = getAnnotations(tokenizer, token);
        if (result != null) {
            token = result.component1();
            annotations = result.component2();
        }

        processModifiers:
        while (true) {
            switch (token) {
                case "public":
                    isPublic = true;
                    token = tokenizer.requireToken();
                    break;
                case "protected":
                    isProtected = true;
                    token = tokenizer.requireToken();
                    break;
                case "private":
                    isPrivate = true;
                    token = tokenizer.requireToken();
                    break;
                case "internal":
                    isInternal = true;
                    token = tokenizer.requireToken();
                    break;
                case "deprecated":
                    isDeprecated = true;
                    token = tokenizer.requireToken();
                    break;
                default:
                    break processModifiers;
            }
        }

        assertIdent(tokenizer, token);
        name = token.substring(token.lastIndexOf('.') + 1); // For inner classes, strip outer classes from name
        token = tokenizer.requireToken();
        if (!"(".equals(token)) {
            throw new ApiParseException("expected (", tokenizer);
        }
        method = new TextConstructorItem(api, /*typeParameters*/
            name, /*signature*/ cl, isPublic, isProtected, isPrivate, isInternal, false/*isFinal*/,
            false/*isStatic*/, /*isSynthetic*/ false/*isAbstract*/, false/*isSynthetic*/,
            false/*isNative*/, false/* isDefault */,
            /*isAnnotationElement*/  /*flatSignature*/
            /*overriddenMethod*/ cl.asTypeInfo(),
            /*thrownExceptions*/ tokenizer.pos(), annotations);
        method.setDeprecated(isDeprecated);
        parseParameterList(api, tokenizer, method);
        token = tokenizer.requireToken();
        if ("throws".equals(token)) {
            token = parseThrows(tokenizer, method);
        }
        if (!";".equals(token)) {
            throw new ApiParseException("expected ; found " + token, tokenizer);
        }
        cl.addConstructor(method);
    }

    private static void parseMethod(TextCodebase api, Tokenizer tokenizer, TextClassItem cl, String token)
        throws ApiParseException {
        boolean isPublic = false;
        boolean isProtected = false;
        boolean isPrivate = false;
        boolean isInternal = false;
        boolean isStatic = false;
        boolean isFinal = false;
        boolean isAbstract = false;
        boolean isDeprecated = false;
        boolean isSynchronized = false;
        boolean isDefault = false;
        boolean isInfix = false;
        boolean isOperator = false;
        boolean isInline = false;
        boolean isNative = false;
        boolean isStrictFp = false;
        TextTypeItem returnType;
        String name;
        TextMethodItem method;
        TypeParameterList typeParameterList = TypeParameterList.Companion.getNONE();

        // Metalava: including annotations in file now
        List<String> annotations = null;
        Pair<String, List<String>> result = getAnnotations(tokenizer, token);
        if (result != null) {
            token = result.component1();
            annotations = result.component2();
        }

        // Parse modifiers; we loop here because we're not sure about the order (which can vary
        // based on metalava's preference flags)

        processModifiers:
        while (true) {
            switch (token) {
                case "public":
                    isPublic = true;
                    token = tokenizer.requireToken();
                    break;
                case "protected":
                    isProtected = true;
                    token = tokenizer.requireToken();
                    break;
                case "private":
                    isPrivate = true;
                    token = tokenizer.requireToken();
                    break;
                case "internal":
                    isInternal = true;
                    token = tokenizer.requireToken();
                    break;
                case "default":
                    isDefault = true;
                    token = tokenizer.requireToken();
                    break;
                case "static":
                    isStatic = true;
                    token = tokenizer.requireToken();
                    break;
                case "final":
                    isFinal = true;
                    token = tokenizer.requireToken();
                    break;
                case "abstract":
                    isAbstract = true;
                    token = tokenizer.requireToken();
                    break;
                case "deprecated":
                    isDeprecated = true;
                    token = tokenizer.requireToken();
                    break;
                case "synchronized":
                    isSynchronized = true;
                    token = tokenizer.requireToken();
                    break;
                case "native":
                    isNative = true;
                    token = tokenizer.requireToken();
                    break;
                case "strictfp":
                    isStrictFp = true;
                    token = tokenizer.requireToken();
                    break;
                case "infix":
                    isInfix = true;
                    token = tokenizer.requireToken();
                    break;
                case "operator":
                    isOperator = true;
                    token = tokenizer.requireToken();
                    break;
                case "inline":
                    isInline = true;
                    token = tokenizer.requireToken();
                    break;
                default:
                    break processModifiers;
            }
        }

        if ("<".equals(token)) {
            typeParameterList = parseTypeParameterList(api, tokenizer);
            token = tokenizer.requireToken();
        }
        assertIdent(tokenizer, token);

        Pair<String, List<String>> kotlinTypeSuffix = processKotlinTypeSuffix(api, token, annotations);
        token = kotlinTypeSuffix.getFirst();
        annotations = kotlinTypeSuffix.getSecond();
        String returnTypeString = token;

        token = tokenizer.requireToken();

        if (returnTypeString.contains("@") && (returnTypeString.indexOf('<') == -1 ||
                returnTypeString.indexOf('@') < returnTypeString.indexOf('<'))) {
            //noinspection StringConcatenationInLoop
            returnTypeString += " " + token;
            token = tokenizer.requireToken();
        }
        while (true) {
            if (token.contains("@") && (token.indexOf('<') == -1 ||
                   token.indexOf('@') < token.indexOf('<'))) {
                // Type-use annotations in type; keep accumulating
                returnTypeString += " " + token;
                token = tokenizer.requireToken();
                if (token.startsWith("[")) { // TODO: This isn't general purpose; make requireToken smarter!
                    returnTypeString += " " + token;
                    token = tokenizer.requireToken();
                }
            } else {
                break;
            }
        }

        returnType = api.obtainTypeFromString(returnTypeString, cl, typeParameterList);

        assertIdent(tokenizer, token);
        name = token;
        method = new TextMethodItem(
            api, name, /*signature*/ cl,
            isPublic, isProtected, isPrivate, isInternal, isFinal, isStatic, isAbstract,
            isSynchronized, isNative, isDefault, isStrictFp, isInfix, isOperator, isInline,
            returnType, tokenizer.pos(), annotations);
        method.setDeprecated(isDeprecated);
        method.setTypeParameterList(typeParameterList);
        if (typeParameterList instanceof TextTypeParameterList) {
            ((TextTypeParameterList)typeParameterList).setOwner(method);
        }
        token = tokenizer.requireToken();
        if (!"(".equals(token)) {
            throw new ApiParseException("expected (, was " + token, tokenizer);
        }
        parseParameterList(api, tokenizer, method);
        token = tokenizer.requireToken();
        if ("throws".equals(token)) {
            token = parseThrows(tokenizer, method);
        }
        if ("default".equals(token)) {
            token = parseDefault(tokenizer, method);
        }
        if (!";".equals(token)) {
            throw new ApiParseException("expected ; found " + token, tokenizer);
        }
        cl.addMethod(method);
    }

    private static List<String> mergeAnnotations(List<String> annotations, String annotation) {
        if (annotations == null) {
            annotations = new ArrayList<>();
        }
        // Reverse effect of TypeItem.shortenTypes(...)
        String qualifiedName = annotation.indexOf('.') == -1
            ? "@androidx.annotation" + annotation
            : "@" + annotation;

        annotations.add(qualifiedName);
        return annotations;
    }

    private static void parseField(TextCodebase api, Tokenizer tokenizer, TextClassItem cl, String token, boolean isEnum)
        throws ApiParseException {
        boolean isPublic = false;
        boolean isProtected = false;
        boolean isPrivate = false;
        boolean isInternal = false;
        boolean isStatic = false;
        boolean isFinal = false;
        boolean isDeprecated = false;
        boolean isTransient = false;
        boolean isVolatile = false;
        String type;
        String name;
        String val = null;
        Object v;
        TextFieldItem field;

        // Metalava: including annotations in file now
        List<String> annotations = null;
        Pair<String, List<String>> result = getAnnotations(tokenizer, token);
        if (result != null) {
            token = result.component1();
            annotations = result.component2();
        }

        processModifiers:
        while (true) {
            switch (token) {
                case "public":
                    isPublic = true;
                    token = tokenizer.requireToken();
                    break;
                case "protected":
                    isProtected = true;
                    token = tokenizer.requireToken();
                    break;
                case "private":
                    isPrivate = true;
                    token = tokenizer.requireToken();
                    break;
                case "internal":
                    isInternal = true;
                    token = tokenizer.requireToken();
                    break;
                case "static":
                    isStatic = true;
                    token = tokenizer.requireToken();
                    break;
                case "final":
                    isFinal = true;
                    token = tokenizer.requireToken();
                    break;
                case "deprecated":
                    isDeprecated = true;
                    token = tokenizer.requireToken();
                    break;
                case "transient":
                    isTransient = true;
                    token = tokenizer.requireToken();
                    break;
                case "volatile":
                    isVolatile = true;
                    token = tokenizer.requireToken();
                    break;
                default:
                    break processModifiers;
            }
        }

        assertIdent(tokenizer, token);

        Pair<String, List<String>> kotlinTypeSuffix = processKotlinTypeSuffix(api, token, annotations);
        token = kotlinTypeSuffix.getFirst();
        annotations = kotlinTypeSuffix.getSecond();
        type = token;
        TextTypeItem typeInfo = api.obtainTypeFromString(type);

        token = tokenizer.requireToken();
        assertIdent(tokenizer, token);
        name = token;
        token = tokenizer.requireToken();
        if ("=".equals(token)) {
            token = tokenizer.requireToken(false);
            val = token;
            token = tokenizer.requireToken();
        }
        if (!";".equals(token)) {
            throw new ApiParseException("expected ; found " + token, tokenizer);
        }
        try {
            v = parseValue(type, val);
        } catch (ApiParseException ex) {
            ex.line = tokenizer.getLine();
            throw ex;
        }

        field = new TextFieldItem(api, name, cl, isPublic, isProtected, isPrivate, isInternal, isFinal, isStatic,
            isTransient, isVolatile, typeInfo, v, tokenizer.pos(),
            annotations);
        field.setDeprecated(isDeprecated);
        if (isEnum) {
            cl.addEnumConstant(field);
        } else {
            cl.addField(field);
        }
    }

    public static Object parseValue(String type, String val) throws ApiParseException {
        if (val != null) {
            if ("boolean".equals(type)) {
                return "true".equals(val) ? Boolean.TRUE : Boolean.FALSE;
            } else if ("byte".equals(type)) {
                return Integer.valueOf(val);
            } else if ("short".equals(type)) {
                return Integer.valueOf(val);
            } else if ("int".equals(type)) {
                return Integer.valueOf(val);
            } else if ("long".equals(type)) {
                return Long.valueOf(val.substring(0, val.length() - 1));
            } else if ("float".equals(type)) {
                if ("(1.0f/0.0f)".equals(val) || "(1.0f / 0.0f)".equals(val)) {
                    return Float.POSITIVE_INFINITY;
                } else if ("(-1.0f/0.0f)".equals(val) || "(-1.0f / 0.0f)".equals(val)) {
                    return Float.NEGATIVE_INFINITY;
                } else if ("(0.0f/0.0f)".equals(val) || "(0.0f / 0.0f)".equals(val)) {
                    return Float.NaN;
                } else {
                    return Float.valueOf(val);
                }
            } else if ("double".equals(type)) {
                if ("(1.0/0.0)".equals(val) || "(1.0 / 0.0)".equals(val)) {
                    return Double.POSITIVE_INFINITY;
                } else if ("(-1.0/0.0)".equals(val) || "(-1.0 / 0.0)".equals(val)) {
                    return Double.NEGATIVE_INFINITY;
                } else if ("(0.0/0.0)".equals(val) || "(0.0 / 0.0)".equals(val)) {
                    return Double.NaN;
                } else {
                    return Double.valueOf(val);
                }
            } else if ("char".equals(type)) {
                return (char) Integer.parseInt(val);
            } else if (JAVA_LANG_STRING.equals(type)) {
                if ("null".equals(val)) {
                    return null;
                } else {
                    return javaUnescapeString(val.substring(1, val.length() - 1));
                }
            }
        }
        if ("null".equals(val)) {
            return null;
        } else {
            return val;
        }
    }

    private static void parseProperty(TextCodebase api, Tokenizer tokenizer, TextClassItem cl, String token)
        throws ApiParseException {
        boolean isPublic = false;
        boolean isProtected = false;
        boolean isPrivate = false;
        boolean isInternal = false;
        boolean isStatic = false;
        boolean isFinal = false;
        boolean isDeprecated = false;
        boolean isTransient = false;
        boolean isVolatile = false;
        String type;
        String name;

        // Metalava: including annotations in file now
        List<String> annotations = null;
        Pair<String, List<String>> result = getAnnotations(tokenizer, token);
        if (result != null) {
            token = result.component1();
            annotations = result.component2();
        }

        processModifiers:
        while (true) {
            switch (token) {
                case "public":
                    isPublic = true;
                    token = tokenizer.requireToken();
                    break;
                case "protected":
                    isProtected = true;
                    token = tokenizer.requireToken();
                    break;
                case "private":
                    isPrivate = true;
                    token = tokenizer.requireToken();
                    break;
                case "internal":
                    isInternal = true;
                    token = tokenizer.requireToken();
                    break;
                case "static":
                    isStatic = true;
                    token = tokenizer.requireToken();
                    break;
                case "final":
                    isFinal = true;
                    token = tokenizer.requireToken();
                    break;
                case "deprecated":
                    isDeprecated = true;
                    token = tokenizer.requireToken();
                    break;
                case "transient":
                    isTransient = true;
                    token = tokenizer.requireToken();
                    break;
                case "volatile":
                    isVolatile = true;
                    token = tokenizer.requireToken();
                    break;
                default:
                    break processModifiers;
            }
        }

        assertIdent(tokenizer, token);

        Pair<String, List<String>> kotlinTypeSuffix = processKotlinTypeSuffix(api, token, annotations);
        token = kotlinTypeSuffix.getFirst();
        annotations = kotlinTypeSuffix.getSecond();
        type = token;
        TextTypeItem typeInfo = api.obtainTypeFromString(type);

        token = tokenizer.requireToken();
        assertIdent(tokenizer, token);
        name = token;
        token = tokenizer.requireToken();
        if (!";".equals(token)) {
            throw new ApiParseException("expected ; found " + token, tokenizer);
        }

        TextPropertyItem property = new TextPropertyItem(api, name, cl, isPublic, isProtected, isPrivate, isInternal, isFinal, isStatic,
            isTransient, isVolatile, typeInfo, tokenizer.pos(), annotations);
        property.setDeprecated(isDeprecated);
        cl.addProperty(property);
    }

    private static TypeParameterList parseTypeParameterList(TextCodebase codebase, Tokenizer tokenizer) throws ApiParseException {
        String token;

        int start = tokenizer.offset() - 1;
        int balance = 1;
        while (balance > 0) {
            token = tokenizer.requireToken();
            if (token.equals("<")) {
                balance++;
            } else if (token.equals(">")) {
                balance--;
            }
        }

        String typeParameterList = tokenizer.getStringFromOffset(start);
        if (typeParameterList.isEmpty()) {
            return TypeParameterList.Companion.getNONE();
        } else {
            return TextTypeParameterList.Companion.create(codebase, null, typeParameterList);
        }
    }

    private static void parseParameterList(TextCodebase api, Tokenizer tokenizer, TextMethodItem method)
                                           throws ApiParseException {
        String token = tokenizer.requireToken();
        int index = 0;
        while (true) {
            if (")".equals(token)) {
                return;
            }

            // Each item can be
            // annotations optional-modifiers type-with-use-annotations-and-generics optional-name optional-equals-default-value

            // Metalava: including annotations in file now
            List<String> annotations = null;
            Pair<String, List<String>> result = getAnnotations(tokenizer, token);
            if (result != null) {
                token = result.component1();
                annotations = result.component2();
            }

            boolean vararg = false;
            if ("vararg".equals(token)) {
                vararg = true;
                token = tokenizer.requireToken();
            }

            // Token should now represent the type
            String type = token;
            token = tokenizer.requireToken();
            if (token.startsWith("@")) {
                // Type use annotations within the type, which broke up the tokenizer;
                // put it back together
                type += " " + token;
                token = tokenizer.requireToken();
                if (token.startsWith("[")) { // TODO: This isn't general purpose; make requireToken smarter!
                    type += " " + token;
                    token = tokenizer.requireToken();
                }
            }

            Pair<String, List<String>> kotlinTypeSuffix = processKotlinTypeSuffix(api, type, annotations);
            String typeString = kotlinTypeSuffix.getFirst();
            annotations = kotlinTypeSuffix.getSecond();
            TextTypeItem typeInfo = api.obtainTypeFromString(typeString);

            String name;
            String publicName;
            if (isIdent(token) && !token.equals("=")) {
                name = token;
                publicName = name;
                token = tokenizer.requireToken();
            } else {
                name = "arg" + (index + 1);
                publicName = null;
            }

            String defaultValue = TextParameterItemKt.NO_DEFAULT_VALUE;
            if ("=".equals(token)) {
                defaultValue = tokenizer.requireToken(true);
                StringBuilder sb = new StringBuilder(defaultValue);
                if (defaultValue.equals("{")) {
                    int balance = 1;
                    while (balance > 0) {
                        token = tokenizer.requireToken(false, false);
                        sb.append(token);
                        if (token.equals("{")) {
                            balance++;
                        } else if (token.equals("}")) {
                            balance--;
                            if (balance == 0) {
                                break;
                            }
                        }
                    }
                    token = tokenizer.requireToken();
                } else {
                    int balance = defaultValue.equals("(") ? 1 : 0;
                    while (true) {
                        token = tokenizer.requireToken(true, false);
                        if (token.endsWith(",") || token.endsWith(")") && balance <= 0)  {
                            if (token.length() > 1) {
                                sb.append(token.substring(0, token.length() - 1));
                                token = Character.toString(token.charAt(token.length() - 1));
                            }
                            break;
                        }
                        sb.append(token);
                        if (token.equals("(")) {
                            balance++;
                        } else if (token.equals(")")) {
                            balance--;
                        }
                    }
                }
                defaultValue = sb.toString();
            }

            if (",".equals(token)) {
                token = tokenizer.requireToken();
            } else if (")".equals(token)) {
            } else {
                throw new ApiParseException("expected , or ), found " + token, tokenizer);
            }

            method.addParameter(new TextParameterItem(api, method, name, publicName, defaultValue, index, typeString,
                typeInfo,
                vararg || typeString.endsWith("..."),
                tokenizer.pos(),
                annotations));
            if (typeString.endsWith("...")) {
                method.setVarargs(true);
            }
            index++;
        }
    }

    private static String parseDefault(Tokenizer tokenizer, TextMethodItem method)
        throws ApiParseException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            String token = tokenizer.requireToken();
            if (";".equals(token)) {
                method.setAnnotationDefault(sb.toString());
                return token;
            } else {
                sb.append(token);
            }
        }
    }

    private static String parseThrows(Tokenizer tokenizer, TextMethodItem method)
        throws ApiParseException {
        String token = tokenizer.requireToken();
        boolean comma = true;
        while (true) {
            if (";".equals(token)) {
                return token;
            } else if (",".equals(token)) {
                if (comma) {
                    throw new ApiParseException("Expected exception, got ','", tokenizer);
                }
                comma = true;
            } else {
                if (!comma) {
                    throw new ApiParseException("Expected ',' or ';' got " + token, tokenizer);
                }
                comma = false;
                method.addException(token);
            }
            token = tokenizer.requireToken();
        }
    }

    private static String qualifiedName(String pkg, String className) {
        return pkg + "." + className;
    }

    private static boolean isIdent(String token) {
        return isIdent(token.charAt(0));
    }

    private static void assertIdent(Tokenizer tokenizer, String token) throws ApiParseException {
        if (!isIdent(token.charAt(0))) {
            throw new ApiParseException("Expected identifier: " + token, tokenizer);
        }
    }

    static class Tokenizer {
        final char[] mBuf;
        String mFilename;
        int mPos;
        int mLine = 1;

        Tokenizer(String filename, char[] buf) {
            mFilename = filename;
            mBuf = buf;
        }

        public SourcePositionInfo pos() {
            return new SourcePositionInfo(mFilename, mLine, 0);
        }

        public int getLine() {
            return mLine;
        }

        boolean eatWhitespace() {
            boolean ate = false;
            while (mPos < mBuf.length && isSpace(mBuf[mPos])) {
                if (mBuf[mPos] == '\n') {
                    mLine++;
                }
                mPos++;
                ate = true;
            }
            return ate;
        }

        boolean eatComment() {
            if (mPos + 1 < mBuf.length) {
                if (mBuf[mPos] == '/' && mBuf[mPos + 1] == '/') {
                    mPos += 2;
                    while (mPos < mBuf.length && !isNewline(mBuf[mPos])) {
                        mPos++;
                    }
                    return true;
                }
            }
            return false;
        }

        void eatWhitespaceAndComments() {
            while (eatWhitespace() || eatComment()) {
            }
        }

        public String requireToken() throws ApiParseException {
            return requireToken(true);
        }

        public String requireToken(boolean parenIsSep) throws ApiParseException {
            return requireToken(parenIsSep, true);
        }

        public String requireToken(boolean parenIsSep, boolean eatWhitespace) throws ApiParseException {
            final String token = getToken(parenIsSep, eatWhitespace);
            if (token != null) {
                return token;
            } else {
                throw new ApiParseException("Unexpected end of file", mLine);
            }
        }

        public String getToken() throws ApiParseException {
            return getToken(true);
        }

        public int offset() {
            return mPos;
        }

        public String getStringFromOffset(int offset) {
            return new String(mBuf, offset, mPos - offset);
        }

        public String getToken(boolean parenIsSep) throws ApiParseException {
            return getToken(parenIsSep, true);
        }

        public String getToken(boolean parenIsSep, boolean eatWhitespace) throws ApiParseException {
            if (eatWhitespace) {
                eatWhitespaceAndComments();
            }
            if (mPos >= mBuf.length) {
                return null;
            }
            final int line = mLine;
            final char c = mBuf[mPos];
            final int start = mPos;
            mPos++;
            if (c == '"') {
                final int STATE_BEGIN = 0;
                final int STATE_ESCAPE = 1;
                int state = STATE_BEGIN;
                while (true) {
                    if (mPos >= mBuf.length) {
                        throw new ApiParseException("Unexpected end of file for \" starting at " + line, mLine);
                    }
                    final char k = mBuf[mPos];
                    if (k == '\n' || k == '\r') {
                        throw new ApiParseException("Unexpected newline for \" starting at " + line + " in " + mFilename, mLine);
                    }
                    mPos++;
                    switch (state) {
                        case STATE_BEGIN:
                            switch (k) {
                                case '\\':
                                    state = STATE_ESCAPE;
                                    mPos++;
                                    break;
                                case '"':
                                    return new String(mBuf, start, mPos - start);
                            }
                        case STATE_ESCAPE:
                            state = STATE_BEGIN;
                            break;
                    }
                }
            } else if (isSeparator(c, parenIsSep)) {
                return "" + c;
            } else {
                int genericDepth = 0;
                do {
                    while (mPos < mBuf.length) {
                        char d = mBuf[mPos];
                        if (isSpace(d) || isSeparator(d, parenIsSep)) {
                            break;
                        } else if (d == '"') {
                            // String literal in token: skip the full thing
                            mPos++;
                            while (mPos < mBuf.length) {
                                if (mBuf[mPos] == '"') {
                                    mPos++;
                                    break;
                                } else if (mBuf[mPos] == '\\') {
                                    mPos++;
                                }
                                mPos++;
                            }
                            continue;
                        }
                        mPos++;
                    }
                    if (mPos < mBuf.length) {
                        if (mBuf[mPos] == '<') {
                            genericDepth++;
                            mPos++;
                        } else if (genericDepth != 0) {
                            if (mBuf[mPos] == '>') {
                                genericDepth--;
                            }
                            mPos++;
                        }
                    }
                } while (mPos < mBuf.length
                    && ((!isSpace(mBuf[mPos]) && !isSeparator(mBuf[mPos], parenIsSep)) || genericDepth != 0));
                if (mPos >= mBuf.length) {
                    throw new ApiParseException("Unexpected end of file for \" starting at " + line, mLine);
                }
                return new String(mBuf, start, mPos - start);
            }
        }

        @Nullable
        public String getFileName() {
            return mFilename;
        }
    }

    static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    static boolean isNewline(char c) {
        return c == '\n' || c == '\r';
    }

    static boolean isSeparator(char c, boolean parenIsSep) {
        if (parenIsSep) {
            if (c == '(' || c == ')') {
                return true;
            }
        }
        return c == '{' || c == '}' || c == ',' || c == ';' || c == '<' || c == '>';
    }

    private static boolean isIdent(char c) {
        return c != '"' && !isSeparator(c, true);
    }
}
