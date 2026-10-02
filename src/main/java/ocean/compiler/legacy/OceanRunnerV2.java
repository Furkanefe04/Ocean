package ocean.compiler.legacy;

import ocean.compiler.*;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import ocean.compiler.OceanParser;
import ocean.compiler.OceanLexer;
import ocean.compiler.legacy.OceanToBytecodeVisitor;
import org.jetbrains.annotations.NotNull;

/**
 * Ocean Language - Version 2.0 Runner (Legacy)
 * This is the historical AST-based runner. 
 * Kept for backward compatibility and as a reference.
 */
public class OceanRunnerV2 {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: OceanRunnerV2 <MainClassName> [-cp <classpath>]");
            System.exit(1);
        }

        String mainClassName = null;
        String classpath = null;
        boolean isJson = false;

        StringBuilder cpBuilder = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if ("-cp".equals(args[i]) || "-classpath".equals(args[i])) {
                while (i + 1 < args.length && !args[i+1].startsWith("-")) {
                    if (!cpBuilder.isEmpty()) cpBuilder.append(File.pathSeparator);
                    cpBuilder.append(args[++i]);
                }
                classpath = cpBuilder.toString();
            } else if ("--json".equals(args[i])) {
                isJson = true;
            } else if (mainClassName == null) {
                mainClassName = OceanRunnerV3.cleanClassName(args[i]);
            }
        }

        if (mainClassName == null) {
            System.err.println("Main class name is required.");
            System.exit(1);
        }

        setupClasspath(classpath);

        try {
            CompilerReporter.clear();
            OceanToBytecodeVisitor.clearRegistries();
            
            List<Path> nameCollectionPaths = new ArrayList<>();
            for (String arg : args) {
                if (!arg.startsWith("-")) {
                    File f = new File(arg);
                    if (f.exists()) {
                        if (f.isDirectory()) nameCollectionPaths.add(f.toPath());
                        else if (arg.endsWith(".ocean")) nameCollectionPaths.add(f.toPath());
                    }
                }
            }
            if (nameCollectionPaths.isEmpty()) {
                nameCollectionPaths.add(Paths.get("examples"));
                nameCollectionPaths.add(Paths.get("src"));
            }

            for (Path path : nameCollectionPaths) {
                if (Files.exists(path)) {
                    Files.walkFileTree(path, new SimpleFileVisitor<>() {
                        @NotNull
                        @Override
                        public FileVisitResult visitFile(@NotNull Path p, @NotNull BasicFileAttributes attrs) {
                            if (p.toString().endsWith(".ocean")) {
                                String fileName = p.getFileName().toString();
                                String baseName = fileName.substring(0, fileName.length() - 6);
                                String fullPathKey = "ocean/compiler/stdlib/" + baseName + "/" + baseName;
                                OceanToBytecodeVisitor.globalMethodRegistry.computeIfAbsent(fullPathKey, k -> new HashMap<>());
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }

            List<Path> preScanPaths = new ArrayList<>();
            boolean customPreScanProvided = false;
            for (String arg : args) {
                if (!arg.startsWith("-")) {
                    File f = new File(arg);
                    if (f.exists()) {
                        if (f.isDirectory()) {
                            preScanPaths.add(f.toPath());
                            customPreScanProvided = true;
                        } else if (arg.endsWith(".ocean")) {
                            preScanPaths.add(f.toPath());
                            Path parent = f.toPath().toAbsolutePath().getParent();
                            if (parent != null && !preScanPaths.contains(parent)) {
                                preScanPaths.add(parent);
                            }
                            customPreScanProvided = true;
                        }
                    }
                }
            }
            if (!customPreScanProvided) {
                preScanPaths.add(Paths.get("examples"));
                preScanPaths.add(Paths.get("src"));
            }
            
            for (Path resourcesPath : preScanPaths) {
                if (Files.exists(resourcesPath)) {
                    Files.walkFileTree(resourcesPath, new SimpleFileVisitor<>() {
                        @NotNull
                        @Override
                        public FileVisitResult visitFile(@NotNull Path p, @NotNull BasicFileAttributes attrs) {
                            if (p.toString().endsWith(".ocean")) {
                                try {
                                    String content = Files.readString(p);
                                    OceanLexer lexer = new OceanLexer(CharStreams.fromString(content));
                                    lexer.removeErrorListeners();
                                    lexer.addErrorListener(new OceanErrorListener(p.toAbsolutePath().toString()));

                                    OceanParser parser = new OceanParser(OceanTokenStreamFactory.createTokenStream(lexer));
                                    parser.removeErrorListeners();
                                    parser.addErrorListener(new OceanErrorListener(p.toAbsolutePath().toString()));

                                    ParseTree tree = parser.program();

                                    OceanToBytecodeVisitor visitor = new OceanToBytecodeVisitor();
                                    visitor.setCurrentFile(p.getFileName().toString());
                                    visitor.setPreScan(true);
                                    try {
                                        visitor.visit(tree);
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @NotNull
                        @Override
                        public FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                            String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
                            if (name.equals("build") || name.equals("bin") || name.startsWith(".") || name.equals("ocean_bin"))
                                return FileVisitResult.SKIP_SUBTREE;
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
            
            if (CompilerReporter.hasErrors()) {
                CompilerReporter.printSummary();
                System.exit(1);
            }

            List<Path> searchPaths = new ArrayList<>();
            List<Path> explicitSourceFiles = new ArrayList<>();
            boolean customPathProvided = false;
            for (String arg : args) {
                if (!arg.startsWith("-")) {
                    File f = new File(arg);
                    if (f.exists()) {
                        if (f.isDirectory()) {
                            searchPaths.add(f.toPath());
                            customPathProvided = true;
                        } else if (arg.endsWith(".ocean")) {
                            explicitSourceFiles.add(f.toPath());
                            customPathProvided = true;
                        }
                    }
                }
            }
            
            if (!customPathProvided) {
                searchPaths.add(Paths.get("examples"));
                searchPaths.add(Paths.get("src"));
            }

            final boolean[] compileFailed = {false};
            for (Path sourceFile : explicitSourceFiles) {
                compileOceanFileIfStale(sourceFile, compileFailed);
            }
            for (Path searchPath : searchPaths) {
                if (Files.exists(searchPath)) {
                    Files.walkFileTree(searchPath, new SimpleFileVisitor<>() {
                        @NotNull
                        @Override
                        public FileVisitResult visitFile(@NotNull Path p, @NotNull BasicFileAttributes attrs) {
                            if (p.toString().endsWith(".ocean")) {
                                try {
                                    String fileName = p.getFileName().toString();
                                    String baseName = fileName.substring(0, fileName.length() - 6);
                                    Path targetDir = Paths.get("build", "ocean_bin", "org", "Ocean", "Compiler", "generated", baseName);

                                    if (isCompiledOutputCurrent(p, targetDir)) return FileVisitResult.CONTINUE;

                                    String content = Files.readString(p);
                                    OceanLexer lexer = new OceanLexer(CharStreams.fromString(content));
                                    lexer.removeErrorListeners();
                                    lexer.addErrorListener(new OceanErrorListener(p.toAbsolutePath().toString()));

                                    OceanParser parser = new OceanParser(OceanTokenStreamFactory.createTokenStream(lexer));
                                    parser.removeErrorListeners();
                                    parser.addErrorListener(new OceanErrorListener(p.toAbsolutePath().toString()));

                                    ParseTree tree = parser.program();
                                    if (CompilerReporter.hasErrors()) return FileVisitResult.CONTINUE;

                                    OceanToBytecodeVisitor visitor = new OceanToBytecodeVisitor();
                                    visitor.setCurrentFile(p.getFileName().toString());
                                    visitor.setPreScan(false);
                                    if (!Boolean.getBoolean("quiet"))
                                        System.out.println("Compiling (V2): " + p.getFileName());
                                    visitor.visit(tree);
                                    saveBytecode(visitor.getBytecodeResults());
                                } catch (Exception e) {
                                    compileFailed[0] = true;
                                    CompilerReporter.error(p.toAbsolutePath().toString(), 0, 0, "Compilation failed: " + e.getMessage(), "OceanRunnerV2");
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }

            if (compileFailed[0] || CompilerReporter.hasErrors()) {
                CompilerReporter.printSummary();
                System.exit(1);
            }

            if (!isJson) CompilerReporter.printSummary();

            setupClasspath(classpath);
            if (!executeMain(mainClassName)) System.exit(1);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void setupClasspath(String cp) {
        try {
            List<URL> urls = new ArrayList<>();
            urls.add(new File("build/ocean_bin").getAbsoluteFile().toURI().toURL());
            if (cp != null && !cp.isEmpty()) {
                for (String part : cp.split(File.pathSeparator)) {
                    File f = new File(part.trim().replace("\"", ""));
                    if (f.exists()) urls.add(f.toURI().toURL());
                }
            }
            URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[0]), OceanRunnerV2.class.getClassLoader());
            Thread.currentThread().setContextClassLoader(classLoader);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static void saveBytecode(Map<String, byte[]> results) {
        try {
            File binDir = new File("build/ocean_bin");
            if (!binDir.exists()) binDir.mkdirs();
            for (Map.Entry<String, byte[]> entry : results.entrySet()) {
                File classFile = new File(binDir, entry.getKey() + ".class");
                if (classFile.getParentFile() != null) classFile.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(classFile)) {
                    fos.write(entry.getValue());
                }
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private static void compileOceanFileIfStale(Path p, boolean[] compileFailed) {
        try {
            String fileName = p.getFileName().toString();
            String baseName = fileName.substring(0, fileName.length() - 6);
            Path targetDir = Paths.get("build", "ocean_bin", "org", "Ocean", "Compiler", "generated", baseName);
            if (isCompiledOutputCurrent(p, targetDir)) return;
            // ... (similar to visitFile logic)
        } catch (Exception e) { compileFailed[0] = true; }
    }

    private static boolean isCompiledOutputCurrent(Path sourceFile, Path targetDir) throws IOException {
        if (!Files.exists(targetDir)) return false;
        FileTime targetTime = Files.getLastModifiedTime(targetDir);
        return Files.getLastModifiedTime(sourceFile).compareTo(targetTime) <= 0;
    }

    private static boolean executeMain(String mainClassName) {
        try {
            String fullClassName = mainClassName;
            if (!mainClassName.contains(".")) {
                fullClassName = "ocean.compiler.generated." + mainClassName + "." + mainClassName;
            }
            Class<?> cls = Class.forName(fullClassName, true, Thread.currentThread().getContextClassLoader());
            Method mainMethod = cls.getDeclaredMethod("main", String[].class);
            mainMethod.setAccessible(true);
            mainMethod.invoke(null, (Object) new String[0]);
            return true;
        } catch (Exception e) {
            System.err.println("Execution Error: " + e.getMessage());
            return false;
        }
    }

}
