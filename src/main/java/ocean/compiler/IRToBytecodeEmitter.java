package ocean.compiler;

import ocean.compiler.ir.*;
import ocean.compiler.symbol.ClassSymbol;
import org.objectweb.asm.*;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IR ağacını gezip JVM bytecode'u üreten sınıf.
 */
public class IRToBytecodeEmitter extends BaseIRVisitor {
    private ClassWriter classWriter;
    private MethodVisitor methodVisitor;
    private final Map<String, byte[]> generatedClasses = new HashMap<>();
    private String sourceFileName = "Unknown Source";
    private int lastEmittedLineNumber = -1;
    private IRExpression discardRoot;

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    private void emitLineNumber(IRNode node) {
        if (node == null || methodVisitor == null) return;
        int line = node.getLineNumber();
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("  [DEBUG LINE] node=" + node.getClass().getSimpleName() + " line=" + line + " last=" + lastEmittedLineNumber);
        }
        if (line > 0 && line != lastEmittedLineNumber) {
            Label label = new Label();
            methodVisitor.visitLabel(label);
            methodVisitor.visitLineNumber(line, label);
            lastEmittedLineNumber = line;
        }
    }

    private record LoopTarget(String label, Label stopLabel, Label skipLabel, int finallySize) {
    }

    private final Stack<Label> stopLabels = new Stack<>();
    private final Stack<Label> skipLabels = new Stack<>();
    private final Stack<Integer> loopFinallySizes = new Stack<>();
    private final Stack<LoopTarget> loopTargets = new Stack<>();
    private String currentPendingLabel = null;
    private String currentClassName;
    private String currentSuperName;
    private IRClass currentClassNode;
    private IRCompilationUnit currentCompilationUnit;
    private String currentMethodReturnType;
    private boolean currentMethodIsStatic;
    private boolean currentClassIsEnum = false;
    private final Deque<Map<String, Integer>> scopes = new ArrayDeque<>();
    private final Deque<Map<String, String>> typeScopes = new ArrayDeque<>();
    private final Deque<Integer> indexStack = new ArrayDeque<>();
    private int nextLocalIndex = 0;
    private final Stack<Runnable> finallyBlocks = new Stack<>();
    private final Set<String> declaredLambdaCaches = new HashSet<>();
    private final Deque<String> activeSwitchExprTypes = new ArrayDeque<>();
    private final Deque<Label> activeSwitchExprEndLabels = new ArrayDeque<>();
    private final Deque<Integer> activeSwitchExprTempIndices = new ArrayDeque<>();
    private final Map<String, Set<String>> globalInterfaceImplementations = new HashMap<>();
    private boolean isStatementContext = false;
    private boolean wasValueDiscardedByExpr = false;


    private void enterScope() {
        scopes.push(new HashMap<>());
        typeScopes.push(new HashMap<>());
        indexStack.push(nextLocalIndex);
    }

    private void exitScope() {
        if (!scopes.isEmpty()) {
            scopes.pop();
        }
        if (!typeScopes.isEmpty()) {
            typeScopes.pop();
        }
        if (!indexStack.isEmpty()) {
            nextLocalIndex = indexStack.pop();
        }
    }

    private void declareVariable(String name, int index, String type) {
        if (name == null || name.equals("_")) return;
        if (!scopes.isEmpty()) {
            scopes.peek().put(name, index);
        }
        if (!typeScopes.isEmpty() && type != null) {
            typeScopes.peek().put(name, type);
        }
    }

    private void declareVariable(String name, int index) {
        declareVariable(name, index, OceanTypeSystem.OBJECT_DESC);
    }

    private String getVariableType(String name) {
        String lookup = name.equals("super") ? "this" : name;
        for (Map<String, String> scope : typeScopes) {
            if (scope.containsKey(lookup)) {
                return scope.get(lookup);
            }
        }
        return null;
    }

    private int getVariableIndex(String name) {
        String lookup = name.equals("super") ? "this" : name;
        for (Map<String, Integer> scope : scopes) {
            if (scope.containsKey(lookup)) {
                return scope.get(lookup);
            }
        }
        return -1;
    }

    /*private static final Pattern PATTERN_GEN_I = Pattern.compile("Locean/compiler/generated/[^;]+/(I|int);");
    private static final Pattern PATTERN_GEN_Z = Pattern.compile("Locean/compiler/generated/[^;]+/(Z|boolean|bool);");
    private static final Pattern PATTERN_GEN_J = Pattern.compile("Locean/compiler/generated/[^;]+/(J|long);");
    private static final Pattern PATTERN_GEN_F = Pattern.compile("Locean/compiler/generated/[^;]+/(F|float);");
    private static final Pattern PATTERN_GEN_D = Pattern.compile("Locean/compiler/generated/[^;]+/(D|double);");
    private static final Pattern PATTERN_GEN_B = Pattern.compile("Locean/compiler/generated/[^;]+/(B|byte);");
    private static final Pattern PATTERN_GEN_S = Pattern.compile("Locean/compiler/generated/[^;]+/(S|short);");
    private static final Pattern PATTERN_GEN_C = Pattern.compile("Locean/compiler/generated/[^;]+/(C|char);");
*/
    private final Map<String, String> commonSuperClassCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> interfaceCache = new ConcurrentHashMap<>();

    private record TryRange(Label start, Label end, int startInsn, int endInsn) {

        boolean hasInstructions() {
                return endInsn > startInsn;
            }
    }

    private static class TryScope {
        Label currentStart;
        int currentStartInsn;
        final List<TryRange> ranges = new ArrayList<>();
        Runnable finallyBlock;
    }

    private final Deque<TryScope> activeTryScopes = new ArrayDeque<>();

    private int getCurrentInsnCount() {
        if (methodVisitor instanceof CleaningMethodVisitor cmv) {
            return cmv.getInsnCount();
        }
        return 0;
    }

    private void splitTryScopesBeforeInlineFinally(Set<Runnable> executingFinallyBlocks) {
        int insn = getCurrentInsnCount();
        for (TryScope scope : activeTryScopes) {
            if (executingFinallyBlocks == null || (scope.finallyBlock != null && executingFinallyBlocks.contains(scope.finallyBlock))) {
                Label splitEnd = new Label();
                methodVisitor.visitLabel(splitEnd);
                scope.ranges.add(new TryRange(scope.currentStart, splitEnd, scope.currentStartInsn, insn));
            }
        }
    }

    private void resumeTryScopesAfterInlineFinally(Set<Runnable> executingFinallyBlocks) {
        int insn = getCurrentInsnCount();
        for (TryScope scope : activeTryScopes) {
            if (executingFinallyBlocks == null || (scope.finallyBlock != null && executingFinallyBlocks.contains(scope.finallyBlock))) {
                Label splitStart = new Label();
                methodVisitor.visitLabel(splitStart);
                scope.currentStart = splitStart;
                scope.currentStartInsn = insn;
            }
        }
    }

    private static String cleanDesc(String desc) {
        if (desc == null) return null;
        if (desc.length() <= 1) return desc;
        if (desc.equals("<init>") || desc.equals("<clinit>")) return desc;
        desc = desc.replace("?", "");
        /*if (desc.contains("Locean/compiler/generated/") && (desc.contains("/I;") || desc.contains("/Z;") || desc.contains("/J;") || desc.contains("/F;") || desc.contains("/D;") || desc.contains("/B;") || desc.contains("/S;") || desc.contains("/C;") || desc.contains("/int;") || desc.contains("/boolean;") || desc.contains("/byte;") || desc.contains("/short;") || desc.contains("/char;"))) {
            desc = PATTERN_GEN_I.matcher(desc).replaceAll("I");
            desc = PATTERN_GEN_Z.matcher(desc).replaceAll("Z");
            desc = PATTERN_GEN_J.matcher(desc).replaceAll("J");
            desc = PATTERN_GEN_F.matcher(desc).replaceAll("F");
            desc = PATTERN_GEN_D.matcher(desc).replaceAll("D");
            desc = PATTERN_GEN_B.matcher(desc).replaceAll("B");
            desc = PATTERN_GEN_S.matcher(desc).replaceAll("S");
            desc = PATTERN_GEN_C.matcher(desc).replaceAll("C");
        }*/
        desc = OceanTypeSystem.clearLInPrimitive(desc);
        if (!desc.contains("<")) return desc;

        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < desc.length(); i++) {
            char c = desc.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String[] cleanDescs(String[] descs) {
        if (descs == null) return null;
        String[] cleaned = new String[descs.length];
        for (int i = 0; i < descs.length; i++) cleaned[i] = cleanDesc(descs[i]);
        return cleaned;
    }

    public static boolean isValidJvmTypeSignature(String sig) {
        if (sig == null || sig.isEmpty()) return false;
        try {
            new SignatureReader(sig).acceptType(new SignatureVisitor(Opcodes.ASM9) {});
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isValidJvmClassSignature(String sig) {
        if (sig == null || sig.isEmpty()) return false;
        try {
            new SignatureReader(sig).accept(new SignatureVisitor(Opcodes.ASM9) {});
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isValidJvmMethodSignature(String sig) {
        if (sig == null || sig.isEmpty()) return false;
        try {
            new SignatureReader(sig).accept(new SignatureVisitor(Opcodes.ASM9) {});
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String buildClassSignature(String className, String superName, String[] interfaces) {
        if (className == null) return null;
        List<CompilerRegistry.TypeParameterInfo> typeParams = CompilerRegistry.globalTypeParameterRegistry.get(className);
        if (typeParams == null || typeParams.isEmpty()) {
            typeParams = CompilerRegistry.globalTypeParameterRegistry.get(className.replace('/', '.'));
        }
        String simpleClassName = className.contains("/") ? className.substring(className.lastIndexOf('/') + 1) : className;
        if (typeParams == null || typeParams.isEmpty()) {
            typeParams = CompilerRegistry.globalTypeParameterRegistry.get(simpleClassName);
        }
        if (typeParams == null || typeParams.isEmpty()) {
            ClassSymbol sym = CompilerRegistry.getClassSymbol(className);
            if (sym == null) sym = CompilerRegistry.getClassSymbol(className.replace('/', '.'));
            if (sym == null) sym = CompilerRegistry.getClassSymbol(simpleClassName);
            if (sym != null && sym.getTypeParameters() != null && !sym.getTypeParameters().isEmpty()) {
                typeParams = sym.getTypeParameters();
            }
        }
        String registeredSuperSig = CompilerRegistry.globalSuperClassGenericSignatureRegistry.get(className);
        if (registeredSuperSig == null) {
            registeredSuperSig = CompilerRegistry.globalSuperClassGenericSignatureRegistry.get(className.replace('/', '.'));
        }
        if (registeredSuperSig == null) {
            registeredSuperSig = CompilerRegistry.globalSuperClassGenericSignatureRegistry.get(simpleClassName);
        }
        List<String> registeredIfaceSigs = CompilerRegistry.globalInterfaceGenericSignatureRegistry.get(className);
        if (registeredIfaceSigs == null) {
            registeredIfaceSigs = CompilerRegistry.globalInterfaceGenericSignatureRegistry.get(className.replace('/', '.'));
        }
        if (registeredIfaceSigs == null) {
            registeredIfaceSigs = CompilerRegistry.globalInterfaceGenericSignatureRegistry.get(simpleClassName);
        }

        boolean hasTypeParams = typeParams != null && !typeParams.isEmpty();
        boolean hasGenericSuper = registeredSuperSig != null && registeredSuperSig.contains("<");
        boolean hasGenericIface = registeredIfaceSigs != null && registeredIfaceSigs.stream().anyMatch(s -> s != null && s.contains("<"));

        if (!hasTypeParams && !hasGenericSuper && !hasGenericIface) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        if (hasTypeParams) {
            sb.append("<");
            for (CompilerRegistry.TypeParameterInfo tp : typeParams) {
                sb.append(tp.name);
                String bound = (tp.upperBounds != null && !tp.upperBounds.isEmpty()) ? tp.upperBounds.getFirst() : tp.upperBound;
                if (bound == null || bound.isEmpty() || bound.equals("Ljava/lang/Object;") || bound.equals("java/lang/Object")) {
                    sb.append(":Ljava/lang/Object;");
                } else {
                    if (!bound.startsWith("L") && !bound.startsWith("[")) {
                        bound = "L" + bound.replace(".", "/") + ";";
                    }
                    sb.append(":").append(bound);
                }
                if (tp.upperBounds != null && tp.upperBounds.size() > 1) {
                    for (int i = 1; i < tp.upperBounds.size(); i++) {
                        String ifaceBound = tp.upperBounds.get(i);
                        if (!ifaceBound.startsWith("L") && !ifaceBound.startsWith("[")) {
                            ifaceBound = "L" + ifaceBound.replace(".", "/") + ";";
                        }
                        sb.append("::").append(ifaceBound);
                    }
                }
            }
            sb.append(">");
        }

        if (registeredSuperSig != null && !registeredSuperSig.isEmpty()) {
            if (!registeredSuperSig.startsWith("L") && !registeredSuperSig.startsWith("[")) {
                registeredSuperSig = "L" + registeredSuperSig + ";";
            }
            sb.append(registeredSuperSig);
        } else if (superName != null && !superName.isEmpty()) {
            sb.append("L").append(superName.replace(".", "/")).append(";");
        } else {
            sb.append("Ljava/lang/Object;");
        }

        if (registeredIfaceSigs != null && !registeredIfaceSigs.isEmpty()) {
            for (String isig : registeredIfaceSigs) {
                if (!isig.startsWith("L") && !isig.startsWith("[")) {
                    isig = "L" + isig + ";";
                }
                sb.append(isig);
            }
        } else if (interfaces != null) {
            for (String iface : interfaces) {
                if (iface != null && !iface.isEmpty()) {
                    sb.append("L").append(iface.replace(".", "/")).append(";");
                }
            }
        }

        String sig = sb.toString();
        if (isValidJvmClassSignature(sig)) {
            return sig;
        }
        return null;
    }

    private Set<String> getClassTypeParameterNames(String className) {
        Set<String> set = new HashSet<>();
        if (className == null) return set;
        List<CompilerRegistry.TypeParameterInfo> typeParams = CompilerRegistry.globalTypeParameterRegistry.get(className);
        if (typeParams == null || typeParams.isEmpty()) {
            typeParams = CompilerRegistry.globalTypeParameterRegistry.get(className.replace('/', '.'));
        }
        if (typeParams == null || typeParams.isEmpty()) {
            ClassSymbol sym = CompilerRegistry.getClassSymbol(className);
            if (sym == null) sym = CompilerRegistry.getClassSymbol(className.replace('/', '.'));
            if (sym != null && sym.getTypeParameters() != null) {
                typeParams = sym.getTypeParameters();
            }
        }
        if (typeParams != null) {
            for (CompilerRegistry.TypeParameterInfo tp : typeParams) {
                set.add(tp.name);
            }
        }
        return set;
    }

    private String toJvmFieldSignature(String sig, String className) {
        if (sig == null || sig.isEmpty()) return null;
        Set<String> typeParams = getClassTypeParameterNames(className);
        String converted = convertToJvmTypeSignature(sig.trim(), typeParams);
        if (isValidJvmTypeSignature(converted)) {
            boolean isGeneric = converted.contains("<") || converted.contains("*") || converted.contains("+") || converted.contains("-");
            if (!isGeneric && !typeParams.isEmpty()) {
                for (String tp : typeParams) {
                    if (converted.contains("T" + tp + ";")) {
                        isGeneric = true;
                        break;
                    }
                }
            }
            if (isGeneric) {
                return converted;
            }
        }
        return null;
    }

    private String convertToJvmTypeSignature(String sig, Set<String> typeParams) {
        if (sig == null || sig.isEmpty()) return null;
        sig = sig.trim();
        while (sig.endsWith("?")) {
            sig = sig.substring(0, sig.length() - 1).trim();
        }
        if (sig.isEmpty()) return null;

        if (sig.endsWith("[]")) {
            String elem = convertToJvmTypeSignature(sig.substring(0, sig.length() - 2).trim(), typeParams);
            return elem != null ? "[" + elem : null;
        }
        if (sig.startsWith("[")) {
            String elem = convertToJvmTypeSignature(sig.substring(1).trim(), typeParams);
            return elem != null ? "[" + elem : null;
        }
        if (sig.equals("*")) return "*";
        if (sig.startsWith("+") || sig.startsWith("-")) {
            char wildcard = sig.charAt(0);
            String sub = convertToJvmTypeSignature(sig.substring(1).trim(), typeParams);
            return sub != null ? wildcard + sub : null;
        }
        if (sig.startsWith("? extends ") || sig.startsWith("?extends")) {
            String sub = sig.contains("extends") ? sig.substring(sig.indexOf("extends") + 7).trim() : sig;
            String subSig = convertToJvmTypeSignature(sub, typeParams);
            return subSig != null ? "+" + subSig : null;
        }
        if (sig.startsWith("? super ") || sig.startsWith("?super")) {
            String sub = sig.contains("super") ? sig.substring(sig.indexOf("super") + 5).trim() : sig;
            String subSig = convertToJvmTypeSignature(sub, typeParams);
            return subSig != null ? "-" + subSig : null;
        }
        if (typeParams != null && typeParams.contains(sig)) {
            return "T" + sig + ";";
        }
        if (sig.startsWith("T") && sig.endsWith(";") && sig.length() > 2) {
            String candidate = sig.substring(1, sig.length() - 1);
            if (typeParams != null && typeParams.contains(candidate)) {
                return sig;
            }
        }
        if (sig.contains("<")) {
            String s = sig;
            if (s.startsWith("L") && s.endsWith(";")) {
                s = s.substring(1, s.length() - 1);
            }
            int lt = s.indexOf('<');
            int gt = s.lastIndexOf('>');
            if (lt > 0 && gt > lt) {
                String base = s.substring(0, lt).trim();
                String inner = s.substring(lt + 1, gt).trim();
                String baseInternal = OceanTypeSystem.resolveStandardClassPath(base);
                if (baseInternal == null) {
                    baseInternal = base.replace('.', '/');
                }
                if (baseInternal.startsWith("L") && baseInternal.endsWith(";")) {
                    baseInternal = baseInternal.substring(1, baseInternal.length() - 1);
                }
                List<String> args = splitGenericArgs(inner);
                StringBuilder sb = new StringBuilder("L").append(baseInternal).append("<");
                for (String arg : args) {
                    String convertedArg = convertToJvmTypeSignature(arg.trim(), typeParams);
                    if (convertedArg == null) {
                        String boxed = TypeChecker.box(arg.trim());
                        if (boxed != null) {
                            convertedArg = boxed;
                        } else {
                            String resolved = OceanTypeSystem.resolveStandardClassPath(arg.trim());
                            if (resolved == null) resolved = arg.trim().replace('.', '/');
                            if (resolved.startsWith("L") && resolved.endsWith(";")) {
                                convertedArg = resolved;
                            } else {
                                convertedArg = "L" + resolved + ";";
                            }
                        }
                    }
                    sb.append(convertedArg);
                }
                sb.append(">;");
                return sb.toString();
            }
        }
        if ("void".equalsIgnoreCase(sig) || "V".equals(sig)) {
            return "V";
        }
        if (TypeChecker.isPrimitive(sig) || (sig.length() == 1 && "BCDFIJSZ".indexOf(sig.charAt(0)) != -1)) {
            return TypeChecker.cleanDescriptor(sig);
        }
        String path = OceanTypeSystem.resolveStandardClassPath(sig);
        if (path == null) path = sig.replace('.', '/');
        if (path.startsWith("L") && path.endsWith(";")) return path;
        return "L" + path + ";";
    }

    private String buildMethodSignature(IRMethod node, String className) {
        if (node == null || className == null) return null;
        String methodName = node.getName();
        String descriptor = node.getDescriptor();

        Set<String> classTypeParams = getClassTypeParameterNames(className);
        List<String> methodTypeParams = null;
        Map<String, List<String>> mTypeParamsMap = CompilerRegistry.globalMethodTypeParametersRegistry.get(className);
        if (mTypeParamsMap != null) {
            methodTypeParams = mTypeParamsMap.get(methodName + descriptor);
            if (methodTypeParams == null) {
                methodTypeParams = mTypeParamsMap.get(methodName);
            }
        }
        if (methodTypeParams == null) {
            String simpleName = OceanTypeSystem.findSimpleName(className);
            if (!simpleName.equals(className)) {
                Map<String, List<String>> sMap = CompilerRegistry.globalMethodTypeParametersRegistry.get(simpleName);
                if (sMap != null) {
                    methodTypeParams = sMap.get(methodName + descriptor);
                    if (methodTypeParams == null) {
                        methodTypeParams = sMap.get(methodName);
                    }
                }
            }
        }

        Set<String> allTypeParams = new HashSet<>(classTypeParams);
        if (methodTypeParams != null) {
            allTypeParams.addAll(methodTypeParams);
        }

        String genericReturn = null;
        Map<String, String> genReturnMap = CompilerRegistry.globalMethodGenericReturnTypeRegistry.get(className);
        if (genReturnMap != null) {
            genericReturn = genReturnMap.get(methodName + "#" + node.getParameters().size());
            if (genericReturn == null) {
                genericReturn = genReturnMap.get(methodName);
            }
        }
        if (genericReturn == null) {
            String simpleName = OceanTypeSystem.findSimpleName(className);
            if (!simpleName.equals(className)) {
                Map<String, String> sMap = CompilerRegistry.globalMethodGenericReturnTypeRegistry.get(simpleName);
                if (sMap != null) {
                    genericReturn = sMap.get(methodName + "#" + node.getParameters().size());
                    if (genericReturn == null) {
                        genericReturn = sMap.get(methodName);
                    }
                }
            }
        }

        List<CompilerRegistry.MethodParamInfo> paramInfos = null;
        Map<String, List<CompilerRegistry.MethodParamInfo>> pInfoMap = CompilerRegistry.globalMethodParamsRegistry.get(className);
        if (pInfoMap != null) {
            paramInfos = pInfoMap.get(methodName + "#" + node.getParameters().size());
            if (paramInfos == null) {
                paramInfos = pInfoMap.get(methodName);
            }
        }
        if (paramInfos == null) {
            String simpleName = OceanTypeSystem.findSimpleName(className);
            if (!simpleName.equals(className)) {
                Map<String, List<CompilerRegistry.MethodParamInfo>> sMap = CompilerRegistry.globalMethodParamsRegistry.get(simpleName);
                if (sMap != null) {
                    paramInfos = sMap.get(methodName + "#" + node.getParameters().size());
                    if (paramInfos == null) {
                        paramInfos = sMap.get(methodName);
                    }
                }
            }
        }

        boolean hasMethodGenerics = methodTypeParams != null && !methodTypeParams.isEmpty();
        boolean hasGenericReturn = genericReturn != null && (genericReturn.contains("<") || allTypeParams.contains(genericReturn));
        boolean hasGenericParam = false;
        if (paramInfos != null) {
            for (CompilerRegistry.MethodParamInfo pi : paramInfos) {
                if (pi.rawType() != null && (pi.rawType().contains("<") || allTypeParams.contains(pi.rawType()))) {
                    hasGenericParam = true;
                    break;
                }
            }
        }
        if (!hasGenericParam && node.getParameters() != null) {
            for (IRMethod.IRParameter p : node.getParameters()) {
                String d = p.typeDescriptor();
                if (d != null && (d.contains("<") || allTypeParams.contains(d))) {
                    hasGenericParam = true;
                    break;
                }
            }
        }

        if (!hasMethodGenerics && !hasGenericReturn && !hasGenericParam) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        if (hasMethodGenerics) {
            sb.append("<");
            for (String mtp : methodTypeParams) {
                sb.append(mtp).append(":Ljava/lang/Object;");
            }
            sb.append(">");
        }

        sb.append("(");
        List<IRMethod.IRParameter> params = node.getParameters();
        for (int i = 0; i < params.size(); i++) {
            String pTypeToConvert = null;
            if (paramInfos != null && i < paramInfos.size() && paramInfos.get(i).rawType() != null) {
                pTypeToConvert = paramInfos.get(i).rawType();
            }
            if (pTypeToConvert == null || pTypeToConvert.isEmpty()) {
                pTypeToConvert = params.get(i).typeDescriptor();
            }
            String pSig = convertToJvmTypeSignature(pTypeToConvert, allTypeParams);
            if (pSig == null) {
                pSig = TypeChecker.cleanDescriptor(params.get(i).typeDescriptor());
            }
            sb.append(pSig);
        }
        sb.append(")");

        String returnTypeToConvert = genericReturn;
        if (returnTypeToConvert == null || returnTypeToConvert.isEmpty() || "<init>".equals(methodName)) {
            returnTypeToConvert = currentMethodReturnType;
        }
        String retSig = convertToJvmTypeSignature(returnTypeToConvert, allTypeParams);
        if (retSig == null) {
            retSig = TypeChecker.cleanDescriptor(currentMethodReturnType);
        }
        sb.append(retSig);

        String methodSig = sb.toString();
        if (isValidJvmMethodSignature(methodSig)) {
            return methodSig;
        }
        return null;
    }

    private List<String> splitGenericArgs(String inner) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                result.add(inner.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < inner.length()) {
            result.add(inner.substring(start).trim());
        }
        return result;
    }

    private MethodVisitor visitMethod(int access, String name, String descriptor) {
        return visitMethod(access, name, descriptor, null, null);
    }

    private MethodVisitor visitMethod(int access, String name, String descriptor, String[] exceptions) {
        return visitMethod(access, name, descriptor, null, exceptions);
    }

    private MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        if (signature != null && !isValidJvmMethodSignature(signature)) {
            signature = null;
        }
        MethodVisitor mv = classWriter.visitMethod(access, cleanDesc(name), cleanDesc(descriptor), signature, exceptions);
        return new CleaningMethodVisitor(mv);
    }

    private FieldVisitor visitField(int access, String name, String descriptor) {
        String clean = cleanDesc(descriptor);
        if (clean != null && (clean.startsWith("L") || (clean.startsWith("[") && clean.contains("L"))) && !clean.endsWith(";")) {
            clean += ";";
        }
        return classWriter.visitField(access, cleanDesc(name), clean, null, null);
    }

    private FieldVisitor visitField(int access, String name, String descriptor, String signature) {
        String clean = cleanDesc(descriptor);
        if (clean != null && (clean.startsWith("L") || (clean.startsWith("[") && clean.contains("L"))) && !clean.endsWith(";")) {
            clean += ";";
        }
        return classWriter.visitField(access, cleanDesc(name), clean, signature, null);
    }

    private String resolveArrayElementInternalName(String elemType) {
        if (elemType == null || elemType.isEmpty()) return "java/lang/Object";
        if (elemType.startsWith("[")) return TypeChecker.cleanDescriptor(elemType);
        String internalName = TypeChecker.getInternalName(elemType);
        if (internalName == null || internalName.isEmpty()) return "java/lang/Object";
        if (internalName.startsWith("L") && internalName.endsWith(";")) {
            internalName = internalName.substring(1, internalName.length() - 1);
        }
        if (!internalName.contains("/")) {
            String resolved = OceanTypeSystem.resolveInternalClassName(internalName, currentClassName);
            if (resolved != null && !resolved.isEmpty()) {
                internalName = resolved;
            }
        }
        return internalName;
    }

    private ClassWriter createClassWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                type1 = cleanDesc(type1);
                type2 = cleanDesc(type2);
                if (type1.equals(type2))
                    return type1;
                if (type1.equals("java/lang/Object") || type2.equals("java/lang/Object"))
                    return "java/lang/Object";

                String key = type1 + "|" + type2;
                String cached = commonSuperClassCache.get(key);
                if (cached != null) return cached;

                Set<String> supers1 = new HashSet<>();
                String t = type1;
                while (t != null && !t.equals("java/lang/Object")) {
                    supers1.add(t);
                    if (CompilerRegistry.globalSuperClassRegistry.containsKey(t)) {
                        t = CompilerRegistry.globalSuperClassRegistry.get(t);
                    } else {
                        try {
                            Class<?> c = OceanTypeSystem.forName(t.replace('/', '.'));
                            t = (c != null && c.getSuperclass() != null) ? c.getSuperclass().getName().replace('.', '/')
                                    : "java/lang/Object";
                        } catch (Throwable e) {
                            if (Boolean.getBoolean("ocean.debug")) {
                                System.err.println("[WARN] getCommonSuperClass failed for " + t + ": " + e.getMessage());
                            }
                            t = "java/lang/Object";
                        }
                    }
                }
                supers1.add("java/lang/Object");

                t = type2;
                while (t != null && !t.equals("java/lang/Object")) {
                    if (supers1.contains(t)) {
                        commonSuperClassCache.put(key, t);
                        return t;
                    }
                    if (CompilerRegistry.globalSuperClassRegistry.containsKey(t)) {
                        t = CompilerRegistry.globalSuperClassRegistry.get(t);
                    } else {
                        try {
                            Class<?> c = OceanTypeSystem.forName(t.replace('/', '.'));
                            t = (c != null && c.getSuperclass() != null) ? c.getSuperclass().getName().replace('.', '/')
                                    : "java/lang/Object";
                        } catch (Throwable e) {
                            if (Boolean.getBoolean("ocean.debug")) {
                                System.err.println("[WARN] getCommonSuperClass failed for " + t + ": " + e.getMessage());
                            }
                            t = "java/lang/Object";
                        }
                    }
                }
                commonSuperClassCache.put(key, "java/lang/Object");
                return "java/lang/Object";
            }
        };
    }

    public Map<String, byte[]> getGeneratedClasses() {
        return generatedClasses;
    }

    @Override
    public void visitCompilationUnit(IRCompilationUnit node) {
        currentCompilationUnit = node;
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG] Visiting CompilationUnit with " + node.getTypes().size() + " types");
        }
        globalInterfaceImplementations.clear();
        for (IRNode type : node.getTypes()) {
            if (type instanceof IRClass cls) {
                if (!cls.isAbstract()) {
                    for (String iface : cls.getInterfaces()) {
                        String cleanIface = cleanDesc(iface).replace(".", "/");
                        globalInterfaceImplementations.computeIfAbsent(cleanIface, k -> new HashSet<>()).add(cls.getName().replace(".", "/"));
                    }
                }
            }
        }
        for (IRNode type : node.getTypes()) {
            type.accept(this);
        }
    }

    private String resolveTopLevelHost(String currentClass, String enclosingClass) {
        if (enclosingClass != null && !enclosingClass.isEmpty()) {
            String host = enclosingClass.replace('.', '/');
            while (host.contains("$")) {
                int lastIdx = host.lastIndexOf('$');
                host = host.substring(0, lastIdx);
            }
            return host;
        }
        if (currentClass != null && currentClass.contains("$")) {
            String possibleOuter = currentClass.substring(0, currentClass.lastIndexOf('$'));
            if (CompilerRegistry.globalClassAccess.containsKey(possibleOuter)
                    || CompilerRegistry.globalInnerClassOuterMap.containsKey(currentClass)
                    || CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(currentClass)
                    || CompilerRegistry.globalIsInterfaceSet.contains(possibleOuter)
                    || CompilerRegistry.globalClassAccess.containsKey(possibleOuter.replace('/', '.'))) {
                int firstIdx = currentClass.indexOf('$');
                return currentClass.substring(0, firstIdx);
            }
        }
        return null;
    }

    private void emitImplicitSuperCall(MethodVisitor mv, String superInternal) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(superInternal)) {
            String superOuter = CompilerRegistry.globalInnerClassUsedOuterMap.get(superInternal);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", "(L" + superOuter + ";)V", false);
        } else {
            String desc = "()V";
            Map<String, List<String>> overloads = CompilerRegistry.globalOverloadRegistry.get(superInternal);
            if (overloads != null && overloads.containsKey("<init>")) {
                List<String> ctorDescs = overloads.get("<init>");
                if (ctorDescs != null && ctorDescs.contains("()V")) {
                    desc = "()V";
                } else if (ctorDescs != null && !ctorDescs.isEmpty()) {
                    for (String d : ctorDescs) {
                        if (d.equals("()V") || Type.getArgumentTypes(d).length == 0) {
                            desc = d;
                            break;
                        }
                    }
                }
            }
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternal, "<init>", desc, false);
        }
    }

    public static void emitBox(MethodVisitor mv, String primitiveDesc) {
        if (primitiveDesc == null || mv == null) return;
        String clean = TypeChecker.cleanDescriptor(primitiveDesc);
        switch (clean) {
            case "Z", "boolean", "bool" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
            case "B", "byte" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
            case "C", "char" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
            case "S", "short" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
            case "I", "int" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            case "J", "long" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            case "F", "float" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
            case "D", "double" ->
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
        }
    }

    public static void emitUnbox(MethodVisitor mv, String boxedDesc) {
        if (boxedDesc == null || mv == null) return;
        String clean = cleanDesc(boxedDesc);
        switch (clean) {
            case "Ljava/lang/Boolean;" ->
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
            case "Ljava/lang/Byte;" ->
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false);
            case "Ljava/lang/Character;" ->
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
            case "Ljava/lang/Short;" ->
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false);
            case "Ljava/lang/Integer;" ->
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
            case "Ljava/lang/Long;" ->
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
            case "Ljava/lang/Float;" ->
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false);
            case "Ljava/lang/Double;" ->
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
            case "Ljava/lang/Number;" ->
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
        }
    }

    private void emitInnerClassesAttributes(IRNode currentNode, String currentInternalName) {
        if (classWriter == null || currentInternalName == null) return;
        Set<String> visitedInnerClasses = new HashSet<>();

        // 1. EnclosingMethod attribute if this is a local or anonymous class
        if (currentNode instanceof IRClass c && c.getEnclosingClass() != null && c.getEnclosingMethodName() != null) {
            classWriter.visitOuterClass(cleanDesc(c.getEnclosingClass()), c.getEnclosingMethodName(), c.getEnclosingMethodDesc());
        }

        // 2. If current class is itself a nested class (contains '$' or registered in globalInnerClassOuterMap)
        if (currentInternalName.contains("$")) {
            String cur = currentInternalName;
            IRNode curTypeNode = currentNode;
            while (cur.contains("$")) {
                int lastDollar = cur.lastIndexOf('$');
                String outerName = cur.substring(0, lastDollar);
                String simpleName = cur.substring(lastDollar + 1);

                boolean isAnon = false;
                boolean isLoc = false;
                int innerAccess = 0;
                if (curTypeNode instanceof IRClass ic) {
                    isAnon = ic.isAnonymous();
                    isLoc = ic.isLocal();
                    innerAccess = ic.getAccessFlags();
                }
                if (innerAccess == 0) {
                    innerAccess = CompilerRegistry.globalClassAccess.getOrDefault(cur, Opcodes.ACC_PUBLIC);
                }
                if (simpleName.matches("\\d+") || cur.contains("$Anon$")) {
                    isAnon = true;
                }

                String innerNameVal = isAnon ? null : simpleName;
                String outerNameVal = (isAnon || isLoc) ? null : cleanDesc(outerName);
                int legalInnerAccess = innerAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED |
                        Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT |
                        Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM);

                if (visitedInnerClasses.add(cur)) {
                    classWriter.visitInnerClass(cleanDesc(cur), outerNameVal, innerNameVal, legalInnerAccess);
                }

                cur = outerName;
                curTypeNode = findTypeInCompilationUnit(outerName);
            }
        }

        // 3. Emit InnerClasses entries for all nested member types belonging to this class
        if (currentCompilationUnit != null) {
            for (IRNode t : currentCompilationUnit.getTypes()) {
                String memberName = null;
                int memberAccess = 0;
                boolean isAnon = false;
                boolean isLoc = false;
                if (t instanceof IRClass c) {
                    memberName = c.getName().replace('.', '/');
                    memberAccess = c.getAccessFlags();
                    isAnon = c.isAnonymous();
                    isLoc = c.isLocal();
                } else if (t instanceof IREnum e) {
                    memberName = e.getName().replace('.', '/');
                    memberAccess = CompilerRegistry.globalClassAccess.getOrDefault(memberName, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);
                } else if (t instanceof IRInterface i) {
                    memberName = i.getName().replace('.', '/');
                    memberAccess = CompilerRegistry.globalClassAccess.getOrDefault(memberName, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE);
                }
                if (memberName != null && memberName.contains("$") && (memberName.startsWith(currentInternalName + "$") || currentInternalName.startsWith(memberName.substring(0, memberName.lastIndexOf('$'))))) {
                    int lastDollar = memberName.lastIndexOf('$');
                    String outerName = memberName.substring(0, lastDollar);
                    String simpleName = memberName.substring(lastDollar + 1);
                    if (simpleName.matches("\\d+") || memberName.contains("$Anon$")) {
                        isAnon = true;
                    }
                    String innerNameVal = isAnon ? null : simpleName;
                    String outerNameVal = (isAnon || isLoc) ? null : cleanDesc(outerName);
                    int legalInnerAccess = memberAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED |
                            Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT |
                            Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM);

                    if (visitedInnerClasses.add(memberName)) {
                        classWriter.visitInnerClass(cleanDesc(memberName), outerNameVal, innerNameVal, legalInnerAccess);
                    }
                }
            }
        }
    }

    private IRNode findTypeInCompilationUnit(String internalName) {
        if (currentCompilationUnit == null || internalName == null) return null;
        for (IRNode t : currentCompilationUnit.getTypes()) {
            String name = null;
            if (t instanceof IRClass c) name = c.getName().replace('.', '/');
            else if (t instanceof IREnum e) name = e.getName().replace('.', '/');
            else if (t instanceof IRInterface i) name = i.getName().replace('.', '/');
            if (internalName.equals(name)) return t;
        }
        return null;
    }

    private void emitInstanceFieldInitializers(MethodVisitor mv) {
        if (currentClassNode == null) return;
        MethodVisitor oldMv = methodVisitor;
        methodVisitor = mv;
        if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(currentClassName)) {
            String outerFqcn = CompilerRegistry.globalInnerClassUsedOuterMap.get(currentClassName);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitFieldInsn(Opcodes.PUTFIELD, currentClassName, "this$0", "L" + outerFqcn + ";");
        }
        if (!currentClassNode.getInstanceInitializers().isEmpty()) {
            for (IRNode initNode : currentClassNode.getInstanceInitializers()) {
                if (initNode instanceof IRField field) {
                    if (!field.isStatic() && field.getInitialValue() != null) {
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        field.getInitialValue().accept(this);
                        emitConversion(field.getInitialValue().getTypeDescriptor(), field.getTypeDescriptor());
                        mv.visitFieldInsn(Opcodes.PUTFIELD, currentClassName, field.getName(), field.getTypeDescriptor());
                    }
                } else if (initNode instanceof IRBlock block) {
                    block.accept(this);
                }
            }
        } else {
            for (IRField field : currentClassNode.getFields()) {
                if (!field.isStatic() && field.getInitialValue() != null) {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    field.getInitialValue().accept(this);
                    emitConversion(field.getInitialValue().getTypeDescriptor(), field.getTypeDescriptor());
                    mv.visitFieldInsn(Opcodes.PUTFIELD, currentClassName, field.getName(), field.getTypeDescriptor());
                }
            }
        }
        methodVisitor = oldMv;
    }

    @Override
    public void visitClass(IRClass node) {
        declaredLambdaCaches.clear();
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG] Emitting class: " + node.getName());
        }
        currentClassNode = node;
        currentClassIsEnum = false;

        // Scan for used lambdas to prune dead synthetic ones
        Set<String> usedLambdas = new LambdaScanner().scan(node);
        List<IRMethod> methodsToKeep = new ArrayList<>();
        for (IRMethod method : node.getMethods()) {
            if (method.getName().startsWith("lambda$")) {
                if (usedLambdas.contains(method.getName())) {
                    methodsToKeep.add(method);
                } else {
                    if (Boolean.getBoolean("ocean.debug")) {
                        System.out.println("[DEBUG] Pruning unused synthetic lambda method: " + method.getName());
                    }
                }
            } else {
                methodsToKeep.add(method);
            }
        }
        node.getMethods().clear();
        node.getMethods().addAll(methodsToKeep);

        classWriter = createClassWriter();
        currentClassName = node.getName().replace(".", "/");

        String[] interfaces = node.getInterfaces().isEmpty() ? null :
                node.getInterfaces().stream().map(s -> s.replace(".", "/")).distinct().toArray(String[]::new);

        int access = node.getAccessFlags();
        if (node.isAbstract()) access |= Opcodes.ACC_ABSTRACT;
/*
        if (node.isDataClass()) access |= Opcodes.ACC_FINAL;
*/
        if (!node.isSealed() && !node.isNonSealed() && !node.isAbstract()) {
            boolean hasSealedSuper = ClassMetadataCache.isSealed(node.getSuperName());
            if (!hasSealedSuper) {
                for (String iface : node.getInterfaces()) {
                    if (ClassMetadataCache.isSealed(iface)) {
                        hasSealedSuper = true;
                        break;
                    }
                }
            }
            if (hasSealedSuper) {
                access |= Opcodes.ACC_FINAL;
            }
        }

        currentSuperName = node.getSuperName();
        String superName = currentSuperName.replace(".", "/");

        int bytecodeVersion = node.isSealed() ? Opcodes.V17 : CompilerConfig.BYTECODE_VERSION;
        String classSignature = buildClassSignature(currentClassName, superName, interfaces);
        int legalClassAccess = access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL |
                                        Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT |
                                        Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM);
        if (currentClassName.contains("$")) {
            String outerName = currentClassName.substring(0, currentClassName.lastIndexOf('$'));
            if (CompilerRegistry.globalIsInterfaceSet.contains(outerName) || (access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ENUM)) != 0) {
                legalClassAccess |= (access & Opcodes.ACC_STATIC);
            }
        }
        if ((access & Opcodes.ACC_INTERFACE) == 0) {
            legalClassAccess |= Opcodes.ACC_SUPER;
        }
        classWriter.visit(bytecodeVersion, legalClassAccess, cleanDesc(currentClassName), classSignature,
                cleanDesc(superName), cleanDescs(interfaces));

        if (node.isSealed()) {
            for (String sub : node.getPermittedSubclasses()) {
                classWriter.visitPermittedSubclass(cleanDesc(sub.replace(".", "/")));
            }
        }

        for (IRAnnotation anno : node.getAnnotations()) {
            emitAnnotationOnClass(anno);
        }

        int classKind = Metadata.KIND_CLASS;
        if (node.isDataClass()) {
            classKind = Metadata.KIND_DATA_CLASS;
        }
        emitOceanMetadata(classKind);

        if (sourceFileName != null) {
            classWriter.visitSource(sourceFileName, null);
        }

        // Nestmate attributes: Emit NestHost and NestMembers for nested types
        String topLevelHost = resolveTopLevelHost(currentClassName, node.getEnclosingClass());
        if (topLevelHost != null) {
            classWriter.visitNestHost(topLevelHost);
        } else if (currentCompilationUnit != null) {
            for (IRNode t : currentCompilationUnit.getTypes()) {
                String memberName = null;
                if (t instanceof IRClass c) {
                    memberName = c.getName().replace('.', '/');
                } else if (t instanceof IREnum e) {
                    memberName = e.getName().replace('.', '/');
                } else if (t instanceof IRInterface i) {
                    memberName = i.getName().replace('.', '/');
                }
                if (memberName != null && memberName.startsWith(currentClassName + "$")) {
                    classWriter.visitNestMember(memberName);
                }
            }
        }

        // InnerClasses and EnclosingMethod attributes (JVMS §4.7.6, §4.7.7)
        emitInnerClassesAttributes(node, currentClassName);

        // Fields
        for (IRField field : node.getFields()) {
            field.accept(this);
        }

        // Static Initializer <clinit>
        boolean hasStaticInit = !node.getStaticInitializers().isEmpty()
                || node.getFields().stream().anyMatch(f -> f.isStatic() && f.getInitialValue() != null)
                || !node.getStaticBlocks().isEmpty();
        if (hasStaticInit) {
            if (Boolean.getBoolean("ocean.debug")) {
                System.out.println("[DEBUG] Emitting static initializer <clinit> for: " + currentClassName);
            }
            MethodVisitor mv = visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V");
            mv.visitCode();
            MethodVisitor oldMv = methodVisitor;
            methodVisitor = mv;
            if (!node.getStaticInitializers().isEmpty()) {
                for (IRNode initNode : node.getStaticInitializers()) {
                    if (initNode instanceof IRField field) {
                        if (field.isStatic() && field.getInitialValue() != null) {
                            field.getInitialValue().accept(this);
                            emitConversion(field.getInitialValue().getTypeDescriptor(), field.getTypeDescriptor());
                            mv.visitFieldInsn(Opcodes.PUTSTATIC, currentClassName, field.getName(), field.getTypeDescriptor());
                        }
                    } else if (initNode instanceof IRBlock staticBlock) {
                        staticBlock.accept(this);
                    }
                }
            } else {
                for (IRField field : node.getFields()) {
                    if (field.isStatic() && field.getInitialValue() != null) {
                        field.getInitialValue().accept(this);
                        emitConversion(field.getInitialValue().getTypeDescriptor(), field.getTypeDescriptor());
                        mv.visitFieldInsn(Opcodes.PUTSTATIC, currentClassName, field.getName(), field.getTypeDescriptor());
                    }
                }
                for (IRBlock staticBlock : node.getStaticBlocks()) {
                    staticBlock.accept(this);
                }
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            methodVisitor = oldMv;
        }

        // Default Constructor - Only if not already present and not an interface/annotation
        boolean hasCtor = node.getMethods().stream().anyMatch(m -> m.getName().equals("<init>"));
        boolean isInterfaceOrAnno = (node.getAccessFlags() & Opcodes.ACC_INTERFACE) != 0;
        if (!hasCtor && !isInterfaceOrAnno) {
            if ((access & Opcodes.ACC_ENUM) != 0 && CompilerRegistry.globalSuperClassRegistry.containsKey(currentClassName)) {
                String parentEnum = CompilerRegistry.globalSuperClassRegistry.get(currentClassName);
                List<String> parentCtors = new ArrayList<>();
                for (String pKey : Arrays.asList(parentEnum, parentEnum.replace('.', '/'), parentEnum.replace('/', '.'), OceanTypeSystem.findSimpleName(parentEnum))) {
                    if (pKey != null) {
                        if (CompilerRegistry.globalOverloadRegistry.containsKey(pKey) && CompilerRegistry.globalOverloadRegistry.get(pKey).containsKey("<init>")) {
                            for (String d : CompilerRegistry.globalOverloadRegistry.get(pKey).get("<init>")) {
                                if (!parentCtors.contains(d)) parentCtors.add(d);
                            }
                        }
                        if (CompilerRegistry.globalMethodRegistry.containsKey(pKey) && CompilerRegistry.globalMethodRegistry.get(pKey).containsKey("<init>")) {
                            String desc = CompilerRegistry.globalMethodRegistry.get(pKey).get("<init>");
                            if (!parentCtors.contains(desc)) parentCtors.add(desc);
                        }
                    }
                }
                if (parentCtors.isEmpty()) {
                    parentCtors.add("(Ljava/lang/String;I)V");
                }
                for (String ctorDesc : parentCtors) {
                    MethodVisitor mv = visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorDesc);
                    mv.visitCode();
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    Type[] argTypes = Type.getArgumentTypes(ctorDesc);
                    int localIdx = 1;
                    for (Type argType : argTypes) {
                        mv.visitVarInsn(argType.getOpcode(Opcodes.ILOAD), localIdx);
                        localIdx += argType.getSize();
                    }
                    mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", ctorDesc, false);
                    emitInstanceFieldInitializers(mv);
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(localIdx + 1, localIdx + 1);
                    mv.visitEnd();
                }
            } else {
                if (Boolean.getBoolean("ocean.debug")) {
                    System.out.println("[DEBUG] Injecting default constructor into: " + currentClassName + " calling super: " + node.getSuperName());
                }
                int ctorAccess = access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
                MethodVisitor mv = visitMethod(ctorAccess, "<init>", "()V");
                mv.visitCode();
                emitImplicitSuperCall(mv, node.getSuperName().replace(".", "/"));

                emitInstanceFieldInitializers(mv);

                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, 1);
                mv.visitEnd();
            }
        }

        // Methods
        for (IRMethod method : node.getMethods()) {
            method.accept(this);
        }

        emitBridgeMethods(node);

        classWriter.visitEnd();
        String internalName = currentClassName;
        generatedClasses.put(internalName, classWriter.toByteArray());
        // Register in CompilerRegistry for the runner to find main
        CompilerRegistry.globalFieldRegistry.putIfAbsent(internalName, new HashMap<>());
        CompilerRegistry.globalMethodRegistry.putIfAbsent(internalName, new HashMap<>());
    }

    private void gatherAncestors(String classKey, Set<String> result) {
        if (classKey == null || classKey.equals("java/lang/Object")) return;

        String superPath = CompilerRegistry.globalSuperClassRegistry.get(classKey);
        if (superPath != null && !superPath.equals("java/lang/Object") && result.add(superPath)) {
            gatherAncestors(superPath, result);
        }

        String[] registered = CompilerRegistry.globalInterfaceRegistry.get(classKey);
        if (registered != null) {
            for (String itf : registered) {
                if (result.add(itf)) {
                    gatherAncestors(itf, result);
                }
            }
        }

        if (!isOceanStdlibClass(classKey)) {
            try {
                Class<?> cls = OceanTypeSystem.forName(classKey.replace("/", "."));
                if (cls != null) {
                    Class<?> superCls = cls.getSuperclass();
                    if (superCls != null && !superCls.getName().equals("java.lang.Object")) {
                        String superInternal = superCls.getName().replace(".", "/");
                        if (result.add(superInternal)) {
                            gatherAncestors(superInternal, result);
                        }
                    }
                    for (Class<?> itf : cls.getInterfaces()) {
                        String itfPath = itf.getName().replace(".", "/");
                        if (result.add(itfPath)) {
                            gatherAncestors(itfPath, result);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean isOceanStdlibClass(String classKey) {
        return ClassMetadataCache.isOceanStdlibClass(classKey);
    }

    private void emitBridgeMethods(IRClass node) {
        Set<String> allAncestors = new LinkedHashSet<>();
        gatherAncestors(currentClassName, allAncestors);
        if (allAncestors.isEmpty()) return;

        Set<String> existingDescriptors = new HashSet<>();
        for (IRMethod m : node.getMethods()) {
            existingDescriptors.add(m.getName() + cleanDesc(m.getDescriptor()));
        }

        for (String ancestor : allAncestors) {
            String ancestorFq = ancestor.replace(".", "/");
            boolean isInterfaceOrGeneric = isInterfaceOrGenericAncestor(ancestorFq);
            Map<String, List<String>> overloadsMap = CompilerRegistry.globalOverloadRegistry.get(ancestorFq);
            Map<String, String> methodMap = CompilerRegistry.globalMethodRegistry.get(ancestorFq);

            Map<String, List<String>> candidateMethods = new HashMap<>();
            if (overloadsMap != null) {
                for (Map.Entry<String, List<String>> e : overloadsMap.entrySet()) {
                    candidateMethods.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
                }
            }
            if (methodMap != null) {
                for (Map.Entry<String, String> e : methodMap.entrySet()) {
                    List<String> list = candidateMethods.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
                    if (!list.contains(e.getValue())) {
                        list.add(e.getValue());
                    }
                }
            }

            try {
                Class<?> cls = OceanTypeSystem.forName(ancestorFq.replace("/", "."));
                if (cls != null) {
                    Method[] methods = cls.isInterface() ? cls.getMethods() : cls.getDeclaredMethods();
                    for (Method m : methods) {
                        if (Modifier.isStatic(m.getModifiers()) || Modifier.isPrivate(m.getModifiers()) || m.isDefault())
                            continue;
                        StringBuilder sb = new StringBuilder("(");
                        for (Class<?> p : m.getParameterTypes()) {
                            sb.append(Type.getDescriptor(p));
                        }
                        sb.append(")");
                        sb.append(Type.getDescriptor(m.getReturnType()));
                        String desc = sb.toString();
                        List<String> list = candidateMethods.computeIfAbsent(m.getName(), k -> new ArrayList<>());
                        if (!list.contains(desc)) {
                            list.add(desc);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            for (Map.Entry<String, List<String>> entry : candidateMethods.entrySet()) {
                String methodName = entry.getKey();
                if (methodName.equals("<init>") || methodName.equals("<clinit>") || methodName.equals("main")) continue;

                for (String rawAncestorDesc : entry.getValue()) {
                    String ancestorDesc = cleanDesc(rawAncestorDesc);
                    if (existingDescriptors.contains(methodName + ancestorDesc)) continue;

                    int ancestorParamCount = countParameters(ancestorDesc);
                    for (IRMethod targetMethod : node.getMethods()) {
                        String cleanTargetDesc = cleanDesc(targetMethod.getDescriptor());
                        if (targetMethod.getName().equals(methodName) && countParameters(cleanTargetDesc) == ancestorParamCount) {
                            if (!cleanTargetDesc.equals(ancestorDesc)) {
                                if (isBridgeCompatible(ancestorDesc, cleanTargetDesc, isInterfaceOrGeneric)) {
                                    emitSingleBridge(node, methodName, ancestorDesc, targetMethod);
                                    existingDescriptors.add(methodName + ancestorDesc);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isInterfaceOrGenericAncestor(String ancestorFq) {
        if (CompilerRegistry.globalIsInterfaceSet.contains(ancestorFq)) return true;
        if (CompilerRegistry.globalAbstractClassSet.contains(ancestorFq)) return true;
        List<CompilerRegistry.TypeParameterInfo> typeParams = CompilerRegistry.globalTypeParameterRegistry.get(ancestorFq);
        if (typeParams != null && !typeParams.isEmpty()) return true;

        for (String[] ifaces : CompilerRegistry.globalInterfaceRegistry.values()) {
            for (String iface : ifaces) {
                if (iface.replace(".", "/").equals(ancestorFq)) return true;
            }
        }
        try {
            Class<?> cls = OceanTypeSystem.forName(ancestorFq.replace("/", "."));
            if (cls != null && (cls.isInterface() || cls.getTypeParameters().length > 0 || Modifier.isAbstract(cls.getModifiers()))) {
                return true;
            }
        } catch (Exception ignored) {
        }

        return false;
    }

    private boolean isBridgeCompatible(String ancestorDesc, String targetDesc, boolean isInterfaceOrGeneric) {
        List<String> aParams = parseParameterTypes(ancestorDesc);
        List<String> tParams = parseParameterTypes(targetDesc);
        if (aParams.size() != tParams.size()) return false;

        CompilationSession session = CompilationSession.getActiveSession();

        boolean paramsIdentical = true;
        for (int i = 0; i < aParams.size(); i++) {
            String aP = cleanDesc(aParams.get(i));
            String tP = cleanDesc(tParams.get(i));
            if (!aP.equals(tP)) {
                paramsIdentical = false;
                if (!isInterfaceOrGeneric) return false;
                if (!aP.startsWith("L") && !aP.startsWith("[")) return false;
                if (!TypeChecker.isAssignable(aP, tP, session) && !TypeChecker.isBoxedEquivalent(aP, tP)) {
                    return false;
                }
            }
        }

        int aEnd = ancestorDesc.indexOf(')');
        int tEnd = targetDesc.indexOf(')');
        String aRet = aEnd != -1 ? cleanDesc(ancestorDesc.substring(aEnd + 1)) : "V";
        String tRet = tEnd != -1 ? cleanDesc(targetDesc.substring(tEnd + 1)) : "V";

        if (aRet.equals(tRet)) {
            return !paramsIdentical;
        }
        if (aRet.equals("V") || tRet.equals("V")) return false;

        return TypeChecker.isAssignable(aRet, tRet, session) || TypeChecker.isBoxedEquivalent(tRet, aRet);
    }

    private int countParameters(String desc) {
        return parseParameterTypes(desc).size();
    }

    private void emitSingleBridge(IRClass node, String name, String bridgeDesc, IRMethod targetMethod) {
        String cleanBridgeDesc = cleanDesc(bridgeDesc);
        String cleanTargetDesc = cleanDesc(targetMethod.getDescriptor());

        MethodVisitor mv = visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC, name, cleanBridgeDesc);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);

        List<String> bridgeParams = parseParameterTypes(cleanBridgeDesc);
        List<String> targetParams = parseParameterTypes(cleanTargetDesc);
        int localIdx = 1;

        MethodVisitor oldMv = methodVisitor;
        methodVisitor = mv;

        for (int i = 0; i < bridgeParams.size(); i++) {
            String pType = bridgeParams.get(i);
            String targetType = i < targetParams.size() ? targetParams.get(i) : OceanTypeSystem.OBJECT_DESC;
            if (pType.startsWith("L")) {
                mv.visitVarInsn(Opcodes.ALOAD, localIdx++);
                String cleanTarget = TypeChecker.cleanDescriptor(targetType);
                if (cleanTarget.startsWith("L") && !TypeChecker.isObjectType(cleanTarget) && !cleanTarget.equals(pType)) {
                    String clsName = cleanTarget.substring(1, cleanTarget.length() - 1);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, clsName);
                } else if (!cleanTarget.startsWith("L") && !cleanTarget.startsWith("[")) {
                    emitConversion(pType, cleanTarget);
                }
            } else if (pType.startsWith("[")) {
                mv.visitVarInsn(Opcodes.ALOAD, localIdx++);
                if (targetType.startsWith("[") && !targetType.equals(pType)) {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, targetType);
                }
            } else if (pType.equals("J")) {
                mv.visitVarInsn(Opcodes.LLOAD, localIdx);
                localIdx += 2;
                if (!targetType.equals("J")) emitConversion("J", targetType);
            } else if (pType.equals("D")) {
                mv.visitVarInsn(Opcodes.DLOAD, localIdx);
                localIdx += 2;
                if (!targetType.equals("D")) emitConversion("D", targetType);
            } else if (pType.equals("F")) {
                mv.visitVarInsn(Opcodes.FLOAD, localIdx++);
                if (!targetType.equals("F")) emitConversion("F", targetType);
            } else {
                mv.visitVarInsn(Opcodes.ILOAD, localIdx++);
                if (!targetType.equals(pType)) emitConversion(pType, targetType);
            }
        }

        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, currentClassName, name, cleanTargetDesc, false);

        int end = cleanBridgeDesc.indexOf(')');
        String returnType = end != -1 ? cleanBridgeDesc.substring(end + 1) : "V";
        String targetRetType = cleanTargetDesc.substring(cleanTargetDesc.lastIndexOf(')') + 1);

        if (!targetRetType.equals(returnType)) {
            emitConversion(targetRetType, returnType);
        }

        if (returnType.equals("V")) {
            mv.visitInsn(Opcodes.RETURN);
        } else if (returnType.startsWith("L") || returnType.startsWith("[")) {
            mv.visitInsn(Opcodes.ARETURN);
        } else if (returnType.equals("D")) {
            mv.visitInsn(Opcodes.DRETURN);
        } else if (returnType.equals("F")) {
            mv.visitInsn(Opcodes.FRETURN);
        } else if (returnType.equals("J")) {
            mv.visitInsn(Opcodes.LRETURN);
        } else {
            mv.visitInsn(Opcodes.IRETURN);
        }

        mv.visitMaxs(localIdx + 2, localIdx + 1);
        mv.visitEnd();

        methodVisitor = oldMv;
    }

    @Override
    public void visitInterface(IRInterface node) {
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG] Emitting interface: " + node.getName());
        }
        classWriter = createClassWriter();
        currentClassName = node.getName().replace(".", "/");

        String[] interfaces = node.getSuperInterfaces().isEmpty() ? null :
                node.getSuperInterfaces().stream().map(s -> s.replace(".", "/")).distinct().toArray(String[]::new);

        int access = Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE;
        access |= CompilerRegistry.globalClassAccess.getOrDefault(currentClassName, Opcodes.ACC_PUBLIC);
        int legalIfaceAccess = access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE |
                                         Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ANNOTATION);
        if (currentClassName.contains("$")) {
            legalIfaceAccess |= (access & Opcodes.ACC_STATIC);
        }
        int bytecodeVersion = node.isSealed() ? Opcodes.V17 : CompilerConfig.BYTECODE_VERSION;
        String ifaceSignature = buildClassSignature(currentClassName, "java/lang/Object", interfaces);
        classWriter.visit(bytecodeVersion, legalIfaceAccess,
                cleanDesc(currentClassName), ifaceSignature, "java/lang/Object", cleanDescs(interfaces));

        if (node.isSealed()) {
            for (String sub : node.getPermittedSubclasses()) {
                classWriter.visitPermittedSubclass(cleanDesc(sub.replace(".", "/")));
            }
        }

        // Nestmate attributes: Emit NestHost for nested types
        String topLevelHost = resolveTopLevelHost(currentClassName, null);
        if (topLevelHost != null) {
            classWriter.visitNestHost(topLevelHost);
        }

        // InnerClasses and EnclosingMethod attributes
        emitInnerClassesAttributes(node, currentClassName);

        for (IRAnnotation anno : node.getAnnotations()) {
            emitAnnotationOnClass(anno);
        }

        int ifaceKind = ((legalIfaceAccess & Opcodes.ACC_ANNOTATION) != 0)
                ? Metadata.KIND_ANNOTATION
                : Metadata.KIND_INTERFACE;
        emitOceanMetadata(ifaceKind);

        if (sourceFileName != null) {
            classWriter.visitSource(sourceFileName, null);
        }

        for (IRField field : node.getFields()) {
            int fieldAccess = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
            FieldVisitor fv = visitField(fieldAccess, field.getName(), field.getTypeDescriptor());
            for (IRAnnotation anno : field.getAnnotations()) {
                emitAnnotationOnField(fv, anno);
            }
            fv.visitEnd();
        }

        boolean hasStaticInit = node.getFields().stream().anyMatch(f -> f.getInitialValue() != null)
                || !node.getStaticBlocks().isEmpty();
        if (hasStaticInit) {
            MethodVisitor mv = visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V");
            mv.visitCode();
            MethodVisitor oldMv = methodVisitor;
            methodVisitor = mv;
            for (IRField field : node.getFields()) {
                if (field.getInitialValue() != null) {
                    field.getInitialValue().accept(this);
                    emitConversion(field.getInitialValue().getTypeDescriptor(), field.getTypeDescriptor());
                    mv.visitFieldInsn(Opcodes.PUTSTATIC, currentClassName, field.getName(), field.getTypeDescriptor());
                }
            }
            for (IRBlock staticBlock : node.getStaticBlocks()) {
                staticBlock.accept(this);
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            methodVisitor = oldMv;
        }

        for (IRMethod method : node.getMethods()) {
            if (method.getBody() == null) {
                int methodAccess = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
                String[] exceptionsArray = null;
                if (!method.getExceptions().isEmpty()) {
                    exceptionsArray = method.getExceptions().stream()
                            .map(e -> TypeChecker.isClassType(e) ? e.substring(1, e.length() - 1) : e)
                            .toArray(String[]::new);
                }
                MethodVisitor mv = visitMethod(methodAccess, method.getName(), method.getDescriptor(), exceptionsArray);
                for (IRAnnotation anno : method.getAnnotations()) {
                    emitAnnotationOnMethod(mv, anno);
                }
                emitParameterAnnotations(mv, method.getParameters());
                if (method.getDefaultValue() != null) {
                    emitAnnotationDefault(mv, method.getDefaultValue());
                }
                mv.visitEnd();
            } else {
                method.accept(this);
            }
        }

        classWriter.visitEnd();
        String internalName = currentClassName;
        generatedClasses.put(internalName, classWriter.toByteArray());
        CompilerRegistry.globalMethodRegistry.putIfAbsent(internalName, new HashMap<>());
    }

    @Override
    public void visitEnum(IREnum node) {
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG] Emitting enum: " + node.getName());
        }
        classWriter = createClassWriter();
        currentClassName = node.getName().replace(".", "/");
        currentClassIsEnum = true;
        String enumDesc = OceanTypeSystem.wrapObjectType(currentClassName);

        int access = Opcodes.ACC_ENUM;
        boolean hasAbstract = node.getMethods() != null && node.getMethods().stream().anyMatch(IRMethod::isAbstract);
        boolean hasConstantBodies = node.getConstantClassNames() != null && node.getConstantClassNames().stream().anyMatch(Objects::nonNull);
        access |= CompilerRegistry.globalClassAccess.getOrDefault(currentClassName, Opcodes.ACC_PUBLIC);
        if (hasAbstract) {
            access |= Opcodes.ACC_ABSTRACT;
            access &= ~Opcodes.ACC_FINAL;
        } else if (hasConstantBodies) {
            access &= ~Opcodes.ACC_FINAL;
        } else {
            access |= Opcodes.ACC_FINAL;
        }
        String[] ifaces = (node.getInterfaces() != null && !node.getInterfaces().isEmpty()) ?
                node.getInterfaces().stream().map(TypeChecker::getInternalName).map(s -> s.replace(".", "/")).distinct().toArray(String[]::new) : null;
        int legalEnumAccess = access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT |
                                        Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ENUM);
        if (currentClassName.contains("$")) {
            legalEnumAccess |= (access & Opcodes.ACC_STATIC);
        }
        legalEnumAccess |= Opcodes.ACC_SUPER | Opcodes.ACC_ENUM;
        classWriter.visit(CompilerConfig.BYTECODE_VERSION, legalEnumAccess,
                cleanDesc(currentClassName), null, "java/lang/Enum", cleanDescs(ifaces));

        // Nestmate attributes: Emit NestHost for nested types
        String topLevelHost = resolveTopLevelHost(currentClassName, null);
        if (topLevelHost != null) {
            classWriter.visitNestHost(topLevelHost);
        }

        // InnerClasses and EnclosingMethod attributes
        emitInnerClassesAttributes(node, currentClassName);

        for (IRAnnotation anno : node.getAnnotations()) {
            emitAnnotationOnClass(anno);
        }

        emitOceanMetadata(Metadata.KIND_ENUM);

        if (sourceFileName != null) {
            classWriter.visitSource(sourceFileName, null);
        }

        // Enum Constants
        for (String constant : node.getConstants()) {
            visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                    constant, enumDesc).visitEnd();
        }

        // Internal $VALUES field
        visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
                "$VALUES", "[" + enumDesc).visitEnd();

        // Check if any custom constructor was provided
        boolean hasCustomConstructor = false;
        if (node.getMethods() != null) {
            for (IRMethod m : node.getMethods()) {
                if ("<init>".equals(m.getName())) {
                    hasCustomConstructor = true;
                    break;
                }
            }
        }

        if (!hasCustomConstructor) {
            // Default Constructor <init>(String, int)
            int ctorAccess = hasConstantBodies ? 0 : Opcodes.ACC_PRIVATE;
            MethodVisitor mv = visitMethod(ctorAccess, "<init>", "(Ljava/lang/String;I)V");
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ILOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(3, 3);
            mv.visitEnd();
        }

        // Static initializer <clinit>
        MethodVisitor mv = visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V");
        mv.visitCode();
        List<String> constants = node.getConstants();
        methodVisitor = mv; // Use for visiting constants initialization if needed

        // Push size for $VALUES array
        mv.visitLdcInsn(constants.size());
        mv.visitTypeInsn(Opcodes.ANEWARRAY, currentClassName);

        for (int i = 0; i < constants.size(); i++) {
            String name = constants.get(i);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(i);

            String constClass = node.getConstantClass(i);
            String targetType = (constClass != null) ? constClass.replace(".", "/") : currentClassName;

            mv.visitTypeInsn(Opcodes.NEW, targetType);
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn(name);
            mv.visitLdcInsn(i);

            List<IRExpression> constArgs = (node.getConstantArguments() != null && i < node.getConstantArguments().size()) ? node.getConstantArguments().get(i) : Collections.emptyList();
            for (IRExpression arg : constArgs) {
                arg.accept(this);
            }

            String ctorDesc = null;
            if (node.getMethods() != null) {
                boolean hasSpecialConstructor = false;
                for (IRMethod m : node.getMethods()) {
                    if ("<init>".equals(m.getName())) {
                        hasSpecialConstructor = true;
                        Type[] paramTypes = Type.getArgumentTypes(m.getDescriptor());
                        if (paramTypes.length == constArgs.size() + 2) {
                            boolean match = true;
                            for (int j = 0; j < constArgs.size(); j++) {
                                String argType = TypeChecker.cleanDescriptor(constArgs.get(j).getTypeDescriptor());
                                String paramType = paramTypes[j + 2].getDescriptor();
                                if (!argType.equals(paramType) && !TypeChecker.isAssignable(paramType, argType, CompilationSession.getActiveSession())) {
                                    match = false;
                                    break;
                                }
                            }
                            if (match) {
                                ctorDesc = m.getDescriptor();
                                break;
                            }
                        }
                    }
                }
                if (!hasSpecialConstructor && constArgs.isEmpty()) {
                    ctorDesc = "(Ljava/lang/String;I)V";
                }
            }
            if (ctorDesc == null) {
                reportError(node, "Cannot find constructor for enum constant " + name);
                return;
            }

            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, targetType, "<init>", ctorDesc, false);

            mv.visitInsn(Opcodes.DUP);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, currentClassName, name, enumDesc);

            mv.visitInsn(Opcodes.AASTORE);
        }

        mv.visitFieldInsn(Opcodes.PUTSTATIC, currentClassName, "$VALUES", "[" + enumDesc);

        // Initialize any other static fields on the enum
        for (IRField field : node.getFields()) {
            if (field.isStatic() && field.getInitialValue() != null) {
                field.getInitialValue().accept(this);
                emitConversion(field.getInitialValue().getTypeDescriptor(), field.getTypeDescriptor());
                mv.visitFieldInsn(Opcodes.PUTSTATIC, currentClassName, field.getName(), field.getTypeDescriptor());
            }
        }

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // values() method
        mv = visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "values", "()[" + enumDesc);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, currentClassName, "$VALUES", "[" + enumDesc);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[" + enumDesc, "clone", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[" + enumDesc);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // valueOf(String) method
        mv = visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "valueOf", "(Ljava/lang/String;)" + enumDesc);
        mv.visitCode();
        mv.visitLdcInsn(Type.getObjectType(currentClassName));
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, currentClassName);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        // Visit other members (fields/methods) if any
        for (IRField field : node.getFields()) field.accept(this);
        for (IRMethod method : node.getMethods()) {
            if ("<init>".equals(method.getName())) {
                if (hasConstantBodies) {
                    method.setAccessFlags(method.getAccessFlags() & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE));
                } else {
                    method.setAccessFlags((method.getAccessFlags() & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE);
                }
            }
            method.accept(this);
        }

        classWriter.visitEnd();
        String internalName = currentClassName;
        generatedClasses.put(internalName, classWriter.toByteArray());
        CompilerRegistry.globalFieldRegistry.putIfAbsent(internalName, new HashMap<>());
        CompilerRegistry.globalMethodRegistry.putIfAbsent(internalName, new HashMap<>());
        currentClassIsEnum = false;
    }

    @Override
    public void visitField(IRField node) {
        int access = node.getAccessFlags();
        if (node.isStatic()) access |= Opcodes.ACC_STATIC;
        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
            access &= ~Opcodes.ACC_SYNCHRONIZED;
            access |= Opcodes.ACC_VOLATILE;
        }
        access &= (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED |
                   Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_VOLATILE |
                   Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ENUM);
        String rawDesc = node.getTypeDescriptor();
        String signature = node.getGenericSignature();
        if (signature == null) {
            Map<String, String> fieldSigs = CompilerRegistry.globalFieldGenericSignatureRegistry.get(currentClassName);
            if (fieldSigs != null) {
                signature = fieldSigs.get(node.getName());
            }
        }
        if (signature != null) {
            signature = toJvmFieldSignature(signature, currentClassName);
        }
        if (signature == null && rawDesc != null && rawDesc.contains("<") && rawDesc.contains(">")) {
            signature = toJvmFieldSignature(rawDesc, currentClassName);
        }
        if (rawDesc != null) {
            rawDesc = cleanDesc(rawDesc);
            if (rawDesc.contains("<")) {
                int lt = rawDesc.indexOf('<');
                rawDesc = rawDesc.substring(0, lt);
            }
            if ((rawDesc.startsWith("L") || (rawDesc.startsWith("[") && rawDesc.contains("L"))) && !rawDesc.endsWith(";")) {
                rawDesc = rawDesc + ";";
            }
        }
        Set<String> classTypeParams = getClassTypeParameterNames(currentClassName);
        if (rawDesc != null && (classTypeParams.contains(rawDesc) || (rawDesc.startsWith("T") && rawDesc.endsWith(";")))) {
            rawDesc = OceanTypeSystem.OBJECT_DESC;
        }
        if (signature != null && !isValidJvmTypeSignature(signature)) {
            signature = null;
        }
        FieldVisitor fv = visitField(access, node.getName(), rawDesc, signature);
        for (IRAnnotation anno : node.getAnnotations()) {
            emitAnnotationOnField(fv, anno);
        }
        fv.visitEnd();

        // Register in CompilerRegistry
        CompilerRegistry.globalFieldRegistry.computeIfAbsent(currentClassName, k -> new HashMap<>()).put(node.getName(), cleanDesc(rawDesc));
        CompilerRegistry.globalFieldStaticity.computeIfAbsent(currentClassName, k -> new HashMap<>()).put(node.getName(), node.isStatic());
    }

    @Override
    public void visitMethod(IRMethod node) {
        lastEmittedLineNumber = -1;
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG] Emitting method: " + node.getName());
        }
        scopes.clear();
        typeScopes.clear();
        indexStack.clear();
        finallyBlocks.clear();
        stopLabels.clear();
        skipLabels.clear();
        loopFinallySizes.clear();

        currentMethodIsStatic = node.isStatic();
        nextLocalIndex = node.isStatic() ? 0 : 1;
        enterScope();
        if (!node.isStatic()) {
            declareVariable("this", 0, OceanTypeSystem.wrapObjectType(currentClassName));
            declareVariable("super", 0, OceanTypeSystem.wrapObjectType(currentClassName));
        }

        for (IRMethod.IRParameter param : node.getParameters()) {
            declareVariable(param.name(), nextLocalIndex, param.typeDescriptor());
            String d = param.typeDescriptor();
            nextLocalIndex += (d.equals("J") || d.equals("D")) ? 2 : 1;
        }

        int access = node.getAccessFlags();
        if (currentClassIsEnum && "<init>".equals(node.getName())) {
            access &= ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED);
            if ((access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
                access |= Opcodes.ACC_PRIVATE;
            }
        }
        if (node.isStatic()) {
            access |= Opcodes.ACC_STATIC;
        } else {
            access &= ~Opcodes.ACC_STATIC;
        }
        if (node.isAbstract()) access |= Opcodes.ACC_ABSTRACT;
        if (node.isNative()) access |= Opcodes.ACC_NATIVE;
        String desc = TypeChecker.cleanDescriptor(node.getDescriptor());
        currentMethodReturnType = desc.substring(desc.lastIndexOf(')') + 1);
        // Exceptions attribute: throws listesini JVM'ye bildir
        String[] exceptionsArray = null;
        if (!node.getExceptions().isEmpty()) {
            exceptionsArray = node.getExceptions().stream()
                    .map(e -> TypeChecker.isClassType(e) ? e.substring(1, e.length() - 1) : e)
                    .toArray(String[]::new);
        }
        String methodSignature = buildMethodSignature(node, currentClassName);
        methodVisitor = visitMethod(access, node.getName(), desc, methodSignature, exceptionsArray);
        if (Boolean.getBoolean("ocean.debug")) {
            methodVisitor = new LoggingMethodVisitor(methodVisitor, node.getName());
        }

        for (IRAnnotation anno : node.getAnnotations()) {
            emitAnnotationOnMethod(methodVisitor, anno);
        }
        emitParameterAnnotations(methodVisitor, node.getParameters());
        if (node.getDefaultValue() != null) {
            emitAnnotationDefault(methodVisitor, node.getDefaultValue());
        }

        // Register in CompilerRegistry
        Map<String, String> methods = CompilerRegistry.globalMethodRegistry.computeIfAbsent(currentClassName, k -> new HashMap<>());
        methods.put(node.getName(), node.getDescriptor());

        Map<String, Boolean> staticity = CompilerRegistry.globalMethodStaticity.computeIfAbsent(currentClassName, k -> new HashMap<>());
        staticity.put(node.getName(), node.isStatic());

        if (!node.isAbstract() && !node.isNative()) {
            methodVisitor.visitCode();
            if (node.getName().equals("<init>")) {
                int explicitCtorIndex = -1;
                boolean hasThisCall = false;
                boolean hasSuperCall = false;
                IRBlock block = null;
                if (node.getBody() instanceof IRBlock b) {
                    block = b;
                    for (int i = 0; i < block.getStatements().size(); i++) {
                        IRStatement s = block.getStatements().get(i);
                        if (s instanceof IRExprStatement exprStmt && exprStmt.getExpression() instanceof IRMethodCall mc) {
                            if (mc.getName().equals("<init>") && (mc.isSuperCall() || mc.isThisCall())) {
                                explicitCtorIndex = i;
                                if (mc.isThisCall()) {
                                    hasThisCall = true;
                                } else {
                                    hasSuperCall = true;
                                }
                                break;
                            }
                        }
                    }
                }

                if (explicitCtorIndex != -1) {
                    // 1. Prologue: statements before super(...) or this(...)
                    for (int i = 0; i < explicitCtorIndex; i++) {
                        block.getStatements().get(i).accept(this);
                    }

                    // 2. Explicit constructor invocation (super(...) or this(...))
                    block.getStatements().get(explicitCtorIndex).accept(this);

                    // 3. If super(...) was called (not this(...)), initialize instance fields
                    if (hasSuperCall) {
                        emitInstanceFieldInitializers(methodVisitor);
                    }

                    // 4. Epilogue: statements after super(...) or this(...)
                    for (int i = explicitCtorIndex + 1; i < block.getStatements().size(); i++) {
                        block.getStatements().get(i).accept(this);
                    }
                } else {
                    // Emit manual super() call
                    emitImplicitSuperCall(methodVisitor, currentSuperName.replace(".", "/"));

                    // Emit instance field initializations
                    emitInstanceFieldInitializers(methodVisitor);

                    // Compile all statements
                    if (node.getBody() != null) {
                        if (node.getName() != null && node.getName().contains("main")) {
                            if (node.getBody() instanceof IRBlock) {
                                if (Boolean.getBoolean("ocean.debug")) {
                                    for (IRStatement s : ((IRBlock) node.getBody()).getStatements()) {
                                        System.out.println("  [STMT] " + s.getClass().getSimpleName());
                                    }
                                }
                            }
                        }
                        node.getBody().accept(this);
                    }
                }
            } else {
                if (node.getBody() != null) {
                    node.getBody().accept(this);
                }
            }

            // Add implicit return
            if (node.getBody() == null || !alwaysTerminates(node.getBody())) {
                if (desc.endsWith("V")) {
                    runFinallyBeforeReturn();
                    methodVisitor.visitInsn(Opcodes.RETURN);
                } else {
                    if (node.getName() == null || !node.getName().startsWith("lambda$")) {
                        reportError(node, "Missing return statement in non-void method '" + node.getName() + "'.");
                    }

                    runFinallyBeforeReturn();
                    boolean endPrimitive = TypeChecker.isPrimitive(desc.substring(desc.lastIndexOf(')') + 1));
                    if (desc.contains("[")) {
                        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                        methodVisitor.visitInsn(Opcodes.ARETURN);
                    } else if (endPrimitive) {
                        if (desc.endsWith("I") || desc.endsWith("Z") || desc.endsWith("B") || desc.endsWith("S") || desc.endsWith("C")) {
                            methodVisitor.visitInsn(Opcodes.ICONST_0);
                            methodVisitor.visitInsn(Opcodes.IRETURN);
                        } else if (desc.endsWith("J")) {
                            methodVisitor.visitInsn(Opcodes.LCONST_0);
                            methodVisitor.visitInsn(Opcodes.LRETURN);
                        } else if (desc.endsWith("F")) {
                            methodVisitor.visitInsn(Opcodes.FCONST_0);
                            methodVisitor.visitInsn(Opcodes.FRETURN);
                        } else if (desc.endsWith("D")) {
                            methodVisitor.visitInsn(Opcodes.DCONST_0);
                            methodVisitor.visitInsn(Opcodes.DRETURN);
                        }
                    } else {
                        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                        methodVisitor.visitInsn(Opcodes.ARETURN);
                    }
                }
            }
            methodVisitor.visitMaxs(0, 0);
        }
        exitScope();
        methodVisitor.visitEnd();
    }

    @Override
    public void visitBlock(IRBlock node) {
        enterScope();
        for (IRStatement stmt : node.getStatements()) {
            emitLineNumber(stmt);
            stmt.accept(this);
        }
        exitScope();
    }

    @Override
    public void visitVariableDecl(IRVariableDecl node) {
        String desc = node.getTypeDescriptor();
        if (node.getInitialValue() != null) {
            node.getInitialValue().accept(this);
            // Use the JVM-actual (erased) type for conversion when initializer is a generic-refined method call
            String initActualType = getArgActualType(node.getInitialValue());
            // Clean the declared type to remove generics before emitConversion (CHECKCAST needs clean class name)
            String cleanDesc = TypeChecker.cleanDescriptor(desc);
            emitConversion(initActualType, cleanDesc);
        } else {
            emitPushDefaultValue(methodVisitor, desc);
        }

        int index = nextLocalIndex;
        declareVariable(node.getName(), index, desc);

        emitStore(desc, index);
        String cleanType = cleanDesc(desc);
        nextLocalIndex += (cleanType != null && (cleanType.equals("J") || cleanType.equals("D"))) ? 2 : 1;
    }

    @Override
    public void visitExprStatement(IRExprStatement node) {
        boolean oldContext = isStatementContext;
        IRExpression oldDiscardRoot = discardRoot;
        boolean oldDiscarded = wasValueDiscardedByExpr;

        isStatementContext = true;
        discardRoot = node.getExpression();
        wasValueDiscardedByExpr = false;

        try {
            node.getExpression().accept(this);
        } finally {
            discardRoot = oldDiscardRoot;
            isStatementContext = oldContext;
        }

        String desc = node.getExpression().getTypeDescriptor();
        if (desc != null && !desc.equals("V") && !wasValueDiscardedByExpr) {
            if (desc.equals("D") || desc.equals("J")) methodVisitor.visitInsn(Opcodes.POP2);
            else methodVisitor.visitInsn(Opcodes.POP);
        }
        wasValueDiscardedByExpr = oldDiscarded;
    }

    private boolean isSameReceiver(IRExpression r1, IRExpression r2) {
        if (r1 == null && r2 == null) return true;
        if (r1 == null || r2 == null) {
            IRExpression nonNull = (r1 != null) ? r1 : r2;
            return nonNull instanceof IRVariableAccess v && "this".equals(v.getName());
        }
        if (r1 instanceof IRVariableAccess v1 && r2 instanceof IRVariableAccess v2) {
            return Objects.equals(v1.getName(), v2.getName()) && isSameReceiver(v1.getReceiver(), v2.getReceiver());
        }
        return false;
    }

    private boolean isSameArrayAccess(IRArrayAccess a1, IRArrayAccess a2) {
        if (a1 == null || a2 == null) return false;
        if (a1.getArray() instanceof IRVariableAccess v1 && a2.getArray() instanceof IRVariableAccess v2) {
            if (!Objects.equals(v1.getName(), v2.getName()) || !isSameReceiver(v1.getReceiver(), v2.getReceiver())) {
                return false;
            }
        } else {
            return false;
        }

        if (a1.getIndex() instanceof IRVariableAccess i1 && a2.getIndex() instanceof IRVariableAccess i2) {
            return Objects.equals(i1.getName(), i2.getName()) && isSameReceiver(i1.getReceiver(), i2.getReceiver());
        } else if (a1.getIndex() instanceof IRLiteral l1 && a2.getIndex() instanceof IRLiteral l2) {
            return Objects.equals(l1.getValue(), l2.getValue());
        }
        return false;
    }

    @Override
    public void visitAssignment(IRAssignment node) {
        boolean statementContext = isStatementContext;
        isStatementContext = false;

        try {
            if (node.getTarget() instanceof IRVariableAccess varAccess) {
                if (varAccess.isField()) {
                    String ownerInternal = varAccess.getOwner() != null ? varAccess.getOwner().replace(".", "/") : currentClassName;
                    String fieldDesc = null;
                    Map<String, String> fieldMap = CompilerRegistry.globalFieldRegistry.get(ownerInternal);
                    if (fieldMap != null && fieldMap.containsKey(varAccess.getName())) {
                        fieldDesc = fieldMap.get(varAccess.getName());
                    }
                    if (fieldDesc == null || fieldDesc.isEmpty()) {
                        fieldDesc = varAccess.getOriginalTypeDescriptor();
                    }
                    if (fieldDesc == null || fieldDesc.isEmpty()) {
                        fieldDesc = varAccess.getTypeDescriptor();
                    }
                    fieldDesc = cleanDesc(fieldDesc);

                    if (varAccess.isStatic()) {
                        node.getValue().accept(this);

                        emitConversion(node.getValue().getTypeDescriptor(), fieldDesc);
                        if (!statementContext) {
                            if ("J".equals(fieldDesc) || "D".equals(fieldDesc)) {
                                methodVisitor.visitInsn(Opcodes.DUP2);
                            } else {
                                methodVisitor.visitInsn(Opcodes.DUP);
                            }
                        }
                        methodVisitor.visitFieldInsn(Opcodes.PUTSTATIC, ownerInternal, varAccess.getName(), fieldDesc);
                    } else {
                        // Instance field: evaluate receiver exactly once
                        if (varAccess.getReceiver() != null) {
                            varAccess.getReceiver().accept(this);
                            String recType = getReceiverType(varAccess.getReceiver());

                            if (recType != null && !recType.equals("L" + ownerInternal + ";") && !recType.equals(ownerInternal)) {
                                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, ownerInternal);
                            }
                        } else {
                            methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                        }

                        if (node.isCompound()
                                && node.getValue() instanceof IRBinaryOp binOp
                                && binOp.getLeft() instanceof IRVariableAccess leftVar
                                && leftVar.isField()
                                && varAccess.getName().equals(leftVar.getName())) {
                            // Compound assignment to instance field: obj.field op= right
                            // Stack: [receiver]
                            methodVisitor.visitInsn(Opcodes.DUP);
                            // Stack: [receiver, receiver]
                            methodVisitor.visitFieldInsn(Opcodes.GETFIELD, ownerInternal, varAccess.getName(), fieldDesc);
                            // Stack: [receiver, currentVal]

                            if (TypeChecker.isStringType(fieldDesc) && binOp.getOperator() == IRBinaryOp.Op.ADD) {
                                binOp.getRight().accept(this);
                                String rightType = binOp.getRight().getTypeDescriptor();
                                if (!TypeChecker.isStringType(rightType)) {
                                    emitStringValueOf(rightType);
                                }
                                emitStringConcat2();
                            } else {
                                String opType = binOp.getTypeDescriptor();
                                emitConversion(fieldDesc, opType);
                                binOp.getRight().accept(this);
                                if (binOp.getOperator() == IRBinaryOp.Op.LSHIFT || binOp.getOperator() == IRBinaryOp.Op.RSHIFT || binOp.getOperator() == IRBinaryOp.Op.URSHIFT) {
                                    emitConversion(binOp.getRight().getTypeDescriptor(), "I");
                                } else {
                                    emitConversion(binOp.getRight().getTypeDescriptor(), opType);
                                }
                                emitBinaryOpDirect(binOp.getOperator(), opType);
                                emitConversion(opType, fieldDesc);
                            }

                            // Stack: [receiver, newVal]
                        } else {
                            // Normal assignment: obj.field = right
                            node.getValue().accept(this);
                            emitConversion(node.getValue().getTypeDescriptor(), fieldDesc);

                        }
                        if (!statementContext) {
                            if ("J".equals(fieldDesc) || "D".equals(fieldDesc)) {
                                methodVisitor.visitInsn(Opcodes.DUP2_X1);
                            } else {
                                methodVisitor.visitInsn(Opcodes.DUP_X1);
                            }
                        }

                        methodVisitor.visitFieldInsn(Opcodes.PUTFIELD, ownerInternal, varAccess.getName(), fieldDesc);
                    }
                } else {
                    node.getValue().accept(this);
                    int index = getVariableIndex(varAccess.getName());
                    if (index != -1) {
                        String targetType = varAccess.getTypeDescriptor();
                        String declaredType = getVariableType(varAccess.getName());
                        if (declaredType != null && !declaredType.isEmpty()) {
                            targetType = declaredType;
                        }
                        emitConversion(node.getValue().getTypeDescriptor(), targetType);
                        String cleanTarget = cleanDesc(targetType);
                        if (!statementContext) {
                            if ("J".equals(cleanTarget) || "D".equals(cleanTarget)) {
                                methodVisitor.visitInsn(Opcodes.DUP2);
                            } else {
                                methodVisitor.visitInsn(Opcodes.DUP);
                            }
                        }
                        emitStore(targetType, index);
                    } else {
                        reportError(node, "Cannot resolve local variable '" + varAccess.getName() + "' for assignment.");
                        throw new CompilationException("Cannot resolve local variable '" + varAccess.getName() + "' for assignment.");
                    }
                }
            } else if (node.getTarget() instanceof IRArrayAccess arrayAccess) {
                arrayAccess.getArray().accept(this);
                String desc = arrayAccess.getTypeDescriptor();
                if (arrayAccess.getArray() instanceof IRArrayAccess) {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "[" + desc);
                } else {
                    String arrayType = getReceiverType(arrayAccess.getArray());
                    if (arrayType != null && (TypeChecker.isObjectType(arrayType) || arrayType.equals("java/lang/Object"))) {
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "[" + desc);
                    }
                }
                arrayAccess.getIndex().accept(this);
                emitConversion(arrayAccess.getIndex().getTypeDescriptor(), "I");

                if (node.isCompound() && node.getValue() instanceof IRBinaryOp binOp && binOp.getLeft() instanceof IRArrayAccess) {
                    // Compound assignment: arr[i] op= right (e.g. +=, -=, *=, /=, %=)
                    // Duplicate array reference and index on stack
                    methodVisitor.visitInsn(Opcodes.DUP2);

                    // Load existing value
                    switch (desc) {
                        case "I" -> methodVisitor.visitInsn(Opcodes.IALOAD);
                        case "J" -> methodVisitor.visitInsn(Opcodes.LALOAD);
                        case "F" -> methodVisitor.visitInsn(Opcodes.FALOAD);
                        case "D" -> methodVisitor.visitInsn(Opcodes.DALOAD);
                        case "B", "Z" -> methodVisitor.visitInsn(Opcodes.BALOAD);
                        case "C" -> methodVisitor.visitInsn(Opcodes.CALOAD);
                        case "S" -> methodVisitor.visitInsn(Opcodes.SALOAD);
                        default -> methodVisitor.visitInsn(Opcodes.AALOAD);
                    }

                    if (TypeChecker.isStringType(desc) && binOp.getOperator() == IRBinaryOp.Op.ADD) {
                        binOp.getRight().accept(this);
                        String rightType = binOp.getRight().getTypeDescriptor();
                        if (!TypeChecker.isStringType(rightType)) {
                            emitStringValueOf(rightType);
                        }
                        emitStringConcat2();
                    } else {
                        String opType = binOp.getTypeDescriptor();
                        emitConversion(desc, opType);
                        binOp.getRight().accept(this);
                        if (binOp.getOperator() == IRBinaryOp.Op.LSHIFT || binOp.getOperator() == IRBinaryOp.Op.RSHIFT || binOp.getOperator() == IRBinaryOp.Op.URSHIFT) {
                            emitConversion(binOp.getRight().getTypeDescriptor(), "I");
                        } else {
                            emitConversion(binOp.getRight().getTypeDescriptor(), opType);
                        }
                        emitBinaryOpDirect(binOp.getOperator(), opType);
                        emitConversion(opType, desc);
                    }
                } else {
                    node.getValue().accept(this);
                    emitConversion(node.getValue().getTypeDescriptor(), desc);
                }

                // Stack: [array, index, value]
                String cleanD = cleanDesc(desc);
                if (cleanD == null) cleanD = OceanTypeSystem.OBJECT_DESC;
                if (!statementContext) {
                    if ("J".equals(cleanD) || "D".equals(cleanD)) {
                        methodVisitor.visitInsn(Opcodes.DUP2_X2);
                    } else {
                        methodVisitor.visitInsn(Opcodes.DUP_X2);
                    }
                }

                switch (cleanD) {
                    case "I" -> methodVisitor.visitInsn(Opcodes.IASTORE);
                    case "J" -> methodVisitor.visitInsn(Opcodes.LASTORE);
                    case "F" -> methodVisitor.visitInsn(Opcodes.FASTORE);
                    case "D" -> methodVisitor.visitInsn(Opcodes.DASTORE);
                    case "B", "Z" -> methodVisitor.visitInsn(Opcodes.BASTORE);
                    case "C" -> methodVisitor.visitInsn(Opcodes.CASTORE);
                    case "S" -> methodVisitor.visitInsn(Opcodes.SASTORE);
                    default -> methodVisitor.visitInsn(Opcodes.AASTORE);
                }
            }
        } finally {
            isStatementContext = statementContext;
            if (statementContext) {
                wasValueDiscardedByExpr = true;
            }
        }
    }

    @Override
    public void visitVariableAccess(IRVariableAccess node) {
        if (node.isField()) {
            if ("class".equals(node.getName())) {
                String owner = node.getOwner();
                if (owner == null && node.getReceiver() != null) {
                    owner = getReceiverType(node.getReceiver());
                }
                if (owner != null) {
                    owner = owner.replace(".", "/");
                    if (TypeChecker.isPrimitive(owner) || "V".equals(owner)) {
                        emitPrimitiveClassLiteral(owner);
                        return;
                    }
                    String ownerDesc;
                    if (owner.startsWith("[")) {
                        ownerDesc = owner;
                    } else if (TypeChecker.isClassType(owner)) {
                        ownerDesc = owner;
                    } else {
                        ownerDesc = "L" + owner + ";";
                    }
                    methodVisitor.visitLdcInsn(Type.getType(ownerDesc));
                    return;
                }
            }

            if (node.getReceiver() != null) {
                node.getReceiver().accept(this);

                if (node.isSafeAccess()) {
                    Label isNull = new Label();
                    Label end = new Label();
                    methodVisitor.visitInsn(Opcodes.DUP);
                    methodVisitor.visitJumpInsn(Opcodes.IFNULL, isNull);

                    // Not null
                    if (node.getName() != null && node.getReceiver() != null) {
                        String recType = getReceiverType(node.getReceiver());
                        boolean isArrayOrListLength = node.getName().equals("length") && recType != null &&
                                (recType.startsWith("[") || recType.equals("Locean/stdlib/OceanList;"));
                        if (isArrayOrListLength) {
                            if (recType.startsWith("[")) {
                                methodVisitor.visitInsn(Opcodes.ARRAYLENGTH);
                            } else {
                                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "ocean/stdlib/OceanList");
                                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "ocean/stdlib/OceanList", "size", "()I", false);
                            }
                            emitConversion("I", node.getTypeDescriptor());
                        } else {
                            if (!node.isStatic()) {
                                String ownerInternal = node.getOwner().replace(".", "/");
                                if (TypeChecker.isClassType(recType) || (recType != null && (!recType.startsWith("L") && recType.contains("/") && !recType.endsWith(";")) && TypeChecker.isClassType("L" + recType + ";"))) {
                                    String recInternal = recType.startsWith("L") && recType.endsWith(";") ? recType.substring(1, recType.length() - 1) : recType;
                                    if (!recInternal.equals(ownerInternal)) {
                                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, ownerInternal);
                                    }
                                }
                            }
                            int op = node.isStatic() ? Opcodes.GETSTATIC : Opcodes.GETFIELD;
                            if (op == Opcodes.GETSTATIC && node.getReceiver() != null) {
                                String cleanRec = cleanDesc(recType);
                                if ("J".equals(cleanRec) || "D".equals(cleanRec)) {
                                    methodVisitor.visitInsn(Opcodes.POP2);
                                } else {
                                    methodVisitor.visitInsn(Opcodes.POP);
                                }
                            }
                            String origDesc = node.getOriginalTypeDescriptor();
                            if (origDesc == null || origDesc.isEmpty()) {
                                origDesc = node.getTypeDescriptor();
                            }
                            methodVisitor.visitFieldInsn(op, node.getOwner().replace(".", "/"), node.getName(), origDesc);
                            emitConversion(origDesc, node.getTypeDescriptor());
                        }
                        methodVisitor.visitJumpInsn(Opcodes.GOTO, end);
                    }

                    // Is null
                    methodVisitor.visitLabel(isNull);
                    methodVisitor.visitInsn(Opcodes.POP);
                    methodVisitor.visitInsn(Opcodes.ACONST_NULL);

                    methodVisitor.visitLabel(end);
                    return;
                }

                // If it's array length, use arraylength instruction; if OceanList/Object, use size()
                if (node.getName().equals("length")) {
                    String recType = getReceiverType(node.getReceiver());
                    if (recType != null && recType.startsWith("[")) {
                        methodVisitor.visitInsn(Opcodes.ARRAYLENGTH);
                        return;
                    } else if (recType != null && recType.equals("Locean/stdlib/OceanList;")) {
                        String owner = node.getOwner() != null ? node.getOwner().replace(".", "/") : "ocean/stdlib/OceanList";
                        if (!CompilerRegistry.globalFieldRegistry.containsKey(owner + "#length")) {
                            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "ocean/stdlib/OceanList");
                            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "ocean/stdlib/OceanList", "size", "()I", false);
                            return;
                        }
                    }
                }
            } else if (!node.isStatic()) {
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            }
            if (!node.isStatic()) {
                String ownerInternal = node.getOwner().replace(".", "/");
                String recType = getReceiverType(node.getReceiver());
                if (TypeChecker.isClassType(recType) || (recType != null && (!recType.startsWith("L") && recType.contains("/") && !recType.endsWith(";")) && TypeChecker.isClassType("L" + recType + ";"))) {
                    String recInternal = recType.startsWith("L") && recType.endsWith(";") ? recType.substring(1, recType.length() - 1) : recType;
                    if (!recInternal.equals(ownerInternal)) {
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, ownerInternal);
                    }
                }
            }
            String ownerInternal = node.getOwner().replace(".", "/");
            String fieldDesc = null;
            Map<String, String> fieldMap = CompilerRegistry.globalFieldRegistry.get(ownerInternal);
            if (fieldMap != null && fieldMap.containsKey(node.getName())) {
                fieldDesc = fieldMap.get(node.getName());
            }
            if (fieldDesc == null || fieldDesc.isEmpty()) {
                fieldDesc = node.getOriginalTypeDescriptor();
            }
            if (fieldDesc == null || fieldDesc.isEmpty()) {
                fieldDesc = node.getTypeDescriptor();
            }
            fieldDesc = cleanDesc(fieldDesc);
            if (fieldDesc != null && fieldDesc.contains("<")) {
                fieldDesc = TypeChecker.cleanDescriptor(fieldDesc);
            }
            int op = node.isStatic() ? Opcodes.GETSTATIC : Opcodes.GETFIELD;
            methodVisitor.visitFieldInsn(op, ownerInternal, node.getName(), fieldDesc);
            if (node.getTypeDescriptor() != null && !node.getTypeDescriptor().equals(fieldDesc)) {
                emitConversion(fieldDesc, node.getTypeDescriptor());
            }
        } else {
            int index = getVariableIndex(node.getName());
            if (index != -1) {
                String desc = node.getTypeDescriptor();
                String declaredType = getVariableType(node.getName());
                if (declaredType != null && !declaredType.isEmpty()) {
                    desc = declaredType;
                }
                emitLoad(desc, index);
                if (node.getTypeDescriptor() != null && !node.getTypeDescriptor().equals(desc)) {
                    emitConversion(desc, node.getTypeDescriptor());
                }
                String cleanType = cleanDesc(node.getTypeDescriptor());
                if (TypeChecker.isClassType(cleanType)) {
                    String origDesc = node.getOriginalTypeDescriptor();
                    if (origDesc != null && !origDesc.equals(node.getTypeDescriptor())) {
                        String target = cleanType.substring(1, cleanType.length() - 1);
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, target);
                    }
                }
            } else if (node.getName().equals("this") || node.getName().equals("super")) {
                if (!currentMethodIsStatic) {
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                } else {
                    reportError(node, "Cannot reference '" + node.getName() + "' from a static context.");
                    throw new CompilationException("Cannot reference '" + node.getName() + "' from a static context.");
                }
            } else if (node.isClassReference()) {
                String owner = node.getOwner() != null ? node.getOwner() : node.getTypeDescriptor();
                if (owner != null) {
                    String clean = cleanDesc(owner);
                    if (clean.startsWith("L") && clean.endsWith(";")) {
                        clean = clean.substring(1, clean.length() - 1);
                    }
                    methodVisitor.visitLdcInsn(Type.getType("L" + clean.replace('.', '/') + ";"));
                }
            } else {
                reportError(node, "Cannot resolve local variable '" + node.getName() + "' during bytecode emission.");
                throw new CompilationException("Cannot resolve local variable '" + node.getName() + "' during bytecode emission.");
            }
        }
    }

    private void emitPrimitiveClassLiteral(String primDesc) {
        String wrapper = switch (primDesc) {
            case "Z", "boolean", "bool" -> "java/lang/Boolean";
            case "B", "byte" -> "java/lang/Byte";
            case "C", "char" -> "java/lang/Character";
            case "S", "short" -> "java/lang/Short";
            case "I", "int" -> "java/lang/Integer";
            case "J", "long" -> "java/lang/Long";
            case "F", "float" -> "java/lang/Float";
            case "D", "double" -> "java/lang/Double";
            case "V", "void" -> "java/lang/Void";
            default -> null;
        };
        if (wrapper != null) {
            methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, wrapper, "TYPE", "Ljava/lang/Class;");
        } else {
            methodVisitor.visitLdcInsn(Type.getType(OceanTypeSystem.OBJECT_DESC));
        }
    }

    @Override
    public void visitIf(IRIfStatement node) {
        if (node.getElseBranch() != null) {
            // if-else: jump to else if condition is false, GOTO → end
            Label elseLabel = new Label();
            Label endLabel = new Label();
            emitBranch(node.getCondition(), elseLabel, false);
            node.getThenBranch().accept(this);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);

            methodVisitor.visitLabel(elseLabel);
            node.getElseBranch().accept(this);
            methodVisitor.visitLabel(endLabel);
        } else {
            // if-only: jump to end if condition is false
            Label endLabel = new Label();
            emitBranch(node.getCondition(), endLabel, false);
            node.getThenBranch().accept(this);
            methodVisitor.visitLabel(endLabel);
        }
    }

    @Override
    public void visitWhile(IRWhileStatement node) {
        Label labelStart = new Label();
        Label labelEnd = new Label();
        String lbl = currentPendingLabel;
        currentPendingLabel = null;
        loopTargets.push(new LoopTarget(lbl, labelEnd, labelStart, finallyBlocks.size()));
        stopLabels.push(labelEnd);
        skipLabels.push(labelStart);
        loopFinallySizes.push(finallyBlocks.size());

        methodVisitor.visitLabel(labelStart);
        emitBranch(node.getCondition(), labelEnd, false);

        node.getBody().accept(this);
        methodVisitor.visitJumpInsn(Opcodes.GOTO, labelStart);
        methodVisitor.visitLabel(labelEnd);
        loopTargets.pop();
        loopFinallySizes.pop();
        stopLabels.pop();
        skipLabels.pop();
    }

    @Override
    public void visitForStatement(IRForStatement node) {
        Label labelStart = new Label();
        Label labelEnd = new Label();
        Label labelStep = new Label();
        String lbl = currentPendingLabel;
        currentPendingLabel = null;
        loopTargets.push(new LoopTarget(lbl, labelEnd, labelStep, finallyBlocks.size()));
        stopLabels.push(labelEnd);
        skipLabels.push(labelStep);
        loopFinallySizes.push(finallyBlocks.size());

        enterScope();
        if (node.isRange()) {
            String type = node.getTypeDescriptor();
            if (type == null) type = node.getFromExpr().getTypeDescriptor();
            if (type == null) type = "I";
            boolean isLong = type.equals("J");
            boolean isFloat = type.equals("F");
            boolean isDouble = type.equals("D");
            boolean isBigDecimal = type.equals(OceanTypeSystem.BIGDECIMAL_DESC);

            int iteratorIndex = nextLocalIndex;
            nextLocalIndex += (isLong || isDouble) ? 2 : 1;
            declareVariable(node.getIteratorName(), iteratorIndex, type);

            node.getFromExpr().accept(this);
            emitConversion(node.getFromExpr().getTypeDescriptor(), type);
            if (isLong) methodVisitor.visitVarInsn(Opcodes.LSTORE, iteratorIndex);
            else if (isFloat) methodVisitor.visitVarInsn(Opcodes.FSTORE, iteratorIndex);
            else if (isDouble) methodVisitor.visitVarInsn(Opcodes.DSTORE, iteratorIndex);
            else if (isBigDecimal) methodVisitor.visitVarInsn(Opcodes.ASTORE, iteratorIndex);
            else methodVisitor.visitVarInsn(Opcodes.ISTORE, iteratorIndex);

            methodVisitor.visitLabel(labelStart);
            if (isLong) methodVisitor.visitVarInsn(Opcodes.LLOAD, iteratorIndex);
            else if (isFloat) methodVisitor.visitVarInsn(Opcodes.FLOAD, iteratorIndex);
            else if (isDouble) methodVisitor.visitVarInsn(Opcodes.DLOAD, iteratorIndex);
            else if (isBigDecimal) methodVisitor.visitVarInsn(Opcodes.ALOAD, iteratorIndex);
            else methodVisitor.visitVarInsn(Opcodes.ILOAD, iteratorIndex);

            node.getToExpr().accept(this);
            // Ensure types match for comparison
            emitConversion(node.getToExpr().getTypeDescriptor(), type);

            if (node.isIncreasing()) {
                if (isLong) {
                    methodVisitor.visitInsn(Opcodes.LCMP);
                    methodVisitor.visitJumpInsn(Opcodes.IFGE, labelEnd);
                } else if (isFloat) {
                    methodVisitor.visitInsn(Opcodes.FCMPG);
                    methodVisitor.visitJumpInsn(Opcodes.IFGE, labelEnd);
                } else if (isDouble) {
                    methodVisitor.visitInsn(Opcodes.DCMPG);
                    methodVisitor.visitJumpInsn(Opcodes.IFGE, labelEnd);
                } else if (isBigDecimal) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "compareTo", "(Ljava/math/BigDecimal;)I", false);
                    methodVisitor.visitJumpInsn(Opcodes.IFGE, labelEnd);
                } else {
                    methodVisitor.visitJumpInsn(Opcodes.IF_ICMPGE, labelEnd);
                }
            } else {
                if (isLong) {
                    methodVisitor.visitInsn(Opcodes.LCMP);
                    methodVisitor.visitJumpInsn(Opcodes.IFLE, labelEnd);
                } else if (isFloat) {
                    methodVisitor.visitInsn(Opcodes.FCMPL);
                    methodVisitor.visitJumpInsn(Opcodes.IFLE, labelEnd);
                } else if (isDouble) {
                    methodVisitor.visitInsn(Opcodes.DCMPL);
                    methodVisitor.visitJumpInsn(Opcodes.IFLE, labelEnd);
                } else if (isBigDecimal) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "compareTo", "(Ljava/math/BigDecimal;)I", false);
                    methodVisitor.visitJumpInsn(Opcodes.IFLE, labelEnd);
                } else {
                    methodVisitor.visitJumpInsn(Opcodes.IF_ICMPLE, labelEnd);
                }
            }

            node.getBody().accept(this);

            methodVisitor.visitLabel(labelStep);
            // Increment/Decrement
            if (isLong) {
                methodVisitor.visitVarInsn(Opcodes.LLOAD, iteratorIndex);
                node.getStepExpr().accept(this);
                emitConversion(node.getStepExpr().getTypeDescriptor(), type);
                methodVisitor.visitInsn(node.isIncreasing() ? Opcodes.LADD : Opcodes.LSUB);
                methodVisitor.visitVarInsn(Opcodes.LSTORE, iteratorIndex);
            } else if (isFloat) {
                methodVisitor.visitVarInsn(Opcodes.FLOAD, iteratorIndex);
                node.getStepExpr().accept(this);
                emitConversion(node.getStepExpr().getTypeDescriptor(), type);
                methodVisitor.visitInsn(node.isIncreasing() ? Opcodes.FADD : Opcodes.FSUB);
                methodVisitor.visitVarInsn(Opcodes.FSTORE, iteratorIndex);
            } else if (isDouble) {
                methodVisitor.visitVarInsn(Opcodes.DLOAD, iteratorIndex);
                node.getStepExpr().accept(this);
                emitConversion(node.getStepExpr().getTypeDescriptor(), type);
                methodVisitor.visitInsn(node.isIncreasing() ? Opcodes.DADD : Opcodes.DSUB);
                methodVisitor.visitVarInsn(Opcodes.DSTORE, iteratorIndex);
            } else if (isBigDecimal) {
                methodVisitor.visitVarInsn(Opcodes.ALOAD, iteratorIndex);
                node.getStepExpr().accept(this);
                emitConversion(node.getStepExpr().getTypeDescriptor(), type);
                String methodName = node.isIncreasing() ? "add" : "subtract";
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", methodName, "(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;", false);
                methodVisitor.visitVarInsn(Opcodes.ASTORE, iteratorIndex);
            } else {
                boolean iincUsed = false;
                if (node.getStepExpr() instanceof IRLiteral) {
                    Object stepValObj = ((IRLiteral) node.getStepExpr()).getValue();
                    if (stepValObj instanceof Number) {
                        int stepVal = ((Number) stepValObj).intValue();
                        if (stepVal >= -128 && stepVal <= 127) {
                            methodVisitor.visitIincInsn(iteratorIndex, node.isIncreasing() ? stepVal : -stepVal);
                            iincUsed = true;
                        }
                    }
                }
                if (!iincUsed) {
                    methodVisitor.visitVarInsn(Opcodes.ILOAD, iteratorIndex);
                    node.getStepExpr().accept(this);
                    emitConversion(node.getStepExpr().getTypeDescriptor(), type);
                    methodVisitor.visitInsn(node.isIncreasing() ? Opcodes.IADD : Opcodes.ISUB);
                    methodVisitor.visitVarInsn(Opcodes.ISTORE, iteratorIndex);
                }
            }

            methodVisitor.visitJumpInsn(Opcodes.GOTO, labelStart);
        } else {
            node.getIterableExpr().accept(this);
            String iterableType = node.getIterableExpr().getTypeDescriptor();
            if (iterableType == null) iterableType = OceanTypeSystem.OBJECT_DESC;

            if (iterableType.startsWith("[")) {
                // Array iteration
                int arrayIdx = nextLocalIndex++;
                methodVisitor.visitVarInsn(Opcodes.ASTORE, arrayIdx);

                int indexIdx = nextLocalIndex++;
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitVarInsn(Opcodes.ISTORE, indexIdx);

                methodVisitor.visitLabel(labelStart);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, indexIdx);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, arrayIdx);
                methodVisitor.visitInsn(Opcodes.ARRAYLENGTH);
                methodVisitor.visitJumpInsn(Opcodes.IF_ICMPGE, labelEnd);

                methodVisitor.visitVarInsn(Opcodes.ALOAD, arrayIdx);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, indexIdx);

                String elementType = iterableType.substring(1);
                switch (elementType) {
                    case "I" -> methodVisitor.visitInsn(Opcodes.IALOAD);
                    case "J" -> methodVisitor.visitInsn(Opcodes.LALOAD);
                    case "F" -> methodVisitor.visitInsn(Opcodes.FALOAD);
                    case "D" -> methodVisitor.visitInsn(Opcodes.DALOAD);
                    case "B", "Z" -> methodVisitor.visitInsn(Opcodes.BALOAD);
                    case "C" -> methodVisitor.visitInsn(Opcodes.CALOAD);
                    case "S" -> methodVisitor.visitInsn(Opcodes.SALOAD);
                    default -> methodVisitor.visitInsn(Opcodes.AALOAD);
                }

                int iteratorIdx = nextLocalIndex;
                String itDesc = node.getTypeDescriptor();
                nextLocalIndex += (itDesc != null && (itDesc.equals("J") || itDesc.equals("D"))) ? 2 : 1;
                declareVariable(node.getIteratorName(), iteratorIdx, itDesc);

                emitConversion(elementType, itDesc);

                if (itDesc != null && (itDesc.equals("I") || itDesc.equals("Z") || itDesc.equals("B") || itDesc.equals("S") || itDesc.equals("C")))
                    methodVisitor.visitVarInsn(Opcodes.ISTORE, iteratorIdx);
                else if (itDesc != null && itDesc.equals("J")) methodVisitor.visitVarInsn(Opcodes.LSTORE, iteratorIdx);
                else if (itDesc != null && itDesc.equals("F")) methodVisitor.visitVarInsn(Opcodes.FSTORE, iteratorIdx);
                else if (itDesc != null && itDesc.equals("D")) methodVisitor.visitVarInsn(Opcodes.DSTORE, iteratorIdx);
                else methodVisitor.visitVarInsn(Opcodes.ASTORE, iteratorIdx);

                node.getBody().accept(this);

                methodVisitor.visitLabel(labelStep);
                methodVisitor.visitIincInsn(indexIdx, 1);
                methodVisitor.visitJumpInsn(Opcodes.GOTO, labelStart);
            } else if (isPrimitiveListType(iterableType)) {
                // Primitive specialized List iteration: ZERO boxing, ZERO iterator allocation, ZERO Number checkcast
                String owner = getPrimitiveListOwner(iterableType);
                String getterName = getPrimitiveListGetterName(owner);
                String getterDesc = getPrimitiveListGetterDesc(owner);
                String elemType = getPrimitiveListElementType(owner);

                int listIdx = nextLocalIndex++;
                methodVisitor.visitVarInsn(Opcodes.ASTORE, listIdx);

                int indexIdx = nextLocalIndex++;
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitVarInsn(Opcodes.ISTORE, indexIdx);

                methodVisitor.visitLabel(labelStart);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, indexIdx);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, listIdx);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "size", "()I", false);
                methodVisitor.visitJumpInsn(Opcodes.IF_ICMPGE, labelEnd);

                methodVisitor.visitVarInsn(Opcodes.ALOAD, listIdx);
                methodVisitor.visitVarInsn(Opcodes.ILOAD, indexIdx);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, getterName, getterDesc, false);

                int iteratorIdx = nextLocalIndex;
                String itDesc = node.getTypeDescriptor();
                if (itDesc == null) itDesc = elemType;
                nextLocalIndex += (itDesc.equals("J") || itDesc.equals("D")) ? 2 : 1;
                declareVariable(node.getIteratorName(), iteratorIdx, itDesc);

                emitConversion(elemType, itDesc);

                switch (itDesc) {
                    case "I", "Z", "B", "S", "C" -> methodVisitor.visitVarInsn(Opcodes.ISTORE, iteratorIdx);
                    case "J" -> methodVisitor.visitVarInsn(Opcodes.LSTORE, iteratorIdx);
                    case "F" -> methodVisitor.visitVarInsn(Opcodes.FSTORE, iteratorIdx);
                    case "D" -> methodVisitor.visitVarInsn(Opcodes.DSTORE, iteratorIdx);
                    default -> methodVisitor.visitVarInsn(Opcodes.ASTORE, iteratorIdx);
                }

                node.getBody().accept(this);

                methodVisitor.visitLabel(labelStep);
                methodVisitor.visitIincInsn(indexIdx, 1);
                methodVisitor.visitJumpInsn(Opcodes.GOTO, labelStart);
            } else {
                // Iterable iteration
                String cleanIterable = cleanDesc(iterableType);
                if (cleanIterable == null || !cleanIterable.equals("Ljava/lang/Iterable;")) {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Iterable");
                }
                methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;", true);
                int iterIdx = nextLocalIndex++;
                methodVisitor.visitVarInsn(Opcodes.ASTORE, iterIdx);

                methodVisitor.visitLabel(labelStart);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, iterIdx);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
                methodVisitor.visitJumpInsn(Opcodes.IFEQ, labelEnd);

                methodVisitor.visitVarInsn(Opcodes.ALOAD, iterIdx);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);

                String itDesc = node.getTypeDescriptor();
                int iteratorIdx = nextLocalIndex;
                nextLocalIndex += (itDesc != null && (itDesc.equals("J") || itDesc.equals("D"))) ? 2 : 1;
                declareVariable(node.getIteratorName(), iteratorIdx, itDesc);

                emitConversion(OceanTypeSystem.OBJECT_DESC, itDesc);

                if (itDesc != null && (itDesc.equals("I") || itDesc.equals("Z") || itDesc.equals("B") || itDesc.equals("S") || itDesc.equals("C")))
                    methodVisitor.visitVarInsn(Opcodes.ISTORE, iteratorIdx);
                else if (itDesc != null && itDesc.equals("J")) methodVisitor.visitVarInsn(Opcodes.LSTORE, iteratorIdx);
                else if (itDesc != null && itDesc.equals("F")) methodVisitor.visitVarInsn(Opcodes.FSTORE, iteratorIdx);
                else if (itDesc != null && itDesc.equals("D")) methodVisitor.visitVarInsn(Opcodes.DSTORE, iteratorIdx);
                else methodVisitor.visitVarInsn(Opcodes.ASTORE, iteratorIdx);

                node.getBody().accept(this);

                methodVisitor.visitLabel(labelStep);
                methodVisitor.visitJumpInsn(Opcodes.GOTO, labelStart);
            }
        }

        methodVisitor.visitLabel(labelEnd);
        loopTargets.pop();
        loopFinallySizes.pop();
        stopLabels.pop();
        skipLabels.pop();
        exitScope();
    }


    @Override
    public void visitReturn(IRReturnStatement node) {
        if (node.getExpression() != null) {
            node.getExpression().accept(this);
            String desc = node.getExpression().getTypeDescriptor();
            emitConversion(desc, currentMethodReturnType);

            if (!finallyBlocks.isEmpty()) {
                String retDesc = cleanDesc(currentMethodReturnType);
                int tempRetVar = nextLocalIndex;
                nextLocalIndex += ("J".equals(retDesc) || "D".equals(retDesc)) ? 2 : 1;
                switch (retDesc) {
                    case "I", "Z", "B", "S", "C" -> {
                        methodVisitor.visitVarInsn(Opcodes.ISTORE, tempRetVar);
                        runFinallyBeforeReturn();
                        methodVisitor.visitVarInsn(Opcodes.ILOAD, tempRetVar);
                        methodVisitor.visitInsn(Opcodes.IRETURN);
                    }
                    case "J" -> {
                        methodVisitor.visitVarInsn(Opcodes.LSTORE, tempRetVar);
                        runFinallyBeforeReturn();
                        methodVisitor.visitVarInsn(Opcodes.LLOAD, tempRetVar);
                        methodVisitor.visitInsn(Opcodes.LRETURN);
                    }
                    case "F" -> {
                        methodVisitor.visitVarInsn(Opcodes.FSTORE, tempRetVar);
                        runFinallyBeforeReturn();
                        methodVisitor.visitVarInsn(Opcodes.FLOAD, tempRetVar);
                        methodVisitor.visitInsn(Opcodes.FRETURN);
                    }
                    case "D" -> {
                        methodVisitor.visitVarInsn(Opcodes.DSTORE, tempRetVar);
                        runFinallyBeforeReturn();
                        methodVisitor.visitVarInsn(Opcodes.DLOAD, tempRetVar);
                        methodVisitor.visitInsn(Opcodes.DRETURN);
                    }
                    case null, default -> {
                        methodVisitor.visitVarInsn(Opcodes.ASTORE, tempRetVar);
                        runFinallyBeforeReturn();
                        methodVisitor.visitVarInsn(Opcodes.ALOAD, tempRetVar);
                        methodVisitor.visitInsn(Opcodes.ARETURN);
                    }
                }
            } else {
                String retDesc = cleanDesc(currentMethodReturnType);
                switch (retDesc) {
                    case "I", "Z", "B", "S", "C" -> methodVisitor.visitInsn(Opcodes.IRETURN);
                    case "J" -> methodVisitor.visitInsn(Opcodes.LRETURN);
                    case "F" -> methodVisitor.visitInsn(Opcodes.FRETURN);
                    case "D" -> methodVisitor.visitInsn(Opcodes.DRETURN);
                    default -> methodVisitor.visitInsn(Opcodes.ARETURN);
                }
            }
        } else {
            Set<Runnable> executing = new HashSet<>(finallyBlocks);
            splitTryScopesBeforeInlineFinally(executing);
            // Run finally blocks before return
            for (int i = finallyBlocks.size() - 1; i >= 0; i--) {
                finallyBlocks.get(i).run();
            }
            resumeTryScopesAfterInlineFinally(executing);
            methodVisitor.visitInsn(Opcodes.RETURN);
        }
    }

    // Helper to run finally blocks for value-returning returns
    private void runFinallyBeforeReturn() {
        Set<Runnable> executing = new HashSet<>(finallyBlocks);
        splitTryScopesBeforeInlineFinally(executing);
        for (int i = finallyBlocks.size() - 1; i >= 0; i--) {
            finallyBlocks.get(i).run();
        }
        resumeTryScopesAfterInlineFinally(executing);
    }

    /**
     * BF6: Emit the finally block of a try-catch statement if it is non-null.
     * Centralises the null-guard and accept call, eliminating three separate
     * copy-paste sites inside visitTryCatch.
     */
    private void emitFinally(IRTryCatchStatement node) {
        if (node.getFinallyBlock() != null) {
            node.getFinallyBlock().accept(this);
        }
    }

    @Override
    public void visitNewObject(IRNewObject node) {
        boolean oldContext = isStatementContext;
        isStatementContext = false;
        try {
            String internalName = node.getClassName();
            if (internalName != null) {
                internalName = TypeChecker.cleanDescriptor(internalName);
                if (TypeChecker.isClassType(internalName)) {
                    internalName = internalName.substring(1, internalName.length() - 1);
                }
                if (!internalName.contains("/")) {
                    internalName = OceanTypeSystem.resolveInternalClassName(internalName, currentClassName);
                }
            }
            methodVisitor.visitTypeInsn(Opcodes.NEW, internalName);
            methodVisitor.visitInsn(Opcodes.DUP);

            String desc = TypeChecker.cleanDescriptor(node.getDescriptor());
            List<String> paramTypes = parseParameterTypes(desc);
            for (int i = 0; i < node.getArguments().size(); i++) {
                IRExpression arg = node.getArguments().get(i);
                arg.accept(this);
                if (i < paramTypes.size()) {
                    String argActualType = getArgActualType(arg);
                    emitConversion(argActualType, paramTypes.get(i));
                }
            }

            methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", desc, false);
        } finally {
            isStatementContext = oldContext;
        }
    }


    public static int getLoadOpcode(String desc) {
        if (desc == null) return Opcodes.ALOAD;
        String clean = cleanDesc(desc);
        return switch (clean) {
            case "I", "Z", "B", "C", "S" -> Opcodes.ILOAD;
            case "J" -> Opcodes.LLOAD;
            case "F" -> Opcodes.FLOAD;
            case "D" -> Opcodes.DLOAD;
            default -> Opcodes.ALOAD;
        };
    }

    public static int getStoreOpcode(String desc) {
        if (desc == null) return Opcodes.ASTORE;
        String clean = cleanDesc(desc);
        return switch (clean) {
            case "I", "Z", "B", "C", "S" -> Opcodes.ISTORE;
            case "J" -> Opcodes.LSTORE;
            case "F" -> Opcodes.FSTORE;
            case "D" -> Opcodes.DSTORE;
            default -> Opcodes.ASTORE;
        };
    }

    public static void emitPushDefaultValue(MethodVisitor mv, String desc) {
        if (mv == null) return;
        if (desc == null) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            return;
        }
        String clean = cleanDesc(desc);
        switch (clean) {
            case "I", "Z", "B", "C", "S" -> mv.visitInsn(Opcodes.ICONST_0);
            case "J" -> mv.visitInsn(Opcodes.LCONST_0);
            case "F" -> mv.visitInsn(Opcodes.FCONST_0);
            case "D" -> mv.visitInsn(Opcodes.DCONST_0);
            default -> mv.visitInsn(Opcodes.ACONST_NULL);
        }
    }

    private boolean isPrimitiveDesc(String desc) {
        return desc.length() == 1 && "ZBCSIJFDV".contains(desc);
    }

    @Override
    public void visitLambda(IRLambdaExpression node) {
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG] Emitting lambda visitor: " + node.getLambdaMethodName());
        }
        emitLambdaCreation(node);
    }

    private void emitLambdaCreation(IRLambdaExpression node) {
        // 1. Load captured variables on the stack
        for (int i = 0; i < node.getCapturedNames().size(); i++) {
            String name = node.getCapturedNames().get(i);
            String type = node.getCapturedTypes().get(i);
            int index = getVariableIndex(name);
            if (index != -1) {
                switch (type) {
                    case "I", "Z", "B", "S", "C" -> methodVisitor.visitVarInsn(Opcodes.ILOAD, index);
                    case "J" -> methodVisitor.visitVarInsn(Opcodes.LLOAD, index);
                    case "F" -> methodVisitor.visitVarInsn(Opcodes.FLOAD, index);
                    case "D" -> methodVisitor.visitVarInsn(Opcodes.DLOAD, index);
                    default -> methodVisitor.visitVarInsn(Opcodes.ALOAD, index);
                }
            } else if (name.equals("this")) {
                reportError(currentClassNode, "Cannot capture 'this' in a static context for lambda.");
                methodVisitor.visitInsn(Opcodes.ACONST_NULL);
            } else {
                reportError(currentClassNode, "Captured variable '" + name + "' not found in scope for lambda.");
                emitPushDefaultValue(methodVisitor, type);
            }
        }

        // 2. Build invokedType descriptor: (capturedTypes...)targetInterface
        StringBuilder invokedType = new StringBuilder("(");
        for (String type : node.getCapturedTypes()) {
            invokedType.append(TypeChecker.cleanDescriptor(type));
        }
        invokedType.append(")").append(TypeChecker.cleanDescriptor(node.getTargetInterface()));

        // 3. Build implementation method handle descriptor (implDesc)
        StringBuilder implDesc = new StringBuilder("(");
        for (String pt : node.getParameterTypes()) {
            implDesc.append(TypeChecker.cleanDescriptor(pt));
        }
        String samReturn = node.getSamMethodDesc().substring(node.getSamMethodDesc().lastIndexOf(')') + 1);
        Map<String, String> currentClassMethods = CompilerRegistry.globalMethodRegistry.get(currentClassName);
        String registeredDesc = currentClassMethods != null ? currentClassMethods.get(node.getLambdaMethodName()) : null;
        String implReturn = registeredDesc != null ? registeredDesc.substring(registeredDesc.lastIndexOf(')') + 1) : samReturn;
        implDesc.append(")").append(TypeChecker.cleanDescriptor(implReturn));

        // Build instantiated method descriptor (excluding captured variables, boxing primitives only if SAM expects reference)
        StringBuilder instantiatedDesc = new StringBuilder("(");
        int capturedInParams = 0;
        for (String cn : node.getCapturedNames()) {
            if (node.isStatic() || !cn.equals("this")) {
                capturedInParams++;
            }
        }
        List<String> samParamTypes = parseParameterTypes(TypeChecker.cleanDescriptor(node.getSamMethodDesc()));
        for (int i = capturedInParams; i < node.getParameterTypes().size(); i++) {
            String pt = TypeChecker.cleanDescriptor(node.getParameterTypes().get(i));
            int regularIndex = i - capturedInParams;
            if (regularIndex < samParamTypes.size()) {
                String samPt = TypeChecker.cleanDescriptor(samParamTypes.get(regularIndex));
                if (isPrimitiveDesc(samPt)) {
                    instantiatedDesc.append(samPt);
                } else if (isPrimitiveDesc(pt)) {
                    instantiatedDesc.append(OceanTypeSystem.box(pt));
                } else {
                    instantiatedDesc.append(pt);
                }
            } else {
                instantiatedDesc.append(pt);
            }
        }
        if (isPrimitiveDesc(samReturn)) {
            instantiatedDesc.append(")").append(TypeChecker.cleanDescriptor(samReturn));
        } else {
            instantiatedDesc.append(")").append(TypeChecker.cleanDescriptor(OceanTypeSystem.getBoxedDescriptor(samReturn)));
        }

        // 4. Emit invokedynamic
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false);

        boolean isOwnerInterface = isInterface(currentClassName);
        Handle implMethod = new Handle(node.isStatic() ? Opcodes.H_INVOKESTATIC : (isOwnerInterface ? Opcodes.H_INVOKEINTERFACE : Opcodes.H_INVOKESPECIAL),
                currentClassName, node.getLambdaMethodName(), implDesc.toString(), isOwnerInterface);

        methodVisitor.visitInvokeDynamicInsn(node.getSamMethodName(), invokedType.toString(), bsm,
                Type.getType(TypeChecker.cleanDescriptor(node.getSamMethodDesc())), implMethod, Type.getType(instantiatedDesc.toString()));
    }

    @Override
    public void visitArrayCreation(IRArrayCreation node) {
        for (IRExpression size : node.getSizes()) {
            size.accept(this);
            emitConversion(size.getTypeDescriptor(), "I");
        }

        int dims = node.getSizes().size();
        String baseType = node.getBaseType();

        if (dims > 1) {
            String typeDesc = TypeChecker.cleanDescriptor(node.getTypeDescriptor());
            methodVisitor.visitMultiANewArrayInsn(typeDesc, dims);
        } else {
            // 1D array creation
            switch (baseType) {
                case "I" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
                case "Z" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN);
                case "J" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG);
                case "F" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
                case "D" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE);
                case "B" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
                case "C" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_CHAR);
                case "S" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_SHORT);
                default -> {
                    String internalName = resolveArrayElementInternalName(baseType);
                    methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, internalName);
                }
            }
        }
    }

    public void emitIntConst(int val) {
        if (val >= -1 && val <= 5) {
            switch (val) {
                case -1:
                    methodVisitor.visitInsn(Opcodes.ICONST_M1);
                    break;
                case 0:
                    methodVisitor.visitInsn(Opcodes.ICONST_0);
                    break;
                case 1:
                    methodVisitor.visitInsn(Opcodes.ICONST_1);
                    break;
                case 2:
                    methodVisitor.visitInsn(Opcodes.ICONST_2);
                    break;
                case 3:
                    methodVisitor.visitInsn(Opcodes.ICONST_3);
                    break;
                case 4:
                    methodVisitor.visitInsn(Opcodes.ICONST_4);
                    break;
                case 5:
                    methodVisitor.visitInsn(Opcodes.ICONST_5);
                    break;
            }
        } else if (val >= Byte.MIN_VALUE && val <= Byte.MAX_VALUE) {
            methodVisitor.visitIntInsn(Opcodes.BIPUSH, val);
        } else if (val >= Short.MIN_VALUE && val <= Short.MAX_VALUE) {
            methodVisitor.visitIntInsn(Opcodes.SIPUSH, val);
        } else {
            methodVisitor.visitLdcInsn(val);
        }
    }

    public void emitSwitchInsn(Label defaultLabel, int[] keys, Label[] labels) {
        if (keys == null || keys.length == 0) {
            methodVisitor.visitInsn(Opcodes.POP);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, defaultLabel);
            return;
        }

        class SwitchPair {
            final int key;
            final Label label;

            SwitchPair(int key, Label label) {
                this.key = key;
                this.label = label;
            }
        }
        List<SwitchPair> pairs = new ArrayList<>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            pairs.add(new SwitchPair(keys[i], labels[i]));
        }
        pairs.sort(Comparator.comparingInt(p -> p.key));

        // De-duplicate keys to ensure strictly increasing order for LOOKUPSWITCH and valid TABLESWITCH
        List<Integer> cleanKeys = new ArrayList<>();
        List<Label> cleanLabels = new ArrayList<>();
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0 && pairs.get(i).key == pairs.get(i - 1).key) {
                continue; // duplicate key: ignore to preserve strictly increasing order
            }
            cleanKeys.add(pairs.get(i).key);
            cleanLabels.add(pairs.get(i).label);
        }
        if (cleanKeys.isEmpty()) {
            methodVisitor.visitInsn(Opcodes.POP);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, defaultLabel);
            return;
        }
        int[] finalKeys = new int[cleanKeys.size()];
        Label[] finalLabels = new Label[cleanLabels.size()];
        for (int i = 0; i < cleanKeys.size(); i++) {
            finalKeys[i] = cleanKeys.get(i);
            finalLabels[i] = cleanLabels.get(i);
        }

        long min = finalKeys[0];
        long max = finalKeys[finalKeys.length - 1];
        long range = max - min + 1;

        if (range > 0 && range <= 1024 && range <= (long) finalKeys.length * 2 + 8) {
            int minInt = (int) min;
            int maxInt = (int) max;
            int tableLen = (int) range;
            Label[] tableLabels = new Label[tableLen];
            Arrays.fill(tableLabels, defaultLabel);
            for (int i = 0; i < finalKeys.length; i++) {
                tableLabels[finalKeys[i] - minInt] = finalLabels[i];
            }
            methodVisitor.visitTableSwitchInsn(minInt, maxInt, defaultLabel, tableLabels);
        } else {
            methodVisitor.visitLookupSwitchInsn(defaultLabel, finalKeys, finalLabels);
        }
    }

    @Override
    public void visitLiteral(IRLiteral node) {
        Object value = node.getValue();
        switch (value) {
            case null -> methodVisitor.visitInsn(Opcodes.ACONST_NULL);
            case Integer i -> emitIntConst(i);
            case Character c -> emitIntConst((int) c);
            case Byte b -> emitIntConst((int) b);
            case Short i -> emitIntConst((int) i);
            case Long l -> methodVisitor.visitLdcInsn(l);
            case Float v -> methodVisitor.visitLdcInsn(v);
            case Double v -> methodVisitor.visitLdcInsn(v);
            case String s -> methodVisitor.visitLdcInsn(s);
            case Boolean b -> methodVisitor.visitInsn(b ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            case BigDecimal decimal -> {
                methodVisitor.visitTypeInsn(Opcodes.NEW, "java/math/BigDecimal");
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitLdcInsn(decimal.toString());
                methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/math/BigDecimal", "<init>", "(Ljava/lang/String;)V", false);
            }
            default -> {
            }
        }
    }

    @Override
    public void visitBinaryOp(IRBinaryOp node) {
        String targetType = node.getTypeDescriptor();
        IRBinaryOp.Op op = node.getOperator();

        // Karşılaştırma operatörleri için hedef tip 'Z' (boolean) olur.
        // Ancak operandlar karşılaştırılmadan önce ortak bir tipe dönüştürülmelidir (örn: long vs int -> long).
        String operandType = targetType;
        boolean isCompareOpCode = op == IRBinaryOp.Op.EQ || op == IRBinaryOp.Op.NE ||
                op == IRBinaryOp.Op.LT || op == IRBinaryOp.Op.LE ||
                op == IRBinaryOp.Op.GT || op == IRBinaryOp.Op.GE;
        if (isCompareOpCode) {
            operandType = getCommonType(node.getLeft().getTypeDescriptor(), node.getRight().getTypeDescriptor());
        }

        boolean isBigDecimal = TypeChecker.isBigDecimalType(operandType);
        String arithmeticType = (targetType == null || TypeChecker.isObjectType(targetType)) ? "I" : targetType;

        // 1. Special case: String concat
        boolean isStringConcat = op == IRBinaryOp.Op.ADD && (
                TypeChecker.isStringType(targetType) ||
                        TypeChecker.isStringType(node.getLeft().getTypeDescriptor()) ||
                        TypeChecker.isStringType(node.getRight().getTypeDescriptor())
        );
        if (isStringConcat) {
            if (Boolean.getBoolean("ocean.debug")) {
                System.out.println("[DEBUG] String concat left: " + node.getLeft().getTypeDescriptor() + " (" + node.getLeft().getClass().getSimpleName() +
                        ") right: " + node.getRight().getTypeDescriptor() + " (" + node.getRight().getClass().getSimpleName() + ")");
            }
            List<IRNode> chain = new ArrayList<>();
            collectStringChain(node, chain);
            emitStringConcatIndy(chain);
            return;
        }

        // 2. Visit operands with correct conversion
        if (op == IRBinaryOp.Op.AND || op == IRBinaryOp.Op.OR) {
            Label labelAlt = new Label();
            Label labelEnd = new Label();

            node.getLeft().accept(this);
            emitConversion(node.getLeft().getTypeDescriptor(), "Z");

            if (op == IRBinaryOp.Op.AND) {
                methodVisitor.visitJumpInsn(Opcodes.IFEQ, labelAlt);
            } else {
                methodVisitor.visitJumpInsn(Opcodes.IFNE, labelAlt);
            }

            node.getRight().accept(this);
            emitConversion(node.getRight().getTypeDescriptor(), "Z");
            methodVisitor.visitJumpInsn(Opcodes.GOTO, labelEnd);

            methodVisitor.visitLabel(labelAlt);
            methodVisitor.visitInsn(op == IRBinaryOp.Op.AND ? Opcodes.ICONST_0 : Opcodes.ICONST_1);

            methodVisitor.visitLabel(labelEnd);
            return;
        }

        String shiftType = "I";
        if (op == IRBinaryOp.Op.LSHIFT || op == IRBinaryOp.Op.RSHIFT || op == IRBinaryOp.Op.URSHIFT) {
            String leftRaw = node.getLeft().getTypeDescriptor();
            String cleanLeft = TypeChecker.cleanDescriptor(leftRaw);
            shiftType = "J".equals(cleanLeft) ? "J" : "I";
            node.getLeft().accept(this);
            emitConversion(leftRaw, shiftType);
            node.getRight().accept(this);
            emitConversion(node.getRight().getTypeDescriptor(), "I");
        } else if (isCompareOpCode) {
            node.getLeft().accept(this);
            emitConversion(node.getLeft().getTypeDescriptor(), operandType);
            node.getRight().accept(this);
            emitConversion(node.getRight().getTypeDescriptor(), operandType);
        } else {
            node.getLeft().accept(this);
            emitConversion(node.getLeft().getTypeDescriptor(), arithmeticType);
            node.getRight().accept(this);
            emitConversion(node.getRight().getTypeDescriptor(), arithmeticType);
        }

        // 3. Switch on operator
        boolean isGenericNumber = arithmeticType.startsWith("L") && !TypeChecker.isStringType(arithmeticType) && !TypeChecker.isBigDecimalType(arithmeticType);
        switch (op) {
            case ADD:
                if (isBigDecimal) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "add", "(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;", false);
                } else if (isGenericNumber) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "add", "(Ljava/lang/Number;Ljava/lang/Number;)Ljava/lang/Number;", false);
                } else {
                    emitArithmetic(Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD, arithmeticType);
                }
                break;
            case SUB:
                if (isBigDecimal) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "subtract", "(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;", false);
                } else if (isGenericNumber) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "subtract", "(Ljava/lang/Number;Ljava/lang/Number;)Ljava/lang/Number;", false);
                } else {
                    emitArithmetic(Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB, arithmeticType);
                }
                break;
            case MUL:
                if (isBigDecimal) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "multiply", "(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;", false);
                } else if (isGenericNumber) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "multiply", "(Ljava/lang/Number;Ljava/lang/Number;)Ljava/lang/Number;", false);
                } else {
                    emitArithmetic(Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL, arithmeticType);
                }
                break;
            case DIV:
                if (isBigDecimal) {
                    methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/math/MathContext", "DECIMAL128", "Ljava/math/MathContext;");
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "divide", "(Ljava/math/BigDecimal;Ljava/math/MathContext;)Ljava/math/BigDecimal;", false);
                } else if (isGenericNumber) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "divide", "(Ljava/lang/Number;Ljava/lang/Number;)Ljava/lang/Number;", false);
                } else {
                    emitArithmetic(Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV, arithmeticType);
                }
                break;
            case MOD:
                if (isBigDecimal) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "remainder", "(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;", false);
                } else if (isGenericNumber) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "mod", "(Ljava/lang/Number;Ljava/lang/Number;)Ljava/lang/Number;", false);
                } else {
                    emitArithmetic(Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM, arithmeticType);
                }
                break;
            case EQ:
                emitComparison(Opcodes.IF_ICMPEQ, Opcodes.IFEQ, operandType);
                break;
            case NE:
                emitComparison(Opcodes.IF_ICMPNE, Opcodes.IFNE, operandType);
                break;
            case LT:
                emitComparison(Opcodes.IF_ICMPLT, Opcodes.IFLT, operandType);
                break;
            case LE:
                emitComparison(Opcodes.IF_ICMPLE, Opcodes.IFLE, operandType);
                break;
            case GT:
                emitComparison(Opcodes.IF_ICMPGT, Opcodes.IFGT, operandType);
                break;
            case GE:
                emitComparison(Opcodes.IF_ICMPGE, Opcodes.IFGE, operandType);
                break;
            case LSHIFT:
                emitArithmetic(Opcodes.ISHL, Opcodes.LSHL, 0, 0, shiftType);
                break;
            case RSHIFT:
                emitArithmetic(Opcodes.ISHR, Opcodes.LSHR, 0, 0, shiftType);
                break;
            case URSHIFT:
                emitArithmetic(Opcodes.IUSHR, Opcodes.LUSHR, 0, 0, shiftType);
                break;
            case BIT_AND:
                emitArithmetic(Opcodes.IAND, Opcodes.LAND, 0, 0, arithmeticType);
                break;
            case BIT_OR:
                emitArithmetic(Opcodes.IOR, Opcodes.LOR, 0, 0, arithmeticType);
                break;
            case BIT_XOR:
                emitArithmetic(Opcodes.IXOR, Opcodes.LXOR, 0, 0, arithmeticType);
                break;
            default:
                break;
        }
    }

    private void emitConversion(String from, String to) {
        if (from == null || to == null) return;
        from = TypeChecker.cleanDescriptor(from);
        to = TypeChecker.cleanDescriptor(to);

        if (from.equals(to)) {
            switch (to) {
                case "B" -> methodVisitor.visitInsn(Opcodes.I2B);
                case "S" -> methodVisitor.visitInsn(Opcodes.I2S);
                case "C" -> methodVisitor.visitInsn(Opcodes.I2C);
            }
            return;
        }

        if (to.equals("Ljava/lang/Integer;") && from.equals("I")) {
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
            return;
        }
        if (to.equals("Ljava/lang/Boolean;") && from.equals("Z")) {
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
            return;
        }
        if (to.equals("Ljava/lang/Long;") && from.equals("J")) {
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            return;
        }
        if (to.equals("Ljava/lang/Float;") && from.equals("F")) {
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
            return;
        }
        if (to.equals("Ljava/lang/Double;") && from.equals("D")) {
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
            return;
        }
        if (to.equals("Ljava/lang/Byte;") && from.equals("B")) {
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
            return;
        }
        if (to.equals("Ljava/lang/Short;") && from.equals("S")) {
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
            return;
        }
        if (to.equals("Ljava/lang/Character;") && from.equals("C")) {
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
            return;
        }

        if (TypeChecker.isBigDecimalType(to)) {
            switch (from) {
                case "I" -> {
                    methodVisitor.visitInsn(Opcodes.I2L);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/math/BigDecimal", "valueOf", "(J)Ljava/math/BigDecimal;", false);
                }
                case "J" ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/math/BigDecimal", "valueOf", "(J)Ljava/math/BigDecimal;", false);
                case "D" ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/math/BigDecimal", "valueOf", "(D)Ljava/math/BigDecimal;", false);
                case "F" -> {
                    methodVisitor.visitInsn(Opcodes.F2D);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/math/BigDecimal", "valueOf", "(D)Ljava/math/BigDecimal;", false);
                }
                case OceanTypeSystem.OBJECT_DESC ->
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/math/BigDecimal");
            }
            return;
        }

        if (to.equals(OceanTypeSystem.NUMBER_DESC) && TypeChecker.isObjectType(from)) {
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
            return;
        }

        if ((to.equals(OceanTypeSystem.STRING_DESC) || to.equals("java/lang/String")) && TypeChecker.isPrimitive(from)) {
            switch (from) {
                case "Z" ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(Z)Ljava/lang/String;", false);
                case "C" ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(C)Ljava/lang/String;", false);
                case "B", "S", "I" ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(I)Ljava/lang/String;", false);
                case "J" ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(J)Ljava/lang/String;", false);
                case "F" ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(F)Ljava/lang/String;", false);
                case "D" ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(D)Ljava/lang/String;", false);
            }
            return;
        }

        if ((to.startsWith("L") || to.startsWith("[")) && TypeChecker.isPrimitive(from)) {
            emitBox(methodVisitor, from);
            return;
        }

        if (TypeChecker.isBigDecimalType(from)) {
            switch (to) {
                case "I", "B", "S", "C" ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "intValue", "()I", false);
                case "J" ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "longValue", "()J", false);
                case "F" ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "floatValue", "()F", false);
                case "D" ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "doubleValue", "()D", false);
            }
            return;
        }

        if (from.equals("Ljava/lang/Integer;") && to.equals("I")) {
            emitWrapperUnbox("java/lang/Integer", "I", "intValue", "()I", Opcodes.ICONST_0);
            return;
        }
        if (from.equals("Ljava/lang/Boolean;") && to.equals("Z")) {
            emitWrapperUnbox("java/lang/Boolean", "Z", "booleanValue", "()Z", Opcodes.ICONST_0);
            return;
        }
        if (from.equals("Ljava/lang/Long;") && to.equals("J")) {
            emitWrapperUnbox("java/lang/Long", "J", "longValue", "()J", Opcodes.LCONST_0);
            return;
        }
        if (from.equals("Ljava/lang/Float;") && to.equals("F")) {
            emitWrapperUnbox("java/lang/Float", "F", "floatValue", "()F", Opcodes.FCONST_0);
            return;
        }
        if (from.equals("Ljava/lang/Double;") && to.equals("D")) {
            emitWrapperUnbox("java/lang/Double", "D", "doubleValue", "()D", Opcodes.DCONST_0);
            return;
        }
        if (from.equals("Ljava/lang/Character;") && (to.equals("C") || to.equals("I"))) {
            emitWrapperUnbox("java/lang/Character", "C", "charValue", "()C", Opcodes.ICONST_0);
            return;
        }
        if (from.equals("Ljava/lang/Byte;") && (to.equals("B") || to.equals("I"))) {
            emitWrapperUnbox("java/lang/Byte", "B", "byteValue", "()B", Opcodes.ICONST_0);
            return;
        }
        if (from.equals("Ljava/lang/Short;") && (to.equals("S") || to.equals("I"))) {
            emitWrapperUnbox("java/lang/Short", "S", "shortValue", "()S", Opcodes.ICONST_0);
            return;
        }
        boolean isToPrimitive = to.equals("I") || to.equals("B") || to.equals("S") || to.equals("C") ||
                to.equals("J") || to.equals("F") || to.equals("D") || to.equals("Z");
        boolean isFromRef = from.equals("null") || TypeChecker.isObjectType(from) || TypeChecker.isClassType(from) || from.startsWith("L");

        if (isToPrimitive && isFromRef) {
            switch (to) {
                case "I", "B", "S" -> {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
                    if (to.equals("B")) methodVisitor.visitInsn(Opcodes.I2B);
                    else if (to.equals("S")) methodVisitor.visitInsn(Opcodes.I2S);
                }
                case "C" -> {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Character");
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                }
                case "J" -> {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
                }
                case "F" -> {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
                }
                case "D" -> {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
                }
                case "Z" -> {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
                }
            }
            return;
        }

        if ((from.equals("null") || TypeChecker.isObjectType(from) || from.startsWith("Ljava/lang/")) && (TypeChecker.isClassType(to) || to.startsWith("["))) {
            if (TypeChecker.isClassType(to)) {
                String cleanTo = TypeChecker.cleanDescriptor(to);
                if (cleanTo.startsWith("L") && cleanTo.endsWith(";")) {
                    cleanTo = cleanTo.substring(1, cleanTo.length() - 1);
                }
                if (cleanTo.contains("<")) {
                    cleanTo = cleanTo.substring(0, cleanTo.indexOf('<'));
                }
                /*String builtin = OceanTypeSystem.getBuiltinInternalName(cleanTo);
                if (builtin != null) {
                    cleanTo = builtin;
                } else*/
                if (!cleanTo.contains("/")) {
                    String resolved = OceanTypeSystem.resolveInternalClassName(cleanTo, currentClassName);
                    if (resolved != null) cleanTo = resolved;
                }
                if (!cleanTo.equals("java/lang/Object") && !from.equals("null")) {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, cleanTo);
                }
            } else if (to.startsWith("[")) {
                if (!from.equals("null")) {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, TypeChecker.cleanDescriptor(to));
                }
            }
            return;
        }

        if (to.startsWith("[")) {
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, TypeChecker.cleanDescriptor(to));
            return;
        }

        if (TypeChecker.isClassType(from) && TypeChecker.isClassType(to)) {
            String cleanFrom = TypeChecker.cleanDescriptor(from);
            String cleanTo = TypeChecker.cleanDescriptor(to);
            if (cleanFrom.startsWith("L") && cleanFrom.endsWith(";"))
                cleanFrom = cleanFrom.substring(1, cleanFrom.length() - 1);
            if (cleanTo.startsWith("L") && cleanTo.endsWith(";")) cleanTo = cleanTo.substring(1, cleanTo.length() - 1);
            if (cleanFrom.contains("<")) cleanFrom = cleanFrom.substring(0, cleanFrom.indexOf('<'));
            if (cleanTo.contains("<")) cleanTo = cleanTo.substring(0, cleanTo.indexOf('<'));
            /*String builtin = OceanTypeSystem.getBuiltinInternalName(cleanTo);
            if (builtin != null) {
                cleanTo = builtin;
            } else*/
            if (!cleanTo.contains("/")) {
                String resolved = OceanTypeSystem.resolveInternalClassName(cleanTo, currentClassName);
                if (resolved != null) cleanTo = resolved;
            }
            if (!cleanFrom.contains("/")) {
                String resolved = OceanTypeSystem.resolveInternalClassName(cleanFrom, currentClassName);
                if (resolved != null) cleanFrom = resolved;
            }
            if (!cleanFrom.equals(cleanTo) && !cleanTo.equals("java/lang/Object")) {
                if (!ClassMetadataCache.isSubtype(cleanFrom, cleanTo) || ClassMetadataCache.isSubtype(cleanTo, cleanFrom)) {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, cleanTo);
                    return;
                }
            }
        }

        switch (from) {
            case "I" -> {
                switch (to) {
                    case "J" -> methodVisitor.visitInsn(Opcodes.I2L);
                    case "F" -> methodVisitor.visitInsn(Opcodes.I2F);
                    case "D" -> methodVisitor.visitInsn(Opcodes.I2D);
                    case "S" -> methodVisitor.visitInsn(Opcodes.I2S);
                    case "B" -> methodVisitor.visitInsn(Opcodes.I2B);
                    case "C" -> methodVisitor.visitInsn(Opcodes.I2C);
                }
            }

            // JVM stores short, byte, char as int on stack/locals.
            // They need the same widening conversions as "I" when casting to 2-slot types (J, D) or other 1-slot types.
            case "S", "B", "C" -> {
                switch (to) {
                    case "J" -> methodVisitor.visitInsn(Opcodes.I2L);
                    case "F" -> methodVisitor.visitInsn(Opcodes.I2F);
                    case "D" -> methodVisitor.visitInsn(Opcodes.I2D);
                    case "B" -> methodVisitor.visitInsn(Opcodes.I2B);
                    case "S" -> methodVisitor.visitInsn(Opcodes.I2S);
                    case "C" -> methodVisitor.visitInsn(Opcodes.I2C);
                }
            }

            case "J" -> {
                switch (to) {
                    case "I", "Z" -> methodVisitor.visitInsn(Opcodes.L2I);

                    case "B" -> {
                        methodVisitor.visitInsn(Opcodes.L2I);
                        methodVisitor.visitInsn(Opcodes.I2B);
                    }

                    case "S" -> {
                        methodVisitor.visitInsn(Opcodes.L2I);
                        methodVisitor.visitInsn(Opcodes.I2S);
                    }

                    case "C" -> {
                        methodVisitor.visitInsn(Opcodes.L2I);
                        methodVisitor.visitInsn(Opcodes.I2C);
                    }

                    case "F" -> methodVisitor.visitInsn(Opcodes.L2F);
                    case "D" -> methodVisitor.visitInsn(Opcodes.L2D);

                }
            }

            case "F" -> {
                switch (to) {
                    case "I", "Z" -> methodVisitor.visitInsn(Opcodes.F2I);

                    case "B" -> {
                        methodVisitor.visitInsn(Opcodes.F2I);
                        methodVisitor.visitInsn(Opcodes.I2B);
                    }

                    case "S" -> {
                        methodVisitor.visitInsn(Opcodes.F2I);
                        methodVisitor.visitInsn(Opcodes.I2S);
                    }

                    case "C" -> {
                        methodVisitor.visitInsn(Opcodes.F2I);
                        methodVisitor.visitInsn(Opcodes.I2C);
                    }

                    case "J" -> methodVisitor.visitInsn(Opcodes.F2L);
                    case "D" -> methodVisitor.visitInsn(Opcodes.F2D);
                }
            }

            case "D" -> {
                switch (to) {
                    case "I", "Z" -> methodVisitor.visitInsn(Opcodes.D2I);

                    case "B" -> {
                        methodVisitor.visitInsn(Opcodes.D2I);
                        methodVisitor.visitInsn(Opcodes.I2B);
                    }

                    case "S" -> {
                        methodVisitor.visitInsn(Opcodes.D2I);
                        methodVisitor.visitInsn(Opcodes.I2S);
                    }

                    case "C" -> {
                        methodVisitor.visitInsn(Opcodes.D2I);
                        methodVisitor.visitInsn(Opcodes.I2C);
                    }

                    case "J" -> methodVisitor.visitInsn(Opcodes.D2L);
                    case "F" -> methodVisitor.visitInsn(Opcodes.D2F);
                }
            }
        }
    }

    // this method convert null reference unbox class to its primitive type 0 value so it's caused errors

    /*private void emitWrapperUnbox(String wrapperClass, String primitiveType, String unboxMethod, String methodDesc, int defaultOpcode) {
        Label labelNonNull = new Label();
        Label labelEnd = new Label();
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitJumpInsn(Opcodes.IFNONNULL, labelNonNull);
        methodVisitor.visitInsn(Opcodes.POP);
        if (defaultOpcode == Opcodes.LCONST_0) {
            methodVisitor.visitInsn(Opcodes.LCONST_0);
        } else if (defaultOpcode == Opcodes.FCONST_0) {
            methodVisitor.visitInsn(Opcodes.FCONST_0);
        } else if (defaultOpcode == Opcodes.DCONST_0) {
            methodVisitor.visitInsn(Opcodes.DCONST_0);
        } else {
            methodVisitor.visitInsn(Opcodes.ICONST_0);
        }
        methodVisitor.visitJumpInsn(Opcodes.GOTO, labelEnd);

        methodVisitor.visitLabel(labelNonNull);
        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, wrapperClass);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapperClass, unboxMethod, methodDesc, false);

        methodVisitor.visitLabel(labelEnd);
    }*/

    /**
     * more optimized method from top method
     **/

    private void emitWrapperUnbox(String wrapperClass, String primitiveType, String unboxMethod, String methodDesc, int defaultOpcode) {
        emitWrapperUnbox(wrapperClass, unboxMethod, methodDesc);
    }

    private void emitWrapperUnbox(String wrapperClass, String unboxMethod, String methodDesc) {
        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, wrapperClass);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapperClass, unboxMethod, methodDesc, false);
    }

    @Override
    public void visitUnaryOp(IRUnaryOp node) {
        IRUnaryOp.Op op = node.getOperator();
        if (op != IRUnaryOp.Op.PRE_INC && op != IRUnaryOp.Op.POST_INC &&
                op != IRUnaryOp.Op.PRE_DEC && op != IRUnaryOp.Op.POST_DEC) {
            node.getExpression().accept(this);
        }

        String type = node.getTypeDescriptor();
        switch (op) {
            case NOT:
                emitConversion(node.getExpression().getTypeDescriptor(), "Z");
                Label labelTrue = new Label();
                Label labelEnd = new Label();
                methodVisitor.visitJumpInsn(Opcodes.IFNE, labelTrue);
                methodVisitor.visitInsn(Opcodes.ICONST_1);
                methodVisitor.visitJumpInsn(Opcodes.GOTO, labelEnd);
                methodVisitor.visitLabel(labelTrue);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitLabel(labelEnd);
                break;
            case NEG:
                emitConversion(node.getExpression().getTypeDescriptor(), type);
                switch (type) {
                    case "I", "B", "S", "C" -> methodVisitor.visitInsn(Opcodes.INEG);
                    case "J" -> methodVisitor.visitInsn(Opcodes.LNEG);
                    case "F" -> methodVisitor.visitInsn(Opcodes.FNEG);
                    case "D" -> methodVisitor.visitInsn(Opcodes.DNEG);

                    case OceanTypeSystem.BIGDECIMAL_DESC ->
                            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "negate",
                                    "()Ljava/math/BigDecimal;", false);
                }
                break;
            case BIT_NOT:
                emitConversion(node.getExpression().getTypeDescriptor(), type);
                if (type.equals("I") || type.equals("B") || type.equals("C") || type.equals("S")) {
                    methodVisitor.visitInsn(Opcodes.ICONST_M1);
                    methodVisitor.visitInsn(Opcodes.IXOR);
                } else if (type.equals("J")) {
                    methodVisitor.visitLdcInsn(-1L);
                    methodVisitor.visitInsn(Opcodes.LXOR);
                }
                break;
            case PRE_INC:
            case POST_INC:
            case PRE_DEC:
            case POST_DEC:
                handleIncrement(node);
                break;
        }
    }

    private void handleIncrement(IRUnaryOp node) {
        boolean isInc = node.getOperator() == IRUnaryOp.Op.PRE_INC || node.getOperator() == IRUnaryOp.Op.POST_INC;
        boolean isPost = node.getOperator() == IRUnaryOp.Op.POST_INC || node.getOperator() == IRUnaryOp.Op.POST_DEC;
        IRExpression expr = node.getExpression();

        if (expr instanceof IRVariableAccess var) {
            String desc = var.getTypeDescriptor();
            boolean isIntLike = desc.equals("I") || desc.equals("B") || desc.equals("S") || desc.equals("C");

            if (var.isField()) {
                // Field increment
                boolean isStatic = var.isStatic();
                if (!isStatic) {
                    if (var.getReceiver() != null) {
                        var.getReceiver().accept(this);
                        methodVisitor.visitInsn(Opcodes.DUP);
                    } else {
                        methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                        methodVisitor.visitInsn(Opcodes.DUP);
                    }
                } else {
                    if (var.getReceiver() != null) {
                        var.getReceiver().accept(this);
                        methodVisitor.visitInsn(Opcodes.POP);
                    }
                }

                int loadOp = isStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD;
                methodVisitor.visitFieldInsn(loadOp, var.getOwner().replace(".", "/"), var.getName(), desc);

                if (isPost) {
                    if (desc.equals("J") || desc.equals("D")) {
                        methodVisitor.visitInsn(!isStatic ? Opcodes.DUP2_X1 : Opcodes.DUP2);
                    } else {
                        methodVisitor.visitInsn(!isStatic ? Opcodes.DUP_X1 : Opcodes.DUP);
                    }
                }

                // Add/Sub 1
                if (isIntLike) {
                    methodVisitor.visitInsn(Opcodes.ICONST_1);
                    methodVisitor.visitInsn(isInc ? Opcodes.IADD : Opcodes.ISUB);
                } else if (desc.startsWith("L")) {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                    emitIntConst(1);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                    String method = isInc ? "add" : "subtract";
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", method, "(Ljava/lang/Number;Ljava/lang/Number;)Ljava/lang/Number;", false);
                    emitCheckcast(desc);
                } else if (desc.equals("J")) {
                    methodVisitor.visitInsn(Opcodes.LCONST_1);
                    methodVisitor.visitInsn(isInc ? Opcodes.LADD : Opcodes.LSUB);
                } else if (desc.equals("F")) {
                    methodVisitor.visitInsn(Opcodes.FCONST_1);
                    methodVisitor.visitInsn(isInc ? Opcodes.FADD : Opcodes.FSUB);
                } else if (desc.equals("D")) {
                    methodVisitor.visitInsn(Opcodes.DCONST_1);
                    methodVisitor.visitInsn(isInc ? Opcodes.DADD : Opcodes.DSUB);
                }

                if (!isPost) {
                    if (desc.equals("J") || desc.equals("D")) {
                        methodVisitor.visitInsn(!isStatic ? Opcodes.DUP2_X1 : Opcodes.DUP2);
                    } else {
                        methodVisitor.visitInsn(!isStatic ? Opcodes.DUP_X1 : Opcodes.DUP);
                    }
                }

                int storeOp = isStatic ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD;
                methodVisitor.visitFieldInsn(storeOp, var.getOwner().replace(".", "/"), var.getName(), desc);
            } else {
                // Local variable increment
                int index = getVariableIndex(var.getName());
                if (index != -1) {
                    if (desc.equals("I")) {
                        boolean discard = (node == discardRoot);
                        if (discard) wasValueDiscardedByExpr = true;
                        if (isPost && !discard) methodVisitor.visitVarInsn(Opcodes.ILOAD, index);
                        methodVisitor.visitIincInsn(index, isInc ? 1 : -1);
                        if (!isPost && !discard) methodVisitor.visitVarInsn(Opcodes.ILOAD, index);
                    } else if (isIntLike) {
                        boolean discard = (node == discardRoot);
                        if (discard) wasValueDiscardedByExpr = true;
                        methodVisitor.visitVarInsn(Opcodes.ILOAD, index);
                        if (isPost && !discard) methodVisitor.visitInsn(Opcodes.DUP);
                        methodVisitor.visitInsn(Opcodes.ICONST_1);
                        methodVisitor.visitInsn(isInc ? Opcodes.IADD : Opcodes.ISUB);
                        emitConversion("I", desc);
                        if (!isPost && !discard) methodVisitor.visitInsn(Opcodes.DUP);
                        methodVisitor.visitVarInsn(Opcodes.ISTORE, index);
                    } else if (desc.startsWith("L")) {
                        boolean discard = (node == discardRoot);
                        if (discard) wasValueDiscardedByExpr = true;

                        methodVisitor.visitVarInsn(Opcodes.ALOAD, index);
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");

                        if (isPost && !discard) {
                            methodVisitor.visitInsn(Opcodes.DUP);
                        }

                        emitIntConst(1);
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);

                        String method = isInc ? "add" : "subtract";
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", method, "(Ljava/lang/Number;Ljava/lang/Number;)Ljava/lang/Number;", false);
                        emitCheckcast(desc);

                        if (!isPost && !discard) {
                            methodVisitor.visitInsn(Opcodes.DUP);
                        }

                        methodVisitor.visitVarInsn(Opcodes.ASTORE, index);
                    } else {
                        // Long/Float/Double locals (no IINC)
                        int loadOp = desc.equals("J") ? Opcodes.LLOAD : (desc.equals("F") ? Opcodes.FLOAD : Opcodes.DLOAD);
                        int storeOp = desc.equals("J") ? Opcodes.LSTORE : (desc.equals("F") ? Opcodes.FSTORE : Opcodes.DSTORE);

                        methodVisitor.visitVarInsn(loadOp, index);
                        int slotAmountOp = desc.equals("J") || desc.equals("D") ? Opcodes.DUP2 : Opcodes.DUP;
                        if (isPost) methodVisitor.visitInsn(slotAmountOp);

                        switch (desc) {
                            case "J" -> {
                                methodVisitor.visitInsn(Opcodes.LCONST_1);
                                methodVisitor.visitInsn(isInc ? Opcodes.LADD : Opcodes.LSUB);
                            }
                            case "F" -> {
                                methodVisitor.visitInsn(Opcodes.FCONST_1);
                                methodVisitor.visitInsn(isInc ? Opcodes.FADD : Opcodes.FSUB);
                            }
                            case "D" -> {
                                methodVisitor.visitInsn(Opcodes.DCONST_1);
                                methodVisitor.visitInsn(isInc ? Opcodes.DADD : Opcodes.DSUB);
                            }
                        }

                        if (!isPost) methodVisitor.visitInsn(slotAmountOp);
                        methodVisitor.visitVarInsn(storeOp, index);
                    }
                }
            }
        }
        else if (expr instanceof IRArrayAccess arrayAccess) {
            String desc = arrayAccess.getTypeDescriptor();
            boolean isIntLike = desc.equals("I") || desc.equals("Z") || desc.equals("B") || desc.equals("S") || desc.equals("C");
            boolean isDoubleSlot = desc.equals("J") || desc.equals("D");

            // 1. Evaluate array and index
            arrayAccess.getArray().accept(this);
            if (arrayAccess.getArray() instanceof IRArrayAccess) {
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "[" + desc);
            } else {
                String arrayType = getReceiverType(arrayAccess.getArray());
                if (arrayType != null && (TypeChecker.isObjectType(arrayType) || arrayType.equals("java/lang/Object"))) {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "[" + desc);
                }
            }
            arrayAccess.getIndex().accept(this);
            emitConversion(arrayAccess.getIndex().getTypeDescriptor(), "I");

            // DUP2 to duplicate array ref and index
            methodVisitor.visitInsn(Opcodes.DUP2);

            // 2. Load the value from array
            int loadOp = Opcodes.AALOAD;
            loadOp = switch (desc) {
                case "I" -> Opcodes.IALOAD;
                case "J" -> Opcodes.LALOAD;
                case "F" -> Opcodes.FALOAD;
                case "D" -> Opcodes.DALOAD;
                case "B", "Z" -> Opcodes.BALOAD;
                case "C" -> Opcodes.CALOAD;
                case "S" -> Opcodes.SALOAD;
                default -> loadOp;
            };
            methodVisitor.visitInsn(loadOp);

            // 3. Post-increment: copy the loaded value behind array and index
            if (isPost) {
                if (isDoubleSlot) {
                    methodVisitor.visitInsn(Opcodes.DUP2_X2);
                } else {
                    methodVisitor.visitInsn(Opcodes.DUP_X2);
                }
            }

            // 4. Push 1 and add/sub
            if (isIntLike) {
                methodVisitor.visitInsn(Opcodes.ICONST_1);
                methodVisitor.visitInsn(isInc ? Opcodes.IADD : Opcodes.ISUB);
                switch (desc) {
                    case "B" -> methodVisitor.visitInsn(Opcodes.I2B);
                    case "S" -> methodVisitor.visitInsn(Opcodes.I2S);
                    case "C" -> methodVisitor.visitInsn(Opcodes.I2C);
                }
            } else if (desc.equals("J")) {
                methodVisitor.visitInsn(Opcodes.LCONST_1);
                methodVisitor.visitInsn(isInc ? Opcodes.LADD : Opcodes.LSUB);
            } else if (desc.equals("F")) {
                methodVisitor.visitInsn(Opcodes.FCONST_1);
                methodVisitor.visitInsn(isInc ? Opcodes.FADD : Opcodes.FSUB);
            } else if (desc.equals("D")) {
                methodVisitor.visitInsn(Opcodes.DCONST_1);
                methodVisitor.visitInsn(isInc ? Opcodes.DADD : Opcodes.DSUB);
            } else if (TypeChecker.isClassType(desc) && TypeChecker.isNumeric(desc)) {
                if (desc.equals("Ljava/math/BigDecimal;")) {
                    methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/math/BigDecimal", "ONE", "Ljava/math/BigDecimal;");
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", isInc ? "add" : "subtract", "(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;", false);
                } else {
                    String unbox = TypeChecker.unbox(desc);
                    boolean isIntLikeForUnbox = unbox.equals("I") || unbox.equals("Z") || unbox.equals("B") || unbox.equals("S") || unbox.equals("C");
                    if (isIntLikeForUnbox) {

                        switch (unbox) {
                            case "I" -> {
                                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
                                methodVisitor.visitInsn(Opcodes.ICONST_1);
                                methodVisitor.visitInsn(isInc ? Opcodes.IADD : Opcodes.ISUB);
                                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                            }
                            case "B" -> {
                                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false);
                                methodVisitor.visitInsn(Opcodes.ICONST_1);
                                methodVisitor.visitInsn(isInc ? Opcodes.IADD : Opcodes.ISUB);
                                methodVisitor.visitInsn(Opcodes.I2B);
                                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
                            }
                            case "S" -> {
                                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false);
                                methodVisitor.visitInsn(Opcodes.ICONST_1);
                                methodVisitor.visitInsn(isInc ? Opcodes.IADD : Opcodes.ISUB);
                                methodVisitor.visitInsn(Opcodes.I2S);
                                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
                            }
                            case "C" -> {
                                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                                methodVisitor.visitInsn(Opcodes.ICONST_1);
                                methodVisitor.visitInsn(isInc ? Opcodes.IADD : Opcodes.ISUB);
                                methodVisitor.visitInsn(Opcodes.I2C);
                                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
                            }
                        }
                    } else if (unbox.equals("J")) {
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false);
                        methodVisitor.visitInsn(Opcodes.LCONST_1);
                        methodVisitor.visitInsn(isInc ? Opcodes.LADD : Opcodes.LSUB);
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                    } else if (unbox.equals("F")) {
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false);
                        methodVisitor.visitInsn(Opcodes.FCONST_1);
                        methodVisitor.visitInsn(isInc ? Opcodes.FADD : Opcodes.FSUB);
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
                    } else if (unbox.equals("D")) {
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false);
                        methodVisitor.visitInsn(Opcodes.DCONST_1);
                        methodVisitor.visitInsn(isInc ? Opcodes.DADD : Opcodes.DSUB);
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                    }
                }
            }

            // 5. Pre-increment: copy the incremented value behind array and index
            if (!isPost) {
                if (isDoubleSlot) {
                    methodVisitor.visitInsn(Opcodes.DUP2_X2);
                } else {
                    methodVisitor.visitInsn(Opcodes.DUP_X2);
                }
            }

            // 6. Store back to array
            int storeOp = Opcodes.AASTORE;
            storeOp = switch (desc) {
                case "I" -> Opcodes.IASTORE;
                case "J" -> Opcodes.LASTORE;
                case "F" -> Opcodes.FASTORE;
                case "D" -> Opcodes.DASTORE;
                case "B", "Z" -> Opcodes.BASTORE;
                case "C" -> Opcodes.CASTORE;
                case "S" -> Opcodes.SASTORE;
                default -> storeOp;
            };
            methodVisitor.visitInsn(storeOp);
        }
    }

    @Override
    public void visitTernary(IRTernaryExpression node) {

        // Null coalescing (a ?? b) özel durumu: LHS'i yalnızca bir kez değerlendir.
        // Bytecode pattern: eval(LHS) -> DUP -> IFNONNULL labelTrue -> POP -> eval(RHS) -> GOTO labelEnd -> labelTrue: -> labelEnd:
        if (node.isNullCoalescing()) {
            String rawTrueDesc = node.getTrueExpr().getTypeDescriptor();
            boolean isNullableTrue = TypeChecker.isNullable(rawTrueDesc)
                    || (node.getTrueExpr() instanceof IRVariableAccess va && va.isSafeAccess())
                    || (node.getTrueExpr() instanceof IRMethodCall mc && mc.isSafeAccess());
            String trueDesc = cleanDesc(rawTrueDesc);
            if (isPrimitive(trueDesc) && !isNullableTrue) {
                // Primitive types cannot be null in JVM, evaluate LHS directly without DUP/IFNONNULL
                node.getTrueExpr().accept(this);
                emitConversion(trueDesc, node.getTypeDescriptor());
                return;
            }

            Label labelTrue = new Label();
            Label labelEnd = new Label();

            // LHS'i bir kez stack'e yükle
            node.getTrueExpr().accept(this);
            // DUP: null kontrolü için kopyasını al (null değilse bu kopya sonuç olacak)
            methodVisitor.visitInsn(Opcodes.DUP);
            // Null değilse doğrudan true dalı sonucuna git (stack'te zaten LHS var)
            methodVisitor.visitJumpInsn(Opcodes.IFNONNULL, labelTrue);
            // Null ise: DUP'un kopyasını temizle, RHS'i değerlendir
            methodVisitor.visitInsn(Opcodes.POP);
            node.getFalseExpr().accept(this);
            emitConversion(node.getFalseExpr().getTypeDescriptor(), node.getTypeDescriptor());
            methodVisitor.visitJumpInsn(Opcodes.GOTO, labelEnd);
            // True dal: LHS zaten stack'te (DUP'tan gelen kopya)
            methodVisitor.visitLabel(labelTrue);
            emitConversion(node.getTrueExpr().getTypeDescriptor(), node.getTypeDescriptor());
            methodVisitor.visitLabel(labelEnd);
            return;
        }

        // Normal ternary (cond ? a : b)
        Label labelElse = new Label();
        Label labelEnd = new Label();

        emitBranch(node.getCondition(), labelElse, false);

        node.getTrueExpr().accept(this);
        emitConversion(node.getTrueExpr().getTypeDescriptor(), node.getTypeDescriptor());
        methodVisitor.visitJumpInsn(Opcodes.GOTO, labelEnd);

        methodVisitor.visitLabel(labelElse);
        node.getFalseExpr().accept(this);
        emitConversion(node.getFalseExpr().getTypeDescriptor(), node.getTypeDescriptor());

        methodVisitor.visitLabel(labelEnd);
    }


    @Override
    public void visitCast(IRCastExpression node) {
        node.getExpression().accept(this);
        String sourceType = node.getExpression().getTypeDescriptor();
        String targetType = node.getTargetType();

        // Handle primitive casts via emitConversion
        if (isPrimitive(targetType) || isPrimitive(sourceType)) {
            emitConversion(sourceType, targetType);
            return;
        }

        // Redundant CHECKCAST Elimination (1.1)
        if (node.getAdditionalBounds().isEmpty()) {
            if (sourceType != null && targetType != null) {
                String cleanSource = TypeChecker.cleanDescriptor(sourceType);
                String cleanTarget = TypeChecker.cleanDescriptor(targetType);
                if (cleanSource.equals(cleanTarget) || TypeChecker.isObjectType(cleanTarget)) {
                    return;
                }
            }
        }

        emitCheckcast(targetType);
        for (String extra : node.getAdditionalBounds()) {
            emitCheckcast(extra);
        }
    }

    private void emitCheckcast(String targetType) {
        if (targetType == null) return;
        String cleaned = TypeChecker.cleanDescriptor(targetType);
        String target = cleaned.replace(".", "/");

        // 1. Array types ending with [] (e.g. String[], int[], MyClass[][])
        if (target.contains("[]")) {
            int dims = 0;
            while (target.endsWith("[]")) {
                dims++;
                target = target.substring(0, target.length() - 2).trim();
            }
            while (TypeChecker.isClassType(target)) {
                target = target.substring(1, target.length() - 1);
            }
            if (target.contains("<")) {
                target = target.substring(0, target.indexOf('<'));
            }
            String baseDesc;
            if (TypeChecker.isPrimitive(target)) {
                baseDesc = target;
            } else {
                if (!target.contains("/")) {
                    target = OceanTypeSystem.resolveInternalClassName(target, currentClassName);
                }
                baseDesc = (target.startsWith("L") && target.endsWith(";")) ? target : "L" + target + ";";
            }
            target = "[".repeat(dims) + baseDesc;
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, target);
            return;
        }

        // 2. Already array descriptor starting with [ (e.g. [Ljava/lang/String;, [I)
        if (target.startsWith("[")) {
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, target);
            return;
        }

        while (TypeChecker.isClassType(target)) {
            target = target.substring(1, target.length() - 1);
        }
        if (target.contains("<")) {
            target = target.substring(0, target.indexOf('<'));
        }

        // 3. Generic type parameter check (e.g. T, E, K, V)
        if (!target.contains("/")) {
            boolean isTypeParam = false;
            String bound = null;
            if (currentClassName != null) {
                String fullPath = currentClassName.replace(".", "/");
                List<CompilerRegistry.TypeParameterInfo> infos = CompilerRegistry.globalTypeParameterRegistry.get(fullPath);
                if (infos == null) {
                    infos = CompilerRegistry.globalTypeParameterRegistry.get(currentClassName);
                }
                if (infos != null) {
                    for (CompilerRegistry.TypeParameterInfo info : infos) {
                        if (info.name.equals(target)) {
                            isTypeParam = true;
                            bound = info.upperBound;
                            break;
                        }
                    }
                }
            }
            if (isTypeParam) {
                if (bound != null && !bound.isEmpty() && !bound.equals(OceanTypeSystem.OBJECT_DESC) && !bound.equals("java/lang/Object")) {
                    emitCheckcast(bound);
                }
                return;
            }

            // Fallback for method type parameters or single uppercase letter type parameters
            if (target.length() <= 2 && Character.isUpperCase(target.charAt(0))) {
                String resolved = OceanTypeSystem.resolveInternalClassName(target, currentClassName);
                if (resolved.equals(target) && !OceanTypeSystem.hasClass(target) && CompilerRegistry.getClassSymbol(target) == null) {
                    // Not a real class in classpath/project, treat as unbounded type parameter -> no-op checkcast
                    return;
                }
                target = resolved;
            } else {
                target = OceanTypeSystem.resolveInternalClassName(target, currentClassName);
            }
        }

        if (target.equals("java/lang/Object") || target.equals(OceanTypeSystem.OBJECT_DESC)) {
            return; // Redundant CHECKCAST java/lang/Object
        }

        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, target);
    }

    @Override
    public void visitAwaitExpression(IRAwaitExpression node) {
        node.getFuture().accept(this);

        methodVisitor.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "ocean/stdlib/RuntimeUtils",
                "await",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                false
        );

        String resultDesc = node.getTypeDescriptor();
        if (resultDesc == null) {
            return;
        }
        switch (resultDesc) {
            case "I" -> emitWrapperUnbox("java/lang/Integer", "I", "intValue", "()I", Opcodes.ICONST_0);
            case "Z" -> emitWrapperUnbox("java/lang/Boolean", "Z", "booleanValue", "()Z", Opcodes.ICONST_0);
            case "J" -> emitWrapperUnbox("java/lang/Long", "J", "longValue", "()J", Opcodes.LCONST_0);
            case "F" -> emitWrapperUnbox("java/lang/Float", "F", "floatValue", "()F", Opcodes.FCONST_0);
            case "D" -> emitWrapperUnbox("java/lang/Double", "D", "doubleValue", "()D", Opcodes.DCONST_0);
            case "C" -> emitWrapperUnbox("java/lang/Character", "C", "charValue", "()C", Opcodes.ICONST_0);
            case "B" -> emitWrapperUnbox("java/lang/Byte", "B", "byteValue", "()B", Opcodes.ICONST_0);
            case "S" -> emitWrapperUnbox("java/lang/Short", "S", "shortValue", "()S", Opcodes.ICONST_0);
            case "V" -> methodVisitor.visitInsn(Opcodes.POP);
            default -> {
                String cleanType = cleanDesc(resultDesc);
                if (cleanType != null && !cleanType.equals("java/lang/Object") && !cleanType.equals(OceanTypeSystem.OBJECT_DESC)) {
                    if (TypeChecker.isClassType(cleanType)) {
                        cleanType = cleanType.substring(1, cleanType.length() - 1);
                    }
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, cleanType);
                }
            }
        }
    }

    private boolean isPrimitive(String desc) {
        if (desc == null) return false;
        return TypeChecker.isPrimitive(desc);
    }

    private boolean isPrimitiveListType(String typeDesc) {
        if (typeDesc == null) return false;
        String clean = TypeChecker.cleanDescriptor(typeDesc);
        if (clean.startsWith("L") && clean.endsWith(";")) clean = clean.substring(1, clean.length() - 1);
        return clean.equals("ocean/stdlib/OceanIntList")
                || clean.equals("ocean/stdlib/OceanLongList")
                || clean.equals("ocean/stdlib/OceanDoubleList")
                || clean.equals("ocean/stdlib/OceanFloatList")
                || clean.equals("ocean/stdlib/OceanBooleanList")
                || clean.equals("ocean/stdlib/OceanByteList")
                || clean.equals("ocean/stdlib/OceanShortList")
                || clean.equals("ocean/stdlib/OceanCharList");
    }

    private String getPrimitiveListOwner(String typeDesc) {
        String clean = TypeChecker.cleanDescriptor(typeDesc);
        if (clean.startsWith("L") && clean.endsWith(";")) clean = clean.substring(1, clean.length() - 1);
        return clean;
    }

    private String getPrimitiveListGetterName(String owner) {
        return switch (owner) {
            case "ocean/stdlib/OceanIntList" -> "getInt";
            case "ocean/stdlib/OceanLongList" -> "getLong";
            case "ocean/stdlib/OceanDoubleList" -> "getDouble";
            case "ocean/stdlib/OceanFloatList" -> "getFloat";
            case "ocean/stdlib/OceanBooleanList" -> "getBool";
            case "ocean/stdlib/OceanByteList" -> "getByte";
            case "ocean/stdlib/OceanShortList" -> "getShort";
            case "ocean/stdlib/OceanCharList" -> "getChar";
            default -> "get";
        };
    }

    private String getPrimitiveListGetterDesc(String owner) {
        return switch (owner) {
            case "ocean/stdlib/OceanIntList" -> "(I)I";
            case "ocean/stdlib/OceanLongList" -> "(I)J";
            case "ocean/stdlib/OceanDoubleList" -> "(I)D";
            case "ocean/stdlib/OceanFloatList" -> "(I)F";
            case "ocean/stdlib/OceanBooleanList" -> "(I)Z";
            case "ocean/stdlib/OceanByteList" -> "(I)B";
            case "ocean/stdlib/OceanShortList" -> "(I)S";
            case "ocean/stdlib/OceanCharList" -> "(I)C";
            default -> "(I)Ljava/lang/Object;";
        };
    }

    private String getPrimitiveListElementType(String owner) {
        return switch (owner) {
            case "ocean/stdlib/OceanIntList" -> "I";
            case "ocean/stdlib/OceanLongList" -> "J";
            case "ocean/stdlib/OceanDoubleList" -> "D";
            case "ocean/stdlib/OceanFloatList" -> "F";
            case "ocean/stdlib/OceanBooleanList" -> "Z";
            case "ocean/stdlib/OceanByteList" -> "B";
            case "ocean/stdlib/OceanShortList" -> "S";
            case "ocean/stdlib/OceanCharList" -> "C";
            default -> OceanTypeSystem.OBJECT_DESC;
        };
    }

    private boolean isEnumType(String typeDesc) {
        if (!TypeChecker.isClassType(typeDesc)) return false;
        String internalName = typeDesc.substring(1, typeDesc.length() - 1);
        if (CompilerRegistry.globalSuperClassRegistry.containsKey(internalName)) {
            return "java/lang/Enum".equals(CompilerRegistry.globalSuperClassRegistry.get(internalName));
        }
        try {
            Class<?> cls = OceanTypeSystem.forName(internalName.replace("/", "."));
            return cls != null && cls.isEnum();
        } catch (Exception e) {
            return false;
        }
    }

    private int resolveEnumOrdinal(String switchType, IRExpression valExpr) {
        String constName = null;
        String enumOwner = null;

        if (valExpr instanceof IRVariableAccess va) {
            constName = va.getName();
            enumOwner = va.getOwner();
        } else if (valExpr instanceof IRLiteral lit) {
            Object v = lit.getValue();
            if (v != null) constName = v.toString();
        }

        if (constName == null) {
            return -1;
        }

        String expectedEnum = null;
        if (TypeChecker.isClassType(switchType)) {
            expectedEnum = switchType.substring(1, switchType.length() - 1).replace('.', '/');
        } else if (switchType != null && !switchType.isEmpty()) {
            expectedEnum = switchType.replace('.', '/');
        }

        if (enumOwner != null && !enumOwner.isEmpty() && expectedEnum != null) {
            String cleanOwner = enumOwner.replace('.', '/');
            if (cleanOwner.startsWith("L") && cleanOwner.endsWith(";")) {
                cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
            }
            String simpleOwner = cleanOwner.contains("/") ? cleanOwner.substring(cleanOwner.lastIndexOf('/') + 1) : cleanOwner;
            String simpleExpected = expectedEnum.contains("/") ? expectedEnum.substring(expectedEnum.lastIndexOf('/') + 1) : expectedEnum;
            if (!cleanOwner.equals(expectedEnum) && !simpleOwner.equals(simpleExpected)) {
                return -1;
            }
        }

        String internalName = expectedEnum;

        if (internalName != null) {
            // 1. Look in CompilerRegistry.globalEnumConstants
            List<String> consts = CompilerRegistry.globalEnumConstants.get(internalName);
            if (consts == null) {
                String simple = internalName.substring(internalName.lastIndexOf('/') + 1);
                consts = CompilerRegistry.globalEnumConstants.get(simple);
            }
            if (consts != null) {
                int idx = consts.indexOf(constName);
                if (idx >= 0) return idx;
            }

            // 2. Check via Reflection for external Java enums
            try {
                Class<?> cls = OceanTypeSystem.forName(internalName.replace('/', '.'));
                if (cls != null && cls.isEnum()) {
                    for (Field f : cls.getFields()) {
                        if (f.isEnumConstant() && f.getName().equals(constName)) {
                            Enum<?> e = (Enum<?>) f.get(null);
                            return e.ordinal();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        return -1;
    }

    private static boolean isDoubleOrLong(String desc) {
        return "D".equals(desc) || "J".equals(desc);
    }

    private void emitPrimitiveComparison(String compDesc, String exprType, Label failLabel) {
        switch (compDesc) {
            case "I", "Z", "B", "C", "S" -> methodVisitor.visitJumpInsn(Opcodes.IF_ICMPNE, failLabel);
            case "J" -> {
                methodVisitor.visitInsn(Opcodes.LCMP);
                methodVisitor.visitJumpInsn(Opcodes.IFNE, failLabel);
            }
            case "F" -> {
                methodVisitor.visitInsn(Opcodes.FCMPL);
                methodVisitor.visitJumpInsn(Opcodes.IFNE, failLabel);
            }
            case "D" -> {
                methodVisitor.visitInsn(Opcodes.DCMPL);
                methodVisitor.visitJumpInsn(Opcodes.IFNE, failLabel);
            }
            case null, default -> {
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false);
                methodVisitor.visitJumpInsn(Opcodes.IFEQ, failLabel);
            }
        }
    }

    private String getPrimitiveMatchMethod(String primDesc) {
        if (primDesc == null) return null;
        String clean = TypeChecker.cleanDescriptor(primDesc);
        return switch (clean) {
            case "B" -> "matchesPrimitiveByte";
            case "S" -> "matchesPrimitiveShort";
            case "I" -> "matchesPrimitiveInt";
            case "J" -> "matchesPrimitiveLong";
            case "F" -> "matchesPrimitiveFloat";
            case "D" -> "matchesPrimitiveDouble";
            case "C" -> "matchesPrimitiveChar";
            case "Z" -> "matchesPrimitiveBoolean";
            default -> null;
        };
    }

    private String getPrimitiveExtractMethod(String primDesc) {
        if (primDesc == null) return null;
        String clean = TypeChecker.cleanDescriptor(primDesc);
        return switch (clean) {
            case "B" -> "toPrimitiveByte";
            case "S" -> "toPrimitiveShort";
            case "I" -> "toPrimitiveInt";
            case "J" -> "toPrimitiveLong";
            case "F" -> "toPrimitiveFloat";
            case "D" -> "toPrimitiveDouble";
            case "C" -> "toPrimitiveChar";
            case "Z" -> "toPrimitiveBoolean";
            default -> null;
        };
    }

    private void emitPatternCheck(IRSwitchPattern pat, int targetIndex, Label failLabel) {
        boolean isNullPat = pat.getKind() == IRSwitchPattern.Kind.NULL
                || (pat.getExpression() instanceof IRLiteral lit && lit.getValue() == null);

        if (isNullPat) {
            methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
            methodVisitor.visitJumpInsn(Opcodes.IFNONNULL, failLabel);
        } else if (pat.getKind() == IRSwitchPattern.Kind.TYPE) {
            String typeDesc = pat.getTypeDescriptor();
            String specialized = TypeChecker.getSpecializedPrimitiveListClass(typeDesc);
            if (specialized != null) {
                typeDesc = OceanTypeSystem.wrapObjectType(specialized);
            }
            if (TypeChecker.isPrimitive(typeDesc)) {
                String matchMethod = getPrimitiveMatchMethod(typeDesc);
                if (matchMethod != null) {
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", matchMethod, "(Ljava/lang/Object;)Z", false);
                    methodVisitor.visitJumpInsn(Opcodes.IFEQ, failLabel);
                }
            } else if (typeDesc == null || typeDesc.equals(OceanTypeSystem.OBJECT_DESC)) {
                methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                methodVisitor.visitJumpInsn(Opcodes.IFNULL, failLabel);
            } else {
                String internalName = TypeChecker.getInternalName(typeDesc);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                methodVisitor.visitTypeInsn(Opcodes.INSTANCEOF, internalName.replace('.', '/'));
                methodVisitor.visitJumpInsn(Opcodes.IFEQ, failLabel);
            }

            if (pat.getVariableName() != null) {
                int varIdx = getVariableIndex(pat.getVariableName());
                if (varIdx == -1) {
                    varIdx = nextLocalIndex;
                    if (isDoubleOrLong(typeDesc)) {
                        nextLocalIndex += 2;
                    } else {
                        nextLocalIndex += 1;
                    }
                    declareVariable(pat.getVariableName(), varIdx, typeDesc);
                }
                if (TypeChecker.isPrimitive(typeDesc)) {
                    String extractMethod = getPrimitiveExtractMethod(typeDesc);
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                    String retDesc = TypeChecker.cleanDescriptor(typeDesc);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", extractMethod, "(Ljava/lang/Object;)" + retDesc, false);
                    emitStore(typeDesc, varIdx);
                } else {
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                    if (typeDesc != null && !typeDesc.equals(OceanTypeSystem.OBJECT_DESC)) {
                        String internalName = TypeChecker.getInternalName(typeDesc);
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, internalName.replace('.', '/'));
                    }
                    emitStore(typeDesc, varIdx);
                }
            }
        } else if (pat.getKind() == IRSwitchPattern.Kind.RECORD) {
            String typeDesc = pat.getTypeDescriptor();
            String internalName = TypeChecker.getInternalName(typeDesc);

            // 1. null check & instanceof check
            methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
            methodVisitor.visitJumpInsn(Opcodes.IFNULL, failLabel);
            methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
            methodVisitor.visitTypeInsn(Opcodes.INSTANCEOF, internalName);
            methodVisitor.visitJumpInsn(Opcodes.IFEQ, failLabel);

            // 2. If record is bound to a variable e.g. Point(...) p
            if (pat.getVariableName() != null) {
                int varIdx = getVariableIndex(pat.getVariableName());
                if (varIdx == -1) {
                    varIdx = nextLocalIndex;
                    if (isDoubleOrLong(typeDesc)) {
                        nextLocalIndex += 2;
                    } else {
                        nextLocalIndex += 1;
                    }
                    declareVariable(pat.getVariableName(), varIdx, typeDesc);
                }
                methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, internalName);
                emitStore(typeDesc, varIdx);
            }

            // 3. Deconstruct each component
            List<CompilerRegistry.RecordComponentInfo> components = RecordHelper.getRecordComponents(typeDesc);
            List<IRSwitchPattern> nested = pat.getNestedPatterns();
            if (nested != null && !nested.isEmpty()) {
                for (int compIdx = 0; compIdx < nested.size(); compIdx++) {
                    IRSwitchPattern subPat = nested.get(compIdx);
                    String compName = (components != null && compIdx < components.size()) ? components.get(compIdx).name() : null;
                    String compDesc = (components != null && compIdx < components.size()) ? components.get(compIdx).descriptor() : OceanTypeSystem.OBJECT_DESC;

                    // Extract component value: load target, checkcast, call getter
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, internalName);

                    String getterName = compName != null ? "get" + compName.substring(0, 1).toUpperCase(Locale.ENGLISH) + compName.substring(1) : null;

                    boolean isInterface = CompilerRegistry.globalIsInterfaceSet.contains(internalName);
                    int invokeOp = isInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;

                    boolean isJavaRec = RecordHelper.isJavaRecord(internalName);

                    if (isJavaRec && compName != null) {
                        // Classpath records declare component accessors x(), not getX()
                        methodVisitor.visitMethodInsn(invokeOp, internalName, compName, "()" + compDesc, isInterface);
                    } else if (getterName != null && CompilerRegistry.globalMethodRegistry.containsKey(internalName)
                            && CompilerRegistry.globalMethodRegistry.get(internalName).containsKey(getterName)) {
                        // Ocean data classes use getX()
                        methodVisitor.visitMethodInsn(invokeOp, internalName, getterName, "()" + compDesc, isInterface);
                    } else if (compName != null && CompilerRegistry.globalMethodRegistry.containsKey(internalName)
                            && CompilerRegistry.globalMethodRegistry.get(internalName).containsKey(compName)) {
                        methodVisitor.visitMethodInsn(invokeOp, internalName, compName, "()" + compDesc, isInterface);
                    } else if (compName != null) {
                        methodVisitor.visitFieldInsn(Opcodes.GETFIELD, internalName, compName, compDesc);
                    } else {
                        reportError(currentClassNode, "Unable to resolve record component accessor for " + internalName);
                        methodVisitor.visitInsn(Opcodes.POP);
                        emitPushDefaultValue(methodVisitor, compDesc);
                    }

                    int compSlot = nextLocalIndex;
                    if (isDoubleOrLong(compDesc)) {
                        nextLocalIndex += 2;
                    } else {
                        nextLocalIndex += 1;
                    }
                    emitStore(compDesc, compSlot);

                    if (TypeChecker.isPrimitive(compDesc)) {
                        if (subPat.getKind() == IRSwitchPattern.Kind.TYPE) {
                            String targetPrim = subPat.getTypeDescriptor();
                            if (TypeChecker.isPrimitive(targetPrim) && !compDesc.equals(targetPrim)) {
                                String matchMethod = getPrimitiveMatchMethod(targetPrim);
                                if (matchMethod != null) {
                                    emitLoad(compDesc, compSlot);
                                    emitConversion(compDesc, OceanTypeSystem.OBJECT_DESC);
                                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", matchMethod, "(Ljava/lang/Object;)Z", false);
                                    methodVisitor.visitJumpInsn(Opcodes.IFEQ, failLabel);
                                }
                            }
                            if (subPat.getVariableName() != null && !subPat.getVariableName().equals("_")) {
                                int varIdx = getVariableIndex(subPat.getVariableName());
                                String varType = subPat.getTypeDescriptor();
                                if (varType == null || OceanTypeSystem.OBJECT_DESC.equals(varType)) {
                                    varType = compDesc;
                                }
                                if (varIdx == -1) {
                                    varIdx = nextLocalIndex;
                                    if (isDoubleOrLong(varType)) {
                                        nextLocalIndex += 2;
                                    } else {
                                        nextLocalIndex += 1;
                                    }
                                    declareVariable(subPat.getVariableName(), varIdx, varType);
                                }
                                emitLoad(compDesc, compSlot);
                                if (!compDesc.equals(varType)) {
                                    emitConversion(compDesc, varType);
                                }
                                emitStore(varType, varIdx);
                            }
                        } else if (subPat.getKind() == IRSwitchPattern.Kind.UNNAMED) {
                            String targetPrim = subPat.getTypeDescriptor();
                            if (TypeChecker.isPrimitive(targetPrim) && !compDesc.equals(targetPrim)) {
                                String matchMethod = getPrimitiveMatchMethod(targetPrim);
                                if (matchMethod != null) {
                                    emitLoad(compDesc, compSlot);
                                    emitConversion(compDesc, OceanTypeSystem.OBJECT_DESC);
                                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", matchMethod, "(Ljava/lang/Object;)Z", false);
                                    methodVisitor.visitJumpInsn(Opcodes.IFEQ, failLabel);
                                }
                            }
                        } else if (subPat.getKind() == IRSwitchPattern.Kind.EXPR) {
                            emitLoad(compDesc, compSlot);
                            subPat.getExpression().accept(this);
                            String exprType = subPat.getExpression().getTypeDescriptor();
                            emitPrimitiveComparison(compDesc, exprType, failLabel);
                        }
                    } else {
                        emitPatternCheck(subPat, compSlot, failLabel);
                    }
                }
            }
        } else if (pat.getKind() == IRSwitchPattern.Kind.UNNAMED) {
            String typeDesc = pat.getTypeDescriptor();
            if (TypeChecker.isPrimitive(typeDesc)) {
                String matchMethod = getPrimitiveMatchMethod(typeDesc);
                if (matchMethod != null) {
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", matchMethod, "(Ljava/lang/Object;)Z", false);
                    methodVisitor.visitJumpInsn(Opcodes.IFEQ, failLabel);
                }
            } else if (typeDesc != null && !typeDesc.equals(OceanTypeSystem.OBJECT_DESC)) {
                String internalName = TypeChecker.getInternalName(typeDesc);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                methodVisitor.visitJumpInsn(Opcodes.IFNULL, failLabel);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                methodVisitor.visitTypeInsn(Opcodes.INSTANCEOF, internalName.replace('.', '/'));
                methodVisitor.visitJumpInsn(Opcodes.IFEQ, failLabel);
            }
        } else if (pat.getKind() == IRSwitchPattern.Kind.EXPR) {
            methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
            methodVisitor.visitJumpInsn(Opcodes.IFNULL, failLabel);
            methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
            pat.getExpression().accept(this);
            String exprType = pat.getExpression().getTypeDescriptor();
            if (TypeChecker.isPrimitive(exprType)) {
                emitConversion(exprType, OceanTypeSystem.OBJECT_DESC);
            }
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false);
            methodVisitor.visitJumpInsn(Opcodes.IFEQ, failLabel);
        }
    }

    @Override
    public void visitSwitch(IRSwitchStatement node) {
        enterScope();
        Label defaultLabel = new Label();
        Label endLabel = new Label();

        String switchType = node.getExpression().getTypeDescriptor();
        boolean isEnumSwitch = isEnumType(switchType);
        boolean isStringSwitch = TypeChecker.isStringType(switchType);

        loopTargets.push(new LoopTarget(null, endLabel, null, finallyBlocks.size()));
        stopLabels.push(endLabel);

        // Create a body label for each case to support source-order fall-through
        Label[] bodyLabels = new Label[node.getCases().size()];
        for (int i = 0; i < node.getCases().size(); i++) {
            bodyLabels[i] = new Label();
        }

        boolean hasPattern = false;
        for (IRSwitchCase c : node.getCases()) {
            if (c.hasPattern() || c.getGuard() != null) {
                hasPattern = true;
                break;
            }
        }
        if (!hasPattern && !isEnumSwitch && !isStringSwitch && switchType != null
                && !TypeChecker.isIntegerType(switchType) && !switchType.equals("C") && !switchType.equals("B") && !switchType.equals("S")) {
            hasPattern = true;
        }

        if (hasPattern) {
            // 1. Evaluate expression and store in a temp variable
            node.getExpression().accept(this);
            String rawTargetType = node.getExpression().getTypeDescriptor();
            if (TypeChecker.isPrimitive(rawTargetType)) {
                emitConversion(rawTargetType, OceanTypeSystem.OBJECT_DESC);
            }
            int targetIndex = nextLocalIndex++;
            methodVisitor.visitVarInsn(Opcodes.ASTORE, targetIndex);

            // 2. Dispatch each case
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                Label bodyLabel = bodyLabels[i];
                Label nextCaseLabel = new Label();

                if (c.getPatterns() != null && !c.getPatterns().isEmpty()) {
                    for (int pIdx = 0; pIdx < c.getPatterns().size(); pIdx++) {
                        IRSwitchPattern pat = c.getPatterns().get(pIdx);
                        Label failThisPattern = (pIdx == c.getPatterns().size() - 1) ? nextCaseLabel : new Label();

                        emitPatternCheck(pat, targetIndex, failThisPattern);

                        // Evaluate guard (when) if present
                        if (c.getGuard() != null) {
                            c.getGuard().accept(this);
                            String gType = c.getGuard().getTypeDescriptor();
                            if (gType != null && !gType.equals("Z")) {
                                emitConversion(gType, "Z");
                            }
                            methodVisitor.visitJumpInsn(Opcodes.IFEQ, failThisPattern);
                        }

                        methodVisitor.visitJumpInsn(Opcodes.GOTO, bodyLabel);

                        if (failThisPattern != nextCaseLabel) {
                            methodVisitor.visitLabel(failThisPattern);
                        }
                    }
                } else if (c.getValues() != null && !c.getValues().isEmpty()) {
                    for (int vIdx = 0; vIdx < c.getValues().size(); vIdx++) {
                        IRExpression val = c.getValues().get(vIdx);
                        Label failVal = (vIdx == c.getValues().size() - 1) ? nextCaseLabel : new Label();
                        boolean isValNull = (val instanceof IRLiteral lit && lit.getValue() == null)
                                || "null".equals(val.getTypeDescriptor());
                        if (isValNull) {
                            methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                            methodVisitor.visitJumpInsn(Opcodes.IFNONNULL, failVal);
                        } else {
                            methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                            methodVisitor.visitJumpInsn(Opcodes.IFNULL, failVal);
                            methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                            val.accept(this);
                            String exprType = val.getTypeDescriptor();
                            if (TypeChecker.isPrimitive(exprType)) {
                                emitConversion(exprType, OceanTypeSystem.OBJECT_DESC);
                            }
                            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false);
                            methodVisitor.visitJumpInsn(Opcodes.IFEQ, failVal);
                        }

                        if (c.getGuard() != null) {
                            c.getGuard().accept(this);
                            String gType = c.getGuard().getTypeDescriptor();
                            if (gType != null && !gType.equals("Z")) {
                                emitConversion(gType, "Z");
                            }
                            methodVisitor.visitJumpInsn(Opcodes.IFEQ, failVal);
                        }

                        methodVisitor.visitJumpInsn(Opcodes.GOTO, bodyLabel);
                        if (failVal != nextCaseLabel) {
                            methodVisitor.visitLabel(failVal);
                        }
                    }
                }

                methodVisitor.visitLabel(nextCaseLabel);
            }

            // No case matched -> jump to default
            methodVisitor.visitJumpInsn(Opcodes.GOTO, defaultLabel);

            // 3. Lay out case bodies
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                methodVisitor.visitLabel(bodyLabels[i]);
                c.getBody().accept(this);
                if (c.isArrow()) {
                    methodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
                }
            }

            methodVisitor.visitLabel(defaultLabel);
            if (node.getDefaultBlock() != null) {
                node.getDefaultBlock().accept(this);
            }
            methodVisitor.visitLabel(endLabel);

        } else if (isEnumSwitch) {
            // 1. Evaluate expression and call ordinal() directly -> O(1) TABLESWITCH / LOOKUPSWITCH
            node.getExpression().accept(this);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "ordinal", "()I", false);

            Map<Integer, Label> sortedCases = new TreeMap<>();
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                Label bodyLabel = bodyLabels[i];
                for (IRExpression valExpr : c.getValues()) {
                    int ordinal = resolveEnumOrdinal(switchType, valExpr);
                    if (ordinal >= 0) {
                        sortedCases.putIfAbsent(ordinal, bodyLabel);
                    }
                }
            }

            int[] keys = new int[sortedCases.size()];
            Label[] labels = new Label[sortedCases.size()];
            int idx = 0;
            for (Map.Entry<Integer, Label> entry : sortedCases.entrySet()) {
                keys[idx] = entry.getKey();
                labels[idx] = entry.getValue();
                idx++;
            }
            emitSwitchInsn(defaultLabel, keys, labels);

            // 2. Lay out case bodies in original source code order for fall-through
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                methodVisitor.visitLabel(bodyLabels[i]);
                c.getBody().accept(this);
                if (c.isArrow()) {
                    methodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
                }
            }

            methodVisitor.visitLabel(defaultLabel);
            if (node.getDefaultBlock() != null) {
                node.getDefaultBlock().accept(this);
            }
            methodVisitor.visitLabel(endLabel);

        } else if (isStringSwitch) {
            // 1. Evaluate expression and store in a temp variable
            node.getExpression().accept(this);
            int tempIndex = nextLocalIndex++;
            methodVisitor.visitVarInsn(Opcodes.ASTORE, tempIndex);

            // Build case string -> label mapping
            class StringCaseInfo {
                final String value;
                final int hashCode;
                final Label matchLabel;

                StringCaseInfo(String v, Label l) {
                    value = v;
                    hashCode = v.hashCode();
                    matchLabel = l;
                }
            }

            List<StringCaseInfo> stringCases = new ArrayList<>();
            Label nullCaseLabel = null; // Madde 9 fix: track explicit case null
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                Label bodyLabel = bodyLabels[i];
                for (IRExpression valExpr : c.getValues()) {
                    if (valExpr instanceof IRLiteral lit && lit.getValue() == null) {
                        nullCaseLabel = bodyLabel;
                    } else if (valExpr instanceof IRLiteral) {
                        Object val = ((IRLiteral) valExpr).getValue();
                        if (val instanceof String) {
                            stringCases.add(new StringCaseInfo((String) val, bodyLabel));
                        }
                    }
                }
            }

            // Group by hashCode (hash collisions possible)
            Map<Integer, List<StringCaseInfo>> byHash = new TreeMap<>();
            for (StringCaseInfo sci : stringCases) {
                byHash.computeIfAbsent(sci.hashCode, k -> new ArrayList<>()).add(sci);
            }

            // Emit: tempVar == null -> nullCaseLabel (if explicit case null) else defaultLabel; else hash switch
            methodVisitor.visitVarInsn(Opcodes.ALOAD, tempIndex);
            methodVisitor.visitJumpInsn(Opcodes.IFNULL, nullCaseLabel != null ? nullCaseLabel : defaultLabel);
            methodVisitor.visitVarInsn(Opcodes.ALOAD, tempIndex);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false);

            int[] hashKeys = new int[byHash.size()];
            Label[] hashLabels = new Label[byHash.size()];
            int idx = 0;
            for (Map.Entry<Integer, List<StringCaseInfo>> entry : byHash.entrySet()) {
                hashKeys[idx] = entry.getKey();
                hashLabels[idx] = new Label();
                idx++;
            }
            emitSwitchInsn(defaultLabel, hashKeys, hashLabels);

            // For each hash bucket, emit equals() checks
            idx = 0;
            for (Map.Entry<Integer, List<StringCaseInfo>> entry : byHash.entrySet()) {
                methodVisitor.visitLabel(hashLabels[idx]);
                for (StringCaseInfo sci : entry.getValue()) {
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, tempIndex);
                    methodVisitor.visitLdcInsn(sci.value);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                            "(Ljava/lang/Object;)Z", false);
                    methodVisitor.visitJumpInsn(Opcodes.IFNE, sci.matchLabel);
                }
                methodVisitor.visitJumpInsn(Opcodes.GOTO, defaultLabel);
                idx++;
            }

            // 2. Lay out case bodies in original source code order for fall-through
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                methodVisitor.visitLabel(bodyLabels[i]);
                c.getBody().accept(this);
                if (c.isArrow()) {
                    methodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
                }
            }

            methodVisitor.visitLabel(defaultLabel);
            if (node.getDefaultBlock() != null) {
                node.getDefaultBlock().accept(this);
            }
            methodVisitor.visitLabel(endLabel);

        } else {
            node.getExpression().accept(this);
            String actualSwitchType = switchType;
            if (actualSwitchType == null && node.getExpression() != null) {
                actualSwitchType = node.getExpression().getTypeDescriptor();
            }
            if (actualSwitchType == null) {
                actualSwitchType = OceanTypeSystem.OBJECT_DESC;
            }
            if (!actualSwitchType.equals("I")) {
                emitConversion(actualSwitchType, "I");
            }

            class CaseInfo {
                final int key;
                final Label label;

                CaseInfo(int k, Label l) {
                    key = k;
                    label = l;
                }
            }

            List<CaseInfo> sortedCases = new ArrayList<>();
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                Label bodyLabel = bodyLabels[i];
                for (IRExpression valExpr : c.getValues()) {
                    int key = getKey(valExpr);
                    sortedCases.add(new CaseInfo(key, bodyLabel));
                }
            }

            sortedCases.sort(Comparator.comparingInt(c -> c.key));

            int[] keys = new int[sortedCases.size()];
            Label[] labels = new Label[sortedCases.size()];
            for (int i = 0; i < sortedCases.size(); i++) {
                keys[i] = sortedCases.get(i).key;
                labels[i] = sortedCases.get(i).label;
            }
            emitSwitchInsn(defaultLabel, keys, labels);

            // 2. Lay out case bodies in original source code order for fall-through
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                methodVisitor.visitLabel(bodyLabels[i]);
                c.getBody().accept(this);
                if (c.isArrow()) {
                    methodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
                }
            }

            methodVisitor.visitLabel(defaultLabel);
            if (node.getDefaultBlock() != null) {
                node.getDefaultBlock().accept(this);
            }
            methodVisitor.visitLabel(endLabel);
        }

        loopTargets.pop();
        stopLabels.pop();
        exitScope();
    }

    private static int getKey(IRExpression valExpr) {
        int key = 0;
        if (valExpr instanceof IRLiteral) {
            Object val = ((IRLiteral) valExpr).getValue();
            switch (val) {
                case Integer integer -> key = integer;
                case Character character -> key = character;
                case Boolean b -> key = b ? 1 : 0;
                case String s when s.length() == 1 -> key = s.charAt(0);
                case null, default -> {
                    try {
                        if (val != null) {
                            key = Integer.parseInt(val.toString());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return key;
    }

    @Override
    public void visitDoWhile(IRDoWhileStatement node) {
        Label labelStart = new Label();
        Label labelEnd = new Label();
        Label labelCond = new Label();
        String lbl = currentPendingLabel;
        currentPendingLabel = null;
        loopTargets.push(new LoopTarget(lbl, labelEnd, labelCond, finallyBlocks.size()));
        stopLabels.push(labelEnd);
        skipLabels.push(labelCond);
        loopFinallySizes.push(finallyBlocks.size());

        methodVisitor.visitLabel(labelStart);
        node.getBody().accept(this);
        methodVisitor.visitLabel(labelCond);
        emitBranch(node.getCondition(), labelStart, true);

        methodVisitor.visitLabel(labelEnd);
        loopTargets.pop();
        loopFinallySizes.pop();
        stopLabels.pop();
        skipLabels.pop();
    }

    @Override
    public void visitThrow(IRThrowStatement node) {
        node.getExpression().accept(this);
        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Throwable");
        methodVisitor.visitInsn(Opcodes.ATHROW);
    }

    @Override
    public void visitVerify(IRVerifyStatement node) {
        Label passLabel = new Label();
        emitBranch(node.getCondition(), passLabel, true);

        methodVisitor.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError");
        methodVisitor.visitInsn(Opcodes.DUP);

        if (node.getDetailMessage() != null) {
            node.getDetailMessage().accept(this);
            String detailType = node.getDetailMessage().getTypeDescriptor();
            String ctorDesc;
            if (detailType == null) {
                ctorDesc = "(Ljava/lang/Object;)V";
            } else {
                String clean = cleanDesc(detailType);
                ctorDesc = switch (clean) {
                    case "Z" -> "(Z)V";
                    case "C" -> "(C)V";
                    case "I", "B", "S" -> "(I)V";
                    case "J" -> "(J)V";
                    case "F" -> "(F)V";
                    case "D" -> "(D)V";
                    default -> "(Ljava/lang/Object;)V";
                };
            }
            methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", ctorDesc, false);
        } else {
            methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false);
        }

        methodVisitor.visitInsn(Opcodes.ATHROW);
        methodVisitor.visitLabel(passLabel);
    }

    @Override
    public void visitTryCatch(IRTryCatchStatement node) {
        Label labelStart = new Label();
        Label labelEnd = new Label();
        Label labelFinally = new Label();
        Label labelExit = new Label();

        Map<IRTryCatchStatement.IRCatchClause, Label> catchLabels = new LinkedHashMap<>();
        Map<IRTryCatchStatement.IRCatchClause, Label> catchStartLabels = new LinkedHashMap<>();
        Map<IRTryCatchStatement.IRCatchClause, Label> catchEndLabels = new LinkedHashMap<>();

        for (IRTryCatchStatement.IRCatchClause clause : node.getCatchClauses()) {
            catchLabels.put(clause, new Label());
            catchStartLabels.put(clause, new Label());
            catchEndLabels.put(clause, new Label());
        }

        // 1. Try block
        Runnable tryFinallyAction = null;
        if (node.getFinallyBlock() != null) {
            tryFinallyAction = () -> node.getFinallyBlock().accept(this);
            finallyBlocks.push(tryFinallyAction);
        }

        TryScope tryScope = new TryScope();
        tryScope.currentStart = labelStart;
        tryScope.currentStartInsn = getCurrentInsnCount();
        tryScope.finallyBlock = tryFinallyAction;
        activeTryScopes.push(tryScope);

        methodVisitor.visitLabel(labelStart);
        methodVisitor.visitInsn(Opcodes.NOP);
        node.getTryBlock().accept(this);
        methodVisitor.visitLabel(labelEnd);

        tryScope.ranges.add(new TryRange(tryScope.currentStart, labelEnd, tryScope.currentStartInsn, getCurrentInsnCount()));
        activeTryScopes.pop();

        if (node.getFinallyBlock() != null) {
            finallyBlocks.pop();
            emitFinally(node); // BF6: use helper instead of inline accept
        }
        methodVisitor.visitJumpInsn(Opcodes.GOTO, labelExit);

        // 2. Catch clauses
        Map<IRTryCatchStatement.IRCatchClause, TryScope> catchScopes = new LinkedHashMap<>();
        for (IRTryCatchStatement.IRCatchClause clause : node.getCatchClauses()) {
            Label labelCatch = catchLabels.get(clause);
            Label catchStart = catchStartLabels.get(clause);
            Label catchEnd = catchEndLabels.get(clause);

            methodVisitor.visitLabel(labelCatch);
            methodVisitor.visitLabel(catchStart);

            enterScope();
            int excIndex = nextLocalIndex++;
            String excType;
            if (clause.exceptionTypes() != null && !clause.exceptionTypes().isEmpty()) {
                if (clause.exceptionTypes().size() == 1) {
                    excType = clause.exceptionTypes().getFirst();
                } else {
                    String common = TypeChecker.getCommonSuperClass(clause.exceptionTypes());
                    if (common != null && !common.isEmpty()) {
                        excType = (common.startsWith("L") && common.endsWith(";")) ? common : "L" + common.replace('.', '/') + ";";
                    } else {
                        excType = "Ljava/lang/Throwable;";
                    }
                }
            } else {
                excType = "Ljava/lang/Throwable;";
            }
            declareVariable(clause.exceptionVar(), excIndex, excType);
            methodVisitor.visitVarInsn(Opcodes.ASTORE, excIndex);

            Runnable catchFinallyAction = null;
            if (node.getFinallyBlock() != null) {
                catchFinallyAction = () -> node.getFinallyBlock().accept(this);
                finallyBlocks.push(catchFinallyAction);
            }

            TryScope catchScope = new TryScope();
            catchScope.currentStart = catchStart;
            catchScope.currentStartInsn = getCurrentInsnCount();
            catchScope.finallyBlock = catchFinallyAction;
            activeTryScopes.push(catchScope);
            catchScopes.put(clause, catchScope);

            clause.body().accept(this);

            if (node.getFinallyBlock() != null) {
                finallyBlocks.pop();
            }

            methodVisitor.visitLabel(catchEnd);
            catchScope.ranges.add(new TryRange(catchScope.currentStart, catchEnd, catchScope.currentStartInsn, getCurrentInsnCount()));
            activeTryScopes.pop();

            emitFinally(node); // BF6: use helper instead of inline accept
            exitScope();
            methodVisitor.visitJumpInsn(Opcodes.GOTO, labelExit);
        }

        // 3. Finally block (the catch-all handler)
        if (node.getFinallyBlock() != null) {
            methodVisitor.visitLabel(labelFinally);
            enterScope();
            int excTmp = nextLocalIndex++;
            methodVisitor.visitVarInsn(Opcodes.ASTORE, excTmp);
            emitFinally(node); // BF6: use helper instead of inline accept
            methodVisitor.visitVarInsn(Opcodes.ALOAD, excTmp);
            methodVisitor.visitInsn(Opcodes.ATHROW);
            exitScope();
        }

        methodVisitor.visitLabel(labelExit);

        // 4. Register exception handlers at the very end to ensure inner blocks are registered first
        for (TryRange r : tryScope.ranges) {
            if (!r.hasInstructions()) continue;
            for (IRTryCatchStatement.IRCatchClause clause : node.getCatchClauses()) {
                Label labelCatch = catchLabels.get(clause);
                for (String internalExcType : clause.exceptionTypes()) {
                    String cleanExc = TypeChecker.cleanDescriptor(internalExcType);
                    if (cleanExc.startsWith("L") && cleanExc.endsWith(";")) {
                        cleanExc = cleanExc.substring(1, cleanExc.length() - 1);
                    } else {
                        cleanExc = cleanExc.replace(".", "/");
                    }
                    if (!cleanExc.contains("/")) {
                        String resolved = OceanTypeSystem.resolveInternalClassName(cleanExc, currentClassName);
                        if (resolved != null && !resolved.isEmpty()) cleanExc = resolved;
                    }
                    methodVisitor.visitTryCatchBlock(r.start, r.end, labelCatch, cleanExc);
                }
            }

            if (node.getFinallyBlock() != null) {
                methodVisitor.visitTryCatchBlock(r.start, r.end, labelFinally, null);
            }
        }

        if (node.getFinallyBlock() != null) {
            for (IRTryCatchStatement.IRCatchClause clause : node.getCatchClauses()) {
                TryScope cScope = catchScopes.get(clause);
                if (cScope != null) {
                    for (TryRange cr : cScope.ranges) {
                        if (cr.hasInstructions()) {
                            methodVisitor.visitTryCatchBlock(cr.start, cr.end, labelFinally, null);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void visitLockStatement(IRLockStatement node) {
        enterScope();
        Label labelTryStart = new Label();
        Label labelTryEnd = new Label();
        Label labelCatchStart = new Label();
        Label labelExit = new Label();

        // 1. Evaluate lock expression
        node.getLockExpression().accept(this);

        // 2. Duplicate it and store it in a local variable slot
        methodVisitor.visitInsn(Opcodes.DUP);
        int lockVarIndex = nextLocalIndex++;
        methodVisitor.visitVarInsn(Opcodes.ASTORE, lockVarIndex);

        // 3. Enter monitor
        methodVisitor.visitInsn(Opcodes.MONITORENTER);

        // 4. Push monitor exit logic onto the finallyBlocks stack to handle returns
        Runnable lockFinally = () -> {
            methodVisitor.visitVarInsn(Opcodes.ALOAD, lockVarIndex);
            methodVisitor.visitInsn(Opcodes.MONITOREXIT);
        };
        finallyBlocks.push(lockFinally);

        // 5. Execute the lock body
        TryScope lockScope = new TryScope();
        lockScope.currentStart = labelTryStart;
        lockScope.currentStartInsn = getCurrentInsnCount();
        lockScope.finallyBlock = lockFinally;
        activeTryScopes.push(lockScope);

        methodVisitor.visitLabel(labelTryStart);
        node.getBody().accept(this);
        methodVisitor.visitLabel(labelTryEnd);

        lockScope.ranges.add(new TryRange(lockScope.currentStart, labelTryEnd, lockScope.currentStartInsn, getCurrentInsnCount()));
        activeTryScopes.pop();

        // 6. Pop finally logic and execute for normal exit path
        finallyBlocks.pop();
        methodVisitor.visitVarInsn(Opcodes.ALOAD, lockVarIndex);
        methodVisitor.visitInsn(Opcodes.MONITOREXIT);
        methodVisitor.visitJumpInsn(Opcodes.GOTO, labelExit);

        // 7. Catch block for exception path
        methodVisitor.visitLabel(labelCatchStart);
        methodVisitor.visitVarInsn(Opcodes.ALOAD, lockVarIndex);
        methodVisitor.visitInsn(Opcodes.MONITOREXIT);
        methodVisitor.visitInsn(Opcodes.ATHROW);

        methodVisitor.visitLabel(labelExit);
        exitScope();

        // 8. Register try-catch block after body so inner try-catches take precedence in exception table
        for (TryRange r : lockScope.ranges) {
            if (!r.hasInstructions()) continue;
            methodVisitor.visitTryCatchBlock(r.start, r.end, labelCatchStart, null);
        }
    }

    @Override
    public void visitLabeled(IRLabeledStatement node) {
        boolean isLoop = (node.getStatement() instanceof IRWhileStatement)
                || (node.getStatement() instanceof IRForStatement)
                || (node.getStatement() instanceof IRDoWhileStatement);
        if (isLoop) {
            String oldLabel = currentPendingLabel;
            currentPendingLabel = node.getLabel();
            if (node.getStatement() != null) {
                node.getStatement().accept(this);
            }
            currentPendingLabel = oldLabel;
        } else {
            Label blockEndLabel = new Label();
            loopTargets.push(new LoopTarget(node.getLabel(), blockEndLabel, null, finallyBlocks.size()));
            if (node.getStatement() != null) {
                node.getStatement().accept(this);
            }
            methodVisitor.visitLabel(blockEndLabel);
            loopTargets.pop();
        }
    }

    @Override
    public void visitStop(IRStopStatement node) {
        String targetLabel = node.getTargetLabel();
        LoopTarget target = null;
        if (targetLabel == null) {
            if (!loopTargets.isEmpty()) target = loopTargets.peek();
        } else {
            for (int i = loopTargets.size() - 1; i >= 0; i--) {
                if (targetLabel.equals(loopTargets.get(i).label)) {
                    target = loopTargets.get(i);
                    break;
                }
            }
        }
        if (target != null) {
            int targetFinallySize = target.finallySize;
            Set<Runnable> executing = new HashSet<>();
            for (int i = finallyBlocks.size() - 1; i >= targetFinallySize; i--) {
                executing.add(finallyBlocks.get(i));
            }
            splitTryScopesBeforeInlineFinally(executing);
            for (int i = finallyBlocks.size() - 1; i >= targetFinallySize; i--) {
                finallyBlocks.get(i).run();
            }
            resumeTryScopesAfterInlineFinally(executing);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, target.stopLabel);
        } else if (!stopLabels.isEmpty()) {
            int targetFinallySize = !loopFinallySizes.isEmpty() ? loopFinallySizes.peek() : 0;
            Set<Runnable> executing = new HashSet<>();
            for (int i = finallyBlocks.size() - 1; i >= targetFinallySize; i--) {
                executing.add(finallyBlocks.get(i));
            }
            splitTryScopesBeforeInlineFinally(executing);
            for (int i = finallyBlocks.size() - 1; i >= targetFinallySize; i--) {
                finallyBlocks.get(i).run();
            }
            resumeTryScopesAfterInlineFinally(executing);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, stopLabels.peek());
        }
    }

    @Override
    public void visitSkip(IRSkipStatement node) {
        String targetLabel = node.getTargetLabel();
        LoopTarget target = null;
        if (targetLabel == null) {
            if (!loopTargets.isEmpty()) target = loopTargets.peek();
        } else {
            for (int i = loopTargets.size() - 1; i >= 0; i--) {
                if (targetLabel.equals(loopTargets.get(i).label) && loopTargets.get(i).skipLabel != null) {
                    target = loopTargets.get(i);
                    break;
                }
            }
        }
        if (target != null && target.skipLabel != null) {
            int targetFinallySize = target.finallySize;
            Set<Runnable> executing = new HashSet<>();
            for (int i = finallyBlocks.size() - 1; i >= targetFinallySize; i--) {
                executing.add(finallyBlocks.get(i));
            }
            splitTryScopesBeforeInlineFinally(executing);
            for (int i = finallyBlocks.size() - 1; i >= targetFinallySize; i--) {
                finallyBlocks.get(i).run();
            }
            resumeTryScopesAfterInlineFinally(executing);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, target.skipLabel);
        } else if (!skipLabels.isEmpty()) {
            int targetFinallySize = !loopFinallySizes.isEmpty() ? loopFinallySizes.peek() : 0;
            Set<Runnable> executing = new HashSet<>();
            for (int i = finallyBlocks.size() - 1; i >= targetFinallySize; i--) {
                executing.add(finallyBlocks.get(i));
            }
            splitTryScopesBeforeInlineFinally(executing);
            for (int i = finallyBlocks.size() - 1; i >= targetFinallySize; i--) {
                finallyBlocks.get(i).run();
            }
            resumeTryScopesAfterInlineFinally(executing);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, skipLabels.peek());
        }
    }

    private void emitArithmetic(int iOp, int lOp, int fOp, int dOp, String type) {
        switch (type) {
            case "I", "Z", "B", "S", "C" -> methodVisitor.visitInsn(iOp);
            case "J" -> methodVisitor.visitInsn(lOp);
            case "F" -> methodVisitor.visitInsn(fOp);
            case "D" -> methodVisitor.visitInsn(dOp);
        }
    }

    private void emitBinaryOpDirect(IRBinaryOp.Op op, String arithmeticType) {
        if (TypeChecker.isBigDecimalType(arithmeticType)) {
            switch (op) {
                case ADD ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "add", "(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;", false);
                case SUB ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "subtract", "(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;", false);
                case MUL ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "multiply", "(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;", false);
                case DIV -> {
                    methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/math/MathContext", "DECIMAL128", "Ljava/math/MathContext;");
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "divide", "(Ljava/math/BigDecimal;Ljava/math/MathContext;)Ljava/math/BigDecimal;", false);
                }
                case MOD ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "remainder", "(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;", false);
                default -> {
                }
            }
            return;
        }

        boolean isGenericNumber = arithmeticType != null && arithmeticType.startsWith("L") && !TypeChecker.isStringType(arithmeticType);
        if (isGenericNumber) {
            switch (op) {
                case ADD ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "add", "(Ljava/lang/Number;Ljava/lang/Number;)Ljava/lang/Number;", false);
                case SUB ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "subtract", "(Ljava/lang/Number;Ljava/lang/Number;)Ljava/lang/Number;", false);
                case MUL ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "multiply", "(Ljava/lang/Number;Ljava/lang/Number;)Ljava/lang/Number;", false);
                case DIV ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "divide", "(Ljava/lang/Number;Ljava/lang/Number;)Ljava/lang/Number;", false);
                case MOD ->
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "mod", "(Ljava/lang/Number;Ljava/lang/Number;)Ljava/lang/Number;", false);
                default -> {
                }
            }
            return;
        }

        arithmeticType = (arithmeticType == null || TypeChecker.isObjectType(arithmeticType)) ? "I" : arithmeticType;
        switch (op) {
            case ADD -> emitArithmetic(Opcodes.IADD, Opcodes.LADD, Opcodes.FADD, Opcodes.DADD, arithmeticType);
            case SUB -> emitArithmetic(Opcodes.ISUB, Opcodes.LSUB, Opcodes.FSUB, Opcodes.DSUB, arithmeticType);
            case MUL -> emitArithmetic(Opcodes.IMUL, Opcodes.LMUL, Opcodes.FMUL, Opcodes.DMUL, arithmeticType);
            case DIV -> emitArithmetic(Opcodes.IDIV, Opcodes.LDIV, Opcodes.FDIV, Opcodes.DDIV, arithmeticType);
            case MOD -> emitArithmetic(Opcodes.IREM, Opcodes.LREM, Opcodes.FREM, Opcodes.DREM, arithmeticType);
            case LSHIFT -> emitArithmetic(Opcodes.ISHL, Opcodes.LSHL, 0, 0, arithmeticType);
            case RSHIFT -> emitArithmetic(Opcodes.ISHR, Opcodes.LSHR, 0, 0, arithmeticType);
            case URSHIFT -> emitArithmetic(Opcodes.IUSHR, Opcodes.LUSHR, 0, 0, arithmeticType);
            case BIT_AND -> emitArithmetic(Opcodes.IAND, Opcodes.LAND, 0, 0, arithmeticType);
            case BIT_OR -> emitArithmetic(Opcodes.IOR, Opcodes.LOR, 0, 0, arithmeticType);
            case BIT_XOR -> emitArithmetic(Opcodes.IXOR, Opcodes.LXOR, 0, 0, arithmeticType);
            default -> {
            }
        }
    }

    private void emitComparison(int jumpOp, int ifOp, String type) {
        Label labelTrue = new Label();
        Label labelEnd = new Label();

        switch (type) {
            case OceanTypeSystem.BIGDECIMAL_DESC -> {
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "compare", "(Ljava/lang/Object;Ljava/lang/Object;)I", false);
                methodVisitor.visitInsn(Opcodes.ICONST_0);
                methodVisitor.visitJumpInsn(jumpOp, labelTrue);
            }
            case "I", "Z", "B", "S", "C" -> methodVisitor.visitJumpInsn(jumpOp, labelTrue);
            case "J", "F", "D" -> {
                int cmpOp;
                if (type.equals("J")) {
                    cmpOp = Opcodes.LCMP;
                } else if (ifOp == Opcodes.IFGT || ifOp == Opcodes.IFGE) {
                    cmpOp = type.equals("F") ? Opcodes.FCMPL : Opcodes.DCMPL;
                } else {
                    cmpOp = type.equals("F") ? Opcodes.FCMPG : Opcodes.DCMPG;
                }
                methodVisitor.visitInsn(cmpOp);
                methodVisitor.visitJumpInsn(ifOp, labelTrue);
            }
            case OceanTypeSystem.STRING_DESC -> {
                if (jumpOp == Opcodes.IF_ICMPEQ || jumpOp == Opcodes.IF_ACMPEQ) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                    methodVisitor.visitJumpInsn(Opcodes.IFNE, labelTrue);
                } else if (jumpOp == Opcodes.IF_ICMPNE || jumpOp == Opcodes.IF_ACMPNE) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                    methodVisitor.visitJumpInsn(Opcodes.IFEQ, labelTrue);
                } else {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "compare", "(Ljava/lang/Object;Ljava/lang/Object;)I", false);
                    methodVisitor.visitInsn(Opcodes.ICONST_0);
                    methodVisitor.visitJumpInsn(jumpOp, labelTrue);
                }
            }
            default -> {
                // All other object types use reference comparison
                if (jumpOp == Opcodes.IF_ICMPEQ || jumpOp == Opcodes.IF_ACMPEQ) {
                    methodVisitor.visitJumpInsn(Opcodes.IF_ACMPEQ, labelTrue);
                } else if (jumpOp == Opcodes.IF_ICMPNE || jumpOp == Opcodes.IF_ACMPNE) {
                    methodVisitor.visitJumpInsn(Opcodes.IF_ACMPNE, labelTrue);
                } else {
                    reportError(currentClassNode, "Comparison operator not supported for object references: " + type);
                    methodVisitor.visitInsn(Opcodes.POP);
                    methodVisitor.visitInsn(Opcodes.POP);
                }
            }
        }

        methodVisitor.visitInsn(Opcodes.ICONST_0);
        methodVisitor.visitJumpInsn(Opcodes.GOTO, labelEnd);
        methodVisitor.visitLabel(labelTrue);
        methodVisitor.visitInsn(Opcodes.ICONST_1);
        methodVisitor.visitLabel(labelEnd);
    }

    private String getCommonType(String t1, String t2) {
        if (t1 == null || t2 == null) return OceanTypeSystem.OBJECT_DESC;
        String u1 = OceanTypeSystem.unbox(t1);
        if (u1 != null) t1 = u1;
        String u2 = OceanTypeSystem.unbox(t2);
        if (u2 != null) t2 = u2;

        if (t1.equals(t2)) return t1;
        if (TypeChecker.isBigDecimalType(t1) || TypeChecker.isBigDecimalType(t2))
            return OceanTypeSystem.BIGDECIMAL_DESC;
        if (t1.equals("D") || t2.equals("D")) return "D";
        if (t1.equals("F") || t2.equals("F")) return "F";
        if (t1.equals("J") || t2.equals("J")) return "J";
        if (t1.equals("Z") || t2.equals("Z")) {
            if (TypeChecker.isObjectType(t1) || TypeChecker.isObjectType(t2)) {
                return OceanTypeSystem.OBJECT_DESC;
            }
            return "Z";
        }
        if (t1.equals("I") || t1.equals("B") || t1.equals("S") || t1.equals("C") ||
                t2.equals("I") || t2.equals("B") || t2.equals("S") || t2.equals("C")) return "I";
        return OceanTypeSystem.OBJECT_DESC;
    }

    private void collectStringChain(IRExpression node, List<IRNode> chain) {
        if (node instanceof IRBinaryOp binOp) {
            if (binOp.getOperator() == IRBinaryOp.Op.ADD) {
                String leftT = binOp.getLeft().getTypeDescriptor();
                String rightT = binOp.getRight().getTypeDescriptor();
                if (TypeChecker.isStringType(leftT) || TypeChecker.isStringType(rightT)) {
                    collectStringChain(binOp.getLeft(), chain);
                    collectStringChain(binOp.getRight(), chain);
                    return;
                }
            }
        }
        chain.add(node);
    }

    private String getStackDescriptor(IRNode part) {
        if (!(part instanceof IRExpression expr)) return OceanTypeSystem.OBJECT_DESC;
        String desc = expr.getTypeDescriptor();
        if (expr instanceof IRMethodCall && !((IRMethodCall) expr).isSafeAccess()) {
            String methodDesc = ((IRMethodCall) expr).getDescriptor();
            if (methodDesc != null && methodDesc.contains(")")) {
                desc = methodDesc.substring(methodDesc.indexOf(")") + 1);
            }
        }
        desc = cleanDesc(desc);
        if (desc == null || desc.equals("V")) return OceanTypeSystem.OBJECT_DESC;
        if (desc.startsWith("[")) return desc;
        if (TypeChecker.isClassType(desc)) return desc;
        if (desc.length() == 1 && "IJFZDCBS".indexOf(desc.charAt(0)) >= 0) return desc;
        return OceanTypeSystem.OBJECT_DESC;
    }

    private static final int MAX_INDY_CONCAT_ARG_SLOTS = 190;

    private void emitStringConcatIndy(List<IRNode> parts) {
        if (parts == null || parts.isEmpty()) {
            methodVisitor.visitLdcInsn("");
            return;
        }

        int totalSlots = 0;
        for (IRNode part : parts) {
            if (!(part instanceof IRLiteral lit && lit.getValue() instanceof String)) {
                String d = getStackDescriptor(part);
                totalSlots += ("J".equals(d) || "D".equals(d)) ? 2 : 1;
            }
        }

        if (totalSlots <= MAX_INDY_CONCAT_ARG_SLOTS) {
            emitSingleIndyChunk(parts, false);
            return;
        }

        List<IRNode> chunk = new ArrayList<>();
        int currentSlots = 0;
        boolean hasAccOnStack = false;

        for (IRNode part : parts) {
            boolean isConstantString = (part instanceof IRLiteral lit && lit.getValue() instanceof String);
            int partSlots = 0;
            if (!isConstantString) {
                String d = getStackDescriptor(part);
                partSlots = ("J".equals(d) || "D".equals(d)) ? 2 : 1;
            }

            if (!chunk.isEmpty() && (currentSlots + partSlots > MAX_INDY_CONCAT_ARG_SLOTS)) {
                emitSingleIndyChunk(chunk, hasAccOnStack);
                hasAccOnStack = true;
                chunk.clear();
                currentSlots = 1; // Stack'teki bir önceki chunk sonucu String 1 slot kaplar
            }

            chunk.add(part);
            currentSlots += partSlots;
        }

        if (!chunk.isEmpty()) {
            emitSingleIndyChunk(chunk, hasAccOnStack);
        }
    }

    private void emitSingleIndyChunk(List<IRNode> chunkParts, boolean hasAccOnStack) {
        StringBuilder recipe = new StringBuilder();
        StringBuilder descriptor = new StringBuilder("(");
        List<IRNode> dynamicArgs = new ArrayList<>();

        if (hasAccOnStack) {
            recipe.append("\u0001");
            descriptor.append(OceanTypeSystem.STRING_DESC);
        }

        for (IRNode part : chunkParts) {
            if (part instanceof IRLiteral lit && lit.getValue() instanceof String val) {
                recipe.append(val.replace("\u0001", " ").replace("\u0002", " "));
            } else {
                dynamicArgs.add(part);
                recipe.append("\u0001");
                String d = getStackDescriptor(part);
                if (d != null && (d.startsWith("[") || OceanTypeSystem.OBJECT_DESC.equals(d))) {
                    descriptor.append(OceanTypeSystem.STRING_DESC);
                } else {
                    descriptor.append(d);
                }
            }
        }
        descriptor.append(")Ljava/lang/String;");

        for (IRNode arg : dynamicArgs) {
            arg.accept(this);
            if (arg instanceof IRExpression expr) {
                String desc = expr.getTypeDescriptor();
                if (desc != null && desc.equals("V")) {
                    methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                } else {
                    String d = getStackDescriptor(arg);
                    if (d != null && d.startsWith("[")) {
                        emitArrayToString(d);
                    } else if (OceanTypeSystem.OBJECT_DESC.equals(d)) {
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "smartToString", "(Ljava/lang/Object;)Ljava/lang/String;", false);
                    }
                }
            }
        }

        Handle bsm = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false
        );

        methodVisitor.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                descriptor.toString(),
                bsm,
                recipe.toString()
        );
    }

    private void emitStringConcat2() {
        Handle bsm = new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false
        );
        methodVisitor.visitInvokeDynamicInsn(
                "makeConcatWithConstants",
                "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                bsm,
                "\u0001\u0001"
        );
    }

    private void handleStringConcat(String leftDesc, String rightDesc) {
        // Right operand is on top, left is below it
        // 1. Convert right to string
        emitStringValueOf(rightDesc);
        // Stack: [Left, StringRight]

        // 2. Swap to convert left
        if (isDoubleSlot(leftDesc)) {
            // Swap 2-slot with 1-slot: [L1, L2, S] -> [S, L1, L2]
            methodVisitor.visitInsn(Opcodes.DUP_X2);
            methodVisitor.visitInsn(Opcodes.POP);
        } else {
            methodVisitor.visitInsn(Opcodes.SWAP);
        }

        // Stack: [StringRight, Left]
        // 3. Convert left to string
        emitStringValueOf(leftDesc);
        // Stack: [StringRight, StringLeft]

        // 4. Final swap and concat
        methodVisitor.visitInsn(Opcodes.SWAP);
        // Stack: [StringLeft, StringRight]
        emitStringConcat2();
    }

    private boolean isDoubleSlot(String desc) {
        return desc != null && (desc.equals("J") || desc.equals("D"));
    }

    private void emitStringValueOf(IRExpression expr) {
        if (expr != null) {
            String desc = expr.getTypeDescriptor();
            if (TypeChecker.isStringType(desc)) {
                return;
            }
            emitStringValueOf(desc);
        } else {
            emitStringValueOf(OceanTypeSystem.STRING_DESC);
        }
    }

    private void emitStringValueOf(String desc) {
        if (desc == null || "null".equals(desc)) desc = OceanTypeSystem.OBJECT_DESC;
        desc = cleanDesc(desc);
        if (desc == null || "null".equals(desc)) desc = OceanTypeSystem.OBJECT_DESC;
        if (TypeChecker.isStringType(desc)) return;
        if (desc.startsWith("[")) {
            emitArrayToString(desc);
            return;
        }
        if (OceanTypeSystem.OBJECT_DESC.equals(desc)) {
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "smartToString", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            return;
        }
        String valueOfDesc = switch (desc) {
            case "I", "B", "S" -> "(I)Ljava/lang/String;";
            case "C" -> "(C)Ljava/lang/String;";
            case "J" -> "(J)Ljava/lang/String;";
            case "F" -> "(F)Ljava/lang/String;";
            case "D" -> "(D)Ljava/lang/String;";
            case "Z" -> "(Z)Ljava/lang/String;";
            default -> "(Ljava/lang/Object;)Ljava/lang/String;";
        };

        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", valueOfDesc, false);
    }


    @Override
    public void visitMethodCall(IRMethodCall node) {
        boolean oldContext = isStatementContext;
        isStatementContext = false;
        try {
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG] Emitter visiting method call: " + node.getName() + " super: " + node.isSuperCall() + " static: " + node.isStatic() + " receiver: " + (node.getReceiver() != null));
        }
        if (!node.isStatic()) {
            if (node.getReceiver() != null) {
                node.getReceiver().accept(this);
                if (node.isSafeAccess()) {
                    Label isNull = new Label();
                    Label end = new Label();
                    methodVisitor.visitInsn(Opcodes.DUP);
                    methodVisitor.visitJumpInsn(Opcodes.IFNULL, isNull);

                    String recType = getReceiverType(node.getReceiver());
                    String ownerType = OceanTypeSystem.wrapObjectType(node.getOwner().replace(".", "/"));
                    if (recType != null && !recType.equals(ownerType) && !TypeChecker.isObjectType(ownerType) && !recType.startsWith("[")) {
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, node.getOwner().replace(".", "/"));
                    }

                    // Not null
                    String desc = TypeChecker.cleanDescriptor(node.getDescriptor());
                    List<String> paramTypes = parseParameterTypes(desc);
                    for (int i = 0; i < node.getArguments().size(); i++) {
                        IRExpression arg = node.getArguments().get(i);
                        arg.accept(this);
                        if (i < paramTypes.size()) {
                            String argActualType = getArgActualType(arg);
                            emitConversion(argActualType, paramTypes.get(i));
                        }
                    }

                    boolean isInterface = isInterface(node.getOwner());
                    boolean isObjMethod = isObjectMethod(node.getName(), desc);
                    String callOwner = (isInterface && isObjMethod) ? "java/lang/Object" : node.getOwner().replace(".", "/");
                    boolean targetIsItf = isInterface && !isObjMethod;
                    // JVM spec: interface super default call must have itf=true
                    boolean invokeItf = node.isSuperCall() ? (isInterface && !isObjMethod) : targetIsItf;
                    int op = node.isStatic() ? Opcodes.INVOKESTATIC : (node.isSuperCall() ? Opcodes.INVOKESPECIAL : (targetIsItf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL));

                    methodVisitor.visitMethodInsn(op, callOwner, node.getName(), desc, invokeItf);
                    String actualRetType = desc.substring(desc.lastIndexOf(')') + 1);
                    String origRetDesc = node.getOriginalTypeDescriptor();
                    String fromRet = (origRetDesc != null && !origRetDesc.isEmpty()) ? origRetDesc : actualRetType;
                    emitConversion(fromRet, node.getTypeDescriptor());
                    methodVisitor.visitJumpInsn(Opcodes.GOTO, end);

                    // Is null — Madde 6 fix: push correct default for return type (not always ACONST_NULL)
                    methodVisitor.visitLabel(isNull);
                    methodVisitor.visitInsn(Opcodes.POP);
                    // Push null or default result
                    String retType = cleanDesc(node.getTypeDescriptor());
                    if (!"V".equals(retType)) {
                        if (TypeChecker.isPrimitive(retType)) {
                            // Safe access on primitive-returning method: box the null path to match object frame
                            emitConversion(OceanTypeSystem.OBJECT_DESC, retType);
                        } else {
                            methodVisitor.visitInsn(Opcodes.ACONST_NULL);
                        }
                    }

                    methodVisitor.visitLabel(end);
                    return;
                } else {
                    String recType = getReceiverType(node.getReceiver());
                    String ownerType = OceanTypeSystem.wrapObjectType(node.getOwner().replace(".", "/"));
                    if (!node.isSuperCall() && !node.getName().equals("<init>") && recType != null && !recType.equals(ownerType) && !TypeChecker.isObjectType(ownerType) && !recType.startsWith("[")) {
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, node.getOwner().replace(".", "/"));
                    }
                }
            } else {
                methodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            }
        } else {
            if (node.getReceiver() != null) {
                node.getReceiver().accept(this);
                String recDesc = TypeChecker.cleanDescriptor(node.getReceiver().getTypeDescriptor());
                if ("J".equals(recDesc) || "D".equals(recDesc)) {
                    methodVisitor.visitInsn(Opcodes.POP2);
                } else if (!"V".equals(recDesc)) {
                    methodVisitor.visitInsn(Opcodes.POP);
                }
            }
        }
        // Convert arguments to parameter types
        String desc = TypeChecker.cleanDescriptor(node.getDescriptor());
        List<String> paramTypes = parseParameterTypes(desc);
        if (node.getName().equals("main") && paramTypes.size() == 1 && paramTypes.getFirst().equals("[Ljava/lang/String;") && node.getArguments().isEmpty()) {
            methodVisitor.visitInsn(Opcodes.ICONST_0);
            methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
        } else {
            for (int i = 0; i < node.getArguments().size(); i++) {
                IRExpression arg = node.getArguments().get(i);
                arg.accept(this);
                if (i < paramTypes.size()) {
                    // If arg is a generic-refined IRMethodCall, use the JVM erased type for conversion.
                    // The refined typeDescriptor is only for type inference, not for actual stack value.
                    String argActualType = getArgActualType(arg);
                    emitConversion(argActualType, paramTypes.get(i));
                }
            }
        }

        boolean isInterface = isInterface(node.getOwner());
        boolean isObjMethod = isObjectMethod(node.getName(), desc);
        String owner = (isInterface && isObjMethod) ? "java/lang/Object" : node.getOwner().replace(".", "/");
        boolean targetIsItf = isInterface && !isObjMethod;
        // JVM spec: INVOKESPECIAL targeting an interface method (e.g. super default call)
        // must carry isInterface=true, otherwise JVM rejects with VerifyError/IncompatibleClassChangeError.
        boolean invokeItf = node.isSuperCall() ? (isInterface && !isObjMethod) : targetIsItf;
        int op = node.isStatic() ? Opcodes.INVOKESTATIC : (node.isSuperCall() ? Opcodes.INVOKESPECIAL : (targetIsItf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL));
        methodVisitor.visitMethodInsn(op, owner, node.getName(), desc, invokeItf);
        String actualRetType = desc.substring(desc.lastIndexOf(')') + 1);
        String origRetDesc = node.getOriginalTypeDescriptor();
        String fromRet = (origRetDesc != null && !origRetDesc.isEmpty()) ? origRetDesc : actualRetType;
        emitConversion(fromRet, node.getTypeDescriptor());
        } finally {
            isStatementContext = oldContext;
        }
    }

    private static String getArgActualType(IRExpression arg) {
        String argActualType = arg.getTypeDescriptor();
        if (arg instanceof IRMethodCall mcArg) {
            if (!mcArg.isSafeAccess()) {
                String origType = mcArg.getOriginalTypeDescriptor();
                if (origType != null && !origType.equals(argActualType)) {
                    argActualType = origType;
                }
            }
        }
        return argActualType;
    }

    private static boolean isObjectMethod(String name, String desc) {
        if (name == null || desc == null) return false;
        return switch (name) {
            case "toString" -> desc.equals("()Ljava/lang/String;");
            case "hashCode" -> desc.equals("()I");
            case "equals" -> desc.equals("(Ljava/lang/Object;)Z");
            case "getClass" -> desc.equals("()Ljava/lang/Class;");
            case "notify", "notifyAll" -> desc.equals("()V");
            case "wait" -> desc.equals("()V") || desc.equals("(J)V") || desc.equals("(JI)V");
            default -> false;
        };
    }

    private boolean isInterface(String owner) {
        if (owner == null) return false;
        String normalized = cleanDesc(owner).replace(".", "/");
        if (TypeChecker.isClassType(normalized)) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return interfaceCache.computeIfAbsent(normalized, k -> {
            if (CompilerRegistry.globalIsInterfaceSet.contains(k) ||
                    CompilerRegistry.globalIsInterfaceSet.contains(k.replace('/', '.'))) {
                return true;
            }
            if (CompilerRegistry.globalMethodRegistry.containsKey(k) ||
                    CompilerRegistry.globalSuperClassRegistry.containsKey(k)) {
                return false;
            }
            try {
                Class<?> cls = OceanTypeSystem.forName(k.replace("/", "."));
                return cls != null && cls.isInterface();
            } catch (Throwable ignored) {
                return false;
            }
        });
    }

    private List<String> parseParameterTypes(String desc) {
        List<String> types = new ArrayList<>();
        if (desc == null || !desc.startsWith("(") || !desc.contains(")")) return types;
        desc = TypeChecker.cleanDescriptor(desc);
        String params = desc.substring(1, desc.lastIndexOf(')'));
        int i = 0;
        while (i < params.length()) {
            char c = params.charAt(i);
            if (c == 'L') {
                int end = params.indexOf(';', i);
                if (end == -1) break;
                types.add(params.substring(i, end + 1));
                i = end + 1;
            } else if (c == '[') {
                int start = i;
                while (params.charAt(i) == '[') i++;
                if (params.charAt(i) == 'L') {
                    int end = params.indexOf(';', i);
                    if (end == -1) break;
                    types.add(params.substring(start, end + 1));
                    i = end + 1;
                } else {
                    types.add(params.substring(start, i + 1));
                    i++;
                }
            } else {
                types.add(String.valueOf(c));
                i++;
            }
        }
        return types;
    }

    private void initializePatternVariables(IRSwitchPattern pat) {
        if (pat == null || pat.getKind() == IRSwitchPattern.Kind.UNNAMED) return;
        if (pat.getVariableName() != null && !pat.getVariableName().equals("_") && pat.getTypeDescriptor() != null) {
            int varIdx = getVariableIndex(pat.getVariableName());
            if (varIdx != -1) {
                String typeDesc = pat.getTypeDescriptor();
                emitPushDefaultValue(methodVisitor, typeDesc);
                emitStore(typeDesc, varIdx);
            }
        }
        if (pat.getKind() == IRSwitchPattern.Kind.RECORD && pat.getNestedPatterns() != null) {
            for (IRSwitchPattern nested : pat.getNestedPatterns()) {
                initializePatternVariables(nested);
            }
        }
    }

    @Override
    public void visitInstanceof(IRInstanceof node) {
        if (node.getPattern() != null) {
            Label labelFalse = new Label();
            Label labelEnd = new Label();

            node.getExpression().accept(this);
            String rawTargetType = node.getExpression().getTypeDescriptor();
            if (TypeChecker.isPrimitive(rawTargetType)) {
                emitConversion(rawTargetType, OceanTypeSystem.OBJECT_DESC);
            }
            int targetIndex = nextLocalIndex++;
            methodVisitor.visitVarInsn(Opcodes.ASTORE, targetIndex);

            emitPatternCheck(node.getPattern(), targetIndex, labelFalse);

            // True case
            methodVisitor.visitInsn(Opcodes.ICONST_1);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, labelEnd);

            // False case
            methodVisitor.visitLabel(labelFalse);
            initializePatternVariables(node.getPattern());
            methodVisitor.visitInsn(Opcodes.ICONST_0);

            methodVisitor.visitLabel(labelEnd);
            return;
        }

        Label labelFalse = new Label();
        Label labelEnd = new Label();
        node.getExpression().accept(this);
        String rawTargetDesc = node.getTargetType();
        String specialized = TypeChecker.getSpecializedPrimitiveListClass(rawTargetDesc);
        if (specialized != null) {
            rawTargetDesc = specialized;
        }
        String targetType = TypeChecker.getInternalName(rawTargetDesc);
        if (targetType != null) {
            targetType = targetType.replace(".", "/");
        }

        if (TypeChecker.isPrimitive(targetType)) {
            String rawTargetType = node.getExpression().getTypeDescriptor();
            if (TypeChecker.isPrimitive(rawTargetType)) {
                emitConversion(rawTargetType, OceanTypeSystem.OBJECT_DESC);
            }
            String matchMethod = getPrimitiveMatchMethod(targetType);
            String extractMethod = getPrimitiveExtractMethod(targetType);
            String retDesc = TypeChecker.cleanDescriptor(targetType);

            int varIdx = getVariableIndex(node.getPatternVarName());

            if (varIdx == -1) {
                varIdx = nextLocalIndex;
                if (isDoubleOrLong(targetType)) {
                    nextLocalIndex += 2;
                } else {
                    nextLocalIndex += 1;
                }
                declareVariable(node.getPatternVarName(), varIdx, targetType);
            }

            if (node.getPatternVarName() != null) {
                int targetIdx = nextLocalIndex++;

                methodVisitor.visitVarInsn(Opcodes.ASTORE, targetIdx);
                methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIdx);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", matchMethod, "(Ljava/lang/Object;)Z", false);
                methodVisitor.visitJumpInsn(Opcodes.IFEQ, labelFalse);

                // true
                methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIdx);
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", extractMethod, "(Ljava/lang/Object;)" + retDesc, false);
                emitStore(targetType, varIdx);

                methodVisitor.visitInsn(Opcodes.ICONST_1);
                methodVisitor.visitJumpInsn(Opcodes.GOTO, labelEnd);

                // false
                methodVisitor.visitLabel(labelFalse);
                emitPushDefaultValue(methodVisitor, targetType);
                emitStore(targetType, varIdx);

                methodVisitor.visitInsn(Opcodes.ICONST_0);

                methodVisitor.visitLabel(labelEnd);
            } else {
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", matchMethod, "(Ljava/lang/Object;)Z", false);
            }
            return;
        }

        String cleanTargetType = targetType;
        if (cleanTargetType != null) {
            cleanTargetType = cleanTargetType.replace(".", "/");
            if (cleanTargetType.startsWith("L") && cleanTargetType.endsWith(";")) {
                cleanTargetType = cleanTargetType.substring(1, cleanTargetType.length() - 1);
            }
            if (!cleanTargetType.contains("/") && !cleanTargetType.startsWith("[")) {
                String resolved = OceanTypeSystem.resolveInternalClassName(cleanTargetType, currentClassName);
                if (resolved != null && !resolved.isEmpty()) cleanTargetType = resolved;
            }
        } else {
            cleanTargetType = "java/lang/Object";
        }

        if (node.getPatternVarName() != null) {
            int varIdx = getVariableIndex(node.getPatternVarName());
            if (varIdx == -1) {
                varIdx = nextLocalIndex++;
                declareVariable(node.getPatternVarName(), varIdx, targetType);
            }
            int tempExprIdx = nextLocalIndex++;
            methodVisitor.visitVarInsn(Opcodes.ASTORE, tempExprIdx);
            methodVisitor.visitVarInsn(Opcodes.ALOAD, tempExprIdx);
            methodVisitor.visitTypeInsn(Opcodes.INSTANCEOF, cleanTargetType);
            methodVisitor.visitInsn(Opcodes.DUP);
            methodVisitor.visitJumpInsn(Opcodes.IFEQ, labelFalse);

            // True case
            methodVisitor.visitVarInsn(Opcodes.ALOAD, tempExprIdx);
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, cleanTargetType);
            methodVisitor.visitVarInsn(Opcodes.ASTORE, varIdx);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, labelEnd);

            // False case
            methodVisitor.visitLabel(labelFalse);
            methodVisitor.visitInsn(Opcodes.ACONST_NULL);
            methodVisitor.visitVarInsn(Opcodes.ASTORE, varIdx);

            methodVisitor.visitLabel(labelEnd);
        } else {
            methodVisitor.visitTypeInsn(Opcodes.INSTANCEOF, cleanTargetType);
        }
    }

    private void emitArrayToString(String desc) {
        if (desc == null) {
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "smartToString", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            return;
        }
        desc = cleanDesc(desc);
        if (desc.startsWith("[[")) {
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "deepToString", "([Ljava/lang/Object;)Ljava/lang/String;", false);
        } else if (desc.startsWith("[L")) {
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
            methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "deepToString", "([Ljava/lang/Object;)Ljava/lang/String;", false);
        } else {
            String methodDesc = switch (desc) {
                case "[I" -> "([I)Ljava/lang/String;";
                case "[J" -> "([J)Ljava/lang/String;";
                case "[D" -> "([D)Ljava/lang/String;";
                case "[F" -> "([F)Ljava/lang/String;";
                case "[Z" -> "([Z)Ljava/lang/String;";
                case "[B" -> "([B)Ljava/lang/String;";
                case "[C" -> "([C)Ljava/lang/String;";
                case "[S" -> "([S)Ljava/lang/String;";
                default -> null;
            };
            if (methodDesc != null) {
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "toString", methodDesc, false);
            } else {
                methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "smartToString", "(Ljava/lang/Object;)Ljava/lang/String;", false);
            }
        }
    }

    @Override
    public void visitOceanOutput(IROceanOutput node) {
        boolean oldContext = isStatementContext;
        isStatementContext = false;
        try {
            if (node.getArguments().isEmpty()) {
                methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "()V", false);
            } else if (node.getArguments().size() == 1) {
                IRExpression arg = node.getArguments().getFirst();
                methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                arg.accept(this);
                String desc = getArgActualType(arg);
                if (desc != null && desc.startsWith("[")) {
                    emitArrayToString(desc);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                } else if (OceanTypeSystem.OBJECT_DESC.equals(desc)) {
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "smartToString", "(Ljava/lang/Object;)Ljava/lang/String;", false);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                } else {
                    String methodDesc = OceanTypeSystem.getBaseMethodDescriptor(desc);
                    if ("(Ljava/lang/Object;)V".equals(methodDesc) && TypeChecker.isPrimitive(desc)) {
                        emitConversion(desc, OceanTypeSystem.OBJECT_DESC);
                    }
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", methodDesc, false);
                }
            } else {
                List<IRNode> parts = new ArrayList<>();
                for (int i = 0; i < node.getArguments().size(); i++) {
                    if (i > 0) parts.add(new IRLiteral(" ", OceanTypeSystem.STRING_DESC));
                    parts.add(node.getArguments().get(i));
                }
                methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                emitStringConcatIndy(parts);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
            }
        } finally {
            isStatementContext = oldContext;
        }
    }

    @Override
    public void visitInterpolatedString(IRInterpolatedString node) {
        emitStringConcatIndy(node.getParts());
    }

    @Override
    public void visitArrayAccess(IRArrayAccess node) {
        node.getArray().accept(this);
        String desc = node.getTypeDescriptor();
        if (node.getArray() instanceof IRArrayAccess) {
            methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "[" + desc);
        } else {
            String arrayType = getReceiverType(node.getArray());
            if (arrayType != null && (TypeChecker.isObjectType(arrayType) || arrayType.equals("java/lang/Object"))) {
                methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "[" + desc);
            }
        }
        node.getIndex().accept(this);
        emitConversion(node.getIndex().getTypeDescriptor(), "I");

        switch (desc) {
            case "I" -> methodVisitor.visitInsn(Opcodes.IALOAD);
            case "J" -> methodVisitor.visitInsn(Opcodes.LALOAD);
            case "F" -> methodVisitor.visitInsn(Opcodes.FALOAD);
            case "D" -> methodVisitor.visitInsn(Opcodes.DALOAD);
            case "B", "Z" -> methodVisitor.visitInsn(Opcodes.BALOAD);
            case "C" -> methodVisitor.visitInsn(Opcodes.CALOAD);
            case "S" -> methodVisitor.visitInsn(Opcodes.SALOAD);
            default -> methodVisitor.visitInsn(Opcodes.AALOAD);
        }
    }

    private void emitSpecializedListInit(String className, String addDesc, IRArrayLiteral node) {
        methodVisitor.visitTypeInsn(Opcodes.NEW, className);
        methodVisitor.visitInsn(Opcodes.DUP);
        emitIntConst(node.getElements().size());
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, className, "<init>", "(I)V", false);
        String paramType = addDesc.substring(1, addDesc.indexOf(')'));
        for (IRExpression el : node.getElements()) {
            methodVisitor.visitInsn(Opcodes.DUP);
            el.accept(this);
            emitConversion(el.getTypeDescriptor(), paramType);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "add", addDesc, false);
            methodVisitor.visitInsn(Opcodes.POP);
        }
    }

    @Override
    public void visitArrayLiteral(IRArrayLiteral node) {
        String typeDesc = node.getTypeDescriptor();
        String cleanType = TypeChecker.cleanDescriptor(typeDesc);
        if ("Locean/stdlib/OceanList;".equals(cleanType)) {
            if (!node.getElements().isEmpty()) {
                boolean allInts = true, allLongs = true, allDoubles = true, allFloats = true;
                boolean allBools = true, allBytes = true, allShorts = true, allChars = true;
                for (IRExpression el : node.getElements()) {
                    String desc = el.getTypeDescriptor();
                    if (!"I".equals(desc)) allInts = false;
                    if (!"J".equals(desc)) allLongs = false;
                    if (!"D".equals(desc)) allDoubles = false;
                    if (!"F".equals(desc)) allFloats = false;
                    if (!"Z".equals(desc)) allBools = false;
                    if (!"B".equals(desc)) allBytes = false;
                    if (!"S".equals(desc)) allShorts = false;
                    if (!"C".equals(desc)) allChars = false;
                }
                if (allBools) {
                    emitSpecializedListInit("ocean/stdlib/OceanBooleanList", "(Z)Z", node);
                    return;
                }
                if (allChars) {
                    emitSpecializedListInit("ocean/stdlib/OceanCharList", "(C)Z", node);
                    return;
                }
                if (allBytes) {
                    emitSpecializedListInit("ocean/stdlib/OceanByteList", "(B)Z", node);
                    return;
                }
                if (allShorts) {
                    emitSpecializedListInit("ocean/stdlib/OceanShortList", "(S)Z", node);
                    return;
                }
                if (allInts) {
                    emitSpecializedListInit("ocean/stdlib/OceanIntList", "(I)Z", node);
                    return;
                }
                if (allLongs) {
                    emitSpecializedListInit("ocean/stdlib/OceanLongList", "(J)Z", node);
                    return;
                }
                if (allFloats) {
                    emitSpecializedListInit("ocean/stdlib/OceanFloatList", "(F)Z", node);
                    return;
                }
                if (allDoubles) {
                    emitSpecializedListInit("ocean/stdlib/OceanDoubleList", "(D)Z", node);
                    return;
                }
            }

            methodVisitor.visitTypeInsn(Opcodes.NEW, "ocean/stdlib/OceanList");
            methodVisitor.visitInsn(Opcodes.DUP);
            emitIntConst(node.getElements().size());
            methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "ocean/stdlib/OceanList", "<init>", "(I)V", false);
            for (IRExpression el : node.getElements()) {
                methodVisitor.visitInsn(Opcodes.DUP);
                el.accept(this);
                emitConversion(el.getTypeDescriptor(), OceanTypeSystem.OBJECT_DESC);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "ocean/stdlib/OceanList", "add", "(Ljava/lang/Object;)Z", false);
                methodVisitor.visitInsn(Opcodes.POP);
            }
            return;
        }
        if ("Locean/stdlib/OceanSet;".equals(cleanType)) {
            methodVisitor.visitTypeInsn(Opcodes.NEW, "ocean/stdlib/OceanSet");
            methodVisitor.visitInsn(Opcodes.DUP);
            emitIntConst(node.getElements().size());
            methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "ocean/stdlib/OceanSet", "<init>", "(I)V", false);
            for (IRExpression el : node.getElements()) {
                methodVisitor.visitInsn(Opcodes.DUP);
                el.accept(this);
                emitConversion(el.getTypeDescriptor(), OceanTypeSystem.OBJECT_DESC);
                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "ocean/stdlib/OceanSet", "add", "(Ljava/lang/Object;)Z", false);
                methodVisitor.visitInsn(Opcodes.POP);
            }
            return;
        }
        if ("Locean/stdlib/OceanMap;".equals(cleanType)) {
            methodVisitor.visitTypeInsn(Opcodes.NEW, "ocean/stdlib/OceanMap");
            methodVisitor.visitInsn(Opcodes.DUP);
            List<IRExpression> el = node.getElements();
            emitIntConst(el.size() / 2);
            methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "ocean/stdlib/OceanMap", "<init>", "(I)V", false);
            for (int i = 0; i < el.size() - 1; i += 2) {
                methodVisitor.visitInsn(Opcodes.DUP);

                IRExpression key = el.get(i);
                key.accept(this);
                emitConversion(key.getTypeDescriptor(), OceanTypeSystem.OBJECT_DESC);

                IRExpression val = el.get(i + 1);
                val.accept(this);
                emitConversion(val.getTypeDescriptor(), OceanTypeSystem.OBJECT_DESC);

                methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "ocean/stdlib/OceanMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                methodVisitor.visitInsn(Opcodes.POP);
            }
            return;
        }

        String elementType = (typeDesc != null && typeDesc.length() > 1 && typeDesc.startsWith("[")) ?
                typeDesc.substring(1) : (typeDesc != null ? typeDesc : OceanTypeSystem.OBJECT_DESC);

        // Push size
        emitIntConst(node.getElements().size());

        // NEWARRAY or ANEWARRAY
        switch (elementType) {
            case "I" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
            case "Z" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN);
            case "J" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG);
            case "F" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
            case "D" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE);
            case "B" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
            case "C" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_CHAR);
            case "S" -> methodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_SHORT);
            default -> {
                String internalName = resolveArrayElementInternalName(elementType);
                methodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, internalName);
            }
        }

        // Fill elements
        for (int i = 0; i < node.getElements().size(); i++) {
            methodVisitor.visitInsn(Opcodes.DUP);
            emitIntConst(i);
            IRExpression element = node.getElements().get(i);
            element.accept(this);
            emitConversion(element.getTypeDescriptor(), elementType);

            switch (elementType) {
                case "I" -> methodVisitor.visitInsn(Opcodes.IASTORE);
                case "J" -> methodVisitor.visitInsn(Opcodes.LASTORE);
                case "F" -> methodVisitor.visitInsn(Opcodes.FASTORE);
                case "D" -> methodVisitor.visitInsn(Opcodes.DASTORE);
                case "B", "Z" -> methodVisitor.visitInsn(Opcodes.BASTORE);
                case "C" -> methodVisitor.visitInsn(Opcodes.CASTORE);
                case "S" -> methodVisitor.visitInsn(Opcodes.SASTORE);
                default -> methodVisitor.visitInsn(Opcodes.AASTORE);
            }
        }
    }

    private void emitBranch(IRExpression condition, Label target, boolean jumpIfTrue) {
        if (condition instanceof IRLiteral) {
            Object val = ((IRLiteral) condition).getValue();
            if (val instanceof Boolean || val instanceof Integer) {
                boolean boolVal = val instanceof Boolean ? (Boolean) val : ((Integer) val != 0);
                if (boolVal == jumpIfTrue) {
                    methodVisitor.visitJumpInsn(Opcodes.GOTO, target);
                }
                return;
            }
        }

        if (condition instanceof IRUnaryOp unary) {
            if (unary.getOperator() == IRUnaryOp.Op.NOT) {
                emitBranch(unary.getExpression(), target, !jumpIfTrue);
                return;
            }
        }

        if (condition instanceof IRBinaryOp binary) {
            IRBinaryOp.Op op = binary.getOperator();
            if (op == IRBinaryOp.Op.AND) {
                if (jumpIfTrue) {
                    Label labelFalse = new Label();
                    emitBranch(binary.getLeft(), labelFalse, false);
                    emitBranch(binary.getRight(), target, true);
                    methodVisitor.visitLabel(labelFalse);
                } else {
                    emitBranch(binary.getLeft(), target, false);
                    emitBranch(binary.getRight(), target, false);
                }
                return;
            } else if (op == IRBinaryOp.Op.OR) {
                if (jumpIfTrue) {
                    emitBranch(binary.getLeft(), target, true);
                    emitBranch(binary.getRight(), target, true);
                } else {
                    Label labelTrue = new Label();
                    emitBranch(binary.getLeft(), labelTrue, true);
                    emitBranch(binary.getRight(), target, false);
                    methodVisitor.visitLabel(labelTrue);
                }
                return;
            }

            if (op == IRBinaryOp.Op.EQ || op == IRBinaryOp.Op.NE ||
                    op == IRBinaryOp.Op.LT || op == IRBinaryOp.Op.LE ||
                    op == IRBinaryOp.Op.GT || op == IRBinaryOp.Op.GE) {

                String operandType = getCommonType(binary.getLeft().getTypeDescriptor(), binary.getRight().getTypeDescriptor());

                binary.getLeft().accept(this);
                emitConversion(binary.getLeft().getTypeDescriptor(), operandType);
                binary.getRight().accept(this);
                emitConversion(binary.getRight().getTypeDescriptor(), operandType);

                int jumpOp;
                int ifOp;

                if (jumpIfTrue) {
                    ifOp = switch (op) {
                        case EQ -> {
                            jumpOp = Opcodes.IF_ICMPEQ;
                            yield Opcodes.IFEQ;
                        }
                        case NE -> {
                            jumpOp = Opcodes.IF_ICMPNE;
                            yield Opcodes.IFNE;
                        }
                        case LT -> {
                            jumpOp = Opcodes.IF_ICMPLT;
                            yield Opcodes.IFLT;
                        }
                        case LE -> {
                            jumpOp = Opcodes.IF_ICMPLE;
                            yield Opcodes.IFLE;
                        }
                        case GT -> {
                            jumpOp = Opcodes.IF_ICMPGT;
                            yield Opcodes.IFGT;
                        }
                        case GE -> {
                            jumpOp = Opcodes.IF_ICMPGE;
                            yield Opcodes.IFGE;
                        }
                        default -> throw new IllegalArgumentException("Unknown comparison operator: " + op);
                    };
                } else {
                    ifOp = switch (op) {
                        case EQ -> {
                            jumpOp = Opcodes.IF_ICMPNE;
                            yield Opcodes.IFNE;
                        }
                        case NE -> {
                            jumpOp = Opcodes.IF_ICMPEQ;
                            yield Opcodes.IFEQ;
                        }
                        case LT -> {
                            jumpOp = Opcodes.IF_ICMPGE;
                            yield Opcodes.IFGE;
                        }
                        case LE -> {
                            jumpOp = Opcodes.IF_ICMPGT;
                            yield Opcodes.IFGT;
                        }
                        case GT -> {
                            jumpOp = Opcodes.IF_ICMPLE;
                            yield Opcodes.IFLE;
                        }
                        case GE -> {
                            jumpOp = Opcodes.IF_ICMPLT;
                            yield Opcodes.IFLT;
                        }
                        default -> throw new IllegalArgumentException("Unknown comparison operator: " + op);
                    };
                }

                switch (operandType) {
                    case OceanTypeSystem.BIGDECIMAL_DESC -> {
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/stdlib/RuntimeUtils", "compare", "(Ljava/lang/Object;Ljava/lang/Object;)I", false);
                        methodVisitor.visitInsn(Opcodes.ICONST_0);
                        methodVisitor.visitJumpInsn(jumpOp, target);
                    }
                    case "I", "Z", "B", "S", "C" -> methodVisitor.visitJumpInsn(jumpOp, target);
                    case "J", "F", "D" -> {
                        int cmpOp;
                        if (operandType.equals("J")) {
                            cmpOp = Opcodes.LCMP;
                        } else if (ifOp == Opcodes.IFGT || ifOp == Opcodes.IFGE) {
                            cmpOp = operandType.equals("F") ? Opcodes.FCMPL : Opcodes.DCMPL;
                        } else {
                            cmpOp = operandType.equals("F") ? Opcodes.FCMPG : Opcodes.DCMPG;
                        }
                        methodVisitor.visitInsn(cmpOp);
                        methodVisitor.visitJumpInsn(ifOp, target);
                    }
                    case OceanTypeSystem.STRING_DESC -> {
                        // String types use structural value equality (null-safe via Objects.equals)
                        methodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                        if (jumpOp == Opcodes.IF_ICMPEQ) {
                            methodVisitor.visitJumpInsn(Opcodes.IFNE, target);
                        } else if (jumpOp == Opcodes.IF_ICMPNE) {
                            methodVisitor.visitJumpInsn(Opcodes.IFEQ, target);
                        } else {
                            reportError(currentClassNode, "Comparison operator not supported for String references: " + op + " on " + operandType);
                        }
                    }
                    default -> {
                        // All other object types use reference identity comparison
                        if (jumpOp == Opcodes.IF_ICMPEQ) {
                            methodVisitor.visitJumpInsn(Opcodes.IF_ACMPEQ, target);
                        } else if (jumpOp == Opcodes.IF_ICMPNE) {
                            methodVisitor.visitJumpInsn(Opcodes.IF_ACMPNE, target);
                        } else {
                            reportError(currentClassNode, "Comparison operator not supported for object references: " + op + " on " + operandType);
                        }
                    }
                }
                return;
            }
        }

        condition.accept(this);
        emitConversion(condition.getTypeDescriptor(), "Z");
        methodVisitor.visitJumpInsn(jumpIfTrue ? Opcodes.IFNE : Opcodes.IFEQ, target);
    }

    private static class StopFinder extends BaseIRVisitor {
        private boolean found = false;

        @Override
        public void visitStop(IRStopStatement node) {
            found = true;
        }

        @Override
        public void visitWhile(IRWhileStatement node) {
        }

        @Override
        public void visitForStatement(IRForStatement node) {
        }

        @Override
        public void visitSwitchExpression(IRSwitchExpression node) {
        }
    }

    private boolean hasLoopStop(IRNode node) {
        if (node == null) return false;
        StopFinder finder = new StopFinder();
        node.accept(finder);
        return finder.found;
    }

    private static class LabelStopFinder extends BaseIRVisitor {
        private final String targetLabel;
        private boolean found = false;

        LabelStopFinder(String targetLabel) {
            this.targetLabel = targetLabel;
        }

        @Override
        public void visitStop(IRStopStatement node) {
            if (targetLabel != null && targetLabel.equals(node.getTargetLabel())) {
                found = true;
            }
        }
    }

    private boolean hasStopForLabel(IRNode node, String label) {
        if (node == null || label == null) return false;
        LabelStopFinder finder = new LabelStopFinder(label);
        node.accept(finder);
        return finder.found;
    }

    private boolean alwaysTerminates(IRNode node) {
        if (node == null) return false;
        if (node instanceof IRLabeledStatement labeled) {
            if (hasStopForLabel(labeled.getStatement(), labeled.getLabel())) {
                return false;
            }
            return alwaysTerminates(labeled.getStatement());
        }
        if (node instanceof IRReturnStatement
                || node instanceof IRThrowStatement) {
            return true;
        }
        switch (node) {
            case IRSwitchStatement switchStmt -> {
                if (switchStmt.getDefaultBlock() == null || !alwaysTerminates(switchStmt.getDefaultBlock())) {
                    return false;
                }
                for (IRSwitchCase c : switchStmt.getCases()) {
                    if (c.getBody() == null || !alwaysTerminates(c.getBody())) {
                        return false;
                    }
                }
                return true;
            }
            case IRBlock irBlock -> {
                for (IRStatement stmt : irBlock.getStatements()) {
                    if (alwaysTerminates(stmt)) return true;
                }
                return false;
            }
            case IRIfStatement ifStmt -> {
                boolean isAlwaysTrue = false;
                boolean isAlwaysFalse = false;
                if (ifStmt.getCondition() instanceof IRLiteral) {
                    Object val = ((IRLiteral) ifStmt.getCondition()).getValue();
                    if (Boolean.TRUE.equals(val)) isAlwaysTrue = true;
                    else if (Boolean.FALSE.equals(val)) isAlwaysFalse = true;
                }
                if (isAlwaysTrue) {
                    return alwaysTerminates(ifStmt.getThenBranch());
                }
                if (isAlwaysFalse && ifStmt.getElseBranch() != null) {
                    return alwaysTerminates(ifStmt.getElseBranch());
                }
                return ifStmt.getThenBranch() != null && ifStmt.getElseBranch() != null
                        && alwaysTerminates(ifStmt.getThenBranch())
                        && alwaysTerminates(ifStmt.getElseBranch());
            }
            case IRWhileStatement whileStmt -> {
                boolean isAlwaysTrue = false;
                if (whileStmt.getCondition() instanceof IRLiteral) {
                    Object val = ((IRLiteral) whileStmt.getCondition()).getValue();
                    if (Boolean.TRUE.equals(val)) {
                        isAlwaysTrue = true;
                    }
                }
                if (isAlwaysTrue) {
                    if (!hasLoopStop(whileStmt.getBody())) {
                        return true;
                    }
                }
            }
            default -> {
            }
        }
        if (node instanceof IRDoWhileStatement doCtx) {
            boolean isAlwaysTrue = false;
            if (doCtx.getCondition() instanceof IRLiteral) {
                Object val = ((IRLiteral) doCtx.getCondition()).getValue();
                if (Boolean.TRUE.equals(val)) {
                    isAlwaysTrue = true;
                }
            }
            if (isAlwaysTrue) {
                if (!hasLoopStop(doCtx.getBody())) {
                    return true;
                }
            }
        }
        if (node instanceof IRTryCatchStatement tryCatch) {
            boolean tryTerminates = alwaysTerminates(tryCatch.getTryBlock());
            if (tryTerminates) {
                boolean allCatchesTerminate = true;
                if (tryCatch.getCatchClauses() != null) {
                    for (IRTryCatchStatement.IRCatchClause cc : tryCatch.getCatchClauses()) {
                        if (!alwaysTerminates(cc.body())) {
                            allCatchesTerminate = false;
                            break;
                        }
                    }
                }
                if (allCatchesTerminate) return true;
            }
            if (alwaysTerminates(tryCatch.getFinallyBlock())) return true;
        }
        if (node instanceof IRLockStatement) {
            return alwaysTerminates(((IRLockStatement) node).getBody());
        }
        return false;
    }

    @Override
    public void visitSwitchExpression(IRSwitchExpression node) {
        enterScope();
        Label defaultLabel = new Label();
        Label endLabel = new Label();

        String switchType = node.getExpression().getTypeDescriptor();
        boolean isEnumSwitch = isEnumType(switchType);
        boolean isStringSwitch = TypeChecker.isStringType(switchType);

        // Allocate temp index for the switch expression result
        int tempResultIdx = nextLocalIndex;
        String resDesc = cleanDesc(node.getTypeDescriptor());
        nextLocalIndex += (resDesc != null && (resDesc.equals("J") || resDesc.equals("D"))) ? 2 : 1;
        emitPushDefaultValue(methodVisitor, node.getTypeDescriptor());
        emitStore(node.getTypeDescriptor(), tempResultIdx);

        // Push state for result statements
        activeSwitchExprTypes.push(node.getTypeDescriptor());
        activeSwitchExprEndLabels.push(endLabel);
        activeSwitchExprTempIndices.push(tempResultIdx);

        // Create a body label for each case to support source-order fall-through
        Label[] bodyLabels = new Label[node.getCases().size()];
        for (int i = 0; i < node.getCases().size(); i++) {
            bodyLabels[i] = new Label();
        }

        boolean hasPattern = false;
        for (IRSwitchCase c : node.getCases()) {
            if (c.hasPattern() || c.getGuard() != null) {
                hasPattern = true;
                break;
            }
        }
        if (!hasPattern && !isEnumSwitch && !isStringSwitch && switchType != null
                && !TypeChecker.isIntegerType(switchType) && !switchType.equals("C") && !switchType.equals("B") && !switchType.equals("S")) {
            hasPattern = true;
        }

        if (hasPattern) {
            // 1. Evaluate expression and store in a temp variable
            node.getExpression().accept(this);
            String rawTargetType = node.getExpression().getTypeDescriptor();
            if (TypeChecker.isPrimitive(rawTargetType)) {
                emitConversion(rawTargetType, OceanTypeSystem.OBJECT_DESC);
            }
            int targetIndex = nextLocalIndex++;
            methodVisitor.visitVarInsn(Opcodes.ASTORE, targetIndex);

            // 2. Dispatch each case
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                Label bodyLabel = bodyLabels[i];
                Label nextCaseLabel = new Label();

                if (c.getPatterns() != null && !c.getPatterns().isEmpty()) {
                    for (int pIdx = 0; pIdx < c.getPatterns().size(); pIdx++) {
                        IRSwitchPattern pat = c.getPatterns().get(pIdx);
                        Label failThisPattern = (pIdx == c.getPatterns().size() - 1) ? nextCaseLabel : new Label();

                        emitPatternCheck(pat, targetIndex, failThisPattern);

                        // Evaluate guard (when) if present
                        if (c.getGuard() != null) {
                            c.getGuard().accept(this);
                            String gType = c.getGuard().getTypeDescriptor();
                            if (gType != null && !gType.equals("Z")) {
                                emitConversion(gType, "Z");
                            }
                            methodVisitor.visitJumpInsn(Opcodes.IFEQ, failThisPattern);
                        }

                        methodVisitor.visitJumpInsn(Opcodes.GOTO, bodyLabel);

                        if (failThisPattern != nextCaseLabel) {
                            methodVisitor.visitLabel(failThisPattern);
                        }
                    }
                } else if (c.getValues() != null && !c.getValues().isEmpty()) {
                    for (int vIdx = 0; vIdx < c.getValues().size(); vIdx++) {
                        IRExpression val = c.getValues().get(vIdx);
                        Label failVal = (vIdx == c.getValues().size() - 1) ? nextCaseLabel : new Label();
                        boolean isValNull = (val instanceof IRLiteral lit && lit.getValue() == null)
                                || "null".equals(val.getTypeDescriptor());
                        if (isValNull) {
                            methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                            methodVisitor.visitJumpInsn(Opcodes.IFNONNULL, failVal);
                        } else {
                            methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                            methodVisitor.visitJumpInsn(Opcodes.IFNULL, failVal);
                            methodVisitor.visitVarInsn(Opcodes.ALOAD, targetIndex);
                            val.accept(this);
                            String exprType = val.getTypeDescriptor();
                            if (TypeChecker.isPrimitive(exprType)) {
                                emitConversion(exprType, OceanTypeSystem.OBJECT_DESC);
                            }
                            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z", false);
                            methodVisitor.visitJumpInsn(Opcodes.IFEQ, failVal);
                        }

                        if (c.getGuard() != null) {
                            c.getGuard().accept(this);
                            String gType = c.getGuard().getTypeDescriptor();
                            if (gType != null && !gType.equals("Z")) {
                                emitConversion(gType, "Z");
                            }
                            methodVisitor.visitJumpInsn(Opcodes.IFEQ, failVal);
                        }

                        methodVisitor.visitJumpInsn(Opcodes.GOTO, bodyLabel);
                        if (failVal != nextCaseLabel) {
                            methodVisitor.visitLabel(failVal);
                        }
                    }
                }

                methodVisitor.visitLabel(nextCaseLabel);
            }

            // No case matched -> jump to default
            methodVisitor.visitJumpInsn(Opcodes.GOTO, defaultLabel);

            // 3. Lay out case bodies
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                methodVisitor.visitLabel(bodyLabels[i]);
                emitSwitchExprCaseBody(c.getBody(), node.getTypeDescriptor(), tempResultIdx, endLabel);
            }

            methodVisitor.visitLabel(defaultLabel);
            if (node.getDefaultBody() != null) {
                emitSwitchExprCaseBody(node.getDefaultBody(), node.getTypeDescriptor(), tempResultIdx, endLabel);
            } else {
                methodVisitor.visitTypeInsn(Opcodes.NEW, "java/lang/IncompatibleClassChangeError");
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitLdcInsn("Exhaustive switch failed to match at runtime");
                methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IncompatibleClassChangeError", "<init>", "(Ljava/lang/String;)V", false);
                methodVisitor.visitInsn(Opcodes.ATHROW);
            }

        } else if (isEnumSwitch) {
            // 1. Evaluate expression and call ordinal() directly -> O(1) TABLESWITCH / LOOKUPSWITCH
            node.getExpression().accept(this);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "ordinal", "()I", false);

            Map<Integer, Label> sortedCases = new TreeMap<>();
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                Label bodyLabel = bodyLabels[i];
                for (IRExpression valExpr : c.getValues()) {
                    int ordinal = resolveEnumOrdinal(switchType, valExpr);
                    if (ordinal >= 0) {
                        sortedCases.putIfAbsent(ordinal, bodyLabel);
                    }
                }
            }

            int[] keys = new int[sortedCases.size()];
            Label[] labels = new Label[sortedCases.size()];
            int idx = 0;
            for (Map.Entry<Integer, Label> entry : sortedCases.entrySet()) {
                keys[idx] = entry.getKey();
                labels[idx] = entry.getValue();
                idx++;
            }
            emitSwitchInsn(defaultLabel, keys, labels);

            // 2. Lay out case bodies
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                methodVisitor.visitLabel(bodyLabels[i]);
                emitSwitchExprCaseBody(c.getBody(), node.getTypeDescriptor(), tempResultIdx, endLabel);
            }

            methodVisitor.visitLabel(defaultLabel);
            if (node.getDefaultBody() != null) {
                emitSwitchExprCaseBody(node.getDefaultBody(), node.getTypeDescriptor(), tempResultIdx, endLabel);
            } else {
                methodVisitor.visitTypeInsn(Opcodes.NEW, "java/lang/IncompatibleClassChangeError");
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitLdcInsn("Exhaustive enum switch failed to match at runtime");
                methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IncompatibleClassChangeError", "<init>", "(Ljava/lang/String;)V", false);
                methodVisitor.visitInsn(Opcodes.ATHROW);
            }

        } else if (isStringSwitch) {
            // Evaluate expression and store in a temp variable
            node.getExpression().accept(this);
            int tempIndex = nextLocalIndex++;
            methodVisitor.visitVarInsn(Opcodes.ASTORE, tempIndex);

            // Build case string -> label mapping
            class StringCaseInfo {
                final String value;
                final int hashCode;
                final Label matchLabel;

                StringCaseInfo(String v, Label l) {
                    value = v;
                    hashCode = v.hashCode();
                    matchLabel = l;
                }
            }

            List<StringCaseInfo> stringCases = new ArrayList<>();
            Label nullCaseLabel = null; // Madde 9 fix: track explicit case null label
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                Label bodyLabel = bodyLabels[i];
                for (IRExpression valExpr : c.getValues()) {
                    if (valExpr instanceof IRLiteral lit && lit.getValue() == null) {
                        // This case handles null explicitly
                        nullCaseLabel = bodyLabel;
                    } else if (valExpr instanceof IRLiteral) {
                        Object val = ((IRLiteral) valExpr).getValue();
                        if (val instanceof String) {
                            stringCases.add(new StringCaseInfo((String) val, bodyLabel));
                        }
                    }
                }
            }

            // Group by hashCode
            Map<Integer, List<StringCaseInfo>> byHash = new TreeMap<>();
            for (StringCaseInfo sci : stringCases) {
                byHash.computeIfAbsent(sci.hashCode, k -> new ArrayList<>()).add(sci);
            }

            // Madde 9 fix: IFNULL -> explicit null-case body (if any), otherwise default
            methodVisitor.visitVarInsn(Opcodes.ALOAD, tempIndex);
            methodVisitor.visitJumpInsn(Opcodes.IFNULL, nullCaseLabel != null ? nullCaseLabel : defaultLabel);
            methodVisitor.visitVarInsn(Opcodes.ALOAD, tempIndex);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false);

            int[] hashKeys = new int[byHash.size()];
            Label[] hashLabels = new Label[byHash.size()];
            int idx = 0;
            for (Map.Entry<Integer, List<StringCaseInfo>> entry : byHash.entrySet()) {
                hashKeys[idx] = entry.getKey();
                hashLabels[idx] = new Label();
                idx++;
            }
            emitSwitchInsn(defaultLabel, hashKeys, hashLabels);

            idx = 0;
            for (Map.Entry<Integer, List<StringCaseInfo>> entry : byHash.entrySet()) {
                methodVisitor.visitLabel(hashLabels[idx]);
                for (StringCaseInfo sci : entry.getValue()) {
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, tempIndex);
                    methodVisitor.visitLdcInsn(sci.value);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                            "(Ljava/lang/Object;)Z", false);
                    methodVisitor.visitJumpInsn(Opcodes.IFNE, sci.matchLabel);
                }
                methodVisitor.visitJumpInsn(Opcodes.GOTO, defaultLabel);
                idx++;
            }

            // Lay out case bodies
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                methodVisitor.visitLabel(bodyLabels[i]);
                emitSwitchExprCaseBody(c.getBody(), node.getTypeDescriptor(), tempResultIdx, endLabel);
            }

            methodVisitor.visitLabel(defaultLabel);
            if (node.getDefaultBody() != null) {
                emitSwitchExprCaseBody(node.getDefaultBody(), node.getTypeDescriptor(), tempResultIdx, endLabel);
            } else {
                methodVisitor.visitTypeInsn(Opcodes.NEW, "java/lang/IncompatibleClassChangeError");
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitLdcInsn("Exhaustive string switch failed to match at runtime");
                methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IncompatibleClassChangeError", "<init>", "(Ljava/lang/String;)V", false);
                methodVisitor.visitInsn(Opcodes.ATHROW);
            }

        } else {
            // Integer/Char Switch
            node.getExpression().accept(this);
            if (switchType != null && !switchType.equals("I")) {
                emitConversion(switchType, "I");
            }

            class CaseInfo {
                final int key;
                final Label label;

                CaseInfo(int k, Label l) {
                    key = k;
                    label = l;
                }
            }

            List<CaseInfo> sortedCases = new ArrayList<>();
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                Label bodyLabel = bodyLabels[i];
                for (IRExpression valExpr : c.getValues()) {
                    int key = getKey(valExpr);
                    sortedCases.add(new CaseInfo(key, bodyLabel));
                }
            }

            sortedCases.sort(Comparator.comparingInt(c -> c.key));

            int[] keys = new int[sortedCases.size()];
            Label[] labels = new Label[sortedCases.size()];
            for (int i = 0; i < sortedCases.size(); i++) {
                keys[i] = sortedCases.get(i).key;
                labels[i] = sortedCases.get(i).label;
            }
            emitSwitchInsn(defaultLabel, keys, labels);

            // Lay out case bodies
            for (int i = 0; i < node.getCases().size(); i++) {
                IRSwitchCase c = node.getCases().get(i);
                methodVisitor.visitLabel(bodyLabels[i]);
                emitSwitchExprCaseBody(c.getBody(), node.getTypeDescriptor(), tempResultIdx, endLabel);
            }

            methodVisitor.visitLabel(defaultLabel);
            if (node.getDefaultBody() != null) {
                emitSwitchExprCaseBody(node.getDefaultBody(), node.getTypeDescriptor(), tempResultIdx, endLabel);
            } else {
                methodVisitor.visitTypeInsn(Opcodes.NEW, "java/lang/IncompatibleClassChangeError");
                methodVisitor.visitInsn(Opcodes.DUP);
                methodVisitor.visitLdcInsn("Exhaustive switch failed to match at runtime");
                methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IncompatibleClassChangeError", "<init>", "(Ljava/lang/String;)V", false);
                methodVisitor.visitInsn(Opcodes.ATHROW);
            }
        }

        methodVisitor.visitLabel(endLabel);

        // Pop state
        activeSwitchExprTypes.pop();
        activeSwitchExprEndLabels.pop();
        activeSwitchExprTempIndices.pop();

        // Load the result onto stack
        emitLoad(node.getTypeDescriptor(), tempResultIdx);
        exitScope();
    }

    private void emitSwitchExprCaseBody(IRNode body, String expectedType, int tempResultIdx, Label endLabel) {
        if (body instanceof IRExpression) {
            body.accept(this);
            emitConversion(((IRExpression) body).getTypeDescriptor(), expectedType);
            emitStore(expectedType, tempResultIdx);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
        } else if (body != null) {
            body.accept(this);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
        }
    }

    @Override
    public void visitResultStatement(IRResultStatement node) {
        node.getExpression().accept(this);
        String exprType = node.getExpression().getTypeDescriptor();
        String expectedType = activeSwitchExprTypes.peek();
        emitConversion(exprType, expectedType);
        emitStore(expectedType, activeSwitchExprTempIndices.peek());
        methodVisitor.visitJumpInsn(Opcodes.GOTO, activeSwitchExprEndLabels.peek());
    }

    private void emitStore(String type, int index) {
        type = cleanDesc(type);
        if (type != null && (type.equals("LI;") || type.endsWith("/I;") || type.endsWith("/int;"))) type = "I";
        else if (type != null && (type.equals("LZ;") || type.endsWith("/Z;") || type.endsWith("/boolean;") || type.endsWith("/bool;")))
            type = "Z";
        else if (type != null && (type.equals("LJ;") || type.endsWith("/J;") || type.endsWith("/long;"))) type = "J";
        else if (type != null && (type.equals("LD;") || type.endsWith("/D;") || type.endsWith("/double;"))) type = "D";
        else if (type != null && (type.equals("LF;") || type.endsWith("/F;") || type.endsWith("/float;"))) type = "F";

        if (type == null) {
            methodVisitor.visitVarInsn(Opcodes.ASTORE, index);
            return;
        }
        switch (type) {
            case "I", "Z", "B", "C", "S" -> methodVisitor.visitVarInsn(Opcodes.ISTORE, index);
            case "J" -> methodVisitor.visitVarInsn(Opcodes.LSTORE, index);
            case "F" -> methodVisitor.visitVarInsn(Opcodes.FSTORE, index);
            case "D" -> methodVisitor.visitVarInsn(Opcodes.DSTORE, index);
            default -> methodVisitor.visitVarInsn(Opcodes.ASTORE, index);
        }
    }

    private void emitLoad(String type, int index) {
        type = cleanDesc(type);
        if (type != null && (type.equals("LI;") || type.endsWith("/I;") || type.endsWith("/int;"))) type = "I";
        else if (type != null && (type.equals("LZ;") || type.endsWith("/Z;") || type.endsWith("/boolean;") || type.endsWith("/bool;")))
            type = "Z";
        else if (type != null && (type.equals("LJ;") || type.endsWith("/J;") || type.endsWith("/long;"))) type = "J";
        else if (type != null && (type.equals("LD;") || type.endsWith("/D;") || type.endsWith("/double;"))) type = "D";
        else if (type != null && (type.equals("LF;") || type.endsWith("/F;") || type.endsWith("/float;"))) type = "F";

        if (type == null) {
            methodVisitor.visitVarInsn(Opcodes.ALOAD, index);
            return;
        }
        switch (type) {
            case "I", "Z", "B", "C", "S" -> methodVisitor.visitVarInsn(Opcodes.ILOAD, index);
            case "J" -> methodVisitor.visitVarInsn(Opcodes.LLOAD, index);
            case "F" -> methodVisitor.visitVarInsn(Opcodes.FLOAD, index);
            case "D" -> methodVisitor.visitVarInsn(Opcodes.DLOAD, index);
            default -> methodVisitor.visitVarInsn(Opcodes.ALOAD, index);
        }
    }

    private static class LambdaScanner extends BaseIRVisitor {
        private final Set<String> lambdaMethodNames = new HashSet<>();

        public Set<String> scan(IRClass irClass) {
            visitClass(irClass);
            return lambdaMethodNames;
        }

        @Override
        public void visitClass(IRClass node) {
            for (IRField field : node.getFields()) field.accept(this);
            for (IRMethod method : node.getMethods()) method.accept(this);
        }

        @Override
        public void visitAssignment(IRAssignment node) {
            if (node.getTarget() != null) node.getTarget().accept(this);
            if (node.getValue() != null) node.getValue().accept(this);
        }

        @Override
        public void visitIf(IRIfStatement node) {
            if (node.getCondition() != null) node.getCondition().accept(this);
            if (node.getThenBranch() != null) node.getThenBranch().accept(this);
            if (node.getElseBranch() != null) node.getElseBranch().accept(this);
        }

        @Override
        public void visitWhile(IRWhileStatement node) {
            if (node.getCondition() != null) node.getCondition().accept(this);
            if (node.getBody() != null) node.getBody().accept(this);
        }

        @Override
        public void visitDoWhile(IRDoWhileStatement node) {
            if (node.getCondition() != null) node.getCondition().accept(this);
            if (node.getBody() != null) node.getBody().accept(this);
        }

        @Override
        public void visitForStatement(IRForStatement node) {
            if (node.getFromExpr() != null) node.getFromExpr().accept(this);
            if (node.getToExpr() != null) node.getToExpr().accept(this);
            if (node.getBody() != null) node.getBody().accept(this);
        }

        @Override
        public void visitThrow(IRThrowStatement node) {
            if (node.getExpression() != null) node.getExpression().accept(this);
        }

        @Override
        public void visitCast(IRCastExpression node) {
            if (node.getExpression() != null) node.getExpression().accept(this);
        }

        @Override
        public void visitInstanceof(IRInstanceof node) {
            if (node.getExpression() != null) node.getExpression().accept(this);
        }

        @Override
        public void visitTernary(IRTernaryExpression node) {
            if (node.getCondition() != null) node.getCondition().accept(this);
            if (node.getTrueExpr() != null) node.getTrueExpr().accept(this);
            if (node.getFalseExpr() != null) node.getFalseExpr().accept(this);
        }

        @Override
        public void visitNewObject(IRNewObject node) {
            for (IRExpression arg : node.getArguments()) {
                if (arg != null) arg.accept(this);
            }
        }

        @Override
        public void visitArrayCreation(IRArrayCreation node) {
            for (IRExpression size : node.getSizes()) {
                if (size != null) size.accept(this);
            }
        }

        @Override
        public void visitExprStatement(IRExprStatement node) {
            if (node.getExpression() != null) node.getExpression().accept(this);
        }

        @Override
        public void visitVariableDecl(IRVariableDecl node) {
            if (node.getInitialValue() != null) node.getInitialValue().accept(this);
        }

        @Override
        public void visitArrayAccess(IRArrayAccess node) {
            if (node.getArray() != null) node.getArray().accept(this);
            if (node.getIndex() != null) node.getIndex().accept(this);
        }

        @Override
        public void visitArrayLiteral(IRArrayLiteral node) {
            for (IRExpression el : node.getElements()) {
                if (el != null) el.accept(this);
            }
        }

        @Override
        public void visitSwitch(IRSwitchStatement node) {
            if (node.getExpression() != null) node.getExpression().accept(this);
            for (IRSwitchCase sc : node.getCases()) {
                if (sc.getPatterns() != null) {
                    for (IRSwitchPattern p : sc.getPatterns()) {
                        if (p != null) p.accept(this);
                    }
                }
                if (sc.getValues() != null) {
                    for (IRExpression val : sc.getValues()) {
                        if (val != null) val.accept(this);
                    }
                }
                if (sc.getGuard() != null) sc.getGuard().accept(this);
                if (sc.getBody() != null) sc.getBody().accept(this);
            }
            if (node.getDefaultBlock() != null) node.getDefaultBlock().accept(this);
        }

        @Override
        public void visitTryCatch(IRTryCatchStatement node) {
            if (node.getTryBlock() != null) node.getTryBlock().accept(this);
            if (node.getCatchClauses() != null) {
                for (IRTryCatchStatement.IRCatchClause cb : node.getCatchClauses()) {
                    if (cb.body() != null) cb.body().accept(this);
                }
            }
            if (node.getFinallyBlock() != null) node.getFinallyBlock().accept(this);
        }

        @Override
        public void visitUnaryOp(IRUnaryOp node) {
            if (node.getExpression() != null) node.getExpression().accept(this);
        }

        @Override
        public void visitInterpolatedString(IRInterpolatedString node) {
            for (IRNode part : node.getParts()) {
                if (part != null) part.accept(this);
            }
        }

        @Override
        public void visitBinaryOp(IRBinaryOp node) {
            if (node.getLeft() != null) node.getLeft().accept(this);
            if (node.getRight() != null) node.getRight().accept(this);
        }

        @Override
        public void visitMethodCall(IRMethodCall node) {
            if (node.getReceiver() != null) node.getReceiver().accept(this);
            for (IRExpression arg : node.getArguments()) {
                if (arg != null) arg.accept(this);
            }
        }

        @Override
        public void visitOceanOutput(IROceanOutput node) {
            if (node.getArguments() != null) {
                for (IRExpression arg : node.getArguments()) {
                    if (arg != null) arg.accept(this);
                }
            }
        }

        @Override
        public void visitLambda(IRLambdaExpression node) {
            lambdaMethodNames.add(node.getLambdaMethodName());
            if (node.getBody() != null) node.getBody().accept(this);
        }
    }

    private static class LoggingMethodVisitor extends MethodVisitor {
        private final String methodName;

        public LoggingMethodVisitor(MethodVisitor mv, String methodName) {
            super(Opcodes.ASM9, mv);
            this.methodName = methodName;
        }

        @Override
        public void visitInsn(int opcode) {
            System.out.println("  [ASM " + methodName + "] visitInsn " + opcode);
            super.visitInsn(opcode);
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            System.out.println("  [ASM " + methodName + "] visitVarInsn " + opcode + " " + var);
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            System.out.println("  [ASM " + methodName + "] visitTypeInsn " + opcode + " " + type);
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            System.out.println("  [ASM " + methodName + "] visitFieldInsn " + opcode + " " + owner + "." + name + " " + descriptor);
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            System.out.println("  [ASM " + methodName + "] visitMethodInsn " + opcode + " " + owner + "." + name + " " + descriptor);
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            System.out.println("  [ASM " + methodName + "] visitInvokeDynamicInsn " + name + " " + descriptor);
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            System.out.println("  [ASM " + methodName + "] visitJumpInsn " + opcode + " label_" + label.hashCode());
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLabel(Label label) {
            System.out.println("  [ASM " + methodName + "] visitLabel label_" + label.hashCode());
            super.visitLabel(label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            System.out.println("  [ASM " + methodName + "] visitLdcInsn " + value);
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int var, int increment) {
            System.out.println("  [ASM " + methodName + "] visitIincInsn " + var + " " + increment);
            super.visitIincInsn(var, increment);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            System.out.println("  [ASM " + methodName + "] visitMaxs " + maxStack + " " + maxLocals);
            super.visitMaxs(maxStack, maxLocals);
        }
    }

    private String getReceiverType(IRExpression receiver) {
        if (receiver == null) return OceanTypeSystem.wrapObjectType(currentClassName);
        if (receiver instanceof IRVariableAccess) {
            return ((IRVariableAccess) receiver).getOriginalTypeDescriptor();
        }
        return receiver.getTypeDescriptor();
    }

    private static class CleaningMethodVisitor extends MethodVisitor {
        private int insnCount = 0;

        public CleaningMethodVisitor(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        public int getInsnCount() {
            return insnCount;
        }

        @Override
        public void visitInsn(int opcode) {
            insnCount++;
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            insnCount++;
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            insnCount++;
            super.visitVarInsn(opcode, varIndex);
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            insnCount++;
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(Object value) {
            insnCount++;
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
            insnCount++;
            super.visitIincInsn(varIndex, increment);
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            insnCount++;
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            insnCount++;
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        private String clean(String desc) {
            String c = cleanDesc(desc);
            if (c != null && (c.contains("/I") || c.equals("I"))) {
                if (Boolean.getBoolean("ocean.debug")) {
                    System.out.println("[DEBUG CleaningMethodVisitor] original='" + desc + "' cleaned='" + c + "'");
                }
            }
            return c;
        }

        private String cleanOwnerInternal(String owner) {
            if (owner == null) return null;
            String c = cleanDesc(owner);
            if (c != null) {
                if (c.startsWith("[")) {
                    return c;
                }
                if (c.startsWith("L") && c.endsWith(";")) {
                    c = c.substring(1, c.length() - 1);
                }
                while (c.endsWith(";")) {
                    c = c.substring(0, c.length() - 1);
                }
                if (c.contains("<")) {
                    c = c.substring(0, c.indexOf('<'));
                }
                while (c.endsWith(";")) {
                    c = c.substring(0, c.length() - 1);
                }
                c = c.replace('.', '/');
            }
            return c;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            insnCount++;
            if (Boolean.getBoolean("ocean.debug") && (descriptor.equals("I") || descriptor.contains("/I"))) {
                System.out.println("[DEBUG visitFieldInsn] owner=" + owner + " name=" + name + " desc=" + descriptor);
            }
            super.visitFieldInsn(opcode, cleanOwnerInternal(owner), name, clean(descriptor));
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            insnCount++;
            if (Boolean.getBoolean("ocean.debug") && descriptor.contains("Locean/compiler/generated/com/resources/I;")) {
                System.out.println("[DEBUG visitMethodInsn] owner=" + owner + " name=" + name + " desc=" + descriptor);
            }
            super.visitMethodInsn(opcode, cleanOwnerInternal(owner), name, clean(descriptor), isInterface);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            insnCount++;
            if (type == null) {
                if (opcode == Opcodes.NEW) {
                    throw new IllegalStateException("Attempted to emit NEW with null type in method bytecode generation");
                }
                super.visitTypeInsn(opcode, "java/lang/Object");
                return;
            }
            String t = cleanOwnerInternal(type);
            super.visitTypeInsn(opcode, t);
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end, int index) {
            super.visitLocalVariable(name, clean(descriptor), signature, start, end, index);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            insnCount++;
            super.visitMultiANewArrayInsn(clean(descriptor), numDimensions);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            super.visitTryCatchBlock(start, end, handler, clean(type));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
            insnCount++;
            Object[] cleanedArgs = new Object[bootstrapMethodArguments.length];
            for (int i = 0; i < bootstrapMethodArguments.length; i++) {
                Object arg = bootstrapMethodArguments[i];
                if (arg instanceof Type) {
                    cleanedArgs[i] = Type.getType(clean(((Type) arg).getDescriptor()));
                } else if (arg instanceof Handle h) {
                    cleanedArgs[i] = new Handle(h.getTag(), clean(h.getOwner()), h.getName(), clean(h.getDesc()), h.isInterface());
                } else {
                    cleanedArgs[i] = arg;
                }
            }
            super.visitInvokeDynamicInsn(name, clean(descriptor),
                    new Handle(
                            bootstrapMethodHandle.getTag(),
                            clean(bootstrapMethodHandle.getOwner()),
                            bootstrapMethodHandle.getName(),
                            clean(bootstrapMethodHandle.getDesc()),
                            bootstrapMethodHandle.isInterface()
                    ),
                    cleanedArgs
            );
        }
    }

    private boolean isAnnotationVisible(IRAnnotation anno) {
        String desc = anno.getTypeDescriptor();
        String retention = ClassMetadataCache.getAnnotationRetention(cleanDesc(desc));
        return !"CLASS".equalsIgnoreCase(retention);
    }

    private boolean isSourceOnlyAnnotation(IRAnnotation anno) {
        String desc = anno.getTypeDescriptor();
        String retention = ClassMetadataCache.getAnnotationRetention(cleanDesc(desc));
        return "SOURCE".equalsIgnoreCase(retention);
    }

    private void emitAnnotationOnClass(IRAnnotation anno) {
        if (isSourceOnlyAnnotation(anno)) return;
        boolean visible = isAnnotationVisible(anno);
        AnnotationVisitor av = classWriter.visitAnnotation(cleanDesc(anno.getTypeDescriptor()), visible);
        for (Map.Entry<String, IRExpression> entry : anno.getElements().entrySet()) {
            emitAnnotationElementValue(av, entry.getKey(), entry.getValue());
        }
        av.visitEnd();
    }

    private void emitOceanMetadata(int kind) {
        AnnotationVisitor av = classWriter.visitAnnotation("Locean/compiler/Metadata;", true);
        if (av != null) {
            av.visit("language", CompilerConfig.LANGUAGE_NAME);
            av.visit("compilerVersion", CompilerConfig.COMPILER_VERSION);
            av.visit("languageVersion", CompilerConfig.LANGUAGE_VERSION);
            av.visit("kind", kind);
            if (sourceFileName != null) {
                av.visit("sourceFile", sourceFileName);
            }
            av.visitEnd();
        }
    }

    private void emitAnnotationOnField(FieldVisitor fv, IRAnnotation anno) {
        if (isSourceOnlyAnnotation(anno)) return;
        boolean visible = isAnnotationVisible(anno);
        AnnotationVisitor av = fv.visitAnnotation(cleanDesc(anno.getTypeDescriptor()), visible);
        if (av != null) {
            for (Map.Entry<String, IRExpression> entry : anno.getElements().entrySet()) {
                emitAnnotationElementValue(av, entry.getKey(), entry.getValue());
            }
            av.visitEnd();
        }
    }

    private void emitAnnotationOnMethod(MethodVisitor mv, IRAnnotation anno) {
        if (isSourceOnlyAnnotation(anno)) return;
        boolean visible = isAnnotationVisible(anno);
        AnnotationVisitor av = mv.visitAnnotation(cleanDesc(anno.getTypeDescriptor()), visible);
        if (av != null) {
            for (Map.Entry<String, IRExpression> entry : anno.getElements().entrySet()) {
                emitAnnotationElementValue(av, entry.getKey(), entry.getValue());
            }
            av.visitEnd();
        }
    }

    private void emitParameterAnnotation(MethodVisitor mv, int paramIndex, IRAnnotation anno) {
        if (isSourceOnlyAnnotation(anno)) return;
        boolean visible = isAnnotationVisible(anno);
        AnnotationVisitor av = mv.visitParameterAnnotation(paramIndex, cleanDesc(anno.getTypeDescriptor()), visible);
        if (av != null) {
            for (Map.Entry<String, IRExpression> entry : anno.getElements().entrySet()) {
                emitAnnotationElementValue(av, entry.getKey(), entry.getValue());
            }
            av.visitEnd();
        }
    }

    private void emitParameterAnnotations(MethodVisitor mv, List<IRMethod.IRParameter> params) {
        if (params == null || params.isEmpty()) return;
        int paramIndex = 0;
        for (IRMethod.IRParameter p : params) {
            if ("this".equals(p.name()) || "this$0".equals(p.name()) || "$name".equals(p.name()) || "$ordinal".equals(p.name())) {
                continue;
            }
            if (p.annotations() != null) {
                for (IRAnnotation anno : p.annotations()) {
                    emitParameterAnnotation(mv, paramIndex, anno);
                }
            }
            paramIndex++;
        }
    }

    private void emitAnnotationElementValue(AnnotationVisitor av, String name, IRExpression expr) {
        if (expr instanceof IRLiteral lit) {
            Object val = lit.getValue();
            av.visit(name, val);
        } else if (expr instanceof IRVariableAccess va) {
            if ("class".equals(va.getName()) && va.getOwner() != null) {
                String ownerDesc = OceanTypeSystem.wrapObjectType(va.getOwner().replace('.', '/'));
                av.visit(name, Type.getType(cleanDesc(ownerDesc)));
            } else {
                String enumClass = va.getOwner();
                if (enumClass == null) {
                    enumClass = resolveAnnotationEnumOwner(va.getName());
                }
                if (enumClass != null) {
                    String enumDesc = OceanTypeSystem.wrapObjectType(enumClass.replace('.', '/'));
                    av.visitEnum(name, cleanDesc(enumDesc), va.getName());
                } else {
                    av.visit(name, va.getName());
                }
            }
        } else if (expr instanceof IRAnnotation nestedAnno) {
            AnnotationVisitor nestedAv = av.visitAnnotation(name, cleanDesc(nestedAnno.getTypeDescriptor()));
            if (nestedAv != null) {
                for (Map.Entry<String, IRExpression> entry : nestedAnno.getElements().entrySet()) {
                    emitAnnotationElementValue(nestedAv, entry.getKey(), entry.getValue());
                }
                nestedAv.visitEnd();
            }
        } else if (expr instanceof IRArrayLiteral arr) {
            AnnotationVisitor arrAv = av.visitArray(name);
            if (arrAv != null) {
                for (IRExpression elem : arr.getElements()) {
                    emitAnnotationElementValue(arrAv, null, elem);
                }
                arrAv.visitEnd();
            }
        }
    }

    private String resolveAnnotationEnumOwner(String name) {
        if (name == null) return null;
        return switch (name) {
            case "SOURCE", "CLASS", "RUNTIME" -> "java/lang/annotation/RetentionPolicy";
            case "TYPE", "FIELD", "METHOD", "PARAMETER", "CONSTRUCTOR", "LOCAL_VARIABLE",
                 "ANNOTATION_TYPE", "PACKAGE", "TYPE_PARAMETER", "TYPE_USE", "MODULE", "RECORD_COMPONENT" ->
                    "java/lang/annotation/ElementType";
            default -> null;
        };
    }

    private void emitAnnotationDefault(MethodVisitor mv, IRExpression expr) {
        AnnotationVisitor av = mv.visitAnnotationDefault();
        if (av != null) {
            emitAnnotationElementValue(av, null, expr);
            av.visitEnd();
        }
    }


    private void reportError(IRNode node, String message) {
        int line = node != null ? node.getLineNumber() : 0;
        int col = node != null ? node.getColumnNumber() : 0;
        CompilerReporter.error(sourceFileName, line, col, message, "IRToBytecodeEmitter");
        throw new CompilationException(message);
    }

    private void reportError(String message) {
        reportError(null, message);
    }


    private void reportWarning(IRAssignment node, String message) {
        CompilerReporter.warning(sourceFileName, node.getLineNumber(), node.getColumnNumber(), message, "IRToBytecodeEmitter");
    }
}
