package ocean.compiler.symbol;

import ocean.compiler.TypeChecker;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe central registry managing all ClassSymbols, MethodSymbols, and FieldSymbols.
 */
public class SymbolRegistry implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Map<String, ClassSymbol> classSymbols = new ConcurrentHashMap<>();
    private final Map<String, List<ClassSymbol>> simpleNameIndex = new ConcurrentHashMap<>();

    public void registerClassSymbol(ClassSymbol symbol) {
        if (symbol == null || symbol.getInternalName() == null || symbol.getInternalName().isEmpty()) return;
        String internalName = normalizeInternalName(symbol.getInternalName());
        classSymbols.put(internalName, symbol);

        String simpleName = symbol.getSimpleName();
        if (simpleName != null && !simpleName.isEmpty()) {
            List<ClassSymbol> list = simpleNameIndex.computeIfAbsent(simpleName, k -> new CopyOnWriteArrayList<>());
            list.removeIf(s -> s.getInternalName().equals(internalName));
            list.add(symbol);
        }
    }

    public ClassSymbol getClassSymbol(String name) {
        if (name == null || name.isEmpty()) return null;
        String clean = normalizeInternalName(name);

        // 1. Direct match by internal name
        ClassSymbol symbol = classSymbols.get(clean);
        if (symbol != null) return symbol;

        // 2. Unqualified simple name lookup
        if (!clean.contains("/")) {
            List<ClassSymbol> candidates = simpleNameIndex.get(clean);
            if (candidates != null && !candidates.isEmpty()) {
                return candidates.getFirst();
            }
            // Suffix check
            for (Map.Entry<String, ClassSymbol> entry : classSymbols.entrySet()) {
                if (entry.getKey().endsWith("/" + clean) || entry.getKey().endsWith("$" + clean)) {
                    return entry.getValue();
                }
            }
        }

        return null;
    }

    public ClassSymbol getOrCreateClassSymbol(String name) {
        if (name == null || name.isEmpty()) return null;
        String clean = normalizeInternalName(name);
        ClassSymbol existing = getClassSymbol(clean);
        if (existing != null) return existing;

        ClassSymbol created = new ClassSymbol(clean);
        registerClassSymbol(created);
        return created;
    }

    public boolean hasClassSymbol(String name) {
        return getClassSymbol(name) != null;
    }

    public FieldSymbol findField(String owner, String fieldName) {
        ClassSymbol cls = getClassSymbol(owner);
        if (cls == null || fieldName == null) return null;

        // Check current class
        FieldSymbol field = cls.getField(fieldName);
        if (field != null) return field;

        // Check superclass hierarchy
        String superName = cls.getSuperClassName();
        while (superName != null && !superName.equals("java/lang/Object")) {
            ClassSymbol supCls = getClassSymbol(superName);
            if (supCls != null) {
                field = supCls.getField(fieldName);
                if (field != null) return field;
                superName = supCls.getSuperClassName();
            } else {
                break;
            }
        }

        return null;
    }

    public List<MethodSymbol> findMethods(String owner, String methodName) {
        ClassSymbol cls = getClassSymbol(owner);
        if (cls == null || methodName == null) return Collections.emptyList();

        return new ArrayList<>(cls.getMethods(methodName));
    }

    public MethodSymbol findMethod(String owner, String methodName, String descriptor) {
        ClassSymbol cls = getClassSymbol(owner);
        if (cls == null || methodName == null || descriptor == null) return null;

        String cleanDesc = TypeChecker.cleanDescriptor(descriptor);
        for (MethodSymbol m : cls.getMethods(methodName)) {
            if (TypeChecker.cleanDescriptor(m.getDescriptor()).equals(cleanDesc)) {
                return m;
            }
        }
        return null;
    }

    public List<String> getClassHierarchy(String internalName) {
        if (internalName == null || internalName.isEmpty()) return Collections.emptyList();
        List<String> hierarchy = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        String curr = normalizeInternalName(internalName);
        while (curr != null && visited.add(curr)) {
            hierarchy.add(curr);
            ClassSymbol sym = getClassSymbol(curr);
            if (sym != null) {
                curr = sym.getSuperClassName();
            } else {
                if (!curr.equals("java/lang/Object")) {
                    hierarchy.add("java/lang/Object");
                }
                break;
            }
        }
        return hierarchy;
    }

    public Map<String, ClassSymbol> getAllClassSymbols() {
        return Collections.unmodifiableMap(classSymbols);
    }

    public void remove(String internalName) {
        if (internalName == null) return;
        String clean = normalizeInternalName(internalName);
        ClassSymbol removed = classSymbols.remove(clean);
        if (removed != null) {
            List<ClassSymbol> list = simpleNameIndex.get(removed.getSimpleName());
            if (list != null) {
                list.remove(removed);
                if (list.isEmpty()) simpleNameIndex.remove(removed.getSimpleName());
            }
        }
    }

    public void clear() {
        classSymbols.clear();
        simpleNameIndex.clear();
    }

    public static String normalizeInternalName(String name) {
        if (name == null) return "";
        String clean = name.trim();
        if (clean.endsWith("?")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.startsWith("L") && clean.endsWith(";")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        if (clean.endsWith(";")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.contains("<")) {
            clean = clean.substring(0, clean.indexOf('<'));
        }
        return clean.replace('.', '/');
    }
}