package ocean.compiler;

import ocean.compiler.ir.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.WildcardType;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.GenericArrayType;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import ocean.compiler.OceanBaseVisitor;
import ocean.compiler.OceanParser;
import ocean.compiler.OceanLexer;
/**
 * AST'den IR'a dönüşüm yapan sınıf.
 */
public class IRGenerator extends OceanBaseVisitor<IRNode> {
    private final String currentFile;
    private final Map<String, String> importedClasses;
    private final List<String> importedWildcards;
    private SymbolTable symbolTable;
    private String currentClassName;
    private String currentSuperName;
    private String currentMethodReturnType;
    private String currentMethodName;
    private String currentMethodDescriptor;
    private String packageName;
    private final Set<String> topLevelTypes = new HashSet<>();
    private String currentCastType = null;
    private int lambdaCounter = 0;
    private final List<IRMethod> syntheticLambdaMethods = new ArrayList<>();
    private final List<CompilerRegistry.TypeParameterInfo> currentMethodTypeParamInfos = new ArrayList<>();
    private boolean insideStaticContext = false;
    private boolean insideDataClass = false;
    private boolean insideEnumWithConstantBodies = false;
    private int anonClassCounter = 0;
    private int tempVarCounter = 0;
    private final List<IRClass> anonymousClasses = new ArrayList<>();
    private final List<IRNode> compiledTypes = new ArrayList<>();
    private ParserRuleContext currentCtx = null;
    private final Deque<String> activeEnumSwitchTypes = new ArrayDeque<>();
    private static final Pattern SANITIZE_NAME_PATTERN = Pattern.compile("[^a-zA-Z0-9_$]");
    private final Map<String, String> importedStaticMembers = new HashMap<>();
    private final List<String> importedStaticWildcards = new ArrayList<>();

    // ===== Static Caches =====
    private static final Object NO_CLASS_SENTINEL = new Object();
    private static final ConcurrentHashMap<String, Object> reflectionClassCache = new ConcurrentHashMap<>();
    // key: "owner#name#totalArgs", value: descriptor or "" for unknown
    private static final ConcurrentHashMap<String, String> reflectionMethodDescCache = new ConcurrentHashMap<>();
    // key: "owner#totalArgs", value: descriptor or "" for unknown
    private static final ConcurrentHashMap<String, String> reflectionCtorDescCache = new ConcurrentHashMap<>();
    // key: "owner#name", value: "true"/"false"
    private static final ConcurrentHashMap<String, String> reflectionStaticityCache = new ConcurrentHashMap<>();
    // key: methodDesc, value: List of param descriptors
    private static final ConcurrentHashMap<String, List<String>> paramDescriptorsCache = new ConcurrentHashMap<>();

    public static void clearCaches() {
        reflectionClassCache.clear();
        reflectionMethodDescCache.clear();
        reflectionCtorDescCache.clear();
        reflectionStaticityCache.clear();
        paramDescriptorsCache.clear();
    }

    public IRGenerator(String currentFile, String packageName, Map<String, String> importedClasses, SymbolTable symbolTable) {
        this(currentFile, packageName, importedClasses, null, null, null, symbolTable);
    }

    public IRGenerator(String currentFile, String packageName, Map<String, String> importedClasses, List<String> importedWildcards, Map<String, String> importedStaticMembers, List<String> importedStaticWildcards, SymbolTable symbolTable) {
        this.currentFile = currentFile;
        this.packageName = packageName;
        this.importedClasses = importedClasses;
        this.importedWildcards = importedWildcards != null ? importedWildcards : new ArrayList<>();
        if (importedStaticMembers != null) this.importedStaticMembers.putAll(importedStaticMembers);
        if (importedStaticWildcards != null) this.importedStaticWildcards.addAll(importedStaticWildcards);
        this.symbolTable = symbolTable;
    }

    // ---- State Getters (for IRSemanticAnalyzer injection) ----
    public Map<String, String> getImportedClasses() {
        return importedClasses;
    }

    public List<String> getImportedWildcards() {
        return importedWildcards;
    }

    public Map<String, String> getImportedStaticMembers() {
        return importedStaticMembers;
    }

    public List<String> getImportedStaticWildcards() {
        return importedStaticWildcards;
    }

    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    private IRExpression ensureExpr(IRNode node) {
        if (node instanceof IRExpression) return (IRExpression) node;
        if (node != null)
            throw new IllegalStateException("AST node did not evaluate to an expression: " + node.getClass().getName());
        return new IRLiteral(null, OceanTypeSystem.OBJECT_DESC);
    }

    /**
     * Class.forName wrapper that uses the thread context classloader so that
     * external JARs added via -cp (e.g. Gson, OkHttp) are visible during
     * compilation / IR-generation.  Replaces every bare Class.forName(name)
     * call in this class.
     */
    private static Class<?> forName(String name) throws ClassNotFoundException {
        Object cached = reflectionClassCache.get(name);
        if (cached == NO_CLASS_SENTINEL) throw new ClassNotFoundException(name);
        if (cached instanceof Class<?> cls) return cls;
        try {
            Class<?> cls = OceanTypeSystem.forName(name);
            if (cls == null) {
                reflectionClassCache.put(name, NO_CLASS_SENTINEL);
                throw new ClassNotFoundException(name);
            }
            reflectionClassCache.put(name, cls);
            return cls;
        } catch (ClassNotFoundException e) {
            reflectionClassCache.put(name, NO_CLASS_SENTINEL);
            throw e;
        }
    }

    @Override
    public IRNode visit(ParseTree tree) {
        ParserRuleContext oldCtx = currentCtx;
        if (tree instanceof ParserRuleContext) currentCtx = (ParserRuleContext) tree;
        CompilationSession session = CompilationSession.getActiveSession();
        String oldPkg = null;
        String oldFile = null;
        if (session != null && packageName != null) {
            oldPkg = session.getCurrentPackage();
            oldFile = session.getCurrentFile();
            session.setCurrentFile(currentFile);
            session.setCurrentPackage(packageName.replace('/', '.'));
        }
        try {
            IRNode result = super.visit(tree);
            if (result != null && currentCtx != null && currentCtx.getStart() != null) {
                result.setLocation(currentCtx.getStart().getLine(), currentCtx.getStart().getCharPositionInLine());
            }
            return result;
        } finally {
            currentCtx = oldCtx;
            if (session != null && packageName != null) {
                session.setCurrentPackage(oldPkg);
                session.setCurrentFile(oldFile);
            }
        }
    }


    private String getFullClassName(String simpleName) {
        if (simpleName == null) return null;
        if (packageName != null && !packageName.isEmpty()) {
            String pkgPrefix = packageName.replace('.', '/') + "/";
            if (simpleName.startsWith(pkgPrefix)) return simpleName;
            return pkgPrefix + simpleName;
        }
        return simpleName;
    }

    private String resolveTypeName(String name) {
        if (name == null) return "java/lang/Object";
        name = name.trim();
        if (TypeChecker.isClassType(name)) {
            name = name.substring(1, name.length() - 1).trim();
        }
        if (name.equals("null")) return "java/lang/Object";
        if (name.contains("<")) {
            name = name.substring(0, name.indexOf("<")).trim();
        }
        if (symbolTable != null && symbolTable.getTypeParams() != null && symbolTable.getTypeParams().contains(name)) {
            String erased = getErasedTypeParameter(name);
            if (TypeChecker.isClassType(erased)) return erased.substring(1, erased.length() - 1);
            return erased;
        }
        if (name.isEmpty()) {
            return "java/lang/Object";
        }
        if (name.contains("/")) {
            return name;
        }
        String tempReturn = OceanTypeSystem.word2TypeForPrimitive(name);
        if (tempReturn != null) {
            return tempReturn;
        }
        if (name.contains(".")) {
            try {
                forName(name);
                return name.replace(".", "/");
            } catch (ClassNotFoundException ignored) {
            }
            if (currentClassName != null) {
                String chain = currentClassName.replace('.', '/') + "$" + name.replace('.', '$');
                if (CompilerRegistry.globalMethodRegistry.containsKey(chain) ||
                    CompilerRegistry.globalSuperClassRegistry.containsKey(chain) ||
                    CompilerRegistry.globalIsInterfaceSet.contains(chain)) {
                    return chain;
                }
            }
            int lastDot = name.lastIndexOf(".");
            String outerCandidate = name.substring(0, lastDot).trim();
            String innerName = name.substring(lastDot + 1).trim();
            String resolvedOuter = resolveTypeName(outerCandidate);
            if (resolvedOuter != null) {
                String candidateChain = resolvedOuter + "$" + innerName;
                if (CompilerRegistry.globalMethodRegistry.containsKey(candidateChain) ||
                    CompilerRegistry.globalSuperClassRegistry.containsKey(candidateChain) ||
                    CompilerRegistry.globalIsInterfaceSet.contains(candidateChain) ||
                    CompilerRegistry.globalClassAccess.containsKey(candidateChain) ||
                    CompilerRegistry.globalDataClassSet.contains(candidateChain) ||
                    CompilerRegistry.globalClassAccess.containsKey(resolvedOuter) ||
                    CompilerRegistry.globalMethodRegistry.containsKey(resolvedOuter) ||
                    !resolvedOuter.equals(outerCandidate)) {
                    return candidateChain;
                }
            }
            return name.replace(".", "/");
        }
        if (importedClasses != null && importedClasses.containsKey(name)) {
            return importedClasses.get(name).replace(".", "/");
        }

        if (currentClassName != null) {
            String curr = currentClassName.replace('.', '/');
            while (!curr.isEmpty()) {
                String chain = curr + "$" + name.replace('.', '$');
                if (CompilerRegistry.globalMethodRegistry.containsKey(chain) ||
                    CompilerRegistry.globalSuperClassRegistry.containsKey(chain) ||
                    CompilerRegistry.globalIsInterfaceSet.contains(chain) ||
                    CompilerRegistry.globalClassAccess.containsKey(chain) ||
                    CompilerRegistry.globalDataClassSet.contains(chain)) {
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

        if (topLevelTypes.contains(name)) {
            return getFullClassName(name);
        }

        // Check current package first before aggressive global scan
        String localFq = getFullClassName(name);
        if (CompilerRegistry.globalMethodRegistry.containsKey(localFq) || CompilerRegistry.globalFieldRegistry.containsKey(localFq) || CompilerRegistry.globalSuperClassRegistry.containsKey(localFq) || CompilerRegistry.globalIsInterfaceSet.contains(localFq) || CompilerRegistry.globalDataClassSet.contains(localFq) || CompilerRegistry.globalSealedClassSet.contains(localFq)) {
            return localFq;
        }

        if (name.length() == 1) {
            if ("IZDFJBCSV".indexOf(name.charAt(0)) >= 0) {
                return name;
            }
            return "java/lang/Object";
        }

        // Standard library and java.lang resolution (ocean.stdlib.* and java.lang.*)
        String stdCandidate = OceanTypeSystem.resolveStandardClassPath(name);
        if (stdCandidate != null) {
            return stdCandidate;
        }

        // Scan pre-scanned compilation units — tüm adayları topla (deterministik çözümleme)
        String currentPkg = this.packageName;
        Set<String> candidates = new LinkedHashSet<>();
        for (String fqName : CompilerRegistry.globalMethodRegistry.keySet()) {
            if ((fqName.endsWith("/" + name) || fqName.endsWith("$" + name)) && isAccessibleCandidate(fqName, currentPkg))
                candidates.add(fqName);
        }
        for (String fqName : CompilerRegistry.globalFieldRegistry.keySet()) {
            if ((fqName.endsWith("/" + name) || fqName.endsWith("$" + name)) && isAccessibleCandidate(fqName, currentPkg))
                candidates.add(fqName);
        }
        for (String fqName : CompilerRegistry.globalSuperClassRegistry.keySet()) {
            if ((fqName.endsWith("/" + name) || fqName.endsWith("$" + name)) && isAccessibleCandidate(fqName, currentPkg))
                candidates.add(fqName);
        }
        if (!candidates.isEmpty()) {
            if (candidates.size() > 1) {
                List<String> sortedCandidates = new ArrayList<>(candidates);
                Collections.sort(sortedCandidates);
                reportError(currentCtx, "Ambiguous class name '" + name + "': " + sortedCandidates.size() + " possible matches found (" + String.join(", ", sortedCandidates) + "). Use an explicit import to resolve ambiguity.", "TypeResolver");
                return sortedCandidates.getFirst();
            }
            return candidates.iterator().next();
        }

        // Check Wildcard Imports
        if (importedWildcards != null) {
            for (String wild : importedWildcards) {
                String testFq = wild.replace(".", "/") + "/" + name;
                if (CompilerRegistry.globalMethodRegistry.containsKey(testFq) || CompilerRegistry.globalSuperClassRegistry.containsKey(testFq) || CompilerRegistry.globalIsInterfaceSet.contains(testFq)) {
                    return testFq;
                }
                if (OceanTypeSystem.hasClass(testFq)) {
                    return testFq;
                }
            }
        }

        // Implicit package imports — driven by CompilerRegistry.implicitImportPrefixes
        // so that new prefixes can be added without touching IRGenerator source code.
        for (String prefix : CompilerRegistry.implicitImportPrefixes) {
            String candidate = prefix + name;
            String result = OceanTypeSystem.resolveStandardClassPath(name);
            if (result != null && result.equals(candidate.replace('.', '/'))) {
                return result;
            }
            // Direct prefix check via OceanTypeSystem cache
            String prefixInternal = prefix.replace('.', '/');
            String fqInternal = prefixInternal + name;
            if (OceanTypeSystem.hasClass(candidate)) {
                return fqInternal;
            }
        }

        return getFullClassName(name);
    }

    private boolean isAccessibleCandidate(String fqName, String currentPkg) {
        if (!CompilerRegistry.globalMethodRegistry.containsKey(fqName)) return true;

        String targetPkg = "";
        int lastSlash = fqName.lastIndexOf('/');
        if (lastSlash != -1) targetPkg = fqName.substring(0, lastSlash);
        if (currentPkg != null && !targetPkg.equals(currentPkg.replace('.', '/'))) {
            int access = CompilerRegistry.globalClassAccess.getOrDefault(fqName, Opcodes.ACC_PUBLIC);
            return (access & Opcodes.ACC_PUBLIC) != 0;
        }
        return true;
    }

    @Override
    public IRNode visitProgram(OceanParser.ProgramContext ctx) {
        CompilationSession session = CompilationSession.getActiveSession();
        if (session != null) {
            session.resetAnonClassCounters();
        } else {
            anonClassCounter = 0;
        }
        anonymousClasses.clear();
        topLevelTypes.clear();
        for (OceanParser.CompilationUnitContext cu : ctx.compilationUnit()) {
            if (cu.classDeclaration() != null) topLevelTypes.add(cu.classDeclaration().anyId().getText());
            else if (cu.interfaceDeclaration() != null) topLevelTypes.add(cu.interfaceDeclaration().anyId().getText());
            else if (cu.enumDeclaration() != null) topLevelTypes.add(cu.enumDeclaration().anyId().getText());
            else if (cu.annotationDeclaration() != null) topLevelTypes.add(cu.annotationDeclaration().anyId().getText());
        }

        List<IRNode> allTypes = new ArrayList<>();
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG] IRGenerator visiting program with " + ctx.compilationUnit().size() + " compilation units");
        }
        for (OceanParser.CompilationUnitContext cu : ctx.compilationUnit()) {
            String savedPkg = this.packageName;
            if (cu.packageDeclaration() != null) {
                this.packageName = extractPackage(cu.packageDeclaration());
            }
            try {
                preScan(cu);
            } finally {
                this.packageName = savedPkg;
            }
        }

        for (OceanParser.CompilationUnitContext cu : ctx.compilationUnit()) {
            String savedPkg = this.packageName;
            if (cu.packageDeclaration() != null) {
                this.packageName = extractPackage(cu.packageDeclaration());
            }
            try {
                IRNode node = visit(cu);
                if (node instanceof IRCompilationUnit) {
                    List<IRNode> types = ((IRCompilationUnit) node).getTypes();
                    if (Boolean.getBoolean("ocean.debug")) {
                        System.out.println("[DEBUG] Compilation unit returned " + types.size() + " types");
                    }
                    allTypes.addAll(types);
                }
            } finally {
                this.packageName = savedPkg;
            }
        }
        allTypes.addAll(anonymousClasses);
        return new IRCompilationUnit(allTypes);
    }

    private void preScan(OceanParser.CompilationUnitContext ctx) {
        if (ctx.classDeclaration() != null) {
            OceanParser.ClassDeclarationContext c = ctx.classDeclaration();
            String name = c.anyId().getText();
            String fullPath = getFullClassName(name);

            String superPath = "java/lang/Object";
            if (c.type() != null) {
                superPath = resolveTypeName(c.type().getText());
            }
            CompilerRegistry.globalSuperClassRegistry.put(fullPath, superPath);

            Map<String, String> methods = CompilerRegistry.globalMethodRegistry.computeIfAbsent(fullPath, k -> new HashMap<>());
            if (c.memberDeclaration() != null) {
                for (OceanParser.MemberDeclarationContext m : c.memberDeclaration()) {
                    if (m.methodDeclaration() instanceof OceanParser.NormalMethodContext nm) {
                        if (nm.anyId() == null) continue;
                        String mName = nm.anyId().getText();
                        if (mName.equals(name)) mName = "<init>";
                        String ret = (nm.type() != null && !nm.type().isEmpty()) ? getTypeDescriptor(nm.type(0).getText()) : "V";
                        if (mName.equals("<init>")) ret = "V";
                        StringBuilder desc = new StringBuilder("(");
                        if (nm.parameterList() != null) {
                            desc.append(buildParameterTypesDescriptor(nm.parameterList()));
                        }
                        desc.append(")").append(ret);
                        methods.put(mName, desc.toString());

                        boolean isStatic = ModifierHelper.isStatic(nm.modifier());
                        CompilerRegistry.globalMethodStaticity.computeIfAbsent(fullPath, k -> new HashMap<>()).put(mName, isStatic);
                    } else if (m.constructorDeclaration() != null) {
                        OceanParser.ConstructorDeclarationContext cd = m.constructorDeclaration();
                        if (cd.anyId() == null) continue;
                        String ctorName = cd.anyId().getText();
                        boolean isRealConstructor = ctorName.equals(name);
                        String mName = isRealConstructor ? "<init>" : ctorName;
                        boolean isStatic = mName.equals("main");
                        StringBuilder desc = new StringBuilder("(");
                        if (isStatic && (cd.parameterList() == null || cd.parameterList().parameter().isEmpty())) {
                            desc.append("[Ljava/lang/String;");
                        } else {
                            if (cd.parameterList() != null) {
                                for (OceanParser.ParameterContext p : cd.parameterList().parameter()) {
                                    desc.append(getTypeDescriptor(p.type().getText()));
                                }
                            }
                        }
                        desc.append(")V");
                        methods.put(mName, desc.toString());
                        CompilerRegistry.globalMethodStaticity.computeIfAbsent(fullPath, k -> new HashMap<>()).put(mName, isStatic);
                    } else if (m.fieldDeclaration() != null) {
                        OceanParser.FieldDeclarationContext fd = m.fieldDeclaration();
                        boolean isStatic = ModifierHelper.isStatic(fd.modifier());
                        String typeText = fd.type() != null ? fd.type().getText() : null;
                        String declaredType = (typeText != null && !typeText.equals("value") && !typeText.equals("variable") && !typeText.equals("var")) ? getTypeDescriptor(typeText) : null;
                        for (OceanParser.VariableDeclaratorContext d : fd.variableDeclarator()) {
                            String fName = d.anyId().getText();
                            String fType = OceanTypeSystem.OBJECT_DESC;
                            if (declaredType != null) {
                                fType = declaredType;
                            } else if (d.expression() != null) {
                                String oldClassName = currentClassName;
                                currentClassName = fullPath;
                                fType = inferType(d.expression());
                                currentClassName = oldClassName;
                            } else {
                                Map<String, String> existingMap = CompilerRegistry.globalFieldRegistry.get(fullPath);
                                if (existingMap != null && existingMap.containsKey(fName) && existingMap.get(fName) != null) {
                                    fType = existingMap.get(fName);
                                }
                            }

                            CompilerRegistry.globalFieldRegistry.computeIfAbsent(fullPath, k -> new HashMap<>()).put(fName, fType);
                            CompilerRegistry.globalFieldStaticity.computeIfAbsent(fullPath, k -> new HashMap<>()).put(fName, isStatic);

                            if (Boolean.getBoolean("ocean.debug")) {
                                System.out.println("[DEBUG] preScan registered field: " + fName + " in " + fullPath + " (static: " + isStatic + ")");
                            }
                        }
                    }
                }
            }
        } else if (ctx.interfaceDeclaration() != null) {
            OceanParser.InterfaceDeclarationContext ic = ctx.interfaceDeclaration();
            if (ic.anyId() == null) return;
            String name = ic.anyId().getText();
            String fullPath = getFullClassName(name);
            CompilerRegistry.globalIsInterfaceSet.add(fullPath);
            CompilerRegistry.globalIsInterfaceSet.add(name);
            String oldClass = currentClassName;
            currentClassName = fullPath;
            CompilationSession session = CompilationSession.getActiveSession();
            String oldClassFqcn = null;
            if (session != null) {
                oldClassFqcn = session.getCurrentClassFqcn();
                session.setCurrentClassFqcn(fullPath);
            }
            try {
                Map<String, String> methods = CompilerRegistry.globalMethodRegistry.computeIfAbsent(fullPath, k -> new HashMap<>());
                if (ic.memberDeclaration() != null) {
                    for (OceanParser.MemberDeclarationContext m : ic.memberDeclaration()) {
                        if (m.methodDeclaration() instanceof OceanParser.NormalMethodContext nm) {
                            if (nm.anyId() == null) continue;
                            String mName = nm.anyId().getText();
                            String ret = (nm.type() != null && !nm.type().isEmpty()) ? getTypeDescriptor(nm.type(0).getText()) : "V";
                            StringBuilder desc = new StringBuilder("(");
                            if (nm.parameterList() != null) {
                                desc.append(buildParameterTypesDescriptor(nm.parameterList()));
                              /*  for (OceanParser.ParameterContext p : nm.parameterList().parameter()) {
                                    desc.append(getTypeDescriptor(p.type().getText()));
                                }*/
                            }
                            desc.append(")").append(ret);
                            methods.put(mName, desc.toString());
                        }
                    }
                }
            } finally {
                currentClassName = oldClass;
                if (session != null) {
                    session.setCurrentClassFqcn(oldClassFqcn);
                }
            }
        } else if (ctx.enumDeclaration() != null) {
            OceanParser.EnumDeclarationContext ec = ctx.enumDeclaration();
            String name = ec.anyId().getText();
            String fullPath = getFullClassName(name);
            CompilerRegistry.globalSuperClassRegistry.put(fullPath, "java/lang/Enum");

            List<String> interfaces = new ArrayList<>();
            if (ec.typeList() != null) {
                for (OceanParser.TypeContext tc : ec.typeList().type()) {
                    interfaces.add(resolveTypeName(tc.getText()));
                }
            }
            CompilerRegistry.globalInterfaceRegistry.put(fullPath, interfaces.toArray(new String[0]));

            Map<String, String> methods = CompilerRegistry.globalMethodRegistry.computeIfAbsent(fullPath, k -> new HashMap<>());
            methods.put("values", "()[L" + fullPath + ";");
            methods.put("valueOf", "(Ljava/lang/String;)L" + fullPath + ";");
            CompilerRegistry.globalMethodStaticity.computeIfAbsent(fullPath, k -> new HashMap<>()).put("values", true);
            CompilerRegistry.globalMethodStaticity.computeIfAbsent(fullPath, k -> new HashMap<>()).put("valueOf", true);

            List<String> constNames = new ArrayList<>();
            if (ec.enumConstants() != null) {
                for (OceanParser.EnumConstantContext c : ec.enumConstants().enumConstant()) {
                    constNames.add(c.anyId().getText());
                }
            }
            CompilerRegistry.globalEnumConstants.put(fullPath, constNames);

            if (ec.memberDeclaration() != null) {
                for (OceanParser.MemberDeclarationContext m : ec.memberDeclaration()) {
                    if (m.methodDeclaration() instanceof OceanParser.NormalMethodContext nm) {
                        if (nm.anyId() == null) continue;
                        String mName = nm.anyId().getText();
                        String ret = (nm.type() != null && !nm.type().isEmpty()) ? getTypeDescriptor(nm.type(0).getText()) : "V";
                        StringBuilder desc = new StringBuilder("(");
                        if (nm.parameterList() != null) {
                            desc.append(buildParameterTypesDescriptor(nm.parameterList()));
                           /* for (OceanParser.ParameterContext p : nm.parameterList().parameter()) {
                                desc.append(getTypeDescriptor(p.type().getText()));
                            }*/
                        }
                        desc.append(")").append(ret);
                        methods.put(mName, desc.toString());
                        boolean isStatic = ModifierHelper.isStatic(nm.modifier());
                        CompilerRegistry.globalMethodStaticity.computeIfAbsent(fullPath, k -> new HashMap<>()).put(mName, isStatic);
                    } else if (m.constructorDeclaration() != null) {
                        OceanParser.ConstructorDeclarationContext cd = m.constructorDeclaration();
                        if (cd.anyId() == null) continue;
                        StringBuilder desc = new StringBuilder("(Ljava/lang/String;I");
                        if (cd.parameterList() != null) {
                            for (OceanParser.ParameterContext p : cd.parameterList().parameter()) {
                                desc.append(getTypeDescriptor(p.type().getText()));
                            }
                        }
                        desc.append(")V");
                        methods.put("<init>", desc.toString());
                        CompilerRegistry.globalMethodStaticity.computeIfAbsent(fullPath, k -> new HashMap<>()).put("<init>", false);
                    } else if (m.fieldDeclaration() != null) {
                        OceanParser.FieldDeclarationContext fd = m.fieldDeclaration();
                        boolean isStatic = ModifierHelper.isStatic(fd.modifier());
                        String declaredType = (fd.type() != null) ? getTypeDescriptor(fd.type().getText()) : OceanTypeSystem.OBJECT_DESC;
                        for (OceanParser.VariableDeclaratorContext d : fd.variableDeclarator()) {
                            String fName = d.anyId().getText();
                            CompilerRegistry.globalFieldRegistry.computeIfAbsent(fullPath, k -> new HashMap<>()).put(fName, declaredType);
                            CompilerRegistry.globalFieldStaticity.computeIfAbsent(fullPath, k -> new HashMap<>()).put(fName, isStatic);
                        }
                    }
                }
            }
        } else if (ctx.annotationDeclaration() != null) {
            OceanParser.AnnotationDeclarationContext a = ctx.annotationDeclaration();
            String name = a.anyId().getText();
            String fullPath = getFullClassName(name);
            CompilerRegistry.globalSuperClassRegistry.put(fullPath, "java/lang/Object");
            CompilerRegistry.globalInterfaceRegistry.put(fullPath, new String[] { "java/lang/annotation/Annotation" });
            CompilerRegistry.globalIsInterfaceSet.add(fullPath);
            CompilerRegistry.globalAbstractClassSet.add(fullPath);
            Map<String, String> methods = CompilerRegistry.globalMethodRegistry.computeIfAbsent(fullPath, k -> new HashMap<>());
            if (a.annotationMemberDeclaration() != null) {
                for (OceanParser.AnnotationMemberDeclarationContext m : a.annotationMemberDeclaration()) {
                    String mName = m.anyId().getText();
                    String ret = getTypeDescriptor(m.type() != null ? m.type().getText() : "void");
                    methods.put(mName, "()" + ret);
                    CompilerRegistry.globalMethodStaticity.computeIfAbsent(fullPath, k -> new HashMap<>()).put(mName, false);
                }
            }
        }
    }

    private String extractPackage(OceanParser.PackageDeclarationContext ctx) {
        if (ctx == null || ctx.anyId() == null || ctx.anyId().isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ctx.anyId().size(); i++) {
            if (i > 0) sb.append("/");
            sb.append(ctx.anyId(i).getText());
        }
        return sb.toString();
    }

    @Override
    public IRNode visitCompilationUnit(OceanParser.CompilationUnitContext ctx) {
        String savedPkg = this.packageName;
        if (ctx.packageDeclaration() != null) {
            this.packageName = extractPackage(ctx.packageDeclaration());
        }
        CompilationSession session = CompilationSession.getActiveSession();
        String prevFile = session != null ? session.getCurrentFile() : null;
        String prevPkg = session != null ? session.getCurrentPackage() : null;
        if (session != null) {
            session.setCurrentFile(currentFile);
            if (packageName != null && !packageName.isEmpty()) {
                session.setCurrentPackage(packageName.replace('/', '.'));
            } else {
                session.setCurrentPackage("");
            }
        }
        try {
            compiledTypes.clear();
            if (ctx.classDeclaration() != null) visit(ctx.classDeclaration());
            if (ctx.interfaceDeclaration() != null) visit(ctx.interfaceDeclaration());
            if (ctx.enumDeclaration() != null) visit(ctx.enumDeclaration());
            if (ctx.annotationDeclaration() != null) visit(ctx.annotationDeclaration());
            return new IRCompilationUnit(new ArrayList<>(compiledTypes));
        } finally {
            if (session != null) {
                session.setCurrentFile(prevFile);
                session.setCurrentPackage(prevPkg);
            }
            this.packageName = savedPkg;
        }
    }

    @Override
    public IRNode visitInterfaceDeclaration(OceanParser.InterfaceDeclarationContext ctx) {
        String simpleName = ctx.anyId().getText();
        String oldClass = currentClassName;
        String fullName;
        if (oldClass != null) {
            fullName = oldClass + "$" + simpleName;
        } else {
            fullName = getFullClassName(simpleName);
        }
        CompilerRegistry.globalIsInterfaceSet.add(fullName);
        CompilerRegistry.globalIsInterfaceSet.add(simpleName);
        currentClassName = fullName;
        CompilationSession session = CompilationSession.getActiveSession();
        String oldClassFqcn = null;
        if (session != null) {
            oldClassFqcn = session.getCurrentClassFqcn();
            session.setCurrentClassFqcn(fullName);
        }

        List<IRMethod> oldSynthetic = new ArrayList<>(syntheticLambdaMethods);
        syntheticLambdaMethods.clear();

        List<String> superInterfaces = new ArrayList<>();
        if (ctx.typeList() != null) {
            for (OceanParser.TypeContext tc : ctx.typeList().type()) {
                superInterfaces.add(resolveTypeName(tc.getText()));
            }
        }

        List<IRMethod> methods = new ArrayList<>();
        List<IRField> fields = new ArrayList<>();
        List<IRBlock> staticBlocks = new ArrayList<>();
        for (OceanParser.MemberDeclarationContext member : ctx.memberDeclaration()) {
            if (member.constructorDeclaration() != null) {
                reportError(member.constructorDeclaration(), "Interfaces cannot declare constructors.");
            }
            if (member.block() != null) {
                if (member.STATIC() == null) {
                    reportError(member, "Interfaces cannot declare instance initializer blocks.");
                } else {
                    IRNode blockNode = visit(member.block());
                    if (blockNode instanceof IRBlock b) {
                        staticBlocks.add(b);
                    }
                }
            }
            if (member.fieldDeclaration() != null) {
                IRNode node = visit(member.fieldDeclaration());
                if (node instanceof IRField f) {
                    fields.add(f);
                } else if (node instanceof ocean.compiler.ir.IRFieldGroup group) {
                    fields.addAll(group.getFields());
                }
            }
            if (member.methodDeclaration() != null) {
                IRNode methodNode = visit(member.methodDeclaration());
                if (methodNode instanceof IRMethod m) {
                    methods.add(m);
                }
            }
            if (member.classDeclaration() != null || member.interfaceDeclaration() != null || member.enumDeclaration() != null) {
                visit(member);
            }
        }

        methods.addAll(syntheticLambdaMethods);
        syntheticLambdaMethods.clear();
        syntheticLambdaMethods.addAll(oldSynthetic);

        IRInterface irInterface = new IRInterface(fullName, superInterfaces, methods, fields);
        for (IRBlock sb : staticBlocks) {
            irInterface.addStaticBlock(sb);
        }
        if (CompilerRegistry.globalSealedClassSet.contains(fullName) || CompilerRegistry.globalSealedClassSet.contains(simpleName)) {
            irInterface.setSealed(true);
            List<String> permitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(fullName);
            if (permitted == null) permitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(simpleName);
            if (permitted != null) {
                for (String p : permitted) {
                    String cleanP = resolveTypeName(p);
                    irInterface.addPermittedSubclass(cleanP != null ? cleanP : p);
                }
            }
        }
        String interStatus = CompilerRegistry.globalSubclassStatusRegistry.get(fullName);
        if (interStatus == null) interStatus = CompilerRegistry.globalSubclassStatusRegistry.get(simpleName);
        if ("non-sealed".equals(interStatus)) {
            irInterface.setNonSealed(true);
        }

        List<IRAnnotation> interfaceAnnos = parseAnnotations(ctx.annotation());
        for (IRAnnotation a : interfaceAnnos) irInterface.addAnnotation(a);

        compiledTypes.add(irInterface);
        currentClassName = oldClass;
        if (session != null) {
            session.setCurrentClassFqcn(oldClassFqcn);
        }
        return irInterface;
    }

    @Override
    public IRNode visitAnnotationDeclaration(OceanParser.AnnotationDeclarationContext ctx) {
        String simpleName = ctx.anyId().getText();
        String fullName = getFullClassName(simpleName);
        String oldClass = currentClassName;
        currentClassName = fullName;
        CompilationSession session = CompilationSession.getActiveSession();
        String oldClassFqcn = null;
        if (session != null) {
            oldClassFqcn = session.getCurrentClassFqcn();
            session.setCurrentClassFqcn(fullName);
        }

        List<String> interfaces = new ArrayList<>();
        interfaces.add("java/lang/annotation/Annotation");

        IRClass irClass = new IRClass(fullName, "java/lang/Object", interfaces, true);
        int accessFlags = Opcodes.ACC_PUBLIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        irClass.setAccessFlags(accessFlags);

        List<IRAnnotation> declAnnos = parseAnnotations(ctx.annotation());
        Set<String> targets = new HashSet<>();
        String retention = "RUNTIME";
        boolean isRepeatable = false;
        boolean hasExplicitRetention = false;

        for (IRAnnotation a : declAnnos) {
            irClass.addAnnotation(a);
            String desc = a.getTypeDescriptor();
            String cleanDesc = TypeChecker.cleanDescriptor(desc);
            if (cleanDesc != null && cleanDesc.startsWith("L") && cleanDesc.endsWith(";")) {
                cleanDesc = cleanDesc.substring(1, cleanDesc.length() - 1);
            }
            if (cleanDesc != null && (cleanDesc.equals("java/lang/annotation/Target") || cleanDesc.equals("Target"))) {
                IRExpression val = a.getElements().get("value");
                if (val == null) val = a.getElements().get("val");
                if (val instanceof IRVariableAccess va) {
                    targets.add(va.getName());
                } else if (val instanceof IRArrayLiteral arr) {
                    for (IRExpression elem : arr.getElements()) {
                        if (elem instanceof IRVariableAccess va) {
                            targets.add(va.getName());
                        }
                    }
                }
            } else if (cleanDesc != null && (cleanDesc.equals("java/lang/annotation/Retention") || cleanDesc.equals("Retention"))) {
                hasExplicitRetention = true;
                IRExpression val = a.getElements().get("value");
                if (val == null) val = a.getElements().get("val");
                if (val instanceof IRVariableAccess va) {
                    retention = va.getName();
                }
            } else if (cleanDesc != null && (cleanDesc.equals("java/lang/annotation/Repeatable") || cleanDesc.equals("Repeatable"))) {
                isRepeatable = true;
            }
        }

        if (!hasExplicitRetention) {
            // Add @Retention(RetentionPolicy.RUNTIME) automatically so custom annotations are visible at runtime by default
            IRAnnotation retentionAnno = new IRAnnotation("Ljava/lang/annotation/Retention;");
            retentionAnno.addElement("value", new IRVariableAccess("RUNTIME", "Ljava/lang/annotation/RetentionPolicy;", true, "java/lang/annotation/RetentionPolicy", true));
            irClass.addAnnotation(retentionAnno);
        }

        Map<String, CompilerRegistry.AnnotationMemberInfo> memberInfoMap = new LinkedHashMap<>();
        if (ctx.annotationMemberDeclaration() != null) {
            for (OceanParser.AnnotationMemberDeclarationContext member : ctx.annotationMemberDeclaration()) {
                String mName = member.anyId().getText();
                String mType = member.type() != null ? member.type().getText() : "void";
                String retDesc = getTypeDescriptor(mType);
                String mDesc = "()" + retDesc;
                boolean hasDefault = (member.DEFAULT() != null && member.expression() != null);
                memberInfoMap.put(mName, new CompilerRegistry.AnnotationMemberInfo(mName, retDesc, hasDefault));

                IRExpression defaultExpr = null;
                if (hasDefault) {
                    defaultExpr = (IRExpression) visit(member.expression());
                    if (retDesc != null && retDesc.startsWith("[") && defaultExpr instanceof IRArrayLiteral arrLit && arrLit.getElements().isEmpty()) {
                        arrLit.setTypeDescriptor(retDesc);
                    }
                }

                IRMethod method = new IRMethod(mName, mDesc, false, true, null);
                method.setAccessFlags(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT);
                if (defaultExpr != null) {
                    method.setDefaultValue(defaultExpr);
                }
                irClass.addMethod(method);
            }
        }

        CompilerRegistry.AnnotationTypeInfo typeInfo = new CompilerRegistry.AnnotationTypeInfo(fullName, targets, retention, isRepeatable, memberInfoMap);
        CompilerRegistry.globalAnnotationTypeRegistry.put(fullName, typeInfo);
        CompilerRegistry.globalAnnotationTypeRegistry.put(simpleName, typeInfo);

        compiledTypes.add(irClass);
        currentClassName = oldClass;
        if (session != null) {
            session.setCurrentClassFqcn(oldClassFqcn);
        }
        return irClass;
    }

    private List<IRAnnotation> parseAnnotations(List<OceanParser.AnnotationContext> annoCtxs) {
        List<IRAnnotation> result = new ArrayList<>();
        if (annoCtxs == null || annoCtxs.isEmpty()) return result;
        for (OceanParser.AnnotationContext ac : annoCtxs) {
            IRAnnotation anno = parseSingleAnnotation(ac);
            if (anno != null) {
                result.add(anno);
            }
        }
        return result;
    }

    private IRAnnotation parseSingleAnnotation(OceanParser.AnnotationContext ac) {
        if (ac == null) return null;
        String name = ac.typeName().getText();
        String desc = getTypeDescriptor(name);
        IRAnnotation anno = new IRAnnotation(desc);
        if (ac.annotationElement() != null) {
            for (OceanParser.AnnotationElementContext elem : ac.annotationElement()) {
                String key = (elem.anyId() != null) ? elem.anyId().getText() : "value";
                IRExpression valExpr = null;
                if (elem.annotation() != null) {
                    valExpr = parseSingleAnnotation(elem.annotation());
                } else if (elem.expression() != null) {
                    valExpr = (IRExpression) visit(elem.expression());
                }
                if (valExpr != null) {
                    anno.addElement(key, valExpr);
                }
            }
        }
        return anno;
    }

    @Override
    public IRNode visitMemberDeclaration(OceanParser.MemberDeclarationContext ctx) {
        List<IRAnnotation> annos = parseAnnotations(ctx.annotation());
        if (ctx.STATIC() != null && ctx.block() != null) {
            IRNode bNode = visit(ctx.block());
            if (bNode instanceof IRBlock block) {
                block.setStatic(true);
            }
            return bNode;
        }
        if (ctx.block() != null) {
            String currentClassPath = currentClassName != null ? currentClassName.replace('.', '/') : "";
            if (CompilerRegistry.globalIsInterfaceSet.contains(currentClassPath) || ClassMetadataCache.isInterface(currentClassPath)) {
                reportError(ctx.block(), "Interfaces cannot declare instance initializer blocks. Only 'static { ... }' blocks are supported.");
                return null;
            }
            IRNode bNode = visit(ctx.block());
            if (bNode instanceof IRBlock block) {
                block.setStatic(false);
            }
            return bNode;
        }
        IRNode node;
        if (ctx.methodDeclaration() != null) {
            node = visit(ctx.methodDeclaration());
            if (node instanceof IRMethod) {
                for (IRAnnotation a : annos) ((IRMethod) node).addAnnotation(a);
            }
            return node;
        }
        if (ctx.fieldDeclaration() != null) {
            node = visit(ctx.fieldDeclaration());
            if (node instanceof IRField) {
                for (IRAnnotation a : annos) ((IRField) node).addAnnotation(a);
            }
            return node;
        }
        if (ctx.constructorDeclaration() != null) {
            node = visit(ctx.constructorDeclaration());
            if (node instanceof IRMethod) {
                for (IRAnnotation a : annos) ((IRMethod) node).addAnnotation(a);
            }
            return node;
        }
        if (ctx.classDeclaration() != null) {
            node = visit(ctx.classDeclaration());
            if (node instanceof IRClass) {
                for (IRAnnotation a : annos) ((IRClass) node).addAnnotation(a);
            }
            return node;
        }
        if (ctx.interfaceDeclaration() != null) {
            node = visit(ctx.interfaceDeclaration());
            return node;
        }
        if (ctx.enumDeclaration() != null) {
            node = visit(ctx.enumDeclaration());
            return node;
        }
        if (ctx.annotationDeclaration() != null) {
            node = visit(ctx.annotationDeclaration());
            if (node instanceof IRClass) {
                for (IRAnnotation a : annos) ((IRClass) node).addAnnotation(a);
            }
            return node;
        }
        return null;
    }

    @Override
    public IRNode visitEnumDeclaration(OceanParser.EnumDeclarationContext ctx) {
        String simpleName = ctx.anyId().getText();
        String oldClass = currentClassName;
        String fullName;
        if (oldClass != null) {
            fullName = oldClass + "$" + simpleName;
        } else {
            fullName = getFullClassName(simpleName);
        }
        currentClassName = fullName;
        CompilationSession session = CompilationSession.getActiveSession();
        String oldClassFqcn = null;
        if (session != null) {
            oldClassFqcn = session.getCurrentClassFqcn();
            session.setCurrentClassFqcn(fullName);
        }

        String oldSuper = currentSuperName;
        currentSuperName = "java/lang/Enum";

        List<String> constants = new ArrayList<>();
        List<List<IRExpression>> constantArguments = new ArrayList<>();
        List<String> constantClassNames = new ArrayList<>();
        List<IRClass> constantSubclasses = new ArrayList<>();
        int anonIndex = 0;

        if (ctx.enumConstants() != null) {
            for (OceanParser.EnumConstantContext ec : ctx.enumConstants().enumConstant()) {
                String cName = ec.anyId().getText();
                constants.add(cName);
                List<IRExpression> args = new ArrayList<>();
                if (ec.argumentList() != null) {
                    List<OceanParser.ExpressionContext> argExprs = getArgumentExpressions(ec.argumentList());
                    int totalArgs = argExprs.size();
                    for (int i = 0; i < totalArgs; i++) {
                        OceanParser.ExpressionContext exprCtx = argExprs.get(i);
                        String expectedType = getExpectedCtorParamType(fullName, i, totalArgs);
                        String oldCast = currentCastType;
                        if (expectedType != null) {
                            currentCastType = expectedType;
                        }
                        args.add(ensureExpr(visit(exprCtx)));
                        currentCastType = oldCast;
                    }
                }
                constantArguments.add(args);

                if (ec.LBRACE() != null) {
                    anonIndex++;
                    String subName = fullName + "$" + anonIndex;
                    constantClassNames.add(subName);

                    IRClass subClass = new IRClass(subName, fullName, Collections.emptyList(), false);
                    subClass.setAccessFlags(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM | Opcodes.ACC_SUPER);

                    String prevClass = currentClassName;
                    String prevSuper = currentSuperName;
                    currentClassName = subName;
                    currentSuperName = fullName;
                    if (session != null) session.setCurrentClassFqcn(subName);

                    for (OceanParser.MemberDeclarationContext member : ec.memberDeclaration()) {
                        IRNode res = visit(member);
                        if (res instanceof IRField f) subClass.addField(f);
                        else if (res instanceof IRFieldGroup fg) {
                            for (IRField f : fg.getFields()) subClass.addField(f);
                        } else if (res instanceof IRMethod m) {
                            subClass.addMethod(m);
                        }
                    }

                    constantSubclasses.add(subClass);

                    currentClassName = prevClass;
                    currentSuperName = prevSuper;
                    if (session != null) session.setCurrentClassFqcn(fullName);
                } else {
                    constantClassNames.add(null);
                }
            }
        }

        List<IRField> fields = new ArrayList<>();
        List<IRMethod> methods = new ArrayList<>();

        boolean hasConstantBodies = anonIndex > 0;
        boolean oldInsideEnumWithConstantBodies = insideEnumWithConstantBodies;
        insideEnumWithConstantBodies = hasConstantBodies;
        try {
            if (ctx.memberDeclaration() != null) {
                for (OceanParser.MemberDeclarationContext member : ctx.memberDeclaration()) {
                    IRNode res = visit(member);
                    if (res instanceof IRField) fields.add((IRField) res);
                    else if (res instanceof IRFieldGroup fg) {
                        fields.addAll(fg.getFields());
                    }
                    else if (res instanceof IRMethod) methods.add((IRMethod) res);
                }
            }
        } finally {
            insideEnumWithConstantBodies = oldInsideEnumWithConstantBodies;
        }

        List<String> interfaces = new ArrayList<>();
        if (ctx.typeList() != null) {
            for (OceanParser.TypeContext tc : ctx.typeList().type()) {
                interfaces.add(resolveTypeName(tc.getText()));
            }
        }
        CompilerRegistry.globalInterfaceRegistry.put(fullName, interfaces.toArray(new String[0]));

        IREnum irEnum = new IREnum(fullName, constants, constantArguments, fields, methods, interfaces, constantClassNames);
        List<IRAnnotation> enumAnnos = parseAnnotations(ctx.annotation());
        for (IRAnnotation a : enumAnnos) irEnum.addAnnotation(a);

        compiledTypes.add(irEnum);
        compiledTypes.addAll(constantSubclasses);

        currentClassName = oldClass;
        currentSuperName = oldSuper;
        if (session != null) {
            session.setCurrentClassFqcn(oldClassFqcn);
        }
        return irEnum;
    }

    @Override
    public IRNode visitClassDeclaration(OceanParser.ClassDeclarationContext ctx) {
        Set<String> outerClassParams = new HashSet<>(symbolTable.getTypeParams());
        List<String> classParams = new ArrayList<>();
        if (ctx.typeParameter() != null) {
            for (OceanParser.TypeParameterContext tp : ctx.typeParameter()) {
                if (tp != null && tp.anyId() != null) {
                    classParams.add(tp.anyId().getText());
                }
            }
        }
        symbolTable.getTypeParams().clear();
        symbolTable.getTypeParams().addAll(classParams);
        String simpleName = ctx.anyId().getText();
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG] IRGenerator visiting class: " + simpleName);
        }
        String oldClass = currentClassName;
        if (oldClass != null) {
            currentClassName = oldClass + "$" + simpleName;
        } else {
            currentClassName = getFullClassName(simpleName);
        }
        CompilationSession session = CompilationSession.getActiveSession();
        String oldClassFqcn = null;
        if (session != null) {
            oldClassFqcn = session.getCurrentClassFqcn();
            session.setCurrentClassFqcn(currentClassName);
        }

        String oldSuper = currentSuperName;
        currentSuperName = "java/lang/Object";
        if (ctx.type() != null) {
            currentSuperName = resolveTypeName(ctx.type().getText());
        }
        String superName = currentSuperName;
        final String capturedOldClassFqcn = oldClassFqcn;

        List<String> interfaces = new ArrayList<>();
        if (ctx.typeList() != null) {
            for (OceanParser.TypeContext tc : ctx.typeList().type()) {
                interfaces.add(resolveTypeName(tc.getText()));
            }
        }

        boolean isAbstractClass = ModifierHelper.isAbstract(ctx.modifier());
        boolean isDataClass = ModifierHelper.isData(ctx.modifier());
        boolean oldInsideDataClass = insideDataClass;
        insideDataClass = isDataClass;

        IRClass irClass = new IRClass(currentClassName, superName, interfaces, isAbstractClass);
        if (oldClass != null) {
            irClass.setEnclosingClass(oldClass);
            if (currentMethodName != null) {
                irClass.setLocal(true);
                irClass.setEnclosingMethod(oldClass, currentMethodName, currentMethodDescriptor);
            }
        }
        CompilerRegistry.globalSuperClassRegistry.put(currentClassName, superName);
        CompilerRegistry.globalInterfaceRegistry.put(currentClassName, interfaces.toArray(new String[0]));
        if (ctx.type() != null) {
            String rawSuper = ctx.type().getText();
            if (rawSuper.contains("<")) {
                if (!CompilerRegistry.globalSuperClassGenericSignatureRegistry.containsKey(currentClassName)
                        && !CompilerRegistry.globalSuperClassGenericSignatureRegistry.containsKey(simpleName)) {
                    int ltIdx = rawSuper.indexOf('<');
                    String inner = rawSuper.substring(ltIdx);
                    CompilerRegistry.globalSuperClassGenericSignatureRegistry.put(currentClassName, superName + inner);
                }
            }
        }
        if (isDataClass || CompilerRegistry.globalDataClassSet.contains(currentClassName)) {
            irClass.setDataClass(true);
        }

        if (CompilerRegistry.globalClassAccess.containsKey(currentClassName)) {
            irClass.setAccessFlags(CompilerRegistry.globalClassAccess.get(currentClassName));
        }

        if (CompilerRegistry.globalSealedClassSet.contains(currentClassName) || CompilerRegistry.globalSealedClassSet.contains(simpleName)) {
            irClass.setSealed(true);
            List<String> permitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(currentClassName);
            if (permitted == null) permitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(simpleName);
            if (permitted != null) {
                for (String p : permitted) {
                    String cleanP = resolveTypeName(p);
                    irClass.addPermittedSubclass(cleanP != null ? cleanP : p);
                }
            }
        }
        String status = CompilerRegistry.globalSubclassStatusRegistry.get(currentClassName);
        if (status == null) status = CompilerRegistry.globalSubclassStatusRegistry.get(simpleName);
        if ("non-sealed".equals(status)) {
            irClass.setNonSealed(true);
        }

        List<IRAnnotation> classAnnos = parseAnnotations(ctx.annotation());
        for (IRAnnotation a : classAnnos) irClass.addAnnotation(a);

        List<IRMethod> oldSynthetic = new ArrayList<>(syntheticLambdaMethods);
        syntheticLambdaMethods.clear();

        // 1. If data class, generate fields for parameters first
        if (isDataClass && ctx.parameterList() != null) {
            for (OceanParser.ParameterContext p : ctx.parameterList().parameter()) {
                String pName = p.anyId().getText();
                String pType = p.type() != null ? p.type().getText() : "Object";
                String pDesc = getTypeDescriptor(pType);
                if (p.ELLIPSIS() != null) {
                    pDesc = "[" + pDesc;
                }
                boolean isFinal = p.FINAL() != null;
                IRField field = new IRField(pName, pDesc, false, null);
                field.setAccessFlags(Opcodes.ACC_PUBLIC | (isFinal ? Opcodes.ACC_FINAL : 0));
                field.setFinal(isFinal);
                List<IRAnnotation> fieldAnnos = parseAnnotations(p.annotation());
                for (IRAnnotation a : fieldAnnos) field.addAnnotation(a);
                irClass.addField(field);
            }
        }

        // Synthetic this$0 field for non-static inner classes
        if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(currentClassName)) {
            String outerFqcn = CompilerRegistry.globalInnerClassUsedOuterMap.get(currentClassName);
            IRField this0Field = new IRField("this$0", "L" + outerFqcn + ";", false, null);
            this0Field.setAccessFlags(Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC);
            irClass.addField(this0Field);
        }

        if (ctx.memberDeclaration() != null) {
            boolean hasConstructor = false;
            for (OceanParser.MemberDeclarationContext mCtx : ctx.memberDeclaration()) {
                IRNode node = visit(mCtx);
                if (node instanceof IRMethod) {
                    irClass.addMethod((IRMethod) node);
                    if (((IRMethod) node).getName().equals("<init>")) hasConstructor = true;
                } else if (node instanceof IRField field) {
                    irClass.addField(field);
                    if (!field.isStatic()) {
                        irClass.addInstanceInitializer(field);
                    } else {
                        irClass.addStaticInitializer(field);
                    }
                } else if (node instanceof ocean.compiler.ir.IRFieldGroup group) {
                    for (IRField f : group.getFields()) {
                        irClass.addField(f);
                        if (!f.isStatic()) {
                            irClass.addInstanceInitializer(f);
                        } else {
                            irClass.addStaticInitializer(f);
                        }
                    }
                } else if (node instanceof IRBlock block) {
                    if (block.isStatic()) {
                        irClass.addStaticBlock(block);
                        irClass.addStaticInitializer(block);
                    } else {
                        irClass.addInstanceBlock(block);
                        irClass.addInstanceInitializer(block);
                    }
                }
            }

            boolean isInterfaceOrAnno = (irClass.getAccessFlags() & Opcodes.ACC_INTERFACE) != 0;
            if (!hasConstructor && !isDataClass && !isInterfaceOrAnno) {
                // Add default constructor
                IRBlock body = new IRBlock(new ArrayList<>());
                String ctorDesc = "()V";
                IRMethod ctor;
                if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(currentClassName)) {
                    String outerFqcn = CompilerRegistry.globalInnerClassUsedOuterMap.get(currentClassName);
                    ctorDesc = "(L" + outerFqcn + ";)V";
                    ctor = new IRMethod("<init>", ctorDesc, false, false, body);
                    ctor.addParameter(new IRMethod.IRParameter("this$0", "L" + outerFqcn + ";"));
                } else {
                    ctor = new IRMethod("<init>", ctorDesc, false, false, body);
                }
                ctor.setAccessFlags(irClass.getAccessFlags() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED));
                irClass.addMethod(ctor);
            }
        }

        // 2. Generate constructors, getters, setters, equals, hashCode, toString for data class
        if (isDataClass) {
            // Getter and setters for all instance fields (constructor params + body fields)
            for (IRField field : irClass.getFields()) {
                if (field.isStatic()) continue;
                String fName = field.getName();
                String fDesc = field.getTypeDescriptor();
                String capName = fName.substring(0, 1).toUpperCase(Locale.ENGLISH) + fName.substring(1);

                // Getter
                boolean hasGetter = irClass.getMethods().stream().anyMatch(m -> m.getName().equals("get" + capName));
                if (!hasGetter) {
                    IRMethod getter = getIrMethod(fName, fDesc, capName);
                    irClass.addMethod(getter);
                }

                // Setter
                boolean hasSetter = irClass.getMethods().stream().anyMatch(m -> m.getName().equals("set" + capName));
                if (!hasSetter && !field.isFinal()) {
                    IRMethod setter = getMethod(fName, fDesc, capName);
                    setter.addParameter(new IRMethod.IRParameter("val", fDesc));
                    setter.setAccessFlags(Opcodes.ACC_PUBLIC);
                    irClass.addMethod(setter);
                }
            }

            // Constructor for primary parameters
            String dataClassCtorDesc = "()V";
            boolean hasVarargs = false;
            if (ctx.parameterList() != null) {
                List<IRMethod.IRParameter> ctorParams = new ArrayList<>();
                List<IRStatement> ctorBodyStats = new ArrayList<>();
                int paramIndex = 0;
                for (OceanParser.ParameterContext p : ctx.parameterList().parameter()) {
                    String pName = p.anyId().getText();
                    String pType = p.type() != null ? p.type().getText() : "Object";
                    String pDesc = getTypeDescriptor(pType);
                    if (p.ELLIPSIS() != null) {
                        hasVarargs = true;
                        pDesc = "[" + pDesc;
                    }
                    String paramVarName = "p" + paramIndex;
                    List<IRAnnotation> paramAnnos = parseAnnotations(p.annotation());
                    ctorParams.add(new IRMethod.IRParameter(paramVarName, pDesc, paramAnnos));

                    // Assignment statement: this.pName = p_index;
                    IRAssignment assign = getIrAssignment(pName, pDesc, paramVarName);
                    ctorBodyStats.add(new IRExprStatement(assign));
                    paramIndex++;
                }

                StringBuilder ctorDesc = new StringBuilder("(");
                for (IRMethod.IRParameter param : ctorParams) {
                    ctorDesc.append(param.typeDescriptor());
                }
                ctorDesc.append(")V");
                dataClassCtorDesc = ctorDesc.toString();
                String targetCtorDesc = dataClassCtorDesc;
                boolean hasMatchingCtor = irClass.getMethods().stream().anyMatch(m -> m.getName().equals("<init>") && targetCtorDesc.equals(m.getDescriptor()));
                if (!hasMatchingCtor) {
                    IRMethod ctor = new IRMethod("<init>", dataClassCtorDesc, false, false, new IRBlock(ctorBodyStats));
                    for (IRMethod.IRParameter param : ctorParams) {
                        ctor.addParameter(param);
                    }
                    ctor.setAccessFlags(Opcodes.ACC_PUBLIC | (hasVarargs ? Opcodes.ACC_VARARGS : 0));
                    irClass.addMethod(ctor);
                } else {
                    IRMethod matchingCtor = irClass.getMethods().stream()
                            .filter(m -> m.getName().equals("<init>") && targetCtorDesc.equals(m.getDescriptor()))
                            .findFirst().orElse(null);
                    if (matchingCtor != null) {
                        matchingCtor.setAccessFlags(matchingCtor.getAccessFlags() | (hasVarargs ? Opcodes.ACC_VARARGS : 0));
                    }
                    if (matchingCtor != null && matchingCtor.getBody() instanceof IRBlock block) {
                        List<IRStatement> stats = new ArrayList<>(block.getStatements());
                        int insertIdx = 0;
                        for (int i = 0; i < stats.size(); i++) {
                            IRStatement st = stats.get(i);
                            if (st instanceof IRExprStatement exprSt && exprSt.getExpression() instanceof IRMethodCall mc) {
                                if (mc.isSuperCall() || mc.isThisCall()) {
                                    insertIdx = i + 1;
                                    break;
                                }
                            }
                        }
                        List<IRMethod.IRParameter> ctorParamsList = matchingCtor.getParameters();
                        for (int i = 0; i < ctx.parameterList().parameter().size(); i++) {
                            OceanParser.ParameterContext p = ctx.parameterList().parameter(i);
                            String pName = p.anyId().getText();
                            String pType = p.type() != null ? p.type().getText() : "Object";
                            String pDesc = getTypeDescriptor(pType);
                            if (p.ELLIPSIS() != null) pDesc = "[" + pDesc;
                            String argParamName = (i < ctorParamsList.size()) ? ctorParamsList.get(i).name() : ("p" + i);
                            IRAssignment assign = getIrAssignment(pName, pDesc, argParamName);
                            stats.add(insertIdx++, new IRExprStatement(assign));
                        }
                        matchingCtor.setBody(new IRBlock(stats));
                    }
                }
            } else {
                // Add default empty constructor
                boolean hasEmptyCtor = irClass.getMethods().stream().anyMatch(m -> m.getName().equals("<init>") && "()V".equals(m.getDescriptor()));
                if (!hasEmptyCtor) {
                    IRBlock body = new IRBlock(new ArrayList<>());
                    IRMethod ctor = new IRMethod("<init>", "()V", false, false, body);
                    ctor.setAccessFlags(Opcodes.ACC_PUBLIC);
                    irClass.addMethod(ctor);
                }
            }

            List<String> primaryParamNames = new ArrayList<>();
            if (ctx.parameterList() != null) {
                for (OceanParser.ParameterContext p : ctx.parameterList().parameter()) {
                    primaryParamNames.add(p.anyId().getText());
                }
            }
            List<IRField> dataComponents = new ArrayList<>();
            for (String pName : primaryParamNames) {
                irClass.getFields().stream()
                        .filter(f -> !f.isStatic() && f.getName().equals(pName))
                        .findFirst()
                        .ifPresent(dataComponents::add);
            }

            // toString()
            boolean hasToString = irClass.getMethods().stream().anyMatch(m -> m.getName().equals("toString") && "()Ljava/lang/String;".equals(m.getDescriptor()));
            if (!hasToString) {
                IRMethod toStringMethod = getIrMethod(simpleName, dataComponents);
                irClass.addMethod(toStringMethod);
            }

            // hashCode()
            boolean hasHashCode = irClass.getMethods().stream().anyMatch(m -> m.getName().equals("hashCode") && "()I".equals(m.getDescriptor()));
            if (!hasHashCode) {
                List<IRExpression> arrayElements = new ArrayList<>();
                for (IRField f : dataComponents) {
                    String fName = f.getName();
                    String fDesc = f.getTypeDescriptor();
                    IRVariableAccess thisAccess = new IRVariableAccess("this", "L" + currentClassName.replace(".", "/") + ";", false, currentClassName, false);
                    IRVariableAccess fieldAccess = new IRVariableAccess(fName, fDesc, true, currentClassName, false);
                    fieldAccess.setReceiver(thisAccess);

                    IRExpression elemExpr = fieldAccess;
                    if (TypeChecker.isPrimitive(fDesc)) {
                        elemExpr = new IRCastExpression(fieldAccess, OceanTypeSystem.OBJECT_DESC);
                    }
                    arrayElements.add(elemExpr);
                }
                IRArrayLiteral arrayLiteral = new IRArrayLiteral(arrayElements, "[Ljava/lang/Object;");
                IRMethodCall hashCall = new IRMethodCall("java/util/Objects", "hash", "([Ljava/lang/Object;)I", List.of(arrayLiteral), true);
                IRMethod hashCodeMethod = new IRMethod("hashCode", "()I", false, false, new IRBlock(List.of(new IRReturnStatement(hashCall))));
                hashCodeMethod.setAccessFlags(Opcodes.ACC_PUBLIC);
                irClass.addMethod(hashCodeMethod);
            }

            // equals(Object other)
            boolean hasEquals = irClass.getMethods().stream().anyMatch(m -> m.getName().equals("equals") && "(Ljava/lang/Object;)Z".equals(m.getDescriptor()));
            if (!hasEquals) {
                List<IRStatement> equalsStats = new ArrayList<>();
                IRVariableAccess thisAccess = new IRVariableAccess("this", "L" + currentClassName.replace(".", "/") + ";", false, currentClassName, false);
                IRVariableAccess otherAccess = new IRVariableAccess("other", OceanTypeSystem.OBJECT_DESC, false, null, false);

                // 1. if (this == other) return true;
                IRBinaryOp identityCond = new IRBinaryOp(thisAccess, otherAccess, IRBinaryOp.Op.EQ, "Z");
                equalsStats.add(new IRIfStatement(identityCond, new IRReturnStatement(new IRLiteral(true, "Z")), null));

                // 2. if (other == null) return false;
                IRBinaryOp nullCond = new IRBinaryOp(otherAccess, new IRLiteral(null, OceanTypeSystem.OBJECT_DESC), IRBinaryOp.Op.EQ, "Z");
                equalsStats.add(new IRIfStatement(nullCond, new IRReturnStatement(new IRLiteral(false, "Z")), null));

                // 3. if (this.getClass() != other.getClass()) return false;
                IRMethodCall thisGetClass = new IRMethodCall("java/lang/Object", "getClass", "()Ljava/lang/Class;", Collections.emptyList(), false);
                thisGetClass.setReceiver(new IRVariableAccess("this", "L" + currentClassName.replace(".", "/") + ";", false, currentClassName, false));
                IRMethodCall otherGetClass = new IRMethodCall("java/lang/Object", "getClass", "()Ljava/lang/Class;", Collections.emptyList(), false);
                otherGetClass.setReceiver(otherAccess);
                IRBinaryOp classDiff = new IRBinaryOp(thisGetClass, otherGetClass, IRBinaryOp.Op.NE, "Z");
                equalsStats.add(new IRIfStatement(classDiff, new IRReturnStatement(new IRLiteral(false, "Z")), null));

                // 4. Point o = (Point) other;
                IRCastExpression castExpr = new IRCastExpression(otherAccess, "L" + currentClassName.replace(".", "/") + ";");
                equalsStats.add(new IRVariableDecl("o", "L" + currentClassName.replace(".", "/") + ";", castExpr, true));

                // 5. Compare each field
                IRVariableAccess otherCastAccess = new IRVariableAccess("o", "L" + currentClassName.replace(".", "/") + ";", false, null, false);
                for (IRField f : dataComponents) {
                    String fName = f.getName();
                    String fDesc = f.getTypeDescriptor();

                    IRVariableAccess thisField = new IRVariableAccess(fName, fDesc, true, currentClassName, false);
                    thisField.setReceiver(new IRVariableAccess("this", "L" + currentClassName.replace(".", "/") + ";", false, currentClassName, false));

                    IRVariableAccess otherField = new IRVariableAccess(fName, fDesc, true, currentClassName, false);
                    otherField.setReceiver(otherCastAccess);

                    if (TypeChecker.isPrimitive(fDesc)) {
                        if (fDesc.equals("D")) {
                            IRMethodCall compareCall = new IRMethodCall("java/lang/Double", "compare", "(DD)I", Arrays.asList(thisField, otherField), true);
                            IRBinaryOp compareOp = new IRBinaryOp(compareCall, new IRLiteral(0, "I"), IRBinaryOp.Op.NE, "Z");
                            equalsStats.add(new IRIfStatement(compareOp, new IRReturnStatement(new IRLiteral(false, "Z")), null));
                        } else if (fDesc.equals("F")) {
                            IRMethodCall compareCall = new IRMethodCall("java/lang/Float", "compare", "(FF)I", Arrays.asList(thisField, otherField), true);
                            IRBinaryOp compareOp = new IRBinaryOp(compareCall, new IRLiteral(0, "I"), IRBinaryOp.Op.NE, "Z");
                            equalsStats.add(new IRIfStatement(compareOp, new IRReturnStatement(new IRLiteral(false, "Z")), null));
                        } else {
                            IRBinaryOp compareOp = new IRBinaryOp(thisField, otherField, IRBinaryOp.Op.NE, "Z");
                            equalsStats.add(new IRIfStatement(compareOp, new IRReturnStatement(new IRLiteral(false, "Z")), null));
                        }
                    } else {
                        List<IRExpression> equalsArgs = Arrays.asList(thisField, otherField);
                        IRMethodCall equalsCall = new IRMethodCall("java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z", equalsArgs, true);
                        IRUnaryOp notEqualsCall = new IRUnaryOp(equalsCall, IRUnaryOp.Op.NOT, "Z");
                        equalsStats.add(new IRIfStatement(notEqualsCall, new IRReturnStatement(new IRLiteral(false, "Z")), null));
                    }
                }
                equalsStats.add(new IRReturnStatement(new IRLiteral(true, "Z")));
                IRMethod equalsMethod = new IRMethod("equals", "(Ljava/lang/Object;)Z", false, false, new IRBlock(equalsStats));
                equalsMethod.addParameter(new IRMethod.IRParameter("other", OceanTypeSystem.OBJECT_DESC));
                equalsMethod.setAccessFlags(Opcodes.ACC_PUBLIC);
                irClass.addMethod(equalsMethod);
            }

            // 6. Generate copy() and copy(...) methods
            String classDesc = OceanTypeSystem.wrapObjectType(currentClassName.replace(".", "/"));

            // 6.1 copy() (no-arg copy to clone instance)
            boolean hasCopyNoArg = irClass.getMethods().stream().anyMatch(m -> m.getName().equals("copy") && ("()" + classDesc).equals(m.getDescriptor()));
            if (!hasCopyNoArg) {
                List<IRStatement> copyBodyStats = new ArrayList<>();
                List<IRExpression> copyArgs = new ArrayList<>();

                for (String pName : primaryParamNames) {
                    IRField matchingField = irClass.getFields().stream().filter(f -> f.getName().equals(pName)).findFirst().orElse(null);
                    String fDesc = matchingField != null ? matchingField.getTypeDescriptor() : OceanTypeSystem.OBJECT_DESC;
                    IRVariableAccess thisField = new IRVariableAccess(pName, fDesc, true, currentClassName, false);
                    thisField.setReceiver(new IRVariableAccess("this", classDesc, false, currentClassName, false));
                    copyArgs.add(thisField);
                }
                IRNewObject newObj = new IRNewObject(currentClassName.replace(".", "/"), dataClassCtorDesc, copyArgs);
                copyBodyStats.add(new IRReturnStatement(newObj));
                IRMethod copyMethodNoArg = new IRMethod("copy", "()" + classDesc, false, false, new IRBlock(copyBodyStats));
                copyMethodNoArg.setAccessFlags(Opcodes.ACC_PUBLIC);
                irClass.addMethod(copyMethodNoArg);
            }

            // 6.2 copy(...) with params to customize instance values
            if (ctx.parameterList() != null) {
                List<IRMethod.IRParameter> copyParams = new ArrayList<>();
                List<IRStatement> copyWithArgsBody = new ArrayList<>();
                List<IRExpression> copyWithArgsNewArgs = new ArrayList<>();
                int paramIndex = 0;

                for (OceanParser.ParameterContext p : ctx.parameterList().parameter()) {
                    String pName = p.anyId().getText();
                    String pType = p.type().getText();
                    String pDesc = getTypeDescriptor(pType);
                    if (p.ELLIPSIS() != null) {
                        pDesc = "[" + pDesc;
                    }
                    String paramVarName = "p" + paramIndex;
                    copyParams.add(new IRMethod.IRParameter(paramVarName, pDesc));
                    copyWithArgsNewArgs.add(new IRVariableAccess(paramVarName, pDesc, false, null, false));
                    paramIndex++;
                }

                StringBuilder copyWithArgsDesc = new StringBuilder("(");
                for (IRMethod.IRParameter param : copyParams) {
                    copyWithArgsDesc.append(param.typeDescriptor());
                }
                copyWithArgsDesc.append(")").append(classDesc);

                boolean hasCopyWithArgs = irClass.getMethods().stream().anyMatch(m -> m.getName().equals("copy") && copyWithArgsDesc.toString().equals(m.getDescriptor()));
                if (!hasCopyWithArgs) {
                    IRNewObject newObjWithArgs = new IRNewObject(currentClassName.replace(".", "/"), dataClassCtorDesc, copyWithArgsNewArgs);
                    copyWithArgsBody.add(new IRReturnStatement(newObjWithArgs));

                    IRMethod copyMethodWithArgs = new IRMethod("copy", copyWithArgsDesc.toString(), false, false, new IRBlock(copyWithArgsBody));
                    for (IRMethod.IRParameter param : copyParams) {
                        copyMethodWithArgs.addParameter(param);
                    }
                    copyMethodWithArgs.setAccessFlags(Opcodes.ACC_PUBLIC);
                    irClass.addMethod(copyMethodWithArgs);
                }
            }
        }

        // Add any synthetic methods generated during class compilation
        for (IRMethod m : syntheticLambdaMethods) {
            irClass.addMethod(m);
        }
        syntheticLambdaMethods.clear();
        syntheticLambdaMethods.addAll(oldSynthetic);

        compiledTypes.add(irClass);
        currentClassName = oldClass;
        currentSuperName = oldSuper;
        insideDataClass = oldInsideDataClass;
        symbolTable.getTypeParams().clear();
        symbolTable.getTypeParams().addAll(outerClassParams);
        if (session != null) {
            session.setCurrentClassFqcn(capturedOldClassFqcn);
        }
        return irClass;
    }

    @NotNull
    private IRMethod getIrMethod(String simpleName, List<IRField> instanceFields) {
        IRExpression toStringExpr = new IRLiteral(simpleName + "(", OceanTypeSystem.STRING_DESC);
        for (int i = 0; i < instanceFields.size(); i++) {
            IRField f = instanceFields.get(i);
            String fName = f.getName();
            String fDesc = f.getTypeDescriptor();

            IRExpression fieldLabel = new IRLiteral((i > 0 ? ", " : "") + fName + "=", OceanTypeSystem.STRING_DESC);
            toStringExpr = new IRBinaryOp(toStringExpr, fieldLabel, IRBinaryOp.Op.ADD, OceanTypeSystem.STRING_DESC);

            IRVariableAccess thisAccess = new IRVariableAccess("this", OceanTypeSystem.wrapObjectType(currentClassName.replace(".", "/")), false, currentClassName, false);
            IRVariableAccess fieldAccess = new IRVariableAccess(fName, fDesc, true, currentClassName, false);
            fieldAccess.setReceiver(thisAccess);
            toStringExpr = new IRBinaryOp(toStringExpr, fieldAccess, IRBinaryOp.Op.ADD, OceanTypeSystem.STRING_DESC);
        }
        toStringExpr = new IRBinaryOp(toStringExpr, new IRLiteral(")", OceanTypeSystem.STRING_DESC), IRBinaryOp.Op.ADD, OceanTypeSystem.STRING_DESC);
        IRMethod toStringMethod = new IRMethod("toString", "()Ljava/lang/String;", false, false, new IRBlock(List.of(new IRReturnStatement(toStringExpr))));
        toStringMethod.setAccessFlags(Opcodes.ACC_PUBLIC);
        return toStringMethod;
    }

    @NotNull
    private IRAssignment getIrAssignment(String pName, String pDesc, String paramVarName) {
        IRVariableAccess thisAccess = new IRVariableAccess("this", OceanTypeSystem.wrapObjectType(currentClassName.replace(".", "/")), false, currentClassName, false);
        IRVariableAccess fieldAccess = new IRVariableAccess(pName, pDesc, true, currentClassName, false);
        fieldAccess.setReceiver(thisAccess);
        IRVariableAccess paramAccess = new IRVariableAccess(paramVarName, pDesc, false, null, false);
        return new IRAssignment(fieldAccess, paramAccess, false);
    }

    @NotNull
    private IRMethod getMethod(String fName, String fDesc, String capName) {
        IRAssignment setterAssign = getIrAssignment(fName, fDesc, "val");
        IRBlock setterBody = new IRBlock(Arrays.asList(new IRExprStatement(setterAssign), new IRReturnStatement(null)));
        return new IRMethod("set" + capName, "(" + fDesc + ")V", false, false, setterBody);
    }

    @NotNull
    private IRMethod getIrMethod(String fName, String fDesc, String capName) {
        IRVariableAccess thisAccess = new IRVariableAccess("this", OceanTypeSystem.wrapObjectType(currentClassName.replace(".", "/")), false, currentClassName, false);
        IRVariableAccess fieldAccess = new IRVariableAccess(fName, fDesc, true, currentClassName, false);
        fieldAccess.setReceiver(thisAccess);
        IRReturnStatement getterReturn = new IRReturnStatement(fieldAccess);
        IRMethod getter = new IRMethod("get" + capName, "()" + fDesc, false, false, new IRBlock(List.of(getterReturn)));
        getter.setAccessFlags(Opcodes.ACC_PUBLIC);
        return getter;
    }

    private String buildParameterTypesDescriptor(OceanParser.ParameterListContext paramList) {
        if (paramList == null) return "";
        StringBuilder sb = new StringBuilder();
        for (OceanParser.ParameterContext pCtx : paramList.parameter()) {
            String pDesc = getTypeDescriptor(pCtx.type().getText());
            if (pCtx.ELLIPSIS() != null) {
                pDesc = "[" + pDesc;
            }
            sb.append(pDesc);
        }
        return sb.toString();
    }

    @Override
    public IRNode visitConstructorDeclaration(OceanParser.ConstructorDeclarationContext ctx) {
        String ctorName = ctx.anyId().getText();
        String simpleClassName = currentClassName;
        if (simpleClassName != null && simpleClassName.contains("/")) {
            simpleClassName = OceanTypeSystem.findSimpleName(simpleClassName);
        }
        boolean isRealConstructor = ctorName.equals(simpleClassName) || (simpleClassName != null && simpleClassName.endsWith("$" + ctorName));
        String name = isRealConstructor ? "<init>" : ctorName;
        boolean isStatic = name.equals("main") || ModifierHelper.isStatic(ctx.modifier());

        StringBuilder desc = new StringBuilder("(");
        if (isStatic && (ctx.parameterList() == null || ctx.parameterList().parameter().isEmpty())) {
            desc.append("[Ljava/lang/String;");
        } else {
            if (isRealConstructor) {
                if ("java/lang/Enum".equals(currentSuperName)) {
                    desc.append("Ljava/lang/String;I");
                } else if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(currentClassName)) {
                    String outerFqcn = CompilerRegistry.globalInnerClassUsedOuterMap.get(currentClassName);
                    desc.append("L").append(outerFqcn).append(";");
                }
            }
            if (ctx.parameterList() != null) {
                desc.append(buildParameterTypesDescriptor(ctx.parameterList()));
            }
        }
        desc.append(")V");

        // Register signature early
        String oldMethodName = currentMethodName;
        String oldMethodDesc = currentMethodDescriptor;
        currentMethodName = name;
        currentMethodDescriptor = desc.toString();
        if (currentClassName != null) {
            CompilerRegistry.globalMethodRegistry.putIfAbsent(currentClassName, new HashMap<>());
            CompilerRegistry.globalMethodRegistry.get(currentClassName).put(name, desc.toString());
            CompilerRegistry.globalMethodStaticity.computeIfAbsent(currentClassName, k -> new HashMap<>()).put(name, isStatic);
            List<String> overloads = CompilerRegistry.globalOverloadRegistry
                    .computeIfAbsent(currentClassName, k -> new java.util.concurrent.ConcurrentHashMap<>())
                    .computeIfAbsent(name, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
            if (!overloads.contains(desc.toString())) {
                overloads.add(desc.toString());
            }
        }

        symbolTable.enterScope();
        if (!isStatic && currentClassName != null) {
            symbolTable.declareVariable("this", OceanTypeSystem.wrapObjectType(currentClassName));
        }
        if (isRealConstructor && CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(currentClassName)) {
            String outerFqcn = CompilerRegistry.globalInnerClassUsedOuterMap.get(currentClassName);
            symbolTable.declareVariable("this$0", "L" + outerFqcn + ";", true);
        }
        if (isRealConstructor && "java/lang/Enum".equals(currentSuperName)) {
            symbolTable.declareVariable("$name", OceanTypeSystem.STRING_DESC);
            symbolTable.declareVariable("$ordinal", "I");
        }
        if (isStatic && (ctx.parameterList() == null || ctx.parameterList().parameter().isEmpty())) {
            symbolTable.declareVariable("args", "[Ljava/lang/String;");
        } else {
            if (ctx.parameterList() != null) {
                for (OceanParser.ParameterContext pCtx : ctx.parameterList().parameter()) {
                    String rawType = pCtx.type().getText() + (pCtx.ELLIPSIS() != null ? "[]" : "");
                    boolean isParamFinal = pCtx.FINAL() != null || pCtx.VALUE() != null;
                    symbolTable.declareVariable(pCtx.anyId().getText(), rawType, isParamFinal);
                }
            }
        }

        boolean oldStatic = insideStaticContext;
        insideStaticContext = isStatic;
        IRStatement body = (IRStatement) visit(ctx.block());
        insideStaticContext = oldStatic;

        if (isRealConstructor && body instanceof IRBlock block) {
            boolean hasCtorCall = isHasCtorCall(block);

            if (!hasCtorCall) {
                IRMethodCall superCall = getIrMethodCall();
                block.getStatements().addFirst(new IRExprStatement(superCall));
            }
        }

        symbolTable.exitScope();

        IRMethod method = new IRMethod(name, desc.toString(), isStatic, false, body);
        Integer regAccess = CompilerRegistry.getMethodAccess(currentClassName, name, desc.toString());
        if (regAccess != null) {
            method.setAccessFlags(regAccess);
        }
        if (isStatic) {
            method.setAccessFlags(method.getAccessFlags() | Opcodes.ACC_STATIC);
        } else {
            method.setAccessFlags(method.getAccessFlags() & ~Opcodes.ACC_STATIC);
        }
        if ("main".equals(name)) {
            method.setAccessFlags(method.getAccessFlags() | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        }
        if (isStatic && (ctx.parameterList() == null || ctx.parameterList().parameter().isEmpty())) {
            method.addParameter(new IRMethod.IRParameter("args", "[Ljava/lang/String;"));
        } else {
            if (isRealConstructor) {
                if ("java/lang/Enum".equals(currentSuperName)) {
                    if (insideEnumWithConstantBodies) {
                        method.setAccessFlags(0);
                    } else if (method.getAccessFlags() == 0 || (method.getAccessFlags() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
                        method.setAccessFlags(Opcodes.ACC_PRIVATE);
                    }

                    method.addParameter(new IRMethod.IRParameter("$name", OceanTypeSystem.STRING_DESC));
                    method.addParameter(new IRMethod.IRParameter("$ordinal", "I"));
                } else if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(currentClassName)) {
                    String outerFqcn = CompilerRegistry.globalInnerClassUsedOuterMap.get(currentClassName);
                    method.addParameter(new IRMethod.IRParameter("this$0", "L" + outerFqcn + ";"));
                }
            }
            if (ctx.parameterList() != null) {
                for (OceanParser.ParameterContext pCtx : ctx.parameterList().parameter()) {
                    String pDesc = getTypeDescriptor(pCtx.type() != null ? pCtx.type().getText() : "Object");
                    if (pCtx.ELLIPSIS() != null) {
                        pDesc = "[" + pDesc;
                    }
                    List<IRAnnotation> paramAnnos = parseAnnotations(pCtx.annotation());
                    boolean isParamFinal = pCtx.FINAL() != null || pCtx.VALUE() != null;
                    method.addParameter(new IRMethod.IRParameter(pCtx.anyId().getText(), pDesc, paramAnnos, isParamFinal));
                }
            }
        }
        // --- throws listesini ekle ---
        if (ctx.typeList() != null) {
            for (OceanParser.TypeContext tc : ctx.typeList().type()) {
                String excPath = getTypeDescriptor(tc.getText());
                // getTypeDescriptor returns "Lpath;" form, we need internal "path" form
                if (TypeChecker.isClassType(excPath)) {
                    method.getExceptions().add(excPath.substring(1, excPath.length() - 1));
                } else if (excPath != null) {
                    method.getExceptions().add(excPath);
                }
            }
        }
        currentMethodName = oldMethodName;
        currentMethodDescriptor = oldMethodDesc;
        return method;
    }

    @NotNull
    private IRMethodCall getIrMethodCall() {
        String superName = (currentSuperName != null) ? currentSuperName : "java/lang/Object";
        IRMethodCall superCall;
        if ("java/lang/Enum".equals(superName)) {
            IRVariableAccess nameAccess = new IRVariableAccess("$name", OceanTypeSystem.STRING_DESC, false, null, false);
            IRVariableAccess ordinalAccess = new IRVariableAccess("$ordinal", "I", false, null, false);
            superCall = new IRMethodCall(superName, "<init>", "(Ljava/lang/String;I)V", Arrays.asList(nameAccess, ordinalAccess), false);
        } else {
            superCall = new IRMethodCall(superName, "<init>", "()V", new ArrayList<>(), false);
        }
        superCall.setSuperCall(true);
        superCall.setSynthetic(true);
        return superCall;
    }

    private static boolean isHasCtorCall(IRBlock block) {
        if (block == null || block.getStatements().isEmpty()) return false;
        for (IRStatement stmt : block.getStatements()) {
            if (stmt instanceof IRExprStatement exprStmt && exprStmt.getExpression() instanceof IRMethodCall call) {
                if (call.isSuperCall() || call.getName().equals("<init>")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public IRNode visitNormalMethod(OceanParser.NormalMethodContext ctx) {
        Set<String> outerMethodParams = new HashSet<>(symbolTable.getTypeParams());
        List<CompilerRegistry.TypeParameterInfo> oldMethodTpInfos = new ArrayList<>(currentMethodTypeParamInfos);
        currentMethodTypeParamInfos.clear();
        Set<String> methodParams = new HashSet<>();
        if (ctx.typeParameter() != null) {
            currentMethodTypeParamInfos.addAll(extractMethodTypeParameters(ctx.typeParameter()));
            for (OceanParser.TypeParameterContext tp : ctx.typeParameter()) {
                if (tp != null && tp.anyId() != null) {
                    methodParams.add(tp.anyId().getText());
                }
            }
        }
        symbolTable.getTypeParams().addAll(methodParams);

        if (ctx.anyId() == null) return null;
        String mName = ctx.anyId().getText();
        String simpleClassName = currentClassName;
        if (simpleClassName != null && simpleClassName.contains("/")) {
            simpleClassName = OceanTypeSystem.findSimpleName(simpleClassName);
        }
        if (mName.equals(simpleClassName) || (simpleClassName != null && simpleClassName.endsWith("$" + mName)))
            mName = "<init>";

        boolean isMain = mName.equals("main");
        String returnType = getMethodReturnType(ctx);
        if (isMain || mName.equals("<init>")) {
            returnType = "V";
        }
        currentMethodReturnType = returnType;

        boolean isAsync = ModifierHelper.isAsync(ctx.modifier());
        if (isAsync) {
            String boxedRealRet = TypeChecker.isPrimitive(returnType) ? OceanTypeSystem.getBoxedDescriptor(returnType) : returnType;
            returnType = "Ljava/util/concurrent/CompletableFuture<" + boxedRealRet + ">;";
        }

        boolean isStatic = ModifierHelper.isStatic(ctx.modifier());
        if (isMain || ctx.extType != null) isStatic = true;
        if (mName.equals("<init>")) isStatic = false;

        boolean isNative = ModifierHelper.isNative(ctx.modifier());
        boolean isAbstract = ModifierHelper.isAbstract(ctx.modifier());
        if (ctx.block() == null && !isNative) {
            isAbstract = true;
        }

        StringBuilder desc = new StringBuilder("(");
        if (isMain && (ctx.parameterList() == null || ctx.parameterList().parameter().isEmpty())) {
            desc.append("[Ljava/lang/String;");
        } else {
            if (ctx.extType != null) {
                desc.append(getTypeDescriptor(ctx.extType.getText()));
            }
            if (ctx.parameterList() != null) {
                for (OceanParser.ParameterContext pCtx : ctx.parameterList().parameter()) {
                    String pDesc = getTypeDescriptor(pCtx.type().getText());
                    if (pCtx.ELLIPSIS() != null) {
                        pDesc = "[" + pDesc;
                    }
                    desc.append(pDesc);
                }
            }
        }
        desc.append(")").append(returnType);
        String oldMethodName = currentMethodName;
        String oldMethodDesc = currentMethodDescriptor;
        currentMethodName = mName;
        currentMethodDescriptor = desc.toString();

        // Register signature early
        if (currentClassName != null) {
            CompilerRegistry.globalMethodRegistry.putIfAbsent(currentClassName, new HashMap<>());
            CompilerRegistry.globalMethodRegistry.get(currentClassName).put(mName, desc.toString());
            List<String> overloads = CompilerRegistry.globalOverloadRegistry
                    .computeIfAbsent(currentClassName, k -> new java.util.concurrent.ConcurrentHashMap<>())
                    .computeIfAbsent(mName, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
            if (!overloads.contains(desc.toString())) {
                overloads.add(desc.toString());
            }
        }

        IRStatement methodBody = null;
        if (ctx.block() != null) {
            symbolTable.enterScope();
            if (isMain && (ctx.parameterList() == null || ctx.parameterList().parameter().isEmpty())) {
                symbolTable.declareVariable("args", "[Ljava/lang/String;");
            } else {
                if (ctx.extType != null) {
                    symbolTable.declareVariable("this", ctx.extType.getText());
                }
                if (ctx.parameterList() != null) {
                    for (OceanParser.ParameterContext pCtx : ctx.parameterList().parameter()) {
                        String rawType = pCtx.type() != null ? pCtx.type().getText() : OceanTypeSystem.OBJECT_DESC;
                        if (pCtx.ELLIPSIS() != null) {
                            rawType = rawType + "[]";
                        }
                        boolean isParamFinal = pCtx.FINAL() != null || pCtx.VALUE() != null;
                        symbolTable.declareVariable(pCtx.anyId().getText(), rawType, isParamFinal);
                    }
                }
            }
            boolean oldStatic = insideStaticContext;
            insideStaticContext = isStatic;
            methodBody = (IRStatement) visit(ctx.block());
            insideStaticContext = oldStatic;

            if (isAsync && methodBody != null) {
                methodBody = wrapAsyncMethodBody(methodBody, currentMethodReturnType, isStatic, ctx.block());
            }

            symbolTable.exitScope();
        }

        IRMethod method = new IRMethod(mName, desc.toString(), isStatic, isAbstract, methodBody);
        method.setAsync(isAsync);
        int accessFlags = ModifierHelper.toAsmAccess(ctx.modifier());
        if (isNative) {
            accessFlags |= Opcodes.ACC_NATIVE;
        }
        Integer regAccess = CompilerRegistry.getMethodAccess(currentClassName, mName, desc.toString());
        if (regAccess != null) {
            accessFlags = regAccess | (isNative ? Opcodes.ACC_NATIVE : 0);
        } else {
            boolean hasExplicitAccess = (accessFlags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) != 0;
            if (!hasExplicitAccess) {
                accessFlags |= Opcodes.ACC_PUBLIC;
            }
            if (currentClassName != null) {
                String classKey = currentClassName.replace('.', '/');
                CompilerRegistry.globalMethodAccess.computeIfAbsent(classKey, k -> new ConcurrentHashMap<>())
                        .put(mName, accessFlags);
                CompilerRegistry.globalMethodAccess.get(classKey)
                        .put(mName + desc, accessFlags);
            }
        }
        if (isStatic) {
            accessFlags |= Opcodes.ACC_STATIC;
        } else {
            accessFlags &= ~Opcodes.ACC_STATIC;
        }
        method.setAccessFlags(accessFlags);
        if (isMain) {
            method.setAccessFlags(method.getAccessFlags() | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        }
        if (isMain && (ctx.parameterList() == null || ctx.parameterList().parameter().isEmpty())) {
            method.addParameter(new IRMethod.IRParameter("args", "[Ljava/lang/String;"));
        } else {
            if (ctx.extType != null) {
                method.addParameter(new IRMethod.IRParameter("this", getTypeDescriptor(ctx.extType.getText())));
            }
            if (ctx.parameterList() != null) {
                for (OceanParser.ParameterContext pCtx : ctx.parameterList().parameter()) {
                    String pDesc = getTypeDescriptor(pCtx.type() != null ? pCtx.type().getText() : null);
                    if (pCtx.ELLIPSIS() != null) {
                        pDesc = "[" + pDesc;
                    }
                    List<IRAnnotation> paramAnnos = parseAnnotations(pCtx.annotation());
                    boolean isParamFinal = pCtx.FINAL() != null || pCtx.VALUE() != null;
                    method.addParameter(new IRMethod.IRParameter(pCtx.anyId().getText(), pDesc, paramAnnos, isParamFinal));
                }
            }
        }
        // --- throws listesini ekle ---
        if (ctx.typeList() != null) {
            for (OceanParser.TypeContext tc : ctx.typeList().type()) {
                String excPath = getTypeDescriptor(tc.getText());
                if (TypeChecker.isClassType(excPath)) {
                    method.getExceptions().add(excPath.substring(1, excPath.length() - 1));
                } else if (excPath != null) {
                    method.getExceptions().add(excPath);
                }
            }
        }
        currentMethodReturnType = null;
        symbolTable.getTypeParams().clear();
        symbolTable.getTypeParams().addAll(outerMethodParams);
        currentMethodTypeParamInfos.clear();
        currentMethodTypeParamInfos.addAll(oldMethodTpInfos);
        currentMethodName = oldMethodName;
        currentMethodDescriptor = oldMethodDesc;
        return method;
    }

    @Override
    public IRNode visitMainMethod(OceanParser.MainMethodContext ctx) {
        String oldMethodName = currentMethodName;
        String oldMethodDesc = currentMethodDescriptor;
        currentMethodName = "main";
        currentMethodDescriptor = "([Ljava/lang/String;)V";
        symbolTable.enterScope();
        symbolTable.declareVariable("args", "[Ljava/lang/String;");
        boolean oldStatic = insideStaticContext;
        insideStaticContext = true;
        IRStatement body = (IRStatement) visit(ctx.block());
        insideStaticContext = oldStatic;
        symbolTable.exitScope();

        IRMethod method = new IRMethod("main", "([Ljava/lang/String;)V", true, false, body);
        method.setAccessFlags(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        method.addParameter(new IRMethod.IRParameter("args", "[Ljava/lang/String;"));
        currentMethodName = oldMethodName;
        currentMethodDescriptor = oldMethodDesc;
        return method;
    }

    @Override
    public IRNode visitOceanOutputPrimary(OceanParser.OceanOutputPrimaryContext ctx) {
        List<IRExpression> args = new ArrayList<>();
        if (ctx.argumentList() != null) {
            for (OceanParser.ExpressionContext eCtx : getArgumentExpressions(ctx.argumentList())) {
                args.add(ensureExpr(visit(eCtx)));
            }
        }
        return new IROceanOutput(args);
    }

    @Override
    public IRNode visitSuperStmt(OceanParser.SuperStmtContext ctx) {
        if ("java/lang/Enum".equals(currentSuperName)) {
            reportError(ctx, "Cannot invoke 'super()' in an enum constructor. Only 'this()' is allowed.");
            throw new CompilationException("Cannot invoke 'super()' in an enum constructor. Only 'this()' is allowed.");
        }
        String owner = (currentSuperName != null) ? currentSuperName : "java/lang/Object";
        List<IRExpression> args = new ArrayList<>();
        if (ctx.argumentList() != null) {
            List<OceanParser.ExpressionContext> argExprs = getArgumentExpressions(ctx.argumentList());
            int totalArgs = argExprs.size();
            for (int i = 0; i < totalArgs; i++) {
                OceanParser.ExpressionContext eCtx = argExprs.get(i);
                String expectedType = getExpectedCtorParamType(owner, i, totalArgs);
                String oldCast = currentCastType;
                if (expectedType != null) {
                    currentCastType = expectedType;
                }
                args.add(ensureExpr(visit(eCtx)));
                currentCastType = oldCast;
            }
        }

        String desc = resolveMethodDescriptor(owner, "<init>", args);
        packVarargsIfNecessary(owner, "<init>", desc, args);
        IRMethodCall call = new IRMethodCall(owner, "<init>", desc, args, false);
        call.setSuperCall(true);
        return new IRExprStatement(call);
    }

    @Override
    public IRNode visitFieldDeclaration(OceanParser.FieldDeclarationContext ctx) {
        String currentKey = currentClassName != null ? currentClassName.replace('.', '/') : "";
        boolean isInterfaceField = CompilerRegistry.globalIsInterfaceSet.contains(currentKey) || CompilerRegistry.globalIsInterfaceSet.contains(getFullClassName(currentClassName));
        boolean isStatic = ModifierHelper.isStatic(ctx.modifier()) || isInterfaceField;
        boolean isFinal = ModifierHelper.isFinal(ctx.modifier()) || ctx.VALUE() != null || ctx.FINAL() != null || isInterfaceField;
        String typeText = ctx.type() != null ? ctx.type().getText() : null;

        List<IRField> fields = new ArrayList<>();
        for (OceanParser.VariableDeclaratorContext d : ctx.variableDeclarator()) {
            String name = d.anyId().getText();
            boolean oldStatic = insideStaticContext;
            insideStaticContext = isStatic;
            IRExpression initialValue = d.expression() != null ? ensureExpr(visit(d.expression())) : null;
            insideStaticContext = oldStatic;

            String type = OceanTypeSystem.OBJECT_DESC;
            String classPathKey = currentClassName != null ? currentClassName.replace('.', '/') : "";
            if (typeText != null && !typeText.equals("value") && !typeText.equals("variable") && !typeText.equals("var")) {
                type = getTypeDescriptor(typeText);
            } else if (initialValue != null) {
                type = initialValue.getTypeDescriptor();
                if ("null".equals(type)) {
                    type = OceanTypeSystem.OBJECT_DESC + "?";
                }
            } else if (currentClassName != null) {
                Map<String, String> fMap = CompilerRegistry.globalFieldRegistry.get(classPathKey);
                if (fMap != null && fMap.containsKey(name) && fMap.get(name) != null) {
                    type = fMap.get(name);
                }
            }

            // Ensure registered
            if (currentClassName != null) {
                CompilerRegistry.globalFieldRegistry.computeIfAbsent(classPathKey, k -> new HashMap<>()).put(name, type);
                CompilerRegistry.globalFieldStaticity.computeIfAbsent(classPathKey, k -> new HashMap<>()).put(name, isStatic);
                CompilerRegistry.globalFieldMutability.computeIfAbsent(classPathKey, k -> new HashMap<>()).put(name, isFinal);
            }

            IRField field = new IRField(name, type, isStatic, initialValue);

            int access = ModifierHelper.toAsmFieldAccess(ctx.modifier());
            if (isFinal) {
                access |= Opcodes.ACC_FINAL;
            }
            if (insideDataClass) {
                access = Opcodes.ACC_PRIVATE | (isFinal ? Opcodes.ACC_FINAL : 0) | (isStatic ? Opcodes.ACC_STATIC : 0);
            } else if (currentClassName != null) {
                Map<String, Integer> fieldAccessMap = CompilerRegistry.globalFieldAccess.get(currentClassName.replace('.', '/'));
                if (fieldAccessMap != null && fieldAccessMap.containsKey(name)) {
                    access |= fieldAccessMap.get(name);
                }
            }
            if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                access &= ~Opcodes.ACC_SYNCHRONIZED;
                access |= Opcodes.ACC_VOLATILE;
            }
            field.setAccessFlags(access);
            fields.add(field);
        }

        if (fields.size() == 1) return fields.getFirst();
        return new ocean.compiler.ir.IRFieldGroup(fields);
    }

    @Override
    public IRNode visitBlockStmt(OceanParser.BlockStmtContext ctx) {
        return visit(ctx.block());
    }

    @Override
    public IRNode visitLocalClassDeclStmt(OceanParser.LocalClassDeclStmtContext ctx) {
        return visit(ctx.classDeclaration());
    }

    @Override
    public IRNode visitBlock(OceanParser.BlockContext ctx) {
        symbolTable.enterScope();
        try {
            List<IRStatement> statements = new ArrayList<>();
            if (ctx.statement() != null) {
                for (OceanParser.StatementContext stmtCtx : ctx.statement()) {
                    IRNode node = visit(stmtCtx);
                    if (node instanceof IRBlock blockNode && !(stmtCtx instanceof OceanParser.BlockStmtContext)) {
                        statements.addAll(blockNode.getStatements());
                    } else if (node instanceof IRStatement stmt) {
                        statements.add(stmt);
                    } else if (node instanceof IRExpression) {
                        statements.add(new IRExprStatement((IRExpression) node));
                    }
                }
            }
            return new IRBlock(statements);
        } finally {
            symbolTable.exitScope();
        }
    }

    private boolean isTerminalStatement(IRStatement stmt) {
        return stmt instanceof IRReturnStatement || stmt instanceof IRThrowStatement || stmt instanceof IRStopStatement;
    }

    @Override
    public IRNode visitAssignmentStmt(OceanParser.AssignmentStmtContext ctx) {
        IRNode node = visit(ctx.assignment());
        if (node instanceof IRExpression expr) {
            IRExprStatement stmt = new IRExprStatement(expr);
            stmt.setLocation(ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine());
            return stmt;
        }
        return node;
    }

    @Override
    public IRNode visitAssignment(OceanParser.AssignmentContext ctx) {
        return handleAssignment(ctx.expression(0), ctx.op, ctx.expression(1));
    }

    @Override
    public IRNode visitAssignmentExpr(OceanParser.AssignmentExprContext ctx) {
        return handleAssignment(ctx.expression(0), ctx.op, ctx.expression(1));
    }

    private IRNode handleAssignment(OceanParser.ExpressionContext leftExprCtx, org.antlr.v4.runtime.Token opToken, OceanParser.ExpressionContext rightExprCtx) {
        if (leftExprCtx instanceof OceanParser.ArrayAccessExprContext arrCtx) {
            IRExpression array = ensureExpr(visit(arrCtx.expression(0)));
            String typeDesc = inferRawType(arrCtx.expression(0));
            if (typeDesc == null) typeDesc = array.getTypeDescriptor();
            String internalName = typeDesc != null ? TypeChecker.cleanDescriptor(typeDesc) : "";
            if (internalName.startsWith("L") && internalName.endsWith(";")) {
                internalName = internalName.substring(1, internalName.length() - 1);
            }
            String std = OceanTypeSystem.resolveStandardClassPath(internalName);
            if (std != null) {
                internalName = std;
            } else {
                internalName = OceanTypeSystem.resolveInternalClassName(internalName, currentClassName);
            }

            boolean isList = TypeChecker.isListOrCollectionType(internalName, CompilationSession.getActiveSession());

            if (isList) {
                IRExpression index = ensureExpr(visit(arrCtx.expression(1)));
                int opType = opToken.getType();

                IRExpression valueToSet;
                List<IRStatement> tempStmts = new ArrayList<>();
                IRExpression evalArray = array;
                IRExpression evalIndex = index;

                if (opType == OceanParser.ASSIGN) {
                    valueToSet = ensureExpr(visit(rightExprCtx));
                } else {
                    if (!isSideEffectFree(array)) {
                        String tempArrName = "$arr$" + (tempVarCounter++);
                        String arrType = array.getTypeDescriptor() != null ? array.getTypeDescriptor() : OceanTypeSystem.OBJECT_DESC;
                        symbolTable.declareVariable(tempArrName, arrType);
                        tempStmts.add(new IRVariableDecl(tempArrName, arrType, array, false));
                        evalArray = new IRVariableAccess(tempArrName, arrType, false, null, false);
                    }
                    if (!isSideEffectFree(index)) {
                        String tempIdxName = "$idx$" + (tempVarCounter++);
                        String idxType = index.getTypeDescriptor() != null ? index.getTypeDescriptor() : "I";
                        symbolTable.declareVariable(tempIdxName, idxType);
                        tempStmts.add(new IRVariableDecl(tempIdxName, idxType, index, false));
                        evalIndex = new IRVariableAccess(tempIdxName, idxType, false, null, false);
                    }

                    String getMethod;
                    String getDesc;
                    switch (internalName) {
                        case "ocean/stdlib/OceanIntList" -> { getMethod = "getInt"; getDesc = "(I)I"; }
                        case "ocean/stdlib/OceanLongList" -> { getMethod = "getLong"; getDesc = "(I)J"; }
                        case "ocean/stdlib/OceanDoubleList" -> { getMethod = "getDouble"; getDesc = "(I)D"; }
                        case "ocean/stdlib/OceanFloatList" -> { getMethod = "getFloat"; getDesc = "(I)F"; }
                        case "ocean/stdlib/OceanBooleanList" -> { getMethod = "getBoolean"; getDesc = "(I)Z"; }
                        default -> {
                            getMethod = "get";
                            getDesc = resolveMethodDescriptor(internalName, "get", Collections.singletonList(evalIndex));
                            if (getDesc == null) getDesc = "(I)Ljava/lang/Object;";
                        }
                    }
                    IRMethodCall getCall = new IRMethodCall(internalName, getMethod, getDesc, Collections.singletonList(evalIndex), false);
                    getCall.setReceiver(evalArray);

                    IRExpression rhs = ensureExpr(visit(rightExprCtx));
                    IRBinaryOp.Op op = getCompoundBinaryOp(opType);

                    String resType = getCall.getTypeDescriptor();
                    if (TypeChecker.isStringType(resType) && op == IRBinaryOp.Op.ADD) {
                        resType = OceanTypeSystem.STRING_DESC;
                    } else if (op == IRBinaryOp.Op.BIT_AND || op == IRBinaryOp.Op.BIT_OR || op == IRBinaryOp.Op.BIT_XOR) {
                        resType = TypeChecker.isBoolean(resType) ? "Z" : (resType != null ? resType : "I");
                    } else if (op == IRBinaryOp.Op.LSHIFT || op == IRBinaryOp.Op.RSHIFT || op == IRBinaryOp.Op.URSHIFT) {
                        resType = resType != null ? resType : "I";
                    } else if (resType == null || resType.equals(OceanTypeSystem.OBJECT_DESC)) {
                        resType = rhs.getTypeDescriptor();
                    } else if (TypeChecker.isPrimitive(resType) && TypeChecker.isPrimitive(rhs.getTypeDescriptor())) {
                        resType = getPromotedType(resType, rhs.getTypeDescriptor());
                    }
                    valueToSet = new IRBinaryOp(getCall, rhs, op, resType);
                }

                String desc = resolveMethodDescriptor(internalName, "set", Arrays.asList(evalIndex, valueToSet));
                if (desc == null) desc = "(ILjava/lang/Object;)Ljava/lang/Object;";
                IRMethodCall call = new IRMethodCall(internalName, "set", desc, Arrays.asList(evalIndex, valueToSet), false);
                call.setReceiver(evalArray);

                if (!tempStmts.isEmpty()) {
                    tempStmts.add(new IRExprStatement(call));
                    return new IRBlock(tempStmts);
                }
                return call;
            }
        }

        IRExpression left = ensureExpr(visit(leftExprCtx));
        String oldCast = currentCastType;
        currentCastType = left.getTypeDescriptor();
        IRExpression right = ensureExpr(visit(rightExprCtx));
        currentCastType = oldCast;
        int opType = opToken.getType();
        if (opType == OceanParser.ASSIGN) {
            boolean isStatic = (left instanceof IRVariableAccess) && ((IRVariableAccess) left).isStatic();
            if (left instanceof IRVariableAccess va && !va.isField()) {
                String varName = va.getName();
                String origType = symbolTable.getOriginalType(varName);
                if (origType != null) {
                    String rightType = right.getTypeDescriptor();
                    if (rightType == null || "null".equals(rightType) || TypeChecker.isNullable(rightType)) {
                        symbolTable.setType(varName, origType);
                    } else {
                        if (TypeChecker.isAssignable(origType, rightType, CompilationSession.getActiveSession())) {
                            symbolTable.setType(varName, rightType);
                        } else {
                            symbolTable.setType(varName, origType);
                        }
                    }
                }
            }
            IRAssignment assignment = new IRAssignment(left, right, isStatic);
            assignment.setTypeDescriptor(left.getTypeDescriptor() != null ? left.getTypeDescriptor() : right.getTypeDescriptor());
            return assignment;
        } else {
            IRBinaryOp.Op op = getCompoundBinaryOp(opType);

            String leftDesc = left.getTypeDescriptor();
            String rightDesc = right.getTypeDescriptor();
            String resType;
            if (op == IRBinaryOp.Op.ADD && (TypeChecker.isStringType(leftDesc) || TypeChecker.isStringType(rightDesc))) {
                resType = OceanTypeSystem.STRING_DESC;
            } else if (op == IRBinaryOp.Op.BIT_AND || op == IRBinaryOp.Op.BIT_OR || op == IRBinaryOp.Op.BIT_XOR) {
                resType = TypeChecker.isBoolean(leftDesc) ? "Z" : (leftDesc != null ? TypeChecker.getBinaryNumericPromotedType(leftDesc, rightDesc) : "I");
            } else if (op == IRBinaryOp.Op.LSHIFT || op == IRBinaryOp.Op.RSHIFT || op == IRBinaryOp.Op.URSHIFT) {
                resType = leftDesc != null ? TypeChecker.getUnaryNumericPromotedType(leftDesc) : "I";
            } else if (TypeChecker.isNumeric(leftDesc) && TypeChecker.isNumeric(rightDesc)) {
                resType = TypeChecker.getBinaryNumericPromotedType(leftDesc, rightDesc);
            } else {
                resType = getPromotedType(leftDesc, rightDesc);
            }

            IRBinaryOp binOp = new IRBinaryOp(left, right, op, resType);
            IRAssignment assignment = new IRAssignment(left, binOp, (left instanceof IRVariableAccess && ((IRVariableAccess) left).isStatic()), true);
            assignment.setTypeDescriptor(left.getTypeDescriptor() != null ? left.getTypeDescriptor() : binOp.getTypeDescriptor());
            return assignment;
        }
    }

    private IRBinaryOp.Op getCompoundBinaryOp(int opType) {
        if (opType == OceanParser.PLUS_ASSIGN) return IRBinaryOp.Op.ADD;
        if (opType == OceanParser.MINUS_ASSIGN) return IRBinaryOp.Op.SUB;
        if (opType == OceanParser.STAR_ASSIGN) return IRBinaryOp.Op.MUL;
        if (opType == OceanParser.SLASH_ASSIGN) return IRBinaryOp.Op.DIV;
        if (opType == OceanParser.PERCENT_ASSIGN) return IRBinaryOp.Op.MOD;
        if (opType == OceanParser.AMP_ASSIGN) return IRBinaryOp.Op.BIT_AND;
        if (opType == OceanParser.PIPE_ASSIGN) return IRBinaryOp.Op.BIT_OR;
        if (opType == OceanParser.CARET_ASSIGN) return IRBinaryOp.Op.BIT_XOR;
        if (opType == OceanParser.LSHIFT_ASSIGN) return IRBinaryOp.Op.LSHIFT;
        if (opType == OceanParser.RSHIFT_ASSIGN) return IRBinaryOp.Op.RSHIFT;
        if (opType == OceanParser.URSHIFT_ASSIGN) return IRBinaryOp.Op.URSHIFT;
        return IRBinaryOp.Op.MOD;
    }

    private boolean isSideEffectFree(IRExpression expr) {
        if (expr instanceof IRLiteral) return true;
        if (expr instanceof IRVariableAccess va) {
            return !va.isField() || (va.getReceiver() == null || isSideEffectFree(va.getReceiver()));
        }
        return false;
    }

    @Override
    public IRNode visitVariableDeclStmt(OceanParser.VariableDeclStmtContext ctx) {
        return visit(ctx.variableDeclaration());
    }

    private void adjustArrayLiteralTypeIfCompatible(String type, IRExpression initialValue) {
        if (type != null && type.startsWith("[")) {
            if (initialValue instanceof IRArrayLiteral arr) {
                String targetElemType = type.substring(1);
                boolean allValid = true;
                List<IRExpression> newElements = new ArrayList<>();
                for (IRExpression exp : arr.getElements()) {
                    if (exp instanceof IRLiteral literal) {
                        String valStr = String.valueOf(literal.getValue());
                        if (TypeChecker.checkBoundForPrimary(targetElemType, valStr)) {
                            Object convertedVal = literal.getValue();
                            try {
                                switch (targetElemType) {
                                    case "B" -> convertedVal = Byte.parseByte(valStr);
                                    case "S" -> convertedVal = Short.parseShort(valStr);
                                    case "I" -> convertedVal = Integer.parseInt(valStr);
                                    case "J" -> convertedVal = Long.parseLong(valStr);
                                    case "F" -> convertedVal = Float.parseFloat(valStr);
                                    case "D" -> convertedVal = Double.parseDouble(valStr);
                                    default -> {}
                                }
                                newElements.add(new IRLiteral(convertedVal, targetElemType));
                            } catch (Exception e) {
                                allValid = false;
                                break;
                            }
                        } else {
                            allValid = false;
                            break;
                        }
                    } else {
                        allValid = false;
                        break;
                    }
                }

                if (allValid) {
                    arr.getElements().clear();
                    arr.getElements().addAll(newElements);
                    arr.setTypeDescriptor(type);
                }
            }
        }
    }

    @Override
    public IRNode visitFinalVarDecl(OceanParser.FinalVarDeclContext ctx) {
        String rawType = ctx.type().getText();
        String type = rawType.contains("<") ? ensureDescriptor(rawType) : getTypeDescriptor(rawType);
        List<IRVariableDecl> decls = new ArrayList<>();
        for (OceanParser.VariableDeclaratorContext d : ctx.variableDeclarator()) {
            String name = d.anyId().getText();
            String oldCast = currentCastType;
            currentCastType = type;
            IRExpression initialValue = d.expression() != null ? ensureExpr(visit(d.expression())) : null;
            currentCastType = oldCast;
            symbolTable.declareVariable(name, rawType, true);
            adjustArrayLiteralTypeIfCompatible(type, initialValue);
            decls.add(new IRVariableDecl(name, type, initialValue, true, true));
        }
        if (decls.size() == 1) return decls.getFirst();
        return new IRBlock(new ArrayList<>(decls));
    }

    @Override
    public IRNode visitTypedVarDecl(OceanParser.TypedVarDeclContext ctx) {
        String rawType = ctx.type().getText();
        // "variable?" → nullable tip çıkarımı, "variable" gibi davran: isExplicitType=false, tipi initializer'dan al
        if ("variable?".equals(rawType) || "variable".equals(rawType)) {
            List<IRVariableDecl> decls = new ArrayList<>();
            for (OceanParser.VariableDeclaratorContext d : ctx.variableDeclarator()) {
                String name = d.anyId().getText();
                String inferredRaw = inferRawType(d.expression());
                String inferredType;
                if (inferredRaw != null && inferredRaw.contains("<")) {
                    inferredType = inferredRaw;
                } else {
                    inferredType = TypeChecker.cleanDescriptor(inferredRaw);
                }
                if ("null".equals(inferredType)) {
                    inferredType = OceanTypeSystem.OBJECT_DESC + "?";
                }
                String oldCast = currentCastType;
                currentCastType = inferredType;
                IRExpression initialValue = d.expression() != null ? ensureExpr(visit(d.expression())) : null;
                currentCastType = oldCast;
                if (initialValue != null) {
                    String initType = initialValue.getTypeDescriptor();
                    if (initType != null && !OceanTypeSystem.OBJECT_DESC.equals(initType) && !"V".equals(initType) && !"null".equals(initType)) {
                        if (!TypeChecker.isSpecializedPrimitiveList(inferredType)) {
                            if (inferredType == null || OceanTypeSystem.OBJECT_DESC.equals(inferredType) || (initType.contains("<") && !initType.equals(inferredType))) {
                                inferredType = initType;
                                inferredRaw = initType;
                            }
                        }
                    }
                }
                symbolTable.declareVariable(name, inferredRaw, false);
                decls.add(new IRVariableDecl(name, inferredType, initialValue, false, false));
            }
            if (decls.size() == 1) return decls.getFirst();
            return new IRBlock(new ArrayList<>(decls));
        }
        String type = rawType.contains("<") ? ensureDescriptor(rawType) : getTypeDescriptor(rawType);
        List<IRVariableDecl> decls = new ArrayList<>();
        for (OceanParser.VariableDeclaratorContext d : ctx.variableDeclarator()) {
            String name = d.anyId().getText();
            String oldCast = currentCastType;
            currentCastType = type;
            IRExpression initialValue = d.expression() != null ? ensureExpr(visit(d.expression())) : null;
            currentCastType = oldCast;
            symbolTable.declareVariable(name, rawType, false);
            adjustArrayLiteralTypeIfCompatible(type, initialValue);
            decls.add(new IRVariableDecl(name, type, initialValue, false, true));
        }
        if (decls.size() == 1) return decls.getFirst();
        return new IRBlock(new ArrayList<>(decls));
    }

    @Override
    public IRNode visitVariableDecl(OceanParser.VariableDeclContext ctx) {
        List<IRVariableDecl> decls = new ArrayList<>();
        for (OceanParser.VariableDeclaratorContext d : ctx.variableDeclarator()) {
            String name = d.anyId().getText();
            String rawType = inferRawType(d.expression());
            String type;
            if (rawType != null && rawType.contains("<")) {
                type = rawType;
            } else {
                type = TypeChecker.cleanDescriptor(rawType);
            }
            if ("null".equals(type)) {
                type = OceanTypeSystem.OBJECT_DESC + "?";
            }
            String oldCast = currentCastType;
            currentCastType = type;
            IRExpression initialValue = d.expression() != null ? ensureExpr(visit(d.expression())) : null;
            currentCastType = oldCast;
            if (initialValue != null) {
                String initType = initialValue.getTypeDescriptor();
                if (initType != null && !OceanTypeSystem.OBJECT_DESC.equals(initType) && !"V".equals(initType) && !"null".equals(initType)) {
                    boolean isSpecializedPrim = TypeChecker.isSpecializedPrimitiveList(type);
                    if (!isSpecializedPrim) {
                        if (type == null || OceanTypeSystem.OBJECT_DESC.equals(type) || initType.contains("<") && !initType.equals(type)) {
                            type = initType;
                            rawType = initType;
                        }
                    }
                }
            }
            symbolTable.declareVariable(name, rawType, false);
            decls.add(new IRVariableDecl(name, type, initialValue, false, false));
        }
        if (decls.size() == 1) return decls.getFirst();
        return new IRBlock(new ArrayList<>(decls));
    }

    @Override
    public IRNode visitValueDecl(OceanParser.ValueDeclContext ctx) {
        List<IRVariableDecl> decls = new ArrayList<>();
        for (OceanParser.VariableDeclaratorContext d : ctx.variableDeclarator()) {
            String name = d.anyId().getText();
            String rawType = inferRawType(d.expression());
            String type;
            if (rawType != null && rawType.contains("<")) {
                type = rawType;
            } else {
                type = TypeChecker.cleanDescriptor(rawType);
            }
            if ("null".equals(type)) {
                type = OceanTypeSystem.OBJECT_DESC + "?";
            }
            IRExpression initialValue = d.expression() != null ? ensureExpr(visit(d.expression())) : null;
            if (initialValue != null) {
                String initType = initialValue.getTypeDescriptor();
                if (initType != null && !OceanTypeSystem.OBJECT_DESC.equals(initType) && !"V".equals(initType) && !"null".equals(initType)) {
                    boolean isSpecializedPrim = TypeChecker.isSpecializedPrimitiveList(type);
                    if (!isSpecializedPrim) {
                        if (type == null || OceanTypeSystem.OBJECT_DESC.equals(type) || initType.contains("<") && !initType.equals(type)) {
                            type = initType;
                            rawType = initType;
                        }
                    }
                }
            }
            symbolTable.declareVariable(name, rawType, true);
            decls.add(new IRVariableDecl(name, type, initialValue, true, false));
        }
        if (decls.size() == 1) return decls.getFirst();
        return new IRBlock(new ArrayList<>(decls));
    }

    @Override
    public IRNode visitExprStmt(OceanParser.ExprStmtContext ctx) {
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG] visitExprStmt: " + ctx.getText());
        }
        return visit(ctx.expressionStatement());
    }

    @Override
    public IRNode visitExpressionStatement(OceanParser.ExpressionStatementContext ctx) {
        IRExpression expr = ensureExpr(visit(ctx.expression()));
        return new IRExprStatement(expr);
    }

    private String getVariableName(OceanParser.ExpressionContext expr) {
        switch (expr) {
            case null -> {
                return null;
            }
            case OceanParser.PrimaryExprContext primaryExprContext -> {
                OceanParser.PrimaryContext p = primaryExprContext.primary();
                if (p instanceof OceanParser.IdPrimaryContext) {
                    return ((OceanParser.IdPrimaryContext) p).anyId().getText();
                } else if (p instanceof OceanParser.ParenthesizedPrimaryContext) {
                    return getVariableName(((OceanParser.ParenthesizedPrimaryContext) p).expression());
                }
            }
            case OceanParser.AssignmentExprContext aec -> {
                return getVariableName(aec.expression(0));
            }
            default -> {
            }
        }
        return null;
    }

    private boolean isNullLiteralExpr(OceanParser.ExpressionContext expr) {
        if (expr == null) return false;
        if (expr instanceof OceanParser.PrimaryExprContext pec) {
            OceanParser.PrimaryContext p = pec.primary();
            if (p instanceof OceanParser.NullPrimaryContext) {
                return true;
            } else if (p instanceof OceanParser.ParenthesizedPrimaryContext ppc) {
                return isNullLiteralExpr(ppc.expression());
            }
        }
        return false;
    }

    private void extractSmartCasts(OceanParser.ExpressionContext cond, Map<String, String> thenCasts, Map<String, String> elseCasts) {
        if (cond == null) return;
        switch (cond) {
            case OceanParser.InstanceOfExprContext ioe -> {
                String varName = getVariableName(ioe.expression());
                if (varName != null && ioe.instanceofPattern() != null) {
                    String targetType = null;
                    if (ioe.instanceofPattern() instanceof OceanParser.TypeInstanceofPatternContext typePat && typePat.type() != null) {
                        targetType = typePat.type().getText();
                    } else if (ioe.instanceofPattern() instanceof OceanParser.RecordInstanceofPatternContext recPat) {
                        targetType = recPat.type().getText();
                    }
                    if (targetType != null) {
                        thenCasts.put(varName, targetType);
                    }
                }
            }
            case OceanParser.EqualityExprContext eq -> {
                String leftVar = getVariableName(eq.expression(0));
                String rightVar = getVariableName(eq.expression(1));
                boolean leftIsNull = isNullLiteralExpr(eq.expression(0));
                boolean rightIsNull = isNullLiteralExpr(eq.expression(1));

                String varName = (leftVar != null && rightIsNull) ? leftVar : ((rightVar != null && leftIsNull) ? rightVar : null);
                if (varName != null) {
                    String currentType = symbolTable.getType(varName);
                    if (TypeChecker.isNullable(currentType)) {
                        String nonNullType = currentType.endsWith("?") ? currentType.substring(0, currentType.length() - 1) : currentType;
                        if (eq.op.getType() == OceanParser.NOT_EQUALS) { // var != null
                            thenCasts.put(varName, nonNullType);
                            elseCasts.put(varName, "null");
                        } else if (eq.op.getType() == OceanParser.EQUALS) { // var == null
                            thenCasts.put(varName, "null");
                            elseCasts.put(varName, nonNullType);
                        }
                    }
                }
            }
            case OceanParser.UnaryExprContext ue -> {
                if (ue.op.getType() == OceanParser.BANG) {
                    Map<String, String> subThen = new HashMap<>();
                    Map<String, String> subElse = new HashMap<>();
                    extractSmartCasts(ue.expression(), subThen, subElse);
                    thenCasts.putAll(subElse);
                    elseCasts.putAll(subThen);
                }
            }
            case OceanParser.LogicalAndExprContext lae -> {
                Map<String, String> thenA = new HashMap<>();
                Map<String, String> elseA = new HashMap<>();
                extractSmartCasts(lae.expression(0), thenA, elseA);

                Map<String, String> thenB = new HashMap<>();
                Map<String, String> elseB = new HashMap<>();
                extractSmartCasts(lae.expression(1), thenB, elseB);

                thenCasts.putAll(thenA);
                thenCasts.putAll(thenB);
                elseCasts.putAll(elseA);
            }
            case OceanParser.LogicalOrExprContext loe -> {
                Map<String, String> thenA = new HashMap<>();
                Map<String, String> elseA = new HashMap<>();
                extractSmartCasts(loe.expression(0), thenA, elseA);

                Map<String, String> thenB = new HashMap<>();
                Map<String, String> elseB = new HashMap<>();
                extractSmartCasts(loe.expression(1), thenB, elseB);

                elseCasts.putAll(elseA);
                elseCasts.putAll(elseB);
            }
            case OceanParser.PrimaryExprContext primaryExprContext -> {
                OceanParser.PrimaryContext p = primaryExprContext.primary();
                if (p instanceof OceanParser.ParenthesizedPrimaryContext ppc) {
                    extractSmartCasts(ppc.expression(), thenCasts, elseCasts);
                }
            }
            default -> {
            }
        }
    }

    private Map<String, String> applySmartCasts(Map<String, String> smartCasts) {
        Map<String, String> oldTypes = new HashMap<>();
        for (Map.Entry<String, String> entry : smartCasts.entrySet()) {
            String varName = entry.getKey();
            String targetType = entry.getValue();
            if ("null".equals(targetType)) continue;
            String oldType = symbolTable.getType(varName);
            if (oldType != null) {
                oldTypes.put(varName, oldType);
                String resolvedDesc = targetType.startsWith("L") || targetType.startsWith("[") || targetType.length() == 1
                        ? targetType
                        : getTypeDescriptor(targetType);
                if (resolvedDesc.endsWith("?")) {
                    resolvedDesc = resolvedDesc.substring(0, resolvedDesc.length() - 1);
                }
                symbolTable.setType(varName, resolvedDesc);
            }
        }
        return oldTypes;
    }

    private void restoreSmartCasts(Map<String, String> oldTypes) {
        for (Map.Entry<String, String> entry : oldTypes.entrySet()) {
            symbolTable.setType(entry.getKey(), entry.getValue());
        }
    }

    private void applyPermanentSmartCasts(Map<String, String> smartCasts) {
        for (Map.Entry<String, String> entry : smartCasts.entrySet()) {
            String varName = entry.getKey();
            String targetType = entry.getValue();
            if ("null".equals(targetType)) continue;
            String oldType = symbolTable.getType(varName);
            if (oldType != null) {
                String resolvedDesc = targetType.startsWith("L") || targetType.startsWith("[") || targetType.length() == 1
                        ? targetType
                        : getTypeDescriptor(targetType);
                if (resolvedDesc.endsWith("?")) {
                    resolvedDesc = resolvedDesc.substring(0, resolvedDesc.length() - 1);
                }
                symbolTable.setType(varName, resolvedDesc);
            }
        }
    }

    @Override
    public IRNode visitIfStmt(OceanParser.IfStmtContext ctx) {
        OceanParser.IfStatementContext ifCtx = ctx.ifStatement();

        IRExpression condition = ensureExpr(visit(ifCtx.expression()));

        // Smart cast extraction
        Map<String, String> thenSmartCasts = new HashMap<>();
        Map<String, String> elseSmartCasts = new HashMap<>();
        extractSmartCasts(ifCtx.expression(), thenSmartCasts, elseSmartCasts);

        // Apply then smart casts to symbolTable for thenBranch
        Map<String, String> oldTypesThen = applySmartCasts(thenSmartCasts);
        symbolTable.enterScope();
        IRStatement thenBranch;
        try {
            thenBranch = (IRStatement) visit(ifCtx.statement(0));
        } finally {
            symbolTable.exitScope();
            restoreSmartCasts(oldTypesThen);
        }

        // Apply else smart casts to symbolTable for elseBranch
        IRStatement elseBranch = null;
        if (ifCtx.statement().size() > 1) {
            Map<String, String> oldTypesElse = applySmartCasts(elseSmartCasts);
            symbolTable.enterScope();
            try {
                elseBranch = (IRStatement) visit(ifCtx.statement(1));
            } finally {
                symbolTable.exitScope();
                restoreSmartCasts(oldTypesElse);
            }
        }

        // Guard Clause (Early Exit) handling:
        if (statementExitsFlow(ifCtx.statement(0))) {
            applyPermanentSmartCasts(elseSmartCasts);
        } else if (ifCtx.statement().size() > 1 && statementExitsFlow(ifCtx.statement(1))) {
            applyPermanentSmartCasts(thenSmartCasts);
        }

        return new IRIfStatement(condition, thenBranch, elseBranch);
    }

    @Override
    public IRNode visitWhileStmt(OceanParser.WhileStmtContext ctx) {
        OceanParser.WhileStatementContext whileCtx = ctx.whileStatement();
        IRExpression condition = ensureExpr(visit(whileCtx.expression()));

        Map<String, String> thenSmartCasts = new HashMap<>();
        Map<String, String> elseSmartCasts = new HashMap<>();
        extractSmartCasts(whileCtx.expression(), thenSmartCasts, elseSmartCasts);

        Map<String, String> typeSnapshot = symbolTable.getTypeSnapshot();
        Map<String, String> oldTypes = applySmartCasts(thenSmartCasts);
        symbolTable.enterScope();
        IRStatement body;
        try {
            body = (IRStatement) visit(whileCtx.statement());
        } finally {
            symbolTable.exitScope();
            restoreSmartCasts(oldTypes);
            symbolTable.restoreTypeSnapshot(typeSnapshot);
        }

        return new IRWhileStatement(condition, body);
    }

    @Override
    public IRNode visitDoWhileStmt(OceanParser.DoWhileStmtContext ctx) {
        OceanParser.DoWhileStatementContext doWhileCtx = ctx.doWhileStatement();
        Map<String, String> typeSnapshot = symbolTable.getTypeSnapshot();
        symbolTable.enterScope();
        IRStatement body;
        IRExpression condition;
        try {
            body = (IRStatement) visit(doWhileCtx.statement());
            condition = ensureExpr(visit(doWhileCtx.expression()));
        } finally {
            symbolTable.exitScope();
            symbolTable.restoreTypeSnapshot(typeSnapshot);
        }
        return new IRDoWhileStatement(condition, body);
    }

    @Override
    public IRNode visitStopStmt(OceanParser.StopStmtContext ctx) {
        String targetLabel = ctx.anyId() != null ? ctx.anyId().getText() : null;
        return new IRStopStatement(targetLabel);
    }

    @Override
    public IRNode visitSkipStmt(OceanParser.SkipStmtContext ctx) {
        String targetLabel = ctx.anyId() != null ? ctx.anyId().getText() : null;
        return new IRSkipStatement(targetLabel);
    }

    @Override
    public IRNode visitLabeledStmt(OceanParser.LabeledStmtContext ctx) {
        String label = ctx.anyId().getText();
        IRStatement stmt = (IRStatement) visit(ctx.statement());
        return new IRLabeledStatement(label, stmt);
    }

    @Override
    public IRNode visitThrowStmt(OceanParser.ThrowStmtContext ctx) {
        IRExpression expr = ensureExpr(visit(ctx.expression()));
        return new IRThrowStatement(expr);
    }

    @Override
    public IRNode visitVerifyStmt(OceanParser.VerifyStmtContext ctx) {
        OceanParser.VerifyStatementContext vCtx = ctx.verifyStatement();
        IRExpression cond = ensureExpr(visit(vCtx.expression(0)));
        IRExpression detail = vCtx.expression().size() > 1 ? ensureExpr(visit(vCtx.expression(1))) : null;

        Map<String, String> thenSmartCasts = new HashMap<>();
        Map<String, String> elseSmartCasts = new HashMap<>();
        extractSmartCasts(vCtx.expression(0), thenSmartCasts, elseSmartCasts);
        applyPermanentSmartCasts(thenSmartCasts);

        return new IRVerifyStatement(cond, detail);
    }

    @Override
    public IRNode visitReturnStmt(OceanParser.ReturnStmtContext ctx) {
        OceanParser.ReturnStatementContext retCtx = ctx.returnStatement();
        String oldCast = currentCastType;
        if (currentMethodReturnType != null) {
            currentCastType = currentMethodReturnType;
        }
        IRExpression expr = retCtx.expression() != null ? ensureExpr(visit(retCtx.expression())) : null;
        currentCastType = oldCast;
        return new IRReturnStatement(expr);
    }

    @Override
    public IRNode visitForStmt(OceanParser.ForStmtContext ctx) {
        OceanParser.ForStatementContext forCtx = ctx.forStatement();
        if (forCtx == null) {
            return new IRBlock(Collections.emptyList());
        }

        if (forCtx.forControl() != null) {
            OceanParser.ForControlContext fc = forCtx.forControl();
            if (fc.anyId() == null) {
                return new IRBlock(Collections.emptyList());
            }
            IRExpression from = (!fc.expression().isEmpty()) ? ensureExpr(visit(fc.expression(0))) : new IRLiteral(0, "I");
            IRExpression to = (fc.expression().size() > 1) ? ensureExpr(visit(fc.expression(1))) : new IRLiteral(10, "I");
            IRExpression step = (fc.expression().size() > 2) ? ensureExpr(visit(fc.expression(2))) : new IRLiteral(1, "I");
            if (step instanceof IRLiteral lit && lit.getValue() instanceof Number n && n.doubleValue() == 0.0) {
                reportError(fc, "For loop step value cannot be 0.");
                throw new CompilationException("For loop step value cannot be 0.");
            }
            boolean increasing = fc.INCREASING() != null;

            String type;
            if (fc.type() != null && !fc.type().getText().equals("variable") && !fc.type().getText().equals("var") && !fc.type().getText().equals("value")) {
                type = getTypeDescriptor(fc.type().getText());
            } else {
                String t1 = from.getTypeDescriptor();
                String t2 = to.getTypeDescriptor();
                String t3 = step.getTypeDescriptor();
                type = getPromotedType(getPromotedType(t1, t2), t3);
            }

            Map<String, String> typeSnapshot = symbolTable.getTypeSnapshot();
            symbolTable.enterScope();
            String iteratorName = fc.anyId().getText();
            String boundIteratorName = iteratorName;
            if (iteratorName.equals("_")) {
                boundIteratorName = "$_unused_for_" + System.nanoTime();
            }
            symbolTable.declareVariable(boundIteratorName, type);

            IRStatement body;
            try {
                body = (IRStatement) visit(forCtx.statement());
            } finally {
                symbolTable.exitScope();
                symbolTable.restoreTypeSnapshot(typeSnapshot);
            }

            return new IRForStatement(boundIteratorName, type, from, to, step, increasing, body);
        } else {
            if (forCtx.anyId() == null) {
                return new IRBlock(Collections.emptyList());
            }
            IRExpression iterable = ensureExpr(visit(forCtx.expression()));
            String type = OceanTypeSystem.OBJECT_DESC;
            if (forCtx.type() != null && !forCtx.type().getText().equals("variable") && !forCtx.type().getText().equals("var") && !forCtx.type().getText().equals("value")) {
                type = getTypeDescriptor(forCtx.type().getText());
            } else {
                String iterableType = iterable.getTypeDescriptor();
                if (iterableType != null && iterableType.startsWith("[")) {
                    type = iterableType.substring(1);
                } else if (iterableType != null) {
                    String clean = TypeChecker.cleanDescriptor(iterableType);
                    if (clean.startsWith("L") && clean.endsWith(";")) clean = clean.substring(1, clean.length() - 1);
                    switch (clean) {
                        case "ocean/stdlib/OceanIntList" -> type = "I";
                        case "ocean/stdlib/OceanLongList" -> type = "J";
                        case "ocean/stdlib/OceanDoubleList" -> type = "D";
                        case "ocean/stdlib/OceanFloatList" -> type = "F";
                        case "ocean/stdlib/OceanBooleanList" -> type = "Z";
                        case "ocean/stdlib/OceanByteList" -> type = "B";
                        case "ocean/stdlib/OceanShortList" -> type = "S";
                        case "ocean/stdlib/OceanCharList" -> type = "C";
                        default -> {
                            String rawCollType = getRawExprTypeIR(forCtx.expression());
                            String arg = getGenericTypeArgument(rawCollType);
                            if (arg != null) {
                                type = getTypeDescriptor(arg);
                            }
                        }
                    }
                }
            }

            Map<String, String> typeSnapshot = symbolTable.getTypeSnapshot();
            symbolTable.enterScope();
            String iteratorName = forCtx.anyId().getText();
            String boundIteratorName = iteratorName;
            if (iteratorName.equals("_")) {
                boundIteratorName = "$_unused_for_" + System.nanoTime();
            }
            symbolTable.declareVariable(boundIteratorName, type);

            IRStatement body;
            try {
                body = (IRStatement) visit(forCtx.statement());
            } finally {
                symbolTable.exitScope();
                symbolTable.restoreTypeSnapshot(typeSnapshot);
            }

            return new IRForStatement(boundIteratorName, type, iterable, body);
        }
    }

    private IRSwitchPattern parseSwitchPattern(OceanParser.SwitchPatternContext patCtx, String expectedDesc) {
        if (patCtx instanceof OceanParser.UnnamedSwitchPatternContext || (patCtx != null && patCtx.getText().equals("_"))) {
            return IRSwitchPattern.ofUnnamed(expectedDesc, null);
        } else if (patCtx instanceof OceanParser.RecordSwitchPatternContext recPat) {
            String rawTypeName = recPat.type().getText();
            String typeDesc = getTypeDescriptor(rawTypeName);
            String varName = recPat.anyId() != null ? recPat.anyId().getText() : null;
            if (varName != null && varName.equals("_")) {
                varName = null;
            }

            List<CompilerRegistry.RecordComponentInfo> components = RecordHelper.getRecordComponents(typeDesc);
            boolean isRecord = components != null
                    || CompilerRegistry.globalDataClassSet.contains(rawTypeName)
                    || CompilerRegistry.globalDataClassSet.contains(TypeChecker.cleanDescriptor(typeDesc));

            if (!isRecord && varName == null && (recPat.patternList() == null || recPat.patternList().switchPattern().isEmpty())) {
                IRNode callNode = visitMemberCallExprDelegate(rawTypeName, null);
                if (callNode instanceof IRExpression expr) {
                    return IRSwitchPattern.ofExpr(expr, null);
                }
            }

            if (!isRecord) {
                reportError(recPat, "Record pattern type must be a record or data class: '" + rawTypeName + "'");
            }

            if (varName != null) {
                if (symbolTable.getIndex(varName) == -1) {
                    symbolTable.declareVariable(varName, typeDesc, true, true);
                }
            }

            List<IRSwitchPattern> nested = new ArrayList<>();
            if (recPat.patternList() != null) {
                List<OceanParser.SwitchPatternContext> subPatterns = recPat.patternList().switchPattern();
                for (int i = 0; i < subPatterns.size(); i++) {
                    OceanParser.SwitchPatternContext subPatCtx = subPatterns.get(i);
                    String compDesc = (components != null && i < components.size()) ? components.get(i).descriptor() : null;
                    nested.add(parseSwitchPattern(subPatCtx, compDesc));
                }
            }
            return IRSwitchPattern.ofRecord(typeDesc, varName, nested, null);
        } else if (patCtx instanceof OceanParser.TypeSwitchPatternContext typePat) {
            String varName = typePat.anyId() != null ? typePat.anyId().getText() : null;
            String typeDesc;
            boolean isValue = (typePat.VALUE() != null) || (typePat.type() != null && "value".equals(typePat.type().getText()));
            if (typePat.type() != null && !typePat.type().getText().equals("variable") && !typePat.type().getText().equals("value") && !typePat.type().getText().equals("var")) {
                typeDesc = getTypeDescriptor(typePat.type().getText());
            } else {
                typeDesc = Objects.requireNonNullElse(expectedDesc, OceanTypeSystem.OBJECT_DESC);
            }
            if (varName != null && varName.equals("_")) {
                return IRSwitchPattern.ofUnnamed(typeDesc, null);
            }
            if (varName != null) {
                if (symbolTable.getIndex(varName) == -1) {
                    symbolTable.declareVariable(varName, typeDesc, true, !isValue);
                }
            }
            return IRSwitchPattern.ofType(typeDesc, varName, null);
        } else if (patCtx instanceof OceanParser.NullSwitchPatternContext) {
            return IRSwitchPattern.ofNull(null);
        } else if (patCtx instanceof OceanParser.ExprSwitchPatternContext exprPat) {
            if (exprPat.expression() instanceof OceanParser.PrimaryExprContext priCtx) {
                if (priCtx.primary() instanceof OceanParser.IdPrimaryContext idPri) {
                    String varName = idPri.anyId().getText();
                    if (varName.equals("_")) {
                        return IRSwitchPattern.ofUnnamed(expectedDesc, null);
                    }
                    if (expectedDesc != null) {
                        if (symbolTable.getIndex(varName) == -1 && !isKnownEnumConstant(varName)) {
                            symbolTable.declareVariable(varName, expectedDesc, true, true);
                            return IRSwitchPattern.ofType(expectedDesc, varName, null);
                        }
                    }
                    if (isActuallyKnownType(varName)) {
                        String resolvedType = getTypeDescriptor(varName);
                        if (resolvedType != null && !resolvedType.isEmpty()) {
                            return IRSwitchPattern.ofType(resolvedType, null, null);
                        }
                    }
                } else if (priCtx.primary() instanceof OceanParser.PrimitiveTypePrimaryContext primPri) {
                    String typeName = primPri.getText();
                    String resolvedType = getTypeDescriptor(typeName);
                    if (resolvedType != null && !resolvedType.isEmpty()) {
                        return IRSwitchPattern.ofType(resolvedType, null, null);
                    }
                }
            } else if (isActuallyKnownType(exprPat.expression().getText())) {
                String resolvedType = getTypeDescriptor(exprPat.expression().getText());
                if (resolvedType != null && !resolvedType.isEmpty()) {
                    return IRSwitchPattern.ofType(resolvedType, null, null);
                }
            }
            IRExpression valExpr = ensureExpr(visit(exprPat.expression()));
            if (valExpr instanceof IRLiteral lit && lit.getValue() == null) {
                return IRSwitchPattern.ofNull(null);
            }
            return IRSwitchPattern.ofExpr(valExpr, null);
        }
        return IRSwitchPattern.ofNull(null);
    }

    private boolean isActuallyKnownType(String name) {
        if (name == null || name.isEmpty()) return false;
        if (isKnownEnumConstant(name)) return false;
        if (OceanTypeSystem.word2TypeForPrimitive(name) != null) return true;
        if ("String".equals(name) || "Object".equals(name) || "Number".equals(name) || "Boolean".equals(name) || "Integer".equals(name) || "Double".equals(name) || "Float".equals(name) || "Long".equals(name) || "Short".equals(name) || "Byte".equals(name) || "Character".equals(name) || "CharSequence".equals(name) || "Exception".equals(name) || "Throwable".equals(name) || "RuntimeException".equals(name)) return true;
        if (topLevelTypes.contains(name)) return true;
        String fullFq = getFullClassName(name);
        if (fullFq != null && (CompilerRegistry.globalMethodRegistry.containsKey(fullFq) || CompilerRegistry.globalFieldRegistry.containsKey(fullFq) || CompilerRegistry.globalSuperClassRegistry.containsKey(fullFq) || CompilerRegistry.globalIsInterfaceSet.contains(fullFq) || CompilerRegistry.globalDataClassSet.contains(fullFq))) return true;
        String clean = TypeChecker.cleanDescriptor(name);
        String simple = clean.contains("/") ? clean.substring(clean.lastIndexOf('/') + 1) : clean;
        if (CompilerRegistry.globalSuperClassRegistry.containsKey(clean) || CompilerRegistry.globalSuperClassRegistry.containsKey(simple)) return true;
        if (CompilerRegistry.globalIsInterfaceSet.contains(clean) || CompilerRegistry.globalIsInterfaceSet.contains(simple)) return true;
        if (CompilerRegistry.globalDataClassSet.contains(clean) || CompilerRegistry.globalDataClassSet.contains(simple)) return true;
        if (CompilerRegistry.globalRecordComponents.containsKey(clean) || CompilerRegistry.globalRecordComponents.containsKey(simple)) return true;
        if (CompilerRegistry.globalSealedClassSet.contains(clean) || CompilerRegistry.globalSealedClassSet.contains(simple)) return true;
        if (importedClasses != null && (importedClasses.containsKey(name) || importedClasses.containsKey(simple))) return true;
        if (OceanTypeSystem.resolveStandardClassPath(name) != null) return true;
        try {
            Class<?> c = OceanTypeSystem.forName(name);
            if (c != null) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isKnownEnumConstant(String name) {
        if (name == null) return false;
        if (!activeEnumSwitchTypes.isEmpty() && !activeEnumSwitchTypes.peek().isEmpty()) {
            String activeEnum = activeEnumSwitchTypes.peek();
            List<String> consts = ClassMetadataCache.getEnumConstants(activeEnum);
            return consts != null && consts.contains(name);
        }
        for (List<String> list : CompilerRegistry.globalEnumConstants.values()) {
            if (list != null && list.contains(name)) return true;
        }
        return false;
    }

    @Override
    public IRNode visitSwitchStmt(OceanParser.SwitchStmtContext ctx) {
        OceanParser.SwitchStatementContext swCtx = ctx.switchStatement();
        IRExpression expr = ensureExpr(visit(swCtx.expression()));
        String swType = expr.getTypeDescriptor();
        if (swType == null && expr instanceof IRVariableAccess va) {
            swType = symbolTable.getType(va.getName());
        }
        if (swType != null) {
            String clean = TypeChecker.cleanDescriptor(swType);
            if (TypeChecker.isEnumType(swType) || !ClassMetadataCache.getEnumConstants(clean).isEmpty()) {
                activeEnumSwitchTypes.push(clean);
            } else {
                activeEnumSwitchTypes.push("");
            }
        } else {
            activeEnumSwitchTypes.push("");
        }

        try {
            List<IRSwitchCase> cases = new ArrayList<>();
            for (OceanParser.SwitchCaseContext caseCtx : swCtx.switchCase()) {
                symbolTable.enterScope();
                try {
                    OceanParser.SwitchLabelContext labelCtx = caseCtx.switchLabel();
                    List<IRSwitchPattern> patterns = new ArrayList<>();
                    List<IRExpression> values = new ArrayList<>();
                    IRExpression guard = null;

                    if (labelCtx != null) {
                        for (OceanParser.SwitchPatternContext patCtx : labelCtx.switchPattern()) {
                            IRSwitchPattern pattern = parseSwitchPattern(patCtx, null);
                            patterns.add(pattern);
                            if (pattern.getKind() == IRSwitchPattern.Kind.NULL) {
                                values.add(new IRLiteral(null, OceanTypeSystem.OBJECT_DESC));
                            } else if (pattern.getExpression() != null) {
                                values.add(pattern.getExpression());
                            }
                        }
                        if (labelCtx.WHEN() != null && labelCtx.expression() != null) {
                            guard = ensureExpr(visit(labelCtx.expression()));
                        }
                    }

                    boolean isArrow = caseCtx.ARROW() != null;

                    IRStatement body;
                    if (caseCtx.block() != null) {
                        body = (IRStatement) visit(caseCtx.block());
                    } else if (caseCtx.statement() != null && !caseCtx.statement().isEmpty()) {
                        List<IRStatement> bodyStats = new ArrayList<>();
                        for (OceanParser.StatementContext s : caseCtx.statement()) {
                            IRNode n = visit(s);
                            if (n instanceof IRStatement) bodyStats.add((IRStatement) n);
                            else if (n instanceof IRExpression e) bodyStats.add(new IRExprStatement(e));
                        }
                        body = new IRBlock(bodyStats);
                    } else {
                        body = new IRBlock(Collections.emptyList());
                    }
                    cases.add(new IRSwitchCase(patterns, values, guard, body, isArrow));
                } finally {
                    symbolTable.exitScope();
                }
            }
            IRStatement defaultBlock = null;
            boolean isDefaultArrow = false;
            if (swCtx.defaultCase() != null) {
                OceanParser.DefaultCaseContext defCtx = swCtx.defaultCase();
                isDefaultArrow = defCtx.ARROW() != null;
                if (defCtx.block() != null) {
                    defaultBlock = (IRStatement) visit(defCtx.block());
                } else if (defCtx.statement() != null && !defCtx.statement().isEmpty()) {
                    List<IRStatement> bodyStats = new ArrayList<>();
                    for (OceanParser.StatementContext s : defCtx.statement()) {
                        IRNode n = visit(s);
                        if (n instanceof IRStatement) bodyStats.add((IRStatement) n);
                        else if (n instanceof IRExpression e) bodyStats.add(new IRExprStatement(e));
                    }
                    defaultBlock = new IRBlock(bodyStats);
                }
            }
            return new IRSwitchStatement(expr, cases, defaultBlock, isDefaultArrow);
        } finally {
            activeEnumSwitchTypes.pop();
        }
    }

    @Override
    public IRNode visitTryStatement(OceanParser.TryStatementContext ctx) {
        List<String> resNames = new ArrayList<>();
        if (ctx.resourceList() != null) {
            for (OceanParser.ResourceContext rc : ctx.resourceList().resource()) {
                resNames.add(rc.anyId().getText());
            }
        }

        IRStatement tryBlock;
        if (ctx.resourceList() != null && !ctx.resourceList().resource().isEmpty()) {
            tryBlock = buildTryWithResources(ctx.resourceList().resource(), 0, ctx.block(0));
        } else {
            tryBlock = (IRStatement) visit(ctx.block(0));
        }

        List<IRTryCatchStatement.IRCatchClause> catches = new ArrayList<>();
        for (OceanParser.CatchClauseContext catchCtx : ctx.catchClause()) {
            String varName = catchCtx.anyId().getText();
            List<String> types = new ArrayList<>();
            for (OceanParser.TypeContext tc : catchCtx.type()) {
                types.add(getTypeDescriptor(tc.getText()));
            }

            String declType = findCatchDesc(types);
            boolean isMultiCatch = types.size() > 1;

            symbolTable.enterScope();
            String boundName = varName;
            if (varName.equals("_")) {
                boundName = "$_unused_catch_" + System.nanoTime();
            }
            symbolTable.declareVariable(boundName, declType, isMultiCatch);
            IRStatement body = (IRStatement) visit(catchCtx.block());
            symbolTable.exitScope();

            catches.add(new IRTryCatchStatement.IRCatchClause(boundName, types, body));
        }

        IRStatement finallyBlock = null;
        if (ctx.FINALLY() != null) {
            finallyBlock = (IRStatement) visit(ctx.block(ctx.block().size() - 1));
        }
        return new IRTryCatchStatement(tryBlock, catches, finallyBlock, resNames);
    }

    private static String findCatchDesc(List<String> types) {
        if (types == null || types.isEmpty()) return OceanTypeSystem.OBJECT_DESC;
        if (types.size() == 1) return types.getFirst();
        String commonInternal = TypeChecker.getCommonSuperClass(types);
        return "L" + commonInternal + ";";
    }

    @Override
    public IRNode visitLockStmt(OceanParser.LockStmtContext ctx) {
        OceanParser.LockBlockStatementContext lockBlock = ctx.lockBlockStatement();
        IRExpression expr = ensureExpr(visit(lockBlock.expression()));
        IRStatement body = (IRStatement) visit(lockBlock.block());
        return new IRLockStatement(expr, body);
    }

    @Override
    public IRNode visitPostfixExpr(OceanParser.PostfixExprContext ctx) {
        IRExpression expr = ensureExpr(visit(ctx.expression()));
        IRUnaryOp.Op op = ctx.op.getType() == OceanParser.PLUS_PLUS ? IRUnaryOp.Op.POST_INC : IRUnaryOp.Op.POST_DEC;
        return new IRUnaryOp(expr, op, expr.getTypeDescriptor());
    }

    @Override
    public IRNode visitPrefixExpr(OceanParser.PrefixExprContext ctx) {
        IRExpression expr = ensureExpr(visit(ctx.expression()));
        IRUnaryOp.Op op = ctx.op.getType() == OceanParser.PLUS_PLUS ? IRUnaryOp.Op.PRE_INC : IRUnaryOp.Op.PRE_DEC;
        return new IRUnaryOp(expr, op, expr.getTypeDescriptor());
    }

    @Override
    public IRNode visitUnaryExpr(OceanParser.UnaryExprContext ctx) {
        IRExpression expr = ensureExpr(visit(ctx.expression()));
        IRUnaryOp.Op op;
        int opType = ctx.op.getType();
        if (opType == OceanParser.BANG) op = IRUnaryOp.Op.NOT;
        else if (opType == OceanParser.MINUS) op = IRUnaryOp.Op.NEG;
        else if (opType == OceanParser.TILDE) op = IRUnaryOp.Op.BIT_NOT;
        else return expr;

        String typeDesc = expr.getTypeDescriptor();
        if (op == IRUnaryOp.Op.NOT) {
            typeDesc = "Z";
        } else {
            typeDesc = TypeChecker.getUnaryNumericPromotedType(typeDesc);
        }
        return new IRUnaryOp(expr, op, typeDesc);
    }

    @Override
    public IRNode visitInstanceOfExpr(OceanParser.InstanceOfExprContext ctx) {
        IRExpression expr = ensureExpr(visit(ctx.expression()));
        IRSwitchPattern pattern = parseInstanceOfPattern(ctx.instanceofPattern(), null);
        return new IRInstanceof(expr, pattern);
    }

    private IRSwitchPattern parseInstanceOfPattern(OceanParser.InstanceofPatternContext patCtx, String expectedDesc) {
        if (patCtx instanceof OceanParser.UnnamedInstanceofPatternContext) {
            return IRSwitchPattern.ofUnnamed(expectedDesc, null);
        } else if (patCtx instanceof OceanParser.NullInstanceofPatternContext) {
            return IRSwitchPattern.ofNull(null);
        } else if (patCtx instanceof OceanParser.TypeInstanceofPatternContext typePat) {
            String typeDesc;
            boolean isValue = (typePat.VALUE() != null) || (typePat.type() != null && "value".equals(typePat.type().getText()));
            if (typePat.type() != null && !typePat.type().getText().equals("variable") && !typePat.type().getText().equals("value") && !typePat.type().getText().equals("var")) {
                String rawText = typePat.type().getText();
                String specialized = TypeChecker.getSpecializedPrimitiveListClass(rawText);
                if (specialized != null) {
                    typeDesc = OceanTypeSystem.wrapObjectType(specialized);
                } else {
                    typeDesc = getTypeDescriptor(rawText);
                }
            } else {
                typeDesc = Objects.requireNonNullElse(expectedDesc, OceanTypeSystem.OBJECT_DESC);
            }
            String varName = typePat.anyId() != null ? typePat.anyId().getText() : null;
            if (varName != null && !varName.equals("_")) {
                if (symbolTable.getIndex(varName) == -1) {
                    symbolTable.declareVariable(varName, typeDesc, true, !isValue);
                }
            }
            return IRSwitchPattern.ofType(typeDesc, varName, null);
        } else if (patCtx instanceof OceanParser.RecordInstanceofPatternContext recPat) {
            String rawTypeName = recPat.type().getText();
            String specialized = TypeChecker.getSpecializedPrimitiveListClass(rawTypeName);
            String typeDesc = specialized != null ? OceanTypeSystem.wrapObjectType(specialized) : getTypeDescriptor(rawTypeName);
            String varName = recPat.anyId() != null ? recPat.anyId().getText() : null;
            if (varName != null && !varName.equals("_")) {
                if (symbolTable.getIndex(varName) == -1) {
                    symbolTable.declareVariable(varName, typeDesc, true, true);
                }
            }
            List<CompilerRegistry.RecordComponentInfo> components = RecordHelper.getRecordComponents(typeDesc);
            List<IRSwitchPattern> nested = new ArrayList<>();
            if (recPat.patternList() != null) {
                List<OceanParser.SwitchPatternContext> subPatterns = recPat.patternList().switchPattern();
                for (int i = 0; i < subPatterns.size(); i++) {
                    OceanParser.SwitchPatternContext subPatCtx = subPatterns.get(i);
                    String compDesc = (components != null && i < components.size()) ? components.get(i).descriptor() : null;
                    nested.add(parseSwitchPattern(subPatCtx, compDesc));
                }
            }
            return IRSwitchPattern.ofRecord(typeDesc, varName, nested, null);
        }
        return IRSwitchPattern.ofType(OceanTypeSystem.OBJECT_DESC, null, null);
    }

    @Override
    public IRNode visitArrayAccessExpr(OceanParser.ArrayAccessExprContext ctx) {
        IRExpression array = ensureExpr(visit(ctx.expression(0)));
        IRExpression index = ensureExpr(visit(ctx.expression(1)));

        String typeDesc = inferRawType(ctx.expression(0));
        if (typeDesc == null || !typeDesc.startsWith("[") && array.getTypeDescriptor() != null && array.getTypeDescriptor().startsWith("[")) {
            typeDesc = array.getTypeDescriptor();
        }

        if (typeDesc != null && !typeDesc.startsWith("[")) {
            String internalName = TypeChecker.cleanDescriptor(typeDesc);
            if (internalName.startsWith("L") && internalName.endsWith(";")) {
                internalName = internalName.substring(1, internalName.length() - 1);
            }
            String std = OceanTypeSystem.resolveStandardClassPath(internalName);
            if (std != null) {
                internalName = std;
            } else {
                internalName = OceanTypeSystem.resolveInternalClassName(internalName, currentClassName);
            }
            if (TypeChecker.isMapType(internalName)) {
                return new IRArrayAccess(array, index, OceanTypeSystem.OBJECT_DESC);
            }
            String getDesc;
            String method;

            switch (internalName) {
                case "ocean/stdlib/OceanIntList" -> {
                    getDesc = "(I)I";
                    method = "getInt";
                }
                case "ocean/stdlib/OceanLongList" -> {
                    getDesc = "(I)J";
                    method = "getLong";
                }
                case "ocean/stdlib/OceanDoubleList" -> {
                    getDesc = "(I)D";
                    method = "getDouble";
                }
                case "ocean/stdlib/OceanFloatList" -> {
                    getDesc = "(I)F";
                    method = "getFloat";
                }
                case "ocean/stdlib/OceanBooleanList" -> {
                    getDesc = "(I)Z";
                    method = "getBool";
                }
                case "ocean/stdlib/OceanByteList" -> {
                    getDesc = "(I)B";
                    method = "getByte";
                }
                case "ocean/stdlib/OceanShortList" -> {
                    getDesc = "(I)S";
                    method = "getShort";
                }
                case "ocean/stdlib/OceanCharList" -> {
                    getDesc = "(I)C";
                    method = "getChar";
                }
                default -> {
                    getDesc = OverloadResolver.resolve(internalName, "get", Collections.singletonList("I"));
                    method = "get";
                    if (getDesc == null) {
                        getDesc = OverloadResolver.resolve(internalName, "getItem", Collections.singletonList("I"));
                        method = "getItem";
                    }
                    if (getDesc == null) {
                        getDesc = OverloadResolver.resolve(internalName, "at", Collections.singletonList("I"));
                        method = "at";
                    }
                    if (getDesc == null && (TypeChecker.isObjectType(internalName) || internalName.equals("java/lang/Object") || internalName.equals("ocean/stdlib/OceanList"))) {
                        internalName = "ocean/stdlib/OceanList";
                        getDesc = "(I)Ljava/lang/Object;";
                        method = "get";
                    }
                }
            }
            if (getDesc != null) {
                IRMethodCall call = new IRMethodCall(internalName, method, getDesc, Collections.singletonList(index), false);
                call.setReceiver(array);
                if (typeDesc.contains("<")) {
                    String elemType = getGenericValueType(typeDesc);
                    if (elemType != null && !TypeChecker.isObjectType(elemType)) {
                        call.setTypeDescriptor(ensureDescriptor(elemType));
                    }
                }
                return call;
            }
        }

        if (typeDesc != null && typeDesc.startsWith("[")) typeDesc = typeDesc.substring(1);
        else typeDesc = OceanTypeSystem.OBJECT_DESC;
        return new IRArrayAccess(array, index, typeDesc);
    }

    @Override
    public IRNode visitRangeSliceExpr(OceanParser.RangeSliceExprContext ctx) {
        OceanParser.ExpressionContext targetCtx = ctx.expression(0);
        IRExpression target = ensureExpr(visit(targetCtx));

        String targetType = inferRawType(targetCtx);
        if (targetType == null) targetType = target.getTypeDescriptor();
        String cleanType = TypeChecker.cleanDescriptor(targetType);

        IRExpression start = (ctx.start != null)
                ? ensureExpr(visit(ctx.start))
                : new IRLiteral(0, "I");

        if (ctx.end == null) {
            if (targetType != null && targetType.startsWith("[")) {
                String elemType = targetType.substring(1);
                boolean isPrimitiveArray = TypeChecker.isPrimitive(elemType);
                String erasedDesc = isPrimitiveArray ? targetType : "[Ljava/lang/Object;";
                IRMethodCall call = new IRMethodCall("ocean/stdlib/RuntimeUtils", "sliceArray", "(" + erasedDesc + "I)" + erasedDesc, Arrays.asList(target, start), true);
                if (!isPrimitiveArray) {
                    call.setTypeDescriptor(targetType);
                }
                return call;
            } else if (cleanType != null && (cleanType.equals("java/lang/String") || ClassMetadataCache.isSubtype(cleanType, "java/lang/CharSequence") || TypeChecker.isStringType(cleanType) || TypeChecker.isStringType(targetType))) {
                IRMethodCall call = new IRMethodCall("java/lang/String", "substring", "(I)Ljava/lang/String;", Collections.singletonList(start), false);
                call.setReceiver(target);
                return call;
            } else if (cleanType != null && cleanType.equals("ocean/stdlib/OceanList")) {
                IRMethodCall call = new IRMethodCall("ocean/stdlib/OceanList", "slice", "(I)Locean/stdlib/OceanList;", Collections.singletonList(start), false);
                call.setReceiver(target);
                return call;
            }
        }

        IRExpression endPlusOne;
        if (ctx.end != null) {
            IRExpression end = ensureExpr(visit(ctx.end));
            endPlusOne = new IRBinaryOp(end, new IRLiteral(1, "I"), IRBinaryOp.Op.ADD, "I");
        } else {
            // End omitted: fallback for other list-like types
            IRMethodCall size = new IRMethodCall(cleanType != null ? cleanType : "java/util/List", "size", "()I", Collections.emptyList(), false);
            size.setReceiver(target);
            endPlusOne = size;
        }

        if (targetType != null && targetType.startsWith("[")) {
            String elemType = targetType.substring(1);
            boolean isPrimitiveArray = TypeChecker.isPrimitive(elemType);
            String erasedDesc = isPrimitiveArray ? targetType : "[Ljava/lang/Object;";
            IRMethodCall call = new IRMethodCall("java/util/Arrays", "copyOfRange", "(" + erasedDesc + "II)" + erasedDesc, Arrays.asList(target, start, endPlusOne), true);
            if (!isPrimitiveArray) {
                call.setTypeDescriptor(targetType);
            }
            return call;
        }

        OverloadResolver.SliceMethodResolution sliceRes = OverloadResolver.findSliceMethod(cleanType);
        if (sliceRes != null) {
            IRMethodCall call = new IRMethodCall(cleanType, sliceRes.methodName(), sliceRes.descriptor(), Arrays.asList(start, endPlusOne), sliceRes.isStatic());
            call.setReceiver(target);
            return call;
        }

        // Fallback: if it's String or CharSequence, emit substring
        if (cleanType != null && (cleanType.equals("java/lang/String") || ClassMetadataCache.isSubtype(cleanType, "java/lang/CharSequence") || TypeChecker.isStringType(cleanType) || TypeChecker.isStringType(targetType))) {
            IRMethodCall call = new IRMethodCall("java/lang/String", "substring", "(II)Ljava/lang/String;", Arrays.asList(start, endPlusOne), false);
            call.setReceiver(target);
            return call;
        }

        String typeName = cleanType != null ? cleanType.replace('/', '.') : "unknown";
        reportError(ctx, "Type '" + typeName + "' does not support range slicing [start..end].");
        return new IRLiteral(null, OceanTypeSystem.OBJECT_DESC);
    }

    @Override
    public IRNode visitShiftExpr(OceanParser.ShiftExprContext ctx) {
        IRExpression left = ensureExpr(visit(ctx.expression(0)));
        IRExpression right = ensureExpr(visit(ctx.expression(1)));
        IRBinaryOp.Op op;
        String opText = ctx.op.getText();
        if (opText.equals("<<")) op = IRBinaryOp.Op.LSHIFT;
        else if (opText.equals(">>>")) op = IRBinaryOp.Op.URSHIFT;
        else op = IRBinaryOp.Op.RSHIFT;
        String shiftType = TypeChecker.getUnaryNumericPromotedType(left.getTypeDescriptor());
        return new IRBinaryOp(left, right, op, shiftType);
    }

    @Override
    public IRNode visitBitAndExpr(OceanParser.BitAndExprContext ctx) {
        IRExpression left = ensureExpr(visit(ctx.expression(0)));
        IRExpression right = ensureExpr(visit(ctx.expression(1)));
        boolean isBool = TypeChecker.isBoolean(left.getTypeDescriptor()) && TypeChecker.isBoolean(right.getTypeDescriptor());
        String type = isBool ? "Z" : (TypeChecker.isNumeric(left.getTypeDescriptor()) && TypeChecker.isNumeric(right.getTypeDescriptor())
                ? TypeChecker.getBinaryNumericPromotedType(left.getTypeDescriptor(), right.getTypeDescriptor())
                : getPromotedType(left.getTypeDescriptor(), right.getTypeDescriptor()));
        return new IRBinaryOp(left, right, IRBinaryOp.Op.BIT_AND, type);
    }

    @Override
    public IRNode visitBitXorExpr(OceanParser.BitXorExprContext ctx) {
        IRExpression left = ensureExpr(visit(ctx.expression(0)));
        IRExpression right = ensureExpr(visit(ctx.expression(1)));
        boolean isBool = TypeChecker.isBoolean(left.getTypeDescriptor()) && TypeChecker.isBoolean(right.getTypeDescriptor());
        String type = isBool ? "Z" : (TypeChecker.isNumeric(left.getTypeDescriptor()) && TypeChecker.isNumeric(right.getTypeDescriptor())
                ? TypeChecker.getBinaryNumericPromotedType(left.getTypeDescriptor(), right.getTypeDescriptor())
                : getPromotedType(left.getTypeDescriptor(), right.getTypeDescriptor()));
        return new IRBinaryOp(left, right, IRBinaryOp.Op.BIT_XOR, type);
    }

    @Override
    public IRNode visitBitOrExpr(OceanParser.BitOrExprContext ctx) {
        IRExpression left = ensureExpr(visit(ctx.expression(0)));
        IRExpression right = ensureExpr(visit(ctx.expression(1)));
        boolean isBool = TypeChecker.isBoolean(left.getTypeDescriptor()) && TypeChecker.isBoolean(right.getTypeDescriptor());
        String type = isBool ? "Z" : (TypeChecker.isNumeric(left.getTypeDescriptor()) && TypeChecker.isNumeric(right.getTypeDescriptor())
                ? TypeChecker.getBinaryNumericPromotedType(left.getTypeDescriptor(), right.getTypeDescriptor())
                : getPromotedType(left.getTypeDescriptor(), right.getTypeDescriptor()));
        return new IRBinaryOp(left, right, IRBinaryOp.Op.BIT_OR, type);
    }

    @Override
    public IRNode visitTernaryExpr(OceanParser.TernaryExprContext ctx) {
        IRExpression condition = ensureExpr(visit(ctx.expression(0)));

        Map<String, String> thenCasts = new HashMap<>();
        Map<String, String> elseCasts = new HashMap<>();
        extractSmartCasts(ctx.expression(0), thenCasts, elseCasts);

        Map<String, String> oldTypesThen = applySmartCasts(thenCasts);
        IRExpression thenExpr;
        try {
            thenExpr = ensureExpr(visit(ctx.expression(1)));
        } finally {
            restoreSmartCasts(oldTypesThen);
        }

        Map<String, String> oldTypesElse = applySmartCasts(elseCasts);
        IRExpression elseExpr;
        try {
            elseExpr = ensureExpr(visit(ctx.expression(2)));
        } finally {
            restoreSmartCasts(oldTypesElse);
        }

        String commonType = getTernaryCommonType(thenExpr.getTypeDescriptor(), elseExpr.getTypeDescriptor());
        return new IRTernaryExpression(condition, thenExpr, elseExpr, commonType);
    }

    @Override
    public IRNode visitNullCoalescingExpr(OceanParser.NullCoalescingExprContext ctx) {
        IRExpression left = ensureExpr(visit(ctx.expression(0)));
        IRExpression right = ensureExpr(visit(ctx.expression(1)));

        // a ?? b -> a != null ? a : b
        // isNullCoalescing=true bayrağı sayesinde emitter LHS'i yalnızca bir kez
        // değerlendirir (DUP + IFNONNULL + POP pattern) ve double evaluation yan etkisini önler.
        IRExpression condition;
        if (TypeChecker.isPrimitive(left.getTypeDescriptor()) && !TypeChecker.isNullable(left.getTypeDescriptor())) {
            condition = new IRLiteral(true, "Z");
        } else {
            condition = new IRBinaryOp(left, new IRLiteral(null, OceanTypeSystem.OBJECT_DESC), IRBinaryOp.Op.NE, "Z");
        }
        String commonType = getTernaryCommonType(left.getTypeDescriptor(), right.getTypeDescriptor());
        if (right.getTypeDescriptor() != null && !TypeChecker.isNullable(right.getTypeDescriptor()) && !"null".equals(right.getTypeDescriptor())) {
            commonType = TypeChecker.cleanDescriptor(commonType);
        }
        return new IRTernaryExpression(condition, left, right, commonType, true);
    }

    @Override
    public IRNode visitLogicalAndExpr(OceanParser.LogicalAndExprContext ctx) {
        IRExpression left = ensureExpr(visit(ctx.expression(0)));

        Map<String, String> thenCasts = new HashMap<>();
        Map<String, String> elseCasts = new HashMap<>();
        extractSmartCasts(ctx.expression(0), thenCasts, elseCasts);

        Map<String, String> oldTypes = applySmartCasts(thenCasts);
        IRExpression right;
        try {
            right = ensureExpr(visit(ctx.expression(1)));
        } finally {
            restoreSmartCasts(oldTypes);
        }

        return new IRBinaryOp(left, right, IRBinaryOp.Op.AND, "Z");
    }

    @Override
    public IRNode visitLogicalOrExpr(OceanParser.LogicalOrExprContext ctx) {
        IRExpression left = ensureExpr(visit(ctx.expression(0)));

        Map<String, String> thenCasts = new HashMap<>();
        Map<String, String> elseCasts = new HashMap<>();
        extractSmartCasts(ctx.expression(0), thenCasts, elseCasts);

        Map<String, String> oldTypes = applySmartCasts(elseCasts);
        IRExpression right;
        try {
            right = ensureExpr(visit(ctx.expression(1)));
        } finally {
            restoreSmartCasts(oldTypes);
        }

        return new IRBinaryOp(left, right, IRBinaryOp.Op.OR, "Z");
    }

    @Override
    public IRNode visitEqualityExpr(OceanParser.EqualityExprContext ctx) {
        IRExpression left = ensureExpr(visit(ctx.expression(0)));
        IRExpression right = ensureExpr(visit(ctx.expression(1)));
        IRBinaryOp.Op op = ctx.op.getType() == OceanParser.EQUALS ? IRBinaryOp.Op.EQ : IRBinaryOp.Op.NE;
        return new IRBinaryOp(left, right, op, "Z");
    }

    @Override
    public IRNode visitComparisonExpr(OceanParser.ComparisonExprContext ctx) {
        IRExpression left = ensureExpr(visit(ctx.expression(0)));
        IRExpression right = ensureExpr(visit(ctx.expression(1)));
        IRBinaryOp.Op op;
        int opType = ctx.op.getType();
        if (opType == OceanParser.LT) op = IRBinaryOp.Op.LT;
        else if (opType == OceanParser.GT) op = IRBinaryOp.Op.GT;
        else if (opType == OceanParser.LESS_EQUAL) op = IRBinaryOp.Op.LE;
        else op = IRBinaryOp.Op.GE;
        return new IRBinaryOp(left, right, op, "Z");
    }

    @Override
    public IRNode visitAddSubExpr(OceanParser.AddSubExprContext ctx) {
        IRExpression left = ensureExpr(visit(ctx.expression(0)));
        IRExpression right = ensureExpr(visit(ctx.expression(1)));
        IRBinaryOp.Op op = ctx.op.getType() == OceanParser.PLUS ? IRBinaryOp.Op.ADD : IRBinaryOp.Op.SUB;
        String type = getPromotedType(left.getTypeDescriptor(), right.getTypeDescriptor());
        if (op == IRBinaryOp.Op.ADD && ((TypeChecker.isStringType(left.getTypeDescriptor())) || (TypeChecker.isStringType(right.getTypeDescriptor())))) {
            type = OceanTypeSystem.STRING_DESC;
        }
        return new IRBinaryOp(left, right, op, type);
    }

    @Override
    public IRNode visitMulDivModExpr(OceanParser.MulDivModExprContext ctx) {
        IRExpression left = ensureExpr(visit(ctx.expression(0)));
        IRExpression right = ensureExpr(visit(ctx.expression(1)));
        IRBinaryOp.Op op;
        int opType = ctx.op.getType();
        if (opType == OceanParser.STAR) op = IRBinaryOp.Op.MUL;
        else if (opType == OceanParser.SLASH) op = IRBinaryOp.Op.DIV;
        else op = IRBinaryOp.Op.MOD;
        String type = getPromotedType(left.getTypeDescriptor(), right.getTypeDescriptor());
        return new IRBinaryOp(left, right, op, type);
    }

    @Override
    public IRNode visitPrimitiveTypePrimary(OceanParser.PrimitiveTypePrimaryContext ctx) {
        String typeName = ctx.getText();
        String desc = getTypeDescriptor(typeName);
        String fullPath = resolveTypeName(typeName);
        IRVariableAccess classRef = new IRVariableAccess(typeName, desc, false, fullPath, true);
        classRef.setClassReference(true);
        return classRef;
    }

    @Override
    public IRNode visitIdPrimary(OceanParser.IdPrimaryContext ctx) {
        String name = ctx.anyId().getText();
        boolean isJavaLangClass = false;
        boolean isOceanStdlibClass = false;
        boolean isWildcardClass = false;

        if (name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0))) {
            String std = OceanTypeSystem.resolveStandardClassPath(name);
            if (std != null) {
                if (std.startsWith("java/lang/")) isJavaLangClass = true;
                else if (std.startsWith("ocean/stdlib/")) isOceanStdlibClass = true;
            }

            if (!isJavaLangClass && !isOceanStdlibClass && importedWildcards != null) {
                for (String wild : importedWildcards) {
                    String testFq = wild.replace('/', '.') + "." + name;
                    if (OceanTypeSystem.hasClass(testFq)) {
                        isWildcardClass = true;
                        break;
                    }
                }
            }
        }

        boolean isGlobalClass = false;
        for (String fqName : CompilerRegistry.globalMethodRegistry.keySet()) {
            if (fqName.endsWith("/" + name) || fqName.endsWith("$" + name)) {
                isGlobalClass = true;
                break;
            }
        }
        if (!isGlobalClass) {
            for (String fqName : CompilerRegistry.globalFieldRegistry.keySet()) {
                if (fqName.endsWith("/" + name) || fqName.endsWith("$" + name)) {
                    isGlobalClass = true;
                    break;
                }
            }
        }
        if (!isGlobalClass) {
            for (String fqName : CompilerRegistry.globalSuperClassRegistry.keySet()) {
                if (fqName.endsWith("/" + name) || fqName.endsWith("$" + name)) {
                    isGlobalClass = true;
                    break;
                }
            }
        }

        boolean isLocalOrField = false;
        if (name != null) {
            isLocalOrField = (symbolTable.getType(name) != null) || (symbolTable.getIndex(name) != -1);
        }
        if (!isLocalOrField && currentClassName != null) {
            String internalName = currentClassName.replace(".", "/");
            isLocalOrField = classHasField(internalName, name) || classHasField(internalName, "val$" + name);
        }

        if (name != null && !isLocalOrField && (topLevelTypes.contains(name) || (importedClasses != null && importedClasses.containsKey(name)) || name.equals("BigDecimal") || isJavaLangClass || isOceanStdlibClass || isWildcardClass || isGlobalClass)) {
            String fullPath = resolveTypeName(name);
            IRVariableAccess classRef = new IRVariableAccess(name, OceanTypeSystem.wrapObjectType(fullPath), false, null, true);
            classRef.setClassReference(true);
            return classRef;
        }

        int localIndex = 0;
        if (name != null) {
            localIndex = symbolTable.getIndex(name);
        }
        String type = symbolTable.getType(name);
        if (type == null) type = OceanTypeSystem.OBJECT_DESC;

        boolean isField = localIndex == -1;
        String owner = isField ? currentClassName : null;
        boolean isStatic = false;

        if (isField && currentClassName != null) {
            String internalName = currentClassName.replace(".", "/");
            boolean hasLocalField = classHasField(internalName, name);
            if (!hasLocalField && classHasField(internalName, "val$" + name)) {
                name = "val$" + name;
                hasLocalField = true;
                type = resolveFieldType(internalName, name);
            }
            if (!hasLocalField) {
                String searchClass = internalName;
                boolean foundEnclosing = false;
                while (searchClass.contains("$")) {
                    searchClass = searchClass.substring(0, searchClass.lastIndexOf('$'));
                    if (classHasField(searchClass, name)) {
                        boolean isOuterStatic = resolveFieldStaticity(searchClass, name);
                        owner = searchClass;
                        isStatic = isOuterStatic;
                        type = resolveFieldType(searchClass, name);
                        foundEnclosing = true;
                        break;
                    }
                }
                if (!foundEnclosing) {
                    String staticOwner = resolveStaticImportFieldOwner(name);
                    if (staticOwner != null) {
                        owner = staticOwner;
                        isStatic = true;
                        type = resolveFieldType(staticOwner, name);
                    } else if (!activeEnumSwitchTypes.isEmpty() && !activeEnumSwitchTypes.peek().isEmpty()) {
                        String activeEnum = activeEnumSwitchTypes.peek();
                        List<String> consts = ClassMetadataCache.getEnumConstants(activeEnum);
                        if (consts != null && consts.contains(name)) {
                            owner = activeEnum;
                            isStatic = true;
                            type = owner;
                        } else {
                            reportError(ctx, "Enum switch case constant '" + name + "' is not defined in selector enum type '" + TypeChecker.humanReadable(activeEnum) + "'.", "IRGenerator");
                            throw new CompilationException("Enum switch case constant '" + name + "' is not defined in selector enum type '" + TypeChecker.humanReadable(activeEnum) + "'.");
                        }
                    } else if (isKnownEnumConstant(name)) {
                        for (Map.Entry<String, List<String>> entry : CompilerRegistry.globalEnumConstants.entrySet()) {
                            if (entry.getValue() != null && entry.getValue().contains(name)) {
                                owner = entry.getKey();
                                isStatic = true;
                                type = owner;
                                break;
                            }
                        }
                    } else {
                        reportError(ctx, "Cannot resolve symbol '" + name + "'", "IRGenerator");
                        throw new CompilationException("Cannot resolve symbol '" + name + "'");
                    }
                }
            } else {
                isStatic = resolveFieldStaticity(internalName, name);
                type = resolveFieldType(internalName, name);
            }
        }

        IRVariableAccess access = new IRVariableAccess(name, type, isField, owner, isStatic);
        if (isField && !isStatic && owner != null && !owner.replace('.', '/').equals(currentClassName != null ? currentClassName.replace('.', '/') : "")) {
            IRExpression enclosingReceiver = resolveOuterInstanceReceiver(owner);
            access.setReceiver(enclosingReceiver);
        }
        if (!isField) {
            String originalType = symbolTable.getOriginalType(name);
            if (originalType != null) {
                access.setOriginalTypeDescriptor(originalType);
            }
        }
        return access;
    }

    private IRExpression resolveOuterInstanceReceiver(String targetOuterFqcn) {
        if (targetOuterFqcn == null) return null;
        String targetClean = targetOuterFqcn.replace('.', '/');
        if (insideStaticContext && (currentClassName == null || !currentClassName.contains("$"))) {
            return null; // Top-level static method has no outer instance
        }
        String curr = currentClassName != null ? currentClassName.replace('.', '/') : null;
        if (curr != null && (curr.equals(targetClean) || curr.endsWith("/" + targetClean) || curr.endsWith("$" + targetClean))) {
            return new IRVariableAccess("this", OceanTypeSystem.wrapObjectType(curr), false, null, false);
        }
        IRExpression receiver = new IRVariableAccess("this", OceanTypeSystem.wrapObjectType(curr), false, null, false);
        while (curr != null && CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(curr)) {
            String nextOuter = CompilerRegistry.globalInnerClassUsedOuterMap.get(curr).replace('.', '/');
            IRVariableAccess this0 = new IRVariableAccess("this$0", OceanTypeSystem.wrapObjectType(nextOuter), true, curr, false);
            this0.setReceiver(receiver);
            receiver = this0;
            if (nextOuter.equals(targetClean) || nextOuter.endsWith("/" + targetClean) || nextOuter.endsWith("$" + targetClean)) {
                return receiver;
            }
            curr = nextOuter;
        }
        return null;
    }

    @Override
    public IRNode visitNumberPrimary(OceanParser.NumberPrimaryContext ctx) {
        String raw = ctx.NUMBER().getText();
        String val = raw.replace("_", "").toLowerCase();

        // 1. Hexadecimal Floating-Point
        if (val.startsWith("0x") && (val.contains("p") || val.contains("."))) {
            if (val.endsWith("f")) {
                return new IRLiteral(Float.parseFloat(val), "F");
            } else if (val.endsWith("d")) {
                return new IRLiteral(Double.parseDouble(val), "D");
            } else {
                return new IRLiteral(Double.parseDouble(val), "D");
            }
        }

        // 2. Hexadecimal Integer
        if (val.startsWith("0x")) {
            String digits = val.substring(2);
            boolean isLong = digits.endsWith("l");
            if (isLong) digits = digits.substring(0, digits.length() - 1);
            if (isLong) {
                return new IRLiteral(Long.parseUnsignedLong(digits, 16), "J");
            } else {
                try {
                    return new IRLiteral(Integer.parseUnsignedInt(digits, 16), "I");
                } catch (NumberFormatException e) {
                    return new IRLiteral(Long.parseUnsignedLong(digits, 16), "J");
                }
            }
        }

        // 3. Binary Integer
        if (val.startsWith("0b")) {
            String digits = val.substring(2);
            boolean isLong = digits.endsWith("l");
            if (isLong) digits = digits.substring(0, digits.length() - 1);
            if (isLong) {
                return new IRLiteral(Long.parseUnsignedLong(digits, 2), "J");
            } else {
                try {
                    return new IRLiteral(Integer.parseUnsignedInt(digits, 2), "I");
                } catch (NumberFormatException e) {
                    return new IRLiteral(Long.parseUnsignedLong(digits, 2), "J");
                }
            }
        }

        // 4. Floating Point (Decimal) with exponents or suffixes
        if (val.contains(".") || val.contains("e") || val.endsWith("f") || val.endsWith("d")) {
            if (val.endsWith("f")) {
                return new IRLiteral(Float.parseFloat(val.substring(0, val.length() - 1)), "F");
            } else if (val.endsWith("d")) {
                return new IRLiteral(Double.parseDouble(val.substring(0, val.length() - 1)), "D");
            } else {
                try {
                    double d = Double.parseDouble(val);
                    if (!Double.isInfinite(d) && !Double.isNaN(d)) {
                        return new IRLiteral(d, "D");
                    }
                } catch (NumberFormatException ignored) {
                }
                return new IRLiteral(new BigDecimal(val), OceanTypeSystem.BIGDECIMAL_DESC);
            }
        }

        // 5. Octal Integer
        if (val.startsWith("0") && val.length() > 1) {
            String digits = val;
            boolean isLong = digits.endsWith("l");
            if (isLong) digits = digits.substring(0, digits.length() - 1);
            boolean isOctal = true;
            for (int i = 1; i < digits.length(); i++) {
                char c = digits.charAt(i);
                if (c < '0' || c > '7') {
                    isOctal = false;
                    break;
                }
            }
            if (isOctal) {
                if (isLong) {
                    return new IRLiteral(Long.parseUnsignedLong(digits, 8), "J");
                } else {
                    try {
                        return new IRLiteral(Integer.parseUnsignedInt(digits, 8), "I");
                    } catch (NumberFormatException e) {
                        return new IRLiteral(Long.parseUnsignedLong(digits, 8), "J");
                    }
                }
            }
        }

        // 6. Decimal Integer
        if (val.endsWith("l")) {
            String clean = val.substring(0, val.length() - 1);
            if (clean.isEmpty()) clean="0";
            return new IRLiteral(Long.parseLong(clean), "J");
        } else {
            try {
                return new IRLiteral(Integer.parseInt(val), "I");
            } catch (NumberFormatException e) {
                try {
                    return new IRLiteral(Long.parseLong(val), "J");
                } catch (NumberFormatException ex) {
                    return new IRLiteral(new BigDecimal(val), OceanTypeSystem.BIGDECIMAL_DESC);
                }
            }
        }
    }

    @Override
    public IRNode visitTruePrimary(OceanParser.TruePrimaryContext ctx) {
        return new IRLiteral(true, "Z");
    }

    @Override
    public IRNode visitFalsePrimary(OceanParser.FalsePrimaryContext ctx) {
        return new IRLiteral(false, "Z");
    }

    @Override
    public IRNode visitNullPrimary(OceanParser.NullPrimaryContext ctx) {
        return new IRLiteral(null, "null");
    }

    @Override
    public IRNode visitCharPrimary(OceanParser.CharPrimaryContext ctx) {
        if (ctx.CHAR_LITERAL() != null) {
            String text = ctx.CHAR_LITERAL().getText();
            String unescaped = StringHelper.unescapeString(text.substring(1, text.length() - 1));
            char c = unescaped.isEmpty() ? '\0' : unescaped.charAt(0);
            return new IRLiteral(c, "C");
        }
        return new IRLiteral('\0', "C");
    }

    @Override
    public IRNode visitStringPrimary(OceanParser.StringPrimaryContext ctx) {
        if (ctx.STRING_LITERAL() != null) {
            String val = ctx.STRING_LITERAL().getText();
            return new IRLiteral(StringHelper.unescapeString(val.substring(1, val.length() - 1)), OceanTypeSystem.STRING_DESC);
        } else {
            String val = ctx.MULTILINE_STRING().getText();
            String raw = val.substring(3, val.length() - 3);
            return new IRLiteral(StringHelper.unescapeTextBlock(StringHelper.stripIndent(raw)), OceanTypeSystem.STRING_DESC);
        }
    }

    @Override
    public IRNode visitParenthesizedPrimary(OceanParser.ParenthesizedPrimaryContext ctx) {
        return visit(ctx.expression());
    }

    @Override
    public IRNode visitThisRefPrimary(OceanParser.ThisRefPrimaryContext ctx) {
        String type = symbolTable.getType("this");
        if (type != null) return new IRVariableAccess("this", type, false, null, false);
        return new IRVariableAccess("this", OceanTypeSystem.wrapObjectType(currentClassName != null ? currentClassName.replace(".", "/") : "java/lang/Object"), false, currentClassName, false);
    }

    @Override
    public IRNode visitSuperRefPrimary(OceanParser.SuperRefPrimaryContext ctx) {
        return new IRVariableAccess("super", OceanTypeSystem.wrapObjectType(currentSuperName != null ? currentSuperName.replace(".", "/") : "java/lang/Object"), false, currentSuperName, false);
    }

    @Override
    public IRNode visitListLiteralPrimary(OceanParser.ListLiteralPrimaryContext ctx) {
        List<IRExpression> elements = new ArrayList<>();
        if (ctx.argumentList() != null) {
            for (OceanParser.ExpressionContext eCtx : getArgumentExpressions(ctx.argumentList())) {
                elements.add(ensureExpr(visit(eCtx)));
            }
        }
        String typeDesc = inferListLiteralDescriptor(elements);
        IRArrayLiteral lit = new IRArrayLiteral(elements, typeDesc);
        lit.setHomogeneous(computeCollectionHomogeneous(elements, false));
        return lit;
    }

    @Override
    public IRNode visitSetLiteralPrimary(OceanParser.SetLiteralPrimaryContext ctx) {
        List<IRExpression> elements = new ArrayList<>();
        if (ctx.argumentList() != null) {
            for (OceanParser.ExpressionContext eCtx : getArgumentExpressions(ctx.argumentList())) {
                elements.add(ensureExpr(visit(eCtx)));
            }
        }
        String typeDesc = inferSetLiteralDescriptor(elements);
        IRArrayLiteral lit = new IRArrayLiteral(elements, typeDesc);
        lit.setHomogeneous(computeCollectionHomogeneous(elements, false));
        return lit;
    }

    @Override
    public IRNode visitMapLiteralPrimary(OceanParser.MapLiteralPrimaryContext ctx) {
        List<IRExpression> elements = new ArrayList<>();
        if (ctx.mapEntry() != null) {
            for (OceanParser.MapEntryContext mCtx : ctx.mapEntry()) {
                elements.add(ensureExpr(visit(mCtx.expression(0))));
                elements.add(ensureExpr(visit(mCtx.expression(1))));
            }
        }
        String typeDesc = inferMapLiteralDescriptor(elements);
        IRArrayLiteral lit = new IRArrayLiteral(elements, typeDesc);
        lit.setHomogeneous(computeCollectionHomogeneous(elements, true));
        return lit;
    }

    /**
     * Recursive homojenlik hesabı:
     * 1. Herhangi bir iç IRArrayLiteral heterojen ise → dıştaki de heterojen.
     * 2. Tüm elemanların (ya da map için key/value çiftlerinin) tam tipi aynı değilse heterojen.
     */
    private boolean computeCollectionHomogeneous(List<IRExpression> elements, boolean isMap) {
        if (elements.isEmpty()) return true;
        // Adım 1: herhangi bir iç literal heterojen mi? (recursive)
        for (IRExpression el : elements) {
            if (el instanceof IRArrayLiteral inner && !inner.isHomogeneous()) {
                return false;
            }
        }
        // Adım 2: elemanların tam tipi aynı mı?
        if (isMap) {
            // Map: key'ler ve value'lar kendi içinde uniform olmalı
            String keyType = elements.get(0).getTypeDescriptor();
            String valType = elements.size() > 1 ? elements.get(1).getTypeDescriptor() : null;
            for (int i = 2; i < elements.size(); i += 2) {
                String k = elements.get(i).getTypeDescriptor();
                String v = (i + 1 < elements.size()) ? elements.get(i + 1).getTypeDescriptor() : null;
                if (k != null && keyType != null && !k.equals(keyType)) return false;
                if (v != null && valType != null && !v.equals(valType)) return false;
            }
        } else {
            String firstType = elements.getFirst().getTypeDescriptor();
            for (int i = 1; i < elements.size(); i++) {
                String t = elements.get(i).getTypeDescriptor();
                if (t != null && firstType != null && !t.equals(firstType)) return false;
            }
        }
        return true;
    }

    /**
     * Recursively infers the element type for a list literal.
     * Elements are already visited IRArrayLiteral nodes whose typeDescriptor
     * carries the full nested generic descriptor — no re-parsing needed.
     */
    private String inferListLiteralDescriptor(List<IRExpression> elements) {
        if (elements.isEmpty()) {
            if (currentCastType != null && currentCastType.contains("<")) {
                String clean = TypeChecker.cleanDescriptor(currentCastType);
                if (clean.equals("ocean/stdlib/OceanList") || TypeChecker.isListOrCollectionType(clean, CompilationSession.getActiveSession())) {
                    return currentCastType;
                }
            }
            return "Locean/stdlib/OceanList;";
        }
        String elemType = elements.getFirst().getTypeDescriptor();
        if (elemType == null) elemType = OceanTypeSystem.OBJECT_DESC;
        for (int i = 1; i < elements.size(); i++) {
            String t = elements.get(i).getTypeDescriptor();
            if (t != null && !elemType.equals(t)) {
                elemType = TypeChecker.getCommonType(elemType, t);
            }
        }
        return "Locean/stdlib/OceanList<" + elemType + ">;";
    }

    private String inferSetLiteralDescriptor(List<IRExpression> elements) {
        if (elements.isEmpty()) {
            if (currentCastType != null && currentCastType.contains("<")) {
                String clean = TypeChecker.cleanDescriptor(currentCastType);
                if (TypeChecker.isSetType(clean)) {
                    return currentCastType;
                }
            }
            return "Locean/stdlib/OceanSet;";
        }
        String elemType = elements.getFirst().getTypeDescriptor();
        if (elemType == null) elemType = OceanTypeSystem.OBJECT_DESC;
        for (int i = 1; i < elements.size(); i++) {
            String t = elements.get(i).getTypeDescriptor();
            if (t != null && !elemType.equals(t)) {
                elemType = TypeChecker.getCommonType(elemType, t);
            }
        }
        return "Locean/stdlib/OceanSet<" + elemType + ">;";
    }

    private String inferMapLiteralDescriptor(List<IRExpression> elements) {
        if (elements.isEmpty() || elements.size() < 2) {
            if (currentCastType != null && currentCastType.contains("<")) {
                String clean = TypeChecker.cleanDescriptor(currentCastType);
                if (TypeChecker.isMapType(clean)) {
                    return currentCastType;
                }
            }
            return "Locean/stdlib/OceanMap;";
        }
        String keyType = elements.get(0).getTypeDescriptor();
        String valType = elements.get(1).getTypeDescriptor();
        if (keyType == null) keyType = OceanTypeSystem.OBJECT_DESC;
        if (valType == null) valType = OceanTypeSystem.OBJECT_DESC;

        for (int i = 2; i < elements.size(); i += 2) {
            String k = elements.get(i).getTypeDescriptor();
            String v = i + 1 < elements.size() ? elements.get(i + 1).getTypeDescriptor() : null;
            if (k != null && !keyType.equals(k)) {
                keyType = TypeChecker.getCommonType(keyType, k);
            }
            if (v != null && !valType.equals(v)) {
                valType = TypeChecker.getCommonType(valType, v);
            }
        }
        return "Locean/stdlib/OceanMap<" + keyType + "," + valType + ">;";
    }

    @Override
    public IRNode visitArrayLiteralPrimary(OceanParser.ArrayLiteralPrimaryContext ctx) {
        List<IRExpression> elements = new ArrayList<>();
        String elemDesc = OceanTypeSystem.OBJECT_DESC;
        List<OceanParser.ExpressionContext> argExprs = getArgumentExpressions(ctx.argumentList());
        if (!argExprs.isEmpty()) {
            for (OceanParser.ExpressionContext eCtx : argExprs) {
                elements.add(ensureExpr(visit(eCtx)));
            }
            elemDesc = elements.getFirst().getTypeDescriptor();
            for (int i = 1; i < elements.size(); i++) {
                String t = elements.get(i).getTypeDescriptor();
                if (!elemDesc.equals(t)) {
                    elemDesc = TypeChecker.getCommonType(elemDesc, t);
                }
            }
        }
        return new IRArrayLiteral(elements, "[" + elemDesc);
    }
    @Override
    public IRNode visitClassLiteralExpr(OceanParser.ClassLiteralExprContext ctx) {
        String rawType;
        if (ctx.VOID() != null) {
            rawType = "V";
        } else if (ctx.primitiveType() != null) {
            rawType = getTypeDescriptor(ctx.primitiveType().getText());
        } else if (ctx.typeName() != null) {
            String name = ctx.typeName().getText();
            String resolved = resolveTypeName(name);
            String clean = resolved != null ? resolved : name;
            clean = clean.replace('.', '/');
            if (TypeChecker.isClassType(clean)) {
                rawType = clean;
            } else {
                rawType = "L" + clean + ";";
            }
        } else {
            rawType = OceanTypeSystem.OBJECT_DESC;
        }

        int arrayDim = (ctx.LBRACK() != null) ? ctx.LBRACK().size() : 0;

        String owner = "[".repeat(arrayDim) + rawType;
        if (arrayDim == 0 && TypeChecker.isClassType(rawType)) {
            owner = rawType.substring(1, rawType.length() - 1);
        }

        return new IRVariableAccess("class", "Ljava/lang/Class;", true, owner, true);
    }

    @Override
    public IRNode visitQualifiedThisExpr(OceanParser.QualifiedThisExprContext ctx) {
        String targetName = ctx.typeName().getText();
        String resolvedTarget = resolveTypeName(targetName);
        resolvedTarget = Objects.requireNonNullElse(resolvedTarget, targetName).replace('.', '/');

        IRExpression receiver = resolveOuterInstanceReceiver(resolvedTarget);
        if (receiver == null) {
            if (insideStaticContext) {
                reportError(ctx, "Cannot use 'X.this' in a static context: '" + targetName + "'");
            } else {
                reportError(ctx, "No enclosing instance of type '" + targetName + "' is in scope.");
            }
            return new IRVariableAccess("this", OceanTypeSystem.wrapObjectType(resolvedTarget), false, null, false);
        }
        return receiver;
    }

    @Override
    public IRNode visitQualifiedSuperExpr(OceanParser.QualifiedSuperExprContext ctx) {
        String targetName = ctx.typeName().getText();
        String resolvedTarget = resolveTypeName(targetName);
        resolvedTarget = Objects.requireNonNullElse(resolvedTarget, targetName).replace('.', '/');

        IRExpression receiver = resolveOuterInstanceReceiver(resolvedTarget);
        if (receiver == null) {
            boolean isIface = CompilerRegistry.globalIsInterfaceSet.contains(resolvedTarget)
                    || ClassMetadataCache.isInterface(resolvedTarget);
            if (isIface) {
                if (insideStaticContext) {
                    reportError(ctx, "Cannot use 'X.super' in a static context: '" + targetName + "'");
                }
                return new IRVariableAccess("super", OceanTypeSystem.wrapObjectType(resolvedTarget), false, null, false);
            }
            if (insideStaticContext) {
                reportError(ctx, "Cannot use 'X.super' in a static context: '" + targetName + "'");
            } else {
                reportError(ctx, "No enclosing instance of type '" + targetName + "' is in scope.");
            }
            return new IRVariableAccess("super", OceanTypeSystem.wrapObjectType(resolvedTarget), false, null, false);
        }
        if (receiver instanceof IRVariableAccess va) {
            va.setName("super");
        }
        return receiver;
    }

    @Override
    public IRNode visitMemberCallExpr(OceanParser.MemberCallExprContext ctx) {
        String rawOwnerType = inferRawType(ctx.expression());
        return visitMemberCallExprDelegate(ensureExpr(visit(ctx.expression())), rawOwnerType, ctx.anyId().getText(), ctx.LPAREN() != null, ctx.argumentList(), ctx.typeArguments());
    }

    @Override
    public IRNode visitSafeMemberCallExpr(OceanParser.SafeMemberCallExprContext ctx) {
        String rawOwnerType = inferRawType(ctx.expression());
        IRNode node = visitMemberCallExprDelegate(ensureExpr(visit(ctx.expression())), rawOwnerType, ctx.anyId().getText(), ctx.LPAREN() != null, ctx.argumentList(), ctx.typeArguments());

        boolean isStatic = false;
        if (node instanceof IRMethodCall) isStatic = ((IRMethodCall) node).isStatic();
        else if (node instanceof IRVariableAccess) isStatic = ((IRVariableAccess) node).isStatic();

        if (!isStatic) {
            if (node instanceof IRVariableAccess va) {
                va.setSafeAccess(true);
                String desc = va.getTypeDescriptor();
                if (desc != null && !"V".equals(TypeChecker.cleanDescriptor(desc)) && !"void".equals(desc)) {
                    if (TypeChecker.isPrimitive(desc)) {
                        desc = TypeChecker.box(desc);
                    }
                    if (!desc.endsWith("?")) {
                        desc = desc + "?";
                    }
                    va.setTypeDescriptor(desc);
                }
            } else if (node instanceof IRMethodCall mc) {
                mc.setSafeAccess(true);
                String desc = mc.getTypeDescriptor();
                if (desc != null && !"V".equals(TypeChecker.cleanDescriptor(desc)) && !"void".equals(desc)) {
                    if (TypeChecker.isPrimitive(desc)) {
                        desc = TypeChecker.box(desc);
                    }
                    if (!desc.endsWith("?")) {
                        desc = desc + "?";
                    }
                    mc.setTypeDescriptor(desc);
                }
            }
        }
        return node;
    }

    @Override
    public IRNode visitMethodCallExpr(OceanParser.MethodCallExprContext ctx) {
        String methodName = "unknown";
        if (ctx.expression() instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) ctx.expression()).primary();
            if (p instanceof OceanParser.IdPrimaryContext) {
                methodName = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                if (methodName.equals("OceanOutput") || methodName.equals("Output")) {
                    List<IRExpression> args = new ArrayList<>();
                    for (OceanParser.ExpressionContext eCtx : getArgumentExpressions(ctx.argumentList()))
                        args.add(ensureExpr(visit(eCtx)));
                    return new IROceanOutput(args);
                }

                String varType = symbolTable.getType(methodName);
                if (varType != null) {
                    String[] sam = resolveSAM(varType);
                    if (sam != null) {
                        String samName = sam[0];
                        String samDesc = sam[1];
                        IRVariableAccess receiver = new IRVariableAccess(methodName, varType, false, null, false);
                        List<IRExpression> args = new ArrayList<>();
                        List<String> paramTypes = getParameterDescriptors(samDesc);
                        List<OceanParser.ExpressionContext> argExprs = getArgumentExpressions(ctx.argumentList());
                        int totalArgs = argExprs.size();
                        for (int i = 0; i < totalArgs; i++) {
                            OceanParser.ExpressionContext eCtx = argExprs.get(i);
                            String expectedType = i < paramTypes.size() ? paramTypes.get(i) : OceanTypeSystem.OBJECT_DESC;
                            String oldCast = currentCastType;
                            currentCastType = expectedType;
                            args.add(ensureExpr(visit(eCtx)));
                            currentCastType = oldCast;
                        }
                        String cleanOwner = TypeChecker.cleanDescriptor(varType);
                        if (cleanOwner.startsWith("L") && cleanOwner.endsWith(";")) {
                            cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
                        }
                        String owner = !cleanOwner.isEmpty() ? cleanOwner : "java/lang/Object";
                        IRMethodCall call = new IRMethodCall(owner, samName, samDesc, args, false);
                        call.setReceiver(receiver);
                        List<String> argTypeDescs = args.stream().map(IRExpression::getTypeDescriptor).toList();
                        String refinedReturnType = resolveGenericReturnType(varType, samName, argTypeDescs);
                        if (refinedReturnType != null && !refinedReturnType.equals(call.getTypeDescriptor())) {
                            call.setTypeDescriptor(refinedReturnType);
                        }
                        return call;
                    }
                }
            } else if (p instanceof OceanParser.SuperRefPrimaryContext) {
                if ("java/lang/Enum".equals(currentSuperName)) {
                    reportError(ctx, "Cannot invoke 'super()' in an enum constructor. Only 'this()' is allowed.");
                    throw new CompilationException("Cannot invoke 'super()' in an enum constructor. Only 'this()' is allowed.");
                }
                List<IRExpression> args = new ArrayList<>();
                String owner = currentSuperName != null ? currentSuperName : "java/lang/Object";
                List<OceanParser.ExpressionContext> argExprs = getArgumentExpressions(ctx.argumentList());
                int totalArgs = argExprs.size();
                for (int i = 0; i < totalArgs; i++) {
                    OceanParser.ExpressionContext eCtx = argExprs.get(i);
                    String expectedType = getExpectedCtorParamType(owner, i, totalArgs);
                    String oldCast = currentCastType;
                    if (expectedType != null) {
                        currentCastType = expectedType;
                    }
                    args.add(ensureExpr(visit(eCtx)));
                    currentCastType = oldCast;
                }
                String desc = resolveMethodDescriptor(owner, "<init>", args);
                packVarargsIfNecessary(owner, "<init>", desc, args);
                IRMethodCall call = new IRMethodCall(owner, "<init>", desc, args, false);
                call.setSuperCall(true);
                return call;
            } else if (p instanceof OceanParser.ThisRefPrimaryContext) {
                List<IRExpression> args = new ArrayList<>();
                String owner = currentClassName;
                boolean isEnum = "java/lang/Enum".equals(currentSuperName);
                if (isEnum) {
                    args.add(new IRVariableAccess("$name", OceanTypeSystem.STRING_DESC, false, null, false));
                    args.add(new IRVariableAccess("$ordinal", "I", false, null, false));
                } else if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(currentClassName)) {
                    String outerFqcn = CompilerRegistry.globalInnerClassUsedOuterMap.get(currentClassName);
                    args.add(new IRVariableAccess("this$0", "L" + outerFqcn + ";", true, null, false));
                }
                int offset = isEnum ? 2 : (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(currentClassName) ? 1 : 0);
                List<OceanParser.ExpressionContext> argExprs = getArgumentExpressions(ctx.argumentList());
                int totalArgs = argExprs.size();
                for (int i = 0; i < totalArgs; i++) {
                    OceanParser.ExpressionContext eCtx = argExprs.get(i);
                    String expectedType = getExpectedCtorParamType(owner, i + offset, totalArgs + offset);
                    String oldCast = currentCastType;
                    if (expectedType != null) {
                        currentCastType = expectedType;
                    }
                    args.add(ensureExpr(visit(eCtx)));
                    currentCastType = oldCast;
                }
                String desc = resolveMethodDescriptor(owner, "<init>", args);
                packVarargsIfNecessary(owner, "<init>", desc, args);
                IRMethodCall call = new IRMethodCall(owner, "<init>", desc, args, false);
                call.setSuperCall(true); // this() must use INVOKESPECIAL, not INVOKEVIRTUAL
                call.setThisCall(true);  // mark as delegating constructor (this() not super())
                return call;
            }
        }
        return visitMemberCallExprDelegate( methodName, ctx.argumentList());
    }

    private IRNode visitMemberCallExprDelegate(String memberName, OceanParser.ArgumentListContext argList) {
        return visitMemberCallExprDelegate(null, null, memberName, true, argList);
    }

    private IRNode visitMemberCallExprDelegate(IRExpression receiver, String rawOwnerType, String memberName, boolean isMethod, OceanParser.ArgumentListContext argList) {
        return visitMemberCallExprDelegate(receiver, rawOwnerType, memberName, isMethod, argList, null);
    }

    private IRNode visitMemberCallExprDelegate(IRExpression receiver, String rawOwnerType, String memberName, boolean isMethod, OceanParser.ArgumentListContext argList, OceanParser.TypeArgumentsContext typeArgsCtx) {
        if (receiver != null && memberName.equals("length") && !isMethod) {
            String recType = receiver.getTypeDescriptor();
            if (recType != null && recType.startsWith("[")) {
                IRVariableAccess fieldAcc = new IRVariableAccess("length", "I", true, recType, false);
                fieldAcc.setReceiver(receiver);
                return fieldAcc;
            } else if (rawOwnerType != null && rawOwnerType.startsWith("[")) {
                IRVariableAccess fieldAcc = new IRVariableAccess("length", "I", true, rawOwnerType, false);
                fieldAcc.setReceiver(receiver);
                return fieldAcc;
            } else if ("Locean/stdlib/OceanList;".equals(recType) || "ocean/stdlib/OceanList".equals(rawOwnerType)) {
                IRVariableAccess fieldAcc = new IRVariableAccess("length", "I", true, "ocean/stdlib/OceanList", false);
                fieldAcc.setReceiver(receiver);
                return fieldAcc;
            }
        }
        

        String rawVarType = null;
        if (receiver instanceof IRVariableAccess va && !va.isClassReference()) {
            rawVarType = symbolTable.getRawType(va.getName());
        }
        if (rawVarType == null && rawOwnerType != null && !rawOwnerType.isEmpty()) {
            rawVarType = rawOwnerType;
        }
        CompilerRegistry.TypeParameterInfo tpInfo = findTypeParameterInfo(rawVarType);

        String rawOwnerTypeWithGenerics;
        if (receiver instanceof IRVariableAccess va && va.isClassReference()) {
            rawOwnerTypeWithGenerics = va.getTypeDescriptor();
        } else if (receiver != null && receiver.getTypeDescriptor() != null && receiver.getTypeDescriptor().contains("<")) {
            rawOwnerTypeWithGenerics = receiver.getTypeDescriptor();
        } else if (rawOwnerType != null && !OceanTypeSystem.OBJECT_DESC.equals(rawOwnerType)) {
            rawOwnerTypeWithGenerics = rawOwnerType;
        } else if (receiver != null && receiver.getTypeDescriptor() != null) {
            rawOwnerTypeWithGenerics = receiver.getTypeDescriptor();
        } else {
            rawOwnerTypeWithGenerics = OceanTypeSystem.wrapObjectType(currentClassName != null ? currentClassName.replace(".", "/") : "java/lang/Object");
        }
        String ownerDesc = TypeChecker.cleanDescriptor(rawOwnerTypeWithGenerics);
        String internalOwner = "java/lang/Object";
        if (ownerDesc != null) {
            if (ownerDesc.startsWith("[")) {
                if ("clone".equals(memberName) && (argList == null || argList.argument().isEmpty())) {
                    IRMethodCall call = new IRMethodCall("java/lang/Object", "clone", "()Ljava/lang/Object;", new ArrayList<>(), false);
                    call.setReceiver(receiver);
                    call.setTypeDescriptor(ownerDesc);
                    return call;
                }
                if ("toString".equals(memberName) && (argList == null || argList.argument().isEmpty())) {
                    IRMethodCall call = new IRMethodCall("ocean/stdlib/RuntimeUtils", "smartToString", "(Ljava/lang/Object;)Ljava/lang/String;", List.of(receiver), true);
                    call.setTypeDescriptor(OceanTypeSystem.STRING_DESC);
                    return call;
                }
                if ("equals".equals(memberName) || "hashCode".equals(memberName) || "getClass".equals(memberName)) {
                    internalOwner = "java/lang/Object";
                } else {
                    reportError(currentCtx, "Method '" + memberName + "' not found in type '" + TypeChecker.humanReadable(internalOwner) + "'.");
                }
            } else {
                String cleanOwner = ownerDesc;
                if (TypeChecker.isClassType(cleanOwner)) {
                    cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
                }
                if (cleanOwner.endsWith(";")) {
                    cleanOwner = cleanOwner.substring(0, cleanOwner.length() - 1);
                }
                if (cleanOwner.contains("<")) {
                    cleanOwner = cleanOwner.substring(0, cleanOwner.indexOf('<'));
                }
                String resolved = resolveTypeName(cleanOwner);
                internalOwner = (resolved != null) ? resolved : cleanOwner;
            }
        }

        boolean isStatic = false;
        boolean isExplicitClassTarget = false;
        if (receiver instanceof IRVariableAccess && ((IRVariableAccess) receiver).isClassReference()) {
            isStatic = true;
            isExplicitClassTarget = true;
            receiver = null;
        } else if (receiver == null) {
            if (isMethod) {
                boolean currentClassHasMethod = classHasMethod(internalOwner, memberName);
                if (!currentClassHasMethod) {
                    String searchClass = internalOwner;
                    boolean foundEnclosing = false;
                    while (searchClass.contains("$")) {
                        searchClass = searchClass.substring(0, searchClass.lastIndexOf('$'));
                        if (classHasMethod(searchClass, memberName)) {
                            internalOwner = searchClass;
                            Map<String, Boolean> statics = CompilerRegistry.globalMethodStaticity.get(internalOwner);
                            if (statics != null && statics.getOrDefault(memberName, false)) {
                                isStatic = true;
                            } else {
                                receiver = resolveOuterInstanceReceiver(searchClass);
                            }
                            foundEnclosing = true;
                            break;
                        }
                    }
                    if (!foundEnclosing) {
                        String staticOwner = resolveStaticImportMethodOwner(memberName);
                        if (staticOwner != null) {
                            internalOwner = staticOwner;
                            isStatic = true;
                        }
                    }
                } else {
                    Map<String, Boolean> statics = CompilerRegistry.globalMethodStaticity.get(internalOwner);
                    if (statics != null && statics.getOrDefault(memberName, false)) isStatic = true;
                }
            } else {
                boolean currentClassHasField = classHasField(internalOwner, memberName);
                if (!currentClassHasField) {
                    String searchClass = internalOwner;
                    boolean foundEnclosing = false;
                    while (searchClass.contains("$")) {
                        searchClass = searchClass.substring(0, searchClass.lastIndexOf('$'));
                        if (classHasField(searchClass, memberName)) {
                            internalOwner = searchClass;
                            isStatic = resolveFieldStaticity(internalOwner, memberName);
                            if (!isStatic) {
                                receiver = resolveOuterInstanceReceiver(searchClass);
                            }
                            foundEnclosing = true;
                            break;
                        }
                    }
                    if (!foundEnclosing) {
                        String staticOwner = resolveStaticImportFieldOwner(memberName);
                        if (staticOwner != null) {
                            internalOwner = staticOwner;
                            isStatic = true;
                        } else {
                            isStatic = resolveFieldStaticity(internalOwner, memberName);
                        }
                    }
                } else {
                    isStatic = resolveFieldStaticity(internalOwner, memberName);
                }
            }
        }

        if (isMethod) {
            // Try to resolve as an extension method first
            if (receiver != null && ownerDesc != null) {
                List<CompilerRegistry.ExtensionMethodInfo> candidates = CompilerRegistry.findExtensionMethods(ownerDesc, memberName);
                if (!candidates.isEmpty()) {
                    List<IRExpression> extArgs = new ArrayList<>();
                    extArgs.add(receiver); // Receiver is the first parameter (this) for static extension method
                    if (argList != null) {
                        List<OceanParser.ExpressionContext> argExprs = getArgumentExpressions(argList);
                        int totalArgs = argExprs.size();
                        for (int i = 0; i < totalArgs; i++) {
                            OceanParser.ExpressionContext eCtx = argExprs.get(i);
                            String expectedType = getExpectedParamType(internalOwner, memberName, i, totalArgs);
                            String oldCast = currentCastType;
                            if (expectedType != null) {
                                currentCastType = expectedType;
                            }
                            extArgs.add(ensureExpr(visit(eCtx)));
                            currentCastType = oldCast;
                        }
                    }

                    CompilerRegistry.ExtensionMethodInfo bestMatch = null;
                    for (CompilerRegistry.ExtensionMethodInfo emi : candidates) {
                        List<String> paramTypes = getParameterDescriptors(emi.descriptor());
                        if (paramTypes.size() == extArgs.size()) {
                            boolean compatible = true;
                            for (int i = 0; i < extArgs.size(); i++) {
                                String argDesc = extArgs.get(i).getTypeDescriptor();
                                String paramDesc = paramTypes.get(i);
                                if (!argDesc.equals(paramDesc) && !isAssignableRefs(argDesc, paramDesc)) {
                                    compatible = false;
                                    break;
                                }
                            }
                            if (compatible) {
                                bestMatch = emi;
                                break;
                            }
                        }
                    }

                    if (bestMatch != null) {
                        IRMethodCall call = new IRMethodCall(bestMatch.owner(), bestMatch.name(), bestMatch.descriptor(), extArgs, true);
                        call.setReceiver(null);
                        return call;
                    }
                }
            }

            List<IRExpression> args = buildCanonicalArguments(internalOwner, memberName, argList, rawOwnerTypeWithGenerics);
            if (memberName.equals("main") && args.isEmpty()) {
                args.add(new IRArrayCreation(OceanTypeSystem.STRING_DESC, List.of(new IRLiteral(0, "I"))));
            }
            if (tpInfo == null && currentClassName != null) {
                List<CompilerRegistry.TypeParameterInfo> infos = getTypeParameterInfosForClass(currentClassName);
                if (infos != null) {
                    for (CompilerRegistry.TypeParameterInfo info : infos) {
                        for (String bound : info.getUpperBounds()) {
                            String clean = TypeChecker.cleanDescriptor(bound);
                            if (clean.startsWith("L") && clean.endsWith(";")) clean = clean.substring(1, clean.length() - 1);
                            String resolvedClean = resolveTypeName(clean);
                            if (clean.equals(internalOwner) || resolvedClean.equals(internalOwner) || clean.endsWith("/" + internalOwner) || internalOwner.endsWith("/" + clean)) {
                                tpInfo = info;
                                break;
                            }
                        }
                        if (tpInfo != null) break;
                    }
                }
            }

            OverloadResolver.ResolutionResult res = resolveMethodSilent(internalOwner, memberName, args);
            if (res == null && tpInfo != null && !tpInfo.getUpperBounds().isEmpty()) {
                for (String bound : tpInfo.getUpperBounds()) {
                    String candidateOwner = TypeChecker.cleanDescriptor(bound);
                    if (candidateOwner.startsWith("L") && candidateOwner.endsWith(";")) {
                        candidateOwner = candidateOwner.substring(1, candidateOwner.length() - 1);
                    }
                    String resolvedCandidate = resolveTypeName(candidateOwner);
                    if (resolvedCandidate != null && !resolvedCandidate.isEmpty()) {
                        candidateOwner = resolvedCandidate;
                    }
                    if (candidateOwner.equals(internalOwner)) continue;
                    try {
                        List<IRExpression> candArgs = buildCanonicalArguments(candidateOwner, memberName, argList, rawOwnerTypeWithGenerics);
                        OverloadResolver.ResolutionResult candRes = resolveMethodSilent(candidateOwner, memberName, candArgs);
                        if (candRes != null && candRes.isSuccess()) {
                            internalOwner = candidateOwner;
                            res = candRes;
                            args = candArgs;
                            if (receiver != null) {
                                receiver = new IRCastExpression(receiver, OceanTypeSystem.wrapObjectType(candidateOwner));
                            }
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            if (res == null && receiver instanceof IRVariableAccess va && "super".equals(va.getName()) && currentClassName != null) {
                String[] ifaces = CompilerRegistry.globalInterfaceRegistry.get(currentClassName);
                if (ifaces != null) {
                    for (String iface : ifaces) {
                        String cleanIface = TypeChecker.cleanDescriptor(iface);
                        if (cleanIface.startsWith("L") && cleanIface.endsWith(";")) {
                            cleanIface = cleanIface.substring(1, cleanIface.length() - 1);
                        }
                        String resolvedIface = resolveTypeName(cleanIface);
                        if (resolvedIface != null && !resolvedIface.isEmpty()) {
                            cleanIface = resolvedIface;
                        }
                        try {
                            List<IRExpression> ifaceArgs = buildCanonicalArguments(cleanIface, memberName, argList, rawOwnerTypeWithGenerics);
                            OverloadResolver.ResolutionResult ifaceRes = resolveMethodSilent(cleanIface, memberName, ifaceArgs);
                            if (ifaceRes != null && ifaceRes.isSuccess()) {
                                internalOwner = cleanIface;
                                res = ifaceRes;
                                args = ifaceArgs;
                                break;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            if (res == null) {
                res = resolveMethodWithResult(internalOwner, memberName, args);
            }
            String desc = res.descriptor();
            if (res.owner() != null && "java/lang/Object".equals(internalOwner)) {
                internalOwner = res.owner();
            }
            desc = packDefaultArgumentsIfNecessary(internalOwner, memberName, desc, args);
            packVarargsIfNecessary(internalOwner, memberName, desc, args);
            if (res.isSuccess() && res.descriptor() != null) {
                String resolvedOwner = res.owner() != null ? res.owner() : internalOwner;
                isStatic = CompilerRegistry.isMethodStatic(resolvedOwner, memberName, res.descriptor());
            }
            IRMethodCall call = new IRMethodCall(internalOwner, memberName, desc, args, isStatic);
            call.setReceiver(receiver);
            call.setExplicitClassTarget(isExplicitClassTarget);
            if (receiver instanceof IRVariableAccess && ((IRVariableAccess) receiver).getName().equals("super"))
                call.setSuperCall(true);
            // Generic return type propagation: if return type is Object but owner has generic parameters,
            // extract the actual element type from the generic descriptor for proper chained call resolution
            String old = call.getTypeDescriptor();

            List<String> explicitTypeArgs = new ArrayList<>();
            if (typeArgsCtx != null && typeArgsCtx.type() != null) {
                for (OceanParser.TypeContext tc : typeArgsCtx.type()) {
                    explicitTypeArgs.add(getTypeDescriptor(tc.getText()));
                }
            }

            List<String> argTypeDescs = args.stream().map(IRExpression::getTypeDescriptor).toList();
            String genericOwner = (rawOwnerTypeWithGenerics != null && rawOwnerTypeWithGenerics.contains("<")) ? rawOwnerTypeWithGenerics : internalOwner;
            String refinedReturnType = resolveGenericReturnType(genericOwner, memberName, argTypeDescs);
            if (!explicitTypeArgs.isEmpty()) {
                refinedReturnType = explicitTypeArgs.getFirst();
            }
            if (refinedReturnType != null && !refinedReturnType.equals(call.getTypeDescriptor())) {
                call.setTypeDescriptor(refinedReturnType);
            }

            if (receiver instanceof IRVariableAccess va && !va.isField()) {
                String vName = va.getName();
                String currentType = symbolTable.getType(vName);
                if (currentType != null) {
                    String clean = TypeChecker.cleanDescriptor(currentType);
                    boolean isList = TypeChecker.isListOrCollectionType(clean, CompilationSession.getActiveSession());
                    if (isList && (memberName.equals("add") || memberName.equals("push") || memberName.equals("enqueue")) && !args.isEmpty()) {
                        String addedType = args.getFirst().getTypeDescriptor();
                        if (addedType != null && !OceanTypeSystem.OBJECT_DESC.equals(addedType) && !"null".equals(addedType)) {
                            if (!currentType.contains("<")) {
                                String wrapped = (clean.startsWith("L") && clean.endsWith(";")) ? clean.substring(0, clean.length() - 1) : ("L" + clean);
                                String newType = wrapped + "<" + addedType + ">;";
                                symbolTable.setType(vName, newType);
                                symbolTable.setRawType(vName, newType);
                            }
                        }
                    }
                }
            }

            return call;
        } else {
            String type;
            if (ownerDesc != null && ownerDesc.startsWith("[") && memberName.equals("length")) {
                type = "I";
            } else if ("class".equals(memberName)) {
                type = "Ljava/lang/Class;";
            } else {
                String genericOwner = (rawOwnerTypeWithGenerics != null && rawOwnerTypeWithGenerics.contains("<")) ? rawOwnerTypeWithGenerics : internalOwner;
                type = resolveFieldType(genericOwner, memberName);
            }
            IRVariableAccess access = new IRVariableAccess(memberName, type, true, internalOwner, isStatic);
            access.setReceiver(receiver);
            access.setExplicitClassTarget(isExplicitClassTarget);
            return access;
        }
    }

    private OverloadResolver.ResolutionResult resolveMethodSilent(String owner, String name, List<IRExpression> args) {
        if (owner != null) {
            if (TypeChecker.isClassType(owner)) owner = owner.substring(1, owner.length() - 1);
            else if (owner.endsWith(";")) owner = owner.substring(0, owner.length() - 1);
        }
        List<String> argTypes = new ArrayList<>();
        for (IRExpression arg : args) {
            argTypes.add(arg.getTypeDescriptor());
        }
        OverloadResolver.ResolutionResult resolved = OverloadResolver.resolveWithResult(owner, name, argTypes);
        if (resolved != null && resolved.isSuccess()) return resolved;
        return null;
    }

    private OverloadResolver.ResolutionResult resolveMethodWithResult(String owner, String name, List<IRExpression> args) {
        if (owner != null) {
            if (TypeChecker.isClassType(owner)) owner = owner.substring(1, owner.length() - 1);
            else if (owner.endsWith(";")) owner = owner.substring(0, owner.length() - 1);
        }
        List<String> argTypes = new ArrayList<>();
        for (IRExpression arg : args) {
            argTypes.add(arg.getTypeDescriptor());
        }
        OverloadResolver.ResolutionResult resolved = OverloadResolver.resolveWithResult(owner, name, argTypes);
        if (resolved != null && resolved.isSuccess()) return resolved;
        if (resolved != null && resolved.status() == OverloadResolver.ResolutionStatus.AMBIGUOUS) {
            String candStr = String.join(", ", resolved.ambiguousCandidates());
            reportError(currentCtx, "Ambiguous method call: multiple methods match '" + name + "' on type '" + owner + "' with arguments " + argTypes + ": " + candStr, "IRGenerator");
            throw new CompilationException("Ambiguous method call: multiple methods match '" + name + "' on type '" + owner + "' with arguments " + argTypes + ": " + candStr);
        }
        String ifaceWithStatic = findInterfaceWithStaticMethod(owner, name);
        if (ifaceWithStatic != null) {
            String msg = "Static interface method '" + name + "' cannot be invoked through implementing class ('" + owner.replace('/', '.') + "'). It can only be invoked through interface name ('" + ifaceWithStatic.replace('/', '.') + "').";
            reportError(currentCtx, msg, "IRGenerator");
            throw new CompilationException(msg);
        }
        reportError(currentCtx, "Cannot resolve " + (name.equals("<init>") ? "constructor" : "method") + " '" + name + "' on type '" + owner + "' with argument types " + argTypes, "IRGenerator");
        throw new CompilationException("Cannot resolve " + (name.equals("<init>") ? "constructor" : "method") + " '" + name + "' on type '" + owner + "' with argument types " + argTypes);
    }

    private String findInterfaceWithStaticMethod(String owner, String name) {
        if (owner == null || name == null) return null;
        String clean = TypeChecker.cleanDescriptor(owner);
        if (clean.startsWith("L") && clean.endsWith(";")) clean = clean.substring(1, clean.length() - 1);
        if (clean.contains("<")) clean = clean.substring(0, clean.indexOf('<'));
        clean = clean.replace('.', '/');

        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(clean);
        List<String> ifaces = new ArrayList<>();

        while (!queue.isEmpty()) {
            String curr = queue.poll();
            if (!visited.add(curr)) continue;
            String[] directIfaces = CompilerRegistry.globalInterfaceRegistry.get(curr);
            if (directIfaces != null) {
                for (String di : directIfaces) {
                    queue.add(di);
                    ifaces.add(di);
                }
            }
            String sup = CompilerRegistry.globalSuperClassRegistry.get(curr);
            if (sup != null && !"java/lang/Object".equals(sup)) {
                queue.add(sup);
            }
            try {
                Class<?> cls = OceanTypeSystem.forName(curr.replace('/', '.'));
                if (cls != null) {
                    for (Class<?> ci : cls.getInterfaces()) {
                        String ciName = ci.getName().replace('.', '/');
                        queue.add(ciName);
                        ifaces.add(ciName);
                    }
                    if (cls.getSuperclass() != null && cls.getSuperclass() != Object.class) {
                        queue.add(cls.getSuperclass().getName().replace('.', '/'));
                    }
                }
            } catch (Throwable ignored) {}
        }

        for (String iface : ifaces) {
            String clIface = iface.replace('.', '/');
            Map<String, Boolean> statics = CompilerRegistry.globalMethodStaticity.get(clIface);
            if (statics != null && Boolean.TRUE.equals(statics.get(name))) {
                return clIface;
            }
            Map<String, Integer> access = CompilerRegistry.globalMethodAccess.get(clIface);
            if (access != null && access.containsKey(name) && (access.get(name) & Modifier.STATIC) != 0) {
                return clIface;
            }
            try {
                Class<?> cls = OceanTypeSystem.forName(clIface.replace('/', '.'));
                if (cls != null && cls.isInterface()) {
                    for (Method m : cls.getMethods()) {
                        if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers())) {
                            return clIface;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private String resolveMethodDescriptor(String owner, String name, List<IRExpression> args) {
        return resolveMethodWithResult(owner, name, args).descriptor();
    }

    /**
     * Resolves the actual return type of a method call on a generic container type.
     * For example: OceanList&lt;Ljava/util/HashMap;&gt;.get(int) → Ljava/util/HashMap;
     * instead of Ljava/lang/Object; (which is what type erasure gives us).
     *
     * @param rawOwnerType  The full generic descriptor of the owner (e.g. Lorg/.../OceanList&lt;Ljava/util/HashMap;&gt;;)
     * @param methodName    The method being called (e.g. "get")
     * @param argCount      The number of method arguments (used to distinguish list.get(int) from map.get(key))
     * @return The element type if resolvable, null otherwise
     */
    private String resolveGenericReturnType(String rawOwnerType, String methodName, int argCount) {
        return resolveGenericReturnType(rawOwnerType, methodName, Collections.nCopies(argCount, OceanTypeSystem.OBJECT_DESC));
    }

    private String resolveGenericReturnType(String rawOwnerType, String methodName, List<String> argTypes) {
        String result = resolveGenericReturnTypeInternal(rawOwnerType, methodName, argTypes);
        if (result == null) return null;
        String cleanOwner = TypeChecker.cleanDescriptor(rawOwnerType);
        if (TypeChecker.isClassType(cleanOwner)) {
            cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
        }
        if (CompilerRegistry.globalAsyncMethodSet.contains(cleanOwner + "#" + methodName) && !TypeChecker.isFutureType(result)) {
            return "Ljava/util/concurrent/CompletableFuture<" + result + ">;";
        }
        return result;
    }

    private String resolveGenericReturnTypeInternal(String rawOwnerType, String methodName, int argCount) {
        return resolveGenericReturnTypeInternal(rawOwnerType, methodName, Collections.nCopies(argCount, OceanTypeSystem.OBJECT_DESC));
    }

    private String resolveGenericReturnTypeInternal(String rawOwnerType, String methodName, List<String> argTypes) {
        return resolveGenericReturnTypeInternal(rawOwnerType, methodName, argTypes, new HashSet<>());
    }

    private String resolveGenericReturnTypeInternal(String rawOwnerType, String methodName, List<String> argTypes, Set<String> visited) {
        int argCount = argTypes != null ? argTypes.size() : 0;
        if (rawOwnerType == null) return null;
        if (!visited.add(rawOwnerType)) return null;
        if (!rawOwnerType.contains("<")) {
            String cleanOwner = rawOwnerType.trim();
            if (TypeChecker.isClassType(cleanOwner)) {
                cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
            }
            if (cleanOwner.endsWith(";")) {
                cleanOwner = cleanOwner.substring(0, cleanOwner.length() - 1);
            }
            String resolved = resolveTypeName(cleanOwner);
            if (resolved != null) {
                cleanOwner = resolved;
            }
            String methodGen = resolveMethodGenericReturn(cleanOwner, methodName, argTypes);
            if (methodGen != null) return ensureDescriptor(methodGen);

            // Check superclass generic signature
            String superSig = CompilerRegistry.globalSuperClassGenericSignatureRegistry.get(cleanOwner);
            if (superSig != null) {
                String ret = resolveGenericReturnTypeInternal(OceanTypeSystem.wrapObjectType(superSig), methodName, argTypes, visited);
                if (ret != null) return ret;
            }
            // Check interface generic signatures
            List<String> interSigs = CompilerRegistry.globalInterfaceGenericSignatureRegistry.get(cleanOwner);
            if (interSigs != null) {
                for (String interSig : interSigs) {
                    String ret = resolveGenericReturnTypeInternal(OceanTypeSystem.wrapObjectType(interSig), methodName, argTypes, visited);
                    if (ret != null) return ret;
                }
            }
            return null;
        }
        // Normalize: strip nullable '?', trailing ';', and leading 'L' for FQCNs
        String s = rawOwnerType.trim();
        if (s.endsWith("?")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        if (s.startsWith("L") && s.contains("/")) {
            s = s.substring(1).trim();
        }
        int ltIdx = s.indexOf('<');
        String baseClass = s.substring(0, ltIdx); // e.g. ocean/compiler/generated/OceanList
        String resolvedBase = resolveTypeName(baseClass);
        if (resolvedBase != null) {
            baseClass = resolvedBase;
        }
        String inner = s.substring(ltIdx + 1, s.length() - 1); // strip < and trailing >

        // Extract generic type arguments at depth 0
        List<String> typeArgs = splitGenericArgs(inner);

        String simpleName = baseClass.contains("/") ? OceanTypeSystem.findSimpleName(baseClass) : baseClass;

        boolean isOceanUserClass = CompilerRegistry.globalMethodRegistry.containsKey(baseClass)
                || CompilerRegistry.globalClassAccess.containsKey(baseClass)
                || CompilerRegistry.globalIsInterfaceSet.contains(baseClass)
                || CompilerRegistry.globalTypeParameterRegistry.containsKey(baseClass);
        if (isOceanUserClass) {
            String oceanGenericReturn = resolveOceanClassGenericReturn(baseClass, simpleName, typeArgs, methodName, argTypes, visited);
            if (oceanGenericReturn != null) {
                return oceanGenericReturn;
            }
        }

        // Dynamic Java Reflection generic return type resolution
        try {
            Class<?> cls = forName(baseClass.replace('/', '.'));
            if (cls != null) {
                String javaGenericReturn = resolveJavaGenericReturn(cls, methodName, typeArgs, argTypes);
                if (javaGenericReturn != null) {
                    return javaGenericReturn;
                }
            }
        } catch (Throwable ignored) {}

        // Dynamic container classification via hierarchy checking (JDK / stdlib collections)
        boolean isListOrColl = TypeChecker.isListOrCollectionType(baseClass, CompilationSession.getActiveSession());
        boolean isMap = TypeChecker.isMapType(baseClass, CompilationSession.getActiveSession());

        if (isListOrColl && !typeArgs.isEmpty()) {
            if (methodName.equals("get") && argCount == 1 && (argTypes.isEmpty() || isIntType(argTypes.getFirst()))) {
                return ensureDescriptor(typeArgs.getFirst());
            }
            if ((methodName.equals("getFirst") || methodName.equals("getLast") || methodName.equals("peek")
                    || methodName.equals("poll") || methodName.equals("pop") || methodName.equals("element")) && argCount == 0) {
                return ensureDescriptor(typeArgs.getFirst());
            }
            if (methodName.equals("remove") && argCount == 1) {
                if (!argTypes.isEmpty() && isIntType(argTypes.getFirst())) {
                    return ensureDescriptor(typeArgs.getFirst());
                } else {
                    return "Z";
                }
            }
        }

        if (isMap && typeArgs.size() >= 2) {
            if ((methodName.equals("get") && argCount == 1)
                    || (methodName.equals("getOrDefault") && argCount == 2)
                    || (methodName.equals("remove") && argCount == 1)) {
                return ensureDescriptor(typeArgs.get(1));
            }
            if (methodName.equals("remove") && argCount == 2) {
                return "Z";
            }
        }

        // Functional interfaces & standard JDK generic types
        if (baseClass.equals("java/util/function/Function") && methodName.equals("apply") && typeArgs.size() >= 2 && argCount == 1) {
            return ensureDescriptor(typeArgs.get(1));
        }
        else if ((baseClass.equals("java/util/function/IntFunction") || baseClass.equals("java/util/function/LongFunction") || baseClass.equals("java/util/function/DoubleFunction")) && methodName.equals("apply") && !typeArgs.isEmpty() && argCount == 1) {
            return ensureDescriptor(typeArgs.getFirst());
        }
        else if (baseClass.equals("java/util/function/BiFunction") && methodName.equals("apply") && typeArgs.size() >= 3 && argCount == 2) {
            return ensureDescriptor(typeArgs.get(2));
        }
        else if (baseClass.equals("java/util/function/Supplier") && methodName.equals("get") && !typeArgs.isEmpty() && argCount == 0) {
            return ensureDescriptor(typeArgs.getFirst());
        }
        else if (baseClass.equals("java/util/function/UnaryOperator") && methodName.equals("apply") && !typeArgs.isEmpty() && argCount == 1) {
            return ensureDescriptor(typeArgs.getFirst());
        }
        else if (baseClass.equals("java/util/function/BinaryOperator") && methodName.equals("apply") && !typeArgs.isEmpty() && argCount == 2) {
            return ensureDescriptor(typeArgs.getFirst());
        }
        else if (baseClass.equals("java/util/concurrent/Callable") && methodName.equals("call") && !typeArgs.isEmpty() && argCount == 0) {
            return ensureDescriptor(typeArgs.getFirst());
        }
        else if (baseClass.equals("java/util/concurrent/CompletableFuture") && (methodName.equals("get") || methodName.equals("join")) && !typeArgs.isEmpty()) {
            return ensureDescriptor(typeArgs.getFirst());
        }

        // Fallback: try Ocean user-class resolution if not already checked
        if (!isOceanUserClass) {
            return resolveOceanClassGenericReturn(baseClass, simpleName, typeArgs, methodName, argTypes, visited);
        }
        return null;
    }

    private boolean isIntType(String desc) {
        if (desc == null) return false;
        String clean = TypeChecker.cleanDescriptor(desc);
        return "I".equals(clean) || "int".equals(clean) || "Ljava/lang/Integer;".equals(clean) || "java/lang/Integer".equals(clean);
    }

    private String resolveJavaGenericReturn(Class<?> cls, String methodName, List<String> classTypeArgs, List<String> argTypes) {
        TypeVariable<?>[] classParams = cls.getTypeParameters();
        Map<String, String> typeBindings = new HashMap<>();
        for (int i = 0; i < classParams.length && i < classTypeArgs.size(); i++) {
            typeBindings.put(classParams[i].getName(), classTypeArgs.get(i));
        }

        int targetArgCount = argTypes != null ? argTypes.size() : 0;
        for (Method m : cls.getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            if (m.getParameterCount() != targetArgCount && !m.isVarArgs()) continue;

            if (argTypes != null && argTypes.size() == m.getParameterCount()) {
                Class<?>[] mParams = m.getParameterTypes();
                boolean match = true;
                for (int i = 0; i < mParams.length; i++) {
                    String expectedDesc = Type.getDescriptor(mParams[i]);
                    String actualDesc = argTypes.get(i);
                    if (!TypeChecker.isAssignable(expectedDesc, actualDesc, CompilationSession.getActiveSession())) {
                        match = false;
                        break;
                    }
                }
                if (!match) continue;
            }

            Map<String, String> fullBindings = new HashMap<>(typeBindings);
            java.lang.reflect.Type[] paramTypes = m.getGenericParameterTypes();
            if (argTypes != null) {
                for (int i = 0; i < paramTypes.length && i < argTypes.size(); i++) {
                    bindTypeVariables(paramTypes[i], argTypes.get(i), fullBindings);
                }
            }

            java.lang.reflect.Type retType = m.getGenericReturnType();
            String resolved = resolveReflectedTypeDesc(retType, fullBindings);
            if (resolved != null && !OceanTypeSystem.OBJECT_DESC.equals(resolved)) {
                return resolved;
            }
        }
        return null;
    }

    private void bindTypeVariables(java.lang.reflect.Type genericType, String concreteTypeDesc, Map<String, String> bindings) {
        if (genericType == null || concreteTypeDesc == null) return;
        switch (genericType) {
            case TypeVariable<?> tv -> bindings.putIfAbsent(tv.getName(), ensureDescriptor(concreteTypeDesc));
            case WildcardType wt -> {
                if (wt.getLowerBounds().length > 0) {
                    bindTypeVariables(wt.getLowerBounds()[0], concreteTypeDesc, bindings);
                } else if (wt.getUpperBounds().length > 0) {
                    bindTypeVariables(wt.getUpperBounds()[0], concreteTypeDesc, bindings);
                }
            }
            case ParameterizedType pt when concreteTypeDesc.contains("<") -> {
                int lt = concreteTypeDesc.indexOf('<');
                int gt = concreteTypeDesc.lastIndexOf('>');
                if (lt >= 0 && gt > lt) {
                    String inner = concreteTypeDesc.substring(lt + 1, gt);
                    List<String> actualArgDescs = splitGenericArgs(inner);
                    java.lang.reflect.Type[] genArgs = pt.getActualTypeArguments();
                    for (int i = 0; i < genArgs.length && i < actualArgDescs.size(); i++) {
                        bindTypeVariables(genArgs[i], actualArgDescs.get(i), bindings);
                    }
                }
            }
            default -> {
            }
        }
    }

    private String resolveReflectedTypeDesc(java.lang.reflect.Type type, Map<String, String> bindings) {
        switch (type) {
            case null -> {
                return OceanTypeSystem.OBJECT_DESC;
            }
            case Class<?> c -> {
                return Type.getDescriptor(c);
            }
            case TypeVariable<?> tv -> {
                if (bindings != null && bindings.containsKey(tv.getName())) {
                    return ensureDescriptor(bindings.get(tv.getName()));
                }
                if (tv.getBounds().length > 0 && tv.getBounds()[0] instanceof Class<?> bc) {
                    return Type.getDescriptor(bc);
                }
                return OceanTypeSystem.OBJECT_DESC;
            }
            case WildcardType wt -> {
                if (wt.getLowerBounds().length > 0) {
                    return resolveReflectedTypeDesc(wt.getLowerBounds()[0], bindings);
                }
                if (wt.getUpperBounds().length > 0) {
                    if (wt.getUpperBounds()[0] == Object.class) {
                        return OceanTypeSystem.OBJECT_DESC;
                    }
                    return resolveReflectedTypeDesc(wt.getUpperBounds()[0], bindings);
                }
                return OceanTypeSystem.OBJECT_DESC;
            }
            case ParameterizedType pt -> {
                java.lang.reflect.Type raw = pt.getRawType();
                if (raw instanceof Class<?> rc) {
                    StringBuilder sb = new StringBuilder("L");
                    sb.append(rc.getName().replace('.', '/')).append("<");
                    java.lang.reflect.Type[] actualArgs = pt.getActualTypeArguments();
                    for (int i = 0; i < actualArgs.length; i++) {
                        if (i > 0) sb.append(",");
                        sb.append(resolveReflectedTypeDesc(actualArgs[i], bindings));
                    }
                    sb.append(">;");
                    return sb.toString();
                }
            }
            default -> {
            }
        }
        if (type instanceof GenericArrayType gat) {
            return "[" + resolveReflectedTypeDesc(gat.getGenericComponentType(), bindings);
        }
        return OceanTypeSystem.OBJECT_DESC;
    }

    /**
     * Resolves return type for Ocean user-defined generic classes (e.g. Alpha&lt;T&gt;.get() → bound T).
     * Looks up the class's declared type parameters from globalTypeParameterRegistry and matches
     * the bound type argument from the call site.
     */
    private String resolveOceanClassGenericReturn(String baseClass, String simpleName, List<String> typeArgs, String methodName, List<String> argTypes, Set<String> visited) {
        int argCount = argTypes != null ? argTypes.size() : 0;
        // Look up declared type parameters for this Ocean class
        List<CompilerRegistry.TypeParameterInfo> declaredTypeParams = CompilerRegistry.globalTypeParameterRegistry.get(baseClass);
        if (declaredTypeParams == null || declaredTypeParams.isEmpty()) return null;
        if (typeArgs.isEmpty()) return null;

        Map<String, String> paramMap = new HashMap<>();
        for (int i = 0; i < declaredTypeParams.size(); i++) {
            if (i < typeArgs.size()) {
                paramMap.put(declaredTypeParams.get(i).name, typeArgs.get(i));
            }
        }

        // 1. Try to get the generic/un-erased return type from our new registry
        Map<String, String> genericMap = CompilerRegistry.globalMethodGenericReturnTypeRegistry.get(baseClass);
        String regKey = (genericMap != null && genericMap.containsKey(methodName + "#" + argCount)) ? (methodName + "#" + argCount) : methodName;
        if (genericMap != null && genericMap.containsKey(regKey)) {
            StringBuilder genericReturn = new StringBuilder(genericMap.get(regKey));

            // Check if this generic return is shadowed/declared as a method-level type parameter
            Map<String, List<String>> methodTpMap = CompilerRegistry.globalMethodTypeParametersRegistry.get(baseClass);
            if (methodTpMap != null && methodTpMap.containsKey(methodName)) {
                List<String> methodTps = methodTpMap.get(methodName);
                if (methodTps != null && methodTps.contains(genericReturn.toString())) {
                    Map<String, List<CompilerRegistry.MethodParamInfo>> paramMapRegistry = CompilerRegistry.globalMethodParamsRegistry.get(baseClass);
                    if (paramMapRegistry != null && paramMapRegistry.containsKey(methodName)) {
                        List<CompilerRegistry.MethodParamInfo> pInfos = paramMapRegistry.get(methodName);
                        for (int j = 0; j < pInfos.size(); j++) {
                            if (argTypes != null && j < argTypes.size() && pInfos.get(j).rawType().contentEquals(genericReturn)) {
                                return ensureDescriptor(argTypes.get(j));
                            }
                        }
                    }
                    return null; // Fall back to method descriptor return type (erased bound)
                }
            }

            String genStr = genericReturn.toString();
            boolean nullable = genStr.endsWith("?");
            if (nullable) genStr = genStr.substring(0, genStr.length() - 1);
            if (genStr.contains("<")) {
                String subst = substituteGenericSignature(genStr, paramMap);
                return ensureDescriptor(nullable ? subst + "?" : subst);
            }
            if (paramMap.containsKey(genStr)) {
                String subst = paramMap.get(genStr);
                return ensureDescriptor(nullable ? subst + "?" : subst);
            }
        }

        // 2. Fallback to declared method descriptors (including overloads)
        Map<String, String> methodMap = CompilerRegistry.globalMethodRegistry.get(baseClass);
        Map<String, List<String>> overloadMap = CompilerRegistry.globalOverloadRegistry.get(baseClass);
        List<String> declaredDescs = overloadMap != null ? overloadMap.get(methodName) : null;
        if (declaredDescs == null && methodMap != null && methodMap.containsKey(methodName)) {
            declaredDescs = Collections.singletonList(methodMap.get(methodName));
        }

        if (declaredDescs != null) {
            for (String declaredReturnType : declaredDescs) {
                List<String> pDescs = getParameterDescriptors(declaredReturnType);
                if (pDescs.size() == argCount) {
                    boolean match = true;
                    if (argTypes != null) {
                        for (int i = 0; i < pDescs.size(); i++) {
                            if (!TypeChecker.isAssignable(pDescs.get(i), argTypes.get(i), CompilationSession.getActiveSession())) {
                                match = false;
                                break;
                            }
                        }
                    }
                    if (match) {
                        String returnType = declaredReturnType;
                        if (returnType.contains(")")) {
                            returnType = returnType.substring(returnType.lastIndexOf(')') + 1);
                        }
                        String cleanReturn = returnType;
                        if (TypeChecker.isClassType(cleanReturn)) cleanReturn = cleanReturn.substring(1, cleanReturn.length() - 1);

                        if (cleanReturn.contains("<")) {
                            String subst = substituteGenericSignature(cleanReturn, paramMap);
                            return ensureDescriptor(subst);
                        }
                        if (paramMap.containsKey(cleanReturn)) {
                            return ensureDescriptor(paramMap.get(cleanReturn));
                        }
                        // Method is declared with a concrete (non-generic) return type on this class.
                        // Do not propagate to super-interfaces; return null so the call's existing return type is kept.
                        return null;
                    }
                }
            }
        }

        // 3. Superclass hierarchy propagation: check if superclass has the method with mapped type arguments
        String superSig = CompilerRegistry.globalSuperClassGenericSignatureRegistry.get(baseClass);
        if (superSig != null && !superSig.equals("java/lang/Object")) {
            String substSuper = substituteGenericSignature(superSig, paramMap);
            String ret = resolveGenericReturnTypeInternal(OceanTypeSystem.wrapObjectType(substSuper), methodName, argTypes, visited);
            if (ret != null) return ret;
        }

        // 4. Interface hierarchy propagation: check if implemented interfaces have the method with mapped type arguments
        List<String> interSigs = CompilerRegistry.globalInterfaceGenericSignatureRegistry.get(baseClass);
        if (interSigs != null) {
            for (String interSig : interSigs) {
                String substInter = substituteGenericSignature(interSig, paramMap);
                String ret = resolveGenericReturnTypeInternal(OceanTypeSystem.wrapObjectType(substInter), methodName, argTypes, visited);
                if (ret != null) return ret;
            }
        }

        return null;
    }

    private void extractTypeBindings(String declaredType, String actualType, Map<String, String> typeBindings) {
        if (declaredType == null || actualType == null) return;
        declaredType = declaredType.trim();
        actualType = actualType.trim();
        if (actualType.startsWith("L") && actualType.endsWith(";")) {
            actualType = actualType.substring(1, actualType.length() - 1);
        }
        if (declaredType.startsWith("L") && declaredType.endsWith(";")) {
            declaredType = declaredType.substring(1, declaredType.length() - 1);
        }
        if (!declaredType.contains("<")) {
            if (!declaredType.isEmpty() && Character.isUpperCase(declaredType.charAt(0)) && !declaredType.contains("/")) {
                typeBindings.put(declaredType, ensureDescriptor(actualType));
            }
            return;
        }
        if (!actualType.contains("<")) return;
        int dLt = declaredType.indexOf('<');
        int dGt = declaredType.lastIndexOf('>');
        int aLt = actualType.indexOf('<');
        int aGt = actualType.lastIndexOf('>');
        if (dLt < 0 || dGt <= dLt || aLt < 0 || aGt <= aLt) return;
        String dInner = declaredType.substring(dLt + 1, dGt);
        String aInner = actualType.substring(aLt + 1, aGt);
        List<String> dArgs = splitGenericArgs(dInner);
        List<String> aArgs = splitGenericArgs(aInner);
        for (int i = 0; i < dArgs.size() && i < aArgs.size(); i++) {
            extractTypeBindings(dArgs.get(i), aArgs.get(i), typeBindings);
        }
    }

    private String resolveMethodGenericReturn(String cleanOwner, String methodName, List<String> argTypes) {
        if (cleanOwner == null || methodName == null || argTypes == null || argTypes.isEmpty()) return null;
        Map<String, List<String>> methodTpMap = CompilerRegistry.globalMethodTypeParametersRegistry.get(cleanOwner);
        if (methodTpMap == null || !methodTpMap.containsKey(methodName)) return null;
        List<String> methodTps = methodTpMap.get(methodName);
        if (methodTps == null || methodTps.isEmpty()) return null;

        Map<String, String> genRetMap = CompilerRegistry.globalMethodGenericReturnTypeRegistry.get(cleanOwner);
        if (genRetMap == null) return null;
        String regKey = genRetMap.containsKey(methodName + "#" + argTypes.size()) ? (methodName + "#" + argTypes.size()) : methodName;
        if (!genRetMap.containsKey(regKey)) return null;
        String genericReturn = genRetMap.get(regKey);

        Map<String, List<CompilerRegistry.MethodParamInfo>> paramMap = CompilerRegistry.globalMethodParamsRegistry.get(cleanOwner);
        if (paramMap == null || !paramMap.containsKey(methodName)) return null;
        List<CompilerRegistry.MethodParamInfo> params = paramMap.get(methodName);

        Map<String, String> typeBindings = new HashMap<>();
        for (int i = 0; i < params.size(); i++) {
            if (i < argTypes.size()) {
                String pRaw = params.get(i).rawType();
                String argType = argTypes.get(i);
                extractTypeBindings(pRaw, argType, typeBindings);
            }
        }
        if (typeBindings.containsKey(genericReturn)) {
            return typeBindings.get(genericReturn);
        }
        if (genericReturn.contains("<")) {
            return substituteGenericSignature(genericReturn, typeBindings);
        }
        return null;
    }

    private String substituteGenericSignature(String sig, Map<String, String> paramMap) {
        return substituteGenericSignature(sig, paramMap, 0);
    }

    private String substituteGenericSignature(String sig, Map<String, String> paramMap, int depth) {
        if (sig == null || sig.isEmpty() || paramMap == null || paramMap.isEmpty()) return sig;
        if (depth > 8) return sig;
        String s = sig.trim();
        boolean isDesc = s.startsWith("L") && s.endsWith(";");
        boolean isTypeVar = s.startsWith("T") && s.endsWith(";");
        if (isDesc || isTypeVar) s = s.substring(1, s.length() - 1);

        int ltIdx = s.indexOf('<');
        int gtIdx = s.lastIndexOf('>');
        if (ltIdx < 0 || gtIdx <= ltIdx) {
            if (paramMap.containsKey(s)) {
                String sub = paramMap.get(s);
                return (isDesc || isTypeVar) ? ensureDescriptor(sub) : TypeChecker.cleanDescriptor(sub);
            }
            return sig;
        }

        String base = s.substring(0, ltIdx).trim();
        String qualifiedBase = base;
        if (!base.contains("/")) {
            String std = OceanTypeSystem.resolveStandardClassPath(base);
            if (std != null) {
                qualifiedBase = std;
            } else {
                String desc = getTypeDescriptor(base);
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


    /** Splits generic type arguments at depth 0 (respecting nested angle brackets and sequential JVM signatures). */
    private List<String> splitGenericArgs(String inner) {
        return TypeChecker.splitGenericArgs(inner);
    }


    private boolean isTypeParameter(String token) {
        if (token == null || token.isEmpty()) return false;
        if (token.endsWith("?")) token = token.substring(0, token.length() - 1);
        if (symbolTable != null && symbolTable.getTypeParams() != null && symbolTable.getTypeParams().contains(token)) return true;
        if (currentClassName != null) {
            String fullPath = currentClassName.replace(".", "/");
            List<CompilerRegistry.TypeParameterInfo> infos = CompilerRegistry.globalTypeParameterRegistry.get(fullPath);
            if (infos != null) {
                for (CompilerRegistry.TypeParameterInfo info : infos) {
                    if (info.name.equals(token)) return true;
                }
            }
        }
        return false;
    }

    /** Ensures a type token is a valid JVM descriptor (resolving class names, preserving generics, and boxing primitives). */
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

        if (isTypeParameter(typeToken)) {
            return variancePrefix + (nullable ? typeToken + "?" : typeToken);
        }
        if (typeToken.startsWith("T") && typeToken.endsWith(";")) {
            String tvar = typeToken.substring(1, typeToken.length() - 1);
            return variancePrefix + (nullable ? tvar + "?" : tvar);
        }

        if (TypeChecker.isClassType(typeToken)) return variancePrefix + (nullable ? typeToken + "?" : typeToken);
        if (typeToken.startsWith("[")) return variancePrefix + (nullable ? typeToken + "?" : typeToken);
        if (typeToken.contains("/")) return variancePrefix + (nullable ? OceanTypeSystem.wrapObjectType(typeToken) + "?" : OceanTypeSystem.wrapObjectType(typeToken));

        if (typeToken.contains("<")) {
            if (typeToken.startsWith("L") && typeToken.endsWith(";")) {
                int lt = typeToken.indexOf('<');
                String rawBase = typeToken.substring(1, lt).trim();
                if (rawBase.contains("/")) {
                    return variancePrefix + (nullable ? typeToken + "?" : typeToken);
                }
            }
            int ltIdx = typeToken.indexOf('<');
            int gtIdx = typeToken.lastIndexOf('>');
            if (ltIdx > 0 && gtIdx > ltIdx) {
                String base = typeToken.substring(0, ltIdx).trim();
                if (base.startsWith("L") && base.contains("/")) base = base.substring(1).trim();
                String inner = typeToken.substring(ltIdx + 1, gtIdx).trim();
                List<String> args = splitGenericArgs(inner);
                String baseDesc = getTypeDescriptor(base);
                if (baseDesc.endsWith("?")) baseDesc = baseDesc.substring(0, baseDesc.length() - 1);
                if (baseDesc.endsWith(";")) {
                    baseDesc = baseDesc.substring(0, baseDesc.length() - 1);
                }
                StringBuilder sb = new StringBuilder(baseDesc);
                if (!args.isEmpty()) {
                    sb.append("<");
                    for (int i = 0; i < args.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(ensureDescriptor(args.get(i)));
                    }
                    sb.append(">");
                }
                sb.append(";");
                if (nullable) sb.append("?");
                return variancePrefix + sb;
            } else if (ltIdx > 0) {
                String base = typeToken.substring(0, ltIdx).trim();
                String res = getTypeDescriptor(base);
                return variancePrefix + (nullable ? res + "?" : res);
            }
        }
        String desc = getTypeDescriptor(typeToken);
        if (desc.length() == 1 && "ZBCSIJFD".indexOf(desc.charAt(0)) >= 0) {
            desc = OceanTypeSystem.getBoxedDescriptor(desc);
        }
        return variancePrefix + (nullable ? desc + "?" : desc);
    }

    private boolean isAssignableRefs(String sub, String sup) {
        if (sub == null || sup == null) return false;
        if (sub.equals(sup) || "java/lang/Object".equals(sup)) return true;
        String targetDesc = sup.startsWith("L") ? sup : OceanTypeSystem.wrapObjectType(sup);
        String sourceDesc = sub.startsWith("L") ? sub : OceanTypeSystem.wrapObjectType(sub);
        return TypeChecker.isAssignable(targetDesc, sourceDesc, CompilationSession.getActiveSession());
    }

    private String resolveFieldType(String owner, String name) {
        String sig = resolveFieldGenericSignature(owner, name);
        if (sig == null) return null;
        String baseOwner = owner.trim();
        if (baseOwner.startsWith("L") && baseOwner.endsWith(";")) {
            baseOwner = baseOwner.substring(1, baseOwner.length() - 1);
        }
        if (baseOwner.contains("<")) {
            baseOwner = baseOwner.substring(0, baseOwner.indexOf('<')).trim();
        }
        String cleanSig = TypeChecker.cleanDescriptor(sig);
        if (TypeChecker.isClassType(cleanSig)) {
            cleanSig = cleanSig.substring(1, cleanSig.length() - 1);
        } else if (cleanSig.startsWith("T") && cleanSig.endsWith(";")) {
            cleanSig = cleanSig.substring(1, cleanSig.length() - 1);
        }
        List<CompilerRegistry.TypeParameterInfo> typeParams = CompilerRegistry.globalTypeParameterRegistry.get(baseOwner);
        if (typeParams != null) {
            for (CompilerRegistry.TypeParameterInfo tp : typeParams) {
                if (tp.name.equals(cleanSig) || tp.name.equals(sig)) {
                    return tp.getErasedType();
                }
            }
        }
        if (currentClassName != null && !currentClassName.equals(baseOwner)) {
            List<CompilerRegistry.TypeParameterInfo> currParams = CompilerRegistry.globalTypeParameterRegistry.get(currentClassName);
            if (currParams != null) {
                for (CompilerRegistry.TypeParameterInfo tp : currParams) {
                    if (tp.name.equals(cleanSig) || tp.name.equals(sig)) {
                        return tp.getErasedType();
                    }
                }
            }
        }
        if (sig.contains("<") && !sig.startsWith("L") && !sig.startsWith("[")) {
            sig = OceanTypeSystem.wrapObjectType(sig);
        }
        return sig;
    }

    private String resolveFieldGenericSignature(String owner, String name) {
        if (owner == null || name == null) return null;
        String rawOwner = owner.trim();
        if (rawOwner.startsWith("L") && rawOwner.endsWith(";")) {
            rawOwner = rawOwner.substring(1, rawOwner.length() - 1);
        }
        String baseOwner = rawOwner;
        List<String> instanceTypeArgs = null;
        if (rawOwner.contains("<") && rawOwner.endsWith(">")) {
            int lt = rawOwner.indexOf('<');
            baseOwner = rawOwner.substring(0, lt).trim();
            String inner = rawOwner.substring(lt + 1, rawOwner.length() - 1).trim();
            instanceTypeArgs = splitGenericArgs(inner);
        }

        // 1. Check current class in globalFieldGenericSignatureRegistry or globalFieldRegistry
        String fieldType = null;
        Map<String, String> genFields = CompilerRegistry.globalFieldGenericSignatureRegistry.get(baseOwner);
        if (genFields != null && genFields.containsKey(name)) {
            fieldType = genFields.get(name);
        } else {
            Map<String, String> fields = CompilerRegistry.globalFieldRegistry.get(baseOwner);
            if (fields != null && fields.containsKey(name)) {
                fieldType = fields.get(name);
            }
        }

        if (fieldType != null) {
            if (instanceTypeArgs != null && !instanceTypeArgs.isEmpty()) {
                List<CompilerRegistry.TypeParameterInfo> typeParams = CompilerRegistry.globalTypeParameterRegistry.get(baseOwner);
                if (typeParams != null && !typeParams.isEmpty()) {
                    Map<String, String> paramMap = new HashMap<>();
                    for (int i = 0; i < typeParams.size(); i++) {
                        if (i < instanceTypeArgs.size()) {
                            paramMap.put(typeParams.get(i).name, instanceTypeArgs.get(i));
                        }
                    }
                    fieldType = substituteGenericSignature(fieldType, paramMap);
                }
            }
            return fieldType;
        }

        // 2. Check superclass hierarchy
        String sup = CompilerRegistry.globalSuperClassRegistry.get(baseOwner);
        if (sup != null && !sup.equals(baseOwner)) {
            String inheritedType = resolveFieldGenericSignature(sup, name);
            if (inheritedType != null) {
                String superSig = CompilerRegistry.globalSuperClassGenericSignatureRegistry.get(baseOwner);
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
                if (instanceTypeArgs != null && !instanceTypeArgs.isEmpty()) {
                    List<CompilerRegistry.TypeParameterInfo> typeParams = CompilerRegistry.globalTypeParameterRegistry.get(baseOwner);
                    if (typeParams != null && !typeParams.isEmpty()) {
                        Map<String, String> paramMap = new HashMap<>();
                        for (int i = 0; i < typeParams.size(); i++) {
                            if (i < instanceTypeArgs.size()) {
                                paramMap.put(typeParams.get(i).name, instanceTypeArgs.get(i));
                            }
                        }
                        inheritedType = substituteGenericSignature(inheritedType, paramMap);
                    }
                }
                return inheritedType;
            }
        }

        String[] ifaces = CompilerRegistry.globalInterfaceRegistry.get(baseOwner);
        if (ifaces != null) {
            for (String iface : ifaces) {
                if (iface != null) {
                    String ifaceType = resolveFieldGenericSignature(iface, name);
                    if (ifaceType != null) return ifaceType;
                }
            }
        }

        // 3. Fallback to Java reflection for standard classes
        try {
            Class<?> clazz = forName(baseOwner.replace("/", "."));
            Field f = clazz.getField(name);
            return Type.getDescriptor(f.getType());
        } catch (Throwable ignored) {}

        return null;
    }

    private boolean resolveFieldStaticity(String owner, String name) {
        Map<String, Boolean> staticityMap = CompilerRegistry.globalFieldStaticity.get(owner);
        if (staticityMap != null && staticityMap.containsKey(name)) return staticityMap.get(name);
        String sup = CompilerRegistry.globalSuperClassRegistry.get(owner);
        if (sup != null && !sup.equals(owner) && resolveFieldStaticity(sup, name)) return true;
        String[] ifaces = CompilerRegistry.globalInterfaceRegistry.get(owner);
        if (ifaces != null) {
            for (String iface : ifaces) {
                if (iface != null && resolveFieldStaticity(iface, name)) return true;
            }
        }
        try {
            Class<?> clazz = forName(owner.replace("/", "."));
            Field f = clazz.getField(name);
            return Modifier.isStatic(f.getModifiers());
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isEnumType(String typeName) {
        if (typeName == null) return false;
        String slash = typeName.replace('.', '/');
        if (CompilerRegistry.globalEnumConstants.containsKey(slash) || CompilerRegistry.globalEnumConstants.containsKey(typeName)) return true;
        ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(slash);
        if (sym != null && sym.isEnum()) return true;
        sym = CompilerRegistry.getClassSymbol(typeName);
        if (sym != null && sym.isEnum()) return true;
        if ("java/lang/Enum".equals(CompilerRegistry.globalSuperClassRegistry.get(slash)) ||
            "java/lang/Enum".equals(CompilerRegistry.globalSuperClassRegistry.get(typeName))) return true;
        try {
            Class<?> clazz = forName(slash.replace('/', '.'));
            if (clazz != null && clazz.isEnum()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean containsWildcardTypeArgument(String typeStr) {
        if (typeStr == null || !typeStr.contains("<")) return false;
        List<String> typeArgs = TypeChecker.extractGenericArguments(typeStr);
        for (String arg : typeArgs) {
            String clean = arg.trim();
            if (clean.startsWith("+") || clean.startsWith("-")) clean = clean.substring(1).trim();
            if (clean.startsWith("?") || clean.startsWith("*")) {
                return true;
            }
            if (containsWildcardTypeArgument(clean)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public IRNode visitNewObjectExpr(OceanParser.NewObjectExprContext ctx) {
        String typeStr = ctx.type().getText();
        if (containsWildcardTypeArgument(typeStr)) {
            String msg = "Wildcard type argument ('?') cannot be used in 'new' expression: '" + typeStr + "'. Use a concrete type or diamond operator ('<>').";
            reportError(ctx, msg);
            throw new CompilationException(msg);
        }
        if (ctx.LBRACK() != null && !ctx.LBRACK().isEmpty()) {
            if (ctx.type() != null && ctx.type().LBRACK() != null && !ctx.type().LBRACK().isEmpty()) {
                String msg = "Cannot specify an array dimension expression after an empty dimension ('[]'): '" + ctx.getText() + "'";
                reportError(ctx, msg);
                throw new CompilationException(msg);
            }
            int sizedDims = ctx.expression().size();
            int totalBrackets = ctx.LBRACK().size();
            int emptyDims = Math.max(0, totalBrackets - sizedDims);

            String baseType = getTypeDescriptor(typeStr);
            if (emptyDims > 0) {
                baseType = "[".repeat(emptyDims) + baseType;
            }
            List<IRExpression> sizes = new ArrayList<>();
            for (OceanParser.ExpressionContext eCtx : ctx.expression()) {
                sizes.add(ensureExpr(visit(eCtx)));
            }
            IRArrayCreation arrCreation = new IRArrayCreation(baseType, sizes);
            arrCreation.setRawType(typeStr);
            return arrCreation;
        } else {
            String resolvedTarget = resolveTypeName(typeStr);
            if (isEnumType(resolvedTarget) || isEnumType(typeStr)) {
                String targetDisplay = resolvedTarget != null ? resolvedTarget : typeStr;
                String msg = "Cannot instantiate enum type '" + targetDisplay + "' with 'new'.";
                reportError(ctx, msg);
                throw new CompilationException(msg);
            }
            List<IRExpression> args = new ArrayList<>();
            if (ctx.argumentList() != null) {
                List<OceanParser.ExpressionContext> argExprs = getArgumentExpressions(ctx.argumentList());
                int totalArgs = argExprs.size();
                for (int i = 0; i < totalArgs; i++) {
                    OceanParser.ExpressionContext eCtx = argExprs.get(i);
                    String expectedType = getExpectedCtorParamType(resolvedTarget, i, totalArgs);
                    String oldCast = currentCastType;
                    if (expectedType != null) {
                        currentCastType = expectedType;
                    }
                    args.add(ensureExpr(visit(eCtx)));
                    currentCastType = oldCast;
                }
            }

            if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(resolvedTarget)) {
                String outerFqcn = CompilerRegistry.globalInnerClassUsedOuterMap.get(resolvedTarget);
                IRExpression outerReceiver = resolveOuterInstanceReceiver(outerFqcn);
                if (outerReceiver == null) {
                    reportError(ctx, "Cannot instantiate non-static inner class '" + resolvedTarget + "' from a static context without an enclosing instance.", "IRGenerator");
                    throw new CompilationException("Cannot instantiate non-static inner class from static context");
                }
                args.addFirst(outerReceiver);
            }

            boolean isInterface = false;
            if (ctx.LBRACE() != null) {
                if (CompilerRegistry.globalIsInterfaceSet.contains(resolvedTarget)) {
                    isInterface = true;
                } else {
                    try {
                        Class<?> clazz = forName(resolvedTarget.replace('/', '.'));
                        isInterface = clazz.isInterface();
                    } catch (Throwable ignored) {
                    }
                }
            }

            String ctorDesc;
            if (ctx.LBRACE() != null && isInterface) {
                ctorDesc = "()V";
            } else {
                ctorDesc = resolveConstructorDescriptor(resolvedTarget, args);
            }
            packVarargsIfNecessary(resolvedTarget, "<init>", ctorDesc, args);

            if (ctx.LBRACE() != null) {

                CompilationSession session = CompilationSession.getActiveSession();
                int index = (session != null) ? session.getNextAnonClassIndex(currentClassName) : (anonClassCounter++);
                String anonClassName = currentClassName + "$Anon$" + index;
                String superName = isInterface ? "java/lang/Object" : resolvedTarget;
                List<String> interfaces = new ArrayList<>();
                if (isInterface) {
                    interfaces.add(resolvedTarget);
                }

                IRClass anonIRClass = new IRClass(anonClassName, superName, interfaces, false);
                anonIRClass.setAccessFlags(Opcodes.ACC_SUPER | Opcodes.ACC_FINAL);
                anonIRClass.setAnonymous(true);
                anonIRClass.setEnclosingClass(currentClassName);
                if (currentMethodName != null) {
                    anonIRClass.setEnclosingMethod(currentClassName, currentMethodName, currentMethodDescriptor);
                }

                CompilerRegistry.globalSuperClassRegistry.put(anonClassName, superName);
                if (!interfaces.isEmpty()) {
                    CompilerRegistry.globalInterfaceRegistry.put(anonClassName, interfaces.toArray(new String[0]));
                }

                String outerEnclosingClass = currentClassName;
                boolean captureOuter = !insideStaticContext && outerEnclosingClass != null;
                if (captureOuter) {
                    CompilerRegistry.globalInnerClassOuterMap.put(anonClassName, outerEnclosingClass);
                    CompilerRegistry.globalInnerClassUsedOuterMap.put(anonClassName, outerEnclosingClass);
                    IRField this0Field = new IRField("this$0", OceanTypeSystem.wrapObjectType(outerEnclosingClass), false, null);
                    this0Field.setAccessFlags(Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC);
                    anonIRClass.addField(this0Field);
                }

                Set<String> usedInAnon = new LinkedHashSet<>();
                if (ctx.memberDeclaration() != null) {
                    for (OceanParser.MemberDeclarationContext mCtx : ctx.memberDeclaration()) {
                        findUsedVariablesRecursive(mCtx, usedInAnon);
                    }
                }
                if (ctx.statement() != null) {
                    for (OceanParser.StatementContext sCtx : ctx.statement()) {
                        findUsedVariablesRecursive(sCtx, usedInAnon);
                    }
                }

                List<String> capturedVarNames = new ArrayList<>();
                List<String> capturedVarTypes = new ArrayList<>();
                for (String uName : usedInAnon) {
                    if ("this".equals(uName) || "super".equals(uName)) continue;
                    if (symbolTable != null && symbolTable.getIndex(uName) != -1) {
                        String uType = symbolTable.getType(uName);
                        if (uType != null) {
                            try {
                                symbolTable.markCaptured(uName, ctx);
                            } catch (Throwable ignored) {
                            }
                            capturedVarNames.add(uName);
                            capturedVarTypes.add(uType);
                        }
                    }
                }

                for (int i = 0; i < capturedVarNames.size(); i++) {
                    String cName = capturedVarNames.get(i);
                    String cType = capturedVarTypes.get(i);
                    IRField valField = new IRField("val$" + cName, cType, false, null);
                    valField.setAccessFlags(Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC);
                    anonIRClass.addField(valField);
                    CompilerRegistry.globalFieldRegistry.computeIfAbsent(anonClassName, k -> new ConcurrentHashMap<>()).put("val$" + cName, cType);
                    CompilerRegistry.globalFieldAccess.computeIfAbsent(anonClassName, k -> new ConcurrentHashMap<>()).put("val$" + cName, Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC);
                }

                String oldClass = currentClassName;
                String oldSuper = currentSuperName;
                SymbolTable oldSymbolTable = symbolTable;

                currentClassName = anonClassName;
                currentSuperName = superName;
                symbolTable = new SymbolTable(false, importedClasses, oldSymbolTable.getTypeParams(), oldSymbolTable.getSession());

                try {
                    boolean hasConstructor = false;
                    if (ctx.memberDeclaration() != null) {
                        for (OceanParser.MemberDeclarationContext mCtx : ctx.memberDeclaration()) {
                            IRNode node = visit(mCtx);
                            if (node instanceof IRMethod) {
                                anonIRClass.addMethod((IRMethod) node);
                                if (((IRMethod) node).getName().equals("<init>")) hasConstructor = true;
                            } else if (node instanceof IRField field) {
                                anonIRClass.addField(field);
                                if (!field.isStatic()) {
                                    anonIRClass.addInstanceInitializer(field);
                                } else {
                                    anonIRClass.addStaticInitializer(field);
                                }
                            } else if (node instanceof ocean.compiler.ir.IRFieldGroup group) {
                                for (IRField f : group.getFields()) {
                                    anonIRClass.addField(f);
                                    if (!f.isStatic()) {
                                        anonIRClass.addInstanceInitializer(f);
                                    } else {
                                        anonIRClass.addStaticInitializer(f);
                                    }
                                }
                            } else if (node instanceof IRBlock block) {
                                if (block.isStatic()) {
                                    anonIRClass.addStaticBlock(block);
                                    anonIRClass.addStaticInitializer(block);
                                } else {
                                    anonIRClass.addInstanceBlock(block);
                                    anonIRClass.addInstanceInitializer(block);
                                }
                            }
                        }
                    }
                    if (ctx.statement() != null) {
                        for (OceanParser.StatementContext sCtx : ctx.statement()) {
                            visit(sCtx);
                        }
                    }

                    if (!hasConstructor) {
                        List<IRMethod.IRParameter> ctorParams = new ArrayList<>();
                        List<IRExpression> superArgs = new ArrayList<>();
                        List<String> paramTypes = getParameterDescriptors(ctorDesc);
                        for (int i = 0; i < paramTypes.size(); i++) {
                            String pType = paramTypes.get(i);
                            String pName = "p" + i;
                            ctorParams.add(new IRMethod.IRParameter(pName, pType));
                            IRVariableAccess varAccess = new IRVariableAccess(pName, pType, false, null, false);
                            superArgs.add(varAccess);
                        }

                        IRMethodCall superCall = new IRMethodCall(superName, "<init>", ctorDesc, superArgs, false);
                        superCall.setSuperCall(true);
                        superCall.setReceiver(new IRVariableAccess("super", OceanTypeSystem.wrapObjectType(superName), false, null, false));

                        List<IRStatement> ctorStmts = new ArrayList<>();
                        ctorStmts.add(new IRExprStatement(superCall));

                        if (captureOuter) {
                            String outerDesc = OceanTypeSystem.wrapObjectType(outerEnclosingClass);
                            ctorParams.addFirst(new IRMethod.IRParameter("this$0", outerDesc));
                            IRAssignment assignThis0 = new IRAssignment(
                                    new IRVariableAccess("this$0", outerDesc, true, anonClassName, false),
                                    new IRVariableAccess("this$0", outerDesc, false, null, false),
                                    false
                            );
                            ctorStmts.add(new IRExprStatement(assignThis0));
                        }

                        for (int i = 0; i < capturedVarNames.size(); i++) {
                            String cName = capturedVarNames.get(i);
                            String cType = capturedVarTypes.get(i);
                            ctorParams.add(new IRMethod.IRParameter("val$" + cName, cType));
                            IRAssignment assignVal = new IRAssignment(
                                    new IRVariableAccess("val$" + cName, cType, true, anonClassName, false),
                                    new IRVariableAccess("val$" + cName, cType, false, null, false),
                                    false
                            );
                            ctorStmts.add(new IRExprStatement(assignVal));
                        }

                        StringBuilder ctorDescBuilder = new StringBuilder("(");
                        if (captureOuter) {
                            ctorDescBuilder.append(OceanTypeSystem.wrapObjectType(outerEnclosingClass));
                        }
                        for (String pt : paramTypes) {
                            ctorDescBuilder.append(pt);
                        }
                        for (String ct : capturedVarTypes) {
                            ctorDescBuilder.append(ct);
                        }
                        ctorDescBuilder.append(")V");
                        String finalAnonCtorDesc = ctorDescBuilder.toString();

                        IRBlock ctorBody = new IRBlock(ctorStmts);
                        IRMethod anonCtor = new IRMethod("<init>", finalAnonCtorDesc, false, false, ctorBody);
                        for (IRMethod.IRParameter param : ctorParams) {
                            anonCtor.addParameter(param);
                        }
                        anonIRClass.addMethod(anonCtor);
                        ctorDesc = finalAnonCtorDesc;
                    }
                } finally {
                    currentClassName = oldClass;
                    currentSuperName = oldSuper;
                    symbolTable = oldSymbolTable;
                }

                anonymousClasses.add(anonIRClass);

                List<IRExpression> newArgs = new ArrayList<>(args);
                if (captureOuter) {
                    newArgs.addFirst(new IRVariableAccess("this", OceanTypeSystem.wrapObjectType(outerEnclosingClass), false, null, false));
                }
                for (int i = 0; i < capturedVarNames.size(); i++) {
                    String cName = capturedVarNames.get(i);
                    String cType = capturedVarTypes.get(i);
                    newArgs.add(new IRVariableAccess(cName, cType, false, null, false));
                }
                CompilerRegistry.globalMethodRegistry.computeIfAbsent(anonClassName, k -> new ConcurrentHashMap<>()).put("<init>", ctorDesc);
                CompilerRegistry.globalOverloadRegistry.computeIfAbsent(anonClassName, k -> new ConcurrentHashMap<>()).computeIfAbsent("<init>", k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(ctorDesc);

                IRNewObject newObj = new IRNewObject(anonClassName, ctorDesc, newArgs);
                newObj.setTypeDescriptor(OceanTypeSystem.wrapObjectType(anonClassName));
                return newObj;
            }

            String effectiveTypeDesc = ensureDescriptor(typeStr);
            if (!typeStr.contains("<")) {
                String inferred = inferGenericTypeDescriptorForNew(resolvedTarget, currentCastType, args);
                if (inferred != null) {
                    effectiveTypeDesc = inferred;
                }
            }
            IRNewObject newObj = new IRNewObject(resolvedTarget, ctorDesc, args);
            newObj.setTypeDescriptor(effectiveTypeDesc);
            return newObj;
        }
    }

    private String inferGenericTypeDescriptorForNew(String resolvedTarget, String castType, List<IRExpression> args) {
        if (resolvedTarget == null) return null;
        String cleanTarget = resolvedTarget.replace('.', '/');

        List<CompilerRegistry.TypeParameterInfo> typeParams = CompilerRegistry.globalTypeParameterRegistry.get(cleanTarget);
        if (typeParams == null) {
            String simple = OceanTypeSystem.findSimpleName(cleanTarget);
            for (Map.Entry<String, List<CompilerRegistry.TypeParameterInfo>> entry : CompilerRegistry.globalTypeParameterRegistry.entrySet()) {
                if (entry.getKey().endsWith("/" + simple) || entry.getKey().endsWith("$" + simple) || entry.getKey().equals(simple)) {
                    typeParams = entry.getValue();
                    break;
                }
            }
        }

        if (typeParams == null || typeParams.isEmpty()) {
            try {
                Class<?> cls = OceanTypeSystem.forName(cleanTarget.replace('/', '.'));
                if (cls == null || cls.getTypeParameters().length == 0) {
                    return null;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }

        // 1. Target-type inference from castType
        if (castType != null && castType.contains("<") && castType.contains(">")) {
            String castRaw = castType.substring(0, castType.indexOf('<')).trim();
            if (castRaw.startsWith("L") && castRaw.endsWith(";")) castRaw = castRaw.substring(1, castRaw.length() - 1);
            castRaw = TypeChecker.cleanDescriptor(castRaw);
            if (castRaw.equals(cleanTarget) || castRaw.endsWith("/" + OceanTypeSystem.findSimpleName(cleanTarget))) {
                return ensureDescriptor(castType);
            }
        }

        // 2. Argument-type inference from constructor parameters
        if (typeParams != null && !typeParams.isEmpty() && args != null && !args.isEmpty()) {
            Map<String, List<CompilerRegistry.MethodParamInfo>> classParams = CompilerRegistry.globalMethodParamsRegistry.get(cleanTarget);
            if (classParams == null) {
                String simple = OceanTypeSystem.findSimpleName(cleanTarget);
                for (Map.Entry<String, Map<String, List<CompilerRegistry.MethodParamInfo>>> entry : CompilerRegistry.globalMethodParamsRegistry.entrySet()) {
                    if (entry.getKey().endsWith("/" + simple) || entry.getKey().endsWith("$" + simple) || entry.getKey().equals(simple)) {
                        classParams = entry.getValue();
                        break;
                    }
                }
            }
            if (classParams != null) {
                List<CompilerRegistry.MethodParamInfo> pInfos = classParams.get("<init>#" + args.size());
                if (pInfos == null) pInfos = classParams.get("<init>");
                if (pInfos != null) {
                    Map<String, String> inferredMap = new HashMap<>();
                    for (int i = 0; i < args.size() && i < pInfos.size(); i++) {
                        String rawP = pInfos.get(i).rawType();
                        if (rawP != null) {
                            boolean isNull = rawP.endsWith("?");
                            String baseP = isNull ? rawP.substring(0, rawP.length() - 1) : rawP;
                            for (CompilerRegistry.TypeParameterInfo tp : typeParams) {
                                if (tp.name.equals(baseP)) {
                                    String argDesc = args.get(i).getTypeDescriptor();
                                    if (argDesc != null && !"null".equals(argDesc)) {
                                        inferredMap.put(tp.name, ensureDescriptor(argDesc));
                                    }
                                }
                            }
                        }
                    }
                    if (inferredMap.size() == typeParams.size()) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("L").append(cleanTarget).append("<");
                        for (int i = 0; i < typeParams.size(); i++) {
                            if (i > 0) sb.append(",");
                            sb.append(inferredMap.get(typeParams.get(i).name));
                        }
                        sb.append(">;");
                        return sb.toString();
                    }
                }
            }
        }

        return null;
    }

    @Override
    public IRNode visitOceanInputPrimary(OceanParser.OceanInputPrimaryContext ctx) {
        return new IRMethodCall("ocean/stdlib/RuntimeUtils", "getScanner", "()Ljava/util/Scanner;", new ArrayList<>(), true);
    }

    private String resolveConstructorDescriptor(String internalName, List<IRExpression> args) {
        return resolveMethodDescriptor(internalName, "<init>", args);
    }

    private String getPromotedType(String t1, String t2) {
        if (t1 == null || t2 == null) return OceanTypeSystem.OBJECT_DESC;
        // Strip nullable "?" suffix and generics before comparison so that
        // e.g. "Ljava/lang/String;?" is still recognized as a String type.
        t1 = TypeChecker.cleanDescriptor(t1);
        t2 = TypeChecker.cleanDescriptor(t2);
        if (TypeChecker.isStringType(t1) || TypeChecker.isStringType(t2)) return OceanTypeSystem.STRING_DESC;
        if (TypeChecker.isBigDecimalType(t1) || TypeChecker.isBigDecimalType(t2)) return OceanTypeSystem.BIGDECIMAL_DESC;
        if (TypeChecker.isBoolean(t1) && TypeChecker.isBoolean(t2)) return "Z";

        String p1 = TypeChecker.isPrimitive(t1) ? t1 : TypeChecker.unbox(t1);
        String p2 = TypeChecker.isPrimitive(t2) ? t2 : TypeChecker.unbox(t2);
        if (p1 != null && p2 != null) {
            if (p1.equals("D") || p2.equals("D")) return "D";
            if (p1.equals("F") || p2.equals("F")) return "F";
            if (p1.equals("J") || p2.equals("J")) return "J";
            return "I";
        }
        if (p1 != null) {
            return switch (p1) {
                case "D" -> "D";
                case "F" -> "F";
                case "J" -> "J";
                default -> "I";
            };
        }
        if (p2 != null) {
            return switch (p2) {
                case "D" -> "D";
                case "F" -> "F";
                case "J" -> "J";
                default -> "I";
            };
        }

        // Numeric wrapper nesneleri (Integer, Double, Long, vb.) → Number
        if (isNumericObjectType(t1) || isNumericObjectType(t2)) return OceanTypeSystem.NUMBER_DESC;
        // Genel nesne türleri (generic T, Object, vb.) aritmetik/bitwise bağlamda
        // NUMBER_DESC olarak korunur — emitter unbox ederek işlem yapabilir.
        // NOT: Ternary ifadeler için getTernaryCommonType kullanılır, burası değil.
        if ((t1.startsWith("L") && !TypeChecker.isBigDecimalType(t1)) || (t2.startsWith("L") && !TypeChecker.isBigDecimalType(t2))){
            return OceanTypeSystem.NUMBER_DESC;
        }
        // Primitif sayısal tür genişletme kuralları (widening)
        if (t1.equals("D") || t2.equals("D")) return "D";
        if (t1.equals("F") || t2.equals("F")) return "F";
        if (t1.equals("J") || t2.equals("J")) return "J";
        return "I";
    }

    private String getTernaryCommonType(String t1, String t2) {
        return TypeChecker.getTernaryPromotedType(t1, t2, null);
    }

    private boolean isNumericObjectType(String desc) {
        if (desc == null || !desc.startsWith("L")) return false;
        String clean = TypeChecker.cleanDescriptor(desc);
        return clean.equals(OceanTypeSystem.NUMBER_DESC) ||
               clean.equals("Ljava/lang/Integer;") ||
               clean.equals("Ljava/lang/Double;") ||
               clean.equals("Ljava/lang/Float;") ||
               clean.equals("Ljava/lang/Long;") ||
               clean.equals("Ljava/lang/Short;") ||
               clean.equals("Ljava/lang/Byte;") ||
               clean.equals("Ljava/math/BigInteger;");
    }

    private String findCommonObjectType(String t1, String t2) {
        if (t1 == null || t2 == null) return OceanTypeSystem.OBJECT_DESC;
        if (t1.equals(t2)) return t1;
        CompilationSession session = CompilationSession.getActiveSession();
        if (TypeChecker.isAssignable(t1, t2, session)) return t1;
        if (TypeChecker.isAssignable(t2, t1, session)) return t2;

        List<String> chain1 = getAncestorChain(t1);
        List<String> chain2 = getAncestorChain(t2);

        for (String ancestor : chain1) {
            if (chain2.contains(ancestor)) {
                return OceanTypeSystem.wrapObjectType(ancestor);
            }
        }
        return OceanTypeSystem.OBJECT_DESC;
    }

    private List<String> getAncestorChain(String typeDesc) {
        List<String> chain = new ArrayList<>();
        String current = TypeChecker.getInternalName(typeDesc);
        while (current != null && !"java/lang/Object".equals(current) && chain.size() < 50) {
            chain.add(current);
            current = CompilerRegistry.globalSuperClassRegistry.get(current);
        }
        chain.add("java/lang/Object");
        return chain;
    }

    private String getMethodReturnType(OceanParser.NormalMethodContext ctx) {
        String detected = null;
        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree child = ctx.getChild(i);
            if (child.getText().equals("function")) {
                break;
            }
            if (child.getText().equals("void")) {
                detected = "void";
                break;
            }
            if (child instanceof OceanParser.TypeContext typeCtx) {
                if (ctx.extType == null || typeCtx != ctx.extType) {
                    detected = typeCtx.getText();
                    break;
                }
            }
        }
        if (detected == null || detected.equals("void")) {
            return "V";
        }
        return getTypeDescriptor(detected);
    }

    private String getTypeDescriptor(String type) {
        if (type == null) return OceanTypeSystem.OBJECT_DESC;
        type = type.trim();

        boolean nullable = false;
        while (type.endsWith("?")) {
            nullable = true;
            type = type.substring(0, type.length() - 1).trim();
        }

        /*String builtin = OceanTypeSystem.getBuiltinDescriptor(type);
        if (builtin != null && !builtin.equals(OceanTypeSystem.OBJECT_DESC)) {
            if (nullable) {
                if (builtin.length() == 1 && "ZBCSIJFD".indexOf(builtin.charAt(0)) >= 0) {
                    builtin = OceanTypeSystem.getBoxedDescriptor(builtin);
                }
                builtin = builtin + "?";
            }
            return builtin;
        }*/

        if (type.startsWith("L") && type.endsWith(";") && (type.contains("/") || type.contains("$"))) {
            return nullable ? type + "?" : type;
        }

        if (type.contains("<")) {
            int ltIdx = type.indexOf('<');
            int gtIdx = type.lastIndexOf('>');
            if (ltIdx > 0 && gtIdx > ltIdx) {
                String res = ensureDescriptor(type);
                return nullable ? res + "?" : res;
            }
            if (ltIdx > 0) {
                String res = getTypeDescriptor(type.substring(0, ltIdx).trim());
                return nullable ? res + "?" : res;
            }
        }

        if (type.startsWith("[")) {
            return nullable ? type + "?" : type;
        }

        if (currentClassName != null) {
            List<CompilerRegistry.TypeParameterInfo> infos = CompilerRegistry.globalTypeParameterRegistry.get(currentClassName);
            if (infos != null) {
                for (CompilerRegistry.TypeParameterInfo info : infos) {
                    if (info.name.equals(type)) {
                        String erased = info.getErasedType();
                        if (nullable) {
                            erased = erased + "?";
                        }
                        return erased;
                    }
                }
            }
        }


        String desc;
        if (type.length() == 1) {
            String resolved = resolveTypeName(type);
            if (resolved != null && !resolved.equals(type)) {
                desc = OceanTypeSystem.wrapObjectType(resolved);
                if (nullable) {
                    desc = desc + "?";
                }
                return desc;
            }
        }
        if (type.endsWith("[]")) {
            desc = "[" + getTypeDescriptor(type.substring(0, type.length() - 2));
        } else if (symbolTable != null && symbolTable.getTypeParams() != null && symbolTable.getTypeParams().contains(type)) {
            desc = getErasedTypeParameter(type);
        } else {
            desc = switch (type) {
                case "I", "int" -> "I";
                case "J", "long" -> "J";
                case "Z", "bool", "boolean" -> "Z";
                case "F", "float" -> "F";
                case "D", "double" -> "D";
                case "B", "byte" -> "B";
                case "C", "char" -> "C";
                case "S", "short" -> "S";
                case "string", "String" -> OceanTypeSystem.STRING_DESC;
                case "variable", "value", "var", "null" -> OceanTypeSystem.OBJECT_DESC;
                case "V", "void" -> "V";
                default -> OceanTypeSystem.wrapObjectType(resolveTypeName(type));
            };
        }
        if (nullable) {
            if (desc.length() == 1 && "ZBCSIJFD".indexOf(desc.charAt(0)) >= 0) {
                desc = OceanTypeSystem.getBoxedDescriptor(desc);
            }
            desc = desc + "?";
        }
        return desc;
    }

    private List<CompilerRegistry.TypeParameterInfo> getTypeParameterInfosForClass(String className) {
        if (className == null) return null;
        String full = getFullClassName(className);
        List<CompilerRegistry.TypeParameterInfo> infos = CompilerRegistry.globalTypeParameterRegistry.get(full);
        if (infos != null) return infos;
        infos = CompilerRegistry.globalTypeParameterRegistry.get(className);
        if (infos != null) return infos;
        for (Map.Entry<String, List<CompilerRegistry.TypeParameterInfo>> entry : CompilerRegistry.globalTypeParameterRegistry.entrySet()) {
            if (entry.getKey().endsWith("/" + className) || entry.getKey().equals(className)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String getErasedTypeParameter(String type) {
        if (currentClassName != null) {
            List<CompilerRegistry.TypeParameterInfo> infos = getTypeParameterInfosForClass(currentClassName);
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

    private List<CompilerRegistry.TypeParameterInfo> extractMethodTypeParameters(List<OceanParser.TypeParameterContext> tpList) {
        List<CompilerRegistry.TypeParameterInfo> typeParams = new ArrayList<>();
        if (tpList != null && !tpList.isEmpty()) {
            for (OceanParser.TypeParameterContext tpCtx : tpList) {
                if (tpCtx == null || tpCtx.anyId() == null) continue;
                String tpName = tpCtx.anyId().getText();
                CompilerRegistry.Variance var = CompilerRegistry.Variance.INVARIANT;
                if (tpCtx.PLUS() != null) var = CompilerRegistry.Variance.COVARIANT;
                else if (tpCtx.MINUS() != null) var = CompilerRegistry.Variance.CONTRAVARIANT;

                List<String> upperBounds = new ArrayList<>();
                String lowerBound = null;
                if (tpCtx.type() != null) {
                    for (OceanParser.TypeContext tCtx : tpCtx.type()) {
                        String rawText = tCtx.getText().trim();
                        if (rawText.contains("&")) {
                            int depth = 0;
                            int last = 0;
                            for (int i = 0; i < rawText.length(); i++) {
                                char ch = rawText.charAt(i);
                                if (ch == '<') depth++;
                                else if (ch == '>') depth--;
                                else if (ch == '&' && depth == 0) {
                                    String part = rawText.substring(last, i).trim();
                                    if (!part.isEmpty()) upperBounds.add(getTypeDescriptor(part));
                                    last = i + 1;
                                }
                            }
                            String part = rawText.substring(last).trim();
                            if (!part.isEmpty()) upperBounds.add(getTypeDescriptor(part));
                        } else {
                            upperBounds.add(getTypeDescriptor(rawText));
                        }
                    }
                }
                typeParams.add(new CompilerRegistry.TypeParameterInfo(tpName, var, upperBounds, lowerBound, false));
            }
        }
        return typeParams;
    }

    private CompilerRegistry.TypeParameterInfo findTypeParameterInfo(String typeName) {
        if (typeName == null) return null;
        while (typeName.endsWith("?")) {
            typeName = typeName.substring(0, typeName.length() - 1);
        }
        if (TypeChecker.isClassType(typeName)) {
            typeName = typeName.substring(1, typeName.length() - 1);
        }
        for (CompilerRegistry.TypeParameterInfo info : currentMethodTypeParamInfos) {
            if (info.name.equals(typeName)) return info;
        }
        if (currentClassName != null) {
            List<CompilerRegistry.TypeParameterInfo> infos = getTypeParameterInfosForClass(currentClassName);
            if (infos != null) {
                for (CompilerRegistry.TypeParameterInfo info : infos) {
                    if (info.name.equals(typeName)) return info;
                }
            }
            if (currentClassName.contains("$")) {
                String outer = currentClassName.substring(0, currentClassName.lastIndexOf('$'));
                infos = getTypeParameterInfosForClass(outer);
                if (infos != null) {
                    for (CompilerRegistry.TypeParameterInfo info : infos) {
                        if (info.name.equals(typeName)) return info;
                    }
                }
            }
        }
        return null;
    }


    private String inferType(OceanParser.ExpressionContext ctx) {
        if (ctx == null) return OceanTypeSystem.OBJECT_DESC;
        IRNode node = visit(ctx);
        if (node instanceof IRExpression) return ((IRExpression) node).getTypeDescriptor();
        return OceanTypeSystem.OBJECT_DESC;
    }

    private String inferRawType(OceanParser.ExpressionContext expr) {
        if (expr == null) return OceanTypeSystem.OBJECT_DESC;
        return TypeInferenceEngine.inferType(expr, new TypeInferenceEngine.InferenceContext() {
            @Override
            public String lookupVariableType(String name) {
                return symbolTable.getType(name);
            }

            @Override
            public String lookupVariableRawType(String name) {
                return symbolTable.getRawType(name);
            }

            @Override
            public String lookupVariableOriginalType(String name) {
                return symbolTable.getOriginalType(name);
            }

            @Override
            public String getFieldType(String ownerDesc, String fieldName) {
                if (ownerDesc == null) {
                    String internalClass = currentClassName != null ? currentClassName.replace(".", "/") : null;
                    if (internalClass != null) {
                        String search = internalClass;
                        while (true) {
                            String fType = resolveFieldType(search, fieldName);
                            if (fType != null) return fType;
                            if (search.contains("$")) {
                                search = search.substring(0, search.lastIndexOf('$'));
                            } else {
                                break;
                            }
                        }
                    }
                    String staticOwner = resolveStaticImportFieldOwner(fieldName);
                    return staticOwner != null ? resolveFieldType(staticOwner, fieldName) : null;
                }
                return resolveFieldType(ownerDesc, fieldName);
            }

            @Override
            public String resolveMethodReturnType(String ownerDesc, String methodName, List<String> argTypes) {
                if (ownerDesc == null) return OceanTypeSystem.OBJECT_DESC;

                // Try extension method first!
                List<CompilerRegistry.ExtensionMethodInfo> extCandidates = CompilerRegistry.findExtensionMethods(ownerDesc, methodName);
                if (!extCandidates.isEmpty()) {
                    for (CompilerRegistry.ExtensionMethodInfo emi : extCandidates) {
                        List<String> paramTypes = getParameterDescriptors(emi.descriptor());
                        if (paramTypes.size() == argTypes.size() + 1) {
                            boolean match = true;
                            for (int i = 0; i < argTypes.size(); i++) {
                                if (!TypeChecker.isAssignable(paramTypes.get(i + 1), argTypes.get(i), CompilationSession.getActiveSession())) {
                                    match = false;
                                    break;
                                }
                            }
                            if (match) {
                                return emi.descriptor().substring(emi.descriptor().lastIndexOf(')') + 1);
                            }
                        }
                    }
                }

                String cleanOwner = TypeChecker.cleanDescriptor(ownerDesc);
                if (TypeChecker.isClassType(cleanOwner)) {
                    cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
                }
                String desc = OverloadResolver.resolve(cleanOwner, methodName, argTypes);
                if (desc != null && desc.contains(")")) {
                    String retType = desc.substring(desc.lastIndexOf(')') + 1);
                    String refined = resolveGenericReturnType(ownerDesc, methodName, argTypes);
                    if (refined != null) return refined;
                    return retType;
                }
                String refined = resolveGenericReturnType(ownerDesc, methodName, argTypes);
                if (refined != null) return refined;
                return OceanTypeSystem.OBJECT_DESC;
            }

            @Override
            public String resolveClassName(String name) {
                return resolveTypeName(name);
            }

            @Override
            public String getTypeDescriptor(String typeName) {
                return IRGenerator.this.getTypeDescriptor(typeName);
            }

            @Override
            public String getCurrentClassName() {
                return currentClassName;
            }

            @Override
            public String getCurrentSuperName() {
                return currentSuperName;
            }
        });
    }

    @Override
    public IRNode visitInterpolatedStringPrimary(OceanParser.InterpolatedStringPrimaryContext ctx) {
        boolean isMultiline = ctx.MULTILINE_INTERPOLATED_STRING() != null;
        String text = isMultiline ? ctx.MULTILINE_INTERPOLATED_STRING().getText() : ctx.INTERPOLATED_STRING().getText();
        String rawContent = isMultiline ? text.substring(4, text.length() - 3) : text.substring(2, text.length() - 1);
        String content = isMultiline ? StringHelper.stripIndent(rawContent) : rawContent;
        List<IRNode> parts = new ArrayList<>();
        int i = 0;
        while (i < content.length()) {
            int braceStart = findUnescapedStartBrace(content, i);
            if (braceStart == -1) {
                String rawPart = content.substring(i).replace("{{", "{").replace("}}", "}");
                String unescaped = isMultiline ? StringHelper.unescapeTextBlock(rawPart) : StringHelper.unescapeString(rawPart);
                parts.add(new IRLiteral(unescaped, OceanTypeSystem.STRING_DESC));
                break;
            }
            if (braceStart > i) {
                String rawPart = content.substring(i, braceStart).replace("{{", "{").replace("}}", "}");
                String unescaped = isMultiline ? StringHelper.unescapeTextBlock(rawPart) : StringHelper.unescapeString(rawPart);
                parts.add(new IRLiteral(unescaped, OceanTypeSystem.STRING_DESC));
            }
            int braceEnd = findMatchingBrace(content, braceStart);
            if (braceEnd == -1) break;
            String exprText = content.substring(braceStart + 1, braceEnd);
            parts.add(parseExpression(exprText));
            i = braceEnd + 1;
        }
        return new IRInterpolatedString(parts);
    }

    private int findUnescapedStartBrace(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '{') {
                    i++;
                    continue;
                }
                return i;
            }
        }
        return -1;
    }

    private int findMatchingBrace(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            // Multiline string """..."""
            if (s.startsWith("\"\"\"", i)) {
                i += 3;
                while (i < s.length()) {
                    if (s.startsWith("\"\"\"", i)) {
                        i += 2; // will be incremented by loop
                        break;
                    }
                    if (s.charAt(i) == '\\' && i + 1 < s.length()) {
                        i += 2;
                    } else {
                        i++;
                    }
                }
                continue;
            }

            char c = s.charAt(i);

            // Regular string "..."
            if (c == '"') {
                i++;
                while (i < s.length()) {
                    char sc = s.charAt(i);
                    if (sc == '\\' && i + 1 < s.length()) {
                        i += 2;
                        continue;
                    }
                    if (sc == '"') {
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Char literal '...'
            if (c == '\'') {
                i++;
                while (i < s.length()) {
                    char cc = s.charAt(i);
                    if (cc == '\\' && i + 1 < s.length()) {
                        i += 2;
                        continue;
                    }
                    if (cc == '\'') {
                        break;
                    }
                    i++;
                }
                continue;
            }

            // Single line comment //
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                i += 2;
                while (i < s.length() && s.charAt(i) != '\n' && s.charAt(i) != '\r') {
                    i++;
                }
                continue;
            }

            // Multi-line comment /* ... */
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    i++;
                }
                i++; // skip /
                continue;
            }

            // Nested braces inside expression:
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private IRNode parseExpression(String expr) {
        if (expr == null || expr.trim().isEmpty()) {
            throw new CompilationException("Dize enterpolasyonundaki ifade boş olamaz: {}");
        }
        OceanLexer lexer = new OceanLexer(CharStreams.fromString(expr));
        lexer.removeErrorListeners();
        final List<String> errors = new ArrayList<>();
        BaseErrorListener errListener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
                errors.add("pos " + charPositionInLine + ": " + msg);
            }
        };
        lexer.addErrorListener(errListener);
        OceanParser parser = new OceanParser(OceanTokenStreamFactory.createTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(errListener);
        OceanParser.ExpressionContext exprCtx = parser.expression();
        if (parser.getCurrentToken().getType() != Token.EOF) {
            errors.add("beklenmeyen token: " + parser.getCurrentToken().getText());
        }
        if (!errors.isEmpty() || parser.getNumberOfSyntaxErrors() > 0) {
            throw new CompilationException("Dize enterpolasyonundaki ifade geçersiz: {" + expr + "} - " + String.join(", ", errors));
        }
        IRNode node = visit(exprCtx);
        return (node != null) ? node : new IRLiteral(expr, OceanTypeSystem.STRING_DESC);
    }

    @Override
    public IRNode visitCastExpr(OceanParser.CastExprContext ctx) {
        String typeText = ctx.type().getText();
        if (typeText.contains("&")) {
            List<String> bounds = new java.util.ArrayList<>();
            int depth = 0;
            int last = 0;
            for (int i = 0; i < typeText.length(); i++) {
                char ch = typeText.charAt(i);
                if (ch == '<') depth++;
                else if (ch == '>') depth--;
                else if (ch == '&' && depth == 0) {
                    bounds.add(typeText.substring(last, i).trim());
                    last = i + 1;
                }
            }
            bounds.add(typeText.substring(last).trim());

            String primaryType = getTypeDescriptor(bounds.getFirst());
            List<String> extraBounds = new java.util.ArrayList<>();
            for (int i = 1; i < bounds.size(); i++) {
                extraBounds.add(getTypeDescriptor(bounds.get(i)));
            }

            String oldCast = currentCastType;
            currentCastType = primaryType;
            IRExpression expr = ensureExpr(visit(ctx.expression()));
            currentCastType = oldCast;
            return new IRCastExpression(expr, primaryType, extraBounds);
        }

        String targetType = getTypeDescriptor(ctx.type().getText());
        String oldCast = currentCastType;
        currentCastType = targetType;
        IRExpression expr = ensureExpr(visit(ctx.expression()));
        currentCastType = oldCast;
        return new IRCastExpression(expr, targetType);
    }

    @Override
    public IRNode visitLambdaExpr(OceanParser.LambdaExprContext ctx) {
        String targetType = currentCastType;
        if (targetType == null || resolveSAM(targetType) == null) {
            int paramCount = 0;
            if (ctx.parameterList() != null) {
                paramCount = ctx.parameterList().parameter().size();
            } else if (ctx.identifierList() != null) {
                paramCount = ctx.identifierList().anyId().size();
            }
            boolean hasReturnValue = false;
            if (ctx.expression() != null) {
                hasReturnValue = true;
            } else if (ctx.block() != null) {
                hasReturnValue = blockHasReturn(ctx.block());
            }
            targetType = OceanTypeSystem.findFuncInterface(paramCount, hasReturnValue);
        }

        String[] sam = resolveSAM(targetType);
        if (sam == null) {
            reportWarning(ctx, "Target type '" + targetType + "' is not a functional interface.");
            return new IRLiteral(null, OceanTypeSystem.OBJECT_DESC);
        }

        String samName = sam[0];
        String samDesc = sam[1];

        // Declared parameters of this lambda
        Set<String> declaredParamNames = new HashSet<>();
        if (ctx.parameterList() != null) {
            for (OceanParser.ParameterContext p : ctx.parameterList().parameter()) {
                String pName = p.anyId().getText();
                if (!pName.equals("_")) {
                    if (declaredParamNames.contains(pName)) {
                        reportError(p, "Duplicate lambda parameter '" + pName + "'.");
                    }
                    if (symbolTable.getType(pName) != null) {
                        reportError(p, "Variable '" + pName + "' is already defined in this scope.");
                    }
                    declaredParamNames.add(pName);
                }
            }
        } else if (ctx.identifierList() != null) {
            for (OceanParser.AnyIdContext id : ctx.identifierList().anyId()) {
                String pName = id.getText();
                if (!pName.equals("_")) {
                    if (declaredParamNames.contains(pName)) {
                        reportError(id, "Duplicate lambda parameter '" + pName + "'.");
                    }
                    if (symbolTable.getType(pName) != null) {
                        reportError(id, "Variable '" + pName + "' is already defined in this scope.");
                    }
                    declaredParamNames.add(pName);
                }
            }
        }

        // 1. Capture analysis
        Set<String> used = new LinkedHashSet<>();
        findUsedVariablesRecursive(ctx.block() != null ? ctx.block() : ctx.expression(), used);

        List<String> capturedNames = new ArrayList<>();
        List<String> capturedTypes = new ArrayList<>();

        boolean capturesThis = !insideStaticContext && (used.contains("this") || used.contains("super") || (symbolTable.getType("this") != null && (used.contains("this") || used.contains("super"))));
        if (!capturesThis && !insideStaticContext && currentClassName != null) {
            String internalName = currentClassName.replace(".", "/");
            for (String name : used) {
                if (declaredParamNames.contains(name)) continue;
                if (symbolTable.getType(name) != null) continue; // local variable
                if (classHasField(internalName, name) && !resolveFieldStaticity(internalName, name)) {
                    capturesThis = true;
                    break;
                }
                if (classHasMethod(internalName, name) && !hasStaticMethod(internalName, name)) {
                    capturesThis = true;
                    break;
                }
            }
        }

        if (capturesThis && currentClassName != null) {
            capturedNames.add("this");
            capturedTypes.add(OceanTypeSystem.wrapObjectType(currentClassName));
        }

        for (String name : used) {
            if (name.equals("this") || name.equals("super")) continue;
            if (declaredParamNames.contains(name)) continue;
            String type = symbolTable.getType(name);
            if (type != null) {
                try {
                    symbolTable.markCaptured(name, ctx);
                } catch (Throwable ignored) {
                }
                capturedNames.add(name);
                capturedTypes.add(type);
            }
        }

        // 2. Determine synthetic method name and isStatic
        String simpleClassName = "Global";
        if (currentClassName != null) {
            String tmp = currentClassName;
            int lastDot = tmp.lastIndexOf('.');
            int lastSlash = tmp.lastIndexOf('/');
            int lastSep = Math.max(lastDot, lastSlash);
            simpleClassName = lastSep >= 0 ? tmp.substring(lastSep + 1) : tmp;
            // JVM method names must not contain '/', '.', '<', '>', '[', ';'
            simpleClassName = SANITIZE_NAME_PATTERN.matcher(simpleClassName).replaceAll("_");
        }
        String lambdaName = "lambda$" + simpleClassName + "$" + (currentMethodReturnType != null ? "method" : "main") + "$" + (lambdaCounter++);
        boolean isStatic = insideStaticContext || !capturedNames.contains("this");

        // 3. Construct synthetic method parameter types and names
        List<String> paramNames = new ArrayList<>();
        List<String> paramTypes = new ArrayList<>();

        // Captured variables come first (excluding 'this' for instance methods where 'this' is ALOAD 0 receiver)
        for (int i = 0; i < capturedNames.size(); i++) {
            String cName = capturedNames.get(i);
            if (!isStatic && cName.equals("this")) continue;
            paramNames.add(cName);
            paramTypes.add(capturedTypes.get(i));
        }

        // Regular lambda parameters
        List<String> samParamTypes = extractSAMGenericParamTypes(targetType, samDesc);
        if (ctx.parameterList() != null) {
            for (int i = 0; i < ctx.parameterList().parameter().size(); i++) {
                OceanParser.ParameterContext p = ctx.parameterList().parameter(i);
                paramNames.add(p.anyId().getText());
                if (p.type() != null) {
                    paramTypes.add(getTypeDescriptor(p.type().getText()));
                } else if (i < samParamTypes.size()) {
                    paramTypes.add(samParamTypes.get(i));
                } else {
                    paramTypes.add(OceanTypeSystem.OBJECT_DESC);
                }
            }
        } else if (ctx.identifierList() != null) {
            for (int i = 0; i < ctx.identifierList().anyId().size(); i++) {
                paramNames.add(ctx.identifierList().anyId(i).getText());
                if (i < samParamTypes.size()) {
                    paramTypes.add(samParamTypes.get(i));
                } else {
                    paramTypes.add(OceanTypeSystem.OBJECT_DESC);
                }
            }
        }

        // 4. Determine return descriptor of the SAM method
        String samReturn = samDesc.substring(samDesc.lastIndexOf(')') + 1);

        // 5. Generate the body IR node
        symbolTable.enterScope();
        if (!isStatic && currentClassName != null) {
            symbolTable.declareVariable("this", OceanTypeSystem.wrapObjectType(currentClassName));
        }
        for (int i = 0; i < paramNames.size(); i++) {
            symbolTable.declareVariable(paramNames.get(i), paramTypes.get(i));
        }

        IRStatement bodyStmt = null;
        if (ctx.block() != null) {
            bodyStmt = (IRStatement) visit(ctx.block());
        } else if (ctx.expression() != null) {
            String oldCast = currentCastType;
            String expectedReturn = extractSAMGenericReturnType(targetType, samReturn);
            if (expectedReturn != null) {
                currentCastType = expectedReturn;
            }
            IRExpression expr = ensureExpr(visit(ctx.expression()));
            currentCastType = oldCast;

            if ("V".equals(samReturn)) {
                bodyStmt = new IRBlock(Arrays.asList(new IRExprStatement(expr), new IRReturnStatement(null)));
            } else {
                bodyStmt = new IRReturnStatement(expr);
            }

            if (expr.getTypeDescriptor() != null && !OceanTypeSystem.OBJECT_DESC.equals(expr.getTypeDescriptor())) {
                String retDesc = expr.getTypeDescriptor();
                if (targetType.contains("<")) {
                    int lt = targetType.indexOf('<');
                    int gt = targetType.lastIndexOf('>');
                    if (lt >= 0 && gt > lt) {
                        String base = targetType.substring(0, lt);
                        String inner = targetType.substring(lt + 1, gt);
                        List<String> typeArgs = splitGenericArgs(inner);
                        if (typeArgs.size() >= 2) {
                            String lastArg = typeArgs.getLast();
                            if (OceanTypeSystem.OBJECT_DESC.equals(lastArg) || "java/lang/Object".equals(lastArg)) {
                                typeArgs.set(typeArgs.size() - 1, retDesc);
                                targetType = base + "<" + String.join(",", typeArgs) + ">" + (targetType.endsWith(";") ? ";" : "");
                            }
                        }
                    }
                }
            }
        }
        symbolTable.exitScope();

        // 6. Build the IRMethod for the synthetic lambda method
        StringBuilder implDesc = new StringBuilder("(");
        for (String pt : paramTypes) {
            implDesc.append(TypeChecker.cleanDescriptor(pt));
        }
        implDesc.append(")").append(samReturn);

        List<IRMethod.IRParameter> irParams = new ArrayList<>();
        for (int i = 0; i < paramNames.size(); i++) {
            irParams.add(new IRMethod.IRParameter(paramNames.get(i), paramTypes.get(i)));
        }

        IRMethod lambdaMethod = new IRMethod(lambdaName, implDesc.toString(), isStatic, false, bodyStmt);
        for (IRMethod.IRParameter ip : irParams) {
            lambdaMethod.addParameter(ip);
        }

        syntheticLambdaMethods.add(lambdaMethod);

        // 7. Return the IRLambdaExpression node
        return new IRLambdaExpression(targetType, samName, samDesc, paramNames, paramTypes, bodyStmt, capturedNames, capturedTypes, lambdaName, isStatic);
    }

    private Map<String, String> buildTypeParameterMapping(String targetType) {
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
            Class<?> cls = forName(cleanOwner.replace('/', '.'));
            java.lang.reflect.TypeVariable<?>[] tps = cls.getTypeParameters();
            for (int i = 0; i < tps.length && i < rawArgs.size(); i++) {
                typeParamMap.put(tps[i].getName(), rawArgs.get(i));
            }
        } catch (Throwable ignored) {
        }

        return typeParamMap;
    }

    private String extractSAMGenericReturnType(String targetType, String samReturn) {
        if (targetType == null || !targetType.contains("<")) return null;
        Map<String, String> typeParamMap = buildTypeParameterMapping(targetType);
        if (typeParamMap.isEmpty()) return null;

        String cleanOwner = TypeChecker.cleanDescriptor(targetType);
        if (cleanOwner.startsWith("L") && cleanOwner.endsWith(";")) {
            cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
        }

        String[] sam = resolveSAM(targetType);
        String samMethodName = sam != null ? sam[0] : null;

        // 1. Check Ocean method generic return type registry
        Map<String, String> genericReturnMap = CompilerRegistry.globalMethodGenericReturnTypeRegistry.get(cleanOwner);

        if (genericReturnMap != null && samMethodName != null && genericReturnMap.containsKey(samMethodName)) {
            String declaredRet = genericReturnMap.get(samMethodName);
            if (declaredRet != null) {
                boolean isNullable = declaredRet.endsWith("?");
                String baseRet = isNullable ? declaredRet.substring(0, declaredRet.length() - 1) : declaredRet;
                if (typeParamMap.containsKey(baseRet)) {
                    String substituted = typeParamMap.get(baseRet);
                    return isNullable ? substituted + "?" : substituted;
                }
                return declaredRet;
            }
        }

        // 2. Check Java reflection for JDK interfaces
        try {
            Class<?> cls = forName(cleanOwner.replace('/', '.'));
            for (Method m : cls.getMethods()) {
                if (Modifier.isAbstract(m.getModifiers()) && !isObjectMethod(m)) {
                    if (samMethodName == null || m.getName().equals(samMethodName)) {
                        java.lang.reflect.Type genericRet = m.getGenericReturnType();
                        if (genericRet instanceof java.lang.reflect.TypeVariable<?> tv && typeParamMap.containsKey(tv.getName())) {
                            return getTypeDescriptor(typeParamMap.get(tv.getName()));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // Fallback for standard interfaces if not found
        int start = targetType.indexOf('<');
        int end = targetType.lastIndexOf('>');
        if (start >= 0 && end > start) {
            List<String> rawArgs = splitGenericArgs(targetType.substring(start + 1, end));
            if (!rawArgs.isEmpty()) {
                return getTypeDescriptor(rawArgs.getLast());
            }
        }

        return null;
    }

    private List<String> extractSAMGenericParamTypes(String targetType, String samDesc) {
        List<String> rawSamParams = getParameterDescriptors(samDesc);
        if (targetType == null || !targetType.contains("<")) {
            return rawSamParams;
        }

        Map<String, String> typeParamMap = buildTypeParameterMapping(targetType);
        if (typeParamMap.isEmpty()) {
            return rawSamParams;
        }

        String cleanOwner = TypeChecker.cleanDescriptor(targetType);
        if (cleanOwner.startsWith("L") && cleanOwner.endsWith(";")) {
            cleanOwner = cleanOwner.substring(1, cleanOwner.length() - 1);
        }

        String[] sam = resolveSAM(targetType);
        String samMethodName = sam != null ? sam[0] : null;

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
                    String mapped = typeParamMap.get(baseType);
                    resolved.add(getTypeDescriptor(isNullable ? mapped + "?" : mapped));
                } else if (pType != null) {
                    resolved.add(getTypeDescriptor(pType));
                }
            }
            if (resolved.size() == rawSamParams.size()) {
                return resolved;
            }
        }

        // 2. Check Java reflection for JDK interfaces
        try {
            Class<?> cls = forName(cleanOwner.replace('/', '.'));
            for (Method m : cls.getMethods()) {
                if (Modifier.isAbstract(m.getModifiers()) && !isObjectMethod(m)) {
                    if (samMethodName == null || m.getName().equals(samMethodName)) {
                        java.lang.reflect.Type[] genericParams = m.getGenericParameterTypes();
                        List<String> resolved = new ArrayList<>();
                        for (java.lang.reflect.Type gp : genericParams) {
                            if (gp instanceof java.lang.reflect.TypeVariable<?> tv && typeParamMap.containsKey(tv.getName())) {
                                resolved.add(getTypeDescriptor(typeParamMap.get(tv.getName())));
                            } else if (gp instanceof Class<?> c) {
                                resolved.add(Type.getDescriptor(c));
                            } else {
                                resolved.add(getTypeDescriptor(gp.getTypeName()));
                            }
                        }
                        if (resolved.size() == rawSamParams.size()) {
                            return resolved;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return rawSamParams;
    }

    private String resolveMethodRefDescriptor(String owner, String methodName, int expectedParamCount, List<String> samParamTypes) {
        if (owner != null) {
            if (TypeChecker.isClassType(owner)) owner = owner.substring(1, owner.length() - 1);
            else if (owner.endsWith(";")) owner = owner.substring(0, owner.length() - 1);
        }

        List<IRExpression> directArgs = new ArrayList<>();
        for (String pt : samParamTypes) {
            directArgs.add(new IRVariableAccess("dummy", pt, false, null, false));
        }

        try {
            List<String> argTypes = new ArrayList<>();
            for (IRExpression a : directArgs) argTypes.add(a.getTypeDescriptor());
            String direct = OverloadResolver.resolve(owner, methodName, argTypes);
            if (direct != null) return direct;
        } catch (Throwable ignored) {
        }

        List<String> candidates = new ArrayList<>();
        List<String> hierarchy = OverloadResolver.getClassHierarchy(owner, methodName);
        for (String cls : hierarchy) {
            Map<String, List<String>> overloads = CompilerRegistry.globalOverloadRegistry.get(cls);
            if (overloads != null && overloads.containsKey(methodName)) {
                for (String d : overloads.get(methodName)) {
                    if (getParameterDescriptors(d).size() == expectedParamCount) {
                        candidates.add(d);
                    }
                }
            }
            Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(cls);
            if (methods != null && methods.containsKey(methodName)) {
                String d = methods.get(methodName);
                if (getParameterDescriptors(d).size() == expectedParamCount && !candidates.contains(d)) {
                    candidates.add(d);
                }
            }
            try {
                Class<?> clazz = forName(cls.replace("/", "."));
                for (Method m : clazz.getMethods()) {
                    if (m.getName().equals(methodName)) {
                        String d = Type.getMethodDescriptor(m);
                        if (getParameterDescriptors(d).size() == expectedParamCount && !candidates.contains(d)) {
                            candidates.add(d);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (!candidates.isEmpty()) break;
        }

        if (!candidates.isEmpty()) {
            return candidates.getFirst();
        }

        return resolveMethodDescriptor(owner, methodName, directArgs);
    }

    private IRStatement wrapAsyncMethodBody(IRStatement originalBody, String originalReturnType, boolean isStaticMethod, ParserRuleContext block) {
        if (originalBody == null) return null;

        boolean isVoid = "V".equals(originalReturnType);
        String targetInterface = isVoid ? "Ljava/lang/Runnable;" : "Ljava/util/function/Supplier;";
        String samName = isVoid ? "run" : "get";
        String samDesc = isVoid ? "()V" : "()Ljava/lang/Object;";
        if (originalReturnType != null && originalReturnType.endsWith("?")) samDesc += "?";
        String samReturn = samDesc.substring(samDesc.lastIndexOf(')') + 1);

        Set<String> used = new LinkedHashSet<>();
        findUsedVariablesRecursive(block, used);
        List<String> capturedNames = new ArrayList<>();
        List<String> capturedTypes = new ArrayList<>();
        if (!isStaticMethod && currentClassName != null) {
            capturedNames.add("this");
            capturedTypes.add("L" + currentClassName.replace(".", "/") + ";");
        } else if (isStaticMethod && symbolTable.getType("this") != null && used.contains("this")) {
            capturedNames.add("this");
            capturedTypes.add(symbolTable.getType("this"));
        }
        for (String name : used) {
            if (name.equals("this") || name.equals("super")) continue;
            String type = symbolTable.getType(name);
            if (type != null) {
                capturedNames.add(name);
                capturedTypes.add(type);
            }
        }

        String simpleClassName = "Global";
        if (currentClassName != null) {
            String tmp = currentClassName;
            int lastDot = tmp.lastIndexOf('.');
            int lastSlash = tmp.lastIndexOf('/');
            int lastSep = Math.max(lastDot, lastSlash);
            simpleClassName = lastSep >= 0 ? tmp.substring(lastSep + 1) : tmp;
            simpleClassName = SANITIZE_NAME_PATTERN.matcher(simpleClassName).replaceAll("_");
        }
        String lambdaName = "lambda$" + simpleClassName + "$async$" + (lambdaCounter++);
        boolean isStatic = true;

        List<String> paramNames = new ArrayList<>();
        List<String> paramTypes = new ArrayList<>();
        for (int i = 0; i < capturedNames.size(); i++) {
            paramNames.add(capturedNames.get(i));
            paramTypes.add(capturedTypes.get(i));
        }

        StringBuilder implDesc = new StringBuilder("(");
        for (String pt : paramTypes) {
            implDesc.append(TypeChecker.cleanDescriptor(pt));
        }
        implDesc.append(")").append(samReturn);

        List<IRMethod.IRParameter> irParams = new ArrayList<>();
        for (int i = 0; i < paramNames.size(); i++) {
            irParams.add(new IRMethod.IRParameter(paramNames.get(i), paramTypes.get(i)));
        }

        IRMethod lambdaMethod = new IRMethod(lambdaName, implDesc.toString(), isStatic, false, originalBody);
        for (IRMethod.IRParameter ip : irParams) {
            lambdaMethod.addParameter(ip);
        }

        syntheticLambdaMethods.add(lambdaMethod);

        IRLambdaExpression lambdaExpr = new IRLambdaExpression(targetInterface, samName, samDesc, paramNames, paramTypes, originalBody, capturedNames, capturedTypes, lambdaName, isStatic);

        String methodName = isVoid ? "runAsync" : "supplyAsync";
        String methodDesc = isVoid ? "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;" : "(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;";

        List<IRExpression> asyncArgs = new ArrayList<>();
        asyncArgs.add(lambdaExpr);
        IRMethodCall asyncCall = new IRMethodCall("ocean/stdlib/RuntimeUtils", methodName, methodDesc, asyncArgs, true);

        return new IRReturnStatement(asyncCall);
    }

    @Override
    public IRNode visitAwaitExpr(OceanParser.AwaitExprContext ctx) {
        IRExpression expr = ensureExpr(visit(ctx.expression()));
        String typeDesc = inferRawType(ctx);
        if (typeDesc != null && typeDesc.contains("<") && typeDesc.contains(">")) {
            int ltIdx = typeDesc.indexOf('<');
            int gtIdx = typeDesc.lastIndexOf('>');
            typeDesc = typeDesc.substring(ltIdx + 1, gtIdx);
        } else if (expr.getTypeDescriptor() != null && expr.getTypeDescriptor().contains("<") && expr.getTypeDescriptor().contains(">")) {
            String exprType = expr.getTypeDescriptor();
            int ltIdx = exprType.indexOf('<');
            int gtIdx = exprType.lastIndexOf('>');
            typeDesc = exprType.substring(ltIdx + 1, gtIdx);
        } else if (TypeChecker.isFutureType(typeDesc)) {
            typeDesc = OceanTypeSystem.OBJECT_DESC;
        }
        return new IRAwaitExpression(expr, typeDesc);
    }

    @Override
    public IRNode visitMethodRefExpr(OceanParser.MethodRefExprContext ctx) {
        String targetType = currentCastType;
        if (targetType == null) {
            if (ctx.expression() instanceof OceanParser.CastExprContext) {
                targetType = resolveTypeName(((OceanParser.CastExprContext) ctx.expression()).type().getText());
            }
        }
        if (targetType == null) {
            ParseTree parent = ctx.getParent();
            while ((parent instanceof OceanParser.ParenthesizedPrimaryContext || parent instanceof OceanParser.ExpressionContext)) {
                if (parent instanceof OceanParser.CastExprContext) {
                    targetType = resolveTypeName(((OceanParser.CastExprContext) parent).type().getText());
                    break;
                }
                parent = parent.getParent();
            }
        }

        IRExpression receiver = ensureExpr(visit(ctx.expression()));
        String receiverType = receiver.getTypeDescriptor();
        String owner = receiverType != null && receiverType.startsWith("L") && receiverType.endsWith(";")
                ? receiverType.substring(1, receiverType.length() - 1)
                : (receiverType != null ? receiverType : "java/lang/Object");

        String methodName = (ctx.anyId() != null) ? ctx.anyId().getText() : (ctx.NEW() != null ? "new" : "unknown");

        boolean isThisOrSuper = receiver instanceof IRVariableAccess va && (va.getName().equals("this") || va.getName().equals("super"));

        boolean isClassRef = !isThisOrSuper && (
            (receiver instanceof IRVariableAccess && ((IRVariableAccess) receiver).isClassReference()) ||
            (receiver instanceof IRLiteral lit && isActuallyKnownType(String.valueOf(lit.getValue()))) ||
            isActuallyKnownType(ctx.expression().getText()) ||
            methodName.equals("new") || methodName.equals("<init>")
        );
        if (!isClassRef && !isThisOrSuper && receiver instanceof IRVariableAccess va) {
            String name = va.getName();
            if (symbolTable.getType(name) == null && symbolTable.getIndex(name) == -1) {
                isClassRef = true;
                String resName = resolveTypeName(name);
                if (resName != null) {
                    if (resName.startsWith("L") && resName.endsWith(";")) resName = resName.substring(1, resName.length() - 1);
                    owner = resName;
                }
            }
        } else if (isClassRef) {
            String exprText = ctx.expression().getText();
            String resName = resolveTypeName(exprText);
            if (resName != null) {
                if (resName.startsWith("L") && resName.endsWith(";")) resName = resName.substring(1, resName.length() - 1);
                owner = resName;
            }
        }

        String[] sam = resolveSAM(targetType);
        if (sam == null) {
            if (methodName.equals("new") || methodName.equals("<init>")) {
                int ctorParamCount = 0;
                Map<String, List<String>> overloads = CompilerRegistry.globalOverloadRegistry.get(owner);
                if (overloads != null && overloads.containsKey("<init>") && !overloads.get("<init>").isEmpty()) {
                    ctorParamCount = getParameterDescriptors(overloads.get("<init>").getFirst()).size();
                } else {
                    Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(owner);
                    if (methods != null && methods.containsKey("<init>")) {
                        ctorParamCount = getParameterDescriptors(methods.get("<init>")).size();
                    } else {
                        try {
                            Class<?> clazz = forName(owner.replace("/", "."));
                            Constructor<?>[] ctors = clazz.getConstructors();
                            if (ctors.length > 0) {
                                ctorParamCount = ctors[0].getParameterCount();
                            }
                        } catch (Throwable ignored) {}
                    }
                }
                if (ctorParamCount == 1) {
                    targetType = "Ljava/util/function/Function;";
                    sam = new String[]{"apply", "(Ljava/lang/Object;)Ljava/lang/Object;"};
                } else if (ctorParamCount == 2) {
                    targetType = "Ljava/util/function/BiFunction;";
                    sam = new String[]{"apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"};
                } else if (ctorParamCount == 0) {
                    targetType = "Ljava/util/function/Supplier;";
                    sam = new String[]{"get", "()Ljava/lang/Object;"};
                } else {
                    sam = new String[]{"run", "()V"};
                }
            } else {
                sam = new String[]{"run", "()V"};
            }
        }
        String samName = sam[0];
        String samDesc = sam[1];
        List<String> samParamTypes = extractSAMGenericParamTypes(targetType, samDesc);

        List<String> paramNames = new ArrayList<>();
        List<String> paramTypes = new ArrayList<>();
        List<String> capturedNames = new ArrayList<>();
        List<String> capturedTypes = new ArrayList<>();

        IRStatement bodyStmt;

        if (isClassRef) {
            // Case 1: Class target (Constructor or Static or Unbound method reference)
            for (int i = 0; i < samParamTypes.size(); i++) {
                paramNames.add("arg" + i);
                paramTypes.add(samParamTypes.get(i));
            }

            if (methodName.equals("new") || methodName.equals("<init>")) {
                // Constructor reference: ClassName::new
                String constructorDesc = resolveMethodRefDescriptor(owner, "<init>", samParamTypes.size(), samParamTypes);
                List<String> targetParamTypes = getParameterDescriptors(constructorDesc);
                List<IRExpression> callArgs = new ArrayList<>();
                for (int i = 0; i < samParamTypes.size(); i++) {
                    String samP = samParamTypes.get(i);
                    String targetP = i < targetParamTypes.size() ? targetParamTypes.get(i) : samP;
                    IRExpression argAccess = new IRVariableAccess("arg" + i, samP, false, null, false);
                    if (!samP.equals(targetP)) {
                        callArgs.add(new IRCastExpression(argAccess, targetP));
                    } else {
                        callArgs.add(argAccess);
                    }
                }
                packVarargsIfNecessary(owner, "<init>", constructorDesc, callArgs);
                IRNewObject ctorCall = new IRNewObject(owner, constructorDesc, callArgs);
                bodyStmt = new IRReturnStatement(ctorCall);
            } else {
                Map<String, Boolean> statics = CompilerRegistry.globalMethodStaticity.get(owner);
                boolean isStatic = (statics != null && statics.getOrDefault(methodName, false));
                if (statics == null || !statics.containsKey(methodName)) {
                    try {
                        Class<?> clazz = forName(owner.replace("/", "."));
                        for (Method m : clazz.getMethods()) {
                            if (m.getName().equals(methodName)) {
                                isStatic = Modifier.isStatic(m.getModifiers());
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (isStatic) {
                    // Static method reference: ClassName::staticMethod
                    String methodDesc = resolveMethodRefDescriptor(owner, methodName, samParamTypes.size(), samParamTypes);
                    List<String> targetParamTypes = getParameterDescriptors(methodDesc);
                    List<IRExpression> callArgs = new ArrayList<>();
                    for (int i = 0; i < samParamTypes.size(); i++) {
                        String samP = samParamTypes.get(i);
                        String targetP = i < targetParamTypes.size() ? targetParamTypes.get(i) : samP;
                        IRExpression argAccess = new IRVariableAccess("arg" + i, samP, false, null, false);
                        if (!samP.equals(targetP)) {
                            callArgs.add(new IRCastExpression(argAccess, targetP));
                        } else {
                            callArgs.add(argAccess);
                        }
                    }
                    IRMethodCall methodCall = new IRMethodCall(owner, methodName, methodDesc, callArgs, true);
                    if (samDesc.endsWith("V")) {
                        bodyStmt = new IRBlock(Arrays.asList(new IRExprStatement(methodCall), new IRReturnStatement(null)));
                    } else {
                        bodyStmt = new IRReturnStatement(methodCall);
                    }
                } else {
                    // Unbound instance method reference: ClassName::instanceMethod
                    // First parameter of SAM is the receiver (arg0)
                    if (samParamTypes.isEmpty()) {
                        bodyStmt = new IRReturnStatement(null);
                    } else {
                        String receiverParamType = samParamTypes.getFirst();
                        String methodOwner = TypeChecker.isClassType(receiverParamType) ? receiverParamType.substring(1, receiverParamType.length() - 1) : owner;
                        if (methodOwner.startsWith("L") && methodOwner.endsWith(";")) {
                            methodOwner = methodOwner.substring(1, methodOwner.length() - 1);
                        }
                        List<String> unboundParamTypes = new ArrayList<>();
                        for (int i = 1; i < samParamTypes.size(); i++) unboundParamTypes.add(samParamTypes.get(i));
                        String methodDesc = resolveMethodRefDescriptor(methodOwner, methodName, unboundParamTypes.size(), unboundParamTypes);
                        List<String> targetParamTypes = getParameterDescriptors(methodDesc);
                        List<IRExpression> callArgs = new ArrayList<>();
                        for (int i = 1; i < samParamTypes.size(); i++) {
                            String samP = samParamTypes.get(i);
                            int targetIndex = i - 1;
                            String targetP = targetIndex < targetParamTypes.size() ? targetParamTypes.get(targetIndex) : samP;
                            IRExpression argAccess = new IRVariableAccess("arg" + i, samP, false, null, false);
                            if (!samP.equals(targetP)) {
                                callArgs.add(new IRCastExpression(argAccess, targetP));
                            } else {
                                callArgs.add(argAccess);
                            }
                        }
                        IRMethodCall methodCall = new IRMethodCall(methodOwner, methodName, methodDesc, callArgs, false);
                        methodCall.setReceiver(new IRVariableAccess("arg0", receiverParamType, false, null, false));
                        if (samDesc.endsWith("V")) {
                            bodyStmt = new IRBlock(Arrays.asList(new IRExprStatement(methodCall), new IRReturnStatement(null)));
                        } else {
                            bodyStmt = new IRReturnStatement(methodCall);
                        }
                    }
                }
            }
        } else {
            // Case 2: Bound instance method reference: expr::instanceMethod
            String receiverName = null;
            if (receiver instanceof IRVariableAccess va && !va.isField() && !va.isClassReference()) {
                receiverName = va.getName();
            }
            if (receiverName == null || isThisOrSuper) {
                receiverName = "this";
                receiverType = OceanTypeSystem.wrapObjectType(currentClassName);
                owner = currentClassName;
            }

            paramNames.add(receiverName);
            paramTypes.add(receiverType);

            for (int i = 0; i < samParamTypes.size(); i++) {
                paramNames.add("arg" + i);
                paramTypes.add(samParamTypes.get(i));
            }

            Map<String, Boolean> statics = CompilerRegistry.globalMethodStaticity.get(owner);
            boolean isStatic = (statics != null && statics.getOrDefault(methodName, false));

            String methodDesc = resolveMethodRefDescriptor(owner, methodName, samParamTypes.size(), samParamTypes);
            List<String> targetParamTypes = getParameterDescriptors(methodDesc);
            List<IRExpression> callArgs = new ArrayList<>();
            for (int i = 0; i < samParamTypes.size(); i++) {
                String samP = samParamTypes.get(i);
                String targetP = i < targetParamTypes.size() ? targetParamTypes.get(i) : samP;
                IRExpression argAccess = new IRVariableAccess("arg" + i, samP, false, null, false);
                if (!samP.equals(targetP)) {
                    callArgs.add(new IRCastExpression(argAccess, targetP));
                } else {
                    callArgs.add(argAccess);
                }
            }

            IRMethodCall methodCall = new IRMethodCall(owner, methodName, methodDesc, callArgs, isStatic);
            methodCall.setReceiver(new IRVariableAccess(receiverName, receiverType, false, null, false));

            if (samDesc.endsWith("V")) {
                bodyStmt = new IRBlock(Arrays.asList(new IRExprStatement(methodCall), new IRReturnStatement(null)));
            } else {
                bodyStmt = new IRReturnStatement(methodCall);
            }

            capturedNames.add(receiverName);
            capturedTypes.add(receiverType);
        }

        String simpleClassName2 = "Global";
        if (currentClassName != null) {
            String tmp = currentClassName;
            int lastDot = tmp.lastIndexOf('.');
            int lastSlash = tmp.lastIndexOf('/');
            int lastSep = Math.max(lastDot, lastSlash);
            simpleClassName2 = lastSep >= 0 ? tmp.substring(lastSep + 1) : tmp;
            simpleClassName2 = simpleClassName2.replaceAll("[^a-zA-Z0-9_$]", "_");
        }
        String lambdaName = "lambda$" + simpleClassName2 + "$method$" + (lambdaCounter++);
        boolean isLambdaStatic = true;

        StringBuilder implDesc = new StringBuilder("(");
        for (String pt : paramTypes) {
            implDesc.append(TypeChecker.cleanDescriptor(pt));
        }
        String samReturn = samDesc.substring(samDesc.lastIndexOf(')') + 1);
        implDesc.append(")").append(samReturn);

        List<IRMethod.IRParameter> irParams = new ArrayList<>();
        for (int i = 0; i < paramNames.size(); i++) {
            irParams.add(new IRMethod.IRParameter(paramNames.get(i), paramTypes.get(i)));
        }

        IRMethod lambdaMethod = new IRMethod(lambdaName, implDesc.toString(), isLambdaStatic, false, bodyStmt);
        for (IRMethod.IRParameter ip : irParams) {
            lambdaMethod.addParameter(ip);
        }

        syntheticLambdaMethods.add(lambdaMethod);

        return new IRLambdaExpression(targetType, samName, samDesc, paramNames, paramTypes, bodyStmt, capturedNames, capturedTypes, lambdaName, isLambdaStatic);
    }

    @Override
    public IRNode visitArrayMethodRefExpr(OceanParser.ArrayMethodRefExprContext ctx) {
        String targetType = currentCastType;
        if (targetType == null) {
            ParseTree parent = ctx.getParent();
            while ((parent instanceof OceanParser.ParenthesizedPrimaryContext || parent instanceof OceanParser.ExpressionContext)) {
                if (parent instanceof OceanParser.CastExprContext) {
                    targetType = resolveTypeName(((OceanParser.CastExprContext) parent).type().getText());
                    break;
                }
                parent = parent.getParent();
            }
        }

        String elemType;
        if (ctx.primitiveType() != null) {
            elemType = getTypeDescriptor(ctx.primitiveType().getText());
        } else {
            String base = ctx.typeName().getText();
            elemType = getTypeDescriptor(base);
        }

        int dims = ctx.LBRACK().size();
        String arrayTypeDesc = "[".repeat(dims) + elemType;

        if (targetType == null || resolveSAM(targetType) == null) {
            targetType = "Ljava/util/function/IntFunction<" + arrayTypeDesc + ">;";
        }

        String[] sam = resolveSAM(targetType);
        if (sam == null) {
            sam = new String[]{"apply", "(I)Ljava/lang/Object;"};
        }
        String samName = sam[0];
        String samDesc = sam[1];

        List<String> paramNames = new ArrayList<>();
        List<String> paramTypes = new ArrayList<>();
        paramNames.add("size");
        paramTypes.add("I");

        List<IRExpression> dimSizes = List.of(new IRVariableAccess("size", "I", false, null, false));
        IRArrayCreation arrayCreation = new IRArrayCreation(elemType, dimSizes);
        IRStatement bodyStmt = new IRReturnStatement(arrayCreation);

        String simpleClassName = "Global";
        if (currentClassName != null) {
            String tmp = currentClassName;
            int lastDot = tmp.lastIndexOf('.');
            int lastSlash = tmp.lastIndexOf('/');
            int lastSep = Math.max(lastDot, lastSlash);
            simpleClassName = lastSep >= 0 ? tmp.substring(lastSep + 1) : tmp;
            simpleClassName = simpleClassName.replaceAll("[^a-zA-Z0-9_$]", "_");
        }
        String lambdaName = "lambda$" + simpleClassName + "$array$" + (lambdaCounter++);
        boolean isLambdaStatic = true;

        String samReturn = samDesc.substring(samDesc.lastIndexOf(')') + 1);
        String implDesc = "(I)" + samReturn;

        IRMethod lambdaMethod = new IRMethod(lambdaName, implDesc, isLambdaStatic, false, bodyStmt);
        lambdaMethod.addParameter(new IRMethod.IRParameter("size", "I"));
        syntheticLambdaMethods.add(lambdaMethod);

        return new IRLambdaExpression(targetType, samName, samDesc, paramNames, paramTypes, bodyStmt, Collections.emptyList(), Collections.emptyList(), lambdaName, isLambdaStatic);
    }

    private String[] resolveSAM(String descriptor) {
        descriptor = TypeChecker.cleanDescriptor(descriptor);
        descriptor = ensureDescriptor(descriptor);
        if (descriptor == null || !descriptor.startsWith("L")) return null;
        String internalName = descriptor.substring(1, descriptor.length() - 1);

        switch (internalName) {
            case "java/lang/Runnable" -> {
                return new String[]{"run", "()V"};
            }
            case "java/util/function/Supplier" -> {
                return new String[]{"get", "()Ljava/lang/Object;"};
            }
            case "java/util/function/Consumer" -> {
                return new String[]{"accept", "(Ljava/lang/Object;)V"};
            }
            case "java/util/function/Function", "java/util/function/UnaryOperator" -> {
                return new String[]{"apply", "(Ljava/lang/Object;)Ljava/lang/Object;"};
            }
            case "java/util/function/BiConsumer" -> {
                return new String[]{"accept", "(Ljava/lang/Object;Ljava/lang/Object;)V"};
            }
            case "java/util/function/BiFunction", "java/util/function/BinaryOperator" -> {
                return new String[]{"apply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"};
            }
            case "java/util/function/Predicate" -> {
                return new String[]{"test", "(Ljava/lang/Object;)Z"};
            }
        }

        String resolvedInternal = internalName;
        OceanTypeSystem.SAMMethodInfo samInfo = OceanTypeSystem.getSingleAbstractMethodInfo(resolvedInternal);
        if (samInfo == null) {
            String simpleName = OceanTypeSystem.findSimpleName(resolvedInternal);
            for (String iface : CompilerRegistry.globalIsInterfaceSet) {
                if (iface.endsWith("/" + simpleName) || iface.endsWith("$" + simpleName) || iface.equals(simpleName)) {
                    samInfo = OceanTypeSystem.getSingleAbstractMethodInfo(iface);
                    if (samInfo != null) break;
                }
            }
        }
        if (samInfo != null) {
            return new String[]{samInfo.name(), samInfo.descriptor()};
        }
        return null;
    }

    private boolean isObjectMethod(Method m) {
        String name = m.getName();
        Class<?>[] params = m.getParameterTypes();
        if (name.equals("toString") && params.length == 0) return true;
        if (name.equals("hashCode") && params.length == 0) return true;
        return name.equals("equals") && params.length == 1 && params[0] == Object.class;
    }

    private List<String> getParameterDescriptors(String methodDesc) {
        if (methodDesc == null) return Collections.emptyList();
        String cleanMethodDesc = TypeChecker.cleanDescriptor(methodDesc);
        return paramDescriptorsCache.computeIfAbsent(cleanMethodDesc, desc -> {
            List<String> params = new ArrayList<>();
            int i = desc.indexOf('(') + 1;
            int end = desc.indexOf(')');
            if (i <= 0 || end < 0) return params;
            while (i < end) {
                int start = i;
                while (desc.charAt(i) == '[') i++;
                if (desc.charAt(i) == 'L') {
                    i = desc.indexOf(';', i) + 1;
                } else {
                    i++;
                }
                params.add(desc.substring(start, i));
            }
            return Collections.unmodifiableList(params);
        });
    }

    private boolean statementExitsFlow(OceanParser.StatementContext stmt) {
        switch (stmt) {
            case null -> {
                return false;
            }
            case OceanParser.ReturnStmtContext returnStmtContext -> {
                return true;
            }
            case OceanParser.ThrowStmtContext throwStmtContext -> {
                return true;
            }
            case OceanParser.StopStmtContext stopStmtContext -> {
                return true;
            }
            case OceanParser.SkipStmtContext skipStmtContext -> {
                return true;
            }
            case OceanParser.BlockStmtContext blockStmtContext -> {
                return blockExitsFlow(blockStmtContext.block());
            }
            case OceanParser.IfStmtContext ifStmtContext -> {
                OceanParser.IfStatementContext ifCtx = ifStmtContext.ifStatement();
                if (ifCtx.statement().size() == 2) {
                    return statementExitsFlow(ifCtx.statement(0)) && statementExitsFlow(ifCtx.statement(1));
                }
                return false;
            }
            case OceanParser.TryStmtContext tryStmtContext -> {
                OceanParser.TryStatementContext tryCtx = tryStmtContext.tryStatement();
                boolean tryExits = tryCtx.block(0) != null && blockExitsFlow(tryCtx.block(0));
                boolean allCatchesExit = true;
                if (tryCtx.catchClause() != null) {
                    for (OceanParser.CatchClauseContext catchClause : tryCtx.catchClause()) {
                        if (catchClause.block() == null || !blockExitsFlow(catchClause.block())) {
                            allCatchesExit = false;
                            break;
                        }
                    }
                }
                boolean finallyExits = false;
                if (tryCtx.FINALLY() != null && tryCtx.block().size() > 1) {
                    finallyExits = blockExitsFlow(tryCtx.block(1));
                }
                if (finallyExits) return true;
                return tryExits && allCatchesExit;
            }
            case OceanParser.SwitchStmtContext switchStmtContext -> {
                OceanParser.SwitchStatementContext switchCtx = switchStmtContext.switchStatement();
                boolean hasDefault = false;
                boolean allCasesExit = true;
                if (switchCtx.switchCase() != null) {
                    for (OceanParser.SwitchCaseContext sc : switchCtx.switchCase()) {
                        boolean caseExits = false;
                        if (sc.block() != null) {
                            caseExits = blockExitsFlow(sc.block());
                        } else if (sc.statement() != null && !sc.statement().isEmpty()) {
                            for (OceanParser.StatementContext scStmt : sc.statement()) {
                                if (statementExitsFlow(scStmt)) {
                                    caseExits = true;
                                    break;
                                }
                            }
                        }
                        if (!caseExits) {
                            allCasesExit = false;
                        }
                    }
                }
                if (switchCtx.defaultCase() != null) {
                    hasDefault = true;
                    OceanParser.DefaultCaseContext dc = switchCtx.defaultCase();
                    boolean defaultExits = false;
                    if (dc.block() != null) {
                        defaultExits = blockExitsFlow(dc.block());
                    } else if (dc.statement() != null && !dc.statement().isEmpty()) {
                        for (OceanParser.StatementContext dcStmt : dc.statement()) {
                            if (statementExitsFlow(dcStmt)) {
                                defaultExits = true;
                                break;
                            }
                        }
                    }
                    if (!defaultExits) {
                        allCasesExit = false;
                    }
                }
                return hasDefault && allCasesExit;
            }
            case OceanParser.LockStmtContext lockStmt -> {
                if (lockStmt.lockBlockStatement() != null && lockStmt.lockBlockStatement().block() != null) {
                    return blockExitsFlow(lockStmt.lockBlockStatement().block());
                }
            }
            default -> {
            }
        }
        return false;
    }

    private boolean blockExitsFlow(OceanParser.BlockContext block) {
        if (block == null) return false;
        for (OceanParser.StatementContext stmt : block.statement()) {
            if (statementExitsFlow(stmt)) return true;
        }
        return false;
    }

    private boolean statementAlwaysReturns(OceanParser.StatementContext stmt) {
        switch (stmt) {
            case null -> {
                return false;
            }
            case OceanParser.ReturnStmtContext returnStmtContext -> {
                return true;
            }
            case OceanParser.ThrowStmtContext throwStmtContext -> {
                return true;
            }
            case OceanParser.BlockStmtContext blockStmtContext -> {
                return blockHasReturn(blockStmtContext.block());
            }
            case OceanParser.IfStmtContext ifStmtContext -> {
                OceanParser.IfStatementContext ifCtx = ifStmtContext.ifStatement();
                if (ifCtx.statement().size() == 2) {
                    return statementAlwaysReturns(ifCtx.statement(0)) && statementAlwaysReturns(ifCtx.statement(1));
                }
                return false;
            }
            case OceanParser.TryStmtContext tryStmtContext -> {
                OceanParser.TryStatementContext tryCtx = tryStmtContext.tryStatement();
                boolean tryReturns = tryCtx.block(0) != null && blockHasReturn(tryCtx.block(0));
                boolean allCatchesReturn = true;
                if (tryCtx.catchClause() != null) {
                    for (OceanParser.CatchClauseContext catchClause : tryCtx.catchClause()) {
                        if (catchClause.block() == null || !blockHasReturn(catchClause.block())) {
                            allCatchesReturn = false;
                            break;
                        }
                    }
                }
                boolean finallyReturns = false;
                if (tryCtx.FINALLY() != null && tryCtx.block().size() > 1) {
                    finallyReturns = blockHasReturn(tryCtx.block(1));
                }
                if (finallyReturns) return true;
                return tryReturns && allCatchesReturn;
            }
            case OceanParser.SwitchStmtContext switchStmtContext -> {
                OceanParser.SwitchStatementContext switchCtx = switchStmtContext.switchStatement();
                boolean hasDefault = false;
                boolean allCasesReturn = true;
                if (switchCtx.switchCase() != null) {
                    for (OceanParser.SwitchCaseContext sc : switchCtx.switchCase()) {
                        boolean caseReturns = false;
                        if (sc.block() != null) {
                            caseReturns = blockHasReturn(sc.block());
                        } else if (sc.statement() != null && !sc.statement().isEmpty()) {
                            for (OceanParser.StatementContext scStmt : sc.statement()) {
                                if (statementAlwaysReturns(scStmt)) {
                                    caseReturns = true;
                                    break;
                                }
                            }
                        }
                        if (!caseReturns) {
                            allCasesReturn = false;
                        }
                    }
                }
                if (switchCtx.defaultCase() != null) {
                    hasDefault = true;
                    OceanParser.DefaultCaseContext dc = switchCtx.defaultCase();
                    boolean defaultReturns = false;
                    if (dc.block() != null) {
                        defaultReturns = blockHasReturn(dc.block());
                    } else if (dc.statement() != null && !dc.statement().isEmpty()) {
                        for (OceanParser.StatementContext dcStmt : dc.statement()) {
                            if (statementAlwaysReturns(dcStmt)) {
                                defaultReturns = true;
                                break;
                            }
                        }
                    }
                    if (!defaultReturns) {
                        allCasesReturn = false;
                    }
                }
                return hasDefault && allCasesReturn;
            }
            case OceanParser.LockStmtContext lockStmt -> {
                if (lockStmt.lockBlockStatement() != null && lockStmt.lockBlockStatement().block() != null) {
                    return blockHasReturn(lockStmt.lockBlockStatement().block());
                }
            }
            default -> {
            }
        }
        return false;
    }

    private boolean blockHasReturn(OceanParser.BlockContext block) {
        if (block == null) return false;
        for (OceanParser.StatementContext stmt : block.statement()) {
            if (statementAlwaysReturns(stmt)) return true;
        }
        return false;
    }

    private void findUsedVariablesRecursive(ParseTree tree, Set<String> used) {
        switch (tree) {
            case null -> {
                return;
            }
            case OceanParser.IdPrimaryContext idx -> used.add(idx.anyId().getText());
            case OceanParser.ThisRefPrimaryContext thisRefPrimaryContext -> used.add("this");
            case OceanParser.SuperRefPrimaryContext superRefPrimaryContext -> used.add("super");
            case OceanParser.InterpolatedStringPrimaryContext is -> {
                boolean isMultiline = is.MULTILINE_INTERPOLATED_STRING() != null;
                String text = isMultiline ? is.MULTILINE_INTERPOLATED_STRING().getText() : is.INTERPOLATED_STRING().getText();
                String content = isMultiline ? text.substring(4, text.length() - 3) : text.substring(2, text.length() - 1);
                int i = 0;
                while (i < content.length()) {
                    int braceStart = findUnescapedStartBrace(content, i);
                    if (braceStart == -1) {
                        break;
                    }
                    int braceEnd = findMatchingBrace(content, braceStart);
                    if (braceEnd == -1) break;
                    String exprText = content.substring(braceStart + 1, braceEnd);
                    try {
                        OceanLexer lexer = new OceanLexer(CharStreams.fromString(exprText));
                        lexer.removeErrorListeners();
                        OceanParser parser = new OceanParser(OceanTokenStreamFactory.createTokenStream(lexer));
                        parser.removeErrorListeners();
                        OceanParser.ExpressionContext exprCtx = parser.expression();
                        if (exprCtx != null) {
                            findUsedVariablesRecursive(exprCtx, used);
                        }
                    } catch (Exception ignored) {
                    }
                    i = braceEnd + 1;
                }
            }
            default -> {
            }
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            findUsedVariablesRecursive(tree.getChild(i), used);
        }
    }

    private String getExpectedParamType(String owner, String name, int argIndex, int totalArgs) {
        return getExpectedParamType(owner, name, argIndex, totalArgs, null);
    }

    private String getExpectedParamType(String owner, String name, int argIndex, int totalArgs, String genericOwner) {
        String current = owner;
        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current)) {
            Map<String, List<String>> overloadsMap = CompilerRegistry.globalOverloadRegistry.get(current);
            if (overloadsMap != null && overloadsMap.containsKey(name)) {
                List<String> foundTypes = new ArrayList<>();
                for (String desc : overloadsMap.get(name)) {
                    List<String> paramTypes = getParameterDescriptors(desc);
                    boolean isVarargs = !paramTypes.isEmpty() && paramTypes.getLast().startsWith("[");
                    if (isVarargs) {
                        int normalParamCount = paramTypes.size() - 1;
                        if (argIndex < normalParamCount) {
                            foundTypes.add(paramTypes.get(argIndex));
                        } else {
                            foundTypes.add(paramTypes.get(normalParamCount).substring(1));
                        }
                    } else if (paramTypes.size() == totalArgs && argIndex < paramTypes.size()) {
                        foundTypes.add(paramTypes.get(argIndex));
                    }
                }
                if (!foundTypes.isEmpty()) {
                    String first = foundTypes.getFirst();
                    for (String t : foundTypes) {
                        if (!t.equals(first)) return null;
                    }
                    return first;
                }
            }
            Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(current);
            if (methods != null && methods.containsKey(name)) {
                String desc = methods.get(name);
                List<String> paramTypes = getParameterDescriptors(desc);
                boolean isVarargs = !paramTypes.isEmpty() && paramTypes.getLast().startsWith("[");
                if (isVarargs) {
                    int normalParamCount = paramTypes.size() - 1;
                    if (argIndex < normalParamCount) {
                        return paramTypes.get(argIndex);
                    } else {
                        return paramTypes.get(normalParamCount).substring(1);
                    }
                } else if (paramTypes.size() == totalArgs && argIndex < paramTypes.size()) {
                    return paramTypes.get(argIndex);
                }
            }
            current = CompilerRegistry.globalSuperClassRegistry.get(current);
        }

        String ownerDesc = null;
        if (owner != null) {
            ownerDesc = OceanTypeSystem.wrapObjectType(owner.replace(".", "/"));
        }
        List<CompilerRegistry.ExtensionMethodInfo> extCandidates = CompilerRegistry.findExtensionMethods(ownerDesc, name);
        if (!extCandidates.isEmpty()) {
            for (CompilerRegistry.ExtensionMethodInfo emi : extCandidates) {
                List<String> paramTypes = getParameterDescriptors(emi.descriptor());
                boolean isVarargs = !paramTypes.isEmpty() && paramTypes.getLast().startsWith("[");
                if (isVarargs) {
                    int normalParamCount = paramTypes.size() - 1;
                    if (argIndex + 1 < normalParamCount) {
                        return paramTypes.get(argIndex + 1);
                    } else {
                        return paramTypes.get(normalParamCount).substring(1);
                    }
                } else if (paramTypes.size() == totalArgs + 1 && (argIndex + 1) < paramTypes.size()) {
                    return paramTypes.get(argIndex + 1);
                }
            }
        }

        try {
            Class<?> clazz = null;
            if (owner != null) {
                clazz = forName(owner.replace("/", "."));
            }
            if (clazz == null) return null;

            Map<String, String> typeParamMap = new HashMap<>();
            if (genericOwner != null && genericOwner.contains("<")) {
                int lt = genericOwner.indexOf('<');
                int gt = genericOwner.lastIndexOf('>');
                if (lt >= 0 && gt > lt) {
                    String inner = genericOwner.substring(lt + 1, gt);
                    List<String> typeArgs = splitGenericArgs(inner);
                    TypeVariable<?>[] classParams = clazz.getTypeParameters();
                    for (int i = 0; i < classParams.length && i < typeArgs.size(); i++) {
                        typeParamMap.put(classParams[i].getName(), typeArgs.get(i));
                    }
                }
            }

            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name)) {
                    Class<?>[] paramTypes = m.getParameterTypes();
                    java.lang.reflect.Type[] genParamTypes = m.getGenericParameterTypes();
                    boolean isVarargs = m.isVarArgs();
                    if (isVarargs) {
                        int normalParamCount = paramTypes.length - 1;
                        if (argIndex < normalParamCount) {
                            java.lang.reflect.Type gp = genParamTypes[argIndex];
                            String resolved = resolveReflectedTypeDesc(gp, typeParamMap);
                            if (resolved != null && !OceanTypeSystem.OBJECT_DESC.equals(resolved)) return resolved;
                            return Type.getDescriptor(paramTypes[argIndex]);
                        } else {
                            return Type.getDescriptor(paramTypes[normalParamCount].getComponentType());
                        }
                    } else if (m.getParameterCount() == totalArgs && argIndex < m.getParameterCount()) {
                        java.lang.reflect.Type gp = genParamTypes[argIndex];
                        String resolved = resolveReflectedTypeDesc(gp, typeParamMap);
                        if (resolved != null && !OceanTypeSystem.OBJECT_DESC.equals(resolved)) return resolved;
                        return Type.getDescriptor(paramTypes[argIndex]);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Map<String, String> buildTypeParamMappingFromDesc(String internalName, String genericDesc) {
        if (genericDesc == null || !genericDesc.contains("<")) return Collections.emptyMap();
        List<String> args = TypeChecker.extractGenericArguments(genericDesc);
        if (args.isEmpty()) return Collections.emptyMap();

        String clean = internalName.replace('.', '/');
        List<CompilerRegistry.TypeParameterInfo> declTypeParams = CompilerRegistry.globalTypeParameterRegistry.get(clean);
        if (declTypeParams == null) {
            String simple = OceanTypeSystem.findSimpleName(clean);
            for (Map.Entry<String, List<CompilerRegistry.TypeParameterInfo>> entry : CompilerRegistry.globalTypeParameterRegistry.entrySet()) {
                if (entry.getKey().endsWith("/" + simple) || entry.getKey().endsWith("$" + simple) || entry.getKey().equals(simple)) {
                    declTypeParams = entry.getValue();
                    break;
                }
            }
        }
        if (declTypeParams != null && !declTypeParams.isEmpty()) {
            Map<String, String> map = new HashMap<>();
            for (int i = 0; i < declTypeParams.size() && i < args.size(); i++) {
                map.put(declTypeParams.get(i).name, args.get(i));
            }
            return map;
        }
        return Collections.emptyMap();
    }

    private String getExpectedCtorParamType(String internalName, int argIndex, int totalArgs) {
        Map<String, List<CompilerRegistry.MethodParamInfo>> classParams = CompilerRegistry.globalMethodParamsRegistry.get(internalName);
        if (classParams == null) {
            String simple = OceanTypeSystem.findSimpleName(internalName);
            for (Map.Entry<String, Map<String, List<CompilerRegistry.MethodParamInfo>>> entry : CompilerRegistry.globalMethodParamsRegistry.entrySet()) {
                if (entry.getKey().endsWith("/" + simple) || entry.getKey().endsWith("$" + simple) || entry.getKey().equals(simple)) {
                    classParams = entry.getValue();
                    break;
                }
            }
        }
        if (classParams != null) {
            List<CompilerRegistry.MethodParamInfo> pInfos = classParams.get("<init>#" + totalArgs);
            if (pInfos == null) {
                for (Map.Entry<String, List<CompilerRegistry.MethodParamInfo>> entry : classParams.entrySet()) {
                    if (entry.getKey().startsWith("<init>#")) {
                        try {
                            int count = Integer.parseInt(entry.getKey().substring(7));
                            if (count <= totalArgs && argIndex >= (totalArgs - count)) {
                                pInfos = entry.getValue();
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            if (pInfos == null) pInfos = classParams.get("<init>");
            if (pInfos != null) {
                int effectiveIndex = argIndex;
                if (effectiveIndex >= pInfos.size() && pInfos.size() < totalArgs) {
                    effectiveIndex = argIndex - (totalArgs - pInfos.size());
                }
                if (effectiveIndex >= 0 && effectiveIndex < pInfos.size()) {
                    String rawType = pInfos.get(effectiveIndex).rawType();
                    if (currentCastType != null && currentCastType.contains("<")) {
                        Map<String, String> mapping = buildTypeParamMappingFromDesc(internalName, currentCastType);
                        if (mapping.containsKey(rawType)) {
                            return ensureDescriptor(mapping.get(rawType));
                        }
                    }
                    return pInfos.get(effectiveIndex).typeDesc();
                }
            }
        }

        Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(internalName);
        if (methods != null && methods.containsKey("<init>")) {
            String desc = methods.get("<init>");
            List<String> paramTypes = getParameterDescriptors(desc);
            boolean isVarargs = !paramTypes.isEmpty() && paramTypes.getLast().startsWith("[");
            if (isVarargs) {
                int normalParamCount = paramTypes.size() - 1;
                if (argIndex < normalParamCount) {
                    return paramTypes.get(argIndex);
                } else {
                    return paramTypes.get(normalParamCount).substring(1);
                }
            } else if (paramTypes.size() == totalArgs && argIndex < paramTypes.size()) {
                return paramTypes.get(argIndex);
            }
        }
        try {
            Class<?> clazz = forName(internalName.replace("/", "."));
            for (Constructor<?> c : clazz.getConstructors()) {
                Class<?>[] paramTypes = c.getParameterTypes();
                boolean isVarargs = c.isVarArgs();
                if (isVarargs) {
                    int normalParamCount = paramTypes.length - 1;
                    if (argIndex < normalParamCount) {
                        return Type.getDescriptor(paramTypes[argIndex]);
                    } else {
                        return Type.getDescriptor(paramTypes[normalParamCount].getComponentType());
                    }
                } else if (c.getParameterCount() == totalArgs && argIndex < c.getParameterCount()) {
                    return Type.getDescriptor(paramTypes[argIndex]);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void packVarargsIfNecessary(String owner, String methodName, String desc, List<IRExpression> args) {
        if (desc == null || args == null) return;
        boolean isVarargs = CompilerRegistry.isVarargsMethod(owner, methodName, desc);
        if (isVarargs) {
            List<String> paramTypes = getParameterDescriptors(desc);
            int normalParamCount = paramTypes.size() - 1;
            String varargArrayType = paramTypes.get(normalParamCount);
            String varargElementType = varargArrayType.startsWith("[") ? varargArrayType.substring(1) : varargArrayType;

            if (args.size() == normalParamCount) {
                IRExpression emptyArray = new IRArrayCreation(varargElementType, List.of(new IRLiteral(0, "I")));
                args.add(emptyArray);
            } else if (args.size() >= paramTypes.size()) {
                IRExpression lastArg = args.get(normalParamCount);
                String lastArgType = lastArg.getTypeDescriptor();
                if (lastArgType == null && lastArg instanceof IRVariableAccess varAcc) {
                    if (symbolTable != null) {
                        lastArgType = symbolTable.getType(varAcc.getName());
                        if (lastArgType == null) {
                            lastArgType = symbolTable.getOriginalType(varAcc.getName());
                        }
                    }
                }
                if (lastArgType != null && lastArgType.endsWith("[]")) {
                    lastArgType = getTypeDescriptor(lastArgType);
                }
                boolean lastIsArray = args.size() == paramTypes.size() && lastArgType != null &&
                        (lastArgType.startsWith("[") || lastArgType.endsWith("[]")) &&
                        TypeChecker.isAssignable(varargArrayType, lastArgType, null);
                if (!lastIsArray) {
                    List<IRExpression> packed = new ArrayList<>();
                    for (int i = normalParamCount; i < args.size(); i++) {
                        packed.add(args.get(i));
                    }
                    while (args.size() > normalParamCount) {
                        args.removeLast();
                    }
                    IRArrayLiteral arrayLit = new IRArrayLiteral(packed, varargArrayType);
                    args.add(arrayLit);
                }
            }
        }
    }

    private String packDefaultArgumentsIfNecessary(String owner, String methodName, String desc, List<IRExpression> args) {
        if (owner == null || methodName == null) return desc;
        Map<String, List<CompilerRegistry.MethodParamInfo>> classMap = CompilerRegistry.globalMethodParamsRegistry.get(owner);
        if (classMap == null) return desc;
        List<CompilerRegistry.MethodParamInfo> paramInfos = classMap.get(methodName);
        if (paramInfos == null || paramInfos.isEmpty()) return desc;

        boolean addedDefaults = false;
        if (args.size() < paramInfos.size()) {
            for (int i = args.size(); i < paramInfos.size(); i++) {
                CompilerRegistry.MethodParamInfo pInfo = paramInfos.get(i);
                if (pInfo.defaultExpr() != null) {
                    IRExpression defaultVal = ensureExpr(visit(pInfo.defaultExpr()));
                    args.add(defaultVal);
                    addedDefaults = true;
                } else {
                    break;
                }
            }
        }

        if (addedDefaults) {
            StringBuilder methodDescList = new StringBuilder("(");
            for (CompilerRegistry.MethodParamInfo pInfo : paramInfos) {
                methodDescList.append(pInfo.typeDesc());
            }
            methodDescList.append(")");
            String returnDesc = desc.substring(desc.lastIndexOf(")") + 1);
            desc = methodDescList.append(returnDesc).toString();
        }

        return desc;
    }

    private IRStatement buildTryWithResources(List<OceanParser.ResourceContext> resources, int index, OceanParser.BlockContext bodyCtx) {
        if (index >= resources.size()) {
            return (IRStatement) visit(bodyCtx);
        }

        OceanParser.ResourceContext resCtx = resources.get(index);
        String varName = resCtx.anyId().getText();
        String boundName = varName;
        if (varName.equals("_")) {
            boundName = "$_unused_res_" + System.nanoTime();
        }

        boolean isExistingVar = (resCtx.expression() == null);
        IRExpression initVal = isExistingVar ? null : ensureExpr(visit(resCtx.expression()));

        String typeDesc;
        if (isExistingVar) {
            typeDesc = symbolTable.getType(varName);
            if (typeDesc == null) {
                typeDesc = OceanTypeSystem.OBJECT_DESC;
            }
        } else if (resCtx.type() != null && !resCtx.type().getText().equals("variable") && !resCtx.type().getText().equals("value") && !resCtx.type().getText().equals("var")) {
            typeDesc = getTypeDescriptor(resCtx.type().getText());
        } else if (initVal.getTypeDescriptor() != null) {
            typeDesc = initVal.getTypeDescriptor();
        } else {
            typeDesc = OceanTypeSystem.OBJECT_DESC;
        }

        String cleanType = TypeChecker.cleanDescriptor(typeDesc);
        if (cleanType.startsWith("L") && cleanType.endsWith(";")) {
            cleanType = cleanType.substring(1, cleanType.length() - 1);
        }

        CompilationSession activeSession = CompilationSession.getActiveSession();
        boolean isCloseable = cleanType.equals("java/lang/AutoCloseable")
                || cleanType.equals("java/io/Closeable")
                || ClassMetadataCache.isSubtype(cleanType, "java/lang/AutoCloseable")
                || ClassMetadataCache.isSubtype(cleanType, "java/io/Closeable")
                || (activeSession != null && (activeSession.isSubType("L" + cleanType + ";", "Ljava/lang/AutoCloseable;") || activeSession.isSubType("L" + cleanType + ";", "Ljava/io/Closeable;")));

        if (!isCloseable) {
            String typeDisplayName = resCtx.type() != null ? resCtx.type().getText() : cleanType;
            reportError(resCtx, "Resource type '" + typeDisplayName + "' must implement java.lang.AutoCloseable.");
            throw new CompilationException("Resource type '" + typeDisplayName + "' does not implement java.lang.AutoCloseable");
        }

        if (isExistingVar) {
            IRStatement innerBody = buildTryWithResources(resources, index + 1, bodyCtx);
            return getIrTryCatchStatement(boundName, typeDesc, innerBody);
        } else {
            symbolTable.enterScope();
            symbolTable.declareVariable(boundName, typeDesc, true);

            IRStatement innerBody = buildTryWithResources(resources, index + 1, bodyCtx);

            symbolTable.exitScope();

            IRVariableDecl decl = new IRVariableDecl(boundName, typeDesc, initVal, true);
            IRTryCatchStatement tryCatch = getIrTryCatchStatement(boundName, typeDesc, innerBody);

            return new IRBlock(Arrays.asList(decl, tryCatch));
        }
    }

    @NotNull
    private static IRTryCatchStatement getIrTryCatchStatement(String varName, String typeDesc, IRStatement innerBody) {
        IRVariableAccess varAccess = new IRVariableAccess(varName, typeDesc, false, null, false);
        IRLiteral nullLit = new IRLiteral(null, OceanTypeSystem.OBJECT_DESC);
        IRBinaryOp cond = new IRBinaryOp(varAccess, nullLit, IRBinaryOp.Op.NE, "Z");

        String ownerClass = TypeChecker.isClassType(typeDesc) ? typeDesc.substring(1, typeDesc.length() - 1) : "java/lang/AutoCloseable";
        IRMethodCall closeCall = new IRMethodCall(ownerClass, "close", "()V", Collections.emptyList(), false);
        IRVariableAccess varAccessForReceiver = new IRVariableAccess(varName, typeDesc, false, null, false);
        closeCall.setReceiver(varAccessForReceiver);
        IRExprStatement closeStmt = new IRExprStatement(closeCall);

        IRIfStatement ifStmt = new IRIfStatement(cond, closeStmt, null);

        return new IRTryCatchStatement(innerBody, Collections.emptyList(), ifStmt, Collections.singletonList(varName));
    }

    @Override
    public IRNode visitResultStmt(OceanParser.ResultStmtContext ctx) {
        IRExpression expr = ensureExpr(visit(ctx.expression()));
        return new IRResultStatement(expr);
    }

    @Override
    public IRNode visitSwitchExpr(OceanParser.SwitchExprContext ctx) {
        IRExpression expr = ensureExpr(visit(ctx.expression()));
        String swType = expr.getTypeDescriptor();
        if (swType == null && expr instanceof IRVariableAccess va) {
            swType = symbolTable.getType(va.getName());
        }
        if (swType != null) {
            String clean = TypeChecker.cleanDescriptor(swType);
            if (TypeChecker.isEnumType(swType) || !ClassMetadataCache.getEnumConstants(clean).isEmpty()) {
                activeEnumSwitchTypes.push(clean);
            } else {
                activeEnumSwitchTypes.push("");
            }
        } else {
            activeEnumSwitchTypes.push("");
        }

        try {
            List<IRSwitchCase> cases = new ArrayList<>();

            for (OceanParser.SwitchExpressionCaseContext caseCtx : ctx.switchExpressionCase()) {
                symbolTable.enterScope();
                try {
                    OceanParser.SwitchLabelContext labelCtx = caseCtx.switchLabel();
                    List<IRSwitchPattern> patterns = new ArrayList<>();
                    List<IRExpression> values = new ArrayList<>();
                    IRExpression guard = null;

                    if (labelCtx != null) {
                        for (OceanParser.SwitchPatternContext patCtx : labelCtx.switchPattern()) {
                            IRSwitchPattern pattern = parseSwitchPattern(patCtx, null);
                            patterns.add(pattern);
                            if (pattern.getKind() == IRSwitchPattern.Kind.NULL) {
                                values.add(new IRLiteral(null, OceanTypeSystem.OBJECT_DESC));
                            } else if (pattern.getExpression() != null) {
                                values.add(pattern.getExpression());
                            }
                        }
                        if (labelCtx.WHEN() != null && labelCtx.expression() != null) {
                            guard = ensureExpr(visit(labelCtx.expression()));
                        }
                    }

                    IRNode body;
                    if (caseCtx.block() != null) {
                        body = visit(caseCtx.block());
                    } else {
                        body = visit(caseCtx.expression());
                    }
                    cases.add(new IRSwitchCase(patterns, values, guard, body,true));
                } finally {
                    symbolTable.exitScope();
                }
            }

            IRNode defaultBody = null;
            if (ctx.defaultExpressionCase() != null) {
                OceanParser.DefaultExpressionCaseContext defaultCtx = ctx.defaultExpressionCase();
                if (defaultCtx.block() != null) {
                    defaultBody = visit(defaultCtx.block());
                } else {
                    defaultBody = visit(defaultCtx.expression());
                }
            }

            String typeDesc = getSwitchExprType(cases, defaultBody);
            return new IRSwitchExpression(expr, cases, defaultBody, typeDesc);
        } finally {
            activeEnumSwitchTypes.pop();
        }
    }

    private String getSwitchExprType(List<IRSwitchCase> cases, IRNode defaultBody) {
        List<String> types = new ArrayList<>();
        for (IRSwitchCase c : cases) {
            addBodyTypes(c.getBody(), types);
        }
        if (defaultBody != null) {
            addBodyTypes(defaultBody, types);
        }
        if (types.isEmpty()) return OceanTypeSystem.OBJECT_DESC;
        String common = types.getFirst();
        for (int i = 1; i < types.size(); i++) {
            common = TypeChecker.getCommonType(common, types.get(i));
        }
        return common;
    }

    private void addBodyTypes(IRNode node, List<String> types) {
        if (node instanceof IRExpression) {
            types.add(((IRExpression) node).getTypeDescriptor());
        } else if (node instanceof IRBlock) {
            findResultsInBlock((IRBlock) node, types);
        }
    }

    private void findResultsInBlock(IRBlock block, List<String> types) {
        for (IRStatement stmt : block.getStatements()) {
            if (stmt instanceof IRResultStatement) {
                types.add(((IRResultStatement) stmt).getExpression().getTypeDescriptor());
            } else if (stmt instanceof IRBlock) {
                findResultsInBlock((IRBlock) stmt, types);
            } else if (stmt instanceof IRIfStatement ifs) {
                if (ifs.getThenBranch() instanceof IRBlock) findResultsInBlock((IRBlock) ifs.getThenBranch(), types);
                else if (ifs.getThenBranch() instanceof IRResultStatement)
                    types.add(((IRResultStatement) ifs.getThenBranch()).getExpression().getTypeDescriptor());
                if (ifs.getElseBranch() instanceof IRBlock) findResultsInBlock((IRBlock) ifs.getElseBranch(), types);
                else if (ifs.getElseBranch() instanceof IRResultStatement)
                    types.add(((IRResultStatement) ifs.getElseBranch()).getExpression().getTypeDescriptor());
            }
        }
    }

    // ========== Static Import Helpers ==========

    private boolean classHasField(String owner, String fieldName) {
        Map<String, String> fields = CompilerRegistry.globalFieldRegistry.get(owner);
        if (fields != null && fields.containsKey(fieldName)) return true;
        String sup = CompilerRegistry.globalSuperClassRegistry.get(owner);
        if (sup != null && !sup.equals(owner) && classHasField(sup, fieldName)) return true;
        String[] ifaces = CompilerRegistry.globalInterfaceRegistry.get(owner);
        if (ifaces != null) {
            for (String iface : ifaces) {
                if (iface != null && classHasField(iface, fieldName)) return true;
            }
        }
        return false;
    }

    private boolean classHasMethod(String owner, String methodName) {
        Map<String, List<String>> overloads = CompilerRegistry.globalOverloadRegistry.get(owner);
        if (overloads != null && overloads.containsKey(methodName)) return true;
        Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(owner);
        if (methods != null && methods.containsKey(methodName)) return true;
        String sup = CompilerRegistry.globalSuperClassRegistry.get(owner);
        if (sup != null && !sup.equals(owner)) return classHasMethod(sup, methodName);
        return false;
    }

    private String resolveStaticImportFieldOwner(String name) {
        if (importedStaticMembers.containsKey(name)) {
            String owner = importedStaticMembers.get(name);
            if (hasStaticField(owner, name)) return owner;
        }
        for (String owner : importedStaticWildcards) {
            if (hasStaticField(owner, name)) return owner;
        }
        return null;
    }

    private boolean hasStaticField(String owner, String name) {
        Map<String, String> fields = CompilerRegistry.globalFieldRegistry.get(owner);
        if (fields != null && fields.containsKey(name)) {
            Map<String, Boolean> staticity = CompilerRegistry.globalFieldStaticity.get(owner);
            if (staticity != null && staticity.getOrDefault(name, false)) return true;
        }
        try {
            Class<?> clazz = forName(owner.replace("/", "."));
            Field f = clazz.getField(name);
            return Modifier.isStatic(f.getModifiers());
        } catch (Throwable ignored) {
        }
        return false;
    }

    private String resolveStaticImportMethodOwner(String methodName) {
        if (importedStaticMembers.containsKey(methodName)) {
            return importedStaticMembers.get(methodName);
        }
        List<String> candidates = new ArrayList<>();
        for (String owner : importedStaticWildcards) {
            if (hasStaticMethod(owner, methodName)) {
                candidates.add(owner);
            }
        }
        if (candidates.size() > 1) {
            reportError(currentCtx, "Ambiguous static method reference '" + methodName + "'. Matches multiple static wildcard imports: " + candidates, "IRGenerator");
            return candidates.getFirst();
        }
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private boolean hasStaticMethod(String owner, String methodName) {
        Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(owner);
        if (methods != null && methods.containsKey(methodName)) {
            Map<String, Boolean> staticity = CompilerRegistry.globalMethodStaticity.get(owner);
            if (staticity != null && staticity.getOrDefault(methodName, false)) {
                return true;
            }
        }
        String staticKey = owner + "#" + methodName;
        String cachedStaticity = reflectionStaticityCache.get(staticKey);
        if (cachedStaticity != null) return "true".equals(cachedStaticity);
        try {
            Class<?> clazz = forName(owner.replace("/", "."));
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(methodName) && Modifier.isStatic(m.getModifiers())) {
                    reflectionStaticityCache.put(staticKey, "true");
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        reflectionStaticityCache.put(staticKey, "false");
        return false;
    }

    public static List<String> parseGenericTypeArguments(String typeStr) {
        if (typeStr == null) return Collections.emptyList();
        int start = typeStr.indexOf('<');
        int end = typeStr.lastIndexOf('>');
        if (start < 0 || end <= start) return Collections.emptyList();

        String inner = typeStr.substring(start + 1, end).trim();
        List<String> args = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<' || c == '(' || c == '[') {
                depth++;
                current.append(c);
            } else if (c == '>' || c == ')' || c == ']') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                args.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            args.add(current.toString().trim());
        }
        return args;
    }

    public static String getGenericTypeArgument(String typeStr) {
        List<String> args = parseGenericTypeArguments(typeStr);
        return args.isEmpty() ? null : args.getFirst();
    }

    public static String getGenericValueType(String typeStr) {
        if (typeStr == null) return null;
        List<String> args = parseGenericTypeArguments(typeStr);
        if (args.isEmpty()) return OceanTypeSystem.OBJECT_DESC;
        String clean = TypeChecker.cleanDescriptor(typeStr);
        if (TypeChecker.isMapType(clean) && args.size() >= 2) {
            return args.get(1);
        }
        return args.get(0);
    }

    private String getRawExprTypeIR(OceanParser.ExpressionContext expr) {
        if (expr instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) expr).primary();
            if (p instanceof OceanParser.IdPrimaryContext) {
                String name = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                String raw = symbolTable.getRawType(name);
                if (raw != null) return raw;
            }
        } else if (expr instanceof OceanParser.ArrayAccessExprContext arrCtx) {
            String receiverRaw = getRawExprTypeIR(arrCtx.expression(0));
            if (receiverRaw != null) {
                String valType = getGenericValueType(receiverRaw);
                if (valType != null) return valType;
            }
        }
        IRNode visited = visit(expr);
        if (visited instanceof IRExpression) {
            return ((IRExpression) visited).getTypeDescriptor();
        }
        return OceanTypeSystem.OBJECT_DESC;
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


    private List<IRExpression> buildCanonicalArguments(String classKey, String methodName, OceanParser.ArgumentListContext argListCtx) {
        return buildCanonicalArguments(classKey, methodName, argListCtx, null);
    }

    private List<IRExpression> buildCanonicalArguments(String classKey, String methodName, OceanParser.ArgumentListContext argListCtx, String genericOwner) {
        List<IRExpression> result = new ArrayList<>();
        if (argListCtx == null) return result;

        List<OceanParser.ArgumentContext> argNodes = argListCtx.argument();

        List<CompilerRegistry.MethodParamInfo> params = null;
        if (classKey != null) {
            Map<String, List<CompilerRegistry.MethodParamInfo>> classParams = CompilerRegistry.globalMethodParamsRegistry.get(classKey);
            if (classParams != null) {
                params = classParams.get(methodName + "#" + argNodes.size());
                if (params == null) {
                    params = classParams.get(methodName);
                }
            }
        }

        if (params == null || params.isEmpty()) {
            int totalArgs = argNodes.size();
            for (int i = 0; i < totalArgs; i++) {
                OceanParser.ArgumentContext aCtx = argNodes.get(i);
                String expectedType = getExpectedParamType(classKey, methodName, i, totalArgs, genericOwner);
                String oldCast = currentCastType;
                if (expectedType != null) {
                    currentCastType = expectedType;
                }
                result.add(ensureExpr(visit(aCtx.expression())));
                currentCastType = oldCast;
            }
            return result;
        }

        int size = Math.max(params.size(), argNodes.size());
        IRExpression[] posArgs = new IRExpression[size];
        int nextPos = 0;
        int totalArgs = argNodes.size();

        for (int i = 0; i < totalArgs; i++) {
            OceanParser.ArgumentContext aCtx = argNodes.get(i);
            String expectedType = getExpectedParamType(classKey, methodName, i, totalArgs, genericOwner);
            String oldCast = currentCastType;
            if (expectedType != null) {
                currentCastType = expectedType;
            }
            IRExpression exprVal = ensureExpr(visit(aCtx.expression()));
            currentCastType = oldCast;

            if (aCtx.anyId() != null) {
                String namedParam = aCtx.anyId().getText();
                int foundIdx = -1;
                for (int j = 0; j < params.size(); j++) {
                    if (params.get(j).name().equals(namedParam)) {
                        foundIdx = j;
                        break;
                    }
                }
                if (foundIdx >= 0) {
                    posArgs[foundIdx] = exprVal;
                } else if (nextPos < posArgs.length) {
                    posArgs[nextPos++] = exprVal;
                }
            } else {
                while (nextPos < posArgs.length && posArgs[nextPos] != null) nextPos++;
                if (nextPos < posArgs.length) {
                    posArgs[nextPos++] = exprVal;
                }
            }
        }

        // Fill missing defaults
        for (int i = 0; i < posArgs.length; i++) {
            if (posArgs[i] == null && i < params.size()) {
                CompilerRegistry.MethodParamInfo pi = params.get(i);
                if (pi.defaultExpr() != null) {
                    posArgs[i] = ensureExpr(visit(pi.defaultExpr()));
                }
            }
            if (posArgs[i] != null) {
                result.add(posArgs[i]);
            }
        }

        return result;
    }

    private void reportError(ParserRuleContext node, String message, String context) {
        int line = node != null && node.getStart() != null ? node.getStart().getLine() : 0;
        int col = node != null && node.getStart() != null ? node.getStart().getCharPositionInLine() : 0;
        CompilerReporter.error(currentFile, line, col, message, context);
    }

    private void reportError(ParserRuleContext node, String message) {
        reportError(node, message, "IRGenerator");
    }

    private void reportWarning(ParserRuleContext node, String message) {
        int line = node != null && node.getStart() != null ? node.getStart().getLine() : 0;
        int col = node != null && node.getStart() != null ? node.getStart().getCharPositionInLine() : 0;
        CompilerReporter.warning(currentFile, line, col, message, "IRGenerator");
    }

}
