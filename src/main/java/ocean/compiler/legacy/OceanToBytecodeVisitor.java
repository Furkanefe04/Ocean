/**
 * Ocean Language - Version 2.0 Bytecode Visitor
 * This class is the legacy AST-based bytecode generator for the Ocean Compiler.
 * It is kept as a historical artifact and memory of the language's evolution.
 */
package ocean.compiler.legacy;

import ocean.compiler.*;

import org.antlr.v4.runtime.*;
import org.objectweb.asm.*;

import java.io.*;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.*;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.antlr.v4.runtime.tree.ParseTree;
import ocean.compiler.OceanLexer;
import ocean.compiler.OceanBaseVisitor;
import ocean.compiler.OceanParser;
/**
 * Eski doğrudan AST-Baytkod ziyaretçi derleyicisi (Legacy V2).
 * ANTLR4 parse ağacını doğrudan ASM baytkoduna dönüştüren eski nesil derleyici sınıfı.
 */
public class OceanToBytecodeVisitor extends OceanBaseVisitor<Object> {

    private static final Map<String, String> OCEAN_TYPE_ALIASES = new HashMap<>();
    static {
        OCEAN_TYPE_ALIASES.put("OceanMap", "java/util/HashMap");
        OCEAN_TYPE_ALIASES.put("OceanOutput", "java/io/PrintStream");
        OCEAN_TYPE_ALIASES.put("OceanInput", "java/util/Scanner");
        // Common Java Utilities (Auto-Imports)
        OCEAN_TYPE_ALIASES.put("List", "java/util/List");
        OCEAN_TYPE_ALIASES.put("Map", "java/util/Map");
        OCEAN_TYPE_ALIASES.put("HashMap", "java/util/HashMap");
        OCEAN_TYPE_ALIASES.put("Scanner", "java/util/Scanner");
    }

    private static final String NAME_OCEAN_INPUT = "OceanInput";
    private static final String NAME_OCEAN_OUTPUT = "OceanOutput";

    private static volatile String[] printlnOneArgParamDescriptorsCache;

    private static boolean isCompileVerbose() {
        return true;
    }

    private static String[] getPrintlnOneArgParamDescriptors() {
        String[] cached = printlnOneArgParamDescriptorsCache;
        if (cached != null)
            return cached;
        synchronized (OceanToBytecodeVisitor.class) {
            if (printlnOneArgParamDescriptorsCache != null)
                return printlnOneArgParamDescriptorsCache;
            List<String> out = new ArrayList<>();
            for (Method m : PrintStream.class.getMethods()) {
                if ("println".equals(m.getName()) && m.getParameterCount() == 1)
                    out.add(getClassDescriptor(m.getParameterTypes()[0]));
            }
            out.sort(Comparator.naturalOrder());
            printlnOneArgParamDescriptorsCache = out.toArray(new String[0]);
            return printlnOneArgParamDescriptorsCache;
        }
    }

    private final Map<String, byte[]> classes = new HashMap<>();
    public boolean registerOnly = false;
    private ClassWriter cw;
    private int lambdaCounter = 0;
    private List<LambdaInfo> syntheticLambdas = new ArrayList<>();
    private String currentCastType = null;

    private static class LambdaInfo {
        String name;
        String descriptor;
        OceanParser.LambdaExprContext ctx;
        List<String> capturedNames;
        List<String> capturedTypes;
        boolean isStatic;
        String samDesc;
        String samInterface;
    }

    private String currentClassName;
    private String currentMethodName;
    private Map<String, String> currentClassFields = new HashMap<>();
    private Map<String, Boolean> isFieldStatic = new HashMap<>();
    // ── Global registries — backed by CompilerRegistry ────────────────────────
    // These aliases preserve backward compatibility for the thousands of internal
    // usages inside this file while actual storage lives in CompilerRegistry.
    public static final Map<String, Map<String, String>> globalFieldRegistry = CompilerRegistry.globalFieldRegistry;
    public static final Map<String, Map<String, Boolean>> globalFieldMutability = CompilerRegistry.globalFieldMutability;
    public static final Map<String, Map<String, Boolean>> globalFieldStaticity = CompilerRegistry.globalFieldStaticity;
    public static final Map<String, Map<String, Integer>> globalFieldAccess = CompilerRegistry.globalFieldAccess;
    public static final Map<String, Map<String, String>> globalMethodRegistry = CompilerRegistry.globalMethodRegistry;
    public static final Map<String, Map<String, Boolean>> globalMethodStaticity = CompilerRegistry.globalMethodStaticity;
    public static final Map<String, Map<String, Integer>> globalMethodAccess = CompilerRegistry.globalMethodAccess;
    public static final Map<String, Map<String, List<String>>> globalOverloadRegistry = CompilerRegistry.globalOverloadRegistry;
    public static final Map<String, Map<String, List<CompilerRegistry.ExtensionMethodInfo>>> globalExtensionMethodRegistry = CompilerRegistry.globalExtensionMethodRegistry;
    public static final Map<String, String> globalSuperClassRegistry = CompilerRegistry.globalSuperClassRegistry;
    public static final Map<String, String[]> globalInterfaceRegistry = CompilerRegistry.globalInterfaceRegistry;
    public static final Map<String, List<CompilerRegistry.TypeParameterInfo>> globalTypeParameterRegistry = CompilerRegistry.globalTypeParameterRegistry;
    public static final Map<String, Object> reflectionCache = CompilerRegistry.reflectionCache;

    // CompilerRegistry.static{} seeds reflectionCache on class load
    // ÃƒÂ¢Ã¢â€šÂ¬Ã¢â‚¬Â no
    // init
    // needed here.

    public static final Set<String> globalAbstractClassSet = CompilerRegistry.globalAbstractClassSet;
    public static final Set<String> globalIsInterfaceSet = CompilerRegistry.globalIsInterfaceSet;

    public static void saveCache(File file) {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
            Map<String, Object> allData = new HashMap<>();
            allData.put("fieldRegistry", globalFieldRegistry);
            allData.put("fieldMutability", globalFieldMutability);
            allData.put("methodStaticity", globalMethodStaticity);
            allData.put("extensionRegistry", globalExtensionMethodRegistry);
            allData.put("methodAccess", globalMethodAccess);
            allData.put("fieldStaticity", globalFieldStaticity);
            allData.put("fieldAccess", globalFieldAccess);
            allData.put("methodRegistry", globalMethodRegistry);
            allData.put("overloadRegistry", globalOverloadRegistry);
            allData.put("superClassRegistry", globalSuperClassRegistry);
            allData.put("interfaceRegistry", globalInterfaceRegistry);
            allData.put("typeParamRegistry", globalTypeParameterRegistry);
            allData.put("abstractClassSet", new HashSet<>(globalAbstractClassSet));
            allData.put("isInterfaceSet", new HashSet<>(globalIsInterfaceSet));
            oos.writeObject(allData);
        } catch (IOException e) {
            System.err.println("Failed to save compiler cache: " + e.getMessage());
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static void loadCache(File file) {
        if (!file.exists())
            return;
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            Map<String, Object> allData = (Map<String, Object>) ois.readObject();
            if (allData.containsKey("fieldRegistry"))
                globalFieldRegistry.putAll((Map) allData.get("fieldRegistry"));
            if (allData.containsKey("fieldMutability"))
                globalFieldMutability.putAll((Map) allData.get("fieldMutability"));
            if (allData.containsKey("methodStaticity"))
                globalMethodStaticity.putAll((Map) allData.get("methodStaticity"));
            if (allData.containsKey("extensionRegistry"))
                globalExtensionMethodRegistry.putAll((Map) allData.get("extensionRegistry"));
            if (allData.containsKey("methodAccess"))
                globalMethodAccess.putAll((Map) allData.get("methodAccess"));
            if (allData.containsKey("fieldStaticity"))
                globalFieldStaticity.putAll((Map) allData.get("fieldStaticity"));
            if (allData.containsKey("fieldAccess"))
                globalFieldAccess.putAll((Map) allData.get("fieldAccess"));
            if (allData.containsKey("methodRegistry"))
                globalMethodRegistry.putAll((Map) allData.get("methodRegistry"));
            if (allData.containsKey("overloadRegistry"))
                globalOverloadRegistry.putAll((Map) allData.get("overloadRegistry"));
            if (allData.containsKey("superClassRegistry"))
                globalSuperClassRegistry.putAll((Map) allData.get("superClassRegistry"));
            if (allData.containsKey("interfaceRegistry"))
                globalInterfaceRegistry.putAll((Map) allData.get("interfaceRegistry"));
            if (allData.containsKey("typeParamRegistry"))
                globalTypeParameterRegistry.putAll((Map) allData.get("typeParamRegistry"));
            if (allData.containsKey("abstractClassSet"))
                globalAbstractClassSet.addAll((Set) allData.get("abstractClassSet"));
            if (allData.containsKey("isInterfaceSet"))
                globalIsInterfaceSet.addAll((Set) allData.get("isInterfaceSet"));
        } catch (Exception e) {
            System.err.println("Failed to load compiler cache (it might be stale): " + e.getMessage());
            file.delete();
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void loadMap(Map<String, Object> data, String key, Map target) {
        if (data.containsKey(key))
            target.putAll((Map) data.get(key));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void loadSet(Map<String, Object> data, String key, java.util.Collection target) {
        if (data.containsKey(key))
            target.addAll((java.util.Collection) data.get(key));
    }

    private final List<OceanParser.FieldDeclarationContext> fieldInitializers = new ArrayList<>();
    private String currentFile = "unknown.ocean";
    private String currentMethodContext = "Global";
    private boolean needsComputeMaxsFallback = false;
    private final Set<String> currentMethodTypeParameters = new HashSet<>();
    private String currentFilePackage = "default";

    private static boolean runtimeUtilsGenerated = false;

    public void setCurrentFile(String fileName) {
        this.currentFile = fileName;
        // Default package is the file's base name (no package declaration)
        if (fileName != null && fileName.endsWith(".ocean")) {
            this.currentFilePackage = fileName.substring(0, fileName.length() - 6);
        } else {
            this.currentFilePackage = "default";
        }
    }

    /**
     * Explicitly sets the file's package path (from package declaration).
     * Called by OceanRunner when it reads the package declaration from source.
     */
    public void setCurrentFilePackage(String packagePath) {
        if (packagePath != null) {
            this.currentFilePackage = packagePath.replace('.', '/');
        }
    }

    private String getCurrentClassPath() {
        return "ocean/compiler/stdlib/" + currentFilePackage + "/" + currentClassName;
    }

    private Set<String> getAllActiveTypeParameters() {
        Set<String> all = new HashSet<>(currentMethodTypeParameters);
        if (currentClassName != null) {
            String fullPath = getCurrentClassPath();
            List<CompilerRegistry.TypeParameterInfo> classTypeParams = globalTypeParameterRegistry.get(fullPath);
            if (classTypeParams != null) {
                for (CompilerRegistry.TypeParameterInfo info : classTypeParams) {
                    all.add(info.name);
                }
            }
        }
        return all;
    }

    private String getGeneratedPackagePrefix() {
        return "ocean/compiler/stdlib/";
    }

    private String resolveInternalPath(String id) {
        String local = getGeneratedPackagePrefix() + currentFilePackage + "/" + id;
        if (globalMethodRegistry.containsKey(local))
            return local;

        List<String> matches = new ArrayList<>();
        for (String fqName : globalMethodRegistry.keySet()) {
            if (fqName.startsWith(getGeneratedPackagePrefix())) {
                String suffix = fqName.substring(getGeneratedPackagePrefix().length());
                if (suffix.contains("/") && suffix.endsWith("/" + id)) {
                    matches.add(fqName);
                }
            }
        }
        if (matches.size() == 1)
            return matches.getFirst();
        return local;
    }

    private void reportError(ParserRuleContext ctx, String message) {
        int line = ctx != null ? ctx.start.getLine() : 0;
        int col = ctx != null ? ctx.start.getCharPositionInLine() : 0;
        CompilerReporter.error(currentFile, line, col, message, currentMethodContext);
    }

    private void reportWarning(ParserRuleContext ctx, String message) {
        int line = ctx != null ? ctx.start.getLine() : 0;
        int col = ctx != null ? ctx.start.getCharPositionInLine() : 0;
        CompilerReporter.warning(currentFile, line, col, message, currentMethodContext);
    }

    private boolean isPreScan = false;

    public void setPreScan(boolean preScan) {
        this.isPreScan = preScan;
    }

    public static void clearRegistries() {
        CompilerRegistry.clearAll();
    }

    private static boolean isOceanAbstractClass(String jvmName) {
        return globalAbstractClassSet.contains(jvmName);
    }

    private void checkAccess(String owner, String member, int modifiers, ParserRuleContext ctx) {
        if (isPreScan || currentClassName == null)
            return;

        String currentClassPath = getCurrentClassPath();
        if (owner.equals(currentClassPath))
            return; // Self access is always allowed

        if ((modifiers & Opcodes.ACC_PRIVATE) != 0) {
            reportError(ctx, "Member '" + member + "' of class '" + owner + "' is private and not accessible from '"
                    + currentClassPath + "'.");
        } else if ((modifiers & Opcodes.ACC_PROTECTED) != 0) {
            // Check if current class is a subclass of owner
            if (!isAssignable(OceanTypeSystem.wrapObjectType(currentClassPath), OceanTypeSystem.wrapObjectType(owner))) {
                reportError(ctx, "Member '" + member + "' of class '" + owner
                        + "' is protected and not accessible from '" + currentClassPath + "'.");
            }
        }
        // Package-private could be added here if packages were strictly enforced
    }

    private void resetClassLocalState() {
        currentClassFields = new HashMap<>();
        isFieldStatic = new HashMap<>();
        fieldInitializers.clear();
        syntheticLambdas = new ArrayList<>();
        needsComputeMaxsFallback = false;
    }

    private boolean hasConstructor = false;

    private String currentMethodReturnDescriptor = "V";
    private String currentSuperName = "java/lang/Object";
    private final Map<String, String> importedClasses = new HashMap<>();

    private boolean isOceanClass(String desc) {
        return desc != null && desc.startsWith("Locean/compiler/generated/");
    }

    private boolean isImportedClass(String desc) {
        if (desc == null)
            return false;
        String typeName = desc;
        if (desc.startsWith("L") && desc.endsWith(";")) {
            typeName = desc.substring(1, desc.length() - 1).replace("/", ".");
        }
        return importedClasses.containsKey(typeName) || getJavaLangDefault(typeName) != null;
    }

    private boolean isStaticTarget(String id) {
        if (id == null)
            return false;
        if (id.contains(".") || getJavaLangDefault(id) != null || importedClasses.containsKey(id))
            return true;

        // Check for Ocean classes
        for (String fqName : globalMethodRegistry.keySet()) {
            if (fqName.endsWith("/" + id)) {
                return true;
            }
        }

        String desc = getTypeDescriptor(id);
        if (desc.startsWith("L") && !desc.startsWith("Locean/compiler/generated/"))
            return true;

        return false;
    }

    private final Deque<LoopContext> loopStack = new ArrayDeque<>();
    private final Deque<Runnable> finallyStack = new ArrayDeque<>();

    private record LoopContext(Label continueLabel, Label breakLabel, int finallyStackSize, String label) {
    }

    private String currentLabel = null;

    private int unrollDepth = 0;
    private static final int MAX_UNROLL_BODY_COMPLEXITY = 50;
    private static final int FOR_UNROLL_FACTOR = 8;
    private static final int WHILE_UNROLL_FACTOR = 4;

    private int estimateComplexity(ParseTree tree) {
        if (tree == null)
            return 0;
        int count = 1;
        for (int i = 0; i < tree.getChildCount(); i++) {
            count += estimateComplexity(tree.getChild(i));
        }
        return count;
    }

    public Map<String, byte[]> getBytecodeResults() {
        return classes;
    }

    @Override
    public Object visitImportStatement(OceanParser.ImportStatementContext ctx) {
        List<OceanParser.AnyIdContext> ids = ctx.anyId();
        if (ids == null || ids.isEmpty())
            return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0)
                sb.append("/");
            sb.append(ids.get(i).getText());
        }
        String fullPath = sb.toString();
        String className = ids.getLast().getText();

        // Java stdlib import: import ocean.util.List → java/util/List
        if (fullPath.startsWith("ocean/")) {
            fullPath = fullPath.replaceFirst("ocean/", "java/");
        }
        // Check if this is a Java class (contains at least one lowercase segment before
        // classname)
        else if (isJavaImport(fullPath)) {
            // Leave as-is for Java imports like java/util/ArrayList
        }
        // Ocean package import: import math.Calculator →
        // ocean/compiler/generated/math/Calculator
        else {
            String oceanPath = getGeneratedPackagePrefix() + fullPath;
            // Check if this is a known Ocean class
            if (globalMethodRegistry.containsKey(oceanPath)) {
                fullPath = oceanPath;
            }
        }

        importedClasses.put(className, fullPath);
        return null;
    }

    private boolean isJavaImport(String path) {
        // Check if it resolves to a known Java class
        try {
            Class.forName(path.replace('/', '.'));
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private String getJavaLangDefault(String typeName) {
        String trimmed = typeName.trim();
        String cacheKey = "jlang#" + trimmed;
        String cached = (String) reflectionCache.get(cacheKey);
        if (cached != null)
            return cached.isEmpty() ? null : cached;
        try {
            Class.forName("java.lang." + trimmed);
            String result = "java/lang/" + trimmed;
            reflectionCache.put(cacheKey, result);
            return result;
        } catch (ClassNotFoundException e) {
            // Check Utility classes or common ones
            if (trimmed.equals("System") || trimmed.equals("String") || trimmed.equals("Math")
                    || trimmed.equals("Object") || trimmed.equals("Thread")) {
                String res = "java/lang/" + trimmed;
                reflectionCache.put(cacheKey, res);
                return res;
            }
            reflectionCache.put(cacheKey, "");
            return null;
        }
    }

    @Override
    public Object visitProgram(OceanParser.ProgramContext ctx) {
        if (!runtimeUtilsGenerated) {
            generateRuntimeUtils();
            runtimeUtilsGenerated = true;
        }

        if (isPreScan && ctx.compilationUnit() != null) {
            for (OceanParser.CompilationUnitContext cu : ctx.compilationUnit()) {
                if (cu.classDeclaration() != null) {
                    String className = cu.classDeclaration().anyId().getText();
                    String fullPath = getGeneratedPackagePrefix() + currentFilePackage + "/" + className;
                    globalMethodRegistry.computeIfAbsent(fullPath, k -> new ConcurrentHashMap<>());
                } else if (cu.interfaceDeclaration() != null) {
                    String interfaceName = cu.interfaceDeclaration().anyId().getText();
                    String fullPath = getGeneratedPackagePrefix() + currentFilePackage + "/" + interfaceName;
                    globalMethodRegistry.computeIfAbsent(fullPath, k -> new ConcurrentHashMap<>());
                    globalIsInterfaceSet.add(fullPath);
                } else if (cu.enumDeclaration() != null) {
                    String enumName = cu.enumDeclaration().anyId().getText();
                    String fullPath = getGeneratedPackagePrefix() + currentFilePackage + "/" + enumName;
                    globalMethodRegistry.computeIfAbsent(fullPath, k -> new ConcurrentHashMap<>());
                }
            }
        }

        return visitChildren(ctx);
    }

    private void generateRuntimeUtils() {
        ClassWriter cwUtils = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cwUtils.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "ocean/compiler/stdlib/RuntimeUtils",
                null, "java/lang/Object", null);
        cwUtils.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "scanner", "Ljava/util/Scanner;", null, null)
                .visitEnd();

        MethodVisitor mv = cwUtils.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getScanner",
                "()Ljava/util/Scanner;", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, "ocean/compiler/stdlib/RuntimeUtils", "scanner",
                "Ljava/util/Scanner;");
        Label l0 = new Label();
        mv.visitJumpInsn(Opcodes.IFNONNULL, l0);
        mv.visitTypeInsn(Opcodes.NEW, "java/util/Scanner");
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "in", "Ljava/io/InputStream;");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/Scanner", "<init>", "(Ljava/io/InputStream;)V", false);
        mv.visitFieldInsn(Opcodes.PUTSTATIC, "ocean/compiler/stdlib/RuntimeUtils", "scanner",
                "Ljava/util/Scanner;");
        mv.visitLabel(l0);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "ocean/compiler/stdlib/RuntimeUtils", "scanner",
                "Ljava/util/Scanner;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cwUtils.visitEnd();
        classes.put("ocean/compiler/stdlib/RuntimeUtils", cwUtils.toByteArray());
    }

    @Override
    public Object visitCompilationUnit(OceanParser.CompilationUnitContext ctx) {
        // package deklarasyonundan paket adını oku
        if (ctx.packageDeclaration() != null) {
            OceanParser.PackageDeclarationContext pkgCtx = ctx.packageDeclaration();
            StringBuilder pkgName = new StringBuilder();
            for (int i = 0; i < pkgCtx.anyId().size(); i++) {
                if (i > 0)
                    pkgName.append("/");
                pkgName.append(pkgCtx.anyId(i).getText());
            }
            this.currentFilePackage = pkgName.toString();
        }
        CompilationSession session = CompilationSession.getActiveSession();
        if (session != null) {
            session.setCurrentPackage(this.currentFilePackage.replace('/', '.'));
        }
        try {
            return visitChildren(ctx);
        } finally {
            if (session != null) {
                session.setCurrentPackage(null);
            }
        }
    }

    private ClassWriter createClassWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                if (type1.equals(type2))
                    return type1;
                if (type1.equals("java/lang/Object") || type2.equals("java/lang/Object"))
                    return "java/lang/Object";

                Set<String> supers1 = new HashSet<>();
                String t = type1;
                while (t != null && !t.equals("java/lang/Object")) {
                    supers1.add(t);
                    if (globalSuperClassRegistry.containsKey(t)) {
                        t = globalSuperClassRegistry.get(t);
                    } else {
                        try {
                            Class<?> c = Class.forName(t.replace('/', '.'), false, getClass().getClassLoader());
                            t = (c.getSuperclass() != null) ? c.getSuperclass().getName().replace('.', '/')
                                    : "java/lang/Object";
                        } catch (Throwable e) {
                            t = "java/lang/Object";
                        }
                    }
                }
                supers1.add("java/lang/Object");

                t = type2;
                while (t != null && !t.equals("java/lang/Object")) {
                    if (supers1.contains(t))
                        return t;
                    if (globalSuperClassRegistry.containsKey(t)) {
                        t = globalSuperClassRegistry.get(t);
                    } else {
                        try {
                            Class<?> c = Class.forName(t.replace('/', '.'), false, getClass().getClassLoader());
                            t = (c.getSuperclass() != null) ? c.getSuperclass().getName().replace('.', '/')
                                    : "java/lang/Object";
                        } catch (Throwable e) {
                            t = "java/lang/Object";
                        }
                    }
                }
                return "java/lang/Object";
            }
        };
    }

    private String determineCommonType(String t1, String t2) {
        if (t1 == null || t2 == null)
            return OceanTypeSystem.OBJECT_DESC;
        if (t1.equals("Ljava/math/BigDecimal;") || t2.equals("Ljava/math/BigDecimal;"))
            return "Ljava/math/BigDecimal;";
        if (t1.equals(OceanTypeSystem.STRING_DESC) || t2.equals(OceanTypeSystem.STRING_DESC))
            return OceanTypeSystem.STRING_DESC;
        if (t1.equals("D") || t2.equals("D"))
            return "D";
        if (t1.equals("J") || t2.equals("J"))
            return "J";
        if (t1.equals("F") || t2.equals("F"))
            return "F";
        if (t1.equals("I") || t2.equals("I"))
            return "I";
        return OceanTypeSystem.OBJECT_DESC;
    }

    private void emitCoerceTo(String current, String target) {
        if (current == null || target == null || current.equals(target))
            return;

        // 1. Primitive to Primitive
        if (isPrimitive(current) && isPrimitive(target)) {
            emitNumericCast(current, target);
            return;
        }

        // 2. Unboxing: Boxed/Object to Primitive
        if (isPrimitive(target) && (current.startsWith("Ljava/lang/") || TypeChecker.isObjectType(current))) {
            emitUnboxing(target);
            return;
        }

        // 3. Boxing: Primitive to Boxed/Object
        if (isPrimitive(current) && (target.startsWith("Ljava/lang/") || TypeChecker.isObjectType(target))) {
            emitBoxing(current);
            return;
        }

        // 4. BigDecimal handling
        if (target.equals("Ljava/math/BigDecimal;")) {
            ensureBigDecimal(current);
        } else if (OceanTypeSystem.STRING_DESC.equals(target)) {
            if (isPrimitive(current))
                emitBoxing(current);
            currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf",
                    "(Ljava/lang/Object;)Ljava/lang/String;", false);
        }
    }

    private void ensureBigDecimal(String currentType) {
        switch (currentType) {
            case "Ljava/math/BigDecimal;" -> {
                return;
            }
            case "I" -> {
                currentMethodVisitor.visitInsn(Opcodes.I2L);
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/math/BigDecimal", "valueOf",
                        "(J)Ljava/math/BigDecimal;", false);
            }
            case "J" -> currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/math/BigDecimal", "valueOf",
                    "(J)Ljava/math/BigDecimal;", false);
            case "D" -> currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/math/BigDecimal", "valueOf",
                    "(D)Ljava/math/BigDecimal;", false);
            case "F" -> {
                currentMethodVisitor.visitInsn(Opcodes.F2D);
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/math/BigDecimal", "valueOf",
                        "(D)Ljava/math/BigDecimal;", false);
            }
            case OceanTypeSystem.STRING_DESC -> {
                currentMethodVisitor.visitTypeInsn(Opcodes.NEW, "java/math/BigDecimal");
                currentMethodVisitor.visitInsn(Opcodes.DUP_X1);
                currentMethodVisitor.visitInsn(Opcodes.SWAP);
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/math/BigDecimal", "<init>",
                        "(Ljava/lang/String;)V", false);
            }
            default -> currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/math/BigDecimal");
        }
    }

    private List<OceanParser.AnnotationContext> getMemberAnnotations(ParserRuleContext ctx) {
        if (ctx == null)
            return null;
        if (ctx instanceof OceanParser.MemberDeclarationContext) {
            return ((OceanParser.MemberDeclarationContext) ctx).annotation();
        }
        return getMemberAnnotations(ctx.getParent());
    }

    private void emitAnnotations(List<OceanParser.AnnotationContext> annotations, Object visitor) {
        if (annotations == null)
            return;
        for (OceanParser.AnnotationContext ann : annotations) {
            String annName = ann.typeName().getText();
            String desc = getTypeDescriptor(annName);
            AnnotationVisitor av;
            switch (visitor) {
                case ClassWriter classWriter -> av = classWriter.visitAnnotation(desc, true);
                case MethodVisitor methodVisitor -> av = methodVisitor.visitAnnotation(desc, true);
                case FieldVisitor fieldVisitor -> av = fieldVisitor.visitAnnotation(desc, true);
                case null, default -> {
                    continue;
                }
            }

            if (ann.annotationElement() != null) {
                for (OceanParser.AnnotationElementContext elem : ann.annotationElement()) {
                    String name = "value";
                    OceanParser.ExpressionContext expr;
                    if (elem.anyId() != null) {
                        name = elem.anyId().getText();
                        expr = elem.expression();
                    } else {
                        expr = elem.expression();
                    }
                    Object value = ConstantFolder.fold(expr);
                    if (value != null) {
                        av.visit(name, value);
                    } else {
                        String type = inferType(expr);
                        if (isEnumType(type) && expr instanceof OceanParser.MemberCallExprContext) {
                            String enumValue = ((OceanParser.MemberCallExprContext) expr).anyId().getText();
                            av.visitEnum(name, type, enumValue);
                        }
                    }
                }
            }
            av.visitEnd();
        }
    }

    public Object visitClassDeclaration(OceanParser.ClassDeclarationContext ctx) {
        inferredTypeCache.clear();
        String oldClassName = currentClassName;
        String oldSuperName = currentSuperName;
        Map<String, String> oldFields = currentClassFields;
        Map<String, Boolean> oldFieldStatic = isFieldStatic;
        List<OceanParser.FieldDeclarationContext> oldFieldInitializers = new ArrayList<>(fieldInitializers);
        List<LambdaInfo> oldSyntheticLambdas = syntheticLambdas;
        boolean oldNeedsComputeMaxsFallback = needsComputeMaxsFallback;
        ClassWriter oldCw = cw;
        currentClassName = ctx.anyId().getText();
        cw = createClassWriter();
        resetClassLocalState();
        boolean oldHasConstructor = hasConstructor;
        hasConstructor = false;
        String fullPathKey = getCurrentClassPath();
        globalMethodRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>());

        // Generic type parameters
        List<CompilerRegistry.TypeParameterInfo> typeParams = new ArrayList<>();
        if (ctx.typeParameter() != null && !ctx.typeParameter().isEmpty()) {
            for (OceanParser.TypeParameterContext tpCtx : ctx.typeParameter()) {
                typeParams.add((CompilerRegistry.TypeParameterInfo) visit(tpCtx));
            }
        }
        globalTypeParameterRegistry.put(fullPathKey, typeParams);

        currentSuperName = "java/lang/Object";
        if (ctx.type() != null) {
            currentSuperName = resolveClassName(ctx.type().getText(), ctx.type());
            checkTypeArgs(ctx.type(), robustResolveInternalOwner(currentSuperName));
        }
        String[] interfaces = null;
        if (ctx.typeList() != null) {
            List<OceanParser.TypeContext> typeCtxs = ctx.typeList().type();
            List<String> interfaceList = new ArrayList<>();
            for (OceanParser.TypeContext typeCtx : typeCtxs) {
                String resolvedName = resolveClassName(typeCtx.getText(), typeCtx);
                checkTypeArgs(typeCtx, robustResolveInternalOwner(resolvedName));
                // If the name refers to an Ocean abstract class (not a real interface),
                // treat it as extends (superclass) rather than implements.
                if (isOceanAbstractClass(resolvedName)) {
                    // Only override superClass if not already set by explicit 'extends'
                    if (ctx.type() == null) {
                        currentSuperName = resolvedName;
                    }
                } else {
                    interfaceList.add(resolvedName);
                }
            }
            if (!interfaceList.isEmpty()) {
                interfaces = interfaceList.toArray(new String[0]);
            }
        }
        // Track which classes are declared abstract so the above check works for
        // pre-scan
        boolean isDeclaredAbstract = false;
        if (ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                if (mod.ABSTRACT() != null)
                    isDeclaredAbstract = true;
            }
        }
        // Store abstract flag immediately (before cw.visit) so nested/later classes can
        // use it
        if (isDeclaredAbstract) {
            globalAbstractClassSet.add(fullPathKey);
        }
        int classAccess = Opcodes.ACC_SUPER;
        boolean hasExplicitClassAccess = false;
        if (ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String modText = mod.getText();
                if (modText.equals("public")) {
                    classAccess |= Opcodes.ACC_PUBLIC;
                    hasExplicitClassAccess = true;
                } else if (modText.equals("private")) {
                    classAccess |= Opcodes.ACC_PRIVATE;
                    hasExplicitClassAccess = true;
                } else if (modText.equals("protected")) {
                    classAccess |= Opcodes.ACC_PROTECTED;
                    hasExplicitClassAccess = true;
                } else if (mod.ABSTRACT() != null) {
                    classAccess |= Opcodes.ACC_ABSTRACT;
                } else if (modText.equals("final")) {
                    classAccess |= Opcodes.ACC_FINAL;
                }
            }
        }
        CompilerRegistry.globalClassAccess.put(fullPathKey, classAccess);
        cw.visit(Opcodes.V11, classAccess, fullPathKey, null, stripGenerics(currentSuperName),
                stripGenerics(interfaces));
        emitAnnotations(ctx.annotation(), cw);
        globalSuperClassRegistry.put(fullPathKey, currentSuperName);
        globalInterfaceRegistry.put(fullPathKey, interfaces != null ? interfaces : new String[0]);
        // Every generated class must appear in the method registry so field access is
        // not misclassified as
        // "external" (which would fall back to java/lang/Object descriptors and break
        // primitives).
        globalMethodRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>());
        if (isPreScan) {
            super.visitClassDeclaration(ctx);
            if (!hasConstructor && !globalIsInterfaceSet.contains(fullPathKey)) {
                globalMethodRegistry.get(fullPathKey).put("<init>", "()V");
            }
            hasConstructor = oldHasConstructor;
            return null;
        }

        // hasConstructor already reset to false above for the real pass too
        // hasConstructor = false; // removed as it is now redundant with
        // oldHasConstructor pattern

        // Inherit fields from superclasses for correct type inference and owner
        // resolution
        inheritFields(fullPathKey, currentClassFields);

        // Setup <clinit> for class-level statements
        MethodVisitor clinitMv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinitMv.visitCode();
        MethodVisitor oldMv = currentMethodVisitor;
        currentMethodVisitor = clinitMv;

        super.visitClassDeclaration(ctx);

        // Emit static field initializers into <clinit>
        emitStaticFieldInitializers(clinitMv);

        // Finalize <clinit>
        clinitMv.visitInsn(Opcodes.RETURN);
        clinitMv.visitMaxs(0, 0);
        clinitMv.visitEnd();
        currentMethodVisitor = oldMv;

        if (!hasConstructor) {
            int ctorAccess = classAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
            MethodVisitor mv = cw.visitMethod(ctorAccess, "<init>", "()V", null, null);
            mv.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, currentSuperName, "<init>", "()V", false);
            emitFieldInitializers(mv);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        emitSyntheticLambdas();
        cw.visitEnd();
        byte[] bytecode = null;
        Throwable capturedError = null;
        if (!needsComputeMaxsFallback) {
            try {
                bytecode = cw.toByteArray();
            } catch (Throwable frameError) {
                capturedError = frameError;
                reportError(ctx, "CRITICAL: Bytecode verification (COMPUTE_FRAMES) failed for class " + currentClassName
                        + ": " + frameError.getMessage());
                if (isCompileVerbose()) {
                    frameError.printStackTrace(System.out);
                }
            }
        }
        if (bytecode == null) {
            throw new RuntimeException("Bytecode generation failed (COMPUTE_FRAMES failure)", capturedError);
        }
        classes.put(fullPathKey, bytecode);
        currentClassName = oldClassName;
        currentSuperName = oldSuperName;
        currentClassFields = oldFields;
        isFieldStatic = oldFieldStatic;
        fieldInitializers.clear();
        fieldInitializers.addAll(oldFieldInitializers);
        syntheticLambdas = oldSyntheticLambdas;
        needsComputeMaxsFallback = oldNeedsComputeMaxsFallback;
        cw = oldCw;
        hasConstructor = oldHasConstructor;
        return null;
    }

    @Override
    public Object visitInterfaceDeclaration(OceanParser.InterfaceDeclarationContext ctx) {
        inferredTypeCache.clear();
        String oldClassName = currentClassName;
        String oldSuperName = currentSuperName;
        Map<String, String> oldFields = currentClassFields;
        Map<String, Boolean> oldFieldStatic = isFieldStatic;
        List<OceanParser.FieldDeclarationContext> oldFieldInitializers = new ArrayList<>(fieldInitializers);
        List<LambdaInfo> oldSyntheticLambdas = syntheticLambdas;
        boolean oldNeedsComputeMaxsFallback = needsComputeMaxsFallback;
        ClassWriter oldCw = cw;

        currentClassName = ctx.anyId().getText();
        cw = createClassWriter();
        resetClassLocalState();
        currentSuperName = "java/lang/Object";

        String fullPathKey = getCurrentClassPath();
        List<CompilerRegistry.TypeParameterInfo> typeParams = new ArrayList<>();
        if (ctx.typeParameter() != null && !ctx.typeParameter().isEmpty()) {
            for (OceanParser.TypeParameterContext tpCtx : ctx.typeParameter()) {
                typeParams.add((CompilerRegistry.TypeParameterInfo) visit(tpCtx));
            }
        }
        globalTypeParameterRegistry.put(fullPathKey, typeParams);
        globalIsInterfaceSet.add(fullPathKey);

        String[] interfaces = null;
        if (ctx.typeList() != null) {
            List<OceanParser.TypeContext> typeCtxs = ctx.typeList().type();
            List<String> interfaceList = new ArrayList<>();
            for (OceanParser.TypeContext typeCtx : typeCtxs) {
                interfaceList.add(resolveClassName(typeCtx.getText(), typeCtx));
            }
            if (!interfaceList.isEmpty()) {
                interfaces = interfaceList.toArray(new String[0]);
            }
        }

        int classAccess = Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        boolean hasExplicitInterfaceAccess = false;
        if (ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String modText = mod.getText();
                switch (modText) {
                    case "public" -> {
                        classAccess |= Opcodes.ACC_PUBLIC;
                        hasExplicitInterfaceAccess = true;
                    }
                    case "private" -> {
                        classAccess |= Opcodes.ACC_PRIVATE;
                        hasExplicitInterfaceAccess = true;
                    }
                    case "protected" -> {
                        classAccess |= Opcodes.ACC_PROTECTED;
                        hasExplicitInterfaceAccess = true;
                    }
                }
            }
        }
        CompilerRegistry.globalClassAccess.put(fullPathKey, classAccess);

        cw.visit(Opcodes.V11, classAccess, fullPathKey, null, stripGenerics(currentSuperName),
                stripGenerics(interfaces));
        emitAnnotations(ctx.annotation(), cw);
        globalSuperClassRegistry.put(fullPathKey, currentSuperName);
        globalInterfaceRegistry.put(fullPathKey, interfaces != null ? interfaces : new String[0]);
        globalMethodRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>());

        if (isPreScan) {
            super.visitInterfaceDeclaration(ctx);
            return null;
        }

        super.visitInterfaceDeclaration(ctx);

        cw.visitEnd();
        byte[] bytecode = cw.toByteArray();
        classes.put(fullPathKey, bytecode);

        currentClassName = oldClassName;
        currentSuperName = oldSuperName;
        currentClassFields = oldFields;
        isFieldStatic = oldFieldStatic;
        fieldInitializers.clear();
        fieldInitializers.addAll(oldFieldInitializers);
        syntheticLambdas = oldSyntheticLambdas;
        needsComputeMaxsFallback = oldNeedsComputeMaxsFallback;
        cw = oldCw;
        return null;
    }

    @Override
    public Object visitEnumDeclaration(OceanParser.EnumDeclarationContext ctx) {
        inferredTypeCache.clear();
        String oldClassName = currentClassName;
        String oldSuperName = currentSuperName;
        Map<String, String> oldFields = currentClassFields;
        Map<String, Boolean> oldFieldStatic = isFieldStatic;
        List<OceanParser.FieldDeclarationContext> oldFieldInitializers = new ArrayList<>(fieldInitializers);
        List<LambdaInfo> oldSyntheticLambdas = syntheticLambdas;
        boolean oldNeedsComputeMaxsFallback = needsComputeMaxsFallback;
        ClassWriter oldCw = cw;

        currentClassName = ctx.anyId().getText();
        cw = createClassWriter();
        resetClassLocalState();
        currentSuperName = "java/lang/Enum";

        int classAccess = Opcodes.ACC_ENUM | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        if (ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String modText = mod.getText();
                switch (modText) {
                    case "public" -> classAccess |= Opcodes.ACC_PUBLIC;
                    case "private" -> classAccess |= Opcodes.ACC_PRIVATE;
                    case "protected" -> classAccess |= Opcodes.ACC_PROTECTED;
                }
            }
        } else {
            // Default enum access (could be public or package-private, let's stick to
            // public for now if no modifier is present to match Java's common use, or 0 for
            // consistency)
            // classAccess |= Opcodes.ACC_PUBLIC;
        }
        String fullPathKey = getCurrentClassPath();
        CompilerRegistry.globalClassAccess.put(fullPathKey, classAccess);

        cw.visit(Opcodes.V11, classAccess, fullPathKey, "Ljava/lang/Enum<L" + fullPathKey + ";>;", currentSuperName,
                null);
        emitAnnotations(ctx.annotation(), cw);
        globalSuperClassRegistry.put(fullPathKey, currentSuperName);
        globalInterfaceRegistry.put(fullPathKey, new String[0]);
        Map<String, String> enumMethods = globalMethodRegistry.computeIfAbsent(fullPathKey,
                k -> new ConcurrentHashMap<>());
        enumMethods.put("values", "()[L" + fullPathKey + ";");
        enumMethods.put("valueOf", "(Ljava/lang/String;)L" + fullPathKey + ";");
        enumMethods.put("name", "()Ljava/lang/String;");
        enumMethods.put("ordinal", "()I");
        enumMethods.put("toString", "()Ljava/lang/String;");
        globalMethodStaticity.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                .put("values", true);
        globalMethodStaticity.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                .put("valueOf", true);
        globalMethodStaticity.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                .put("name", false);
        globalMethodStaticity.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                .put("ordinal", false);
        globalMethodStaticity.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                .put("toString", false);

        List<String> enumConstants = new ArrayList<>();
        Map<String, String> enumFields = globalFieldRegistry.computeIfAbsent(fullPathKey,
                k -> new ConcurrentHashMap<>());
        Map<String, Boolean> enumFieldStaticity = globalFieldStaticity.computeIfAbsent(fullPathKey,
                k -> new ConcurrentHashMap<>());

        if (ctx.enumConstants() != null) {
            for (OceanParser.EnumConstantContext ec : ctx.enumConstants().enumConstant()) {
                String enumName = ec.anyId().getText();
                enumConstants.add(enumName);
                enumFields.put(enumName, OceanTypeSystem.wrapObjectType(fullPathKey));
                enumFieldStaticity.put(enumName, true);
            }
        }

        if (isPreScan) {
            super.visitEnumDeclaration(ctx);
            return null;
        }

        if (ctx.enumConstants() != null) {
            for (String enumName : enumConstants) {
                cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM, enumName,
                        OceanTypeSystem.wrapObjectType(fullPathKey), null, null).visitEnd();
            }
        }

        // $VALUES field
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "$VALUES",
                "[L" + fullPathKey + ";", null, null).visitEnd();

        // <init>(String, int)
        MethodVisitor ctorMv = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(Ljava/lang/String;I)V", null, null);
        ctorMv.visitCode();
        ctorMv.visitVarInsn(Opcodes.ALOAD, 0);
        ctorMv.visitVarInsn(Opcodes.ALOAD, 1);
        ctorMv.visitVarInsn(Opcodes.ILOAD, 2);
        ctorMv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false);
        ctorMv.visitInsn(Opcodes.RETURN);
        ctorMv.visitMaxs(0, 0);
        ctorMv.visitEnd();

        // values()
        MethodVisitor valuesMv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "values",
                "()[L" + fullPathKey + ";", null, null);
        valuesMv.visitCode();
        valuesMv.visitFieldInsn(Opcodes.GETSTATIC, fullPathKey, "$VALUES", "[L" + fullPathKey + ";");
        valuesMv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[L" + fullPathKey + ";", "clone", "()Ljava/lang/Object;",
                false);
        valuesMv.visitTypeInsn(Opcodes.CHECKCAST, "[L" + fullPathKey + ";");
        valuesMv.visitInsn(Opcodes.ARETURN);
        valuesMv.visitMaxs(0, 0);
        valuesMv.visitEnd();

        // valueOf(String)
        MethodVisitor valueOfMv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "valueOf",
                "(Ljava/lang/String;)L" + fullPathKey + ";", null, null);
        valueOfMv.visitCode();
        valueOfMv.visitLdcInsn(Type.getType(OceanTypeSystem.wrapObjectType(fullPathKey)));
        valueOfMv.visitVarInsn(Opcodes.ALOAD, 0);
        valueOfMv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
        valueOfMv.visitTypeInsn(Opcodes.CHECKCAST, fullPathKey);
        valueOfMv.visitInsn(Opcodes.ARETURN);
        valueOfMv.visitMaxs(0, 0);
        valueOfMv.visitEnd();

        // $values() synthetic method
        MethodVisitor synthMv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "$values", "()[L" + fullPathKey + ";", null, null);
        synthMv.visitCode();
        emitIntConstant(synthMv, enumConstants.size());
        synthMv.visitTypeInsn(Opcodes.ANEWARRAY, fullPathKey);
        for (int i = 0; i < enumConstants.size(); i++) {
            synthMv.visitInsn(Opcodes.DUP);
            emitIntConstant(synthMv, i);
            synthMv.visitFieldInsn(Opcodes.GETSTATIC, fullPathKey, enumConstants.get(i), OceanTypeSystem.wrapObjectType(fullPathKey));
            synthMv.visitInsn(Opcodes.AASTORE);
        }
        synthMv.visitInsn(Opcodes.ARETURN);
        synthMv.visitMaxs(0, 0);
        synthMv.visitEnd();

        // <clinit>
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        for (int i = 0; i < enumConstants.size(); i++) {
            clinit.visitTypeInsn(Opcodes.NEW, fullPathKey);
            clinit.visitInsn(Opcodes.DUP);
            clinit.visitLdcInsn(enumConstants.get(i));
            emitIntConstant(clinit, i);
            clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, fullPathKey, "<init>", "(Ljava/lang/String;I)V", false);
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, fullPathKey, enumConstants.get(i), OceanTypeSystem.wrapObjectType(fullPathKey));
        }
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, fullPathKey, "$values", "()[L" + fullPathKey + ";", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, fullPathKey, "$VALUES", "[L" + fullPathKey + ";");

        currentMethodVisitor = clinit;
        super.visitEnumDeclaration(ctx);
        emitStaticFieldInitializers(clinit);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();

        cw.visitEnd();
        byte[] bytecode = cw.toByteArray();
        classes.put(fullPathKey, bytecode);

        currentClassName = oldClassName;
        currentSuperName = oldSuperName;
        currentClassFields = oldFields;
        isFieldStatic = oldFieldStatic;
        fieldInitializers.clear();
        fieldInitializers.addAll(oldFieldInitializers);
        syntheticLambdas = oldSyntheticLambdas;
        needsComputeMaxsFallback = oldNeedsComputeMaxsFallback;
        cw = oldCw;
        return null;
    }

    private SymbolTable symbolTable;

    @Override
    public Object visitFieldDeclaration(OceanParser.FieldDeclarationContext ctx) {
        String typeText = ctx.type() != null ? ctx.type().getText() : null;
        int access = 0; // Default to package-private!
        RuleContext parentCtx = ctx;
        while (parentCtx != null) {
            if (parentCtx instanceof OceanParser.InterfaceDeclarationContext) {
                access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
                break;
            }
            parentCtx = parentCtx.getParent();
        }

        if (ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String mt = mod.getText();
                switch (mt) {
                    case "private" -> access = Opcodes.ACC_PRIVATE;
                    case "protected" -> access = Opcodes.ACC_PROTECTED;
                    case "public" -> access = Opcodes.ACC_PUBLIC;
                    case "static" -> access |= Opcodes.ACC_STATIC;
                    case "final" -> access |= Opcodes.ACC_FINAL;
                    case "sync" -> access |= Opcodes.ACC_VOLATILE;
                }
            }
        }
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        boolean isFinal = (access & Opcodes.ACC_FINAL) != 0;
        String classPath = getCurrentClassPath();

        for (OceanParser.VariableDeclaratorContext d : ctx.variableDeclarator()) {
            String fieldName = d.anyId().getText();
            String typeDesc = OceanTypeSystem.OBJECT_DESC;
            if (typeText != null) {
                typeDesc = getTypeDescriptor(typeText);
            } else if (d.expression() != null) {
                typeDesc = inferType(d.expression());
            }

            FieldVisitor fv = cw.visitField(access, fieldName, typeDesc, null, null);
            emitAnnotations(getMemberAnnotations(ctx), fv);
            fv.visitEnd();
            currentClassFields.put(fieldName, typeDesc);
            isFieldStatic.put(fieldName, isStatic);

            globalFieldRegistry.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                    .put(fieldName, typeDesc);
            globalFieldStaticity.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                    .put(fieldName, isStatic);
            globalFieldAccess.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>()).put(fieldName,
                    access);
            globalFieldMutability.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                    .put(fieldName, isFinal);
        }
        boolean hasExpr = false;
        for (OceanParser.VariableDeclaratorContext d : ctx.variableDeclarator()) {
            if (d.expression() != null) { hasExpr = true; break; }
        }
        if (hasExpr)
            fieldInitializers.add(ctx);
        return null;
    }

    @Override
    public Object visitConstructorDeclaration(OceanParser.ConstructorDeclarationContext ctx) {
        String oldMethodName = currentMethodName;
        MethodVisitor oldMv = currentMethodVisitor;
        SymbolTable oldSymbolTable = symbolTable;
        currentMethodName = "<init>";
        String name = ctx.anyId().getText();
        if (!name.equals(currentClassName) && !(currentClassName != null && currentClassName.endsWith("$" + name))) {
            currentMethodName = oldMethodName;
            Object result = handlePseudoMethodFromConstructor(ctx);
            currentMethodVisitor = oldMv;
            symbolTable = oldSymbolTable;
            return result;
        }
        hasConstructor = true;
        symbolTable = new SymbolTable(false, importedClasses, getAllActiveTypeParameters());
        StringBuilder descriptor = new StringBuilder("(");
        if (ctx.parameterList() != null) {
            for (OceanParser.ParameterContext p : ctx.parameterList().parameter()) {
                String pType = p.type().getText();
                String pName = p.anyId().getText();
                descriptor.append(getTypeDescriptor(pType));
                symbolTable.declareVariable(pName, pType);
            }
        }
        descriptor.append(")V");
        String classPath = getCurrentClassPath();
        globalMethodRegistry.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                .put("<init>", descriptor.toString());
        // Register constructor overload
        globalOverloadRegistry.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                .computeIfAbsent("<init>", k -> new CopyOnWriteArrayList<>());
        List<String> ctorOverloads = globalOverloadRegistry.get(classPath).get("<init>");
        if (!ctorOverloads.contains(descriptor.toString())) {
            ctorOverloads.add(descriptor.toString());
        }
        if (isPreScan) {
            currentMethodVisitor = oldMv;
            symbolTable = oldSymbolTable;
            currentMethodName = oldMethodName;
            return null;
        }
        int access = 0; // Default to package-private!
        if (ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String modText = mod.getText();
                access = switch (modText) {
                    case "public" -> Opcodes.ACC_PUBLIC;
                    case "private" -> Opcodes.ACC_PRIVATE;
                    case "protected" -> Opcodes.ACC_PROTECTED;
                    default -> access;
                };
            }
        }

        MethodVisitor mv = cw.visitMethod(access, "<init>", descriptor.toString(), null, null);
        mv.visitCode();
        this.currentMethodVisitor = mv;
        String previousReturn = currentMethodReturnDescriptor;
        currentMethodReturnDescriptor = "V";
        if (ctx.block() != null && !ctx.block().statement().isEmpty()) {
            OceanParser.StatementContext firstStmt = ctx.block().statement(0);
            if (firstStmt instanceof OceanParser.SuperStmtContext) {
                visit(firstStmt);
                emitFieldInitializers(mv);
                if (symbolTable != null)
                    symbolTable.enterScope();
                for (int i = 1; i < ctx.block().statement().size(); i++) {
                    visit(ctx.block().statement(i));
                }
                if (symbolTable != null)
                    symbolTable.exitScope();
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, currentSuperName, "<init>", "()V", false);
                emitFieldInitializers(mv);
                visit(ctx.block());
            }
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, currentSuperName, "<init>", "()V", false);
            emitFieldInitializers(mv);
        }
        currentMethodReturnDescriptor = previousReturn;
        mv.visitInsn(Opcodes.RETURN);
        try {
            mv.visitMaxs(0, 0);
        } catch (Exception e) {
            needsComputeMaxsFallback = true;
        }
        mv.visitEnd();
        currentMethodVisitor = oldMv;
        symbolTable = oldSymbolTable;
        currentMethodName = oldMethodName;
        return null;
    }

    private Object handlePseudoMethodFromConstructor(OceanParser.ConstructorDeclarationContext ctx) {
        MethodVisitor oldMv = currentMethodVisitor;
        SymbolTable oldSymbolTable = symbolTable;
        String methodName = ctx.anyId().getText();
        boolean isMain = methodName.equals("main");
        StringBuilder descriptor = new StringBuilder("(");
        symbolTable = new SymbolTable(isMain, importedClasses, getAllActiveTypeParameters());
        if (isMain) {
            descriptor.append("[Ljava/lang/String;");
            symbolTable.declareVariable("args", "[Ljava/lang/String;");
        } else if (ctx.parameterList() != null) {
            for (OceanParser.ParameterContext p : ctx.parameterList().parameter()) {
                String pType = p.type().getText();
                String pName = p.anyId().getText();
                descriptor.append(getTypeDescriptor(pType));
                symbolTable.declareVariable(pName, pType);
            }
        }
        descriptor.append(")");
        String returnTypeDesc = "V";
        descriptor.append(returnTypeDesc);
        String classPath = getCurrentClassPath();

        globalMethodRegistry.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                .put(methodName, descriptor.toString());
        globalMethodStaticity.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                .put(methodName, isMain);
        if (isPreScan) {
            currentMethodVisitor = oldMv;
            symbolTable = oldSymbolTable;
            return null;
        }
        int access = 0; // Default to package-private!
        if (isMain)
            access |= Opcodes.ACC_STATIC;
        if (isMain)
            access |= Opcodes.ACC_PUBLIC;
        if (ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String modText = mod.getText();
                switch (modText) {
                    case "public" -> access |= Opcodes.ACC_PUBLIC;
                    case "private" -> access |= Opcodes.ACC_PRIVATE;
                    case "protected" -> access |= Opcodes.ACC_PROTECTED;
                }
            }
        }
        MethodVisitor mv = cw.visitMethod(access, methodName, descriptor.toString(), null, null);
        mv.visitCode();
        this.currentMethodVisitor = mv;
        String previousReturn = currentMethodReturnDescriptor;
        currentMethodReturnDescriptor = returnTypeDesc;
        if (ctx.block() != null)
            visit(ctx.block());
        currentMethodReturnDescriptor = previousReturn;
        emitDefaultReturn(returnTypeDesc);
        try {
            mv.visitMaxs(0, 0);
        } catch (Exception e) {
            needsComputeMaxsFallback = true;
        }
        mv.visitEnd();
        currentMethodVisitor = oldMv;
        symbolTable = oldSymbolTable;
        return null;
    }

    private void emitStore(int index, String typeDesc) {
        if (isPreScan || currentMethodVisitor == null)
            return;
        switch (typeDesc) {
            case "I", "Z", "B", "C", "S" -> currentMethodVisitor.visitVarInsn(Opcodes.ISTORE, index);
            case "D" -> currentMethodVisitor.visitVarInsn(Opcodes.DSTORE, index);
            case "J" -> currentMethodVisitor.visitVarInsn(Opcodes.LSTORE, index);
            case "F" -> currentMethodVisitor.visitVarInsn(Opcodes.FSTORE, index);
            default -> currentMethodVisitor.visitVarInsn(Opcodes.ASTORE, index);
        }
    }

    private void emitLoad(int index, String typeDesc) {
        if (isPreScan || currentMethodVisitor == null)
            return;
        switch (typeDesc) {
            case "I", "Z", "B", "C", "S" -> currentMethodVisitor.visitVarInsn(Opcodes.ILOAD, index);
            case "D" -> currentMethodVisitor.visitVarInsn(Opcodes.DLOAD, index);
            case "J" -> currentMethodVisitor.visitVarInsn(Opcodes.LLOAD, index);
            case "F" -> currentMethodVisitor.visitVarInsn(Opcodes.FLOAD, index);
            default -> currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, index);
        }
    }

    private void emitDefaultReturn(String returnTypeDesc) {
        if (isPreScan || currentMethodVisitor == null)
            return;
        if (returnTypeDesc.equals("V"))
            currentMethodVisitor.visitInsn(Opcodes.RETURN);
        else if (returnTypeDesc.equals("D"))
            currentMethodVisitor.visitInsn(Opcodes.DRETURN);
        else if (returnTypeDesc.equals("J"))
            currentMethodVisitor.visitInsn(Opcodes.LRETURN);
        else if (returnTypeDesc.equals("F"))
            currentMethodVisitor.visitInsn(Opcodes.FRETURN);
        else if (returnTypeDesc.startsWith("L") || returnTypeDesc.startsWith("["))
            currentMethodVisitor.visitInsn(Opcodes.ARETURN);
        else
            currentMethodVisitor.visitInsn(Opcodes.IRETURN);
    }

    private boolean blockEndsWithReturn(OceanParser.BlockContext block) {
        if (block == null || block.statement().isEmpty())
            return false;
        OceanParser.StatementContext last = block.statement(block.statement().size() - 1);
        return stmtEndsWithReturn(last);
    }

    private boolean stmtEndsWithReturn(OceanParser.StatementContext stmt) {
        if (stmt instanceof OceanParser.ReturnStmtContext)
            return true;
        if (stmt instanceof OceanParser.BlockStmtContext) {
            return blockEndsWithReturn(((OceanParser.BlockStmtContext) stmt).block());
        }
        if (stmt instanceof OceanParser.IfStmtContext) {
            OceanParser.IfStatementContext ifStmt = ((OceanParser.IfStmtContext) stmt).ifStatement();
            if (ifStmt.statement().size() >= 2) {
                return stmtEndsWithReturn(ifStmt.statement(0)) && stmtEndsWithReturn(ifStmt.statement(1));
            }
        }
        if (stmt instanceof OceanParser.TryStmtContext) {
            OceanParser.TryStatementContext tryCtx = ((OceanParser.TryStmtContext) stmt).tryStatement();
            boolean tryReturns = blockEndsWithReturn(tryCtx.block(0));
            boolean allCatchesReturn = !tryCtx.catchClause().isEmpty();
            for (OceanParser.CatchClauseContext catchCtx : tryCtx.catchClause()) {
                if (!blockEndsWithReturn(catchCtx.block())) {
                    allCatchesReturn = false;
                    break;
                }
            }
            return tryReturns && allCatchesReturn;
        }
        if (stmt instanceof OceanParser.SwitchStmtContext) {
            OceanParser.SwitchStatementContext swCtx = ((OceanParser.SwitchStmtContext) stmt).switchStatement();
            if (swCtx.defaultCase() == null)
                return false;
            List<OceanParser.StatementContext> defStmts = swCtx.defaultCase().statement();
            if (defStmts.isEmpty() || !stmtEndsWithReturn(defStmts.getLast()))
                return false;

            for (OceanParser.SwitchCaseContext caseCtx : swCtx.switchCase()) {
                List<OceanParser.StatementContext> caseStmts = caseCtx.statement();
                if (caseStmts.isEmpty() || !stmtEndsWithReturn(caseStmts.getLast()))
                    return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Check if a block contains ANY return statement (for lambda body analysis).
     */
    private boolean blockHasReturn(OceanParser.BlockContext block) {
        if (block == null)
            return false;
        for (OceanParser.StatementContext stmt : block.statement()) {
            if (stmt instanceof OceanParser.ReturnStmtContext)
                return true;
            if (stmt instanceof OceanParser.BlockStmtContext) {
                if (blockHasReturn(((OceanParser.BlockStmtContext) stmt).block()))
                    return true;
            }
            if (stmt instanceof OceanParser.IfStmtContext) {
                OceanParser.IfStatementContext ifCtx = ((OceanParser.IfStmtContext) stmt).ifStatement();
                for (OceanParser.StatementContext s : ifCtx.statement()) {
                    if (s instanceof OceanParser.ReturnStmtContext)
                        return true;
                    if (s instanceof OceanParser.BlockStmtContext) {
                        if (blockHasReturn(((OceanParser.BlockStmtContext) s).block()))
                            return true;
                    }
                }
            }
        }
        return false;
    }

    private void emitDefaultReturnWithPush(String returnTypeDesc) {
        if (returnTypeDesc.equals("V")) {
            currentMethodVisitor.visitInsn(Opcodes.RETURN);
        } else {
            emitPushDefault(returnTypeDesc);
            emitDefaultReturn(returnTypeDesc);
        }
    }

    private void emitPushDefault(String typeDesc) {
        if (typeDesc.equals("D"))
            currentMethodVisitor.visitLdcInsn(0.0d);
        else if (typeDesc.equals("J"))
            currentMethodVisitor.visitLdcInsn(0L);
        else if (typeDesc.equals("F"))
            currentMethodVisitor.visitLdcInsn(0.0f);
        else if (typeDesc.startsWith("L") || typeDesc.startsWith("["))
            currentMethodVisitor.visitInsn(Opcodes.ACONST_NULL);
        else
            currentMethodVisitor.visitInsn(Opcodes.ICONST_0);
    }

    /**
     * Narrow stack top when expression type does not match declared method return
     * (Java allows int <- double etc.).
     */
    private void emitNarrowingIfNeeded(String exprType, String methodReturnType) {
        emitNumericCast(exprType, methodReturnType);
    }

    private void emitIntConstant(int value) {
        emitIntConstant(currentMethodVisitor, value);
    }

    private void emitIntConstant(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    private void emitFieldInitializers(MethodVisitor mv) {
        MethodVisitor oldMv = this.currentMethodVisitor;
        this.currentMethodVisitor = mv;
        for (OceanParser.FieldDeclarationContext fieldCtx : fieldInitializers) {
            for (OceanParser.VariableDeclaratorContext d : fieldCtx.variableDeclarator()) {
                String fName = d.anyId().getText();
                boolean isStatic = isFieldStatic.getOrDefault(fName, false);
                if (!isStatic && d.expression() != null) {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    visit(d.expression());
                    String fDesc = currentClassFields.get(fName);
                    String exprType = inferType(d.expression());
                    emitAssignConversion(exprType, fDesc);
                    mv.visitFieldInsn(Opcodes.PUTFIELD, getCurrentClassPath(), fName, fDesc);
                }
            }
        }
        this.currentMethodVisitor = oldMv;
    }

    private void emitStaticFieldInitializers(MethodVisitor mv) {
        MethodVisitor oldMv = this.currentMethodVisitor;
        this.currentMethodVisitor = mv;
        for (OceanParser.FieldDeclarationContext fieldCtx : fieldInitializers) {
            for (OceanParser.VariableDeclaratorContext d : fieldCtx.variableDeclarator()) {
                String fName = d.anyId().getText();
                boolean isStatic = isFieldStatic.getOrDefault(fName, false);
                if (isStatic && d.expression() != null) {
                    visit(d.expression());
                    String fDesc = currentClassFields.get(fName);
                    String exprType = inferType(d.expression());
                    emitAssignConversion(exprType, fDesc);
                    mv.visitFieldInsn(Opcodes.PUTSTATIC, getCurrentClassPath(), fName, fDesc);
                }
            }
        }
        this.currentMethodVisitor = oldMv;
    }




    @Override
    public Object visitTypeParameter(OceanParser.TypeParameterContext ctx) {
        // Ocean declaration-site variance and bounds: +T, -T, T, T <: Number, -T >: Integer
        String name = ctx.anyId().getText();
        CompilerRegistry.Variance variance = CompilerRegistry.Variance.INVARIANT;
        if (ctx.PLUS() != null) {
            variance = CompilerRegistry.Variance.COVARIANT;
        } else if (ctx.MINUS() != null) {
            variance = CompilerRegistry.Variance.CONTRAVARIANT;
        }

        String upperBound = null;
        String lowerBound = null;
        List<OceanParser.TypeContext> types = ctx.type();
        if (types != null && !types.isEmpty()) {
            // <: üst bound (first type if UPPER_BOUND present)
            if (ctx.UPPER_BOUND() != null) {
                upperBound = SymbolTable.getDescriptor(types.getFirst().getText(), importedClasses);
            }
            // >: alt bound (second type if both, or first if only LOWER_BOUND)
            if (ctx.LOWER_BOUND() != null) {
                int idx = ctx.UPPER_BOUND() != null ? 1 : 0;
                if (types.size() > idx) {
                    lowerBound = SymbolTable.getDescriptor(types.get(idx).getText(), importedClasses);
                }
            }
        }

        return new CompilerRegistry.TypeParameterInfo(name, variance, upperBound, lowerBound, false);
    }

    private void checkTypeArgs(OceanParser.TypeContext typeCtx, String ownerPath) {
        if (typeCtx == null || ownerPath == null || typeCtx.type().isEmpty())
            return;

        List<CompilerRegistry.TypeParameterInfo> tps = globalTypeParameterRegistry.get(ownerPath);
        if (tps == null || tps.isEmpty())
            return;

        List<OceanParser.TypeContext> args = typeCtx.type();
        for (int i = 0; i < Math.min(tps.size(), args.size()); i++) {
            checkTypeBounds(args.get(i).getText(), tps.get(i), args.get(i));
        }
    }

    private void checkTypeBounds(String typeArg, CompilerRegistry.TypeParameterInfo tp, ParserRuleContext ctx) {
        if (typeArg == null || tp == null)
            return;

        String argDesc = getTypeDescriptor(typeArg);

        if (tp.upperBound != null) {
            if (!isAssignable(argDesc, tp.upperBound)) {
                reportError(ctx, "Type argument '" + typeArg + "' does not satisfy upper bound '" + tp.upperBound
                        + "' of type parameter '" + tp.name + "'.");
            }
        }

        if (tp.lowerBound != null) {
            if (!isAssignable(tp.lowerBound, argDesc)) {
                reportError(ctx, "Type argument '" + typeArg + "' does not satisfy lower bound '" + tp.lowerBound
                        + "' of type parameter '" + tp.name + "'.");
            }
        }
    }

    private void checkVariance(ParserRuleContext ctx, String type, String ownerPath, boolean isParameter) {
        List<CompilerRegistry.TypeParameterInfo> classTypeParams = globalTypeParameterRegistry.get(ownerPath);
        if (classTypeParams == null || classTypeParams.isEmpty())
            return;

        for (CompilerRegistry.TypeParameterInfo tp : classTypeParams) {
            if (type.equals(tp.name) || type.contains("<" + tp.name + ">")
                    || type.contains("," + tp.name + ">")
                    || type.contains("<" + tp.name + ",")) {
                if (tp.variance == CompilerRegistry.Variance.COVARIANT && isParameter) {
                    reportError(ctx,
                            "Covariant type parameter '" + tp.name
                                    + "' cannot be used in a parameter position in class '"
                                     + ownerPath + "'.");
                } else if (tp.variance == CompilerRegistry.Variance.CONTRAVARIANT && !isParameter) {
                    reportError(ctx,
                            "Contravariant type parameter '" + tp.name
                                    + "' cannot be used in a return position in class '"
                                    + ownerPath + "'.");
                }
            }
        }
    }

    @Override
    public Object visitNormalMethod(OceanParser.NormalMethodContext ctx) {
        String methodName = ctx.anyId().getText();
        String extendedClass = ctx.extType != null ? ctx.extType.getText() : null;

        boolean isMain = methodName.equals("main");
        String oldContext = currentMethodContext;
        currentMethodContext = currentClassName + "." + methodName;
        String oldMethodName = currentMethodName;
        currentMethodName = methodName;

        Object res = processMethod(ctx, methodName, isMain, ctx.modifier(), ctx.parameterList(), ctx.block(),
                extendedClass);

        currentMethodContext = oldContext;
        currentMethodName = oldMethodName;
        return res;
    }

    @Override
    public Object visitMainMethod(OceanParser.MainMethodContext ctx) {
        String oldContext = currentMethodContext;
        currentMethodContext = currentClassName + ".main";
        String oldMethodName = currentMethodName;
        currentMethodName = "main";
        Object res = processMethod(ctx, "main", true, ctx.modifier(), ctx.parameterList(), ctx.block(), null);
        currentMethodContext = oldContext;
        currentMethodName = oldMethodName;
        return res;
    }

    private Object processMethod(ParserRuleContext ctx, String methodName, boolean isMain,
            List<OceanParser.ModifierContext> modifiers,
            OceanParser.ParameterListContext parameterList,
            OceanParser.BlockContext block,
            String extendedClass) {

        Set<String> oldMethodTypeParams = new HashSet<>(currentMethodTypeParameters);
        currentMethodTypeParameters.clear();
        if (ctx instanceof OceanParser.NormalMethodContext nmCtx) {
            if (nmCtx.typeParameter() != null) {
                for (OceanParser.TypeParameterContext tpCtx : nmCtx.typeParameter()) {
                    CompilerRegistry.TypeParameterInfo info = (CompilerRegistry.TypeParameterInfo) visit(tpCtx);
                    currentMethodTypeParameters.add(info.name);
                }
            }
        }

        MethodVisitor oldMv = this.currentMethodVisitor;
        int access = 0; // Default to package-private!
        boolean hasExplicitAccess = false;
        if (isMain) {
            access = Opcodes.ACC_PUBLIC;
            hasExplicitAccess = true;
        }
        boolean isStatic = isMain;
        boolean isSynchronized = false;
        boolean isAbstract = false;
        if (modifiers != null) {
            for (OceanParser.ModifierContext mod : modifiers) {
                String modText = mod.getText();
                switch (modText) {
                    case "private" -> {
                        access = Opcodes.ACC_PRIVATE;
                        hasExplicitAccess = true;
                    }
                    case "protected" -> {
                        access = Opcodes.ACC_PROTECTED;
                        hasExplicitAccess = true;
                    }
                    case "public" -> {
                        access = Opcodes.ACC_PUBLIC;
                        hasExplicitAccess = true;
                    }
                    case "static" -> isStatic = true;
                    case "final" -> access |= Opcodes.ACC_FINAL;
                    case "abstract" -> {
                        isAbstract = true;
                        access |= Opcodes.ACC_ABSTRACT;
                    }
                    case "sync" -> isSynchronized = true;
                }
            }
        }
        if (block == null) {
            isAbstract = true;
            access |= Opcodes.ACC_ABSTRACT;
        }
        for (int i = 0; i < ctx.getChildCount(); i++) {
            String text = ctx.getChild(i).getText();
            if (text.equals("("))
                break;
            if (text.equals("static"))
                isStatic = true;
            if (text.equals("lock"))
                isSynchronized = true;
        }
        StringBuilder descriptor = new StringBuilder("(");

        boolean isExtension = extendedClass != null;
        if (isExtension)
            isStatic = true; // Extension methods are static in bytecode

        symbolTable = new SymbolTable(isStatic, importedClasses, getAllActiveTypeParameters());

        if (isExtension) {
            String extendedTypeDesc = getTypeDescriptor(extendedClass);
            descriptor.append(extendedTypeDesc);
            int idx = symbolTable.declareVariable("this", extendedClass); // 'this' refers to the instance

        }

        if (isMain) {
            descriptor.append("[Ljava/lang/String;");
            symbolTable.declareVariable("args", "[Ljava/lang/String;");
        } else if (parameterList != null) {
            for (OceanParser.ParameterContext p : parameterList.parameter()) {
                String pType = p.type().getText();
                String pName = p.anyId().getText();
                checkVariance(p, pType, getCurrentClassPath(), true);
                String pDesc = getTypeDescriptor(pType);
                descriptor.append(pDesc);
                symbolTable.declareVariable(pName, pType);
            }
        }
        descriptor.append(")");
        String returnTypeDesc;
        if (isMain) {
            returnTypeDesc = "V";
        } else {
            String detectedType = "void";
            for (int i = 0; i < ctx.getChildCount(); i++) {
                if (ctx.getChild(i).getText().equals("void")) {
                    detectedType = "void";
                    break;
                }
                if (ctx.getChild(i) instanceof OceanParser.TypeContext) {
                    detectedType = ctx.getChild(i).getText();
                    break;
                }
            }
            if (detectedType.equals("void") && block != null) {
                returnTypeDesc = inferReturnTypeFromBlock(block);
            } else {
                checkVariance(ctx, detectedType, getCurrentClassPath(), false);
                returnTypeDesc = getTypeDescriptor(detectedType);
            }
        }

        descriptor.append(returnTypeDesc);
        String classKey = getCurrentClassPath();

        if (!isPreScan) {
            // Validate @Override annotation
            List<OceanParser.AnnotationContext> annotations = getMemberAnnotations(ctx);
            boolean hasOverride = false;
            if (annotations != null) {
                for (OceanParser.AnnotationContext ann : annotations) {
                    String annName = ann.typeName() != null ? ann.typeName().getText() : ann.getChild(1).getText();
                    if (annName.equals("Override")) {
                        hasOverride = true;
                        break;
                    }
                }
            }

            String superPath = globalSuperClassRegistry.get(classKey);
            String[] superInterfaces = globalInterfaceRegistry.get(classKey);
            boolean foundOverride = false;

            List<String> pathsToCheck = new ArrayList<>();
            if (superPath != null)
                pathsToCheck.add(superPath);
            if (superInterfaces != null) {
                pathsToCheck.addAll(Arrays.asList(superInterfaces));
            }

            for (String path : pathsToCheck) {
                Map<String, List<String>> superOverloads = globalOverloadRegistry.get(path);
                if (superOverloads != null && superOverloads.containsKey(methodName)) {
                    List<String> supers = superOverloads.get(methodName);
                    for (String superDesc : supers) {
                        String superParams = superDesc.substring(0, superDesc.indexOf(')') + 1);
                        String currentParams = descriptor.substring(0,
                                descriptor.toString().indexOf(')') + 1);
                        if (superParams.equals(currentParams)) {
                            foundOverride = true;
                            String superReturn = superDesc.substring(superDesc.indexOf(')') + 1);
                            if (!superReturn.equals(returnTypeDesc)) {
                                reportError(ctx,
                                        "Method '" + methodName + "' overrides a method from '" + path
                                                + "' but has a different return type. Expected: " + superReturn
                                                + ", Found: " + returnTypeDesc);
                            }
                            break;
                        }
                    }
                }
                if (foundOverride)
                    break;

                if (!isInternalOceanClass(path)) {
                    try {
                        Class<?> cls = Class.forName(path.replace("/", "."));
                        for (Method m : cls.getMethods()) {
                            if (m.getName().equals(methodName)) {
                                StringBuilder sb = new StringBuilder("(");
                                for (Class<?> p : m.getParameterTypes()) {
                                    sb.append(getClassDescriptor(p));
                                }
                                sb.append(")");
                                String superParams = sb.toString();
                                String currentParams = descriptor.substring(0,
                                        descriptor.toString().indexOf(')') + 1);
                                if (superParams.equals(currentParams)) {
                                    foundOverride = true;
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (foundOverride)
                    break;
            }

            if (hasOverride && !foundOverride && superPath != null) {
                reportError(ctx, "Method '" + methodName
                        + "' is annotated with @Override but does not override any method from its supertypes.");
            }
        }

        globalMethodRegistry.computeIfAbsent(classKey, k -> new ConcurrentHashMap<>())
                .put(methodName, descriptor.toString());
        // Register overload: keep ALL descriptors for a method name
        globalOverloadRegistry.computeIfAbsent(classKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(methodName, k -> new CopyOnWriteArrayList<>());
        List<String> overloads = globalOverloadRegistry.get(classKey).get(methodName);
        if (!overloads.contains(descriptor.toString())) {
            overloads.add(descriptor.toString());
        }
        globalMethodStaticity.computeIfAbsent(classKey, k -> new ConcurrentHashMap<>())
                .put(methodName, isStatic);
        int finalAccess = access;
        if (!hasExplicitAccess)
            finalAccess |= Opcodes.ACC_PUBLIC;
        globalMethodAccess.computeIfAbsent(classKey, k -> new ConcurrentHashMap<>())
                .put(methodName, finalAccess);

        if (extendedClass != null) {
            String receiverDesc = getTypeDescriptor(extendedClass);

            globalExtensionMethodRegistry
                    .computeIfAbsent(receiverDesc, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(methodName, k -> new CopyOnWriteArrayList<>())
                    .add(new CompilerRegistry.ExtensionMethodInfo(classKey, methodName, descriptor.toString()));
        }

        if (isPreScan) {
            return null;
        }
        if (!hasExplicitAccess) {
            access |= Opcodes.ACC_PUBLIC;
        }
        if (isStatic)
            access |= Opcodes.ACC_STATIC;
        if (isSynchronized)
            access |= Opcodes.ACC_SYNCHRONIZED;
        MethodVisitor mv = cw.visitMethod(access, methodName, stripGenerics(descriptor.toString()), null, null);
        emitAnnotations(getMemberAnnotations(ctx), mv);
        if (!isAbstract) {
            mv.visitCode();
            this.currentMethodVisitor = mv;
            String previousReturn = currentMethodReturnDescriptor;
            currentMethodReturnDescriptor = returnTypeDesc;
            if (block != null) {
                try {
                    visit(block);
                } catch (Exception e) {
                    reportError(ctx, "Bytecode generation failed in method '" + methodName + "': " + e.getMessage() + " (" + e.getClass().getSimpleName() + ")");
                    
                    // Fallback to prevent ASM frame computation crashes downstream
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    if (!returnTypeDesc.equals("V")) {
                        emitDefaultReturn(returnTypeDesc);
                    } else {
                        mv.visitInsn(Opcodes.POP);
                        mv.visitInsn(Opcodes.RETURN);
                    }
                }
            }
            currentMethodReturnDescriptor = previousReturn;
            if (!blockEndsWithReturn(block)) {
                if (!returnTypeDesc.equals("V")) {
                    reportError(ctx, "Missing return statement in method '" + methodName + "' (expected "
                            + returnTypeDesc + ")");
                }
                emitDefaultReturnWithPush(returnTypeDesc);
            }
            try {
                mv.visitMaxs(0, 0);
            } catch (Throwable e) {
                System.err.println("CRITICAL: ASM Frame Computation Failed in " + currentClassName + "::" + methodName);
                System.err.println("Descriptor: " + descriptor);
                needsComputeMaxsFallback = true;
                // Eğer ASM çökerse, COMPUTE_FRAMES olmadan devam etmeyi deneyebiliriz
                // Ama genellikle bu durum bytecode'un bozuk olduğunu gösterir.
                throw new RuntimeException("Bytecode generation failed for " + methodName + " in " + currentClassName,
                        e);
            }
            currentMethodVisitor = oldMv;
        }
        mv.visitEnd();
        return null;
    }

    @Override
    public Object visitReturnStmt(OceanParser.ReturnStmtContext ctx) {
        if (ctx.returnStatement().expression() != null) {
            Object result = visit(ctx.returnStatement().expression());
            String type = (result instanceof String) ? (String) result : inferType(ctx.returnStatement().expression());
            if (currentMethodReturnDescriptor.equals("V")) {
                if (type.equals("D") || type.equals("J"))
                    currentMethodVisitor.visitInsn(Opcodes.POP2);
                else
                    currentMethodVisitor.visitInsn(Opcodes.POP);

                for (Runnable fin : finallyStack)
                    fin.run();
                currentMethodVisitor.visitInsn(Opcodes.RETURN);
            } else {
                emitAssignConversion(type, currentMethodReturnDescriptor);
                for (Runnable fin : finallyStack)
                    fin.run();
                emitDefaultReturn(currentMethodReturnDescriptor);
            }
        } else {
            for (Runnable fin : finallyStack)
                fin.run();
            currentMethodVisitor.visitInsn(Opcodes.RETURN);
        }
        return null;
    }

    @Override
    public Object visitTryStmt(OceanParser.TryStmtContext ctx) {
        if (currentMethodVisitor == null)
            return null;

        OceanParser.TryStatementContext tryCtx = ctx.tryStatement();

        boolean hasUserFinally = tryCtx.FINALLY() != null;
        boolean hasResources = tryCtx.resourceList() != null && !tryCtx.resourceList().resource().isEmpty();
        boolean hasFinally = hasUserFinally || hasResources;

        if (hasResources && symbolTable != null) {
            symbolTable.enterScope();
        }

        class ResourceInfo {
            final int varIndex;
            final String typeDesc;

            ResourceInfo(int v, String t) {
                varIndex = v;
                typeDesc = t;
            }
        }
        List<ResourceInfo> resources = new ArrayList<>();

        if (hasResources) {
            for (OceanParser.ResourceContext resCtx : tryCtx.resourceList().resource()) {
                String varName = resCtx.anyId().getText();
                String typeName = resCtx.type().getText();
                String typeDesc = getTypeDescriptor(typeName);
                visit(resCtx.expression());
                int varIndex = symbolTable != null ? symbolTable.declareVariable(varName, typeDesc) : 0;
                currentMethodVisitor.visitVarInsn(Opcodes.ASTORE, varIndex);
                resources.add(new ResourceInfo(varIndex, typeDesc));
            }
        }

        Label startTry = new Label();
        Label endTry = new Label();
        Label endTryStmt = new Label();
        Label finallyLabel = new Label();

        final Runnable[] currentFin = new Runnable[1];
        currentFin[0] = () -> {
            for (int i = resources.size() - 1; i >= 0; i--) {
                ResourceInfo res = resources.get(i);
                currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, res.varIndex);
                Label skipClose = new Label();
                currentMethodVisitor.visitJumpInsn(Opcodes.IFNULL, skipClose);
                currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, res.varIndex);
                currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/AutoCloseable");
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/AutoCloseable", "close", "()V",
                        true);
                currentMethodVisitor.visitLabel(skipClose);
            }
            if (hasUserFinally) {
                boolean popped = false;
                Runnable saved = null;
                if (!finallyStack.isEmpty() && finallyStack.peek() == currentFin[0]) {
                    saved = finallyStack.pop();
                    popped = true;
                }
                try {
                    visit(tryCtx.block(tryCtx.block().size() - 1));
                } finally {
                    if (popped) {
                        finallyStack.push(saved);
                    }
                }
            }
        };
        Runnable finTask = currentFin[0];

        List<Label> catchLabels = new ArrayList<>();
        for (OceanParser.CatchClauseContext catchCtx : tryCtx.catchClause()) {
            Label startCatch = new Label();
            catchLabels.add(startCatch);
            for (OceanParser.TypeContext typeCtx : catchCtx.type()) {
                String exceptionType = resolveExceptionType(typeCtx.getText());
                currentMethodVisitor.visitTryCatchBlock(startTry, endTry, startCatch, exceptionType);
            }
        }

        if (hasFinally) {
            currentMethodVisitor.visitTryCatchBlock(startTry, endTry, finallyLabel, null);
            for (Label l : catchLabels) {
                currentMethodVisitor.visitTryCatchBlock(l, endTryStmt, finallyLabel, null);
            }
        }

        currentMethodVisitor.visitLabel(startTry);
        if (hasFinally) {
            finallyStack.push(finTask);
        }
        visit(tryCtx.block(0));
        currentMethodVisitor.visitLabel(endTry);

        if (hasFinally) {
            finTask.run();
        }
        currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, endTryStmt);

        for (int i = 0; i < tryCtx.catchClause().size(); i++) {
            OceanParser.CatchClauseContext catchCtx = tryCtx.catchClause(i);
            currentMethodVisitor.visitLabel(catchLabels.get(i));

            if (symbolTable != null)
                symbolTable.enterScope();

            String varName = catchCtx.anyId().getText();
            String baseType = "Ljava/lang/Exception;";
            if (catchCtx.type().size() == 1) {
                baseType = OceanTypeSystem.wrapObjectType(resolveExceptionType(catchCtx.type(0).getText()));
            } else if (catchCtx.type().size() > 1) {
                baseType = "Ljava/lang/Exception;";
            }

            int excVar = symbolTable != null ? symbolTable.declareVariable(varName, baseType) : 0;
            currentMethodVisitor.visitVarInsn(Opcodes.ASTORE, excVar);

            visit(catchCtx.block());

            if (symbolTable != null)
                symbolTable.exitScope();

            if (hasFinally) {
                finTask.run();
            }
            currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, endTryStmt);
        }

        if (hasFinally) {
            finallyStack.pop();
        }

        currentMethodVisitor.visitLabel(endTryStmt);
        Label endFinally = new Label();
        currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, endFinally);

        if (hasFinally) {
            currentMethodVisitor.visitLabel(finallyLabel);
            int tmpVar = symbolTable != null
                    ? symbolTable.declareVariable("$tmp_exc_" + tryCtx.getStart().getStartIndex(),
                            "Ljava/lang/Throwable;")
                    : 0;
            currentMethodVisitor.visitVarInsn(Opcodes.ASTORE, tmpVar);

            finTask.run();

            currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, tmpVar);
            currentMethodVisitor.visitInsn(Opcodes.ATHROW);
        }
        currentMethodVisitor.visitLabel(endFinally);

        if (hasResources && symbolTable != null) {
            symbolTable.exitScope();
        }

        return null;
    }

    private MethodVisitor currentMethodVisitor;

    private String getBoxedDescriptor(String desc) {
        if (desc == null)
            return null;
        return switch (desc) {
            case "Z" -> "Ljava/lang/Boolean;";
            case "B" -> "Ljava/lang/Byte;";
            case "C" -> "Ljava/lang/Character;";
            case "S" -> "Ljava/lang/Short;";
            case "I" -> "Ljava/lang/Integer;";
            case "J" -> "Ljava/lang/Long;";
            case "F" -> "Ljava/lang/Float;";
            case "D" -> "Ljava/lang/Double;";
            default -> desc;
        };
    }

    private boolean isPrimitive(String desc) {
        return desc != null && desc.length() == 1 && "IZDJFCBS".indexOf(desc.charAt(0)) >= 0;
    }

    private String getWrapperType(String primitive) {
        return switch (primitive) {
            case "I" -> "Ljava/lang/Integer;";
            case "Z" -> "Ljava/lang/Boolean;";
            case "J" -> "Ljava/lang/Long;";
            case "D" -> "Ljava/lang/Double;";
            case "F" -> "Ljava/lang/Float;";
            case "B" -> "Ljava/lang/Byte;";
            case "S" -> "Ljava/lang/Short;";
            case "C" -> "Ljava/lang/Character;";
            default -> primitive;
        };
    }

    private void emitBoxing(String primitiveType) {
        String wrapper = getWrapperInternalName(primitiveType);
        if (wrapper == null)
            return;
        String boxDesc = "(" + primitiveType + ")L" + wrapper + ";";
        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, wrapper, "valueOf", boxDesc, false);
    }

    private void emitUnboxToPrimitive(String primitiveType) {
        if ("Z".equals(primitiveType)) {
            currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
            currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z",
                    false);
            return;
        }
        if ("C".equals(primitiveType)) {
            currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Character");
            currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C",
                    false);
            return;
        }

        // For numbers, use java/lang/Number to allow flexibility (e.g., Integer stored
        // in Object unboxed to long)
        currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
        String unboxMethod = getUnboxMethodName(primitiveType);
        String unboxDesc = "()" + primitiveType;
        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", unboxMethod, unboxDesc, false);
    }

    private String getWrapperInternalName(String primitiveType) {
        if (primitiveType == null)
            return null;
        return switch (primitiveType) {
            case "I" -> "java/lang/Integer";
            case "Z" -> "java/lang/Boolean";
            case "D" -> "java/lang/Double";
            case "J" -> "java/lang/Long";
            case "F" -> "java/lang/Float";
            case "C" -> "java/lang/Character";
            case "B" -> "java/lang/Byte";
            case "S" -> "java/lang/Short";
            default -> null;
        };
    }

    private String getUnboxMethodName(String primitiveType) {
        if (primitiveType == null)
            return null;
        return switch (primitiveType) {
            case "I" -> "intValue";
            case "Z" -> "booleanValue";
            case "D" -> "doubleValue";
            case "J" -> "longValue";
            case "F" -> "floatValue";
            case "C" -> "charValue";
            case "B" -> "byteValue";
            case "S" -> "shortValue";
            default -> null;
        };
    }

    private String getWrapperDescriptor(String primitiveType) {
        String wrapper = getWrapperInternalName(primitiveType);
        return wrapper != null ? OceanTypeSystem.wrapObjectType(wrapper) : primitiveType;
    }

    private void emitAssignConversion(String stackType, String targetType) {
        if (stackType == null || targetType == null)
            return;
        if (stackType.equals(targetType))
            return;

        // 1. BigDecimal promotion
        if ("Ljava/math/BigDecimal;".equals(targetType)) {
            ensureBigDecimal(stackType);
            return;
        }

        // 2. Unified primitive-to-primitive handling (includes narrowing and widening)
        if (isPrimitive(stackType) && isPrimitive(targetType)) {
            emitNumericCast(stackType, targetType);
            return;
        }
        if (stackType.startsWith("L") && isPrimitive(targetType)) {
            emitUnboxToPrimitive(targetType);
        } else if (isPrimitive(stackType) && (targetType.startsWith("L") || targetType.startsWith("["))) {
            emitBoxing(stackType);
            if (!OceanTypeSystem.OBJECT_DESC.equals(targetType)) {
                String internal = targetType;
                if (internal.startsWith("L") && internal.endsWith(";")) {
                    internal = internal.substring(1, internal.length() - 1);
                }
                currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, internal);
            }
        } else if ((stackType.startsWith("L") || stackType.startsWith("["))
                && (targetType.startsWith("L") || targetType.startsWith("["))) {
            if (!OceanTypeSystem.OBJECT_DESC.equals(targetType)) {
                String internal = targetType;
                if (internal.startsWith("L") && internal.endsWith(";")) {
                    internal = internal.substring(1, internal.length() - 1);
                }
                currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, internal);
            }
        } else if ("V".equals(stackType)) {
            currentMethodVisitor.visitInsn(Opcodes.ACONST_NULL);
            if (targetType.startsWith("L") && !OceanTypeSystem.OBJECT_DESC.equals(targetType)) {
                String internal = targetType;
                if (internal.startsWith("L") && internal.endsWith(";")) {
                    internal = internal.substring(1, internal.length() - 1);
                }
                currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, internal);
            }
        }
    }

    @Override
    public Object visitVariableDeclStmt(OceanParser.VariableDeclStmtContext ctx) {
        if (currentMethodVisitor == null)
            return null;
        OceanParser.VariableDeclarationContext varCtx = ctx.variableDeclaration();
        List<OceanParser.VariableDeclaratorContext> decls = Collections.emptyList();
        OceanParser.TypeContext typeCtx = null;
        boolean isFinal = false;

        if (varCtx instanceof OceanParser.FinalVarDeclContext vd) {
            decls = vd.variableDeclarator();
            typeCtx = vd.type();
            isFinal = true;
        } else if (varCtx instanceof OceanParser.TypedVarDeclContext vd) {
            decls = vd.variableDeclarator();
            typeCtx = vd.type();
        } else if (varCtx instanceof OceanParser.VariableDeclContext vd) {
            decls = vd.variableDeclarator();
        } else if (varCtx instanceof OceanParser.ValueDeclContext vd) {
            decls = vd.variableDeclarator();
            isFinal = true;
        }

        for (OceanParser.VariableDeclaratorContext d : decls) {
            String varName = d.anyId().getText();
            OceanParser.ExpressionContext exprCtx = d.expression();

            String typeDesc = null;
            if (typeCtx != null) {
                String rawType = typeCtx.getText();
                if (rawType.equals("variable") || rawType.equals("value") || rawType.equals("var")) {
                    if (exprCtx != null) {
                        typeDesc = inferType(exprCtx);
                    }
                }

                if (typeDesc == null) {
                    typeDesc = getTypeDescriptor(rawType);
                    if (typeDesc.startsWith("Locean/compiler/generated/")) {
                        String resolvedName = resolveClassName(rawType, ctx);
                        typeDesc = OceanTypeSystem.wrapObjectType(resolvedName);
                    }
                }
            } else if (exprCtx != null) {
                typeDesc = inferType(exprCtx);
            } else {
                typeDesc = OceanTypeSystem.OBJECT_DESC;
            }

            if (exprCtx != null) {
                Object result = visit(exprCtx);
                String stackType = (result instanceof String) ? (String) result : inferType(exprCtx);
                emitAssignConversion(stackType, typeDesc);
            } else {
                emitPushDefault(typeDesc);
            }

            int index = symbolTable.declareVariable(varName, typeDesc, isFinal);
            emitStore(index, typeDesc);
        }
        return null;
    }

    @Override
    public Object visitAssignmentStmt(OceanParser.AssignmentStmtContext ctx) {
        if (currentMethodVisitor == null)
            return null;
        OceanParser.AssignmentContext assignCtx = ctx.assignment();
        OceanParser.ExpressionContext lhs = assignCtx.expression(0);
        OceanParser.ExpressionContext rhs = assignCtx.expression(1);
        String opText = assignCtx.op.getText();

        // Compound assignment: +=, -=, *=, /=, %=
        if (!opText.equals("=")) {
            return handleCompoundAssignment(ctx, lhs, rhs, opText);
        }

        if (lhs instanceof OceanParser.ArrayAccessExprContext arrayCtx) {
            visit(arrayCtx.expression(0));
            visit(arrayCtx.expression(1));
            Object rhsResult = visit(rhs);
            String arrType = inferType(arrayCtx.expression(0));
            String exprType = (rhsResult instanceof String) ? (String) rhsResult : inferType(rhs);
            String elemType = arrType.startsWith("[") ? arrType.substring(1) : OceanTypeSystem.OBJECT_DESC;
            emitAssignConversion(exprType, elemType);
            currentMethodVisitor.visitInsn(getArrayOpcode(arrType, false));
            return null;
        }

        if (!assignCtx.expression().isEmpty()
                && assignCtx.expression().getFirst() instanceof OceanParser.MemberCallExprContext mCtx) {
            String memberName = mCtx.anyId().getText();
            OceanParser.ExpressionContext ownerExpr = mCtx.expression();
            String ownerDesc = inferType(ownerExpr);
            String internalOwner = (ownerDesc != null && ownerDesc.startsWith("L") && ownerDesc.length() > 2)
                    ? ownerDesc.substring(1, ownerDesc.length() - 1)
                    : "java/lang/Object";

            boolean isStaticClassCall = false;
            if (ownerExpr instanceof OceanParser.PrimaryExprContext) {
                OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) ownerExpr).primary();
                if (p instanceof OceanParser.IdPrimaryContext) {
                    String id = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                    if (symbolTable.getType(id) == null && !currentClassFields.containsKey(id)) {
                        isStaticClassCall = isStaticTarget(id);
                        if (isStaticClassCall) {
                            String langDefault = getJavaLangDefault(id);
                            if (langDefault != null)
                                internalOwner = langDefault;
                            else if (importedClasses.containsKey(id))
                                internalOwner = importedClasses.get(id);
                            else
                                internalOwner = resolveInternalPath(id);
                        }
                    }
                }
            }
            internalOwner = robustResolveInternalOwner(internalOwner);

            boolean isExternal = !isInternalOceanClass(internalOwner);

            if (!isStaticClassCall) {
                visit(ownerExpr);
            }
            Object rhsResult = visit(rhs);
            String exprType = (rhsResult instanceof String) ? (String) rhsResult : inferType(rhs);
            String resolvedOwner = internalOwner;
            String fieldDesc = null;

            if (!isExternal) {
                resolvedOwner = findFieldOwner(internalOwner, memberName);
                fieldDesc = globalFieldRegistry.getOrDefault(resolvedOwner, Collections.emptyMap()).get(memberName);
                int mods = globalFieldAccess.getOrDefault(resolvedOwner, Collections.emptyMap())
                        .getOrDefault(memberName, Opcodes.ACC_PUBLIC);
                checkAccess(resolvedOwner, memberName, mods, ctx);
            } else {
                fieldDesc = resolveExternalFieldDescriptor(internalOwner, memberName);
            }

            if (fieldDesc == null) {
                fieldDesc = OceanTypeSystem.OBJECT_DESC;
            }

            emitAssignConversion(exprType, fieldDesc);

            int opcode = isStaticClassCall ? Opcodes.PUTSTATIC : Opcodes.PUTFIELD;
            currentMethodVisitor.visitFieldInsn(opcode, stripGenerics(resolvedOwner), memberName,
                    stripGenerics(fieldDesc));
            return null;
        }

        String rawLhs = lhs.getText();
        boolean forceField = rawLhs.startsWith("this.");
        String varName = forceField ? rawLhs.substring(5) : rawLhs;
        int index = forceField ? -1 : symbolTable.getIndex(varName);

        // Optimization: use IINC for i = i + const or i = i - const
        if (index != -1 && "I".equals(symbolTable.getType(varName))) {
            Integer delta = null;
            if (rhs instanceof OceanParser.AddSubExprContext ase) {
                String op = ase.op.getText();
                OceanParser.ExpressionContext e0 = ase.expression(0);
                OceanParser.ExpressionContext e1 = ase.expression(1);
                if (e0.getText().equals(varName) && isConstantInt(e1)) {
                    int val = getConstantInt(e1);
                    delta = op.equals("+") ? val : -val;
                } else if (op.equals("+") && e1.getText().equals(varName) && isConstantInt(e0)) {
                    delta = getConstantInt(e0);
                }
            }
            if (delta != null && delta >= Short.MIN_VALUE && delta <= Short.MAX_VALUE) {
                symbolTable.markMutated(varName, ctx);
                currentMethodVisitor.visitIincInsn(index, delta);
                return null;
            }
        }

        boolean isInstanceField = (index == -1 && currentClassFields.containsKey(varName));
        if (isInstanceField && !isFieldStatic.getOrDefault(varName, false)) {
            currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        }
        Object rhsResult = visit(rhs);
        String exprType = (rhsResult instanceof String) ? (String) rhsResult : inferType(rhs);
        if (index != -1) {
            if (symbolTable.isFinal(varName)) {
                reportError(ctx, "Cannot reassign constant variable '" + varName + "'");
            }
            symbolTable.markMutated(varName, ctx);
            String typeDesc = symbolTable.getType(varName);
            emitAssignConversion(exprType, typeDesc);
            emitStore(index, typeDesc);
        } else if (currentClassFields.containsKey(varName)) {
            String owner = getCurrentClassPath();
            String resolvedOwner = findFieldOwner(owner, varName);
            boolean isFinal = globalFieldMutability.getOrDefault(resolvedOwner, Collections.emptyMap())
                    .getOrDefault(varName, false);
            if (isFinal) {
                reportError(ctx, "Cannot reassign constant field '" + varName + "'");
            }
            String fieldDesc = currentClassFields.get(varName);
            emitAssignConversion(exprType, fieldDesc);
            if (isFieldStatic.getOrDefault(varName, false)) {
                currentMethodVisitor.visitFieldInsn(Opcodes.PUTSTATIC, stripGenerics(resolvedOwner), varName,
                        stripGenerics(fieldDesc));
            } else {
                currentMethodVisitor.visitFieldInsn(Opcodes.PUTFIELD, stripGenerics(resolvedOwner), varName,
                        stripGenerics(fieldDesc));
            }
        }
        return null;
    }

    // ========== Compound Assignment (+=, -=, *=, /=, %=) ==========

    private Object handleCompoundAssignment(OceanParser.AssignmentStmtContext ctx,
            OceanParser.ExpressionContext lhs,
            OceanParser.ExpressionContext rhs,
            String opText) {
        // Determine the arithmetic operation
        int arithmeticOp;
        switch (opText) {
            case "+=":
                arithmeticOp = Opcodes.IADD;
                break;
            case "-=":
                arithmeticOp = Opcodes.ISUB;
                break;
            case "*=":
                arithmeticOp = Opcodes.IMUL;
                break;
            case "/=":
                arithmeticOp = Opcodes.IDIV;
                break;
            case "%=":
                arithmeticOp = Opcodes.IREM;
                break;
            default:
                return null;
        }

        String rawLhs = lhs.getText();
        boolean forceField = rawLhs.startsWith("this.");
        String varName = forceField ? rawLhs.substring(5) : rawLhs;
        int index = forceField ? -1 : symbolTable.getIndex(varName);

        // IINC optimization for simple int += const or -= const
        if (index != -1 && "I".equals(symbolTable.getType(varName))
                && (opText.equals("+=") || opText.equals("-="))) {
            if (isConstantInt(rhs)) {
                int val = getConstantInt(rhs);
                int delta = opText.equals("+=") ? val : -val;
                if (delta >= Short.MIN_VALUE && delta <= Short.MAX_VALUE) {
                    currentMethodVisitor.visitIincInsn(index, delta);
                    return null;
                }
            }
        }

        if (index != -1) {
            // Local variable compound assignment
            symbolTable.markMutated(varName, ctx);
            String typeDesc = symbolTable.getType(varName);
            emitLoad(index, typeDesc);
            visit(rhs);
            String rhsType = inferType(rhs);
            emitAssignConversion(rhsType, typeDesc);
            int adjustedOp = getArithmeticOpcode(arithmeticOp, typeDesc);
            currentMethodVisitor.visitInsn(adjustedOp);
            emitStore(index, typeDesc);
        } else if (currentClassFields.containsKey(varName)) {
            // Instance/static field compound assignment
            String owner = getCurrentClassPath();
            String resolvedOwner = findFieldOwner(owner, varName);
            String fieldDesc = currentClassFields.get(varName);
            boolean isStatic = isFieldStatic.getOrDefault(varName, false);

            if (isStatic) {
                currentMethodVisitor.visitFieldInsn(Opcodes.GETSTATIC, stripGenerics(resolvedOwner), varName,
                        stripGenerics(fieldDesc));
            } else {
                currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                currentMethodVisitor.visitInsn(Opcodes.DUP);
                currentMethodVisitor.visitFieldInsn(Opcodes.GETFIELD, stripGenerics(resolvedOwner), varName,
                        stripGenerics(fieldDesc));
            }
            visit(rhs);
            String rhsType = inferType(rhs);
            emitAssignConversion(rhsType, fieldDesc);
            int adjustedOp = getArithmeticOpcode(arithmeticOp, fieldDesc);
            currentMethodVisitor.visitInsn(adjustedOp);
            if (isStatic) {
                currentMethodVisitor.visitFieldInsn(Opcodes.PUTSTATIC, stripGenerics(resolvedOwner), varName,
                        stripGenerics(fieldDesc));
            } else {
                currentMethodVisitor.visitFieldInsn(Opcodes.PUTFIELD, stripGenerics(resolvedOwner), varName,
                        stripGenerics(fieldDesc));
            }
        } else if (lhs instanceof OceanParser.MemberCallExprContext mCtx) {
            // obj.field += rhs
            String memberName = mCtx.anyId().getText();
            OceanParser.ExpressionContext ownerExpr = mCtx.expression();
            visit(ownerExpr);
            currentMethodVisitor.visitInsn(Opcodes.DUP);
            String ownerDesc = inferType(ownerExpr);
            String internalOwner = (ownerDesc != null && ownerDesc.startsWith("L") && ownerDesc.length() > 2)
                    ? ownerDesc.substring(1, ownerDesc.length() - 1)
                    : "java/lang/Object";
            internalOwner = robustResolveInternalOwner(internalOwner);
            String fieldDesc = globalFieldRegistry.getOrDefault(internalOwner, Collections.emptyMap()).get(memberName);
            if (fieldDesc == null)
                fieldDesc = resolveExternalFieldDescriptor(internalOwner, memberName);
            if (fieldDesc == null)
                fieldDesc = "I";
            currentMethodVisitor.visitFieldInsn(Opcodes.GETFIELD, stripGenerics(internalOwner), memberName,
                    stripGenerics(fieldDesc));
            visit(rhs);
            int adjustedOp = getArithmeticOpcode(arithmeticOp, fieldDesc);
            currentMethodVisitor.visitInsn(adjustedOp);
            currentMethodVisitor.visitFieldInsn(Opcodes.PUTFIELD, stripGenerics(internalOwner), memberName,
                    stripGenerics(fieldDesc));
        } else if (lhs instanceof OceanParser.ArrayAccessExprContext arrCtx) {
            // arr[i] += rhs
            visit(arrCtx.expression(0));
            visit(arrCtx.expression(1));
            String arrType = inferType(arrCtx.expression(0));
            currentMethodVisitor.visitInsn(Opcodes.DUP2);
            currentMethodVisitor.visitInsn(getArrayOpcode(arrType, true)); // load
            visit(rhs);
            String elemType = arrType.startsWith("[") ? arrType.substring(1) : "I";
            int adjustedOp = getArithmeticOpcode(arithmeticOp, elemType);
            currentMethodVisitor.visitInsn(adjustedOp);
            currentMethodVisitor.visitInsn(getArrayOpcode(arrType, false)); // store
        }
        return null;
    }

    /**
     * Adjusts int-based arithmetic opcode (IADD, ISUB, etc.) for the actual type
     * descriptor.
     */
    private int getArithmeticOpcode(int intOpcode, String typeDesc) {
        if (typeDesc == null)
            return intOpcode;
        return switch (typeDesc) {
            case "J" -> intOpcode + (Opcodes.LADD - Opcodes.IADD);
            case "F" -> intOpcode + (Opcodes.FADD - Opcodes.IADD);
            case "D" -> intOpcode + (Opcodes.DADD - Opcodes.IADD);
            default -> intOpcode;
        };
    }

    // ========== Postfix (x++, x--) ==========

    @Override
    public Object visitPostfixExpr(OceanParser.PostfixExprContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return null;
        return handleIncrDecr(ctx.expression(), ctx.op.getText(), true);
    }

    // ========== Prefix (++x, --x) ==========

    @Override
    public Object visitPrefixExpr(OceanParser.PrefixExprContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return null;
        return handleIncrDecr(ctx.expression(), ctx.op.getText(), false);
    }

    /**
     * Handles both prefix and postfix increment/decrement.
     * 
     * @param isPostfix if true, pushes old value; if false, pushes new value
     */
    private Object handleIncrDecr(OceanParser.ExpressionContext expr, String op, boolean isPostfix) {
        int delta = op.equals("++") ? 1 : -1;

        if (expr instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) expr).primary();
            if (p instanceof OceanParser.IdPrimaryContext) {
                String varName = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                int index = symbolTable.getIndex(varName);
                if (index != -1 && "I".equals(symbolTable.getType(varName))) {
                    // IINC optimization for local int variable
                    symbolTable.markMutated(varName, expr);
                    if (isPostfix) {
                        currentMethodVisitor.visitVarInsn(Opcodes.ILOAD, index);
                    }
                    currentMethodVisitor.visitIincInsn(index, delta);
                    if (!isPostfix) {
                        currentMethodVisitor.visitVarInsn(Opcodes.ILOAD, index);
                    }
                    return "I";
                }
                // General case: local variable
                if (index != -1) {
                    symbolTable.markMutated(varName, expr);
                    String typeDesc = symbolTable.getType(varName);
                    emitLoad(index, typeDesc);
                    if (isPostfix)
                        currentMethodVisitor.visitInsn(Opcodes.DUP);
                    currentMethodVisitor.visitInsn(Opcodes.ICONST_1);
                    currentMethodVisitor.visitInsn(delta > 0 ? Opcodes.IADD : Opcodes.ISUB);
                    if (!isPostfix)
                        currentMethodVisitor.visitInsn(Opcodes.DUP);
                    emitStore(index, typeDesc);
                    return typeDesc;
                }
                // Field access
                if (currentClassFields.containsKey(varName)) {
                    String owner = stripGenerics(getCurrentClassPath());
                    String resolvedOwner = stripGenerics(findFieldOwner(getCurrentClassPath(), varName));
                    String fieldDesc = stripGenerics(currentClassFields.get(varName));
                    boolean isStatic = isFieldStatic.getOrDefault(varName, false);
                    if (isStatic) {
                        currentMethodVisitor.visitFieldInsn(Opcodes.GETSTATIC, resolvedOwner, varName, fieldDesc);
                        if (isPostfix)
                            currentMethodVisitor.visitInsn(Opcodes.DUP);
                        currentMethodVisitor.visitInsn(Opcodes.ICONST_1);
                        currentMethodVisitor.visitInsn(delta > 0 ? Opcodes.IADD : Opcodes.ISUB);
                        if (!isPostfix)
                            currentMethodVisitor.visitInsn(Opcodes.DUP);
                        currentMethodVisitor.visitFieldInsn(Opcodes.PUTSTATIC, resolvedOwner, varName, fieldDesc);
                    } else {
                        currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                        currentMethodVisitor.visitInsn(Opcodes.DUP);
                        currentMethodVisitor.visitFieldInsn(Opcodes.GETFIELD, resolvedOwner, varName, fieldDesc);
                        if (isPostfix) {
                            // stack: this, old_val → need: this, old_val_copy, new_val
                            currentMethodVisitor.visitInsn(Opcodes.DUP_X1);
                        }
                        currentMethodVisitor.visitInsn(Opcodes.ICONST_1);
                        currentMethodVisitor.visitInsn(delta > 0 ? Opcodes.IADD : Opcodes.ISUB);
                        if (!isPostfix)
                            currentMethodVisitor.visitInsn(Opcodes.DUP_X1);
                        currentMethodVisitor.visitFieldInsn(Opcodes.PUTFIELD, resolvedOwner, varName, fieldDesc);
                    }
                    return fieldDesc;
                }
            }
        }
        // Fallback: visit expression, add 1 (result stays on stack but can't store
        // back)
        visit(expr);
        currentMethodVisitor.visitInsn(Opcodes.ICONST_1);
        currentMethodVisitor.visitInsn(delta > 0 ? Opcodes.IADD : Opcodes.ISUB);
        return "I";
    }

    // ========== throw Statement ==========

    @Override
    public Object visitThrowStmt(OceanParser.ThrowStmtContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return null;
        visit(ctx.expression());
        currentMethodVisitor.visitInsn(Opcodes.ATHROW);
        return null;
    }

    // ========== instanceof Expression ==========

    @Override
    public Object visitInstanceOfExpr(OceanParser.InstanceOfExprContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return null;
        visit(ctx.expression());
        String typeText = ctx.instanceofPattern() != null ? ctx.instanceofPattern().getText() : "Object";
        String internalName = SymbolTable.getDescriptor(typeText, importedClasses);
        // Convert descriptor to internal name for INSTANCEOF
        if (internalName.startsWith("L") && internalName.endsWith(";")) {
            internalName = internalName.substring(1, internalName.length() - 1);
        }
        currentMethodVisitor.visitTypeInsn(Opcodes.INSTANCEOF, internalName);
        return "Z";
    }

    // ========== do-while Statement ==========

    @Override
    public Object visitDoWhileStmt(OceanParser.DoWhileStmtContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return null;
        OceanParser.DoWhileStatementContext doWhile = ctx.doWhileStatement();
        Label loopStart = new Label();
        Label loopEnd = new Label();

        loopStack.push(new LoopContext(loopStart, loopEnd, finallyStack.size(), currentLabel));
        currentLabel = null; // Consume label

        currentMethodVisitor.visitLabel(loopStart);
        if (doWhile.statement() != null) {
            visit(doWhile.statement());
        }
        // Condition
        emitConditionJump(doWhile.expression(), loopStart, true);

        currentMethodVisitor.visitLabel(loopEnd);

        loopStack.pop();
        return null;
    }

    // ========== lock Block (MONITORENTER/MONITOREXIT) ==========

    @Override
    public Object visitLockStmt(OceanParser.LockStmtContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return null;
        OceanParser.LockBlockStatementContext lockCtx = ctx.lockBlockStatement();

        // Evaluate the lock expression
        visit(lockCtx.expression());
        int lockVarIndex = symbolTable.nextLocalIndex();
        currentMethodVisitor.visitVarInsn(Opcodes.ASTORE, lockVarIndex);

        // MONITORENTER
        currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, lockVarIndex);
        currentMethodVisitor.visitInsn(Opcodes.MONITORENTER);

        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchHandler = new Label();
        Label afterCatch = new Label();

        currentMethodVisitor.visitTryCatchBlock(tryStart, tryEnd, catchHandler, null);

        currentMethodVisitor.visitLabel(tryStart);

        Runnable unlockTask = () -> {
            currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, lockVarIndex);
            currentMethodVisitor.visitInsn(Opcodes.MONITOREXIT);
        };
        finallyStack.push(unlockTask);

        // Execute the body
        visit(lockCtx.block());

        finallyStack.pop();

        currentMethodVisitor.visitLabel(tryEnd);

        // MONITOREXIT in normal flow
        unlockTask.run();
        currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, afterCatch);

        // MONITOREXIT in exception flow
        currentMethodVisitor.visitLabel(catchHandler);
        unlockTask.run();
        currentMethodVisitor.visitInsn(Opcodes.ATHROW);

        currentMethodVisitor.visitLabel(afterCatch);
        return null;
    }

    @Override
    public Object visitIfStmt(OceanParser.IfStmtContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return null;
        Object folded = foldConstant(ctx.ifStatement().expression());
        if (folded instanceof Boolean) {
            boolean val = (Boolean) folded;
            if (val) {
                visit(ctx.ifStatement().statement(0));
            } else if (ctx.ifStatement().statement().size() > 1) {
                visit(ctx.ifStatement().statement(1));
            }
            return null;
        }

        Label elseLabel = new Label();
        Label endLabel = new Label();
        emitConditionJump(ctx.ifStatement().expression(), elseLabel, false);
        visit(ctx.ifStatement().statement(0));
        currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
        currentMethodVisitor.visitLabel(elseLabel);
        if (ctx.ifStatement().statement().size() > 1)
            visit(ctx.ifStatement().statement(1));
        currentMethodVisitor.visitLabel(endLabel);
        return null;
    }

    @Override
    public Object visitWhileStmt(OceanParser.WhileStmtContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return null;
        Object folded = foldConstant(ctx.whileStatement().expression());
        if (folded instanceof Boolean && !(Boolean) folded) {
            return null; // Dead code elimination
        }

        Label startLabel = new Label();
        Label endLabel = new Label();

        // Heuristic: only unroll if body is small and we are not already deep in
        // unrolling
        boolean canUnroll = unrollDepth < 1 && isSimpleBody(ctx.whileStatement().statement());
        int factor = canUnroll ? WHILE_UNROLL_FACTOR : 1;
        if (canUnroll) {
            if (ctx.whileStatement().statement().getText().length() > 200)
                factor = 2;
        }

        currentMethodVisitor.visitLabel(startLabel);

        if (factor > 1) {
            unrollDepth++;
            Label[] condLabels = new Label[factor];
            condLabels[0] = startLabel;
            for (int j = 1; j < factor; j++)
                condLabels[j] = new Label();

            String preservedLabel = currentLabel;
            for (int j = 0; j < factor; j++) {
                if (j > 0)
                    currentMethodVisitor.visitLabel(condLabels[j]);

                visit(ctx.whileStatement().expression());
                currentMethodVisitor.visitJumpInsn(Opcodes.IFEQ, endLabel);

                Label continueTarget = (j < factor - 1) ? condLabels[j + 1] : startLabel;
                loopStack.push(new LoopContext(continueTarget, endLabel, finallyStack.size(), preservedLabel));
                if (symbolTable != null)
                    symbolTable.enterScope();
                visit(ctx.whileStatement().statement());
                if (symbolTable != null)
                    symbolTable.exitScope();
                loopStack.pop();
            }
            currentLabel = null; // Consume
            currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, startLabel);
            currentMethodVisitor.visitLabel(endLabel);
            unrollDepth--;
        } else {
            visit(ctx.whileStatement().expression());
            currentMethodVisitor.visitJumpInsn(Opcodes.IFEQ, endLabel);

            loopStack.push(new LoopContext(startLabel, endLabel, finallyStack.size(), currentLabel));
            currentLabel = null; // Consume label
            visit(ctx.whileStatement().statement());
            loopStack.pop();

            currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, startLabel);
            currentMethodVisitor.visitLabel(endLabel);
        }

        return null;
    }

    private boolean isSimpleBody(OceanParser.StatementContext ctx) {
        if (ctx == null)
            return true;
        return !containsComplexLogic(ctx);
    }

    private boolean containsComplexLogic(ParseTree node) {
        if (node instanceof OceanParser.WhileStmtContext ||
                node instanceof OceanParser.ForStmtContext ||
                node instanceof OceanParser.TryStatementContext) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsComplexLogic(node.getChild(i)))
                return true;
        }
        return false;
    }

    @Override
    public Object visitBlock(OceanParser.BlockContext ctx) {
        if (symbolTable != null) {
            symbolTable.enterScope();
        }

        for (OceanParser.StatementContext stmt : ctx.statement()) {
            visit(stmt);
            if (isAlwaysTerminating(stmt)) {
                break; // Dead code elimination: stop visiting unreachable statements
            }
        }
        if (symbolTable != null) {
            symbolTable.exitScope();
        }
        return null;
    }

    @Override
    public Object visitForStmt(OceanParser.ForStmtContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return null;
        OceanParser.ForStatementContext forStmtCtx = ctx.forStatement();
        OceanParser.ForControlContext forCtx = forStmtCtx.forControl();

        if (symbolTable != null) {
            symbolTable.enterScope();
        }

        if (forCtx != null) {
            // for (type id from x to y with increasing/decreasing z)
            String varName = forCtx.anyId().getText();
            String typeName = forCtx.type() != null ? forCtx.type().getText() : "variable";
            String startType = inferType(forCtx.expression(0));

            String typeDesc;
            if (typeName.equals("variable") || typeName.equals("var") || typeName.equals("value")) {
                typeDesc = startType != null ? startType : OceanTypeSystem.OBJECT_DESC;
            } else {
                typeDesc = getTypeDescriptor(typeName);
            }

            boolean isBigDecimal = typeDesc.equals("Ljava/math/BigDecimal;");
            boolean isLong = typeDesc.equals("J");
            boolean isDouble = typeDesc.equals("D");
            boolean isFloat = typeDesc.equals("F");

            int varIndex = symbolTable.declareVariable(varName, typeDesc);

            // 1. Initial value
            Object startResult = visit(forCtx.expression(0));
            if (isBigDecimal) {
                ensureBigDecimal((startResult instanceof String) ? (String) startResult : startType);
            } else {
                emitNumericCast((startResult instanceof String) ? (String) startResult : startType, typeDesc);
            }
            currentMethodVisitor.visitVarInsn(getStoreOpcode(typeDesc), varIndex);

            // 2. Limit
            String limitType = inferType(forCtx.expression(1));
            Object limitResult = visit(forCtx.expression(1));
            if (isBigDecimal) {
                ensureBigDecimal((limitResult instanceof String) ? (String) limitResult : limitType);
            } else {
                emitNumericCast((limitResult instanceof String) ? (String) limitResult : limitType, typeDesc);
            }
            int limitVar = symbolTable.declareVariable("$limit_" + forCtx.getStart().getStartIndex(), typeDesc);
            currentMethodVisitor.visitVarInsn(getStoreOpcode(typeDesc), limitVar);

            Label startLabel = new Label();
            Label endLabel = new Label();
            Label continueLabel = new Label();

            // Direction: with increasing or with decreasing (mandatory in grammar)
            boolean isIncreasing = forCtx.DECREASING() == null;

            currentMethodVisitor.visitLabel(startLabel);

            // 3. Comparison
            currentMethodVisitor.visitVarInsn(getLoadOpcode(typeDesc), varIndex);
            currentMethodVisitor.visitVarInsn(getLoadOpcode(typeDesc), limitVar);

            if (isBigDecimal) {
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "compareTo",
                        "(Ljava/math/BigDecimal;)I", false);
                if (isIncreasing) {
                    currentMethodVisitor.visitJumpInsn(Opcodes.IFGE, endLabel);
                } else {
                    currentMethodVisitor.visitJumpInsn(Opcodes.IFLE, endLabel);
                }
            } else if (isLong) {
                currentMethodVisitor.visitInsn(Opcodes.LCMP);
                if (isIncreasing) {
                    currentMethodVisitor.visitJumpInsn(Opcodes.IFGE, endLabel);
                } else {
                    currentMethodVisitor.visitJumpInsn(Opcodes.IFLE, endLabel);
                }
            } else if (isDouble) {
                currentMethodVisitor.visitInsn(Opcodes.DCMPG);
                if (isIncreasing) {
                    currentMethodVisitor.visitJumpInsn(Opcodes.IFGE, endLabel);
                } else {
                    currentMethodVisitor.visitJumpInsn(Opcodes.IFLE, endLabel);
                }
            } else if (isFloat) {
                currentMethodVisitor.visitInsn(Opcodes.FCMPG);
                if (isIncreasing) {
                    currentMethodVisitor.visitJumpInsn(Opcodes.IFGE, endLabel);
                } else {
                    currentMethodVisitor.visitJumpInsn(Opcodes.IFLE, endLabel);
                }
            } else {
                if (isIncreasing) {
                    currentMethodVisitor.visitJumpInsn(Opcodes.IF_ICMPGE, endLabel);
                } else {
                    currentMethodVisitor.visitJumpInsn(Opcodes.IF_ICMPLE, endLabel);
                }
            }

            loopStack.push(new LoopContext(continueLabel, endLabel, finallyStack.size(), currentLabel));
            currentLabel = null;
            if (forStmtCtx.statement() != null) {
                visit(forStmtCtx.statement());
            }

            if (!loopStack.isEmpty())
                loopStack.pop();

            currentMethodVisitor.visitLabel(continueLabel);

            // 4. Increment (Step is mandatory in from-to loop now)
            currentMethodVisitor.visitVarInsn(getLoadOpcode(typeDesc), varIndex);
            String stepType = inferType(forCtx.expression(2));
            visit(forCtx.expression(2));
            if (isBigDecimal) {
                ensureBigDecimal(stepType);
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal",
                        isIncreasing ? "add" : "subtract", "(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;", false);
            } else {
                emitNumericCast(stepType, typeDesc);
                if (isLong) {
                    currentMethodVisitor.visitInsn(isIncreasing ? Opcodes.LADD : Opcodes.LSUB);
                } else if (isDouble) {
                    currentMethodVisitor.visitInsn(isIncreasing ? Opcodes.DADD : Opcodes.DSUB);
                } else if (isFloat) {
                    currentMethodVisitor.visitInsn(isIncreasing ? Opcodes.FADD : Opcodes.FSUB);
                } else {
                    currentMethodVisitor.visitInsn(isIncreasing ? Opcodes.IADD : Opcodes.ISUB);
                }
            }
            currentMethodVisitor.visitVarInsn(getStoreOpcode(typeDesc), varIndex);

            currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, startLabel);
            currentMethodVisitor.visitLabel(endLabel);
            if (symbolTable != null) {
                symbolTable.exitScope();
            }
        } else {
            // for (element in collection)
            String varName = forStmtCtx.anyId().getText();
            String typeName = forStmtCtx.type() != null ? forStmtCtx.type().getText() : ("variable");
            String expType = inferType(forStmtCtx.expression());
            String iterTypeDesc = null;

            if (typeName.equals("variable") || typeName.equals("var") || typeName.equals("value")) {
                if (expType != null && expType.startsWith("[")) {
                    iterTypeDesc = expType.substring(1);
                } else {
                    iterTypeDesc = OceanTypeSystem.OBJECT_DESC;
                }
            } else {
                iterTypeDesc = getTypeDescriptor(typeName);
            }

            int varIndex = symbolTable.declareVariable(varName, iterTypeDesc);
            visit(forStmtCtx.expression());
            Label startLabel = new Label();
            Label endLabel = new Label();
            Label continueLabel = new Label();
            loopStack.push(new LoopContext(continueLabel, endLabel, finallyStack.size(), currentLabel));
            currentLabel = null; // Consume label
            if (expType != null && expType.startsWith("[")) {
                int arrVar = symbolTable.declareVariable("$arr_" + forStmtCtx.getStart().getStartIndex(), expType);
                currentMethodVisitor.visitVarInsn(Opcodes.ASTORE, arrVar);
                int indexVar = symbolTable.declareVariable("$index_" + forStmtCtx.getStart().getStartIndex(), "I");
                currentMethodVisitor.visitInsn(Opcodes.ICONST_0);
                currentMethodVisitor.visitVarInsn(Opcodes.ISTORE, indexVar);
                currentMethodVisitor.visitLabel(startLabel);
                currentMethodVisitor.visitVarInsn(Opcodes.ILOAD, indexVar);
                currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, arrVar);
                currentMethodVisitor.visitInsn(Opcodes.ARRAYLENGTH);
                currentMethodVisitor.visitJumpInsn(Opcodes.IF_ICMPGE, endLabel);
                currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, arrVar);
                currentMethodVisitor.visitVarInsn(Opcodes.ILOAD, indexVar);
                currentMethodVisitor.visitInsn(getArrayOpcode(expType, true));
                if (expType.startsWith("[L") || expType.startsWith("[[")) {
                    String targetInternal = iterTypeDesc.startsWith("L")
                            ? iterTypeDesc.substring(1, iterTypeDesc.length() - 1)
                            : iterTypeDesc;
                    currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, stripGenerics(targetInternal));
                    currentMethodVisitor.visitVarInsn(Opcodes.ASTORE, varIndex);
                } else if (expType.equals("[I") || expType.equals("[Z")) {
                    currentMethodVisitor.visitVarInsn(Opcodes.ISTORE, varIndex);
                } else if (expType.equals("[D")) {
                    currentMethodVisitor.visitVarInsn(Opcodes.DSTORE, varIndex);
                } else if (expType.equals("[F")) {
                    currentMethodVisitor.visitVarInsn(Opcodes.FSTORE, varIndex);
                } else if (expType.equals("[J")) {
                    currentMethodVisitor.visitVarInsn(Opcodes.LSTORE, varIndex);
                }
                visit(forStmtCtx.statement());
                currentMethodVisitor.visitLabel(continueLabel);
                currentMethodVisitor.visitIincInsn(indexVar, 1);
                currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, startLabel);
            } else {
                int iterVar = symbolTable.declareVariable("$iter_" + forStmtCtx.getStart().getStartIndex(),
                        "Ljava/util/Iterator;");
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Iterable", "iterator",
                        "()Ljava/util/Iterator;", true);
                currentMethodVisitor.visitVarInsn(Opcodes.ASTORE, iterVar);
                currentMethodVisitor.visitLabel(startLabel);
                currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, iterVar);
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z",
                        true);
                currentMethodVisitor.visitJumpInsn(Opcodes.IFEQ, endLabel);
                currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, iterVar);
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next",
                        "()Ljava/lang/Object;", true);
                if (isPrimitive(iterTypeDesc)) {
                    emitUnboxToPrimitive(iterTypeDesc);
                } else {
                    currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST,
                            iterTypeDesc.substring(1, iterTypeDesc.length() - 1));
                }
                currentMethodVisitor.visitVarInsn(getStoreOpcode(iterTypeDesc), varIndex);
                visit(forStmtCtx.statement());
                currentMethodVisitor.visitLabel(continueLabel);
                currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, startLabel);
            }
            currentMethodVisitor.visitLabel(endLabel);
            if (!loopStack.isEmpty())
                loopStack.pop();
            if (symbolTable != null) {
                symbolTable.exitScope();
            }
        }
        return null;
    }

    @Override
    public Object visitStopStmt(OceanParser.StopStmtContext ctx) {

        if (!loopStack.isEmpty()) {
            String targetLabel = ctx.anyId() != null ? ctx.anyId().getText() : null;
            LoopContext target = null;
            if (targetLabel == null) {
                target = loopStack.peek();
            } else {
                for (LoopContext lc : loopStack) {
                    if (targetLabel.equals(lc.label)) {
                        target = lc;
                        break;
                    }
                }
            }

            if (target != null) {
                int toExecute = finallyStack.size() - target.finallyStackSize;
                int count = 0;
                for (Runnable fin : finallyStack) {
                    if (count >= toExecute)
                        break;
                    fin.run();
                    count++;
                }
                currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, target.breakLabel);
            } else if (targetLabel != null) {
                reportError(ctx, "Label '" + targetLabel + "' not found.");
            }
        }
        return null;
    }

    @Override
    public Object visitSkipStmt(OceanParser.SkipStmtContext ctx) {
        if (!loopStack.isEmpty()) {
            String targetLabel = ctx.anyId() != null ? ctx.anyId().getText() : null;
            LoopContext target = null;
            if (targetLabel == null) {
                target = loopStack.peek();
            } else {
                for (LoopContext lc : loopStack) {
                    if (targetLabel.equals(lc.label)) {
                        target = lc;
                        break;
                    }
                }
            }

            if (target != null && target.continueLabel != null) {
                int toExecute = finallyStack.size() - target.finallyStackSize;
                int count = 0;
                for (Runnable fin : finallyStack) {
                    if (count >= toExecute)
                        break;
                    fin.run();
                    count++;
                }
                currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, target.continueLabel);
            } else if (targetLabel != null) {
                reportError(ctx, "Label '" + targetLabel + "' not found or does not support skip.");
            }
        }
        return null;
    }

    @Override
    public Object visitLabeledStmt(OceanParser.LabeledStmtContext ctx) {
        String label = ctx.anyId().getText();
        String oldLabel = currentLabel;
        currentLabel = label;
        visit(ctx.statement());
        currentLabel = oldLabel;
        return null;
    }

    @Override
    public Object visitSuperStmt(OceanParser.SuperStmtContext ctx) {
        if (currentMethodVisitor == null)
            return null;
        currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        StringBuilder desc = new StringBuilder("(");
        if (ctx.argumentList() != null) {
            for (OceanParser.ExpressionContext arg : getArgumentExpressions(ctx.argumentList())) {
                visit(arg);
                desc.append(inferType(arg));
            }
        }
        desc.append(")V");
        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, currentSuperName, "<init>", desc.toString(), false);
        return null;
    }

    @Override
    public Object visitSwitchStmt(OceanParser.SwitchStmtContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return null;
        OceanParser.SwitchStatementContext swCtx = ctx.switchStatement();
        Label defaultLabel = new Label();
        Label endLabel = new Label();
        List<OceanParser.SwitchCaseContext> cases = swCtx.switchCase();

        String switchType = inferType(swCtx.expression());
        boolean isEnumSwitch = isEnumType(switchType);

        // Detect if this is a string switch
        boolean isStringSwitch = OceanTypeSystem.STRING_DESC.equals(switchType);
        if (!isStringSwitch && !isEnumSwitch) {
            for (OceanParser.SwitchCaseContext c : cases) {
                String caseText = c.switchLabel() != null ? c.switchLabel().getText() : "";
                if ((caseText.startsWith("\"") && caseText.endsWith("\""))
                        || (caseText.startsWith("$\"") && caseText.endsWith("\""))) {
                    isStringSwitch = true;
                    break;
                }
            }
        }

        if (isEnumSwitch) {
            emitEnumSwitch(swCtx, cases, defaultLabel, endLabel);
        } else if (isStringSwitch) {
            emitStringSwitch(swCtx, cases, defaultLabel, endLabel);
        } else {
            emitIntSwitch(swCtx, cases, defaultLabel, endLabel);
        }
        return null;
    }

    private boolean isEnumType(String typeDesc) {
        if (typeDesc == null || !typeDesc.startsWith("L") || !typeDesc.endsWith(";"))
            return false;
        String internalName = typeDesc.substring(1, typeDesc.length() - 1);
        if (globalSuperClassRegistry.containsKey(internalName)) {
            return "java/lang/Enum".equals(globalSuperClassRegistry.get(internalName));
        }
        try {
            Class<?> cls = Class.forName(internalName.replace("/", "."));
            return cls.isEnum();
        } catch (Exception e) {
            return false;
        }
    }

    private void emitIntSwitch(OceanParser.SwitchStatementContext swCtx,
            List<OceanParser.SwitchCaseContext> cases,
            Label defaultLabel, Label endLabel) {
        visit(swCtx.expression());

        class CaseInfo {
            final int key;
            final Label label;
            final OceanParser.SwitchCaseContext ctx;

            CaseInfo(int k, Label l, OceanParser.SwitchCaseContext c) {
                key = k;
                label = l;
                ctx = c;
            }
        }

        List<CaseInfo> sortedCases = new ArrayList<>();
        for (OceanParser.SwitchCaseContext c : cases) {
            int key;
            String caseText = c.switchLabel() != null ? c.switchLabel().getText() : "";
            // Support char literal in case
            if (caseText.startsWith("'") && caseText.endsWith("'") && caseText.length() >= 3) {
                String inner = caseText.substring(1, caseText.length() - 1);
                key = inner.startsWith("\\") ? ConstantFolder.unescapeString(inner).charAt(0) : inner.charAt(0);
            } else {
                try {
                    key = Integer.parseInt(caseText);
                } catch (NumberFormatException e) {
                    reportError(c, "Switch cases support integer, char, or string constants.");
                    key = 0;
                }
            }
            sortedCases.add(new CaseInfo(key, new Label(), c));
        }

        sortedCases.sort(Comparator.comparingInt(c -> c.key));

        int[] keys = new int[sortedCases.size()];
        Label[] labels = new Label[sortedCases.size()];
        for (int i = 0; i < sortedCases.size(); i++) {
            keys[i] = sortedCases.get(i).key;
            labels[i] = sortedCases.get(i).label;
        }

        currentMethodVisitor.visitLookupSwitchInsn(defaultLabel, keys, labels);
        loopStack.push(new LoopContext(null, endLabel, finallyStack.size(), currentLabel));
        currentLabel = null; // Consume label

        for (CaseInfo ci : sortedCases) {
            currentMethodVisitor.visitLabel(ci.label);
            for (OceanParser.StatementContext stmt : ci.ctx.statement())
                visit(stmt);
            currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
        }
        currentMethodVisitor.visitLabel(defaultLabel);
        if (swCtx.defaultCase() != null) {
            for (OceanParser.StatementContext stmt : swCtx.defaultCase().statement())
                visit(stmt);
        }
        currentMethodVisitor.visitLabel(endLabel);
        if (!loopStack.isEmpty())
            loopStack.pop();
    }

    /**
     * String switch using hashCode() + equals() pattern (same as javac).
     * switch (str) { case "a": ... case "b": ... }
     * becomes: switch (str.hashCode()) → then equals() to verify
     */
    private void emitStringSwitch(OceanParser.SwitchStatementContext swCtx,
            List<OceanParser.SwitchCaseContext> cases,
            Label defaultLabel, Label endLabel) {
        // Evaluate expression and store in temp
        visit(swCtx.expression());
        int tempIndex = symbolTable.nextLocalIndex();
        currentMethodVisitor.visitVarInsn(Opcodes.ASTORE, tempIndex);

        // Build case string → label mapping
        class StringCaseInfo {
            final String value;
            final int hashCode;
            final Label matchLabel;
            final OceanParser.SwitchCaseContext ctx;

            StringCaseInfo(String v, Label l, OceanParser.SwitchCaseContext c) {
                value = v;
                hashCode = v.hashCode();
                matchLabel = l;
                ctx = c;
            }
        }

        List<StringCaseInfo> stringCases = new ArrayList<>();
        for (OceanParser.SwitchCaseContext c : cases) {
            String raw = c.switchLabel() != null ? c.switchLabel().getText() : "";
            String val;
            if (raw.startsWith("\"") && raw.endsWith("\"")) {
                val = ConstantFolder.unescapeString(raw.substring(1, raw.length() - 1));
            } else {
                val = raw;
            }
            stringCases.add(new StringCaseInfo(val, new Label(), c));
        }

        // Group by hashCode (hash collisions possible)
        Map<Integer, List<StringCaseInfo>> byHash = new java.util.TreeMap<>();
        for (StringCaseInfo sci : stringCases) {
            byHash.computeIfAbsent(sci.hashCode, k -> new CopyOnWriteArrayList<>()).add(sci);
        }

        // Emit: tempVar.hashCode() → lookupswitch by hash
        currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, tempIndex);
        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false);

        int[] hashKeys = new int[byHash.size()];
        Label[] hashLabels = new Label[byHash.size()];
        int idx = 0;
        for (Map.Entry<Integer, List<StringCaseInfo>> entry : byHash.entrySet()) {
            hashKeys[idx] = entry.getKey();
            hashLabels[idx] = new Label();
            idx++;
        }
        currentMethodVisitor.visitLookupSwitchInsn(defaultLabel, hashKeys, hashLabels);

        loopStack.push(new LoopContext(null, endLabel, finallyStack.size(), currentLabel));
        currentLabel = null; // Consume label

        // For each hash bucket, emit equals() checks
        idx = 0;
        for (Map.Entry<Integer, List<StringCaseInfo>> entry : byHash.entrySet()) {
            currentMethodVisitor.visitLabel(hashLabels[idx]);
            for (StringCaseInfo sci : entry.getValue()) {
                currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, tempIndex);
                currentMethodVisitor.visitLdcInsn(sci.value);
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                        "(Ljava/lang/Object;)Z", false);
                currentMethodVisitor.visitJumpInsn(Opcodes.IFNE, sci.matchLabel);
            }
            currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, defaultLabel);
            idx++;
        }

        // Emit case bodies
        for (StringCaseInfo sci : stringCases) {
            currentMethodVisitor.visitLabel(sci.matchLabel);
            for (OceanParser.StatementContext stmt : sci.ctx.statement())
                visit(stmt);
            currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
        }

        currentMethodVisitor.visitLabel(defaultLabel);
        if (swCtx.defaultCase() != null) {
            for (OceanParser.StatementContext stmt : swCtx.defaultCase().statement())
                visit(stmt);
        }
        currentMethodVisitor.visitLabel(endLabel);
        if (!loopStack.isEmpty())
            loopStack.pop();
    }

    private void emitEnumSwitch(OceanParser.SwitchStatementContext swCtx,
            List<OceanParser.SwitchCaseContext> cases,
            Label defaultLabel, Label endLabel) {
        visit(swCtx.expression());
        int tempIndex = symbolTable.nextLocalIndex();
        currentMethodVisitor.visitVarInsn(Opcodes.ASTORE, tempIndex);

        loopStack.push(new LoopContext(null, endLabel, finallyStack.size(), currentLabel));
        currentLabel = null; // Consume label

        for (OceanParser.SwitchCaseContext c : cases) {
            Label matchLabel = new Label();
            Label nextCaseLabel = new Label();

            currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, tempIndex);
            if (c.switchLabel() != null) {
                // simple fallback
            }
            currentMethodVisitor.visitJumpInsn(Opcodes.IF_ACMPEQ, matchLabel);
            currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, nextCaseLabel);

            currentMethodVisitor.visitLabel(matchLabel);
            for (OceanParser.StatementContext stmt : c.statement())
                visit(stmt);
            currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);

            currentMethodVisitor.visitLabel(nextCaseLabel);
        }

        currentMethodVisitor.visitLabel(defaultLabel);
        if (swCtx.defaultCase() != null) {
            for (OceanParser.StatementContext stmt : swCtx.defaultCase().statement())
                visit(stmt);
        }
        currentMethodVisitor.visitLabel(endLabel);
        if (!loopStack.isEmpty())
            loopStack.pop();
    }

    @Override
    public Object visitBlockStmt(OceanParser.BlockStmtContext ctx) {
        return visitBlock(ctx.block());
    }

    @Override
    public Object visitExprStmt(OceanParser.ExprStmtContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return null;
        OceanParser.ExpressionContext expr = ctx.expressionStatement().expression();
        if (isSideEffectFree(expr)) {
            return null; // Dead code elimination
        }
        Object result = visit(expr);
        String type = (result instanceof String) ? (String) result : inferType(expr);
        if (type != null && !type.equals("V")) {
            if (type.equals("D") || type.equals("J"))
                currentMethodVisitor.visitInsn(Opcodes.POP2);
            else
                currentMethodVisitor.visitInsn(Opcodes.POP);
        }
        return null;
    }

    private boolean isSideEffectFree(OceanParser.ExpressionContext expr) {
        if (expr instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) expr).primary();
            if (p instanceof OceanParser.ParenthesizedPrimaryContext) {
                return isSideEffectFree(((OceanParser.ParenthesizedPrimaryContext) p).expression());
            }
            return p instanceof OceanParser.NumberPrimaryContext ||
                    p instanceof OceanParser.StringPrimaryContext ||
                    p instanceof OceanParser.TruePrimaryContext ||
                    p instanceof OceanParser.FalsePrimaryContext ||
                    p instanceof OceanParser.NullPrimaryContext ||
                    p instanceof OceanParser.IdPrimaryContext ||
                    p instanceof OceanParser.ThisRefPrimaryContext ||
                    p instanceof OceanParser.SuperRefPrimaryContext;
        }
        return false;
    }

    @Override
    public Object visitCastExpr(OceanParser.CastExprContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return null;
        String targetTypeText = ctx.type().getText();
        String targetType = getTypeDescriptor(targetTypeText);

        String oldCast = currentCastType;
        currentCastType = targetType;
        visit(ctx.expression());
        currentCastType = oldCast;

        String sourceType = inferType(ctx.expression());

        // Same type — no-op
        if (sourceType.equals(targetType))
            return targetType;

        // String → Primitive (parsing)
        if (OceanTypeSystem.STRING_DESC.equals(sourceType) && isPrimitive(targetType)) {
            switch (targetType) {
                case "I" -> currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt",
                        "(Ljava/lang/String;)I", false);
                case "D" ->
                        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "parseDouble",
                                "(Ljava/lang/String;)D", false);
                case "J" -> currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "parseLong",
                        "(Ljava/lang/String;)J", false);
                case "F" -> currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "parseFloat",
                        "(Ljava/lang/String;)F", false);
                case "Z" ->
                        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "parseBoolean",
                                "(Ljava/lang/String;)Z", false);
                default -> emitUnboxing(targetType);
            }
        }
        // Primitive → Primitive casts (widening + narrowing)
        else if (isPrimitive(sourceType) && isPrimitive(targetType)) {
            emitPrimitiveCast(sourceType, targetType);
        }
        // Primitive → Object (boxing)
        else if (isPrimitive(sourceType) && targetType.startsWith("L")) {
            emitBoxing(sourceType);
            String cleanT = stripGenerics(targetType);
            if (cleanT.endsWith("?")) cleanT = cleanT.substring(0, cleanT.length() - 1);
            if (!OceanTypeSystem.OBJECT_DESC.equals(cleanT)) {
                while (cleanT.startsWith("L") && cleanT.endsWith(";")) {
                    cleanT = cleanT.substring(1, cleanT.length() - 1);
                }
                currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, cleanT);
            }
        }
        // Object → Primitive (unboxing)
        else if (sourceType.startsWith("L") && isPrimitive(targetType)) {
            emitUnboxing(targetType);
        }
        // Object → Object (CHECKCAST)
        else if (targetType.startsWith("L") || targetType.startsWith("[")) {
            String internal = stripGenerics(targetType);
            if (internal.endsWith("?")) internal = internal.substring(0, internal.length() - 1);
            while (internal.startsWith("L") && internal.endsWith(";")) {
                internal = internal.substring(1, internal.length() - 1);
            }
            currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, internal);
        }
        return targetType;
    }

    @Override
    public Object visitLambdaExpr(OceanParser.LambdaExprContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return null;

        // Lambda SAM auto-inference: if no cast type, infer from parameter count
        if (currentCastType == null) {
            int paramCount = 0;
            if (ctx.parameterList() != null) {
                paramCount = ctx.parameterList().parameter().size();
            } else if (ctx.identifierList() != null) {
                paramCount = ctx.identifierList().anyId().size();
            }
            boolean hasReturnValue = false;
            if (ctx.expression() != null) {
                hasReturnValue = true; // Arrow expression lambda: (x) -> expr
            } else if (ctx.block() != null) {
                hasReturnValue = blockHasReturn(ctx.block());
            }
            switch (paramCount) {
                case 0:
                    currentCastType = hasReturnValue
                            ? "Ljava/util/function/Supplier;"
                            : "Ljava/lang/Runnable;";
                    break;
                case 1:
                    currentCastType = hasReturnValue
                            ? "Ljava/util/function/Function;"
                            : "Ljava/util/function/Consumer;";
                    break;
                case 2:
                    currentCastType = hasReturnValue
                            ? "Ljava/util/function/BiFunction;"
                            : "Ljava/util/function/BiConsumer;";
                    break;
                default:
                    currentCastType = "Ljava/util/function/Function;";
                    break;
            }
        }
        String[] sam = resolveSAM(currentCastType);
        if (sam == null) {
            reportError(ctx, "Target type '" + currentCastType + "' is not a functional interface.");
            return null;
        }

        String samName = sam[0];
        String samDesc = sam[1];

        // Capture analysis
        Set<String> used = new HashSet<>();
        findUsedVariablesRecursive(ctx.block() != null ? ctx.block() : ctx.expression(), used);

        List<String> capturedNames = new ArrayList<>();
        List<String> capturedTypes = new ArrayList<>();
        StringBuilder invokedType = new StringBuilder("(");

        for (String name : used) {
            String type = symbolTable.getType(name);
            if (type != null) {
                symbolTable.markCaptured(name, ctx);
                capturedNames.add(name);
                capturedTypes.add(type);
                invokedType.append(type);
            }
        }
        invokedType.append(")").append(currentCastType);

        String lambdaName = "lambda$" + currentMethodName + "$" + (lambdaCounter++);

        // Implementation descriptor: captured variables + SAM parameters
        StringBuilder implDesc = new StringBuilder("(");
        for (String ct : capturedTypes)
            implDesc.append(ct);

        List<String> samParamTypes = getParameterDescriptors(samDesc);
        for (String spt : samParamTypes) {
            implDesc.append(spt);
        }
        implDesc.append(")").append(samDesc.substring(samDesc.lastIndexOf(')') + 1));

        LambdaInfo info = new LambdaInfo();
        info.name = lambdaName;
        info.descriptor = implDesc.toString();
        info.ctx = ctx;
        info.capturedNames = capturedNames;
        info.capturedTypes = capturedTypes;
        info.isStatic = symbolTable.isStaticContext();
        info.samDesc = samDesc;
        info.samInterface = currentCastType;
        syntheticLambdas.add(info);

        // Load captured variables
        for (String name : capturedNames) {
            int idx = symbolTable.getIndex(name);
            String type = symbolTable.getType(name);
            emitLoad(idx, type);
        }

        // Emit invokedynamic
        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false);

        Handle implMethod = new Handle(info.isStatic ? Opcodes.H_INVOKESTATIC : Opcodes.H_INVOKESPECIAL,
                getCurrentClassPath(), lambdaName, info.descriptor, false);

        currentMethodVisitor.visitInvokeDynamicInsn(samName, invokedType.toString(), bsm,
                Type.getType(samDesc), implMethod, Type.getType(samDesc));

        return currentCastType;
    }

    @Override
    public Object visitMethodRefExpr(OceanParser.MethodRefExprContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return null;
        String targetType = currentCastType;
        if (targetType == null) {
            // Check if the receiver itself was cast - common parser priority issue
            if (ctx.expression() instanceof OceanParser.CastExprContext) {
                targetType = SymbolTable.getDescriptor(
                        ((OceanParser.CastExprContext) ctx.expression()).type().getText(),
                        importedClasses);
            }
        }

        if (targetType == null) {
            // Still null? Try one more thing: look up the tree
            ParseTree parent = ctx.getParent();
            while ((parent instanceof OceanParser.PrimaryExprContext
                    || parent instanceof OceanParser.ParenthesizedPrimaryContext
                    || parent instanceof OceanParser.ExpressionContext)) {
                if (parent instanceof OceanParser.CastExprContext) {
                    targetType = SymbolTable.getDescriptor(((OceanParser.CastExprContext) parent).type().getText(),
                            importedClasses);
                    break;
                }
                parent = parent.getParent();
            }
        }

        if (targetType == null) {
            reportError(ctx, "Method references must be cast to a functional interface.");
            return null;
        }
        String[] sam = resolveSAM(targetType);
        if (sam == null) {
            reportError(ctx, "Target type '" + targetType + "' is not a functional interface.");
            return null;
        }

        String samName = sam[0];
        String samDesc = sam[1];

        String ownerDesc = inferType(ctx.expression());
        String owner = ownerDesc.startsWith("L") ? ownerDesc.substring(1, ownerDesc.length() - 1) : "java/lang/Object";
        String methodName = ctx.anyId().getText();

        List<String> argTypes = getParameterDescriptors(samDesc);
        MethodInfo info = resolveMethodInfo(owner, methodName, argTypes);
        String implDesc = (info != null) ? info.descriptor : samDesc;
        boolean isStatic = (info != null) ? info.isStatic : false;

        if (!isStatic) {
            visit(ctx.expression());
        }

        Handle bsm = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false);

        Handle implMethod = new Handle(isStatic ? Opcodes.H_INVOKESTATIC : Opcodes.H_INVOKEVIRTUAL, owner, methodName,
                implDesc, false);

        String invokedType = isStatic ? "()" + targetType : "(" + ownerDesc + ")" + targetType;

        currentMethodVisitor.visitInvokeDynamicInsn(samName, invokedType, bsm,
                Type.getType(samDesc), implMethod, Type.getType(samDesc));

        return targetType;
    }

    @Override
    public Object visitPrimaryExpr(OceanParser.PrimaryExprContext ctx) {
        return visit(ctx.primary());
    }

    @Override
    public Object visitMulDivModExpr(OceanParser.MulDivModExprContext ctx) {
        Object folded = foldConstant(ctx);
        if (folded instanceof Integer) {
            emitIntConstant((Integer) folded);
            return "I";
        } else if (folded instanceof Long) {
            currentMethodVisitor.visitLdcInsn(folded);
            return "J";
        } else if (folded instanceof BigDecimal) {
            emitBigDecimalInsn(folded.toString());
            return "Ljava/math/BigDecimal;";
        } else if (folded instanceof Double) {
            currentMethodVisitor.visitLdcInsn(folded);
            return "D";
        }

        String leftType = inferType(ctx.expression(0));
        String rightType = inferType(ctx.expression(1));
        String op = ctx.op.getText();

        // Strength Reduction: x * powerOfTwo -> x << n, x / powerOfTwo -> x >> n
        if (leftType.equals("I") && rightType.equals("I")) {
            if (op.equals("*")) {
                if (isConstantInt(ctx.expression(1))) {
                    int val = getConstantInt(ctx.expression(1));
                    if (val > 0 && (val & (val - 1)) == 0) {
                        visit(ctx.expression(0));
                        emitIntConstant(Integer.numberOfTrailingZeros(val));
                        currentMethodVisitor.visitInsn(Opcodes.ISHL);
                        return null;
                    }
                } else if (isConstantInt(ctx.expression(0))) {
                    int val = getConstantInt(ctx.expression(0));
                    if (val > 0 && (val & (val - 1)) == 0) {
                        visit(ctx.expression(1));
                        emitIntConstant(Integer.numberOfTrailingZeros(val));
                        currentMethodVisitor.visitInsn(Opcodes.ISHL);
                        return null;
                    }
                }
            } else if (op.equals("/")) {
                if (isConstantInt(ctx.expression(1))) {
                    int val = getConstantInt(ctx.expression(1));
                    if (val > 0 && (val & (val - 1)) == 0) {
                        visit(ctx.expression(0));
                        emitIntConstant(Integer.numberOfTrailingZeros(val));
                        currentMethodVisitor.visitInsn(Opcodes.ISHR);
                        return null;
                    }
                }
            }
        }

        String targetType = determineCommonType(leftType, rightType);
        visit(ctx.expression(0));
        emitCoerceTo(leftType, targetType);
        visit(ctx.expression(1));
        emitCoerceTo(rightType, targetType);

        if (targetType.equals("Ljava/math/BigDecimal;")) {
            if (op.equals("*")) {
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "multiply",
                        "(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;", false);
            } else if (op.equals("/")) {
                // Use MathContext.DECIMAL128 for high precision division by default
                currentMethodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/math/MathContext", "DECIMAL128",
                        "Ljava/math/MathContext;");
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "divide",
                        "(Ljava/math/BigDecimal;Ljava/math/MathContext;)Ljava/math/BigDecimal;", false);
            } else {
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "remainder",
                        "(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;", false);
            }
        } else {
            emitPrimitiveArithmetic(targetType, op);
        }
        return null;
    }

    private void emitPrimitiveArithmetic(String type, String op) {
        switch (type) {
            case "D" -> {
                switch (op) {
                    case "+" -> currentMethodVisitor.visitInsn(Opcodes.DADD);
                    case "-" -> currentMethodVisitor.visitInsn(Opcodes.DSUB);
                    case "*" -> currentMethodVisitor.visitInsn(Opcodes.DMUL);
                    case "/" -> currentMethodVisitor.visitInsn(Opcodes.DDIV);
                    default -> currentMethodVisitor.visitInsn(Opcodes.DREM);
                }
            }
            case "J" -> {
                switch (op) {
                    case "+" -> currentMethodVisitor.visitInsn(Opcodes.LADD);
                    case "-" -> currentMethodVisitor.visitInsn(Opcodes.LSUB);
                    case "*" -> currentMethodVisitor.visitInsn(Opcodes.LMUL);
                    case "/" -> currentMethodVisitor.visitInsn(Opcodes.LDIV);
                    default -> currentMethodVisitor.visitInsn(Opcodes.LREM);
                }
            }
            case "F" -> {
                switch (op) {
                    case "+" -> currentMethodVisitor.visitInsn(Opcodes.FADD);
                    case "-" -> currentMethodVisitor.visitInsn(Opcodes.FSUB);
                    case "*" -> currentMethodVisitor.visitInsn(Opcodes.FMUL);
                    case "/" -> currentMethodVisitor.visitInsn(Opcodes.FDIV);
                    default -> currentMethodVisitor.visitInsn(Opcodes.FREM);
                }
            }
            default -> {
                switch (op) {
                    case "+" -> currentMethodVisitor.visitInsn(Opcodes.IADD);
                    case "-" -> currentMethodVisitor.visitInsn(Opcodes.ISUB);
                    case "*" -> currentMethodVisitor.visitInsn(Opcodes.IMUL);
                    case "/" -> currentMethodVisitor.visitInsn(Opcodes.IDIV);
                    default -> currentMethodVisitor.visitInsn(Opcodes.IREM);
                }
            }
        }
    }

    @Override
    public Object visitAddSubExpr(OceanParser.AddSubExprContext ctx) {
        Object folded = foldConstant(ctx);
        if (folded instanceof Integer) {
            emitIntConstant((Integer) folded);
            return "I";
        } else if (folded instanceof Long) {
            currentMethodVisitor.visitLdcInsn(folded);
            return "J";
        } else if (folded instanceof BigDecimal) {
            emitBigDecimalInsn(folded.toString());
            return "Ljava/math/BigDecimal;";
        } else if (folded instanceof Double) {
            currentMethodVisitor.visitLdcInsn(folded);
            return "D";
        } else if (folded instanceof String) {
            currentMethodVisitor.visitLdcInsn(folded);
            return OceanTypeSystem.STRING_DESC;
        }
        String op = ctx.op.getText();
        String leftType = inferType(ctx.expression(0));
        String rightType = inferType(ctx.expression(1));

        if (op.equals("+")) {
            boolean stringConcat = OceanTypeSystem.STRING_DESC.equals(leftType) || OceanTypeSystem.STRING_DESC.equals(rightType);
            if (stringConcat) {
                List<OceanParser.ExpressionContext> chain = new ArrayList<>();
                collectStringChain(ctx, chain);

                StringBuilder recipe = new StringBuilder();
                StringBuilder descriptor = new StringBuilder("(");

                for (OceanParser.ExpressionContext part : chain) {
                    Object foldedPart = ConstantFolder.fold(part);
                    if (foldedPart != null) {
                        recipe.append(foldedPart.toString().replace("\u0001", " ").replace("\u0002", " "));
                    } else {
                        visit(part);
                        String partDesc = inferType(part);
                        // Primitive to wrapper types for Indy
                        if (partDesc.equals("V")) {
                            currentMethodVisitor.visitInsn(Opcodes.ACONST_NULL);
                            partDesc = OceanTypeSystem.OBJECT_DESC;
                        }
                        descriptor.append(partDesc);
                        recipe.append("\u0001");
                    }
                }
                descriptor.append(")Ljava/lang/String;");

                Handle bsm = new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/StringConcatFactory",
                        "makeConcatWithConstants",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                        false);

                currentMethodVisitor.visitInvokeDynamicInsn("makeConcatWithConstants",
                        descriptor.toString(), bsm, recipe.toString());

                return OceanTypeSystem.STRING_DESC;
            }
        }

        String targetType = determineCommonType(leftType, rightType);
        visit(ctx.expression(0));
        emitCoerceTo(leftType, targetType);
        visit(ctx.expression(1));
        emitCoerceTo(rightType, targetType);

        if (targetType.equals("Ljava/math/BigDecimal;")) {
            String methodName = op.equals("+") ? "add" : "subtract";
            currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", methodName,
                    "(Ljava/math/BigDecimal;)Ljava/math/BigDecimal;", false);
        } else {
            emitPrimitiveArithmetic(targetType, op);
        }
        return null;
    }

    @Override
    public Object visitShiftExpr(OceanParser.ShiftExprContext ctx) {
        String type0 = inferType(ctx.expression(0));
        String type1 = inferType(ctx.expression(1));

        visit(ctx.expression(0));
        // First operand stays as is (or promoted if needed, but shift works on I or J)

        visit(ctx.expression(1));
        emitCoerceTo(type1, "I"); // Shift amount MUST be int

        String op = ctx.op.getText();
        if ("J".equals(type0)) {
            switch (op) {
                case "<<" -> currentMethodVisitor.visitInsn(Opcodes.LSHL);
                case ">>" -> currentMethodVisitor.visitInsn(Opcodes.LSHR);
                case ">>>" -> currentMethodVisitor.visitInsn(Opcodes.LUSHR);
            }
            return "J";
        } else {
            switch (op) {
                case "<<" -> currentMethodVisitor.visitInsn(Opcodes.ISHL);
                case ">>" -> currentMethodVisitor.visitInsn(Opcodes.ISHR);
                case ">>>" -> currentMethodVisitor.visitInsn(Opcodes.IUSHR);
            }
            return "I";
        }
    }

    private void collectStringChain(OceanParser.ExpressionContext ctx, List<OceanParser.ExpressionContext> chain) {
        if (ctx instanceof OceanParser.AddSubExprContext ase) {
            if (ase.op.getText().equals("+")) {
                String leftT = inferType(ase.expression(0));
                String rightT = inferType(ase.expression(1));
                if (OceanTypeSystem.STRING_DESC.equals(leftT) || OceanTypeSystem.STRING_DESC.equals(rightT)) {
                    collectStringChain(ase.expression(0), chain);
                    collectStringChain(ase.expression(1), chain);
                    return;
                }
            }
        }
        chain.add(ctx);
    }

    @Override
    public Object visitComparisonExpr(OceanParser.ComparisonExprContext ctx) {
        Object folded = foldConstant(ctx);
        if (folded instanceof Boolean) {
            currentMethodVisitor.visitInsn((Boolean) folded ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            return null;
        }
        String leftType = inferType(ctx.expression(0));
        String rightType = inferType(ctx.expression(1));
        String op = ctx.op.getText();
        String targetType = determineCommonType(leftType, rightType);

        visit(ctx.expression(0));
        emitCoerceTo(leftType, targetType);
        visit(ctx.expression(1));
        emitCoerceTo(rightType, targetType);

        Label trueLabel = new Label();
        Label endLabel = new Label();

        switch (targetType) {
            case "Ljava/math/BigDecimal;" -> {
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "compareTo",
                        "(Ljava/math/BigDecimal;)I", false);
                int jumpOp = -1;
                jumpOp = switch (op) {
                    case "<" -> Opcodes.IFLT;
                    case ">" -> Opcodes.IFGT;
                    case "<=" -> Opcodes.IFLE;
                    case ">=" -> Opcodes.IFGE;
                    default -> jumpOp;
                };
                currentMethodVisitor.visitJumpInsn(jumpOp, trueLabel);
            }
            case "D" -> {
                currentMethodVisitor.visitInsn(Opcodes.DCMPG);
                int jumpOp = -1;
                jumpOp = switch (op) {
                    case "<" -> Opcodes.IFLT;
                    case ">" -> Opcodes.IFGT;
                    case "<=" -> Opcodes.IFLE;
                    case ">=" -> Opcodes.IFGE;
                    default -> jumpOp;
                };
                currentMethodVisitor.visitJumpInsn(jumpOp, trueLabel);
            }
            case "J" -> {
                currentMethodVisitor.visitInsn(Opcodes.LCMP);
                int jumpOp = -1;
                jumpOp = switch (op) {
                    case "<" -> Opcodes.IFLT;
                    case ">" -> Opcodes.IFGT;
                    case "<=" -> Opcodes.IFLE;
                    case ">=" -> Opcodes.IFGE;
                    default -> jumpOp;
                };
                currentMethodVisitor.visitJumpInsn(jumpOp, trueLabel);
            }
            case "F" -> {
                currentMethodVisitor.visitInsn(Opcodes.FCMPG);
                int jumpOp = -1;
                jumpOp = switch (op) {
                    case "<" -> Opcodes.IFLT;
                    case ">" -> Opcodes.IFGT;
                    case "<=" -> Opcodes.IFLE;
                    case ">=" -> Opcodes.IFGE;
                    default -> jumpOp;
                };
                currentMethodVisitor.visitJumpInsn(jumpOp, trueLabel);
            }
            default -> {
                int jumpOpCode = -1;
                jumpOpCode = switch (op) {
                    case "<" -> Opcodes.IF_ICMPLT;
                    case ">" -> Opcodes.IF_ICMPGT;
                    case "<=" -> Opcodes.IF_ICMPLE;
                    case ">=" -> Opcodes.IF_ICMPGE;
                    default -> jumpOpCode;
                };
                currentMethodVisitor.visitJumpInsn(jumpOpCode, trueLabel);
            }
        }

        currentMethodVisitor.visitInsn(Opcodes.ICONST_0);
        currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
        currentMethodVisitor.visitLabel(trueLabel);
        currentMethodVisitor.visitInsn(Opcodes.ICONST_1);
        currentMethodVisitor.visitLabel(endLabel);
        return null;
    }

    @Override
    public Object visitEqualityExpr(OceanParser.EqualityExprContext ctx) {
        Object folded = foldConstant(ctx);
        if (folded instanceof Boolean) {
            currentMethodVisitor.visitInsn((Boolean) folded ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
            return null;
        }
        String leftType = inferType(ctx.expression(0));
        String rightType = inferType(ctx.expression(1));
        String op = ctx.op.getText();

        Label trueLabel = new Label();
        Label endLabel = new Label();

        // Null-literal optimization: use IFNULL / IFNONNULL (single-operand)
        boolean leftIsNull = isNullLiteral(ctx.expression(0));
        boolean rightIsNull = isNullLiteral(ctx.expression(1));
        if (leftIsNull || rightIsNull) {
            if (leftIsNull)
                visit(ctx.expression(1));
            else
                visit(ctx.expression(0));
            int jumpOp = op.equals("==") ? Opcodes.IFNULL : Opcodes.IFNONNULL;
            currentMethodVisitor.visitJumpInsn(jumpOp, trueLabel);
        } else {
            String targetType = determineCommonType(leftType, rightType);
            Object res0 = visit(ctx.expression(0));
            String realLeftType = (res0 instanceof String) ? (String) res0 : leftType;
            emitCoerceTo(realLeftType, targetType);

            Object res1 = visit(ctx.expression(1));
            String realRightType = (res1 instanceof String) ? (String) res1 : rightType;
            emitCoerceTo(realRightType, targetType);

            if (targetType.equals("Ljava/math/BigDecimal;")) {
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "compareTo",
                        "(Ljava/math/BigDecimal;)I", false);
                int jumpOp = op.equals("==") ? Opcodes.IFEQ : Opcodes.IFNE;
                currentMethodVisitor.visitJumpInsn(jumpOp, trueLabel);
            } else if (targetType.equals(OceanTypeSystem.STRING_DESC)) {
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "equals",
                        "(Ljava/lang/Object;)Z", false);
                int jumpOp = op.equals("==") ? Opcodes.IFNE : Opcodes.IFEQ;
                currentMethodVisitor.visitJumpInsn(jumpOp, trueLabel);
            } else if (isPrimitive(targetType)) {
                switch (targetType) {
                    case "D" -> {
                        currentMethodVisitor.visitInsn(Opcodes.DCMPG);
                        int jumpOp = op.equals("==") ? Opcodes.IFEQ : Opcodes.IFNE;
                        currentMethodVisitor.visitJumpInsn(jumpOp, trueLabel);
                    }
                    case "J" -> {
                        currentMethodVisitor.visitInsn(Opcodes.LCMP);
                        int jumpOp = op.equals("==") ? Opcodes.IFEQ : Opcodes.IFNE;
                        currentMethodVisitor.visitJumpInsn(jumpOp, trueLabel);
                    }
                    case "F" -> {
                        currentMethodVisitor.visitInsn(Opcodes.FCMPG);
                        int jumpOp = op.equals("==") ? Opcodes.IFEQ : Opcodes.IFNE;
                        currentMethodVisitor.visitJumpInsn(jumpOp, trueLabel);
                    }
                    default -> {
                        int jumpOpCode = op.equals("==") ? Opcodes.IF_ICMPEQ : Opcodes.IF_ICMPNE;
                        currentMethodVisitor.visitJumpInsn(jumpOpCode, trueLabel);
                    }
                }
            } else {
                // Object reference comparison
                int jumpOpCode = op.equals("==") ? Opcodes.IF_ACMPEQ : Opcodes.IF_ACMPNE;
                currentMethodVisitor.visitJumpInsn(jumpOpCode, trueLabel);
            }
        }

        currentMethodVisitor.visitInsn(Opcodes.ICONST_0);
        currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
        currentMethodVisitor.visitLabel(trueLabel);
        currentMethodVisitor.visitInsn(Opcodes.ICONST_1);
        currentMethodVisitor.visitLabel(endLabel);
        return null;
    }

    @Override
    public Object visitBitAndExpr(OceanParser.BitAndExprContext ctx) {
        String leftType = inferType(ctx.expression(0));
        String rightType = inferType(ctx.expression(1));
        String targetType = determineCommonType(leftType, rightType);

        visit(ctx.expression(0));
        emitCoerceTo(leftType, targetType);
        visit(ctx.expression(1));
        emitCoerceTo(rightType, targetType);

        if ("J".equals(targetType)) {
            currentMethodVisitor.visitInsn(Opcodes.LAND);
        } else {
            currentMethodVisitor.visitInsn(Opcodes.IAND);
            targetType = "I";
        }
        return targetType;
    }

    @Override
    public Object visitBitXorExpr(OceanParser.BitXorExprContext ctx) {
        String leftType = inferType(ctx.expression(0));
        String rightType = inferType(ctx.expression(1));
        String targetType = determineCommonType(leftType, rightType);

        visit(ctx.expression(0));
        emitCoerceTo(leftType, targetType);
        visit(ctx.expression(1));
        emitCoerceTo(rightType, targetType);

        if ("J".equals(targetType)) {
            currentMethodVisitor.visitInsn(Opcodes.LXOR);
        } else {
            currentMethodVisitor.visitInsn(Opcodes.IXOR);
            targetType = "I";
        }
        return targetType;
    }

    @Override
    public Object visitBitOrExpr(OceanParser.BitOrExprContext ctx) {
        String leftType = inferType(ctx.expression(0));
        String rightType = inferType(ctx.expression(1));
        String targetType = determineCommonType(leftType, rightType);

        visit(ctx.expression(0));
        emitCoerceTo(leftType, targetType);
        visit(ctx.expression(1));
        emitCoerceTo(rightType, targetType);

        if ("J".equals(targetType)) {
            currentMethodVisitor.visitInsn(Opcodes.LOR);
        } else {
            currentMethodVisitor.visitInsn(Opcodes.IOR);
            targetType = "I";
        }
        return targetType;
    }

    @Override
    public Object visitLogicalAndExpr(OceanParser.LogicalAndExprContext ctx) {
        Label falseLabel = new Label();
        Label endLabel = new Label();
        Object res0 = visit(ctx.expression(0));
        String type0 = (res0 instanceof String) ? (String) res0 : inferType(ctx.expression(0));
        emitCoerceTo(type0, "Z");
        currentMethodVisitor.visitJumpInsn(Opcodes.IFEQ, falseLabel);

        Object res1 = visit(ctx.expression(1));
        String type1 = (res1 instanceof String) ? (String) res1 : inferType(ctx.expression(1));
        emitCoerceTo(type1, "Z");
        currentMethodVisitor.visitJumpInsn(Opcodes.IFEQ, falseLabel);
        currentMethodVisitor.visitInsn(Opcodes.ICONST_1);
        currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
        currentMethodVisitor.visitLabel(falseLabel);
        currentMethodVisitor.visitInsn(Opcodes.ICONST_0);
        currentMethodVisitor.visitLabel(endLabel);
        return null;
    }

    @Override
    public Object visitLogicalOrExpr(OceanParser.LogicalOrExprContext ctx) {
        Label trueLabel = new Label();
        Label endLabel = new Label();
        Object res0 = visit(ctx.expression(0));
        String type0 = (res0 instanceof String) ? (String) res0 : inferType(ctx.expression(0));
        emitCoerceTo(type0, "Z");
        currentMethodVisitor.visitJumpInsn(Opcodes.IFNE, trueLabel);

        Object res1 = visit(ctx.expression(1));
        String type1 = (res1 instanceof String) ? (String) res1 : inferType(ctx.expression(1));
        emitCoerceTo(type1, "Z");
        currentMethodVisitor.visitJumpInsn(Opcodes.IFNE, trueLabel);
        currentMethodVisitor.visitInsn(Opcodes.ICONST_0);
        currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
        currentMethodVisitor.visitLabel(trueLabel);
        currentMethodVisitor.visitInsn(Opcodes.ICONST_1);
        currentMethodVisitor.visitLabel(endLabel);
        return null;
    }

    private void emitPrimitiveCast(String from, String to) {
        switch (from + "->" + to) {
            case "I->J":
                currentMethodVisitor.visitInsn(Opcodes.I2L);
                break;
            case "I->D":
                currentMethodVisitor.visitInsn(Opcodes.I2D);
                break;
            case "I->F":
                currentMethodVisitor.visitInsn(Opcodes.I2F);
                break;
            case "I->B":
                currentMethodVisitor.visitInsn(Opcodes.I2B);
                break;
            case "I->S":
                currentMethodVisitor.visitInsn(Opcodes.I2S);
                break;
            case "I->C":
                currentMethodVisitor.visitInsn(Opcodes.I2C);
                break;
            case "J->I":
                currentMethodVisitor.visitInsn(Opcodes.L2I);
                break;
            case "J->D":
                currentMethodVisitor.visitInsn(Opcodes.L2D);
                break;
            case "J->F":
                currentMethodVisitor.visitInsn(Opcodes.L2F);
                break;
            case "D->I":
                currentMethodVisitor.visitInsn(Opcodes.D2I);
                break;
            case "D->J":
                currentMethodVisitor.visitInsn(Opcodes.D2L);
                break;
            case "D->F":
                currentMethodVisitor.visitInsn(Opcodes.D2F);
                break;
            case "F->I":
                currentMethodVisitor.visitInsn(Opcodes.F2I);
                break;
            case "F->J":
                currentMethodVisitor.visitInsn(Opcodes.F2L);
                break;
            case "F->D":
                currentMethodVisitor.visitInsn(Opcodes.F2D);
                break;
            default:
                break; // Z, B, S, C → I is a no-op on JVM
        }
    }

    private void emitUnboxing(String targetPrimitive) {
        switch (targetPrimitive) {
            case "I":
                currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I",
                        false);
                break;
            case "J":
                currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J",
                        false);
                break;
            case "D":
                currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D",
                        false);
                break;
            case "F":
                currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F",
                        false);
                break;
            default:
                break;
        }
    }

    @Override
    public Object visitUnaryExpr(OceanParser.UnaryExprContext ctx) {
        visit(ctx.expression());
        String op = ctx.op.getText();
        String type = inferType(ctx.expression());
        switch (op) {
            case "!" -> {
                if (type.startsWith("L") || type.startsWith("[")) {
                    emitUnboxToPrimitive("Z");
                }
                Label trueLabel = new Label();
                Label endLabel = new Label();
                currentMethodVisitor.visitJumpInsn(Opcodes.IFEQ, trueLabel);
                currentMethodVisitor.visitInsn(Opcodes.ICONST_0);
                currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
                currentMethodVisitor.visitLabel(trueLabel);
                currentMethodVisitor.visitInsn(Opcodes.ICONST_1);
                currentMethodVisitor.visitLabel(endLabel);
            }
            case "~" -> {
                if (type.equals("J")) {
                    currentMethodVisitor.visitLdcInsn(-1L);
                    currentMethodVisitor.visitInsn(Opcodes.LXOR);
                } else {
                    emitIntConstant(-1);
                    currentMethodVisitor.visitInsn(Opcodes.IXOR);
                }
            }
            case "-" -> {
                switch (type) {
                    case "D" -> currentMethodVisitor.visitInsn(Opcodes.DNEG);
                    case "J" -> currentMethodVisitor.visitInsn(Opcodes.LNEG);
                    case "F" -> currentMethodVisitor.visitInsn(Opcodes.FNEG);
                    case "Ljava/math/BigDecimal;" ->
                            currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/math/BigDecimal", "negate",
                                    "()Ljava/math/BigDecimal;", false);
                    default -> currentMethodVisitor.visitInsn(Opcodes.INEG);
                }
            }
        }
        if (op.equals("!"))
            return "Z";
        return type;
    }

    @Override
    public Object visitNullCoalescingExpr(OceanParser.NullCoalescingExprContext ctx) {
        if (isPreScan || currentMethodVisitor == null) {
            return inferType(ctx.expression(1));
        }

        Label endLabel = new Label();
        String leftType = inferType(ctx.expression(0));
        String rightType = inferType(ctx.expression(1));

        visit(ctx.expression(0));
        if (isPrimitive(leftType)) {
            emitBoxing(leftType);
            leftType = getWrapperType(leftType);
        }

        currentMethodVisitor.visitInsn(Opcodes.DUP);
        currentMethodVisitor.visitJumpInsn(Opcodes.IFNONNULL, endLabel);
        currentMethodVisitor.visitInsn(Opcodes.POP);

        visit(ctx.expression(1));
        String actualRightType = inferType(ctx.expression(1));
        if (isPrimitive(actualRightType)) {
            emitBoxing(actualRightType);
            actualRightType = getWrapperType(actualRightType);
        }

        currentMethodVisitor.visitLabel(endLabel);
        return isPrimitive(rightType) ? getWrapperType(rightType) : rightType;
    }

    @Override
    public Object visitSafeMemberCallExpr(OceanParser.SafeMemberCallExprContext ctx) {
        String ownerDesc = inferType(ctx.expression());
        String internalOwner = (ownerDesc != null && ownerDesc.startsWith("L") && ownerDesc.length() > 2)
                ? ownerDesc.substring(1, ownerDesc.length() - 1)
                : "java/lang/Object";

        internalOwner = robustResolveInternalOwner(internalOwner);
        String memberName = ctx.anyId().getText();

        boolean isMethodCall = false;
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i).getText().equals("(")) {
                isMethodCall = true;
                break;
            }
        }

        boolean isStatic = false;
        MethodInfo info = null;

        // Determine return type without full recursion if possible
        String returnType = OceanTypeSystem.OBJECT_DESC;
        if (isMethodCall) {
            boolean wasPreScan = isPreScan;
            isPreScan = true;
            List<String> argTypes = new ArrayList<>();
            if (ctx.argumentList() != null) {
                for (OceanParser.ExpressionContext arg : getArgumentExpressions(ctx.argumentList())) {
                    argTypes.add(inferType(arg));
                }
            }

            info = resolveMethodInfo(internalOwner, memberName, argTypes);
            if (info != null) {
                returnType = info.descriptor.substring(info.descriptor.lastIndexOf(')') + 1);
                isStatic = info.isStatic;
                internalOwner = info.resolvedOwner;
            } else {
                String resolved = findMethodOwner(internalOwner, memberName);
                Map<String, String> targetMethods = globalMethodRegistry.get(resolved);
                if (targetMethods != null && targetMethods.containsKey(memberName)) {
                    String r = targetMethods.get(memberName);
                    returnType = r.contains(")") ? r.substring(r.lastIndexOf(')') + 1) : r;
                    internalOwner = resolved;
                }
            }
            isPreScan = wasPreScan;
        } else {
            String fDesc = globalFieldRegistry.getOrDefault(internalOwner, Collections.emptyMap()).get(memberName);
            if (fDesc == null)
                fDesc = resolveExternalFieldDescriptor(internalOwner, memberName);
            if (fDesc != null)
                returnType = fDesc;
        }

        if (isPrimitive(returnType)) {
            returnType = getWrapperType(returnType);
        }

        if (isPreScan || currentMethodVisitor == null) {
            return returnType;
        }

        Label nullLabel = new Label();
        Label endLabel = new Label();
        visit(ctx.expression());
        currentMethodVisitor.visitInsn(Opcodes.DUP);
        currentMethodVisitor.visitJumpInsn(Opcodes.IFNULL, nullLabel);

        OceanParser.ExpressionContext ownerExpr = ctx.expression();
        if (!isStatic) {
            if (ownerExpr instanceof OceanParser.PrimaryExprContext) {
                OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) ownerExpr).primary();
                if (p instanceof OceanParser.IdPrimaryContext) {
                    String id = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                    if (symbolTable.getType(id) == null && !currentClassFields.containsKey(id)) {
                        isStatic = isStaticTarget(id);
                    }
                }
            }
        }

        String actualType;
        if (isMethodCall) {
            if (isStatic) {
                currentMethodVisitor.visitInsn(Opcodes.POP); // Pop owner for static call
            }
            actualType = (String) emitCall(internalOwner, memberName, isStatic, false, false, ctx.argumentList(), ctx,
                    info);
        } else {
            if (isStatic) {
                currentMethodVisitor.visitInsn(Opcodes.POP); // Pop owner for static field
            }
            String fDesc = globalFieldRegistry.getOrDefault(internalOwner, Collections.emptyMap()).get(memberName);
            if (fDesc == null)
                fDesc = resolveExternalFieldDescriptor(internalOwner, memberName);
            if (fDesc == null)
                fDesc = OceanTypeSystem.OBJECT_DESC;
            actualType = fDesc;
            boolean fieldIsStatic = globalFieldStaticity.getOrDefault(internalOwner, Collections.emptyMap())
                    .getOrDefault(memberName, false);
            int opcode = fieldIsStatic ? Opcodes.GETSTATIC : Opcodes.GETFIELD;
            currentMethodVisitor.visitFieldInsn(opcode, stripGenerics(internalOwner), memberName, stripGenerics(fDesc));
        }

        if (isPrimitive(actualType)) {
            emitBoxing(actualType);
            actualType = getWrapperType(actualType);
        } else if ("V".equals(actualType)) {
            currentMethodVisitor.visitInsn(Opcodes.ACONST_NULL); // Match null branch height
        }

        currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);
        currentMethodVisitor.visitLabel(nullLabel);
        currentMethodVisitor.visitInsn(Opcodes.POP);
        currentMethodVisitor.visitInsn(Opcodes.ACONST_NULL);
        currentMethodVisitor.visitLabel(endLabel);

        return returnType;
    }

    @Override
    public Object visitNewObjectExpr(OceanParser.NewObjectExprContext ctx) {
        String typeName;
        if (ctx.type().typeName() != null) {
            typeName = ctx.type().getText();
        } else if (ctx.type().primitiveType() != null) {
            typeName = ctx.type().primitiveType().getText();
        } else {
            typeName = "Object";
        }
        String internalName = resolveClassName(typeName, ctx);
        checkTypeArgs(ctx.type(), robustResolveInternalOwner(internalName));

        List<OceanParser.ExpressionContext> dimExprs = ctx.expression();
        boolean isArray = dimExprs != null && !dimExprs.isEmpty();

        if (isArray) {
            int dims = dimExprs.size();
            for (OceanParser.ExpressionContext expr : dimExprs) {
                visit(expr);
            }

            String baseDesc = getTypeDescriptor(typeName);
            StringBuilder arrayDesc = new StringBuilder();
            arrayDesc.append("[".repeat(dims));
            arrayDesc.append(baseDesc);

            if (dims > 1) {
                currentMethodVisitor.visitMultiANewArrayInsn(arrayDesc.toString(), dims);
            } else {
                if (baseDesc.startsWith("L") || baseDesc.startsWith("[")) {
                    String internal = baseDesc;
                    if (internal.startsWith("L") && internal.endsWith(";")) {
                        internal = internal.substring(1, internal.length() - 1);
                    }
                    currentMethodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, internal);
                } else {
                    int arrayType = getArrayTypeCode(baseDesc);
                    currentMethodVisitor.visitIntInsn(Opcodes.NEWARRAY, arrayType);
                }
            }
            return arrayDesc.toString();
        }

        // 1. Determine argument types via pre-scan
        boolean wasPreScan = isPreScan;
        isPreScan = true;
        List<String> argTypes = new ArrayList<>();
        if (ctx.argumentList() != null) {
            for (OceanParser.ExpressionContext arg : getArgumentExpressions(ctx.argumentList())) {
                argTypes.add(inferType(arg));
            }
        }
        isPreScan = wasPreScan;

        // 2. Resolve constructor descriptor
        String finalCtorDesc;
        String robustInternalName = robustResolveInternalOwner(internalName);
        boolean isExternal = !isInternalOceanClass(robustInternalName);
        Map<String, String> ctorRegistry = globalMethodRegistry.get(robustInternalName);
        String registeredDesc = (ctorRegistry != null) ? ctorRegistry.get("<init>") : null;

        if (registeredDesc != null) {
            finalCtorDesc = registeredDesc;
        } else {
            String reflectionDesc = resolveExternalMethodDescriptor(internalName, "<init>", argTypes);
            if (reflectionDesc != null) {
                finalCtorDesc = reflectionDesc.substring(0, reflectionDesc.lastIndexOf(')') + 1) + "V";
            } else {
                StringBuilder sb = new StringBuilder("(");
                for (String at : argTypes)
                    sb.append(at);
                sb.append(")V");
                finalCtorDesc = sb.toString();
            }
        }

        // 3. Emit bytecode
        currentMethodVisitor.visitTypeInsn(Opcodes.NEW, stripGenerics(internalName));
        currentMethodVisitor.visitInsn(Opcodes.DUP);

        int expectedCount = 0;
        int open = finalCtorDesc.indexOf('(');
        int close = finalCtorDesc.indexOf(')');
        String paramsOnly = (open != -1 && close != -1) ? finalCtorDesc.substring(open + 1, close) : "";

        if (!paramsOnly.isEmpty()) {
            int j = 0;
            while (j < paramsOnly.length()) {
                if (paramsOnly.charAt(j) == '[') {
                    while (paramsOnly.charAt(j) == '[')
                        j++;
                }
                if (paramsOnly.charAt(j) == 'L') {
                    while (paramsOnly.charAt(j) != ';')
                        j++;
                    j++;
                } else {
                    j++;
                }
                expectedCount++;
            }
        }

        List<OceanParser.ExpressionContext> args = getArgumentExpressions(ctx.argumentList());

        if (args.size() != expectedCount) {
            reportError(ctx,
                    "Constructor argument count mismatch. Expected " + expectedCount + " but got " + args.size());
        }

        for (int i = 0; i < args.size(); i++) {
            visit(args.get(i));
            String actualType = inferType(args.get(i));
            String expected = getIthDescriptor(paramsOnly, i);
            if (!expected.isEmpty()) {
                emitCoerceStackTopForParameter(actualType, expected);
            }
        }

        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", finalCtorDesc, false);
        return null;
    }

    @Override
    public Object visitArrayAccessExpr(OceanParser.ArrayAccessExprContext ctx) {
        visit(ctx.expression(0));
        String arrType = inferType(ctx.expression(0));
        visit(ctx.expression(1));
        currentMethodVisitor.visitInsn(getArrayOpcode(arrType, true));
        return null;
    }

    @Override
    public Object visitListLiteralPrimary(OceanParser.ListLiteralPrimaryContext ctx) {
        List<OceanParser.ExpressionContext> exprs = getArgumentExpressions(ctx.argumentList());
        currentMethodVisitor.visitTypeInsn(Opcodes.NEW, "ocean/compiler/stdlib/OceanList");
        currentMethodVisitor.visitInsn(Opcodes.DUP);
        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "ocean/compiler/stdlib/OceanList", "<init>", "()V", false);
        for (OceanParser.ExpressionContext expr : exprs) {
            currentMethodVisitor.visitInsn(Opcodes.DUP);
            visit(expr);
            String exprType = inferType(expr);
            emitCoerceStackTopForParameter(exprType, OceanTypeSystem.OBJECT_DESC);
            currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "ocean/compiler/stdlib/OceanList", "add", "(Ljava/lang/Object;)Z", false);
            currentMethodVisitor.visitInsn(Opcodes.POP);
        }
        return "Locean/compiler/generated/OceanList;";
    }

    @Override
    public Object visitSetLiteralPrimary(OceanParser.SetLiteralPrimaryContext ctx) {
        List<OceanParser.ExpressionContext> exprs = getArgumentExpressions(ctx.argumentList());
        currentMethodVisitor.visitTypeInsn(Opcodes.NEW, "ocean/compiler/stdlib/OceanSet");
        currentMethodVisitor.visitInsn(Opcodes.DUP);
        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "ocean/compiler/stdlib/OceanSet", "<init>", "()V", false);
        for (OceanParser.ExpressionContext expr : exprs) {
            currentMethodVisitor.visitInsn(Opcodes.DUP);
            visit(expr);
            String exprType = inferType(expr);
            emitCoerceStackTopForParameter(exprType, OceanTypeSystem.OBJECT_DESC);
            currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "ocean/compiler/stdlib/OceanSet", "add", "(Ljava/lang/Object;)Z", false);
            currentMethodVisitor.visitInsn(Opcodes.POP);
        }
        return "Locean/compiler/generated/OceanSet;";
    }

    @Override
    public Object visitMapLiteralPrimary(OceanParser.MapLiteralPrimaryContext ctx) {
        currentMethodVisitor.visitTypeInsn(Opcodes.NEW, "ocean/compiler/stdlib/OceanMap");
        currentMethodVisitor.visitInsn(Opcodes.DUP);
        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "ocean/compiler/stdlib/OceanMap", "<init>", "()V", false);
        if (ctx.mapEntry() != null) {
            for (OceanParser.MapEntryContext mCtx : ctx.mapEntry()) {
                currentMethodVisitor.visitInsn(Opcodes.DUP);
                visit(mCtx.expression(0));
                String kType = inferType(mCtx.expression(0));
                emitCoerceStackTopForParameter(kType, OceanTypeSystem.OBJECT_DESC);
                visit(mCtx.expression(1));
                String vType = inferType(mCtx.expression(1));
                emitCoerceStackTopForParameter(vType, OceanTypeSystem.OBJECT_DESC);
                currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "ocean/compiler/stdlib/OceanMap", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                currentMethodVisitor.visitInsn(Opcodes.POP);
            }
        }
        return "Locean/compiler/generated/OceanMap;";
    }

    @Override
    public Object visitArrayLiteralPrimary(OceanParser.ArrayLiteralPrimaryContext ctx) {
        List<OceanParser.ExpressionContext> exprs = getArgumentExpressions(ctx.argumentList());
        int size = exprs.size();
        String elemDesc = OceanTypeSystem.OBJECT_DESC;
        if (size > 0) {
            elemDesc = inferType(exprs.getFirst());
        }
        
        currentMethodVisitor.visitLdcInsn(size);

        switch (elemDesc) {
            case "I" -> currentMethodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
            case "Z" -> currentMethodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BOOLEAN);
            case "J" -> currentMethodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_LONG);
            case "F" -> currentMethodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_FLOAT);
            case "D" -> currentMethodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_DOUBLE);
            case "B" -> currentMethodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
            case "C" -> currentMethodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_CHAR);
            case "S" -> currentMethodVisitor.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_SHORT);
            default -> {
                String internalName = elemDesc.startsWith("L") ?
                        elemDesc.substring(1, elemDesc.length() - 1) : elemDesc;
                currentMethodVisitor.visitTypeInsn(Opcodes.ANEWARRAY, internalName);
            }
        }
        
        for (int i = 0; i < size; i++) {
            currentMethodVisitor.visitInsn(Opcodes.DUP);
            currentMethodVisitor.visitLdcInsn(i);
            OceanParser.ExpressionContext expr = exprs.get(i);
            visit(expr);
            String exprType = inferType(expr);
            emitCoerceStackTopForParameter(exprType, elemDesc);

            switch (elemDesc) {
                case "I", "Z" -> currentMethodVisitor.visitInsn(Opcodes.IASTORE);
                case "J" -> currentMethodVisitor.visitInsn(Opcodes.LASTORE);
                case "F" -> currentMethodVisitor.visitInsn(Opcodes.FASTORE);
                case "D" -> currentMethodVisitor.visitInsn(Opcodes.DASTORE);
                case "B" -> currentMethodVisitor.visitInsn(Opcodes.BASTORE);
                case "C" -> currentMethodVisitor.visitInsn(Opcodes.CASTORE);
                case "S" -> currentMethodVisitor.visitInsn(Opcodes.SASTORE);
                default -> currentMethodVisitor.visitInsn(Opcodes.AASTORE);
            }
        }
        return "[" + elemDesc;
    }

    private int getArrayOpcode(String arrType, boolean isLoad) {
        if (arrType == null || !arrType.startsWith("["))
            return isLoad ? Opcodes.IALOAD : Opcodes.IASTORE;
        char component = arrType.substring(1).charAt(0);
        return switch (component) {
            case 'I' -> isLoad ? Opcodes.IALOAD : Opcodes.IASTORE;
            case 'Z' -> isLoad ? Opcodes.BALOAD : Opcodes.BASTORE;
            case 'D' -> isLoad ? Opcodes.DALOAD : Opcodes.DASTORE;
            case 'J' -> isLoad ? Opcodes.LALOAD : Opcodes.LASTORE;
            case 'F' -> isLoad ? Opcodes.FALOAD : Opcodes.FASTORE;
            case 'L', '[' -> isLoad ? Opcodes.AALOAD : Opcodes.AASTORE;
            default -> isLoad ? Opcodes.IALOAD : Opcodes.IASTORE;
        };
    }

    @Override
    public Object visitMethodCallExpr(OceanParser.MethodCallExprContext ctx) {
        OceanParser.ExpressionContext callExpr = ctx.expression();
        if (callExpr instanceof OceanParser.MemberCallExprContext) {
            // It's a dotted call! Delegate to a modified version of MemberCall logic
            return handleDottedMethodCall((OceanParser.MemberCallExprContext) callExpr, ctx.argumentList());
        } else if (isIdOrParenthesizedId(callExpr)) {
            String methodName = getBaseIdentifier(callExpr);
            if (methodName == null) {
                reportError(ctx, "Unsupported method call target: " + callExpr.getText());
                return OceanTypeSystem.OBJECT_DESC;
            }
            if (methodName.equals("println") || methodName.equals("Output") || NAME_OCEAN_OUTPUT.equals(methodName)) {
                emitStdoutPrintln(ctx.argumentList());
                return "V";
            }
            if (methodName.equals("Input") || NAME_OCEAN_INPUT.equals(methodName)) {
                if (ctx.argumentList() == null || getArgumentExpressions(ctx.argumentList()).isEmpty()) {
                    emitNewScannerOnStdin();
                    return "Ljava/util/Scanner;";
                }
            }

            // 1. Resolve method info with signatures
            List<String> argTypes = new ArrayList<>();
            if (ctx.argumentList() != null) {
                for (OceanParser.ExpressionContext arg : getArgumentExpressions(ctx.argumentList())) {
                    argTypes.add(inferType(arg));
                }
            }

            MethodInfo info = resolveMethodInfo(getCurrentClassPath(), methodName, argTypes);
            boolean isStatic = false;
            String returnType = OceanTypeSystem.OBJECT_DESC;
            String resolvedOwner = getCurrentClassPath();

            if (info != null) {
                returnType = info.descriptor.substring(info.descriptor.lastIndexOf(')') + 1);
                isStatic = info.isStatic;
                resolvedOwner = info.resolvedOwner;
            } else {
                isStatic = globalMethodStaticity.getOrDefault(getCurrentClassPath(), Collections.emptyMap())
                        .getOrDefault(methodName, false);
            }

            if (isPreScan || currentMethodVisitor == null) {
                return isPrimitive(returnType) ? getWrapperType(returnType) : returnType;
            }

            if (!isStatic) {
                if (symbolTable != null && symbolTable.isStaticContext()) {
                    reportError(ctx, "Cannot call local instance method '" + methodName + "' from static context.");
                }
                currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
            }

            return emitCall(resolvedOwner, methodName, isStatic, false, false, ctx.argumentList(), ctx,
                    info);
        } else {
            // Complex expression call? (e.g. someFunc()(arg))
            // We'll treat the callExpr as the owner and search for "apply" or similar if we
            // supported lambdas well,
            // but for now let's just visit it.
            visit(callExpr);
            return emitCall("java/lang/Object", "invoke", false, false, false, ctx.argumentList(), ctx, null);
        }
    }

    private String resolveMethodDescriptor(String owner, String name) {
        return resolveMethodDescriptor(owner, name, Collections.emptyList());
    }

    private String resolveMethodDescriptor(String owner, String name, List<String> argTypes) {
        MethodInfo info = resolveMethodInfo(owner, name, argTypes);
        return info != null ? info.descriptor : null;
    }

    private boolean isStaticMethod(String owner, String name) {
        return isStaticMethod(owner, name, Collections.emptyList());
    }

    private boolean isStaticMethod(String owner, String name, List<String> argTypes) {
        MethodInfo info = resolveMethodInfo(owner, name, argTypes);
        return info != null ? info.isStatic : false;
    }

    private List<String> getParameterDescriptors(String methodDesc) {
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
    }

    private String[] resolveSAM(String descriptor) {
        if (descriptor == null || !descriptor.startsWith("L"))
            return null;
        String internalName = descriptor.substring(1, descriptor.length() - 1);

        // 1. Check if it's an Ocean-defined interface
        // We look in globalMethodRegistry directly for a SAM-like structure in Ocean
        // classes.
        String resolvedInternal = internalName;
        Map<String, String> methods = globalMethodRegistry.get(resolvedInternal);

        // If not found by exact name, try suffix match in the registry keys
        if (methods == null && resolvedInternal.startsWith("ocean/compiler/stdlib/")) {
            String simpleName = resolvedInternal.substring(resolvedInternal.lastIndexOf('/') + 1);
            for (Map.Entry<String, Map<String, String>> entry : globalMethodRegistry.entrySet()) {
                String fqName = entry.getKey();
                if (fqName.endsWith("/" + simpleName)) {
                    resolvedInternal = fqName;
                    methods = entry.getValue();
                    break;
                }
            }
        }

        if (methods != null) {
            // In Ocean, interfaces do NOT have <init> methods. Classes ALWAYS do.
            boolean hasInit = methods.containsKey("<init>");
            boolean isInterface = globalIsInterfaceSet.contains(resolvedInternal) || !hasInit;

            if (isInterface) {
                String samName = null;
                String samDesc = null;
                int abstractCount = 0;
                for (Map.Entry<String, String> entry : methods.entrySet()) {
                    String name = entry.getKey();
                    if (name.equals("<init>") || name.equals("<clinit>") || name.startsWith("lambda$"))
                        continue;

                    if (abstractCount == 0) {
                        samName = name;
                        samDesc = entry.getValue();
                    }
                    abstractCount++;
                }
                if (abstractCount == 1) {
                    return new String[] { samName, samDesc };
                }
            }
        }

        // 2. Fallback to Java Reflection for standard library interfaces
        try {
            Class<?> cls = Class.forName(internalName.replace("/", "."));
            Method sam = null;
            for (Method m : cls.getMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) {
                    if (isObjectMethod(m))
                        continue;
                    if (sam != null)
                        return null;
                    sam = m;
                }
            }
            if (sam == null)
                return null;
            return new String[] { sam.getName(), Type.getMethodDescriptor(sam) };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolves a simple or qualified exception class name to its JVM internal name.
     * Checks importedClasses first, then tries java/lang/ as a fallback.
     */
    private String resolveExceptionType(String typeName) {
        // Already fully qualified with dots
        if (typeName.contains(".")) {
            return typeName.replace(".", "/");
        }
        // Check import map
        if (importedClasses.containsKey(typeName)) {
            return importedClasses.get(typeName).replace(".", "/");
        }
        // Try java.lang package
        try {
            Class.forName("java.lang." + typeName);
            return "java/lang/" + typeName;
        } catch (ClassNotFoundException ignored) {
        }
        // Try java.io package
        try {
            Class.forName("java.io." + typeName);
            return "java/io/" + typeName;
        } catch (ClassNotFoundException ignored) {
        }
        // Try java.sql package
        try {
            Class.forName("java.sql." + typeName);
            return "java/sql/" + typeName;
        } catch (ClassNotFoundException ignored) {
        }
        // Return as-is (will likely fail at verify time if wrong)
        return typeName;
    }

    private boolean isObjectMethod(Method m) {
        try {
            Object.class.getMethod(m.getName(), m.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private void findUsedVariablesRecursive(ParseTree tree, Set<String> used) {
        if (tree instanceof OceanParser.IdPrimaryContext) {
            used.add(((OceanParser.IdPrimaryContext) tree).anyId().getText());
        } else if (tree instanceof OceanParser.ThisRefPrimaryContext) {
            used.add("this");
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            findUsedVariablesRecursive(tree.getChild(i), used);
        }
    }

    private String resolveExtensionMethod(String receiverType, String methodName, List<String> argTypes) {
        for (Map.Entry<String, Map<String, String>> entry : globalMethodRegistry.entrySet()) {
            String hostClass = entry.getKey();
            Map<String, String> methods = entry.getValue();
            if (methods.containsKey(methodName)) {
                String desc = methods.get(methodName);
                if (desc.startsWith("(" + receiverType)) {
                    return hostClass;
                }
            }
        }
        return null;
    }

    private void emitSyntheticLambdas() {
        while (!syntheticLambdas.isEmpty()) {
            List<LambdaInfo> toProcess = new ArrayList<>(syntheticLambdas);
            syntheticLambdas.clear();
            for (LambdaInfo info : toProcess) {
                int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
                if (info.isStatic)
                    access |= Opcodes.ACC_STATIC;

                MethodVisitor mv = cw.visitMethod(access, info.name, info.descriptor, null, null);
                mv.visitCode();

                MethodVisitor oldMv = currentMethodVisitor;
                SymbolTable oldSt = symbolTable;
                String oldReturn = currentMethodReturnDescriptor;
                String oldMethodName = currentMethodName;

                currentMethodVisitor = mv;
                symbolTable = new SymbolTable(info.isStatic, importedClasses, getAllActiveTypeParameters());
                currentMethodName = info.name;

                // Map captured variables
                for (int i = 0; i < info.capturedNames.size(); i++) {
                    symbolTable.declareVariable(info.capturedNames.get(i), info.capturedTypes.get(i));
                }
                // Lambda parameters
                List<String> samParamTypes = getParameterDescriptors(info.samDesc);
                if (info.ctx.parameterList() != null) {
                    for (int i = 0; i < info.ctx.parameterList().parameter().size(); i++) {
                        OceanParser.ParameterContext p = info.ctx.parameterList().parameter(i);
                        String pName = p.anyId().getText();
                        String oceanType = getTypeDescriptor(p.type().getText());
                        String samType = samParamTypes.get(i);

                        if (!oceanType.equals(samType)) {
                            // Declare a temp variable for the SAM-typed parameter
                            int samIdx = symbolTable.declareVariable("$sam_" + pName, samType);
                            // Declare the real Ocean-typed variable
                            int oceanIdx = symbolTable.declareVariable(pName, oceanType);
                            // Unbox/Cast and store
                            emitLoad(samIdx, samType);
                            emitCoerceStackTopForParameter(samType, oceanType);
                            emitStore(oceanIdx, oceanType);
                        } else {
                            symbolTable.declareVariable(pName, oceanType);
                        }
                    }
                } else if (info.ctx.identifierList() != null) {
                    for (int i = 0; i < info.ctx.identifierList().anyId().size(); i++) {
                        String pName = info.ctx.identifierList().anyId(i).getText();
                        String samType = samParamTypes.get(i);
                        symbolTable.declareVariable(pName, samType);
                    }
                }

                String samReturn = info.samDesc.substring(info.samDesc.lastIndexOf(')') + 1);
                currentMethodReturnDescriptor = samReturn;

                if (info.ctx.block() != null) {
                    visit(info.ctx.block());
                    if (!blockEndsWithReturn(info.ctx.block())) {
                        emitDefaultReturnWithPush(samReturn);
                    }
                } else {
                    visit(info.ctx.expression());
                    String exprType = inferType(info.ctx.expression());
                    emitAssignConversion(exprType, samReturn);
                    emitDefaultReturn(samReturn);
                }

                try {
                    mv.visitMaxs(0, 0);
                } catch (Exception e) {
                    needsComputeMaxsFallback = true;
                }
                mv.visitEnd();

                currentMethodVisitor = oldMv;
                symbolTable = oldSt;
                currentMethodReturnDescriptor = oldReturn;
                currentMethodName = oldMethodName;
            }
        }
    }



    private Object handleDottedMethodCall(OceanParser.MemberCallExprContext mCtx,
            OceanParser.ArgumentListContext argList) {
        // Reuse the MemberCall logic but override the argument list
        String ownerDesc = inferType(mCtx.expression());
        String internalOwner;
        if (ownerDesc != null && ownerDesc.startsWith("L") && ownerDesc.length() > 2) {
            internalOwner = ownerDesc.substring(1, ownerDesc.length() - 1);
        } else if (isPrimitive(ownerDesc)) {
            internalOwner = ownerDesc;
        } else if (ownerDesc != null && ownerDesc.startsWith("[")) {
            internalOwner = ownerDesc;
        } else {
            internalOwner = "java/lang/Object";
        }
        String memberName = mCtx.anyId().getText();

        boolean isStaticClassCall = false;
        OceanParser.ExpressionContext ownerExpr = mCtx.expression();
        if (ownerExpr instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) ownerExpr).primary();
            if (p instanceof OceanParser.IdPrimaryContext) {
                String id = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                if (symbolTable.getType(id) == null && !currentClassFields.containsKey(id)) {
                    isStaticClassCall = isStaticTarget(id);
                    if (isStaticClassCall) {
                        String langDefault = getJavaLangDefault(id);
                        if (langDefault != null)
                            internalOwner = langDefault;
                        else if (importedClasses.containsKey(id))
                            internalOwner = importedClasses.get(id);
                        else {
                            String d = getTypeDescriptor(id);
                            if (d.startsWith("L") && d.endsWith(";"))
                                internalOwner = d.substring(1, d.length() - 1);
                            else
                                internalOwner = resolveInternalPath(id);
                        }
                    }
                }
            }
        }

        internalOwner = robustResolveInternalOwner(internalOwner);
        boolean isExternal = !isInternalOceanClass(internalOwner);

        boolean calledOnType = isStaticClassCall;

        // Resolve method info with signatures
        List<String> argTypes = new ArrayList<>();
        if (argList != null) {
            for (OceanParser.ExpressionContext arg : getArgumentExpressions(argList)) {
                argTypes.add(inferType(arg));
            }
        }
        MethodInfo info = resolveMethodInfo(internalOwner, memberName, argTypes);
        String returnType = OceanTypeSystem.OBJECT_DESC;
        boolean targetIsStatic = calledOnType;
        if (info != null) {
            returnType = info.descriptor.substring(info.descriptor.lastIndexOf(')') + 1);
            targetIsStatic = info.isStatic;
            internalOwner = info.resolvedOwner;
        }

        if (isPreScan || currentMethodVisitor == null) {
            return isPrimitive(returnType) ? getWrapperType(returnType) : returnType;
        }

        if (!calledOnType)
            visit(mCtx.expression());

        Object actualType = emitCall(internalOwner, memberName, targetIsStatic, false, false, argList, mCtx, info);
        if (isPrimitive((String) actualType)) {
            emitBoxing((String) actualType);
            actualType = getWrapperType((String) actualType);
        }
        return actualType;
    }

    private Object emitCall(String owner, String name, boolean isStatic, boolean isInterface, boolean isSpecial,
            OceanParser.ArgumentListContext argList, ParserRuleContext ctx, MethodInfo providedInfo) {

        // 1. Pre-scan types to resolve method descriptor
        boolean wasPreScan = isPreScan;
        isPreScan = true;
        List<String> typesForResolution = new ArrayList<>();
        if (argList != null) {
            for (OceanParser.ExpressionContext arg : getArgumentExpressions(argList)) {
                typesForResolution.add(inferType(arg));
            }
        }
        isPreScan = wasPreScan;

        owner = robustResolveInternalOwner(owner);
        boolean isExternal = !isInternalOceanClass(owner);

        MethodInfo resolvedInfo = providedInfo != null ? providedInfo
                : resolveMethodInfo(owner, name, typesForResolution);
        String finalDesc = null;
        String registeredDesc = null;
        String reflectionDesc = null;

        if (resolvedInfo != null) {
            finalDesc = resolvedInfo.descriptor;
            owner = resolvedInfo.resolvedOwner;
            isStatic = resolvedInfo.isStatic;
            if (!isExternal) {
                registeredDesc = finalDesc;
                int mods = globalMethodAccess.getOrDefault(owner, Collections.emptyMap()).getOrDefault(name,
                        Opcodes.ACC_PUBLIC);
                checkAccess(owner, name, mods, ctx);
            } else {
                reflectionDesc = finalDesc;
            }
        } else {
            reflectionDesc = resolveExternalMethodDescriptor(owner, name, typesForResolution);
            if (!isExternal) {
                String resolvedOwner = findMethodOwner(owner, name);
                registeredDesc = globalMethodRegistry.getOrDefault(resolvedOwner, Collections.emptyMap()).get(name);
                if (registeredDesc != null)
                    owner = resolvedOwner;
            }

            if (registeredDesc != null) {
                finalDesc = registeredDesc;
            } else if (reflectionDesc != null) {
                finalDesc = reflectionDesc;
            } else {
                String ret = "V";
                if (isExternal) {
                    ret = resolveReturnTypeByReflection(owner, name, typesForResolution.size());
                } else {
                    String resolved = findMethodOwner(owner, name);
                    Map<String, String> targetMethods = globalMethodRegistry.get(resolved);
                    if (targetMethods != null && targetMethods.containsKey(name)) {
                        ret = targetMethods.get(name);
                        if (ret.contains(")"))
                            ret = ret.substring(ret.lastIndexOf(')') + 1);
                        owner = resolved;
                    }
                }
                if (ret == null)
                    ret = "V";

                StringBuilder descBuilder = new StringBuilder("(");
                for (String t : typesForResolution)
                    descBuilder.append(t);
                descBuilder.append(")");
                finalDesc = descBuilder + ret;
            }
        }

        if (wasPreScan) {
            return finalDesc.substring(finalDesc.lastIndexOf(')') + 1);
        }

        // 3. Emit code for arguments with proper boxing/coercion
        StringBuilder desc = new StringBuilder("(");
        if (argList != null) {
            List<OceanParser.ExpressionContext> argExprs = getArgumentExpressions(argList);
            for (int i = 0; i < argExprs.size(); i++) {
                OceanParser.ExpressionContext arg = argExprs.get(i);
                visit(arg);
                String argType = inferType(arg);

                String expected = null;
                if (registeredDesc != null) {
                    String params = registeredDesc.substring(registeredDesc.indexOf('(') + 1,
                            registeredDesc.indexOf(')'));
                    int paramIdx = i + (providedInfo != null && providedInfo.isExtension ? 1 : 0);
                    expected = getIthDescriptor(params, paramIdx);
                } else if (reflectionDesc != null) {
                    String params = reflectionDesc.substring(reflectionDesc.indexOf('(') + 1,
                            reflectionDesc.indexOf(')'));
                    expected = getIthDescriptor(params, i);
                }

                if (isExternal && needsAutoBoxing(owner, name, typesForResolution, i)) {
                    emitBoxing(argType);
                    desc.append(OceanTypeSystem.OBJECT_DESC);
                } else if (expected != null) {
                    emitCoerceStackTopForParameter(argType, expected);
                    desc.append(expected);
                } else {
                    desc.append(argType);
                }
            }
        }
        desc.append(")");

        if (name.equals("main") && !isExternal && (argList == null || getArgumentExpressions(argList).isEmpty())
                && finalDesc.contains("[Ljava/lang/String;")) {
            currentMethodVisitor.visitInsn(Opcodes.ACONST_NULL);
        }

        if (!isStatic && !isInterface && globalIsInterfaceSet.contains(owner)) {
            isInterface = true;
        }

        int opcode = isStatic ? Opcodes.INVOKESTATIC
                : (isSpecial ? Opcodes.INVOKESPECIAL : (isInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL));
        if (isExternal && !isStatic) {
            try {
                Class<?> cls = Class.forName(owner.replace("/", "."));
                if (cls.isInterface()) {
                    isInterface = true;
                    opcode = Opcodes.INVOKEINTERFACE;
                }
            } catch (Exception ignored) {
            }
        }
        currentMethodVisitor.visitMethodInsn(opcode, stripGenerics(owner), name, stripGenerics(finalDesc), isInterface);
        return finalDesc.substring(finalDesc.lastIndexOf(')') + 1);
    }

    @Override
    public Object visitMemberCallExpr(OceanParser.MemberCallExprContext ctx) {
        String ownerDesc = inferType(ctx.expression());
        String internalOwner;
        if (ownerDesc != null && ownerDesc.startsWith("L") && ownerDesc.length() > 2) {
            internalOwner = ownerDesc.substring(1, ownerDesc.length() - 1);
        } else if (isPrimitive(ownerDesc)) {
            internalOwner = ownerDesc;
        } else if (ownerDesc != null && ownerDesc.startsWith("[")) {
            internalOwner = ownerDesc;
        } else {
            internalOwner = "java/lang/Object";
        }

        boolean isStaticClassCall = false;
        OceanParser.ExpressionContext ownerExpr = ctx.expression();
        if (ownerExpr instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) ownerExpr).primary();
            if (p instanceof OceanParser.IdPrimaryContext) {
                String id = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                if (symbolTable.getType(id) == null && !currentClassFields.containsKey(id)) {
                    isStaticClassCall = isStaticTarget(id);
                    if (isStaticClassCall) {
                        String langDefault = getJavaLangDefault(id);
                        if (langDefault != null)
                            internalOwner = langDefault;
                        else if (importedClasses.containsKey(id))
                            internalOwner = importedClasses.get(id);
                        else
                            internalOwner = resolveInternalPath(id);
                    }
                }
            }
        }
        internalOwner = robustResolveInternalOwner(internalOwner);
        String memberName = ctx.anyId().getText();

        boolean calledOnType = isStaticClassCall; // Was it called on a Class name?

        // Handle array.length
        if (ownerDesc != null && ownerDesc.startsWith("[") && memberName.equals("length")) {
            visit(ctx.expression());
            currentMethodVisitor.visitInsn(Opcodes.ARRAYLENGTH);
            return "I";
        }

        boolean isMethodCall = false;
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i).getText().equals("(")) {
                isMethodCall = true;
                break;
            }
        }

        if (isMethodCall) {
            boolean isSuperCall = false;
            if (ownerExpr instanceof OceanParser.PrimaryExprContext) {
                OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) ownerExpr).primary();
                if (p instanceof OceanParser.SuperRefPrimaryContext) {
                    isSuperCall = true;
                }
            }

            // Infer return type and check for staticity before call
            boolean wasPreScan = isPreScan;
            isPreScan = true;
            List<String> argTypes = new ArrayList<>();
            if (ctx.argumentList() != null) {
                for (OceanParser.ExpressionContext arg : getArgumentExpressions(ctx.argumentList())) {
                    argTypes.add(inferType(arg));
                }
            }
            MethodInfo info = resolveMethodInfo(internalOwner, memberName, argTypes);
            String returnType = "V";
            boolean targetIsStatic = calledOnType;
            if (info != null) {
                returnType = info.descriptor.substring(info.descriptor.lastIndexOf(')') + 1);
                targetIsStatic = info.isStatic;
                internalOwner = info.resolvedOwner;
            }
            isPreScan = wasPreScan;

            if (isPreScan || currentMethodVisitor == null) {
                return returnType;
            }

            // If it's an extension method (targetIsStatic but NOT calledOnType),
            // or a normal instance method (!targetIsStatic), we need the receiver.
            if (!calledOnType) {
                visit(ctx.expression());
                if (targetIsStatic && !isInternalOceanClass(internalOwner)) {
                    // Possible extension or java static called on instance?
                    // If it's an extension method, the receiver is the first arg.
                }
            }

            // Delegate to emitCall for robust method call handling
            return emitCall(internalOwner, memberName, targetIsStatic, false, isSuperCall,
                    ctx.argumentList(), ctx, info);
        } else {
            // Field access
            if (!calledOnType) {
                visit(ctx.expression());
            }

            String fDesc = null;
            boolean isExternal = !isInternalOceanClass(internalOwner);

            if (!isExternal) {
                String resolvedOwner = findFieldOwner(internalOwner, memberName);
                fDesc = globalFieldRegistry.getOrDefault(resolvedOwner, Collections.emptyMap()).get(memberName);
                if (fDesc != null) {
                    internalOwner = resolvedOwner;
                    int mods = globalFieldAccess.getOrDefault(internalOwner, Collections.emptyMap())
                            .getOrDefault(memberName, Opcodes.ACC_PUBLIC);
                    checkAccess(internalOwner, memberName, mods, ctx);
                }
            } else {
                fDesc = resolveExternalFieldDescriptor(internalOwner, memberName);
            }

            if (fDesc == null) {
                fDesc = OceanTypeSystem.OBJECT_DESC;
            }

            int opcode = isStaticClassCall ? Opcodes.GETSTATIC : Opcodes.GETFIELD;
            currentMethodVisitor.visitFieldInsn(opcode, stripGenerics(internalOwner), memberName, stripGenerics(fDesc));
            return fDesc;
        }
    }

    private int getArrayTypeCode(String desc) {
        return switch (desc) {
            case "Z" -> Opcodes.T_BOOLEAN;
            case "C" -> Opcodes.T_CHAR;
            case "F" -> Opcodes.T_FLOAT;
            case "D" -> Opcodes.T_DOUBLE;
            case "B" -> Opcodes.T_BYTE;
            case "S" -> Opcodes.T_SHORT;
            case "I" -> Opcodes.T_INT;
            case "J" -> Opcodes.T_LONG;
            default -> Opcodes.T_INT;
        };
    }

    private String resolveClassName(String typeNameRaw, ParserRuleContext ctx) {
        String typeName = stripGenerics(typeNameRaw).trim();
        String cacheKey = "CLASS:" + typeName + ":" + currentFilePackage;
        String cached = (String) reflectionCache.get(cacheKey);
        if (cached != null)
            return cached;

        // Check current package first
        String localFq = getGeneratedPackagePrefix() + currentFilePackage + "/" + typeName;
        if (globalMethodRegistry.containsKey(localFq) || globalFieldRegistry.containsKey(localFq)) {
            reflectionCache.put(cacheKey, localFq);
            return localFq;
        }

        // Check Aliases/Auto-imports (List, Map etc) BEFORE aggressive global search
        String stripped = stripGenerics(typeName);
        String builtin = OCEAN_TYPE_ALIASES.get(stripped);
        if (builtin != null) {
            reflectionCache.put(cacheKey, builtin);
            return builtin;
        }

        // Aggressive resolution for Ocean classes in other packages
        for (String fqName : globalMethodRegistry.keySet()) {
            if (fqName.startsWith(getGeneratedPackagePrefix() + currentFilePackage + "/")
                    && fqName.endsWith("/" + typeName)) {
                reflectionCache.put(cacheKey, fqName);
                return fqName;
            }
        }

        // Fallback to any matching Ocean class if still not found
        for (String fqName : globalMethodRegistry.keySet()) {
            if (fqName.endsWith("/" + typeName)) {
                reflectionCache.put(cacheKey, fqName);
                return fqName;
            }
        }

        String fromImports = importedClasses.get(typeName);
        if (fromImports != null) {
            reflectionCache.put(cacheKey, fromImports);
            return fromImports;
        }
        String langResult = getJavaLangDefault(typeName);
        if (langResult != null) {
            reflectionCache.put(cacheKey, langResult);
            return langResult;
        }

        if (currentMethodTypeParameters.contains(typeName))
            return "java/lang/Object";

        if (currentClassName != null) {
            String fullPath = getCurrentClassPath();
            List<CompilerRegistry.TypeParameterInfo> infos = globalTypeParameterRegistry.get(fullPath);
            if (infos != null) {
                for (CompilerRegistry.TypeParameterInfo info : infos) {
                    if (info.name.equals(typeName))
                        return "java/lang/Object";
                }
            }
        }

        return getGeneratedPackagePrefix() + currentFilePackage + "/" + typeName;
    }

    private String resolvePrintlnDescriptor(String argType) {
        for (String expected : getPrintlnOneArgParamDescriptors()) {
            if (expected.equals(argType))
                return "(" + expected + ")V";
        }
        return "(" + (argType.length() == 1 ? argType : OceanTypeSystem.OBJECT_DESC) + ")V";
    }

    /**
     * System.out.println(...) — shared by OceanOutput primary, MethodCallExpr, and
     * IdPrimary fast paths.
     */
    private void emitStdoutPrintln(OceanParser.ArgumentListContext argList) {
        List<OceanParser.ExpressionContext> argExprs = getArgumentExpressions(argList);
        if (!argExprs.isEmpty()) {
            OceanParser.ExpressionContext expr = argExprs.getFirst();
            visit(expr); // Evaluate value first
            String actualType = inferType(expr);

            // Push System.out AFTER evaluation
            currentMethodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");

            // Safety: Only SWAP if we actually have something to swap with
            if (actualType != null && !actualType.equals("V")) {
                if (actualType.equals("D") || actualType.equals("J")) {
                    currentMethodVisitor.visitInsn(Opcodes.DUP_X2);
                    currentMethodVisitor.visitInsn(Opcodes.POP);
                } else {
                    currentMethodVisitor.visitInsn(Opcodes.SWAP);
                }
            }

            String descriptor = resolvePrintlnDescriptor(actualType);
            String expectedType = descriptor.substring(1, descriptor.lastIndexOf(')'));
            emitCoerceStackTopForParameter(actualType, expectedType);
            currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", descriptor,
                    false);
        } else {
            currentMethodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "()V", false);
        }
    }

    /**
     * Align stack top (actual) with a resolved external parameter slot (expected),
     * using widening and boxing
     * consistent with {@link #resolveExternalMethodDescriptor}.
     */
    private void emitCoerceStackTopForParameter(String actualJvm, String expectedJvm) {
        if (actualJvm == null || expectedJvm == null || actualJvm.equals(expectedJvm))
            return;

        if (isPrimitive(actualJvm) && isPrimitive(expectedJvm)) {
            emitNumericCast(actualJvm, expectedJvm);
        } else if (isPrimitive(actualJvm) && expectedJvm.startsWith("L")) {
            emitBoxing(actualJvm);
            String wrapper = getWrapperType(actualJvm);
            if (!OceanTypeSystem.OBJECT_DESC.equals(expectedJvm)) {
                String internal = expectedJvm.substring(1, expectedJvm.length() - 1);
                currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, internal);
            }
        } else if (actualJvm.startsWith("L") && isPrimitive(expectedJvm)) {
            emitUnboxToPrimitive(expectedJvm);
        } else if ("V".equals(actualJvm)) {
            currentMethodVisitor.visitInsn(Opcodes.ACONST_NULL);
            if (expectedJvm.startsWith("L") && !OceanTypeSystem.OBJECT_DESC.equals(expectedJvm)) {
                String internal = expectedJvm;
                if (internal.startsWith("L") && internal.endsWith(";")) {
                    internal = internal.substring(1, internal.length() - 1);
                }
                currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, internal);
            }
        } else if (actualJvm.startsWith("L") && expectedJvm.startsWith("L")) {
            if (!OceanTypeSystem.OBJECT_DESC.equals(expectedJvm)) {
                String internal = expectedJvm;
                if (internal.startsWith("L") && internal.endsWith(";")) {
                    internal = internal.substring(1, internal.length() - 1);
                }
                currentMethodVisitor.visitTypeInsn(Opcodes.CHECKCAST, internal);
            }
        }
    }

    private boolean needsAutoBoxing(String owner, String methodName, List<String> argTypes, int argIndex) {
        if (argIndex >= argTypes.size())
            return false;
        String actual = argTypes.get(argIndex);
        if (!isPrimitive(actual))
            return false;
        String sig = resolveExternalMethodDescriptor(owner, methodName, argTypes);
        if (sig == null)
            return false;
        int open = sig.indexOf('(');
        int close = sig.indexOf(')');
        if (open < 0 || close < 0 || close <= open)
            return false;
        String expected = getIthDescriptor(sig.substring(open + 1, close), argIndex);
        return OceanTypeSystem.OBJECT_DESC.equals(expected);
    }

    @Override
    public Object visitNumberPrimary(OceanParser.NumberPrimaryContext ctx) {
        if (isPreScan || currentMethodVisitor == null) {
            String val = ctx.NUMBER().getText();
            char last = Character.toUpperCase(val.charAt(val.length() - 1));
            if (last == 'F')
                return "F";
            if (last == 'D' || val.contains("."))
                return "D";
            if (last == 'L')
                return "J";
            try {
                long n = Long.parseLong(val);
                if (n > Integer.MAX_VALUE || n < Integer.MIN_VALUE)
                    return "J";
                return "I";
            } catch (NumberFormatException e) {
                return "Ljava/math/BigDecimal;";
            }
        }

        String val = ctx.NUMBER().getText();
        char last = Character.toUpperCase(val.charAt(val.length() - 1));
        boolean isLong = last == 'L';
        boolean isFloat = last == 'F';
        boolean isDouble = last == 'D';
        if (isLong || isFloat || isDouble) {
            val = val.substring(0, val.length() - 1);
        }

        if (isFloat) {
            currentMethodVisitor.visitLdcInsn(Float.parseFloat(val));
            return "F";
        } else if (isDouble || val.contains(".")) {
            currentMethodVisitor.visitLdcInsn(Double.parseDouble(val));
            return "D";
        } else if (isLong) {
            try {
                currentMethodVisitor.visitLdcInsn(Long.parseLong(val));
            } catch (NumberFormatException e) {
                emitBigDecimalInsn(val);
                return "Ljava/math/BigDecimal;";
            }
            return "J";
        } else {
            try {
                long n = Long.parseLong(val);
                if (n > Integer.MAX_VALUE || n < Integer.MIN_VALUE) {
                    currentMethodVisitor.visitLdcInsn(n);
                    return "J";
                } else {
                    emitIntConstant((int) n);
                    return "I";
                }
            } catch (NumberFormatException e) {
                emitBigDecimalInsn(val);
                return "Ljava/math/BigDecimal;";
            }
        }
    }

    private void emitBigDecimalInsn(String val) {
        currentMethodVisitor.visitTypeInsn(Opcodes.NEW, "java/math/BigDecimal");
        currentMethodVisitor.visitInsn(Opcodes.DUP);
        currentMethodVisitor.visitLdcInsn(val);
        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/math/BigDecimal", "<init>",
                "(Ljava/lang/String;)V", false);
    }

    public Object visitStringPrimary(OceanParser.StringPrimaryContext ctx) {
        if (ctx.STRING_LITERAL() != null) {
            String text = ctx.STRING_LITERAL().getText();
            String content = text.substring(1, text.length() - 1);
            currentMethodVisitor.visitLdcInsn(ConstantFolder.unescapeString(content));
        } else {
            String text = ctx.MULTILINE_STRING().getText();
            String content = text.substring(3, text.length() - 3);
            currentMethodVisitor.visitLdcInsn(ConstantFolder.unescapeString(content));
        }
        return OceanTypeSystem.STRING_DESC;
    }

    @Override
    public Object visitCharPrimary(OceanParser.CharPrimaryContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return "C";
        Object folded = ConstantFolder.fold(ctx);
        char ch = (folded instanceof Character) ? (Character) folded : '\0';
        currentMethodVisitor.visitIntInsn(Opcodes.BIPUSH, ch);
        return "C";
    }

    @Override
    public Object visitInterpolatedStringPrimary(OceanParser.InterpolatedStringPrimaryContext ctx) {
        if (isPreScan || currentMethodVisitor == null)
            return OceanTypeSystem.STRING_DESC;
        String text = ctx.INTERPOLATED_STRING().getText();
        String content = text.substring(2, text.length() - 1); // Remove $" and "

        currentMethodVisitor.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        currentMethodVisitor.visitInsn(Opcodes.DUP);
        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);

        int sbIdx = symbolTable.declareVariable("$sb_" + System.nanoTime(), "Ljava/lang/StringBuilder;");
        currentMethodVisitor.visitVarInsn(Opcodes.ASTORE, sbIdx);

        int i = 0;
        while (i < content.length()) {
            int braceStart = content.indexOf('{', i);
            if (braceStart == -1) {
                appendStringPartWithSb(content.substring(i), sbIdx);
                break;
            }

            if (braceStart > i) {
                appendStringPartWithSb(content.substring(i, braceStart), sbIdx);
            }

            if (braceStart + 1 < content.length() && content.charAt(braceStart + 1) == '{') {
                appendStringPartWithSb("{", sbIdx);
                i = braceStart + 2;
                continue;
            }

            int braceEnd = findMatchingEndBrace(content, braceStart + 1);
            if (braceEnd == -1) {
                appendStringPartWithSb("{", sbIdx);
                i = braceStart + 1;
                continue;
            }

            String exprText = content.substring(braceStart + 1, braceEnd);
            compileAndAppendExpressionWithSb(exprText, sbIdx);
            i = braceEnd + 1;
        }

        currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, sbIdx);
        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false);
        return OceanTypeSystem.STRING_DESC;
    }

    private void appendStringPartWithSb(String part, int sbIdx) {
        String unescaped = part.replace("{{", "{").replace("}}", "}");
        if (unescaped.isEmpty())
            return;
        currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, sbIdx);
        currentMethodVisitor.visitLdcInsn(ConstantFolder.unescapeString(unescaped));
        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        currentMethodVisitor.visitInsn(Opcodes.POP);
    }

    private void compileAndAppendExpressionWithSb(String exprText, int sbIdx) {
        CharStream input = CharStreams.fromString(exprText);
        OceanLexer lexer = new OceanLexer(input);
        TokenStream tokens = OceanTokenStreamFactory.createTokenStream(lexer);
        OceanParser parser = new OceanParser(tokens);
        OceanParser.ExpressionContext exprCtx = parser.expression();

        Object result = visit(exprCtx);
        String type = (result instanceof String) ? (String) result : inferType(exprCtx);

        if (type != null && type.equals("V")) {
            currentMethodVisitor.visitLdcInsn("null");
            type = OceanTypeSystem.STRING_DESC;
        }

        currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, sbIdx);
        if (type.equals("D") || type.equals("J")) {
            // Stack: [Value(2), SB(1)] -> Goal: [SB(1), Value(2)]
            currentMethodVisitor.visitInsn(Opcodes.DUP_X2);
            currentMethodVisitor.visitInsn(Opcodes.POP);
        } else {
            // Stack: [Value(1), SB(1)] -> Goal: [SB(1), Value(1)]
            currentMethodVisitor.visitInsn(Opcodes.SWAP);
        }

        String appendDesc = "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
        if (type.length() == 1) {
            appendDesc = "(" + type + ")Ljava/lang/StringBuilder;";
        }

        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", appendDesc,
                false);
        currentMethodVisitor.visitInsn(Opcodes.POP);
    }

    private int findMatchingEndBrace(String s, int start) {
        int depth = 1;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{')
                depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0)
                    return i;
            }
        }
        return -1;
    }

    @Override
    public Object visitTernaryExpr(OceanParser.TernaryExprContext ctx) {
        String type1 = inferType(ctx.expression(1));
        String type2 = inferType(ctx.expression(2));
        String commonType = getCommonType(type1, type2);

        if (isPreScan || currentMethodVisitor == null)
            return commonType;

        Label falseLabel = new Label();
        Label endLabel = new Label();

        emitConditionJump(ctx.expression(0), falseLabel, false);

        visit(ctx.expression(1));
        emitAssignConversion(type1, commonType);
        currentMethodVisitor.visitJumpInsn(Opcodes.GOTO, endLabel);

        currentMethodVisitor.visitLabel(falseLabel);
        visit(ctx.expression(2));
        emitAssignConversion(type2, commonType);

        currentMethodVisitor.visitLabel(endLabel);

        return commonType;
    }

    private String getCommonType(String t1, String t2) {
        if (t1 == null)
            return t2;
        if (t2 == null)
            return t1;
        if (t1.equals(t2))
            return t1;
        if (isCompatible(t1, t2))
            return t2;
        if (isCompatible(t2, t1))
            return t1;

        // Handle numeric widening if not covered by isCompatible
        if (isPrimitive(t1) && isPrimitive(t2)) {
            if (t1.equals("D") || t2.equals("D"))
                return "D";
            if (t1.equals("J") || t2.equals("J"))
                return "J";
            if (t1.equals("F") || t2.equals("F"))
                return "F";
            return "I";
        }

        return OceanTypeSystem.OBJECT_DESC;
    }

    @Override
    public Object visitTruePrimary(OceanParser.TruePrimaryContext ctx) {
        currentMethodVisitor.visitInsn(Opcodes.ICONST_1);
        return "Z";
    }

    @Override
    public Object visitFalsePrimary(OceanParser.FalsePrimaryContext ctx) {
        currentMethodVisitor.visitInsn(Opcodes.ICONST_0);
        return "Z";
    }

    @Override
    public Object visitNullPrimary(OceanParser.NullPrimaryContext ctx) {
        currentMethodVisitor.visitInsn(Opcodes.ACONST_NULL);
        return OceanTypeSystem.OBJECT_DESC;
    }

    @Override
    public Object visitParenthesizedPrimary(OceanParser.ParenthesizedPrimaryContext ctx) {
        return visit(ctx.expression());
    }

    @Override
    public Object visitThisRefPrimary(OceanParser.ThisRefPrimaryContext ctx) {
        if (symbolTable.isStaticContext() && symbolTable.getIndex("this") == -1) {
            currentMethodVisitor.visitInsn(Opcodes.ACONST_NULL);
            return OceanTypeSystem.OBJECT_DESC;
        } else {
            String type = symbolTable.getType("this");
            int idx = symbolTable.getIndex("this");
            if (idx == -1)
                idx = 0; // Default for instance methods

            String typeDesc = (type != null) ? getTypeDescriptor(type) : OceanTypeSystem.wrapObjectType(getCurrentClassPath());
            if (!isPreScan && currentMethodVisitor != null) {

                emitLoad(idx, typeDesc);
            }
            return typeDesc;
        }
    }

    @Override
    public Object visitSuperRefPrimary(OceanParser.SuperRefPrimaryContext ctx) {
        currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
        return null;
    }

    @Override
    public Object visitIdPrimary(OceanParser.IdPrimaryContext ctx) {
        String name = ctx.anyId().getText();
        int index = symbolTable.getIndex(name);
        if (index != -1) {
            String typeDesc = symbolTable.getType(name);
            if (!isPreScan && currentMethodVisitor != null) {
                emitLoad(index, typeDesc);
            }
            return typeDesc;
        } else {
            String owner = getCurrentClassPath();
            String resolvedOwner = findFieldOwner(owner, name);
            Map<String, String> targetFields = globalFieldRegistry.get(resolvedOwner);
            if (targetFields != null && targetFields.containsKey(name)) {
                String desc = targetFields.get(name);
                boolean isStatic = globalFieldStaticity.getOrDefault(resolvedOwner, Collections.emptyMap())
                        .getOrDefault(name, false);
                if (isStatic) {
                    currentMethodVisitor.visitFieldInsn(Opcodes.GETSTATIC, resolvedOwner, name, desc);
                } else {
                    currentMethodVisitor.visitVarInsn(Opcodes.ALOAD, 0);
                    currentMethodVisitor.visitFieldInsn(Opcodes.GETFIELD, resolvedOwner, name, desc);
                }
                return desc;
            } else if (getTypeDescriptor(name).startsWith("L") && !isInternalOceanClass(name.replace(".", "/"))) {
                return getTypeDescriptor(name);
            } else {
                String classPath = resolveInternalPath(name);
                if (globalMethodRegistry.containsKey(classPath) || globalFieldRegistry.containsKey(classPath)) {
                    return OceanTypeSystem.wrapObjectType(classPath);
                } else {
                    reportError(ctx, "Unknown identifier '" + name + "'");
                    return OceanTypeSystem.OBJECT_DESC;
                }
            }
        }
    }

    @Override
    public Object visitOceanOutputPrimary(OceanParser.OceanOutputPrimaryContext ctx) {
        if (ctx.argumentList() == null || getArgumentExpressions(ctx.argumentList()).isEmpty()) {
            currentMethodVisitor.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            return "Ljava/io/PrintStream;";
        }
        emitStdoutPrintln(ctx.argumentList());
        return "V";
    }

    private final Map<ParserRuleContext, String> inferredTypeCache = new IdentityHashMap<>();

    /** Fallback type when type inference cannot determine an expression's type. */
    private static final String INFER_FALLBACK_TYPE = "I";

    private String inferType(ParserRuleContext ctx) {
        if (ctx == null)
            return INFER_FALLBACK_TYPE;
        if (inferredTypeCache.containsKey(ctx))
            return inferredTypeCache.get(ctx);
        String result = inferTypeImpl(ctx);
        if (result == null) {
            reportWarning(ctx, "Cannot infer type of '" + ctx.getText() + "', defaulting to int");
            result = INFER_FALLBACK_TYPE;
        }
        inferredTypeCache.put(ctx, result);
        return result;
    }

    private String inferTypeImpl(ParserRuleContext ctx) {
        if (ctx instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext pCtx = ((OceanParser.PrimaryExprContext) ctx).primary();
            if (pCtx instanceof OceanParser.IdPrimaryContext) {
                String name = ((OceanParser.IdPrimaryContext) pCtx).anyId().getText();
                int idx = symbolTable.getIndex(name);
                if (idx != -1)
                    return symbolTable.getType(name);
                if (currentClassFields.containsKey(name))
                    return currentClassFields.get(name);
                String resolvedClass = resolveClassName(name, ctx);
                return OceanTypeSystem.wrapObjectType(resolvedClass);
            } else if (pCtx instanceof OceanParser.StringPrimaryContext
                    || pCtx instanceof OceanParser.InterpolatedStringPrimaryContext) {
                return OceanTypeSystem.STRING_DESC;
            } else if (pCtx instanceof OceanParser.CharPrimaryContext) {
                return "C";
            } else if (pCtx instanceof OceanParser.NumberPrimaryContext) {
                String val = ((OceanParser.NumberPrimaryContext) pCtx).NUMBER().getText();
                char last = Character.toUpperCase(val.charAt(val.length() - 1));
                if (last == 'F')
                    return "F";
                if (last == 'D' || val.contains("."))
                    return "D";
                if (last == 'L')
                    return "J";
                try {
                    String numPart = (last >= 'A' && last <= 'Z') ? val.substring(0, val.length() - 1) : val;
                    if (!numPart.isEmpty()) {
                        long n = Long.parseLong(numPart);
                        if (n > Integer.MAX_VALUE || n < Integer.MIN_VALUE)
                            return "J";
                    }
                } catch (Exception e) {
                    // Too large for Long, promote to BigDecimal
                    return "Ljava/math/BigDecimal;";
                }
                return "I";
            } else if (pCtx instanceof OceanParser.TruePrimaryContext
                    || pCtx instanceof OceanParser.FalsePrimaryContext) {
                return "Z";
            } else if (pCtx instanceof OceanParser.ParenthesizedPrimaryContext) {
                return inferType(((OceanParser.ParenthesizedPrimaryContext) pCtx).expression());
            } else if (pCtx instanceof OceanParser.ThisRefPrimaryContext) {
                String type = symbolTable.getType("this");
                if (type != null)
                    return getTypeDescriptor(type);
                return OceanTypeSystem.wrapObjectType(getCurrentClassPath());
            } else if (pCtx instanceof OceanParser.SuperRefPrimaryContext) {
                return currentSuperName.startsWith("L") ? currentSuperName : OceanTypeSystem.wrapObjectType(currentSuperName);
            } else if (pCtx instanceof OceanParser.NullPrimaryContext) {
                return OceanTypeSystem.OBJECT_DESC;
            } else if (pCtx instanceof OceanParser.OceanOutputPrimaryContext ooCtx) {
                if (ooCtx.argumentList() == null || getArgumentExpressions(ooCtx.argumentList()).isEmpty()) {
                    return "Ljava/io/PrintStream;";
                }
                return "V";
            } else if (pCtx instanceof OceanParser.OceanInputPrimaryContext) {
                return "Ljava/util/Scanner;";
            } else if (pCtx instanceof OceanParser.ListLiteralPrimaryContext) {
                return "Locean/compiler/generated/OceanList;";
            } else if (pCtx instanceof OceanParser.SetLiteralPrimaryContext) {
                return "Locean/compiler/generated/OceanSet;";
            } else if (pCtx instanceof OceanParser.MapLiteralPrimaryContext) {
                return "Locean/compiler/generated/OceanMap;";
            }
        } else if (ctx instanceof OceanParser.LambdaExprContext lCtx) {
            if (currentCastType != null)
                return currentCastType;

            // Auto-infer common functional interfaces based on parameter count
            int paramCount = 0;
            if (lCtx.parameterList() != null)
                paramCount = lCtx.parameterList().parameter().size();
            else if (lCtx.identifierList() != null)
                paramCount = lCtx.identifierList().anyId().size();

            boolean hasReturn = false;
            if (lCtx.expression() != null)
                hasReturn = true;
            else if (lCtx.block() != null)
                hasReturn = blockHasReturn(lCtx.block());

            return switch (paramCount) {
                case 0 -> hasReturn ? "Ljava/util/function/Supplier;" : "Ljava/lang/Runnable;";
                case 1 -> hasReturn ? "Ljava/util/function/Function;" : "Ljava/util/function/Consumer;";
                case 2 -> hasReturn ? "Ljava/util/function/BiFunction;" : "Ljava/util/function/BiConsumer;";
                default -> "Ljava/util/function/Function;";
            };
        } else if (ctx instanceof OceanParser.ArrayAccessExprContext) {
            String arrayType = inferType(((OceanParser.ArrayAccessExprContext) ctx).expression(0));
            return (arrayType.startsWith("[")) ? arrayType.substring(1) : arrayType;
        } else if (ctx instanceof OceanParser.NewObjectExprContext nCtx) {
            String typeName;
            if (nCtx.type().typeName() != null) {
                typeName = nCtx.type().getText();
            } else if (nCtx.type().primitiveType() != null) {
                typeName = nCtx.type().primitiveType().getText();
            } else {
                typeName = "Object";
            }
            List<OceanParser.ExpressionContext> dimExprs = nCtx.expression();
            boolean isArray = dimExprs != null && !dimExprs.isEmpty();
            String baseDesc = getTypeDescriptor(typeName);
            if (baseDesc.startsWith("Locean/compiler/generated/")) {
                String resolvedBase = resolveClassName(typeName, ctx);
                baseDesc = OceanTypeSystem.wrapObjectType(resolvedBase);
            }
            if (isArray) {
                StringBuilder sb = new StringBuilder();
                sb.append("[".repeat(dimExprs.size()));
                return sb.append(baseDesc).toString();
            }
            return baseDesc;
        } else if (ctx instanceof OceanParser.AddSubExprContext || ctx instanceof OceanParser.MulDivModExprContext) {
            Object folded = foldConstant(ctx);
            if (folded instanceof Integer)
                return "I";
            if (folded instanceof Long)
                return "J";
            if (folded instanceof BigDecimal)
                return "Ljava/math/BigDecimal;";
            if (folded instanceof Double)
                return "D";
            if (folded instanceof Float)
                return "F";
            if (folded instanceof String)
                return OceanTypeSystem.STRING_DESC;

            OceanParser.ExpressionContext e0, e1;
            if (ctx instanceof OceanParser.AddSubExprContext) {
                e0 = ((OceanParser.AddSubExprContext) ctx).expression(0);
                e1 = ((OceanParser.AddSubExprContext) ctx).expression(1);
            } else {
                e0 = ((OceanParser.MulDivModExprContext) ctx).expression(0);
                e1 = ((OceanParser.MulDivModExprContext) ctx).expression(1);
            }
            String left = inferType(e0);
            String right = inferType(e1);
            if (left.equals("Ljava/math/BigDecimal;") || right.equals("Ljava/math/BigDecimal;"))
                return "Ljava/math/BigDecimal;";
            if (ctx instanceof OceanParser.AddSubExprContext
                    && (left.equals(OceanTypeSystem.STRING_DESC) || right.equals(OceanTypeSystem.STRING_DESC)))
                return OceanTypeSystem.STRING_DESC;
            if (left.equals("D") || right.equals("D"))
                return "D";
            if (left.equals("J") || right.equals("J"))
                return "J";
            if (left.equals("F") || right.equals("F"))
                return "F";
            return "I";
        } else if (ctx instanceof OceanParser.UnaryExprContext uCtx) {
            if (uCtx.op.getText().equals("!"))
                return "Z";
            return inferType(uCtx.expression());
        } else if (ctx instanceof OceanParser.MethodCallExprContext mCallCtx) {
            if (mCallCtx.expression() instanceof OceanParser.MemberCallExprContext mCtx) {
                String ownerDesc = inferType(mCtx.expression());
                String memberName = mCtx.anyId().getText();
                String owner = (ownerDesc != null && ownerDesc.startsWith("L") && ownerDesc.length() > 2)
                        ? ownerDesc.substring(1, ownerDesc.length() - 1)
                        : (isPrimitive(ownerDesc) || (ownerDesc != null && ownerDesc.startsWith("[")) ? ownerDesc
                                : "java/lang/Object");

                owner = robustResolveInternalOwner(owner);

                List<String> argTypes = new ArrayList<>();
                if (mCallCtx.argumentList() != null) {
                    for (OceanParser.ExpressionContext arg : getArgumentExpressions(mCallCtx.argumentList())) {
                        argTypes.add(inferType(arg));
                    }
                }
                MethodInfo info = resolveMethodInfo(owner, memberName, argTypes);
                if (info != null && info.descriptor.contains(")")) {
                    return info.descriptor.substring(info.descriptor.lastIndexOf(')') + 1);
                }
                return "V";
            } else {
                String methodName = mCallCtx.expression().getText();
                boolean isSuper = methodName.startsWith("super.");
                if (isSuper)
                    methodName = methodName.substring(6);
                else if (methodName.startsWith("this."))
                    methodName = methodName.substring(5);
                if (methodName.equals("println") || methodName.equals("Output") || NAME_OCEAN_OUTPUT.equals(methodName))
                    return "V";
                if (methodName.equals("Input") || NAME_OCEAN_INPUT.equals(methodName))
                    return "Ljava/util/Scanner;";
                String targetOwner = isSuper ? currentSuperName : getCurrentClassPath();
                String resolvedOwner = findMethodOwner(targetOwner, methodName);
                Map<String, String> classMethods = globalMethodRegistry.getOrDefault(resolvedOwner,
                        Collections.emptyMap());
                String returnType = classMethods.get(methodName);
                if (returnType != null && returnType.startsWith("(")) {
                    returnType = returnType.substring(returnType.lastIndexOf(')') + 1);
                }
                return returnType != null ? returnType : "V";
            }
        } else if (ctx instanceof OceanParser.MemberCallExprContext mCtx) {
            String ownerDesc = inferType(mCtx.expression());
            String memberName = mCtx.anyId().getText();
            if (ownerDesc != null && ownerDesc.startsWith("[") && memberName.equals("length"))
                return "I";
            String owner = (ownerDesc != null && ownerDesc.startsWith("L") && ownerDesc.length() > 2)
                    ? ownerDesc.substring(1, ownerDesc.length() - 1)
                    : (isPrimitive(ownerDesc) || (ownerDesc != null && ownerDesc.startsWith("[")) ? ownerDesc
                            : "java/lang/Object");

            owner = robustResolveInternalOwner(owner);

            boolean isMethodCall = false;
            for (int i = 0; i < mCtx.getChildCount(); i++) {
                if ("(".equals(mCtx.getChild(i).getText())) {
                    isMethodCall = true;
                    break;
                }
            }
            if (isMethodCall) {
                List<String> argTypes = new ArrayList<>();
                if (mCtx.argumentList() != null) {
                    for (OceanParser.ExpressionContext arg : getArgumentExpressions(mCtx.argumentList())) {
                        argTypes.add(inferType(arg));
                    }
                }
                MethodInfo info = resolveMethodInfo(owner, memberName, argTypes);
                if (info != null && info.descriptor.contains(")")) {
                    return info.descriptor.substring(info.descriptor.lastIndexOf(')') + 1);
                }
                return OceanTypeSystem.OBJECT_DESC;
            } else {
                String resolvedOwner = findFieldOwner(owner, memberName);
                String fDesc = globalFieldRegistry.getOrDefault(resolvedOwner, Collections.emptyMap()).get(memberName);
                if (fDesc == null) {
                    fDesc = resolveExternalFieldDescriptor(owner, memberName);
                }
                return fDesc != null ? fDesc : OceanTypeSystem.OBJECT_DESC;
            }
        } else if (ctx instanceof OceanParser.TernaryExprContext) {
            return inferType(((OceanParser.TernaryExprContext) ctx).expression(1));
        } else if (ctx instanceof OceanParser.NullCoalescingExprContext) {
            String t1 = inferType(((OceanParser.NullCoalescingExprContext) ctx).expression(0));
            String t2 = inferType(((OceanParser.NullCoalescingExprContext) ctx).expression(1));
            if (!isPrimitive(t1) && isPrimitive(t2))
                return getWrapperType(t2);
            return t2;
        } else if (ctx instanceof OceanParser.LogicalAndExprContext
                || ctx instanceof OceanParser.LogicalOrExprContext) {
            return "Z";
        } else if (ctx instanceof OceanParser.ComparisonExprContext || ctx instanceof OceanParser.EqualityExprContext) {
            return "Z";
        } else if (ctx instanceof OceanParser.CastExprContext) {
            return getTypeDescriptor(((OceanParser.CastExprContext) ctx).type().getText());
        } else if (ctx instanceof OceanParser.MethodRefExprContext) {
            return currentCastType != null ? currentCastType : OceanTypeSystem.OBJECT_DESC;
        } else if (ctx instanceof OceanParser.SafeMemberCallExprContext sCtx) {
            String ownerDesc = inferType(sCtx.expression());
            String memberName = sCtx.anyId().getText();
            String owner = (ownerDesc != null && ownerDesc.startsWith("L") && ownerDesc.length() > 2)
                    ? ownerDesc.substring(1, ownerDesc.length() - 1)
                    : (isPrimitive(ownerDesc) || (ownerDesc != null && ownerDesc.startsWith("[")) ? ownerDesc
                            : "java/lang/Object");

            // Robust resolution for Ocean internal owner
            if (owner.startsWith("ocean/compiler/stdlib/")) {
                if (!globalMethodRegistry.containsKey(owner)) {
                    String simpleName = owner.substring(owner.lastIndexOf('/') + 1);
                    for (String fqName : globalMethodRegistry.keySet()) {
                        if (fqName.endsWith("/" + simpleName)) {
                            owner = fqName;
                            break;
                        }
                    }
                }
            }

            boolean isMethodCall = false;
            for (int i = 0; i < sCtx.getChildCount(); i++) {
                if ("(".equals(sCtx.getChild(i).getText())) {
                    isMethodCall = true;
                    break;
                }
            }
            String resultType;
            if (isMethodCall) {
                List<String> argTypes = new ArrayList<>();
                if (sCtx.argumentList() != null) {
                    for (OceanParser.ExpressionContext arg : getArgumentExpressions(sCtx.argumentList())) {
                        argTypes.add(inferType(arg));
                    }
                }
                String resolvedOwner = findMethodOwner(owner, memberName);
                Map<String, String> targetMethods = globalMethodRegistry.get(resolvedOwner);
                if (targetMethods != null && targetMethods.containsKey(memberName)) {
                    resultType = targetMethods.get(memberName);
                    if (resultType != null && resultType.contains(")"))
                        resultType = resultType.substring(resultType.lastIndexOf(')') + 1);
                } else {
                    String reflectionDesc = resolveExternalMethodDescriptor(owner, memberName, argTypes);
                    if (reflectionDesc != null) {
                        resultType = reflectionDesc.substring(reflectionDesc.lastIndexOf(')') + 1);
                    } else {
                        String rtr = resolveReturnTypeByReflection(owner, memberName, argTypes.size());
                        resultType = rtr != null ? rtr : OceanTypeSystem.OBJECT_DESC;
                    }
                }
            } else {
                String fDesc = resolveExternalFieldDescriptor(owner, memberName);
                resultType = fDesc != null ? fDesc : OceanTypeSystem.OBJECT_DESC;
            }
            if (isPrimitive(resultType))
                return getWrapperType(resultType);
            return resultType;
        }
        // instanceof → boolean
        if (ctx instanceof OceanParser.InstanceOfExprContext) {
            return "Z";
        }
        // Postfix (x++, x--) → preserve operand type
        if (ctx instanceof OceanParser.PostfixExprContext) {
            return inferType(((OceanParser.PostfixExprContext) ctx).expression());
        }
        // Prefix (++x, --x) → preserve operand type
        if (ctx instanceof OceanParser.PrefixExprContext) {
            return inferType(((OceanParser.PrefixExprContext) ctx).expression());
        }
        // Shift expressions
        if (ctx instanceof OceanParser.ShiftExprContext s) {
            return inferType(s.expression(0));
        }
        // Bit operations
        if (ctx instanceof OceanParser.BitAndExprContext b) {
            return determineCommonType(inferType(b.expression(0)), inferType(b.expression(1)));
        }
        if (ctx instanceof OceanParser.BitOrExprContext b) {
            return determineCommonType(inferType(b.expression(0)), inferType(b.expression(1)));
        }
        if (ctx instanceof OceanParser.BitXorExprContext b) {
            return determineCommonType(inferType(b.expression(0)), inferType(b.expression(1)));
        }
        return "I";
    }

    /**
     * Resolve JVM return descriptor via reflection. {@code arity} is the argument
     * count at the call site;
     * use {@code -1} only when unknown (ambiguous overloads fall back to
     * {@code java/lang/Object}).
     */
    private String resolveReturnTypeByReflection(String owner, String methodName, int arity) {
        String cacheKey = owner + "." + methodName + "#a" + arity;
        String cached = (String) reflectionCache.get(cacheKey);
        if (cached != null)
            return cached;
        try {
            Class<?> cls = Class.forName(owner.replace("/", "."));
            Method bestMatch = null;
            int bestScore = -1;
            for (Method m : cls.getMethods()) {
                if (m.getName().equals(methodName)) {
                    if (arity >= 0 && m.getParameterCount() != arity)
                        continue;
                    int score = 0;
                    if (!m.isBridge())
                        score += 50;
                    if (m.getDeclaringClass() == cls)
                        score += 100;
                    if (score > bestScore) {
                        bestScore = score;
                        bestMatch = m;
                    }
                }
            }
            if (bestMatch != null) {
                String result = getClassDescriptor(bestMatch.getReturnType());
                reflectionCache.put(cacheKey, result);
                return result;
            }
        } catch (Exception ignored) {
        }
        reflectionCache.put(cacheKey, OceanTypeSystem.OBJECT_DESC);
        return OceanTypeSystem.OBJECT_DESC;
    }

    private String resolveExternalFieldDescriptor(String owner, String fieldName) {
        String cacheKey = owner + "#" + fieldName;
        String cached = (String) reflectionCache.get(cacheKey);
        if (cached != null)
            return cached;

        if (isInternalOceanClass(owner)) {
            String robustOwner = robustResolveInternalOwner(owner);
            String fDesc = globalFieldRegistry.getOrDefault(robustOwner, Collections.emptyMap()).get(fieldName);
            if (fDesc != null) {
                reflectionCache.put(cacheKey, fDesc);
                return fDesc;
            }
            return null;
        }

        try {
            Class<?> cls = Class.forName(owner.replace("/", "."));
            Field field = cls.getField(fieldName);
            String result = getClassDescriptor(field.getType());
            reflectionCache.put(cacheKey, result);
            return result;
        } catch (Exception ignored) {
        }
        return null;
    }

    private static class MethodInfo {
        final String descriptor;
        final boolean isStatic;
        final String resolvedOwner;
        boolean isExtension = false;

        MethodInfo(String descriptor, boolean isStatic, String resolvedOwner) {
            this.descriptor = descriptor;
            this.isStatic = isStatic;
            this.resolvedOwner = resolvedOwner;
        }

        MethodInfo(String descriptor, boolean isStatic, String resolvedOwner, boolean isExtension) {
            this.descriptor = descriptor;
            this.isStatic = isStatic;
            this.resolvedOwner = resolvedOwner;
            this.isExtension = isExtension;
        }
    }

    private String resolveExternalMethodDescriptor(String owner, String methodName, List<String> argTypes) {
        MethodInfo info = resolveMethodInfo(owner, methodName, argTypes);
        return info != null ? info.descriptor : null;
    }

    private MethodInfo resolveMethodInfo(String owner, String methodName, List<String> argTypes) {

        String cacheKey = "INFO:" + owner + "." + methodName + "(" + String.join(",", argTypes) + ")";
        Object cached = reflectionCache.get(cacheKey);
        if (cached instanceof MethodInfo)
            return (MethodInfo) cached;

        String currentOwner = owner;
        Set<String> visited = new HashSet<>();
        while (currentOwner != null && visited.add(currentOwner)) {
            if (!isInternalOceanClass(currentOwner)) {
                try {
                    Class<?> cls = Class.forName(currentOwner.replace("/", "."));
                    boolean isConstructor = methodName.equals("<init>");
                    Executable[] methods = isConstructor ? cls.getConstructors() : cls.getMethods();
                    Executable bestMatch = null;
                    int bestScore = -1;
                    for (Executable m : methods) {
                        String mName = isConstructor ? "<init>" : m.getName();
                        if (mName.equals(methodName) && m.getParameterCount() == argTypes.size()) {
                            boolean compatible = true;
                            int score = 0;
                            Class<?>[] params = m.getParameterTypes();
                            for (int i = 0; i < argTypes.size(); i++) {
                                String provided = argTypes.get(i);
                                String expected = getClassDescriptor(params[i]);
                                if (provided.equals(expected)) {
                                    score += 100;
                                } else if (isCompatible(provided, expected)) {
                                    score += 10;
                                } else if (isAssignable(provided, expected)) {
                                    score += 5;
                                } else {
                                    compatible = false;
                                    break;
                                }
                            }
                            if (compatible) {
                                if (score > bestScore) {
                                    bestScore = score;
                                    bestMatch = m;
                                }
                            }
                        }
                    }
                    if (bestMatch != null) {
                        StringBuilder sb = new StringBuilder("(");
                        for (Class<?> p : bestMatch.getParameterTypes()) {
                            sb.append(getClassDescriptor(p));
                        }
                        sb.append(")");
                        if (isConstructor) {
                            sb.append("V");
                        } else {
                            sb.append(getClassDescriptor(((Method) bestMatch).getReturnType()));
                        }
                        boolean isStatic = Modifier.isStatic(bestMatch.getModifiers());
                        MethodInfo info = new MethodInfo(sb.toString(), isStatic, currentOwner);
                        reflectionCache.put(cacheKey, info);
                        return info;
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                }
            } else {
                String robustOwner = robustResolveInternalOwner(currentOwner);
                Map<String, String> targetMethods = globalMethodRegistry.get(robustOwner);
                if (targetMethods != null && targetMethods.containsKey(methodName)) {
                    Map<String, List<String>> overloads = globalOverloadRegistry.get(robustOwner);
                    String bestDesc = null;
                    if (overloads != null && overloads.containsKey(methodName)) {
                        bestDesc = resolveOverload(overloads.get(methodName), argTypes);
                    }

                    if (bestDesc != null) {
                        boolean isStatic = globalMethodStaticity.getOrDefault(robustOwner, Collections.emptyMap())
                                .getOrDefault(methodName, false);
                        MethodInfo info = new MethodInfo(bestDesc, isStatic, robustOwner);
                        reflectionCache.put(cacheKey, info);
                        return info;
                    }
                }
            }

            // Try superclass from registry
            String nextOwner = globalSuperClassRegistry.get(currentOwner);
            if (nextOwner == null && currentOwner.startsWith("ocean/compiler/stdlib/")) {
                nextOwner = "java/lang/Object";
            }
            currentOwner = nextOwner;
        }

        // 3. Try Extension Methods
        String receiverDesc;
        if (isPrimitive(owner) || owner.startsWith("[")) {
            receiverDesc = owner;
        } else {
            receiverDesc = OceanTypeSystem.wrapObjectType(owner);
        }

        Map<String, List<CompilerRegistry.ExtensionMethodInfo>> extensions = globalExtensionMethodRegistry
                .get(receiverDesc);

        if (extensions != null && extensions.containsKey(methodName)) {
            List<CompilerRegistry.ExtensionMethodInfo> candidates = extensions.get(methodName);
            CompilerRegistry.ExtensionMethodInfo bestMatch = null;
            int bestScore = -1;

            for (CompilerRegistry.ExtensionMethodInfo emi : candidates) {
                // Extension method descriptor is (Receiver, Args...)ReturnType
                List<String> paramTypes = parseDescriptorParams(emi.descriptor());
                // First param is receiver, so we compare args from index 1
                if (paramTypes.size() != argTypes.size() + 1)
                    continue;

                boolean compatible = true;
                int score = 0;
                for (int i = 0; i < argTypes.size(); i++) {
                    String provided = argTypes.get(i);
                    String expected = paramTypes.get(i + 1);
                    if (provided.equals(expected))
                        score += 100;
                    else if (isCompatible(provided, expected))
                        score += 10;
                    else if (isAssignable(provided, expected))
                        score += 5;
                    else {
                        compatible = false;
                        break;
                    }
                }

                if (compatible && score > bestScore) {
                    bestScore = score;
                    bestMatch = emi;
                }
            }

            if (bestMatch != null) {
                MethodInfo info = new MethodInfo(bestMatch.descriptor(), true, bestMatch.owner(), true);
                reflectionCache.put(cacheKey, info);
                return info;
            }
        }

        return null;
    }

    /**
     * Resolve the best overload from a list of method descriptors based on argument
     * types.
     * Uses a scoring system: exact match = 100, compatible (widening) = 10,
     * assignable = 5.
     */
    private String resolveOverload(List<String> descriptors, List<String> argTypes) {
        String best = null;
        int bestScore = -1;

        for (String desc : descriptors) {
            List<String> paramTypes = parseDescriptorParams(desc);
            if (paramTypes.size() != argTypes.size())
                continue;

            boolean compatible = true;
            int score = 0;
            for (int i = 0; i < argTypes.size(); i++) {
                String provided = argTypes.get(i);
                String expected = paramTypes.get(i);
                if (provided.equals(expected)) {
                    score += 1000;
                } else if (isCompatible(provided, expected)) {
                    score += 100;
                    // Prefer smaller widening
                    if (provided.equals("I") && expected.equals("J"))
                        score += 10;
                    if (provided.equals("F") && expected.equals("D"))
                        score += 10;
                } else if (isAssignable(provided, expected)) {
                    score += 10;
                } else {
                    compatible = false;
                    break;
                }
            }
            if (compatible && score > bestScore) {
                bestScore = score;
                best = desc;
            }
        }
        return best;
    }

    /**
     * Parse parameter type descriptors from a method descriptor string.
     * e.g. "(II)V" → ["I", "I"], "(Ljava/lang/String;I)V" → ["Ljava/lang/String;",
     * "I"]
     */
    private List<String> parseDescriptorParams(String desc) {
        List<String> params = new ArrayList<>();
        if (desc == null || !desc.startsWith("("))
            return params;
        int i = 1; // skip '('
        while (i < desc.length() && desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0)
                    break;
                params.add(desc.substring(i, end + 1));
                i = end + 1;
            } else if (c == '[') {
                int start = i;
                i++;
                while (i < desc.length() && desc.charAt(i) == '[')
                    i++;
                if (i < desc.length() && desc.charAt(i) == 'L') {
                    int end = desc.indexOf(';', i);
                    if (end < 0)
                        break;
                    params.add(desc.substring(start, end + 1));
                    i = end + 1;
                } else if (i < desc.length()) {
                    params.add(desc.substring(start, i + 1));
                    i++;
                }
            } else {
                // primitive: I, D, F, J, Z, C, S, B
                params.add(String.valueOf(c));
                i++;
            }
        }
        return params;
    }

    private boolean isAssignable(String provided, String expected) {
        if (provided.equals(expected))
            return true;

        // Handle Generics CompilerRegistry.Variance
        if (provided.contains("<") || expected.contains("<")) {
            String pBase = stripGenerics(provided);
            String eBase = stripGenerics(expected);
            if (!isAssignable(pBase, eBase))
                return false;

            // Extract type arguments if both have them
            if (provided.contains("<") && expected.contains("<")) {
                List<String> pArgs = extractTypeArguments(provided);
                List<String> eArgs = extractTypeArguments(expected);

                String pBaseInternal = pBase.substring(1, pBase.length() - 1);
                List<CompilerRegistry.TypeParameterInfo> tps = globalTypeParameterRegistry.get(pBaseInternal);
                if (tps != null && tps.size() == pArgs.size() && pArgs.size() == eArgs.size()) {
                    for (int i = 0; i < tps.size(); i++) {
                        CompilerRegistry.Variance v = tps.get(i).variance;
                        String pA = pArgs.get(i);
                        String eA = eArgs.get(i);
                        if (v == CompilerRegistry.Variance.COVARIANT) {
                            if (!isAssignable(pA, eA))
                                return false;
                        } else if (v == CompilerRegistry.Variance.CONTRAVARIANT) {
                            if (!isAssignable(eA, pA))
                                return false;
                        } else {
                            if (!pA.equals(eA))
                                return false;
                        }
                    }
                    return true;
                }
            }
            return true; // If one side is raw, allow it (Java compatibility)
        }

        if (isCompatible(provided, expected))
            return true;
        if (!provided.startsWith("L") || !expected.startsWith("L"))
            return false;
        String pInternal = provided.substring(1, provided.length() - 1);
        String eInternal = expected.substring(1, expected.length() - 1);
        if (isOceanSubclass(pInternal, eInternal))
            return true;

        // Skip reflection for Ocean classes to avoid loading stale binary classes
        if (isInternalOceanClass(pInternal) || isInternalOceanClass(eInternal)) {
            return false;
        }

        try {
            Class<?> pCls = Class.forName(pInternal.replace("/", "."));
            Class<?> eCls = Class.forName(eInternal.replace("/", "."));
            return eCls.isAssignableFrom(pCls);
        } catch (Throwable ignored) {
            // pCls might be an Ocean class not yet loaded or with broken bytecode,
            // handled by isOceanSubclass above.
        }
        return false;
    }

    private boolean isOceanSubclass(String pInternal, String eInternal) {
        if (pInternal.equals(eInternal))
            return true;
        if (eInternal.equals("java/lang/Object"))
            return true;

        String current = pInternal;
        Set<String> visited = new HashSet<>();
        while (current != null && !current.equals("java/lang/Object") && visited.add(current)) {
            String superCls = globalSuperClassRegistry.get(current);
            if (eInternal.equals(superCls))
                return true;

            String[] interfaces = globalInterfaceRegistry.get(current);
            if (interfaces != null) {
                for (String itf : interfaces) {
                    if (itf.equals(eInternal))
                        return true;
                    // Recursive check for inherited interfaces could be here,
                    // but usually direct implementation is enough for this compiler.
                }
            }
            current = superCls;
        }
        return false;
    }

    private boolean isCompatible(String provided, String expected) {
        if (provided.equals(expected))
            return true;
        if (provided.equals("I") && expected.equals("D"))
            return true;
        if (provided.equals("I") && expected.equals("J"))
            return true;
        if (provided.equals("I") && expected.equals("F"))
            return true;
        if (provided.equals("F") && expected.equals("D"))
            return true;
        if (provided.equals("I") && expected.equals("Ljava/lang/Integer;"))
            return true;
        if (provided.equals("Z") && expected.equals("Ljava/lang/Boolean;"))
            return true;
        if (provided.equals("J") && expected.equals("Ljava/lang/Long;"))
            return true;
        if (provided.equals("D") && expected.equals("Ljava/lang/Double;"))
            return true;
        if (provided.equals("F") && expected.equals("Ljava/lang/Float;"))
            return true;
        if (provided.equals(OceanTypeSystem.STRING_DESC) && expected.equals(OceanTypeSystem.OBJECT_DESC))
            return true;
        if (provided.startsWith("L") && expected.equals(OceanTypeSystem.OBJECT_DESC))
            return true;
        if (provided.startsWith("[") && expected.equals(OceanTypeSystem.OBJECT_DESC))
            return true;
        if (isPrimitive(provided) && expected.equals(OceanTypeSystem.OBJECT_DESC))
            return true;
        return false;
    }

    private static String getClassDescriptor(Class<?> c) {
        if (c.isArray())
            return "[" + getClassDescriptor(c.getComponentType());
        if (c == void.class)
            return "V";
        if (c == int.class)
            return "I";
        if (c == boolean.class)
            return "Z";
        if (c == long.class)
            return "J";
        if (c == double.class)
            return "D";
        if (c == float.class)
            return "F";
        if (c == char.class)
            return "C";
        if (c == byte.class)
            return "B";
        if (c == short.class)
            return "S";
        return OceanTypeSystem.wrapObjectType(c.getName().replace(".", "/"));
    }

    private String stripGenerics(String typeName) {
        if (typeName == null)
            return null;
        if (typeName.endsWith("?")) {
            typeName = typeName.substring(0, typeName.length() - 1);
        }
        typeName = typeName.replace("?", "");
        if (!typeName.contains("<"))
            return typeName;

        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < typeName.length(); i++) {
            char c = typeName.charAt(i);
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

    private String[] stripGenerics(String[] types) {
        if (types == null)
            return null;
        String[] stripped = new String[types.length];
        for (int i = 0; i < types.length; i++)
            stripped[i] = stripGenerics(types[i]);
        return stripped;
    }

    private List<String> extractTypeArguments(String desc) {
        List<String> args = new ArrayList<>();
        int start = desc.indexOf("<");
        int end = desc.lastIndexOf(">");
        if (start == -1 || end == -1)
            return args;
        String content = desc.substring(start + 1, end);

        int depth = 0;
        int last = 0;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '<')
                depth++;
            else if (c == '>')
                depth--;
            else if (c == ',' && depth == 0) {
                args.add(content.substring(last, i).trim());
                last = i + 1;
            }
        }
        args.add(content.substring(last).trim());
        return args;
    }

    private String getIthDescriptor(String paramsOnly, int index) {
        int count = 0;
        int i = 0;
        while (i < paramsOnly.length()) {
            int start = i;
            if (paramsOnly.charAt(i) == '[') {
                while (paramsOnly.charAt(i) == '[')
                    i++;
            }
            if (paramsOnly.charAt(i) == 'L') {
                while (paramsOnly.charAt(i) != ';')
                    i++;
                i++;
            } else {
                i++;
            }
            if (count == index)
                return paramsOnly.substring(start, i);
            count++;
        }
        return "";
    }

    @Override
    public Object visitOceanInputPrimary(OceanParser.OceanInputPrimaryContext ctx) {
        emitNewScannerOnStdin();
        return "Ljava/util/Scanner;";
    }

    private void emitNewScannerOnStdin() {
        currentMethodVisitor.visitMethodInsn(Opcodes.INVOKESTATIC, "ocean/compiler/stdlib/RuntimeUtils",
                "getScanner", "()Ljava/util/Scanner;", false);
    }

    private String findMethodOwner(String ownerPath, String methodName) {
        if (globalMethodRegistry.containsKey(ownerPath)
                && globalMethodRegistry.get(ownerPath).containsKey(methodName)) {
            return ownerPath;
        }
        String superPath = globalSuperClassRegistry.get(ownerPath);
        if (superPath != null && !superPath.equals("java/lang/Object"))
            return findMethodOwner(superPath, methodName);
        return ownerPath;
    }

    private String findFieldOwner(String ownerPath, String fieldName) {
        if (globalFieldRegistry.containsKey(ownerPath) && globalFieldRegistry.get(ownerPath).containsKey(fieldName)) {
            return ownerPath;
        }
        String superPath = globalSuperClassRegistry.get(ownerPath);
        if (superPath != null && !superPath.equals("java/lang/Object"))
            return findFieldOwner(superPath, fieldName);
        return ownerPath;
    }

    private void inheritFields(String ownerPath, Map<String, String> targetMap) {
        String superPath = globalSuperClassRegistry.get(ownerPath);
        if (superPath != null && !superPath.equals("java/lang/Object")) {
            inheritFields(superPath, targetMap);
            Map<String, String> superFields = globalFieldRegistry.get(superPath);
            if (superFields != null) {
                targetMap.putAll(superFields);
            }
        }
    }

    private Object foldConstant(ParseTree ctx) {
        return ConstantFolder.fold(ctx);
    }

    private void emitConditionJump(OceanParser.ExpressionContext expr, Label target, boolean jumpIfTrue) {
        Object res = visit(expr);
        String type = (res instanceof String) ? (String) res : inferType(expr);

        if (type.equals("Z") || type.equals("I")) {
            currentMethodVisitor.visitJumpInsn(jumpIfTrue ? Opcodes.IFNE : Opcodes.IFEQ, target);
        } else if (type.equals("J")) {
            currentMethodVisitor.visitInsn(Opcodes.LCONST_0);
            currentMethodVisitor.visitInsn(Opcodes.LCMP);
            currentMethodVisitor.visitJumpInsn(jumpIfTrue ? Opcodes.IFNE : Opcodes.IFEQ, target);
        } else if (type.equals("D")) {
            currentMethodVisitor.visitInsn(Opcodes.DCONST_0);
            currentMethodVisitor.visitInsn(Opcodes.DCMPG);
            currentMethodVisitor.visitJumpInsn(jumpIfTrue ? Opcodes.IFNE : Opcodes.IFEQ, target);
        } else if (type.equals("F")) {
            currentMethodVisitor.visitInsn(Opcodes.FCONST_0);
            currentMethodVisitor.visitInsn(Opcodes.FCMPG);
            currentMethodVisitor.visitJumpInsn(jumpIfTrue ? Opcodes.IFNE : Opcodes.IFEQ, target);
        } else if (type.startsWith("L") || type.startsWith("[")) {
            if ("Ljava/lang/Boolean;".equals(type)) {
                emitUnboxToPrimitive("Z");
                currentMethodVisitor.visitJumpInsn(jumpIfTrue ? Opcodes.IFNE : Opcodes.IFEQ, target);
            } else {
                currentMethodVisitor.visitJumpInsn(jumpIfTrue ? Opcodes.IFNONNULL : Opcodes.IFNULL, target);
            }
        } else {
            currentMethodVisitor.visitJumpInsn(jumpIfTrue ? Opcodes.IFNE : Opcodes.IFEQ, target);
        }
    }

    /**
     * Returns true if the given expression is the {@code null} literal.
     */
    private boolean isNullLiteral(OceanParser.ExpressionContext expr) {
        if (expr instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) expr).primary();
            return p instanceof OceanParser.NullPrimaryContext;
        }
        return false;
    }

    private void emitNumericCast(String from, String to) {
        if (from == null || from.equals(to))
            return;
        if ((from.startsWith("L") || from.startsWith("[")) && isPrimitive(to)) {
            emitUnboxToPrimitive(to);
            return;
        }
        switch (to) {
            case "D" -> {
                if (from.equals("F"))
                    currentMethodVisitor.visitInsn(Opcodes.F2D);
                else if (from.equals("J"))
                    currentMethodVisitor.visitInsn(Opcodes.L2D);
                else
                    currentMethodVisitor.visitInsn(Opcodes.I2D);
            }
            case "J" -> {
                if (from.equals("D"))
                    currentMethodVisitor.visitInsn(Opcodes.D2L);
                else if (from.equals("F"))
                    currentMethodVisitor.visitInsn(Opcodes.F2L);
                else
                    currentMethodVisitor.visitInsn(Opcodes.I2L);
            }
            case "F" -> {
                if (from.equals("D"))
                    currentMethodVisitor.visitInsn(Opcodes.D2F);
                else if (from.equals("J"))
                    currentMethodVisitor.visitInsn(Opcodes.L2F);
                else
                    currentMethodVisitor.visitInsn(Opcodes.I2F);
            }
            case "I", "Z" -> {
                switch (from) {
                    case "D" -> currentMethodVisitor.visitInsn(Opcodes.D2I);
                    case "J" -> currentMethodVisitor.visitInsn(Opcodes.L2I);
                    case "F" -> currentMethodVisitor.visitInsn(Opcodes.F2I);
                }
            }
        }
    }

    private boolean isConstantInt(OceanParser.ExpressionContext expr) {
        return getConstantInt(expr) != null;
    }

    private Integer getConstantInt(OceanParser.ExpressionContext expr) {
        Object val = foldConstant(expr);
        return (val instanceof Integer) ? (Integer) val : null;
    }

    private boolean isExternalResource(String name) {
        if (importedClasses.containsKey(name) || getJavaLangDefault(name) != null)
            return true;
        String desc = getTypeDescriptor(name);
        return desc.startsWith("L") && !desc.startsWith("Locean/compiler/generated/");
    }

    private String inferReturnTypeFromBlock(OceanParser.BlockContext ctx) {
        if (ctx == null)
            return "V";
        for (OceanParser.StatementContext stmt : ctx.statement()) {
            if (stmt instanceof OceanParser.ReturnStmtContext) {
                OceanParser.ReturnStatementContext ret = ((OceanParser.ReturnStmtContext) stmt).returnStatement();
                if (ret.expression() == null)
                    return "V";
                return inferType(ret.expression());
            }
            if (stmt instanceof OceanParser.IfStmtContext) {
                OceanParser.IfStatementContext ifStmt = ((OceanParser.IfStmtContext) stmt).ifStatement();
                OceanParser.StatementContext thenStmt = ifStmt.statement(0);
                if (thenStmt instanceof OceanParser.BlockStmtContext) {
                    String r = inferReturnTypeFromBlock(((OceanParser.BlockStmtContext) thenStmt).block());
                    if (!r.equals("V"))
                        return r;
                }
                if (ifStmt.statement().size() > 1) {
                    OceanParser.StatementContext elseStmt = ifStmt.statement(1);
                    if (elseStmt instanceof OceanParser.BlockStmtContext) {
                        String r = inferReturnTypeFromBlock(((OceanParser.BlockStmtContext) elseStmt).block());
                        if (!r.equals("V"))
                            return r;
                    }
                }
            }
            if (stmt instanceof OceanParser.BlockStmtContext) {
                String r = inferReturnTypeFromBlock(((OceanParser.BlockStmtContext) stmt).block());
                if (!r.equals("V"))
                    return r;
            }
        }
        return "V";
    }

    private boolean isAlwaysTerminating(OceanParser.StatementContext ctx) {
        switch (ctx) {
            case null -> {
                return false;
            }
            case OceanParser.ReturnStmtContext returnStmtContext -> {
                return true;
            }
            case OceanParser.StopStmtContext stopStmtContext -> {
                return true;
            }
            case OceanParser.SkipStmtContext skipStmtContext -> {
                return true;
            }
            case OceanParser.BlockStmtContext blockStmtContext -> {
                for (OceanParser.StatementContext s : blockStmtContext.block().statement()) {
                    if (isAlwaysTerminating(s))
                        return true;
                }
            }
            default -> {
            }
        }
        if (ctx instanceof OceanParser.IfStmtContext) {
            OceanParser.IfStatementContext ifCtx = ((OceanParser.IfStmtContext) ctx).ifStatement();
            if (ifCtx.statement().size() > 1) { // has else
                return isAlwaysTerminating(ifCtx.statement(0)) && isAlwaysTerminating(ifCtx.statement(1));
            }
            // Constant folding check for if(true) return ...
            Object folded = foldConstant(ifCtx.expression());
            if (folded instanceof Boolean && (Boolean) folded) {
                return isAlwaysTerminating(ifCtx.statement(0));
            }
        }
        return false;
    }

    private boolean isIdOrParenthesizedId(OceanParser.ExpressionContext expr) {
        if (expr instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) expr).primary();
            if (p instanceof OceanParser.IdPrimaryContext)
                return true;
            if (p instanceof OceanParser.ParenthesizedPrimaryContext) {
                return isIdOrParenthesizedId(((OceanParser.ParenthesizedPrimaryContext) p).expression());
            }
        }
        return false;
    }

    private String getBaseIdentifier(OceanParser.ExpressionContext expr) {
        if (expr instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) expr).primary();
            if (p instanceof OceanParser.IdPrimaryContext) {
                return ((OceanParser.IdPrimaryContext) p).anyId().getText();
            }
            if (p instanceof OceanParser.ParenthesizedPrimaryContext) {
                return getBaseIdentifier(((OceanParser.ParenthesizedPrimaryContext) p).expression());
            }
        }
        return null;
    }

    private boolean isInternalOceanClass(String internalName) {
        if (internalName == null)
            return false;
        // If it's in our source registry, it's definitely an Ocean source class
        if (globalMethodRegistry.containsKey(internalName) || globalFieldRegistry.containsKey(internalName)) {
            return true;
        }
        // Ocean-source classes are always generated in a sub-package matching their
        // name
        // e.g. ocean/compiler/generated/MyClass/MyClass
        if (internalName.startsWith("ocean/compiler/stdlib/")) {
            String subPath = internalName.substring("ocean/compiler/stdlib/".length());
            return subPath.contains("/");
        }
        return false;
    }

    private String robustResolveInternalOwner(String internalOwner) {
        if (internalOwner == null)
            return null;
        if (isInternalOceanClass(internalOwner)) {
            if (!globalMethodRegistry.containsKey(internalOwner) && !globalFieldRegistry.containsKey(internalOwner)) {
                String simpleName = internalOwner.substring(internalOwner.lastIndexOf('/') + 1);
                for (String fqName : globalMethodRegistry.keySet()) {
                    if (fqName.endsWith("/" + simpleName)) {
                        return fqName;
                    }
                }
                for (String fqName : globalFieldRegistry.keySet()) {
                    if (fqName.endsWith("/" + simpleName)) {
                        return fqName;
                    }
                }
            }
        }
        return internalOwner;
    }

    private int getLoadOpcode(String desc) {
        if (desc == null)
            return Opcodes.ALOAD;
        return switch (desc) {
            case "I", "Z", "B", "S", "C" -> Opcodes.ILOAD;
            case "J" -> Opcodes.LLOAD;
            case "F" -> Opcodes.FLOAD;
            case "D" -> Opcodes.DLOAD;
            default -> Opcodes.ALOAD;
        };
    }

    private int getStoreOpcode(String desc) {
        if (desc == null)
            return Opcodes.ASTORE;
        return switch (desc) {
            case "I", "Z", "B", "S", "C" -> Opcodes.ISTORE;
            case "J" -> Opcodes.LSTORE;
            case "F" -> Opcodes.FSTORE;
            case "D" -> Opcodes.DSTORE;
            default -> Opcodes.ASTORE;
        };
    }

    private String getTypeDescriptor(String type) {
        if (type == null)
            return OceanTypeSystem.OBJECT_DESC;
        type = stripGenerics(type);

        // If already a descriptor, return as-is
        if (type.startsWith("[") || (type.startsWith("L") && type.endsWith(";")))
            return type;

        // Aggressive Erasure: If it's a known type parameter, it's ALWAYS Object in
        // bytecode.
        if (currentMethodTypeParameters.contains(type))
            return OceanTypeSystem.OBJECT_DESC;

        if (currentClassName != null) {
            String fullPath = getCurrentClassPath();
            List<CompilerRegistry.TypeParameterInfo> infos = globalTypeParameterRegistry.get(fullPath);
            if (infos != null) {
                for (CompilerRegistry.TypeParameterInfo info : infos) {
                    if (info.name.equals(type))
                        return info.getErasedType();
                }
            }
        }

        Set<String> typeParamNames = new HashSet<>(currentMethodTypeParameters);
        if (currentClassName != null) {
            String fullPath = getCurrentClassPath();
            List<CompilerRegistry.TypeParameterInfo> infos = globalTypeParameterRegistry.get(fullPath);
            if (infos != null) {
                for (CompilerRegistry.TypeParameterInfo info : infos) {
                    typeParamNames.add(info.name);
                }
            }
        }
        // Check aliases/auto-imports directly to avoid recursion
        String stripped = stripGenerics(type);
        String builtin = OCEAN_TYPE_ALIASES.get(stripped);
        if (builtin != null) {
            return OceanTypeSystem.wrapObjectType(builtin);
        }

        return SymbolTable.getDescriptor(type, importedClasses, typeParamNames);
    }

    public static List<OceanParser.ExpressionContext> getArgumentExpressions(OceanParser.ArgumentListContext argList) {
        List<OceanParser.ExpressionContext> list = new ArrayList<>();
        if (argList != null && argList.argument() != null) {
            for (OceanParser.ArgumentContext a : argList.argument()) {
                if (a.expression() != null) list.add(a.expression());
            }
        }
        return list;
    }
}
