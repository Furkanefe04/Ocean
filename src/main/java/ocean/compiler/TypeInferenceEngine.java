package ocean.compiler;

import org.antlr.v4.runtime.ParserRuleContext;
import java.util.*;

import ocean.compiler.OceanParser;
/**
 * Ocean derleyicisinin statik tip çıkarım motoru.
 * Açık tip belirtilmeyen değişkenler (`variable`, `value`), dizi literalleri ve ifadeler için
 * bağlamsal kurallara göre derleme zamanı tip tanımlayıcısını (type descriptor) hesaplar.
 */
public final class TypeInferenceEngine {

    private TypeInferenceEngine() {}

    private static final String FALLBACK = OceanTypeSystem.OBJECT_DESC;
    private static final boolean DEBUG = Boolean.getBoolean("ocean.debug");
    private static final List<String> INT_ARG_LIST = Collections.singletonList("I");

    public static String inferType(ParserRuleContext ctx, InferenceContext context) {
        if (ctx == null) return FALLBACK;

        String result = inferTypeImpl(ctx, context);

        return (result != null) ? result : FALLBACK;
    }

    private static String inferTypeImpl(ParserRuleContext ctx, InferenceContext context) {

        if (ctx instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext pCtx = ((OceanParser.PrimaryExprContext) ctx).primary();
            
            if (pCtx instanceof OceanParser.IdPrimaryContext) {
                String name = ((OceanParser.IdPrimaryContext) pCtx).anyId().getText();
                String type;
                String refined = context.lookupVariableType(name);
                String original = context.lookupVariableOriginalType(name);
                if (refined != null && original != null && !refined.equals(original)) {
                    type = refined;
                } else {
                    String raw = context.lookupVariableRawType(name);
                    if (raw != null) {
                        type = resolveRawTypeToDescriptor(raw, context);
                    } else {
                        type = refined;
                    }
                }
                if (type != null) return type;
                
                type = context.getFieldType(null, name);
                if (type != null) return type;
                
                String resolvedClass = context.resolveClassName(name);
                if (resolvedClass != null && !resolvedClass.contains("generated/null")) {
                    return OceanTypeSystem.wrapObjectType(resolvedClass);
                }
                
                return FALLBACK;
            } 
            
            if (pCtx instanceof OceanParser.StringPrimaryContext || pCtx instanceof OceanParser.InterpolatedStringPrimaryContext) {
                return OceanTypeSystem.STRING_DESC;
            }
            if (pCtx instanceof OceanParser.CharPrimaryContext) return "C";
            if (pCtx instanceof OceanParser.NumberPrimaryContext) return inferNumberType(((OceanParser.NumberPrimaryContext) pCtx).NUMBER().getText());
            if (pCtx instanceof OceanParser.TruePrimaryContext || pCtx instanceof OceanParser.FalsePrimaryContext) return "Z";
            if (pCtx instanceof OceanParser.ParenthesizedPrimaryContext) return inferType(((OceanParser.ParenthesizedPrimaryContext) pCtx).expression(), context);
            
            if (pCtx instanceof OceanParser.ThisRefPrimaryContext) {
                String thisType = context.lookupVariableType("this");
                if (thisType != null) return thisType;
                return OceanTypeSystem.wrapObjectType(context.getCurrentClassName());
            }
            
            if (pCtx instanceof OceanParser.SuperRefPrimaryContext) {
                String superName = context.getCurrentSuperName();
                if (superName == null) return FALLBACK;
                return superName.startsWith("L") ? superName : OceanTypeSystem.wrapObjectType(superName);
            }
            
            if (pCtx instanceof OceanParser.NullPrimaryContext) return "null";
            
            if (pCtx instanceof OceanParser.OceanOutputPrimaryContext ooCtx) {
                if (ooCtx.argumentList() == null || ooCtx.argumentList().argument().isEmpty()) return "Ljava/io/PrintStream;";
                return "V";
            }
            
            if (pCtx instanceof OceanParser.OceanInputPrimaryContext) return "Ljava/util/Scanner;";
            
            if (pCtx instanceof OceanParser.ListLiteralPrimaryContext ll) {
                if (ll.argumentList() != null && !ll.argumentList().argument().isEmpty()) {
                    List<OceanParser.ArgumentContext> args = ll.argumentList().argument();
                    String elemType = findElemType(args,context);
                    if ("I".equals(elemType)) return "Locean/stdlib/OceanIntList;";
                    if ("J".equals(elemType)) return "Locean/stdlib/OceanLongList;";
                    if ("D".equals(elemType)) return "Locean/stdlib/OceanDoubleList;";
                    if ("F".equals(elemType)) return "Locean/stdlib/OceanFloatList;";
                    if ("Z".equals(elemType)) return "Locean/stdlib/OceanBooleanList;";
                    if ("B".equals(elemType)) return "Locean/stdlib/OceanByteList;";
                    if ("S".equals(elemType)) return "Locean/stdlib/OceanShortList;";
                    if ("C".equals(elemType)) return "Locean/stdlib/OceanCharList;";
                    return "Locean/stdlib/OceanList<" + elemType + ">;";
                }
                return "Locean/stdlib/OceanList;";
            }
            if (pCtx instanceof OceanParser.SetLiteralPrimaryContext sl) {
                if (sl.argumentList() != null && !sl.argumentList().argument().isEmpty()) {
                    List<OceanParser.ArgumentContext> args = sl.argumentList().argument();
                    String elemType = findElemType(args,context);
                    return "Locean/stdlib/OceanSet<" + elemType + ">;";
                }
                return "Locean/stdlib/OceanSet;";
            }
            if (pCtx instanceof OceanParser.MapLiteralPrimaryContext ml) {
                if (ml.mapEntry() != null && !ml.mapEntry().isEmpty()) {
                    List<OceanParser.MapEntryContext> entries = ml.mapEntry();
                    OceanParser.MapEntryContext firstEntry = entries.getFirst();
                    String keyType = inferType(firstEntry.expression(0), context);
                    String valType = inferType(firstEntry.expression(1), context);
                    for (int i = 1; i < entries.size(); i++) {
                        OceanParser.MapEntryContext entry = entries.get(i);
                        String k = inferType(entry.expression(0), context);
                        String v = inferType(entry.expression(1), context);
                        if (!keyType.equals(k)) {
                            keyType = TypeChecker.getCommonType(keyType, k);
                        }
                        if (!valType.equals(v)) {
                            valType = TypeChecker.getCommonType(valType, v);
                        }
                    }
                    return "Locean/stdlib/OceanMap<" + keyType + "," + valType + ">;";
                }
                return "Locean/stdlib/OceanMap;";
            }
            if (pCtx instanceof OceanParser.ArrayLiteralPrimaryContext al) {
                if (al.argumentList() != null && !al.argumentList().argument().isEmpty()) {
                    List<OceanParser.ArgumentContext> args = al.argumentList().argument();
                    String elemType = findElemType(args,context);
                    return "[" + elemType;
                }
                return "[Ljava/lang/Object;";
            }
        }

        if (ctx instanceof OceanParser.AddSubExprContext || ctx instanceof OceanParser.MulDivModExprContext) {
            OceanParser.ExpressionContext e0, e1;
            if (ctx instanceof OceanParser.AddSubExprContext as) {
                e0 = as.expression(0); e1 = as.expression(1);
            } else {
                OceanParser.MulDivModExprContext md = (OceanParser.MulDivModExprContext) ctx;
                e0 = md.expression(0); e1 = md.expression(1);
            }
            String left = inferType(e0, context);
            String right = inferType(e1, context);
            if (left.equals(OceanTypeSystem.BIGDECIMAL_DESC) || right.equals(OceanTypeSystem.BIGDECIMAL_DESC)) return OceanTypeSystem.BIGDECIMAL_DESC;
            if (ctx instanceof OceanParser.AddSubExprContext asCtx && "+".equals(asCtx.op.getText()) && (TypeChecker.isStringType(left) || TypeChecker.isStringType(right))) return OceanTypeSystem.STRING_DESC;
            return TypeChecker.getBinaryNumericPromotedType(left, right);
        }

        if (ctx instanceof OceanParser.ComparisonExprContext || ctx instanceof OceanParser.EqualityExprContext ||
            ctx instanceof OceanParser.LogicalAndExprContext || ctx instanceof OceanParser.LogicalOrExprContext ||
            ctx instanceof OceanParser.InstanceOfExprContext) return "Z";

        if (ctx instanceof OceanParser.UnaryExprContext uCtx) {
            if (uCtx.op.getText().equals("!")) return "Z";
            String subType = inferType(uCtx.expression(), context);
            if (uCtx.op.getText().equals("+") || uCtx.op.getText().equals("-") || uCtx.op.getText().equals("~")) {
                return TypeChecker.getUnaryNumericPromotedType(subType);
            }
            return subType;
        }

        if (ctx instanceof OceanParser.CastExprContext) return context.getTypeDescriptor(((OceanParser.CastExprContext) ctx).type().getText());

        if (ctx instanceof OceanParser.NewObjectExprContext nCtx) {
            String typeName = nCtx.type().getText();
            String desc = context.getTypeDescriptor(typeName);
            if (!nCtx.expression().isEmpty()) {
                return "[".repeat(nCtx.expression().size()) +
                        desc;
            }
            return desc;
        }

        if (ctx instanceof OceanParser.MethodCallExprContext mc) {
            if (mc.expression() instanceof OceanParser.MemberCallExprContext mce) {
                String ownerType = inferType(mce.expression(), context);
                String methodName = mce.anyId().getText();
                List<String> argTypes = inferArgTypes(mc.argumentList(), context);
                String rType = context.resolveMethodReturnType(ownerType, methodName, argTypes);
                if (DEBUG) System.out.println("[DEBUG] Engine called resolveMethodReturnType for " + ownerType + "." + methodName + " got " + rType);
                return (rType != null) ? rType : FALLBACK;
            } else {
                String methodName = mc.expression().getText();
                List<String> argTypes = inferArgTypes(mc.argumentList(), context);
                String rType = context.resolveMethodReturnType(OceanTypeSystem.wrapObjectType(context.getCurrentClassName()), methodName, argTypes);
                return (rType != null) ? rType : FALLBACK;
            }
        }
        if (ctx instanceof OceanParser.ClassLiteralExprContext) {
            return "Ljava/lang/Class;";
        }

        if (ctx instanceof OceanParser.QualifiedThisExprContext qte) {
            String target = qte.typeName().getText();
            String desc = context.getTypeDescriptor(target);
            return (desc != null) ? desc : OceanTypeSystem.wrapObjectType(target);
        }

        if (ctx instanceof OceanParser.QualifiedSuperExprContext qse) {
            String target = qse.typeName().getText();
            String desc = context.getTypeDescriptor(target);
            return (desc != null) ? desc : OceanTypeSystem.wrapObjectType(target);
        }

        if (ctx instanceof OceanParser.MemberCallExprContext mce) {
            String ownerType = inferType(mce.expression(), context);
            String memberName = mce.anyId().getText();
            
            if (mce.LPAREN() != null) {
                // It's a method call!
                List<String> argTypes = inferArgTypes(mce.argumentList(), context);
                String rType = context.resolveMethodReturnType(ownerType, memberName, argTypes);
                if (DEBUG) System.out.println("[DEBUG] Engine MemberCall (Method): " + ownerType + "." + memberName + " -> " + rType);
                return (rType != null) ? rType : FALLBACK;
            } else {
                // It's a field access!
                if (ownerType != null && ownerType.startsWith("[") && memberName.equals("length")) return "I";
                String fType = context.getFieldType(ownerType, memberName);
                if (DEBUG) System.out.println("[DEBUG] Engine MemberCall (Field): " + ownerType + "." + memberName + " -> " + fType);
                return (fType != null) ? fType : FALLBACK;
            }
        }

        if (ctx instanceof OceanParser.SafeMemberCallExprContext smc) {
            String ownerType = inferType(smc.expression(), context);
            String memberName = smc.anyId().getText();
            String result;
            if (smc.LPAREN() != null) {
                result = context.resolveMethodReturnType(ownerType, memberName, inferArgTypes(smc.argumentList(), context));
            } else {
                result = context.getFieldType(ownerType, memberName);
            }
            if (result == null || result.equals("V")) return FALLBACK;
            if (TypeChecker.isPrimitive(result)) { // Primitive
                return TypeChecker.box(result);
            }
            return result;
        }

        if (ctx instanceof OceanParser.RangeSliceExprContext) {
            String targetType = inferType(((OceanParser.RangeSliceExprContext) ctx).expression(0), context);
            if (targetType != null) {
                if (targetType.startsWith("[")) return targetType;
                String clean = TypeChecker.cleanDescriptor(targetType);
                OverloadResolver.SliceMethodResolution sliceRes = OverloadResolver.findSliceMethod(clean);
                if (sliceRes != null && sliceRes.descriptor() != null && sliceRes.descriptor().contains(")")) {
                    return sliceRes.descriptor().substring(sliceRes.descriptor().lastIndexOf(')') + 1);
                }
                return targetType;
            }
            return FALLBACK;
        }

        if (ctx instanceof OceanParser.ArrayAccessExprContext aac) {
            String arrayType = inferType(aac.expression(0), context);
            if (arrayType != null) {
                if (arrayType.startsWith("[")) {
                    return arrayType.substring(1);
                }
                String clean = TypeChecker.cleanDescriptor(arrayType);
                if (arrayType.contains("<")) {
                    int start = arrayType.indexOf("<") + 1;
                    int end = arrayType.lastIndexOf(">");
                    if (start < end) {
                        String inner = arrayType.substring(start, end).trim();
                        List<String> parts = splitGenericsParts(inner);
                        if (TypeChecker.isMapType(clean) && parts.size() >= 2) {
                            return parts.get(1);
                        }
                        if (!parts.isEmpty()) {
                            return parts.getFirst();
                        }
                        return inner;
                    }
                }
                List<String> indexArgList = INT_ARG_LIST;
                if (aac.expression().size() > 1) {
                    String indexType = inferType(aac.expression(1), context);
                    if (indexType != null && !indexType.equals(FALLBACK)) {
                        indexArgList = Collections.singletonList(indexType);
                    }
                }
                String getDesc = OverloadResolver.resolve(clean, "get", indexArgList);
                if (getDesc == null) getDesc = OverloadResolver.resolve(clean, "getItem", indexArgList);
                if (getDesc == null) getDesc = OverloadResolver.resolve(clean, "at", indexArgList);
                if (getDesc != null && getDesc.contains(")")) {
                    String ret = getDesc.substring(getDesc.lastIndexOf(')') + 1);
                    if (!OceanTypeSystem.OBJECT_DESC.equals(ret)) {
                        return ret;
                    }
                }
                return FALLBACK;
            }
            return FALLBACK;
        }

        if (ctx instanceof OceanParser.TernaryExprContext) {
            String thenType = inferType(((OceanParser.TernaryExprContext) ctx).expression(1), context);
            String elseType = inferType(((OceanParser.TernaryExprContext) ctx).expression(2), context);
            return TypeChecker.getCommonType(thenType, elseType);
        }
        if (ctx instanceof OceanParser.NullCoalescingExprContext) {
            String leftType  = inferType(((OceanParser.NullCoalescingExprContext) ctx).expression(0), context);
            String rightType = inferType(((OceanParser.NullCoalescingExprContext) ctx).expression(1), context);
            if (!TypeChecker.isNullable(rightType)) {
                return TypeChecker.cleanDescriptor(rightType);
            }
            return TypeChecker.getCommonType(leftType, rightType);
        }

        if (ctx instanceof OceanParser.ShiftExprContext sCtx) return TypeChecker.getUnaryNumericPromotedType(inferType(sCtx.expression(0), context));
        
        if (ctx instanceof OceanParser.BitAndExprContext) return TypeChecker.getBinaryNumericPromotedType(inferType(((OceanParser.BitAndExprContext)ctx).expression(0), context), inferType(((OceanParser.BitAndExprContext)ctx).expression(1), context));
        if (ctx instanceof OceanParser.BitOrExprContext) return TypeChecker.getBinaryNumericPromotedType(inferType(((OceanParser.BitOrExprContext)ctx).expression(0), context), inferType(((OceanParser.BitOrExprContext)ctx).expression(1), context));
        if (ctx instanceof OceanParser.BitXorExprContext) return TypeChecker.getBinaryNumericPromotedType(inferType(((OceanParser.BitXorExprContext)ctx).expression(0), context), inferType(((OceanParser.BitXorExprContext)ctx).expression(1), context));

        if (ctx instanceof OceanParser.PostfixExprContext) return inferType(((OceanParser.PostfixExprContext) ctx).expression(), context);
        if (ctx instanceof OceanParser.PrefixExprContext) return inferType(((OceanParser.PrefixExprContext) ctx).expression(), context);

        if (ctx instanceof OceanParser.LambdaExprContext lctx) {
            int paramCount = 0;
            if (lctx.parameterList() != null) {
                paramCount = lctx.parameterList().parameter().size();
            } else if (lctx.identifierList() != null) {
                paramCount = lctx.identifierList().anyId().size();
            }
            boolean hasReturnValue = false;
            if (lctx.expression() != null) {
                hasReturnValue = true;
            } else if (lctx.block() != null) {
                hasReturnValue = blockHasReturn(lctx.block());
            }
            return OceanTypeSystem.findFuncInterface(paramCount, hasReturnValue);
        }

        if (ctx instanceof OceanParser.MethodRefExprContext mrCtx) {
            String receiverType = inferType(mrCtx.expression(), context);
            String cleanReceiver = receiverType != null ? TypeChecker.cleanDescriptor(receiverType) : null;
            String owner = TypeChecker.isClassType(cleanReceiver) ? cleanReceiver.substring(1, cleanReceiver.length() - 1) : "java/lang/Object";
            String methodName = (mrCtx.anyId() != null) ? mrCtx.anyId().getText() : "new";

            int paramCount = 0;
            boolean hasReturnValue = false;
            String methodDesc = null;
            if (!methodName.equals("new") && !methodName.equals("<init>")) {
                Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(owner);
                if (methods != null && methods.containsKey(methodName)) {
                    methodDesc = methods.get(methodName);
                } else {
                    Map<String, List<String>> overloads = CompilerRegistry.globalOverloadRegistry.get(owner);
                    if (overloads != null && overloads.containsKey(methodName) && !overloads.get(methodName).isEmpty()) {
                        methodDesc = overloads.get(methodName).getFirst();
                    }
                }
            }
            if (methodDesc != null && methodDesc.contains("(") && methodDesc.contains(")")) {
                String paramPart = methodDesc.substring(methodDesc.indexOf('(') + 1, methodDesc.indexOf(')'));
                for (int i = 0; i < paramPart.length(); i++) {
                    char c = paramPart.charAt(i);
                    if (c == 'L') {
                        int end = paramPart.indexOf(';', i);
                        if (end != -1) i = end;
                    } else if (c == '[') {
                        continue;
                    }
                    paramCount++;
                }
                String ret = methodDesc.substring(methodDesc.lastIndexOf(')') + 1);
                hasReturnValue = !ret.equals("V");
            }
            return OceanTypeSystem.findFuncInterface(paramCount, hasReturnValue);
        }

        if (ctx instanceof OceanParser.AwaitExprContext awaitCtx) {
            return getUnwrappedAwaitType(awaitCtx.expression(), context);
        }

        if (ctx instanceof OceanParser.AssignmentExprContext aec) {
            return inferType(aec.expression(0), context);
        }

        if (ctx instanceof OceanParser.SwitchExprContext swCtx) {
            List<String> types = new ArrayList<>();
            if (swCtx.switchExpressionCase() != null) {
                for (OceanParser.SwitchExpressionCaseContext caseCtx : swCtx.switchExpressionCase()) {
                    if (caseCtx.expression() != null) {
                        types.add(inferType(caseCtx.expression(), context));
                    } else if (caseCtx.block() != null) {
                        findResultTypesInBlock(caseCtx.block(), types, context);
                    }
                }
            }
            if (swCtx.defaultExpressionCase() != null) {
                OceanParser.DefaultExpressionCaseContext defCtx = swCtx.defaultExpressionCase();
                if (defCtx.expression() != null) {
                    types.add(inferType(defCtx.expression(), context));
                } else if (defCtx.block() != null) {
                    findResultTypesInBlock(defCtx.block(), types, context);
                }
            }
            if (types.isEmpty()) return FALLBACK;
            String common = types.getFirst();
            for (int i = 1; i < types.size(); i++) {
                common = TypeChecker.getCommonType(common, types.get(i));
            }
            return (common != null) ? common : FALLBACK;
        }

        return FALLBACK;
    }

    private static String getUnwrappedAwaitType(OceanParser.ExpressionContext expr, InferenceContext context) {
        String methodName = null;
        String ownerType = null;
        
        if (expr instanceof OceanParser.MethodCallExprContext mc) {
            if (mc.expression() instanceof OceanParser.MemberCallExprContext mce) {
                ownerType = inferType(mce.expression(), context);
                methodName = mce.anyId().getText();
            } else {
                ownerType = OceanTypeSystem.wrapObjectType(context.getCurrentClassName());
                methodName = mc.expression().getText();
            }
        } else if (expr instanceof OceanParser.MemberCallExprContext mce) {
            if (mce.LPAREN() != null) {
                ownerType = inferType(mce.expression(), context);
                methodName = mce.anyId().getText();
            }
        }
        
        if (methodName != null && ownerType != null) {
            String cleanOwner = TypeChecker.cleanDescriptor(ownerType);
            if (TypeChecker.isClassType(cleanOwner)) cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
            CompilationSession session = CompilationSession.getActiveSession();
            if (session != null) {
                Map<String, String> classMethods = session.globalMethodGenericReturnTypeRegistry.get(cleanOwner);
                if (classMethods != null) {
                    String originalReturn = classMethods.get(methodName);
                    if (originalReturn != null) {
                        return resolveRawTypeToDescriptor(originalReturn, context);
                    }
                }
            }
        }
        
        return OceanTypeSystem.OBJECT_DESC;
    }

    static String inferNumberType(String val) {
        if (val == null || val.isEmpty()) return "I";
        val = val.replace("_", "");
        String lower = val.toLowerCase();

        if (lower.startsWith("0x") && !val.contains(".") && !val.contains("p") && !val.contains("P")) {
            char last = Character.toUpperCase(val.charAt(val.length() - 1));
            String digits = (last == 'L') ? lower.substring(2, lower.length() - 1) : lower.substring(2);
            if (last == 'L') return "J";
            try {
                long n = Long.parseUnsignedLong(digits, 16);
                if (n > 0x7FFFFFFFL) return "J";
                return "I";
            } catch (Exception e) {
                return "I";
            }
        }

        if (lower.startsWith("0b")) {
            char last = Character.toUpperCase(val.charAt(val.length() - 1));
            String digits = (last == 'L') ? lower.substring(2, lower.length() - 1) : lower.substring(2);
            if (last == 'L') return "J";
            try {
                long n = Long.parseUnsignedLong(digits, 2);
                if (n > 0x7FFFFFFFL) return "J";
                return "I";
            } catch (Exception e) {
                return "I";
            }
        }

        char last = Character.toUpperCase(val.charAt(val.length() - 1));
        if (last == 'F') return "F";
        if (last == 'D') return "D";
        if (last == 'L') return "J";
        if (val.contains(".") || val.contains("p") || val.contains("P") || val.contains("e") || val.contains("E")) return "D";

        try {
            if (lower.startsWith("0") && lower.length() > 1) {
                long n = Long.parseUnsignedLong(lower, 8);
                if (n > 0x7FFFFFFFL) return "J";
                return "I";
            } else {
                long n = Long.parseLong(val);
                if (n > Integer.MAX_VALUE || n < Integer.MIN_VALUE) return "J";
                return "I";
            }
        } catch (Exception e) { return OceanTypeSystem.BIGDECIMAL_DESC; }
    }

    private static List<String> inferArgTypes(OceanParser.ArgumentListContext ctx, InferenceContext context) {
        List<String> types = new ArrayList<>();
        if (ctx != null && ctx.argument() != null) {
            for (OceanParser.ArgumentContext argCtx : ctx.argument()) {
                if (argCtx.expression() != null) {
                    types.add(inferType(argCtx.expression(), context));
                }
            }
        }
        return types;
    }

    private static String resolveRawTypeToDescriptor(String rawType, InferenceContext context) {
        if (rawType == null) return null;
        rawType = rawType.trim();
        if (rawType.isEmpty()) return null;

        if (rawType.startsWith("[") || TypeChecker.isClassType(rawType) ||
            (rawType.length() == 1 && "ZBCSIJFDV".indexOf(rawType.charAt(0)) >= 0)) {
            return rawType;
        }

        if (rawType.endsWith("[]")) {
            String base = rawType.substring(0, rawType.length() - 2);
            return "[" + resolveRawTypeToDescriptor(base, context);
        }

        String baseName = rawType;
        String generics = "";
        if (rawType.contains("<") && rawType.endsWith(">")) {
            int idx = rawType.indexOf("<");
            baseName = rawType.substring(0, idx).trim();
            generics = rawType.substring(idx);
        }

        String baseDesc = context.getTypeDescriptor(baseName);
        if (baseDesc == null) return FALLBACK;

        if (!generics.isEmpty()) {
            String inner = generics.substring(1, generics.length() - 1).trim();
            List<String> parts = splitGenericsParts(inner);
            StringBuilder sb = new StringBuilder();
            for (String part : parts) {
                if (!sb.isEmpty()) sb.append(",");
                sb.append(resolveRawTypeToDescriptor(part, context));
            }
            if (baseDesc.endsWith(";")) {
                baseDesc = baseDesc.substring(0, baseDesc.length() - 1) + "<" + sb + ">;";
            }
        }

        return baseDesc;
    }

    private static List<String> splitGenericsParts(String inner) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            
            if (c == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    private static boolean statementAlwaysReturns(OceanParser.StatementContext stmt) {
        switch (stmt) {
            case null -> {
                return false;
            }
            case OceanParser.ReturnStmtContext returnStmtContext -> {
                return true;
            }
            case OceanParser.ThrowStmtContext throwStmtContext -> {
                return true;
            }
            case OceanParser.BlockStmtContext blockStmtContext -> {
                return blockHasReturn(blockStmtContext.block());
            }
            case OceanParser.IfStmtContext ifStmtContext -> {
                OceanParser.IfStatementContext ifCtx = ifStmtContext.ifStatement();
                if (ifCtx.statement().size() == 2) {
                    return statementAlwaysReturns(ifCtx.statement(0)) && statementAlwaysReturns(ifCtx.statement(1));
                }
                return false;
            }
            case OceanParser.TryStmtContext tryStmtContext -> {
                OceanParser.TryStatementContext tryCtx = tryStmtContext.tryStatement();
                boolean tryReturns = tryCtx.block(0) != null && blockHasReturn(tryCtx.block(0));
                boolean allCatchesReturn = true;
                if (tryCtx.catchClause() != null) {
                    for (OceanParser.CatchClauseContext catchClause : tryCtx.catchClause()) {
                        if (catchClause.block() == null || !blockHasReturn(catchClause.block())) {
                            allCatchesReturn = false;
                            break;
                        }
                    }
                }
                boolean finallyReturns = false;
                if (tryCtx.FINALLY() != null && tryCtx.block().size() > 1) {
                    finallyReturns = blockHasReturn(tryCtx.block(1));
                }
                if (finallyReturns) return true;
                return tryReturns && allCatchesReturn;
            }
            case OceanParser.SwitchStmtContext switchStmtContext -> {
                OceanParser.SwitchStatementContext switchCtx = switchStmtContext.switchStatement();
                boolean hasDefault = false;
                boolean allCasesReturn = true;
                if (switchCtx.switchCase() != null) {
                    for (OceanParser.SwitchCaseContext sc : switchCtx.switchCase()) {
                        if (sc.block() != null) {
                            allCasesReturn = isAllCasesReturn(allCasesReturn, sc.block().statement());
                        } else if (sc.statement() != null && !sc.statement().isEmpty()) {
                            allCasesReturn = isAllCasesReturn(allCasesReturn, sc.statement());
                        } else {
                            allCasesReturn = false;
                        }
                    }
                }
                if (switchCtx.defaultCase() != null) {
                    hasDefault = true;
                    OceanParser.DefaultCaseContext dc = switchCtx.defaultCase();
                    if (dc.block() != null) {
                        allCasesReturn = isAllCasesReturn(allCasesReturn, dc.block().statement());
                    } else if (dc.statement() != null && !dc.statement().isEmpty()) {
                        allCasesReturn = isAllCasesReturn(allCasesReturn, dc.statement());
                    } else {
                        allCasesReturn = false;
                    }
                }
                return hasDefault && allCasesReturn;
            }
            case OceanParser.LockStmtContext lockStmt -> {
                if (lockStmt.lockBlockStatement() != null && lockStmt.lockBlockStatement().block() != null) {
                    return blockHasReturn(lockStmt.lockBlockStatement().block());
                }
            }
            default -> {
            }
        }
        return false;
    }

    private static boolean isAllCasesReturn(boolean allCasesReturn, List<OceanParser.StatementContext> statement) {
        boolean defaultReturns = false;
        if (statement != null && !statement.isEmpty()) {
            for (OceanParser.StatementContext dcStmt : statement) {
                if (statementAlwaysReturns(dcStmt)) {
                    defaultReturns = true;
                    break;
                }
            }
        }
        if (!defaultReturns) {
            allCasesReturn = false;
        }
        return allCasesReturn;
    }

    private static boolean blockHasReturn(OceanParser.BlockContext block) {
        if (block == null)
            return false;
        for (OceanParser.StatementContext stmt : block.statement()) {
            if (statementAlwaysReturns(stmt))
                return true;
        }
        return false;
    }

    private static String findElemType(List<OceanParser.ArgumentContext> args, InferenceContext context ) {
        String elemType = inferType(args.getFirst().expression(), context);
        for (int i = 1; i < args.size(); i++) {
            String t = inferType(args.get(i).expression(), context);
            if (!elemType.equals(t)) {
                elemType = TypeChecker.getCommonType(elemType, t);
            }
        }
        return elemType;
    }

    private static void findResultTypesInBlock(OceanParser.BlockContext block, List<String> types, InferenceContext context) {
        if (block == null || block.statement() == null) return;
        for (OceanParser.StatementContext stmt : block.statement()) {
            findResultTypesInStatement(stmt, types, context);
        }
    }

    private static void findResultTypesInStatement(OceanParser.StatementContext stmt, List<String> types, InferenceContext context) {
        if (stmt instanceof OceanParser.ResultStmtContext rCtx) {
            types.add(inferType(rCtx.expression(), context));
        } else if (stmt instanceof OceanParser.ReturnStmtContext retCtx) {
            if (retCtx.returnStatement() != null && retCtx.returnStatement().expression() != null) {
                types.add(inferType(retCtx.returnStatement().expression(), context));
            }
        } else if (stmt instanceof OceanParser.BlockStmtContext bCtx) {
            findResultTypesInBlock(bCtx.block(), types, context);
        } else if (stmt instanceof OceanParser.IfStmtContext ifCtx) {
            OceanParser.IfStatementContext ifStmt = ifCtx.ifStatement();
            if (ifStmt != null) {
                if (ifStmt.statement(0) != null) {
                    findResultTypesInStatement(ifStmt.statement(0), types, context);
                }
                if (ifStmt.statement().size() > 1 && ifStmt.statement(1) != null) {
                    findResultTypesInStatement(ifStmt.statement(1), types, context);
                }
            }
        } else if (stmt instanceof OceanParser.TryStmtContext tCtx) {
            OceanParser.TryStatementContext tryStmt = tCtx.tryStatement();
            if (tryStmt != null) {
                if (tryStmt.block() != null) {
                    for (OceanParser.BlockContext b : tryStmt.block()) {
                        findResultTypesInBlock(b, types, context);
                    }
                }
                if (tryStmt.catchClause() != null) {
                    for (OceanParser.CatchClauseContext cc : tryStmt.catchClause()) {
                        findResultTypesInBlock(cc.block(), types, context);
                    }
                }
            }
        } else if (stmt instanceof OceanParser.SwitchStmtContext swCtx) {
            OceanParser.SwitchStatementContext swStmt = swCtx.switchStatement();
            if (swStmt != null) {
                if (swStmt.switchCase() != null) {
                    for (OceanParser.SwitchCaseContext sc : swStmt.switchCase()) {
                        if (sc.statement() != null) {
                            for (OceanParser.StatementContext s : sc.statement()) {
                                findResultTypesInStatement(s, types, context);
                            }
                        }
                        if (sc.block() != null) {
                            findResultTypesInBlock(sc.block(), types, context);
                        }
                    }
                }
                if (swStmt.defaultCase() != null) {
                    if (swStmt.defaultCase().statement() != null) {
                        for (OceanParser.StatementContext s : swStmt.defaultCase().statement()) {
                            findResultTypesInStatement(s, types, context);
                        }
                    }
                    if (swStmt.defaultCase().block() != null) {
                        findResultTypesInBlock(swStmt.defaultCase().block(), types, context);
                    }
                }
            }
        }
    }

    public interface InferenceContext {
        String lookupVariableType(String name);
        String lookupVariableRawType(String name);
        String lookupVariableOriginalType(String name);
        String getFieldType(String ownerDesc, String fieldName);
        String resolveMethodReturnType(String ownerDesc, String methodName, List<String> argTypes);
        String resolveClassName(String name);
        String getTypeDescriptor(String typeName);
        String getCurrentClassName();
        String getCurrentSuperName();
    }
}
