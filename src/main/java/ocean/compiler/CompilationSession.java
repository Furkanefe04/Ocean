package ocean.compiler;

import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Derleme oturumu boyunca (session) tutulan tüm global durumu saklar.
 * CompilerRegistry içindeki statik yapıyı nesne tabanlı hale getirir,
 * böylece paralel derleme ve test edilebilirlik sağlar.
 */
public class CompilationSession {

    public final List<CompilerReporter.Message> messages = new CopyOnWriteArrayList<>();

    private static final ThreadLocal<CompilationSession> activeSession = new ThreadLocal<>();
    private static final CompilationSession fallbackSession = new CompilationSession();

    public static CompilationSession getActiveSession() {
        return activeSession.get();
    }

    public static void setActiveSession(CompilationSession session) {
        activeSession.set(session);
    }

    public static void clearActiveSession() {
        CompilationSession session = activeSession.get();
        if (session != null) {
            session.clearThreadLocals();
        }
        activeSession.remove();
    }

    public static CompilationSession getFallbackSession() {
        return fallbackSession;
    }

    public static boolean isSystemPackage(String internalName) {
        if (internalName == null || internalName.isEmpty()) return false;
        String clean = TypeChecker.isClassType(internalName)
                ? internalName.substring(1, internalName.length() - 1)
                : internalName;
        clean = clean.replace('.', '/');
        return clean.equals("java") || clean.startsWith("java/") ||
               clean.equals("javax") || clean.startsWith("javax/") ||
               clean.equals("sun") || clean.startsWith("sun/") ||
               clean.equals("ocean/stdlib") || clean.startsWith("ocean/stdlib/");
    }

    // ── Unified Symbol Registry ──────────────────────────────────────────────
    public final ocean.compiler.symbol.SymbolRegistry symbolRegistry = new ocean.compiler.symbol.SymbolRegistry();

    // ── Field registries ──────────────────────────────────────────────────────
    public final Map<String, Map<String, String>> globalFieldRegistry = new ConcurrentHashMap<>();
    public final Map<String, Map<String, String>> globalFieldGenericSignatureRegistry = new ConcurrentHashMap<>();
    public final Map<String, Map<String, Boolean>> globalFieldMutability = new ConcurrentHashMap<>();
    public final Map<String, Map<String, Boolean>> globalFieldStaticity = new ConcurrentHashMap<>();
    public final Map<String, Map<String, Integer>> globalFieldAccess = new ConcurrentHashMap<>();

    // ── Method registries ─────────────────────────────────────────────────────
    public final Map<String, Map<String, String>> globalMethodRegistry = new ConcurrentHashMap<>();
    public final Map<String, Map<String, String>> globalMethodGenericReturnTypeRegistry = new ConcurrentHashMap<>();
    public final Map<String, Map<String, Boolean>> globalMethodStaticity = new ConcurrentHashMap<>();
    public final Map<String, Map<String, Integer>> globalMethodAccess = new ConcurrentHashMap<>();
    public final Map<String, Map<String, List<String>>> globalOverloadRegistry = new ConcurrentHashMap<>();
    public final Map<String, Map<String, List<String>>> globalMethodTypeParametersRegistry = new ConcurrentHashMap<>();
    /** className → (methodName+descriptor → list of thrown exception internal names) */
    public final Map<String, Map<String, List<String>>> globalMethodThrowsRegistry = new ConcurrentHashMap<>();
    public final Map<String, Map<String, List<CompilerRegistry.MethodParamInfo>>> globalMethodParamsRegistry = new ConcurrentHashMap<>();

    // ── Extension methods ─────────────────────────────────────────────────────
    public final Map<String, Map<String, List<CompilerRegistry.ExtensionMethodInfo>>> globalExtensionMethodRegistry = new ConcurrentHashMap<>();

    // ── Class hierarchy registries ────────────────────────────────────────────
    public final Map<String, String> globalSuperClassRegistry = new ConcurrentHashMap<>();
    public final Map<String, String> globalInnerClassOuterMap = new ConcurrentHashMap<>();
    public final Map<String, String> getGlobalInnerClassUsesOuterMap = new ConcurrentHashMap<>();
    public final Map<String, Integer> globalClassAccess = new ConcurrentHashMap<>();
    public final Map<String, String[]> globalInterfaceRegistry = new ConcurrentHashMap<>();
    public final Map<String, List<CompilerRegistry.TypeParameterInfo>> globalTypeParameterRegistry = new ConcurrentHashMap<>();
    public final Map<String, String> globalSuperClassGenericSignatureRegistry = new ConcurrentHashMap<>();
    public final Map<String, List<String>> globalInterfaceGenericSignatureRegistry = new ConcurrentHashMap<>();
    public final Set<String> globalAbstractClassSet = ConcurrentHashMap.newKeySet();
    public final Set<String> globalIsInterfaceSet = ConcurrentHashMap.newKeySet();
    public final Set<String> globalSealedClassSet = ConcurrentHashMap.newKeySet();
    public final Set<String> globalExplicitRestrictsSet = ConcurrentHashMap.newKeySet();
    public final Set<String> globalDataClassSet = ConcurrentHashMap.newKeySet();
    public final Map<String, List<CompilerRegistry.RecordComponentInfo>> globalRecordComponents = new ConcurrentHashMap<>();
    public final Set<String> globalAsyncMethodSet = ConcurrentHashMap.newKeySet();
    public final Map<String, List<String>> globalPermittedSubclassesRegistry = new ConcurrentHashMap<>();
    public final Map<String, String> globalSubclassStatusRegistry = new ConcurrentHashMap<>();
    public final Map<String, List<String>> globalEnumConstants = new ConcurrentHashMap<>();
    public final Map<String, CompilerRegistry.AnnotationTypeInfo> globalAnnotationTypeRegistry = new ConcurrentHashMap<>();
    // ── Dynamic type-system registries ────────────────────────────────────────
    public final Set<String> globalFunctionalInterfaceRegistry = ConcurrentHashMap.newKeySet();
    public final Set<String> globalListLikeOwnerRegistry       = ConcurrentHashMap.newKeySet();
    public final Set<String> globalMapLikeOwnerRegistry        = ConcurrentHashMap.newKeySet();
    public final Map<String, Class<?>> reflectionClassCache = new ConcurrentHashMap<>();
    public final Set<String> nonExistentClassesCache = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<List<String>> activeWildcardsInternal = ThreadLocal.withInitial(CopyOnWriteArrayList::new);
    public final List<String> activeWildcards = new AbstractList<>() {
        private List<String> getDelegate() { return activeWildcardsInternal.get(); }
        @Override public String get(int index) { return getDelegate().get(index); }
        @Override public int size() { return getDelegate().size(); }
        @Override public String set(int index, String element) { return getDelegate().set(index, element); }
        @Override public void add(int index, String element) { getDelegate().add(index, element); }
        @Override public String remove(int index) { return getDelegate().remove(index); }
        @Override public boolean contains(Object o) { return getDelegate().contains(o); }
        @Override public void clear() { getDelegate().clear(); }
        @Override public boolean add(String s) { return getDelegate().add(s); }
        @NotNull
        @Override public Iterator<String> iterator() { return getDelegate().iterator(); }
    };

    // ── Reflection cache ──────────────────────────────────────────────────────
    public final Map<String, Object> reflectionCache = new ConcurrentHashMap<>();

    public static class SourceMetadata implements Serializable {
        @Serial
        private static final long serialVersionUID = 2L;
        public final long lastModified;
        public final String packageName;
        public final Set<String> declaredTypes;
        /** Hash of recorded dependency set at the time this metadata was saved. */
        public final long dependencyFingerprint;

        public SourceMetadata(long lastModified, String packageName, Set<String> declaredTypes) {
            this(lastModified, packageName, declaredTypes, 0L);
        }

        public SourceMetadata(long lastModified, String packageName, Set<String> declaredTypes, long dependencyFingerprint) {
            this.lastModified = lastModified;
            this.packageName = packageName;
            this.declaredTypes = new HashSet<>(declaredTypes);
            this.dependencyFingerprint = dependencyFingerprint;
        }
    }

    public final Map<String, SourceMetadata> sourceFileMetadata = new ConcurrentHashMap<>();
    public final Map<String, Set<String>> classDependencies = new ConcurrentHashMap<>();

    // ── Anonymous Class Counters ──────────────────────────────────────────────
    private final Map<String, AtomicInteger> anonCounters = new ConcurrentHashMap<>();

    public int getNextAnonClassIndex(String className) {
        if (className == null) className = "Global";
        return anonCounters.computeIfAbsent(className, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public void resetAnonClassCounters() {
        anonCounters.clear();
    }

    // ── Compilation Context fields ────────────────────────────────────────────
    private final ThreadLocal<String> currentFile = new ThreadLocal<>();
    private final ThreadLocal<String> currentPackage = new ThreadLocal<>();
    private final ThreadLocal<String> currentClassFqcn = new ThreadLocal<>();

    public String getCurrentClassFqcn() {
        return currentClassFqcn.get();
    }

    public void setCurrentClassFqcn(String currentClassFqcn) {
        this.currentClassFqcn.set(currentClassFqcn);
    }

    public String getCurrentFile() {
        return currentFile.get();
    }

    public void setCurrentFile(String currentFile) {
        this.currentFile.set(currentFile);
    }

    public String getCurrentPackage() {
        return currentPackage.get();
    }

    public void setCurrentPackage(String currentPackage) {
        this.currentPackage.set(currentPackage);
    }

    public CompilationSession() {
        seedJavaLangDefaults();
    }

    private void seedJavaLangDefaults() {
        String[] common = {
            "String", "Object", "Math", "System", "Thread",
            "Integer", "Double", "Boolean", "Long", "Float",
            "Character", "Byte", "Short", "Exception", "RuntimeException"
        };
        for (String c : common) {
            reflectionCache.put("jlang#" + c, "java/lang/" + c);
        }
    }

    private String stripGenerics(String path) {
        if (path == null) return null;
        int idx = path.indexOf('<');
        if (idx >= 0) return path.substring(0, idx).trim();
        return path.trim();
    }

    /**
     * İki tip arasındaki hiyerarşik ilişkiyi kontrol eder.
     */
    public boolean isSubType(String sub, String sup) {
        if (ClassMetadataCache.isSubtype(sub, sup)) return true;
        return isSubType(sub, sup, new HashSet<>());
    }

    private boolean isSubType(String sub, String sup, Set<String> visited) {
        if (sub == null || sup == null) return false;
        if (sub.equals(sup)) return true;
        if (TypeChecker.isObjectType(sup)) return true;
        if (!visited.add(sub)) return false;

        String subPath = (TypeChecker.isClassType(sub)) ? sub.substring(1, sub.length() - 1) : sub;
        String supPath = (TypeChecker.isClassType(sup)) ? sup.substring(1, sup.length() - 1) : sup;

        subPath = stripGenerics(subPath);
        supPath = stripGenerics(supPath);

        if (subPath.equals(supPath)) return true;
        if (supPath.equals("java/lang/Object")) return true;

        // 1. Kendi sınıflarımız içindeki hiyerarşi ve arayüzler
        String current = subPath;
        Set<String> classChainVisited = new HashSet<>();
        while (current != null && classChainVisited.add(current)) {
            ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(current);
            List<String> ifaceList = new ArrayList<>();
            if (sym != null) ifaceList.addAll(sym.getInterfaces());
            String[] interfaces = CompilerRegistry.globalInterfaceRegistry.get(current);
            if (interfaces == null) interfaces = this.globalInterfaceRegistry.get(current);
            if (interfaces != null) Collections.addAll(ifaceList, interfaces);
            for (String inter : ifaceList) {
                String cleanInter = stripGenerics(inter);
                if (cleanInter.equals(supPath) || (!cleanInter.contains("/") && cleanInter.equals(supPath)) || (!supPath.contains("/") && cleanInter.endsWith("/" + supPath)) || isSubType("L" + cleanInter + ";", sup, visited)) return true;
            }
            String parent = (sym != null && sym.getSuperClassName() != null && !"java/lang/Object".equals(sym.getSuperClassName()))
                    ? sym.getSuperClassName()
                    : CompilerRegistry.globalSuperClassRegistry.get(current);
            if (parent == null) parent = this.globalSuperClassRegistry.get(current);
            if (parent == null && sym != null) parent = sym.getSuperClassName();
            if (parent != null) {
                String cleanParent = stripGenerics(parent);
                // Ebeveyn doğrudan supPath ise eşleşti
                if (cleanParent.equals(supPath) || !supPath.contains("/") && cleanParent.endsWith("/" + supPath)) return true;
                // Ebeveyn kendi sınıfımız değilse (yani java.* gibi bir sınıfsa)
                // recursive isSubType ile reflection fallback'e yönlendir
                if (!CompilerRegistry.globalSuperClassRegistry.containsKey(cleanParent) && !this.globalSuperClassRegistry.containsKey(cleanParent) && !CompilerRegistry.globalInterfaceRegistry.containsKey(cleanParent)) {
                    if (isSubType("L" + cleanParent + ";", sup, visited)) return true;
                }
                current = cleanParent;
            } else {
                current = null;
            }
        }

        // 3. Reflection ile Java standart kütüphanesi sınıfları arasında alt tip kontrolü.
        // Hata oluşursa false döner (güvenli fallback).
        try {
            String subDot  = subPath.replace('/', '.');
            String supDot  = supPath.replace('/', '.');
            Class<?> subCls = cachedForName(subDot);
            Class<?> supCls = cachedForName(supDot);
            return supCls.isAssignableFrom(subCls);
        } catch (ClassNotFoundException | SecurityException | LinkageError ignored) {
            // Kendi sınıflarımız veya bulunamayan sınıflar için beklenen durum
        }
        return false;
    }

    private Class<?> cachedForName(String name) throws ClassNotFoundException {
        if (nonExistentClassesCache.contains(name)) {
            throw new ClassNotFoundException(name);
        }
        Class<?> cached = reflectionClassCache.get(name);
        if (cached != null) return cached;

        try {
            Class<?> loaded = OceanTypeSystem.forName(name);
            if (loaded == null) {
                throw new ClassNotFoundException(name);
            }
            reflectionClassCache.put(name, loaded);
            return loaded;
        } catch (ClassNotFoundException e) {
            nonExistentClassesCache.add(name);
            throw e;
        }
    }

    private static final long COMPILER_TIMESTAMP = computeCompilerTimestamp();

    private static long computeCompilerTimestamp() {
        long latest = 0;
        try {
            URL resSession = CompilationSession.class.getResource("CompilationSession.class");
            if (resSession != null) {
                if ("file".equals(resSession.getProtocol())) {
                    latest = Math.max(latest, new File(resSession.toURI()).lastModified());
                } else {
                    URLConnection conn = resSession.openConnection();
                    latest = Math.max(latest, conn.getLastModified());
                }
            }
            URL resParser = CompilationSession.class.getResource("OceanParser.class");
            if (resParser != null) {
                if ("file".equals(resParser.getProtocol())) {
                    latest = Math.max(latest, new File(resParser.toURI()).lastModified());
                } else {
                    URLConnection conn = resParser.openConnection();
                    latest = Math.max(latest, conn.getLastModified());
                }
            }
        } catch (Exception ignored) {
        }
        return latest;
    }

    private long getCompilerTimestamp() {
        return COMPILER_TIMESTAMP;
    }

    @SuppressWarnings("unchecked")
    public void loadFromCache(Path cacheFile) {
        if (!Files.exists(cacheFile)) return;
        try (InputStream fis = Files.newInputStream(cacheFile);
             BufferedInputStream bis = new BufferedInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            Object readObj = ois.readObject();
            if (!(readObj instanceof Map<?, ?> cacheMap)) {
                return;
            }
            Map<String, Object> cache = (Map<String, Object>) cacheMap;
            
            Object timeObj = cache.get("compilerTimestamp");
            if (!(timeObj instanceof Long) || (Long) timeObj != getCompilerTimestamp()) {
                // cache is stale, do not load
                return;
            }
            
            if (cache.get("globalFieldRegistry") instanceof Map<?, ?> m) globalFieldRegistry.putAll((Map) m);
            if (cache.get("globalFieldMutability") instanceof Map<?, ?> m) globalFieldMutability.putAll((Map) m);
            if (cache.get("globalFieldStaticity") instanceof Map<?, ?> m) globalFieldStaticity.putAll((Map) m);
            if (cache.get("globalFieldAccess") instanceof Map<?, ?> m) globalFieldAccess.putAll((Map) m);
            
            if (cache.get("globalMethodRegistry") instanceof Map<?, ?> m) globalMethodRegistry.putAll((Map) m);
            if (cache.get("globalMethodGenericReturnTypeRegistry") instanceof Map<?, ?> m) globalMethodGenericReturnTypeRegistry.putAll((Map) m);
            if (cache.get("globalMethodStaticity") instanceof Map<?, ?> m) globalMethodStaticity.putAll((Map) m);
            if (cache.get("globalMethodAccess") instanceof Map<?, ?> m) globalMethodAccess.putAll((Map) m);
            if (cache.get("globalOverloadRegistry") instanceof Map<?, ?> m) globalOverloadRegistry.putAll((Map) m);
            if (cache.get("globalMethodTypeParametersRegistry") instanceof Map<?, ?> m) globalMethodTypeParametersRegistry.putAll((Map) m);
            if (cache.get("globalMethodThrowsRegistry") instanceof Map<?, ?> m) globalMethodThrowsRegistry.putAll((Map) m);
            
            if (cache.get("globalMethodParamsRegistry") instanceof Map<?, ?> m) globalMethodParamsRegistry.putAll((Map) m);
            
            if (cache.get("globalExtensionMethodRegistry") instanceof Map<?, ?> m) globalExtensionMethodRegistry.putAll((Map) m);
            
            if (cache.get("globalSuperClassRegistry") instanceof Map<?, ?> m) globalSuperClassRegistry.putAll((Map) m);
            if (cache.get("globalClassAccess") instanceof Map<?, ?> m) globalClassAccess.putAll((Map) m);
            if (cache.get("globalInterfaceRegistry") instanceof Map<?, ?> m) globalInterfaceRegistry.putAll((Map) m);
            if (cache.get("globalTypeParameterRegistry") instanceof Map<?, ?> m) globalTypeParameterRegistry.putAll((Map) m);
            if (cache.get("globalSuperClassGenericSignatureRegistry") instanceof Map<?, ?> m) globalSuperClassGenericSignatureRegistry.putAll((Map) m);
            if (cache.get("globalInterfaceGenericSignatureRegistry") instanceof Map<?, ?> m) globalInterfaceGenericSignatureRegistry.putAll((Map) m);
            
            if (cache.get("globalAbstractClassSet") instanceof Set<?> s) globalAbstractClassSet.addAll((Set) s);
            if (cache.get("globalIsInterfaceSet") instanceof Set<?> s) globalIsInterfaceSet.addAll((Set) s);
            if (cache.get("globalSealedClassSet") instanceof Set<?> s) globalSealedClassSet.addAll((Set) s);
            if (cache.get("globalExplicitRestrictsSet") instanceof Set<?> s) globalExplicitRestrictsSet.addAll((Set) s);
            if (cache.get("globalPermittedSubclassesRegistry") instanceof Map<?, ?> m) globalPermittedSubclassesRegistry.putAll((Map) m);
            if (cache.get("globalSubclassStatusRegistry") instanceof Map<?, ?> m) globalSubclassStatusRegistry.putAll((Map) m);
            if (cache.get("globalDataClassSet") instanceof Set<?> s) globalDataClassSet.addAll((Set) s);
            if (cache.get("globalRecordComponents") instanceof Map<?, ?> m) globalRecordComponents.putAll((Map) m);
            if (cache.get("globalAsyncMethodSet") instanceof Set<?> s) globalAsyncMethodSet.addAll((Set) s);
            if (cache.get("globalFunctionalInterfaceRegistry") instanceof Set<?> s) globalFunctionalInterfaceRegistry.addAll((Set) s);
            if (cache.get("globalListLikeOwnerRegistry") instanceof Set<?> s) globalListLikeOwnerRegistry.addAll((Set) s);
            if (cache.get("globalMapLikeOwnerRegistry") instanceof Set<?> s) globalMapLikeOwnerRegistry.addAll((Set) s);
            if (cache.get("sourceFileMetadata") instanceof Map<?, ?> m) sourceFileMetadata.putAll((Map) m);
            if (cache.get("classDependencies") instanceof Map<?, ?> rawMap) {
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    if (entry.getKey() instanceof String k && entry.getValue() instanceof Set<?> v) {
                        Set<String> depSet = ConcurrentHashMap.newKeySet();
                        depSet.addAll((Set) v);
                        classDependencies.put(k, depSet);
                    }
                }
            }

            // Purge stale cache entries for files that no longer exist on disk
            Set<String> staleKeys = new HashSet<>();
            for (String pathKey : sourceFileMetadata.keySet()) {
                try {
                    if (!Files.exists(Paths.get(pathKey))) {
                        staleKeys.add(pathKey);
                    }
                } catch (Exception e) {
                    staleKeys.add(pathKey);
                }
            }
            for (String key : staleKeys) {
                SourceMetadata meta = sourceFileMetadata.remove(key);
                classDependencies.remove(key);
                if (meta != null && meta.declaredTypes != null) {
                    for (String simple : meta.declaredTypes) {
                        removeRegistryEntriesForClass(simple);
                    }
                }
            }
        } catch (Exception e) {
            if (Boolean.getBoolean("ocean.debug")) {
                e.printStackTrace();
            }
            try {
                Files.deleteIfExists(cacheFile);
            } catch (Exception ignored) {
            }
        }
    }

    public void saveToCache(Path cacheFile) {
        try {
            if (cacheFile.getParent() != null) {
                Files.createDirectories(cacheFile.getParent());
            }
            try (OutputStream fos = Files.newOutputStream(cacheFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos);
                 ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                Map<String, Object> cache = new HashMap<>();
                cache.put("compilerTimestamp", getCompilerTimestamp());
                cache.put("globalFieldRegistry", new HashMap<>(globalFieldRegistry));
                cache.put("globalFieldMutability", new HashMap<>(globalFieldMutability));
                cache.put("globalFieldStaticity", new HashMap<>(globalFieldStaticity));
                cache.put("globalFieldAccess", new HashMap<>(globalFieldAccess));
                
                cache.put("globalMethodRegistry", new HashMap<>(globalMethodRegistry));
                cache.put("globalMethodGenericReturnTypeRegistry", new HashMap<>(globalMethodGenericReturnTypeRegistry));
                cache.put("globalMethodStaticity", new HashMap<>(globalMethodStaticity));
                cache.put("globalMethodAccess", new HashMap<>(globalMethodAccess));
                cache.put("globalOverloadRegistry", new HashMap<>(globalOverloadRegistry));
                cache.put("globalMethodTypeParametersRegistry", new HashMap<>(globalMethodTypeParametersRegistry));
                cache.put("globalMethodThrowsRegistry", new HashMap<>(globalMethodThrowsRegistry));
                Map<String, Map<String, List<CompilerRegistry.MethodParamInfo>>> sanitizedParams = new HashMap<>();
                for (Map.Entry<String, Map<String, List<CompilerRegistry.MethodParamInfo>>> pEntry : globalMethodParamsRegistry.entrySet()) {
                    Map<String, List<CompilerRegistry.MethodParamInfo>> m = new HashMap<>();
                    for (Map.Entry<String, List<CompilerRegistry.MethodParamInfo>> mEntry : pEntry.getValue().entrySet()) {
                        List<CompilerRegistry.MethodParamInfo> sList = new ArrayList<>();
                        for (CompilerRegistry.MethodParamInfo info : mEntry.getValue()) {
                            sList.add(new CompilerRegistry.MethodParamInfo(info.name(), info.rawType(), info.typeDesc(), null));
                        }
                        m.put(mEntry.getKey(), sList);
                    }
                    sanitizedParams.put(pEntry.getKey(), m);
                }
                cache.put("globalMethodParamsRegistry", sanitizedParams);
                
                cache.put("globalExtensionMethodRegistry", new HashMap<>(globalExtensionMethodRegistry));
                
                cache.put("globalSuperClassRegistry", new HashMap<>(globalSuperClassRegistry));
                cache.put("globalClassAccess", new HashMap<>(globalClassAccess));
                cache.put("globalInterfaceRegistry", new HashMap<>(globalInterfaceRegistry));
                cache.put("globalTypeParameterRegistry", new HashMap<>(globalTypeParameterRegistry));
                cache.put("globalSuperClassGenericSignatureRegistry", new HashMap<>(globalSuperClassGenericSignatureRegistry));
                cache.put("globalInterfaceGenericSignatureRegistry", new HashMap<>(globalInterfaceGenericSignatureRegistry));
                
                cache.put("globalAbstractClassSet", new HashSet<>(globalAbstractClassSet));
                cache.put("globalIsInterfaceSet", new HashSet<>(globalIsInterfaceSet));
                cache.put("globalSealedClassSet", new HashSet<>(globalSealedClassSet));
                cache.put("globalExplicitRestrictsSet", new HashSet<>(globalExplicitRestrictsSet));
                cache.put("globalPermittedSubclassesRegistry", new HashMap<>(globalPermittedSubclassesRegistry));
                cache.put("globalSubclassStatusRegistry", new HashMap<>(globalSubclassStatusRegistry));
                cache.put("globalDataClassSet", new HashSet<>(globalDataClassSet));
                cache.put("globalRecordComponents", new HashMap<>(globalRecordComponents));
                cache.put("globalAsyncMethodSet", new HashSet<>(globalAsyncMethodSet));
                cache.put("globalFunctionalInterfaceRegistry", new HashSet<>(globalFunctionalInterfaceRegistry));
                cache.put("globalListLikeOwnerRegistry", new HashSet<>(globalListLikeOwnerRegistry));
                cache.put("globalMapLikeOwnerRegistry", new HashSet<>(globalMapLikeOwnerRegistry));
                cache.put("sourceFileMetadata", new HashMap<>(sourceFileMetadata));
                Map<String, Set<String>> serializableDeps = new HashMap<>();
                for (Map.Entry<String, Set<String>> entry : classDependencies.entrySet()) {
                    serializableDeps.put(entry.getKey(), new HashSet<>(entry.getValue()));
                }
                cache.put("classDependencies", serializableDeps);
                
                oos.writeObject(cache);
                oos.flush();
            }
        } catch (Exception e) {
            if (Boolean.getBoolean("ocean.debug")) {
                e.printStackTrace();
            }
        }
    }

    public void removeClassesBySimpleNames(Set<String> simpleNames) {
        for (String simple : simpleNames) {
            removeRegistryEntriesForClass(simple);
        }
    }

    public void removeClassesByFqcns(Set<String> fqcns) {
        for (String fqcn : fqcns) {
            removeRegistryEntriesForFqcn(fqcn);
        }
    }

    /**
     * Başarısız derleme sonrasında kirli kaynak dosyalarına ait metadata'yı
     * session'dan kaldırır. Böylece hatalı metadata önbelleğe yazılmaz.
     *
     * @param pathKeys rollback yapılacak normalize edilmiş dosya yol anahtarları
     */
    public void rollbackDirtyMetadata(Set<String> pathKeys) {
        for (String key : pathKeys) {
            sourceFileMetadata.remove(key);
            classDependencies.remove(key);
        }
    }

    public void removeRegistryEntriesForFqcn(String fqcn) {
        if (fqcn == null) return;
        String innerPrefix = fqcn + "$";
        
        removeKeysMatchingFqcn(globalFieldRegistry, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalFieldGenericSignatureRegistry, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalFieldMutability, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalFieldStaticity, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalFieldAccess, fqcn, innerPrefix);
        
        removeKeysMatchingFqcn(globalMethodRegistry, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalMethodGenericReturnTypeRegistry, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalMethodStaticity, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalMethodAccess, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalOverloadRegistry, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalMethodTypeParametersRegistry, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalMethodThrowsRegistry, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalMethodParamsRegistry, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalPermittedSubclassesRegistry, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalSubclassStatusRegistry, fqcn, innerPrefix);
        
        removeKeysMatchingFqcn(globalSuperClassRegistry, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalClassAccess, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalInterfaceRegistry, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalTypeParameterRegistry, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalSuperClassGenericSignatureRegistry, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalInterfaceGenericSignatureRegistry, fqcn, innerPrefix);
        removeKeysMatchingFqcn(globalEnumConstants, fqcn, innerPrefix);
        
        globalAbstractClassSet.removeIf(k -> k.equals(fqcn) || k.startsWith(innerPrefix));
        globalIsInterfaceSet.removeIf(k -> k.equals(fqcn) || k.startsWith(innerPrefix));
        globalSealedClassSet.removeIf(k -> k.equals(fqcn) || k.startsWith(innerPrefix));
        globalExplicitRestrictsSet.removeIf(k -> k.equals(fqcn) || k.startsWith(innerPrefix));
        globalDataClassSet.removeIf(k -> k.equals(fqcn) || k.startsWith(innerPrefix));
        globalAsyncMethodSet.removeIf(k -> k.equals(fqcn) || k.startsWith(innerPrefix));
        globalFunctionalInterfaceRegistry.removeIf(k -> k.equals(fqcn) || k.startsWith(innerPrefix));
        globalListLikeOwnerRegistry.removeIf(k -> k.equals(fqcn) || k.startsWith(innerPrefix));
        globalMapLikeOwnerRegistry.removeIf(k -> k.equals(fqcn) || k.startsWith(innerPrefix));
        removeKeysMatchingFqcn(globalRecordComponents, fqcn, innerPrefix);
        
        removeKeysMatchingFqcn(globalExtensionMethodRegistry, fqcn, innerPrefix);
        nonExistentClassesCache.remove(fqcn);
        nonExistentClassesCache.remove(fqcn.replace('/', '.'));
        reflectionClassCache.remove(fqcn.replace('/', '.'));
        SymbolTable.removeNegativeCacheEntry(fqcn);
    }

    private void removeKeysMatchingFqcn(Map<String, ?> map, String fqcn, String innerPrefix) {
        map.keySet().removeIf(k -> k.equals(fqcn) || k.startsWith(innerPrefix));
    }

    private void removeRegistryEntriesForClass(String simpleName) {
        String suffix = "/" + simpleName;
        
        removeKeysMatching(globalFieldRegistry, simpleName, suffix);
        removeKeysMatching(globalFieldGenericSignatureRegistry, simpleName, suffix);
        removeKeysMatching(globalFieldMutability, simpleName, suffix);
        removeKeysMatching(globalFieldStaticity, simpleName, suffix);
        removeKeysMatching(globalFieldAccess, simpleName, suffix);
        
        removeKeysMatching(globalMethodRegistry, simpleName, suffix);
        removeKeysMatching(globalMethodGenericReturnTypeRegistry, simpleName, suffix);
        removeKeysMatching(globalMethodStaticity, simpleName, suffix);
        removeKeysMatching(globalMethodAccess, simpleName, suffix);
        removeKeysMatching(globalOverloadRegistry, simpleName, suffix);
        removeKeysMatching(globalMethodTypeParametersRegistry, simpleName, suffix);
        removeKeysMatching(globalMethodThrowsRegistry, simpleName, suffix);
        removeKeysMatching(globalMethodParamsRegistry, simpleName, suffix);
        removeKeysMatching(globalPermittedSubclassesRegistry, simpleName, suffix);
        removeKeysMatching(globalSubclassStatusRegistry, simpleName, suffix);
        
        removeKeysMatching(globalSuperClassRegistry, simpleName, suffix);
        removeKeysMatching(globalClassAccess, simpleName, suffix);
        removeKeysMatching(globalInterfaceRegistry, simpleName, suffix);
        removeKeysMatching(globalTypeParameterRegistry, simpleName, suffix);
        removeKeysMatching(globalSuperClassGenericSignatureRegistry, simpleName, suffix);
        removeKeysMatching(globalInterfaceGenericSignatureRegistry, simpleName, suffix);
        removeKeysMatching(globalEnumConstants, simpleName, suffix);
        removeKeysMatching(globalRecordComponents, simpleName, suffix);
        
        globalAbstractClassSet.removeIf(k -> k.equals(simpleName) || k.endsWith(suffix) || k.startsWith(simpleName + "$") || k.contains(suffix + "$"));
        globalIsInterfaceSet.removeIf(k -> k.equals(simpleName) || k.endsWith(suffix) || k.startsWith(simpleName + "$") || k.contains(suffix + "$"));
        globalSealedClassSet.removeIf(k -> k.equals(simpleName) || k.endsWith(suffix) || k.startsWith(simpleName + "$") || k.contains(suffix + "$"));
        globalExplicitRestrictsSet.removeIf(k -> k.equals(simpleName) || k.endsWith(suffix) || k.startsWith(simpleName + "$") || k.contains(suffix + "$"));
        globalDataClassSet.removeIf(k -> k.equals(simpleName) || k.endsWith(suffix) || k.startsWith(simpleName + "$") || k.contains(suffix + "$"));
        globalAsyncMethodSet.removeIf(k -> k.equals(simpleName) || k.endsWith(suffix) || k.startsWith(simpleName + "$") || k.contains(suffix + "$"));
        globalFunctionalInterfaceRegistry.removeIf(k -> k.equals(simpleName) || k.endsWith(suffix) || k.startsWith(simpleName + "$") || k.contains(suffix + "$"));
        globalListLikeOwnerRegistry.removeIf(k -> k.equals(simpleName) || k.endsWith(suffix) || k.startsWith(simpleName + "$") || k.contains(suffix + "$"));
        globalMapLikeOwnerRegistry.removeIf(k -> k.equals(simpleName) || k.endsWith(suffix) || k.startsWith(simpleName + "$") || k.contains(suffix + "$"));
        removeKeysMatching(globalExtensionMethodRegistry, simpleName, suffix);
        SymbolTable.removeNegativeCacheEntry(simpleName);
    }

    private void removeKeysMatching(Map<String, ?> map, String simpleName, String suffix) {
        map.keySet().removeIf(k -> k.equals(simpleName) || k.endsWith(suffix) || k.startsWith(simpleName + "$") || k.contains(suffix + "$"));
    }

    public void clearThreadLocals() {
        currentFile.remove();
        currentPackage.remove();
        currentClassFqcn.remove();
        activeWildcardsInternal.remove();
    }
}

