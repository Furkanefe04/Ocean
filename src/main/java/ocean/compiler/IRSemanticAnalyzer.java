package ocean.compiler;

import ocean.compiler.ir.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IR-based Semantic Analyzer for the Ocean Compiler.
 * Validates semantics directly on high-level IR nodes (IRClass, IRMethod, IRBlock, IRExpression, etc.).
 */
public class IRSemanticAnalyzer extends BaseIRVisitor {

    private static final Log log = LogFactory.getLog(IRSemanticAnalyzer.class);
    private final String currentFile;
    private final CompilationSession session;
    private final SymbolTable symbolTable;
    private final Map<String, String> importedClasses;
    private final Set<String> importedWildcards;
    private final Map<String, String> importedStaticMembers;
    private final Set<String> importedStaticWildcards;
    private int errorCount = 0;

    /** Default constructor — creates empty import maps (for unit tests). */
    public IRSemanticAnalyzer(String currentFile, CompilationSession session) {
        this.currentFile = currentFile;
        this.session = session;
        this.importedClasses = new HashMap<>();
        this.importedWildcards = new HashSet<>();
        this.importedStaticMembers = new HashMap<>();
        this.importedStaticWildcards = new HashSet<>();
        this.symbolTable = new SymbolTable(false, importedClasses, Collections.emptySet(), session);
    }

    /**
     * Full constructor — receives already-populated import state from IRGenerator.
     * Use this in the compilation pipeline so that class/static imports are resolved.
     */
    public IRSemanticAnalyzer(String currentFile, CompilationSession session,
                              Map<String, String> importedClasses,
                              Collection<String> importedWildcards,
                              Map<String, String> importedStaticMembers,
                              Collection<String> importedStaticWildcards,
                              SymbolTable symbolTable) {
        this.currentFile = currentFile;
        this.session = session;
        this.importedClasses     = importedClasses     != null ? importedClasses     : new HashMap<>();
        this.importedWildcards   = importedWildcards   != null ? new HashSet<>(importedWildcards)   : new HashSet<>();
        this.importedStaticMembers  = importedStaticMembers  != null ? importedStaticMembers  : new HashMap<>();
        this.importedStaticWildcards = importedStaticWildcards != null ? new HashSet<>(importedStaticWildcards) : new HashSet<>();
        // Reuse the symbolTable that already has all variables/types populated by IRGenerator
        this.symbolTable = symbolTable != null ? symbolTable : new SymbolTable(false, this.importedClasses, Collections.emptySet(), session);
    }

    private String currentClassFqcn = null;
    private boolean currentClassIsData = false;
    private String currentClassSimpleName = null;
    private String currentSuperName = null;
    private boolean currentClassIsAbstract = false;
    private boolean currentClassIsInterface = false;
    private boolean currentClassIsEnum = false;
    private boolean isStaticContext = false;
    private boolean insideStaticInitializer = false;
    private boolean insideInstanceInitializer = false;
    private boolean currentMethodIsAsync = false;
    private String currentMethodReturnDesc = null;
    private String currentMethodName = null;
    private boolean inEarlyConstructionContext = false;
    private IRBlock currentRootCtorBlock = null;
    private boolean currentStmtIsTopLevelCtorStmt = false;

    private int switchExpressionDepth = 0;

    // R2-1: Loop & Switch Context Depth Tracking
    private int loopDepth = 0;
    private int switchDepth = 0;
    private int enclosingLoopDepth = 0;
    private int enclosingSwitchDepth = 0;
    private int enclosingSwitchExpressionDepth = 0;
    private int lambdaDepth = 0;
    private final Deque<Integer> lambdaScopeDepths = new ArrayDeque<>();
    private int currentMethodBaseScopeDepth = 0;

    private enum ControlTarget {
        LOOP,
        COLON_SWITCH,
        ARROW_SWITCH,
        SWITCH_EXPR
    }
    private final Deque<ControlTarget> controlTargetStack = new ArrayDeque<>();

    // P2-1: Nullability Scope Stack for Flow-Sensitive Smart Casting
    private final Deque<Map<String, Boolean>> nullabilityScopes = new ArrayDeque<>();
    private final Deque<Map<String, String>> typeNarrowingScopes = new ArrayDeque<>();
    private Map<String, Boolean> lastBlockNullabilitySnapshot = null;
    private Map<String, Boolean> lastBlockInitSnapshot = null;

    // Checked Exception Stack Tracking
    private final Deque<List<String>> caughtExceptionsStack = new ArrayDeque<>();
    private final Deque<Set<String>> tryBlockThrownExceptions = new ArrayDeque<>();
    private final List<String> currentMethodDeclaredThrows = new ArrayList<>();
    private final Deque<String> activeEnumSwitchTypes = new ArrayDeque<>();
    private int finallyDepth = 0;
    private final List<String> currentClassBlankFinalFields = new ArrayList<>();
    private final Set<String> definitelyAssignedBlankFinalFields = new HashSet<>();
    private final Set<String> potentiallyAssignedBlankFinalFields = new HashSet<>();
    private final Set<String> assignedBlankFinalFieldsInCtor = definitelyAssignedBlankFinalFields;
    private final List<String> currentClassBlankStaticFinalFields = new ArrayList<>();
    private final Set<String> definitelyAssignedBlankStaticFinalFields = new HashSet<>();
    private final Set<String> potentiallyAssignedBlankStaticFinalFields = new HashSet<>();
    private Set<String> currentClassForwardFields = null;
    private String currentFieldBeingInitialized = null;

    // Labeled statement tracking for stop/skip validation
        private record LabelInfo(String name, IRStatement statement, boolean isLoop, int lambdaDepth) {
    }
    private final Map<String, LabelInfo> activeLabels = new HashMap<>();

    private LabelInfo findActiveLabel(String name) {
        return activeLabels.get(name);
    }

    private boolean inAssignmentTarget = false;
    private Set<String> usedAsResourceInMethod = new HashSet<>();
    private final Deque<Set<String>> inaccessiblePatternVarScopes = new ArrayDeque<>();

    private boolean isPatternVarInaccessible(String name) {
        for (Set<String> set : inaccessiblePatternVarScopes) {
            if (set.contains(name)) return true;
        }
        return false;
    }

    private static final Field NO_FIELD = createNoFieldSentinel();

    private static Field createNoFieldSentinel() {
        try {
            Field f = DummySentinel.class.getDeclaredField("DUMMY");
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static class DummySentinel {
        public static Object DUMMY;
    }

    private static final Map<String, Field> reflectedFieldCache = new ConcurrentHashMap<>();
    private static final Map<String, ClassMethodsInfo> classMethodsCache = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> parsedParamsCache = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> checkedExceptionCache = new ConcurrentHashMap<>();
    private static final Map<String, String> fieldTypeCache = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> overrideMatchingCache = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> methodOverrideCache = new ConcurrentHashMap<>();
    private static final String NO_FIELD_TYPE = "NO_FIELD_TYPE_SENTINEL";

    private record ClassMethodsInfo(Set<MethodSignature> abstractMethods, Set<MethodSignature> concreteMethods) { }

    public static void clearCaches() {
        reflectedFieldCache.clear();
        classMethodsCache.clear();
        parsedParamsCache.clear();
        checkedExceptionCache.clear();
        fieldTypeCache.clear();
        overrideMatchingCache.clear();
        methodOverrideCache.clear();
    }


    public boolean hasErrors() {
        return errorCount > 0 || CompilerReporter.hasErrors();
    }

    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    public Map<String, String> getImportedClasses() {
        return importedClasses;
    }

    public Set<String> getImportedWildcards() {
        return importedWildcards;
    }

    public Map<String, String> getImportedStaticMembers() {
        return importedStaticMembers;
    }

    public Set<String> getImportedStaticWildcards() {
        return importedStaticWildcards;
    }

    private String getFilePackage() {
        if (currentClassFqcn != null && currentClassFqcn.contains("/")) {
            return currentClassFqcn.substring(0, currentClassFqcn.lastIndexOf('/'));
        }
        return "";
    }

    // ========== Error & Warning Reporting ==========

    private void reportError(IRNode node, String message) {
        int line = node != null ? node.getLineNumber() : 0;
        int col = node != null ? node.getColumnNumber() : 0;
        CompilerReporter.error(currentFile, line, col, message, "IRSemanticAnalyzer");
        errorCount++;
    }

    private void reportError(String message) {
        reportError(null, message);
    }

    private void reportWarning(IRNode node, String message) {
        int line = node != null ? node.getLineNumber() : 0;
        int col = node != null ? node.getColumnNumber() : 0;
        CompilerReporter.warning(currentFile, line, col, message, "IRSemanticAnalyzer");
    }

    private void reportWarning(String message) {
        reportWarning(null, message);
    }

    // ========== Circular Inheritance Dependency Detection ==========

    private boolean hasCycle(String current, Set<String> visited, Set<String> stack) {
        if (stack.contains(current)) {
            return true;
        }
        if (visited.contains(current)) {
            return false;
        }
        visited.add(current);
        stack.add(current);

        String superName = CompilerRegistry.globalSuperClassRegistry.get(current);
        if (superName != null && !superName.equals("java/lang/Object")) {
            if (hasCycle(superName, visited, stack)) {
                return true;
            }
        }
        String[] interfaces = CompilerRegistry.globalInterfaceRegistry.get(current);
        if (interfaces != null) {
            for (String inter : interfaces) {
                if (hasCycle(inter, visited, stack)) {
                    return true;
                }
            }
        }
        stack.remove(current);
        return false;
    }

    public boolean isAnnotation(String descOrName) {
        if (descOrName == null || descOrName.isEmpty()) return false;
        String clean = descOrName.trim();
        if (clean.startsWith("L") && clean.endsWith(";")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        clean = clean.replace('.', '/');
        if ("java/lang/annotation/Annotation".equals(clean)) return true;
        if (CompilerRegistry.globalAnnotationTypeRegistry.containsKey(clean) ||
            CompilerRegistry.globalAnnotationTypeRegistry.containsKey(OceanTypeSystem.findSimpleName(clean))) {
            return true;
        }
        if (CompilerRegistry.globalClassAccess.containsKey(clean)) {
            int access = CompilerRegistry.globalClassAccess.get(clean);
            if ((access & Opcodes.ACC_ANNOTATION) != 0) return true;
        }
        ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(clean);
        if (sym != null && (sym.getAccessFlags() & Opcodes.ACC_ANNOTATION) != 0) return true;
        return ClassMetadataCache.isAnnotation(clean);
    }

    private boolean hasAnnotationCycle(String current, Set<String> visited, Set<String> stack) {
        if (current == null) return false;
        String cleanCurrent = current.replace('.', '/');
        if (cleanCurrent.startsWith("L") && cleanCurrent.endsWith(";")) {
            cleanCurrent = cleanCurrent.substring(1, cleanCurrent.length() - 1);
        }
        if (stack.contains(cleanCurrent)) {
            return true;
        }
        if (visited.contains(cleanCurrent)) {
            return false;
        }
        visited.add(cleanCurrent);
        stack.add(cleanCurrent);

        CompilerRegistry.AnnotationTypeInfo info = CompilerRegistry.globalAnnotationTypeRegistry.get(cleanCurrent);
        if (info == null) {
            info = CompilerRegistry.globalAnnotationTypeRegistry.get(OceanTypeSystem.findSimpleName(cleanCurrent));
        }
        if (info != null && info.members() != null) {
            for (CompilerRegistry.AnnotationMemberInfo member : info.members().values()) {
                String memberDesc = member.descriptor();
                if (memberDesc != null) {
                    String clean = TypeChecker.cleanDescriptor(memberDesc);
                    while (clean.startsWith("[")) clean = clean.substring(1);
                    if (clean.startsWith("L") && clean.endsWith(";")) {
                        clean = clean.substring(1, clean.length() - 1);
                    }
                    clean = clean.replace('.', '/');
                    if (isAnnotation(clean)) {
                        if (hasAnnotationCycle(clean, visited, stack)) {
                            return true;
                        }
                    }
                }
            }
        } else {
            ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(cleanCurrent);
            if (sym == null) sym = CompilerRegistry.getClassSymbol(OceanTypeSystem.findSimpleName(cleanCurrent));
            if (sym != null && sym.getMethods() != null) {
                for (List<ocean.compiler.symbol.MethodSymbol> methodList : sym.getMethods().values()) {
                    for (ocean.compiler.symbol.MethodSymbol m : methodList) {
                        String memberDesc = m.getDescriptor();
                        if (memberDesc != null && memberDesc.contains(")")) {
                            String ret = memberDesc.substring(memberDesc.lastIndexOf(')') + 1);
                            String clean = TypeChecker.cleanDescriptor(ret);
                            while (clean.startsWith("[")) clean = clean.substring(1);
                            if (clean.startsWith("L") && clean.endsWith(";")) clean = clean.substring(1, clean.length() - 1);
                            clean = clean.replace('.', '/');
                            if (isAnnotation(clean)) {
                                if (hasAnnotationCycle(clean, visited, stack)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        stack.remove(cleanCurrent);
        return false;
    }

    private void collectAllInterfaces(String type, Set<String> result) {
        if (type == null || "java/lang/Object".equals(type)) return;
        String clean = type.replace('.', '/');
        String[] ifaces = CompilerRegistry.globalInterfaceRegistry.get(clean);
        if (ifaces == null) ifaces = CompilerRegistry.globalInterfaceRegistry.get(OceanTypeSystem.findSimpleName(clean));
        if (ifaces != null) {
            for (String iface : ifaces) {
                if (result.add(iface)) {
                    collectAllInterfaces(iface, result);
                }
            }
        }
        ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(clean);
        if (sym == null) sym = CompilerRegistry.getClassSymbol(OceanTypeSystem.findSimpleName(clean));
        if (sym != null && sym.getInterfaces() != null) {
            for (String iface : sym.getInterfaces()) {
                if (result.add(iface)) {
                    collectAllInterfaces(iface, result);
                }
            }
        }
        String sup = CompilerRegistry.globalSuperClassRegistry.get(clean);
        if (sup == null) sup = CompilerRegistry.globalSuperClassRegistry.get(OceanTypeSystem.findSimpleName(clean));
        if (sup != null && !"java/lang/Object".equals(sup)) {
            collectAllInterfaces(sup, result);
        }
        try {
            Class<?> cls = OceanTypeSystem.forName(clean.replace('/', '.'));
            if (cls != null) {
                for (Class<?> ifc : cls.getInterfaces()) {
                    String ifcName = ifc.getName().replace('.', '/');
                    if (result.add(ifcName)) {
                        collectAllInterfaces(ifcName, result);
                    }
                }
                if (cls.getSuperclass() != null && cls.getSuperclass() != Object.class) {
                    collectAllInterfaces(cls.getSuperclass().getName().replace('.', '/'), result);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void validateStaticMethodAgainstInterfaces(IRMethod node, String methodName, String desc) {
        Set<String> allIfaces = new HashSet<>();
        collectAllInterfaces(currentClassFqcn, allIfaces);
        for (String iface : allIfaces) {
            Map<String, String> ifaceMethods = CompilerRegistry.globalMethodRegistry.get(iface);
            if (ifaceMethods == null) {
                ifaceMethods = CompilerRegistry.globalMethodRegistry.get(OceanTypeSystem.findSimpleName(iface));
            }
            if (ifaceMethods != null && ifaceMethods.containsKey(methodName)) {
                String ifaceDesc = ifaceMethods.get(methodName);
                if (areParamsCompatible(desc, ifaceDesc)) {
                    int ifaceMethodAccess = CompilerRegistry.globalMethodAccess.getOrDefault(iface, Collections.emptyMap()).getOrDefault(methodName, 0);
                    boolean isIfaceMethodStatic = (ifaceMethodAccess & Opcodes.ACC_STATIC) != 0;
                    if (!isIfaceMethodStatic) {
                        reportError(node, "Static method '" + methodName + "' cannot hide instance method from interface '" + OceanTypeSystem.findSimpleName(iface) + "'.");
                        return;
                    }
                }
            }
            ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(iface);
            if (sym == null) sym = CompilerRegistry.getClassSymbol(OceanTypeSystem.findSimpleName(iface));
            if (sym != null && sym.getMethods() != null) {
                for (List<ocean.compiler.symbol.MethodSymbol> methodList : sym.getMethods().values()) {
                    for (ocean.compiler.symbol.MethodSymbol m : methodList) {
                        if (m.getName().equals(methodName)) {
                            if (areParamsCompatible(desc, m.getDescriptor())) {
                                if (!m.isStatic()) {
                                    reportError(node, "Static method '" + methodName + "' cannot hide instance method from interface '" + OceanTypeSystem.findSimpleName(iface) + "'.");
                                    return;
                                }
                            }
                        }
                    }
                }
            }
            try {
                Class<?> clazz = OceanTypeSystem.forName(iface.replace('/', '.'));
                if (clazz != null && clazz.isInterface()) {
                    for (Method m : clazz.getMethods()) {
                        if (m.getName().equals(methodName)) {
                            String ifaceDesc = Type.getMethodDescriptor(m);
                            if (areParamsCompatible(desc, ifaceDesc)) {
                                if (!Modifier.isStatic(m.getModifiers())) {
                                    reportError(node, "Static method '" + methodName + "' cannot hide instance method from interface '" + OceanTypeSystem.findSimpleName(iface) + "'.");
                                    return;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    // ========== Static Import Resolution ==========

    private String resolveStaticImportField(String name, IRNode node) {
        if (importedStaticMembers.containsKey(name)) {
            String owner = importedStaticMembers.get(name);
            String type = getStaticFieldType(owner, name);
            if (type != null) return type;
        }
        List<String> matchingTypes = new ArrayList<>();
        List<String> matchingOwners = new ArrayList<>();
        for (String owner : importedStaticWildcards) {
            String type = getStaticFieldType(owner, name);
            if (type != null) {
                matchingTypes.add(type);
                matchingOwners.add(owner);
            }
        }
        if (matchingOwners.size() > 1) {
            reportError(node, "Ambiguous static field reference '" + name + "'. Matches multiple static wildcard imports: " + matchingOwners);
            return null;
        }
        return matchingTypes.isEmpty() ? null : matchingTypes.getFirst();
    }

    private String getStaticFieldType(String owner, String name) {
        Map<String, String> fields = CompilerRegistry.globalFieldRegistry.get(owner);
        if (fields != null && fields.containsKey(name)) {
            Map<String, Boolean> staticity = CompilerRegistry.globalFieldStaticity.get(owner);
            if (staticity != null && staticity.getOrDefault(name, false)) {
                return fields.get(name);
            }
        }
        try {
            Class<?> clazz = OceanTypeSystem.forName(owner.replace("/", "."));
            Field f = findReflectedField(clazz, name);
            if (f != null && Modifier.isStatic(f.getModifiers())) {
                return Type.getDescriptor(f.getType());
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Field findReflectedField(Class<?> clazz, String name) {
        if (clazz == null || clazz == Object.class) return null;
        String key = clazz.getName() + "#" + name;
        Field cached = reflectedFieldCache.get(key);
        if (cached != null) {
            return cached == NO_FIELD ? null : cached;
        }
        Field found = searchReflectedField(clazz, name);
        reflectedFieldCache.put(key, found != null ? found : NO_FIELD);
        return found;
    }

    private Field searchReflectedField(Class<?> clazz, String name) {
        if (clazz == null || clazz == Object.class) return null;
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            for (Class<?> inter : clazz.getInterfaces()) {
                Field f = searchReflectedField(inter, name);
                if (f != null) return f;
            }
            return searchReflectedField(clazz.getSuperclass(), name);
        }
    }

    private String resolveStaticImportMethodOwner(String methodName, IRNode node) {
        if (importedStaticMembers.containsKey(methodName)) return importedStaticMembers.get(methodName);

        List<String> candidates = new ArrayList<>();
        for (String owner : importedStaticWildcards) {
            if (hasStaticMethod(owner, methodName)) candidates.add(owner);

        }
        if (candidates.size() > 1) {
            reportError(node, "Ambiguous static method reference '" + methodName + "'. Matches multiple static wildcard imports: " + candidates);
            return null;
        }
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private boolean hasStaticMethod(String owner, String methodName) {
        Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(owner);
        if (methods != null && methods.containsKey(methodName)) {
            Map<String, Boolean> staticity = CompilerRegistry.globalMethodStaticity.get(owner);
            if (staticity != null) {
                if (staticity.getOrDefault(methodName, false)) {
                    return true;
                }
                for (Map.Entry<String, Boolean> entry : staticity.entrySet()) {
                    if (entry.getKey().startsWith(methodName + "(") && Boolean.TRUE.equals(entry.getValue())) {
                        return true;
                    }
                }
            }
        }
        try {
            Class<?> clazz = OceanTypeSystem.forName(owner.replace("/", "."));
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(methodName) && Modifier.isStatic(m.getModifiers())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ========== Checked Exception Helpers ==========

    private boolean isCheckedException(String exceptionType) {
        if (exceptionType == null) return false;
        String cleanType = TypeChecker.cleanDescriptor(exceptionType);
        String internal = cleanType.startsWith("L") && cleanType.endsWith(";") ? cleanType.substring(1, cleanType.length() - 1) : cleanType;
        return checkedExceptionCache.computeIfAbsent(internal, k -> {
            boolean isThrowable = ClassMetadataCache.isSubtype(k, "java/lang/Throwable");
            boolean isRuntimeException = ClassMetadataCache.isSubtype(k, "java/lang/RuntimeException");
            boolean isError = ClassMetadataCache.isSubtype(k, "java/lang/Error");
            return isThrowable && !isRuntimeException && !isError;
        });
    }

    private void recordThrownException(String excDesc) {
        if (excDesc == null || tryBlockThrownExceptions.isEmpty()) return;
        String clean = TypeChecker.cleanDescriptor(excDesc);
        for (Set<String> set : tryBlockThrownExceptions) {
            set.add(clean);
        }
    }

    private String findFieldType(String ownerFqcn, String fieldName) {
        if (ownerFqcn == null || fieldName == null) return null;
        String key = ownerFqcn + "#" + fieldName;
        String cached = fieldTypeCache.get(key);
        if (cached != null) {
            return NO_FIELD_TYPE.equals(cached) ? null : cached;
        }
        String found = findFieldTypeImpl(ownerFqcn, fieldName);
        fieldTypeCache.put(key, found != null ? found : NO_FIELD_TYPE);
        return found;
    }

    private String findFieldTypeImpl(String ownerFqcn, String fieldName) {
        if (ownerFqcn == null || fieldName == null) return null;

        String clean = TypeChecker.cleanDescriptor(ownerFqcn);
        if (clean.contains("<")) {
            clean = clean.substring(0, clean.indexOf('<'));
        }
        Map<String, String> fields = CompilerRegistry.globalFieldRegistry.get(clean);
        if (fields != null && fields.containsKey(fieldName)) return fields.get(fieldName);
        String sup = CompilerRegistry.globalSuperClassRegistry.get(clean);
        if (sup != null && !sup.equals(clean)) {
            String inheritedType = findFieldTypeImpl(sup, fieldName);
            if (inheritedType != null) {
                String superSig = CompilerRegistry.globalSuperClassGenericSignatureRegistry.get(clean);
                if (superSig != null && superSig.contains("<")) {
                    List<CompilerRegistry.TypeParameterInfo> supTypeParams = CompilerRegistry.globalTypeParameterRegistry.get(sup);
                    if (supTypeParams != null && !supTypeParams.isEmpty()) {
                        int ltIdx = superSig.indexOf('<');
                        int gtIdx = superSig.lastIndexOf('>');
                        if (ltIdx > 0 && gtIdx > ltIdx) {
                            String inner = superSig.substring(ltIdx + 1, gtIdx);
                            List<String> typeArgs = splitGenericArgs(inner);
                            Map<String, String> paramMap = new HashMap<>();
                            for (int i = 0; i < supTypeParams.size(); i++) {
                                if (i < typeArgs.size()) {
                                    paramMap.put(supTypeParams.get(i).name, typeArgs.get(i));
                                }
                            }
                            inheritedType = substituteGenericSignature(inheritedType, paramMap);
                        }
                    }
                }
                return inheritedType;
            }
        }

        String[] ifaces = CompilerRegistry.globalInterfaceRegistry.get(clean);
        if (ifaces != null) {
            for (String iface : ifaces) {
                String inheritedType = findFieldType(iface, fieldName);
                if (inheritedType != null) return inheritedType;
            }
        }

        try {
            Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
            Field f = findReflectedField(clazz, fieldName);
            if (f != null) {
                return Type.getDescriptor(f.getType());
            }
            if (clazz != null) {
                for (Class<?> iface : clazz.getInterfaces()) {
                    Field fIf = findReflectedField(iface, fieldName);
                    if (fIf != null) return Type.getDescriptor(fIf.getType());
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String checkFieldAmbiguityAndResolveOwner(String targetOwner, String fieldName, IRNode node) {
        if (targetOwner == null || fieldName == null) return targetOwner;
        String clean = TypeChecker.cleanDescriptor(targetOwner);
        if (clean.startsWith("L") && clean.endsWith(";")) clean = clean.substring(1, clean.length() - 1);
        if (clean.contains("<")) clean = clean.substring(0, clean.indexOf('<'));

        // 1. If clean directly declares fieldName, it shadows all super/interface fields -> no ambiguity!
        Map<String, String> fields = CompilerRegistry.globalFieldRegistry.get(clean);
        if (fields != null && fields.containsKey(fieldName)) {
            return clean;
        }
        try {
            Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
            if (clazz != null) {
                Field f = findReflectedField(clazz, fieldName);
                if (f != null && f.getDeclaringClass() == clazz) {
                    return clean;
                }
            }
        } catch (Throwable ignored) {}

        // 2. Find all declaring types in superclasses and interfaces
        Set<String> declaringTypes = new LinkedHashSet<>();
        collectFieldDeclaringTypes(clean, fieldName, new HashSet<>(), declaringTypes);

        if (declaringTypes.isEmpty()) {
            return clean;
        }

        // 3. Filter by dominance (if I2 extends I1, I2 dominates I1)
        Set<String> nonDominated = new LinkedHashSet<>(declaringTypes);
        for (String t1 : declaringTypes) {
            for (String t2 : declaringTypes) {
                if (!t1.equals(t2) && isSubInterfaceOf(t2, t1)) {
                    nonDominated.remove(t1);
                }
            }
        }

        // 4. If multiple non-dominated providers remain -> Ambiguity Error!
        if (nonDominated.size() > 1) {
            Iterator<String> it = nonDominated.iterator();
            String if1 = OceanTypeSystem.findSimpleName(it.next());
            String if2 = OceanTypeSystem.findSimpleName(it.next());
            reportError(node, "Ambiguous field reference: '" + fieldName + "' is inherited from interfaces '" + if1 + "' and '" + if2 + "'. Qualify field to disambiguate.");
            return nonDominated.iterator().next();
        }

        return nonDominated.iterator().next();
    }

    private void collectFieldDeclaringTypes(String typeName, String fieldName, Set<String> visited, Set<String> declaringTypes) {
        if (typeName == null || !visited.add(typeName)) return;
        String clean = TypeChecker.cleanDescriptor(typeName);
        if (clean.startsWith("L") && clean.endsWith(";")) clean = clean.substring(1, clean.length() - 1);
        if (clean.contains("<")) clean = clean.substring(0, clean.indexOf('<'));

        // Does clean declare it?
        Map<String, String> fields = CompilerRegistry.globalFieldRegistry.get(clean);
        if (fields != null && fields.containsKey(fieldName)) {
            declaringTypes.add(clean);
            return; // Declared in clean -> shadows superclasses/interfaces for this branch
        }
        try {
            Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
            if (clazz != null) {
                Field f = findReflectedField(clazz, fieldName);
                if (f != null && f.getDeclaringClass() == clazz) {
                    declaringTypes.add(clean);
                    return;
                }
            }
        } catch (Throwable ignored) {}

        // Traverse superclass
        String sup = CompilerRegistry.globalSuperClassRegistry.get(clean);
        if (sup != null && !sup.equals(clean) && !sup.equals("java/lang/Object")) {
            collectFieldDeclaringTypes(sup, fieldName, visited, declaringTypes);
        }

        // Traverse interfaces
        String[] ifaces = CompilerRegistry.globalInterfaceRegistry.get(clean);
        if (ifaces != null) {
            for (String iface : ifaces) {
                collectFieldDeclaringTypes(iface, fieldName, visited, declaringTypes);
            }
        }

        try {
            Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
            if (clazz != null) {
                if (clazz.getSuperclass() != null && clazz.getSuperclass() != Object.class) {
                    collectFieldDeclaringTypes(Type.getInternalName(clazz.getSuperclass()), fieldName, visited, declaringTypes);
                }
                for (Class<?> iface : clazz.getInterfaces()) {
                    collectFieldDeclaringTypes(Type.getInternalName(iface), fieldName, visited, declaringTypes);
                }
            }
        } catch (Throwable ignored) {}
    }

    private boolean isFieldFinal(String ownerFqcn, String fieldName) {
        if (ownerFqcn == null || fieldName == null) return false;
        String clean = TypeChecker.cleanDescriptor(ownerFqcn);
        if (clean.startsWith("L") && clean.endsWith(";")) clean = clean.substring(1, clean.length() - 1);
        if (clean.contains("<")) clean = clean.substring(0, clean.indexOf('<'));

        if (isInterfaceType(clean)) {
            return true;
        }

        if (clean.startsWith("[") && "length".equals(fieldName)) {
            return true;
        }

        Map<String, Boolean> mutMap = CompilerRegistry.globalFieldMutability.get(clean);
        if (mutMap == null) {
            for (Map.Entry<String, Map<String, Boolean>> entry : CompilerRegistry.globalFieldMutability.entrySet()) {
                if (entry.getKey().equals(clean) || entry.getKey().endsWith("/" + clean) || clean.endsWith("/" + entry.getKey())) {
                    mutMap = entry.getValue();
                    break;
                }
            }
        }
        if (mutMap != null && mutMap.containsKey(fieldName)) {
            return Boolean.TRUE.equals(mutMap.get(fieldName));
        }

        Map<String, Integer> accMap = CompilerRegistry.globalFieldAccess.get(clean);
        if (accMap == null) {
            for (Map.Entry<String, Map<String, Integer>> entry : CompilerRegistry.globalFieldAccess.entrySet()) {
                if (entry.getKey().equals(clean) || entry.getKey().endsWith("/" + clean) || clean.endsWith("/" + entry.getKey())) {
                    accMap = entry.getValue();
                    break;
                }
            }
        }
        if (accMap != null && accMap.containsKey(fieldName)) {
            if ((accMap.get(fieldName) & Opcodes.ACC_FINAL) != 0) return true;
        }

        String sup = CompilerRegistry.globalSuperClassRegistry.get(clean);
        if (sup != null && !sup.equals(clean) && !"java/lang/Object".equals(sup)) {
            if (isFieldFinal(sup, fieldName)) return true;
        }

        String[] ifaces = CompilerRegistry.globalInterfaceRegistry.get(clean);
        if (ifaces != null) {
            for (String iface : ifaces) {
                if (isFieldFinal(iface, fieldName)) return true;
            }
        }

        try {
            Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
            Field f = findReflectedField(clazz, fieldName);
            if (f != null) {
                return Modifier.isFinal(f.getModifiers());
            }
            if (clazz != null) {
                for (Class<?> iface : clazz.getInterfaces()) {
                    Field fIf = findReflectedField(iface, fieldName);
                    if (fIf != null) return Modifier.isFinal(fIf.getModifiers());
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private boolean isFieldStatic(String ownerFqcn, String fieldName) {
        if (ownerFqcn == null || fieldName == null) return false;
        String clean = TypeChecker.cleanDescriptor(ownerFqcn);
        if (clean.startsWith("L") && clean.endsWith(";")) clean = clean.substring(1, clean.length() - 1);
        if (clean.contains("<")) clean = clean.substring(0, clean.indexOf('<'));

        Map<String, Boolean> statMap = CompilerRegistry.globalFieldStaticity.get(clean);
        if (statMap != null && statMap.containsKey(fieldName)) {
            return Boolean.TRUE.equals(statMap.get(fieldName));
        }

        Map<String, Integer> accMap = CompilerRegistry.globalFieldAccess.get(clean);
        if (accMap != null && accMap.containsKey(fieldName)) {
            if ((accMap.get(fieldName) & Opcodes.ACC_STATIC) != 0) return true;
        }

        String sup = CompilerRegistry.globalSuperClassRegistry.get(clean);
        if (sup != null && !sup.equals(clean) && !"java/lang/Object".equals(sup)) {
            if (isFieldStatic(sup, fieldName)) return true;
        }

        String[] ifaces = CompilerRegistry.globalInterfaceRegistry.get(clean);
        if (ifaces != null) {
            for (String iface : ifaces) {
                if (isFieldStatic(iface, fieldName)) return true;
            }
        }

        try {
            Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
            Field f = findReflectedField(clazz, fieldName);
            if (f != null) {
                return Modifier.isStatic(f.getModifiers());
            }
            if (clazz != null) {
                for (Class<?> iface : clazz.getInterfaces()) {
                    Field fIf = findReflectedField(iface, fieldName);
                    if (fIf != null) return Modifier.isStatic(fIf.getModifiers());
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private String cleanClassOwner(String owner) {
        if (owner == null) return null;
        String clean = TypeChecker.cleanDescriptor(owner);
        if (clean.startsWith("L") && clean.endsWith(";")) clean = clean.substring(1, clean.length() - 1);
        if (clean.contains("<")) clean = clean.substring(0, clean.indexOf('<'));
        return clean.replace('.', '/');
    }

    private boolean isOwnerAccessibleViaThis(String targetOwner) {
        if (targetOwner == null || currentClassFqcn == null) return false;
        String cleanTarget = cleanClassOwner(targetOwner);
        String curr = cleanClassOwner(currentClassFqcn);
        while (curr != null && !curr.isEmpty()) {
            if (curr.equals(cleanTarget)) {
                return true;
            }
            if (TypeChecker.isAssignable(OceanTypeSystem.wrapObjectType(cleanTarget), OceanTypeSystem.wrapObjectType(curr), session)) {
                return true;
            }
            //
            if (isStaticOrNoEnclosingInstance(curr)) {
                break;
            }
            int dollar = curr.lastIndexOf('$');
            if (dollar != -1) {
                curr = curr.substring(0, dollar);
            } else {
                break;
            }
        }
        return false;
    }

    private boolean isStaticOrNoEnclosingInstance(String className) {
        if (className == null) return true;
        String clean = cleanClassOwner(className);
        String simple = OceanTypeSystem.findSimpleName(clean);
        int access = CompilerRegistry.globalClassAccess.getOrDefault(clean, 0);
        if ((access & Opcodes.ACC_STATIC) != 0 || (access & Opcodes.ACC_INTERFACE) != 0 || (access & Opcodes.ACC_ENUM) != 0) {
            return true;
        }
        if (CompilerRegistry.globalIsInterfaceSet.contains(clean)) {
            return true;
        }
        if (CompilerRegistry.globalEnumConstants.containsKey(clean)) {
            return true;
        }
        try {
            Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
            if (clazz != null) {
                int mod = clazz.getModifiers();
                return Modifier.isStatic(mod) || clazz.isInterface() || clazz.isEnum();
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isMethodStatic(String owner, String methodName, String descriptor) {
        if (owner == null || methodName == null) return false;
        String clean = cleanClassOwner(owner);

        String curr = clean;
        while (curr != null && !curr.isEmpty() && !"java/lang/Object".equals(curr)) {
            Map<String, Boolean> staticMap = CompilerRegistry.globalMethodStaticity.get(curr);
            if (staticMap != null) {
                if (descriptor != null && staticMap.containsKey(methodName + descriptor)) {
                    return Boolean.TRUE.equals(staticMap.get(methodName + descriptor));
                }
                if (staticMap.containsKey(methodName)) {
                    return Boolean.TRUE.equals(staticMap.get(methodName));
                }
            }
            Map<String, Integer> accessMap = CompilerRegistry.globalMethodAccess.get(curr);
            if (accessMap != null) {
                if (descriptor != null && accessMap.containsKey(methodName + descriptor)) {
                    return (accessMap.get(methodName + descriptor) & Opcodes.ACC_STATIC) != 0;
                }
                if (accessMap.containsKey(methodName)) {
                    return (accessMap.get(methodName) & Opcodes.ACC_STATIC) != 0;
                }
            }
            curr = CompilerRegistry.globalSuperClassRegistry.get(curr);
        }
        try {
            Class<?> clazz = null;
            if (clean != null) {
                clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
            }
            if (clazz != null) {
                for (Method m : clazz.getMethods()) {
                    if (m.getName().equals(methodName)) {
                        if (descriptor == null || TypeChecker.cleanDescriptor(org.objectweb.asm.Type.getMethodDescriptor(m)).equals(TypeChecker.cleanDescriptor(descriptor))) {
                            return Modifier.isStatic(m.getModifiers());
                        }
                    }
                }
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().equals(methodName)) {
                        if (descriptor == null || TypeChecker.cleanDescriptor(org.objectweb.asm.Type.getMethodDescriptor(m)).equals(TypeChecker.cleanDescriptor(descriptor))) {
                            return Modifier.isStatic(m.getModifiers());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isMethodAbstract(String owner, String methodName, String descriptor) {
        if (owner == null || methodName == null) return false;
        String clean = TypeChecker.cleanDescriptor(owner);
        if (clean.startsWith("L") && clean.endsWith(";")) clean = clean.substring(1, clean.length() - 1);
        if (clean.contains("<")) clean = clean.substring(0, clean.indexOf('<'));
        clean = clean.replace('.', '/');

        Set<String> visited = new HashSet<>();
        String curr = clean;
        while (curr != null && !curr.isEmpty() && !"java/lang/Object".equals(curr) && visited.add(curr)) {
            Map<String, Integer> accessMap = CompilerRegistry.globalMethodAccess.get(curr);
            if (accessMap != null) {
                Integer acc = null;
                if (descriptor != null && accessMap.containsKey(methodName + descriptor)) {
                    acc = accessMap.get(methodName + descriptor);
                } else if (accessMap.containsKey(methodName)) {
                    acc = accessMap.get(methodName);
                }
                if (acc != null) {
                    return (acc & Opcodes.ACC_ABSTRACT) != 0;
                }
            }

            ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(curr);
            if (sym != null) {
                List<ocean.compiler.symbol.MethodSymbol> methods = sym.getMethods(methodName);
                if (methods != null && !methods.isEmpty()) {
                    for (ocean.compiler.symbol.MethodSymbol ms : methods) {
                        if (descriptor == null || areParamsCompatible(descriptor, ms.getDescriptor())) {
                            return (ms.getAccessFlags() & Opcodes.ACC_ABSTRACT) != 0;
                        }
                    }
                }
            }

            // Reflection lookup if curr is an already loaded or JDK class
            try {
                Class<?> clazz = OceanTypeSystem.forName(curr.replace('/', '.'));
                if (clazz != null) {
                    for (Method m : clazz.getDeclaredMethods()) {
                        if (m.getName().equals(methodName)) {
                            if (descriptor == null || areParamsCompatible(descriptor, org.objectweb.asm.Type.getMethodDescriptor(m))) {
                                return Modifier.isAbstract(m.getModifiers());
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            curr = CompilerRegistry.globalSuperClassRegistry.get(curr);
        }

        try {
            Class<?> c = OceanTypeSystem.forName(clean.replace('/', '.'));
            while (c != null && c != Object.class) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(methodName)) {
                        if (descriptor == null || areParamsCompatible(descriptor, org.objectweb.asm.Type.getMethodDescriptor(m))) {
                            return Modifier.isAbstract(m.getModifiers());
                        }
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private Long evaluateConstantInt(IRExpression expr,IRNode node) {
        if (expr instanceof IRLiteral lit) {
            if (lit.getValue() instanceof Number num) return num.longValue();
            if (lit.getValue() instanceof Character ch) return (long) ch;
        }
        if (expr instanceof IRUnaryOp unOp && unOp.getOperator() == IRUnaryOp.Op.NEG) {
            Long inner = evaluateConstantInt(unOp.getExpression(),node);
            if (inner != null) return -inner;
        }
        if (expr instanceof IRBinaryOp binOp) {
            Long left = evaluateConstantInt(binOp.getLeft(),node);
            Long right = evaluateConstantInt(binOp.getRight(),node);
            if (left != null && right != null) {
                boolean isDivOrMod = binOp.getOperator() == IRBinaryOp.Op.DIV || binOp.getOperator() == IRBinaryOp.Op.MOD;
                if (isDivOrMod && right == 0) {
                    reportWarning(node, "Division by zero.");
                }
                return switch (binOp.getOperator()) {
                    case ADD -> left + right;
                    case SUB -> left - right;
                    case MUL -> left * right;
                    case DIV -> right != 0 ? left / right : null;
                    case MOD -> right != 0 ? left % right : null;
                    default -> null;
                };
            }
        }
        return null;
    }

    private String substituteGenericSignature(String s, Map<String, String> paramMap) {
        return substituteGenericSignature(s, paramMap, 0);
    }

    private String substituteGenericSignature(String s, Map<String, String> paramMap, int depth) {
        if (s == null || paramMap == null || paramMap.isEmpty()) return s;
        if (depth > 8) return s;
        boolean isDesc = (s.startsWith("L") && s.endsWith(";")) || s.contains("/");
        if (s.startsWith("L") && s.endsWith(";")) {
            s = s.substring(1, s.length() - 1);
        }
        int ltIdx = s.indexOf('<');
        int gtIdx = s.lastIndexOf('>');
        if (ltIdx < 0 || gtIdx < ltIdx) {
            if (paramMap.containsKey(s)) {
                String val = paramMap.get(s);
                return isDesc ? ensureDescriptor(val) : (TypeChecker.isClassType(val) ? val.substring(1, val.length() - 1) : val);
            }
            return isDesc ? "L" + s.replace('.', '/') + ";" : s;
        }

        String base = s.substring(0, ltIdx).trim().replace('.', '/');
        String qualifiedBase = base;
        if (!base.contains("/")) {
            String std = OceanTypeSystem.resolveStandardClassPath(base);
            if (std != null) {
                qualifiedBase = std;
            } else {
                String desc = ensureDescriptor(base);
                if (TypeChecker.isClassType(desc)) {
                    qualifiedBase = desc.substring(1, desc.length() - 1);
                }
            }
        }
        String inner = s.substring(ltIdx + 1, gtIdx).trim();
        List<String> args = splitGenericArgs(inner);
        StringBuilder sb = new StringBuilder();
        if (isDesc) sb.append("L");
        sb.append(qualifiedBase).append("<");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(",");
            String arg = args.get(i).trim();
            String prefix = "";
            String bound = arg;
            if (bound.startsWith("? super ")) {
                prefix = "-";
                bound = bound.substring(8).trim();
            } else if (bound.startsWith("?super")) {
                prefix = "-";
                bound = bound.substring(6).trim();
            } else if (bound.startsWith("? extends ")) {
                prefix = "+";
                bound = bound.substring(10).trim();
            } else if (bound.startsWith("?extends")) {
                prefix = "+";
                bound = bound.substring(8).trim();
            } else if (bound.startsWith("-") || bound.startsWith("+")) {
                prefix = bound.substring(0, 1);
                bound = bound.substring(1).trim();
            } else if (bound.equals("?") || bound.equals("*")) {
                sb.append("*");
                continue;
            }

            String cleanBound = bound;
            if (cleanBound.startsWith("L") && cleanBound.endsWith(";")) {
                cleanBound = cleanBound.substring(1, cleanBound.length() - 1);
            }
            if (paramMap.containsKey(bound)) {
                sb.append(prefix).append(ensureDescriptor(paramMap.get(bound)));
            } else if (paramMap.containsKey(cleanBound)) {
                sb.append(prefix).append(ensureDescriptor(paramMap.get(cleanBound)));
            } else if (bound.contains("<")) {
                sb.append(prefix).append(substituteGenericSignature(bound, paramMap, depth + 1));
            } else {
                boolean isKnownTypeParam = (symbolTable != null && symbolTable.getTypeParams() != null
                        && (symbolTable.getTypeParams().contains(bound) || symbolTable.getTypeParams().contains(cleanBound)));
                if (isKnownTypeParam || (bound.length() == 1 && Character.isUpperCase(bound.charAt(0)))) {
                    sb.append(prefix).append(OceanTypeSystem.OBJECT_DESC);
                } else {
                    sb.append(prefix).append(ensureDescriptor(bound));
                }
            }
        }
        sb.append(">");
        if (isDesc) sb.append(";");
        return sb.toString();
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

    private String ensureDescriptor(String typeToken) {
        if (typeToken == null || typeToken.isEmpty()) return OceanTypeSystem.OBJECT_DESC;
        typeToken = typeToken.trim();

        //
        if (typeToken.equals("?") || typeToken.equals("*")) {
            return "*";
        }
        if (typeToken.startsWith("?extends") || typeToken.startsWith("? extends ") || typeToken.startsWith("?<:") || typeToken.startsWith("? <: ")) {
            String bound;
            if (typeToken.startsWith("? extends ")) bound = typeToken.substring(10).trim();
            else if (typeToken.startsWith("?extends")) bound = typeToken.substring(8).trim();
            else if (typeToken.startsWith("? <: ")) bound = typeToken.substring(5).trim();
            else bound = typeToken.substring(3).trim();
            return "+" + ensureDescriptor(bound);
        }
        if (typeToken.startsWith("?super") || typeToken.startsWith("? super ") || typeToken.startsWith("?>:") || typeToken.startsWith("? >: ")) {
            String bound;
            if (typeToken.startsWith("? super ")) bound = typeToken.substring(8).trim();
            else if (typeToken.startsWith("?super")) bound = typeToken.substring(6).trim();
            else if (typeToken.startsWith("? >: ")) bound = typeToken.substring(5).trim();
            else bound = typeToken.substring(3).trim();
            return "-" + ensureDescriptor(bound);
        }

        String variancePrefix = "";
        if (typeToken.startsWith("+") || typeToken.startsWith("-") || typeToken.startsWith("*")) {
            variancePrefix = typeToken.substring(0, 1);
            typeToken = typeToken.substring(1).trim();
        }

        boolean nullable = false;
        while (typeToken.endsWith("?")) {
            nullable = true;
            typeToken = typeToken.substring(0, typeToken.length() - 1).trim();
        }

        int arrayDim = 0;
        while (typeToken.endsWith("[]")) {
            arrayDim++;
            typeToken = typeToken.substring(0, typeToken.length() - 2).trim();
            while (typeToken.endsWith("?")) {
                nullable = true;
                typeToken = typeToken.substring(0, typeToken.length() - 1).trim();
            }
        }
        String arrayPrefix = "[".repeat(arrayDim);

        if (typeToken.contains("<")) {
            if (typeToken.startsWith("L") && typeToken.endsWith(";")) {
                int lt = typeToken.indexOf('<');
                String rawBase = typeToken.substring(1, lt).trim();
                if (rawBase.contains("/")) {
                    return variancePrefix + arrayPrefix + (nullable ? typeToken + "?" : typeToken);
                }
            }
            int ltIdx = typeToken.indexOf('<');
            int gtIdx = typeToken.lastIndexOf('>');
            if (ltIdx > 0 && gtIdx > ltIdx) {
                String base = typeToken.substring(0, ltIdx).trim();
                String inner = typeToken.substring(ltIdx + 1, gtIdx).trim();
                List<String> args = splitGenericArgs(inner);
                String baseDesc = ensureDescriptor(base);
                if (baseDesc.endsWith("?")) baseDesc = baseDesc.substring(0, baseDesc.length() - 1);
                if (baseDesc.endsWith(";")) baseDesc = baseDesc.substring(0, baseDesc.length() - 1);
                StringBuilder sb = new StringBuilder(baseDesc).append("<");
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(ensureDescriptor(args.get(i)));
                }
                sb.append(">;");
                return variancePrefix + arrayPrefix + (nullable ? sb + "?" : sb);
            }
        }
        if (TypeChecker.isClassType(typeToken)) return variancePrefix + arrayPrefix + (nullable ? typeToken + "?" : typeToken);
        if (typeToken.length() == 1 && "ZBCSIJFD".indexOf(typeToken.charAt(0)) >= 0) {
            String b = arrayDim > 0 ? typeToken : TypeChecker.box(typeToken);
            return variancePrefix + arrayPrefix + (nullable ? b + "?" : b);
        }
        String prim = OceanTypeSystem.word2TypeForPrimitive(typeToken);
        if (prim != null) {
            String b = arrayDim > 0 ? prim : TypeChecker.box(prim);
            return variancePrefix + arrayPrefix + (nullable ? b + "?" : b);
        }
        String desc = SymbolTable.getDescriptor(typeToken, importedClasses, null);
        if (desc == null) desc = OceanTypeSystem.OBJECT_DESC;
        return variancePrefix + arrayPrefix + (nullable ? (desc.endsWith("?") ? desc : desc + "?") : desc);
    }

    private boolean isExceptionHandled(String thrownType) {
        if (!isCheckedException(thrownType)) return true;
        String cleanThrown = TypeChecker.cleanDescriptor(thrownType);
        String thrownDesc = OceanTypeSystem.wrapObjectType(cleanThrown);
        for (List<String> catches : caughtExceptionsStack) {
            for (String caughtType : catches) {
                String cleanCaught = TypeChecker.cleanDescriptor(caughtType);
                String caughtDesc = cleanCaught.startsWith("L") ? cleanCaught : OceanTypeSystem.wrapObjectType(cleanCaught);
                if (TypeChecker.isAssignable(caughtDesc, thrownDesc, session)) return true;
            }
        }
        for (String declaredType : currentMethodDeclaredThrows) {
            String cleanDeclared = TypeChecker.cleanDescriptor(declaredType);
            String declaredDesc = OceanTypeSystem.wrapObjectType(cleanDeclared);
            if (TypeChecker.isAssignable(declaredDesc, thrownDesc, session)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSystemClass(String className) {
        return ClassMetadataCache.isSystemClass(className);
    }

    // ========== P3-1: Access Control Checks ==========

    private void checkClassAccess(String targetClass, IRNode node) {
        if (targetClass == null) return;
        if (isSystemClass(targetClass)) return;

        String currentPackage = getFilePackage();
        String targetPackage = "";
        int lastSlash = targetClass.lastIndexOf('/');

        if (lastSlash != -1) targetPackage = targetClass.substring(0, lastSlash);

        int classAccess = Opcodes.ACC_PUBLIC;
        if (CompilerRegistry.globalClassAccess.containsKey(targetClass)) {
            classAccess = CompilerRegistry.globalClassAccess.get(targetClass);
        } else {
            try {
                Class<?> clazz = OceanTypeSystem.forName(targetClass.replace('/', '.'));
                classAccess = clazz.getModifiers();
            } catch (Throwable ignored) {}
        }

        if (!targetPackage.equals(currentPackage)) {
            if ((classAccess & Opcodes.ACC_PUBLIC) == 0) {
                reportError(node, "Class '" + targetClass + "' is package-private and cannot be accessed from outside its package.");
            }
        }
    }
    private boolean isSameNest(String classA, String classB) {
        if (classA == null || classB == null) return false;
        String hostA = getTopLevelClass(classA);
        String hostB = getTopLevelClass(classB);
        return hostA.equals(hostB);
    }

    private String getTopLevelClass(String className) {
        if (className == null) return "";
        int dollarIndex = className.indexOf('$');
        if (dollarIndex != -1) {
            return className.substring(0, dollarIndex);
        }
        return className;
    }

    private void checkAccess(String targetOwner, String memberName, boolean isMethod, IRNode node) {
        if (targetOwner == null || memberName == null) return;
        if (isSystemClass(targetOwner)) return;

        targetOwner = resolveTypeName(targetOwner).replace('.', '/');

        String currentClass = currentClassFqcn;
        if (isSameNest(currentClass, targetOwner)) return;

        int accessFlags = -1;
        String resolvedOwner = targetOwner;
        Set<String> visited = new HashSet<>();

        while (resolvedOwner != null && visited.add(resolvedOwner)) {
            if (isMethod) {
                Map<String, Integer> methodAccessMap = CompilerRegistry.globalMethodAccess.get(resolvedOwner);
                if (methodAccessMap != null) {
                    if (methodAccessMap.containsKey(memberName)) {
                        accessFlags = methodAccessMap.get(memberName);
                        break;
                    }
                    for (Map.Entry<String, Integer> entry : methodAccessMap.entrySet()) {
                        if (entry.getKey().equals(memberName) || entry.getKey().startsWith(memberName + "(")) {
                            accessFlags = entry.getValue();
                            break;
                        }
                    }
                    if (accessFlags != -1) break;
                }
            } else {
                Map<String, Integer> fieldAccessMap = CompilerRegistry.globalFieldAccess.get(resolvedOwner);
                if (fieldAccessMap != null && fieldAccessMap.containsKey(memberName)) {
                    accessFlags = fieldAccessMap.get(memberName);
                    break;
                }
            }
            resolvedOwner = CompilerRegistry.globalSuperClassRegistry.get(resolvedOwner);
        }

        if (accessFlags == -1) {
            try {
                Class<?> clazz = OceanTypeSystem.forName(targetOwner.replace('/', '.'));
                if (isMethod) {
                    for (Method m : clazz.getDeclaredMethods()) {
                        if (m.getName().equals(memberName)) {
                            accessFlags = m.getModifiers();
                            break;
                        }
                    }
                } else {
                    Field f = findReflectedField(clazz, memberName);
                    if (f != null) {
                        accessFlags = f.getModifiers();
                    }
                }
            } catch (Throwable ignored) {}
            if (accessFlags == -1) {
                accessFlags = Opcodes.ACC_PUBLIC;
            }
        }

        if ((accessFlags & Opcodes.ACC_PRIVATE) != 0) {
            reportError(node, (isMethod ? "Method '" : "Field '") + memberName + "' has private access in '" + targetOwner + "'");
            return;
        }

        boolean isPublic = (accessFlags & Opcodes.ACC_PUBLIC) != 0;
        boolean isProtected = (accessFlags & Opcodes.ACC_PROTECTED) != 0;
        boolean isPrivate = (accessFlags & Opcodes.ACC_PRIVATE) != 0;

        String targetPackage = "";
        int lastSlash = targetOwner.lastIndexOf('/');
        if (lastSlash != -1) {
            targetPackage = targetOwner.substring(0, lastSlash);
        }

        if (!isPublic && !isProtected && !isPrivate) {
            String cleanCurrentPkg = getFilePackage();
            if (!targetPackage.equals(cleanCurrentPkg)) {
                reportError(node, (isMethod ? "Method '" : "Field '") + memberName + "' is package-private and cannot be accessed from outside its package");
                return;
            }
        }

        if (isProtected) {
            String normalizedCurrentPkg = getFilePackage();
            if (!targetPackage.equals(normalizedCurrentPkg)) {
                boolean isSubclass = false;
                Set<String> subVisited = new HashSet<>();
                String parent = CompilerRegistry.globalSuperClassRegistry.get(currentClass);
                while (parent != null && subVisited.add(parent)) {
                    if (parent.equals(targetOwner)) {
                        isSubclass = true;
                        break;
                    }
                    parent = CompilerRegistry.globalSuperClassRegistry.get(parent);
                }
                if (!isSubclass) {
                    reportError(node, (isMethod ? "Method '" : "Field '") + memberName + "' has protected access in '" + targetOwner + "'");
                }
            }
        }
    }

    private void checkMethodAccess(String targetOwner, String memberName, IRNode node) {
        checkAccess(targetOwner, memberName, true, node);
    }

    private void checkConstructorAccess(String targetOwner, IRNewObject node) {
        if (targetOwner == null) return;

        if (isSystemClass(targetOwner)) return;

        targetOwner = resolveTypeName(targetOwner).replace('.', '/');

        String currentClass = currentClassFqcn;
        if (currentClass != null && currentClass.equals(targetOwner)) return;

        int accessFlags = -1;
        Map<String, Integer> methodAccessMap = CompilerRegistry.globalMethodAccess.get(targetOwner);
        if (methodAccessMap != null) {
            StringBuilder argDescBuilder = new StringBuilder("(");
            for (IRExpression arg : node.getArguments()) {
                String aDesc = arg.getTypeDescriptor();
                if (aDesc == null && arg instanceof IRVariableAccess va) {
                    aDesc = symbolTable.getType(va.getName());
                }
                if (aDesc == null) aDesc = OceanTypeSystem.OBJECT_DESC;
                argDescBuilder.append(TypeChecker.cleanDescriptor(aDesc));
            }
            argDescBuilder.append(")V");
            String specificKey = "<init>" + argDescBuilder;

            if (methodAccessMap.containsKey(specificKey)) {
                accessFlags = methodAccessMap.get(specificKey);
            } else if (node.getArguments().isEmpty() && methodAccessMap.containsKey("<init>()V")) {
                accessFlags = methodAccessMap.get("<init>()V");
            } else {
                int targetArgCount = node.getArguments().size();
                for (Map.Entry<String, Integer> entry : methodAccessMap.entrySet()) {
                    if (entry.getKey().startsWith("<init>(")) {
                        String desc = entry.getKey().substring("<init>".length());
                        try {
                            Type[] argTypes = Type.getArgumentTypes(desc);
                            if (argTypes.length == targetArgCount) {
                                accessFlags = entry.getValue();
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                if (accessFlags == -1 && methodAccessMap.containsKey("<init>")) {
                    accessFlags = methodAccessMap.get("<init>");
                }
            }
        }

        if (accessFlags == -1) {
            try {
                Class<?> clazz = OceanTypeSystem.forName(targetOwner.replace('/', '.'));
                for (Constructor<?> c : clazz.getDeclaredConstructors()) {
                    if (c.getParameterCount() == node.getArguments().size()) {
                        accessFlags = c.getModifiers();
                        break;
                    }
                }
            } catch (Throwable ignored) {}
            if (accessFlags == -1) {
                accessFlags = Opcodes.ACC_PUBLIC;
            }
        }

        if ((accessFlags & Opcodes.ACC_PRIVATE) != 0) {
            reportError(node, "Constructor '" + targetOwner + "' has private access and cannot be invoked from outside");
            return;
        }

        boolean isPublic = (accessFlags & Opcodes.ACC_PUBLIC) != 0;
        boolean isProtected = (accessFlags & Opcodes.ACC_PROTECTED) != 0;
        boolean isPrivate = (accessFlags & Opcodes.ACC_PRIVATE) != 0;

        String targetPackage = "";
        int lastSlash = targetOwner.lastIndexOf('/');

        if (lastSlash != -1) targetPackage = targetOwner.substring(0, lastSlash);


        if (!isPublic && !isProtected && !isPrivate) {
            String cleanCurrentPkg = getFilePackage();
            if (!targetPackage.equals(cleanCurrentPkg)) reportError(node, "Constructor '" + targetOwner + "' is package-private and cannot be accessed from outside its package");
        }
    }

    // ========== Visitor Implementation ==========

    @Override
    public void visitCompilationUnit(IRCompilationUnit node) {
        if (session != null) {
            session.setCurrentFile(currentFile);
        }
        for (IRNode type : node.getTypes()) {
            type.accept(this);
        }
    }

    @Override
    public void visitClass(IRClass node) {
        String oldClassFqcn = currentClassFqcn;
        String oldClassSimple = currentClassSimpleName;
        String oldSuperName = currentSuperName;

        currentClassFqcn = node.getName();
        currentClassSimpleName = OceanTypeSystem.findSimpleName(currentClassFqcn);
        currentSuperName = node.getSuperName();
        List<String> oldBlankFinals = new ArrayList<>(currentClassBlankFinalFields);
        List<String> oldBlankStaticFinals = new ArrayList<>(currentClassBlankStaticFinalFields);
        Set<String> oldDAStaticFinals = new HashSet<>(definitelyAssignedBlankStaticFinalFields);
        Set<String> oldPAStaticFinals = new HashSet<>(potentiallyAssignedBlankStaticFinalFields);
        boolean oldIsAbstract = currentClassIsAbstract;
        boolean oldIsInterface = currentClassIsInterface;
        boolean oldIsEnum = currentClassIsEnum;

        CompilationSession session = CompilationSession.getActiveSession();
        String oldSessionClass = null;
        String oldSessionPkg = null;
        String oldSessionFile = null;
        if (session != null) {
            oldSessionClass = session.getCurrentClassFqcn();
            oldSessionPkg = session.getCurrentPackage();
            oldSessionFile = session.getCurrentFile();
            session.setCurrentClassFqcn(currentClassFqcn);
            String pkg = getFilePackage();
            session.setCurrentPackage(pkg.replace('/', '.'));
            session.setCurrentFile(currentFile);
        }

        Set<String> oldTypeParams = new HashSet<>(symbolTable.getTypeParams());
        symbolTable.enterScope();
        try {
            List<CompilerRegistry.TypeParameterInfo> classTpList = CompilerRegistry.globalTypeParameterRegistry.get(currentClassFqcn);
            if (classTpList != null) {
                for (CompilerRegistry.TypeParameterInfo tp : classTpList) {
                    symbolTable.getTypeParams().add(tp.name);
                }

                // Bağımlı bound sıra doğrulaması: class Foo<U, T <: U> tanımında
                // bound olarak kullanılan parametrenin T'den önce bildirilmiş olması gerekir.
                for (int tpIdx = 0; tpIdx < classTpList.size(); tpIdx++) {
                    CompilerRegistry.TypeParameterInfo tp = classTpList.get(tpIdx);

                    // Upper bound bağımlılıkları (T <: U)
                    for (String depParamName : tp.dependentUpperBoundParams) {
                        int boundIdx = -1;
                        for (int k = 0; k < classTpList.size(); k++) {
                            if (classTpList.get(k).name.equals(depParamName)) { boundIdx = k; break; }
                        }
                        if (boundIdx > tpIdx) {
                            reportError(node, "Type parameter '" + tp.name + "' bounds to '" + depParamName + "' which is declared after '" + tp.name + "'. Forward type parameter references in bounds are not allowed.");
                        }
                    }

                    // Lower bound bağımlılıkları (T >: U)
                    if (tp.dependentLowerBoundParam != null) {
                        int boundIdx = -1;
                        for (int k = 0; k < classTpList.size(); k++) {
                            if (classTpList.get(k).name.equals(tp.dependentLowerBoundParam)) { boundIdx = k; break; }
                        }
                        if (boundIdx > tpIdx) {
                            reportError(node, "Type parameter '" + tp.name + "' lower bound references '" + tp.dependentLowerBoundParam + "' which is declared after '" + tp.name + "'. Forward type parameter references in bounds are not allowed.");
                        }
                    }
                }
            }

            // Check Circular Inheritance Dependency
            Set<String> visitedCycle = new HashSet<>();
            Set<String> stackCycle = new HashSet<>();
            if (hasCycle(currentClassFqcn, visitedCycle, stackCycle)) {
                reportError(node, "Cyclic inheritance involving class '" + currentClassSimpleName + "'.");
            }

            int access = node.getAccessFlags();
            boolean isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0 || node.isAbstract();
            boolean isFinal = (access & Opcodes.ACC_FINAL) != 0;
            boolean isInterface = (access & Opcodes.ACC_INTERFACE) != 0 || CompilerRegistry.globalIsInterfaceSet.contains(currentClassFqcn);
            boolean isSealed = node.isSealed() || CompilerRegistry.globalSealedClassSet.contains(currentClassFqcn);
            boolean isDataClass = node.isDataClass() || CompilerRegistry.globalDataClassSet.contains(currentClassFqcn);
            currentClassIsData = isDataClass;
            boolean isAnnotation = (access & Opcodes.ACC_ANNOTATION) != 0;
            boolean isEnum = CompilerRegistry.globalEnumConstants.containsKey(currentClassFqcn) || ((access & Opcodes.ACC_ENUM) != 0);
            currentClassIsAbstract = isAbstract;
            currentClassIsInterface = isInterface;
            currentClassIsEnum = isEnum;
            if (isInterface && !isAnnotation) {
                for (IRMethod m : node.getMethods()) {
                    if (m.isNative()) {
                        reportError(m, "Native methods cannot be declared in interfaces.");
                    }
                }
            }
            if (isAnnotation) {
                Set<String> visitedAnno = new HashSet<>();
                Set<String> stackAnno = new HashSet<>();
                if (hasAnnotationCycle(currentClassFqcn, visitedAnno, stackAnno)) {
                    reportError(node, "Cyclic dependency involving annotation '" + currentClassSimpleName + "'.");
                }
                for (IRMethod m : node.getMethods()) {
                    if (m.getBody() != null) {
                        reportError(m, "Annotation methods cannot declare a body.");
                    }
                    if (m.isNative()) {
                        reportError(m, "Native methods cannot be declared in annotations.");
                    }
                    if (m.isStatic() || (m.getAccessFlags() & Opcodes.ACC_STATIC) != 0) {
                        reportError(m, "Annotation methods cannot be declared 'static': '" + m.getName() + "'.");
                    }
                    if ((m.getAccessFlags() & Opcodes.ACC_FINAL) != 0) {
                        reportError(m, "Annotation methods cannot be declared 'final': '" + m.getName() + "'.");
                    }
                    if ((m.getAccessFlags() & Opcodes.ACC_SYNCHRONIZED) != 0) {
                        reportError(m, "Annotation methods cannot be declared 'sync': '" + m.getName() + "'.");
                    }
                    if ((m.getAccessFlags() & (Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) != 0) {
                        reportError(m, "Annotation methods cannot be declared 'private' or 'protected': '" + m.getName() + "'.");
                    }
                    String desc = m.getDescriptor();
                    String retType = desc != null && desc.contains(")") ? desc.substring(desc.indexOf(')') + 1) : null;
                    if (retType != null && !isValidAnnotationMemberType(retType)) {
                        reportError(m, "Invalid return type for annotation member '" + m.getName() + "': '" + TypeChecker.humanReadable(retType) + "'. Only primitive types, String, Class, Enum, Annotation, or 1D arrays thereof are allowed.");
                    }
                }
            }

            validateAnnotations(node.getAnnotations(), "TYPE", node);

            // 1. Modifier Conflict Checks
            if (isAbstract && isFinal) {
                reportError(node, "Class '" + currentClassSimpleName + "' cannot be both 'abstract' and 'final'.");
            }
            if (isDataClass) {
                if (isAbstract || isSealed) {
                    reportError(node, "Data class '" + currentClassSimpleName + "' cannot be abstract or sealed.");
                }
                List<CompilerRegistry.RecordComponentInfo> components = CompilerRegistry.globalRecordComponents.get(currentClassFqcn);
                if (components != null) {
                    for (CompilerRegistry.RecordComponentInfo comp : components) {
                        if (PreScanner.FORBIDDEN_RECORD_COMPONENT_NAMES.contains(comp.name())) {
                            reportError(node, "Data class component cannot be named '" + comp.name() + "' as it conflicts with Object methods.");
                        }
                    }
                }
            }

            // 2. Inheritance Checks
            if (currentSuperName != null && !currentSuperName.isEmpty() && !"java/lang/Object".equals(currentSuperName)) {
                checkClassAccess(currentSuperName, node);
                boolean isEnumConstantSubclass = (CompilerRegistry.globalClassAccess.getOrDefault(currentClassFqcn, 0) & Opcodes.ACC_ENUM) != 0;
                ocean.compiler.symbol.ClassSymbol cs = CompilerRegistry.getClassSymbol(currentClassFqcn);
                if (cs != null && cs.isEnum()) {
                    isEnumConstantSubclass = true;
                }
                if (isEnumClass(currentSuperName) && !currentClassIsEnum && !isEnumConstantSubclass) {
                    if ("java/lang/Enum".equals(currentSuperName)) {
                        reportError(node, "Class '" + currentClassSimpleName + "' cannot directly inherit from java.lang.Enum.");
                    } else {
                        reportError(node, "Class '" + currentClassSimpleName + "' cannot inherit from enum '" + OceanTypeSystem.findSimpleName(currentSuperName) + "'.");
                    }
                }
                if ("java/lang/Record".equals(currentSuperName) || "java.lang.Record".equals(currentSuperName) || "Record".equals(currentSuperName)) {
                    reportError(node, "Class '" + currentClassSimpleName + "' cannot directly inherit from java.lang.Record.");
                }
                if (classTpList != null && !classTpList.isEmpty()) {
                    if (isSubclassOfThrowable(currentSuperName)) {
                        reportError(node, "Generic class '" + currentClassSimpleName + "' cannot extend java.lang.Throwable.");
                    }
                }

                if (isInterfaceType(currentSuperName)) {
                    reportError(node, "Class '" + currentClassSimpleName + "' cannot extend interface '" + currentSuperName + "' (use 'implements' instead).");
                }

                /*// Check if current class is a data class and extends another class
                if (isDataClass && currentSuperName != null && !currentSuperName.equals("java/lang/Object") && !currentSuperName.equals("java/lang/Record")) {
                    reportError(node, "Data class '" + currentClassSimpleName + "' cannot extend another class.");
                }*/

                // Check if superclass is a data class (record)
                boolean isSuperDataClass = CompilerRegistry.globalDataClassSet.contains(currentSuperName) ;
                if (!isSuperDataClass) {
                    ocean.compiler.symbol.ClassSymbol superSym = CompilerRegistry.getClassSymbol(currentSuperName);
                    if (superSym != null && superSym.isDataClass()) isSuperDataClass = true;
                }
                if (!isSuperDataClass) {
                    try {
                        Class<?> clazz = OceanTypeSystem.forName(currentSuperName.replace('/', '.'));
                        if (clazz != null && clazz.isRecord()) isSuperDataClass = true;
                    } catch (Throwable ignored) {}
                }
                /*if (isSuperDataClass) {
                    reportError(node, "Class '" + currentClassSimpleName + "' cannot extend data class '" + currentSuperName + "' (data classes are implicitly final).");
                }*/

                // Check if superclass is final
                boolean isSuperFinal = false;
                if (CompilerRegistry.globalClassAccess.containsKey(currentSuperName)) {
                    int superAccess = CompilerRegistry.globalClassAccess.get(currentSuperName);
                    isSuperFinal = (superAccess & Opcodes.ACC_FINAL) != 0;
                } else if ("final".equals(CompilerRegistry.globalSubclassStatusRegistry.get(currentSuperName))
                        || "final".equals(CompilerRegistry.globalSubclassStatusRegistry.get(OceanTypeSystem.findSimpleName(currentSuperName)))) {
                    isSuperFinal = true;
                } else {
                    try {
                        Class<?> clazz = OceanTypeSystem.forName(currentSuperName.replace('/', '.'));
                        isSuperFinal = Modifier.isFinal(clazz.getModifiers());
                    } catch (Throwable ignored) {}
                }
                if (!isSuperDataClass && isSuperFinal) {
                    reportError(node, "Cannot inherit from final class '" + currentSuperName + "'.");
                }

                boolean isDataOrEnum = node.isDataClass() || CompilerRegistry.globalDataClassSet.contains(currentClassFqcn)
                        || CompilerRegistry.globalEnumConstants.containsKey(currentClassFqcn) || "java/lang/Enum".equals(node.getSuperName());

                // Sealed class inheritance check
                if (CompilerRegistry.globalSealedClassSet.contains(currentSuperName)) {
                    List<String> permitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(currentSuperName);
                    if (permitted != null && !permitted.contains(currentClassFqcn)) {
                        reportError(node, "Class '" + currentClassSimpleName + "' is not permitted to extend sealed class '" + currentSuperName + "'.");
                    }
                    String superPkg = currentSuperName.contains("/") ? currentSuperName.substring(0, currentSuperName.lastIndexOf('/')) : "";
                    String subPkg = currentClassFqcn.contains("/") ? currentClassFqcn.substring(0, currentClassFqcn.lastIndexOf('/')) : "";
                    if (!superPkg.equals(subPkg)) {
                        reportError(node, "Permitted subclass '" + currentClassSimpleName + "' in package '" + (subPkg.isEmpty() ? "unnamed" : subPkg) + "' cannot extend sealed class '" + OceanTypeSystem.findSimpleName(currentSuperName) + "' in package '" + (superPkg.isEmpty() ? "unnamed" : superPkg) + "': sealed class and its permitted subclasses must belong to the same package.");
                    }

                    String status = CompilerRegistry.globalSubclassStatusRegistry.get(currentClassFqcn);
                    if (status == null && !isFinal && !node.isSealed() && !node.isNonSealed() && !isDataOrEnum) {
                        reportError(node, "Class '" + currentClassSimpleName + "' extending or implementing a sealed type must be declared 'final', 'sealed', or 'non-sealed'.");
                    }
                }
            }

            //
            boolean isNonSealed = node.isNonSealed() || "non-sealed".equals(CompilerRegistry.globalSubclassStatusRegistry.get(currentClassFqcn));
            if (isNonSealed) {
                boolean superIsSealed = currentSuperName != null && !"java/lang/Object".equals(currentSuperName) &&
                        CompilerRegistry.globalSealedClassSet.contains(currentSuperName);
                boolean implementsSealedIface = node.getInterfaces() != null &&
                        node.getInterfaces().stream().anyMatch(
                                CompilerRegistry.globalSealedClassSet::contains);
                if (!superIsSealed && !implementsSealedIface) {
                    reportError(node, "Modifier 'non-sealed' is only permitted on subclasses of a sealed class or interface. Direct supertype is not sealed.");
                }
            }

            // Interface implementation check: A class can only implement interfaces, not classes
            if (node.getInterfaces() != null) {
                boolean isDataOrEnum = node.isDataClass() || CompilerRegistry.globalDataClassSet.contains(currentClassFqcn)
                        || CompilerRegistry.globalEnumConstants.containsKey(currentClassFqcn) || "java/lang/Enum".equals(node.getSuperName());
                Set<String> seenIfaces = new HashSet<>();
                for (String iface : node.getInterfaces()) {
                    if (!seenIfaces.add(iface)) {
                        reportError(node, "Duplicate interface: '" + OceanTypeSystem.findSimpleName(iface) + "' is implemented more than once in '" + currentClassSimpleName + "'.");
                    }
                    if (isClassType(iface)) {
                        reportError(node, "Class '" + currentClassSimpleName + "' cannot implement class '" + iface + "'. Only interfaces can be implemented.");
                    }
                    if (!isAnnotation && isAnnotation(iface)) {
                        reportError(node, "Class '" + currentClassSimpleName + "' cannot implement annotation '" + OceanTypeSystem.findSimpleName(iface) + "'.");
                    }
                    if (CompilerRegistry.globalSealedClassSet.contains(iface)) {
                        List<String> permitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(iface);
                        if (permitted != null && !permitted.contains(currentClassFqcn)) {
                            reportError(node, "Class '" + currentClassSimpleName + "' is not permitted to implement sealed interface '" + OceanTypeSystem.findSimpleName(iface) + "'.");
                        }
                        String ifacePkg = iface.contains("/") ? iface.substring(0, iface.lastIndexOf('/')) : "";
                        String subPkg = currentClassFqcn.contains("/") ? currentClassFqcn.substring(0, currentClassFqcn.lastIndexOf('/')) : "";
                        if (!ifacePkg.equals(subPkg)) {
                            reportError(node, "Permitted implementation '" + currentClassSimpleName + "' in package '" + (subPkg.isEmpty() ? "unnamed" : subPkg) + "' cannot implement sealed interface '" + OceanTypeSystem.findSimpleName(iface) + "' in package '" + (ifacePkg.isEmpty() ? "unnamed" : ifacePkg) + "': sealed interface and its permitted implementations must belong to the same package.");
                        }
                        String status = CompilerRegistry.globalSubclassStatusRegistry.get(currentClassFqcn);
                        if (status == null && !isFinal && !node.isSealed() && !node.isNonSealed() && !isDataOrEnum) {
                            reportError(node, "Class '" + currentClassSimpleName + "' extending or implementing a sealed type must be declared 'final', 'sealed', or 'non-sealed'.");
                        }
                    }
                }
                if (node.getInterfaces().size() > 1) {
                    validateSuperInterfaceMethodConflicts(node, node.getInterfaces(), currentClassSimpleName);
                }
            }

            //
            if ((node.isSealed() || CompilerRegistry.globalSealedClassSet.contains(currentClassFqcn))
                    && (CompilerRegistry.globalExplicitRestrictsSet.contains(currentClassFqcn) || CompilerRegistry.globalExplicitRestrictsSet.contains(currentClassSimpleName))) {
                List<String> permitted = node.getPermittedSubclasses();
                if (permitted == null || permitted.isEmpty()) {
                    permitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(currentClassFqcn);
                    if (permitted == null) permitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(currentClassSimpleName);
                }
                if (permitted != null) {
                    for (String sub : permitted) {
                        String superPkg = currentClassFqcn.contains("/") ? currentClassFqcn.substring(0, currentClassFqcn.lastIndexOf('/')) : "";
                        String subPkg = sub.contains("/") ? sub.substring(0, sub.lastIndexOf('/')) : "";
                        if (!superPkg.equals(subPkg)) {
                            reportError(node, "Permitted subclass '" + OceanTypeSystem.findSimpleName(sub) + "' in package '" + (subPkg.isEmpty() ? "unnamed" : subPkg) + "' cannot be permitted by sealed class '" + currentClassSimpleName + "' in package '" + (superPkg.isEmpty() ? "unnamed" : superPkg) + "': sealed class and its permitted subclasses must belong to the same package.");
                        }
                        if (!isPermittedSubtypeExtendingSealed(sub, currentClassFqcn, false)) {
                            reportError(node, "Permitted subclass '" + OceanTypeSystem.findSimpleName(sub) + "' in restricts clause does not directly extend sealed class '" + currentClassSimpleName + "'.");
                        }
                    }
                }
            }

            // 3. Abstract Method Presence & Full Hierarchy Check (P1-2)
            if (!isAbstract) {
                for (IRMethod method : node.getMethods()) {
                    if ((method.getAccessFlags() & Opcodes.ACC_ABSTRACT) != 0 || method.isAbstract()) {
                        reportError(method, "Class '" + currentClassSimpleName + "' is not abstract and cannot declare abstract method '" + method.getName() + "'.");
                    }
                }
                checkAbstractMethods(currentClassFqcn, currentClassSimpleName, node);
            }

            // Visit Fields
            Set<String> declaredFieldsInClass = new HashSet<>();
            currentClassBlankFinalFields.clear();
            currentClassBlankStaticFinalFields.clear();
            definitelyAssignedBlankStaticFinalFields.clear();
            potentiallyAssignedBlankStaticFinalFields.clear();
            Set<String> forwardFields = new LinkedHashSet<>();
            for (IRField f : node.getFields()) {
                if (f.getName() != null) {
                    forwardFields.add(f.getName());
                }
            }
            for (IRField field : node.getFields()) {
                if (field.getName() != null) {
                    if (!declaredFieldsInClass.add(field.getName())) {
                        reportError(field, "Duplicate field declaration: '" + field.getName() + "' is already defined.");
                    }
                    forwardFields.remove(field.getName());
                }
                boolean isFinalField = field.isFinal() || isFieldFinal(currentClassFqcn, field.getName());
                if (!field.isStatic() && isFinalField && field.getInitialValue() == null && (field.getAccessFlags() & Opcodes.ACC_SYNTHETIC) == 0 && (field.getName() == null || !field.getName().startsWith("this$"))) {
                    currentClassBlankFinalFields.add(field.getName());
                } else if (field.isStatic() && isFinalField && field.getInitialValue() == null && (field.getAccessFlags() & Opcodes.ACC_SYNTHETIC) == 0) {
                    currentClassBlankStaticFinalFields.add(field.getName());
                }
                if (currentClassFqcn != null && field.getName() != null) {
                    String fDesc = field.getTypeDescriptor() != null ? field.getTypeDescriptor() : OceanTypeSystem.OBJECT_DESC;
                    CompilerRegistry.globalFieldRegistry.computeIfAbsent(currentClassFqcn, k -> new ConcurrentHashMap<>()).put(field.getName(), fDesc);
                    CompilerRegistry.globalFieldStaticity.computeIfAbsent(currentClassFqcn, k -> new ConcurrentHashMap<>()).put(field.getName(), field.isStatic());
                    symbolTable.declareVariable(field.getName(), fDesc);
                }
                Set<String> oldForward = currentClassForwardFields;
                String oldFieldInit = currentFieldBeingInitialized;
                currentClassForwardFields = forwardFields;
                currentFieldBeingInitialized = field.getName();
                try {
                    field.accept(this);
                } finally {
                    currentClassForwardFields = oldForward;
                    currentFieldBeingInitialized = oldFieldInit;
                }
            }

            // Duplicate Method & Constructor Check
            Set<String> declaredMethodSignatures = new HashSet<>();
            for (IRMethod method : node.getMethods()) {
                if ((method.getAccessFlags() & Opcodes.ACC_SYNTHETIC) != 0 || (method.getAccessFlags() & Opcodes.ACC_BRIDGE) != 0) {
                    continue;
                }
                String name = method.getName();
                if (name != null && name.startsWith("lambda$")) continue;
                if ("<clinit>".equals(name)) continue;

                String desc = method.getDescriptor();
                String paramPart = (desc != null && desc.contains(")")) ? desc.substring(0, desc.indexOf(')') + 1) : "()";
                String sigKey = name + ":" + paramPart;
                if (!declaredMethodSignatures.add(sigKey)) {
                    reportError(method, ("<init>".equals(name) ? "Constructor" : "Method '" + name + "'") + " is already defined with the same parameter signature.");
                }
            }

            // Recursive Constructor Invocation Check (this() cycle)
            Map<String, String> thisCallTargets = new HashMap<>();
            for (IRMethod method : node.getMethods()) {
                if ("<init>".equals(method.getName()) && method.getBody() instanceof IRBlock block) {
                    for (IRStatement stmt : block.getStatements()) {
                        if (stmt instanceof IRExprStatement es && es.getExpression() instanceof IRMethodCall mc && mc.isThisCall() && "<init>".equals(mc.getName())) {
                            String targetDesc = mc.getDescriptor();
                            if (targetDesc != null) {
                                String paramPart = targetDesc.contains(")") ? targetDesc.substring(0, targetDesc.indexOf(')') + 1) : targetDesc;
                                String fromParamPart = (method.getDescriptor() != null && method.getDescriptor().contains(")")) ? method.getDescriptor().substring(0, method.getDescriptor().indexOf(')') + 1) : method.getDescriptor();
                                thisCallTargets.put(fromParamPart, paramPart);
                            }
                            break;
                        }
                    }
                }
            }

            for (Map.Entry<String, String> entry : thisCallTargets.entrySet()) {
                Set<String> visited = new HashSet<>();
                String curr = entry.getKey();
                while (curr != null && thisCallTargets.containsKey(curr)) {
                    if (!visited.add(curr)) {
                        reportError(node, "Recursive constructor invocation: 'this(...)'");
                        break;
                    }
                    curr = thisCallTargets.get(curr);
                }
            }

            // Visit Methods
            for (IRMethod method : node.getMethods()) {
                method.accept(this);
            }

            boolean isStaticClass = (CompilerRegistry.globalClassAccess.getOrDefault(currentClassFqcn, 0) & Opcodes.ACC_STATIC) != 0
                    || currentClassIsInterface || currentClassIsEnum || CompilerRegistry.globalDataClassSet.contains(currentClassFqcn);
            boolean isInnerClass = !isStaticClass && (CompilerRegistry.globalInnerClassOuterMap.containsKey(currentClassFqcn)
                    || CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(currentClassFqcn)
                    || (currentClassFqcn != null && currentClassFqcn.contains("$")));
            for (IRBlock block : node.getStaticBlocks()) {
                if (isInnerClass) {
                    reportError(block, "Non-static inner classes cannot declare static initializer blocks.");
                }
                boolean oldStatic = isStaticContext;
                boolean oldStaticInit = insideStaticInitializer;
                isStaticContext = true;
                insideStaticInitializer = true;
                block.accept(this);
                isStaticContext = oldStatic;
                insideStaticInitializer = oldStaticInit;
            }

            for (IRBlock block : node.getInstanceBlocks()) {
                boolean oldStatic = isStaticContext;
                boolean oldInstanceInit = insideInstanceInitializer;
                isStaticContext = false;
                insideInstanceInitializer = true;
                symbolTable.enterScope();
                if (currentClassFqcn != null) {
                    symbolTable.declareVariable("this", OceanTypeSystem.wrapObjectType(currentClassFqcn), false, true);
                }
                block.accept(this);
                symbolTable.exitScope();
                isStaticContext = oldStatic;
                insideInstanceInitializer = oldInstanceInit;
            }

            for (String bsff : currentClassBlankStaticFinalFields) {
                if (!definitelyAssignedBlankStaticFinalFields.contains(bsff)) {
                    reportError(node, "Static final field '" + bsff + "' might not have been initialized");
                }
            }
        } finally {
            currentClassBlankFinalFields.clear();
            currentClassBlankFinalFields.addAll(oldBlankFinals);
            currentClassBlankStaticFinalFields.clear();
            currentClassBlankStaticFinalFields.addAll(oldBlankStaticFinals);
            definitelyAssignedBlankStaticFinalFields.clear();
            definitelyAssignedBlankStaticFinalFields.addAll(oldDAStaticFinals);
            potentiallyAssignedBlankStaticFinalFields.clear();
            potentiallyAssignedBlankStaticFinalFields.addAll(oldPAStaticFinals);

            if (session != null) {
                session.setCurrentClassFqcn(oldSessionClass);
                session.setCurrentPackage(oldSessionPkg);
                session.setCurrentFile(oldSessionFile);
            }

            currentClassFqcn = oldClassFqcn;
            currentClassSimpleName = oldClassSimple;
            currentSuperName = oldSuperName;
            currentClassIsAbstract = oldIsAbstract;
            currentClassIsInterface = oldIsInterface;
            currentClassIsEnum = oldIsEnum;

            symbolTable.getTypeParams().clear();
            symbolTable.getTypeParams().addAll(oldTypeParams);
            symbolTable.exitScope();
        }
    }

    // P1-2: Full Hierarchy Abstract Method Implementation Verification
        private record MethodSignature(String name, String descriptor) {
            private MethodSignature(String name, String descriptor) {
                this.name = name;
                this.descriptor = descriptor != null ? TypeChecker.cleanDescriptor(descriptor) : null;
            }
    }

    private void checkAbstractMethods(String classPath, String className, IRNode node) {
        if (CompilerRegistry.globalIsInterfaceSet.contains(classPath)) return;

        Set<MethodSignature> abstractMethods = new HashSet<>();
        Set<MethodSignature> concreteMethods = new HashSet<>();
        Set<MethodSignature> interfaceAbstractMethods = new HashSet<>();
        Set<MethodSignature> genericAbstractMethods = new HashSet<>();

        Map<String, String> localMethods = CompilerRegistry.globalMethodRegistry.getOrDefault(classPath, Collections.emptyMap());
        Map<String, Integer> localAccess = new HashMap<>(CompilerRegistry.globalMethodAccess.getOrDefault(classPath, Collections.emptyMap()));
        if (node instanceof IRClass irClass) {
            for (IRMethod m : irClass.getMethods()) {
                localAccess.putIfAbsent(m.getName(), m.getAccessFlags());
                localAccess.putIfAbsent(m.getName() + m.getDescriptor(), m.getAccessFlags());
            }
        }
        for (Map.Entry<String, String> entry : localMethods.entrySet()) {
            String mName = entry.getKey();
            int access = localAccess.getOrDefault(mName, 0);
            if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                // Handled in visitClass
            } else if (!mName.equals("<init>") && !mName.equals("<clinit>")) {
                concreteMethods.add(new MethodSignature(mName, entry.getValue()));
            }
        }

        collectMethodsHierarchy(classPath, abstractMethods, concreteMethods, genericAbstractMethods);

        if (node instanceof IRClass irClass && irClass.getInterfaces() != null) {
            for (String inter : irClass.getInterfaces()) {
                Set<MethodSignature> ifaceAbs = new HashSet<>();
                Set<MethodSignature> ifaceConc = new HashSet<>();
                collectClassMethods(inter, ifaceAbs, ifaceConc);
                collectMethodsHierarchy(inter, ifaceAbs, ifaceConc, genericAbstractMethods, new HashSet<>());
                abstractMethods.addAll(ifaceAbs);
                concreteMethods.addAll(ifaceConc);
                interfaceAbstractMethods.addAll(ifaceAbs);
            }
        }
        String[] directInterfaces = CompilerRegistry.globalInterfaceRegistry.get(classPath);
        if (directInterfaces != null) {
            for (String inter : directInterfaces) {
                Set<MethodSignature> ifaceAbs = new HashSet<>();
                Set<MethodSignature> ifaceConc = new HashSet<>();
                collectClassMethods(inter, ifaceAbs, ifaceConc);
                collectMethodsHierarchy(inter, ifaceAbs, ifaceConc, genericAbstractMethods, new HashSet<>());
                abstractMethods.addAll(ifaceAbs);
                concreteMethods.addAll(ifaceConc);
                interfaceAbstractMethods.addAll(ifaceAbs);
            }
        }

        for (MethodSignature sig : abstractMethods) {
            boolean implemented = false;
            boolean returnTypeMismatch = false;
            String mismatchedReturnType = null;
            String expectedReturnType = null;

            String cleanSigDesc = TypeChecker.cleanDescriptor(sig.descriptor);
            List<String> sigParams = parseDescriptorParams(cleanSigDesc);
            String sigRet = (cleanSigDesc != null && cleanSigDesc.contains(")")) ? cleanSigDesc.substring(cleanSigDesc.lastIndexOf(')') + 1) : "V";

            for (MethodSignature concrete : concreteMethods) {
                if (concrete.name.equals(sig.name)) {
                    String cleanConcDesc = TypeChecker.cleanDescriptor(concrete.descriptor);
                    String concRet = (cleanConcDesc != null && cleanConcDesc.contains(")")) ? cleanConcDesc.substring(cleanConcDesc.lastIndexOf(')') + 1) : "V";
                    boolean retCompatible = concRet.equals(sigRet) ||
                            isGenericTypeVariable(sigRet) || isGenericTypeVariable(concRet) ||
                            (session != null ? session.isSubType(concRet, sigRet) : TypeChecker.isAssignable(sigRet, concRet, null));
                    if (cleanConcDesc!=null){
                        if (cleanConcDesc.equals(cleanSigDesc)) {
                            implemented = true;
                            if (interfaceAbstractMethods.contains(sig)) {
                                int concAcc = localAccess.getOrDefault(concrete.name, 0);
                                if (localMethods.containsKey(concrete.name) && (concAcc & Opcodes.ACC_PUBLIC) == 0) {
                                    reportError(node, "Class '" + className + "' must declare 'public' access modifier when implementing interface method '" + sig.name + "'.");
                                }
                            }
                            break;
                        }
                    }
                    List<String> concreteParams = parseDescriptorParams(cleanConcDesc);
                    if (concreteParams.size() == sigParams.size()) {
                        boolean paramsCompatible = true;
                        for (int i = 0; i < concreteParams.size(); i++) {
                            String cp = concreteParams.get(i);
                            String sp = sigParams.get(i);
                            boolean match = cp.equals(sp) || isGenericTypeVariable(sp) || isGenericTypeVariable(cp)
                                    || ((interfaceAbstractMethods.contains(sig) || genericAbstractMethods.contains(sig)) && (TypeChecker.isAssignable(sp, cp, session) || TypeChecker.isAssignable(cp, sp, session)));
                            if (!match) {
                                paramsCompatible = false;
                                break;
                            }
                        }
                        if (paramsCompatible) {
                            if (retCompatible) {
                                implemented = true;
                                if (interfaceAbstractMethods.contains(sig)) {
                                    int concAcc = localAccess.getOrDefault(concrete.name, 0);
                                    if (localMethods.containsKey(concrete.name) && (concAcc & Opcodes.ACC_PUBLIC) == 0) {
                                        reportError(node, "Class '" + className + "' must declare 'public' access modifier when implementing interface method '" + sig.name + "'.");
                                    }
                                }
                                break;
                            } else {
                                returnTypeMismatch = true;
                                mismatchedReturnType = concRet;
                                expectedReturnType = sigRet;
                            }
                        }
                    }
                }
            }
            if (!implemented) {
                if (returnTypeMismatch) {
                    reportError(node, "Class '" + className + "' implements interface method '" + sig.name + "' with incompatible return type: expected '" + TypeChecker.humanReadable(expectedReturnType) + "', found '" + TypeChecker.humanReadable(mismatchedReturnType) + "'.");
                } else {
                    reportError(node, "Class '" + className + "' is not abstract and does not override abstract method '" + sig.name + cleanSigDesc + "'.");
                }
            }
        }
    }

    private void validateSuperInterfaceMethodConflicts(IRNode node, List<String> interfaceNames, String declaringTypeName) {
        if (interfaceNames == null || interfaceNames.size() < 2) return;

        // (methodName:paramDescriptor) -> Map<declaringInterface, returnType>
        Map<String, Map<String, String>> methodMap = new LinkedHashMap<>();
        Map<String, Set<String>> defaultMethodProviders = new LinkedHashMap<>();

        for (String ifaceName : interfaceNames) {
            Set<String> visited = new HashSet<>();
            Queue<String> queue = new ArrayDeque<>();
            queue.add(ifaceName);

            while (!queue.isEmpty()) {
                String curr = queue.poll();
                if (curr == null || !visited.add(curr)) continue;

                ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(curr);
                if (sym != null) {
                    for (List<ocean.compiler.symbol.MethodSymbol> mList : sym.getMethods().values()) {
                        for (ocean.compiler.symbol.MethodSymbol m : mList) {
                            if (m.isStatic()) continue;
                            String desc = m.getDescriptor();
                            if (desc != null && desc.contains(")")) {
                                String paramPart = desc.substring(0, desc.indexOf(')') + 1);
                                String retPart = desc.substring(desc.indexOf(')') + 1);
                                String sigKey = m.getName() + ":" + paramPart;
                                methodMap.computeIfAbsent(sigKey, k -> new LinkedHashMap<>()).put(curr, retPart);
                                boolean isDef = (m.getAccessFlags() & Opcodes.ACC_ABSTRACT) == 0;
                                if (isDef) {
                                    defaultMethodProviders.computeIfAbsent(sigKey, k -> new LinkedHashSet<>()).add(curr);
                                }
                            }
                        }
                    }
                    if (sym.getInterfaces() != null) {
                        queue.addAll(sym.getInterfaces());
                    }
                } else {
                    Map<String, Integer> accessMap = CompilerRegistry.globalMethodAccess.get(curr);
                    Map<String, Boolean> staticMap = CompilerRegistry.globalMethodStaticity.get(curr);
                    Map<String, List<String>> overloads = CompilerRegistry.globalOverloadRegistry.get(curr);
                    if (overloads != null) {
                        for (Map.Entry<String, List<String>> entry : overloads.entrySet()) {
                            String mName = entry.getKey();
                            boolean isStatic = staticMap != null && Boolean.TRUE.equals(staticMap.get(mName));
                            if (isStatic) continue;
                            for (String desc : entry.getValue()) {
                                if (desc != null && desc.contains(")")) {
                                    String paramPart = desc.substring(0, desc.indexOf(')') + 1);
                                    String retPart = desc.substring(desc.indexOf(')') + 1);
                                    String sigKey = mName + ":" + paramPart;
                                    methodMap.computeIfAbsent(sigKey, k -> new LinkedHashMap<>()).put(curr, retPart);
                                    int acc = accessMap != null ? accessMap.getOrDefault(mName, 0) : 0;
                                    if (accessMap != null && accessMap.containsKey(mName + desc)) {
                                        acc = accessMap.get(mName + desc);
                                    }
                                    boolean isDef = (acc & Opcodes.ACC_ABSTRACT) == 0;
                                    if (isDef) {
                                        defaultMethodProviders.computeIfAbsent(sigKey, k -> new LinkedHashSet<>()).add(curr);
                                    }
                                }
                            }
                        }
                    }
                    String[] inters = CompilerRegistry.globalInterfaceRegistry.get(curr);
                    if (inters != null) {
                        for (String in : inters) {
                            if (in != null) queue.add(in);
                        }
                    }

                    try {
                        Class<?> clazz = OceanTypeSystem.forName(curr.replace('/', '.'));
                        if (clazz != null && clazz.isInterface()) {
                            for (Method m : clazz.getMethods()) {
                                int mods = m.getModifiers();
                                if (Modifier.isStatic(mods)) continue;
                                String desc = Type.getMethodDescriptor(m);
                                String paramPart = desc.substring(0, desc.indexOf(')') + 1);
                                String retPart = desc.substring(desc.indexOf(')') + 1);
                                String sigKey = m.getName() + ":" + paramPart;
                                String normName = clazz.getName().replace('.', '/');
                                methodMap.computeIfAbsent(sigKey, k -> new LinkedHashMap<>()).put(normName, retPart);
                                if (m.isDefault()) {
                                    defaultMethodProviders.computeIfAbsent(sigKey, k -> new LinkedHashSet<>()).add(normName);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }

        for (Map.Entry<String, Map<String, String>> entry : methodMap.entrySet()) {
            Map<String, String> ifaceToRet = entry.getValue();
            if (ifaceToRet.size() > 1) {
                Iterator<Map.Entry<String, String>> it = ifaceToRet.entrySet().iterator();
                Map.Entry<String, String> first = it.next();
                String if1 = first.getKey();
                String r1 = first.getValue();

                while (it.hasNext()) {
                    Map.Entry<String, String> next = it.next();
                    String if2 = next.getKey();
                    String r2 = next.getValue();

                    boolean compatible = r1.equals(r2) || isGenericTypeVariable(r1) || isGenericTypeVariable(r2) ||
                            (session != null ? (session.isSubType(r1, r2) || session.isSubType(r2, r1)) :
                                    (TypeChecker.isAssignable(r1, r2, null) || TypeChecker.isAssignable(r2, r1, null)));

                    if (!compatible) {
                        String mName = entry.getKey().substring(0, entry.getKey().indexOf(':'));
                        reportError(node, "'" + declaringTypeName + "' inherits conflicting return types for method '" + mName + "' from interfaces '" + OceanTypeSystem.findSimpleName(if1) + "' and '" + OceanTypeSystem.findSimpleName(if2) + "' ('" + TypeChecker.humanReadable(r1) + "' and '" + TypeChecker.humanReadable(r2) + "').");
                        return;
                    }
                }
            }
        }

        // 2. Default method diamond conflict check
        for (Map.Entry<String, Set<String>> entry : defaultMethodProviders.entrySet()) {
            String sigKey = entry.getKey();
            Set<String> providers = new LinkedHashSet<>(entry.getValue());
            if (providers.size() > 1) {
                // Rule 1: Classes Win / Explicit Override: Check if overridden in the implementing class/interface or concrete superclass
                if (isMethodOverriddenInType(node, sigKey)) {
                    continue;
                }

                // Rule 2: Dominance check (if B extends A, B dominates A)
                Set<String> nonDominated = new LinkedHashSet<>();
                for (String p1 : providers) {
                    boolean dominated = false;
                    for (String p2 : providers) {
                        if (!p1.equals(p2) && isSubInterfaceOf(p2, p1)) {
                            dominated = true;
                            break;
                        }
                    }
                    if (!dominated) {
                        nonDominated.add(p1);
                    }
                }

                // Rule 3: If multiple unrelated default implementations remain, report conflict
                if (nonDominated.size() > 1) {
                    Iterator<String> it = nonDominated.iterator();
                    String if1 = it.next();
                    String if2 = it.next();
                    String mName = sigKey.substring(0, sigKey.indexOf(':'));
                    String entityKind = (node instanceof IRInterface) ? "Interface" : "Class";
                    reportError(node, entityKind + " '" + declaringTypeName + "' inherits unrelated defaults for '" + mName + "' from interfaces '" + OceanTypeSystem.findSimpleName(if1) + "' and '" + OceanTypeSystem.findSimpleName(if2) + "'. It must be overridden in '" + declaringTypeName + "'.");
                }
            }
        }
    }

    private boolean isMethodOverriddenInType(IRNode node, String sigKey) {
        String mName = sigKey.substring(0, sigKey.indexOf(':'));
        String paramPart = sigKey.substring(sigKey.indexOf(':') + 1);

        if (node instanceof IRClass irClass) {
            for (IRMethod m : irClass.getMethods()) {
                if (m.getName().equals(mName) && !m.isAbstract()) {
                    String desc = m.getDescriptor();
                    String mParam = (desc != null && desc.contains(")")) ? desc.substring(0, desc.indexOf(')') + 1) : "()";
                    if (mParam.equals(paramPart)) {
                        return true;
                    }
                }
            }
            String superName = irClass.getSuperName();
            return hasConcreteSuperclassMethod(superName, mName, paramPart);
        } else if (node instanceof IRInterface irIface) {
            for (IRMethod m : irIface.getMethods()) {
                if (m.getName().equals(mName)) {
                    String desc = m.getDescriptor();
                    String mParam = (desc != null && desc.contains(")")) ? desc.substring(0, desc.indexOf(')') + 1) : "()";
                    if (mParam.equals(paramPart)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasConcreteSuperclassMethod(String superName, String mName, String paramPart) {
        String curr = superName;
        Set<String> visited = new HashSet<>();
        while (curr != null && !curr.equals("java/lang/Object") && visited.add(curr)) {
            ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(curr);
            if (sym != null) {
                List<ocean.compiler.symbol.MethodSymbol> mList = sym.getMethods().get(mName);
                if (mList != null) {
                    for (ocean.compiler.symbol.MethodSymbol m : mList) {
                        if (!m.isStatic() && (m.getAccessFlags() & Opcodes.ACC_ABSTRACT) == 0 && (m.getAccessFlags() & Opcodes.ACC_PRIVATE) == 0) {
                            String desc = m.getDescriptor();
                            String mParam = (desc != null && desc.contains(")")) ? desc.substring(0, desc.indexOf(')') + 1) : "()";
                            if (mParam.equals(paramPart)) {
                                return true;
                            }
                        }
                    }
                }
            } else {
                Map<String, Integer> accessMap = CompilerRegistry.globalMethodAccess.get(curr);
                Map<String, Boolean> staticMap = CompilerRegistry.globalMethodStaticity.get(curr);
                Map<String, List<String>> overloads = CompilerRegistry.globalOverloadRegistry.get(curr);
                if (overloads != null && overloads.containsKey(mName)) {
                    for (String desc : overloads.get(mName)) {
                        String mParam = (desc != null && desc.contains(")")) ? desc.substring(0, desc.indexOf(')') + 1) : "()";
                        if (mParam.equals(paramPart)) {
                            boolean isStatic = staticMap != null && Boolean.TRUE.equals(staticMap.get(mName));
                            int acc = accessMap != null ? accessMap.getOrDefault(mName, 0) : 0;
                            if (!isStatic && (acc & Opcodes.ACC_ABSTRACT) == 0 && (acc & Opcodes.ACC_PRIVATE) == 0) {
                                return true;
                            }
                        }
                    }
                }
                try {
                    Class<?> clazz = OceanTypeSystem.forName(curr.replace('/', '.'));
                    if (clazz != null && !clazz.isInterface()) {
                        for (Method m : clazz.getMethods()) {
                            int mods = m.getModifiers();
                            if (!Modifier.isStatic(mods) && !Modifier.isAbstract(mods) && !m.getDeclaringClass().isInterface()) {
                                String desc = Type.getMethodDescriptor(m);
                                String mParam = desc.substring(0, desc.indexOf(')') + 1);
                                if (m.getName().equals(mName) && mParam.equals(paramPart)) {
                                    return true;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            curr = CompilerRegistry.globalSuperClassRegistry.get(curr);
        }
        return false;
    }

    private boolean isSubInterfaceOf(String sub, String sup) {
        if (sub == null || sup == null || sub.equals(sup)) return false;
        String cleanSub = sub.replace('.', '/');
        String cleanSup = sup.replace('.', '/');
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(cleanSub);
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            if (curr == null || !visited.add(curr)) continue;
            String[] direct = CompilerRegistry.globalInterfaceRegistry.get(curr);
            if (direct != null) {
                for (String d : direct) {
                    if (d != null) {
                        String cleanD = d.replace('.', '/');
                        if (cleanD.equals(cleanSup)) return true;
                        queue.add(cleanD);
                    }
                }
            }
            ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(curr);
            if (sym != null && sym.getInterfaces() != null) {
                for (String d : sym.getInterfaces()) {
                    if (d != null) {
                        String cleanD = d.replace('.', '/');
                        if (cleanD.equals(cleanSup)) return true;
                        queue.add(cleanD);
                    }
                }
            }
            try {
                Class<?> clazz = OceanTypeSystem.forName(curr.replace('/', '.'));
                if (clazz != null) {
                    for (Class<?> iface : clazz.getInterfaces()) {
                        String cleanIface = iface.getName().replace('.', '/');
                        if (cleanIface.equals(cleanSup)) return true;
                        queue.add(cleanIface);
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private boolean isGenericAncestor(String parent) {
        if (parent == null) return false;
        List<CompilerRegistry.TypeParameterInfo> tp = CompilerRegistry.globalTypeParameterRegistry.get(parent);
        if (tp != null && !tp.isEmpty()) return true;
        for (Map.Entry<String, List<CompilerRegistry.TypeParameterInfo>> e : CompilerRegistry.globalTypeParameterRegistry.entrySet()) {
            if (e.getKey().equals(parent) || e.getKey().endsWith("/" + parent) || parent.endsWith("/" + e.getKey())) {
                if (!e.getValue().isEmpty()) return true;
            }
        }
        try {
            Class<?> cls = OceanTypeSystem.forName(parent.replace('/', '.'));
            if (cls != null && cls.getTypeParameters().length > 0) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private void collectMethodsHierarchy(String classPath, Set<MethodSignature> abstractMethods, Set<MethodSignature> concreteMethods, Set<MethodSignature> genericAbstractMethods) {
        collectMethodsHierarchy(classPath, abstractMethods, concreteMethods, genericAbstractMethods, new HashSet<>());
    }

    private void collectMethodsHierarchy(String classPath, Set<MethodSignature> abstractMethods, Set<MethodSignature> concreteMethods, Set<MethodSignature> genericAbstractMethods, Set<String> visited) {
        if (classPath == null || !visited.add(classPath)) return;

        String parent = CompilerRegistry.globalSuperClassRegistry.get(classPath);
        if (parent == null) {
            for (Map.Entry<String, String> entry : CompilerRegistry.globalSuperClassRegistry.entrySet()) {
                if (entry.getKey().equals(classPath) || entry.getKey().endsWith("/" + classPath)) {
                    parent = entry.getValue();
                    break;
                }
            }
        }
        if (parent != null) {
            Set<MethodSignature> parentAbs = new HashSet<>();
            Set<MethodSignature> parentConc = new HashSet<>();
            collectClassMethods(parent, parentAbs, parentConc);
            abstractMethods.addAll(parentAbs);
            concreteMethods.addAll(parentConc);
            if (isGenericAncestor(parent)) {
                genericAbstractMethods.addAll(parentAbs);
            }
            collectMethodsHierarchy(parent, abstractMethods, concreteMethods, genericAbstractMethods, visited);
        } else if (!classPath.equals("java/lang/Object") && !classPath.endsWith("/Object")) {
            collectClassMethods("java/lang/Object", abstractMethods, concreteMethods);
        }

        String[] interfaces = CompilerRegistry.globalInterfaceRegistry.get(classPath);
        if (interfaces == null) {
            for (Map.Entry<String, String[]> entry : CompilerRegistry.globalInterfaceRegistry.entrySet()) {
                if (entry.getKey().equals(classPath) || entry.getKey().endsWith("/" + classPath)) {
                    interfaces = entry.getValue();
                    break;
                }
            }
        }
        if (interfaces != null) {
            for (String inter : interfaces) {
                Set<MethodSignature> ifaceAbs = new HashSet<>();
                Set<MethodSignature> ifaceConc = new HashSet<>();
                collectClassMethods(inter, ifaceAbs, ifaceConc);
                abstractMethods.addAll(ifaceAbs);
                concreteMethods.addAll(ifaceConc);
                genericAbstractMethods.addAll(ifaceAbs);
                collectMethodsHierarchy(inter, abstractMethods, concreteMethods, genericAbstractMethods, visited);
            }
        }
    }

    private void collectClassMethods(String classPath, Set<MethodSignature> abstractMethods, Set<MethodSignature> concreteMethods) {
        if (classPath == null) return;
        String lookupKey = classPath;
        if (!CompilerRegistry.globalMethodRegistry.containsKey(lookupKey) && !CompilerRegistry.globalClassAccess.containsKey(lookupKey)) {
            String pkg = getFilePackage();
            if (!pkg.isEmpty() && CompilerRegistry.globalMethodRegistry.containsKey(pkg + "/" + lookupKey)) {
                lookupKey = pkg + "/" + lookupKey;
            } else {
                for (String regKey : CompilerRegistry.globalMethodRegistry.keySet()) {
                    if (regKey.equals(lookupKey) || regKey.endsWith("/" + lookupKey)) {
                        lookupKey = regKey;
                        break;
                    }
                }
            }
        }
        final String effectiveKey = lookupKey;
        ClassMethodsInfo info = classMethodsCache.computeIfAbsent(effectiveKey, k -> {
            Set<MethodSignature> abs = new HashSet<>();
            Set<MethodSignature> conc = new HashSet<>();
            if (CompilerRegistry.globalMethodRegistry.containsKey(k)) {
                Map<String, String> methods = CompilerRegistry.globalMethodRegistry.getOrDefault(k, Collections.emptyMap());
                Map<String, Integer> accessMap = CompilerRegistry.globalMethodAccess.getOrDefault(k, Collections.emptyMap());
                for (Map.Entry<String, String> entry : methods.entrySet()) {
                    String mName = entry.getKey();
                    String desc = entry.getValue();
                    if (mName.equals("<init>") || mName.equals("<clinit>")) continue;
                    int access = accessMap.getOrDefault(mName, 0);
                    MethodSignature sig = new MethodSignature(mName, desc);
                    if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                        abs.add(sig);
                    } else {
                        conc.add(sig);
                    }
                }
            } else {
                try {
                    Class<?> clazz = OceanTypeSystem.forName(k.replace('/', '.'));
                    for (Method m : clazz.getMethods()) {
                        String desc = Type.getMethodDescriptor(m);
                        MethodSignature sig = new MethodSignature(m.getName(), desc);
                        if (Modifier.isAbstract(m.getModifiers())) {
                            abs.add(sig);
                        } else {
                            conc.add(sig);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            return new ClassMethodsInfo(Collections.unmodifiableSet(abs), Collections.unmodifiableSet(conc));
        });
        abstractMethods.addAll(info.abstractMethods);
        concreteMethods.addAll(info.concreteMethods);
    }

    private List<String> parseDescriptorParams(String desc) {
        if (desc == null) return Collections.emptyList();
        return parsedParamsCache.computeIfAbsent(desc, k -> {
            List<String> params = new ArrayList<>();
            if (!k.startsWith("(")) return params;
            int i = 1;
            while (i < k.length() && k.charAt(i) != ')') {
                char c = k.charAt(i);
                if (c == 'L') {
                    int depth = 0;
                    int end = i;
                    while (end < k.length()) {
                        char ch = k.charAt(end);
                        if (ch == '<') depth++;
                        else if (ch == '>') depth--;
                        else if (ch == ';' && depth == 0) break;
                        end++;
                    }
                    if (end >= k.length()) break;
                    params.add(k.substring(i, end + 1));
                    i = end + 1;
                } else if (c == '[') {
                    int start = i;
                    do i++;
                    while (i < k.length() && k.charAt(i) == '[');
                    if (i < k.length() && k.charAt(i) == 'L') {
                        int depth = 0;
                        int end = i;
                        while (end < k.length()) {
                            char ch = k.charAt(end);
                            if (ch == '<') depth++;
                            else if (ch == '>') depth--;
                            else if (ch == ';' && depth == 0) break;
                            end++;
                        }
                        if (end >= k.length()) break;
                        params.add(k.substring(start, end + 1));
                        i = end + 1;
                    } else if (i < k.length()) {
                        params.add(k.substring(start, i + 1));
                        i++;
                    }
                } else {
                    params.add(String.valueOf(c));
                    i++;
                }
            }
            return Collections.unmodifiableList(params);
        });
    }

    private boolean isInterfaceType(String typeName) {
        if (typeName == null || typeName.isEmpty()) return false;
        String clean = TypeChecker.cleanDescriptor(typeName).replace('.', '/');
        if (clean.startsWith("L") && clean.endsWith(";")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        if (clean.contains("<")) {
            clean = clean.substring(0, clean.indexOf('<'));
        }
        if (CompilerRegistry.globalIsInterfaceSet.contains(clean)) return true;

        // If explicitly known in Ocean compiler registries as class/enum/data class, it's not an interface
        if (CompilerRegistry.globalSuperClassRegistry.containsKey(clean) ||
            CompilerRegistry.globalMethodRegistry.containsKey(clean) ||
            CompilerRegistry.globalFieldRegistry.containsKey(clean) ||
            CompilerRegistry.globalDataClassSet.contains(clean)) {
            return false;
        }

        // Reflection lookup for JDK / external classes
        try {
            Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
            if (clazz != null) {
                return clazz.isInterface();
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private boolean isSubclassOfThrowable(String className) {
        if (className == null || className.isEmpty() || "java/lang/Object".equals(className)) return false;
        String clean = className;
        if (clean.contains("<")) clean = clean.substring(0, clean.indexOf('<'));
        clean = clean.replace('.', '/');
        if (clean.startsWith("L") && clean.endsWith(";")) clean = clean.substring(1, clean.length() - 1);
        if ("java/lang/Throwable".equals(clean) || "java/lang/Exception".equals(clean) || "java/lang/Error".equals(clean) || "java/lang/RuntimeException".equals(clean)
                || "Throwable".equals(clean) || "Exception".equals(clean) || "Error".equals(clean) || "RuntimeException".equals(clean)) {
            return true;
        }
        Set<String> visited = new HashSet<>();
        String curr = clean;
        while (!"java/lang/Object".equals(curr) && visited.add(curr)) {
            if ("java/lang/Throwable".equals(curr) || "java/lang/Exception".equals(curr) || "java/lang/Error".equals(curr) || "java/lang/RuntimeException".equals(curr)
                    || "Throwable".equals(curr) || "Exception".equals(curr) || "Error".equals(curr) || "RuntimeException".equals(curr)) {
                return true;
            }
            String next = CompilerRegistry.globalSuperClassRegistry.get(curr);
            if (next != null) {
                if (next.contains("<")) next = next.substring(0, next.indexOf('<'));
                curr = next.replace('.', '/');
                if (curr.startsWith("L") && curr.endsWith(";")) curr = curr.substring(1, curr.length() - 1);
            } else {
                try {
                    Class<?> clazz = OceanTypeSystem.forName(curr.replace('/', '.'));
                    return clazz != null && Throwable.class.isAssignableFrom(clazz);
                } catch (Throwable ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    private boolean isEnumClass(String className) {
        if (className == null || className.isEmpty()) return false;
        String clean = TypeChecker.cleanDescriptor(className).replace('.', '/');
        if (clean.startsWith("L") && clean.endsWith(";")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        if (clean.contains("<")) {
            clean = clean.substring(0, clean.indexOf('<'));
        }
        if ("java/lang/Enum".equals(clean)) return true;
        if (CompilerRegistry.globalEnumConstants.containsKey(clean)) return true;
        String superName = CompilerRegistry.globalSuperClassRegistry.get(clean);
        if ("java/lang/Enum".equals(superName)) return true;
        try {
            Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
            if (clazz != null) {
                return clazz.isEnum() || Enum.class.isAssignableFrom(clazz);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isClassType(String typeName) {
        if (typeName == null || typeName.isEmpty()) return false;
        String clean = TypeChecker.cleanDescriptor(typeName).replace('.', '/');
        if (clean.startsWith("L") && clean.endsWith(";")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        if (clean.contains("<")) {
            clean = clean.substring(0, clean.indexOf('<'));
        }
        // If it's an interface, it cannot be a class
        if (isInterfaceType(clean)) return false;

        // If it's known in Ocean registries as a class/enum/data class
        if (CompilerRegistry.globalSuperClassRegistry.containsKey(clean) ||
            CompilerRegistry.globalMethodRegistry.containsKey(clean) ||
            CompilerRegistry.globalFieldRegistry.containsKey(clean) ||
            CompilerRegistry.globalDataClassSet.contains(clean)) {
            return true;
        }

        // Reflection lookup for JDK / external classes
        try {
            Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
            if (clazz != null) {
                return !clazz.isInterface() && !clazz.isPrimitive() && !clazz.isArray();
            }
        } catch (Throwable ignored) {}

        return false;
    }

    private boolean isPermittedSubtypeExtendingSealed(String subName, String sealedFqcn, boolean isSealedInterface) {
        if (subName == null || sealedFqcn == null) return false;
        String subClean = ClassMetadataCache.cleanTypeName(subName);
        String sealedClean = ClassMetadataCache.cleanTypeName(sealedFqcn);

        if (!subClean.contains("/")) {
            subClean = OceanTypeSystem.resolveInternalClassName(subClean, sealedClean);
        }

        if (!isSealedInterface) {
            // Sealed Class: sub must have sealedClean as direct superclass
            String parent = CompilerRegistry.globalSuperClassRegistry.get(subClean);
            if (parent == null) parent = CompilerRegistry.globalSuperClassRegistry.get(OceanTypeSystem.findSimpleName(subClean));
            if (parent != null) {
                String cleanParent = ClassMetadataCache.cleanTypeName(parent);
                if (!cleanParent.contains("/")) {
                    cleanParent = OceanTypeSystem.resolveInternalClassName(cleanParent, subClean);
                }
                if (cleanParent.equals(sealedClean)) {
                    return true;
                }
            }
            ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(subClean);
            if (sym == null) sym = CompilerRegistry.getClassSymbol(OceanTypeSystem.findSimpleName(subClean));
            if (sym != null && sym.getSuperClassName() != null) {
                String cleanParent = ClassMetadataCache.cleanTypeName(sym.getSuperClassName());
                if (!cleanParent.contains("/")) {
                    cleanParent = OceanTypeSystem.resolveInternalClassName(cleanParent, subClean);
                }
                if (cleanParent.equals(sealedClean)) {
                    return true;
                }
            }
            try {
                Class<?> cls = OceanTypeSystem.forName(subClean.replace('/', '.'));
                if (cls != null && cls.getSuperclass() != null) {
                    String supName = cls.getSuperclass().getName().replace('.', '/');
                    if (supName.equals(sealedClean)) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        } else {
            // Sealed Interface: sub must have sealedClean in direct interfaces (or direct superinterfaces)
            String[] ifaces = CompilerRegistry.globalInterfaceRegistry.get(subClean);
            if (ifaces == null) ifaces = CompilerRegistry.globalInterfaceRegistry.get(OceanTypeSystem.findSimpleName(subClean));
            if (ifaces != null) {
                for (String iface : ifaces) {
                    if (iface != null) {
                        String cleanIface = ClassMetadataCache.cleanTypeName(iface);
                        if (!cleanIface.contains("/")) {
                            cleanIface = OceanTypeSystem.resolveInternalClassName(cleanIface, subClean);
                        }
                        if (cleanIface.equals(sealedClean)) {
                            return true;
                        }
                    }
                }
            }
            ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(subClean);
            if (sym == null) sym = CompilerRegistry.getClassSymbol(OceanTypeSystem.findSimpleName(subClean));
            if (sym != null && sym.getInterfaces() != null) {
                for (String iface : sym.getInterfaces()) {
                    if (iface != null) {
                        String cleanIface = ClassMetadataCache.cleanTypeName(iface);
                        if (!cleanIface.contains("/")) {
                            cleanIface = OceanTypeSystem.resolveInternalClassName(cleanIface, subClean);
                        }
                        if (cleanIface.equals(sealedClean)) {
                            return true;
                        }
                    }
                }
            }
            try {
                Class<?> cls = OceanTypeSystem.forName(subClean.replace('/', '.'));
                if (cls != null) {
                    for (Class<?> ifc : cls.getInterfaces()) {
                        String ifcName = ifc.getName().replace('.', '/');
                        if (ifcName.equals(sealedClean)) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    // R2-3 & R3-3: Interface Method Body & Inheritance Checks
    @Override
    public void visitInterface(IRInterface node) {
        String oldClassFqcn = currentClassFqcn;
        String oldClassSimple = currentClassSimpleName;
        boolean oldIsAbstract = currentClassIsAbstract;
        boolean oldIsInterface = currentClassIsInterface;
        boolean oldIsEnum = currentClassIsEnum;

        currentClassFqcn = node.getName();
        currentClassSimpleName = OceanTypeSystem.findSimpleName(currentClassFqcn);
        currentClassIsAbstract = true;
        currentClassIsInterface = true;
        currentClassIsEnum = false;
        try {
            // Check Circular Inheritance Dependency for Interface
            Set<String> visitedCycle = new HashSet<>();
            Set<String> stackCycle = new HashSet<>();

            if (hasCycle(currentClassFqcn, visitedCycle, stackCycle)) {
                reportError(node, "Cyclic inheritance involving interface '" + currentClassSimpleName + "'.");
            }

            // Superinterface check: An interface can only extend other interfaces, not classes
            if (node.getSuperInterfaces() != null) {
                Set<String> seenIfaces = new HashSet<>();
                for (String sup : node.getSuperInterfaces()) {
                    if (!seenIfaces.add(sup)) {
                        reportError(node, "Duplicate interface: '" + OceanTypeSystem.findSimpleName(sup) + "' is extended more than once in '" + currentClassSimpleName + "'.");
                    }
                    if (isAnnotation(sup)) {
                        reportError(node, "Interface '" + currentClassSimpleName + "' cannot extend annotation '" + OceanTypeSystem.findSimpleName(sup) + "'.");
                    }
                    if (isClassType(sup)) {
                        reportError(node, "Interface '" + currentClassSimpleName + "' cannot extend class '" + sup + "'. Interfaces can only extend other interfaces.");
                    }
                    if (CompilerRegistry.globalSealedClassSet.contains(sup)) {
                        List<String> permitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(sup);
                        if (permitted != null && !permitted.contains(currentClassFqcn)) {
                            reportError(node, "Interface '" + currentClassSimpleName + "' is not permitted to extend sealed interface '" + OceanTypeSystem.findSimpleName(sup) + "'.");
                        }
                        String supPkg = sup.contains("/") ? sup.substring(0, sup.lastIndexOf('/')) : "";
                        String subPkg = currentClassFqcn.contains("/") ? currentClassFqcn.substring(0, currentClassFqcn.lastIndexOf('/')) : "";
                        if (!supPkg.equals(subPkg)) {
                            reportError(node, "Permitted subinterface '" + currentClassSimpleName + "' in package '" + (subPkg.isEmpty() ? "unnamed" : subPkg) + "' cannot extend sealed interface '" + OceanTypeSystem.findSimpleName(sup) + "' in package '" + (supPkg.isEmpty() ? "unnamed" : supPkg) + "': sealed interface and its permitted subinterfaces must belong to the same package.");
                        }
                        String status = CompilerRegistry.globalSubclassStatusRegistry.get(currentClassFqcn);
                        if (status == null && !node.isSealed() && !node.isNonSealed()) {
                            reportError(node, "Interface '" + currentClassSimpleName + "' extending a sealed interface must be declared 'sealed' or 'non-sealed'.");
                        }
                    }
                }
                if (node.getSuperInterfaces().size() > 1) {
                    validateSuperInterfaceMethodConflicts(node, node.getSuperInterfaces(), currentClassSimpleName);
                }
            }

            //
            if (node.isNonSealed()) {
                boolean anySuperSealed = node.getSuperInterfaces() != null &&
                        node.getSuperInterfaces().stream().anyMatch(
                                CompilerRegistry.globalSealedClassSet::contains);
                if (!anySuperSealed) {
                    reportError(node, "Modifier 'non-sealed' is only permitted on subinterfaces of a sealed interface hierarchy. No superinterface is sealed.");
                }
            }

            //
            if ((node.isSealed() || CompilerRegistry.globalSealedClassSet.contains(currentClassFqcn))
                    && (CompilerRegistry.globalExplicitRestrictsSet.contains(currentClassFqcn) || CompilerRegistry.globalExplicitRestrictsSet.contains(currentClassSimpleName))) {
                List<String> permitted = node.getPermittedSubclasses();
                if (permitted == null || permitted.isEmpty()) {
                    permitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(currentClassFqcn);
                    if (permitted == null) permitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(currentClassSimpleName);
                }
                if (permitted != null) {
                    for (String sub : permitted) {
                        String ifacePkg = currentClassFqcn.contains("/") ? currentClassFqcn.substring(0, currentClassFqcn.lastIndexOf('/')) : "";
                        String subPkg = sub.contains("/") ? sub.substring(0, sub.lastIndexOf('/')) : "";
                        if (!ifacePkg.equals(subPkg)) {
                            reportError(node, "Permitted type '" + OceanTypeSystem.findSimpleName(sub) + "' in package '" + (subPkg.isEmpty() ? "unnamed" : subPkg) + "' cannot be permitted by sealed interface '" + currentClassSimpleName + "' in package '" + (ifacePkg.isEmpty() ? "unnamed" : ifacePkg) + "': sealed interface and its permitted types must belong to the same package.");
                        }
                        if (!isPermittedSubtypeExtendingSealed(sub, currentClassFqcn, true)) {
                            reportError(node, "Permitted type '" + OceanTypeSystem.findSimpleName(sub) + "' in restricts clause does not directly extend or implement sealed interface '" + currentClassSimpleName + "'.");
                        }
                    }
                }
            }
            if (node.getFields() != null) {
                Set<String> seenFields = new HashSet<>();
                for (IRField field : node.getFields()) {
                    if (!seenFields.add(field.getName())) {
                        reportError(field, "Field '" + field.getName() + "' is already defined in this interface.");
                    }
                    field.accept(this);
                }
            }

            Set<String> declaredInterfaceMethodSignatures = new HashSet<>();
            for (IRMethod method : node.getMethods()) {
                String name = method.getName();
                String desc = method.getDescriptor();
                String paramPart = (desc != null && desc.contains(")")) ? desc.substring(0, desc.indexOf(')') + 1) : "()";
                String sigKey = name + ":" + paramPart;
                if (!declaredInterfaceMethodSignatures.add(sigKey)) {
                    reportError(method, "Method '" + name + "' is already defined with the same parameter signature in this interface.");
                }
                if ("<init>".equals(method.getName())) {
                    reportError(method, "Interfaces cannot declare constructors.");
                }
                if (method.isNative()) {
                    reportError(method, "Native methods cannot be declared in interfaces.");
                }
                if ((method.getAccessFlags() & Opcodes.ACC_PROTECTED) != 0) {
                    reportError(method, "Interface methods cannot be declared 'protected'.");
                }
                if ((method.getAccessFlags() & Opcodes.ACC_FINAL) != 0) {
                    reportError(method, "Interface methods cannot be declared 'final'.");
                }
                if ((method.getAccessFlags() & Opcodes.ACC_SYNCHRONIZED) != 0) {
                    reportError(method, "Interface methods cannot be declared 'sync'.");
                }
                boolean isDefault = !method.isStatic() && !method.isAbstract() && (method.getAccessFlags() & Opcodes.ACC_PRIVATE) == 0;
                if (isDefault && isObjectPublicMethodSignature(name, desc)) {
                    reportError(method, "Default method '" + name + "' in interface cannot override public method from java.lang.Object.");
                }
                if (method.isStatic() && method.getBody() == null) {
                    reportError(method, "Static methods in interfaces must have a body: '" + name + "'");
                }
                if ((method.getAccessFlags() & Opcodes.ACC_PRIVATE) != 0 && method.getBody() == null) {
                    reportError(method, "Private methods in interfaces must have a body: '" + name + "'");
                }
                method.accept(this);
            }
        } finally {
            currentClassFqcn = oldClassFqcn;
            currentClassSimpleName = oldClassSimple;
            currentClassIsAbstract = oldIsAbstract;
            currentClassIsInterface = oldIsInterface;
            currentClassIsEnum = oldIsEnum;
        }
    }

    private static boolean isObjectPublicMethodSignature(String name, String desc) {
        if (name == null || desc == null) return false;
        String cleanDesc = TypeChecker.cleanDescriptor(desc);
        String paramPart = cleanDesc.contains(")") ? cleanDesc.substring(0, cleanDesc.indexOf(')') + 1) : "()";
        return switch (name) {
            case "equals" -> paramPart.equals("(Ljava/lang/Object;)");
            case "hashCode", "toString", "getClass", "notify", "notifyAll" -> paramPart.equals("()");
            case "wait" -> paramPart.equals("()") || paramPart.equals("(J)") || paramPart.equals("(JI)");
            default -> false;
        };
    }

    // R3-4: Enum Duplicate Field & Method Checks
    @Override
    public void visitEnum(IREnum node) {
        String oldClassFqcn = currentClassFqcn;
        String oldClassSimple = currentClassSimpleName;
        String oldSuperName = currentSuperName;
        boolean oldIsAbstract = currentClassIsAbstract;
        boolean oldIsInterface = currentClassIsInterface;
        boolean oldIsEnum = currentClassIsEnum;

        currentClassFqcn = node.getName();
        currentClassSimpleName = OceanTypeSystem.findSimpleName(currentClassFqcn);
        currentSuperName = "java/lang/Enum";
        currentClassIsAbstract = false;
        currentClassIsInterface = false;
        currentClassIsEnum = true;

        List<String> oldBlankFinals = new ArrayList<>(currentClassBlankFinalFields);
        List<String> oldBlankStaticFinals = new ArrayList<>(currentClassBlankStaticFinalFields);
        currentClassBlankFinalFields.clear();
        currentClassBlankStaticFinalFields.clear();

        try {
            // Enum implements check: An enum can only implement interfaces, not classes
            if (node.getInterfaces() != null) {
                for (String iface : node.getInterfaces()) {
                    if (isAnnotation(iface)) {
                        reportError(node, "Enum '" + currentClassSimpleName + "' cannot implement annotation '" + OceanTypeSystem.findSimpleName(iface) + "'.");
                    }
                    if (isClassType(iface)) {
                        reportError(node, "Enum '" + currentClassSimpleName + "' cannot implement class '" + iface + "'. Only interfaces can be implemented.");
                    }
                }
            }

            Set<String> seenConsts = new HashSet<>();
            for (String constName : node.getConstants()) {
                if (!seenConsts.add(constName)) {
                    reportError(node, "Duplicate enum constant: '" + constName + "'");
                }
            }

            validateAnnotations(node.getAnnotations(), "TYPE", node);

            Set<String> seenFields = new HashSet<>();
            for (IRField field : node.getFields()) {
                if (node.getConstants() != null && node.getConstants().contains(field.getName())) {
                    reportError(field, "Enum field '" + field.getName() + "' conflicts with enum constant of the same name.");
                }
                if (!seenFields.add(field.getName())) {
                    reportError(field, "Field '" + field.getName() + "' is already defined in this enum.");
                }
                boolean isFinalField = field.isFinal() || isFieldFinal(currentClassFqcn, field.getName());
                if (!field.isStatic() && isFinalField && field.getInitialValue() == null && (field.getAccessFlags() & Opcodes.ACC_SYNTHETIC) == 0 && (field.getName() == null || !field.getName().startsWith("this$"))) {
                    currentClassBlankFinalFields.add(field.getName());
                }
                field.accept(this);
            }

            Set<String> seenMethods = new HashSet<>();
            for (IRMethod method : node.getMethods()) {
                String sig = method.getName() + (method.getDescriptor() != null ? method.getDescriptor() : "");
                if (method.getName().equals("<init>")) {
                    if ((method.getAccessFlags() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
                        reportError(method, "Enum constructor cannot be declared 'public' or 'protected'.");
                    }
                }
                if (method.getName().equals("clone")) {
                    reportError(method, "Enums cannot declare a 'clone' method.");
                }
                if (isEnumFinalMethod(method.getName(), method.getDescriptor())) {
                    reportError(method, "Enum '" + currentClassSimpleName + "' cannot override final method from java.lang.Enum: '" + method.getName() + "'");
                }
                if (!seenMethods.add(sig)) {
                    reportError(method, "Method '" + method.getName() + "' is already defined with the same parameter signature in this enum.");
                }
                method.accept(this);
            }

            List<IRMethod> abstractMethods = node.getMethods().stream()
                    .filter(m -> (m.getAccessFlags() & Opcodes.ACC_ABSTRACT) != 0 || m.isAbstract())
                    .toList();
            if (!abstractMethods.isEmpty()) {
                if (node.getConstants().isEmpty()) {
                    reportError(node, "Enum '" + currentClassSimpleName + "' with abstract methods has no enum constants.");
                } else {
                    for (int i = 0; i < node.getConstants().size(); i++) {
                        String constName = node.getConstants().get(i);
                        String subClassFqcn = node.getConstantClass(i);
                        if (subClassFqcn == null) {
                            for (IRMethod am : abstractMethods) {
                                reportError(node, "Enum constant '" + constName + "' must implement abstract method '" + am.getName() + "' from '" + currentClassSimpleName + "'.");
                            }
                        } else {
                            Map<String, String> subMethods = CompilerRegistry.globalMethodRegistry.get(subClassFqcn);
                            Map<String, List<String>> subOverloads = CompilerRegistry.globalOverloadRegistry.get(subClassFqcn);
                            for (IRMethod am : abstractMethods) {
                                boolean found = false;
                                String amCleanDesc = am.getDescriptor() != null ? TypeChecker.cleanDescriptor(am.getDescriptor()) : null;
                                String amParamPart = (amCleanDesc != null && amCleanDesc.contains(")")) ? amCleanDesc.substring(0, amCleanDesc.indexOf(')') + 1) : null;

                                if (subOverloads != null && subOverloads.containsKey(am.getName())) {
                                    List<String> descs = subOverloads.get(am.getName());
                                    if (amParamPart == null) {
                                        found = !descs.isEmpty();
                                    } else {
                                        for (String d : descs) {
                                            String cleanD = TypeChecker.cleanDescriptor(d);
                                            String paramPart = (cleanD != null && cleanD.contains(")")) ? cleanD.substring(0, cleanD.indexOf(')') + 1) : null;
                                            if (Objects.equals(amParamPart, paramPart)) {
                                                found = true;
                                                break;
                                            }
                                        }
                                    }
                                } else if (subMethods != null && subMethods.containsKey(am.getName())) {
                                    found = true;
                                }
                                if (!found) {
                                    reportError(node, "Enum constant '" + constName + "' must implement abstract method '" + am.getName() + "' from '" + currentClassSimpleName + "'.");
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            currentClassBlankFinalFields.clear();
            currentClassBlankFinalFields.addAll(oldBlankFinals);
            currentClassBlankStaticFinalFields.clear();
            currentClassBlankStaticFinalFields.addAll(oldBlankStaticFinals);
            currentClassFqcn = oldClassFqcn;
            currentClassSimpleName = oldClassSimple;
            currentSuperName = oldSuperName;
            currentClassIsAbstract = oldIsAbstract;
            currentClassIsInterface = oldIsInterface;
            currentClassIsEnum = oldIsEnum;
        }
    }

    private boolean isEnumFinalMethod(String name, String desc) {
        if ("name".equals(name) && (desc == null || desc.startsWith("()"))) return true;
        if ("ordinal".equals(name) && (desc == null || desc.startsWith("()"))) return true;
        if ("compareTo".equals(name) && (desc == null || (desc.startsWith("(") && desc.indexOf(')') > 1))) return true;
        if ("equals".equals(name) && (desc == null || desc.startsWith("(Ljava/lang/Object;)"))) return true;
        if ("hashCode".equals(name) && (desc == null || desc.startsWith("()"))) return true;
        return "getDeclaringClass".equals(name) && (desc == null || desc.startsWith("()"));
    }

    @Override
    public void visitField(IRField node) {
        int flags = node.getAccessFlags();
        boolean isFinal = (flags & Opcodes.ACC_FINAL) != 0;
        if (isFinal && (flags & Opcodes.ACC_VOLATILE) != 0) {
            reportError(node, "Field cannot be declared both 'final' and 'sync' (volatile): '" + node.getName() + "'");
        }

        validateAnnotations(node.getAnnotations(), "FIELD", node);
        if (node.getTypeDescriptor() != null) {
            validateGenericTypeArguments(node.getTypeDescriptor(), node);
        }

        //
        if (node.isStatic() && currentClassFqcn != null) {
            List<CompilerRegistry.TypeParameterInfo> classTpList = CompilerRegistry.globalTypeParameterRegistry.get(currentClassFqcn);
            if (classTpList != null && !classTpList.isEmpty()) {
                for (CompilerRegistry.TypeParameterInfo tp : classTpList) {
                    if (typeContainsParam(node.getTypeDescriptor(), tp.name)) {
                        reportError(node, "Static fields cannot reference class type parameter ('" + tp.name + "').");
                    }
                }
            }
        }

        if (node.getInitialValue() != null) {
            node.getInitialValue().accept(this);
            String fDesc = node.getTypeDescriptor();
            String initType = node.getInitialValue().getTypeDescriptor();
            if (fDesc != null && initType != null && !"variable".equals(fDesc) && !"value".equals(fDesc) && !"var".equals(fDesc)) {
                checkPrimitiveLiteralBounds(fDesc, node.getInitialValue(), node);
                if (!TypeChecker.isAssignable(fDesc, initType, session)) {
                    reportError(node, "Incompatible types: field '" + node.getName() + "' of type '" + TypeChecker.humanReadable(fDesc) + "' cannot be initialized with '" + TypeChecker.humanReadable(initType) + "'.");
                }
            }
        }
    }

    @Override
    public void visitMethod(IRMethod node) {
        if (node.getName() != null && node.getName().startsWith("lambda$")) {
            return;
        }
        String oldMethodName = currentMethodName;
        String oldReturnDesc = currentMethodReturnDesc;
        boolean oldStatic = isStaticContext;
        boolean oldAsync = currentMethodIsAsync;
        Set<String> oldAssignedBlankFinals = new HashSet<>(definitelyAssignedBlankFinalFields);
        Set<String> oldPotentiallyAssigned = new HashSet<>(potentiallyAssignedBlankFinalFields);
        definitelyAssignedBlankFinalFields.clear();
        potentiallyAssignedBlankFinalFields.clear();
        Map<String, LabelInfo> oldActiveLabels = new HashMap<>(activeLabels);
        activeLabels.clear();

        currentMethodName = node.getName();
        String desc = node.getDescriptor();
        if ((desc == null || !desc.contains(")")) && currentClassFqcn != null) {
            Map<String, String> mReg = CompilerRegistry.globalMethodRegistry.get(currentClassFqcn);
            if (mReg != null && mReg.containsKey(node.getName())) {
                desc = mReg.get(node.getName());
            }
        }

        validateAnnotations(node.getAnnotations(), "<init>".equals(node.getName()) ? "CONSTRUCTOR" : "METHOD", node);

        currentMethodReturnDesc = (desc != null && desc.contains(")")) ? desc.substring(desc.lastIndexOf(')') + 1) : (desc != null ? desc : "V");
        isStaticContext = node.isStatic();
        currentMethodIsAsync = node.isAsync() || (node.getDescriptor() != null && TypeChecker.isFutureType(node.getDescriptor())) || (node.getName() != null && (node.getName().startsWith("lambda$") || node.getName().endsWith("$async")));

        currentMethodDeclaredThrows.clear();
        if (node.getExceptions() != null) {
            Set<String> seenExceptions = new HashSet<>();
            for (String exc : node.getExceptions()) {
                boolean isPrimitive = TypeChecker.isPrimitive(exc) || (exc.length() == 1 && "ZBCSIJFDV".contains(exc)) || "void".equalsIgnoreCase(exc);
                boolean isArray = exc.startsWith("[") || exc.endsWith("[]") || TypeChecker.isArrayType(exc);
                if (isPrimitive) {
                    reportError(node, "Exception type cannot be a primitive type: '" + TypeChecker.humanReadable(exc) + "'");
                    continue;
                }
                if (isArray) {
                    reportError(node, "Exception type cannot be an array: '" + TypeChecker.humanReadable(exc) + "'");
                    continue;
                }
                String excDesc = OceanTypeSystem.wrapObjectType(exc);
                if (!seenExceptions.add(excDesc)) {
                    reportError(node, "Duplicate exception type in throws clause: '" + TypeChecker.humanReadable(excDesc) + "'");
                }
                if (!TypeChecker.isAssignable("Ljava/lang/Throwable;", excDesc, session)) {
                    reportError(node, "Declared exception type '" + TypeChecker.humanReadable(excDesc) + "' must extend java.lang.Throwable.");
                }
                currentMethodDeclaredThrows.add(excDesc);
            }
        }

        int access = node.getAccessFlags();
        boolean isAbstract = (access & Opcodes.ACC_ABSTRACT) != 0 || node.isAbstract();
        boolean isNative = (access & Opcodes.ACC_NATIVE) != 0 || node.isNative();
        boolean isStatic = node.isStatic();
        boolean isPrivate = (access & Opcodes.ACC_PRIVATE) != 0;
        boolean isFinal = (access & Opcodes.ACC_FINAL) != 0;
        boolean hasLockOrSync = (access & Opcodes.ACC_SYNCHRONIZED) !=0 || (access & Opcodes.ACC_VOLATILE) != 0;

        // Native method validations
        if (isNative) {
            if (isAbstract) {
                reportError(node, "Method '" + node.getName() + "' cannot be both 'native' and 'abstract'.");
            }
            if (node.isAsync()) {
                reportError(node, "Native methods cannot be declared 'async'.");
            }
            if (node.getBody() != null) {
                reportError(node, "Native methods cannot declare a body.");
            }
        }
        if (node.getBody() == null && !isAbstract && !isNative) {
            reportError(node, "Method '" + node.getName() + "' is not abstract or native and must declare a body.");
        }

        // R3-1: @Override Annotation Check
        boolean hasOverride = false;
        for (IRAnnotation ann : node.getAnnotations()) {
            if (ann.getTypeDescriptor() != null) {
                String cleanAnn = TypeChecker.cleanDescriptor(ann.getTypeDescriptor());
                if (cleanAnn.startsWith("L") && cleanAnn.endsWith(";")) cleanAnn = cleanAnn.substring(1, cleanAnn.length() - 1);
                if ("java/lang/Override".equals(cleanAnn) || "Override".equals(cleanAnn)) {
                    hasOverride = true;
                    break;
                }
            }
        }
        if (hasOverride) {
            if (isStatic) {
                reportError(node, "Static methods cannot be annotated with '@Override' (static methods cannot override, only hide).");
            } else if (!hasMatchingOverride(currentClassFqcn, node.getName(), node.getDescriptor())) {
                reportError(node, "Method '" + node.getName() + "' does not override or implement a method from a supertype.");
            }
        }

        //
        boolean hasSafeVarargs = false;
        for (IRAnnotation ann : node.getAnnotations()) {
            if (ann.getTypeDescriptor() != null) {
                String cleanAnn = TypeChecker.cleanDescriptor(ann.getTypeDescriptor());
                if (cleanAnn.startsWith("L") && cleanAnn.endsWith(";")) cleanAnn = cleanAnn.substring(1, cleanAnn.length() - 1);
                if ("java/lang/SafeVarargs".equals(cleanAnn) || "SafeVarargs".equals(cleanAnn)) {
                    hasSafeVarargs = true;
                    break;
                }
            }
        }
        if (hasSafeVarargs) {
            boolean isCtor = "<init>".equals(node.getName());
            boolean isVarargs = (access & Opcodes.ACC_VARARGS) != 0 || CompilerRegistry.isVarargsMethod(currentClassFqcn, node.getName(), node.getDescriptor());
            if (!isVarargs && node.getParameters() != null && !node.getParameters().isEmpty()) {
                String lastType = node.getParameters().getLast().typeDescriptor();
                if (lastType != null && lastType.endsWith("...")) {
                    isVarargs = true;
                }
            }
            if (!isVarargs) {
                reportError(node, "@SafeVarargs annotation can only be applied to methods or constructors with varargs parameter.");
            } else if (!isStatic && !isFinal && !isPrivate && !isCtor) {
                reportError(node, "@SafeVarargs annotation cannot be applied to overridable methods. Method must be 'static', 'final', 'private', or a constructor.");
            }
        }

        // R2-2: Constructor Async & Modifier Conflict Checks
        if ("<init>".equals(node.getName())) {
            boolean isProtected = (node.getAccessFlags() & Opcodes.ACC_PROTECTED) != 0;
            boolean isPublic = (node.getAccessFlags() & Opcodes.ACC_PUBLIC) != 0;
            if (currentClassIsEnum && (isProtected || isPublic)) {
                reportError(node, "Enum constructor cannot be declared 'public' or 'protected'.");
            }
            if (node.isAsync() || node.isAbstract() || node.isNative() || node.isStatic() || isFinal || hasLockOrSync) {
                if (currentClassIsEnum) {
                    reportError(node, "Enum constructor can only have 'private' modifier.");
                } else {
                    reportError(node, "Constructors can only have 'public', 'protected', or 'private' modifiers.");
                }
            }
        }
        if (isAbstract){
            if (!currentClassIsAbstract && !currentClassIsInterface && !currentClassIsEnum) {
                reportError(node, "Non-abstract class '" + currentClassSimpleName + "' cannot declare abstract method '" + node.getName() + "'. The class must be declared 'abstract'.");
            }
            if (node.getBody()!=null){
                reportError(node,"Abstract method '" + node.getName() + "' cannot have a body.");
            }
            if (isStatic) {
                reportError(node, "Method '" + node.getName() + "' cannot be both 'abstract' and 'static'.");
            }
            if (isPrivate) {
                reportError(node, "Method '" + node.getName() + "' cannot be both 'private' and 'abstract'.");
            }
            if (isFinal) {
                reportError(node, "Method '" + node.getName() + "' cannot be both 'abstract' and 'final'.");
            }
            if (hasLockOrSync) {
                reportError(node, "Method '" + node.getName() + "' cannot be both 'abstract' and 'sync'/'lock'.");
            }
        }



        // 2. Parameter Checks
        Set<String> seenParams = new HashSet<>();
        for (IRMethod.IRParameter param : node.getParameters()) {
            String pType = param.typeDescriptor();
            if ("variable".equals(pType) || "value".equals(pType) || "var".equals(pType)) {
                reportError(node, "Cannot use 'variable' / 'value' type for method or constructor parameter: '" + param.name() + "'");
            }
            if (!"_".equals(param.name()) && !seenParams.add(param.name())) {
                reportError(node, "Duplicate method parameter: '" + param.name() + "'");
            }
        }

        // 2.1 Variance Position Checks (+T cannot be parameter, -T cannot be return)
        boolean nullCheck = currentClassFqcn != null && CompilerRegistry.globalTypeParameterRegistry.get(currentClassFqcn) != null;
        List<CompilerRegistry.TypeParameterInfo> classTpList =nullCheck ? CompilerRegistry.globalTypeParameterRegistry.get(currentClassFqcn) : Collections.emptyList();
        if (classTpList != null && !classTpList.isEmpty() && !isStatic && !"<init>".equals(node.getName()) && !"<clinit>".equals(node.getName())) {
            for (CompilerRegistry.TypeParameterInfo tp : classTpList) {
                if (tp.variance == CompilerRegistry.Variance.COVARIANT) {
                    for (IRMethod.IRParameter param : node.getParameters()) {
                        if (typeContainsParam(param.typeDescriptor(), tp.name)) {
                            reportError(node, "Covariant type parameter '" + tp.name + "' cannot occur in contravariant position (method parameter) in method '" + node.getName() + "' of class '" + currentClassSimpleName + "'.");
                        }
                    }
                } else if (tp.variance == CompilerRegistry.Variance.CONTRAVARIANT) {
                    if (typeContainsParam(currentMethodReturnDesc, tp.name)) {
                        reportError(node, "Contravariant type parameter '" + tp.name + "' cannot occur in covariant position (return type) in method '" + node.getName() + "' of class '" + currentClassSimpleName + "'.");
                    }
                }
            }
        }

        //
        if (isStatic && !"<init>".equals(node.getName()) && !"<clinit>".equals(node.getName()) && classTpList != null && !classTpList.isEmpty()) {
            Map<String, List<String>> mTypeParamsMap = CompilerRegistry.globalMethodTypeParametersRegistry.get(currentClassFqcn);
            List<String> methodTps = mTypeParamsMap != null ? mTypeParamsMap.get(node.getName()) : null;
            Set<String> methodTpSet = methodTps != null ? new HashSet<>(methodTps) : Collections.emptySet();

            for (CompilerRegistry.TypeParameterInfo tp : classTpList) {
                if (methodTpSet.contains(tp.name)) {
                    continue;
                }
                if (typeContainsParam(currentMethodReturnDesc, tp.name)) {
                    reportError(node, "Static methods cannot reference class type parameter ('" + tp.name + "').");
                }
                for (IRMethod.IRParameter param : node.getParameters()) {
                    if (typeContainsParam(param.typeDescriptor(), tp.name)) {
                        reportError(node, "Static methods cannot reference class type parameter ('" + tp.name + "').");
                    }
                }
            }
        }

        // 3. Parent Override Checks (Final method override & Return type mismatch)
        if (currentSuperName != null && !currentSuperName.isEmpty() && !"<init>".equals(node.getName()) && !"<clinit>".equals(node.getName())) {
            validateMethodOverride(node);
        }

        int totalSlots = isStatic ? 0 : 1;
        if (node.getParameters() != null) {
            for (IRMethod.IRParameter param : node.getParameters()) {
                String pType = param.typeDescriptor();
                if ("J".equals(pType) || "D".equals(pType)) {
                    totalSlots += 2;
                } else {
                    totalSlots += 1;
                }
            }
        }
        if (totalSlots > 255) {
            reportError(node, "Method '" + node.getName() + "' parameter slots limit exceeded (" + totalSlots + " > 255). JVM permits at most 255 parameter slots.");
        }

        symbolTable.enterScope();
        inaccessiblePatternVarScopes.push(new HashSet<>());
        int oldMethodBaseScopeDepth = currentMethodBaseScopeDepth;
        currentMethodBaseScopeDepth = symbolTable.getScopeDepth();
        Set<String> oldUsedAsResourceInMethod = usedAsResourceInMethod;
        usedAsResourceInMethod = new HashSet<>();
        try {
            if (!isStatic && currentClassFqcn != null) {
                symbolTable.declareVariable("this", OceanTypeSystem.wrapObjectType(currentClassFqcn), false, true);
            }

            for (IRMethod.IRParameter param : node.getParameters()) {
                String pName = param.name();
                if (pName != null && pName.endsWith("...")) {
                    pName = pName.substring(0, pName.length() - 3).trim();
                }
                if (pName != null && pName.startsWith("...")) {
                    pName = pName.substring(3).trim();
                }
                String pType = param.typeDescriptor();
                symbolTable.declareParameter(pName, pType, param.isFinal());
            }

            boolean isCtor = "<init>".equals(node.getName()) || (currentClassSimpleName != null && currentClassSimpleName.equals(node.getName()));
            if (isCtor && currentSuperName != null && !"java/lang/Object".equals(currentSuperName)) {
                boolean hasExplicitSuperCall = isHasExplicitSuperCall(node);
                if (!hasExplicitSuperCall) {
                    String superKey = resolveTypeName(currentSuperName).replace('.', '/');
                    Map<String, List<String>> superOverloads = null;
                    for (Map.Entry<String, Map<String, List<String>>> entry : CompilerRegistry.globalOverloadRegistry.entrySet()) {
                        String k = entry.getKey();
                        //  || k.endsWith("/" + currentSuperName) || k.endsWith("." + currentSuperName) simple search
                        if (k.equals(currentSuperName) || k.equals(superKey)) {
                            superOverloads = entry.getValue();
                            break;
                        }
                    }
                    if (superOverloads != null && superOverloads.containsKey("<init>")) {
                        List<String> ctorDescs = superOverloads.get("<init>");
                        String expectedDefaultCtor = "()V";
                        if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(superKey)) {
                            String superOuter = CompilerRegistry.globalInnerClassUsedOuterMap.get(superKey);
                            expectedDefaultCtor = "(L" + superOuter + ";)V";
                        }
                        boolean hasDefaultCtor = ctorDescs != null && (ctorDescs.contains("()V") || ctorDescs.contains(expectedDefaultCtor));
                        if (!hasDefaultCtor) {
                            reportError(node, "No default (no-arg) constructor found in superclass '" + currentSuperName + "'.");
                        }
                    }
                }
            }

            if (node.getBody() != null) {
                // Ocean Flexible Constructor Bodies: Check explicit constructor calls
                int explicitCtorCount = 0;
                IRMethodCall secondExplicitCtor = null;
                boolean hasThisCall = false;

                if (node.getBody() instanceof IRBlock block) {
                    for (IRStatement stmt : block.getStatements()) {
                        if (stmt instanceof IRExprStatement exprStmt && exprStmt.getExpression() instanceof IRMethodCall mc && (mc.isSuperCall() || mc.isThisCall()) && "<init>".equals(mc.getName()) && !mc.isSynthetic()) {
                            if (currentClassIsEnum && mc.isSuperCall() && !mc.isThisCall()) {
                                reportError(mc, "Explicit 'super()' call is not allowed in enum constructors. Only 'this()' can be used.");
                            } else if (!isCtor) {
                                reportError(mc, (mc.isThisCall() ? "this(...)" : "super(...)") + " can only be invoked inside a constructor.");
                            } else {
                                explicitCtorCount++;
                                if (mc.isThisCall()) {
                                    hasThisCall = true;
                                }
                                if (explicitCtorCount > 1 && secondExplicitCtor == null) {
                                    secondExplicitCtor = mc;
                                }
                            }
                        }
                    }
                }

                if (explicitCtorCount > 1) {
                    reportError(secondExplicitCtor, "Constructor cannot contain multiple 'super()' or 'this()' calls.");
                }

                boolean oldEarly = inEarlyConstructionContext;
                inEarlyConstructionContext = (isCtor && explicitCtorCount > 0);
                IRBlock oldRootCtorBlock = currentRootCtorBlock;
                currentRootCtorBlock = (isCtor && node.getBody() instanceof IRBlock b) ? b : null;

                try {
                    node.getBody().accept(this);
                } finally {
                    currentRootCtorBlock = oldRootCtorBlock;
                    inEarlyConstructionContext = oldEarly;
                }

                if (isCtor && !hasThisCall) {
                    for (String bff : currentClassBlankFinalFields) {
                        if (!definitelyAssignedBlankFinalFields.contains(bff)) {
                            reportError(node, "Final field '" + bff + "' might not have been initialized");
                        }
                    }
                }

                // P1-3: Flow-Sensitive Return Path Analysis for Non-Void Methods
                if (!"V".equals(currentMethodReturnDesc) && !isAbstract && !"<init>".equals(node.getName()) && !"<clinit>".equals(node.getName()) && !node.getName().startsWith("lambda$")) {
                    if (node.getBody() instanceof IRBlock block) {
                        if (!allPathsReturn(block)) {
                            reportError(node, "Missing return statement at end of method '" + node.getName() + "'.");
                        }
                    }
                }
            }

            if (node.getDefaultValue() != null) {
                node.getDefaultValue().accept(this);
                String mDesc = node.getDescriptor();
                String retType = (mDesc != null && mDesc.contains(")")) ? mDesc.substring(mDesc.indexOf(')') + 1) : null;
                if (retType != null) {
                    String valType = node.getDefaultValue().getTypeDescriptor();
                    if (valType != null) {
                        checkPrimitiveLiteralBounds(retType, node.getDefaultValue(), node);
                        if (!TypeChecker.isAssignable(retType, valType, session)) {
                            reportError(node, "Incompatible type for annotation member '" + node.getName() + "' default value: expected '" + TypeChecker.humanReadable(retType) + "', found '" + TypeChecker.humanReadable(valType) + "'.");
                        }
                    }
                }
            }
        } finally {
            if (!inaccessiblePatternVarScopes.isEmpty()) {
                inaccessiblePatternVarScopes.pop();
            }
            symbolTable.exitScope();
            currentMethodBaseScopeDepth = oldMethodBaseScopeDepth;

            definitelyAssignedBlankFinalFields.clear();
            definitelyAssignedBlankFinalFields.addAll(oldAssignedBlankFinals);
            potentiallyAssignedBlankFinalFields.clear();
            potentiallyAssignedBlankFinalFields.addAll(oldPotentiallyAssigned);

            currentMethodName = oldMethodName;
            currentMethodReturnDesc = oldReturnDesc;
            isStaticContext = oldStatic;
            currentMethodIsAsync = oldAsync;
            usedAsResourceInMethod = oldUsedAsResourceInMethod;
            activeLabels.clear();
            activeLabels.putAll(oldActiveLabels);
        }
    }

    private static boolean isHasExplicitSuperCall(IRMethod node) {
        boolean hasExplicitSuperCall = false;
        if (node.getBody() instanceof IRBlock block) {
            for (IRStatement stmt : block.getStatements()) {
                if (stmt instanceof IRExprStatement exprStmt && exprStmt.getExpression() instanceof IRMethodCall mc && mc.isSuperCall() && "<init>".equals(mc.getName())) {
                    hasExplicitSuperCall = true;
                    break;
                }
            }
        }
        return hasExplicitSuperCall;
    }

    private boolean hasMatchingOverride(String classPath, String name, String desc) {
        if (classPath == null || name == null) return false;
        String key = classPath + "#" + name + "#" + (desc != null ? desc : "");
        return overrideMatchingCache.computeIfAbsent(key, k -> hasMatchingOverrideImpl(classPath, name, desc));
    }

    private boolean hasMatchingOverrideImpl(String classPath, String name, String childDesc) {
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(classPath);

        while (!queue.isEmpty()) {
            String curr = queue.poll();
            if (!visited.add(curr)) continue;

            if (!curr.equals(classPath)) {
                ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(curr);
                if (sym != null) {
                    List<ocean.compiler.symbol.MethodSymbol> methodSyms = sym.getMethods(name);
                    for (ocean.compiler.symbol.MethodSymbol ms : methodSyms) {
                        if (childDesc == null || areParamsCompatible(childDesc, ms.getDescriptor())) return true;
                    }
                }

                Map<String, List<String>> overloads = CompilerRegistry.globalOverloadRegistry.get(curr);
                if (overloads != null && overloads.containsKey(name)) {
                    List<String> descs = overloads.get(name);
                    if (descs != null) {
                        for (String parentDesc : descs) {
                            if (childDesc == null || areParamsCompatible(childDesc, parentDesc)) return true;
                        }
                    }
                }

                Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(curr);
                if (methods != null && methods.containsKey(name)) {
                    String parentDesc = methods.get(name);
                    if (childDesc == null || areParamsCompatible(childDesc, parentDesc)) return true;
                }

                try {
                    Class<?> clazz = OceanTypeSystem.forName(curr.replace('/', '.'));
                    if (clazz != null) {
                        Class<?> c = clazz;
                        while (c != null) {
                            for (Method m : c.getDeclaredMethods()) {
                                int mods = m.getModifiers();
                                if (Modifier.isPrivate(mods) || Modifier.isStatic(mods)) continue;
                                if (m.getName().equals(name)) {
                                    if (childDesc == null) return true;
                                    String parentDesc = Type.getMethodDescriptor(m);
                                    if (areParamsCompatible(childDesc, parentDesc)) return true;
                                }
                            }
                            for (Class<?> iface : c.getInterfaces()) {
                                for (Method m : iface.getMethods()) {
                                    int mods = m.getModifiers();
                                    if (Modifier.isStatic(mods)) continue;
                                    if (m.getName().equals(name)) {
                                        if (childDesc == null) return true;
                                        String parentDesc = Type.getMethodDescriptor(m);
                                        if (areParamsCompatible(childDesc, parentDesc)) return true;
                                    }
                                }
                            }
                            c = c.getSuperclass();
                        }
                    }
                } catch (Throwable ignored) {}
            }

            ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(curr);
            if (sym != null) {
                if (sym.getSuperClassName() != null) queue.add(sym.getSuperClassName());
                for (String iface : sym.getInterfaces()) {
                    if (iface != null) queue.add(iface);
                }
            }

            String sup = CompilerRegistry.globalSuperClassRegistry.get(curr);
            if (sup != null) queue.add(sup);

            String[] inters = CompilerRegistry.globalInterfaceRegistry.get(curr);
            if (inters != null) {
                for (String inter : inters) {
                    if (inter != null) queue.add(inter);
                }
            }
        }
        return false;
    }

    private boolean areParamsCompatible(String childDesc, String parentDesc) {
        if (childDesc == null || parentDesc == null) return true;
        List<String> childParams = parseDescriptorParams(childDesc);
        List<String> parentParams = parseDescriptorParams(parentDesc);

        if (childParams.size() != parentParams.size()) {
            return false;
        }

        for (int i = 0; i < childParams.size(); i++) {
            if (!isParamCompatible(childParams.get(i), parentParams.get(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean isParamCompatible(String childP, String parentP) {
        if (childP == null || parentP == null) return true;
        if (childP.equals(parentP)) return true;

        String c = cleanParamType(childP);
        String p = cleanParamType(parentP);
        if (c.equals(p)) return true;

        if (isGenericTypeVariable(c) || isGenericTypeVariable(p)) {
            return true;
        }

        if ("java/lang/Object".equals(p) || "Object".equals(p)) {
            if (TypeChecker.isObjectType(childP) || TypeChecker.isObjectType(c)) return true;
        }
        if ("java/lang/Object".equals(c) || "Object".equals(c)) {
            if (TypeChecker.isObjectType(parentP) || TypeChecker.isObjectType(p)) return true;
        }

        return TypeChecker.isAssignable(p, c, session) || TypeChecker.isAssignable(c, p, session);
    }

    private boolean isGenericTypeVariable(String type) {
        if (type == null) return false;
        String t = type.trim();
        if (t.startsWith("L") && t.endsWith(";")) {
            t = t.substring(1, t.length() - 1);
        }
        if ("I".equals(t) || "J".equals(t) || "Z".equals(t) || "D".equals(t) || "F".equals(t) || "B".equals(t) || "C".equals(t) || "S".equals(t) || "V".equals(t)) {
            return false;
        }
        if (t.length() == 1 && Character.isUpperCase(t.charAt(0))) return true;
        return t.length() == 2 && Character.isUpperCase(t.charAt(0)) && Character.isLetterOrDigit(t.charAt(1));
    }

    private String cleanParamType(String p) {
        if (p == null) return "";
        String s = p.trim();
        if (s.startsWith("L") && s.endsWith(";")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.contains("<")) {
            s = s.substring(0, s.indexOf('<')).trim();
        }
        return s.replace('.', '/');
    }

    private boolean hasFieldInClassHierarchy(String classFqcn, String fieldName) {
        if (classFqcn == null || fieldName == null) return false;
        String curr = classFqcn;
        Set<String> visited = new HashSet<>();
        while (curr != null && !"java/lang/Object".equals(curr) && visited.add(curr)) {
            Map<String, String> fields = CompilerRegistry.globalFieldRegistry.get(curr);
            if (fields != null && fields.containsKey(fieldName)) {
                return true;
            }
            curr = CompilerRegistry.globalSuperClassRegistry.get(curr);
        }
        return false;
    }

    // P1-3: Flow-Sensitive Return Path Analysis Implementation
    private boolean allPathsReturn(IRBlock block) {
        if (block == null || block.getStatements().isEmpty()) return false;
        for (IRStatement stmt : block.getStatements()) {
            if (statementReturns(stmt)) return true;
        }
        return false;
    }

    private boolean statementReturns(IRStatement stmt) {
        if (stmt instanceof IRReturnStatement || stmt instanceof IRThrowStatement) return true;
        if (stmt instanceof IRBlock block) return allPathsReturn(block);
        if (stmt instanceof IRIfStatement ifStmt) {
            if (ifStmt.getCondition() instanceof IRLiteral lit && Boolean.TRUE.equals(lit.getValue())) {
                return statementReturns(ifStmt.getThenBranch());
            }
            boolean thenRet = statementReturns(ifStmt.getThenBranch());
            boolean elseRet = ifStmt.getElseBranch() != null && statementReturns(ifStmt.getElseBranch());
            return thenRet && elseRet;
        }
        if (stmt instanceof IRSwitchStatement switchStmt) {
            if (switchStmt.getDefaultBlock() == null || !statementReturns(switchStmt.getDefaultBlock())) return false;
            for (IRSwitchCase c : switchStmt.getCases()) {
                if (c.getBody() == null || c.getBody() instanceof IRStatement body && !statementReturns(body)) return false;
            }
            return true;
        }
        if (stmt instanceof IRTryCatchStatement tryStmt) {
            if (tryStmt.getFinallyBlock() != null && statementReturns(tryStmt.getFinallyBlock())) return true;
            if (!statementReturns(tryStmt.getTryBlock())) return false;
            for (IRTryCatchStatement.IRCatchClause catchClause : tryStmt.getCatchClauses()) {
                if (catchClause.body() == null || !statementReturns(catchClause.body())) return false;
            }
            return true;
        }
        if (stmt instanceof IRWhileStatement whileStmt) {
            if (whileStmt.getCondition() instanceof IRLiteral lit && Boolean.TRUE.equals(lit.getValue())) {
                IRStatement body = whileStmt.getBody();
                if (statementReturns(body)) return true;
                return !loopCanBreak(body, null);
            }
        }
        if (stmt instanceof IRDoWhileStatement doWhileStmt) {
            IRStatement body = doWhileStmt.getBody();
            if (statementReturns(body)) return true;
            if (doWhileStmt.getCondition() instanceof IRLiteral lit && Boolean.TRUE.equals(lit.getValue())) {
                return !loopCanBreak(body, null);
            }
        }
        if (stmt instanceof IRLockStatement lockStmt) {
            return statementReturns(lockStmt.getBody());
        }
        if (stmt instanceof IRLabeledStatement labeledStmt) {
            if (hasStopForLabel(labeledStmt.getStatement(), labeledStmt.getLabel())) {
                return false;
            }
            return statementReturns(labeledStmt.getStatement());
        }
        return false;
    }

    private static class LoopStopFinder extends BaseIRVisitor {
        private final String targetLabel;
        private int nestedLoopDepth = 0;
        private boolean found = false;

        LoopStopFinder(String targetLabel) {
            this.targetLabel = targetLabel;
        }

        @Override
        public void visitStop(IRStopStatement node) {
            if (targetLabel != null && targetLabel.equals(node.getTargetLabel())) {
                found = true;
            } else if (targetLabel == null && (node.getTargetLabel() == null || node.getTargetLabel().isEmpty()) && nestedLoopDepth == 0) {
                found = true;
            }
        }

        @Override
        public void visitWhile(IRWhileStatement node) {
            nestedLoopDepth++;
            try {
                super.visitWhile(node);
            } finally {
                nestedLoopDepth--;
            }
        }

        @Override
        public void visitDoWhile(IRDoWhileStatement node) {
            nestedLoopDepth++;
            try {
                super.visitDoWhile(node);
            } finally {
                nestedLoopDepth--;
            }
        }

        @Override
        public void visitForStatement(IRForStatement node) {
            nestedLoopDepth++;
            try {
                super.visitForStatement(node);
            } finally {
                nestedLoopDepth--;
            }
        }
    }

    private boolean loopCanBreak(IRNode body, String targetLabel) {
        if (body == null) return false;
        LoopStopFinder finder = new LoopStopFinder(targetLabel);
        body.accept(finder);
        return finder.found;
    }

    private boolean hasStopForLabel(IRNode node, String label) {
        return loopCanBreak(node, label);
    }

    private void validateMethodOverride(IRMethod node) {
        if (node == null || node.getName() == null) return;
        String key = currentClassFqcn + "#" + node.getName() + "#" + (node.getDescriptor() != null ? node.getDescriptor() : "");
        methodOverrideCache.computeIfAbsent(key, k -> {
            validateMethodOverrideImpl(node);
            return true;
        });
    }

    private void validateMethodOverrideImpl(IRMethod node) {
        String methodName = node.getName();
        if ("<init>".equals(methodName) || "<clinit>".equals(methodName)) return;
        String desc = node.getDescriptor();
        boolean isChildStatic = node.isStatic() || (node.getAccessFlags() & Opcodes.ACC_STATIC) != 0;
        if (isChildStatic) {
            validateStaticMethodAgainstInterfaces(node, methodName, desc);
        }
        String childRet = (desc != null && desc.contains(")")) ? desc.substring(desc.lastIndexOf(')') + 1) : "V";
        String current = currentSuperName;

        while (current != null) {
            Map<String, String> parentMethods = CompilerRegistry.globalMethodRegistry.get(current);
            Map<String, List<String>> classOverloads = CompilerRegistry.globalOverloadRegistry.get(current);
            List<String> overloads = classOverloads != null ? classOverloads.get(methodName) : null;
            String matchedParentDesc = null;
            if (overloads != null && !overloads.isEmpty()) {
                for (String ovDesc : overloads) {
                    if (areParamsCompatible(desc, ovDesc)) {
                        matchedParentDesc = ovDesc;
                        break;
                    }
                }
            }
            if (matchedParentDesc == null && parentMethods != null && parentMethods.containsKey(methodName)) {
                String candidate = parentMethods.get(methodName);
                if (candidate != null && areParamsCompatible(desc, candidate)) {
                    matchedParentDesc = candidate;
                }
            }

            if (matchedParentDesc != null) {

                int parentAccess = CompilerRegistry.globalMethodAccess.getOrDefault(current, Collections.emptyMap()).getOrDefault(methodName, 0);
                if ((parentAccess & Opcodes.ACC_PRIVATE) != 0) {
                    current = CompilerRegistry.globalSuperClassRegistry.get(current);
                    continue;
                }

                boolean isParentStatic = (parentAccess & Opcodes.ACC_STATIC) != 0;

                if (isParentStatic && !isChildStatic) {
                    reportError(node, "Instance method '" + methodName + "' cannot override static method from superclass.");
                } else if (!isParentStatic && isChildStatic) {
                    reportError(node, "Static method '" + methodName + "' cannot hide instance method from superclass.");
                }

                boolean isParentFinal = (parentAccess & Opcodes.ACC_FINAL) != 0;
                if (isParentFinal) {
                    reportError(node, "Cannot override final method '" + methodName + "' from class '" + current + "'.");
                }

                int childAccess = node.getAccessFlags();
                if ((parentAccess & Opcodes.ACC_PUBLIC) != 0) {
                    if ((childAccess & Opcodes.ACC_PUBLIC) == 0) {
                        reportError(node, "Cannot reduce visibility: method '" + methodName + "' overriding public method must be 'public'.");
                    }
                } else if ((parentAccess & Opcodes.ACC_PROTECTED) != 0) {
                    if ((childAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) {
                        reportError(node, "Cannot reduce visibility: method '" + methodName + "' overriding protected method must be 'protected' or 'public'.");
                    }
                }

                String parentRet = matchedParentDesc.substring(matchedParentDesc.lastIndexOf(')') + 1);
                boolean strictSub = isGenericTypeVariable(parentRet) || isGenericTypeVariable(childRet) ||
                        (session != null ? session.isSubType(childRet, parentRet) : TypeChecker.isAssignable(parentRet, childRet,null));
                if (!childRet.equals(parentRet) && !strictSub) {
                    reportError(node, "Incompatible return type when overriding method '" + methodName + "': '" + TypeChecker.humanReadable(childRet) + "' is not compatible with '" + TypeChecker.humanReadable(parentRet) + "'.");
                }
                Map<String, List<String>> throwsMap = CompilerRegistry.globalMethodThrowsRegistry.get(current);
                List<String> parentThrows = throwsMap != null ? throwsMap.containsKey(methodName + matchedParentDesc) ? throwsMap.get(methodName + matchedParentDesc) : throwsMap.get(methodName) : null;
                checkOverriddenMethodThrows(node, parentThrows, current);
                break;
            } else {
                try {
                    Class<?> clazz = OceanTypeSystem.forName(current.replace('/', '.'));
                    if (clazz != null) {
                        Class<?> c = clazz;
                        while (c != null) {
                            for (Method m : c.getDeclaredMethods()) {
                                int mods = m.getModifiers();
                                if (Modifier.isPrivate(mods)) continue;
                                if (m.getName().equals(methodName)) {
                                    String parentDesc = Type.getMethodDescriptor(m);
                                    if (areParamsCompatible(desc, parentDesc)) {
                                        boolean isParentStatic = Modifier.isStatic(mods);

                                        if (isParentStatic && !isChildStatic) {
                                            reportError(node, "Instance method '" + methodName + "' cannot override static method from superclass.");
                                        } else if (!isParentStatic && isChildStatic) {
                                            reportError(node, "Static method '" + methodName + "' cannot hide instance method from superclass.");
                                        }

                                        if (Modifier.isFinal(mods)) {
                                            reportError(node, "Cannot override final method '" + methodName + "' from class '" + c.getName() + "'.");
                                        }
                                        int childAccess = node.getAccessFlags();
                                        if (Modifier.isPublic(mods)) {
                                            if ((childAccess & Opcodes.ACC_PUBLIC) == 0) {
                                                reportError(node, "Cannot reduce visibility: method '" + methodName + "' overriding public method must be 'public'.");
                                            }
                                        } else if (Modifier.isProtected(mods)) {
                                            if ((childAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) {
                                                reportError(node, "Cannot reduce visibility: method '" + methodName + "' overriding protected method must be 'protected' or 'public'.");
                                            }
                                        }
                                        String parentRet = Type.getReturnType(m).getDescriptor();
                                        boolean strictSub = isGenericTypeVariable(parentRet) || isGenericTypeVariable(childRet) ||
                                                (session != null ? session.isSubType(childRet, parentRet) : TypeChecker.isAssignable(parentRet, childRet, null));
                                        if (!childRet.equals(parentRet) && !strictSub) {
                                            reportError(node, "Incompatible return type when overriding method '" + methodName + "': '" + TypeChecker.humanReadable(childRet) + "' is not compatible with '" + TypeChecker.humanReadable(parentRet) + "'.");
                                        }
                                        List<String> parentThrows = new ArrayList<>();
                                        for (Class<?> e : m.getExceptionTypes()) {
                                            parentThrows.add(e.getName().replace('.', '/'));
                                        }
                                        checkOverriddenMethodThrows(node, parentThrows, c.getName());
                                        return;
                                    }
                                }
                            }
                            c = c.getSuperclass();
                        }
                        break;
                    }
                } catch (Throwable ignored) {}
            }
            current = CompilerRegistry.globalSuperClassRegistry.get(current);
        }

        // Check implemented interfaces
        String[] directInterfaces = CompilerRegistry.globalInterfaceRegistry.get(currentClassFqcn);
        if (directInterfaces != null) {
            for (String iface : directInterfaces) {
                checkInterfaceMethodOverride(node, iface);
            }
        }
    }

    private void checkOverriddenMethodThrows(IRMethod childMethod, List<String> parentThrows, String parentName) {
        if (childMethod == null || childMethod.getExceptions() == null || childMethod.getExceptions().isEmpty()) return;
        for (String childExc : childMethod.getExceptions()) {
            String cleanChild = TypeChecker.cleanDescriptor(childExc);
            if (cleanChild.startsWith("L") && cleanChild.endsWith(";")) cleanChild = cleanChild.substring(1, cleanChild.length() - 1);
            if (!isCheckedException(cleanChild)) continue;
            boolean covered = false;
            if (parentThrows != null) {
                for (String parentExc : parentThrows) {
                    String cleanParent = TypeChecker.cleanDescriptor(parentExc);
                    if (cleanParent.startsWith("L") && cleanParent.endsWith(";")) cleanParent = cleanParent.substring(1, cleanParent.length() - 1);
                    if (cleanChild.equals(cleanParent) || TypeChecker.isAssignable(cleanParent, cleanChild, session)) {
                        covered = true;
                        break;
                    }
                }
            }
            if (!covered) {
                reportError(childMethod, "Overridden method in '" + OceanTypeSystem.findSimpleName(parentName) + "' does not throw checked exception '" + TypeChecker.humanReadable(cleanChild) + "'");
            }
        }
    }

    private void checkInterfaceMethodOverride(IRMethod node, String iface) {
        if (iface == null || node == null) return;
        String methodName = node.getName();
        String desc = node.getDescriptor();
        Map<String, String> ifaceMethods = CompilerRegistry.globalMethodRegistry.get(iface);
        Map<String, List<String>> ifaceOverloads = CompilerRegistry.globalOverloadRegistry.get(iface);
        List<String> overloads = ifaceOverloads != null ? ifaceOverloads.get(methodName) : null;
        String matchedParentDesc = null;
        if (overloads != null && !overloads.isEmpty()) {
            for (String ovDesc : overloads) {
                if (areParamsCompatible(desc, ovDesc)) {
                    matchedParentDesc = ovDesc;
                    break;
                }
            }
        }
        if (matchedParentDesc == null && ifaceMethods != null && ifaceMethods.containsKey(methodName)) {
            String candidate = ifaceMethods.get(methodName);
            if (candidate == null || areParamsCompatible(desc, candidate)) {
                matchedParentDesc = candidate;
            }
        }
        if (matchedParentDesc != null) {
            Map<String, List<String>> throwsMap = CompilerRegistry.globalMethodThrowsRegistry.get(iface);
            List<String> parentThrows = throwsMap != null ? (throwsMap.containsKey(methodName + matchedParentDesc) ? throwsMap.get(methodName + matchedParentDesc) : throwsMap.get(methodName)) : null;
            checkOverriddenMethodThrows(node, parentThrows, iface);
        } else {
            try {
                Class<?> clazz = OceanTypeSystem.forName(iface.replace('/', '.'));
                if (clazz != null) {
                    for (Method m : clazz.getMethods()) {
                        if (m.getName().equals(methodName)) {
                            String parentDesc = Type.getMethodDescriptor(m);
                            if (areParamsCompatible(desc, parentDesc)) {
                                List<String> parentThrows = new ArrayList<>();
                                for (Class<?> e : m.getExceptionTypes()) {
                                    parentThrows.add(e.getName().replace('.', '/'));
                                }
                                checkOverriddenMethodThrows(node, parentThrows, clazz.getName());
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void visitExprStatement(IRExprStatement node) {
        if (node.getExpression() != null) {
            node.getExpression().accept(this);
            validateExpressionStatementSideEffects(node);
        }
    }

    private void validateExpressionStatementSideEffects(IRExprStatement node) {
        IRExpression expr = node.getExpression();
        if (expr != null && !hasSideEffects(expr)) {
            reportWarning(node, "Statement expression has no side effect and its result is unused.");
        }
    }

    private boolean hasSideEffects(IRExpression expr) {
        return switch (expr) {
            case null -> false;
            case IRAssignment irAssignment -> true;
            case IRUnaryOp unary -> unary.getOperator() == IRUnaryOp.Op.PRE_INC
                    || unary.getOperator() == IRUnaryOp.Op.PRE_DEC
                    || unary.getOperator() == IRUnaryOp.Op.POST_INC
                    || unary.getOperator() == IRUnaryOp.Op.POST_DEC;
            case IRMethodCall irMethodCall -> true;
            case IRNewObject irNewObject -> true;
            case IROceanOutput irOceanOutput -> true;
            default -> expr instanceof IRAwaitExpression;
        };
    }

    @Override
    public void visitBlock(IRBlock node) {
        symbolTable.enterScope();
        nullabilityScopes.push(new HashMap<>());
        typeNarrowingScopes.push(new HashMap<>());
        inaccessiblePatternVarScopes.push(new HashSet<>());
        try {
            boolean foundTerminator = false;
            boolean isCtorRoot = (node == currentRootCtorBlock);
            for (IRStatement stmt : node.getStatements()) {
                if (foundTerminator) {
                    reportWarning(stmt, "Unreachable code");
                    break;
                }
                boolean isTopLevelCtorStmt = isCtorRoot && (stmt instanceof IRExprStatement exprStmt && exprStmt.getExpression() instanceof IRMethodCall mc && "<init>".equals(mc.getName()) && (mc.isSuperCall() || mc.isThisCall()));
                boolean oldTopLevel = currentStmtIsTopLevelCtorStmt;
                currentStmtIsTopLevelCtorStmt = isTopLevelCtorStmt;
                try {
                    stmt.accept(this);
                } finally {
                    currentStmtIsTopLevelCtorStmt = oldTopLevel;
                }
                if (isStatementTerminating(stmt)) {
                    foundTerminator = true;
                }
            }
        } finally {
            if (!inaccessiblePatternVarScopes.isEmpty()) {
                inaccessiblePatternVarScopes.pop();
            }
            typeNarrowingScopes.pop();
            lastBlockNullabilitySnapshot = getNullabilitySnapshot();
            lastBlockInitSnapshot = symbolTable.getInitializationSnapshot();
            nullabilityScopes.pop();
            symbolTable.exitScope();
        }
    }

    private boolean isStatementTerminating(IRStatement stmt) {
        if (stmt == null) return false;
        return statementReturns(stmt) || stmt instanceof IRStopStatement || stmt instanceof IRSkipStatement || stmt instanceof IRThrowStatement;
    }

    // P2-1: Flow-sensitive nullability and type narrowing extraction and scope tracking
    @Override
    public void visitIf(IRIfStatement node) {
        if (node.getCondition() != null) {
            node.getCondition().accept(this);
            String condType = node.getCondition().getTypeDescriptor();
            if (condType != null && !TypeChecker.isBoolean(condType) && !TypeChecker.isObjectType(condType)) {
                reportError(node, "Condition in 'if' statement must be boolean, found '" + TypeChecker.humanReadable(condType) + "'");
            }
            if (node.getCondition() instanceof IRLiteral lit && lit.getValue() instanceof Boolean b) {
                reportWarning(node, "Constant condition detected: 'if (" + b + ")'");
            }
        }

        Map<String, Boolean> initBefore = symbolTable.getInitializationSnapshot();

        Map<String, String> whenTrueVars = new HashMap<>();
        Map<String, String> whenFalseVars = new HashMap<>();
        extractPatternVariables(node.getCondition(), whenTrueVars, whenFalseVars);

        Map<String, String> allPatternVarsInCond = new HashMap<>();
        collectAllPatternVarsInExpr(node.getCondition(), allPatternVarsInCond);

        Set<String> invalidInThen = new HashSet<>(allPatternVarsInCond.keySet());
        invalidInThen.removeAll(whenTrueVars.keySet());

        Set<String> invalidInElse = new HashSet<>(allPatternVarsInCond.keySet());
        invalidInElse.removeAll(whenFalseVars.keySet());

        Map<String, Boolean> thenNonNull = new HashMap<>();
        Map<String, Boolean> elseNonNull = new HashMap<>();
        extractNullChecks(node.getCondition(), thenNonNull, elseNonNull);

        Map<String, String> thenNarrowed = new HashMap<>();
        Map<String, String> elseNarrowed = new HashMap<>();
        extractTypeNarrowing(node.getCondition(), thenNarrowed, elseNarrowed);

        Set<String> daBefore = new HashSet<>(definitelyAssignedBlankFinalFields);
        Set<String> paBefore = new HashSet<>(potentiallyAssignedBlankFinalFields);
        Set<String> daStaticBefore = new HashSet<>(definitelyAssignedBlankStaticFinalFields);
        Set<String> paStaticBefore = new HashSet<>(potentiallyAssignedBlankStaticFinalFields);

        nullabilityScopes.push(thenNonNull);
        typeNarrowingScopes.push(thenNarrowed);
        inaccessiblePatternVarScopes.push(invalidInThen);
        Map<String, Boolean> thenNull = null;
        try {
            if (node.getThenBranch() != null) {
                node.getThenBranch().accept(this);
                thenNull = lastBlockNullabilitySnapshot != null ? lastBlockNullabilitySnapshot : getNullabilitySnapshot();
            }
        } finally {
            if (!inaccessiblePatternVarScopes.isEmpty()) {
                inaccessiblePatternVarScopes.pop();
            }
            typeNarrowingScopes.pop();
            nullabilityScopes.pop();
        }
        Map<String, Boolean> thenInit = symbolTable.getInitializationSnapshot();
        Set<String> daThen = new HashSet<>(definitelyAssignedBlankFinalFields);
        Set<String> paThen = new HashSet<>(potentiallyAssignedBlankFinalFields);
        Set<String> daStaticThen = new HashSet<>(definitelyAssignedBlankStaticFinalFields);
        Set<String> paStaticThen = new HashSet<>(potentiallyAssignedBlankStaticFinalFields);

        Map<String, Boolean> elseInit = null;
        Map<String, Boolean> elseNull = null;
        Set<String> daElse = null;
        Set<String> paElse = null;
        Set<String> daStaticElse = null;
        Set<String> paStaticElse = null;
        if (node.getElseBranch() != null) {
            symbolTable.restoreInitializationSnapshot(initBefore);
            definitelyAssignedBlankFinalFields.clear();
            definitelyAssignedBlankFinalFields.addAll(daBefore);
            potentiallyAssignedBlankFinalFields.clear();
            potentiallyAssignedBlankFinalFields.addAll(paBefore);
            definitelyAssignedBlankStaticFinalFields.clear();
            definitelyAssignedBlankStaticFinalFields.addAll(daStaticBefore);
            potentiallyAssignedBlankStaticFinalFields.clear();
            potentiallyAssignedBlankStaticFinalFields.addAll(paStaticBefore);

            nullabilityScopes.push(elseNonNull);
            typeNarrowingScopes.push(elseNarrowed);
            inaccessiblePatternVarScopes.push(invalidInElse);
            try {
                node.getElseBranch().accept(this);
                elseNull = lastBlockNullabilitySnapshot != null ? lastBlockNullabilitySnapshot : getNullabilitySnapshot();
            } finally {
                if (!inaccessiblePatternVarScopes.isEmpty()) {
                    inaccessiblePatternVarScopes.pop();
                }
                typeNarrowingScopes.pop();
                nullabilityScopes.pop();
            }
            elseInit = symbolTable.getInitializationSnapshot();
            daElse = new HashSet<>(definitelyAssignedBlankFinalFields);
            paElse = new HashSet<>(potentiallyAssignedBlankFinalFields);
            daStaticElse = new HashSet<>(definitelyAssignedBlankStaticFinalFields);
            paStaticElse = new HashSet<>(potentiallyAssignedBlankStaticFinalFields);
        }

        // Guard Clause (Early Exit) propagation & Definite Assignment merge:
        boolean thenTerm = isStatementTerminating(node.getThenBranch());
        boolean elseTerm = node.getElseBranch() != null && isStatementTerminating(node.getElseBranch());

        if (thenTerm) {
            if (!nullabilityScopes.isEmpty()) {
                nullabilityScopes.peek().putAll(elseNonNull);
            }
            if (!typeNarrowingScopes.isEmpty()) {
                typeNarrowingScopes.peek().putAll(elseNarrowed);
            }
            if (elseNull != null) {
                applyNullabilitySnapshot(elseNull);
            }
            if (elseInit != null) {
                symbolTable.restoreInitializationSnapshot(elseInit);
                definitelyAssignedBlankFinalFields.clear();
                definitelyAssignedBlankFinalFields.addAll(daElse);
                potentiallyAssignedBlankFinalFields.clear();
                potentiallyAssignedBlankFinalFields.addAll(paElse);
                definitelyAssignedBlankStaticFinalFields.clear();
                definitelyAssignedBlankStaticFinalFields.addAll(daStaticElse);
                potentiallyAssignedBlankStaticFinalFields.clear();
                potentiallyAssignedBlankStaticFinalFields.addAll(paStaticElse);
            } else {
                symbolTable.restoreInitializationSnapshot(initBefore);
                definitelyAssignedBlankFinalFields.clear();
                definitelyAssignedBlankFinalFields.addAll(daBefore);
                potentiallyAssignedBlankFinalFields.clear();
                potentiallyAssignedBlankFinalFields.addAll(paBefore);
                definitelyAssignedBlankStaticFinalFields.clear();
                definitelyAssignedBlankStaticFinalFields.addAll(daStaticBefore);
                potentiallyAssignedBlankStaticFinalFields.clear();
                potentiallyAssignedBlankStaticFinalFields.addAll(paStaticBefore);
            }
            for (Map.Entry<String, String> e : whenFalseVars.entrySet()) {
                if (!symbolTable.isDeclaredInCurrentScope(e.getKey())) {
                    symbolTable.declareVariable(e.getKey(), e.getValue(), true, true);
                }
            }
            if (!inaccessiblePatternVarScopes.isEmpty()) {
                Set<String> invalidOutside = new HashSet<>(allPatternVarsInCond.keySet());
                invalidOutside.removeAll(whenFalseVars.keySet());
                inaccessiblePatternVarScopes.peek().addAll(invalidOutside);
            }
        } else if (elseTerm) {
            if (!nullabilityScopes.isEmpty()) {
                nullabilityScopes.peek().putAll(thenNonNull);
            }
            if (!typeNarrowingScopes.isEmpty()) {
                typeNarrowingScopes.peek().putAll(thenNarrowed);
            }
            if (thenNull != null) {
                applyNullabilitySnapshot(thenNull);
            }
            symbolTable.restoreInitializationSnapshot(thenInit);
            definitelyAssignedBlankFinalFields.clear();
            definitelyAssignedBlankFinalFields.addAll(daThen);
            potentiallyAssignedBlankFinalFields.clear();
            potentiallyAssignedBlankFinalFields.addAll(paThen);
            definitelyAssignedBlankStaticFinalFields.clear();
            definitelyAssignedBlankStaticFinalFields.addAll(daStaticThen);
            potentiallyAssignedBlankStaticFinalFields.clear();
            potentiallyAssignedBlankStaticFinalFields.addAll(paStaticThen);

            for (Map.Entry<String, String> e : whenTrueVars.entrySet()) {
                if (!symbolTable.isDeclaredInCurrentScope(e.getKey())) {
                    symbolTable.declareVariable(e.getKey(), e.getValue(), true, true);
                }
            }
            if (!inaccessiblePatternVarScopes.isEmpty()) {
                Set<String> invalidOutside = new HashSet<>(allPatternVarsInCond.keySet());
                invalidOutside.removeAll(whenTrueVars.keySet());
                inaccessiblePatternVarScopes.peek().addAll(invalidOutside);
            }
        } else {
            if (elseInit != null) {
                symbolTable.mergeInitializationSnapshots(thenInit, elseInit);
                if (thenNull != null) {
                    mergeNullabilitySnapshots(thenNull, elseNull);
                }
                definitelyAssignedBlankFinalFields.clear();
                definitelyAssignedBlankFinalFields.addAll(daThen);
                definitelyAssignedBlankFinalFields.retainAll(daElse);

                potentiallyAssignedBlankFinalFields.clear();
                potentiallyAssignedBlankFinalFields.addAll(paThen);
                potentiallyAssignedBlankFinalFields.addAll(paElse);

                definitelyAssignedBlankStaticFinalFields.clear();
                definitelyAssignedBlankStaticFinalFields.addAll(daStaticThen);
                definitelyAssignedBlankStaticFinalFields.retainAll(daStaticElse);

                potentiallyAssignedBlankStaticFinalFields.clear();
                potentiallyAssignedBlankStaticFinalFields.addAll(paStaticThen);
                potentiallyAssignedBlankStaticFinalFields.addAll(paStaticElse);
            } else {
                symbolTable.restoreInitializationSnapshot(initBefore);
                definitelyAssignedBlankFinalFields.clear();
                definitelyAssignedBlankFinalFields.addAll(daBefore);

                potentiallyAssignedBlankFinalFields.clear();
                potentiallyAssignedBlankFinalFields.addAll(paBefore);
                potentiallyAssignedBlankFinalFields.addAll(paThen);

                definitelyAssignedBlankStaticFinalFields.clear();
                definitelyAssignedBlankStaticFinalFields.addAll(daStaticBefore);

                potentiallyAssignedBlankStaticFinalFields.clear();
                potentiallyAssignedBlankStaticFinalFields.addAll(paStaticBefore);
                potentiallyAssignedBlankStaticFinalFields.addAll(paStaticThen);
            }
            if (!inaccessiblePatternVarScopes.isEmpty()) {
                inaccessiblePatternVarScopes.peek().addAll(allPatternVarsInCond.keySet());
            }
        }
    }

    @Override
    public void visitWhile(IRWhileStatement node) {
        Map<String, Boolean> initBefore = symbolTable.getInitializationSnapshot();
        loopDepth++;
        controlTargetStack.push(ControlTarget.LOOP);
        try {
            if (node.getCondition() != null) {
                node.getCondition().accept(this);
                String condType = node.getCondition().getTypeDescriptor();
                if (condType != null && !TypeChecker.isBoolean(condType) && !TypeChecker.isObjectType(condType)) {
                    reportError(node, "Condition in 'while' loop must be boolean, found '" + TypeChecker.humanReadable(condType) + "'");
                }
                if (node.getCondition() instanceof IRLiteral lit && lit.getValue() instanceof Boolean b) {
                    reportWarning(node, "Constant condition detected: 'while (" + b + ")'");
                }
            }

            Map<String, Boolean> thenNonNull = new HashMap<>();
            Map<String, Boolean> elseNonNull = new HashMap<>();
            extractNullChecks(node.getCondition(), thenNonNull, elseNonNull);

            Map<String, String> thenNarrowed = new HashMap<>();
            Map<String, String> elseNarrowed = new HashMap<>();
            extractTypeNarrowing(node.getCondition(), thenNarrowed, elseNarrowed);

            nullabilityScopes.push(thenNonNull);
            typeNarrowingScopes.push(thenNarrowed);
            try {
                if (node.getBody() != null) node.getBody().accept(this);
            } finally {
                typeNarrowingScopes.pop();
                nullabilityScopes.pop();
            }
        } finally {
            controlTargetStack.pop();
            loopDepth--;
            boolean isWhileTrue = (node.getCondition() instanceof IRLiteral lit && Boolean.TRUE.equals(lit.getValue()));
            if (!isWhileTrue) {
                symbolTable.restoreInitializationSnapshot(initBefore);
            }
        }
    }

    @Override
    public void visitDoWhile(IRDoWhileStatement node) {
        loopDepth++;
        controlTargetStack.push(ControlTarget.LOOP);
        try {
            if (node.getBody() != null) node.getBody().accept(this);
            if (node.getCondition() != null) {
                node.getCondition().accept(this);
                String condType = node.getCondition().getTypeDescriptor();
                if (condType != null && !TypeChecker.isBoolean(condType) && !TypeChecker.isObjectType(condType)) {
                    reportError(node, "Condition in 'do-while' loop must be boolean, found '" + TypeChecker.humanReadable(condType) + "'");
                }
            }
        } finally {
            controlTargetStack.pop();
            loopDepth--;
        }
    }

    @Override
    public void visitForStatement(IRForStatement node) {
        Map<String, Boolean> initBefore = symbolTable.getInitializationSnapshot();
        symbolTable.enterScope();
        try {
            if (node.getIteratorName() != null) {
                if (!"_".equals(node.getIteratorName()) && currentMethodBaseScopeDepth > 0 && symbolTable.isDeclaredInMethodScope(node.getIteratorName(), currentMethodBaseScopeDepth)) {
                    reportError(node, "Loop variable '" + node.getIteratorName() + "' is already defined in this method scope.");
                }
                symbolTable.declareParameter(node.getIteratorName(), node.getTypeDescriptor() != null ? node.getTypeDescriptor() : "I");
                symbolTable.markMutated(node.getIteratorName());
            }
            loopDepth++;
            controlTargetStack.push(ControlTarget.LOOP);
            try {
                if (node.isRange()) {
                    String iterType = node.getTypeDescriptor();
                    if (iterType != null && !iterType.equals("variable") && !iterType.equals("value") && !iterType.equals("var") && !isArithmeticOperand(iterType)) {
                        reportError(node, "Range-based 'for' loop (from ... to ...) is only applicable to numeric types: '" + TypeChecker.humanReadable(iterType) + "'");
                    }
                    if (node.getFromExpr() != null) {
                        node.getFromExpr().accept(this);
                        String fromType = node.getFromExpr().getTypeDescriptor();
                        if (fromType != null && !isArithmeticOperand(fromType)) {
                            reportError(node.getFromExpr(), "Range 'from' expression must be a numeric type: '" + TypeChecker.humanReadable(fromType) + "'");
                        }
                    }
                    if (node.getToExpr() != null) {
                        node.getToExpr().accept(this);
                        String toType = node.getToExpr().getTypeDescriptor();
                        if (toType != null && !isArithmeticOperand(toType)) {
                            reportError(node.getToExpr(), "Range 'to' expression must be a numeric type: '" + TypeChecker.humanReadable(toType) + "'");
                        }
                    }
                    if (node.getStepExpr() != null) {
                        node.getStepExpr().accept(this);
                        String stepType = node.getStepExpr().getTypeDescriptor();
                        if (stepType != null && !isArithmeticOperand(stepType)) {
                            reportError(node.getStepExpr(), "Range 'step' expression must be a numeric type: '" + TypeChecker.humanReadable(stepType) + "'");
                        }
                        if (node.getStepExpr() instanceof IRLiteral lit && lit.getValue() instanceof Number n && n.doubleValue() == 0.0) {
                            reportError(node.getStepExpr(), "For loop step value cannot be 0.");
                        }
                    }
                } else if (node.getIterableExpr() != null) {
                    node.getIterableExpr().accept(this);
                    String iterType = node.getIterableExpr().getTypeDescriptor();
                    if (iterType != null && !iterType.equals("variable") && !iterType.equals("value") && !iterType.equals("var")) {
                        boolean isArray = iterType.startsWith("[");
                        boolean isIterable = TypeChecker.isAssignable("Ljava/lang/Iterable;", iterType, session);
                        if (!isArray && !isIterable) {
                            reportError(node.getIterableExpr(), "Loop expression must be an array or java.lang.Iterable: found '" + TypeChecker.humanReadable(iterType) + "'");
                        } else if (isArray) {
                            String elemType = iterType.substring(1);
                            String targetType = node.getTypeDescriptor();
                            if (targetType != null && !targetType.equals("variable") && !targetType.equals("value") && !targetType.equals("var") && !TypeChecker.isObjectType(targetType)) {
                                if (!TypeChecker.isAssignable(targetType, elemType, session)) {
                                    reportError(node, "Incompatible types in for-each loop: cannot convert from '" + TypeChecker.humanReadable(elemType) + "' to '" + TypeChecker.humanReadable(targetType) + "'.");
                                }
                            }
                        } else if (iterType.contains("<") && iterType.contains(">")) {
                            int lt = iterType.indexOf('<');
                            int gt = iterType.lastIndexOf('>');
                            String inner = iterType.substring(lt + 1, gt).trim();
                            if (inner.contains(",")) {
                                inner = inner.substring(0, inner.indexOf(',')).trim();
                            }
                            String elemType = inner;
                            String targetType = node.getTypeDescriptor();
                            if (targetType != null && !targetType.equals("variable") && !targetType.equals("value") && !targetType.equals("var") && !TypeChecker.isObjectType(targetType)) {
                                if (!TypeChecker.isAssignable(targetType, elemType, session)) {
                                    reportError(node, "Incompatible types in for-each loop: cannot convert from '" + TypeChecker.humanReadable(elemType) + "' to '" + TypeChecker.humanReadable(targetType) + "'.");
                                }
                            }
                        }
                    }
                }
                if (node.getBody() != null) node.getBody().accept(this);
            } finally {
                controlTargetStack.pop();
                loopDepth--;
            }
        } finally {
            symbolTable.exitScope();
            symbolTable.restoreInitializationSnapshot(initBefore);
        }
    }

    // R2-1: Stop & Skip Statement Placement Validation
    @Override
    public void visitStop(IRStopStatement node) {
        if (finallyDepth > 0) {
            reportError(node, "'stop' (break) statement cannot be used inside finally block (abrupt completion).");
        }
        if (node.getTargetLabel() != null) {
            LabelInfo info = findActiveLabel(node.getTargetLabel());
            if (info == null) {
                reportError(node, "Undefined label: '" + node.getTargetLabel() + "'");
            } else if (info.lambdaDepth < this.lambdaDepth) {
                reportError(node, "'stop' (break) statement cannot jump outside lambda boundary");
            }
        } else {
            if (controlTargetStack.isEmpty()) {
                if (lambdaDepth > 0 && (enclosingLoopDepth > 0 || enclosingSwitchDepth > 0 || enclosingSwitchExpressionDepth > 0)) {
                    reportError(node, "'stop' (break) statement cannot jump outside lambda boundary");
                } else {
                    reportError(node, "'stop' (break) statement can only be used within a loop or switch");
                }
            } else {
                ControlTarget nearest = controlTargetStack.peek();
                if (nearest == ControlTarget.SWITCH_EXPR) {
                    reportError(node, "Cannot 'stop' (break) out of a switch expression; use 'result' to yield a value.");
                } else if (nearest == ControlTarget.ARROW_SWITCH) {
                    reportError(node, "'stop' (break) statement targeting switch is not allowed in arrow ('->') rule.");
                }
            }
        }
    }

    @Override
    public void visitSkip(IRSkipStatement node) {
        if (finallyDepth > 0) {
            reportError(node, "'skip' (continue) statement cannot be used inside finally block (abrupt completion).");
        }
        if (node.getTargetLabel() != null) {
            LabelInfo info = findActiveLabel(node.getTargetLabel());
            if (info == null) {
                reportError(node, "Undefined label: '" + node.getTargetLabel() + "'");
            } else if (!info.isLoop) {
                reportError(node, "'skip' (continue) target must be a loop, '" + node.getTargetLabel() + "' is not a loop.");
            } else if (info.lambdaDepth < this.lambdaDepth) {
                reportError(node, "'skip' (continue) statement cannot jump outside lambda boundary");
            }
        } else {
            if (loopDepth == 0) {
                if (lambdaDepth > 0 && enclosingLoopDepth > 0) {
                    reportError(node, "'skip' (continue) statement cannot jump outside lambda boundary");
                } else {
                    reportError(node, "'skip' (continue) statement can only be used within a loop");
                }
            }
        }
    }

    private String extractVariableName(IRExpression expr) {
        return switch (expr) {
            case IRVariableAccess va -> va.getName();
            case IRAssignment assign -> extractVariableName(assign.getTarget());
            case null, default -> null;
        };
    }

    private void extractNullChecks(IRExpression cond, Map<String, Boolean> thenBranchNonNull, Map<String, Boolean> elseBranchNonNull) {
        switch (cond) {
            case IRUnaryOp unary when unary.getOperator() == IRUnaryOp.Op.NOT -> {
                Map<String, Boolean> subThen = new HashMap<>();
                Map<String, Boolean> subElse = new HashMap<>();
                extractNullChecks(unary.getExpression(), subThen, subElse);
                thenBranchNonNull.putAll(subElse);
                elseBranchNonNull.putAll(subThen);
            }
            case IRInstanceof ioe -> {
                String varName = extractVariableName(ioe.getExpression());
                if (varName != null) {
                    thenBranchNonNull.put(varName, true);
                }
            }
            case IRBinaryOp binOp -> {
                IRBinaryOp.Op op = binOp.getOperator();
                if (op == IRBinaryOp.Op.AND) {
                    Map<String, Boolean> thenA = new HashMap<>();
                    Map<String, Boolean> elseA = new HashMap<>();
                    extractNullChecks(binOp.getLeft(), thenA, elseA);

                    Map<String, Boolean> thenB = new HashMap<>();
                    Map<String, Boolean> elseB = new HashMap<>();
                    extractNullChecks(binOp.getRight(), thenB, elseB);

                    thenBranchNonNull.putAll(thenA);
                    thenBranchNonNull.putAll(thenB);
                    elseBranchNonNull.putAll(elseA);
                } else if (op == IRBinaryOp.Op.OR) {
                    Map<String, Boolean> thenA = new HashMap<>();
                    Map<String, Boolean> elseA = new HashMap<>();
                    extractNullChecks(binOp.getLeft(), thenA, elseA);

                    Map<String, Boolean> thenB = new HashMap<>();
                    Map<String, Boolean> elseB = new HashMap<>();
                    extractNullChecks(binOp.getRight(), thenB, elseB);

                    elseBranchNonNull.putAll(elseA);
                    elseBranchNonNull.putAll(elseB);
                } else if (op == IRBinaryOp.Op.EQ || op == IRBinaryOp.Op.NE) {
                    String leftVar = extractVariableName(binOp.getLeft());
                    String rightVar = extractVariableName(binOp.getRight());
                    boolean leftIsNull = isNullLiteral(binOp.getLeft());
                    boolean rightIsNull = isNullLiteral(binOp.getRight());

                    String varName = (leftVar != null && rightIsNull) ? leftVar : ((rightVar != null && leftIsNull) ? rightVar : null);
                    if (varName != null) {
                        if (op == IRBinaryOp.Op.NE) {
                            thenBranchNonNull.put(varName, true);
                            elseBranchNonNull.put(varName, false);
                        } else {
                            thenBranchNonNull.put(varName, false);
                            elseBranchNonNull.put(varName, true);
                        }
                    }
                }
            }
            case null, default -> {
            }
        }
    }

    private boolean isNullLiteral(IRExpression expr) {
        if (expr == null) return false;
        if (expr instanceof IRLiteral lit) {
            return lit.getValue() == null || "null".equals(lit.getTypeDescriptor());
        }
        return false;
    }

    private boolean isVariableNonNullInScope(String varName) {
        for (Map<String, Boolean> scope : nullabilityScopes) {
            if (scope.containsKey(varName)) {
                return Boolean.TRUE.equals(scope.get(varName));
            }
        }
        return false;
    }

    private Map<String, Boolean> getNullabilitySnapshot() {
        Map<String, Boolean> snapshot = new HashMap<>();
        for (Iterator<Map<String, Boolean>> it = nullabilityScopes.descendingIterator(); it.hasNext(); ) {
            Map<String, Boolean> scope = it.next();
            snapshot.putAll(scope);
        }
        return snapshot;
    }

    private void applyNullabilitySnapshot(Map<String, Boolean> snapshot) {
        if (snapshot == null || nullabilityScopes.isEmpty()) return;
        nullabilityScopes.peek().putAll(snapshot);
    }

    private void mergeNullabilitySnapshots(Map<String, Boolean> thenNull, Map<String, Boolean> elseNull) {
        if (nullabilityScopes.isEmpty() || thenNull == null || elseNull == null) return;
        Map<String, Boolean> current = nullabilityScopes.peek();
        for (Map.Entry<String, Boolean> e : thenNull.entrySet()) {
            String var = e.getKey();
            if (Boolean.TRUE.equals(e.getValue()) && Boolean.TRUE.equals(elseNull.get(var))) {
                current.put(var, true);
            }
        }
    }

    private void extractTypeNarrowing(IRExpression cond, Map<String, String> thenBranchTypes, Map<String, String> elseBranchTypes) {
        switch (cond) {
            case IRUnaryOp unary when unary.getOperator() == IRUnaryOp.Op.NOT -> {
                Map<String, String> subThen = new HashMap<>();
                Map<String, String> subElse = new HashMap<>();
                extractTypeNarrowing(unary.getExpression(), subThen, subElse);
                thenBranchTypes.putAll(subElse);
                elseBranchTypes.putAll(subThen);
            }
            case IRInstanceof ioe -> {
                String varName = extractVariableName(ioe.getExpression());
                if (varName != null) {
                    String targetType = ioe.getTargetType();
                    if (targetType == null && ioe.getPattern() != null) {
                        targetType = ioe.getPattern().getTypeDescriptor();
                    }
                    if (targetType != null) {
                        thenBranchTypes.put(varName, targetType);
                    }
                }
            }
            case IRBinaryOp binOp -> {
                IRBinaryOp.Op op = binOp.getOperator();
                if (op == IRBinaryOp.Op.AND) {
                    Map<String, String> thenA = new HashMap<>();
                    Map<String, String> elseA = new HashMap<>();
                    extractTypeNarrowing(binOp.getLeft(), thenA, elseA);

                    Map<String, String> thenB = new HashMap<>();
                    Map<String, String> elseB = new HashMap<>();
                    extractTypeNarrowing(binOp.getRight(), thenB, elseB);

                    thenBranchTypes.putAll(thenA);
                    thenBranchTypes.putAll(thenB);
                    elseBranchTypes.putAll(elseA);
                } else if (op == IRBinaryOp.Op.OR) {
                    Map<String, String> thenA = new HashMap<>();
                    Map<String, String> elseA = new HashMap<>();
                    extractTypeNarrowing(binOp.getLeft(), thenA, elseA);

                    Map<String, String> thenB = new HashMap<>();
                    Map<String, String> elseB = new HashMap<>();
                    extractTypeNarrowing(binOp.getRight(), thenB, elseB);

                    elseBranchTypes.putAll(elseA);
                    elseBranchTypes.putAll(elseB);
                }
            }
            case null, default -> {
            }
        }
    }

    private String getNarrowedType(String varName) {
        if (varName == null) return null;
        for (Map<String, String> scope : typeNarrowingScopes) {
            if (scope.containsKey(varName)) {
                return scope.get(varName);
            }
        }
        return null;
    }

    // R3-5 & R3-6: Mandatory Initializer for Val and Untyped Variable/Value
    @Override
    public void visitVariableDecl(IRVariableDecl node) {
        String varName = node.getName();
        String typeDesc = node.getTypeDescriptor();
        if (TypeChecker.getArrayDimensions(typeDesc) > 255) {
            reportError(node, "Array dimension limit exceeded (maximum 255 dimensions supported).");
        }
        if (!"_".equals(varName) && symbolTable.isDeclaredInCurrentScope(varName)) {
            reportError(node, "Variable '" + varName + "' is already defined in this scope.");
        } else if (!"_".equals(varName) && currentMethodBaseScopeDepth > 0 && symbolTable.isDeclaredInMethodScope(varName, currentMethodBaseScopeDepth)) {
            reportError(node, "Variable '" + varName + "' is already defined in this method scope.");
        }

        if (node.isFinal()) {
            if (node.getInitialValue() == null) {
                reportError(node, "'value' (constant) declaration must have an initializer: '" + varName + "'");
            }
        }

        if (typeDesc == null || "var".equals(typeDesc) || "value".equals(typeDesc) || "variable".equals(typeDesc)) {
            if (node.getInitialValue() == null) {
                reportError(node, "Variable declaration without explicit type must have an initializer: '" + varName + "'");
            }
        }

        // P1-1: Visit Initializer BEFORE declaring variable in scope so self-reference (int x = x + 1) triggers undefined variable error
        String initType = null;
        if (node.isExplicitType() && typeDesc != null && !"variable".equals(typeDesc) && !"value".equals(typeDesc) && !"var".equals(typeDesc)) {
            validateGenericTypeArguments(typeDesc, node);
        }

        if (node.getInitialValue() != null) {
            node.getInitialValue().accept(this);

            if (node.getInitialValue() instanceof IRLambdaExpression) {
                if (!node.isExplicitType()) {
                    reportError(node, "Lambda expression requires an explicit target type (functional interface).");
                } else if (!TypeChecker.isFunctionalInterface(typeDesc)) {
                    reportError(node, "Target type '" + TypeChecker.humanReadable(typeDesc) + "' is not a functional interface.");
                }
            }

            checkPrimitiveLiteralBounds(typeDesc, node.getInitialValue(), node);
            initType = node.getInitialValue().getTypeDescriptor();
            if (initType != null && "V".equals(TypeChecker.cleanDescriptor(initType))) {
                reportError(node, "'void' expression cannot be assigned to a variable.");
            }
            if (node.isExplicitType() && typeDesc != null && initType != null && !"variable".equals(typeDesc) && !"value".equals(typeDesc) && !"var".equals(typeDesc)) {
                boolean isHeteroArr = node.getInitialValue() instanceof IRArrayLiteral arr && !arr.isHomogeneous();
                boolean compatible;
                if (isHeteroArr) {
                    // Heterojen literal: generic-aware karşılaştırma (erasure yapmaz)
                    compatible = TypeChecker.isAssignableGenericAware(typeDesc, initType, false, session);
                } else if (isPrimitiveConstantLiteralFor(typeDesc, node.getInitialValue())) {
                    compatible = true;
                } else {
                    compatible = TypeChecker.isAssignable(typeDesc, initType, session);
                }
                if (!compatible) {
                    reportError(node, "Incompatible types: '" + TypeChecker.humanReadable(initType) + "' cannot be converted to '" + TypeChecker.humanReadable(typeDesc) + "' (explicit cast '(" + TypeChecker.humanReadable(typeDesc) + ")' required)");
                }
            }
        }

        boolean isFinalVar = node.isFinal() || "value".equals(typeDesc);
        boolean isExplicit = node.isExplicitType() && typeDesc != null && !"variable".equals(typeDesc) && !"value".equals(typeDesc) && !"var".equals(typeDesc);
        boolean isInitNull = node.getInitialValue() instanceof IRLiteral lit && lit.getValue() == null;
        String finalTypeDesc;
        if (isExplicit) {
            finalTypeDesc = typeDesc;
        } else {
            if (isInitNull) {
                finalTypeDesc = OceanTypeSystem.OBJECT_DESC + "?";
            } else if (initType != null && !"null".equals(initType)) {
                if ("V".equals(TypeChecker.cleanDescriptor(initType))) {
                    finalTypeDesc = OceanTypeSystem.OBJECT_DESC;
                } else {
                    finalTypeDesc = initType;
                }
            } else {
                finalTypeDesc = OceanTypeSystem.OBJECT_DESC + "?";
            }
        }
        symbolTable.declareVariable(varName, finalTypeDesc, isFinalVar, node.getInitialValue() != null);
    }

    private boolean isAssignableExpression(IRExpression expression) {
        return expression instanceof IRVariableAccess
                || expression instanceof IRArrayAccess;

    }

    // R3-7: 'this' Reassignment Prevention & Self Assignment Check
    @Override
    public void visitAssignment(IRAssignment node) {
        if (node.getTarget() != null) {
            boolean oldTarget = inAssignmentTarget;
            inAssignmentTarget = true;
            try {
                node.getTarget().accept(this);
            } finally {
                inAssignmentTarget = oldTarget;
            }
        }
        if (node.getValue() != null) {
            node.getValue().accept(this);
        }

        if (!isAssignableExpression(node.getTarget())) {
            reportError(node,"Invalid assignment target. Left-hand side must be a variable, field, or array element.");
        }

        if (node.getTarget() instanceof IRVariableAccess varAccess) {
            String name = varAccess.getName();

            if ("this".equals(name)) {
                reportError(node, "Cannot assign a value to 'this'.");
            }
            else if ("super".equals(name)) {
                reportError(node, "Cannot assign a value to 'super'.");
            }
            if (varAccess.getReceiver() != null && "length".equals(name)) {
                String recType = varAccess.getReceiver().getTypeDescriptor();
                if (recType != null && (recType.startsWith("[") || TypeChecker.isArrayType(recType))) {
                    reportError(node, "Cannot assign a value to final field 'length' of array.");
                    return;
                }
            }
            boolean isExplicitReceiver = varAccess.getReceiver() != null;
            boolean isField = varAccess.isField() || isExplicitReceiver;
            String ownerFqcn = null;
            if (isExplicitReceiver) {
                ownerFqcn = varAccess.getReceiver().getTypeDescriptor();
                if (ownerFqcn == null && varAccess.getReceiver() instanceof IRVariableAccess rva) {
                    if ("this".equals(rva.getName())) ownerFqcn = currentClassFqcn;
                    else if ("super".equals(rva.getName())) ownerFqcn = currentSuperName;
                    else ownerFqcn = symbolTable.getType(rva.getName());
                }
            } else if (varAccess.getOwner() != null) {
                ownerFqcn = varAccess.getOwner();
                isField = true;
            } else if (varAccess.isField() || (symbolTable.getDeclarationScopeDepth(name) <= 0 && currentClassFqcn != null)) {
                if (symbolTable.getDeclarationScopeDepth(name) <= 0 && findFieldType(currentClassFqcn, name) != null) {
                    ownerFqcn = currentClassFqcn;
                    isField = true;
                }
            }

            if (isField) {
                String resolvedOwner = checkFieldAmbiguityAndResolveOwner(ownerFqcn != null ? ownerFqcn : currentClassFqcn, name, node);
                if (resolvedOwner != null) {
                    ownerFqcn = resolvedOwner;
                }
            }

            if (isField && isFieldFinal(ownerFqcn, name)) {
                String cleanOwner = TypeChecker.cleanDescriptor(ownerFqcn);
                if (isInterfaceType(cleanOwner)) {
                    reportError(node, "Cannot assign a value to interface field '" + name + "' (implicitly public static final).");
                    return;
                }
                boolean isThisCtor = ("<init>".equals(currentMethodName) || insideInstanceInitializer) && lambdaDepth == 0 && currentClassFqcn != null && currentClassFqcn.equals(cleanOwner)
                        && (!isExplicitReceiver || (varAccess.getReceiver() instanceof IRVariableAccess rva && "this".equals(rva.getName())));
                boolean isStaticField = isFieldStatic(ownerFqcn, name);

                if (isStaticField) {
                    boolean isClinit = ("<clinit>".equals(currentMethodName) || insideStaticInitializer) && lambdaDepth == 0 && currentClassFqcn != null && currentClassFqcn.equals(cleanOwner);
                    if (!isClinit) {
                        reportError(node, "Cannot assign a value to static final field '" + name + "' outside static initializer.");
                        return;
                    } else if (!currentClassBlankStaticFinalFields.contains(name)) {
                        reportError(node, "Cannot assign a value to static final field '" + name + "' (already initialized)");
                        return;
                    } else if (loopDepth > 0) {
                        reportError(node, "Cannot assign static final field '" + name + "' inside a loop.");
                        return;
                    } else if (potentiallyAssignedBlankStaticFinalFields.contains(name)) {
                        reportError(node, "Cannot assign a value to static final field '" + name + "' (already initialized)");
                        return;
                    } else {
                        definitelyAssignedBlankStaticFinalFields.add(name);
                        potentiallyAssignedBlankStaticFinalFields.add(name);
                    }
                } else if (isThisCtor) {
                    if (name.startsWith("this$") || name.startsWith("val$")) {
                        // Synthetic outer instance or captured local field assignment in constructor
                    } else if (!currentClassBlankFinalFields.contains(name)) {
                        reportError(node, "Cannot assign a value to final field '" + name + "' (already initialized)");
                        return;
                    } else if (loopDepth > 0) {
                        reportError(node, "Cannot assign final field '" + name + "' inside a loop.");
                        return;
                    } else if (potentiallyAssignedBlankFinalFields.contains(name)) {
                        reportError(node, "Cannot assign a value to final field '" + name + "' (already initialized)");
                        return;
                    } else {
                        definitelyAssignedBlankFinalFields.add(name);
                        potentiallyAssignedBlankFinalFields.add(name);
                    }
                } else {
                    reportError(node, "Cannot assign a value to final field '" + name + "'");
                    return;
                }
            } else if (!isField && symbolTable.isFinal(name)) {
                reportError(node, "Cannot assign a value to final variable '" + name + "'");
                return;
            } else if (!isField && usedAsResourceInMethod.contains(name)) {
                reportError(node, "Resource '" + name + "' in try-with-resources statement must be final or effectively final and cannot be reassigned.");
                return;
            } else {
                int declDepth = symbolTable.getDeclarationScopeDepth(name);
                boolean isCapturedOuterVar = !lambdaScopeDepths.isEmpty() && declDepth != -1 && declDepth < lambdaScopeDepths.peek();
                if (isCapturedOuterVar && !currentMethodIsAsync && !varAccess.isField()) {
                    reportError(node, "Local variable '" + name + "' accessed from within inner class or lambda must be final or effectively final.");
                }
                if (!varAccess.isField() && varAccess.getReceiver() == null) {
                    if (symbolTable.isCaptured(name)) {
                        reportError(node, "Local variable '" + name + "' accessed from within inner class or lambda must be final or effectively final.");
                    }
                    if (symbolTable.isInitialized(name)) {
                        symbolTable.markMutated(name);
                    } else {
                        symbolTable.markInitialized(name);
                    }
                }
            }

            if (node.getValue() instanceof IRVariableAccess rhsAccess && name != null && name.equals(rhsAccess.getName())) {
                boolean lhsIsField = varAccess.isField() || varAccess.getReceiver() != null;
                boolean rhsIsField = rhsAccess.isField() || rhsAccess.getReceiver() != null;
                if (lhsIsField == rhsIsField) {
                    reportWarning(node, "Self-assignment warning: variable '" + name + "' is assigned to itself.");
                }
            }
        }

        if (node.getTarget() != null && node.getValue() != null) {
            String targetType = node.getTarget().getTypeDescriptor();
            if (node.getTarget() instanceof IRVariableAccess va && !va.isField()) {
                String origType = symbolTable.getOriginalType(va.getName());
                if (origType != null) {
                    targetType = origType;
                }
            }
            String sourceType = node.getValue().getTypeDescriptor();
            if (node.getValue() instanceof IRLambdaExpression) {
                if (targetType == null || "variable".equals(targetType) || "var".equals(targetType) || "value".equals(targetType) || TypeChecker.isObjectType(targetType)) {
                    reportError(node, "Lambda expression requires an explicit target type (functional interface).");
                } else if (!TypeChecker.isFunctionalInterface(targetType)) {
                    reportError(node, "Target type '" + TypeChecker.humanReadable(targetType) + "' is not a functional interface.");
                }
            }
            checkPrimitiveLiteralBounds(targetType, node.getValue(), node);
            if (sourceType != null && "V".equals(TypeChecker.cleanDescriptor(sourceType))) {
                reportError(node, "'void' expression cannot be assigned to a variable.");
            }
            if (targetType != null && sourceType != null && !TypeChecker.isObjectType(targetType) && !"variable".equals(targetType)) {
                boolean compatible;
                if (node.isCompound() && TypeChecker.isNumeric(targetType) && TypeChecker.isNumeric(sourceType)) {
                    compatible = !isCompoundNarrowing(targetType, sourceType);
                } else if (node.isCompound() && TypeChecker.isStringType(targetType)) {
                    compatible = true;
                } else if (node.getValue() instanceof IRArrayLiteral arr && !arr.isHomogeneous()) {
                    // Heterojen literal: generic-aware karşılaştırma (erasure yapmaz)
                    compatible = TypeChecker.isAssignableGenericAware(targetType, sourceType,false, session);
                } else if (isPrimitiveConstantLiteralFor(targetType, node.getValue())) {
                    compatible = true;
                } else {
                    compatible = TypeChecker.isAssignable(targetType, sourceType, session);
                }
                if (!compatible) {
                    if (node.isCompound() && isCompoundNarrowing(targetType, sourceType)) {
                        reportError(node, "Narrowing compound assignment detected: '" + TypeChecker.humanReadable(sourceType) + "' cannot be assigned to '" + TypeChecker.humanReadable(targetType) + "' without explicit cast '(" + TypeChecker.humanReadable(targetType) + ")'.");
                    } else {
                        reportError(node, "Incompatible types: '" + TypeChecker.humanReadable(sourceType) + "' cannot be converted to '" + TypeChecker.humanReadable(targetType) + "' (explicit cast '(" + TypeChecker.humanReadable(targetType) + ")' required)");
                    }
                }
            }
            if (node.getValue() instanceof IRBinaryOp binOp) {
                IRBinaryOp.Op op = binOp.getOperator();
                String rhsType = binOp.getRight() != null ? binOp.getRight().getTypeDescriptor() : null;
                String cleanLhs = TypeChecker.cleanDescriptor(targetType != null ? targetType : "");
                String cleanRhs = TypeChecker.cleanDescriptor(rhsType != null ? rhsType : "");

                if (op == IRBinaryOp.Op.BIT_AND || op == IRBinaryOp.Op.BIT_OR || op == IRBinaryOp.Op.BIT_XOR) {
                    boolean lhsInt = TypeChecker.isIntegerType(cleanLhs);
                    boolean rhsInt = TypeChecker.isIntegerType(cleanRhs);
                    boolean lhsBool = TypeChecker.isBoolean(cleanLhs);
                    boolean rhsBool = TypeChecker.isBoolean(cleanRhs);

                    if (!((lhsInt && rhsInt) || (lhsBool && rhsBool))) {
                        reportError(node, "Bitwise assignment operator incompatible types: '" + TypeChecker.humanReadable(cleanLhs) + "' and '" + TypeChecker.humanReadable(cleanRhs) + "'");
                    }
                } else if (op == IRBinaryOp.Op.LSHIFT || op == IRBinaryOp.Op.RSHIFT || op == IRBinaryOp.Op.URSHIFT) {
                    if (!TypeChecker.isIntegerType(cleanLhs)) {
                        reportError(node, "Shift assignment operator left-hand side must be an integer type: '" + TypeChecker.humanReadable(cleanLhs) + "'");
                    }
                    if (rhsType != null && !TypeChecker.isIntegerType(cleanRhs)) {
                        reportError(node, "Shift assignment operator right-hand side must be an integer type: '" + TypeChecker.humanReadable(cleanRhs) + "'");
                    }
                }
            }

            if (node.getTarget() instanceof IRVariableAccess va && !va.isField()) {
                String varName = va.getName();
                String origType = symbolTable.getOriginalType(varName);
                if (origType != null) {
                    if (sourceType == null || "null".equals(sourceType) || TypeChecker.isNullable(sourceType)) {
                        symbolTable.setType(varName, origType);
                        if (!nullabilityScopes.isEmpty()) {
                            nullabilityScopes.peek().remove(varName);
                        }
                    } else {
                        if (!nullabilityScopes.isEmpty()) {
                            nullabilityScopes.peek().put(varName, true);
                        }
                    }
                }
            }
        }
        if (node.getTarget() != null) {
            String tDesc = node.getTarget().getTypeDescriptor();
            if (tDesc != null) {
                node.setTypeDescriptor(tDesc);
            }
        }
    }

    private boolean isCompoundNarrowing(String targetType, String sourceType) {
        String cleanTarget = TypeChecker.cleanDescriptor(targetType != null ? targetType : "");
        String cleanSource = TypeChecker.cleanDescriptor(sourceType != null ? sourceType : "");

        // 1. Kayan noktalıdan tam sayıya daraltma (double/float -> int/long/short/byte/char)
        if (("D".equals(cleanSource) || "F".equals(cleanSource)) && TypeChecker.isIntegerType(cleanTarget)) {
            return true;
        }
        // 2. Double'dan Float'a daraltma
        if ("D".equals(cleanSource) && "F".equals(cleanTarget)) {
            return true;
        }
        // 3. Long'dan daha küçük tam sayılara daraltma (long -> int/short/byte/char)
        return "J".equals(cleanSource) && ("I".equals(cleanTarget) || "S".equals(cleanTarget) || "B".equals(cleanTarget) || "C".equals(cleanTarget));
    }

    private boolean isPrimitiveConstantLiteralFor(String targetType, IRExpression expr) {
        if (targetType == null || expr == null) return false;
        String cleanType = TypeChecker.cleanDescriptor(targetType);
        if (!"B".equals(cleanType) && !"S".equals(cleanType) && !"C".equals(cleanType) && !"F".equals(cleanType)) {
            return false;
        }

        if (expr instanceof IRCastExpression cast) {
            expr = cast.getExpression();
        }

        if (expr instanceof IRUnaryOp unary && unary.getOperator() == IRUnaryOp.Op.NEG) {
            if (unary.getExpression() instanceof IRLiteral lit && lit.getValue() instanceof Number num) {
                double doubleVal = -num.doubleValue();
                long longVal = -num.longValue();
                return isValueWithinBounds(cleanType, longVal, doubleVal);
            }
        }

        if (expr instanceof IRLiteral lit && lit.getValue() instanceof Number num) {
            double doubleVal = num.doubleValue();
            long longVal = num.longValue();
            return isValueWithinBounds(cleanType, longVal, doubleVal);
        }
        return false;
    }

    private boolean isValueWithinBounds(String cleanType, long longVal, double doubleVal) {
        return switch (cleanType) {
            case "B" -> longVal >= -128 && longVal <= 127 && doubleVal == (double) longVal;
            case "S" -> longVal >= -32768 && longVal <= 32767 && doubleVal == (double) longVal;
            case "C" -> longVal >= 0 && longVal <= 65535 && doubleVal == (double) longVal;
            case "F" -> !Double.isNaN(doubleVal) && !Double.isInfinite(doubleVal) && Math.abs(doubleVal) <= Float.MAX_VALUE;
            default -> false;
        };
    }

    private void checkPrimitiveLiteralBounds(String targetType, IRExpression expr, IRNode node) {
        if (targetType == null || expr == null) return;
        String cleanType = TypeChecker.cleanDescriptor(targetType);
        if (!"B".equals(cleanType) && !"S".equals(cleanType) && !"C".equals(cleanType) && !"I".equals(cleanType)) {
            return;
        }

        if (expr instanceof IRCastExpression) {
            return;
        }

        if (expr instanceof IRUnaryOp unary && unary.getOperator() == IRUnaryOp.Op.NEG) {
            if (unary.getExpression() instanceof IRLiteral lit && lit.getValue() instanceof Number num) {
                double doubleVal = -num.doubleValue();
                long longVal = -num.longValue();
                validatePrimitiveBounds(cleanType, "-" + lit.getValue(), longVal, doubleVal, node);
                return;
            }
        }

        if (expr instanceof IRLiteral lit && lit.getValue() instanceof Number num) {
            double doubleVal = num.doubleValue();
            long longVal = num.longValue();
            validatePrimitiveBounds(cleanType, lit.getValue(), longVal, doubleVal, node);
        }
    }

    private void validatePrimitiveBounds(String cleanType, Object rawValue, long longVal, double doubleVal, IRNode node) {
        switch (cleanType) {
            case "B" -> {
                if (longVal < -128 || longVal > 127 || doubleVal != (double) longVal) {
                    reportError(node, "Constant value (" + rawValue + ") is out of range for type 'byte' [-128, 127]");
                }
            }
            case "S" -> {
                if (longVal < -32768 || longVal > 32767 || doubleVal != (double) longVal) {
                    reportError(node, "Constant value (" + rawValue + ") is out of range for type 'short' [-32768, 32767]");
                }
            }
            case "C" -> {
                if (longVal < 0 || longVal > 65535 || doubleVal != (double) longVal) {
                    reportError(node, "Constant value (" + rawValue + ") is out of range for type 'char' [0, 65535]");
                }
            }
            case "I" -> {
                if (longVal < Integer.MIN_VALUE || longVal > Integer.MAX_VALUE || doubleVal != (double) longVal) {
                    reportError(node, "Constant value (" + rawValue + ") is out of range for type 'int' [-2147483648, 2147483647]");
                }
            }
        }
    }

    @Override
    public void visitVariableAccess(IRVariableAccess node) {
        String name = node.getName();
        if ("_".equals(name)) {
            reportError(node, "The unnamed variable '_' cannot be read or referenced.");
            return;
        }

        if (isPatternVarInaccessible(name)) {
            reportError(node, "Pattern variable '" + name + "' is not in scope in this context");
            return;
        }

        if (!inAssignmentTarget && node.getReceiver() == null && !node.isField()) {
            if (currentMethodBaseScopeDepth > 0 && symbolTable.isDeclaredInMethodScope(name, currentMethodBaseScopeDepth)) {
                if (!symbolTable.isParameter(name) && !symbolTable.isInitialized(name)) {
                    reportError(node, "Variable '" + name + "' might not have been initialized");
                }
            }
        }

        if (!inAssignmentTarget) {
            String nodeOwner = node.getOwner();
            String cleanNodeOwner = nodeOwner != null ? TypeChecker.cleanDescriptor(nodeOwner) : null;
            if (cleanNodeOwner != null && cleanNodeOwner.startsWith("L") && cleanNodeOwner.endsWith(";")) {
                cleanNodeOwner = cleanNodeOwner.substring(1, cleanNodeOwner.length() - 1);
            }
            boolean isCurrentClassOwner = cleanNodeOwner == null || currentClassFqcn == null
                    || currentClassFqcn.equals(cleanNodeOwner)
                    || cleanNodeOwner.endsWith("/" + currentClassSimpleName)
                    || cleanNodeOwner.equals(currentClassSimpleName);

            //
            if (("<init>".equals(currentMethodName) || insideInstanceInitializer) && lambdaDepth == 0 && currentClassBlankFinalFields.contains(name)) {
                boolean isMethodVar = currentMethodBaseScopeDepth > 0 && symbolTable.isDeclaredInMethodScope(name, currentMethodBaseScopeDepth);
                boolean isThisAccess = (node.getReceiver() == null && !isMethodVar)
                        || (node.getReceiver() instanceof IRVariableAccess rva && "this".equals(rva.getName()));
                if (isThisAccess && isCurrentClassOwner && !definitelyAssignedBlankFinalFields.contains(name)) {
                    reportError(node, "Final field '" + name + "' cannot be read before initialization (variable might not have been initialized)");
                }
            }

            //
            if (("<clinit>".equals(currentMethodName) || insideStaticInitializer) && lambdaDepth == 0 && currentClassBlankStaticFinalFields.contains(name)) {
                boolean isMethodVar = currentMethodBaseScopeDepth > 0 && symbolTable.isDeclaredInMethodScope(name, currentMethodBaseScopeDepth);
                boolean isCurrentClassAccess = (node.getReceiver() == null && !isMethodVar)
                        || (node.getReceiver() instanceof IRVariableAccess rva && (currentClassSimpleName.equals(rva.getName()) || (currentClassFqcn != null && (currentClassFqcn.equals(rva.getName()) || currentClassFqcn.endsWith("/" + rva.getName())))));
                if (isCurrentClassAccess && isCurrentClassOwner && !definitelyAssignedBlankStaticFinalFields.contains(name)) {
                    reportError(node, "Static final field '" + name + "' cannot be read before initialization (variable might not have been initialized)");
                }
            }
        }

        if (lambdaDepth == 0 && currentFieldBeingInitialized != null && node.getReceiver() == null && !inAssignmentTarget) {
            if (name.equals(currentFieldBeingInitialized)) {
                reportError(node, "Illegal forward reference: field '" + name + "' cannot be referenced in its own initializer.");
            } else if (currentClassForwardFields != null && currentClassForwardFields.contains(name)) {
                reportError(node, "Illegal forward reference: field '" + name + "' has not been declared yet.");
            }
        }

        boolean isClassField = node.getOwner() != null && isOwnerAccessibleViaThis(node.getOwner()) && node.getReceiver() == null;
        if (node.isField() && !node.isStatic() && node.getReceiver() == null) {
            if (node.isExplicitClassTarget()) {
                reportError(node, "Non-static field '" + name + "' cannot be referenced from a static context using class name '" + OceanTypeSystem.findSimpleName(node.getOwner()) + "'.");
            } else if (isStaticContext && !currentMethodIsAsync) {
                reportError(node, "Non-static field cannot be referenced from a static context");
            } else if (currentClassFqcn != null && !isOwnerAccessibleViaThis(node.getOwner())) {
                reportError(node, "Non-static field '" + name + "' cannot be accessed without an enclosing instance.");
            }
        } else if (!node.isStatic() && isStaticContext && isClassField && !currentMethodIsAsync) {
            reportError(node, "Non-static field cannot be referenced from a static context");
        }

        if ("length".equals(name)) {
            String recvType = null;
            if (node.getReceiver() != null) {
                node.getReceiver().accept(this);
                recvType = node.getReceiver().getTypeDescriptor();
                if (recvType == null && node.getReceiver() instanceof IRVariableAccess rva) {
                    recvType = symbolTable.getType(rva.getName());
                }
            }
            if (recvType != null) {
                if (recvType.startsWith("[") || recvType.endsWith("[]") || TypeChecker.isStringType(recvType)) {
                    node.setTypeDescriptor("I");
                    return;
                }
                String cleanRecv = TypeChecker.cleanDescriptor(recvType);
                if ("ocean/stdlib/OceanList".equals(cleanRecv) || "java/util/List".equals(cleanRecv)
                        || "ocean/stdlib/OceanMap".equals(cleanRecv) || "java/util/Map".equals(cleanRecv)
                        || "ocean/stdlib/OceanSet".equals(cleanRecv) || "java/util/Set".equals(cleanRecv)) {
                    node.setTypeDescriptor("I");
                    return;
                }
            }
        }

        if (inEarlyConstructionContext) {
            if ("this".equals(name) || "super".equals(name)) {
                reportError(node, "Cannot reference 'this' or 'super' before supertype constructor has been called.");
                return;
            }
            boolean isThisOrSuperField = (isClassField && node.getReceiver() == null) ||
                    (node.getReceiver() instanceof IRVariableAccess rva && ("this".equals(rva.getName()) || "super".equals(rva.getName())));
            if (!node.isStatic() && isThisOrSuperField) {
                reportError(node, "Cannot reference instance field '" + name + "' before supertype constructor has been called.");
                return;
            }
        }

        if ("this".equals(name)) {
            if (isStaticContext && symbolTable.getType("this") == null) {
                reportError(node, "Cannot use 'this' in a static context");
            }
            if (node.getTypeDescriptor() == null) {
                String thisType = symbolTable.getType("this");
                if (thisType != null) {
                    node.setTypeDescriptor(thisType);
                } else if (currentClassFqcn != null) {
                    node.setTypeDescriptor(OceanTypeSystem.wrapObjectType(currentClassFqcn));
                }
            }
            return;
        }

        if ("super".equals(name)) {
            if (isStaticContext && symbolTable.getType("this") == null) {
                reportError(node, "Cannot use 'super' in a static context");
            }
            if (node.getTypeDescriptor() == null && currentSuperName != null) {
                node.setTypeDescriptor(OceanTypeSystem.wrapObjectType(currentSuperName));
            }
            return;
        }

        if ("class".equals(name) && node.isField()) {
            node.setTypeDescriptor("Ljava/lang/Class;");
            node.setOriginalTypeDescriptor("Ljava/lang/Class;");
            return;
        }

        String type = symbolTable.getType(name);
        String owner = node.getOwner();

        if (node.getReceiver() != null) {
            node.getReceiver().accept(this);
            String recvType = node.getReceiver().getTypeDescriptor();
            if (recvType == null && node.getReceiver() instanceof IRVariableAccess rva) {
                recvType = symbolTable.getType(rva.getName());
            }
            if (TypeChecker.isClassType(recvType)) {
                owner = recvType.substring(1, recvType.length() - 1);
                if (owner.contains("<")) {
                    owner = owner.substring(0, owner.indexOf('<'));
                }
            }
        }

        String targetOwner = owner != null ? owner : currentClassFqcn;
        if (type == null && targetOwner != null) {
            type = findFieldType(targetOwner, name);
            if (type != null) {
                String resolvedOwner = checkFieldAmbiguityAndResolveOwner(targetOwner, name, node);
                if (resolvedOwner != null) {
                    node.setOwner(resolvedOwner);
                    if (isInterfaceType(resolvedOwner)) {
                        node.setStatic(true);
                        node.setField(true);
                    }
                }
            }
        }

        if (owner != null && !owner.equals(currentClassFqcn)) {
            checkAccess(owner, name, false, node);
        }

        if (type == null) {
            type = resolveStaticImportField(name, node);
        }

        if (type == null && !activeEnumSwitchTypes.isEmpty()) {
            String activeEnum = activeEnumSwitchTypes.peek();
            if (activeEnum != null && !activeEnum.isEmpty() && ClassMetadataCache.getEnumConstants(activeEnum).contains(name)) {
                type = "L" + activeEnum + ";";
            }
        }

        if (type != null) {
            String narrowed = getNarrowedType(name);
            if (narrowed != null) {
                type = narrowed;
            } else if (type.endsWith("?") && isVariableNonNullInScope(name)) {
                type = type.substring(0, type.length() - 1);
            }
            if (node.getTypeDescriptor() == null) {
                node.setTypeDescriptor(type);
            }
        }

        if (type == null) {
            if (node.getReceiver() != null || node.isField()) {
                reportError(node, "Field '" + name + "' not found in class '" + (targetOwner != null ? targetOwner.replace('/', '.') : "Unknown") + "'");
            } else if (node.isClassReference()) {
                if (!importedClasses.containsKey(name) && !isKnownClassOrType(name)) {
                    reportError(node, "Static field '" + name + "' not found in class '" + (targetOwner != null ? targetOwner.replace('/', '.') : "Unknown") + "'");
                }
            } else if (!importedClasses.containsKey(name) && !isKnownClassOrType(name)) {
                reportError(node, "Undefined variable: '" + name + "'");
            }
        }
    }

    private boolean isKnownClassOrType(String name) {
        if (name == null || name.isEmpty()) return false;
        if (importedClasses.containsKey(name)) return true;
        String internal = name.replace('.', '/');
        if (CompilerRegistry.globalClassAccess.containsKey(internal)
                || CompilerRegistry.globalFieldRegistry.containsKey(internal)
                || CompilerRegistry.globalMethodRegistry.containsKey(internal)
                || CompilerRegistry.globalEnumConstants.containsKey(internal)) {
            return true;
        }
        try {
            Class<?> c = OceanTypeSystem.forName(name.replace('/', '.'));
            if (c != null) return true;
        } catch (Throwable ignored) {}
        try {
            Class<?> c = OceanTypeSystem.forName("java.lang." + name);
            if (c != null) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    public void visitUnaryOp(IRUnaryOp node) {
        if (node.getExpression() != null) {
            node.getExpression().accept(this);
            IRUnaryOp.Op op = node.getOperator();
            if (op == IRUnaryOp.Op.PRE_INC || op == IRUnaryOp.Op.POST_INC || op == IRUnaryOp.Op.PRE_DEC || op == IRUnaryOp.Op.POST_DEC) {
                if (!isAssignableExpression(node.getExpression())) {
                    reportError(node, "Increment (++) and decrement (--) operations can only be applied to assignable variables (local variable, field, or array element).");
                    return;
                }
                String type = node.getExpression().getTypeDescriptor();
                if (type != null && !TypeChecker.isNumeric(type)) {
                    reportError(node, "Increment (++) and decrement (--) operations are only valid for numeric types: '" + TypeChecker.humanReadable(type) + "'");
                }
                if (node.getExpression() instanceof IRVariableAccess varAccess) {
                    String name = varAccess.getName();
                    if ("this".equals(name)) {
                        reportError(node, "Cannot increment or decrement 'this'");
                        return;
                    } else if ("super".equals(name)) {
                        reportError(node, "Cannot increment or decrement 'super'");
                        return;
                    }
                    if (varAccess.getReceiver() != null && "length".equals(name)) {
                        String recType = varAccess.getReceiver().getTypeDescriptor();
                        if (recType != null && (recType.startsWith("[") || TypeChecker.isArrayType(recType))) {
                            reportError(node, "Cannot modify final field 'length' of array.");
                            return;
                        }
                    }
                    boolean isExplicitReceiver = varAccess.getReceiver() != null;
                    boolean isField = varAccess.isField() || isExplicitReceiver;
                    String ownerFqcn = null;
                    if (isExplicitReceiver) {
                        ownerFqcn = varAccess.getReceiver().getTypeDescriptor();
                        if (ownerFqcn == null && varAccess.getReceiver() instanceof IRVariableAccess rva) {
                            if ("this".equals(rva.getName())) ownerFqcn = currentClassFqcn;
                            else if ("super".equals(rva.getName())) ownerFqcn = currentSuperName;
                            else ownerFqcn = symbolTable.getType(rva.getName());
                        }
                    } else if (varAccess.getOwner() != null) {
                        ownerFqcn = varAccess.getOwner();
                        isField = true;
                    } else if (varAccess.isField() || (symbolTable.getDeclarationScopeDepth(name) <= 0 && currentClassFqcn != null)) {
                        if (symbolTable.getDeclarationScopeDepth(name) <= 0 && findFieldType(currentClassFqcn, name) != null) {
                            ownerFqcn = currentClassFqcn;
                            isField = true;
                        }
                    }

                    if (isField) {
                        String resolvedOwner = checkFieldAmbiguityAndResolveOwner(ownerFqcn != null ? ownerFqcn : currentClassFqcn, name, node);
                        if (resolvedOwner != null) {
                            ownerFqcn = resolvedOwner;
                        }
                        if (isFieldFinal(ownerFqcn, name)) {
                            reportError(node, "Cannot assign a value to final field '" + name + "'");
                            return;
                        }
                    } else if (symbolTable.isFinal(name)) {
                        reportError(node, "Cannot assign a value to final variable '" + name + "'");
                        return;
                    } else if (usedAsResourceInMethod.contains(name)) {
                        reportError(node, "Resource '" + name + "' in try-with-resources statement must be final or effectively final and cannot be reassigned.");
                        return;
                    }

                    if (!varAccess.isField() && varAccess.getReceiver() == null) {
                        int declDepth = symbolTable.getDeclarationScopeDepth(name);
                        boolean isCapturedOuterVar = !lambdaScopeDepths.isEmpty() && declDepth != -1 && declDepth < lambdaScopeDepths.peek();
                        if (isCapturedOuterVar && !currentMethodIsAsync) {
                            reportError(node, "Local variable '" + name + "' accessed from within inner class or lambda must be final or effectively final.");
                        }
                        if (symbolTable.isCaptured(name)) {
                            reportError(node, "Local variable '" + name + "' accessed from within inner class or lambda must be final or effectively final.");
                        }
                        symbolTable.markMutated(name);
                    }
                }
            }
            if (op == IRUnaryOp.Op.NOT) {
                if (!TypeChecker.isBoolean(node.getExpression().getTypeDescriptor())) {
                    reportError(node,"Logical NOT operator (!) can only be applied to boolean expressions. Found: " + TypeChecker.humanReadable(node.getExpression().getTypeDescriptor()));
                }
                node.setTypeDescriptor("Z");
            } else if (op == IRUnaryOp.Op.NEG) {
                String type = node.getExpression().getTypeDescriptor();
                if (type != null && !isArithmeticOperand(type)) {
                    reportError(node, "Unary minus (-) operator is only valid for numeric types: '" + TypeChecker.humanReadable(type) + "'");
                } else if (type != null) {
                    node.setTypeDescriptor(TypeChecker.getUnaryNumericPromotedType(type));
                }
            } else if (op == IRUnaryOp.Op.BIT_NOT) {
                String type = node.getExpression().getTypeDescriptor();
                if (type != null && !isIntegerOperand(type)) {
                    reportError(node, "Bitwise NOT (~) operator is only valid for integer types: '" + TypeChecker.humanReadable(type) + "'");
                } else if (type != null) {
                    node.setTypeDescriptor(TypeChecker.getUnaryNumericPromotedType(type));
                }
            }
        }
    }

    // P2-1: Ternary Condition Boolean Type Check
    @Override
    public void visitTernary(IRTernaryExpression node) {
        if (node.getCondition() != null) {
            node.getCondition().accept(this);
            String condType = node.getCondition().getTypeDescriptor();
            if (condType != null && !TypeChecker.isBoolean(condType)) {
                reportError(node, "Ternary conditional expression must be of type boolean: '" + TypeChecker.humanReadable(condType) + "'");
            }
        }

        // accept() must come first so sub-expressions resolve their typeDescriptors
        Map<String, Boolean> thenNonNull = new HashMap<>();
        Map<String, Boolean> elseNonNull = new HashMap<>();
        extractNullChecks(node.getCondition(), thenNonNull, elseNonNull);

        nullabilityScopes.push(thenNonNull);
        try {
            if (node.getTrueExpr() != null) node.getTrueExpr().accept(this);
        } finally {
            nullabilityScopes.pop();
        }

        nullabilityScopes.push(elseNonNull);
        try {
            if (node.getFalseExpr() != null) node.getFalseExpr().accept(this);
        } finally {
            nullabilityScopes.pop();
        }

        // --- Branch type compatibility check ---
        // Types are read AFTER accept() so they are fully resolved.
        String rawTrue  = node.getTrueExpr()  != null ? node.getTrueExpr().getTypeDescriptor()  : null;
        String rawFalse = node.getFalseExpr() != null ? node.getFalseExpr().getTypeDescriptor() : null;
        String cleanTrue  = TypeChecker.cleanDescriptor(rawTrue  != null ? rawTrue  : "");
        String cleanFalse = TypeChecker.cleanDescriptor(rawFalse != null ? rawFalse : "");

        boolean isTrueVoid  = node.getTrueExpr()  != null && "V".equals(cleanTrue);
        boolean isFalseVoid = node.getFalseExpr() != null && "V".equals(cleanFalse);
        if (isTrueVoid || isFalseVoid) {
            reportError(node, "Ternary branches cannot return void.");
            return;
        }

        // null literal is assignable to any reference type — check for primitive incompatibility
        boolean trueIsNull  = cleanTrue.isEmpty()  || "null".equals(cleanTrue);
        boolean falseIsNull = cleanFalse.isEmpty() || "null".equals(cleanFalse);

        boolean truePrim  = TypeChecker.isPrimitive(cleanTrue);
        boolean falsePrim = TypeChecker.isPrimitive(cleanFalse);

        /*if ((trueIsNull && falsePrim) || (falseIsNull && truePrim)) {
            String primSide = truePrim ? cleanTrue : cleanFalse;
            reportError(node, "Ternary expression cannot combine 'null' with primitive type '" + TypeChecker.humanReadable(primSide) + "'.");
            return;
        }
        */

        if (trueIsNull || falseIsNull) {
            String resultType = trueIsNull ? rawFalse : rawTrue;
            if (TypeChecker.isPrimitive(resultType)) resultType = TypeChecker.box(resultType);
            if (resultType != null && !TypeChecker.isNullable(resultType)) resultType = resultType + "?";
            node.setTypeDescriptor(resultType);
            return;
        }

        if (truePrim != falsePrim) {
            // One primitive, one reference type.

            boolean bothNumeric = TypeChecker.isNumeric(cleanTrue) && TypeChecker.isNumeric(cleanFalse);
            boolean bothBoolean = TypeChecker.isBoolean(cleanTrue) && TypeChecker.isBoolean(cleanFalse);
            if (!bothNumeric && !bothBoolean) {
                String primSide = truePrim ? cleanTrue : cleanFalse;
                String refSide  = truePrim ? cleanFalse : cleanTrue;
                String boxed = TypeChecker.box(primSide);
                if (boxed == null || !TypeChecker.isAssignable(refSide, boxed, session)) {
                    reportError(node, "Incompatible branch types in ternary expression: '" + TypeChecker.humanReadable(cleanTrue) + "' and '" + TypeChecker.humanReadable(cleanFalse) + "'.");
                    return;
                }
            }
        } else if (truePrim) {
            // Both primitive — must be mutually assignable (int+long ok, int+boolean not ok)
            boolean compatible = TypeChecker.isAssignable(cleanTrue, cleanFalse, session) || TypeChecker.isAssignable(cleanFalse, cleanTrue, session);
            if (!compatible) {
                reportError(node, "Incompatible branch types in ternary expression: '" + TypeChecker.humanReadable(cleanTrue) + "' and '" + TypeChecker.humanReadable(cleanFalse) + "'.");
                return;
            }
        }

        // Update node type descriptor with promoted/unified type
        String promotedType = TypeChecker.getTernaryPromotedType(rawTrue, rawFalse, session);
        if (node.isNullCoalescing()) {
            if (rawFalse != null && !TypeChecker.isNullable(rawFalse) && !"null".equals(rawFalse)) {
                promotedType = TypeChecker.cleanDescriptor(promotedType);
            }
            if (truePrim && rawTrue != null && !TypeChecker.isNullable(rawTrue)) {
                reportWarning(node, "Left operand of null-coalescing operator ('??') is primitive type '" + TypeChecker.humanReadable(cleanTrue) + "' and can never be null.");
            }
        }
        node.setTypeDescriptor(promotedType);
    }

    @Override
    public void visitLabeled(IRLabeledStatement node) {
        String label = node.getLabel();
        if (findActiveLabel(label) != null) {
            reportError(node, "Label '" + label + "' is already defined in an enclosing scope.");
        }
        boolean isLoop = (node.getStatement() instanceof IRWhileStatement)
                || (node.getStatement() instanceof IRForStatement)
                || (node.getStatement() instanceof IRDoWhileStatement);
        LabelInfo info = new LabelInfo(label, node.getStatement(), isLoop, this.lambdaDepth);
        LabelInfo previous = activeLabels.put(label, info);
        try {
            if (node.getStatement() != null) {
                node.getStatement().accept(this);
            }
        } finally {
            if (previous != null) {
                activeLabels.put(label, previous);
            } else {
                activeLabels.remove(label);
            }
        }
    }

    @Override
    public void visitVerify(IRVerifyStatement node) {
        if (node.getCondition() != null) {
            node.getCondition().accept(this);
            String condType = node.getCondition().getTypeDescriptor();
            if (condType != null && !TypeChecker.isBoolean(condType)) {
                reportError(node, "'verify' condition must be of type boolean: '" + TypeChecker.humanReadable(condType) + "'");
            }
            Map<String, Boolean> thenNonNull = new HashMap<>();
            Map<String, Boolean> elseNonNull = new HashMap<>();
            extractNullChecks(node.getCondition(), thenNonNull, elseNonNull);
            if (!nullabilityScopes.isEmpty()) {
                nullabilityScopes.peek().putAll(thenNonNull);
            }
            Map<String, String> thenNarrowed = new HashMap<>();
            Map<String, String> elseNarrowed = new HashMap<>();
            extractTypeNarrowing(node.getCondition(), thenNarrowed, elseNarrowed);
            if (!typeNarrowingScopes.isEmpty()) {
                typeNarrowingScopes.peek().putAll(thenNarrowed);
            }
        }
        if (node.getDetailMessage() != null) {
            node.getDetailMessage().accept(this);
            String detailType = node.getDetailMessage().getTypeDescriptor();
            if ("V".equals(detailType)) {
                reportError(node, "'verify' detail message cannot be of type void");
            }
        }
    }

    // P2-2 & P2-3: Bitwise, Shift & Relational Operator Constraints & Division By Zero / Identical Expression Warnings
    @Override
    public void visitBinaryOp(IRBinaryOp node) {
        if (node.getOperator() == IRBinaryOp.Op.AND) {
            if (node.getLeft() != null) node.getLeft().accept(this);
            Map<String, Boolean> thenNonNull = new HashMap<>();
            Map<String, Boolean> elseNonNull = new HashMap<>();
            extractNullChecks(node.getLeft(), thenNonNull, elseNonNull);
            nullabilityScopes.push(thenNonNull);
            try {
                if (node.getRight() != null) node.getRight().accept(this);
            } finally {
                nullabilityScopes.pop();
            }
        } else if (node.getOperator() == IRBinaryOp.Op.OR) {
            Map<String, String> leftTrue = new HashMap<>();
            Map<String, String> leftFalse = new HashMap<>();
            extractPatternVariables(node.getLeft(), leftTrue, leftFalse);

            if (node.getLeft() != null) node.getLeft().accept(this);

            Set<String> newlyInaccessible = new HashSet<>(leftTrue.keySet());
            inaccessiblePatternVarScopes.push(newlyInaccessible);
            try {
                Map<String, Boolean> thenNonNull = new HashMap<>();
                Map<String, Boolean> elseNonNull = new HashMap<>();
                extractNullChecks(node.getLeft(), thenNonNull, elseNonNull);
                nullabilityScopes.push(elseNonNull);
                try {
                    if (node.getRight() != null) node.getRight().accept(this);
                } finally {
                    nullabilityScopes.pop();
                }
            } finally {
                if (!inaccessiblePatternVarScopes.isEmpty()) {
                    inaccessiblePatternVarScopes.pop();
                }
            }
        } else {
            if (node.getLeft() != null) node.getLeft().accept(this);
            if (node.getRight() != null) node.getRight().accept(this);
        }

        IRBinaryOp.Op op = node.getOperator();
        String leftType = node.getLeft() != null ? node.getLeft().getTypeDescriptor() : null;
        String rightType = node.getRight() != null ? node.getRight().getTypeDescriptor() : null;

        // Advanced Division By Zero Warning Check
        if (op == IRBinaryOp.Op.DIV || op == IRBinaryOp.Op.MOD) {
            Long constRight = evaluateConstantInt(node.getRight(), node);
            if (constRight != null && constRight == 0L) {
                reportWarning(node, "Division by zero.");
            } else if (node.getRight() instanceof IRLiteral lit) {
                Object val = lit.getValue();
                if (val instanceof Number n && n.doubleValue() == 0.0) {
                    reportWarning(node, "Division by zero.");
                }
            }
        }

        // Advanced Identical Variable Comparison Warning Check
        if (node.getLeft() instanceof IRVariableAccess v1 && node.getRight() instanceof IRVariableAccess v2) {
            if (v1.getName() != null && v1.getName().equals(v2.getName())) {
                boolean sameReceiver;
                if (v1.getReceiver() == null && v2.getReceiver() == null) {
                    sameReceiver = true;
                } else if (v1.getReceiver() != null && v2.getReceiver() != null) {
                    if (v1.getReceiver() instanceof IRVariableAccess r1 && v2.getReceiver() instanceof IRVariableAccess r2) {
                        sameReceiver = Objects.equals(r1.getName(), r2.getName());
                    } else {
                        sameReceiver = false;
                    }
                } else {
                    sameReceiver = false;
                }
                if (sameReceiver) {
                    if (op == IRBinaryOp.Op.EQ || op == IRBinaryOp.Op.NE || op == IRBinaryOp.Op.LT || op == IRBinaryOp.Op.GT || op == IRBinaryOp.Op.LE || op == IRBinaryOp.Op.GE || op == IRBinaryOp.Op.SUB || op == IRBinaryOp.Op.BIT_XOR) {
                        if (!currentClassIsData){
                            reportWarning(node, "Identical variable comparison: '" + v1.getName() + " " + op + " " + v2.getName() + "' yields a constant result.");
                        }
                    }
                }
            }
        }
        if (node.getLeft() instanceof IRExpression v1 && node.getRight() instanceof IRExpression v2) {
            if (TypeChecker.isStringType(v1.getTypeDescriptor()) || TypeChecker.isStringType(v2.getTypeDescriptor())) {
                if (op != IRBinaryOp.Op.EQ && op != IRBinaryOp.Op.NE && op != IRBinaryOp.Op.ADD) {
                    if (v1.getTypeDescriptor() != null && v2.getTypeDescriptor() != null) {
                        if (v1.getTypeDescriptor().equals(v2.getTypeDescriptor())) {
                            reportError(node, "Invalid operator for String. Supported operators are ('==', '!=', '+').");
                        } else {
                            reportError(node, "Operator " + op + " cannot be applied to incompatible types: " + TypeChecker.humanReadable(v1.getTypeDescriptor()) + " and " + TypeChecker.humanReadable(v2.getTypeDescriptor()));
                        }
                    }

                }
            }
        }

        if (op == IRBinaryOp.Op.ADD) {
            boolean leftString = TypeChecker.isStringType(leftType);
            boolean rightString = TypeChecker.isStringType(rightType);
            if (leftString || rightString) {
                if (leftType != null && "V".equals(TypeChecker.cleanDescriptor(leftType))) {
                    reportError(node, "'void' expression cannot be used in string concatenation.");
                }
                if (rightType != null && "V".equals(TypeChecker.cleanDescriptor(rightType))) {
                    reportError(node, "'void' expression cannot be used in string concatenation.");
                }
            } else if (leftType != null && rightType != null) {
                boolean leftNum = isArithmeticOperand(leftType);
                boolean rightNum = isArithmeticOperand(rightType);
                if (!leftNum || !rightNum) {
                    reportError(node, "Binary operator '+' cannot be applied to '" + TypeChecker.humanReadable(leftType) + "', '" + TypeChecker.humanReadable(rightType) + "' (only numeric or String types supported).");
                }
            }
        } else if (op == IRBinaryOp.Op.SUB || op == IRBinaryOp.Op.MUL || op == IRBinaryOp.Op.DIV || op == IRBinaryOp.Op.MOD) {
            String opStr = switch (op) {
                case SUB -> "-";
                case MUL -> "*";
                case DIV -> "/";
                default -> "%";
            };
            if (leftType != null && rightType != null) {
                boolean leftNum = isArithmeticOperand(leftType);
                boolean rightNum = isArithmeticOperand(rightType);
                if (!leftNum || !rightNum) {
                    reportError(node, "Arithmetic operator '" + opStr + "' can only be applied to numeric types: '" + TypeChecker.humanReadable(leftType) + "' and '" + TypeChecker.humanReadable(rightType) + "'");
                }
            }
        }

        if (op == IRBinaryOp.Op.LSHIFT || op == IRBinaryOp.Op.RSHIFT || op == IRBinaryOp.Op.URSHIFT) {
            String opStr = switch (op) {
                case LSHIFT -> "<<";
                case RSHIFT -> ">>";
                default -> ">>>";
            };
            if (leftType != null && !TypeChecker.isIntegerType(leftType)) {
                reportError(node, "Shift operators (" + opStr + ") can only be applied to integer types: '" + TypeChecker.humanReadable(leftType) + "'");
            }
            if (rightType != null && !TypeChecker.isIntegerType(rightType)) {
                reportError(node, "Shift distance must be an integer type: '" + TypeChecker.humanReadable(rightType) + "'");
            }
        } else if (op == IRBinaryOp.Op.BIT_AND || op == IRBinaryOp.Op.BIT_OR || op == IRBinaryOp.Op.BIT_XOR) {
            String opStr = switch (op) {
                case BIT_AND -> "&";
                case BIT_OR -> "|";
                default -> "^";
            };
            boolean leftInt = TypeChecker.isIntegerType(leftType);
            boolean rightInt = TypeChecker.isIntegerType(rightType);
            boolean leftBool = TypeChecker.isBoolean(leftType);
            boolean rightBool = TypeChecker.isBoolean(rightType);

            if (leftType != null && rightType != null) {
                if ((leftInt && rightInt) || (leftBool && rightBool)) {
                    // Valid: both integer or both boolean
                } else if ((leftInt || leftBool) && (rightInt || rightBool)) {
                    reportError(node, "Bitwise operator " + opStr + " cannot be applied to incompatible types: '" + TypeChecker.humanReadable(leftType) + "' and '" + TypeChecker.humanReadable(rightType) + "'. Both operands must be integer or boolean.");
                } else {
                    if (!leftInt && !leftBool) {
                        reportError(node, "Bitwise operator " + opStr + " can only be applied to integer or boolean types: '" + TypeChecker.humanReadable(leftType) + "'");
                    }
                    if (!rightInt && !rightBool) {
                        reportError(node, "Bitwise operator " + opStr + " can only be applied to integer or boolean types: '" + TypeChecker.humanReadable(rightType) + "'");
                    }
                }
            }
        } else if (op == IRBinaryOp.Op.LT || op == IRBinaryOp.Op.LE || op == IRBinaryOp.Op.GT || op == IRBinaryOp.Op.GE) {
            // P2-3: String & Non-Numeric Relational Comparison Restrictions
            String opStr = switch (op) {
                case LT -> "<";
                case LE -> "<=";
                case GT -> ">";
                default -> ">=";
            };
            if (leftType != null && rightType != null) {
                if (!TypeChecker.isComparable(leftType, rightType)) {
                    reportError(node, "Comparison operator " + opStr + " cannot be applied to '" + TypeChecker.humanReadable(leftType) + "', '" + TypeChecker.humanReadable(rightType) + "'");
                }
            }
        } else if (op == IRBinaryOp.Op.EQ || op == IRBinaryOp.Op.NE) {
            String opStr = op == IRBinaryOp.Op.EQ ? "==" : "!=";
            if (leftType != null && rightType != null) {
                String cleanLeft = TypeChecker.cleanDescriptor(leftType);
                String cleanRight = TypeChecker.cleanDescriptor(rightType);

                boolean leftNull = "null".equals(cleanLeft);
                boolean rightNull = "null".equals(cleanRight);

                if (leftNull || rightNull) {
                    String nonNull = leftNull ? cleanRight : cleanLeft;
                    if (TypeChecker.isPrimitive(nonNull)) {
                        reportError(node, "Equality operator " + opStr + " cannot be applied to 'null', '" + TypeChecker.humanReadable(nonNull) + "'");
                    }
                } else {
                    boolean leftBool = TypeChecker.isBoolean(cleanLeft);
                    boolean rightBool = TypeChecker.isBoolean(cleanRight);
                    boolean leftObject = TypeChecker.isObjectType(cleanLeft) || OceanTypeSystem.OBJECT_DESC.equals(cleanLeft);
                    boolean rightObject = TypeChecker.isObjectType(cleanRight) || OceanTypeSystem.OBJECT_DESC.equals(cleanRight);

                    if (leftBool || rightBool) {
                            // Valid boolean equality
                            // Dynamic Object vs Boolean unboxing comparison (e.g. map.get(...) == true)
                        if (!((leftBool && rightObject) || (rightBool && leftObject)) &&  !(leftBool && rightBool)) {
                            reportError(node, "Equality operator " + opStr + " cannot be applied to '" + TypeChecker.humanReadable(leftType) + "', '" + TypeChecker.humanReadable(rightType) + "'");
                        }
                    } else {
                        boolean leftPrim = TypeChecker.isPrimitive(cleanLeft);
                        boolean rightPrim = TypeChecker.isPrimitive(cleanRight);

                        if (leftPrim && rightPrim) {
                            boolean leftNum = TypeChecker.isNumeric(cleanLeft);
                            boolean rightNum = TypeChecker.isNumeric(cleanRight);
                            if (!leftNum || !rightNum) {
                                reportError(node, "Equality operator " + opStr + " cannot be applied to '" + TypeChecker.humanReadable(leftType) + "', '" + TypeChecker.humanReadable(rightType) + "'");
                            }
                        } else if (leftPrim || rightPrim) {
                            String primType = leftPrim ? cleanLeft : cleanRight;
                            String refType = leftPrim ? cleanRight : cleanLeft;
                            String unboxed = TypeChecker.unbox(refType);
                            boolean validUnboxing = false;
                            if (TypeChecker.isNumeric(primType)) {
                                if (TypeChecker.isNumeric(unboxed)) {
                                    validUnboxing = true;
                                } else if (TypeChecker.isObjectType(refType) || OceanTypeSystem.OBJECT_DESC.equals(refType) || OceanTypeSystem.NUMBER_DESC.equals(refType)) {
                                    validUnboxing = true;
                                } else if (TypeChecker.isNumeric(refType) || isReferenceConvertible(refType, OceanTypeSystem.NUMBER_DESC)) {
                                    validUnboxing = true;
                                }
                            }
                            if (!validUnboxing) {
                                reportError(node, "Equality operator " + opStr + " cannot be applied to '" + TypeChecker.humanReadable(leftType) + "', '" + TypeChecker.humanReadable(rightType) + "'");
                            }
                        } else {
                            if (!leftObject && !rightObject) {
                                boolean convertible = isReferenceConvertible(cleanLeft, cleanRight) || isReferenceConvertible(cleanRight, cleanLeft);
                                if (!convertible) {
                                    reportError(node, "Incomparable types: '" + TypeChecker.humanReadable(leftType) + "' and '" + TypeChecker.humanReadable(rightType) + "' cannot be compared with " + opStr);
                                }
                            }
                        }
                    }
                }
            }
        }
        else if (op == IRBinaryOp.Op.AND || op == IRBinaryOp.Op.OR) {
            String opStr = op == IRBinaryOp.Op.AND ? "&&" : "||";
            if (leftType != null && rightType != null) {
                boolean leftObject = TypeChecker.isBoolean(leftType);
                boolean rightObject = TypeChecker.isBoolean(rightType);
                if (!leftObject || !rightObject) {
                    reportError(node, opStr + " can only be applied to boolean expressions. Found: " + TypeChecker.humanReadable(leftType) + " and " + TypeChecker.humanReadable(rightType));
                }
            }
        }

        // Synchronize IRBinaryOp type descriptor with resolved operand types
        if (op == IRBinaryOp.Op.AND || op == IRBinaryOp.Op.OR ||
            op == IRBinaryOp.Op.EQ || op == IRBinaryOp.Op.NE ||
            op == IRBinaryOp.Op.LT || op == IRBinaryOp.Op.LE ||
            op == IRBinaryOp.Op.GT || op == IRBinaryOp.Op.GE) {
            node.setTypeDescriptor("Z");
        } else if (op == IRBinaryOp.Op.BIT_AND || op == IRBinaryOp.Op.BIT_OR || op == IRBinaryOp.Op.BIT_XOR) {
            boolean leftBool = TypeChecker.isBoolean(leftType);
            boolean rightBool = TypeChecker.isBoolean(rightType);
            if (leftBool && rightBool) {
                node.setTypeDescriptor("Z");
            } else if (leftType != null && rightType != null) {
                node.setTypeDescriptor(TypeChecker.getBinaryNumericPromotedType(leftType, rightType));
            }
        } else if (op == IRBinaryOp.Op.LSHIFT || op == IRBinaryOp.Op.RSHIFT || op == IRBinaryOp.Op.URSHIFT) {
            if (leftType != null) {
                node.setTypeDescriptor(TypeChecker.getUnaryNumericPromotedType(leftType));
            }
        } else if (op == IRBinaryOp.Op.ADD) {
            boolean leftString = TypeChecker.isStringType(leftType);
            boolean rightString = TypeChecker.isStringType(rightType);
            if (leftString || rightString) {
                node.setTypeDescriptor(OceanTypeSystem.STRING_DESC);
            } else if (TypeChecker.isBigDecimalType(leftType) || TypeChecker.isBigDecimalType(rightType)) {
                node.setTypeDescriptor(OceanTypeSystem.BIGDECIMAL_DESC);
            } else if (leftType != null && rightType != null) {
                node.setTypeDescriptor(TypeChecker.getBinaryNumericPromotedType(leftType, rightType));
            }
        } else if (op == IRBinaryOp.Op.SUB || op == IRBinaryOp.Op.MUL || op == IRBinaryOp.Op.DIV || op == IRBinaryOp.Op.MOD) {
            if (TypeChecker.isBigDecimalType(leftType) || TypeChecker.isBigDecimalType(rightType)) {
                node.setTypeDescriptor(OceanTypeSystem.BIGDECIMAL_DESC);
            } else if (leftType != null && rightType != null) {
                node.setTypeDescriptor(TypeChecker.getBinaryNumericPromotedType(leftType, rightType));
            }
        }
    }

    private boolean isArithmeticOperand(String type) {
        if (type == null) return false;
        String clean = TypeChecker.cleanDescriptor(type);
        if ("null".equals(clean)) return false;
        if (TypeChecker.isBoolean(type) || TypeChecker.isBoolean(clean)) return false;
        if (TypeChecker.isNumeric(type) || TypeChecker.isNumeric(clean)) return true;
        if (TypeChecker.isObjectType(type) || TypeChecker.isObjectType(clean) || "Object".equals(clean)) return true;
        if ("java/lang/Number".equals(clean) || "Number".equals(clean) || OceanTypeSystem.NUMBER_DESC.equals(type)) return true;
        if ("variable".equals(type) || "value".equals(type)) return true;
        return isReferenceConvertible(clean, "java/lang/Number");
    }

    private boolean isIntegerOperand(String type) {
        if (type == null) return false;
        String clean = TypeChecker.cleanDescriptor(type);
        if ("null".equals(clean)) return false;
        if (TypeChecker.isBoolean(type) || TypeChecker.isBoolean(clean)) return false;
        if (TypeChecker.isIntegerType(type) || TypeChecker.isIntegerType(clean)) return true;
        if (TypeChecker.isObjectType(type) || TypeChecker.isObjectType(clean) || "Object".equals(clean)) return true;
        return "variable".equals(type) || "value".equals(type);
    }

    // Instanceof & Pattern Matching Pattern Var Scope Declaration
    private String resolveTypeName(String name) {
        if (name == null || name.isEmpty()) return name;
        if (importedClasses.containsKey(name)) return importedClasses.get(name);
        if (name.contains("/")) return name;
        if (currentClassFqcn != null) {
            String curr = currentClassFqcn.replace('.', '/');
            while (!curr.isEmpty()) {
                String chain = curr + "$" + name.replace('.', '$');
                if (CompilerRegistry.globalClassAccess.containsKey(chain) ||
                    CompilerRegistry.globalFieldRegistry.containsKey(chain) ||
                    CompilerRegistry.globalMethodRegistry.containsKey(chain) ||
                    CompilerRegistry.globalSuperClassRegistry.containsKey(chain) ||
                    CompilerRegistry.globalIsInterfaceSet.contains(chain)) {
                    return chain;
                }
                int lastDollar = curr.lastIndexOf('$');
                if (lastDollar != -1) {
                    curr = curr.substring(0, lastDollar);
                } else {
                    break;
                }
            }
        }
        String pkg = getFilePackage();
        if (!pkg.isEmpty()) {
            String fullPath = pkg.replace('.', '/') + "/" + name;
            if (CompilerRegistry.globalClassAccess.containsKey(fullPath) || CompilerRegistry.globalFieldRegistry.containsKey(fullPath)) {
                return fullPath;
            }
        }
        return name;
    }

    private String toTypeDescriptor(String type) {
        if (type == null || type.isEmpty()) return OceanTypeSystem.OBJECT_DESC;
        type = type.trim();
        if (type.contains("<")) {
            type = type.substring(0, type.indexOf('<')).trim();
        }
        if (type.startsWith("L") && type.endsWith(";")) return type;
        if (type.startsWith("[")) return type;
        if (type.endsWith("[]")) {
            return "[" + toTypeDescriptor(type.substring(0, type.length() - 2));
        }
        return OceanTypeSystem.wrapObjectType(resolveTypeName(type).replace('.', '/'));
    }

    private void extractPatternVariables(IRExpression cond, Map<String, String> whenTrue, Map<String, String> whenFalse) {
        switch (cond) {
            case null -> {}
            case IRUnaryOp unary when unary.getOperator() == IRUnaryOp.Op.NOT -> {
                Map<String, String> subTrue = new HashMap<>();
                Map<String, String> subFalse = new HashMap<>();
                extractPatternVariables(unary.getExpression(), subTrue, subFalse);
                whenTrue.putAll(subFalse);
                whenFalse.putAll(subTrue);
            }
            case IRInstanceof ioe -> {
                if (ioe.getPatternVarName() != null && ioe.getTargetType() != null) {
                    String targetType = ioe.getTargetType();
                    String desc = TypeChecker.isPrimitive(targetType) ? targetType : OceanTypeSystem.wrapObjectType(resolveTypeName(targetType));
                    whenTrue.put(ioe.getPatternVarName(), desc);
                }
                if (ioe.getPattern() != null) {
                    collectPatternVars(ioe.getPattern(), whenTrue);
                }
            }
            case IRBinaryOp binOp -> {
                if (binOp.getOperator() == IRBinaryOp.Op.AND) {
                    Map<String, String> trueA = new HashMap<>();
                    Map<String, String> falseA = new HashMap<>();
                    extractPatternVariables(binOp.getLeft(), trueA, falseA);

                    Map<String, String> trueB = new HashMap<>();
                    Map<String, String> falseB = new HashMap<>();
                    extractPatternVariables(binOp.getRight(), trueB, falseB);

                    whenTrue.putAll(trueA);
                    whenTrue.putAll(trueB);
                } else if (binOp.getOperator() == IRBinaryOp.Op.OR) {
                    Map<String, String> trueA = new HashMap<>();
                    Map<String, String> falseA = new HashMap<>();
                    extractPatternVariables(binOp.getLeft(), trueA, falseA);

                    Map<String, String> trueB = new HashMap<>();
                    Map<String, String> falseB = new HashMap<>();
                    extractPatternVariables(binOp.getRight(), trueB, falseB);

                    whenFalse.putAll(falseA);
                    whenFalse.putAll(falseB);
                }
            }
            default -> {
            }
        }
    }

    private void collectPatternVars(IRSwitchPattern p, Map<String, String> vars) {
        if (p == null || p.getKind() == IRSwitchPattern.Kind.UNNAMED) return;
        if (p.getVariableName() != null && !p.getVariableName().equals("_")) {
            String desc = p.getTypeDescriptor() != null ? p.getTypeDescriptor() : OceanTypeSystem.OBJECT_DESC;
            vars.put(p.getVariableName(), desc);
        }
        if (p.getNestedPatterns() != null) {
            for (IRSwitchPattern nested : p.getNestedPatterns()) {
                collectPatternVars(nested, vars);
            }
        }
    }

    private void collectAllPatternVarsInExpr(IRExpression expr, Map<String, String> allVars) {
        switch (expr) {
            case IRInstanceof ioe -> {
                if (ioe.getPatternVarName() != null && ioe.getTargetType() != null) {
                    String targetType = ioe.getTargetType();
                    String desc = TypeChecker.isPrimitive(targetType) ? targetType : OceanTypeSystem.wrapObjectType(resolveTypeName(targetType));
                    allVars.put(ioe.getPatternVarName(), desc);
                }
                if (ioe.getPattern() != null) {
                    collectPatternVars(ioe.getPattern(), allVars);
                }
            }
            case IRUnaryOp unary -> collectAllPatternVarsInExpr(unary.getExpression(), allVars);
            case IRBinaryOp bin -> {
                collectAllPatternVarsInExpr(bin.getLeft(), allVars);
                collectAllPatternVarsInExpr(bin.getRight(), allVars);
            }
            case null, default -> {
            }
        }
    }

    private void declarePatternVariables(IRSwitchPattern p) {
        if (p == null || p.getKind() == IRSwitchPattern.Kind.UNNAMED) return;
        if (p.getVariableName() != null && !p.getVariableName().equals("_") && p.getTypeDescriptor() != null) {
            String rawType = p.getTypeDescriptor();
            String desc;
            if (TypeChecker.isPrimitive(rawType)) {
                desc = rawType;
            } else {
                String fqcn = resolveTypeName(rawType);
                desc = OceanTypeSystem.wrapObjectType(fqcn);
            }
            symbolTable.declareVariable(p.getVariableName(), desc, true, true);
        }
        if (p.getKind() == IRSwitchPattern.Kind.RECORD && p.getNestedPatterns() != null) {
            for (IRSwitchPattern nested : p.getNestedPatterns()) {
                declarePatternVariables(nested);
            }
        }
    }

    private boolean isClassFinal(String className) {
        if (className == null) return false;
        String clean = TypeChecker.cleanDescriptor(className);
        if (clean.startsWith("L") && clean.endsWith(";")) clean = clean.substring(1, clean.length() - 1);
        clean = clean.replace('.', '/');
        if (clean.contains("<")) {
            clean = clean.substring(0, clean.indexOf('<'));
        }
        if (!clean.contains("/")) {
            String resolved = resolveTypeName(clean);
            if (resolved != null && !resolved.isEmpty()) {
                clean = resolved;
            }
        }

        if (CompilerRegistry.globalClassAccess.containsKey(clean)) {
            return (CompilerRegistry.globalClassAccess.get(clean) & Opcodes.ACC_FINAL) != 0;
        }
        try {
            Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
            if (clazz != null) {
                return Modifier.isFinal(clazz.getModifiers());
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isReferenceConvertible(String sourceType, String targetType) {
        if (sourceType == null || targetType == null) return true;
        String cleanSource = TypeChecker.cleanDescriptor(sourceType);
        String cleanTarget = TypeChecker.cleanDescriptor(targetType);
        if (cleanSource.equals(cleanTarget)) return true;
        if ("null".equals(cleanSource) || "null".equals(cleanTarget)) return true;
        if (OceanTypeSystem.OBJECT_DESC.equals(cleanSource) || OceanTypeSystem.OBJECT_DESC.equals(cleanTarget)
                || "java/lang/Object".equals(cleanSource) || "java/lang/Object".equals(cleanTarget)) {
            return true;
        }

        boolean sourceIsArray = cleanSource.startsWith("[");
        boolean targetIsArray = cleanTarget.startsWith("[");

        if (sourceIsArray && targetIsArray) {
            String elemSource = cleanSource.substring(1);
            String elemTarget = cleanTarget.substring(1);
            if (TypeChecker.isPrimitive(elemSource) || TypeChecker.isPrimitive(elemTarget)) {
                return elemSource.equals(elemTarget);
            }
            return isReferenceConvertible(elemSource, elemTarget);
        }
        if (sourceIsArray) {
            return "java/lang/Cloneable".equals(cleanTarget) || "Ljava/lang/Cloneable;".equals(cleanTarget)
                    || "java/io/Serializable".equals(cleanTarget) || "Ljava/io/Serializable;".equals(cleanTarget);
        }
        if (targetIsArray) {
            return "java/lang/Cloneable".equals(cleanSource) || "Ljava/lang/Cloneable;".equals(cleanSource)
                    || "java/io/Serializable".equals(cleanSource) || "Ljava/io/Serializable;".equals(cleanSource);
        }

        String srcClass = cleanSource.startsWith("L") && cleanSource.endsWith(";") ? cleanSource.substring(1, cleanSource.length() - 1) : cleanSource;
        String tgtClass = cleanTarget.startsWith("L") && cleanTarget.endsWith(";") ? cleanTarget.substring(1, cleanTarget.length() - 1) : cleanTarget;

        boolean srcIsInterface = isInterfaceType(srcClass);
        boolean tgtIsInterface = isInterfaceType(tgtClass);

        if (!srcIsInterface && !tgtIsInterface) {
            boolean srcSubTgt = TypeChecker.isAssignable(cleanTarget, cleanSource, session);
            boolean tgtSubSrc = TypeChecker.isAssignable(cleanSource, cleanTarget, session);
            return srcSubTgt || tgtSubSrc;
        }

        if (!srcIsInterface) {
            if (isClassFinal(srcClass)) {
                return TypeChecker.isAssignable(cleanTarget, cleanSource, session);
            }
            return true;
        }

        if (!tgtIsInterface) {
            if (isClassFinal(tgtClass)) {
                return TypeChecker.isAssignable(cleanSource, cleanTarget, session);
            }
            return true;
        }

        return true;
    }

    @Override
    public void visitInstanceof(IRInstanceof node) {
        if (node.getExpression() != null) {
            node.getExpression().accept(this);
        }

        String rawTarget = node.getTargetType();
        if (rawTarget == null && node.getPattern() != null) {
            rawTarget = node.getPattern().getTypeDescriptor();
        }

        String specialized = TypeChecker.getSpecializedPrimitiveListClass(rawTarget);
        if (specialized != null) {
            rawTarget = specialized;
            node.setTargetType(specialized);
        } else if (rawTarget != null && rawTarget.contains("<") && rawTarget.contains(">")) {
            int openIdx = rawTarget.indexOf('<');
            int closeIdx = rawTarget.lastIndexOf('>');
            String typeArgs = rawTarget.substring(openIdx + 1, closeIdx).trim();
            if (typeArgs.endsWith(";")) {
                typeArgs = typeArgs.substring(0, typeArgs.length() - 1);
            }
            if (typeArgs.startsWith("L") && typeArgs.endsWith(";")) {
                typeArgs = typeArgs.substring(1, typeArgs.length() - 1);
            }
            if (!typeArgs.equals("?") && !typeArgs.equals("*") && !typeArgs.isEmpty()) {
                reportError(node, "Cannot use non-reifiable type '" + rawTarget + "' in instanceof. Only raw type or unbounded wildcard ('<?>') is allowed due to type erasure.");
            }
            rawTarget = rawTarget.substring(0, openIdx).trim();
            node.setTargetType(rawTarget);
        }

        String exprType = node.getExpression() != null ? node.getExpression().getTypeDescriptor() : null;
        String targetDesc = rawTarget;
        if (targetDesc != null) {
            if (TypeChecker.isPrimitive(targetDesc)) {
                String pDesc = SymbolTable.getDescriptor(targetDesc);
                if (TypeChecker.isPrimitive(pDesc)) targetDesc = pDesc;
            } else if (!targetDesc.startsWith("L") && !targetDesc.startsWith("[")) {
                String fqcn = resolveTypeName(targetDesc);
                targetDesc = OceanTypeSystem.wrapObjectType(fqcn);
            }
        }

        boolean exprIsPrim = TypeChecker.isPrimitive(exprType);
        boolean targetIsPrim = TypeChecker.isPrimitive(targetDesc);

        if (exprIsPrim && targetIsPrim) {
            boolean exprIsNum = TypeChecker.isNumeric(exprType);
            boolean targetIsNum = TypeChecker.isNumeric(targetDesc);
            boolean exprIsBool = TypeChecker.isBoolean(exprType);
            boolean targetIsBool = TypeChecker.isBoolean(targetDesc);

            if (!((exprIsNum && targetIsNum) || (exprIsBool && targetIsBool))) {
                reportError(node, "Inconvertible types for instanceof: '" + TypeChecker.humanReadable(exprType) + "' cannot be cast to '" + TypeChecker.humanReadable(targetDesc) + "'");
            }
        } else {
            String effectiveExprType = exprIsPrim ? TypeChecker.box(exprType) : exprType;
            String effectiveTargetDesc = targetIsPrim ? TypeChecker.box(targetDesc) : targetDesc;

            if (effectiveExprType != null && effectiveTargetDesc != null) {
                if (!isReferenceConvertible(effectiveExprType, effectiveTargetDesc)) {
                    reportError(node, "Inconvertible types for instanceof: '" + TypeChecker.humanReadable(exprType) + "' cannot be cast to '" + TypeChecker.humanReadable(targetDesc) + "'");
                }
            }
        }

        if (node.getPattern() != null) {
            declarePatternVariables(node.getPattern());
        } else if (node.getPatternVarName() != null && targetDesc != null) {
            symbolTable.declareVariable(node.getPatternVarName(), targetDesc, true, true);
        }
    }

    private String unboxType(String typeDesc) {
        if (typeDesc == null) return null;
        String clean = TypeChecker.cleanDescriptor(typeDesc);
        if (!clean.startsWith("L") && !clean.startsWith("[")) {
            clean = "L" + clean.replace('.', '/') + ";";
        }
        return TypeChecker.unbox(clean);
    }

    // Cast Expression Compatibility Check
    private boolean isCastCompatible(String targetType, String sourceType) {
        if (targetType == null || sourceType == null) return true;

        String cleanTarget = TypeChecker.cleanDescriptor(targetType).replace(">", "").replace("<", "");
        String cleanSource = TypeChecker.cleanDescriptor(sourceType).replace(">", "").replace("<", "");
        if (cleanTarget.equals(cleanSource)) return true;
        if (cleanSource.equals("null")) return !TypeChecker.isPrimitive(cleanTarget);
        if (cleanTarget.equals("null")) return false;

        boolean isTargetPrimitive = TypeChecker.isPrimitive(cleanTarget);
        boolean isSourcePrimitive = TypeChecker.isPrimitive(cleanSource);

        if (isTargetPrimitive && isSourcePrimitive) {
            boolean targetIsBool = "Z".equals(cleanTarget);
            boolean sourceIsBool = "Z".equals(cleanSource);
            return !targetIsBool && !sourceIsBool;
            // Numeric primitive conversions are all valid casts
        }

        if (isTargetPrimitive) {
            String srcRaw = cleanSource.startsWith("L") && cleanSource.endsWith(";")
                    ? cleanSource.substring(1, cleanSource.length() - 1) : cleanSource;
            srcRaw = srcRaw.replace('.', '/');

            if ("java/lang/Object".equals(srcRaw)) return true;

            String unboxed = unboxType(cleanSource);
            if (unboxed != null) {
                return isCastCompatible(cleanTarget, unboxed);
            }
            if (isInterfaceType(srcRaw)) {
                return "java/lang/Comparable".equals(srcRaw) || "java/io/Serializable".equals(srcRaw);
            }
            if ("Z".equals(cleanTarget)) {
                return "java/lang/Boolean".equals(srcRaw);
            } else {
                return "java/lang/Number".equals(srcRaw);
            }
        }

        if (isSourcePrimitive) {
            String boxed = TypeChecker.box(cleanSource);
            if (boxed == null) return false;
            return isReferenceConvertible(boxed, cleanTarget);
        }

        return isReferenceConvertible(cleanSource, cleanTarget);
    }


    @Override
    public void visitCast(IRCastExpression node) {
        if (node.getExpression() != null) {
            node.getExpression().accept(this);
            String sourceType = node.getExpression().getTypeDescriptor();
            String targetType = node.getTargetType();
            if (node.getExpression() instanceof IRLambdaExpression) {
                if (!TypeChecker.isFunctionalInterface(targetType)) {
                    reportError(node, "Target type '" + TypeChecker.humanReadable(targetType) + "' is not a functional interface.");
                }
            }
            if (sourceType != null && targetType != null) {
                if (!isCastCompatible(targetType, sourceType)) {
                    reportError(node, "Inconvertible types: cannot cast '" + TypeChecker.humanReadable(sourceType) + "' to '" + TypeChecker.humanReadable(targetType) + "'.");
                }
            }
            if (node.getAdditionalBounds() != null && sourceType != null) {
                for (String extra : node.getAdditionalBounds()) {
                    if (extra != null && !isCastCompatible(extra, sourceType)) {
                        reportError(node, "Inconvertible types: cannot cast '" + TypeChecker.humanReadable(sourceType) + "' to '" + TypeChecker.humanReadable(extra) + "'.");
                    }
                }
            }
        }
    }

    private List<String> findSamDeclaredThrows(String targetInterface, String samMethodName, String samMethodDesc) {
        List<String> throwsList = new ArrayList<>();
        if (targetInterface == null || samMethodName == null) return throwsList;
        String cleanInterface = TypeChecker.cleanDescriptor(targetInterface);
        if (cleanInterface != null && cleanInterface.startsWith("L") && cleanInterface.endsWith(";")) {
            cleanInterface = cleanInterface.substring(1, cleanInterface.length() - 1);
        }

        // 1. Check CompilerRegistry
        Map<String, List<String>> ifaceThrows = CompilerRegistry.globalMethodThrowsRegistry.get(cleanInterface);
        if (ifaceThrows != null && ifaceThrows.containsKey(samMethodName)) {
            List<String> registeredThrows = ifaceThrows.get(samMethodName);
            if (registeredThrows != null) {
                for (String t : registeredThrows) {
                    throwsList.add(OceanTypeSystem.wrapObjectType(t));
                }
                return throwsList;
            }
        }

        // 2. Check Reflection
        String fqcn = cleanInterface != null ? cleanInterface.replace('/', '.') : null;
        try {
            Class<?> clazz = OceanTypeSystem.forName(fqcn);
            if (clazz != null) {
                for (Method m : clazz.getMethods()) {
                    if (m.getName().equals(samMethodName)) {
                        for (Class<?> exc : m.getExceptionTypes()) {
                            throwsList.add(OceanTypeSystem.wrapObjectType(exc.getName().replace('.', '/')));
                        }
                        return throwsList;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return throwsList;
    }

    private Map<String, String> buildSamTypeParameterMapping(String targetType) {
        if (targetType == null || !targetType.contains("<")) return Collections.emptyMap();
        int start = targetType.indexOf('<');
        int end = targetType.lastIndexOf('>');
        if (start < 0 || end <= start) return Collections.emptyMap();

        String genericPart = targetType.substring(start + 1, end);
        List<String> rawArgs = splitGenericArgs(genericPart);
        if (rawArgs.isEmpty()) return Collections.emptyMap();

        String cleanOwner = TypeChecker.cleanDescriptor(targetType);
        if (cleanOwner.startsWith("L") && cleanOwner.endsWith(";")) {
            cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
        }

        Map<String, String> typeParamMap = new HashMap<>();

        // 1. Try Ocean declared type parameters from CompilerRegistry
        List<CompilerRegistry.TypeParameterInfo> declTypeParams = CompilerRegistry.globalTypeParameterRegistry.get(cleanOwner);
        if (declTypeParams == null) {
            String simpleName = OceanTypeSystem.findSimpleName(cleanOwner);
            for (Map.Entry<String, List<CompilerRegistry.TypeParameterInfo>> entry : CompilerRegistry.globalTypeParameterRegistry.entrySet()) {
                if (entry.getKey().endsWith("/" + simpleName) || entry.getKey().endsWith("$" + simpleName)) {
                    declTypeParams = entry.getValue();
                    break;
                }
            }
        }
        if (declTypeParams != null && !declTypeParams.isEmpty()) {
            for (int i = 0; i < declTypeParams.size() && i < rawArgs.size(); i++) {
                typeParamMap.put(declTypeParams.get(i).name, rawArgs.get(i));
            }
            return typeParamMap;
        }

        // 2. Try Java Reflection for JDK / classpath interfaces
        try {
            Class<?> cls = OceanTypeSystem.forName(cleanOwner.replace('/', '.'));
            if (cls != null) {
                java.lang.reflect.TypeVariable<?>[] tps = cls.getTypeParameters();
                for (int i = 0; i < tps.length && i < rawArgs.size(); i++) {
                    typeParamMap.put(tps[i].getName(), rawArgs.get(i));
                }
            }
        } catch (Throwable ignored) {}

        return typeParamMap;
    }

    private String toSamDescriptor(String type) {
        if (type == null) return OceanTypeSystem.OBJECT_DESC;
        type = type.trim();
        if (type.startsWith("L") && type.endsWith(";")) return type;
        if (type.startsWith("[")) return type;
        if (TypeChecker.isPrimitive(type)) {
            String pDesc = SymbolTable.getDescriptor(type);
            return pDesc != null ? pDesc : type;
        }
        if (type.endsWith("[]")) {
            String elem = type.substring(0, type.length() - 2);
            return "[" + toSamDescriptor(elem);
        }
        int genericIdx = type.indexOf('<');
        String baseType = genericIdx >= 0 ? type.substring(0, genericIdx) : type;
        String desc = SymbolTable.getDescriptor(baseType, importedClasses, null);
        if (desc != null) {
            if (!desc.startsWith("L") && !desc.startsWith("[")) {
                desc = OceanTypeSystem.wrapObjectType(desc);
            }
            if (genericIdx >= 0) {
                String raw = desc.substring(1, desc.length() - 1);
                return "L" + raw + type.substring(genericIdx) + ";";
            }
            return desc;
        }
        return OceanTypeSystem.wrapObjectType(type.replace('.', '/'));
    }

    private List<String> parseMethodParamDescriptors(String methodDesc) {
        List<String> params = new ArrayList<>();
        if (methodDesc == null || !methodDesc.startsWith("(") || !methodDesc.contains(")")) return params;
        int i = methodDesc.indexOf('(') + 1;
        int end = methodDesc.indexOf(')');
        while (i < end) {
            int start = i;
            while (i < end && methodDesc.charAt(i) == '[') i++;
            if (i < end && methodDesc.charAt(i) == 'L') {
                int semi = methodDesc.indexOf(';', i);
                if (semi >= 0 && semi <= end) {
                    i = semi + 1;
                } else {
                    i++;
                }
            } else {
                i++;
            }
            params.add(methodDesc.substring(start, i));
        }
        return params;
    }

    private List<String> resolveSamGenericParamTypes(String targetInterface, String samMethodName, String samDesc) {
        List<String> rawSamParams = parseMethodParamDescriptors(samDesc);
        if (targetInterface == null || !targetInterface.contains("<")) {
            return rawSamParams;
        }

        Map<String, String> typeParamMap = buildSamTypeParameterMapping(targetInterface);
        if (typeParamMap.isEmpty()) {
            return rawSamParams;
        }

        String cleanOwner = TypeChecker.cleanDescriptor(targetInterface);
        if (cleanOwner.startsWith("L") && cleanOwner.endsWith(";")) {
            cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
        }

        // 1. Check Ocean method params registry
        Map<String, List<CompilerRegistry.MethodParamInfo>> classParamsMap = CompilerRegistry.globalMethodParamsRegistry.get(cleanOwner);
        if (classParamsMap == null) {
            String simpleName = OceanTypeSystem.findSimpleName(cleanOwner);
            for (Map.Entry<String, Map<String, List<CompilerRegistry.MethodParamInfo>>> entry : CompilerRegistry.globalMethodParamsRegistry.entrySet()) {
                if (entry.getKey().endsWith("/" + simpleName) || entry.getKey().endsWith("$" + simpleName)) {
                    classParamsMap = entry.getValue();
                    break;
                }
            }
        }
        if (classParamsMap != null && samMethodName != null && classParamsMap.containsKey(samMethodName)) {
            List<CompilerRegistry.MethodParamInfo> paramInfos = classParamsMap.get(samMethodName);
            List<String> resolved = new ArrayList<>();
            for (CompilerRegistry.MethodParamInfo pi : paramInfos) {
                String pType = pi.rawType();
                boolean isNullable = pType != null && pType.endsWith("?");
                String baseType = isNullable ? pType.substring(0, pType.length() - 1) : pType;
                if (typeParamMap.containsKey(baseType)) {
                    String mapped = toSamDescriptor(typeParamMap.get(baseType));
                    resolved.add(isNullable ? mapped + "?" : mapped);
                } else if (pType != null) {
                    resolved.add(toSamDescriptor(pType));
                }
            }
            if (resolved.size() == rawSamParams.size()) {
                return resolved;
            }
        }

        // 2. Check Java reflection for JDK interfaces
        try {
            Class<?> cls = OceanTypeSystem.forName(cleanOwner.replace('/', '.'));
            if (cls != null) {
                for (Method m : cls.getMethods()) {
                    if (Modifier.isAbstract(m.getModifiers())) {
                        if (samMethodName == null || m.getName().equals(samMethodName)) {
                            java.lang.reflect.Type[] genericParams = m.getGenericParameterTypes();
                            List<String> resolved = new ArrayList<>();
                            for (java.lang.reflect.Type gp : genericParams) {
                                if (gp instanceof java.lang.reflect.TypeVariable<?> tv && typeParamMap.containsKey(tv.getName())) {
                                    resolved.add(toSamDescriptor(typeParamMap.get(tv.getName())));
                                } else if (gp instanceof Class<?> c) {
                                    resolved.add(Type.getDescriptor(c));
                                }
                            }
                            if (resolved.size() == rawSamParams.size()) {
                                return resolved;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        return rawSamParams;
    }

    private String resolveSamGenericReturnType(String targetInterface, String samMethodName, String samDesc) {
        if (samDesc == null || !samDesc.contains(")")) return OceanTypeSystem.OBJECT_DESC;
        String samReturn = samDesc.substring(samDesc.lastIndexOf(')') + 1);
        if ("V".equals(samReturn)) return "V";
        if (TypeChecker.isPrimitive(samReturn)) return samReturn;
        if (targetInterface == null || !targetInterface.contains("<")) return samReturn;

        Map<String, String> typeParamMap = buildSamTypeParameterMapping(targetInterface);
        if (typeParamMap.isEmpty()) return samReturn;

        String cleanOwner = TypeChecker.cleanDescriptor(targetInterface);
        if (cleanOwner.startsWith("L") && cleanOwner.endsWith(";")) {
            cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
        }

        // 1. Check Ocean method generic return type registry
        Map<String, String> genericReturnMap = CompilerRegistry.globalMethodGenericReturnTypeRegistry.get(cleanOwner);

        if (genericReturnMap != null && samMethodName != null && genericReturnMap.containsKey(samMethodName)) {
            String declaredRet = genericReturnMap.get(samMethodName);
            if (declaredRet != null) {
                boolean isNullable = declaredRet.endsWith("?");
                String baseRet = isNullable ? declaredRet.substring(0, declaredRet.length() - 1) : declaredRet;
                if (typeParamMap.containsKey(baseRet)) {
                    String substituted = toSamDescriptor(typeParamMap.get(baseRet));
                    return isNullable ? substituted + "?" : substituted;
                }
                return toSamDescriptor(declaredRet);
            }
        }

        // 2. Check Java reflection for JDK interfaces
        try {
            Class<?> cls = OceanTypeSystem.forName(cleanOwner.replace('/', '.'));
            if (cls != null) {
                for (Method m : cls.getMethods()) {
                    if (Modifier.isAbstract(m.getModifiers())) {
                        if (samMethodName == null || m.getName().equals(samMethodName)) {
                            java.lang.reflect.Type genericRet = m.getGenericReturnType();
                            if (genericRet instanceof java.lang.reflect.TypeVariable<?> tv && typeParamMap.containsKey(tv.getName())) {
                                return toSamDescriptor(typeParamMap.get(tv.getName()));
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 3. Fallback for standard single-result functional interfaces (e.g. Supplier<T>, Function<T, R>)
        int start = targetInterface.indexOf('<');
        int end = targetInterface.lastIndexOf('>');
        if (start >= 0 && end > start) {
            List<String> rawArgs = splitGenericArgs(targetInterface.substring(start + 1, end));
            if (!rawArgs.isEmpty()) {
                return toSamDescriptor(rawArgs.getLast());
            }
        }

        return samReturn;
    }

    private boolean isLambdaParamCompatible(String expectedType, String actualType) {
        if (expectedType == null || actualType == null) return true;
        expectedType = toSamDescriptor(expectedType);
        actualType = toSamDescriptor(actualType);
        if (expectedType.equals(actualType)) return true;
        String cleanExpected = TypeChecker.cleanDescriptor(expectedType);
        String cleanActual = TypeChecker.cleanDescriptor(actualType);
        if (cleanExpected.equals(cleanActual)) return true;

        // Contravariance: The implementation parameter must accept the argument type provided by SAM.
        if (TypeChecker.isAssignable(actualType, expectedType, session)) return true;

        // Downcasting / narrowing (e.g. raw types like (Consumer) (String x) -> ...)
        if (TypeChecker.isAssignable(expectedType, actualType, session)) return true;

        // Primitives / boxing check
        if (TypeChecker.isPrimitive(cleanActual) && cleanExpected.equals(OceanTypeSystem.box(cleanActual))) return true;
        return TypeChecker.isPrimitive(cleanExpected) && cleanActual.equals(OceanTypeSystem.box(cleanExpected));
    }

    // P2-4 & R3-8: Lambda SAM Resolution & Capture Check
    @Override
    public void visitLambda(IRLambdaExpression node) {
        boolean isAsyncLambda = (node.getLambdaMethodName() != null && node.getLambdaMethodName().contains("$async$"));
        if (!isAsyncLambda) {
            lambdaDepth++;
            lambdaScopeDepths.push(symbolTable.getScopeDepth() + 1);
        }
        String oldReturnDesc = currentMethodReturnDesc;
        boolean oldAsync = currentMethodIsAsync;
        List<String> oldDeclaredThrows = new ArrayList<>(currentMethodDeclaredThrows);
        int oldLoopDepth = loopDepth;
        int oldSwitchDepth = switchDepth;
        int oldSwitchExpressionDepth = switchExpressionDepth;
        int oldFinallyDepth = finallyDepth;
        Deque<ControlTarget> oldControlTargets = new ArrayDeque<>(controlTargetStack);
        if (!isAsyncLambda) {
            enclosingLoopDepth += loopDepth;
            enclosingSwitchDepth += switchDepth;
            enclosingSwitchExpressionDepth += switchExpressionDepth;
            loopDepth = 0;
            switchDepth = 0;
            switchExpressionDepth = 0;
            finallyDepth = 0;
            controlTargetStack.clear();
        }
        try {
            currentMethodIsAsync = isAsyncLambda || oldAsync;
            String samDesc = node.getSamMethodDesc();
            if (samDesc != null && samDesc.contains(")")) {
                currentMethodReturnDesc = resolveSamGenericReturnType(node.getTargetInterface(), node.getSamMethodName(), samDesc);
            } else {
                currentMethodReturnDesc = OceanTypeSystem.OBJECT_DESC;
            }

            if (!isAsyncLambda && node.getTargetInterface() != null && node.getSamMethodName() != null) {
                currentMethodDeclaredThrows.clear();
                currentMethodDeclaredThrows.addAll(findSamDeclaredThrows(node.getTargetInterface(), node.getSamMethodName(), node.getSamMethodDesc()));
            } else if (!isAsyncLambda) {
                currentMethodDeclaredThrows.clear();
            }

            List<String> pNames = node.getParameterNames();
            List<String> pTypes = node.getParameterTypes();
            int capturedInParams = 0;
            if (node.getCapturedNames() != null) {
                for (String cn : node.getCapturedNames()) {
                    if (node.isStatic() || !cn.equals("this")) {
                        capturedInParams++;
                    }
                }
            }
            boolean isSyntheticLambda = (node.getLambdaMethodName() != null && (node.getLambdaMethodName().contains("$async$") || node.getLambdaMethodName().contains("$method$")));

            if (!isAsyncLambda && node.getCapturedNames() != null) {
                for (String cName : node.getCapturedNames()) {
                    if ("this".equals(cName) || "super".equals(cName)) continue;
                    if (symbolTable.isMutated(cName)) {
                        reportError(node, "Local variable '" + cName + "' accessed from within inner class or lambda must be final or effectively final.");
                    }
                    symbolTable.markCaptured(cName);
                }
            }

            // SAM Signature & Parameter Count Validation
            if (!isAsyncLambda && samDesc != null) {
                List<String> expectedSamParamTypes = resolveSamGenericParamTypes(node.getTargetInterface(), node.getSamMethodName(), samDesc);
                int totalParamCount = (pNames != null) ? pNames.size() : 0;
                int regularParamCount = Math.max(0, totalParamCount - capturedInParams);
                if (regularParamCount != expectedSamParamTypes.size()) {
                    reportError(node, "Lambda parameter count mismatch: target functional interface expects " + expectedSamParamTypes.size() + " parameter(s), found " + regularParamCount + ".");
                } else {
                    for (int k = 0; k < regularParamCount; k++) {
                        int paramIdx = capturedInParams + k;
                        String pName = null;
                        if (pNames != null) {
                            pName = pNames.get(paramIdx);
                        }
                        String pType = (pTypes != null && paramIdx < pTypes.size()) ? pTypes.get(paramIdx) : OceanTypeSystem.OBJECT_DESC;
                        String expectedType = expectedSamParamTypes.get(k);
                        if (!isLambdaParamCompatible(expectedType, pType)) {
                            reportError(node, "Lambda parameter type mismatch: expected '" + TypeChecker.humanReadable(expectedType) + "' for parameter '" + pName + "', found '" + TypeChecker.humanReadable(pType) + "'.");
                        }
                    }
                }
            }

            symbolTable.enterScope();
            try {
                if (pNames != null) {
                    Set<String> seenLambdaParams = new HashSet<>();
                    for (int i = 0; i < pNames.size(); i++) {
                        String name = pNames.get(i);
                        boolean isUserParam = (i >= capturedInParams);
                        if (isUserParam) {
                            if (!"_".equals(name)) {
                                if (!seenLambdaParams.add(name)) {
                                    reportError(node, "Duplicate lambda parameter: '" + name + "'");
                                }
                                if (!isSyntheticLambda && currentMethodBaseScopeDepth > 0 && symbolTable.isDeclaredInMethodScope(name, currentMethodBaseScopeDepth)) {
                                    reportError(node, "Lambda parameter '" + name + "' cannot shadow existing variable in method scope.");
                                }
                            }
                        }
                        String type = (pTypes != null && i < pTypes.size()) ? pTypes.get(i) : OceanTypeSystem.OBJECT_DESC;
                        boolean isCapturedParam = (i < capturedInParams) && !isAsyncLambda;
                        symbolTable.declareParameter(name, type != null ? type : OceanTypeSystem.OBJECT_DESC);
                        if (isCapturedParam) {
                            symbolTable.markCaptured(name);
                        }
                    }
                }

                if (node.getBody() != null) {
                    node.getBody().accept(this);
                }
            } finally {
                symbolTable.exitScope();
            }
        } finally {
            if (!isAsyncLambda) {
                enclosingLoopDepth -= oldLoopDepth;
                enclosingSwitchDepth -= oldSwitchDepth;
                enclosingSwitchExpressionDepth -= oldSwitchExpressionDepth;
                loopDepth = oldLoopDepth;
                switchDepth = oldSwitchDepth;
                switchExpressionDepth = oldSwitchExpressionDepth;
                finallyDepth = oldFinallyDepth;
                controlTargetStack.clear();
                controlTargetStack.addAll(oldControlTargets);
            }
            currentMethodReturnDesc = oldReturnDesc;
            currentMethodIsAsync = oldAsync;
            currentMethodDeclaredThrows.clear();
            currentMethodDeclaredThrows.addAll(oldDeclaredThrows);
            if (!isAsyncLambda) {
                lambdaDepth--;
                if (!lambdaScopeDepths.isEmpty()) {
                    lambdaScopeDepths.pop();
                }
            }
        }
    }

    // R3-13 & R3-14: Generic Array Creation & Array Size Integer Type & Negative Size Check
    @Override
    public void visitArrayCreation(IRArrayCreation node) {
        if (node.getSizes() != null && node.getSizes().size() > 255) {
            reportError(node, "Array dimension limit exceeded (maximum 255 dimensions supported, " + node.getSizes().size() + " specified).");
        }
        String rawBase = node.getRawType() != null ? node.getRawType() : node.getBaseType();
        String clean = TypeChecker.isClassType(rawBase) ? rawBase.substring(1, rawBase.length() - 1) : rawBase;
        if (clean != null && (symbolTable.getTypeParams().contains(clean) || (clean.length() == 1 && Character.isUpperCase(clean.charAt(0)) && !"I".equals(clean) && !"J".equals(clean) && !"F".equals(clean) && !"D".equals(clean) && !"Z".equals(clean) && !"B".equals(clean) && !"C".equals(clean) && !"S".equals(clean)))) {
            reportError(node, "Generic array creation: cannot create array of type parameter '" + clean + "'");
        }

        for (IRExpression sizeExpr : node.getSizes()) {
            sizeExpr.accept(this);
            String sizeType = sizeExpr.getTypeDescriptor();
            if (!TypeChecker.isValidArrayIndexType(sizeType)) {
                reportError(node, "Array dimension must be of type int: found '" + TypeChecker.humanReadable(sizeType) + "'");
            }
            Long constVal = evaluateConstantInt(sizeExpr,node);
            if (constVal != null && constVal < 0) {
                reportError(node, "Array dimension cannot be negative: " + constVal);
            }
        }
    }

    // P2-5 & R2-4: Range / Array Access & Custom Indexer Check
    @Override
    public void visitArrayAccess(IRArrayAccess node) {
        if (node.getArray() != null) node.getArray().accept(this);
        if (node.getIndex() != null) node.getIndex().accept(this);

        if (node.getArray() != null) {
            String arrayType = node.getArray().getTypeDescriptor();
            boolean isNonNullInScope = false;
            if (node.getArray() instanceof IRVariableAccess va) {
                isNonNullInScope = isVariableNonNullInScope(va.getName());
                String symType = symbolTable.getType(va.getName());
                if (symType != null && symType.endsWith("?")) {
                    arrayType = symType;
                }
            }
            if (arrayType != null && arrayType.endsWith("?") && !isNonNullInScope) {
                reportError(node, "Direct element access is not allowed on nullable array or object.");
            }

            if (TypeChecker.isMapType(arrayType)) {
                reportError(node, "Index access '[]' is not supported on Map. Please use '.get(key)' and '.put(key, value)'.");
                return;
            }

            // Synchronize element type descriptor
            String elemType = null;
            String baseArrayType = arrayType;
            if (baseArrayType != null && baseArrayType.endsWith("?")) {
                baseArrayType = baseArrayType.substring(0, baseArrayType.length() - 1);
            }

            if (baseArrayType != null && baseArrayType.startsWith("[")) {
                elemType = baseArrayType.substring(1);
            } else if (baseArrayType != null && (baseArrayType.endsWith("[]") || baseArrayType.endsWith("..."))) {
                String clean = TypeChecker.cleanDescriptor(baseArrayType);
                if (clean.endsWith("[]")) {
                    String comp = clean.substring(0, clean.length() - 2).trim();
                    elemType = toTypeDescriptor(comp);
                } else if (clean.endsWith("...")) {
                    String comp = clean.substring(0, clean.length() - 3).trim();
                    elemType = toTypeDescriptor(comp);
                }
            } else if (baseArrayType != null) {
                String primElem = TypeChecker.getSpecializedPrimitiveListElementType(baseArrayType);
                if (primElem != null) {
                    elemType = primElem;
                } else if (baseArrayType.contains("<")) {
                    List<String> typeArgs = IRGenerator.parseGenericTypeArguments(baseArrayType);
                    if (!typeArgs.isEmpty()) {
                        String clean = TypeChecker.cleanDescriptor(baseArrayType);
                        if (TypeChecker.isMapType(clean) && typeArgs.size() >= 2) {
                            elemType = toTypeDescriptor(typeArgs.get(1));
                        } else {
                            elemType = toTypeDescriptor(typeArgs.getFirst());
                        }
                    }
                }
            }

            if (arrayType != null && !arrayType.startsWith("[") && !arrayType.endsWith("...") && !arrayType.endsWith("[]") && !TypeChecker.isStringType(arrayType)) {
                String ownerClass = TypeChecker.cleanDescriptor(arrayType);
                Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(ownerClass);
                boolean hasGet = false;
                if (methods != null) {
                    if (methods.containsKey("get")) {
                        hasGet = true;
                        if (elemType == null) {
                            String mDesc = methods.get("get");
                            if (mDesc != null && mDesc.contains(")")) {
                                elemType = mDesc.substring(mDesc.lastIndexOf(')') + 1);
                            }
                        }
                    } else if (methods.containsKey("getItem")) {
                        hasGet = true;
                        if (elemType == null) {
                            String mDesc = methods.get("getItem");
                            if (mDesc != null && mDesc.contains(")")) {
                                elemType = mDesc.substring(mDesc.lastIndexOf(')') + 1);
                            }
                        }
                    }
                }
                if (!hasGet) {
                    try {
                        Class<?> clazz = OceanTypeSystem.forName(ownerClass.replace('/', '.'));
                        if (clazz != null) {
                            for (Method m : clazz.getMethods()) {
                                if (m.getName().equals("get") && m.getParameterCount() == 1) {
                                    hasGet = true;
                                    if (elemType == null) {
                                        elemType = Type.getDescriptor(m.getReturnType());
                                    }
                                    break;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                if (!hasGet) {
                    reportError(node, "Array index access is not supported on type '" + TypeChecker.humanReadable(arrayType) + "'. Method 'get(int)' must be defined.");
                }
            }

            if (elemType != null) {
                node.setTypeDescriptor(elemType);
            }
        }

        if (node.getIndex() != null) {
            String indexType = node.getIndex().getTypeDescriptor();
            if (indexType != null && !TypeChecker.isValidArrayIndexType(indexType)) {
                reportError(node, "Array index must be of type int: found '" + TypeChecker.humanReadable(indexType) + "'");
            }
        }
    }

    @Override
    public void visitArrayLiteral(IRArrayLiteral node) {
        if (node.getElements() != null) {
            for (IRExpression elem : node.getElements()) {
                if (elem != null) elem.accept(this);
            }
        }
    }

    @Override
    public void visitOceanOutput(IROceanOutput node) {
        if (node.getArguments() != null) {
            for (IRExpression arg : node.getArguments()) {
                if (arg != null) {
                    if (arg.getTypeDescriptor()!=null){
                        if (arg.getTypeDescriptor().equals("V")) reportError(node, "'void' expression cannot be passed as a method argument.");
                        else arg.accept(this);
                    }
                }
            }
        }
    }

    @Override
    public void visitInterpolatedString(IRInterpolatedString node) {
        if (node.getParts() != null) {
            for (IRNode part : node.getParts()) {
                if (part != null) part.accept(this);
            }
        }
    }

    // R2-4 & R3-12: Nullable Member Access & Non-Static Method Call Check & Redundant Safe Access Check
    @Override
    public void visitMethodCall(IRMethodCall node) {
        if (isStaticContext && node.isSuperCall() && symbolTable.getType("this") == null) {
            reportError(node, "Cannot use 'super' in a static context");
        }
        String methodName = node.getName();
        if (node.isSuperCall() && !"<init>".equals(methodName)) {
            String superOwner = node.getOwner() != null ? node.getOwner() : (currentSuperName != null ? currentSuperName : "java/lang/Object");
            if (isMethodStatic(superOwner, methodName, node.getDescriptor())) {
                reportError(node, "Static method '" + methodName + "' cannot be invoked using 'super'. Invoke it using class name '" + OceanTypeSystem.findSimpleName(superOwner) + "'.");
            }
            if (isMethodAbstract(superOwner, methodName, node.getDescriptor())) {
                reportError(node, "Method '" + methodName + "' invoked via 'super' is abstract and has no body.");
            }
        }
        if ("<init>".equals(methodName) && (node.isSuperCall() || node.isThisCall())) {
            if (!node.isSynthetic()) {
                if (!"<init>".equals(currentMethodName)) {
                    reportError(node, (node.isThisCall() ? "this(...)" : "super(...)") + " can only be invoked inside a constructor.");
                    return;
                }
                if (!currentStmtIsTopLevelCtorStmt) {
                    reportError(node, (node.isThisCall() ? "this(...)" : "super(...)") + " call cannot be nested in conditional blocks; it must be the top-level statement in constructor body.");
                    return;
                }
            }
            if (currentClassIsEnum && node.isSuperCall() && !node.isThisCall()) {
                if (!node.isSynthetic()) {
                    reportError(node, "Explicit 'super()' call is not allowed in enum constructors. Only 'this()' can be used.");
                }
                return;
            }
            if (node.getArguments() != null) {
                for (IRExpression arg : node.getArguments()) {
                    arg.accept(this);
                    if (arg.getTypeDescriptor() != null && "V".equals(TypeChecker.cleanDescriptor(arg.getTypeDescriptor()))) {
                        reportError(node, "'void' expression cannot be passed as a method argument.");
                    }
                }
            }
            inEarlyConstructionContext = false;

            if (node.isSuperCall()) {
                String superOwner = node.getOwner();
                if (superOwner != null && !"java/lang/Object".equals(superOwner)) {
                    Map<String, List<String>> superOverloads = null;
                    for (Map.Entry<String, Map<String, List<String>>> entry : CompilerRegistry.globalOverloadRegistry.entrySet()) {
                        String k = entry.getKey();
                        if (k.equals(superOwner) || k.endsWith("/" + superOwner) || k.endsWith("." + superOwner)) {
                            superOverloads = entry.getValue();
                            break;
                        }
                    }
                    List<String> ctors = null;
                    if (superOverloads != null && superOverloads.containsKey("<init>")) {
                        ctors = superOverloads.get("<init>");
                    } else {
                        List<String> javaCtors = ClassMetadataCache.getConstructorDescriptors(superOwner);
                        if (javaCtors != null && !javaCtors.isEmpty()) {
                            ctors = javaCtors;
                        }
                    }
                    if (ctors != null && !ctors.isEmpty() && !ctors.contains("()V") && (node.getArguments() == null || node.getArguments().isEmpty())) {
                        reportError(node, "No suitable constructor found in superclass '" + superOwner + "'.");
                    }
                }
            }
            return;
        }

        if (inEarlyConstructionContext && !node.isStatic()) {
            boolean isThisOrSuperReceiver = (node.getReceiver() == null || (node.getReceiver() instanceof IRVariableAccess va && ("this".equals(va.getName()) || "super".equals(va.getName()))));
            if (isThisOrSuperReceiver) {
                reportError(node, "Cannot invoke instance method '" + methodName + "' before supertype constructor has been called.");
                return;
            }
        }
        if (methodName != null) {
            for (Map<String, List<CompilerRegistry.ExtensionMethodInfo>> extMap : CompilerRegistry.globalExtensionMethodRegistry.values()) {
                if (extMap != null && extMap.containsKey(methodName)) {
                    if (node.getReceiver() != null) node.getReceiver().accept(this);
                    for (IRExpression arg : node.getArguments()) {
                        arg.accept(this);
                        if (arg.getTypeDescriptor() != null && "V".equals(TypeChecker.cleanDescriptor(arg.getTypeDescriptor()))) {
                            reportError(node, "'void' expression cannot be passed as a method argument.");
                        }
                    }
                    return;
                }
            }
        }
        if (node.getReceiver() != null) {
            node.getReceiver().accept(this);
            String recvType = node.getReceiver().getTypeDescriptor();

            boolean isNonNullInScope = false;

            if (node.getReceiver() instanceof IRVariableAccess va) {
                isNonNullInScope = isVariableNonNullInScope(va.getName());
                String symType = symbolTable.getType(va.getName());
                if (symType != null && symType.endsWith("?")) {
                    recvType = symType;
                }
                if (node.isSafeAccess() && isNonNullInScope) {
                    reportWarning(node, "Unnecessary safe-access (?.) operator: variable '" + va.getName() + "' is already non-null in this scope.");
                }
            }

            if (recvType != null && recvType.endsWith("?") && !isNonNullInScope && !node.isSafeAccess()) {
                reportError(node, "Direct member access on nullable value is not allowed. Use safe-call (?.) or null check.");
            }

            if (TypeChecker.isClassType(recvType)) {
                String ownerClass = recvType.substring(1, recvType.length() - 1);
                if (ownerClass.contains("<")) {
                    ownerClass = ownerClass.substring(0, ownerClass.indexOf('<'));
                }
                checkMethodAccess(ownerClass, node.getName(), node);
            }

            String checkOwner = node.getOwner() != null ? node.getOwner() : (TypeChecker.isClassType(recvType) ? recvType.substring(1, recvType.length() - 1) : null);
            if (checkOwner != null && checkOwner.contains("<")) {
                checkOwner = checkOwner.substring(0, checkOwner.indexOf('<'));
            }
            if (isInterfaceType(checkOwner) && isMethodStatic(checkOwner, node.getName(), node.getDescriptor())) {
                reportError(node, "Static method '" + node.getName() + "' in interface '" + checkOwner.replace('/', '.') + "' cannot be invoked on an object reference; invoke it on the interface directly.");
            }
        } else {
            String effectiveOwner = node.getOwner() != null ? node.getOwner() : currentClassFqcn;
            boolean isStaticMethod = isMethodStatic(effectiveOwner, node.getName(), node.getDescriptor());
            if (node.isExplicitClassTarget()) {
                if (!isStaticMethod) {
                    reportError(node, "Non-static method '" + node.getName() + "' cannot be invoked from a static context using class name '" + OceanTypeSystem.findSimpleName(effectiveOwner) + "'.");
                }
            } else if (!isStaticMethod) {
                if (isStaticContext && !currentMethodIsAsync) {
                    reportError(node, "Non-static method '" + node.getName() + "' cannot be referenced from a static context");
                } else if (currentClassFqcn != null && !isOwnerAccessibleViaThis(effectiveOwner)) {
                    reportError(node, "Non-static method '" + node.getName() + "' cannot be invoked without an enclosing instance.");
                }
            }
            resolveStaticImportMethodOwner(node.getName(), node);
            if (node.getOwner() != null && !node.getOwner().equals(currentClassFqcn)) {
                checkMethodAccess(node.getOwner(), node.getName(), node);
            }
        }

        boolean isArrayClone = "clone".equals(node.getName()) && (node.getArguments() == null || node.getArguments().isEmpty())
                && node.getReceiver() != null && node.getReceiver().getTypeDescriptor() != null
                && (node.getReceiver().getTypeDescriptor().startsWith("[") || TypeChecker.isArrayType(node.getReceiver().getTypeDescriptor()));

        if (isArrayClone) {
            String rDesc = TypeChecker.cleanDescriptor(node.getReceiver().getTypeDescriptor());
            node.setTypeDescriptor(rDesc);
        }

        // Unhandled Checked Exception Check for Method Calls
        if (!isArrayClone && node.getOwner() != null && node.getName() != null) {
            String nodeDesc = TypeChecker.cleanDescriptor(node.getDescriptor());
            Map<String, List<String>> ownerThrows = CompilerRegistry.globalMethodThrowsRegistry.get(node.getOwner());
            List<String> thrownExcs = null;
            if (ownerThrows != null) {
                if (nodeDesc != null) {
                    thrownExcs = ownerThrows.get(node.getName() + nodeDesc);
                    if (thrownExcs == null && node.getDescriptor() != null) {
                        thrownExcs = ownerThrows.get(node.getName() + node.getDescriptor());
                    }
                }
                if (thrownExcs == null) {
                    thrownExcs = ownerThrows.get(node.getName());
                }
            }

            if (thrownExcs != null) {
                for (String excDesc : thrownExcs) {
                    recordThrownException(excDesc);
                    if (isCheckedException(excDesc) && !isExceptionHandled(excDesc)) {
                        reportError(node, "Unreported exception '" + TypeChecker.humanReadable(excDesc) + "'; must be caught or declared to be thrown");
                    }
                }
            } else {
                try {
                    Class<?> clazz = OceanTypeSystem.forName(node.getOwner().replace('/', '.'));
                    if (clazz != null) {
                        Method targetMethod = null;
                        for (Method m : clazz.getDeclaredMethods()) {
                            if (m.getName().equals(node.getName())) {
                                if (nodeDesc != null && TypeChecker.cleanDescriptor(org.objectweb.asm.Type.getMethodDescriptor(m)).equals(nodeDesc)) {
                                    targetMethod = m;
                                    break;
                                }
                            }
                        }
                        if (targetMethod == null) {
                            for (Method m : clazz.getMethods()) {
                                if (m.getName().equals(node.getName())) {
                                    if (nodeDesc != null && TypeChecker.cleanDescriptor(org.objectweb.asm.Type.getMethodDescriptor(m)).equals(nodeDesc)) {
                                        targetMethod = m;
                                        break;
                                    }
                                }
                            }
                        }
                        if (targetMethod != null) {
                            for (Class<?> exc : targetMethod.getExceptionTypes()) {
                                String excDesc = OceanTypeSystem.wrapObjectType(exc.getName().replace('.', '/'));
                                recordThrownException(excDesc);
                                if (isCheckedException(excDesc) && !isExceptionHandled(excDesc)) {
                                    reportError(node, "Unreported exception '" + TypeChecker.humanReadable(excDesc) + "'; must be caught or declared to be thrown");
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }

        String receiverType = node.getReceiver() != null ? node.getReceiver().getTypeDescriptor() : null;
        validateCallArguments(node, node.getOwner(), node.getName(), node.getDescriptor(), node.getArguments(), receiverType);
    }

    // R3-15: Direct Instantiation of Abstract / Interface Classes
    @Override
    public void visitNewObject(IRNewObject node) {
        String className = node.getClassName();
        String internalName = className.replace('.', '/');

        //
        String checkType = className.contains("<") ? className : node.getTypeDescriptor();
        if (checkType != null && checkType.contains("<") && checkType.contains(">")) {
            List<String> typeArgs = TypeChecker.extractGenericArguments(checkType);
            for (String arg : typeArgs) {
                String cleanArg = arg.trim();
                if (cleanArg.startsWith("+") || cleanArg.startsWith("-")) cleanArg = cleanArg.substring(1).trim();
                if (cleanArg.startsWith("?") || cleanArg.startsWith("*")) {
                    reportError(node, "Cannot use wildcard type argument in object creation (new): '" + className + "'. Use a concrete type or diamond operator ('<>').");
                    break;
                }
            }
        }

        validateGenericTypeArguments(node.getTypeDescriptor(), node);

        if (className.contains("<")) {
            validateGenericTypeArguments(className, node);
        }

        checkClassAccess(internalName, node);
        checkConstructorAccess(internalName, node);

        boolean isEnum = CompilerRegistry.globalEnumConstants.containsKey(internalName)
                || CompilerRegistry.globalEnumConstants.containsKey(className);
        if (!isEnum) {
            ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(internalName);
            if (sym != null && sym.isEnum()) {
                isEnum = true;
            }
        }
        if (!isEnum) {
            try {
                Class<?> clazz = OceanTypeSystem.forName(internalName.replace('/', '.'));
                if (clazz != null && clazz.isEnum()) {
                    isEnum = true;
                }
            } catch (Throwable ignored) {}
        }

        if (isEnum) {
            reportError(node, "Enum '" + className + "' cannot be instantiated directly with 'new'.");
        } else if (CompilerRegistry.globalAbstractClassSet.contains(internalName)) {
            reportError(node, "Abstract class '" + className + "' cannot be instantiated.");
        }
        else if (isAnnotation(internalName)) {
            reportError(node, "Anotasyon olan '" + className + "' instantiate edilemez.");
        }
        else if (CompilerRegistry.globalIsInterfaceSet.contains(internalName)) {
            reportError(node, "Interface '" + className + "' cannot be instantiated.");
        } else {
            try {
                Class<?> clazz = OceanTypeSystem.forName(internalName.replace('/', '.'));
                if (clazz != null) {
                    if (clazz.isInterface()) {
                        reportError(node, "Interface '" + className + "' cannot be instantiated.");
                    } else if (Modifier.isAbstract(clazz.getModifiers())) {
                        reportError(node, "Abstract class '" + className + "' cannot be instantiated.");
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (!tryBlockThrownExceptions.isEmpty()) {
            try {
                Class<?> clazz = OceanTypeSystem.forName(internalName.replace('/', '.'));
                if (clazz != null) {
                    for (Constructor<?> ctor : clazz.getConstructors()) {
                        for (Class<?> exc : ctor.getExceptionTypes()) {
                            String excDesc = OceanTypeSystem.wrapObjectType(exc.getName().replace('.', '/'));
                            recordThrownException(excDesc);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        validateCallArguments(node, internalName, "<init>", node.getDescriptor(), node.getArguments(), node.getTypeDescriptor());
    }

    private String substituteParamType(String pType, String erasedDesc, Map<String, String> typeParamMap) {
        if (pType == null) return (erasedDesc != null && !erasedDesc.isEmpty()) ? erasedDesc : OceanTypeSystem.OBJECT_DESC;
        pType = pType.trim();
        boolean isNullable = pType.endsWith("?");
        String base = isNullable ? pType.substring(0, pType.length() - 1).trim() : pType;
        if (typeParamMap != null && !typeParamMap.isEmpty()) {
            if (base.contains("<")) {
                String subst = substituteGenericSignature(base, typeParamMap);
                String desc = ensureDescriptor(subst);
                return (isNullable || desc.endsWith("?")) ? (desc.endsWith("?") ? desc : desc + "?") : desc;
            }
            if (typeParamMap.containsKey(base)) {
                String subst = typeParamMap.get(base);
                boolean substNullable = isNullable || subst.endsWith("?");
                String cleanSubst = subst.endsWith("?") ? subst.substring(0, subst.length() - 1) : subst;
                String desc = ensureDescriptor(cleanSubst);
                return substNullable ? (desc.endsWith("?") ? desc : desc + "?") : desc;
            }
        }
        if (erasedDesc != null && !erasedDesc.isEmpty()) {
            return isNullable ? (erasedDesc.endsWith("?") ? erasedDesc : erasedDesc + "?") : erasedDesc;
        }
        if (base.length() == 1 && Character.isUpperCase(base.charAt(0))) {
            return isNullable ? OceanTypeSystem.OBJECT_DESC + "?" : OceanTypeSystem.OBJECT_DESC;
        }
        String desc = ensureDescriptor(base);
        return isNullable ? (desc.endsWith("?") ? desc : desc + "?") : desc;
    }

    private List<CompilerRegistry.MethodParamInfo> toMethodParamInfos(java.lang.reflect.Parameter[] params, Class<?>[] pTypes, java.lang.reflect.Type[] gTypes) {
        List<CompilerRegistry.MethodParamInfo> res = new ArrayList<>();
        for (int i = 0; i < pTypes.length; i++) {
            String rawType;
            if (gTypes != null && i < gTypes.length && gTypes[i] instanceof java.lang.reflect.TypeVariable<?> tv) {
                rawType = tv.getName();
            } else if (gTypes != null && i < gTypes.length && gTypes[i] != null) {
                rawType = gTypes[i].getTypeName();
            } else {
                rawType = pTypes[i].getName();
            }
            String pName = (params != null && i < params.length && params[i].isNamePresent()) ? params[i].getName() : "arg" + i;
            String pDesc = Type.getDescriptor(pTypes[i]);
            if (!pTypes[i].isPrimitive()) {
                if (!pDesc.endsWith("?")) {
                    pDesc = pDesc + "?";
                }
                if (!rawType.endsWith("?")) {
                    rawType = rawType + "?";
                }
            }
            boolean isVarargParam = (params != null && i < params.length && params[i].isVarArgs());
            if (isVarargParam) {
                if (rawType.endsWith("[]?")) {
                    rawType = rawType.substring(0, rawType.length() - 3) + "...";
                } else if (rawType.endsWith("[]")) {
                    rawType = rawType.substring(0, rawType.length() - 2) + "...";
                } else if (!rawType.endsWith("...")) {
                    rawType = rawType + "...";
                }
            }
            res.add(new CompilerRegistry.MethodParamInfo(pName, rawType, pDesc, null));
        }
        return res;
    }

    private <T> T lookupRegistryClassEntry(String cls, Map<String, T> registry) {
        if (cls == null || registry == null || registry.isEmpty()) return null;
        String norm = cls.replace('.', '/');
        T direct = registry.get(norm);
        if (direct != null) return direct;

        String simple = OceanTypeSystem.findSimpleName(norm);
        // 1. Try file package
        String pkg = getFilePackage();
        if (!pkg.isEmpty()) {
            String inPkg = pkg.replace('.', '/') + "/" + simple;
            T pkgEntry = registry.get(inPkg);
            if (pkgEntry != null) return pkgEntry;
        }

        // 2. Try explicit imports
        if (importedClasses != null && importedClasses.containsKey(simple)) {
            String imp = importedClasses.get(simple).replace('.', '/');
            T impEntry = registry.get(imp);
            if (impEntry != null) return impEntry;
        }

        // 3. Fallback scan with candidate collection
        List<Map.Entry<String, T>> matches = new ArrayList<>();
        for (Map.Entry<String, T> entry : registry.entrySet()) {
            String k = entry.getKey();
            if (k.equals(norm) || k.endsWith("/" + simple) || k.endsWith("$" + simple) || k.equals(simple)) {
                matches.add(entry);
            }
        }
        if (matches.size() == 1) {
            return matches.getFirst().getValue();
        } else if (matches.size() > 1) {
            if (!pkg.isEmpty()) {
                String pkgPrefix = pkg.replace('.', '/') + "/";
                for (Map.Entry<String, T> m : matches) {
                    if (m.getKey().startsWith(pkgPrefix)) {
                        return m.getValue();
                    }
                }
            }
            if (importedClasses != null) {
                for (Map.Entry<String, T> m : matches) {
                    if (importedClasses.containsValue(m.getKey().replace('/', '.'))) {
                        return m.getValue();
                    }
                }
            }
            return matches.getFirst().getValue();
        }
        return null;
    }

    private List<CompilerRegistry.MethodParamInfo> findMethodParamInfos(String initialOwner, String callableName, String descriptor, List<IRExpression> args) {
        String cleanDesc = descriptor != null ? TypeChecker.cleanDescriptor(descriptor) : null;
        int argCount = args != null ? args.size() : 0;

        // Collect class hierarchy in order: initialOwner -> superclasses -> interfaces
        List<String> hierarchy = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        if (initialOwner != null && !initialOwner.isEmpty()) {
            queue.add(initialOwner);
        }
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            if (curr == null || curr.isEmpty() || !visited.add(curr)) continue;
            hierarchy.add(curr);

            String sup = CompilerRegistry.globalSuperClassRegistry.get(curr);
            if (sup != null && !sup.equals("java/lang/Object")) {
                if (sup.contains("<")) sup = sup.substring(0, sup.indexOf('<'));
                sup = sup.replace('.', '/');
                if (sup.startsWith("L") && sup.endsWith(";")) sup = sup.substring(1, sup.length() - 1);
                queue.add(sup);
            }

            String[] inters = CompilerRegistry.globalInterfaceRegistry.get(curr);
            if (inters != null) {
                for (String inter : inters) {
                    if (inter != null) {
                        String cleanInter = inter.replace('.', '/');
                        if (cleanInter.startsWith("L") && cleanInter.endsWith(";")) cleanInter = cleanInter.substring(1, cleanInter.length() - 1);
                        queue.add(cleanInter);
                    }
                }
            }
        }

        // PASS 1: Exact / clean descriptor match across hierarchy
        for (String cls : hierarchy) {
            Map<String, List<CompilerRegistry.MethodParamInfo>> classParams = lookupRegistryClassEntry(cls, CompilerRegistry.globalMethodParamsRegistry);
            if (classParams != null) {
                if (descriptor != null) {
                    List<CompilerRegistry.MethodParamInfo> p = classParams.get(callableName + descriptor);
                    if (p != null) return p;
                }
                if (cleanDesc != null) {
                    for (Map.Entry<String, List<CompilerRegistry.MethodParamInfo>> entry : classParams.entrySet()) {
                        String key = entry.getKey();
                        if (key.startsWith(callableName + "(")) {
                            String kDesc = TypeChecker.cleanDescriptor(key.substring(callableName.length()));
                            if (cleanDesc.equals(kDesc)) {
                                return entry.getValue();
                            }
                        }
                    }
                }
            }
            ocean.compiler.symbol.ClassSymbol cs = CompilerRegistry.getClassSymbol(cls);
            if (cs != null) {
                for (ocean.compiler.symbol.MethodSymbol ms : cs.getMethods(callableName)) {
                    if (cleanDesc == null || cleanDesc.equals(TypeChecker.cleanDescriptor(ms.getDescriptor()))) {
                        if (!ms.getParamInfos().isEmpty()) {
                            return ms.getParamInfos();
                        }
                    }
                }
            }
            try {
                Class<?> clazz = OceanTypeSystem.forName(cls.replace('/', '.'));
                if (clazz != null) {
                    if ("<init>".equals(callableName)) {
                        for (Constructor<?> jc : clazz.getConstructors()) {
                            if (cleanDesc != null) {
                                String cDesc = Type.getConstructorDescriptor(jc);
                                if (cleanDesc.equals(TypeChecker.cleanDescriptor(cDesc))) {
                                    return toMethodParamInfos(jc.getParameters(), jc.getParameterTypes(), jc.getGenericParameterTypes());
                                }
                            }
                        }
                    } else {
                        for (Method jm : clazz.getMethods()) {
                            if (jm.getName().equals(callableName)) {
                                if (cleanDesc != null) {
                                    String mDesc = Type.getMethodDescriptor(jm);
                                    if (cleanDesc.equals(TypeChecker.cleanDescriptor(mDesc))) {
                                        return toMethodParamInfos(jm.getParameters(), jm.getParameterTypes(), jm.getGenericParameterTypes());
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // PASS 2: If descriptor didn't match, look for overload with matching argCount & assignable arg types
        List<List<CompilerRegistry.MethodParamInfo>> countCandidates = new ArrayList<>();
        for (String cls : hierarchy) {
            Map<String, List<CompilerRegistry.MethodParamInfo>> classParams = lookupRegistryClassEntry(cls, CompilerRegistry.globalMethodParamsRegistry);
            if (classParams != null) {
                for (Map.Entry<String, List<CompilerRegistry.MethodParamInfo>> entry : classParams.entrySet()) {
                    String key = entry.getKey();
                    if (key.startsWith(callableName + "(") || key.equals(callableName + "#" + argCount)) {
                        List<CompilerRegistry.MethodParamInfo> cand = entry.getValue();
                        if (cand != null && !countCandidates.contains(cand)) {
                            boolean isVarargs = !cand.isEmpty() && (cand.getLast().typeDesc().startsWith("[") || cand.getLast().rawType().endsWith("..."));
                            if (cand.size() == argCount || (isVarargs && argCount >= cand.size() - 1)) {
                                countCandidates.add(cand);
                            }
                        }
                    }
                }
            }
            ocean.compiler.symbol.ClassSymbol cs = CompilerRegistry.getClassSymbol(cls);
            if (cs != null) {
                for (ocean.compiler.symbol.MethodSymbol ms : cs.getMethods(callableName)) {
                    List<CompilerRegistry.MethodParamInfo> cand = ms.getParamInfos();
                    if (cand != null && !cand.isEmpty() && !countCandidates.contains(cand)) {
                        boolean isVarargs = cand.getLast().typeDesc().startsWith("[") || cand.getLast().rawType().endsWith("...");
                        if (cand.size() == argCount || (isVarargs && argCount >= cand.size() - 1)) {
                            countCandidates.add(cand);
                        }
                    }
                }
            }
            try {
                Class<?> clazz = OceanTypeSystem.forName(cls.replace('/', '.'));
                if (clazz != null) {
                    if ("<init>".equals(callableName)) {
                        for (Constructor<?> jc : clazz.getConstructors()) {
                            Class<?>[] pTypes = jc.getParameterTypes();
                            boolean isVarargs = jc.isVarArgs();
                            if (pTypes.length == argCount || (isVarargs && argCount >= pTypes.length - 1)) {
                                countCandidates.add(toMethodParamInfos(jc.getParameters(), jc.getParameterTypes(), jc.getGenericParameterTypes()));
                            }
                        }
                    } else {
                        for (Method jm : clazz.getMethods()) {
                            if (jm.getName().equals(callableName)) {
                                Class<?>[] pTypes = jm.getParameterTypes();
                                boolean isVarargs = jm.isVarArgs();
                                if (pTypes.length == argCount || (isVarargs && argCount >= pTypes.length - 1)) {
                                    countCandidates.add(toMethodParamInfos(jm.getParameters(), jm.getParameterTypes(), jm.getGenericParameterTypes()));
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (countCandidates.size() == 1) {
            return countCandidates.getFirst();
        }

        // Multiple candidates with matching count: find the candidate whose parameters match arg types
        for (List<CompilerRegistry.MethodParamInfo> cand : countCandidates) {
            boolean allMatch = true;
            boolean isVarargs = !cand.isEmpty() && (cand.getLast().typeDesc().startsWith("[") || cand.getLast().rawType().endsWith("..."));
            int normalCount = isVarargs ? cand.size() - 1 : cand.size();
            for (int i = 0; i < argCount; i++) {
                IRExpression arg = args.get(i);
                if (arg == null || arg.getTypeDescriptor() == null) continue;
                String argType = TypeChecker.cleanDescriptor(arg.getTypeDescriptor());
                String paramType;
                if (i < normalCount) {
                    paramType = TypeChecker.cleanDescriptor(cand.get(i).typeDesc());
                } else if (isVarargs) {
                    String vDesc = cand.getLast().typeDesc();
                    if (argCount == cand.size() && TypeChecker.isAssignable(vDesc, argType, session)) {
                        paramType = TypeChecker.cleanDescriptor(vDesc);
                    } else {
                        paramType = TypeChecker.cleanDescriptor(vDesc.startsWith("[") ? vDesc.substring(1) : vDesc);
                    }
                } else {
                    allMatch = false;
                    break;
                }
                if (!TypeChecker.isAssignable(paramType, argType, session)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return cand;
            }
        }

        if (!countCandidates.isEmpty()) {
            return countCandidates.getFirst();
        }

        return null;
    }

    private Set<String> findMethodTypeParameters(String initialOwner, String callableName, String descriptor) {
        if (callableName == null || callableName.isEmpty()) return Collections.emptySet();
        String cleanDesc = descriptor != null ? TypeChecker.cleanDescriptor(descriptor) : null;

        List<String> hierarchy = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        if (initialOwner != null && !initialOwner.isEmpty()) {
            queue.add(initialOwner);
        }
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            if (curr == null || curr.isEmpty() || !visited.add(curr)) continue;
            hierarchy.add(curr);

            String sup = CompilerRegistry.globalSuperClassRegistry.get(curr);
            if (sup != null && !sup.equals("java/lang/Object")) {
                if (sup.contains("<")) sup = sup.substring(0, sup.indexOf('<'));
                sup = sup.replace('.', '/');
                if (sup.startsWith("L") && sup.endsWith(";")) sup = sup.substring(1, sup.length() - 1);
                queue.add(sup);
            }

            String[] inters = CompilerRegistry.globalInterfaceRegistry.get(curr);
            if (inters != null) {
                for (String inter : inters) {
                    if (inter != null) {
                        String cleanInter = inter.replace('.', '/');
                        if (cleanInter.startsWith("L") && cleanInter.endsWith(";")) cleanInter = cleanInter.substring(1, cleanInter.length() - 1);
                        queue.add(cleanInter);
                    }
                }
            }
        }

        for (String cls : hierarchy) {
            Map<String, List<String>> m = lookupRegistryClassEntry(cls, CompilerRegistry.globalMethodTypeParametersRegistry);
            if (m != null) {
                if (descriptor != null && m.containsKey(callableName + descriptor)) {
                    return new HashSet<>(m.get(callableName + descriptor));
                }
                if (m.containsKey(callableName)) {
                    return new HashSet<>(m.get(callableName));
                }
            }

            ocean.compiler.symbol.ClassSymbol cs = CompilerRegistry.getClassSymbol(cls);
            if (cs != null) {
                for (ocean.compiler.symbol.MethodSymbol ms : cs.getMethods(callableName)) {
                    if (cleanDesc == null || cleanDesc.equals(TypeChecker.cleanDescriptor(ms.getDescriptor()))) {
                        if (!ms.getTypeParameters().isEmpty()) {
                            return new HashSet<>(ms.getTypeParameters());
                        }
                    }
                }
                for (ocean.compiler.symbol.MethodSymbol ms : cs.getMethods(callableName)) {
                    if (!ms.getTypeParameters().isEmpty()) {
                        return new HashSet<>(ms.getTypeParameters());
                    }
                }
            }

            try {
                Class<?> clazz = OceanTypeSystem.forName(cls.replace('/', '.'));
                if (clazz != null) {
                    for (java.lang.reflect.Method jm : clazz.getMethods()) {
                        if (jm.getName().equals(callableName)) {
                            java.lang.reflect.TypeVariable<?>[] tps = jm.getTypeParameters();
                            if (tps.length > 0) {
                                Set<String> res = new HashSet<>();
                                for (java.lang.reflect.TypeVariable<?> tp : tps) {
                                    res.add(tp.getName());
                                }
                                return res;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        return Collections.emptySet();
    }

    private void validateCallArguments(IRNode node, String owner, String callableName, String descriptor, List<IRExpression> args, String receiverOrDeclaredType) {
        if (args == null) return;
        for (IRExpression arg : args) {
            arg.accept(this);
            if (arg.getTypeDescriptor() != null && "V".equals(TypeChecker.cleanDescriptor(arg.getTypeDescriptor()))) {
                reportError(node, "'void' expression cannot be passed as a method argument.");
            }
        }

        String cleanOwner = TypeChecker.cleanDescriptor(owner != null ? owner : "");
        if (cleanOwner.startsWith("L") && cleanOwner.endsWith(";")) {
            cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
        }
        List<CompilerRegistry.MethodParamInfo> pInfos = findMethodParamInfos(cleanOwner, callableName, descriptor, args);
        if (pInfos == null || pInfos.isEmpty()) {
            return;
        }

        Map<String, String> typeParamMap = new HashMap<>(buildSamTypeParameterMapping(receiverOrDeclaredType));
        Set<String> methodTypeParams = findMethodTypeParameters(cleanOwner, callableName, descriptor);
        if (!methodTypeParams.isEmpty()) {
            for (String mtp : methodTypeParams) {
                typeParamMap.remove(mtp);
            }
        }

        boolean isVarargs = !pInfos.isEmpty() && (pInfos.getLast().typeDesc().startsWith("[") || pInfos.getLast().rawType().endsWith("..."));
        int normalCount = isVarargs ? pInfos.size() - 1 : pInfos.size();

        for (int i = 0; i < args.size(); i++) {
            IRExpression arg = args.get(i);
            if (arg == null) continue;
            String argType = arg.getTypeDescriptor();
            if (argType == null) continue;

            String rawParamType;
            String erasedTypeDesc;
            if (i < normalCount) {
                rawParamType = pInfos.get(i).rawType();
                erasedTypeDesc = pInfos.get(i).typeDesc();
            } else if (isVarargs) {
                rawParamType = pInfos.getLast().rawType();
                erasedTypeDesc = pInfos.getLast().typeDesc();
                String varargArrayDesc = erasedTypeDesc;

                // Check if arg was wrapped into an IRArrayLiteral by IRGenerator, but its single element is ALREADY an array compatible with varargArrayDesc!
                if (arg instanceof IRArrayLiteral arrayLit && arrayLit.getElements().size() == 1) {
                    IRExpression singleElem = arrayLit.getElements().getFirst();
                    singleElem.accept(this);
                    String singleElemType = singleElem.getTypeDescriptor();
                    if (singleElemType != null) {
                        String normSingle = singleElemType.endsWith("[]") ? ensureDescriptor(singleElemType) : singleElemType;
                        if (normSingle.startsWith("[") && TypeChecker.isAssignable(varargArrayDesc, normSingle, session)) {
                            // Unwrap: replace arrayLit with the direct array
                            args.set(i, singleElem);
                            arg = singleElem;
                            argType = normSingle;
                        }
                    }
                }

                boolean isDirectArray = args.size() == pInfos.size() && ("null".equals(argType) && !TypeChecker.isPrimitive(varargArrayDesc) || TypeChecker.isAssignable(varargArrayDesc, argType, session));

                if (isDirectArray) {
                    if (rawParamType.endsWith("...")) {
                        rawParamType = rawParamType.substring(0, rawParamType.length() - 3).trim() + "[]";
                    }
                } else {
                    if (rawParamType.endsWith("...")) {
                        rawParamType = rawParamType.substring(0, rawParamType.length() - 3).trim();
                    } else if (rawParamType.endsWith("[]?")) {
                        rawParamType = rawParamType.substring(0, rawParamType.length() - 3).trim() + "?";
                    } else if (rawParamType.endsWith("[]")) {
                        rawParamType = rawParamType.substring(0, rawParamType.length() - 2).trim();
                    } else if (rawParamType.startsWith("[")) {
                        rawParamType = rawParamType.substring(1);
                    }
                    if (erasedTypeDesc != null && erasedTypeDesc.startsWith("[")) {
                        erasedTypeDesc = erasedTypeDesc.substring(1);
                    }
                }
            } else {
                break;
            }

            String effectiveParamType = substituteParamType(rawParamType, erasedTypeDesc, typeParamMap);

            boolean isNullLiteral = (arg instanceof IRLiteral lit && lit.getValue() == null) || "null".equals(argType);
            boolean isNullableArg = isNullLiteral || TypeChecker.isNullable(argType);
            boolean isParamNullable = TypeChecker.isNullable(effectiveParamType);

            String callContext = callableName.equals("<init>") ? " (constructor: " + OceanTypeSystem.findSimpleName(cleanOwner) + ")" : " (method: " + callableName + ")";

            //
            String cleanParam = effectiveParamType != null ? (effectiveParamType.endsWith("?") ? effectiveParamType.substring(0, effectiveParamType.length() - 1).trim() : effectiveParamType.trim()) : "";
            if (cleanParam.equals("*") || cleanParam.startsWith("+")) {
                if (!isNullLiteral) {
                    String wildcardDesc = cleanParam.equals("*") ? "?" : "? extends " + TypeChecker.humanReadable(cleanParam.substring(1));
                    reportError(node, "Cannot pass argument other than 'null' to generic receiver parameterized with wildcard ('" + wildcardDesc + "')" + callContext + ".");
                }
                continue;
            } else if (cleanParam.startsWith("-")) {
                String lowerBound = cleanParam.substring(1);
                if (!isNullLiteral) {
                    boolean compatible = isPrimitiveConstantLiteralFor(lowerBound, arg) || TypeChecker.isAssignable(lowerBound, argType, session);
                    if (!compatible) {
                        reportError(node, "Incompatible types: '" + TypeChecker.humanReadable(argType) + "' cannot be converted to lower bound '? super " + TypeChecker.humanReadable(lowerBound) + "'" + callContext + ".");
                    }
                }
                continue;
            }

            if (!isParamNullable) {
                if (isNullLiteral) {
                    reportError(node, "Incompatible types: 'null' cannot be passed to non-nullable parameter '" + TypeChecker.humanReadable(effectiveParamType) + "'" + callContext + ".");
                    continue;
                } else if (isNullableArg) {
                    reportError(node, "Incompatible types: nullable '" + TypeChecker.humanReadable(argType) + "' cannot be passed to non-nullable parameter '" + TypeChecker.humanReadable(effectiveParamType) + "'. Use safe-call (?.) or null check" + callContext + ".");
                    continue;
                }
            }

            if (!isNullLiteral) {
                boolean compatible;
                if (isPrimitiveConstantLiteralFor(effectiveParamType, arg)) {
                    compatible = true;
                } else {
                    compatible = TypeChecker.isAssignable(effectiveParamType, argType, session);
                }
                if (!compatible) {
                    reportError(node, "Incompatible types: '" + TypeChecker.humanReadable(argType) + "' cannot be converted to '" + TypeChecker.humanReadable(effectiveParamType) + "'" + callContext + ".");
                }
            }
        }
    }

    // R3-16 & R3-17: Void Return & Non-Void Missing Return Error Message Alignment
    @Override
    public void visitReturn(IRReturnStatement node) {
        if (finallyDepth > 0) {
            reportError(node, "'return' statement cannot be used inside finally block (abrupt completion)");
        }
        if (insideStaticInitializer) {
            reportError(node, "'return' statement cannot be used inside static initializer block.");
            return;
        }
        if (insideInstanceInitializer) {
            reportError(node, "'return' statement cannot be used inside instance initializer block.");
            return;
        }
        if ("<init>".equals(currentMethodName) && node.getExpression() != null) {

            /*reportError(node, "Cannot return a value from constructor or void method.");
            return;*/
        }
        if (node.getExpression() != null) {
            node.getExpression().accept(this);
            String retType = node.getExpression().getTypeDescriptor();
            if ("V".equals(currentMethodReturnDesc) || "void".equals(currentMethodReturnDesc)) {
                reportError(node, "Cannot return a value from a method with void return type");
            } else if (retType != null && currentMethodReturnDesc != null && !isPrimitiveConstantLiteralFor(currentMethodReturnDesc, node.getExpression()) && !TypeChecker.isAssignable(currentMethodReturnDesc, retType, session)) {
                reportError(node, "Incompatible return type: expected '" + TypeChecker.humanReadable(currentMethodReturnDesc) + "', found '" + TypeChecker.humanReadable(retType) + "'.");
            }
        } else {
            if (currentMethodReturnDesc != null && !"V".equals(currentMethodReturnDesc) && !"void".equals(currentMethodReturnDesc)) {
                reportError(node, "Missing return statement: non-void method must return a value");
            }
        }
    }

    // R3-9: Throw Throwable Type Check
    @Override
    public void visitThrow(IRThrowStatement node) {
        if (node.getExpression() != null) {
            node.getExpression().accept(this);
            String rawThrown = node.getExpression().getTypeDescriptor();
            String thrownType = rawThrown != null ? SymbolTable.getDescriptor(rawThrown, importedClasses, null) : null;
            if (thrownType != null && !thrownType.startsWith("L") && !thrownType.startsWith("[")) {
                thrownType = OceanTypeSystem.wrapObjectType(thrownType);
            }
            if (thrownType != null && !TypeChecker.isAssignable("Ljava/lang/Throwable;", thrownType, session)) {
                reportError(node, "Expression in throw statement must be of type java.lang.Throwable, found '" + TypeChecker.humanReadable(rawThrown) + "'");
            } else if (isCheckedException(thrownType)) {
                recordThrownException(thrownType);
                if (!isExceptionHandled(thrownType)) {
                    reportError(node, "Unreported exception '" + TypeChecker.humanReadable(rawThrown) + "'; must be caught or declared to be thrown");
                }
            }
        }
    }

    // P3-3 & R3-10: Try-With-Resources AutoCloseable Check & Catch Throwable Type Check
    @Override
    public void visitTryCatch(IRTryCatchStatement node) {
        if (node.getCatchClauses().isEmpty() && node.getFinallyBlock() == null && (node.getResourceNames() == null || node.getResourceNames().isEmpty())) {
            reportError(node, "'trying' statement must have at least one 'catch' or 'finally' block, or resource declaration.");
        }

        if (node.getResourceNames() != null) {
            for (String resName : node.getResourceNames()) {
                if (resName != null && !resName.startsWith("$_unused_res_")) {
                    if (!usedAsResourceInMethod.contains(resName)) {
                        if (symbolTable.isMutated(resName)) {
                            reportError(node, "Resource '" + resName + "' in try-with-resources statement must be final or effectively final.");
                        }
                        usedAsResourceInMethod.add(resName);
                    }
                }
            }
        }

        List<String> tryCatchTypes = new ArrayList<>();
        for (IRTryCatchStatement.IRCatchClause catchClause : node.getCatchClauses()) {
            for (String excType : catchClause.exceptionTypes()) {
                if (excType != null) {
                    String resolved = SymbolTable.getDescriptor(excType, importedClasses, null);
                    if (!resolved.startsWith("L") && !resolved.startsWith("[")) {
                        resolved = OceanTypeSystem.wrapObjectType(resolved);
                    }
                    tryCatchTypes.add(resolved);
                }
            }
        }
        Set<String> tryThrown = new HashSet<>();
        tryBlockThrownExceptions.push(tryThrown);
        caughtExceptionsStack.push(tryCatchTypes);

        Map<String, Boolean> tryInitSnapshot = null;
        Map<String, Boolean> tryNullSnapshot = null;
        Set<String> daTry = null;
        Set<String> paTry = null;
        Set<String> daStaticTry = null;
        Set<String> paStaticTry = null;

        try {
            if (node.getTryBlock() != null) {
                node.getTryBlock().accept(this);
                tryInitSnapshot = lastBlockInitSnapshot != null ? lastBlockInitSnapshot : symbolTable.getInitializationSnapshot();
                tryNullSnapshot = lastBlockNullabilitySnapshot != null ? lastBlockNullabilitySnapshot : getNullabilitySnapshot();
                daTry = new HashSet<>(definitelyAssignedBlankFinalFields);
                paTry = new HashSet<>(potentiallyAssignedBlankFinalFields);
                daStaticTry = new HashSet<>(definitelyAssignedBlankStaticFinalFields);
                paStaticTry = new HashSet<>(potentiallyAssignedBlankStaticFinalFields);
            }
        } finally {
            caughtExceptionsStack.pop();
            tryBlockThrownExceptions.pop();
        }

        // Warning: Checked exceptions caught but never thrown in try block
        for (IRTryCatchStatement.IRCatchClause catchClause : node.getCatchClauses()) {
            for (String excType : catchClause.exceptionTypes()) {
                if (excType == null) continue;
                String resolvedExc = SymbolTable.getDescriptor(excType, importedClasses, null);
                if (!resolvedExc.startsWith("L") && !resolvedExc.startsWith("[")) {
                    resolvedExc = OceanTypeSystem.wrapObjectType(resolvedExc);
                }
                String cleanExc = TypeChecker.cleanDescriptor(resolvedExc);
                String fqcn = cleanExc.startsWith("L") && cleanExc.endsWith(";") ? cleanExc.substring(1, cleanExc.length() - 1) : cleanExc;
                if ("java/lang/Exception".equals(fqcn) || "java/lang/Throwable".equals(fqcn) || "Exception".equals(fqcn) || "Throwable".equals(fqcn)) {
                    continue;
                }
                if (isCheckedException(cleanExc)) {
                    boolean canBeThrown = false;
                    for (String thrown : tryThrown) {
                        String cleanThrown = TypeChecker.cleanDescriptor(thrown);
                        if (TypeChecker.isAssignable(cleanExc, cleanThrown, session) || TypeChecker.isAssignable(cleanThrown, cleanExc, session)) {
                            canBeThrown = true;
                            break;
                        }
                    }
                    if (!canBeThrown) {
                        reportWarning(node, "Catch block for checked exception '" + TypeChecker.humanReadable(excType) + "' is never thrown in the corresponding try block.");
                    }
                }
            }
        }

        List<String> allPriorCatchTypes = new ArrayList<>();
        for (IRTryCatchStatement.IRCatchClause catchClause : node.getCatchClauses()) {
            symbolTable.enterScope();
            try {
                for (String excType : catchClause.exceptionTypes()) {
                    if (excType != null && !TypeChecker.isAssignable("Ljava/lang/Throwable;", excType, session)) {
                        reportError(node, "Catch clause type '" + TypeChecker.humanReadable(excType) + "' must extend java.lang.Throwable");
                    }
                }

                // Subtype redundancy and duplicate check in same multi-catch clause
                if (catchClause.exceptionTypes().size() > 1) {
                    List<String> types = catchClause.exceptionTypes();
                    for (int i = 0; i < types.size(); i++) {
                        String t1 = types.get(i);
                        String cleant1 = TypeChecker.cleanDescriptor(t1);
                        if (cleant1.startsWith("L") && cleant1.endsWith(";")) cleant1 = cleant1.substring(1, cleant1.length() - 1);
                        for (int j = 0; j < types.size(); j++) {
                            if (i == j) continue;
                            String t2 = types.get(j);
                            String cleant2 = TypeChecker.cleanDescriptor(t2);
                            if (cleant2.startsWith("L") && cleant2.endsWith(";")) cleant2 = cleant2.substring(1, cleant2.length() - 1);
                            if (cleant1.equals(cleant2)) {
                                if (i < j) {
                                    reportError(node, "Duplicate exception type in catch clause: '" + TypeChecker.humanReadable(cleant1) + "'");
                                }
                            } else if (TypeChecker.isAssignable("L" + cleant2 + ";", "L" + cleant1 + ";", session) || OceanTypeSystem.isSubtypeOfReflection(cleant1, cleant2)) {
                                reportError(node, "Exception '" + TypeChecker.humanReadable(cleant1) + "' is already caught by alternative '" + TypeChecker.humanReadable(cleant2) + "'");
                            }
                        }
                    }
                }

                // Across multiple catch clauses: unreachable catch check
                for (String excType : catchClause.exceptionTypes()) {
                    String resolvedExc = SymbolTable.getDescriptor(excType, importedClasses, null);
                    if (resolvedExc != null && !resolvedExc.startsWith("L") && !resolvedExc.startsWith("[")) {
                        resolvedExc = OceanTypeSystem.wrapObjectType(resolvedExc);
                    }
                    for (String priorType : allPriorCatchTypes) {
                        String resolvedPrior = SymbolTable.getDescriptor(priorType, importedClasses, null);
                        if (resolvedPrior != null && !resolvedPrior.startsWith("L") && !resolvedPrior.startsWith("[")) {
                            resolvedPrior = OceanTypeSystem.wrapObjectType(resolvedPrior);
                        }
                        if (TypeChecker.isAssignable(resolvedPrior, resolvedExc, session)) {
                            reportError(node, "Exception '" + TypeChecker.humanReadable(excType) + "' has already been caught by prior catch clause '" + TypeChecker.humanReadable(priorType) + "'");
                            break;
                        }
                    }
                }
                allPriorCatchTypes.addAll(catchClause.exceptionTypes());

                String declType = !catchClause.exceptionTypes().isEmpty() ? 
                        (catchClause.exceptionTypes().size() == 1 ? catchClause.exceptionTypes().getFirst()
                                : "L" + TypeChecker.getCommonSuperClass(catchClause.exceptionTypes()) + ";")
                        : "Ljava/lang/Throwable;";
                boolean isMultiCatch = catchClause.exceptionTypes().size() > 1;
                if (!"_".equals(catchClause.exceptionVar()) && currentMethodBaseScopeDepth > 0 && symbolTable.isDeclaredInMethodScope(catchClause.exceptionVar(), currentMethodBaseScopeDepth)) {
                    reportError(node, "Catch variable '" + catchClause.exceptionVar() + "' is already defined in this method scope.");
                }
                symbolTable.declareParameter(catchClause.exceptionVar(), declType, isMultiCatch);
                if (catchClause.body() != null) {
                    catchClause.body().accept(this);
                }
            } finally {
                symbolTable.exitScope();
            }
        }

        Map<String, Boolean> finallyInitSnapshot = null;
        Set<String> daFinally = null;
        Set<String> paFinally = null;
        Set<String> daStaticFinally = null;
        Set<String> paStaticFinally = null;

        if (node.getFinallyBlock() != null) {
            finallyDepth++;
            try {
                node.getFinallyBlock().accept(this);
                finallyInitSnapshot = lastBlockInitSnapshot != null ? lastBlockInitSnapshot : symbolTable.getInitializationSnapshot();
                daFinally = new HashSet<>(definitelyAssignedBlankFinalFields);
                paFinally = new HashSet<>(potentiallyAssignedBlankFinalFields);
                daStaticFinally = new HashSet<>(definitelyAssignedBlankStaticFinalFields);
                paStaticFinally = new HashSet<>(potentiallyAssignedBlankStaticFinalFields);
            } finally {
                finallyDepth--;
            }
        }

        boolean allCatchesTerminate = true;
        for (IRTryCatchStatement.IRCatchClause catchClause : node.getCatchClauses()) {
            if (catchClause.body() == null || !isStatementTerminating(catchClause.body())) {
                allCatchesTerminate = false;
                break;
            }
        }

        boolean finallyTerminates = node.getFinallyBlock() != null && isStatementTerminating(node.getFinallyBlock());

        if (allCatchesTerminate && !finallyTerminates) {
            if (tryInitSnapshot != null) {
                symbolTable.restoreInitializationSnapshot(tryInitSnapshot);
            }
            if (tryNullSnapshot != null) {
                applyNullabilitySnapshot(tryNullSnapshot);
            }
            if (daTry != null) {
                definitelyAssignedBlankFinalFields.clear();
                definitelyAssignedBlankFinalFields.addAll(daTry);
            }
            if (paTry != null) {
                potentiallyAssignedBlankFinalFields.clear();
                potentiallyAssignedBlankFinalFields.addAll(paTry);
            }
            if (daStaticTry != null) {
                definitelyAssignedBlankStaticFinalFields.clear();
                definitelyAssignedBlankStaticFinalFields.addAll(daStaticTry);
            }
            if (paStaticTry != null) {
                potentiallyAssignedBlankStaticFinalFields.clear();
                potentiallyAssignedBlankStaticFinalFields.addAll(paStaticTry);
            }
        }

        //
        if (finallyInitSnapshot != null) {
            for (Map.Entry<String, Boolean> entry : finallyInitSnapshot.entrySet()) {
                if (Boolean.TRUE.equals(entry.getValue())) {
                    symbolTable.markInitialized(entry.getKey());
                }
            }
        }
        if (daFinally != null) {
            definitelyAssignedBlankFinalFields.addAll(daFinally);
        }
        if (paFinally != null) {
            potentiallyAssignedBlankFinalFields.addAll(paFinally);
        }
        if (daStaticFinally != null) {
            definitelyAssignedBlankStaticFinalFields.addAll(daStaticFinally);
        }
        if (paStaticFinally != null) {
            potentiallyAssignedBlankStaticFinalFields.addAll(paStaticFinally);
        }
    }

    // R3-11: Lock Expression Reference Type Check
    @Override
    public void visitLockStatement(IRLockStatement node) {
        if (node.getLockExpression() != null) {
            node.getLockExpression().accept(this);
            String lockType = node.getLockExpression().getTypeDescriptor();
            if (TypeChecker.isPrimitive(lockType)) {
                reportError(node, "Lock expression must be a reference type, found '" + TypeChecker.humanReadable(lockType) + "'");
            } else if ("null".equals(lockType) || (node.getLockExpression() instanceof IRLiteral lit && lit.getValue() == null)) {
                reportError(node, "Lock expression cannot be 'null'.");
            }
        }
        if (node.getBody() != null) {
            node.getBody().accept(this);
        }
    }

    private String extractCaseValueKey(IRExpression val,IRNode node) {
        if (val == null) return null;
        Long constInt = evaluateConstantInt(val,node);
        if (constInt != null) {
            return String.valueOf(constInt);
        }
        if (val instanceof IRLiteral lit) {
            return String.valueOf(lit.getValue());
        }
        if (val instanceof IRVariableAccess va) {
            if (va.getReceiver() != null && va.getReceiver() instanceof IRVariableAccess recv) {
                return recv.getName() + "." + va.getName();
            }
            return va.getName();
        }
        return null;
    }

    @Override
    public void visitSwitch(IRSwitchStatement node) {
        switchDepth++;
        String targetType = (node.getExpression() != null) ? node.getExpression().getTypeDescriptor() : null;
        if (targetType == null && node.getExpression() instanceof IRVariableAccess va) {
            targetType = symbolTable.getType(va.getName());
            if (targetType != null) va.setTypeDescriptor(targetType);
        }
        if (targetType != null && (TypeChecker.isEnumType(targetType) || !ClassMetadataCache.getEnumConstants(TypeChecker.cleanDescriptor(targetType)).isEmpty())) {
            activeEnumSwitchTypes.push(TypeChecker.cleanDescriptor(targetType));
        } else {
            activeEnumSwitchTypes.push("");
        }

        try {
            //
            boolean hasArrow = false;
            boolean hasColon = false;
            if (node.getCases() != null) {
                for (IRSwitchCase c : node.getCases()) {
                    if (c.isArrow()) hasArrow = true;
                    else hasColon = true;
                }
            }
            if (node.getDefaultBlock() != null) {
                if (node.isDefaultArrow()) hasArrow = true;
                else hasColon = true;
            }
            if (hasArrow && hasColon) {
                reportError(node, "Different case kinds (arrow '->' and colon ':') cannot be mixed in switch.");
            }

            if (node.getExpression() != null) node.getExpression().accept(this);
            if (targetType == null && node.getExpression() != null) {
                targetType = node.getExpression().getTypeDescriptor();
            }
            if (targetType != null && !activeEnumSwitchTypes.isEmpty() && activeEnumSwitchTypes.peek().isEmpty()) {
                String cleanTarget = TypeChecker.cleanDescriptor(targetType);
                if (TypeChecker.isEnumType(targetType) || !ClassMetadataCache.getEnumConstants(cleanTarget).isEmpty()) {
                    activeEnumSwitchTypes.pop();
                    activeEnumSwitchTypes.push(cleanTarget);
                }
            }
            if (targetType != null) {
                String cleanTarget = TypeChecker.cleanDescriptor(targetType);
                boolean hasPatterns = node.getCases() != null && node.getCases().stream().anyMatch(IRSwitchCase::hasPattern);
                if (!hasPatterns && ("J".equals(cleanTarget) || "F".equals(cleanTarget) || "D".equals(cleanTarget)
                        || "long".equals(cleanTarget) || "float".equals(cleanTarget) || "double".equals(cleanTarget)
                        || "Ljava/lang/Long;".equals(cleanTarget) || "Ljava/lang/Float;".equals(cleanTarget) || "Ljava/lang/Double;".equals(cleanTarget)
                        || "java/lang/Long".equals(cleanTarget) || "java/lang/Float".equals(cleanTarget) || "java/lang/Double".equals(cleanTarget))) {
                    reportError(node, "Selector expression in switch cannot be of type 'long', 'float', or 'double': '" + TypeChecker.humanReadable(cleanTarget) + "'");
                }
            }
            validateSwitchNullRules(node, targetType, node.getCases());
            checkPatternDominance(node, node.getCases(), targetType, node.getDefaultBlock() != null);
            Set<String> seenCases = new HashSet<>();
            for (IRSwitchCase c : node.getCases()) {
                symbolTable.enterScope();
                try {
                    if (c.getPatterns() != null) {
                        for (IRSwitchPattern p : c.getPatterns()) {
                            declarePatternVariables(p);
                            if (p.getKind() == IRSwitchPattern.Kind.RECORD && TypeChecker.isPrimitive(targetType)) {
                                reportError(node, "Record pattern cannot be applied to primitive selector type '" + TypeChecker.humanReadable(targetType) + "'.");
                            }
                        }
                    }
                    if (c.getGuard() != null) c.getGuard().accept(this);
                    if (!c.hasPattern()) {
                        for (IRExpression val : c.getValues()) {
                            val.accept(this);
                            validateSwitchCaseConstant(node, targetType, val);
                            String caseVal = extractCaseValueKey(val,node);
                            if (caseVal != null) {
                                if (!seenCases.add(caseVal)) {
                                    reportError(node, "Duplicate case label: '" + caseVal + "'");
                                }
                            }
                        }
                    } else {
                        for (IRExpression val : c.getValues()) {
                            val.accept(this);
                            validateSwitchCaseConstant(node, targetType, val);
                        }
                    }
                    if (c.getBody() != null) {
                        controlTargetStack.push(c.isArrow() ? ControlTarget.ARROW_SWITCH : ControlTarget.COLON_SWITCH);
                        try {
                            c.getBody().accept(this);
                        } finally {
                            controlTargetStack.pop();
                        }
                    }
                } finally {
                    symbolTable.exitScope();
                }
            }
            if (node.getDefaultBlock() != null) {
                controlTargetStack.push(node.isDefaultArrow() ? ControlTarget.ARROW_SWITCH : ControlTarget.COLON_SWITCH);
                try {
                    node.getDefaultBlock().accept(this);
                } finally {
                    controlTargetStack.pop();
                }
            }

            // If it's a pattern switch statement or over sealed/enum hierarchy and no default:
            String cleanType = targetType != null ? TypeChecker.cleanDescriptor(targetType) : null;
            boolean isSealedTarget = ClassMetadataCache.isSealed(cleanType);
            if (node.getDefaultBlock() == null && isSealedTarget) {
                checkSwitchExhaustiveness(node, node.getExpression(), null, node.getCases(), null);
            }
        } finally {
            activeEnumSwitchTypes.pop();
            switchDepth--;
        }
    }

    // R2-5: Switch Expression Block result Path Exhaustiveness
    private boolean blockHasResult(IRBlock block) {
        if (block == null || block.getStatements().isEmpty()) return false;
        for (IRStatement stmt : block.getStatements()) {
            if (stmt instanceof IRResultStatement || stmt instanceof IRThrowStatement) return true;
            if (stmt instanceof IRBlock subBlock && blockHasResult(subBlock)) return true;
            if (stmt instanceof IRIfStatement ifStmt) {
                boolean thenHas = ifStmt.getThenBranch() instanceof IRBlock b1 ? blockHasResult(b1) : (ifStmt.getThenBranch() instanceof IRResultStatement);
                boolean elseHas = ifStmt.getElseBranch() instanceof IRBlock b2 ? blockHasResult(b2) : (ifStmt.getElseBranch() instanceof IRResultStatement);
                if (thenHas && elseHas) return true;
            }
        }
        return false;
    }

    private void collectSwitchResultExpressions(IRNode body, List<IRExpression> resultExprs) {
        switch (body) {
            case IRExpression expr -> resultExprs.add(expr);
            case IRBlock block -> collectResultsFromBlock(block, resultExprs);
            case null, default -> {
            }
        }
    }

    private void collectResultsFromBlock(IRBlock block, List<IRExpression> resultExprs) {
        if (block == null) return;
        for (IRStatement stmt : block.getStatements()) {
            if (stmt instanceof IRResultStatement rs) {
                if (rs.getExpression() != null) {
                    resultExprs.add(rs.getExpression());
                }
            } else if (stmt instanceof IRBlock subBlock) {
                collectResultsFromBlock(subBlock, resultExprs);
            } else if (stmt instanceof IRIfStatement ifStmt) {
                if (ifStmt.getThenBranch() instanceof IRBlock thenBlock) {
                    collectResultsFromBlock(thenBlock, resultExprs);
                } else if (ifStmt.getThenBranch() instanceof IRResultStatement thenRs) {
                    if (thenRs.getExpression() != null) resultExprs.add(thenRs.getExpression());
                }
                if (ifStmt.getElseBranch() instanceof IRBlock elseBlock) {
                    collectResultsFromBlock(elseBlock, resultExprs);
                } else if (ifStmt.getElseBranch() instanceof IRResultStatement elseRs) {
                    if (elseRs.getExpression() != null) resultExprs.add(elseRs.getExpression());
                }
            }
        }
    }

    @Override
    public void visitSwitchExpression(IRSwitchExpression node) {
        switchExpressionDepth++;
        String targetType = (node.getExpression() != null) ? node.getExpression().getTypeDescriptor() : null;
        if (targetType == null && node.getExpression() instanceof IRVariableAccess va) {
            targetType = symbolTable.getType(va.getName());
            if (targetType != null) va.setTypeDescriptor(targetType);
        }
        if (targetType != null && (TypeChecker.isEnumType(targetType) || !ClassMetadataCache.getEnumConstants(TypeChecker.cleanDescriptor(targetType)).isEmpty())) {
            activeEnumSwitchTypes.push(TypeChecker.cleanDescriptor(targetType));
        } else {
            activeEnumSwitchTypes.push("");
        }

        try {
            if (node.getExpression() != null) node.getExpression().accept(this);
            if (targetType == null && node.getExpression() != null) {
                targetType = node.getExpression().getTypeDescriptor();
            }
            if (targetType != null && !activeEnumSwitchTypes.isEmpty() && activeEnumSwitchTypes.peek().isEmpty()) {
                String cleanTarget = TypeChecker.cleanDescriptor(targetType);
                if (TypeChecker.isEnumType(targetType) || !ClassMetadataCache.getEnumConstants(cleanTarget).isEmpty()) {
                    activeEnumSwitchTypes.pop();
                    activeEnumSwitchTypes.push(cleanTarget);
                }
            }
            if (targetType != null) {
                String cleanTarget = TypeChecker.cleanDescriptor(targetType);
                boolean hasPatterns = node.getCases() != null && node.getCases().stream().anyMatch(IRSwitchCase::hasPattern);
                if (!hasPatterns && ("J".equals(cleanTarget) || "F".equals(cleanTarget) || "D".equals(cleanTarget)
                        || "long".equals(cleanTarget) || "float".equals(cleanTarget) || "double".equals(cleanTarget)
                        || "Ljava/lang/Long;".equals(cleanTarget) || "Ljava/lang/Float;".equals(cleanTarget) || "Ljava/lang/Double;".equals(cleanTarget)
                        || "java/lang/Long".equals(cleanTarget) || "java/lang/Float".equals(cleanTarget) || "java/lang/Double".equals(cleanTarget))) {
                    reportError(node, "Selector expression in switch cannot be of type 'long', 'float', or 'double': '" + TypeChecker.humanReadable(cleanTarget) + "'");
                }
            }
            validateSwitchNullRules(node, targetType, node.getCases());
            checkPatternDominance(node, node.getCases(), targetType, node.getDefaultBody() != null);
            Set<String> seenCases = new HashSet<>();
            for (IRSwitchCase c : node.getCases()) {
                symbolTable.enterScope();
                try {
                    if (c.getPatterns() != null) {
                        for (IRSwitchPattern p : c.getPatterns()) {
                            declarePatternVariables(p);
                            if (p.getKind() == IRSwitchPattern.Kind.RECORD && TypeChecker.isPrimitive(targetType)) {
                                reportError(node, "Record pattern cannot be applied to primitive selector type '" + TypeChecker.humanReadable(targetType) + "'.");
                            }
                        }
                    }
                    if (c.getGuard() != null) c.getGuard().accept(this);
                    if (!c.hasPattern()) {
                        for (IRExpression val : c.getValues()) {
                            val.accept(this);
                            validateSwitchCaseConstant(node, targetType, val);
                            String caseVal = extractCaseValueKey(val,node);
                            if (caseVal != null) {
                                if (!seenCases.add(caseVal)) {
                                    reportError(node, "Duplicate case label: '" + caseVal + "'");
                                }
                            }
                        }
                    } else {
                        for (IRExpression val : c.getValues()) {
                            val.accept(this);
                            validateSwitchCaseConstant(node, targetType, val);
                        }
                    }
                    if (c.getBody() != null) {
                        controlTargetStack.push(ControlTarget.SWITCH_EXPR);
                        try {
                            c.getBody().accept(this);
                        } finally {
                            controlTargetStack.pop();
                        }
                        if (c.getBody() instanceof IRBlock block && !blockHasResult(block)) {
                            reportError(node, "All execution paths in switch expression block must yield a result value");
                        }
                    }
                } finally {
                    symbolTable.exitScope();
                }
            }

            if (node.getDefaultBody() != null) {
                controlTargetStack.push(ControlTarget.SWITCH_EXPR);
                try {
                    node.getDefaultBody().accept(this);
                } finally {
                    controlTargetStack.pop();
                }
                if (node.getDefaultBody() instanceof IRBlock defaultBlock && !blockHasResult(defaultBlock)) {
                    reportError(node, "All execution paths in switch expression block must yield a result value");
                }
            }

            // Exhaustiveness Analysis
            checkSwitchExhaustiveness(node, node.getExpression(), node.getCases(), null, node.getDefaultBody());

            // --- Branch Type Validation & Type Descriptor Synchronization ---
            List<IRExpression> branchExprs = new ArrayList<>();
            if (node.getCases() != null) {
                for (IRSwitchCase c : node.getCases()) {
                    collectSwitchResultExpressions(c.getBody(), branchExprs);
                }
            }
            if (node.getDefaultBody() != null) {
                collectSwitchResultExpressions(node.getDefaultBody(), branchExprs);
            }

            // 1. Check for void
            for (IRExpression expr : branchExprs) {
                String type = expr.getTypeDescriptor();
                String clean = TypeChecker.cleanDescriptor(type != null ? type : "");
                if ("V".equals(clean) || "void".equals(clean)) {
                    reportError(node, "Switch expression branches cannot yield void.");
                    return;
                }
            }

            // 2. Check for null literal with primitive
            boolean hasNullBranch = false;
            String firstPrimitive = null;
            for (IRExpression expr : branchExprs) {
                String type = expr.getTypeDescriptor();
                String clean = TypeChecker.cleanDescriptor(type != null ? type : "");
                if (clean.isEmpty() || "null".equals(clean)) {
                    hasNullBranch = true;
                } else if (TypeChecker.isPrimitive(clean)) {
                    if (firstPrimitive == null) {
                        firstPrimitive = clean;
                    }
                }
            }
            if (hasNullBranch && firstPrimitive != null) {
                reportError(node, "Switch expression cannot combine 'null' with primitive type '" + TypeChecker.humanReadable(firstPrimitive) + "'.");
                return;
            }

            // 3. Incompatible branch types & unified promoted type
            String unifiedType = null;
            for (IRExpression expr : branchExprs) {
                String rawType = expr.getTypeDescriptor();
                if (unifiedType == null) {
                    unifiedType = rawType;
                    continue;
                }
                String cleanPrev = TypeChecker.cleanDescriptor(unifiedType);
                String cleanCurr = TypeChecker.cleanDescriptor(rawType != null ? rawType : "");
                if ("null".equals(cleanPrev) || "null".equals(cleanCurr)) {
                    unifiedType = TypeChecker.getTernaryPromotedType(unifiedType, rawType, session);
                    continue;
                }
                boolean prevPrim = TypeChecker.isPrimitive(cleanPrev);
                boolean currPrim = TypeChecker.isPrimitive(cleanCurr);
                if (prevPrim != currPrim) {
                    boolean bothNumeric = TypeChecker.isNumeric(cleanPrev) && TypeChecker.isNumeric(cleanCurr);
                    boolean bothBoolean = TypeChecker.isBoolean(cleanPrev) && TypeChecker.isBoolean(cleanCurr);
                    if (!bothNumeric && !bothBoolean) {
                        String primSide = prevPrim ? cleanPrev : cleanCurr;
                        String refSide = prevPrim ? cleanCurr : cleanPrev;
                        String boxed = TypeChecker.box(primSide);
                        if (boxed == null || !TypeChecker.isAssignable(refSide, boxed, session)) {
                            reportError(node, "Incompatible branch types in switch expression: '" + TypeChecker.humanReadable(cleanPrev) + "' and '" + TypeChecker.humanReadable(cleanCurr) + "'.");
                            return;
                        }
                    }
                } else if (prevPrim) {
                    boolean compatible = TypeChecker.isAssignable(cleanPrev, cleanCurr, session)
                                      || TypeChecker.isAssignable(cleanCurr, cleanPrev, session);
                    if (!compatible) {
                        reportError(node, "Incompatible branch types in switch expression: '" + TypeChecker.humanReadable(cleanPrev) + "' and '" + TypeChecker.humanReadable(cleanCurr) + "'.");
                        return;
                    }
                }
                unifiedType = TypeChecker.getTernaryPromotedType(unifiedType, rawType, session);
            }

            if (unifiedType != null) {
                node.setTypeDescriptor(unifiedType);
            }
        } finally {
            activeEnumSwitchTypes.pop();
            switchExpressionDepth--;
        }
    }

    private void checkSwitchExhaustiveness(IRNode switchNode, IRExpression switchExpr, List<IRSwitchCase> exprCases, List<IRSwitchCase> stmtCases, IRNode defaultBody) {
        if (defaultBody != null) return;

        String targetTypeDesc = (switchExpr != null) ? switchExpr.getTypeDescriptor() : null;
        if (targetTypeDesc == null && switchExpr instanceof IRVariableAccess va) {
            targetTypeDesc = symbolTable.getType(va.getName());
        }
        if (targetTypeDesc == null) targetTypeDesc = OceanTypeSystem.OBJECT_DESC;
        String cleanTargetType = TypeChecker.cleanDescriptor(targetTypeDesc);

        boolean hasTotalPattern = false;
        List<IRSwitchPattern> patterns = new ArrayList<>();
        List<IRExpression> values = new ArrayList<>();
        Set<String> seenCasesInternals = new HashSet<>();
        if (exprCases != null) {
            for (IRSwitchCase c : exprCases) {
                if (c.getGuard() == null) {
                    if (c.getPatterns() != null) {
                        for (IRSwitchPattern p : c.getPatterns()) {
                            if (isTotalPattern(p, cleanTargetType)) {
                                hasTotalPattern = true;
                                break;
                            }
                            patterns.add(p);
                        }
                    }
                    if (c.getValues() != null) values.addAll(c.getValues());

                }
            }
        } else if (stmtCases != null) {
            for (IRSwitchCase c : stmtCases) {
                if (c.getGuard() == null) {
                    if (c.getPatterns() != null) {
                        for (IRSwitchPattern p : c.getPatterns()) {
                            if (isTotalPattern(p, cleanTargetType)) {
                                hasTotalPattern = true;
                                break;
                            }
                            patterns.add(p);
                        }
                    }
                    if (c.getValues() != null) values.addAll(c.getValues());

                }
            }
        }

        if (hasTotalPattern) return;

        // 1. Check Sealed Class / Interface Hierarchy Exhaustiveness
        if (ClassMetadataCache.isSealed(cleanTargetType)) {
            List<String> missingSubtypes = findMissingSealedSubtypes(cleanTargetType, patterns);
            if (!missingSubtypes.isEmpty()) {
                String simpleTarget = getSimpleClassName(cleanTargetType);
                List<String> simpleMissing = missingSubtypes.stream().map(this::getSimpleClassName).toList();
                reportError(switchNode, "Switch expression does not cover all possible subtypes of '" + simpleTarget + "' (missing: " + String.join(", ", simpleMissing) + ")");
            }
            return;
        }

        // 1b. Check Record Pattern Exhaustiveness where components are sealed
        List<CompilerRegistry.RecordComponentInfo> recordComps = RecordHelper.getRecordComponents(cleanTargetType);
        if (recordComps != null && !recordComps.isEmpty()) {
            boolean hasSealedComp = false;
            for (CompilerRegistry.RecordComponentInfo comp : recordComps) {
                String cleanCompDesc = TypeChecker.cleanDescriptor(comp.descriptor());
                if (cleanCompDesc.startsWith("L") && cleanCompDesc.endsWith(";")) {
                    cleanCompDesc = cleanCompDesc.substring(1, cleanCompDesc.length() - 1);
                }
                if (ClassMetadataCache.isSealed(cleanCompDesc)) {
                    hasSealedComp = true;
                    break;
                }
            }
            if (hasSealedComp) {
                List<String> missingCombos = findMissingRecordComponentSubtypes(cleanTargetType, recordComps, patterns);
                if (!missingCombos.isEmpty()) {
                    String simpleTarget = getSimpleClassName(cleanTargetType);
                    reportError(switchNode, "Switch expression does not cover all possible record patterns for '" + simpleTarget + "' (missing: " + String.join(", ", missingCombos) + ")");
                }
                return;
            }
        }

        // 2. Check Enum Exhaustiveness
        if (TypeChecker.isEnumType(targetTypeDesc) || !ClassMetadataCache.getEnumConstants(cleanTargetType).isEmpty()) {
            List<String> allConstants = ClassMetadataCache.getEnumConstants(cleanTargetType);
            if (!allConstants.isEmpty()) {
                Set<String> matchedConstants = new HashSet<>();
                for (IRExpression val : values) {
                    String constName = extractEnumConstantName(val);
                    if (constName != null) matchedConstants.add(constName);
                }
                for (IRSwitchPattern pat : patterns) {
                    if (pat.getKind() == IRSwitchPattern.Kind.EXPR && pat.getExpression() != null) {
                        String constName = extractEnumConstantName(pat.getExpression());
                        if (constName != null) matchedConstants.add(constName);
                    }
                }

                List<String> missingConstants = new ArrayList<>();
                for (String c : allConstants) {
                    if (!matchedConstants.contains(c)) missingConstants.add(c);
                }

                if (!missingConstants.isEmpty()) {
                    String simpleEnum = getSimpleClassName(cleanTargetType);
                    reportError(switchNode, "Switch expression does not cover all enum constants of '" + simpleEnum + "' (missing: " + String.join(", ", missingConstants) + ")");
                }
                return;
            }
        }
        // 2b. Check Boolean Exhaustiveness
        if (TypeChecker.isBoolean(targetTypeDesc) || "Z".equals(cleanTargetType) || "java/lang/Boolean".equals(cleanTargetType)) {
            boolean hasTrue = false;
            boolean hasFalse = false;
            for (IRExpression val : values) {
                if (val instanceof IRLiteral lit && lit.getValue() instanceof Boolean b) {
                    if (b) hasTrue = true;
                    else hasFalse = true;
                }
            }
            for (IRSwitchPattern pat : patterns) {
                if (pat.getKind() == IRSwitchPattern.Kind.EXPR && pat.getExpression() instanceof IRLiteral lit && lit.getValue() instanceof Boolean b) {
                    if (b) hasTrue = true;
                    else hasFalse = true;
                }
            }
            List<String> missing = new ArrayList<>();
            if (!hasTrue) missing.add("true");
            if (!hasFalse) missing.add("false");
            if (!missing.isEmpty()) {
                reportError(switchNode, "Switch expression does not cover all boolean values (missing: " + String.join(", ", missing) + ")");
            }
            return;
        }

        // 3. If not sealed, not enum, not boolean, and no total pattern/default:
        reportError(switchNode, "Switch expression must cover all possible input values or provide a default clause.");
    }

    // ==================== SPEC §14.11.1 & §15.28: Switch Null Rules ====================

    private void validateSwitchNullRules(IRNode switchNode, String targetType, List<IRSwitchCase> cases) {
        if (cases == null || cases.isEmpty()) return;
        boolean isPrimitiveTarget = TypeChecker.isPrimitive(targetType);
        boolean hasSeenNull = false;

        for (IRSwitchCase c : cases) {
            boolean caseHasNull = false;
            boolean caseHasNullGuard = false;

            if (c.getPatterns() != null) {
                for (IRSwitchPattern p : c.getPatterns()) {
                    if (p.getKind() == IRSwitchPattern.Kind.NULL) {
                        caseHasNull = true;
                        if (p.getGuard() != null || c.getGuard() != null) {
                            caseHasNullGuard = true;
                        }
                    }
                }
            }
            if (c.getValues() != null) {
                for (IRExpression val : c.getValues()) {
                    if (val instanceof IRLiteral lit && lit.getValue() == null) {
                        caseHasNull = true;
                        if (c.getGuard() != null) {
                            caseHasNullGuard = true;
                        }
                    }
                }
            }

            if (caseHasNull) {
                if (isPrimitiveTarget) {
                    reportError(switchNode, "Label 'case null' is not allowed with primitive switch selector type '" + TypeChecker.humanReadable(targetType) + "'.");
                }
                if (caseHasNullGuard) {
                    reportError(switchNode, "'case null' label cannot have a 'when' guard.");
                }
                if (hasSeenNull) {
                    reportError(switchNode, "Duplicate 'null' label: multiple 'case null' labels are not permitted in a switch block.");
                }
                hasSeenNull = true;
            }
        }
    }

    private boolean isCompileTimeConstant(IRExpression expr) {
        switch (expr) {
            case null -> {
                return false;
            }
            case IRLiteral literal -> {
                return true;
            }
            case IRVariableAccess va -> {
                String name = va.getName();
                String owner = va.getOwner();
                if (owner != null && !owner.isEmpty()) {
                    String cleanOwner = ClassMetadataCache.cleanTypeName(owner);
                    if (ClassMetadataCache.getEnumConstants(cleanOwner).contains(name)) return true;
                    if (CompilerRegistry.globalFieldAccess.containsKey(cleanOwner)) {
                        Map<String, Integer> fieldAcc = CompilerRegistry.globalFieldAccess.get(cleanOwner);
                        if (fieldAcc != null && fieldAcc.containsKey(name) && (fieldAcc.get(name) & Opcodes.ACC_FINAL) != 0) {
                            return true;
                        }
                    }
                    try {
                        Class<?> cls = OceanTypeSystem.forName(cleanOwner.replace('/', '.'));
                        if (cls != null) {
                            Field f = cls.getField(name);
                            if (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())) {
                                return true;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                for (String swEnum : activeEnumSwitchTypes) {
                    if (swEnum != null && !swEnum.isEmpty()) {
                        if (ClassMetadataCache.getEnumConstants(swEnum).contains(name)) return true;
                    }
                }
                for (List<String> list : CompilerRegistry.globalEnumConstants.values()) {
                    if (list.contains(name)) return true;
                }

                if (symbolTable != null) {
                    return symbolTable.isFinal(name) && !symbolTable.isMutated(name);
                }
                return false;
            }
            case IRUnaryOp unOp -> {
                return isCompileTimeConstant(unOp.getExpression());
            }
            case IRBinaryOp binOp -> {
                return isCompileTimeConstant(binOp.getLeft()) && isCompileTimeConstant(binOp.getRight());
            }
            case IRCastExpression cast -> {
                return isCompileTimeConstant(cast.getExpression());
            }
            default -> {
            }
        }
        return false;
    }

    private void validateSwitchCaseConstant(IRNode switchNode, String targetType, IRExpression val) {
        if (targetType == null || val == null) return;
        if (val instanceof IRLiteral lit && lit.getValue() == null) {
            // Null literal rules are validated by validateSwitchNullRules
            return;
        }

        // 1. Constant expression validation
        if (!isCompileTimeConstant(val)) {
            reportError(switchNode, "Constant expression required for switch case label.");
            return;
        }

        String cleanTarget = ClassMetadataCache.cleanTypeName(targetType);
        boolean isEnum = TypeChecker.isEnumType(targetType) || !ClassMetadataCache.getEnumConstants(cleanTarget).isEmpty();

        if (isEnum) {
            if (val instanceof IRVariableAccess va) {
                String constName = va.getName();
                String owner = va.getOwner();
                if (owner != null && !owner.isEmpty()) {
                    String cleanOwner = ClassMetadataCache.cleanTypeName(owner);
                    String simpleOwner = getSimpleClassName(cleanOwner);
                    String simpleTarget = getSimpleClassName(cleanTarget);
                    if (!cleanOwner.equals(cleanTarget) && !simpleOwner.equals(simpleTarget)) {
                        String fullConst = (va.getReceiver() instanceof IRVariableAccess r ? r.getName() + "." : "") + constName;
                        reportError(switchNode, "Enum constant '" + fullConst + "' in switch case is not defined in enum '" + TypeChecker.humanReadable(cleanTarget) + "'.");
                        return;
                    }
                }
                List<String> validConstants = ClassMetadataCache.getEnumConstants(cleanTarget);
                if (!validConstants.contains(constName)) {
                    reportError(switchNode, "Enum constant '" + constName + "' in switch case is not defined in enum '" + TypeChecker.humanReadable(cleanTarget) + "'.");
                }
            } else {
                reportError(switchNode, "Invalid case constant type for enum switch selector ('" + TypeChecker.humanReadable(cleanTarget) + "').");
            }
            return;
        }

        // 2. Type compatibility validation
        String valType = val.getTypeDescriptor();
        String cleanValType = valType != null ? ClassMetadataCache.cleanTypeName(valType) : "";

        // String selector
        if (TypeChecker.isStringType(targetType) || "java/lang/String".equals(cleanTarget) || "String".equals(cleanTarget)) {
            if (!TypeChecker.isStringType(valType) && !"java/lang/String".equals(cleanValType) && !"String".equals(cleanValType)) {
                reportError(switchNode, "Incompatible case label type ('" + TypeChecker.humanReadable(valType != null ? valType : "unknown") + "') for switch selector ('String').");
            }
            return;
        }

        // Boolean selector
        if (TypeChecker.isBoolean(targetType) || "Z".equals(cleanTarget) || "java/lang/Boolean".equals(cleanTarget)) {
            if (!TypeChecker.isBoolean(valType) && !"Z".equals(cleanValType) && !"java/lang/Boolean".equals(cleanValType)) {
                reportError(switchNode, "Incompatible case label type ('" + TypeChecker.humanReadable(valType != null ? valType : "unknown") + "') for switch selector ('boolean').");
            }
            return;
        }

        // Integer-compatible selectors (int, byte, short, char)
        boolean isTargetInt = cleanTarget.equals("I") || cleanTarget.equals("int") || cleanTarget.equals("java/lang/Integer")
                || cleanTarget.equals("B") || cleanTarget.equals("byte") || cleanTarget.equals("java/lang/Byte")
                || cleanTarget.equals("S") || cleanTarget.equals("short") || cleanTarget.equals("java/lang/Short")
                || cleanTarget.equals("C") || cleanTarget.equals("char") || cleanTarget.equals("java/lang/Character");

        if (isTargetInt) {
            Long constVal = evaluateConstantInt(val, switchNode);
            if (constVal == null && (TypeChecker.isStringType(valType) || TypeChecker.isBoolean(valType) || "D".equals(cleanValType) || "F".equals(cleanValType) || "J".equals(cleanValType) || TypeChecker.isClassType(valType))) {
                reportError(switchNode, "Incompatible case label type ('" + TypeChecker.humanReadable(valType) + "') for switch selector ('" + TypeChecker.humanReadable(cleanTarget) + "').");
                return;
            }

            // Primitive range checks
            switch (cleanTarget) {
                case "B", "byte", "java/lang/Byte" -> {
                    if (constVal != null) {
                        if (constVal < Byte.MIN_VALUE || constVal > Byte.MAX_VALUE) {
                            reportError(switchNode, "Constant value (" + constVal + ") exceeds bounds for switch selector type 'byte' [-128, 127].");
                        }
                    }
                }
                case "S", "short", "java/lang/Short" -> {
                    if (constVal != null) {
                        if (constVal < Short.MIN_VALUE || constVal > Short.MAX_VALUE) {
                            reportError(switchNode, "Constant value (" + constVal + ") exceeds bounds for switch selector type 'short' [-32768, 32767].");
                        }
                    }
                }
                case "C", "char", "java/lang/Character" -> {
                    if (constVal != null) {
                        if (constVal < Character.MIN_VALUE || constVal > Character.MAX_VALUE) {
                            reportError(switchNode, "Constant value (" + constVal + ") exceeds bounds for switch selector type 'char' [0, 65535].");
                        }
                    }
                }
            }

        }
    }

    // ==================== Pattern Dominance Checking (Ocean Pattern Matching) ====================

    private void checkPatternDominance(IRNode switchNode, List<IRSwitchCase> cases, String targetTypeDesc, boolean hasDefault) {
        if (cases == null || cases.isEmpty()) return;

        String cleanTargetType = targetTypeDesc != null ? TypeChecker.cleanDescriptor(targetTypeDesc) : "java/lang/Object";
        List<DominanceEntry> seen = new ArrayList<>();

        for (IRSwitchCase c : cases) {
            boolean caseHasGuard = (c.getGuard() != null);
            List<DominanceEntry> currentCaseEntries = new ArrayList<>();

            // 1. Check patterns in current case
            if (c.getPatterns() != null && !c.getPatterns().isEmpty()) {
                for (IRSwitchPattern p : c.getPatterns()) {
                    boolean pHasGuard = caseHasGuard || (p.getGuard() != null);
                    for (DominanceEntry prior : seen) {
                        if (prior.pattern != null && prior.isTotal(cleanTargetType) && p.getKind() == IRSwitchPattern.Kind.NULL) {
                            reportError(switchNode, "'case null' etiketi, genel tip deseninden ('"
                                    + prior.format() + "') sonra gelemez.");
                            break;
                        }
                        if (prior.dominates(p, cleanTargetType, session)) {
                            reportError(switchNode, "Dominated pattern: switch label is dominated by a preceding pattern and cannot be reached: '" + formatPatternForError(p) + "'.");
                            break;
                        }
                    }
                    if (!pHasGuard) {
                        currentCaseEntries.add(new DominanceEntry(p, null));
                    }
                }
            } else if (c.getValues() != null) {
                // 2. Only check literal / expression values if the case has NO patterns
                for (IRExpression val : c.getValues()) {
                    for (DominanceEntry prior : seen) {
                        if (prior.dominatesExpr(val, cleanTargetType, session)) {
                            reportError(switchNode, "Dominated pattern: switch label is dominated by a preceding pattern and cannot be reached: '" + formatExprForError(val) + "'.");
                            break;
                        }
                    }
                    if (!caseHasGuard) {
                        currentCaseEntries.add(new DominanceEntry(null, val));
                    }
                }
            }

            seen.addAll(currentCaseEntries);
        }

        // 3. Check if an unguarded total pattern precedes default
        if (hasDefault) {
            for (DominanceEntry prior : seen) {
                if (prior.isTotal(cleanTargetType)) {
                    reportError(switchNode, "Switch 'default' label is dominated by a preceding total pattern ('" + prior.format() + "') and cannot be reached.");
                    break;
                }
            }
        }
    }

    private class DominanceEntry {
        final IRSwitchPattern pattern;
        final IRExpression expr;

        DominanceEntry(IRSwitchPattern pattern, IRExpression expr) {
            this.pattern = pattern;
            this.expr = expr;
        }

        boolean isTotal(String cleanTargetType) {
            if (pattern != null) {
                return isTotalPattern(pattern, cleanTargetType);
            }
            return false;
        }

        String format() {
            if (pattern != null) return formatPatternForError(pattern);
            if (expr != null) return formatExprForError(expr);
            return "";
        }

        boolean dominates(IRSwitchPattern target, String cleanTargetType, CompilationSession session) {
            if (pattern != null) {
                if (isTotalPattern(pattern, cleanTargetType) && target.getKind() != IRSwitchPattern.Kind.NULL) {
                    return true;
                }

                if (pattern.getKind() == IRSwitchPattern.Kind.TYPE || pattern.getKind() == IRSwitchPattern.Kind.UNNAMED) {
                    String priorDesc = pattern.getTypeDescriptor();
                    if (priorDesc != null) {
                        if (target.getKind() == IRSwitchPattern.Kind.TYPE || target.getKind() == IRSwitchPattern.Kind.UNNAMED) {
                            String targetDesc = target.getTypeDescriptor();
                            if (targetDesc != null) {
                                boolean priorIsArray = priorDesc.startsWith("[");
                                boolean targetIsArray = targetDesc.startsWith("[");
                                if (priorIsArray != targetIsArray) {
                                    return priorDesc.equals(OceanTypeSystem.OBJECT_DESC) || TypeChecker.cleanDescriptor(priorDesc).equals("java/lang/Object");
                                }
                                if (priorIsArray) {
                                    String priorElem = priorDesc.substring(1);
                                    String targetElem = targetDesc.substring(1);
                                    if (TypeChecker.isPrimitive(priorElem)) {
                                        return priorDesc.equals(targetDesc);
                                    }
                                    if (TypeChecker.isPrimitive(targetElem)) {
                                        return false;
                                    }
                                    return TypeChecker.isAssignable(priorElem, targetElem, session);
                                }
                                return TypeChecker.isAssignable(priorDesc, targetDesc, session);
                            }
                        } else if (target.getKind() == IRSwitchPattern.Kind.RECORD) {
                            String targetRecDesc = target.getTypeDescriptor();
                            return targetRecDesc != null && TypeChecker.isAssignable(priorDesc, targetRecDesc, session);
                        } else if (target.getKind() == IRSwitchPattern.Kind.EXPR && target.getExpression() != null) {
                            String exprDesc = target.getExpression().getTypeDescriptor();
                            return exprDesc != null && TypeChecker.isAssignable(priorDesc, exprDesc, session);
                        }
                    }
                } else if (pattern.getKind() == IRSwitchPattern.Kind.RECORD && target.getKind() == IRSwitchPattern.Kind.RECORD) {
                    String priorRec = pattern.getTypeDescriptor();
                    String targetRec = target.getTypeDescriptor();
                    if (priorRec != null && targetRec != null && TypeChecker.isAssignable(priorRec, targetRec, session)) {
                        List<IRSwitchPattern> priorNested = pattern.getNestedPatterns();
                        List<IRSwitchPattern> targetNested = target.getNestedPatterns();
                        if (priorNested.size() == targetNested.size() && !priorNested.isEmpty()) {
                            boolean allDominate = true;
                            for (int i = 0; i < priorNested.size(); i++) {
                                DominanceEntry nestedEntry = new DominanceEntry(priorNested.get(i), null);
                                if (!nestedEntry.dominates(targetNested.get(i), "java/lang/Object", session)) {
                                    allDominate = false;
                                    break;
                                }
                            }
                            return allDominate;
                        }
                    }
                } else return pattern.getKind() == IRSwitchPattern.Kind.NULL && target.getKind() == IRSwitchPattern.Kind.NULL;
            }
            return false;
        }

        boolean dominatesExpr(IRExpression val, String cleanTargetType, CompilationSession session) {
            if (pattern != null) {
                if (isTotalPattern(pattern, cleanTargetType)) {
                    return true;
                }
                if (pattern.getKind() == IRSwitchPattern.Kind.TYPE || pattern.getKind() == IRSwitchPattern.Kind.UNNAMED) {
                    String priorDesc = pattern.getTypeDescriptor();
                    String valDesc = val.getTypeDescriptor();
                    return priorDesc != null && valDesc != null && TypeChecker.isAssignable(priorDesc, valDesc, session);
                } else if (pattern.getKind() == IRSwitchPattern.Kind.NULL) {
                    return val instanceof IRLiteral lit && lit.getValue() == null;
                }
            }
            return false;
        }
    }

    private String formatPatternForError(IRSwitchPattern p) {
        if (p == null) return "pattern";
        switch (p.getKind()) {
            case NULL -> { return "null"; }
            case UNNAMED -> {
                return (p.getTypeDescriptor() != null && !p.getTypeDescriptor().equals(OceanTypeSystem.OBJECT_DESC))
                        ? TypeChecker.humanReadable(p.getTypeDescriptor()) + " _"
                        : "_";
            }
            case TYPE -> {
                String typeName = p.getTypeDescriptor() != null ? TypeChecker.humanReadable(p.getTypeDescriptor()) : "Object";
                return (p.getVariableName() != null && !p.getVariableName().isEmpty())
                        ? typeName + " " + p.getVariableName()
                        : typeName;
            }
            case RECORD -> {
                String typeName = p.getTypeDescriptor() != null ? TypeChecker.humanReadable(p.getTypeDescriptor()) : "Record";
                return typeName + "(...)";
            }
            case EXPR -> {
                return p.getExpression() != null ? formatExprForError(p.getExpression()) : "expr";
            }
            default -> { return p.toString(); }
        }
    }

    private String formatExprForError(IRExpression expr) {
        if (expr instanceof IRLiteral lit) {
            return String.valueOf(lit.getValue());
        } else if (expr instanceof IRVariableAccess va) {
            return va.getName();
        }
        return expr != null ? expr.toString() : "expr";
    }

    private boolean isTotalPattern(IRSwitchPattern p, String cleanTargetType) {
        if (p == null) return false;
        if (p.getGuard() != null) return false;
        if (p.getKind() == IRSwitchPattern.Kind.UNNAMED) {
            String pDesc = p.getTypeDescriptor();
            if (pDesc == null || pDesc.equals(OceanTypeSystem.OBJECT_DESC)) return true;
            String pClean = TypeChecker.cleanDescriptor(pDesc);
            if (pClean.startsWith("[") != cleanTargetType.startsWith("[")) return false;
            String simpleP = getSimpleClassName(pClean);
            String simpleTarget = getSimpleClassName(cleanTargetType);
            return pClean.equals(cleanTargetType) || simpleP.equals(simpleTarget) || ClassMetadataCache.isSubtype(cleanTargetType, pClean);
        }
        if (p.getKind() == IRSwitchPattern.Kind.TYPE) {
            String pDesc = p.getTypeDescriptor();
            if (pDesc == null) return false;
            String pClean = TypeChecker.cleanDescriptor(pDesc);
            if (pClean.startsWith("[") != cleanTargetType.startsWith("[")) return false;
            if (pClean.equals("java/lang/Object") || pClean.equals("Object")) return true;
            String simpleP = getSimpleClassName(pClean);
            String simpleTarget = getSimpleClassName(cleanTargetType);
            if (pClean.equals(cleanTargetType) || simpleP.equals(simpleTarget)) return true;
            return ClassMetadataCache.isSubtype(cleanTargetType, pClean);
        }
        return false;
    }

    private List<String> findMissingSealedSubtypes(String sealedType, List<IRSwitchPattern> patterns) {
        List<String> missing = new ArrayList<>();
        List<String> permitted = ClassMetadataCache.getPermittedSubclasses(sealedType);
        if (permitted.isEmpty()) {
            return missing;
        }

        for (String sub : permitted) {
            String cleanSub = TypeChecker.cleanDescriptor(sub);
            boolean covered = false;
            for (IRSwitchPattern p : patterns) {
                if (coversTypeOrRecord(p, cleanSub)) {
                    covered = true;
                    break;
                }
            }

            if (!covered) {
                if (ClassMetadataCache.isSealed(cleanSub)) {
                    List<String> subMissing = findMissingSealedSubtypes(cleanSub, patterns);
                    if (subMissing.isEmpty()) {
                        covered = true;
                    } else {
                        missing.addAll(subMissing);
                    }
                } else {
                    missing.add(cleanSub);
                }
            }
        }
        return missing;
    }

    private List<String> findMissingRecordComponentSubtypes(String recordType, List<CompilerRegistry.RecordComponentInfo> components, List<IRSwitchPattern> patterns) {
        List<String> missing = new ArrayList<>();
        String simpleRec = getSimpleClassName(recordType);

        if (components.size() == 1) {
            String compDesc = components.getFirst().descriptor();
            String cleanComp = TypeChecker.cleanDescriptor(compDesc);
            if (cleanComp.startsWith("L") && cleanComp.endsWith(";")) cleanComp = cleanComp.substring(1, cleanComp.length() - 1);

            if (ClassMetadataCache.isSealed(cleanComp)) {
                List<String> permitted = ClassMetadataCache.getPermittedSubclasses(cleanComp);
                for (String sub : permitted) {
                    String cleanSub = TypeChecker.cleanDescriptor(sub);
                    boolean covered = false;
                    for (IRSwitchPattern p : patterns) {
                        if (p.getGuard() != null) continue;
                        if (p.getKind() == IRSwitchPattern.Kind.RECORD && coversTypeOrRecord(p, recordType)) {
                            List<IRSwitchPattern> nested = p.getNestedPatterns();
                            if (nested != null && !nested.isEmpty()) {
                                IRSwitchPattern subPat = nested.getFirst();
                                if (coversTypeOrRecord(subPat, cleanSub)) {
                                    covered = true;
                                    break;
                                }
                            }
                        } else if (p.getKind() == IRSwitchPattern.Kind.TYPE || p.getKind() == IRSwitchPattern.Kind.UNNAMED) {
                            if (coversTypeOrRecord(p, recordType)) {
                                covered = true;
                                break;
                            }
                        }
                    }
                    if (!covered) {
                        missing.add(simpleRec + "(" + getSimpleClassName(cleanSub) + ")");
                    }
                }
            }
        }
        return missing;
    }

    private boolean coversTypeOrRecord(IRSwitchPattern p, String targetSubtype) {
        if (p == null || p.getGuard() != null) return false;
        String cleanTarget = TypeChecker.cleanDescriptor(targetSubtype);
        String simpleTarget = getSimpleClassName(cleanTarget);
        if (p.getKind() == IRSwitchPattern.Kind.TYPE || p.getKind() == IRSwitchPattern.Kind.UNNAMED) {
            String pDesc = p.getTypeDescriptor();
            if (pDesc == null || pDesc.equals(OceanTypeSystem.OBJECT_DESC)) return true;
            String pClean = TypeChecker.cleanDescriptor(pDesc);
            String simpleP = getSimpleClassName(pClean);
            if (pClean.equals(cleanTarget) || simpleP.equals(simpleTarget) || pClean.endsWith("/" + cleanTarget) || cleanTarget.endsWith("/" + pClean)) return true;
            return ClassMetadataCache.isSubtype(cleanTarget, pClean);
        }
        if (p.getKind() == IRSwitchPattern.Kind.RECORD) {
            String recType = p.getTypeDescriptor();
            if (recType == null) return false;
            String recClean = TypeChecker.cleanDescriptor(recType);
            String simpleRec = getSimpleClassName(recClean);
            if (recClean.equals(cleanTarget) || simpleRec.equals(simpleTarget) || recClean.endsWith("/" + cleanTarget) || cleanTarget.endsWith("/" + recClean) || ClassMetadataCache.isSubtype(cleanTarget, recClean)) {
                if (p.getNestedPatterns() != null) {
                    for (IRSwitchPattern subPat : p.getNestedPatterns()) {
                        if (subPat.getGuard() != null) return false;
                        if (subPat.getKind() == IRSwitchPattern.Kind.EXPR) return false;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private String extractEnumConstantName(IRExpression expr) {
        if (expr instanceof IRVariableAccess va) {
            return va.getName();
        }
        if (expr instanceof IRLiteral lit) {
            Object val = lit.getValue();
            return val != null ? val.toString() : null;
        }
        if (expr instanceof IRMethodCall mc) {
            return mc.getName();
        }
        return null;
    }

    private String getSimpleClassName(String name) {
        if (name == null) return "";
        String clean = ClassMetadataCache.cleanTypeName(name);
        int lastDollar = clean.lastIndexOf('$');
        if (lastDollar >= 0) clean = clean.substring(lastDollar + 1);
        int lastSlash = clean.lastIndexOf('/');
        if (lastSlash >= 0) return clean.substring(lastSlash + 1);
        int lastDot = clean.lastIndexOf('.');
        if (lastDot >= 0) return clean.substring(lastDot + 1);
        return clean;
    }

    @Override
    public void visitResultStatement(IRResultStatement node) {
        if (switchExpressionDepth == 0) {
            if (lambdaDepth > 0 && enclosingSwitchExpressionDepth > 0) {
                reportError(node, "'result' statement cannot jump outside lambda boundary");
            } else {
                reportError(node, "'result' statement can only be used within a switch expression block");
            }
        }
        if (node.getExpression() != null) {
            node.getExpression().accept(this);
        }
    }

    // R3-18: Await Async Scope Error Text Alignment
    @Override
    public void visitAwaitExpression(IRAwaitExpression node) {
        if (!currentMethodIsAsync) {
            reportError(node, "'await' expression is only allowed in 'async' functions.");
        }
        if (node.getFuture() != null) {
            node.getFuture().accept(this);
            String futureType = node.getFuture().getTypeDescriptor();
            if (futureType != null && !TypeChecker.isFutureType(futureType)) {
                reportError(node, "Right-hand side of 'await' expression must be a CompletableFuture or future type.");
            }
            if (futureType != null && futureType.contains("<") && futureType.contains(">")) {
                int start = futureType.indexOf('<') + 1;
                int end = futureType.lastIndexOf('>');
                if (start < end) {
                    String innerType = futureType.substring(start, end).trim();
                    node.setTypeDescriptor(innerType);
                }
            } else {
                node.setTypeDescriptor(OceanTypeSystem.OBJECT_DESC);
            }
        }
    }

    private boolean typeContainsParam(String typeStr, String paramName) {
        if (typeStr == null || paramName == null) return false;
        String clean = TypeChecker.cleanDescriptor(typeStr);
        if (clean != null && (clean.equals(paramName) || clean.equals("L" + paramName + ";") || clean.equals(paramName + "?"))) return true;
        if (typeStr.contains("<" + paramName + ">") || typeStr.contains("<L" + paramName + ";>")) return true;
        if (typeStr.contains("<" + paramName + ",") || typeStr.contains("<L" + paramName + ";,")) return true;
        if (typeStr.contains("," + paramName + ">") || typeStr.contains(",L" + paramName + ";>")) return true;
        if (typeStr.contains("," + paramName + ",") || typeStr.contains(",L" + paramName + ";,")) return true;
        return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(paramName) + "\\b").matcher(typeStr).find();
    }

    private boolean isValidAnnotationMemberType(String typeDesc) {
        if (typeDesc == null || typeDesc.isEmpty()) return false;
        String clean = typeDesc;
        if (clean.startsWith("[")) {
            if (clean.startsWith("[[")) return false; // multidimensional arrays are illegal in annotations
            clean = clean.substring(1);
        }
        if (TypeChecker.isPrimitive(clean)) return true;
        if (clean.equals(OceanTypeSystem.STRING_DESC) || clean.equals("java/lang/String") || clean.equals("String")) return true;
        if (clean.equals("Ljava/lang/Class;") || clean.startsWith("Ljava/lang/Class<") || clean.equals("java/lang/Class") || clean.equals("Class")) return true;
        String cleanType = TypeChecker.cleanDescriptor(clean);
        if (TypeChecker.isEnumType(clean) || !ClassMetadataCache.getEnumConstants(cleanType).isEmpty()) return true;
        return ClassMetadataCache.isAnnotation(cleanType);
    }

    private boolean isCompileTimeAnnotationConstant(IRExpression expr) {
        switch (expr) {
            case null -> {
                return false;
            }
            case IRLiteral lit -> {
                return lit.getValue() != null;
            }
            case IRAnnotation irAnnotation -> {
                return true;
            }
            case IRArrayLiteral arr -> {
                for (IRExpression elem : arr.getElements()) {
                    if (!isCompileTimeAnnotationConstant(elem)) return false;
                }
                return true;
            }
            case IRVariableAccess va -> {
                if (va.isField() || va.getOwner() != null || va.isClassReference() || "class".equals(va.getName()))
                    return true;
                String name = va.getName();
                if ("RUNTIME".equals(name) || "CLASS".equals(name) || "SOURCE".equals(name)) return true;
                if ("TYPE".equals(name) || "METHOD".equals(name) || "FIELD".equals(name) || "CONSTRUCTOR".equals(name) || "PARAMETER".equals(name) || "TYPE_USE".equals(name) || "ANNOTATION_TYPE".equals(name))
                    return true;
                String varType = symbolTable.getType(name);
                if (varType != null && (TypeChecker.isEnumType(varType) || "Ljava/lang/Class;".equals(varType)))
                    return true;
                return !ClassMetadataCache.getEnumConstants(name).isEmpty();
            }
            default -> {
            }
        }
        return false;
    }

    private void validateAnnotations(List<IRAnnotation> annotations, String targetKind, IRNode targetNode) {
        if (annotations == null || annotations.isEmpty()) return;
        Set<String> seenAnnotations = new HashSet<>();

        for (IRAnnotation anno : annotations) {
            String annoDesc = anno.getTypeDescriptor();
            if (annoDesc == null) continue;
            String cleanAnno = TypeChecker.cleanDescriptor(annoDesc);
            String simpleName = getSimpleClassName(cleanAnno);

            // 1. Target Check (@Target)
            Set<String> allowedTargets = ClassMetadataCache.getAnnotationTargetTypes(cleanAnno);
            if (allowedTargets.isEmpty()) {
                allowedTargets = ClassMetadataCache.getAnnotationTargetTypes(simpleName);
            }
            if (!allowedTargets.isEmpty()) {
                boolean isAnnotationType = "TYPE".equals(targetKind) && targetNode instanceof IRClass cls && ((cls.getAccessFlags() & Opcodes.ACC_ANNOTATION) != 0);
                boolean targetMatched = false;
                if (allowedTargets.contains("TYPE_USE")) {
                    targetMatched = true;
                } else if ("TYPE".equals(targetKind)) {
                    targetMatched = allowedTargets.contains("TYPE") || (isAnnotationType && (allowedTargets.contains("ANNOTATION_TYPE") || allowedTargets.contains("TYPE")));
                } else if ("METHOD".equals(targetKind)) {
                    targetMatched = allowedTargets.contains("METHOD");
                } else if ("CONSTRUCTOR".equals(targetKind)) {
                    targetMatched = allowedTargets.contains("CONSTRUCTOR");
                } else if ("FIELD".equals(targetKind)) {
                    targetMatched = allowedTargets.contains("FIELD");
                } else if ("PARAMETER".equals(targetKind)) {
                    targetMatched = allowedTargets.contains("PARAMETER");
                }
                if (!targetMatched) {
                    reportError(targetNode, "'" + simpleName + "' anotasyonu '" + (isAnnotationType ? "ANNOTATION_TYPE" : targetKind) + "' hedefine uygulanamaz (izin verilen hedefler: " + String.join(", ", allowedTargets) + ")");
                }
            }

            // 2. Compile-Time Constant Check for all arguments
            if (anno.getElements() != null) {
                for (Map.Entry<String, IRExpression> entry : anno.getElements().entrySet()) {
                    IRExpression val = entry.getValue();
                    if (val instanceof IRLiteral lit && lit.getValue() == null) {
                        reportError(targetNode, "Annotation '" + simpleName + "' attribute '" + entry.getKey() + "' cannot have 'null' value.");
                        continue;
                    }
                    if (!isCompileTimeAnnotationConstant(val)) {
                        reportError(targetNode, "Attribute '" + entry.getKey() + "' of annotation '" + simpleName + "' must be a constant expression");
                    }
                }
            }

            // 3. Member Validation & Missing Required Parameters
            Map<String, CompilerRegistry.AnnotationMemberInfo> members = ClassMetadataCache.getAnnotationMembers(cleanAnno);
            if (members.isEmpty()) {
                members = ClassMetadataCache.getAnnotationMembers(simpleName);
            }
            if (!members.isEmpty() && anno.getElements() != null) {
                // Check unexpected attributes
                for (String attrKey : anno.getElements().keySet()) {
                    if (!members.containsKey(attrKey) && !("value".equals(attrKey) && members.containsKey("val"))) {
                        reportError(targetNode, "Attribute '" + attrKey + "' is not defined in annotation '" + simpleName + "'");
                    }
                }
                // Check required attributes (those without default)
                for (CompilerRegistry.AnnotationMemberInfo mInfo : members.values()) {
                    if (!mInfo.hasDefault() && !anno.getElements().containsKey(mInfo.name())) {
                        if (!("value".equals(mInfo.name()) && anno.getElements().containsKey("val"))) {
                            reportError(targetNode, "'" + simpleName + "' anotasyonunun zorunlu '" + mInfo.name() + "' parametresi eksik");
                        }
                    }
                }
            }

            // 4. Duplicate Annotation Check (unless @Repeatable)
            String normAnno = cleanAnno.startsWith("L") && cleanAnno.endsWith(";") ? cleanAnno.substring(1, cleanAnno.length() - 1) : cleanAnno;
            if (!seenAnnotations.add(normAnno)) {
                boolean isRepeatable = ClassMetadataCache.isAnnotationRepeatable(cleanAnno) || ClassMetadataCache.isAnnotationRepeatable(normAnno) || ClassMetadataCache.isAnnotationRepeatable(simpleName);
                if (!isRepeatable) {
                    reportError(targetNode, "'" + simpleName + "' anotasyonu tekrarlanamaz (@Repeatable gereklidir)");
                }
            }
        }
    }

    private void validateGenericTypeArguments(String typeDesc, IRNode node) {
        if (typeDesc == null || !typeDesc.contains("<") || !typeDesc.contains(">")) return;
        int lt = typeDesc.indexOf('<');
        if (lt <= 0) return;
        String rawType = typeDesc.substring(0, lt).trim();
        String internalOwner = TypeChecker.cleanDescriptor(rawType).replace('.', '/');
        if (internalOwner.startsWith("L") && (internalOwner.endsWith(";") || typeDesc.endsWith(";") || internalOwner.contains("/"))) {
            internalOwner = internalOwner.substring(1);
        }
        if (internalOwner.endsWith(";")) {
            internalOwner = internalOwner.substring(0, internalOwner.length() - 1);
        }

        List<CompilerRegistry.TypeParameterInfo> declaredTps = CompilerRegistry.globalTypeParameterRegistry.get(internalOwner);
        if (declaredTps == null) {
            String simple = getSimpleClassName(internalOwner);
            declaredTps = CompilerRegistry.globalTypeParameterRegistry.get(simple);
            if (declaredTps == null) {
                // İç sınıf araması: hem '/' hem '$' ayracıyla biten anahtarları kontrol et
                for (Map.Entry<String, List<CompilerRegistry.TypeParameterInfo>> entry : CompilerRegistry.globalTypeParameterRegistry.entrySet()) {
                    String key = entry.getKey();
                    if (key.endsWith("/" + simple) || key.endsWith("$" + simple) || key.equals(simple)) {
                        declaredTps = entry.getValue();
                        break;
                    }
                }
            }
            // Hâlâ bulunamadıysa, mevcut sınıfın iç sınıfı olarak dene (örn. OuterClass$DenemeMap)
            if (declaredTps == null && currentClassFqcn != null) {
                String innerKey = currentClassFqcn + "$" + simple;
                declaredTps = CompilerRegistry.globalTypeParameterRegistry.get(innerKey);
                if (declaredTps == null) {
                    // Basit sınıf adıyla da dene (ör. OuterClass içinde DenemeMap → OuterClass$DenemeMap)
                    String outerSimple = getSimpleClassName(currentClassFqcn);
                    String innerKeySimple = outerSimple + "$" + simple;
                    for (Map.Entry<String, List<CompilerRegistry.TypeParameterInfo>> entry : CompilerRegistry.globalTypeParameterRegistry.entrySet()) {
                        String key = entry.getKey();
                        if (key.endsWith("$" + outerSimple + "$" + simple) || key.equals(innerKeySimple) || key.endsWith("/" + innerKeySimple)) {
                            declaredTps = entry.getValue();
                            break;
                        }
                    }
                }
            }
        }
        if (declaredTps == null || declaredTps.isEmpty()) {
            try {
                Class<?> cls = OceanTypeSystem.forName(internalOwner.replace('/', '.'));
                if (cls != null && cls.getTypeParameters().length > 0) {
                    List<CompilerRegistry.TypeParameterInfo> jTps = new ArrayList<>();
                    for (java.lang.reflect.TypeVariable<?> tv : cls.getTypeParameters()) {
                        List<String> upper = new ArrayList<>();
                        for (java.lang.reflect.Type b : tv.getBounds()) {
                            if (b instanceof Class<?> bc) {
                                if (!bc.equals(Object.class)) {
                                    upper.add(org.objectweb.asm.Type.getDescriptor(bc));
                                }
                            } else if (b instanceof java.lang.reflect.ParameterizedType pt && pt.getRawType() instanceof Class<?> rc) {
                                if (!rc.equals(Object.class)) {
                                    upper.add(org.objectweb.asm.Type.getDescriptor(rc));
                                }
                            }
                        }
                        jTps.add(new CompilerRegistry.TypeParameterInfo(tv.getName(), CompilerRegistry.Variance.INVARIANT, upper, null, false));
                    }
                    declaredTps = jTps;
                }
            } catch (Throwable ignored) {}
        }

        if (declaredTps == null || declaredTps.isEmpty()) return;

        List<String> typeArgs = TypeChecker.extractGenericArguments(typeDesc);
        if (!typeArgs.isEmpty() && declaredTps.size() != typeArgs.size()) {
            reportError(node, "Wrong number of type arguments for '" + TypeChecker.humanReadable(internalOwner) + "': expected " + declaredTps.size() + ", found " + typeArgs.size() + ".");
        }
        for (int i = 0; i < Math.min(declaredTps.size(), typeArgs.size()); i++) {
            CompilerRegistry.TypeParameterInfo tp = declaredTps.get(i);
            String arg = typeArgs.get(i).trim();
            if (arg.startsWith("+") || arg.startsWith("-")) arg = arg.substring(1).trim();
            if (arg.equals("*") || arg.equals("?") || arg.isEmpty()) continue;

            String cleanArg = TypeChecker.cleanDescriptor(arg);
            String boxedArg = TypeChecker.isPrimitive(cleanArg) ? TypeChecker.box(cleanArg) : cleanArg;

            // 1. Check concrete upper bounds (T <: SomeClass)
            if (tp.getUpperBounds() != null && !tp.getUpperBounds().isEmpty()) {
                for (String bound : tp.getUpperBounds()) {
                    if (bound == null || bound.isEmpty() || bound.equals(OceanTypeSystem.OBJECT_DESC) || bound.equals("java/lang/Object") || bound.equals("Object")) continue;
                    String cleanBound = TypeChecker.cleanDescriptor(bound);
                    String boxedBound = TypeChecker.isPrimitive(cleanBound) ? TypeChecker.box(cleanBound) : cleanBound;
                    boolean valid = cleanArg.equals(cleanBound)
                            || (session != null && session.isSubType(boxedArg, boxedBound))
                            || TypeChecker.isAssignable(boxedBound, boxedArg, session);
                    if (!valid) {
                        reportError(node, "Type argument '" + TypeChecker.humanReadable(boxedArg) + "' does not conform to upper bound '" + TypeChecker.humanReadable(boxedBound) + "' of type parameter '" + tp.name + "'.");
                    }
                }
            }

            // 2. Check dependent upper bounds: T <: U (bound başka bir tip parametresi)
            if (!tp.dependentUpperBoundParams.isEmpty()) {
                for (String depParamName : tp.dependentUpperBoundParams) {
                    // Bu parametre adının indexini bul
                    int depIdx = -1;
                    for (int j = 0; j < declaredTps.size(); j++) {
                        if (declaredTps.get(j).name.equals(depParamName)) { depIdx = j; break; }
                    }
                    if (depIdx < 0 || depIdx >= typeArgs.size()) continue;
                    // Bağlı parametreye verilen somut argümanı al
                    String depArg = typeArgs.get(depIdx).trim();
                    if (depArg.startsWith("+") || depArg.startsWith("-")) depArg = depArg.substring(1).trim();
                    if (depArg.equals("*") || depArg.equals("?") || depArg.isEmpty()) continue;
                    String cleanDepArg = TypeChecker.cleanDescriptor(depArg);
                    String resolvedBound = TypeChecker.isPrimitive(cleanDepArg) ? TypeChecker.box(cleanDepArg) : cleanDepArg;
                    boolean valid = cleanArg.equals(cleanDepArg)
                            || (session != null && session.isSubType(boxedArg, resolvedBound))
                            || TypeChecker.isAssignable(resolvedBound, boxedArg, session);
                    if (!valid) {
                        reportError(node, "Type argument '" + TypeChecker.humanReadable(boxedArg) + "' does not conform to upper bound '" + TypeChecker.humanReadable(resolvedBound) + "' (via '" + depParamName + "') of type parameter '" + tp.name + "'.");
                    }
                }
            }

            // 3. Check concrete lower bound (T >: SomeClass)
            if (tp.lowerBound != null && !tp.lowerBound.isEmpty()) {
                String cleanLower = TypeChecker.cleanDescriptor(tp.lowerBound);
                String boxedLower = TypeChecker.isPrimitive(cleanLower) ? TypeChecker.box(cleanLower) : cleanLower;
                boolean valid = cleanArg.equals(cleanLower)
                        || (session != null && session.isSubType(boxedLower, boxedArg))
                        || TypeChecker.isAssignable(boxedArg, boxedLower, session);
                if (!valid) {
                    reportError(node, "Type argument '" + TypeChecker.humanReadable(boxedArg) + "' does not conform to lower bound '" + TypeChecker.humanReadable(boxedLower) + "' of type parameter '" + tp.name + "'.");
                }
            }

            // 4. Check dependent lower bound: T >: U (lower bound başka bir tip parametresi)
            if (tp.dependentLowerBoundParam != null && !tp.dependentLowerBoundParam.isEmpty()) {
                int depIdx = -1;
                for (int j = 0; j < declaredTps.size(); j++) {
                    if (declaredTps.get(j).name.equals(tp.dependentLowerBoundParam)) { depIdx = j; break; }
                }
                if (depIdx >= 0 && depIdx < typeArgs.size()) {
                    String depArg = typeArgs.get(depIdx).trim();
                    if (depArg.startsWith("+") || depArg.startsWith("-")) depArg = depArg.substring(1).trim();
                    if (!depArg.equals("*") && !depArg.equals("?") && !depArg.isEmpty()) {
                        String cleanDepArg = TypeChecker.cleanDescriptor(depArg);
                        String resolvedLower = TypeChecker.isPrimitive(cleanDepArg) ? TypeChecker.box(cleanDepArg) : cleanDepArg;
                        boolean valid = cleanArg.equals(cleanDepArg)
                                || (session != null && session.isSubType(resolvedLower, boxedArg))
                                || TypeChecker.isAssignable(boxedArg, resolvedLower, session);
                        if (!valid) {
                            reportError(node, "Type argument '" + TypeChecker.humanReadable(boxedArg) + "' does not conform to lower bound '" + TypeChecker.humanReadable(resolvedLower) + "' (via '" + tp.dependentLowerBoundParam + "') of type parameter '" + tp.name + "'.");
                        }
                    }
                }
            }

            // Recursively validate inner generics (e.g. Box<List<String>>)
            if (arg.contains("<") && arg.contains(">")) {
                validateGenericTypeArguments(arg, node);
            }
        }
    }
}
