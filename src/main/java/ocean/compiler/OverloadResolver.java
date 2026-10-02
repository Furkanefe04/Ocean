package ocean.compiler;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.*;
/**
 * Aşırı yüklenmiş (overloaded) metot ve kurucu çağrılarını çözümleyen motor.
 * Alt tip (subtyping), genişletme (widening) ve özgüllük (specificity) kurallarına göre en uygun metodu belirler.
 */
public class OverloadResolver {

    private static final Map<String, List<String>> paramDescriptorsCache = new ConcurrentHashMap<>();
    private static final String[] candidateNamesForSlice = new String[] { "slice", "subList", "substring", "subSequence" };
    private static final List<String> twoIntsForSlice = Arrays.asList("I", "I");
    public static void clearCaches() {
        paramDescriptorsCache.clear();
    }

    private static String cleanOwner(String owner) {
        if (owner == null) return null;
        String clean = TypeChecker.getInternalName(owner);
        if (clean != null && !clean.contains("/")) {

            CompilationSession session = CompilationSession.getActiveSession();
            if (session != null) {
                String currentClass = session.getCurrentClassFqcn();
                String resolved = OceanTypeSystem.resolveInternalClassName(clean, currentClass);
                if (resolved != null && resolved.contains("/")) {
                    return resolved;
                }
            }

            if (!CompilerRegistry.globalMethodRegistry.containsKey(clean)) {
                List<String> matches = new ArrayList<>();
                for (String fq : CompilerRegistry.globalMethodRegistry.keySet()) {
                    if (fq.endsWith("/" + clean) || fq.endsWith("$" + clean)) {
                        matches.add(fq);
                    }
                }
                if (!matches.isEmpty()) {
                    if (matches.size() == 1) {
                        return matches.getFirst();
                    }
                    if (session != null) {
                        String currentPkg = session.getCurrentPackage();
                        if (currentPkg != null && !currentPkg.isEmpty()) {
                            String pkgPrefix = currentPkg.replace('.', '/') + "/";
                            for (String m : matches) {
                                if (m.startsWith(pkgPrefix) && (m.equals(pkgPrefix + clean) || m.startsWith(pkgPrefix + clean + "$"))) {
                                    return m;
                                }
                            }
                        }
                        if (!session.activeWildcards.isEmpty()) {
                            List<String> wildcardMatches = new ArrayList<>();
                            for (String wild : session.activeWildcards) {
                                String wildPrefix = wild.replace('.', '/') + "/";
                                for (String m : matches) {
                                    if (m.equals(wildPrefix + clean) || m.startsWith(wildPrefix + clean + "$")) {
                                        wildcardMatches.add(m);
                                    }
                                }
                            }
                            if (wildcardMatches.size() == 1) {
                                return wildcardMatches.getFirst();
                            }
                        }
                    }
                    Collections.sort(matches);
                    return matches.getFirst();
                }
            }
        }
        return clean;
    }

    public static List<String> getClassHierarchy(String owner, String methodName) {
        if (owner == null || owner.isEmpty()) return Collections.emptyList();
        owner = cleanOwner(owner);
        if ("<init>".equals(methodName)) {
            return List.of(owner);
        }
        return ClassMetadataCache.getClassHierarchy(owner);
    }

    private static List<String> getReflectedMethods(String cls, String methodName) {
        if ("<init>".equals(methodName)) {
            return ClassMetadataCache.getConstructorDescriptors(cls);
        }
        return ClassMetadataCache.getPublicMethodDescriptors(cls, methodName);
    }

    private static List<String> getReflectedInterfaces(String cls) {
        return ClassMetadataCache.getClassInterfaces(cls);
    }

    public enum ResolutionStatus {
        SUCCESS,
        AMBIGUOUS,
        NOT_FOUND
    }

    public record ResolutionResult(ResolutionStatus status, String owner, String descriptor, List<String> ambiguousCandidates) {
        public static ResolutionResult success(String owner, String descriptor) {
            return new ResolutionResult(ResolutionStatus.SUCCESS, owner, descriptor, Collections.emptyList());
        }

        public static ResolutionResult ambiguous(String owner, List<String> candidates) {
            return new ResolutionResult(ResolutionStatus.AMBIGUOUS, owner, null, candidates != null ? candidates : Collections.emptyList());
        }

        public static ResolutionResult notFound(String owner) {
            return new ResolutionResult(ResolutionStatus.NOT_FOUND, owner, null, Collections.emptyList());
        }

        public boolean isSuccess() {
            return status == ResolutionStatus.SUCCESS;
        }
    }

    public record SelectionResult(String bestDescriptor, boolean isAmbiguous, List<String> ambiguousCandidates) {}

    public static String resolve(String internalOwner, String methodName, List<String> argTypes) {
        ResolutionResult res = resolveWithResult(internalOwner, methodName, argTypes);
        return (res != null && res.isSuccess()) ? res.descriptor() : null;
    }

    public static ResolutionResult resolveWithResult(String internalOwner, String methodName, List<String> argTypes) {
        CompilationSession session = CompilationSession.getActiveSession();
        internalOwner = cleanOwner(internalOwner);
        
        List<String> cleanArgs = new ArrayList<>();
        for (String t : argTypes) {
            cleanArgs.add(TypeChecker.cleanDescriptor(t));
        }
        argTypes = cleanArgs;

        List<String> classHierarchy = getClassHierarchy(internalOwner, methodName);

        Set<String> uniqueCleanDescriptors = new LinkedHashSet<>();
        Map<String, String> cleanToRawMap = new HashMap<>();

        for (String cls : classHierarchy) {
            boolean isOceanClass = CompilerRegistry.globalMethodRegistry.containsKey(cls) || CompilerRegistry.globalOverloadRegistry.containsKey(cls);

            if (isOceanClass){
                collectMethodDescriptor(cls, methodName, uniqueCleanDescriptors, cleanToRawMap);
            } else {
                for (String mDesc : getReflectedMethods(cls, methodName)) {
                    String cd = TypeChecker.cleanDescriptor(mDesc);
                    uniqueCleanDescriptors.add(cd);
                    cleanToRawMap.putIfAbsent(cd, mDesc);
                }
            }
        }

        if (!methodName.equals("<init>")) {
            Set<String> visitedInterfaces = new HashSet<>();
            List<String> interfacesToSearch = new ArrayList<>();

            for (String cls : classHierarchy) {
                boolean isOceanClass = CompilerRegistry.globalMethodRegistry.containsKey(cls) ||
                                       CompilerRegistry.globalOverloadRegistry.containsKey(cls);
                if (isOceanClass) {
                    String[] ifaces = CompilerRegistry.globalInterfaceRegistry.get(cls);
                    if (ifaces != null) {
                        for (String i : ifaces) {
                            String cleanI = cleanOwner(i);
                            if (cleanI != null) interfacesToSearch.add(cleanI);
                        }
                    }
                } else {
                    interfacesToSearch.addAll(getReflectedInterfaces(cls));
                }
            }

            for (String inter : interfacesToSearch) {
                harvestInterfaceMethodsRecursive(inter, methodName, visitedInterfaces, uniqueCleanDescriptors, cleanToRawMap);
            }

            if (!interfacesToSearch.isEmpty()) {
                for (String mDesc : getReflectedMethods("java/lang/Object", methodName)) {
                    String cd = TypeChecker.cleanDescriptor(mDesc);
                    uniqueCleanDescriptors.add(cd);
                    cleanToRawMap.putIfAbsent(cd, mDesc);
                }
            }
        }
        if (!uniqueCleanDescriptors.isEmpty()) {
            SelectionResult selection = findBestDescriptor(internalOwner, methodName, new ArrayList<>(uniqueCleanDescriptors), argTypes, session);
            if (selection.isAmbiguous()) {
                List<String> rawCandidates = new ArrayList<>();
                for (String cd : selection.ambiguousCandidates()) {
                    rawCandidates.add(cleanToRawMap.getOrDefault(cd, cd));
                }
                return ResolutionResult.ambiguous(internalOwner, rawCandidates);
            }
            if (selection.bestDescriptor() != null) {
                String best = selection.bestDescriptor();
                return ResolutionResult.success(internalOwner, cleanToRawMap.getOrDefault(best, best));
            }
        }

        /*if ("java/lang/Object".equals(internalOwner) || internalOwner == null) {
            if ("get".equals(methodName) && argTypes.size() == 1) {
                String argType = TypeChecker.cleanDescriptor(argTypes.getFirst());
                String targetOwner = TypeChecker.isIntegerType(argType) ? "ocean/stdlib/OceanList" : "ocean/stdlib/OceanMap";
                List<String> fbDescs = new ArrayList<>();
                for (String mDesc : getReflectedMethods(targetOwner, "get")) {
                    fbDescs.add(TypeChecker.cleanDescriptor(mDesc));
                }
                SelectionResult selection = findBestDescriptor(targetOwner, "get", fbDescs, argTypes, session);
                if (selection.bestDescriptor() != null) {
                    return ResolutionResult.success(targetOwner, selection.bestDescriptor());
                }
            }
        }*/

        return ResolutionResult.notFound(internalOwner);
    }

    private static boolean isMethodStatic(String owner, String methodName, String descriptor) {
        if (owner == null || methodName == null) return false;
        String clean = cleanOwner(owner);
        Map<String, Boolean> staticMap = CompilerRegistry.globalMethodStaticity.get(clean);
        if (staticMap != null) {
            if (descriptor != null && staticMap.containsKey(methodName + descriptor)) {
                return Boolean.TRUE.equals(staticMap.get(methodName + descriptor));
            }
            if (staticMap.containsKey(methodName)) {
                return Boolean.TRUE.equals(staticMap.get(methodName));
            }
        }
        Map<String, Integer> accessMap = CompilerRegistry.globalMethodAccess.get(clean);
        if (accessMap != null) {
            if (descriptor != null && accessMap.containsKey(methodName + descriptor)) {
                return (accessMap.get(methodName + descriptor) & Modifier.STATIC) != 0;
            }
            if (accessMap.containsKey(methodName)) {
                return (accessMap.get(methodName) & Modifier.STATIC) != 0;
            }
        }
        try {
            Class<?> cls = OceanTypeSystem.forName(clean.replace('/', '.'));
            if (cls != null) {
                for (Method m : cls.getMethods()) {
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

    private static void harvestInterfaceMethodsRecursive(String inter, String methodName, Set<String> visited, Set<String> targetSet, Map<String, String> cleanToRawMap) {
        inter = cleanOwner(inter);
        if (inter == null || !visited.add(inter)) return;

        collectMethodDescriptor(inter, methodName, targetSet, cleanToRawMap, true);

        for (String mDesc : getReflectedMethods(inter, methodName)) {
            if (isMethodStatic(inter, methodName, mDesc)) continue;
            String cd = TypeChecker.cleanDescriptor(mDesc);
            targetSet.add(cd);
            cleanToRawMap.putIfAbsent(cd, mDesc);
        }

        String[] parentInterfaces = CompilerRegistry.globalInterfaceRegistry.get(inter);
        if (parentInterfaces != null) {
            for (String parentInter : parentInterfaces) {
                harvestInterfaceMethodsRecursive(cleanOwner(parentInter), methodName, visited, targetSet, cleanToRawMap);
            }
        }
        for (String parentInter : getReflectedInterfaces(inter)) {
            harvestInterfaceMethodsRecursive(cleanOwner(parentInter), methodName, visited, targetSet, cleanToRawMap);
        }
    }

    private static void collectMethodDescriptor(String inter, String methodName, Set<String> targetSet, Map<String, String> cleanToRawMap) {
        collectMethodDescriptor(inter, methodName, targetSet, cleanToRawMap, false);
    }

    private static void collectMethodDescriptor(String inter, String methodName, Set<String> targetSet, Map<String, String> cleanToRawMap, boolean excludeStatic) {
        ocean.compiler.symbol.ClassSymbol classSym = CompilerRegistry.getClassSymbol(inter);
        if (classSym != null) {
            for (ocean.compiler.symbol.MethodSymbol m : classSym.getMethods(methodName)) {
                String d = m.getDescriptor();
                if (excludeStatic && (m.isStatic() || isMethodStatic(inter, methodName, d))) continue;
                String cd = TypeChecker.cleanDescriptor(d);
                targetSet.add(cd);
                cleanToRawMap.putIfAbsent(cd, d);
            }
        }

        Map<String, List<String>> overloadsMap = CompilerRegistry.globalOverloadRegistry.get(inter);
        if (overloadsMap != null && overloadsMap.containsKey(methodName)) {
            for (String d : overloadsMap.get(methodName)) {
                if (excludeStatic && isMethodStatic(inter, methodName, d)) continue;
                String cd = TypeChecker.cleanDescriptor(d);
                targetSet.add(cd);
                cleanToRawMap.putIfAbsent(cd, d);
            }
        }

        Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(inter);
        if (methods != null && methods.containsKey(methodName)) {
            String d = methods.get(methodName);
            if (!excludeStatic || !isMethodStatic(inter, methodName, d)) {
                String cd = TypeChecker.cleanDescriptor(d);
                targetSet.add(cd);
                cleanToRawMap.putIfAbsent(cd, d);
            }
        }
    }

    private static SelectionResult findBestDescriptor(String owner, String methodName, List<String> descriptors, List<String> argTypes, CompilationSession session) {
        // Phase 1: Strict matching (no boxing/unboxing, no varargs)
        List<String> phase1Candidates = new ArrayList<>();
        for (String desc : descriptors) {
            if (isStrictlyCompatible(owner, methodName, desc, argTypes, session)) {
                phase1Candidates.add(desc);
            }
        }
        debugLog("[DEBUG] Phase 1 candidates: " + phase1Candidates);
        if (!phase1Candidates.isEmpty()) {
            SelectionResult best = selectMostSpecific(phase1Candidates, owner, methodName, session);
            debugLog("[DEBUG] Phase 1 selectMostSpecific: " + best);
            return best;
        }

        // Phase 2: Loose matching (autoboxing/unboxing allowed, but no varargs)
        List<String> phase2Candidates = new ArrayList<>();
        for (String desc : descriptors) {
            if (isLooselyCompatible(owner, methodName, desc, argTypes, session)) {
                phase2Candidates.add(desc);
            }
        }
        debugLog("[DEBUG] Phase 2 candidates: " + phase2Candidates);
        if (!phase2Candidates.isEmpty()) {
            SelectionResult best = selectMostSpecific(phase2Candidates, owner, methodName, session);
            debugLog("[DEBUG] Phase 2 selectMostSpecific: " + best);
            return best;
        }

        // Phase 3: Varargs matching
        List<String> phase3Candidates = new ArrayList<>();
        for (String desc : descriptors) {
            if (isVarargsCompatible(owner, methodName, desc, argTypes, session)) {
                phase3Candidates.add(desc);
            }
        }
        debugLog("[DEBUG] Phase 3 candidates: " + phase3Candidates);
        if (!phase3Candidates.isEmpty()) {
            SelectionResult best = selectMostSpecific(phase3Candidates, owner, methodName, session);
            debugLog("[DEBUG] Phase 3 selectMostSpecific: " + best);
            return best;
        }

        return new SelectionResult(null, false, Collections.emptyList());
    }

    private static boolean isStrictlyCompatible(String owner, String methodName, String desc, List<String> argTypes, CompilationSession session) {
        if (isVarargs(owner, methodName, desc)) return false;
        List<String> paramTypes = getParameterDescriptors(desc);
        if (paramTypes.size() != argTypes.size()) return false;
        for (int i = 0; i < argTypes.size(); i++) {
            String arg = argTypes.get(i);
            String param = paramTypes.get(i);
            if (arg.equals(param)) continue;
            if ("null".equals(arg)) {
                if (!TypeChecker.isPrimitive(param)) continue;
                return false;
            }
            if (TypeChecker.isPrimitive(arg) && TypeChecker.isPrimitive(param)) {
                if (TypeChecker.canWiden(arg, param)) continue;
            } else if (!TypeChecker.isPrimitive(arg) && !TypeChecker.isPrimitive(param)) {
                if (isRefAssignable(param, arg, session)) continue;
            }
            return false;
        }
        return true;
    }

    private static boolean isLooselyCompatible(String owner, String methodName, String desc, List<String> argTypes, CompilationSession session) {
        if (isVarargs(owner, methodName, desc)) return false;
        List<String> paramTypes = getParameterDescriptors(desc);
        if (paramTypes.size() != argTypes.size()) return false;
        for (int i = 0; i < argTypes.size(); i++) {
            String arg = argTypes.get(i);
            String param = paramTypes.get(i);
            if (!isParamAssignable(param, arg, session)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isVarargsCompatible(String owner, String methodName, String desc, List<String> argTypes, CompilationSession session) {
        if (!isVarargs(owner, methodName, desc)) return false;
        List<String> paramTypes = getParameterDescriptors(desc);
        int normalCount = paramTypes.size() - 1;
        if (argTypes.size() < normalCount) return false;
        for (int i = 0; i < normalCount; i++) {
            if (!isParamAssignable(paramTypes.get(i), argTypes.get(i), session)) {
                return false;
            }
        }
        String varargArray = paramTypes.get(normalCount);
        String varargElement = TypeChecker.cleanDescriptor(varargArray.startsWith("[") ? varargArray.substring(1) : varargArray);
        if (argTypes.size() == paramTypes.size()) {
            String lastArg = argTypes.get(normalCount);
            if (isParamAssignable(varargArray, lastArg, session)) return true;
        }
        for (int i = normalCount; i < argTypes.size(); i++) {
            if (!isParamAssignable(varargElement, argTypes.get(i), session)) return false;
        }
        return true;
    }

    private static boolean isParamAssignable(String param, String arg, CompilationSession session) {
        if (param == null || arg == null) return false;
        if (param.equals(arg)) return true;
        if ("null".equals(arg)) return !TypeChecker.isPrimitive(param);
        if (TypeChecker.isObjectType(param)) return true;
        if (TypeChecker.isPrimitive(param) && TypeChecker.isPrimitive(arg)) {
            return TypeChecker.isAssignable(param, arg, session);
        }
        if (TypeChecker.isPrimitive(arg)) {
            String boxed = TypeChecker.box(arg);
            if (boxed != null) {
                if (param.equals(boxed) || param.equals(OceanTypeSystem.NUMBER_DESC)) return true;
                if (session != null && session.isSubType(boxed, param)) return true;
                if (isRefAssignable(param, boxed, session)) return true;
            }
        }
        if (TypeChecker.isPrimitive(param) && !TypeChecker.isPrimitive(arg)) {
            String unboxed = TypeChecker.unbox(arg);
            if (unboxed != null) {
                if (unboxed.equals(param) || TypeChecker.canWiden(unboxed, param)) {
                    return true;
                }
            }
        }
        if (isRefAssignable(param, arg, session)) return true;
        return TypeChecker.isBoxedEquivalent(param, arg) || TypeChecker.isBoxedEquivalent(arg, param);
    }

    private static boolean isRefAssignable(String target, String source, CompilationSession session) {
        if (target.equals(source)) return true;
        if (TypeChecker.isObjectType(target)) return true;
        if ("null".equals(source)) return !TypeChecker.isPrimitive(target);
        if (session != null && session.isSubType(source, target)) return true;
        if (target.startsWith("L") && source.startsWith("L")) {
            String sub = source.substring(1, source.length() - 1);
            String sup = target.substring(1, target.length() - 1);
            return ClassMetadataCache.isSubtype(sub, sup);
        }
        return OverloadResolver.isAssignableForArray(target, source);
    }

    private static boolean isAssignableForArray(String target, String source) {
        if (!source.startsWith("[")) return false;
        if (target.equals(source)) return true;
        return TypeChecker.isObjectType(target) || target.equals("Ljava/io/Serializable;") || target.equals("Ljava/lang/Cloneable;");
    }

    private static SelectionResult selectMostSpecific(List<String> candidates, String owner, String methodName, CompilationSession session) {
        if (candidates.isEmpty()) return new SelectionResult(null, false, Collections.emptyList());
        if (candidates.size() == 1) return new SelectionResult(candidates.getFirst(), false, Collections.emptyList());

        List<String> maximallySpecific = new ArrayList<>();
        for (String cand1 : candidates) {
            boolean dominated = false;
            for (String cand2 : candidates) {
                if (!cand1.equals(cand2) && isStrictlyMoreSpecific(cand2, cand1, owner, methodName, session)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated && !maximallySpecific.contains(cand1)) {
                maximallySpecific.add(cand1);
            }
        }

        if (maximallySpecific.size() == 1) {
            return new SelectionResult(maximallySpecific.getFirst(), false, Collections.emptyList());
        }

        if (maximallySpecific.size() > 1) {
            // Tiebreaker on return type if parameter types are identical
            String first = maximallySpecific.getFirst();
            List<String> firstParams = getParameterDescriptors(first);
            boolean allSameParams = true;
            for (String m : maximallySpecific) {
                if (!getParameterDescriptors(m).equals(firstParams)) {
                    allSameParams = false;
                    break;
                }
            }
            if (allSameParams) {
                String bestRetDesc = null;
                for (String m : maximallySpecific) {
                    if (bestRetDesc == null) {
                        bestRetDesc = m;
                    } else {
                        String ret1 = m.substring(m.indexOf(')') + 1);
                        String retBest = bestRetDesc.substring(bestRetDesc.indexOf(')') + 1);
                        if (isRefAssignable(retBest, ret1, session)) {
                            bestRetDesc = m;
                        }
                    }
                }
                if (bestRetDesc != null) {
                    return new SelectionResult(bestRetDesc, false, Collections.emptyList());
                }
            }
            return new SelectionResult(null, true, maximallySpecific);
        }

        return new SelectionResult(null, false, Collections.emptyList());
    }

    private static boolean isStrictlyMoreSpecific(String desc1, String desc2, String owner, String methodName, CompilationSession session) {
        boolean more = isMoreSpecific(desc1, desc2, owner, methodName, session);
        boolean less = isMoreSpecific(desc2, desc1, owner, methodName, session);
        return more && !less;
    }

    private static boolean isMoreSpecific(String desc1, String desc2, String owner, String methodName, CompilationSession session) {
        List<String> params1 = getParameterDescriptors(desc1);
        List<String> params2 = getParameterDescriptors(desc2);
        boolean var1 = isVarargs(owner, methodName, desc1);
        boolean var2 = isVarargs(owner, methodName, desc2);

        // Rule 1: Non-varargs is always more specific than varargs
        if (!var1 && var2) return true;
        if (var1 && !var2) return false;

        // Rule 2: Both are non-varargs
        if (!var1) {
            if (params1.size() != params2.size()) return false;
            for (int i = 0; i < params1.size(); i++) {
                String p1 = params1.get(i);
                String p2 = params2.get(i);
                if (p1.equals(p2)) continue;
                if (!isParameterTypeMoreSpecific(p1, p2, session)) {
                    return false;
                }
            }
            return true;
        }

        // Rule 3: Both are varargs
        int len1 = params1.size();
        int len2 = params2.size();
        int maxLen = Math.max(len1, len2);
        for (int i = 0; i < maxLen; i++) {
            String p1 = getVarargParamType(params1, i);
            String p2 = getVarargParamType(params2, i);
            if (p1.equals(p2)) continue;
            if (!isParameterTypeMoreSpecific(p1, p2, session)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isParameterTypeMoreSpecific(String p1, String p2, CompilationSession session) {
        if (p1.equals(p2)) return true;
        if (TypeChecker.isPrimitive(p1) && TypeChecker.isPrimitive(p2)) {
            return TypeChecker.canWiden(p1, p2);
        }
        if (!TypeChecker.isPrimitive(p1) && !TypeChecker.isPrimitive(p2)) {
            return isRefAssignable(p2, p1, session);
        }
        if (TypeChecker.isPrimitive(p1) && !TypeChecker.isPrimitive(p2)) {
            String boxed1 = TypeChecker.box(p1);
            return isRefAssignable(p2, boxed1, session);
        }
        if (!TypeChecker.isPrimitive(p1) && TypeChecker.isPrimitive(p2)) {
            String unboxed1 = TypeChecker.unbox(p1);
            if (unboxed1 != null) {
                return TypeChecker.canWiden(unboxed1, p2);
            }
        }
        return false;
    }

    private static String getVarargParamType(List<String> params, int index) {
        if (params == null || params.isEmpty() || index < 0) return null;
        int normalCount = params.size() - 1;
        if (index < normalCount) return params.get(index);
        String varargArray = params.get(normalCount);
        if (varargArray != null && varargArray.startsWith("[")) return varargArray.substring(1);
        return varargArray;
    }

    private static boolean isVarargs(String owner, String methodName, String desc) {
        if (owner == null) {
            if (methodName != null && desc != null) {
                String cleanDesc = TypeChecker.cleanDescriptor(desc);
                for (java.util.Map.Entry<String, java.util.Map<String, Integer>> entry : CompilerRegistry.globalMethodAccess.entrySet()) {
                    Integer acc = entry.getValue().get(methodName + cleanDesc);
                    if (acc != null) {
                        return (acc & org.objectweb.asm.Opcodes.ACC_VARARGS) != 0;
                    }
                }
            }
            return false;
        }
        return CompilerRegistry.isVarargsMethod(owner, methodName, desc);
    }

    private static List<String> getParameterDescriptors(String methodDesc) {
        return paramDescriptorsCache.computeIfAbsent(methodDesc, k -> {
            List<String> params = new ArrayList<>();
            int i = methodDesc.indexOf('(') + 1;
            int end = methodDesc.indexOf(')');
            while (i < end) {
                int start = i;
                while (methodDesc.charAt(i) == '[')
                    i++;
                if (methodDesc.charAt(i) == 'L') {
                    i = methodDesc.indexOf(';', i) + 1;
                } else {
                    i++;
                }
                params.add(methodDesc.substring(start, i));
            }
            return params;
        });
    }

    private static List<Constructor<?>> getAllConstructors(Class<?> clazz) {
        try {
            List<Constructor<?>> list = new ArrayList<>();
            for (Constructor<?> c : clazz.getDeclaredConstructors()) {
                if (c.isSynthetic()) continue;
                list.add(c);
            }
            return list;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    public record SliceMethodResolution(String methodName, String descriptor, boolean isStatic) {
    }

    public static SliceMethodResolution findSliceMethod(String cleanOwner) {
        if (cleanOwner == null) return null;
        cleanOwner = TypeChecker.cleanDescriptor(cleanOwner);
        if (TypeChecker.isClassType(cleanOwner)) cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);

        String[] preferredNames;
        if (cleanOwner.equals("java/lang/String") || ClassMetadataCache.isSubtype(cleanOwner, "java/lang/CharSequence")) {
            preferredNames = new String[] { "substring", "subSequence" };
        } else if (cleanOwner.equals("java/util/List") || ClassMetadataCache.isSubtype(cleanOwner, "java/util/List")
                || cleanOwner.startsWith("ocean/stdlib/Ocean")) {
            preferredNames = new String[] { "slice", "subList" };
        } else {
            preferredNames = candidateNamesForSlice;
        }

        for (String name : preferredNames) {
            String desc = resolve(cleanOwner, name, twoIntsForSlice);
            if (desc != null) {
                boolean isStatic = false;
                Map<String, Boolean> staticity = CompilerRegistry.globalMethodStaticity.get(cleanOwner);
                if (staticity != null && staticity.containsKey(name)) {
                    isStatic = staticity.get(name);
                }
                return new SliceMethodResolution(name, desc, isStatic);
            }
        }
        return null;
    }

    private static void debugLog(String msg) {
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println(msg);
        }
    }
}
