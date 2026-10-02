package ocean.compiler;

import org.antlr.v4.runtime.ParserRuleContext;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
/**
 * Ocean derleyicisinin sözlüksel kapsam (lexical scope) ve sembol yönetim tablosu.
 * Değişkenlerin, sabitlerin, tiplerin ve yerel değişken indekslerinin (JVM local variable slots)
 * kapsam bazlı yaşam döngüsünü ve aramasını yönetir.
 */
public class SymbolTable {

    private static class VariableInfo {
        final int index;
        String type;
        String rawType;
        final String originalType;
        final boolean isFinal;
        boolean isMutated = false;
        boolean isCaptured = false;
        boolean isInitialized = false;
        boolean isParameter = false;
        
        VariableInfo(int index, String type, String rawType, boolean isFinal) {
            this.index = index;
            this.type = type;
            this.rawType = rawType;
            this.originalType = type;
            this.isFinal = isFinal;
        }
    }

    private static final Pattern GENERIC_NAME_PATTERN = Pattern.compile("^[A-Z][0-9]?$");

    private final Deque<Map<String, VariableInfo>> scopes = new ArrayDeque<>();
    private final Deque<Integer> indexStack = new ArrayDeque<>();
    private int nextIndex = 0;
    private final boolean isStaticContext;
    private final Map<String, String> imports;
    private final Set<String> typeParams;
    private final CompilationSession session;

    // [LEGACY / OBSOLETE] SEARCH_PACKAGES is unused and disabled.
    // Ocean package search logic matches Java exactly (implicit imports only for java.lang).
    // Loop resolution using this list was causing severe compiler slowdowns.
    /*
    public static final String[] SEARCH_PACKAGES = { 
        "java.util.", "java.io.", "java.net.", "java.math.", "java.time.", "java.lang.",
        "java.util.concurrent.", "java.util.zip.", "java.sql.", "java.text."
    };
    */

    public SymbolTable() {
        this(true, new HashMap<>(), new HashSet<>(), null);
    }

    // New Session-based constructor (Recommended for V3 pipeline)
    public SymbolTable(boolean isStatic, Map<String, String> imports, Set<String> typeParams, CompilationSession session) {
        this.isStaticContext = isStatic;
        this.imports = imports;
        this.typeParams = typeParams != null ? new HashSet<>(typeParams) : new HashSet<>();
        this.session = session;
        // Static olmayan metotlarda 0. indeks 'this' nesnesidir.
        if (!isStatic) {
            nextIndex = 1;
        }
        enterScope(); // method level scope
    }

    public CompilationSession getSession() {
        return session;
    }

    public Set<String> getTypeParams() {
        return typeParams;
    }

    // [LEGACY] Old constructor (Kept for compatibility)
    public SymbolTable(boolean isStatic, Map<String, String> imports, Set<String> typeParams) {
        this(isStatic, imports, typeParams, null);
    }

    public boolean isStaticContext() {
        return isStaticContext;
    }

    public void enterScope() {
        scopes.push(new HashMap<>());
        indexStack.push(nextIndex);
    }

    public void setNextIndex(int nextIndex) {
        this.nextIndex = nextIndex;
        if (!indexStack.isEmpty()) {
            indexStack.pop();
            indexStack.push(nextIndex);
        }
    }

    public void exitScope() {
        if (scopes.size() > 1) {
            scopes.pop();
            if (!indexStack.isEmpty()) {
                nextIndex = indexStack.pop();
            }
        } else {
            // Mismatched enterScope/exitScope — compiler bug, not user error
            reportError("exitScope() called with no matching enterScope (scope stack underflow)");
            throw new CompilationException("exitScope() called with no matching enterScope (scope stack underflow)");
        }
    }

    public int getScopeDepth() {
        return scopes.size();
    }

    public boolean isDeclaredInCurrentScope(String name) {
        Map<String, VariableInfo> currentScope = scopes.peek();
        return currentScope != null && currentScope.containsKey(name);
    }

    public boolean isDeclaredInMethodScope(String name, int methodBaseScopeDepth) {
        int currentDepth = scopes.size();
        for (Map<String, VariableInfo> scope : scopes) {
            if (currentDepth >= methodBaseScopeDepth) {
                if (scope.containsKey(name)) {
                    return true;
                }
            } else {
                break;
            }
            currentDepth--;
        }
        return false;
    }

    public int getDeclarationScopeDepth(String name) {
        int depth = scopes.size();
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                return depth;
            }
            depth--;
        }
        return -1;
    }

    public int declareVariable(String name, String type) {
        return declareVariable(name, type, false);
    }

    public int declareVariable(String name, String type, boolean isFinal) {
        String descriptor = getDescriptor(type, imports, typeParams);
        Map<String, VariableInfo> currentScope = scopes.peek();
        if (currentScope == null) {
            enterScope();
            currentScope = scopes.peek();
        }
        
        if (name != null && name.equals("_")) {
            int allocatedIndex = nextIndex;
            if (descriptor.equals("D") || descriptor.equals("J")) {
                nextIndex += 2;
            } else {
                nextIndex++;
            }
            return allocatedIndex;
        }

        if (currentScope != null && currentScope.containsKey(name)) {
            reportError("Variable '" + name + "' is already defined in this scope.");
            return currentScope.get(name).index;
        }

        int allocatedIndex = nextIndex;
        if (currentScope != null) {
            currentScope.put(name, new VariableInfo(allocatedIndex, descriptor, type, isFinal));
        }
        if (descriptor.equals("D") || descriptor.equals("J")) {
            nextIndex += 2;
        } else {
            nextIndex++;
        }
        return allocatedIndex;
    }

    public int declareVariable(String name, String type, boolean isFinal, boolean isInitialized) {
        int idx = declareVariable(name, type, isFinal);
        if (isInitialized) {
            markInitialized(name);
        }
        return idx;
    }

    public int declareParameter(String name, String type) {
        return declareParameter(name, type, false);
    }

    public int declareParameter(String name, String type, boolean isFinal) {
        int idx = declareVariable(name, type, isFinal);
        Map<String, VariableInfo> currentScope = scopes.peek();
        if (currentScope != null && currentScope.containsKey(name)) {
            VariableInfo info = currentScope.get(name);
            info.isParameter = true;
            info.isInitialized = true;
        }
        return idx;
    }

    public boolean isParameter(String name) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name).isParameter;
            }
        }
        return false;
    }

    public void markInitialized(String name) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                scope.get(name).isInitialized = true;
                return;
            }
        }
    }

    public void setInitialized(String name, boolean initialized) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                VariableInfo info = scope.get(name);
                if (info.isParameter) {
                    info.isInitialized = true;
                } else {
                    info.isInitialized = initialized;
                }
                return;
            }
        }
    }

    public Map<String, Boolean> getInitializationSnapshot() {
        Map<String, Boolean> snapshot = new HashMap<>();
        for (Map<String, VariableInfo> scope : scopes) {
            for (Map.Entry<String, VariableInfo> entry : scope.entrySet()) {
                snapshot.putIfAbsent(entry.getKey(), entry.getValue().isParameter || entry.getValue().isInitialized);
            }
        }
        return snapshot;
    }

    public void restoreInitializationSnapshot(Map<String, Boolean> snapshot) {
        if (snapshot == null) return;
        for (Map<String, VariableInfo> scope : scopes) {
            for (Map.Entry<String, VariableInfo> entry : scope.entrySet()) {
                if (entry.getValue().isParameter) {
                    entry.getValue().isInitialized = true;
                    continue;
                }
                Boolean state = snapshot.get(entry.getKey());
                if (state != null) {
                    entry.getValue().isInitialized = state;
                }
            }
        }
    }

    public Map<String, String> getTypeSnapshot() {
        Map<String, String> snapshot = new HashMap<>();
        for (Map<String, VariableInfo> scope : scopes) {
            for (Map.Entry<String, VariableInfo> entry : scope.entrySet()) {
                snapshot.putIfAbsent(entry.getKey(), entry.getValue().type);
            }
        }
        return snapshot;
    }

    public void restoreTypeSnapshot(Map<String, String> snapshot) {
        if (snapshot == null) return;
        for (Map<String, VariableInfo> scope : scopes) {
            for (Map.Entry<String, VariableInfo> entry : scope.entrySet()) {
                String savedType = snapshot.get(entry.getKey());
                if (savedType != null) {
                    if (!savedType.contains("<") && entry.getValue().type != null && entry.getValue().type.contains("<")) {
                        String clean = TypeChecker.cleanDescriptor(savedType);
                        if (TypeChecker.isListOrCollectionType(clean, getEffectiveSession()) || TypeChecker.isMapType(clean)) {
                            continue;
                        }
                    }
                    entry.getValue().type = savedType;
                    entry.getValue().rawType = savedType;
                }
            }
        }
    }

    public void mergeInitializationSnapshots(Map<String, Boolean> thenSnap, Map<String, Boolean> elseSnap) {
        if (thenSnap == null || elseSnap == null) return;
        for (Map<String, VariableInfo> scope : scopes) {
            for (Map.Entry<String, VariableInfo> entry : scope.entrySet()) {
                if (entry.getValue().isParameter) {
                    entry.getValue().isInitialized = true;
                    continue;
                }
                String varName = entry.getKey();
                boolean inThen = Boolean.TRUE.equals(thenSnap.get(varName));
                boolean inElse = Boolean.TRUE.equals(elseSnap.get(varName));
                entry.getValue().isInitialized = (inThen && inElse);
            }
        }
    }

    public boolean isInitialized(String name) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                VariableInfo info = scope.get(name);
                if (info.isParameter) return true;
                return info.isInitialized;
            }
        }
        return false;
    }

    public boolean isFinal(String name) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name).isFinal;
            }
        }
        return false;
    }

    public int getIndex(String name) {
        if (name.equals("originalMap") || name.equals("parsedMap") || name.equals("parsedVal")) {
            if (Boolean.getBoolean("ocean.debug")) {
                System.out.println("[DEBUG] SymbolTable.getIndex(" + name + "):");
                for (Map<String, VariableInfo> scope : scopes) {
                    System.out.println("  Scope keys=" + scope.keySet());
                }
            }
        }
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name).index;
            }
        }
        return -1;
    }

    public String getType(String name) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name).type;
            }
        }
        return null; // Return null if not declared
    }

    public String getOriginalType(String name) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name).originalType;
            }
        }
        return null; // Return null if not declared
    }

    public String getRawType(String name) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name).rawType;
            }
        }
        return null; // Return null if not declared
    }

    public void setType(String name, String type) {
        String descriptor = getDescriptor(type, imports, typeParams);
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                VariableInfo info = scope.get(name);
                info.type = descriptor;
                info.rawType = descriptor;
                return;
            }
        }
    }

    public void setRawType(String name, String rawType) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                scope.get(name).rawType = rawType;
                return;
            }
        }
    }

    public boolean isMutated(String name) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name).isMutated;
            }
        }
        return false;
    }

    public boolean isCaptured(String name) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                return scope.get(name).isCaptured;
            }
        }
        return false;
    }

    public void markMutated(String name) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                scope.get(name).isMutated = true;
                return;
            }
        }
    }

    public void markCaptured(String name) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                scope.get(name).isCaptured = true;
                return;
            }
        }
    }

    public void markMutated(String name, ParserRuleContext ctx) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                VariableInfo info = scope.get(name);
                info.isMutated = true;
                if (info.isCaptured) {
                    reportError(ctx, "Variable '" + name + "' is accessed from within inner class/lambda, needs to be effectively final.");
                }
                return;
            }
        }
    }

    public void markCaptured(String name, ParserRuleContext ctx) {
        for (Map<String, VariableInfo> scope : scopes) {
            if (scope.containsKey(name)) {
                VariableInfo info = scope.get(name);
                info.isCaptured = true;
                if (info.isMutated) {
                    reportError(ctx, "Variable '" + name + "' is mutated and cannot be captured by lambda (must be effectively final).");
                }
                return;
            }
        }
    }

    /**
     * Returns the next available local variable index and advances the counter by 1.
     * Use reserveLocalSlots(descriptor) for typed allocation that respects double/long 2-slot rule.
     */
    public int nextLocalIndex() {
        return nextIndex++;
    }

    /**
     * Reserves one or two local variable slots depending on the type descriptor
     * and returns the starting index. double ("D") and long ("J") require 2 slots.
     */
    public int reserveLocalSlots(String descriptor) {
        int allocated = nextIndex;
        if ("D".equals(descriptor) || "J".equals(descriptor)) {
            nextIndex += 2;
        } else {
            nextIndex++;
        }
        return allocated;
    }

    private static final Map<String, String> descriptorCache = new ConcurrentHashMap<>();
    private static final Set<String> negativeClassCache = ConcurrentHashMap.newKeySet();

    public static void clearCaches() {
        descriptorCache.clear();
        negativeClassCache.clear();
    }

    public static void removeNegativeCacheEntry(String fqcn) {
        if (fqcn != null) {
            negativeClassCache.remove(fqcn);
            negativeClassCache.remove(fqcn.replace('/', '.'));
            String simpleName = fqcn.contains("/") ? fqcn.substring(fqcn.lastIndexOf('/') + 1) : fqcn;
            negativeClassCache.remove(simpleName);
            OceanTypeSystem.removeNegativeCacheEntry(fqcn);
        }
    }

    public static String getDescriptor(String type) {
        return getDescriptor(type, null, null);
    }

    public static String getDescriptor(String type, Map<String, String> imports) {
        return getDescriptor(type, imports, null);
    }

    public static CompilationSession getEffectiveSession() {
        CompilationSession active = CompilationSession.getActiveSession();
        return active != null ? active : CompilationSession.getFallbackSession();
    }

    public static String getDescriptor(String type, Map<String, String> imports, Set<String> typeParams) {
        if (type == null || type.isEmpty()) return OceanTypeSystem.OBJECT_DESC;
        type = type.trim();

        if (typeParams != null && typeParams.contains(type)) {
            CompilationSession session = getEffectiveSession();
            if (session != null && session.getCurrentClassFqcn() != null) {
                List<CompilerRegistry.TypeParameterInfo> infos = CompilerRegistry.globalTypeParameterRegistry.get(session.getCurrentClassFqcn());
                if (infos == null) {
                    ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(session.getCurrentClassFqcn());
                    if (sym != null) {
                        infos = sym.getTypeParameters();
                    }
                }
                if (infos != null) {
                    for (CompilerRegistry.TypeParameterInfo info : infos) {
                        if (info.name.equals(type)) {
                            return info.getErasedType();
                        }
                    }
                }
            }
            return OceanTypeSystem.OBJECT_DESC;
        }

        //
        if (type.equals("?") || type.equals("*")) {
            return "*";
        }
        if (type.startsWith("?extends") || type.startsWith("? extends ") || type.startsWith("?<:") || type.startsWith("? <: ")) {
            String bound;
            if (type.startsWith("? extends ")) bound = type.substring(10).trim();
            else if (type.startsWith("?extends")) bound = type.substring(8).trim();
            else if (type.startsWith("? <: ")) bound = type.substring(5).trim();
            else bound = type.substring(3).trim();
            return "+" + getDescriptor(bound, imports, typeParams);
        }
        if (type.startsWith("?super") || type.startsWith("? super ") || type.startsWith("?>:") || type.startsWith("? >: ")) {
            String bound;
            if (type.startsWith("? super ")) bound = type.substring(8).trim();
            else if (type.startsWith("?super")) bound = type.substring(6).trim();
            else if (type.startsWith("? >: ")) bound = type.substring(5).trim();
            else bound = type.substring(3).trim();
            return "-" + getDescriptor(bound, imports, typeParams);
        }

        //
        int ampIdx = -1;
        int depth = 0;
        for (int i = 0; i < type.length(); i++) {
            char ch = type.charAt(i);
            if (ch == '<') depth++;
            else if (ch == '>') depth--;
            else if (ch == '&' && depth == 0) {
                ampIdx = i;
                break;
            }
        }
        if (ampIdx != -1) {
            String primary = type.substring(0, ampIdx).trim();
            return getDescriptor(primary, imports, typeParams);
        }

        // Fast-path for non-generic primitive types and core common types when not nullable
        if (!type.endsWith("?")) {
            switch (type) {
                case "null" -> { return OceanTypeSystem.OBJECT_DESC + "?"; }
                case "int" -> { return "I"; }
                case "void", "V" -> { return "V"; }
                case "boolean", "bool" -> { return "Z"; }
                case "long" -> { return "J"; }
                case "double" -> { return "D"; }
                case "float" -> { return "F"; }
                case "char" -> { return "C"; }
                case "byte" -> { return "B"; }
                case "short" -> { return "S"; }
                case "String" -> { return OceanTypeSystem.STRING_DESC; }
                case "Object" -> { return OceanTypeSystem.OBJECT_DESC; }

            }
        }
        
        boolean nullable = false;
        if (type.endsWith("?")) {
            nullable = true;
            type = type.substring(0, type.length() - 1).trim();

            String fastBoxed = switch (type) {
                case "int" -> "Ljava/lang/Integer;?";
                case "boolean", "bool" -> "Ljava/lang/Boolean;?";
                case "long" -> "Ljava/lang/Long;?";
                case "double" -> "Ljava/lang/Double;?";
                case "float" -> "Ljava/lang/Float;?";
                case "char" -> "Ljava/lang/Character;?";
                case "byte" -> "Ljava/lang/Byte;?";
                case "short" -> "Ljava/lang/Short;?";
                case "String", OceanTypeSystem.STRING_DESC -> "Ljava/lang/String;?";
                case "Object", OceanTypeSystem.OBJECT_DESC, "variable" -> "Ljava/lang/Object;?";
                default -> null;
            };
            if (fastBoxed != null) return fastBoxed;
        }
        
        CompilationSession session = getEffectiveSession();
        String currentClass = (session != null) ? session.getCurrentClassFqcn() : "";
        String currentPkg = (session != null) ? session.getCurrentPackage() : "";
        String cacheKey = (nullable ? "N?" : "") + type + "|" + (imports != null ? imports.hashCode() : 0)
                        + "|" + (typeParams != null ? typeParams.hashCode() : 0)
                        + "|" + (currentClass != null ? currentClass : "")
                        + "|" + (currentPkg != null ? currentPkg : "");
        String cached = descriptorCache.get(cacheKey);
        if (cached != null) return cached;

        String result = getDescriptorImpl(type, imports, typeParams);
        if (nullable) {
            if (TypeChecker.isPrimitive(result)) {
                result = TypeChecker.box(result);
            }
            result = result + "?";
        }
        descriptorCache.put(cacheKey, result);
        return result;
    }

    private static String getDescriptorImpl(String type, Map<String, String> imports, Set<String> typeParams) {
        if (type == null) return OceanTypeSystem.OBJECT_DESC;
        String prefix = "";
        String raw = type.trim();

        //
        if (raw.equals("?") || raw.equals("*")) {
            return "*";
        }
        if (raw.startsWith("?extends") || raw.startsWith("? extends ") || raw.startsWith("?<:") || raw.startsWith("? <: ")) {
            String bound;
            if (raw.startsWith("? extends ")) bound = raw.substring(10).trim();
            else if (raw.startsWith("?extends")) bound = raw.substring(8).trim();
            else if (raw.startsWith("? <: ")) bound = raw.substring(5).trim();
            else bound = raw.substring(3).trim();
            return "+" + getDescriptor(bound, imports, typeParams);
        }
        if (raw.startsWith("?super") || raw.startsWith("? super ") || raw.startsWith("?>:") || raw.startsWith("? >: ")) {
            String bound;
            if (raw.startsWith("? super ")) bound = raw.substring(8).trim();
            else if (raw.startsWith("?super")) bound = raw.substring(6).trim();
            else if (raw.startsWith("? >: ")) bound = raw.substring(5).trim();
            else bound = raw.substring(3).trim();
            return "-" + getDescriptor(bound, imports, typeParams);
        }

        //
        int ampIdx = -1;
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '<') depth++;
            else if (ch == '>') depth--;
            else if (ch == '&' && depth == 0) {
                ampIdx = i;
                break;
            }
        }
        if (ampIdx != -1) {
            String primary = raw.substring(0, ampIdx).trim();
            return getDescriptor(primary, imports, typeParams);
        }

        if (raw.startsWith("+") || raw.startsWith("-") || raw.startsWith("*")) {
            prefix = raw.substring(0, 1);
            raw = raw.substring(1).trim();
        }
        boolean isNullable = false;
        while (raw.endsWith("?")) {
            isNullable = true;
            raw = raw.substring(0, raw.length() - 1).trim();
        }
        String res = getDescriptorImplInternal(raw, imports, typeParams);
        if (isNullable && !res.endsWith("?")) {
            res = res + "?";
        }
        return prefix + res;
    }

    private static String getDescriptorImplInternal(String type, Map<String, String> imports, Set<String> typeParams) {
        if (type != null && (type.contains("<missing") || type.contains(">"))) {
            type = type.replaceAll("<missing[^>]*>", "");
            type = type.replaceAll("[>'\"\\s]+$", "");
            int openCount = 0;
            int closeCount = 0;
            for (int i = 0; i < type.length(); i++) {
                if (type.charAt(i) == '<') openCount++;
                else if (type.charAt(i) == '>') closeCount++;
            }
            while (closeCount > openCount && type.endsWith(">")) {
                type = type.substring(0, type.length() - 1);
                closeCount--;
            }
            StringBuilder typeBuilder = new StringBuilder(type);
            while (closeCount < openCount) {
                typeBuilder.append(">");
                closeCount++;
            }
            type = typeBuilder.toString();
        }
        String trimmed = null;
        if (type != null) {
            trimmed = type.trim();
        }
        if (type != null && type.contains("<") && (type.endsWith(">"))) {
            int ltIdx = type.indexOf('<');
            String base = type.substring(0, ltIdx).trim();
            List<String> args = parseArgs(type, ltIdx);

            StringBuilder sb = new StringBuilder();
            String baseDesc = getDescriptor(base, imports, typeParams);
            if (baseDesc.endsWith(";")) {
                baseDesc = baseDesc.substring(0, baseDesc.length() - 1);
            }
            sb.append(baseDesc);
            sb.append("<");
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(",");
                String argDesc = getDescriptor(args.get(i), imports, typeParams);
                if (TypeChecker.isPrimitive(argDesc)) {
                    argDesc = TypeChecker.box(argDesc);
                }
                sb.append(argDesc);
            }
            sb.append(">;");
            return sb.toString();
        }
        if (typeParams != null && typeParams.contains(trimmed)) {
            CompilationSession session = getEffectiveSession();
            if (session != null && session.getCurrentClassFqcn() != null) {
                List<CompilerRegistry.TypeParameterInfo> infos = CompilerRegistry.globalTypeParameterRegistry.get(session.getCurrentClassFqcn());
                if (infos == null) {
                    ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(session.getCurrentClassFqcn());
                    if (sym != null) {
                        infos = sym.getTypeParameters();
                    }
                }
                if (infos != null) {
                    for (CompilerRegistry.TypeParameterInfo info : infos) {
                        if (info.name.equals(trimmed)) {
                            return info.getErasedType();
                        }
                    }
                }
            }
            return OceanTypeSystem.OBJECT_DESC;
        }
        if (type != null && type.startsWith("[")) return TypeChecker.cleanDescriptor(type);
        if (type != null && type.endsWith("[]")) {
            String baseType = type.substring(0, type.length() - 2);
            return "[" + getDescriptor(baseType, imports, typeParams);
        }

        if ("null".equals(trimmed)) return OceanTypeSystem.OBJECT_DESC + "?";
        if (TypeChecker.isClassType(type)) return type;

        String trimmedType = null;
        if (type != null) {
            trimmedType = type.trim();
        }

        // 2. Explicit imports resolution
        if (imports != null && imports.containsKey(trimmedType)) {
            String path = imports.get(trimmedType);
            if (TypeChecker.isClassType(path)) return path;
            return OceanTypeSystem.wrapObjectType(path);
        }

        if (trimmedType != null && (trimmedType.contains(".") || trimmedType.contains("/"))) {
            String slType = trimmedType.replace('.', '/');
            if (CompilationSession.isSystemPackage(slType)) return OceanTypeSystem.wrapObjectType(slType);
        }

        // 3. Current Class & Enclosing Class Nested Class Resolution
        CompilationSession activeSession = getEffectiveSession();
        String currentClassFqcn = activeSession != null ? activeSession.getCurrentClassFqcn() : null;
        if (currentClassFqcn != null && trimmedType != null) {
            String curr = currentClassFqcn.replace('.', '/');
            while (!curr.isEmpty()) {
                String innerFq = curr + "$" + trimmedType.replace('.', '$');
                if (CompilerRegistry.globalMethodRegistry.containsKey(innerFq) ||
                    CompilerRegistry.globalSuperClassRegistry.containsKey(innerFq) ||
                    CompilerRegistry.globalIsInterfaceSet.contains(innerFq) ||
                    CompilerRegistry.globalClassAccess.containsKey(innerFq) ||
                    CompilerRegistry.globalDataClassSet.contains(innerFq)) {
                    return OceanTypeSystem.wrapObjectType(innerFq);
                }
                int lastDollar = curr.lastIndexOf('$');
                if (lastDollar != -1) {
                    curr = curr.substring(0, lastDollar);
                } else {
                    break;
                }
            }
        }

        // 4. Current Package Resolution
        String currentPkg = activeSession != null ? activeSession.getCurrentPackage() : null;
        if ((currentPkg == null || currentPkg.isEmpty()) && currentClassFqcn != null && currentClassFqcn.contains("/")) {
            currentPkg = currentClassFqcn.substring(0, currentClassFqcn.lastIndexOf('/')).replace('/', '.');
        }
        if (currentPkg != null && !currentPkg.isEmpty() && !currentPkg.equals("default")) {
            String pkgPath = currentPkg.replace('.', '/');
            String localFq = pkgPath + "/" + trimmedType;
            if (CompilerRegistry.globalMethodRegistry.containsKey(localFq) ||
                CompilerRegistry.globalSuperClassRegistry.containsKey(localFq) ||
                CompilerRegistry.globalIsInterfaceSet.contains(localFq) ||
                CompilerRegistry.globalClassAccess.containsKey(localFq) ||
                CompilerRegistry.globalDataClassSet.contains(localFq)) {
                return OceanTypeSystem.wrapObjectType(localFq);
            }
        } else {
            if (CompilerRegistry.globalMethodRegistry.containsKey(trimmedType) ||
                CompilerRegistry.globalSuperClassRegistry.containsKey(trimmedType) ||
                CompilerRegistry.globalIsInterfaceSet.contains(trimmedType) ||
                CompilerRegistry.globalClassAccess.containsKey(trimmedType) ||
                CompilerRegistry.globalDataClassSet.contains(trimmedType)) {
                return OceanTypeSystem.wrapObjectType(trimmedType);
            }
        }

        /*// 1b. Centralized Built-in types & Language keywords
        String builtinDesc = OceanTypeSystem.getBuiltinDescriptor(trimmedType);
        if (builtinDesc != null) return builtinDesc;*/

        if (trimmedType != null && trimmedType.length() == 1 && "IZDFJBCSV".indexOf(trimmedType.charAt(0)) >= 0) return trimmedType;

        // 4b. Standard library and java.lang resolution (ocean.stdlib.* and java.lang.*)
        String stdCandidate = OceanTypeSystem.resolveStandardClassPath(trimmedType);
        if (stdCandidate != null) {
            return OceanTypeSystem.wrapObjectType(stdCandidate);
        }

        // 5. Global registry fallback — tüm adayları topla (deterministik çözümleme)
        Set<String> allKnownFq = new LinkedHashSet<>();
        allKnownFq.addAll(CompilerRegistry.globalMethodRegistry.keySet());
        allKnownFq.addAll(CompilerRegistry.globalSuperClassRegistry.keySet());
        for (String s : CompilerRegistry.globalIsInterfaceSet) {
            if (s != null) allKnownFq.add(s);
        }
        for (String s : CompilerRegistry.globalDataClassSet) {
            if (s != null) allKnownFq.add(s);
        }
        for (String s : CompilerRegistry.globalSealedClassSet) {
            if (s != null) allKnownFq.add(s);
        }
        List<String> fallbackCandidates = new ArrayList<>();
        for (String fqName : allKnownFq) {
            if ((fqName.equals(trimmedType) || fqName.endsWith("/" + trimmedType) || fqName.endsWith("$" + trimmedType)) && isAccessibleCandidate(fqName, currentPkg)) {
                fallbackCandidates.add(fqName);
            }
        }
        if (!fallbackCandidates.isEmpty()) {
            if (fallbackCandidates.size() > 1) {
                printAmbiguousError(fallbackCandidates, activeSession, trimmedType);
            }
            return OceanTypeSystem.wrapObjectType(fallbackCandidates.getFirst());
        }

        // 6. Member path checks (inner classes or namespace dots)
        if (trimmedType != null && trimmedType.contains(".")) {
            try {
                OceanTypeSystem.forName(trimmedType);
                return OceanTypeSystem.wrapObjectType(trimmedType.replace('.', '/'));
            } catch (ClassNotFoundException ignored) {
            }

            // Direct check for dotted nested classes in local package or globally
            String dollarCandidate = trimmedType.replace('.', '$');
            if (currentPkg != null && !currentPkg.isEmpty() && !currentPkg.equals("default")) {
                String localFq = currentPkg.replace('.', '/') + "/" + dollarCandidate;
                if (CompilerRegistry.globalMethodRegistry.containsKey(localFq) ||
                    CompilerRegistry.globalSuperClassRegistry.containsKey(localFq) ||
                    CompilerRegistry.globalIsInterfaceSet.contains(localFq) ||
                    CompilerRegistry.globalClassAccess.containsKey(localFq) ||
                    CompilerRegistry.globalDataClassSet.contains(localFq) ||
                    OceanRunnerV3.currentRunClasses.contains(localFq)) {
                    return OceanTypeSystem.wrapObjectType(localFq);
                }
            } else {
                if (CompilerRegistry.globalMethodRegistry.containsKey(dollarCandidate) ||
                    CompilerRegistry.globalSuperClassRegistry.containsKey(dollarCandidate) ||
                    CompilerRegistry.globalIsInterfaceSet.contains(dollarCandidate) ||
                    CompilerRegistry.globalClassAccess.containsKey(dollarCandidate) ||
                    CompilerRegistry.globalDataClassSet.contains(dollarCandidate) ||
                    OceanRunnerV3.currentRunClasses.contains(dollarCandidate)) {
                    return OceanTypeSystem.wrapObjectType(dollarCandidate);
                }
            }

            String[] parts = trimmedType.split("\\.");
            String base = parts[0];
            if (imports != null && imports.containsKey(base)) {
                String baseResolved = imports.get(base);
                StringBuilder sb = new StringBuilder(baseResolved);
                for (int i = 1; i < parts.length; i++) {
                    sb.append("$").append(parts[i]);
                }
                String path = sb.toString();
                if (CompilationSession.isSystemPackage(path)) {
                    return OceanTypeSystem.wrapObjectType(path);
                }
                if (CompilerRegistry.globalMethodRegistry.containsKey(path) ||
                        CompilerRegistry.globalSuperClassRegistry.containsKey(path) ||
                        OceanRunnerV3.currentRunClasses.contains(path)) {
                    return OceanTypeSystem.wrapObjectType(path);
                }
                return OceanTypeSystem.wrapObjectType(path);
            }

            // Check base directly in current package before recursive call to prevent unknown type errors on base
            String pkgPrefix = (currentPkg != null && !currentPkg.isEmpty() && !currentPkg.equals("default")) ? (currentPkg.replace('.', '/') + "/") : "";
            String baseCandidate = pkgPrefix + base;
            if (CompilerRegistry.globalMethodRegistry.containsKey(baseCandidate) ||
                CompilerRegistry.globalSuperClassRegistry.containsKey(baseCandidate) ||
                CompilerRegistry.globalClassAccess.containsKey(baseCandidate) ||
                CompilerRegistry.globalDataClassSet.contains(baseCandidate) ||
                CompilerRegistry.globalIsInterfaceSet.contains(baseCandidate) ||
                OceanRunnerV3.currentRunClasses.contains(baseCandidate)) {
                StringBuilder sb = new StringBuilder(baseCandidate);
                for (int i = 1; i < parts.length; i++) {
                    sb.append("$").append(parts[i]);
                }
                return OceanTypeSystem.wrapObjectType(sb.toString());
            }

            // Add support for local package nested classes
            String baseDesc = getDescriptor(base, imports, typeParams);
            if (TypeChecker.isClassType(baseDesc)) {
                String baseResolved = baseDesc.substring(1, baseDesc.length() - 1);
                boolean isKnownGeneratedClass = CompilerRegistry.globalMethodRegistry.containsKey(baseResolved) ||
                        CompilerRegistry.globalSuperClassRegistry.containsKey(baseResolved) ||
                        CompilerRegistry.globalClassAccess.containsKey(baseResolved) ||
                        CompilerRegistry.globalDataClassSet.contains(baseResolved) ||
                        CompilerRegistry.globalIsInterfaceSet.contains(baseResolved);
                if (isKnownGeneratedClass) {
                    StringBuilder sb = new StringBuilder(baseResolved);
                    for (int i = 1; i < parts.length; i++) {
                        sb.append("$").append(parts[i]);
                    }
                    return OceanTypeSystem.wrapObjectType(sb.toString());
                }
            }
            return OceanTypeSystem.wrapObjectType(trimmedType.replace('.', '/'));
        }

        if (trimmedType != null && trimmedType.contains("/")) {
            return OceanTypeSystem.wrapObjectType(trimmedType);
        }

        // 6b. Wildcard imports resolution
        List<String> wildcardSources = new ArrayList<>();
        if (activeSession != null) {
            wildcardSources.addAll(activeSession.activeWildcards);
        }

        List<String> resolvedWildcardTypes = new ArrayList<>();
        for (String wild : wildcardSources) {
                String testFq = wild.replace(".", "/") + "/" + trimmedType;
                boolean found = false;
                String foundPath = null;
                if (CompilerRegistry.globalMethodRegistry.containsKey(testFq) ||
                    CompilerRegistry.globalSuperClassRegistry.containsKey(testFq) ||
                    CompilerRegistry.globalIsInterfaceSet.contains(testFq)) {
                    found = true;
                    foundPath = testFq;
                } else {
                    try {
                        OceanTypeSystem.forName(wild.replace("/", ".") + "." + trimmedType);
                        found = true;
                        foundPath = wild.replace(".", "/") + "/" + trimmedType;
                    } catch (ClassNotFoundException ignored) {}
                }
                if (found) {
                    resolvedWildcardTypes.add(foundPath);
                }
            }
            if (!resolvedWildcardTypes.isEmpty()) {
                if (resolvedWildcardTypes.size() > 1) {
                    printAmbiguousError(resolvedWildcardTypes,activeSession,trimmedType);
                }
                return OceanTypeSystem.wrapObjectType(resolvedWildcardTypes.getFirst());
            }

        // 7. java.lang otomatik çözümleme (Java politikasıyla aynı)
        // java.lang dışındaki HER sınıf için import zorunludur.
        String javaLangName = "java.lang." + trimmedType;
        if (!negativeClassCache.contains(javaLangName)) {
            try {
                OceanTypeSystem.forName(javaLangName);
                return "Ljava/lang/" + trimmedType + ";";
            } catch (ClassNotFoundException ignored) {
                negativeClassCache.add(javaLangName);
            }
        }

        // ocean.stdlib otomatik çözümleme
        String oceanStdlibName = "ocean.stdlib." + trimmedType;
        if (!negativeClassCache.contains(oceanStdlibName)) {
            try {
                OceanTypeSystem.forName(oceanStdlibName);
                return "Locean/stdlib/" + trimmedType + ";";
            } catch (ClassNotFoundException ignored) {
                negativeClassCache.add(oceanStdlibName);
            }
        }

        // Generic/Built-in detection
        boolean isGenericParam = (typeParams != null && typeParams.contains(trimmedType));
        if (!isGenericParam && activeSession != null && activeSession.getCurrentClassFqcn() != null) {
            String currentFqcn = activeSession.getCurrentClassFqcn();
            List<CompilerRegistry.TypeParameterInfo> infos = CompilerRegistry.globalTypeParameterRegistry.get(currentFqcn);
            if (infos != null) {
                for (CompilerRegistry.TypeParameterInfo info : infos) {
                    if (info.name.equals(trimmedType)) {
                        isGenericParam = true;
                        break;
                    }
                }
            }
            if (!isGenericParam) {
                Map<String, List<String>> methodTypeParams = CompilerRegistry.globalMethodTypeParametersRegistry.get(currentFqcn);
                if (methodTypeParams != null) {
                    for (List<String> tParams : methodTypeParams.values()) {
                        if (tParams != null && tParams.contains(trimmedType)) {
                            isGenericParam = true;
                            break;
                        }
                    }
                }
            }
        }
        if (trimmedType != null && !isGenericParam && GENERIC_NAME_PATTERN.matcher(trimmedType).matches()) {
            if (!OceanTypeSystem.hasClass("java.net." + trimmedType)
                    && !OceanTypeSystem.hasClass("java.sql." + trimmedType)
                    && !OceanTypeSystem.hasClass("java.io." + trimmedType)
                    && !OceanTypeSystem.hasClass("java.util." + trimmedType)) {
                isGenericParam = true;
            }
        }
        boolean isKnownBuiltin = false;
        if (trimmedType != null) {
            isKnownBuiltin = trimmedType.equals("Output") || trimmedType.equals("Input") || trimmedType.equals("OceanOutput") || trimmedType.equals("OceanInput");
        }
        if (!isGenericParam && !isKnownBuiltin) {
            String file = null;
            if (activeSession != null) {
                file = activeSession.getCurrentFile();
            }
            reportError(file, "Unknown type '" + trimmedType + "' could not be resolved. An explicit import is required for all classes outside java.lang and ocean.stdlib.");
        }
        return OceanTypeSystem.OBJECT_DESC;
    }

    @NotNull
    private static List<String> parseArgs(String type, int ltIdx) {
        String inner = type.substring(ltIdx + 1, type.length() - 1);

        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                args.add(inner.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < inner.length()) {
            args.add(inner.substring(start).trim());
        }
        return args;
    }

    private static boolean isAccessibleCandidate(String fqName, String currentPkg) {
        if (!CompilerRegistry.globalMethodRegistry.containsKey(fqName)) return true;
        String targetPkg = "";
        int lastSlash = fqName.lastIndexOf('/');
        if (lastSlash != -1) targetPkg = fqName.substring(0, lastSlash);
        String cleanCurrent = (currentPkg != null && !currentPkg.equals("default")) ? currentPkg.replace('.', '/') : "";
        if (!targetPkg.equals(cleanCurrent)) {
            int access = CompilerRegistry.globalClassAccess.getOrDefault(fqName, Opcodes.ACC_PUBLIC);
            return (access & Opcodes.ACC_PUBLIC) != 0;
        }
        return true;
    }

    private static void printAmbiguousError(List<String> candidates,CompilationSession activeSession,String trimmedType) {
        Collections.sort(candidates);
        String file = activeSession != null ? activeSession.getCurrentFile() : null;
        StringBuilder sb = new StringBuilder();
        sb.append("Ambiguous class reference '").append(trimmedType).append("'. Multiple candidates found via wildcard imports: ");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(candidates.get(i));
        }
        sb.append(". Please import the class explicitly.");
        reportError(file, sb.toString());
    }

    // ========== Hata / Uyarı Raporlama ==========

    private void reportError(ParserRuleContext ctx, String message) {
        reportError(session != null ? session.getCurrentFile() : null, ctx, message);
    }

    private void reportError(String message) {
        reportError((ParserRuleContext) null, message);
    }

    private static void reportError(String file, ParserRuleContext ctx, String message) {
        int line = ctx != null && ctx.start != null ? ctx.start.getLine() : 0;
        int col = ctx != null && ctx.start != null ? ctx.start.getCharPositionInLine() : 0;
        CompilerReporter.error(file, line, col, message, "SymbolTable");
    }

    private static void reportError(String file, String message) {
        reportError(file, null, message);
    }

    private void reportWarning(ParserRuleContext ctx, String message) {
        reportWarning(session != null ? session.getCurrentFile() : null, ctx, message);
    }

    private void reportWarning(String message) {
        reportWarning((ParserRuleContext) null, message);
    }

    private static void reportWarning(String file, ParserRuleContext ctx, String message) {
        int line = ctx != null && ctx.start != null ? ctx.start.getLine() : 0;
        int col = ctx != null && ctx.start != null ? ctx.start.getCharPositionInLine() : 0;
        CompilerReporter.warning(file, line, col, message, "SymbolTable");
    }

    private static void reportWarning(String file, String message) {
        reportWarning(file, null, message);
    }
}
