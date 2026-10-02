package ocean.compiler;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

import org.objectweb.asm.Type;
/**
 * Central registry for all compilation-session-wide global state.
 * Previously these maps lived as public static fields on OceanToBytecodeVisitor,
 * which created a circular dependency:
 *   SymbolTable -> OceanToBytecodeVisitor -> SymbolTable
 * Moving them here breaks that cycle and makes the registries independently testable.
 * Lifecycle: CompilerRegistry.clearAll() must be called at the start of each
 * full compilation pass (OceanRunner does this).
 */
public final class CompilerRegistry {

    private CompilerRegistry() {}

    // ── Shared Data Structures ──────────────────────────────────────────────

    public record ExtensionMethodInfo(String owner, String name, String descriptor) implements Serializable {}
    public record RecordComponentInfo(String name, String descriptor) implements Serializable {}
    public record AnnotationMemberInfo(String name, String descriptor, boolean hasDefault) implements Serializable {}
    public record AnnotationTypeInfo(String name, Set<String> targets, String retention, boolean repeatable, Map<String, AnnotationMemberInfo> members) implements Serializable {}

    public enum Variance {
        INVARIANT, COVARIANT, CONTRAVARIANT
    }

    public static class TypeParameterInfo implements Serializable {
        public final String name;
        public final Variance variance;
        public final String upperBound; // Primary upper bound for backward compatibility
        public final List<String> upperBounds; // All intersection upper bounds (JVM descriptors)
        public final String lowerBound; // "super Integer" → "Ljava/lang/Integer;"
        public final boolean isWildcard; // ? extends X veya ? super X

        /**
         * T <: U gibi durumlarda, bound olarak kullanılan diğer tip parametre adları.
         * Örn. class Foo<U, T <: U> → T.dependentUpperBoundParams = ["U"]
         * Bunlar JVM descriptor değil, ham parametre adlarıdır. Sadece semantik kontrol için kullanılır.
         */
        public final List<String> dependentUpperBoundParams;

        /**
         * T >: U gibi durumlarda, lower bound olarak kullanılan diğer tip parametre adı.
         * Ham parametre adıdır, JVM descriptor değildir.
         */
        public final String dependentLowerBoundParam;

        public TypeParameterInfo(String name, Variance variance) {
            this(name, variance, Collections.emptyList(), null, false, Collections.emptyList(), null);
        }

        public TypeParameterInfo(String name, Variance variance, String upperBound, String lowerBound,
                                 boolean isWildcard) {
            this(name, variance, (upperBound != null ? List.of(upperBound) : Collections.emptyList()), lowerBound, isWildcard, Collections.emptyList(), null);
        }

        public TypeParameterInfo(String name, Variance variance, List<String> upperBounds, String lowerBound,
                                 boolean isWildcard) {
            this(name, variance, upperBounds, lowerBound, isWildcard, Collections.emptyList(), null);
        }

        public TypeParameterInfo(String name, Variance variance, List<String> upperBounds, String lowerBound,
                                 boolean isWildcard, List<String> dependentUpperBoundParams, String dependentLowerBoundParam) {
            this.name = name;
            this.variance = variance;
            this.upperBounds = (upperBounds != null && !upperBounds.isEmpty()) ? List.copyOf(upperBounds) : Collections.emptyList();
            this.upperBound = !this.upperBounds.isEmpty() ? this.upperBounds.getFirst() : null;
            this.lowerBound = lowerBound;
            this.isWildcard = isWildcard;
            this.dependentUpperBoundParams = (dependentUpperBoundParams != null && !dependentUpperBoundParams.isEmpty())
                    ? List.copyOf(dependentUpperBoundParams) : Collections.emptyList();
            this.dependentLowerBoundParam = dependentLowerBoundParam;
        }

        public List<String> getUpperBounds() {
            return upperBounds;
        }

        /** Erasure sonrası tip: upperBound varsa onu, yoksa Object kullan */
        public String getErasedType() {
            if (upperBound != null)
                return upperBound;
            return OceanTypeSystem.OBJECT_DESC;
        }
    }

    // ── Field registries ──────────────────────────────────────────────────────

    /** className → (fieldName → descriptor) */
    public static final Map<String, Map<String, String>> globalFieldRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalFieldRegistry);

    /** className → (fieldName → generic signature descriptor) */
    public static final Map<String, Map<String, String>> globalFieldGenericSignatureRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalFieldGenericSignatureRegistry);

    /** className → (fieldName → isMutable) */
    public static final Map<String, Map<String, Boolean>> globalFieldMutability = new ThreadLocalDelegatingMap<>(s -> s.globalFieldMutability);

    /** className → (fieldName → isStatic) */
    public static final Map<String, Map<String, Boolean>> globalFieldStaticity = new ThreadLocalDelegatingMap<>(s -> s.globalFieldStaticity);

    /** className → (fieldName → ACC_* flags) */
    public static final Map<String, Map<String, Integer>> globalFieldAccess = new ThreadLocalDelegatingMap<>(s -> s.globalFieldAccess);

    // ── Method registries ─────────────────────────────────────────────────────

    /** className → (methodName → descriptor) */
    public static final Map<String, Map<String, String>> globalMethodRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalMethodRegistry);

    /** className → (methodName → genericReturnType) */
    public static final Map<String, Map<String, String>> globalMethodGenericReturnTypeRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalMethodGenericReturnTypeRegistry);

    /** className → (methodName → isStatic) */
    public static final Map<String, Map<String, Boolean>> globalMethodStaticity = new ThreadLocalDelegatingMap<>(s -> s.globalMethodStaticity);

    /** className → (methodName → ACC_* flags) */
    public static final Map<String, Map<String, Integer>> globalMethodAccess = new ThreadLocalDelegatingMap<>(s -> s.globalMethodAccess);

    /** className → (methodName → list of overload descriptors) */
    public static final Map<String, Map<String, List<String>>> globalOverloadRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalOverloadRegistry);

    /** className → (methodName → list of method-level type parameter names) */
    public static final Map<String, Map<String, List<String>>> globalMethodTypeParametersRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalMethodTypeParametersRegistry);

    /** className → (methodName → list of thrown exception internal class names) */
    public static final Map<String, Map<String, List<String>>> globalMethodThrowsRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalMethodThrowsRegistry);

    public record MethodParamInfo(String name, String rawType, String typeDesc, OceanParser.ExpressionContext defaultExpr) implements java.io.Serializable {}

    /** className → (methodName → list of MethodParamInfo) */
    public static final Map<String, Map<String, List<MethodParamInfo>>> globalMethodParamsRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalMethodParamsRegistry);

    // ── Extension methods ─────────────────────────────────────────────────────

    /** extendedType → (methodName → list of ExtensionMethodInfo) */
    public static final Map<String, Map<String, List<ExtensionMethodInfo>>> globalExtensionMethodRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalExtensionMethodRegistry);

    // ── Class hierarchy registries ────────────────────────────────────────────

    /** className → superClassName (JVM internal name) */
    public static final Map<String, String> globalSuperClassRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalSuperClassRegistry);
    /** innerClassName → outerClassName (JVM internal name for non-static inner classes) */
    public static final Map<String,String> globalInnerClassOuterMap = new ThreadLocalDelegatingMap<>(s -> s.globalInnerClassOuterMap);
    /** innerClassName → used outerClassName (JVM internal name for non-static inner classes) */
    public static final Map<String, String> globalInnerClassUsedOuterMap = new ThreadLocalDelegatingMap<>(s -> s.getGlobalInnerClassUsesOuterMap);

    /** className → ACC_* accessibility flags */
    public static final Map<String, Integer> globalClassAccess = new ThreadLocalDelegatingMap<>(s -> s.globalClassAccess);

    /** className → implemented interface names */
    public static final Map<String, String[]> globalInterfaceRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalInterfaceRegistry);

    /** className → list of TypeParameterInfo */
    public static final Map<String, List<TypeParameterInfo>> globalTypeParameterRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalTypeParameterRegistry);
    public static final Map<String, String> globalSuperClassGenericSignatureRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalSuperClassGenericSignatureRegistry);
    public static final Map<String, List<String>> globalInterfaceGenericSignatureRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalInterfaceGenericSignatureRegistry);

    /** Set of class names that are abstract */
    public static final Set<String> globalAbstractClassSet = new ThreadLocalDelegatingSet<>(s -> s.globalAbstractClassSet);

    /** Set of class names that are interfaces */
    public static final Set<String> globalIsInterfaceSet = new ThreadLocalDelegatingSet<>(s -> s.globalIsInterfaceSet);

    /** Set of class/interface names that are sealed */
    public static final Set<String> globalSealedClassSet = new ThreadLocalDelegatingSet<>(s -> s.globalSealedClassSet);

    /** Set of sealed class/interface names that have explicit restricts clause */
    public static final Set<String> globalExplicitRestrictsSet = new ThreadLocalDelegatingSet<>(s -> s.globalExplicitRestrictsSet);

    /** Set of data class names that are data class */
    public static final Set<String> globalDataClassSet = new ThreadLocalDelegatingSet<>(s->s.globalDataClassSet);

    /** data class name → list of record component infos */
    public static final Map<String, List<RecordComponentInfo>> globalRecordComponents = new ThreadLocalDelegatingMap<>(s -> s.globalRecordComponents);

    /** className → list of enum constant names in declaration (ordinal) order */
    public static final Map<String, List<String>> globalEnumConstants = new ThreadLocalDelegatingMap<>(s -> s.globalEnumConstants);

    /** Set of method keys (className#methodName) that are async */
    public static final Set<String> globalAsyncMethodSet = new ThreadLocalDelegatingSet<>(s -> s.globalAsyncMethodSet);

    /** sealed class name → list of permitted subclass names */
    public static final Map<String, List<String>> globalPermittedSubclassesRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalPermittedSubclassesRegistry);

    /** subclass name → modifier status ("final", "sealed", "non-sealed") */
    public static final Map<String, String> globalSubclassStatusRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalSubclassStatusRegistry);
 
    /** annotationClassName -> AnnotationTypeInfo */
    public static final Map<String, AnnotationTypeInfo> globalAnnotationTypeRegistry = new ThreadLocalDelegatingMap<>(s -> s.globalAnnotationTypeRegistry);

    // ── Dynamic type-system registries ────────────────────────────────────────

    /**
     * Set of JVM internal class names that are SAM (Single Abstract Method)
     * functional interfaces — both user-defined (@FunctionalInterface) and
     * well-known JDK interfaces.  Populated by PreScanner during the scan phase.
     * Used by TypeChecker.isFunctionalInterface() instead of a hardcoded list.
     */
    public static final Set<String> globalFunctionalInterfaceRegistry = new ThreadLocalDelegatingSet<>(s -> s.globalFunctionalInterfaceRegistry);

    /**
     * Set of JVM internal class names whose generic type argument [0] is the
     * element type (List-like containers).  Used by IRGenerator.resolveGenericReturnTypeInternal
     * instead of hardcoded simpleName equals chains.
     */
    public static final Set<String> globalListLikeOwnerRegistry = new ThreadLocalDelegatingSet<>(s -> s.globalListLikeOwnerRegistry);

    /**
     * Set of JVM internal class names whose generic type argument [1] is the
     * value type (Map-like containers).  Used by IRGenerator.resolveGenericReturnTypeInternal.
     */
    public static final Set<String> globalMapLikeOwnerRegistry = new ThreadLocalDelegatingSet<>(s -> s.globalMapLikeOwnerRegistry);

    public static List<String> implicitImportPrefixes = new ArrayList<>(List.of(
            "java.lang.",
            "ocean.stdlib."
    ));

    // ── Reflection / class-resolution cache ──────────────────────────────────

    /**
     * Cache for resolved class paths and reflection lookups.
     * Uses sentinel object UNRESOLVED (from ConstantFolder) to distinguish
     * "looked up and not found" from "not yet looked up".
     */
    public static final Map<String, Object> reflectionCache = new ThreadLocalDelegatingMap<>(s -> s.reflectionCache);

    // ── Unified Symbol Model Helpers ─────────────────────────────────────────

    public static ocean.compiler.symbol.SymbolRegistry getSymbolRegistry() {
        CompilationSession s = CompilationSession.getActiveSession();
        return s != null ? s.symbolRegistry : CompilationSession.getFallbackSession().symbolRegistry;
    }

    public static ocean.compiler.symbol.ClassSymbol getClassSymbol(String name) {
        return getSymbolRegistry().getClassSymbol(name);
    }

    public static void registerClassSymbol(ocean.compiler.symbol.ClassSymbol symbol) {
        getSymbolRegistry().registerClassSymbol(symbol);
    }

    public static ocean.compiler.symbol.ClassSymbol getOrCreateClassSymbol(String name) {
        return getSymbolRegistry().getOrCreateClassSymbol(name);
    }

    public static boolean hasClass(String internalName) {
        if (internalName == null || internalName.isEmpty()) return false;
        return globalSuperClassRegistry.containsKey(internalName)
                || globalMethodRegistry.containsKey(internalName)
                || globalInterfaceRegistry.containsKey(internalName)
                || globalClassAccess.containsKey(internalName)
                || globalDataClassSet.contains(internalName)
                || globalSealedClassSet.contains(internalName)
                || getClassSymbol(internalName) != null;
    }

    private static void collectExtensionCandidates(String typeDesc, String methodName, List<ExtensionMethodInfo> results, Set<String> seenDescriptors) {
        if (typeDesc == null) return;
        String clean = TypeChecker.cleanDescriptor(typeDesc);

        // 1. Exact descriptor (e.g. "LInterfaceExtTest/Describable;", "Ljava/lang/String;")
        Map<String, List<ExtensionMethodInfo>> exactMap = globalExtensionMethodRegistry.get(clean);
        if (exactMap != null && exactMap.containsKey(methodName)) {
            for (ExtensionMethodInfo emi : exactMap.get(methodName)) {
                if (seenDescriptors.add(emi.owner() + "#" + emi.descriptor())) {
                    results.add(emi);
                }
            }
        }

        // 2. Unqualified descriptor if clean is qualified (e.g. "Lfoo/bar/Baz;" -> "LBaz;")
        if (clean.startsWith("L") && clean.endsWith(";") && clean.contains("/")) {
            String simple = "L" + clean.substring(clean.lastIndexOf('/') + 1);
            Map<String, List<ExtensionMethodInfo>> simpleMap = globalExtensionMethodRegistry.get(simple);
            if (simpleMap != null && simpleMap.containsKey(methodName)) {
                for (ExtensionMethodInfo emi : simpleMap.get(methodName)) {
                    if (seenDescriptors.add(emi.owner() + "#" + emi.descriptor())) {
                        results.add(emi);
                    }
                }
            }
        }

        // 3. Qualified descriptor if clean is unqualified (e.g. "LBaz;" -> lookup "Lfoo/bar/Baz;")
        if (clean.startsWith("L") && clean.endsWith(";") && !clean.contains("/")) {
            String simpleName = clean.substring(1, clean.length() - 1);
            ocean.compiler.symbol.ClassSymbol sym = getClassSymbol(simpleName);
            if (sym != null && !sym.getInternalName().equals(simpleName)) {
                String qual = OceanTypeSystem.wrapObjectType(sym.getInternalName());
                Map<String, List<ExtensionMethodInfo>> qualMap = globalExtensionMethodRegistry.get(qual);
                if (qualMap != null && qualMap.containsKey(methodName)) {
                    for (ExtensionMethodInfo emi : qualMap.get(methodName)) {
                        if (seenDescriptors.add(emi.owner() + "#" + emi.descriptor())) {
                            results.add(emi);
                        }
                    }
                }
            }
            String suffix = "/" + simpleName + ";";
            for (Map.Entry<String, Map<String, List<ExtensionMethodInfo>>> entry : globalExtensionMethodRegistry.entrySet()) {
                if (entry.getKey().endsWith(suffix)) {
                    Map<String, List<ExtensionMethodInfo>> map = entry.getValue();
                    if (map != null && map.containsKey(methodName)) {
                        for (ExtensionMethodInfo emi : map.get(methodName)) {
                            if (seenDescriptors.add(emi.owner() + "#" + emi.descriptor())) {
                                results.add(emi);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Resolves extension methods matching methodName for the given ownerDesc,
     * traversing the receiver's superclass and interface hierarchy.
     */
    public static List<ExtensionMethodInfo> findExtensionMethods(String ownerDesc, String methodName) {
        if (ownerDesc == null || methodName == null) return Collections.emptyList();
        String cleanOwner = TypeChecker.cleanDescriptor(ownerDesc);
        List<ExtensionMethodInfo> results = new ArrayList<>();
        Set<String> seenDescriptors = new HashSet<>();

        // 1. Direct match on receiver type
        collectExtensionCandidates(cleanOwner, methodName, results, seenDescriptors);

        // 2. Class hierarchy traversal for reference types
        if (TypeChecker.isClassType(cleanOwner)) {
            String internalName = cleanOwner.substring(1, cleanOwner.length() - 1);
            List<String> hierarchy = ClassMetadataCache.getClassHierarchy(internalName);
            for (String superName : hierarchy) {
                String superDesc = OceanTypeSystem.wrapObjectType(superName);
                if (superDesc.equals(cleanOwner)) continue; // already checked
                collectExtensionCandidates(superDesc, methodName, results, seenDescriptors);
            }

            // All implemented interfaces across hierarchy
            Set<String> allInterfaces = new LinkedHashSet<>();
            for (String h : hierarchy) {
                allInterfaces.addAll(ClassMetadataCache.getClassInterfaces(h));
            }
            // Also check globalInterfaceRegistry directly
            String[] directIfaces = globalInterfaceRegistry.get(internalName);
            if (directIfaces != null) Collections.addAll(allInterfaces, directIfaces);

            for (String iface : allInterfaces) {
                String ifaceDesc = OceanTypeSystem.wrapObjectType(iface);
                collectExtensionCandidates(ifaceDesc, methodName, results, seenDescriptors);
            }

            // java/lang/Object fallback
            if (!cleanOwner.equals(OceanTypeSystem.OBJECT_DESC)) {
                collectExtensionCandidates(OceanTypeSystem.OBJECT_DESC, methodName, results, seenDescriptors);
            }
        }

        return results;
    }

    /**
     * Checks if a method or constructor in the given owner class is declared as varargs (ACC_VARARGS).
     */
    public static boolean isVarargsMethod(String owner, String methodName, String descriptor) {
        if (owner == null || methodName == null || descriptor == null) return false;
        String cleanOwner = TypeChecker.getInternalName(owner);
        String cleanDesc = TypeChecker.cleanDescriptor(descriptor);
        if (cleanOwner != null) {
            String curr = cleanOwner;
            java.util.Set<String> visited = new java.util.HashSet<>();
            while (curr != null && !"java/lang/Object".equals(curr) && visited.add(curr)) {
                Map<String, Integer> accessMap = globalMethodAccess.get(curr);
                if (accessMap != null) {
                    Integer acc = accessMap.get(methodName + cleanDesc);
                    if (acc == null) {
                        acc = accessMap.get(methodName + descriptor);
                    }
                    if (acc == null) {
                        acc = accessMap.get(methodName);
                    }
                    if (acc != null) {
                        return (acc & org.objectweb.asm.Opcodes.ACC_VARARGS) != 0;
                    }
                }
                curr = globalSuperClassRegistry.get(curr);
            }
        }
        return ClassMetadataCache.isVarargsMethod(cleanOwner != null ? cleanOwner : owner, methodName, descriptor);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Clears all registries and resets to initial state.
     * Must be called at the start of each full compilation pass.
     */
    public static void clearAll() {
        getSymbolRegistry().clear();
        globalFieldRegistry.clear();
        globalFieldMutability.clear();
        globalFieldStaticity.clear();
        globalFieldAccess.clear();
        globalMethodRegistry.clear();
        globalMethodGenericReturnTypeRegistry.clear();
        globalMethodStaticity.clear();
        globalMethodAccess.clear();
        globalOverloadRegistry.clear();
        globalMethodTypeParametersRegistry.clear();
        globalMethodThrowsRegistry.clear();
        globalMethodParamsRegistry.clear();
        globalExtensionMethodRegistry.clear();
        globalSuperClassRegistry.clear();
        globalInnerClassOuterMap.clear();
        globalInnerClassUsedOuterMap.clear();
        globalClassAccess.clear();
        globalInterfaceRegistry.clear();
        globalTypeParameterRegistry.clear();
        globalSuperClassGenericSignatureRegistry.clear();
        globalInterfaceGenericSignatureRegistry.clear();
        globalAbstractClassSet.clear();
        globalIsInterfaceSet.clear();
        globalSealedClassSet.clear();
        globalExplicitRestrictsSet.clear();
        globalPermittedSubclassesRegistry.clear();
        globalSubclassStatusRegistry.clear();
        globalFunctionalInterfaceRegistry.clear();
        globalListLikeOwnerRegistry.clear();
        globalMapLikeOwnerRegistry.clear();
        globalDataClassSet.clear();
        globalRecordComponents.clear();
        globalEnumConstants.clear();
        reflectionCache.clear();
        globalAsyncMethodSet.clear();
        implicitImportPrefixes = new ArrayList<>(List.of("java.lang.", "ocean.stdlib."));
        SymbolTable.clearCaches();
        IRSemanticAnalyzer.clearCaches();
        OverloadResolver.clearCaches();
        TypeChecker.clearCaches();
        IRGenerator.clearCaches();
        ClassMetadataCache.clearCaches();
        PreScanner.clearCaches();
        OceanTypeSystem.clearCaches();
        ClassMetadataCache.clearCaches();
        ocean.compiler.ir.IRConstantFolder.clearCaches();
    }

    public static boolean isMethodStatic(String owner, String name, String descriptor) {
        if (owner == null || name == null) return false;
        String cleanOwner = owner.replace('.', '/');
        if (cleanOwner.startsWith("L") && cleanOwner.endsWith(";")) {
            cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
        }
        Map<String, Boolean> statics = globalMethodStaticity.get(cleanOwner);
        if (statics != null) {
            if (descriptor != null && statics.containsKey(name + descriptor)) {
                return Boolean.TRUE.equals(statics.get(name + descriptor));
            }
            if (statics.containsKey(name)) {
                return Boolean.TRUE.equals(statics.get(name));
            }
        }
        try {
            Class<?> clazz = OceanTypeSystem.forName(cleanOwner.replace('/', '.'));
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name)) {
                    if (descriptor == null || Type.getMethodDescriptor(m).equals(descriptor)) {
                        return Modifier.isStatic(m.getModifiers());
                    }
                }
            }
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    if (descriptor == null || Type.getMethodDescriptor(m).equals(descriptor)) {
                        return Modifier.isStatic(m.getModifiers());
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static Integer getMethodAccess(String owner, String name, String descriptor) {
        if (owner == null || name == null) return null;
        String cleanOwner = owner.replace('.', '/');
        if (cleanOwner.startsWith("L") && cleanOwner.endsWith(";")) {
            cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
        }
        Map<String, Integer> accessMap = globalMethodAccess.get(cleanOwner);
        if (accessMap != null) {
            if (descriptor != null && accessMap.containsKey(name + descriptor)) {
                return accessMap.get(name + descriptor);
            }
            if (accessMap.containsKey(name)) {
                return accessMap.get(name);
            }
        }
        return null;
    }
}
