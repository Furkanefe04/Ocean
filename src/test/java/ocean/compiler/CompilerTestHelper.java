package ocean.compiler;

import org.antlr.v4.runtime.CharStreams;

import java.util.HashMap;
import java.util.Map;

public class CompilerTestHelper {

    public static Class<?> compileAndLoad(String className, String code) throws Exception {
        Map<String, byte[]> classes = compileToBytecodeMap(code, className);
        ClassLoader parent = ClassLoader.getSystemClassLoader();
        ClassLoader cl = new ClassLoader(parent) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                String slashName = name.replace('.', '/');
                if (classes.containsKey(slashName)) {
                    byte[] bytes = classes.get(slashName);
                    return defineClass(name, bytes, 0, bytes.length);
                }
                for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                    if (entry.getKey().endsWith("/" + name) || entry.getKey().equals(name)) {
                        byte[] bytes = entry.getValue();
                        return defineClass(name, bytes, 0, bytes.length);
                    }
                }
                if (name.contains(".")) {
                    String simple = name.substring(name.lastIndexOf('.') + 1);
                    if (classes.containsKey(simple)) {
                        return loadClass(simple);
                    }
                }
                return super.findClass(name);
            }
        };

        String mainClassFq = null;
        for (String k : classes.keySet()) {
            if (k.equals(className) || k.endsWith("/" + className) || k.endsWith("." + className)) {
                mainClassFq = k;
                break;
            }
        }
        if (mainClassFq == null) {
            for (String k : classes.keySet()) {
                if (!k.contains("$") && (k.contains(className) || className.contains(k))) {
                    mainClassFq = k;
                    break;
                }
            }
        }
        if (mainClassFq == null && !classes.isEmpty()) {
            mainClassFq = classes.keySet().iterator().next();
        }
        if (mainClassFq == null) {
            throw new IllegalStateException("No classes generated for: " + className);
        }
        return cl.loadClass(mainClassFq.replace('/', '.'));
    }

    public static Map<String, Class<?>> compileAndLoadAll(String className, String code) throws Exception {
        Map<String, byte[]> classes = compileToBytecodeMap(code, className);
        ClassLoader parent = ClassLoader.getSystemClassLoader();
        ClassLoader cl = new ClassLoader(parent) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                String slashName = name.replace('.', '/');
                if (classes.containsKey(slashName)) {
                    byte[] bytes = classes.get(slashName);
                    return defineClass(name, bytes, 0, bytes.length);
                }
                for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                    if (entry.getKey().endsWith("/" + name) || entry.getKey().equals(name)) {
                        byte[] bytes = entry.getValue();
                        return defineClass(name, bytes, 0, bytes.length);
                    }
                }
                if (name.contains(".")) {
                    String simple = name.substring(name.lastIndexOf('.') + 1);
                    if (classes.containsKey(simple)) {
                        return loadClass(simple);
                    }
                }
                return super.findClass(name);
            }
        };

        Map<String, Class<?>> loaded = new HashMap<>();
        for (String k : classes.keySet()) {
            String dotName = k.replace('/', '.');
            String simpleName = dotName.contains(".") ? dotName.substring(dotName.lastIndexOf('.') + 1) : dotName;
            Class<?> loadedClass = cl.loadClass(dotName);
            loaded.put(simpleName, loadedClass);
            loaded.put(dotName, loadedClass);
            if (simpleName.contains("$")) {
                loaded.put(simpleName.substring(simpleName.lastIndexOf('$') + 1), loadedClass);
            }
        }
        return loaded;
    }

    public static Map<String, byte[]> compileToBytecodeMap(String code, String className) {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
        CompilationSession session = new CompilationSession();
        CompilationSession.setActiveSession(session);
        try {
            OceanLexer lexer = new OceanLexer(CharStreams.fromString(code));
            lexer.removeErrorListeners();
            lexer.addErrorListener(new OceanErrorListener(className + ".ocean"));
            OceanParser parser = new OceanParser(OceanTokenStreamFactory.createTokenStream(lexer));
            parser.removeErrorListeners();
            parser.addErrorListener(new OceanErrorListener(className + ".ocean"));

            OceanParser.ProgramContext tree = parser.program();
            if (CompilerReporter.hasErrors()) {
                String firstError = CompilerReporter.getMessages().stream()
                        .filter(m -> m.level() == CompilerReporter.Level.ERROR)
                        .map(CompilerReporter.Message::text)
                        .findFirst()
                        .orElse("Compilation failed during parsing.");
                CompilerReporter.printSummary();
                throw new CompilationException(firstError);
            }

            String declaredPackage = null;
            for (OceanParser.CompilationUnitContext cu : tree.compilationUnit()) {
                if (cu.packageDeclaration() != null && cu.packageDeclaration().anyId() != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < cu.packageDeclaration().anyId().size(); i++) {
                        if (i > 0) sb.append("/");
                        sb.append(cu.packageDeclaration().anyId(i).getText());
                    }
                    declaredPackage = sb.toString();
                    break;
                }
            }
            String pkgPrefix = (declaredPackage != null && !declaredPackage.isEmpty()) ? (declaredPackage + "/") : "";
            String fqName = pkgPrefix + className;
            CompilerRegistry.globalMethodRegistry.computeIfAbsent(fqName, k -> new HashMap<>());
            for (OceanParser.CompilationUnitContext cu : tree.compilationUnit()) {
                String cuPkg = declaredPackage;
                if (cu.packageDeclaration() != null && cu.packageDeclaration().anyId() != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < cu.packageDeclaration().anyId().size(); i++) {
                        if (i > 0) sb.append("/");
                        sb.append(cu.packageDeclaration().anyId(i).getText());
                    }
                    cuPkg = sb.toString();
                }
                String simpleName = null;
                if (cu.classDeclaration() != null) simpleName = cu.classDeclaration().anyId().getText();
                else if (cu.interfaceDeclaration() != null) simpleName = cu.interfaceDeclaration().anyId().getText();
                else if (cu.enumDeclaration() != null) simpleName = cu.enumDeclaration().anyId().getText();
                else if (cu.annotationDeclaration() != null) simpleName = cu.annotationDeclaration().anyId().getText();

                if (simpleName != null) {
                    String prefix = (cuPkg != null && !cuPkg.isEmpty()) ? (cuPkg + "/") : "";
                    String fq = prefix + simpleName;
                    CompilerRegistry.globalMethodRegistry.computeIfAbsent(fq, k -> new HashMap<>());
                }
            }

            PreScanner scanner = new PreScanner(className + ".ocean", session);
            scanner.setCurrentFilePackage(null);
            scanner.visit(tree);
            if (CompilerReporter.hasErrors()) {
                String firstError = CompilerReporter.getMessages().stream()
                        .filter(m -> m.level() == CompilerReporter.Level.ERROR)
                        .map(CompilerReporter.Message::text)
                        .findFirst()
                        .orElse("Compilation failed during pre-scan.");
                CompilerReporter.printSummary();
                throw new CompilationException(firstError);
            }

            IRGenerator irGen = new IRGenerator(
                className + ".ocean",
                scanner.getCurrentFilePackage(),
                scanner.getImportedClasses(),
                scanner.getImportedWildcards(),
                scanner.getImportedStaticMembers(),
                scanner.getImportedStaticWildcards(),
                scanner.getSymbolTable()
            );
            ocean.compiler.ir.IRNode irTree = irGen.visit(tree);

            if (CompilerReporter.hasErrors()) {
                String firstError = CompilerReporter.getMessages().stream()
                        .filter(m -> m.level() == CompilerReporter.Level.ERROR)
                        .map(CompilerReporter.Message::text)
                        .findFirst()
                        .orElse("Compilation failed during IR generation.");
                CompilerReporter.printSummary();
                throw new CompilationException(firstError);
            }

            IRSemanticAnalyzer irAnalyzer = new IRSemanticAnalyzer(
                className + ".ocean",
                session,
                irGen.getImportedClasses(),
                irGen.getImportedWildcards(),
                irGen.getImportedStaticMembers(),
                irGen.getImportedStaticWildcards(),
                irGen.getSymbolTable()
            );
            irTree.accept(irAnalyzer);

            if (irAnalyzer.hasErrors() || CompilerReporter.hasErrors()) {
                String firstError = CompilerReporter.getMessages().stream()
                        .filter(m -> m.level() == CompilerReporter.Level.ERROR)
                        .map(CompilerReporter.Message::text)
                        .findFirst()
                        .orElse("Compilation failed during IR semantic analysis.");
                CompilerReporter.printSummary();
                throw new CompilationException(firstError);
            }

            ocean.compiler.ir.IROptimizerPipeline optimizer = new ocean.compiler.ir.IROptimizerPipeline();
            irTree = optimizer.optimize(irTree);

            IRToBytecodeEmitter emitter = new IRToBytecodeEmitter();
            irTree.accept(emitter);

            return emitter.getGeneratedClasses();
        } finally {
            // Preserve session messages to fallback so tests can read them after the session ends
            CompilationSession s = CompilationSession.getActiveSession();
            java.util.List<CompilerReporter.Message> saved = (s != null)
                    ? new java.util.ArrayList<>(s.messages)
                    : java.util.Collections.emptyList();
            CompilationSession.clearActiveSession();
            // Re-report each message into the now-active fallback list
            for (CompilerReporter.Message m : saved) {
                CompilerReporter.report(m.level(), m.file(), m.line(), m.column(), m.code(), m.text(), m.context());
            }
        }
    }
}