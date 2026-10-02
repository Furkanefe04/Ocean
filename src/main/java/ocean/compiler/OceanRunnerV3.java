package ocean.compiler;

import ocean.compiler.ir.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.PredictionMode;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.*;

import java.io.*;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import ocean.compiler.OceanLexer;
import ocean.compiler.OceanParser;

import org.jetbrains.annotations.NotNull;

/**
 * Ocean Language - Version 3.0 Runner (IR Driven)
 * This is the primary entry point for the Ocean compiler.
 * It manages the compilation pipeline: ANTLR Parsing -> Semantic Analysis -> IR Generation -> Bytecode Emission.
 */
public class OceanRunnerV3 {
    public static final Set<String> currentRunClasses = ConcurrentHashMap.newKeySet();
    private static String[] programArgs = new String[0];
    private static String activeClasspath = null;

    public static void main(String[] args) {
        // -Dkey=value argümanlarını sistem property olarak uygula (ocean.bat'a dokunmadan test izolasyonu için)
        for (String arg : args) {
            if (arg.startsWith("-D") && arg.contains("=")) {
                String kv = arg.substring(2);
                int eq = kv.indexOf('=');
                System.setProperty(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        // Enable debug if requested in args
        for (String arg : args) {
            if ("-debug".equalsIgnoreCase(arg)) {
                System.setProperty("ocean.debug", "true");
                break;
            }
        }
        if (Boolean.getBoolean("ocean.debug") && !isJsonMode) {
            System.out.println("[DEBUG] Raw args received by compiler: " + Arrays.toString(args));
        }

        for (String arg : args) {
            if ("--version".equals(arg) || "-v".equals(arg)) {
                System.out.println("Ocean Compiler version " + CompilerConfig.COMPILER_VERSION + " (JVM 17/21+)");
                return;
            }
            if ("--help".equals(arg) || "-h".equals(arg)) {
                printHelp();
                return;
            }
        }

        if (args.length < 1) {
            printHelp();
            System.exit(1);
        }

        String mainClassName = null;
        String classpath = null;
        boolean isJson = false;
        boolean isCompileOnly = false;
        boolean isVerify = false;

        for (int i = 0; i < args.length; i++) {
            if ("-cp".equals(args[i]) || "-classpath".equals(args[i])) {
                if (i + 1 < args.length) classpath = args[++i];
            } else if ("--json".equals(args[i])) {
                isJson = true;
                isJsonMode = true;
            } else if ("-c".equals(args[i]) || "--compile-only".equals(args[i])) {
                isCompileOnly = true;
            } else if ("-verify".equals(args[i]) || "--verify".equals(args[i])) {
                isVerify = true;
                isVerifyMode = true;
            } else if (mainClassName == null && !args[i].startsWith("-")) {
                mainClassName = resolveMainClassNameFromArg(args[i]);
            }
        }

        setupClasspath(classpath);

        // Parse execution arguments (the first non-option argument is the main class/file; all subsequent non-option arguments belong to programArgs)

        List<String> execArgsList = collectExecArgs(args);
        programArgs = execArgsList.toArray(new String[0]);

        OceanRunnerV3 runner = new OceanRunnerV3();
        CompilationSession session = new CompilationSession();
        CompilationSession.setActiveSession(session);
        try {
            List<Path> sources = discoverSources(args);
            boolean success = runner.compile(sources);

            if (!success || CompilerReporter.hasErrors()) {
                if (isJson) CompilerReporter.printJson();
                else CompilerReporter.printSummary();
                System.exit(1);
            }

            if (!isJson) CompilerReporter.printSummary();

            if (mainClassName != null && !isCompileOnly) {
                if (!executeMain(mainClassName)) {
                    System.exit(1);
                }
            }
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        } finally {
            CompilationSession.clearActiveSession();
            CompilerRegistry.clearAll();
            SymbolTable.clearCaches();
            ClassMetadataCache.clearCaches();
            OverloadResolver.clearCaches();
            OceanTypeSystem.clearCaches();
            TypeChecker.clearCaches();
        }
    }

    @NotNull
    private static List<String> collectExecArgs(String[] args) {
        List<String> execArgsList = new ArrayList<>();
        boolean foundMainArg = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-debug".equalsIgnoreCase(arg) || "--json".equals(arg) || "-c".equals(arg) || "--compile-only".equals(arg) || "-verify".equals(arg) || "--verify".equals(arg)) {
                continue;
            }
            if ("-cp".equals(arg) || "-classpath".equals(arg)) {
                if (i + 1 < args.length) i++;
                continue;
            }
            if (!foundMainArg && !arg.startsWith("-")) {
                foundMainArg = true;
                continue;
            }
            execArgsList.add(arg);
        }
        return execArgsList;
    }

    private static boolean isDirty(Path p) {
        return isDirty(p, new HashSet<>());
    }

    private static boolean isDirty(Path p, Set<Path> visited) {
        if (!visited.add(p)) {
            return false;
        }
        try {
            CompilationSession session = CompilationSession.getActiveSession();
            String pathKey = getNormalizedPathString(p);
            long sourceTime = Files.getLastModifiedTime(p).toMillis();

            CompilationSession.SourceMetadata cached = (session != null) ? session.sourceFileMetadata.get(pathKey) : null;
            if (cached != null && cached.lastModified == sourceTime) {
                String pkg = cached.packageName;
                String subDir = extractSubDir(p, pkg);
                Path targetDir = Paths.get(getBinaryDir(), subDir);
                if (!Files.exists(targetDir)) {
                    return true;
                }
                for (String type : cached.declaredTypes) {
                    if (controlForDirty(p, pathKey, targetDir, type)) return true;
                }

                // Dependency fingerprint check: if dependency set has changed, consider dirty
                Set<String> cachedDeps = session.classDependencies.get(pathKey);
                long currentFingerprint = computeFingerprint(cachedDeps);
                if (cached.dependencyFingerprint != 0L && cached.dependencyFingerprint != currentFingerprint) {
                    return true;
                }

                // Dependency file dirtiness check (propagate)
                if (cachedDeps != null) {
                    for (String dep : cachedDeps) {
                        Path depSource = findSourceFileForClass(dep, session);
                        if (depSource != null) {
                            if (isDirty(depSource, visited)) {
                                return true;
                            }
                        }
                    }
                }

                return false;
            }

            SourceHeaderInfo info = getSourceHeaderInfo(p);
            String pkg = info.packageName;
            String subDir = extractSubDir(p, pkg);

            Path targetDir = Paths.get(getBinaryDir(), subDir);
            if (!Files.exists(targetDir)) {
                return true;
            }

            Set<String> declaredTypes = addClassToDeclaredTypes(info.declaredTypes,p);

            FileTime sTime = Files.getLastModifiedTime(p);

            for (String type : declaredTypes) {
                Path classFile = targetDir.resolve(type + ".class");
                if (controlForDirty(p, pathKey, targetDir, type)) return true;
                FileTime classTime = Files.getLastModifiedTime(classFile);
                if (sTime.compareTo(classTime) > 0) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean controlForDirty(Path p, String pathKey, Path targetDir, String type) throws IOException {
        Path classFile = targetDir.resolve(type + ".class");
        if (!Files.exists(classFile)) {
            return true;
        }
        Path sourceRecordFile = targetDir.resolve(type + ".class.source");
        if (!Files.exists(sourceRecordFile)) {
            return true;
        }
        String recordedSource = Files.readString(sourceRecordFile, StandardCharsets.UTF_8).trim();
        if (!recordedSource.equals(pathKey)) {
            return true;
        }
        String fqName = getFullClassNameForFile(p, type);
        CompilationSession session = CompilationSession.getActiveSession();
        if (session != null && !session.globalMethodRegistry.isEmpty()) {
            return !session.globalMethodRegistry.containsKey(fqName);
        }
        return false;
    }

    private static Path findSourceFileForClass(String fqcn, CompilationSession session) {
        if (session == null) return null;
        String simpleName = fqcn;
        int lastSlash = fqcn.lastIndexOf('/');
        if (lastSlash != -1) {
            simpleName = fqcn.substring(lastSlash + 1);
        }

        for (Map.Entry<String, CompilationSession.SourceMetadata> entry : session.sourceFileMetadata.entrySet()) {
            if (entry.getValue().declaredTypes.contains(simpleName)) {
                return Paths.get(entry.getKey());
            }
        }
        return null;
    }

    public boolean compile(String sourcePath) {
        return compile(Collections.singletonList(Paths.get(sourcePath)));
    }

    public boolean compile(List<Path> sources) {
        boolean manageSession = (CompilationSession.getActiveSession() == null);
        if (manageSession) {
            CompilationSession.setActiveSession(new CompilationSession());
        }
        CompilationSession session = CompilationSession.getActiveSession();
        try {
            CompilerReporter.clear();
            CompilerRegistry.clearAll();
            SymbolTable.clearCaches();
            ClassMetadataCache.clearCaches();
            OverloadResolver.clearCaches();
            OceanTypeSystem.clearCaches();
            TypeChecker.clearCaches();
            compiledInMemoryClasses.clear(); // OC-31: stale in-memory class'ları temizle

            Path cacheFile = Paths.get(getBinaryDir(), "compiler_cache.dat");
            session.loadFromCache(cacheFile);

            currentRunClasses.clear();
            for (Path p : sources) {
                try {
                    String pathKey = getNormalizedPathString(p);
                    CompilationSession.SourceMetadata cached = session.sourceFileMetadata.get(pathKey);
                    Set<String> declaredTypes;
                    if (cached != null && cached.lastModified == Files.getLastModifiedTime(p).toMillis()) {
                        declaredTypes = cached.declaredTypes;
                    } else {
                        SourceHeaderInfo sInfo = getSourceHeaderInfo(p);
                        declaredTypes = new HashSet<>(sInfo.declaredTypes);
                        String filenameSimple = p.getFileName().toString();
                        if (filenameSimple.endsWith(".ocean")) {
                            filenameSimple = filenameSimple.substring(0, filenameSimple.length() - 6);
                        }
                        declaredTypes.add(filenameSimple);
                    }
                    for (String type : declaredTypes) {
                        String fqName = getFullClassNameForFile(p, type);
                        currentRunClasses.add(fqName);
                        currentRunClasses.add(fqName.replace('/', '.'));
                    }
                } catch (Exception ignored) {
                }
            }

            List<Path> dirtySources = new ArrayList<>();
            for (Path p : sources) {
                if (isDirty(p)) {
                    dirtySources.add(p);
                }
            }

            if (!dirtySources.isEmpty()) {
                headerInfoCache.keySet().removeAll(dirtySources);
                cleanBinaryDir(dirtySources);
            }

            for (Path p : dirtySources) {
                try {
                    SourceHeaderInfo sInfo = getSourceHeaderInfo(p);
                    Set<String> declaredTypes = new HashSet<>(sInfo.declaredTypes);
                    String filenameSimple = p.getFileName().toString();
                    if (filenameSimple.endsWith(".ocean")) {
                        filenameSimple = filenameSimple.substring(0, filenameSimple.length() - 6);
                    }
                    declaredTypes.add(filenameSimple);
                    session.removeClassesBySimpleNames(declaredTypes);
                    session.classDependencies.remove(getNormalizedPathString(p));

                    long modTime = Files.getLastModifiedTime(p).toMillis();
                    String pkgName = sInfo.packageName;
                    long depFingerprint = computeFingerprint(session.classDependencies.get(getNormalizedPathString(p)));
                    session.sourceFileMetadata.put(getNormalizedPathString(p),
                            new CompilationSession.SourceMetadata(modTime, pkgName, declaredTypes, depFingerprint));
                } catch (Exception ignored) {
                }
            }

            final AtomicBoolean compileFailed = new AtomicBoolean(false);

            Map<Path, ParseTree> parsedTrees = new ConcurrentHashMap<>();
            final ClassLoader maincl = Thread.currentThread().getContextClassLoader();

            // First pass: Parse dirty sources in parallel using two-stage (SLL -> LL) parsing
            dirtySources.parallelStream().forEach(p -> {
                CompilationSession.setActiveSession(session);
                ClassLoader oldcl = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(maincl);
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    OceanLexer lexer = new OceanLexer(CharStreams.fromString(content));
                    lexer.removeErrorListeners();

                    TokenStream tokenStream = OceanTokenStreamFactory.createTokenStream(lexer);
                    OceanParser parser = new OceanParser(tokenStream);
                    parser.removeErrorListeners();

                    ParseTree tree;
                    try {
                        // Stage 1: Fast linear SLL parsing
                        parser.getInterpreter().setPredictionMode(PredictionMode.SLL);
                        parser.setErrorHandler(new BailErrorStrategy());
                        tree = parser.program();
                    } catch (ParseCancellationException | RecognitionException e) {
                        // Stage 2: Fallback to full LL parsing with diagnostic reporting
                        tokenStream.seek(0);
                        parser.reset();
                        lexer.addErrorListener(new OceanErrorListener(getNormalizedPathString(p)));
                        parser.addErrorListener(new OceanErrorListener(getNormalizedPathString(p)));
                        parser.setErrorHandler(new DefaultErrorStrategy());
                        parser.getInterpreter().setPredictionMode(PredictionMode.LL);
                        tree = parser.program();
                    }
                    parsedTrees.put(p, tree);
                } catch (Throwable e) {
                    compileFailed.set(true);
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    reportError(getNormalizedPathString(p), "Compilation failed in parsing: " + msg);
                } finally {
                    Thread.currentThread().setContextClassLoader(oldcl);
                    CompilationSession.clearActiveSession();
                }
            });
            CompilationSession.setActiveSession(session);

            if (compileFailed.get() || CompilerReporter.hasErrors()) {
                return false;
            }

            // Pre-register all top-level types so SymbolTable can resolve them during pre-scan
            parsedTrees.entrySet().parallelStream().forEach(entry -> {
                CompilationSession.setActiveSession(session);
                ClassLoader oldcl = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(maincl);
                Path p = entry.getKey();
                ParseTree tree = entry.getValue();
                try {
                    if (tree instanceof OceanParser.ProgramContext prog) {
                        for (OceanParser.CompilationUnitContext cu : prog.compilationUnit()) {
                            String simpleName = null;
                            if (cu.classDeclaration() != null) simpleName = cu.classDeclaration().anyId().getText();
                            else if (cu.interfaceDeclaration() != null)
                                simpleName = cu.interfaceDeclaration().anyId().getText();
                            else if (cu.enumDeclaration() != null) simpleName = cu.enumDeclaration().anyId().getText();
                            else if (cu.annotationDeclaration() != null) simpleName = cu.annotationDeclaration().anyId().getText();

                            if (simpleName != null) {
                                String fqName = getFullClassNameForFile(p, simpleName);
                                CompilerRegistry.globalMethodRegistry.computeIfAbsent(fqName, k -> new HashMap<>());
                            }
                        }
                    }
                } finally {
                    Thread.currentThread().setContextClassLoader(oldcl);
                    CompilationSession.clearActiveSession();
                }
            });
            CompilationSession.setActiveSession(session);

            // Pre-scan with PreScanner to populate global registries in parallel
            // Keep each scanner so its import state can be reused in the second pass.
            Map<Path, PreScanner> preScanners = new ConcurrentHashMap<>();
            parsedTrees.entrySet().parallelStream().forEach(entry -> {
                CompilationSession.setActiveSession(session);
                ClassLoader oldcl = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(maincl);
                Path p = entry.getKey();
                ParseTree tree = entry.getValue();
                try {
                    PreScanner scanner = new PreScanner(p.getFileName().toString(), session);
                    scanner.setCurrentFilePackage(extractPackageName(p));
                    scanner.visit(tree);
                    preScanners.put(p, scanner); // ← save for second pass
                } catch (Throwable e) {
                    compileFailed.set(true);
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    reportError(getNormalizedPathString(p), "Compilation failed in pre-scan: " + msg);
                } finally {
                    Thread.currentThread().setContextClassLoader(oldcl);
                    CompilationSession.clearActiveSession();
                }
            });
            CompilationSession.setActiveSession(session);

            if (compileFailed.get()) {
                return false;
            }

            // Second pass: Semantic Analysis, IR Generation & Bytecode Emission in parallel
            Map<Path, Map<String, byte[]>> allGeneratedClasses = new ConcurrentHashMap<>();
            parsedTrees.entrySet().parallelStream().forEach(entry -> {
                CompilationSession.setActiveSession(session);
                ClassLoader oldcl = Thread.currentThread().getContextClassLoader();
                Thread.currentThread().setContextClassLoader(maincl);
                Path p = entry.getKey();
                ParseTree tree = entry.getValue();
                try {
                    if (compileFailed.get()) {
                        return;
                    }
                    if (!dirtySources.contains(p)) {
                        if (!"true".equals(System.getProperty("quiet")) && !isJsonMode) {
                            System.out.println("[INFO] Up-to-date: " + p.getFileName());
                        }
                        return;
                    }
                    // IR Generation & Bytecode Emission
                    String packageName = extractPackageName(p);
                    session.setCurrentFile(p.getFileName().toString());
                    session.setCurrentPackage(packageName != null ? packageName.replace('/', '.') : "");

                    // ── Semantic Analysis ──────────────────
                    PreScanner pScanner = preScanners.get(p);
                    Map<String, String> importedClasses = pScanner != null ? pScanner.getImportedClasses() : new HashMap<>();
                    List<String> importedWildcards = pScanner != null ? pScanner.getImportedWildcards() : new ArrayList<>();
                    if (importedWildcards != null) {
                        session.activeWildcards.clear();
                        session.activeWildcards.addAll(importedWildcards);
                    }
                    Map<String, String> importedStaticMembers = pScanner != null ? pScanner.getImportedStaticMembers() : new HashMap<>();
                    List<String> importedStaticWildcards = pScanner != null ? pScanner.getImportedStaticWildcards() : new ArrayList<>();
                    SymbolTable symbolTable = pScanner != null ? pScanner.getSymbolTable() : new SymbolTable(false, importedClasses, new HashSet<>(), session);

                    IRGenerator irGen = new IRGenerator(
                            p.getFileName().toString(),
                            packageName,
                            importedClasses,
                            importedWildcards,
                            importedStaticMembers,
                            importedStaticWildcards,
                            symbolTable
                    );
                    IRNode irTree = irGen.visit(tree);

                    session.setCurrentFile(p.getFileName().toString());
                    session.setCurrentPackage(packageName != null ? packageName.replace('/', '.') : "");

                    IRSemanticAnalyzer irSemanticAnalyzer = new IRSemanticAnalyzer(
                            p.getFileName().toString(),
                            session,
                            importedClasses,
                            importedWildcards,
                            importedStaticMembers,
                            importedStaticWildcards,
                            symbolTable
                    );
                    irTree.accept(irSemanticAnalyzer);
                    if (CompilerReporter.hasErrors() || irSemanticAnalyzer.hasErrors()) {
                        compileFailed.set(true);
                        return;
                    }

                    IROptimizerPipeline optimizerPipeline = new IROptimizerPipeline();
                    irTree = optimizerPipeline.optimize(irTree);

                    if (Boolean.getBoolean("ocean.debug")) {
                        System.out.println("[DEBUG] PRE-EMISSION IR DUMP for " + p.getFileName() + ":");
                        IRDumper dumper = new IRDumper();
                        System.out.println(dumper.dump(irTree));
                    }

                    // Emission
                    IRToBytecodeEmitter emitter = new IRToBytecodeEmitter();
                    emitter.setSourceFileName(p.getFileName().toString());
                    irTree.accept(emitter);

                    Map<String, byte[]> generated = emitter.getGeneratedClasses();
                    compiledInMemoryClasses.putAll(generated);
                    allGeneratedClasses.put(p, generated);
                } catch (CompilationException e) {
                    if (Boolean.getBoolean("ocean.debug")) {
                        System.out.println("[IR-FAIL] CompilationException in " + p.getFileName() + ": " + e.getMessage());
                    }
                    compileFailed.set(true);
                } catch (Throwable e) {
                    if (Boolean.getBoolean("ocean.debug")) {
                        System.out.println("[IR-FAIL] " + p.getFileName() + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                        e.printStackTrace(System.out);
                    }
                    compileFailed.set(true);
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    if (!isJsonMode) {
                        System.out.println(msg);
                    }
                    reportError(getNormalizedPathString(p), "Compilation failed: " + msg);
                } finally {
                    Thread.currentThread().setContextClassLoader(oldcl);
                    CompilationSession.clearActiveSession();
                }
            });
            CompilationSession.setActiveSession(session);

            if (!compileFailed.get() && !CompilerReporter.hasErrors()) {
                if (isVerifyRequested()) {
                    for (Map.Entry<Path, Map<String, byte[]>> entry : allGeneratedClasses.entrySet()) {
                        Path sourcePath = entry.getKey();
                        for (Map.Entry<String, byte[]> classEntry : entry.getValue().entrySet()) {
                            if (!verifyBytecode(classEntry.getKey(), classEntry.getValue(), sourcePath)) {
                                compileFailed.set(true);
                            }
                        }
                    }
                }
            }

            if (!compileFailed.get() && !CompilerReporter.hasErrors()) {
                allGeneratedClasses.forEach((path, classes) -> saveBytecode(classes, path));
                if (!"true".equals(System.getProperty("quiet")) && !isJsonMode) {
                    allGeneratedClasses.keySet().forEach(p -> System.out.println("[INFO] Compiled: " + p.getFileName()));
                }
            }

            if (!compileFailed.get() && !CompilerReporter.hasErrors() && !dirtySources.isEmpty()) {
                // Update dependency fingerprints in metadata before saving
                for (Path p : dirtySources) {
                    String pathKey = getNormalizedPathString(p);
                    CompilationSession.SourceMetadata old = session.sourceFileMetadata.get(pathKey);
                    if (old != null) {
                        long newFingerprint = computeFingerprint(session.classDependencies.get(pathKey));
                        session.sourceFileMetadata.put(pathKey,
                                new CompilationSession.SourceMetadata(old.lastModified, old.packageName, old.declaredTypes, newFingerprint));
                    }
                }
                session.saveToCache(cacheFile);
            } else if (compileFailed.get() || CompilerReporter.hasErrors()) {
                // A3: Rollback dirty metadata to prevent cache corruption on failed builds
                Set<String> dirtyKeys = new LinkedHashSet<>();
                for (Path p : dirtySources) {
                    dirtyKeys.add(getNormalizedPathString(p));
                }
                session.rollbackDirtyMetadata(dirtyKeys);
            }

            return !compileFailed.get();
        } finally {
            if (manageSession) {
                CompilationSession.clearActiveSession();
                CompilerRegistry.clearAll();
                SymbolTable.clearCaches();
                ClassMetadataCache.clearCaches();
                OverloadResolver.clearCaches();
                OceanTypeSystem.clearCaches();
                TypeChecker.clearCaches();
            }
        }
    }

    private static final Map<Path, String> normalizedPathCache = new ConcurrentHashMap<>();

    private static String getNormalizedPathString(Path p) {
        if (p == null) return "";
        return normalizedPathCache.computeIfAbsent(p, path -> {
            String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/');
            if (normalized.length() >= 2 && normalized.charAt(1) == ':') {
                normalized = Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
            }
            return normalized;
        });
    }

    /**
     * Bağımlılık kümesinin ve üye imzalarının deterministik hash'ini hesaplar.
     * Bağımlılık kümesi veya üye imzaları değişirse isDirty() bunu algılar.
     */
    private static long computeFingerprint(Set<String> deps) {
        if (deps == null || deps.isEmpty()) return 0L;
        List<String> sorted = new ArrayList<>(deps);
        Collections.sort(sorted);
        long hash = 1L;
        CompilationSession session = CompilationSession.getActiveSession();
        for (String dep : sorted) {
            hash = hash * 31L + dep.hashCode();
            if (session != null) {
                Map<String, String> methods = session.globalMethodRegistry.get(dep);
                if (methods != null) {
                    hash = hash * 31L + methods.hashCode();
                }
                Map<String, String> fields = session.globalFieldRegistry.get(dep);
                if (fields != null) {
                    hash = hash * 31L + fields.hashCode();
                }
            }
        }
        return hash;
    }

    private static Path getSourceRoot(Path file) {
        String pkg = extractPackageName(file);
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent == null) return null;
        if (pkg != null && !pkg.isEmpty()) {
            int segments = pkg.split("\\.").length;
            for (int i = 0; i < segments; i++) {
                if (parent.getParent() != null) {
                    parent = parent.getParent();
                }
            }
        }
        return parent;
    }

    private static List<Path> discoverSources(String[] args) throws IOException {
        Set<Path> uniqueSources = new LinkedHashSet<>();
        List<Path> initialArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-")) {
                if (("-cp".equals(arg) || "-classpath".equals(arg)) && i + 1 < args.length) i++;
                continue;
            }
            try {
                Path p = Paths.get(arg);
                if (!Files.exists(p) && !arg.endsWith(".ocean")) {
                    Path withExt = Paths.get(arg + ".ocean");
                    if (Files.exists(withExt)) p = withExt;
                }
                if (Files.exists(p)) {
                    initialArgs.add(p);
                }
            } catch (Exception ignored) {
            }
        }

        if (initialArgs.isEmpty()) {
            Path ex = Paths.get("examples");
            if (Files.exists(ex)) {
                initialArgs.add(ex);
            } else {
                Path res = Paths.get("src/main/resources");
                if (Files.exists(res)) {
                    initialArgs.add(res);
                }
            }
        }

        for (Path p : initialArgs) {
            if (Files.isDirectory(p)) {
                Files.walkFileTree(p, new SimpleFileVisitor<>() {
                    @NotNull
                    @Override
                    public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                        if (file.toString().endsWith(".ocean")) {
                            uniqueSources.add(file.toAbsolutePath().normalize());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else if (p.toString().endsWith(".ocean")) {
                Path targetAbs = p.toAbsolutePath().normalize();
                uniqueSources.add(targetAbs);

                Path sourceRoot = getSourceRoot(p);
                if (sourceRoot != null && Files.exists(sourceRoot)) {
                    // Collect candidates on-demand from the same parent folder
                    Set<Path> candidateSiblings = new LinkedHashSet<>();
                    Path parentDir = targetAbs.getParent();
                    if (parentDir != null && Files.exists(parentDir)) {
                        addOceanFilesFromDirectory(parentDir,candidateSiblings,targetAbs);
                    }

                    // Transitive closure (fixpoint iteration) to find all dependencies
                    Set<Path> resolved = new LinkedHashSet<>();
                    resolved.add(targetAbs);

                    // Cache contents and words to avoid re-reading files repeatedly, loaded ON-DEMAND
                    Map<Path, String> rawContents = new HashMap<>();
                    Map<Path, Set<String>> fileWords = new HashMap<>();
                    try {
                        String content = Files.readString(targetAbs, StandardCharsets.UTF_8);
                        rawContents.put(targetAbs, content);
                        fileWords.put(targetAbs, extractWordTokens(content));
                        addImportedCandidates(content, sourceRoot, candidateSiblings, targetAbs);
                    } catch (IOException ignored) {
                    }

                    boolean addedAny;
                    do {
                        addedAny = false;
                        Iterator<Path> iter = candidateSiblings.iterator();
                        while (iter.hasNext()) {
                            Path sibling = iter.next();
                            String siblingClassName = sibling.getFileName().toString();
                            if (siblingClassName.endsWith(".ocean")) {
                                siblingClassName = siblingClassName.substring(0, siblingClassName.length() - 6);
                            }

                            // Check if sibling is referenced by any already-resolved file
                            boolean referenced = false;
                            String siblingPkg = extractPackageName(sibling);
                            String siblingFqcn = (siblingPkg == null || siblingPkg.isEmpty()) ? siblingClassName : (siblingPkg + "." + siblingClassName);

                            for (Path resFile : resolved) {
                                if (sibling.getFileName().equals(resFile.getFileName())) {
                                    continue;
                                }
                                String resPkg = extractPackageName(resFile);
                                boolean samePkg = Objects.equals(siblingPkg == null ? "" : siblingPkg, resPkg == null ? "" : resPkg);

                                Set<String> words = fileWords.get(resFile);
                                if (words != null && words.contains(siblingClassName)) {
                                    if (samePkg) {
                                        referenced = true;
                                        break;
                                    } else {
                                        String content = rawContents.get(resFile);
                                        if (content != null) {
                                            boolean hasImport = content.contains("import " + siblingFqcn) ||
                                                    content.contains("import static " + siblingFqcn) ||
                                                    (siblingPkg != null && !siblingPkg.isEmpty() && (
                                                            content.contains("import " + siblingPkg + ".*") ||
                                                                    content.contains("import " + siblingPkg + ".STAR")
                                                    ));
                                            if (hasImport) {
                                                referenced = true;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }

                            if (referenced) {
                                resolved.add(sibling);
                                try {
                                    String siblingContent = Files.readString(sibling, StandardCharsets.UTF_8);
                                    rawContents.put(sibling, siblingContent);
                                    fileWords.put(sibling, extractWordTokens(siblingContent));
                                    addImportedCandidates(siblingContent, sourceRoot, candidateSiblings, targetAbs);
                                } catch (IOException ignored) {
                                }
                                iter.remove(); // Remove from candidates so we don't scan it again
                                addedAny = true;
                                break;
                            }
                        }
                    } while (addedAny);

                    uniqueSources.addAll(resolved);
                }
            }
        }
        return new ArrayList<>(uniqueSources);
    }

    private static final Pattern DISCOVERY_IMPORT_PATTERN = Pattern.compile("import\\s+(?:static\\s+)?([a-zA-Z0-9_.]+)(?:\\.\\*)?;");

    private static void addImportedCandidates(String content, Path sourceRoot, Set<Path> candidates, Path targetAbs) {
        if (content == null || sourceRoot == null) return;
        Matcher m = DISCOVERY_IMPORT_PATTERN.matcher(content);
        while (m.find()) {
            String imp = m.group(1).replace('.', '/');
            Path candidateFile = sourceRoot.resolve(imp + ".ocean");
            if (Files.exists(candidateFile)) {
                Path norm = candidateFile.toAbsolutePath().normalize();
                if (!norm.equals(targetAbs)) candidates.add(norm);
            } else {
                Path candidateDir = sourceRoot.resolve(imp);
                if (Files.isDirectory(candidateDir)) addOceanFilesFromDirectory(candidateDir, candidates, targetAbs);
            }
        }
    }

    private static void addOceanFilesFromDirectory(Path candidateDir, Set<Path> candidates, Path targetAbs) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(candidateDir, "*.ocean")) {
            for (Path file : stream) {
                Path norm = file.toAbsolutePath().normalize();
                if (!norm.equals(targetAbs)) {
                    candidates.add(norm);
                }
            }
        } catch (IOException ignored) {
        }
    }
    private static String getBinaryDir() {
        String home = System.getProperty("user.dir");
        String testId = System.getProperty("ocean.test.id");
        if (testId != null && !testId.isBlank()) {
            // Her test süreci kendi izole dizinini kullanır — Test paketi class'larıyla karışma olmaz
            return home + File.separator + "build" + File.separator + "test-isolation"
                    + File.separator + testId + File.separator + "classes";
        }
        return home + File.separator + "build" + File.separator + "classes" + File.separator + "ocean";
    }

    private static void addClasspathPart(String part, List<URL> urls) {
        part = part.trim();
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG] addClasspathPart: part=" + part);
        }
        if (part.endsWith("*")) {
            String dirPath = part.substring(0, part.length() - 1);
            if (dirPath.isEmpty()) dirPath = ".";
            File dir = new File(dirPath);
            if (dir.exists() && dir.isDirectory()) {
                File[] jars = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
                if (jars != null) {
                    for (File jar : jars) {
                        try {
                            urls.add(jar.getAbsoluteFile().toURI().toURL());
                            if (Boolean.getBoolean("ocean.debug")) {
                                System.out.println("[DEBUG] Loaded wildcard JAR: " + jar.getAbsolutePath());
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } else {
            File f = new File(part);
            if (f.exists()) {
                try {
                    urls.add(f.getAbsoluteFile().toURI().toURL());
                    if (Boolean.getBoolean("ocean.debug")) {
                        System.out.println("[DEBUG] Loaded exact JAR/Dir: " + f.getAbsolutePath());
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void setupClasspath(String cp) {
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG] setupClasspath received: cp=" + cp);
        }
        activeClasspath = cp;
        try {
            List<URL> urls = buildClasspathUrls(false);
            URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), OceanRunnerV3.class.getClassLoader());
            Thread.currentThread().setContextClassLoader(cl);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static final ConcurrentHashMap<String, byte[]> compiledInMemoryClasses = new ConcurrentHashMap<>();

    public static class MemoryClassLoader extends URLClassLoader {
        private final Map<String, byte[]> memoryClasses;

        public MemoryClassLoader(URL[] urls, ClassLoader parent, Map<String, byte[]> memoryClasses) {
            super(urls, parent);
            this.memoryClasses = memoryClasses;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = memoryClasses.get(name);
            if (bytes == null) {
                bytes = memoryClasses.get(name.replace('.', '/'));
            }
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            return super.findClass(name);
        }
    }

    private static final AtomicBoolean stdlibDeployed = new AtomicBoolean(false);

    private static void deployStdlib(String binDir) {
        if (binDir == null) return;
        Path stdlibMarker = Paths.get(binDir, "org", "Ocean", "stdlib", "OceanList.class");
        if (Files.exists(stdlibMarker)) {
            stdlibDeployed.set(true);
            return;
        }
        if (!stdlibDeployed.compareAndSet(false, true)) {
            return;
        }

        try {
            // 1. Try from dev filesystem (build/classes/java/main/ocean/stdlib & utils)
            Path devClassesDir = Paths.get("build", "classes", "java", "main");
            if (Files.exists(devClassesDir)) {
                copyStdlibFromDirectory(devClassesDir, Paths.get(binDir));
            }

            // 2. Try from CodeSource (JAR)
            try {
                URL codeSourceUrl = OceanRunnerV3.class.getProtectionDomain().getCodeSource().getLocation();
                if (codeSourceUrl != null) {
                    File codeSourceFile = new File(codeSourceUrl.toURI());
                    if (codeSourceFile.isFile() && codeSourceFile.getName().endsWith(".jar")) {
                        try (JarFile jar = new JarFile(codeSourceFile)) {
                            Enumeration<JarEntry> entries = jar.entries();
                            while (entries.hasMoreElements()) {
                                JarEntry entry = entries.nextElement();
                                String name = entry.getName();
                                if ((name.startsWith("ocean/stdlib/") || name.startsWith("ocean/utils/") || name.equals("ocean/compiler/Metadata.class")) && name.endsWith(".class")) {
                                    Path dest = Paths.get(binDir, name);
                                    Files.createDirectories(dest.getParent());
                                    try (InputStream is = jar.getInputStream(entry)) {
                                        Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 3. Fallback: Copy via ClassLoader getResourceAsStream for known stdlib classes if still missing
            if (!Files.exists(stdlibMarker)) {
                String[] knownStdlibClasses = {
                    "OceanBitSet", "OceanBooleanList", "OceanByteList", "OceanCharList", "OceanCounter",
                    "OceanDate", "OceanDoubleList", "OceanFile", "OceanFloatList", "OceanHttp",
                    "OceanHttpResponse", "OceanIntList", "OceanJson", "OceanList", "OceanLongList",
                    "OceanMap", "OceanMath", "OceanOptional", "OceanPair", "OceanQueue",
                    "OceanRegex", "OceanResult", "OceanSet", "OceanShortList", "OceanStack",
                    "OceanString", "OceanStringBuilder", "OceanTime", "OceanTriple", "RuntimeUtils", "StrBuilder"
                };
                ClassLoader cl = OceanRunnerV3.class.getClassLoader();
                for (String cls : knownStdlibClasses) {
                    String resPath = "ocean/stdlib/" + cls + ".class";
                    try (InputStream is = cl.getResourceAsStream(resPath)) {
                        if (is != null) {
                            Path dest = Paths.get(binDir, resPath);
                            Files.createDirectories(dest.getParent());
                            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Throwable ignored) {}
                }
                try (InputStream is = cl.getResourceAsStream("ocean/utils/TypeInference.class")) {
                    if (is != null) {
                        Path dest = Paths.get(binDir, "ocean/utils/TypeInference.class");
                        Files.createDirectories(dest.getParent());
                        Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Throwable ignored) {}
                try (InputStream is = cl.getResourceAsStream("ocean/compiler/Metadata.class")) {
                    if (is != null) {
                        Path dest = Paths.get(binDir, "ocean/compiler/Metadata.class");
                        Files.createDirectories(dest.getParent());
                        Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            if (Boolean.getBoolean("ocean.debug")) {
                System.out.println("[DEBUG] Failed to deploy stdlib: " + t.getMessage());
            }
        }
    }

    private static void copyStdlibFromDirectory(Path srcBase, Path destBase) {
        Path metaSrc = srcBase.resolve("ocean").resolve("compiler").resolve("Metadata.class");
        if (Files.exists(metaSrc)) {
            try {
                Path metaDest = destBase.resolve("ocean").resolve("compiler").resolve("Metadata.class");
                Files.createDirectories(metaDest.getParent());
                Files.copy(metaSrc, metaDest, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {}
        }
        String[] subPackages = {"ocean/stdlib", "ocean/utils"};
        for (String sub : subPackages) {
            Path srcSub = srcBase.resolve(sub.replace('/', File.separatorChar));
            if (Files.exists(srcSub)) {
                try (var stream = Files.walk(srcSub)) {
                    stream.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                        try {
                            Path rel = srcBase.relativize(p);
                            Path dest = destBase.resolve(rel);
                            Files.createDirectories(dest.getParent());
                            Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ignored) {}
                    });
                } catch (IOException ignored) {}
            }
        }
    }

    private static volatile boolean isVerifyMode = false;
    private static volatile boolean isJsonMode = false;

    public static boolean isJsonMode() {
        return isJsonMode;
    }

    public static void setJsonMode(boolean json) {
        isJsonMode = json;
    }

    public static boolean isVerifyRequested() {
        return isVerifyMode || Boolean.getBoolean("ocean.verify");
    }

    public static boolean verifyBytecode(String className, byte[] bytecode, Path sourceFile) {
        try {
            org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytecode);
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            org.objectweb.asm.util.CheckClassAdapter.verify(cr, false, pw);
            String output = sw.toString().trim();
            if (output.contains("Error at instruction")) {
                String sourceName = sourceFile != null ? sourceFile.getFileName().toString() : className;
                CompilerReporter.error(sourceName, 0, 0, "Bytecode verification failed for '" + className + "':\n" + output, "BytecodeVerifier");
                return false;
            }
            return true;
        } catch (Throwable t) {
            String sourceName = sourceFile != null ? sourceFile.getFileName().toString() : className;
            CompilerReporter.error(sourceName, 0, 0, "Bytecode verification error for '" + className + "': " + t.getMessage(), "BytecodeVerifier");
            return false;
        }
    }

    private static void saveBytecode(Map<String, byte[]> results, Path sourceFile) {
        String binDir = getBinaryDir();
        deployStdlib(binDir);
        String sourceAbsPath = getNormalizedPathString(sourceFile);
        for (Map.Entry<String, byte[]> entry : results.entrySet()) {
            try {
                Path path = Paths.get(binDir, entry.getKey().replace('.', '/') + ".class");
                Files.createDirectories(path.getParent());
                Files.write(path, entry.getValue());

                Path sourceRecordPath = Paths.get(binDir, entry.getKey().replace('.', '/') + ".class.source");
                Files.writeString(sourceRecordPath, sourceAbsPath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static boolean executeMain(String mainClassName) {
        ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
        try {
            // Re-setup classpath to include newly generated classes
            List<URL> urls = buildClasspathUrls(true);

            try (MemoryClassLoader cl = new MemoryClassLoader(urls.toArray(new URL[0]), OceanRunnerV3.class.getClassLoader(), compiledInMemoryClasses)) {
                Thread.currentThread().setContextClassLoader(cl);

                Class<?> cls = findClassWithMain(mainClassName, cl);
                if (cls == null) {
                    System.err.println("[ERROR] No class found for '" + mainClassName + "'. Tried common generated patterns.");
                    if (Boolean.getBoolean("ocean.debug")) {
                        System.out.println("[DEBUG] Binary directory: " + getBinaryDir());
                        File binDir = new File(getBinaryDir());
                        if (binDir.exists()) {
                            System.out.println("[DEBUG] Bin dir contents: " + Arrays.toString(binDir.list()));
                        } else {
                            System.out.println("[DEBUG] Bin dir does NOT exist!");
                        }
                    }
                    return false;
                }
                Method mainMethod = cls.getDeclaredMethod("main", String[].class);
                mainMethod.setAccessible(true);
                mainMethod.invoke(null, (Object) programArgs);
                return true;
            }
        } catch (NoSuchMethodException e) {
            if (!"true".equals(System.getProperty("quiet")) && !isJsonMode) {
                System.out.println("[INFO] Compilation successful. No main method found in " + mainClassName + ", skipping execution.");
            }
            return true;
        } catch (Exception e) {
            printCleanStackTrace(e);
            return false;
        } finally {
            Thread.currentThread().setContextClassLoader(oldCl);
        }
    }

    private static String cleanStackTraceClassName(String fqName) {
        if (fqName == null) return "";
        int lastDot = fqName.lastIndexOf('.');
        if (lastDot != -1) {
            String pkg = fqName.substring(0, lastDot);
            String cls = fqName.substring(lastDot + 1);
            if (pkg.equals(cls)) {
                return cls;
            }
        }
        return fqName;
    }

    private static void printCleanStackTrace(Throwable t) {
        Throwable cause = (t.getCause() != null) ? t.getCause() : t;
        printCleanStackTraceInternal(cause, false);
    }

    private static void printCleanStackTraceInternal(Throwable t, boolean isCausedBy) {
        if (t == null) return;
        String prefix = isCausedBy ? "Caused by: " : "Exception in thread \"" + Thread.currentThread().getName() + "\" ";
        System.err.println(prefix + t);
        for (StackTraceElement elem : t.getStackTrace()) {
            String className = elem.getClassName();
            if (className.startsWith("java.lang.reflect.") ||
                    className.startsWith("jdk.internal.reflect.") ||
                    className.startsWith("ocean.compiler.") ||
                    className.startsWith("com.sun.local.") ||
                    className.startsWith("sun.reflect.") ||
                    className.startsWith("java.lang.Thread") ||
                    className.startsWith("java.security.AccessController")) {
                continue;
            }
            String cleanClass = cleanStackTraceClassName(className);
            String fileName = elem.getFileName();
            int line = elem.getLineNumber();
            String lineStr = (line >= 0) ? ":" + line : "";
            String fileInfo = (fileName != null) ? "(" + fileName + lineStr + ")" : "(Unknown Source)";
            System.err.println("    at " + cleanClass + "." + elem.getMethodName() + fileInfo);
        }
        if (t.getCause() != null) {
            printCleanStackTraceInternal(t.getCause(), true);
        }
    }

    private static Class<?> findClassWithMain(String name, ClassLoader cl) {
        String capitalized = capitalize(name);

        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG] Searching for class with main: " + name);
        }

        if (name != null && !name.isEmpty()) {
            // 0. Direct binary name check
            try {
                Class<?> cls = Class.forName(name.replace('/', '.'), true, cl);
                if (Boolean.getBoolean("ocean.debug")) {
                    System.out.println("[DEBUG] Found class via direct name: " + name);
                }
                return cls;
            } catch (LinkageError t) {
                if (!(t instanceof NoClassDefFoundError && t.getMessage() != null && t.getMessage().contains("wrong name"))) {
                    System.err.println("[ERROR] An error occurred while loading or verifying target class: '" + name + "' (" + t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "") + ")");
                    printCleanStackTrace(t);
                    return null;
                }
            } catch (Throwable ignored) {
            }

            // 1. Scan registry for a class matching the simple name that has a main method (current compilation run)
            for (String internalName : CompilerRegistry.globalMethodRegistry.keySet()) {
                if (internalName.endsWith("/" + name) || internalName.endsWith("/" + capitalized) || internalName.equals(name)) {
                    Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(internalName);
                    if (methods != null && methods.containsKey("main")) {
                        String desc = methods.get("main");
                        if ("([Ljava/lang/String;)V".equals(desc) || "()V".equals(desc)) {
                            String binaryName = internalName.replace('/', '.');
                            try {
                                Class<?> cls = Class.forName(binaryName, true, cl);
                                if (Boolean.getBoolean("ocean.debug")) {
                                    System.out.println("[DEBUG] Found class via targeted registry scan: " + binaryName);
                                }
                                return cls;
                            } catch (LinkageError t) {
                                System.err.println("[ERROR] An error occurred while loading or verifying target class: '" + binaryName + "' (" + t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "") + ")");
                                printCleanStackTrace(t);
                                return null;
                            } catch (Throwable t) {
                                if (Boolean.getBoolean("ocean.debug")) {
                                    System.out.println("[DEBUG] Class.forName failed for targeted candidate " + internalName + ": " + t.getMessage());
                                    t.printStackTrace(System.out);
                                }
                            }
                        }
                    }
                }
            }

            // 1.5. Scan registry for a class matching the simple name, even if it DOES NOT have a main method (current compilation run)
            for (String internalName : CompilerRegistry.globalMethodRegistry.keySet()) {
                if (internalName.endsWith("/" + name) || internalName.endsWith("/" + capitalized) || internalName.equals(name)) {
                    String binaryName = internalName.replace('/', '.');
                    try {
                        Class<?> cls = Class.forName(binaryName, true, cl);
                        if (Boolean.getBoolean("ocean.debug")) {
                            System.out.println("[DEBUG] Found class via targeted registry scan (no main method): " + binaryName);
                        }
                        return cls;
                    } catch (LinkageError t) {
                        System.err.println("[ERROR] An error occurred while loading or verifying target class: '" + binaryName + "' (" + t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "") + ")");
                        printCleanStackTrace(t);
                        return null;
                    } catch (Throwable ignored) {
                    }
                }
            }

            // 1.6. Try common/stale naming patterns in classpath for targeted name
            String[] trials = {
                    name,
                    capitalized,
                    name + "." + name,
                    name + "." + capitalized,
                    capitalized + "." + capitalized,
                    "ocean.compiler.generated." + name + "." + name,
                    "ocean.compiler.generated." + name + "." + capitalized,
                    "ocean.compiler.generated." + capitalized + "." + capitalized
            };
            for (String trial : trials) {
                try {
                    Class<?> cls = Class.forName(trial, true, cl);
                    if (Boolean.getBoolean("ocean.debug")) {
                        System.out.println("[DEBUG] Found class via trials: " + trial);
                    }
                    return cls;
                } catch (LinkageError t) {
                    if (t instanceof NoClassDefFoundError && t.getMessage() != null && t.getMessage().contains("wrong name")) {
                        int idx = t.getMessage().indexOf("wrong name: ");
                        if (idx != -1) {
                            String actualInternal = t.getMessage().substring(idx + "wrong name: ".length()).replace(')', ' ').trim();
                            String actualBinary = actualInternal.replace('/', '.');
                            try {
                                return Class.forName(actualBinary, true, cl);
                            } catch (Throwable ignored) {}
                        }
                        continue;
                    }
                    System.err.println("[ERROR] An error occurred while loading or verifying target class: '" + trial + "' (" + t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "") + ")");
                    printCleanStackTrace(t);
                    return null;
                } catch (Throwable ignored) {
                }
            }

            // 1.7. Directory scanner: Scan getBinaryDir() recursively for targetName + ".class"
            try {
                File binDir = new File(getBinaryDir());
                if (binDir.exists()) {
                    List<String> foundClasses = new ArrayList<>();
                    String simpleSearchName = (name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name);
                    findClassFiles(binDir, simpleSearchName, "", foundClasses);
                    findClassFiles(binDir, capitalize(simpleSearchName), "", foundClasses);
                    for (String clsName : foundClasses) {
                        try {
                            Class<?> cls = Class.forName(clsName, true, cl);
                            if (Boolean.getBoolean("ocean.debug")) {
                                System.out.println("[DEBUG] Found class via binary directory scan: " + clsName);
                            }
                            return cls;
                        } catch (LinkageError t) {
                            if (t instanceof NoClassDefFoundError && t.getMessage() != null && t.getMessage().contains("wrong name")) {
                                continue;
                            }
                            System.err.println("[ERROR] An error occurred while loading or verifying target class: '" + clsName + "' (" + t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "") + ")");
                            printCleanStackTrace(t);
                            return null;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // When a specific class name was requested, DO NOT fall back to arbitrary unrelated classes!
            return null;
        }

        // 2. Scan registry for ANY class that has a main method (only when no specific class was requested)
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG] Scanning registry for fallback main method... Total classes: " + CompilerRegistry.globalMethodRegistry.size());
        }
        for (String internalName : CompilerRegistry.globalMethodRegistry.keySet()) {
            if (!currentRunClasses.isEmpty() && !currentRunClasses.contains(internalName) && !currentRunClasses.contains(internalName.replace('/', '.'))) {
                continue;
            }
            Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(internalName);
            if (methods != null && methods.containsKey("main")) {
                String desc = methods.get("main");
                if ("([Ljava/lang/String;)V".equals(desc) || "()V".equals(desc)) {
                    try {
                        String binaryName = internalName.replace('/', '.');
                        Class<?> cls = Class.forName(binaryName, true, cl);
                        if (Boolean.getBoolean("ocean.debug")) {
                            System.out.println("[DEBUG] Found class via fallback registry scan: " + binaryName);
                        }
                        return cls;
                    } catch (Throwable e) {
                        if (Boolean.getBoolean("ocean.debug")) {
                            System.out.println("[DEBUG] Failed to load candidate class " + internalName + ": " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                }
            }
        }

        return null;
    }

    private static void findClassFiles(File dir, String targetName, String currentPkg, List<String> result) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                String nextPkg = currentPkg.isEmpty() ? f.getName() : (currentPkg + "." + f.getName());
                findClassFiles(f, targetName, nextPkg, result);
            } else if (f.getName().equalsIgnoreCase(targetName + ".class")) {
                String nameOnly = f.getName().substring(0, f.getName().length() - 6);
                result.add(currentPkg.isEmpty() ? nameOnly : (currentPkg + "." + nameOnly));
            }
        }
    }

    private static String getFullClassNameForFile(Path p, String simpleName) {
        String packageName = extractPackageName(p);
        if (packageName != null && !packageName.isEmpty()) {
            return packageName.replace('.', '/') + "/" + simpleName;
        }
        return simpleName;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ENGLISH) + s.substring(1);
    }

    public static String cleanClassName(String arg) {
        if (arg == null) return null;
        if (arg.endsWith(".ocean")) arg = arg.substring(0, arg.length() - 6);
        int lastSlash = Math.max(arg.lastIndexOf('/'), arg.lastIndexOf('\\'));
        if (lastSlash != -1) arg = arg.substring(lastSlash + 1);
        return arg;
    }

    public static String resolveMainClassNameFromArg(String arg) {
        if (arg == null) return null;
        try {
            Path p = Paths.get(arg);
            if (!Files.exists(p) && !arg.endsWith(".ocean")) {
                Path withExt = Paths.get(arg + ".ocean");
                if (Files.exists(withExt)) p = withExt;
            }
            if (Files.exists(p) && !Files.isDirectory(p)) {
                SourceHeaderInfo info = getSourceHeaderInfo(p);
                String simpleName = cleanClassName(arg);
                if (info != null && info.packageName != null && !info.packageName.isEmpty()) {
                    return info.packageName + "." + simpleName;
                }
                return simpleName;
            }
        } catch (Exception ignored) {
        }
        return cleanClassName(arg);
    }

    public record SourceHeaderInfo(String packageName, Set<String> declaredTypes) {}

    private static final ConcurrentHashMap<Path, SourceHeaderInfo> headerInfoCache = new ConcurrentHashMap<>();

    public static SourceHeaderInfo getSourceHeaderInfo(Path p) {
        return headerInfoCache.computeIfAbsent(p, key -> {
            try {
                String content = Files.readString(key, StandardCharsets.UTF_8);
                return scanHeaderInfo(content, key.getFileName().toString());
            } catch (Exception e) {
                Set<String> types = new HashSet<>();
                String fn = key.getFileName().toString();
                if (fn.endsWith(".ocean")) fn = fn.substring(0, fn.length() - 6);
                types.add(fn);
                return new SourceHeaderInfo("", types);
            }
        });
    }

    private static SourceHeaderInfo scanHeaderInfo(String code, String fileName) {
        Set<String> declaredTypes = new HashSet<>();
        String fn = fileName.endsWith(".ocean") ? fileName.substring(0, fileName.length() - 6) : fileName;
        declaredTypes.add(fn);

        if (code == null || code.isEmpty()) return new SourceHeaderInfo("", declaredTypes);

        String packageName = "";
        int len = code.length();
        int i = 0;

        while (i < len) {
            char c = code.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            if (c == '/' && i + 1 < len && code.charAt(i + 1) == '/') {
                i += 2;
                while (i < len && code.charAt(i) != '\n' && code.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }

            if (c == '/' && i + 1 < len && code.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(code.charAt(i) == '*' && code.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }

            if (c == '"' || c == '\'') {
                i++;
                while (i < len && code.charAt(i) != c) {
                    if (code.charAt(i) == '\\') i++;
                    i++;
                }
                i++;
                continue;
            }

            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < len && Character.isJavaIdentifierPart(code.charAt(i))) {
                    i++;
                }
                String word = code.substring(start, i);

                if ("package".equals(word)) {
                    while (i < len && Character.isWhitespace(code.charAt(i))) i++;
                    int pkgStart = i;
                    while (i < len && (Character.isJavaIdentifierPart(code.charAt(i)) || code.charAt(i) == '.')) {
                        i++;
                    }
                    if (pkgStart < i) {
                        packageName = code.substring(pkgStart, i).trim();
                    }
                } else if ("class".equals(word) || "interface".equals(word) || "enum".equals(word) || "record".equals(word)) {
                    while (i < len && Character.isWhitespace(code.charAt(i))) i++;
                    int nameStart = i;
                    while (i < len && Character.isJavaIdentifierPart(code.charAt(i))) {
                        i++;
                    }
                    if (nameStart < i) {
                        declaredTypes.add(code.substring(nameStart, i));
                    }
                }
                continue;
            }

            i++;
        }

        return new SourceHeaderInfo(packageName, declaredTypes);
    }

    private static Set<String> extractWordTokens(String text) {
        if (text == null || text.isEmpty()) return Collections.emptySet();
        Set<String> words = new HashSet<>();
        int len = text.length();
        int i = 0;
        while (i < len) {
            char c = text.charAt(i);
            if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < len && Character.isJavaIdentifierPart(text.charAt(i))) {
                    i++;
                }
                words.add(text.substring(start, i));
            } else {
                i++;
            }
        }
        return words;
    }

    private static String extractPackageName(Path p) {
        SourceHeaderInfo info = getSourceHeaderInfo(p);
        return (info.packageName != null && !info.packageName.isEmpty()) ? info.packageName : null;
    }

    private static void cleanBinaryDir(List<Path> sources) {
        if (sources == null) return;
        String binDir = getBinaryDir();
        for (Path p : sources) {
            try {
                SourceHeaderInfo info = getSourceHeaderInfo(p);
                String pkg = info.packageName;
                String subDir = extractSubDir(p, pkg);

                Path targetDir = Paths.get(binDir, subDir);
                if (!Files.exists(targetDir)) {
                    continue;
                }

                Set<String> declaredTypes = addClassToDeclaredTypes(info.declaredTypes,p);

                Files.walkFileTree(targetDir, new SimpleFileVisitor<>() {
                    @NotNull
                    @Override
                    public FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                        if (subDir.isEmpty() && !dir.equals(targetDir)) return FileVisitResult.SKIP_SUBTREE;

                        return FileVisitResult.CONTINUE;
                    }

                    @NotNull
                    @Override
                    public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                        String name = file.getFileName().toString();
                        if (name.endsWith(".class")) {
                            String baseClass = name.substring(0, name.length() - 6);
                            int dollarIdx = baseClass.indexOf('$');
                            String mainPart = dollarIdx != -1 ? baseClass.substring(0, dollarIdx) : baseClass;
                            if (declaredTypes.contains(mainPart)) {
                                Files.delete(file);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private static Set<String> addClassToDeclaredTypes(Set<String> declaredTypes ,Path p) {
        String filenameSimple = p.getFileName().toString();
        if (filenameSimple.endsWith(".ocean")) {
            filenameSimple = filenameSimple.substring(0, filenameSimple.length() - 6);
        }
        declaredTypes.add(filenameSimple);
        return declaredTypes;
    }

    private static String extractSubDir(Path p, String pkg) {
        if (pkg != null && !pkg.isEmpty()) {
            return pkg.replace('.', '/');
        }
        return "";
    }

    private static void reportError(String filePath, String message) {
        CompilerReporter.error(filePath, 0, 0, message, "OceanRunner");
    }

    private static List<URL> buildClasspathUrls(boolean includeBinaryDir) throws MalformedURLException {
        List<URL> urls = new ArrayList<>();

        if (includeBinaryDir) {
            urls.add(new File(getBinaryDir()).getAbsoluteFile().toURI().toURL());
        }

        File buildClasses = new File("build/classes/java/main");
        if (buildClasses.exists()) {
            urls.add(buildClasses.getAbsoluteFile().toURI().toURL());
        }

        if (activeClasspath != null && !activeClasspath.isEmpty()) {
            for (String part : activeClasspath.split(File.pathSeparator)) {
                addClasspathPart(part, urls);
            }
        }

        return urls;
    }

    private static void printHelp() {
        System.out.println("Ocean Compiler v" + CompilerConfig.COMPILER_VERSION + " - The Ocean Programming Language");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  ocean <source.ocean> [args...]       Compile and execute an Ocean program");
        System.out.println("  ocean -c <source.ocean>              Compile to bytecode (.class) only");
        System.out.println("  ocean --version | -v                 Display compiler version");
        System.out.println("  ocean --help | -h                    Display this help message");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -cp, -classpath <path>               Specify additional classpath directories or JARs");
        System.out.println("  -c, --compile-only                   Compile source files without running main");
        System.out.println("  --json                               Emit compiler diagnostics in structured JSON format");
        System.out.println("  -debug                               Enable compiler debugging logs");
    }
}
