package ocean.compiler;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.objectweb.asm.Opcodes;
import ocean.compiler.symbol.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/**
 * Modern PreScanner & Signature Collector Pass.
 * Extracts structural metadata (class path, accessibility, method / constructor / field descriptors)
 * from all compiled sources and populates the CompilerRegistry.
 * This completely untangles signature harvesting from code generation.
 */
public class PreScanner extends OceanBaseVisitor<Void> {
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    public static final Set<String> FORBIDDEN_RECORD_COMPONENT_NAMES = Set.of(
            "clone", "finalize", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait"
    );

    public static String findContainedGenericParameter(String typeStr, Set<String> paramNames) {
        if (typeStr == null || paramNames == null || paramNames.isEmpty()) return null;
        for (String param : paramNames) {
            if (Pattern.compile("\\b" + Pattern.quote(param) + "\\b").matcher(typeStr).find()) {
                return param;
            }
        }
        return null;
    }

    private final String currentFile;
    private String currentFilePackage = "";
    private String currentClassName;
    private String currentSuperName = "java/lang/Object";
    private int anonClassCounter = 0;
    
    private final Map<String, String> importedClasses = new HashMap<>();
    private final List<String> importedWildcards = new ArrayList<>();
    private final Map<String, String> importedStaticMembers = new HashMap<>();
    private final List<String> importedStaticWildcards = new ArrayList<>();
    private final Set<String> currentMethodTypeParameters = new HashSet<>();
    private boolean hasConstructor = false;
    private final List<String> definedClasses = new ArrayList<>();
    private final Set<String> definedTopLevelTypes = new HashSet<>();
    private boolean isScanning = false;
    private boolean insideDataClass = false;
    private boolean currentEnumHasConstantBodies = false;
    private final CompilationSession session;

    // New Session-based constructor (Recommended for V3 pipeline)
    public PreScanner(String currentFile, CompilationSession session) {
        this.currentFile = currentFile;
        this.session = session;
        this.symbolTable = new SymbolTable(false, importedClasses, Collections.emptySet(), session);
    }

    // [LEGACY] Old constructor (Kept for V2 stage/compatibility)
    public PreScanner(String currentFile) {
        this(currentFile, null);
    }


    @Override
    public Void visit(ParseTree tree) {
        boolean root = false;
        if (!isScanning) {
            isScanning = true;
            root = true;
            if (session != null) {
                session.resetAnonClassCounters();
            } else {
                anonClassCounter = 0;
            }
            if ((currentFilePackage == null || currentFilePackage.isEmpty()) && tree instanceof OceanParser.ProgramContext prog && prog.compilationUnit() != null) {
                for (OceanParser.CompilationUnitContext cu : prog.compilationUnit()) {
                    if (cu.packageDeclaration() != null && cu.packageDeclaration().anyId() != null) {
                        StringBuilder pkgName = new StringBuilder();
                        for (int i = 0; i < cu.packageDeclaration().anyId().size(); i++) {
                            if (i > 0) pkgName.append("/");
                            pkgName.append(cu.packageDeclaration().anyId(i).getText());
                        }
                        this.currentFilePackage = pkgName.toString();
                        break;
                    }
                }
            }
        }

        // Update session context if available
        if (session != null) {
            session.setCurrentFile(currentFile);
            if (currentFilePackage != null && !currentFilePackage.isEmpty()) {
                session.setCurrentPackage(currentFilePackage.replace('/', '.'));
            } else {
                session.setCurrentPackage("");
            }
        }

        try {
            if (root && tree instanceof OceanParser.ProgramContext prog && prog.compilationUnit() != null) {
                String defaultEffPkg = getEffectivePackage();
                for (OceanParser.CompilationUnitContext cu : prog.compilationUnit()) {
                    String effPkg = defaultEffPkg;
                    if (cu.packageDeclaration() != null && cu.packageDeclaration().anyId() != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < cu.packageDeclaration().anyId().size(); i++) {
                            if (i > 0) sb.append("/");
                            sb.append(cu.packageDeclaration().anyId(i).getText());
                        }
                        effPkg = sb.toString();
                    }
                    String prefix = effPkg.isEmpty() ? "" : (effPkg + "/");
                    String sName = null;
                    if (cu.classDeclaration() != null && cu.classDeclaration().anyId() != null) sName = cu.classDeclaration().anyId().getText();
                    else if (cu.interfaceDeclaration() != null && cu.interfaceDeclaration().anyId() != null) sName = cu.interfaceDeclaration().anyId().getText();
                    else if (cu.enumDeclaration() != null && cu.enumDeclaration().anyId() != null) sName = cu.enumDeclaration().anyId().getText();
                    else if (cu.annotationDeclaration() != null && cu.annotationDeclaration().anyId() != null) {
                        sName = cu.annotationDeclaration().anyId().getText();
                        int annoAccess = Opcodes.ACC_PUBLIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
                        CompilerRegistry.globalClassAccess.put(prefix + sName, annoAccess);
                        CompilerRegistry.globalClassAccess.put(sName, annoAccess);
                        CompilerRegistry.globalIsInterfaceSet.add(prefix + sName);
                        CompilerRegistry.globalIsInterfaceSet.add(sName);
                        CompilerRegistry.globalAbstractClassSet.add(prefix + sName);
                        CompilerRegistry.globalAbstractClassSet.add(sName);
                    }
                    if (sName != null) {
                        definedClasses.add(sName);
                        definedClasses.add(prefix + sName);
                        CompilerRegistry.globalMethodRegistry.computeIfAbsent(prefix + sName, k -> new ConcurrentHashMap<>());
                    }
                }
            }
            Void result = super.visit(tree);
            if (root) {
                validateMainClassPresence();
            }
            return result;
        } finally {
            if (root) {
                isScanning = false;
            }
            if (session != null) {
                session.setCurrentFile(null);
                session.setCurrentPackage(null);
            }
        }
    }

    public void setCurrentFilePackage(String packagePath) {
        if (packagePath != null) {
            this.currentFilePackage = packagePath.replace('.', '/');
        }
    }

    private String getEffectivePackage() {
        if (currentFilePackage != null && !currentFilePackage.isEmpty()) {
            return currentFilePackage;
        }
        return "";
    }

    private String getCurrentClassPath(String className) {
        String effPkg = getEffectivePackage();
        if (!effPkg.isEmpty()) {
            return effPkg + "/" + className;
        }
        return className;
    }

    private String getCurrentClassPath() {
        return getCurrentClassPath(currentClassName);
    }

    private String stripGenerics(String type) {
        if (type == null) return null;
        boolean nullable = type.endsWith("?");
        if (type.contains("<")) {
            String stripped = type.substring(0, type.indexOf("<")).trim();
            return nullable ? stripped + "?" : stripped;
        }
        return type;
    }

    private int countArrayDimensions(String type) {
        if (type == null) return 0;
        int dims = 0;
        for (int i = 0; i < type.length(); i++) {
            if (type.charAt(i) == '[') dims++;
        }
        return dims;
    }

    private String getTypeDescriptor(String type) {
        if (type == null) return OceanTypeSystem.OBJECT_DESC;
        type = stripGenerics(type);

        if (type.startsWith("[") || (TypeChecker.isClassType(type))) return type;

        String dollarType = type.replace('.', '$');
        if (currentClassName != null) {
            String curr = currentClassName;
            while (!curr.isEmpty()) {
                String chain = curr + "$" + dollarType;
                if (definedClasses.contains(chain)) {
                    String effPkg = getEffectivePackage();
                    String fq = (effPkg == null || effPkg.isEmpty()) ? chain : (effPkg + "/" + chain);
                    return "L" + fq + ";";
                }
                int lastDollar = curr.lastIndexOf('$');
                if (lastDollar != -1) {
                    curr = curr.substring(0, lastDollar);
                } else {
                    break;
                }
            }
        }

        if (definedTopLevelTypes.contains(type) || definedClasses.contains(type) || definedClasses.contains(dollarType)) {
            String effPkg = getEffectivePackage();
            String matched = definedClasses.contains(dollarType) ? dollarType : type;
            String fq = (effPkg == null || effPkg.isEmpty()) ? matched : (effPkg + "/" + matched);
            return "L" + fq + ";";
        }

        if (currentMethodTypeParameters.contains(type)) return OceanTypeSystem.OBJECT_DESC;

        if (currentClassName != null) {
            String fullPath = getCurrentClassPath();
            List<CompilerRegistry.TypeParameterInfo> infos = CompilerRegistry.globalTypeParameterRegistry.get(fullPath);
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
            List<CompilerRegistry.TypeParameterInfo> infos = CompilerRegistry.globalTypeParameterRegistry.get(fullPath);
            if (infos != null) {
                for (CompilerRegistry.TypeParameterInfo info : infos) {
                    typeParamNames.add(info.name);
                }
            }
        }

        // Built-in Ocean aliases
        /*String stripped = stripGenerics(type);
        String builtinDesc = OceanTypeSystem.getBuiltinDescriptor(stripped);
        if (builtinDesc != null) {
            return builtinDesc;
        }*/

        return SymbolTable.getDescriptor(type, importedClasses, typeParamNames);
    }

    @Override
    public Void visitPackageDeclaration(OceanParser.PackageDeclarationContext ctx) {
        StringBuilder pkgName = new StringBuilder();
        for (int i = 0; i < ctx.anyId().size(); i++) {
            if (i > 0) pkgName.append("/");
            pkgName.append(ctx.anyId(i).getText());
        }
        this.currentFilePackage = pkgName.toString();
        if (session != null) {
            session.setCurrentPackage(this.currentFilePackage.replace('/', '.'));
        }
        return null;
    }

    @Override
    public Void visitImportStatement(OceanParser.ImportStatementContext ctx) {
        boolean isStatic = ctx.STATIC() != null;
        List<OceanParser.AnyIdContext> parts = ctx.anyId();
        if (parts != null && !parts.isEmpty()) {
            if (isStatic) {
                if (ctx.STAR() != null) {
                    StringBuilder classPath = new StringBuilder();
                    for (int i = 0; i < parts.size(); i++) {
                        if (i > 0) classPath.append("/");
                        classPath.append(parts.get(i).getText());
                    }
                    importedStaticWildcards.add(classPath.toString());
                } else {
                    String memberName = parts.getLast().getText();
                    StringBuilder classPath = new StringBuilder();
                    for (int i = 0; i < parts.size() - 1; i++) {
                        if (i > 0) classPath.append("/");
                        classPath.append(parts.get(i).getText());
                    }
                    String fullClass = classPath.toString();
                    if (importedStaticMembers.containsKey(memberName)) {
                        String existingClass = importedStaticMembers.get(memberName);
                        if (!existingClass.equals(fullClass)) {
                            reportError(ctx, "Conflicting static import: simple name '" + memberName + "' cannot be imported from different classes: '" + existingClass.replace('/', '.') + "' and '" + fullClass.replace('/', '.') + "'.");
                        }
                    }
                    if (importedClasses.containsKey(memberName)) {
                        reportError(ctx, "Static import declaration '" + memberName + "' conflicts with type import '" + importedClasses.get(memberName).replace('/', '.') + "'.");
                    }
                    if (definedTopLevelTypes.contains(memberName)) {
                        reportError(ctx, "Static import declaration '" + memberName + "' conflicts with top-level type defined in this compilation unit.");
                    }
                    importedStaticMembers.put(memberName, fullClass);
                }
            } else {
                if (ctx.STAR() != null) {
                    StringBuilder pkgPath = new StringBuilder();
                    for (int i = 0; i < parts.size(); i++) {
                        if (i > 0) pkgPath.append("/");
                        pkgPath.append(parts.get(i).getText());
                    }
                    String wPath = pkgPath.toString();
                    importedWildcards.add(wPath);
                    if (session != null && !session.activeWildcards.contains(wPath)) {
                        session.activeWildcards.add(wPath);
                    }
                } else {
                    String simpleName = parts.getLast().getText();
                    StringBuilder fullPath = new StringBuilder();
                    for (int i = 0; i < parts.size(); i++) {
                        if (i > 0) fullPath.append("/");
                        fullPath.append(parts.get(i).getText());
                    }
                    String pathStr = fullPath.toString();
                    if (importedClasses.containsKey(simpleName)) {
                        String existing = importedClasses.get(simpleName);
                        if (!existing.equals(pathStr)) {
                            reportError(ctx, "Conflicting import declaration: simple name '" + simpleName + "' is already imported as '" + existing.replace('/', '.') + "'.");
                        }
                    } else {
                        importedClasses.put(simpleName, pathStr);
                    }
                    if (importedStaticMembers.containsKey(simpleName)) {
                        reportError(ctx, "Import declaration '" + pathStr.replace('/', '.') + "' conflicts with statically imported member.");
                    }
                    if (definedTopLevelTypes.contains(simpleName)) {
                        reportError(ctx, "Import declaration '" + pathStr.replace('/', '.') + "' conflicts with top-level type '" + simpleName + "' defined in this compilation unit.");
                    }
                }
            }
        }
        return null;
    }

    private final SymbolTable symbolTable;

    public Map<String, String> getImportedClasses() { return importedClasses; }
    public List<String> getImportedWildcards() { return importedWildcards; }
    public Map<String, String> getImportedStaticMembers() { return importedStaticMembers; }
    public List<String> getImportedStaticWildcards() { return importedStaticWildcards; }
    public SymbolTable getSymbolTable() { return symbolTable; }
    public String getCurrentFilePackage() { return currentFilePackage; }
    private void collectTokens(ParseTree node, Set<String> tokens) {
        if (node instanceof TerminalNode tn) {
            tokens.add(tn.getText());
        } else {
            for (int i = 0; i < node.getChildCount(); i++) {
                collectTokens(node.getChild(i), tokens);
            }
        }
    }

    private boolean hasQualifiedThis(ParseTree tree) {
        if (tree instanceof OceanParser.QualifiedThisExprContext) {
            return true;
        }
        for (int i = 0; i < tree.getChildCount(); i++) {
            if (hasQualifiedThis(tree.getChild(i))) return true;
        }
        return false;
    }

    private boolean isInsideStaticContext(ParserRuleContext ctx) {
        ParserRuleContext cur = ctx.getParent();
        while (cur != null) {
            switch (cur) {
                case OceanParser.NormalMethodContext mCtx -> {
                    return ModifierHelper.isStatic(mCtx.modifier());
                }
                case OceanParser.ConstructorDeclarationContext constructorDeclarationContext -> {
                    return false;
                }
                case OceanParser.MemberDeclarationContext memCtx -> {
                    if (memCtx.STATIC() != null && memCtx.block() != null) {
                        return true;
                    }
                }
                default -> {
                }
            }
            if (cur instanceof OceanParser.ClassDeclarationContext) {
                break;
            }
            cur = cur.getParent();
        }
        return false;
    }

    private boolean classAccessesOuterInstanceMembers(OceanParser.ClassDeclarationContext ctx, String outerFqcn) {
        if (outerFqcn == null) return false;
        if (hasQualifiedThis(ctx)) return true;

        Set<String> classTokens = new HashSet<>();
        collectTokens(ctx, classTokens);

        // Collect names declared directly in this inner class so we don't treat inner's own members as outer access
        Set<String> innerDeclaredMembers = new HashSet<>();
        if (ctx.memberDeclaration() != null) {
            for (OceanParser.MemberDeclarationContext m : ctx.memberDeclaration()) {
                if (m.fieldDeclaration() != null && m.fieldDeclaration().variableDeclarator() != null) {
                    for (OceanParser.VariableDeclaratorContext v : m.fieldDeclaration().variableDeclarator()) {
                        if (v.anyId() != null) innerDeclaredMembers.add(v.anyId().getText());
                    }
                }
                if (m.methodDeclaration() instanceof OceanParser.NormalMethodContext nm && nm.anyId() != null) {
                    innerDeclaredMembers.add(nm.anyId().getText());
                }
            }
        }
        if (ctx.parameterList() != null) {
            for (OceanParser.ParameterContext p : ctx.parameterList().parameter()) {
                if (p.anyId() != null) innerDeclaredMembers.add(p.anyId().getText());
            }
        }

        // Check outer classes in the AST (solves declaration order when outer field is declared after inner class)
        Map<String, Boolean> astOuterFields = new HashMap<>();
        Map<String, Boolean> astOuterMethods = new HashMap<>();
        ParserRuleContext pCur = ctx.getParent();
        while (pCur != null) {
            if (pCur instanceof OceanParser.ClassDeclarationContext encClass) {
                if (encClass.memberDeclaration() != null) {
                    for (OceanParser.MemberDeclarationContext m : encClass.memberDeclaration()) {
                        if (m.fieldDeclaration() != null) {
                            boolean isStat = m.fieldDeclaration().modifier() != null &&
                                    m.fieldDeclaration().modifier().stream().anyMatch(mod -> "static".equals(mod.getText()));
                            if (m.fieldDeclaration().variableDeclarator() != null) {
                                for (OceanParser.VariableDeclaratorContext v : m.fieldDeclaration().variableDeclarator()) {
                                    if (v.anyId() != null) astOuterFields.put(v.anyId().getText(), isStat);
                                }
                            }
                        }
                        if (m.methodDeclaration() instanceof OceanParser.NormalMethodContext nm) {
                            boolean isStat = nm.modifier() != null &&
                                    nm.modifier().stream().anyMatch(mod -> "static".equals(mod.getText()));
                            if (nm.anyId() != null) {
                                astOuterMethods.put(nm.anyId().getText(), isStat);
                            }
                        }
                    }
                }
                if (encClass.parameterList() != null) {
                    for (OceanParser.ParameterContext p : encClass.parameterList().parameter()) {
                        if (p.anyId() != null) astOuterFields.put(p.anyId().getText(), false);
                    }
                }
            }
            pCur = pCur.getParent();
        }

        for (Map.Entry<String, Boolean> entry : astOuterFields.entrySet()) {
            if (!entry.getValue()) {
                String fName = entry.getKey();
                if (!innerDeclaredMembers.contains(fName) && classTokens.contains(fName)) {
                    return true;
                }
            }
        }
        for (Map.Entry<String, Boolean> entry : astOuterMethods.entrySet()) {
            if (!entry.getValue()) {
                String mName = entry.getKey();
                if (!innerDeclaredMembers.contains(mName) && classTokens.contains(mName)) {
                    return true;
                }
            }
        }

        // Walk up outer hierarchy (registry, superclasses, enclosing classes)
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(outerFqcn);

        while (!queue.isEmpty()) {
            String search = queue.poll();
            if (search == null || !visited.add(search)) continue;

            Map<String, String> outerFields = CompilerRegistry.globalFieldRegistry.get(search);
            Map<String, Boolean> outerFieldStatics = CompilerRegistry.globalFieldStaticity.get(search);
            Map<String, String> outerMethods = CompilerRegistry.globalMethodRegistry.get(search);
            Map<String, Boolean> outerMethodStatics = CompilerRegistry.globalMethodStaticity.get(search);

            if (outerFields != null) {
                for (String f : outerFields.keySet()) {
                    boolean isStatic = outerFieldStatics != null && outerFieldStatics.getOrDefault(f, false);
                    if (!isStatic && !innerDeclaredMembers.contains(f) && classTokens.contains(f)) {
                        return true;
                    }
                }
            }
            if (outerMethods != null) {
                for (String m : outerMethods.keySet()) {
                    if (m.equals("<init>") || m.equals("<clinit>") || m.equals("main")) continue;
                    boolean isStatic = outerMethodStatics != null && outerMethodStatics.getOrDefault(m, false);
                    if (!isStatic && !innerDeclaredMembers.contains(m) && classTokens.contains(m)) {
                        return true;
                    }
                }
            }

            // Also check superclass of outer (solves inherited fields/methods)
            String superName = CompilerRegistry.globalSuperClassRegistry.get(search);
            if (superName != null && !superName.isEmpty() && !superName.equals("java/lang/Object") && !superName.equals("Object")) {
                queue.add(superName);
            }

            // Also check enclosing class if nested
            if (CompilerRegistry.globalInnerClassOuterMap.containsKey(search)) {
                queue.add(CompilerRegistry.globalInnerClassOuterMap.get(search));
            } else if (search.contains("$")) {
                queue.add(search.substring(0, search.lastIndexOf('$')));
            }
        }

        return false;
    }

    private void validateModifiers(ParserRuleContext ctx, List<OceanParser.ModifierContext> modifiers) {
        if (modifiers == null || modifiers.isEmpty()) return;
        Set<String> seen = new HashSet<>();
        int accessCount = 0;
        for (OceanParser.ModifierContext mod : modifiers) {
            String mt = mod.getText();
            if (!seen.add(mt)) {
                reportError(mod, "Repeated modifier: '" + mt + "'");
            }
            if ("public".equals(mt) || "private".equals(mt) || "protected".equals(mt)) {
                accessCount++;
            }
        }
        if (accessCount > 1) {
            reportError(ctx, "Illegal combination of modifiers: cannot specify more than one of 'public', 'private', and 'protected'.");
        }
    }

    @Override
    public Void visitClassDeclaration(OceanParser.ClassDeclarationContext ctx) {
        String oldClassName = currentClassName;
        String oldSuperName = currentSuperName;
        boolean oldHasConstructor = hasConstructor;
        boolean oldInsideDataClass = insideDataClass;

        String classSimpleName = ctx.anyId().getText();
        if (oldClassName != null) {
            currentClassName = oldClassName + "$" + classSimpleName;
            if (definedClasses.contains(currentClassName)) {
                reportError(ctx.anyId(), "Duplicate nested type declaration: '" + classSimpleName + "' is already defined in '" + oldClassName + "'.");
            }
        } else {
            currentClassName = classSimpleName;
            if (definedTopLevelTypes.contains(classSimpleName)) {
                reportError(ctx.anyId(), "Duplicate class declaration: '" + classSimpleName + "' is already defined in this compilation unit.");
            }
            definedClasses.add(classSimpleName);
            definedTopLevelTypes.add(classSimpleName);
            if (importedClasses.containsKey(classSimpleName)) {
                reportError(ctx.anyId(), "Top-level type '" + classSimpleName + "' conflicts with imported type '" + importedClasses.get(classSimpleName).replace('/', '.') + "' in the same compilation unit.");
            }
            if (importedStaticMembers.containsKey(classSimpleName)) {
                reportError(ctx.anyId(), "Top-level type '" + classSimpleName + "' conflicts with statically imported member in the same compilation unit.");
            }
        }
        definedClasses.add(currentClassName);
        currentSuperName = "java/lang/Object";
        hasConstructor = false;

        boolean isDataClass = ModifierHelper.isData(ctx.modifier());
        insideDataClass = isDataClass;
        if (Boolean.getBoolean("ocean.debug")) {
            System.out.println("[DEBUG PreScanner] class: " + classSimpleName + ", isDataClass: " + isDataClass + ", modifiers: " + (ctx.modifier() != null ? ctx.modifier().stream().map(RuleContext::getText).toList() : "null"));
        }

        String fullPathKey = getCurrentClassPath();
        CompilationSession session = CompilationSession.getActiveSession();
        String oldClassFqcn = null;
        if (session != null) {
            oldClassFqcn = session.getCurrentClassFqcn();
            session.setCurrentClassFqcn(fullPathKey);
        }



        boolean isInsideInterface = oldClassName != null && (CompilerRegistry.globalIsInterfaceSet.contains(oldClassFqcn) || CompilerRegistry.globalIsInterfaceSet.contains(oldClassName));
        if (isInsideInterface && ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String mt = mod.getText();
                if ("private".equals(mt) || "protected".equals(mt)) {
                    reportError(mod, "Interface member classes cannot be declared 'private' or 'protected'. All interface member types are implicitly 'public static'.");
                }
            }
        }

        boolean isLocalClass = ctx.getParent() instanceof OceanParser.LocalClassDeclStmtContext;
        if (isLocalClass && ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String mt = mod.getText();
                if ("public".equals(mt) || "protected".equals(mt) || "private".equals(mt) || "static".equals(mt)) {
                    reportError(mod, "Modifier is not allowed here in local class declaration: access modifiers or 'static' cannot be used.");
                }
            }
        }

        if (oldClassName != null && !isInsideInterface) {
            String outerFqcn = oldClassFqcn != null ? oldClassFqcn : (currentFilePackage != null && !currentFilePackage.isEmpty() && !currentFilePackage.equals("default") ? currentFilePackage + "/" + oldClassName : oldClassName);
            CompilerRegistry.globalInnerClassOuterMap.put(fullPathKey, outerFqcn);
            if (!ModifierHelper.isStatic(ctx.modifier()) && !isInsideStaticContext(ctx) && classAccessesOuterInstanceMembers(ctx, outerFqcn)) {
                CompilerRegistry.globalInnerClassUsedOuterMap.put(fullPathKey, outerFqcn);
            }
        }

        String simpleInnerName = classSimpleName.substring(classSimpleName.indexOf('$')!=-1?classSimpleName.lastIndexOf('$')+1:0);
        if (CompilerRegistry.globalInnerClassOuterMap.containsKey(fullPathKey)) {
            String newInner =fullPathKey;
            do {
                String outer = CompilerRegistry.globalInnerClassOuterMap.get(newInner);
                String simpleOuter = outer.substring(outer.replace('$','/').lastIndexOf('/')+1);
                if (simpleOuter.equals(simpleInnerName)) {
                    reportError(ctx,"Inner class cannot have the same name as outer class.");
                }
                newInner = outer;
            } while (CompilerRegistry.globalInnerClassOuterMap.containsKey(newInner));
        }

        CompilerRegistry.globalMethodRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>());

        List<CompilerRegistry.TypeParameterInfo> typeParams = extractTypeParameters(ctx.typeParameter());
        CompilerRegistry.globalTypeParameterRegistry.put(fullPathKey, typeParams);
        Set<String> typeParamNames = new HashSet<>();
        for (CompilerRegistry.TypeParameterInfo info : typeParams) {
            typeParamNames.add(info.name);
        }

        // Superclass
        if (ctx.EXTENDS() != null && ctx.type() != null) {
            String sName = ctx.type().getText();
            String sBaseName = sName.contains("<") ? sName.substring(0, sName.indexOf('<')).trim() : sName.trim();
            if (typeParamNames.contains(sBaseName)) {
                reportError(ctx.type(), "Cannot extend a type parameter: '" + sBaseName + "'.");
            }
            currentSuperName = resolveInternalPath(sName);
            String superSig = resolveGenericSignaturePreservingTypeParams(sName, importedClasses, typeParamNames);
            CompilerRegistry.globalSuperClassGenericSignatureRegistry.put(fullPathKey, superSig);
            CompilerRegistry.globalSuperClassGenericSignatureRegistry.put(classSimpleName, superSig);
            checkAndRegisterContainerTypes(fullPathKey, currentSuperName);
        }
        CompilerRegistry.globalSuperClassRegistry.put(fullPathKey, currentSuperName);
        if (currentSuperName != null && !currentSuperName.equals("java/lang/Object")) {
            String superOuter = CompilerRegistry.globalInnerClassUsedOuterMap.get(currentSuperName);
            if (superOuter == null) {
                for (Map.Entry<String, String> entry : CompilerRegistry.globalInnerClassUsedOuterMap.entrySet()) {
                    if (entry.getKey().endsWith("/" + currentSuperName) || entry.getKey().endsWith("$" + currentSuperName) || entry.getKey().equals(currentSuperName)) {
                        superOuter = entry.getValue();
                        break;
                    }
                }
            }
            if (superOuter != null) {
                CompilerRegistry.globalInnerClassUsedOuterMap.put(fullPathKey, superOuter);
            }
        }

        // Interfaces
        String[] interfaces = null;
        if (ctx.IMPLEMENTS() != null && ctx.typeList() != null) {
            List<OceanParser.TypeContext> tList = ctx.typeList().type();
            interfaces = new String[tList.size()];
            List<String> interfaceSigs = new ArrayList<>();
            Set<String> seenInterfaces = new HashSet<>();
            for (int i = 0; i < tList.size(); i++) {
                String tName = tList.get(i).getText();
                String resolvedIface = resolveInternalPath(tName);
                if (!seenInterfaces.add(resolvedIface != null ? resolvedIface : tName)) {
                    reportError(tList.get(i), "Duplicate interface: '" + tName + "' is implemented more than once in '" + classSimpleName + "'.");
                }
                interfaces[i] = resolvedIface;
                checkAndRegisterContainerTypes(fullPathKey, interfaces[i]);
                String interSig = resolveGenericSignaturePreservingTypeParams(tName, importedClasses, typeParamNames);
                interfaceSigs.add(interSig);
            }
            CompilerRegistry.globalInterfaceGenericSignatureRegistry.put(fullPathKey, interfaceSigs);
            CompilerRegistry.globalInterfaceGenericSignatureRegistry.put(classSimpleName, interfaceSigs);
        }
        CompilerRegistry.globalInterfaceRegistry.put(fullPathKey, interfaces != null ? interfaces : new String[0]);

        // Accessibility modifiers
        validateModifiers(ctx, ctx.modifier());
        int classAccess = Opcodes.ACC_SUPER;
        boolean hasExplicitAccessibility = false;
        boolean isSealed = false;
        boolean isNonSealed = false;
        boolean isFinal = false;
        if (ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String mText = mod.getText();
                switch (mText) {
                    case "public" -> {
                        classAccess |= Opcodes.ACC_PUBLIC;
                        hasExplicitAccessibility = true;
                    }
                    case "private" -> {
                        classAccess |= Opcodes.ACC_PRIVATE;
                        hasExplicitAccessibility = true;
                    }
                    case "protected" -> {
                        classAccess |= Opcodes.ACC_PROTECTED;
                        hasExplicitAccessibility = true;
                    }
                    case "static" -> classAccess |= Opcodes.ACC_STATIC;
                    case "abstract" -> classAccess |= Opcodes.ACC_ABSTRACT;
                    case "final" -> {
                        classAccess |= Opcodes.ACC_FINAL;
                        isFinal = true;
                    }
                    case "sealed" -> isSealed = true;
                    case "non-sealed" -> isNonSealed = true;
                }
            }
        }
        int inheritanceModCount = (isFinal ? 1 : 0) + (isSealed ? 1 : 0) + (isNonSealed ? 1 : 0);
        if (inheritanceModCount > 1) {
            reportError(ctx, "Illegal combination of modifiers for class '" + classSimpleName + "': at most one of 'final', 'sealed', and 'non-sealed' may be used.");
        }
        boolean isAbstractClass = (classAccess & Opcodes.ACC_ABSTRACT) != 0;
        if (isAbstractClass) {
            CompilerRegistry.globalAbstractClassSet.add(fullPathKey);
        }
        if (isAbstractClass && isFinal) {
            reportError(ctx,"Class '" + classSimpleName + "' cannot be both 'abstract' and 'final'.");
        }
        if (isDataClass && (isAbstractClass || isSealed)) {
            reportError(ctx, "Data class '" + classSimpleName + "' cannot be abstract or sealed.");
        }
        boolean isTopLevel = oldClassName == null;
        if (isTopLevel && (classAccess & (Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) != 0) {
            reportError(ctx, "Top-level class '" + classSimpleName + "' cannot be declared 'private' or 'protected'.");
        }
        if (isTopLevel && (classAccess & Opcodes.ACC_STATIC) != 0) {
            reportError(ctx, "Top-level class '" + classSimpleName + "' cannot be declared 'static'. Only inner member classes can be 'static'.");
        }
        if (isInsideInterface) {
            classAccess = (classAccess & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        }

        CompilerRegistry.globalClassAccess.put(fullPathKey, classAccess);

        ClassSymbol classSym = CompilerRegistry.getOrCreateClassSymbol(fullPathKey);
        classSym.setSuperClassName(currentSuperName);
        if (ctx.EXTENDS() != null && ctx.type() != null) {
            String superSig = CompilerRegistry.globalSuperClassGenericSignatureRegistry.get(fullPathKey);
            if (superSig != null) classSym.setSuperClassGenericSignature(superSig);
        }
        if (interfaces != null) {
            classSym.setInterfaces(Arrays.asList(interfaces));
        }
        List<String> ifaceSigs = CompilerRegistry.globalInterfaceGenericSignatureRegistry.get(fullPathKey);
        if (ifaceSigs != null) {
            classSym.setInterfaceGenericSignatures(ifaceSigs);
        }
        classSym.setTypeParameters(typeParams);
        classSym.setAccessFlags(classAccess);
        classSym.setAbstract(isAbstractClass);
        classSym.setDataClass(isDataClass);
        classSym.setSealed(isSealed);

        if (isSealed) {
            CompilerRegistry.globalSealedClassSet.add(fullPathKey);
            List<String> permitted = new CopyOnWriteArrayList<>();
            if (ctx.restrictsClause() != null && ctx.restrictsClause().typeList() != null) {
                CompilerRegistry.globalExplicitRestrictsSet.add(fullPathKey);
                for (OceanParser.TypeContext tc : ctx.restrictsClause().typeList().type()) {
                    permitted.add(resolveInternalPath(tc.getText()));
                }
            }
            CompilerRegistry.globalPermittedSubclassesRegistry.put(fullPathKey, permitted);
            classSym.setPermittedSubclasses(permitted);
            classSym.setHasExplicitRestricts(ctx.restrictsClause() != null && ctx.restrictsClause().typeList() != null);
        }

        if (isFinal) {
            CompilerRegistry.globalSubclassStatusRegistry.put(fullPathKey, "final");
            classSym.setSubclassStatus("final");
        } else if (isSealed) {
            CompilerRegistry.globalSubclassStatusRegistry.put(fullPathKey, "sealed");
            classSym.setSubclassStatus("sealed");
        } else if (isNonSealed) {
            CompilerRegistry.globalSubclassStatusRegistry.put(fullPathKey, "non-sealed");
            classSym.setSubclassStatus("non-sealed");
        } /*else if (isDataClass) {
            CompilerRegistry.globalSubclassStatusRegistry.put(fullPathKey, "final");
            classSym.setSubclassStatus("final");
        }*/

        if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(fullPathKey)) {
            classSym.setOuterClassName(CompilerRegistry.globalInnerClassUsedOuterMap.get(fullPathKey));
        }
        CompilerRegistry.registerClassSymbol(classSym);

        if (currentSuperName != null && CompilerRegistry.globalSealedClassSet.contains(currentSuperName)) {
            if (!CompilerRegistry.globalExplicitRestrictsSet.contains(currentSuperName)) {
                List<String> superPermitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(currentSuperName);
                if (superPermitted != null && !superPermitted.contains(fullPathKey)) {
                    superPermitted.add(fullPathKey);
                }
            }
        }
        collectAllPermittedForInterfaces(fullPathKey, interfaces);

        // If it is a data class, register constructor parameters as fields and methods
        StringBuilder ctorDesc = new StringBuilder("(");
        if (isDataClass) {
            CompilerRegistry.globalDataClassSet.add(fullPathKey);
            //CompilerRegistry.globalDataClassSet.add(classSimpleName);
            Map<String, String> fieldReg = CompilerRegistry.globalFieldRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>());
            Map<String, Boolean> fieldStat = CompilerRegistry.globalFieldStaticity.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>());
            Map<String, Integer> fieldAcc = CompilerRegistry.globalFieldAccess.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>());
            Map<String, Boolean> fieldMut = CompilerRegistry.globalFieldMutability.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>());
            Map<String, String> mReg = CompilerRegistry.globalMethodRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>());
            Map<String, Integer> mAcc = CompilerRegistry.globalMethodAccess.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>());
            Map<String, Boolean> mStat = CompilerRegistry.globalMethodStaticity.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>());

            boolean hasVarargs = false;
            if (ctx.parameterList() != null) {
                List<CompilerRegistry.RecordComponentInfo> recordComponents = new java.util.concurrent.CopyOnWriteArrayList<>();
                List<CompilerRegistry.MethodParamInfo> primaryParams = new ArrayList<>();
                if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(fullPathKey)) {
                    String outerFqcn = CompilerRegistry.globalInnerClassUsedOuterMap.get(fullPathKey);
                    ctorDesc.append("L").append(outerFqcn).append(";");
                    primaryParams.add(new CompilerRegistry.MethodParamInfo("this$0", outerFqcn, "L" + outerFqcn + ";", null));
                }
                List<OceanParser.ParameterContext> pList = ctx.parameterList().parameter();
                for (int pIdx = 0; pIdx < pList.size(); pIdx++) {
                    OceanParser.ParameterContext p = pList.get(pIdx);
                    String pName = p.anyId().getText();
                    if (FORBIDDEN_RECORD_COMPONENT_NAMES.contains(pName)) {
                        reportError(p, "Data class component cannot be named '" + pName + "' as it conflicts with Object methods.");
                    }
                    if (p.VARIABLE() != null || p.VALUE() != null) {
                        reportError(ctx, "Cannot use 'value' or 'variable' for data class parameters. An explicit type must be specified: '" + pName + "'.");
                    }
                    if (p.type() == null) {
                        reportError(ctx, "Data class parameters must declare an explicit type: '" + pName + "'.");
                    }
                    String pType = p.type() != null ? p.type().getText() : "Object";
                    String pDesc = getTypeDescriptor(pType);
                    if (p.ELLIPSIS() != null) {
                        if (pIdx != pList.size() - 1) {
                            reportError(p, "Varargs parameter ('...') must be the last parameter in the parameter list.");
                        }
                        hasVarargs = true;
                        pDesc = "[" + pDesc;
                    }
                    ctorDesc.append(pDesc);

                    // Register field
                    fieldReg.put(pName, pDesc);
                    fieldStat.put(pName, false);
                    boolean isParamFinal = p.FINAL() != null;
                    if (isParamFinal) {
                        fieldAcc.put(pName, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL);
                    } else {
                        fieldAcc.put(pName, Opcodes.ACC_PUBLIC);
                    }
                    fieldMut.put(pName, isParamFinal);

                    String genFieldSig = null;
                    if (pType.contains("<") || typeParamNames.contains(pType)) {
                        genFieldSig = resolveGenericSignaturePreservingTypeParams(pType, importedClasses, typeParamNames);
                        CompilerRegistry.globalFieldGenericSignatureRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>()).put(pName, genFieldSig);
                        CompilerRegistry.globalFieldGenericSignatureRegistry.computeIfAbsent(classSimpleName, k -> new ConcurrentHashMap<>()).put(pName, genFieldSig);
                    }

                    classSym.addField(new FieldSymbol(fullPathKey, pName, pDesc, genFieldSig, Opcodes.ACC_PUBLIC, false, false));

                    // Record component
                    recordComponents.add(new CompilerRegistry.RecordComponentInfo(pName, pDesc));
                    primaryParams.add(new CompilerRegistry.MethodParamInfo(pName, pType, pDesc, p.expression()));

                    // Register getter
                    String capName = pName.substring(0, 1).toUpperCase(Locale.ENGLISH) + pName.substring(1);
                    mReg.put("get" + capName, "()" + pDesc);
                    mAcc.put("get" + capName, Opcodes.ACC_PUBLIC);
                    mStat.put("get" + capName, false);
                    classSym.addMethod(new MethodSymbol(fullPathKey, "get" + capName, "()" + pDesc, pType, Opcodes.ACC_PUBLIC, false, false));
                    if (pType.contains("<") || typeParamNames.contains(pType)) {
                        CompilerRegistry.globalMethodGenericReturnTypeRegistry
                                .computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                                .put("get" + capName, pType);
                        CompilerRegistry.globalMethodGenericReturnTypeRegistry
                                .computeIfAbsent(classSimpleName, k -> new ConcurrentHashMap<>())
                                .put("get" + capName, pType);
                    }

                    // Register setter
                    if (!isParamFinal) {
                        mReg.put("set" + capName, "(" + pDesc + ")V");
                        mAcc.put("set" + capName, Opcodes.ACC_PUBLIC);
                        mStat.put("set" + capName, false);
                        classSym.addMethod(new MethodSymbol(fullPathKey, "set" + capName, "(" + pDesc + ")V", "void", Opcodes.ACC_PUBLIC, false, false));
                    }
                }
                ctorDesc.append(")V");
                CompilerRegistry.globalRecordComponents.put(fullPathKey, recordComponents);
                //CompilerRegistry.globalRecordComponents.put(classSimpleName, recordComponents);
                classSym.setRecordComponents(recordComponents);

                // Register primary constructor
                String fullCtorDesc = ctorDesc.toString();
                int ctorAccess = Opcodes.ACC_PUBLIC | (hasVarargs ? Opcodes.ACC_VARARGS : 0);
                mReg.put("<init>", fullCtorDesc);
                mAcc.put("<init>", ctorAccess);
                mAcc.put("<init>" + fullCtorDesc, ctorAccess);
                mStat.put("<init>", false);
                classSym.addMethod(new MethodSymbol(fullPathKey, "<init>", fullCtorDesc, "void", ctorAccess, false, false));
                CompilerRegistry.globalOverloadRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent("<init>", k -> new CopyOnWriteArrayList<>()).add(fullCtorDesc);
                if (!primaryParams.isEmpty()) {
                    Map<String, List<CompilerRegistry.MethodParamInfo>> classMap = CompilerRegistry.globalMethodParamsRegistry
                            .computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>());
                    classMap.put("<init>#" + primaryParams.size(), primaryParams);
                    classMap.put("<init>" + fullCtorDesc, primaryParams);
                }
                hasConstructor = true;
            }

            // Register equals, hashCode, toString for data class
            mReg.put("equals", "(Ljava/lang/Object;)Z");
            mAcc.put("equals", Opcodes.ACC_PUBLIC);
            mStat.put("equals", false);
            classSym.addMethod(new MethodSymbol(fullPathKey, "equals", "(Ljava/lang/Object;)Z", "bool", Opcodes.ACC_PUBLIC, false, false));

            mReg.put("hashCode", "()I");
            mAcc.put("hashCode", Opcodes.ACC_PUBLIC);
            mStat.put("hashCode", false);
            classSym.addMethod(new MethodSymbol(fullPathKey, "hashCode", "()I", "int", Opcodes.ACC_PUBLIC, false, false));

            mReg.put("toString", "()Ljava/lang/String;");
            mAcc.put("toString", Opcodes.ACC_PUBLIC);
            mStat.put("toString", false);
            classSym.addMethod(new MethodSymbol(fullPathKey, "toString", "()Ljava/lang/String;", "String", Opcodes.ACC_PUBLIC, false, false));

            // Register copy() and copy(params...)
            String classDesc = OceanTypeSystem.wrapObjectType(fullPathKey);
            mReg.put("copy", "()" + classDesc);
            CompilerRegistry.globalMethodAccess.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>()).put("copy", Opcodes.ACC_PUBLIC);
            CompilerRegistry.globalMethodStaticity.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>()).put("copy", false);
            CompilerRegistry.globalOverloadRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent("copy", k -> new CopyOnWriteArrayList<>()).add("()" + classDesc);
            classSym.addMethod(new MethodSymbol(fullPathKey, "copy", "()" + classDesc, fullPathKey, Opcodes.ACC_PUBLIC, false, false));

            if (ctx.parameterList() != null) {
                String ctorTypes = ctorDesc.substring(0, ctorDesc.length() - 2); // Remove ')V'
                String copyDesc = ctorTypes + ")" + classDesc;
                mReg.put("copy", copyDesc);
                CompilerRegistry.globalMethodAccess.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>()).put("copy", Opcodes.ACC_PUBLIC);
                CompilerRegistry.globalMethodStaticity.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>()).put("copy", false);
                CompilerRegistry.globalOverloadRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent("copy", k -> new CopyOnWriteArrayList<>()).add(copyDesc);
                classSym.addMethod(new MethodSymbol(fullPathKey, "copy", copyDesc, fullPathKey, Opcodes.ACC_PUBLIC, false, false));
            }
        }

        super.visitClassDeclaration(ctx);

        if (!hasConstructor && !CompilerRegistry.globalIsInterfaceSet.contains(fullPathKey)) {
            // Register default empty constructor
            String defaultCtorDesc = "()V";
            if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(fullPathKey)) {
                String outerFqcn = CompilerRegistry.globalInnerClassUsedOuterMap.get(fullPathKey);
                defaultCtorDesc = "(L" + outerFqcn + ";)V";
            }
            CompilerRegistry.globalMethodRegistry.get(fullPathKey).put("<init>", defaultCtorDesc);
            // Overload registry'ye de kaydet
            CompilerRegistry.globalOverloadRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent("<init>", k -> new CopyOnWriteArrayList<>()).add(defaultCtorDesc);
            classSym.addMethod(new MethodSymbol(fullPathKey, "<init>", defaultCtorDesc, "void", Opcodes.ACC_PUBLIC, false, false));
        }

        currentClassName = oldClassName;
        currentSuperName = oldSuperName;
        hasConstructor = oldHasConstructor;
        insideDataClass = oldInsideDataClass;
        if (session != null) {
            session.setCurrentClassFqcn(oldClassFqcn);
        }
        return null;
    }

    private void collectAllPermittedForInterfaces(String fullPathKey, String[] interfaces) {
        if (interfaces != null) {
            for (String inter : interfaces) {
                if (CompilerRegistry.globalSealedClassSet.contains(inter)) {
                    if (!CompilerRegistry.globalExplicitRestrictsSet.contains(inter)) {
                        List<String> interPermitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(inter);
                        if (interPermitted != null && !interPermitted.contains(fullPathKey)) {
                            interPermitted.add(fullPathKey);
                        }
                    }
                }
            }
        }
    }


    @Override
    public Void visitInterfaceDeclaration(OceanParser.InterfaceDeclarationContext ctx) {
        String oldClassName = currentClassName;
        String oldSuperName = currentSuperName;

        String interSimpleName = ctx.anyId().getText();
        if (oldClassName != null) {
            currentClassName = oldClassName + "$" + interSimpleName;
            if (definedClasses.contains(currentClassName)) {
                reportError(ctx.anyId(), "Duplicate nested type declaration: '" + interSimpleName + "' is already defined in '" + oldClassName + "'.");
            }
        } else {
            currentClassName = interSimpleName;
            if (definedTopLevelTypes.contains(interSimpleName)) {
                reportError(ctx.anyId(), "Duplicate interface declaration: '" + interSimpleName + "' is already defined in this compilation unit.");
            }
            definedClasses.add(interSimpleName);
            definedTopLevelTypes.add(interSimpleName);
            if (importedClasses.containsKey(interSimpleName)) {
                reportError(ctx.anyId(), "Top-level type '" + interSimpleName + "' conflicts with imported type '" + importedClasses.get(interSimpleName).replace('/', '.') + "' in the same compilation unit.");
            }
            if (importedStaticMembers.containsKey(interSimpleName)) {
                reportError(ctx.anyId(), "Top-level type '" + interSimpleName + "' conflicts with statically imported member in the same compilation unit.");
            }
        }
        definedClasses.add(currentClassName);
        currentSuperName = "java/lang/Object";

        String fullPathKey = getCurrentClassPath();
        CompilationSession session = CompilationSession.getActiveSession();
        String oldClassFqcn = null;
        if (session != null) {
            oldClassFqcn = session.getCurrentClassFqcn();
            session.setCurrentClassFqcn(fullPathKey);
        }
        CompilerRegistry.globalMethodRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>());
        CompilerRegistry.globalIsInterfaceSet.add(fullPathKey);
        CompilerRegistry.globalAbstractClassSet.add(fullPathKey);

        List<CompilerRegistry.TypeParameterInfo> typeParams = extractTypeParameters(ctx.typeParameter());
        CompilerRegistry.globalTypeParameterRegistry.put(fullPathKey, typeParams);
        Set<String> typeParamNames = new HashSet<>();
        for (CompilerRegistry.TypeParameterInfo info : typeParams) {
            typeParamNames.add(info.name);
        }

        String[] interfaces = null;
        if ((ctx.EXTENDS() != null || ctx.IMPLEMENTS() != null) && ctx.typeList() != null) {
            List<OceanParser.TypeContext> tList = ctx.typeList().type();
            interfaces = new String[tList.size()];
            List<String> interfaceSigs = new ArrayList<>();
            Set<String> seenInterfaces = new HashSet<>();
            for (int i = 0; i < tList.size(); i++) {
                String tName = tList.get(i).getText();
                String resolvedIface = resolveInternalPath(tName);
                if (!seenInterfaces.add(resolvedIface != null ? resolvedIface : tName)) {
                    reportError(tList.get(i), "Duplicate interface: '" + tName + "' is extended or implemented more than once in '" + currentClassName + "'.");
                }
                interfaces[i] = resolvedIface;
                checkAndRegisterContainerTypes(fullPathKey, interfaces[i]);
                String interSig = resolveGenericSignaturePreservingTypeParams(tName, importedClasses, typeParamNames);
                interfaceSigs.add(interSig);
            }
            CompilerRegistry.globalInterfaceGenericSignatureRegistry.put(fullPathKey, interfaceSigs);
        }
        CompilerRegistry.globalInterfaceRegistry.put(fullPathKey, interfaces != null ? interfaces : new String[0]);
        CompilerRegistry.globalSuperClassRegistry.put(fullPathKey, currentSuperName);

        validateModifiers(ctx, ctx.modifier());
        int interfaceAccess = Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        boolean hasExplicitAccessibility = false;
        boolean isSealed = false;
        boolean isNonSealed = false;
        if (ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String mt = mod.getText();
                switch (mt) {
                    case "public" -> {
                        interfaceAccess |= Opcodes.ACC_PUBLIC;
                        hasExplicitAccessibility = true;
                    }
                    case "private" -> {
                        interfaceAccess |= Opcodes.ACC_PRIVATE;
                        hasExplicitAccessibility = true;
                    }
                    case "protected" -> {
                        interfaceAccess |= Opcodes.ACC_PROTECTED;
                        hasExplicitAccessibility = true;
                    }
                    case "static" -> interfaceAccess |= Opcodes.ACC_STATIC;
                    case "final" -> reportError(ctx, "Interface '" + currentClassName + "' cannot be declared final.");
                    case "sealed" -> isSealed = true;
                    case "non-sealed" -> isNonSealed = true;
                }
            }
        }
        if (isSealed && isNonSealed) {
            reportError(ctx, "Interface '" + currentClassName + "' cannot be both 'sealed' and 'non-sealed'.");
        }
        boolean isTopLevel = oldClassName == null;
        boolean isInsideInterface = oldClassName != null && (CompilerRegistry.globalIsInterfaceSet.contains(oldClassFqcn) || CompilerRegistry.globalIsInterfaceSet.contains(oldClassName));
        if (isInsideInterface && (interfaceAccess & (Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) != 0) {
            reportError(ctx, "Interface member interfaces cannot be declared 'private' or 'protected'. All interface member types are implicitly 'public static'.");
        }
        if (isTopLevel && (interfaceAccess & (Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) != 0) {
            reportError(ctx, "Top-level interface '" + currentClassName + "' cannot be declared 'private' or 'protected'.");
        }
        if (isTopLevel && (interfaceAccess & Opcodes.ACC_STATIC) != 0) {
            reportError(ctx, "Top-level interface '" + currentClassName + "' cannot be declared 'static'. Only inner member interfaces can be 'static'.");
        }
        if (!isTopLevel) {
            interfaceAccess |= Opcodes.ACC_STATIC;
        }
        if (isInsideInterface) {
            interfaceAccess = (interfaceAccess & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        }
        CompilerRegistry.globalClassAccess.put(fullPathKey, interfaceAccess);

        ClassSymbol ifaceSym = CompilerRegistry.getOrCreateClassSymbol(fullPathKey);
        ifaceSym.setInterface(true);
        ifaceSym.setAbstract(true);
        ifaceSym.setSuperClassName("java/lang/Object");
        if (interfaces != null) ifaceSym.setInterfaces(Arrays.asList(interfaces));
        List<String> ifaceSigs = CompilerRegistry.globalInterfaceGenericSignatureRegistry.get(fullPathKey);
        if (ifaceSigs != null) ifaceSym.setInterfaceGenericSignatures(ifaceSigs);
        ifaceSym.setTypeParameters(typeParams);
        ifaceSym.setAccessFlags(interfaceAccess);
        ifaceSym.setSealed(isSealed);

        if (isSealed) {
            CompilerRegistry.globalSealedClassSet.add(fullPathKey);
            List<String> permitted = new CopyOnWriteArrayList<>();
            if (ctx.restrictsClause() != null && ctx.restrictsClause().typeList() != null) {
                CompilerRegistry.globalExplicitRestrictsSet.add(fullPathKey);
                for (OceanParser.TypeContext tc : ctx.restrictsClause().typeList().type()) {
                    permitted.add(resolveInternalPath(tc.getText()));
                }
            }
            CompilerRegistry.globalPermittedSubclassesRegistry.put(fullPathKey, permitted);
            ifaceSym.setPermittedSubclasses(permitted);
            ifaceSym.setHasExplicitRestricts(ctx.restrictsClause() != null && ctx.restrictsClause().typeList() != null);
        }

        if (isSealed) {
            CompilerRegistry.globalSubclassStatusRegistry.put(fullPathKey, "sealed");
            ifaceSym.setSubclassStatus("sealed");
        } else if (isNonSealed) {
            CompilerRegistry.globalSubclassStatusRegistry.put(fullPathKey, "non-sealed");
            ifaceSym.setSubclassStatus("non-sealed");
        }
        CompilerRegistry.registerClassSymbol(ifaceSym);

        collectAllPermittedForInterfaces(fullPathKey, interfaces);

        if (ctx.memberDeclaration() != null) {
            for (OceanParser.MemberDeclarationContext mCtx : ctx.memberDeclaration()) {
                if (mCtx.block() != null && mCtx.STATIC() == null) {
                    reportError(mCtx.block(), "Initializer blocks ({ ... }) cannot be defined in interfaces. Only 'static { ... }' blocks are supported.");
                }
            }
        }

        super.visitInterfaceDeclaration(ctx);

        // SAM (Single Abstract Method) detection: if the interface declares exactly one
        // method, register it as a functional interface so TypeChecker.isFunctionalInterface()
        // can recognise user-defined @FunctionalInterface types without a hardcoded list.
        Map<String, String> absMethods = ClassMetadataCache.collectAbstractInterfaceMethods(fullPathKey, new HashSet<>());
        if (absMethods.size() == 1) {
            CompilerRegistry.globalFunctionalInterfaceRegistry.add(fullPathKey);
            String simpleName = OceanTypeSystem.findSimpleName(fullPathKey);
            if (!simpleName.equals(fullPathKey)) {
                CompilerRegistry.globalFunctionalInterfaceRegistry.add(simpleName);
            }
        }

        currentClassName = oldClassName;
        currentSuperName = oldSuperName;
        if (session != null) {
            session.setCurrentClassFqcn(oldClassFqcn);
        }
        return null;
    }

    @Override
    public Void visitAnnotationDeclaration(OceanParser.AnnotationDeclarationContext ctx) {
        String oldClassName = currentClassName;
        String oldSuperName = currentSuperName;

        String annoSimpleName = ctx.anyId().getText();
        if (oldClassName != null) {
            currentClassName = oldClassName + "$" + annoSimpleName;
            if (definedClasses.contains(currentClassName)) {
                reportError(ctx.anyId(), "Duplicate nested type declaration: '" + annoSimpleName + "' is already defined in '" + oldClassName + "'.");
            }
        } else {
            currentClassName = annoSimpleName;
            if (definedTopLevelTypes.contains(annoSimpleName)) {
                reportError(ctx.anyId(), "Duplicate annotation declaration: '" + annoSimpleName + "' is already defined in this compilation unit.");
            }
            definedClasses.add(annoSimpleName);
            definedTopLevelTypes.add(annoSimpleName);
            if (importedClasses.containsKey(annoSimpleName)) {
                reportError(ctx.anyId(), "Top-level type '" + annoSimpleName + "' conflicts with imported type '" + importedClasses.get(annoSimpleName).replace('/', '.') + "' in the same compilation unit.");
            }
            if (importedStaticMembers.containsKey(annoSimpleName)) {
                reportError(ctx.anyId(), "Top-level type '" + annoSimpleName + "' conflicts with statically imported member in the same compilation unit.");
            }
        }
        definedClasses.add(currentClassName);
        definedClasses.add(annoSimpleName);

        String fullPathKey = getCurrentClassPath();
        CompilationSession session = CompilationSession.getActiveSession();
        String oldClassFqcn = null;
        if (session != null) {
            oldClassFqcn = session.getCurrentClassFqcn();
            session.setCurrentClassFqcn(fullPathKey);
        }
        definedClasses.add(fullPathKey);
        CompilerRegistry.globalSuperClassRegistry.put(fullPathKey, "java/lang/Object");
        CompilerRegistry.globalInterfaceRegistry.put(fullPathKey, new String[] { "java/lang/annotation/Annotation" });

        validateModifiers(ctx, ctx.modifier());
        boolean isTopLevel = oldClassName == null;
        if (ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String mt = mod.getText();
                if (isTopLevel && ("private".equals(mt) || "protected".equals(mt))) {
                    reportError(ctx, "Top-level annotation '" + annoSimpleName + "' cannot be declared 'private' or 'protected'.");
                }
                if ("final".equals(mt) || "sealed".equals(mt) || "non-sealed".equals(mt)) {
                    reportError(mod, "Annotation '" + annoSimpleName + "' cannot be declared '" + mt + "'. All annotations are implicitly abstract.");
                }
            }
        }

        int access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        CompilerRegistry.globalClassAccess.put(fullPathKey, access);
        CompilerRegistry.globalIsInterfaceSet.add(fullPathKey);
        CompilerRegistry.globalAbstractClassSet.add(fullPathKey);

        String retention = "RUNTIME";
        Set<String> targets = new HashSet<>();
        boolean isRepeatable = false;
        if (ctx.annotation() != null) {
            for (OceanParser.AnnotationContext ac : ctx.annotation()) {
                String aName = ac.typeName().getText();
                if (aName.equals("Retention") || aName.equals("java/lang/annotation/Retention")) {
                    if (ac.annotationElement() != null) {
                        for (OceanParser.AnnotationElementContext elem : ac.annotationElement()) {
                            if (elem.expression() != null) {
                                String text = elem.expression().getText();
                                if (text.endsWith("SOURCE")) retention = "SOURCE";
                                else if (text.endsWith("CLASS")) retention = "CLASS";
                                else if (text.endsWith("RUNTIME")) retention = "RUNTIME";
                            }
                        }
                    }
                } else if (aName.equals("Repeatable") || aName.equals("java/lang/annotation/Repeatable")) {
                    isRepeatable = true;
                }
            }
        }
        Map<String, CompilerRegistry.AnnotationMemberInfo> memberInfoMap = new LinkedHashMap<>();
        Set<String> seenMemberNames = new HashSet<>();
        if (ctx.annotationMemberDeclaration() != null) {
            for (OceanParser.AnnotationMemberDeclarationContext member : ctx.annotationMemberDeclaration()) {
                String mName = member.anyId().getText();
                if (!seenMemberNames.add(mName)) {
                    reportError(member.anyId(), "Duplicate member name in annotation: '" + mName + "'.");
                }
                if (member.modifier() != null) {
                    for (OceanParser.ModifierContext mod : member.modifier()) {
                        String mt = mod.getText();
                        if (!"public".equals(mt) && !"abstract".equals(mt)) {
                            reportError(mod, "Modifier '" + mt + "' not allowed on annotation method. Only 'public' and 'abstract' are permitted: '" + mName + "'.");
                        }
                    }
                }
                String mType = member.type() != null ? member.type().getText() : "void";
                String mDesc = "void".equals(mType) ? "V" : SymbolTable.getDescriptor(mType, importedClasses, new HashSet<>());
                org.antlr.v4.runtime.ParserRuleContext errCtx = member.type() != null ? member.type() : member;
                if (!isValidAnnotationMemberType(mDesc, mType)) {
                    reportError(errCtx, "Invalid annotation member return type: '" + mType + "'. Annotation methods can only return primitive types, String, Class, enum, annotation, or 1-dimensional arrays thereof.");
                }
                boolean hasDef = (member.DEFAULT() != null);
                if (hasDef && member.expression() != null) {
                    String defText = member.expression().getText();
                    if ("null".equals(defText)) {
                        reportError(member.expression(), "Annotation default value cannot be 'null'.");
                    }
                }
                memberInfoMap.put(mName, new CompilerRegistry.AnnotationMemberInfo(mName, mDesc, hasDef));
                CompilerRegistry.globalMethodRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                        .put(mName, "()" + mDesc);
            }
        }
        CompilerRegistry.AnnotationTypeInfo typeInfo = new CompilerRegistry.AnnotationTypeInfo(
                fullPathKey, targets, retention, isRepeatable, memberInfoMap
        );
        CompilerRegistry.globalAnnotationTypeRegistry.put(fullPathKey, typeInfo);
        CompilerRegistry.globalAnnotationTypeRegistry.put(annoSimpleName, typeInfo);
        CompilerRegistry.globalAnnotationTypeRegistry.put(currentClassName, typeInfo);

        super.visitAnnotationDeclaration(ctx);

        currentClassName = oldClassName;
        currentSuperName = oldSuperName;
        if (session != null) {
            session.setCurrentClassFqcn(oldClassFqcn);
        }
        return null;
    }

    private boolean isValidAnnotationMemberType(String mDesc, String mType) {
        if (mDesc == null || mDesc.isEmpty()) return false;
        String desc = TypeChecker.cleanDescriptor(mDesc);
        if (desc.startsWith("[[")) {
            //
            return false;
        }
        String comp = desc.startsWith("[") ? desc.substring(1) : desc;
        // 1. Primitive types
        if (TypeChecker.isPrimitive(comp)) {
            return !"V".equals(comp); // void is prohibited
        }
        // 2. String
        if (OceanTypeSystem.STRING_DESC.equals(comp) || "java/lang/String".equals(comp) || "String".equals(comp)) {
            return true;
        }
        // 3. Class or Class<?>
        if ("Ljava/lang/Class;".equals(comp) || "java/lang/Class".equals(comp) || "Class".equals(comp)
                || (mType != null && (mType.equals("Class") || mType.startsWith("Class<")))) {
            return true;
        }
        // 4. Enum check
        if (TypeChecker.isEnumType(comp) || !ClassMetadataCache.getEnumConstants(comp).isEmpty()) {
            return true;
        }
        // 5. Annotation check
        String cleanInternal = comp.startsWith("L") && comp.endsWith(";") ? comp.substring(1, comp.length() - 1) : comp;
        if (ClassMetadataCache.isAnnotation(cleanInternal)
                || CompilerRegistry.globalAnnotationTypeRegistry.containsKey(cleanInternal)
                || CompilerRegistry.globalAnnotationTypeRegistry.containsKey(OceanTypeSystem.findSimpleName(cleanInternal))
                || (CompilerRegistry.globalClassAccess.getOrDefault(cleanInternal, 0) & Opcodes.ACC_ANNOTATION) != 0
                || (CompilerRegistry.globalClassAccess.getOrDefault(OceanTypeSystem.findSimpleName(cleanInternal), 0) & Opcodes.ACC_ANNOTATION) != 0) {
            return true;
        }
        // 6. Check resolved path for local enums / annotations
        String resolved = resolveInternalPath(cleanInternal);
        if (resolved != null) {
            if (ClassMetadataCache.isAnnotation(resolved)
                    || CompilerRegistry.globalAnnotationTypeRegistry.containsKey(resolved)
                    || CompilerRegistry.globalAnnotationTypeRegistry.containsKey(OceanTypeSystem.findSimpleName(resolved))
                    || (CompilerRegistry.globalClassAccess.getOrDefault(resolved, 0) & Opcodes.ACC_ANNOTATION) != 0
                    || (CompilerRegistry.globalClassAccess.getOrDefault(OceanTypeSystem.findSimpleName(resolved), 0) & Opcodes.ACC_ANNOTATION) != 0) {
                return true;
            }
            if (TypeChecker.isEnumType(resolved) || !ClassMetadataCache.getEnumConstants(resolved).isEmpty()) {
                return true;
            }
            if (CompilerRegistry.globalEnumConstants.containsKey(resolved)) {
                return true;
            }
        }
        return CompilerRegistry.globalEnumConstants.containsKey(cleanInternal)
                || CompilerRegistry.globalEnumConstants.containsKey(OceanTypeSystem.findSimpleName(cleanInternal));
    }

    @Override
    public Void visitEnumDeclaration(OceanParser.EnumDeclarationContext ctx) {
        String oldClassName = currentClassName;
        String oldSuperName = currentSuperName;

        String enumSimpleName = ctx.anyId().getText();
        if (oldClassName != null) {
            currentClassName = oldClassName + "$" + enumSimpleName;
            if (definedClasses.contains(currentClassName)) {
                reportError(ctx.anyId(), "Duplicate nested type declaration: '" + enumSimpleName + "' is already defined in '" + oldClassName + "'.");
            }
        } else {
            currentClassName = enumSimpleName;
            if (definedTopLevelTypes.contains(enumSimpleName)) {
                reportError(ctx.anyId(), "Duplicate enum declaration: '" + enumSimpleName + "' is already defined in this compilation unit.");
            }
            definedClasses.add(enumSimpleName);
            definedTopLevelTypes.add(enumSimpleName);
            if (importedClasses.containsKey(enumSimpleName)) {
                reportError(ctx.anyId(), "Top-level type '" + enumSimpleName + "' conflicts with imported type '" + importedClasses.get(enumSimpleName).replace('/', '.') + "' in the same compilation unit.");
            }
            if (importedStaticMembers.containsKey(enumSimpleName)) {
                reportError(ctx.anyId(), "Top-level type '" + enumSimpleName + "' conflicts with statically imported member in the same compilation unit.");
            }
        }
        definedClasses.add(currentClassName);
        currentSuperName = "java/lang/Enum";

        String fullPathKey = getCurrentClassPath();
        CompilationSession session = CompilationSession.getActiveSession();
        String oldClassFqcn = null;
        if (session != null) {
            oldClassFqcn = session.getCurrentClassFqcn();
            session.setCurrentClassFqcn(fullPathKey);
        }
        CompilerRegistry.globalMethodRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>());
        CompilerRegistry.globalSuperClassRegistry.put(fullPathKey, currentSuperName);
        CompilerRegistry.globalInterfaceRegistry.put(fullPathKey, new String[0]);

        validateModifiers(ctx, ctx.modifier());
        int enumAccess = Opcodes.ACC_ENUM | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        if (ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String mt = mod.getText();
                switch (mt) {
                    case "public" -> enumAccess |= Opcodes.ACC_PUBLIC;
                    case "private" -> enumAccess |= Opcodes.ACC_PRIVATE;
                    case "protected" -> enumAccess |= Opcodes.ACC_PROTECTED;
                    case "abstract", "final", "sealed", "non-sealed" ->
                            reportError(ctx, "Enum '" + currentClassName + "' cannot be declared '" + mt + "'.");
                }
            }
        }
        boolean isTopLevel = oldClassName == null;
        boolean isInsideInterface = oldClassName != null && (CompilerRegistry.globalIsInterfaceSet.contains(oldClassFqcn) || CompilerRegistry.globalIsInterfaceSet.contains(oldClassName));
        if (isInsideInterface && (enumAccess & (Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) != 0) {
            reportError(ctx, "Interface member enums cannot be declared 'private' or 'protected'. All interface member types are implicitly 'public static'.");
        }
        if (isTopLevel && (enumAccess & (Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) != 0) {
            reportError(ctx, "Top-level enum '" + currentClassName + "' cannot be declared 'private' or 'protected'.");
        }
        if (!isTopLevel) {
            enumAccess |= Opcodes.ACC_STATIC;
        }
        if (isInsideInterface) {
            enumAccess = (enumAccess & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        }
        CompilerRegistry.globalClassAccess.put(fullPathKey, enumAccess);

        ClassSymbol enumSym = CompilerRegistry.getOrCreateClassSymbol(fullPathKey);
        enumSym.setEnum(true);
        CompilerRegistry.globalSubclassStatusRegistry.put(fullPathKey, "final");
        enumSym.setSubclassStatus("final");
        enumSym.setSuperClassName("java/lang/Enum");
        enumSym.setAccessFlags(enumAccess);

        // Register enum constants into global registries
        String enumDesc = OceanTypeSystem.wrapObjectType(fullPathKey);
        if (ctx.enumConstants() != null) {
            Set<String> seenConsts = new HashSet<>();
            List<String> constList = new ArrayList<>();
            for (OceanParser.EnumConstantContext ec : ctx.enumConstants().enumConstant()) {
                String constName = ec.anyId().getText();
                if (!seenConsts.add(constName)) {
                    reportError(ctx, "Duplicate enum constant: '" + constName + "'");
                }
                constList.add(constName);
                CompilerRegistry.globalFieldRegistry.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                        .put(constName, enumDesc);
                CompilerRegistry.globalFieldStaticity.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                        .put(constName, true);
                CompilerRegistry.globalFieldAccess.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                        .put(constName, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM);
                CompilerRegistry.globalFieldMutability.computeIfAbsent(fullPathKey, k -> new ConcurrentHashMap<>())
                        .put(constName, true);

                enumSym.addField(new FieldSymbol(fullPathKey, constName, enumDesc, enumDesc, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM, true, false));
            }
            CompilerRegistry.globalEnumConstants.put(fullPathKey, constList);
            CompilerRegistry.globalEnumConstants.put(currentClassName, constList);
            enumSym.setEnumConstants(constList);
        }

        boolean hasConstantBodies = false;
        int anonIndex = 0;
        if (ctx.enumConstants() != null) {
            for (OceanParser.EnumConstantContext ec : ctx.enumConstants().enumConstant()) {
                if (ec.LBRACE() != null && ec.memberDeclaration() != null && !ec.memberDeclaration().isEmpty()) {
                    hasConstantBodies = true;
                    anonIndex++;
                    String subName = currentClassName + "$" + anonIndex;
                    String subFqcn = fullPathKey + "$" + anonIndex;
                    definedClasses.add(subName);
                    definedClasses.add(subFqcn);
                    CompilerRegistry.globalSuperClassRegistry.put(subFqcn, fullPathKey);
                    CompilerRegistry.globalSuperClassRegistry.put(subName, fullPathKey);
                    CompilerRegistry.globalClassAccess.put(subFqcn, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM | Opcodes.ACC_SUPER);
                    CompilerRegistry.globalClassAccess.put(subName, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM | Opcodes.ACC_SUPER);

                    ClassSymbol subSym = CompilerRegistry.getOrCreateClassSymbol(subFqcn);
                    subSym.setEnum(true);
                    subSym.setSuperClassName(fullPathKey);
                    subSym.setAccessFlags(Opcodes.ACC_FINAL | Opcodes.ACC_ENUM);

                    // Pre-scan members of this constant subclass
                    String prevClass = currentClassName;
                    String prevSuper = currentSuperName;
                    currentClassName = subName;
                    currentSuperName = fullPathKey;
                    if (session != null) session.setCurrentClassFqcn(subFqcn);

                    for (OceanParser.MemberDeclarationContext mem : ec.memberDeclaration()) {
                        visit(mem);
                    }

                    currentClassName = prevClass;
                    currentSuperName = prevSuper;
                    if (session != null) session.setCurrentClassFqcn(fullPathKey);
                }
            }
        }

        boolean oldEnumHasConstantBodies = currentEnumHasConstantBodies;
        currentEnumHasConstantBodies = hasConstantBodies;
        try {
            if (ctx.memberDeclaration() != null) {
                for (OceanParser.MemberDeclarationContext mem : ctx.memberDeclaration()) {
                    visit(mem);
                }
            }
        } finally {
            currentEnumHasConstantBodies = oldEnumHasConstantBodies;
        }

        if (hasConstantBodies) {
            enumAccess = (enumAccess & ~Opcodes.ACC_FINAL);
        }
        // Check if any declared method in the enum is abstract
        Map<String, Integer> accessMap = CompilerRegistry.globalMethodAccess.get(fullPathKey);
        if (accessMap != null) {
            for (int acc : accessMap.values()) {
                if ((acc & Opcodes.ACC_ABSTRACT) != 0) {
                    enumAccess |= Opcodes.ACC_ABSTRACT;
                    enumAccess &= ~Opcodes.ACC_FINAL;
                    break;
                }
            }
        }
        CompilerRegistry.globalClassAccess.put(fullPathKey, enumAccess);
        enumSym.setAccessFlags(enumAccess);

        // Auto-inject valueOf and values methods for Enums
        Map<String, String> mReg = CompilerRegistry.globalMethodRegistry.get(fullPathKey);
        mReg.put("values", "()[L" + fullPathKey + ";");
        mReg.put("valueOf", "(Ljava/lang/String;)L" + fullPathKey + ";");
        enumSym.addMethod(new MethodSymbol(fullPathKey, "values", "()[L" + fullPathKey + ";", "[L" + fullPathKey + ";", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, true, false));
        enumSym.addMethod(new MethodSymbol(fullPathKey, "valueOf", "(Ljava/lang/String;)L" + fullPathKey + ";", "L" + fullPathKey + ";", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, true, false));
        CompilerRegistry.registerClassSymbol(enumSym);

        currentClassName = oldClassName;
        currentSuperName = oldSuperName;
        if (session != null) {
            session.setCurrentClassFqcn(oldClassFqcn);
        }
        return null;
    }

    private void processMethod(ParserRuleContext ctx, String methodName, boolean isMain,
                               List<OceanParser.ModifierContext> modifiers,
                               OceanParser.ParameterListContext parameterList,
                               OceanParser.BlockContext block,
                               String extendedClass) {

        currentMethodTypeParameters.clear();
        if (ctx instanceof OceanParser.NormalMethodContext nmCtx) {
            if (nmCtx.typeParameter() != null) {
                List<CompilerRegistry.TypeParameterInfo> mTypeParams = extractTypeParameters(nmCtx.typeParameter());
                for (CompilerRegistry.TypeParameterInfo info : mTypeParams) {
                    currentMethodTypeParameters.add(info.name);
                }
            }
        }
        validateModifiers(ctx, modifiers);
        int access = 0; // Default to package-private!
        boolean hasExplicitAccess = false;
        if (isMain) {
            access = Opcodes.ACC_PUBLIC;
            hasExplicitAccess = true;
        }
        boolean isStatic = isMain;
        boolean isSynchronized = false;
        boolean isAbstract = false;
        boolean isNative = false;
        boolean hasDefault = false;
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
                    case "native" -> {
                        isNative = true;
                        access |= Opcodes.ACC_NATIVE;
                    }
                    case "default" -> {
                        hasDefault = true;
                        if (!CompilerRegistry.globalIsInterfaceSet.contains(getCurrentClassPath())) {
                            reportError(ctx, "Modifier 'default' is only allowed on interface methods.");
                        }
                    }
                }
            }
        }
        if (block == null && !isNative) {
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
        if (isExtension) {
            isStatic = true; // Extension methods are static in bytecode
            if ("void".equals(extendedClass) || "null".equals(extendedClass)) {
                reportError(ctx,  "Extension method cannot be defined on type '" + extendedClass + "'.");
            }
        } else {
            if (isAbstract && isStatic) {
                reportError(ctx, "Method '" + methodName + "' cannot be both 'abstract' and 'static'.");
            }
            if (isAbstract && (access & Opcodes.ACC_PRIVATE) != 0) {
                reportError(ctx, "Method '" + methodName + "' cannot be both 'private' and 'abstract'.");
            }
            if (isAbstract && (access & Opcodes.ACC_FINAL) != 0) {
                reportError(ctx, "Method '" + methodName + "' cannot be both 'abstract' and 'final'.");
            }
            if (isAbstract && isNative) {
                reportError(ctx, "Method '" + methodName + "' cannot be both 'native' and 'abstract'.");
            }
            if (isAbstract && isSynchronized) {
                reportError(ctx, "Method '" + methodName + "' cannot be both 'abstract' and 'lock/sync'.");
            }
            if (isNative && block != null) {
                reportError(ctx, "Native methods cannot declare a body: '" + methodName + "'");
            }
        }

        if (isStatic && !isExtension && !methodName.equals("<init>") && !methodName.equals("<clinit>") && currentClassName != null) {
            String fullPath = getCurrentClassPath();
            List<CompilerRegistry.TypeParameterInfo> infos = CompilerRegistry.globalTypeParameterRegistry.get(fullPath);
            if (infos != null && !infos.isEmpty()) {
                Set<String> forbiddenClassTps = new HashSet<>();
                for (CompilerRegistry.TypeParameterInfo info : infos) {
                    if (!currentMethodTypeParameters.contains(info.name)) {
                        forbiddenClassTps.add(info.name);
                    }
                }
                if (!forbiddenClassTps.isEmpty()) {
                    String retTypeStr = determineMethodReturnType(ctx, "void");
                    String matchedRet = findContainedGenericParameter(retTypeStr, forbiddenClassTps);
                    if (matchedRet != null) {
                        reportError(ctx, "Static methods cannot reference class type parameter ('" + matchedRet + "').");
                    }
                    if (parameterList != null) {
                        for (OceanParser.ParameterContext p : parameterList.parameter()) {
                            String pTypeStr = p.type() != null ? p.type().getText() : null;
                            String matchedP = findContainedGenericParameter(pTypeStr, forbiddenClassTps);
                            if (matchedP != null) {
                                reportError(p, "Static methods cannot reference class type parameter ('" + matchedP + "').");
                            }
                        }
                    }
                }
            }
        }

        boolean isInterfaceMethod = CompilerRegistry.globalIsInterfaceSet.contains(getCurrentClassPath());
        if (isInterfaceMethod) {
            if (isNative) {
                reportError(ctx, "Interface methods cannot be declared 'native': '" + methodName + "'");
            }
            if ((access & Opcodes.ACC_PROTECTED) != 0) {
                reportError(ctx, "Interface methods cannot be declared 'protected'.");
            }
            if ((access & Opcodes.ACC_FINAL) != 0) {
                reportError(ctx, "Interface methods cannot be declared 'final'.");
            }
            if (isSynchronized) {
                reportError(ctx, "Interface methods cannot be declared 'sync'.");
            }
            if (hasDefault && isStatic) {
                reportError(ctx, "Interface method cannot be both 'default' and 'static'.");
            }
            if (hasDefault && isAbstract) {
                reportError(ctx, "Interface method cannot be both 'default' and 'abstract'.");
            }
            if (hasDefault && (access & Opcodes.ACC_PRIVATE) != 0) {
                reportError(ctx, "Interface method cannot be both 'default' and 'private'.");
            }
            if (hasDefault && block == null) {
                reportError(ctx, "Default method must have a body.");
            }
            if (isStatic && block == null) {
                reportError(ctx, "Static methods in interfaces must have a body: '" + methodName + "'");
            }
            if ((access & Opcodes.ACC_PRIVATE) != 0 && block == null) {
                reportError(ctx, "Private methods in interfaces must have a body: '" + methodName + "'");
            }
            if (!hasExplicitAccess) {
                access |= Opcodes.ACC_PUBLIC;
            }
            if (block != null && !isStatic && (access & Opcodes.ACC_PRIVATE) == 0) {
                access &= ~Opcodes.ACC_ABSTRACT;
                isAbstract = false;
            }
        }

        if (!isInterfaceMethod && isAbstract) {
            boolean currentIsAbstractClass = (CompilerRegistry.globalClassAccess.getOrDefault(getCurrentClassPath(), 0) & Opcodes.ACC_ABSTRACT) != 0
                    || CompilerRegistry.globalAbstractClassSet.contains(getCurrentClassPath());
            boolean isEnumClass = "java/lang/Enum".equals(currentSuperName) || CompilerRegistry.globalEnumConstants.containsKey(getCurrentClassPath());
            if (!currentIsAbstractClass && !isEnumClass) {
                reportError(ctx, "Non-abstract class '" + currentClassName + "' cannot declare abstract method '" + methodName + "'. The class must be declared 'abstract'.");
            }
        }

        if (isExtension) {
            String extendedTypeDesc = getTypeDescriptor(extendedClass);
            descriptor.append(extendedTypeDesc);
        }

        List<CompilerRegistry.MethodParamInfo> paramInfos = new ArrayList<>();
        boolean hasVarargs = false;
        int totalSlots = isStatic ? 0 : 1;
        if (isExtension && extendedClass != null) {
            totalSlots += ("long".equals(extendedClass) || "double".equals(extendedClass)) ? 2 : 1;
        }
        if (isMain) {
            descriptor.append("[Ljava/lang/String;");
            totalSlots += 1;
        } else if (parameterList != null) {
            List<OceanParser.ParameterContext> params = parameterList.parameter();
            for (int i = 0; i < params.size(); i++) {
                OceanParser.ParameterContext p = params.get(i);
                String pName = p.anyId().getText();
                if (p.type() == null && (p.VARIABLE() != null || p.VALUE() != null)) {
                    reportError(ctx, "Cannot use 'variable' / 'value' type for method or constructor parameter: '" + pName + "'");
                }
                String pType = p.type() != null ? p.type().getText() : "Object";
                if (p.type() != null && countArrayDimensions(pType) > 255) {
                    reportError(p, "Array dimension limit exceeded (maximum 255 dimensions supported).");
                }
                if (pType.equals("variable") || pType.equals("var") || pType.equals("value")) {
                    reportError(ctx, "Cannot use 'variable' / 'value' type for method or constructor parameter: '" + pName + "'");
                }
                if (!isStatic && !methodName.equals("<init>")) {
                    checkVariance(p, pType, getCurrentClassPath(), true);
                }
                String pDesc = getTypeDescriptor(pType);
                if (p.ELLIPSIS() != null) {
                    if (i != params.size() - 1) {
                        reportError(p, "Varargs parameter ('...') must be the last parameter in the parameter list.");
                    }
                    hasVarargs = true;
                    pDesc = "[" + pDesc;
                }
                descriptor.append(pDesc);
                paramInfos.add(new CompilerRegistry.MethodParamInfo(pName, pType, pDesc, p.expression()));
                if (p.ELLIPSIS() == null && ("long".equals(pType) || "double".equals(pType))) {
                    totalSlots += 2;
                } else {
                    totalSlots += 1;
                }
            }
        }
        if (totalSlots > 255) {
            reportError(ctx, "Method '" + methodName + "' parameter slots limit exceeded (" + totalSlots + " > 255). JVM permits at most 255 parameter slots.");
        }
        if (!paramInfos.isEmpty()) {
            Map<String, List<CompilerRegistry.MethodParamInfo>> classMap = CompilerRegistry.globalMethodParamsRegistry
                .computeIfAbsent(getCurrentClassPath(), k -> new ConcurrentHashMap<>());
            classMap.put(methodName + "#" + paramInfos.size(), paramInfos);
            classMap.put(methodName, paramInfos);
        }
        descriptor.append(")");
        String returnTypeDesc;
        if (isMain || methodName.equals("<init>")) {
            returnTypeDesc = "V";
        } else {
            String detectedType = "void";
            detectedType = determineMethodReturnType(ctx,detectedType);
            if (detectedType != null && countArrayDimensions(detectedType) > 255) {
                reportError(ctx, "Array dimension limit exceeded (maximum 255 dimensions supported).");
            }
            if (!isStatic) {
                checkVariance(ctx, detectedType, getCurrentClassPath(), false);
            }
            returnTypeDesc = getTypeDescriptor(detectedType);
        }

        boolean isAsync = modifiers != null && modifiers.stream().anyMatch(m -> m.getText().equals("async"));
        String originalReturnType = "void";
        if (!isMain && !methodName.equals("<init>")) {
            originalReturnType = determineMethodReturnType(ctx,originalReturnType);
        }

        if (isAsync) {
            String boxedRealRet = TypeChecker.isPrimitive(returnTypeDesc) ? OceanTypeSystem.getBoxedDescriptor(returnTypeDesc) : returnTypeDesc;
            returnTypeDesc = "Ljava/util/concurrent/CompletableFuture<" + boxedRealRet + ">;";
        }

        descriptor.append(returnTypeDesc);
        String classKey = getCurrentClassPath();

        if (isAsync) {
            CompilerRegistry.globalAsyncMethodSet.add(classKey + "#" + methodName);
            CompilerRegistry.globalAsyncMethodSet.add(classKey + "#" + methodName + descriptor);
        }

        CompilerRegistry.globalMethodRegistry.computeIfAbsent(classKey, k -> new ConcurrentHashMap<>())
                .put(methodName, descriptor.toString());
        if (!paramInfos.isEmpty()) {
            CompilerRegistry.globalMethodParamsRegistry
                .computeIfAbsent(classKey, k -> new ConcurrentHashMap<>())
                .put(methodName + descriptor, paramInfos);
        }


        if (!currentMethodTypeParameters.isEmpty()) {
            CompilerRegistry.globalMethodTypeParametersRegistry.computeIfAbsent(classKey, k -> new ConcurrentHashMap<>())
                    .put(methodName, new ArrayList<>(currentMethodTypeParameters));
            CompilerRegistry.globalMethodTypeParametersRegistry.computeIfAbsent(classKey, k -> new ConcurrentHashMap<>())
                    .put(methodName + descriptor, new ArrayList<>(currentMethodTypeParameters));
            String simpleName = OceanTypeSystem.findSimpleName(classKey);
            if (!simpleName.equals(classKey)) {
                CompilerRegistry.globalMethodTypeParametersRegistry.computeIfAbsent(simpleName, k -> new ConcurrentHashMap<>())
                        .put(methodName, new ArrayList<>(currentMethodTypeParameters));
                CompilerRegistry.globalMethodTypeParametersRegistry.computeIfAbsent(simpleName, k -> new ConcurrentHashMap<>())
                        .put(methodName + descriptor, new ArrayList<>(currentMethodTypeParameters));
            }
        }

        if (!isMain && !methodName.equals("<init>")) {
            if (isAsync || !originalReturnType.equals("void")) {
                String effectiveGenericReturn = originalReturnType;
                if (effectiveGenericReturn.contains("<")) {
                    int lt = effectiveGenericReturn.indexOf('<');
                    String base = effectiveGenericReturn.substring(0, lt).trim();
                    if (!base.contains("/")) {
                        String std = OceanTypeSystem.resolveStandardClassPath(base);
                        if (std != null) {
                            effectiveGenericReturn = std + effectiveGenericReturn.substring(lt);
                        } else if (importedClasses.containsKey(base)) {
                            effectiveGenericReturn = importedClasses.get(base) + effectiveGenericReturn.substring(lt);
                        }
                    }
                }
                Map<String, String> reg = CompilerRegistry.globalMethodGenericReturnTypeRegistry.computeIfAbsent(classKey, k -> new ConcurrentHashMap<>());
                reg.put(methodName + "#" + paramInfos.size(), effectiveGenericReturn);
                reg.putIfAbsent(methodName, effectiveGenericReturn);
            }
        }
        // Register overload: keep ALL descriptors for a method name
        CompilerRegistry.globalOverloadRegistry.computeIfAbsent(classKey, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(methodName, k -> new CopyOnWriteArrayList<>());
        List<String> overloads = CompilerRegistry.globalOverloadRegistry.get(classKey).get(methodName);
        if (!overloads.contains(descriptor.toString())) {
            overloads.add(descriptor.toString());
        }

        // Register truncated parameter descriptors for default parameters
        if (!paramInfos.isEmpty()) {
            int defaultCount = 0;
            for (int i = paramInfos.size() - 1; i >= 0; i--) {
                if (paramInfos.get(i).defaultExpr() != null) {
                    defaultCount++;
                } else {
                    break;
                }
            }
            if (defaultCount > 0) {
                for (int take = paramInfos.size() - defaultCount; take < paramInfos.size(); take++) {
                    StringBuilder trDesc = new StringBuilder("(");
                    for (int j = 0; j < take; j++) {
                        trDesc.append(paramInfos.get(j).typeDesc());
                    }
                    trDesc.append(")").append(returnTypeDesc);
                    if (!overloads.contains(trDesc.toString())) {
                        overloads.add(trDesc.toString());
                    }
                }
            }
        }

        // --- throws listesini kaydet ---
        List<String> thrownExceptions = new ArrayList<>();
        if (ctx instanceof OceanParser.NormalMethodContext nmCtx) {
            if (nmCtx.typeList() != null) {
                for (OceanParser.TypeContext tc : nmCtx.typeList().type()) {
                    String excPath = resolveInternalPath(tc.getText());
                    if (excPath != null) thrownExceptions.add(excPath);
                }
            }
        }
        if (!thrownExceptions.isEmpty()) {
            CompilerRegistry.globalMethodThrowsRegistry
                    .computeIfAbsent(classKey, k -> new ConcurrentHashMap<>())
                    .put(methodName + descriptor, thrownExceptions);
        }
        CompilerRegistry.globalMethodStaticity.computeIfAbsent(classKey, k -> new ConcurrentHashMap<>())
                .put(methodName, isStatic);
        CompilerRegistry.globalMethodStaticity.get(classKey).put(methodName + descriptor, isStatic);
        int finalAccess = access;
        if (isStatic) {
            finalAccess |= Opcodes.ACC_STATIC;
        }
        if (isSynchronized) {
            finalAccess |= Opcodes.ACC_SYNCHRONIZED;
        }
        if (hasVarargs) {
            finalAccess |= Opcodes.ACC_VARARGS;
        }
        if (methodName.equals("<init>") && "java/lang/Enum".equals(currentSuperName)) {
            if (hasExplicitAccess && (finalAccess & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
                reportError(ctx, "Enum constructor cannot be declared 'public' or 'protected'.");
            }
            if (!hasExplicitAccess) {
                finalAccess = currentEnumHasConstantBodies ? 0 : Opcodes.ACC_PRIVATE;
            }
        } else if (!hasExplicitAccess) {
            finalAccess |= Opcodes.ACC_PUBLIC;
        }
        CompilerRegistry.globalMethodAccess.computeIfAbsent(classKey, k -> new ConcurrentHashMap<>())
                .put(methodName, finalAccess);
        CompilerRegistry.globalMethodAccess.get(classKey).put(methodName + descriptor, finalAccess);
        if (extendedClass != null) {
            String receiverDesc = TypeChecker.cleanDescriptor(getTypeDescriptor(extendedClass));

            CompilerRegistry.globalExtensionMethodRegistry
                    .computeIfAbsent(receiverDesc, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(methodName, k -> new CopyOnWriteArrayList<>())
                    .add(new CompilerRegistry.ExtensionMethodInfo(classKey, methodName, descriptor.toString()));
        }

        ClassSymbol classSym = CompilerRegistry.getOrCreateClassSymbol(classKey);
        MethodSymbol methodSym = new MethodSymbol(classKey, methodName, descriptor.toString(), originalReturnType, finalAccess, isStatic, isAsync);
        methodSym.setTypeParameters(new ArrayList<>(currentMethodTypeParameters));
        methodSym.setThrownExceptions(thrownExceptions);
        methodSym.setParamInfos(paramInfos);
        classSym.addMethod(methodSym);

        if (block != null) {
            visit(block);
        }

        currentMethodTypeParameters.clear();
    }

    private String determineMethodReturnType(ParserRuleContext ctx,String originalReturnType) {
        for (int i = 0; i < ctx.getChildCount(); i++) {
            if (ctx.getChild(i).getText().equals("void")) {
                originalReturnType = "void";
                break;
            }
            if (ctx.getChild(i) instanceof OceanParser.TypeContext) {
                originalReturnType = ctx.getChild(i).getText();
                break;
            }
        }
        return originalReturnType;
    }

    @Override
    public Void visitNormalMethod(OceanParser.NormalMethodContext ctx) {
        if (ctx.anyId() == null) return null;
        String methodName = ctx.anyId().getText();
        String extendedClass = ctx.extType != null ? ctx.extType.getText() : null;
        boolean isMain = methodName.equals("main");
        if (methodName.equals(currentClassName)) {
            reportError(ctx,"Constructors cannot have a return type.");
        }
        if (currentClassName != null && (methodName.equals(currentClassName) || currentClassName.endsWith("/" + methodName) || currentClassName.endsWith("$" + methodName))) {
            methodName = "<init>";
            hasConstructor = true;
        }

        processMethod(ctx, methodName, isMain, ctx.modifier(), ctx.parameterList(), ctx.block(), extendedClass);
        return null;
    }

    @Override
    public Void visitMainMethod(OceanParser.MainMethodContext ctx) {
        processMethod(ctx, "main", true, ctx.modifier(), ctx.parameterList(), ctx.block(), null);
        return null;
    }

    @Override
    public Void visitConstructorDeclaration(OceanParser.ConstructorDeclarationContext ctx) {
        String currentClassPathKey = getCurrentClassPath();
        if (CompilerRegistry.globalIsInterfaceSet.contains(currentClassPathKey)) {
            reportError(ctx, "Interfaces cannot declare constructors.");
            return null;
        }
        String name = ctx.anyId().getText();
        boolean isRealConstructor = name.equals(currentClassName) || (currentClassName != null && (currentClassName.endsWith("$" + name) || currentClassName.endsWith("/" + name)));
        String methodName = isRealConstructor ? "<init>" : name;
        if (!isRealConstructor && !"main".equals(name)) {
            reportError(ctx,"Return type is required for all methods except constructors.");
        }
        if (isRealConstructor) {
            hasConstructor = true;

            if (ctx.parameterList() != null && ctx.block() != null) {
                String classPath = getCurrentClassPath();
                Map<String, String> fieldReg = CompilerRegistry.globalFieldRegistry.get(classPath);
                if (fieldReg != null) {
                    Map<String, String> paramTypes = new HashMap<>();
                    for (OceanParser.ParameterContext p : ctx.parameterList().parameter()) {
                        if (p.anyId() != null && p.type() != null) {
                            paramTypes.put(p.anyId().getText(), getTypeDescriptor(p.type().getText()));
                        }
                    }
                    inferConstructorFieldTypesFromAst(ctx.block(), paramTypes, fieldReg);
                }
            }
        }


        StringBuilder descriptor = new StringBuilder("(");
        if (isRealConstructor) {
            if ("java/lang/Enum".equals(currentSuperName)) {
                descriptor.append("Ljava/lang/String;I");
            } else {
                String classPath = getCurrentClassPath();
                if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(classPath)) {
                    String outerFqcn = CompilerRegistry.globalInnerClassUsedOuterMap.get(classPath);
                    descriptor.append("L").append(outerFqcn).append(";");
                }
            }
        }
        boolean isMain = "main".equals(name);
        boolean hasVarargs = false;
        int totalCtorSlots = 1;
        if (isRealConstructor) {
            if ("java/lang/Enum".equals(currentSuperName)) {
                totalCtorSlots += 2;
            } else {
                String classPath = getCurrentClassPath();
                if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(classPath)) {
                    totalCtorSlots += 1;
                }
            }
        }
        List<CompilerRegistry.MethodParamInfo> paramInfos = new ArrayList<>();
        if (isRealConstructor) {
            if ("java/lang/Enum".equals(currentSuperName)) {
                paramInfos.add(new CompilerRegistry.MethodParamInfo("$name", "String", "Ljava/lang/String;", null));
                paramInfos.add(new CompilerRegistry.MethodParamInfo("$ordinal", "int", "I", null));
            } else {
                String classPath = getCurrentClassPath();
                if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(classPath)) {
                    String outerFqcn = CompilerRegistry.globalInnerClassUsedOuterMap.get(classPath);
                    paramInfos.add(new CompilerRegistry.MethodParamInfo("this$0", outerFqcn, "L" + outerFqcn + ";", null));
                }
            }
        }
        if (isMain && (ctx.parameterList() == null || ctx.parameterList().parameter().isEmpty())) {
            descriptor.append("[Ljava/lang/String;");
            totalCtorSlots += 1;
        } else if (ctx.parameterList() != null) {
            List<OceanParser.ParameterContext> params = ctx.parameterList().parameter();
            for (int i = 0; i < params.size(); i++) {
                OceanParser.ParameterContext p = params.get(i);
                String pName = p.anyId().getText();
                if (p.type() == null && (p.VARIABLE() != null || p.VALUE() != null)) {
                    reportError(ctx, "Cannot use 'variable' / 'value' type for method or constructor parameter: '" + pName + "'");
                }
                String pType = p.type() != null ? p.type().getText() : "Object";
                if (p.type() != null && countArrayDimensions(pType) > 255) {
                    reportError(p, "Array dimension limit exceeded (maximum 255 dimensions supported).");
                }
                if (pType.equals("variable") || pType.equals("var") || pType.equals("value")) {
                    reportError(ctx, "Cannot use 'variable' / 'value' type for method or constructor parameter: '" + pName + "'");
                }
                String pDesc = getTypeDescriptor(pType);
                if (p.ELLIPSIS() != null) {
                    if (i != params.size() - 1) {
                        reportError(p, "Varargs parameter ('...') must be the last parameter in the parameter list.");
                    }
                    hasVarargs = true;
                    pDesc = "[" + pDesc;
                }
                descriptor.append(pDesc);
                paramInfos.add(new CompilerRegistry.MethodParamInfo(pName, pType, pDesc, p.expression()));
                if (p.ELLIPSIS() == null && ("long".equals(pType) || "double".equals(pType))) {
                    totalCtorSlots += 2;
                } else {
                    totalCtorSlots += 1;
                }
            }
        }
        if (totalCtorSlots > 255) {
            reportError(ctx, "Constructor parameter slots limit exceeded (" + totalCtorSlots + " > 255). JVM permits at most 255 parameter slots.");
        }
        descriptor.append(")V");

        validateModifiers(ctx, ctx.modifier());
        int access = 0;
        boolean hasExplicitAccess = false;
        boolean isStatic = isMain || (!isRealConstructor && ModifierHelper.isStatic(ctx.modifier()));
        if (ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String mt = mod.getText();
                if (!isMain && isRealConstructor && !"public".equals(mt) && !"private".equals(mt) && !"protected".equals(mt)) {
                    reportError(ctx, "Constructors can only have 'public', 'protected', or 'private' modifiers: '" + mt + "'");
                }
                switch (mt) {
                    case "public" -> { access |= Opcodes.ACC_PUBLIC; hasExplicitAccess = true; }
                    case "private" -> { access |= Opcodes.ACC_PRIVATE; hasExplicitAccess = true; }
                    case "protected" -> { access |= Opcodes.ACC_PROTECTED; hasExplicitAccess = true; }

                }
            }
        }
        if (isRealConstructor && "java/lang/Enum".equals(currentSuperName)) {
            if (hasExplicitAccess && (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
                reportError(ctx, "Enum constructor cannot be declared 'public' or 'protected'.");
            }
            if (!hasExplicitAccess) {
                access = currentEnumHasConstantBodies ? 0 : Opcodes.ACC_PRIVATE;
            }
        } else if (!hasExplicitAccess || isMain) {
            access |= Opcodes.ACC_PUBLIC;
        }
        if (isStatic) {
            access |= Opcodes.ACC_STATIC;
        }
        if (hasVarargs) {
            access |= Opcodes.ACC_VARARGS;
        }

        String classPath = getCurrentClassPath();
        CompilerRegistry.globalMethodRegistry.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                .put(methodName, descriptor.toString());
        CompilerRegistry.globalMethodAccess.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                .put(methodName, access);
        CompilerRegistry.globalMethodAccess.get(classPath).put(methodName + descriptor, access);
        CompilerRegistry.globalMethodStaticity.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                .put(methodName, isStatic);
        CompilerRegistry.globalMethodStaticity.get(classPath).put(methodName + descriptor, isStatic);

        CompilerRegistry.globalOverloadRegistry.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(methodName, k -> new CopyOnWriteArrayList<>())
                .add(descriptor.toString());

        if (!paramInfos.isEmpty()) {
            Map<String, List<CompilerRegistry.MethodParamInfo>> classMap = CompilerRegistry.globalMethodParamsRegistry
                .computeIfAbsent(classPath, k -> new ConcurrentHashMap<>());
            classMap.put(methodName + "#" + paramInfos.size(), paramInfos);
            classMap.put(methodName + descriptor, paramInfos);
            classMap.put(methodName, paramInfos);
        }

        // --- constructor throws listesini kaydet ---
        List<String> thrownExceptions = new ArrayList<>();
        if (ctx.typeList() != null) {
            for (OceanParser.TypeContext tc : ctx.typeList().type()) {
                String excPath = resolveInternalPath(tc.getText());
                if (excPath != null) thrownExceptions.add(excPath);
            }
            if (!thrownExceptions.isEmpty()) {
                CompilerRegistry.globalMethodThrowsRegistry
                        .computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                        .put(methodName + descriptor, thrownExceptions);
            }
        }

        ClassSymbol classSym = CompilerRegistry.getOrCreateClassSymbol(classPath);
        MethodSymbol ctorSym = new MethodSymbol(classPath, methodName, descriptor.toString(), "void", access, isStatic, false);
        ctorSym.setThrownExceptions(thrownExceptions);
        ctorSym.setParamInfos(paramInfos);
        classSym.addMethod(ctorSym);

        if (ctx.block() != null) {
            visit(ctx.block());
        }

        return null;
    }

    private boolean isFieldAssignedInConstructors(OceanParser.FieldDeclarationContext fieldCtx, String fieldName) {
        RuleContext parent = fieldCtx.getParent();
        OceanParser.ClassDeclarationContext classCtx = null;
        OceanParser.EnumDeclarationContext enumCtx = null;
        while (parent != null) {
            if (parent instanceof OceanParser.ClassDeclarationContext) {
                classCtx = (OceanParser.ClassDeclarationContext) parent;
                break;
            }
            if (parent instanceof OceanParser.EnumDeclarationContext) {
                enumCtx = (OceanParser.EnumDeclarationContext) parent;
                break;
            }
            parent = parent.getParent();
        }

        if (enumCtx != null) {
            String enumName = enumCtx.anyId() != null ? enumCtx.anyId().getText() : null;
            if (enumName == null || enumCtx.memberDeclaration() == null) return false;
            for (OceanParser.MemberDeclarationContext member : enumCtx.memberDeclaration()) {
                String mText = member.getText();
                if (mText.contains(enumName) && mText.contains(fieldName)) {
                    return true;
                }
            }
            return false;
        }

        if (classCtx == null) return false;

        if (insideDataClass || (classCtx.modifier() != null && classCtx.modifier().stream().anyMatch(m -> m.getText().equals("data")))) {
            return true;
        }

        String className = classCtx.anyId() != null ? classCtx.anyId().getText() : null;
        if (className == null || classCtx.memberDeclaration() == null) return false;

        boolean hasConstructor = false;
        boolean assignedInConstructors = false;

        for (OceanParser.MemberDeclarationContext member : classCtx.memberDeclaration()) {
            String mText = member.getText();
            if (mText.contains(className) && mText.contains(fieldName)) {
                hasConstructor = true;
                assignedInConstructors = true;
            }
        }

        return hasConstructor;
    }

    private boolean isFieldAssignedInStaticBlocks(OceanParser.FieldDeclarationContext fieldCtx, String fieldName) {
        RuleContext parent = fieldCtx.getParent();
        List<OceanParser.MemberDeclarationContext> members = null;
        while (parent != null) {
            if (parent instanceof OceanParser.ClassDeclarationContext cCtx) {
                members = cCtx.memberDeclaration();
                break;
            }
            if (parent instanceof OceanParser.EnumDeclarationContext eCtx) {
                members = eCtx.memberDeclaration();
                break;
            }
            parent = parent.getParent();
        }
        if (members == null) return false;
        for (OceanParser.MemberDeclarationContext member : members) {
            if (member.STATIC() != null && member.block() != null) {
                String bText = member.block().getText();
                if (bText.contains(fieldName + "=") || bText.contains("." + fieldName + "=")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String inferFieldTypeFromConstructor(OceanParser.FieldDeclarationContext fieldCtx, String fieldName) {
        RuleContext parent = fieldCtx.getParent();
        OceanParser.ClassDeclarationContext classCtx = null;
        while (parent != null) {
            if (parent instanceof OceanParser.ClassDeclarationContext) {
                classCtx = (OceanParser.ClassDeclarationContext) parent;
                break;
            }
            parent = parent.getParent();
        }
        if (classCtx == null || classCtx.memberDeclaration() == null) return null;
        String className = classCtx.anyId() != null ? classCtx.anyId().getText() : null;
        if (className == null) return null;

        for (OceanParser.MemberDeclarationContext member : classCtx.memberDeclaration()) {
            OceanParser.ConstructorDeclarationContext ctor = member.constructorDeclaration();
            if (ctor != null) {
                if (ctor.anyId() != null && !ctor.anyId().getText().equals(className)) continue;
                if (ctor.block() == null) continue;
                String bodyText = ctor.block().getText();
                if (ctor.parameterList() != null) {
                    for (OceanParser.ParameterContext param : ctor.parameterList().parameter()) {
                        String pName = param.anyId() != null ? param.anyId().getText() : null;
                        if (pName == null) continue;
                        if (bodyText.contains("this." + fieldName + "=" + pName) || bodyText.contains(fieldName + "=" + pName)) {
                            if (param.type() != null) {
                                return getTypeDescriptor(param.type().getText());
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public Void visitFieldDeclaration(OceanParser.FieldDeclarationContext ctx) {
        String typeText = ctx.type() != null ? ctx.type().getText() : null;
        int access = 0; // Default to package-private!
        boolean isStatic = false;
        boolean isInterfaceField = false;
        RuleContext parent = ctx.getParent();
        while (parent != null) {
            if (parent instanceof OceanParser.InterfaceDeclarationContext) {
                isInterfaceField = true;
                access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
                isStatic = true;
                break;
            }
            parent = parent.getParent();
        }

        validateModifiers(ctx, ctx.modifier());
        if (ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String mt = mod.getText();
                switch (mt) {
                    case "public" -> access |= Opcodes.ACC_PUBLIC;
                    case "private" -> access |= Opcodes.ACC_PRIVATE;
                    case "protected" -> access |= Opcodes.ACC_PROTECTED;
                    case "static" -> {
                        access |= Opcodes.ACC_STATIC;
                        isStatic = true;
                    }
                    case "final" -> access |= Opcodes.ACC_FINAL;
                    case "sync" -> access |= Opcodes.ACC_VOLATILE;
                    default -> {
                        if (!isInterfaceField) {
                            reportError(mod, "Invalid modifier for field declaration: '" + mt + "'.");
                        }
                    }
                }
            }
        }

        if (isInterfaceField && ctx.modifier() != null) {
            for (OceanParser.ModifierContext mod : ctx.modifier()) {
                String mt = mod.getText();
                if ("private".equals(mt) || "protected".equals(mt)) {
                    reportError(mod, "Interface fields cannot be declared 'private' or 'protected'.");
                }
                if ("sync".equals(mt)) {
                    reportError(mod, "Interface fields cannot be declared 'sync' (volatile).");
                }
                if (!"public".equals(mt) && !"static".equals(mt) && !"final".equals(mt) && !"private".equals(mt) && !"protected".equals(mt) && !"sync".equals(mt)) {
                    reportError(mod, "Invalid modifier for interface field declaration: '" + mt + "'.");
                }
            }
        }
        if (isInterfaceField && (ctx.VARIABLE() != null || "variable".equals(typeText))) {
            reportError(ctx, "Cannot declare mutable ('variable') field in interface. All interface fields are implicitly constant.");
        }
        if (isInterfaceField) {
            access = (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
            isStatic = true;
        }

        if (insideDataClass) {
            access = Opcodes.ACC_PRIVATE;
        }

        boolean isFinal = (access & Opcodes.ACC_FINAL) != 0 || ctx.VALUE() != null || ctx.FINAL() != null || "value".equals(typeText);
        if (isFinal) {
            access |= Opcodes.ACC_FINAL;
        }
        if (isFinal && (access & Opcodes.ACC_VOLATILE) != 0) {
            reportError(ctx, "Field cannot be declared both 'final' ('value') and 'sync' (volatile).");
        }

        String classPath = getCurrentClassPath();
        Set<String> typeParamNames = new HashSet<>(currentMethodTypeParameters);
        if (currentClassName != null) {
            String fullPath = getCurrentClassPath();
            List<CompilerRegistry.TypeParameterInfo> infos = CompilerRegistry.globalTypeParameterRegistry.get(fullPath);
            if (infos != null) {
                for (CompilerRegistry.TypeParameterInfo info : infos) {
                    typeParamNames.add(info.name);
                }
                if (isStatic) {
                    Set<String> classTpNames = new HashSet<>();
                    for (CompilerRegistry.TypeParameterInfo info : infos) {
                        classTpNames.add(info.name);
                    }
                    String matched = findContainedGenericParameter(typeText, classTpNames);
                    if (matched != null) {
                        reportError(ctx, "Static fields cannot reference class type parameter ('" + matched + "').");
                    }
                }
            }
        }

        if (typeText != null && countArrayDimensions(typeText) > 255) {
            reportError(ctx, "Array dimension limit exceeded (maximum 255 dimensions supported).");
        }

        for (OceanParser.VariableDeclaratorContext decl : ctx.variableDeclarator()) {
            String fieldName = decl.anyId().getText();
            String typeDesc = OceanTypeSystem.OBJECT_DESC;
            boolean isInitializedInCtor = isFieldAssignedInConstructors(ctx, fieldName);
            String ctorInferredType = inferFieldTypeFromConstructor(ctx, fieldName);

            if (typeText != null && !typeText.equals("value") && !typeText.equals("variable") && !typeText.equals("var")) {
                typeDesc = getTypeDescriptor(typeText);
            } else if (decl.expression() != null) {
                typeDesc = inferSimpleType(decl.expression());
            } else if (ctorInferredType != null) {
                typeDesc = ctorInferredType;
            } else if (!isInitializedInCtor) {
                reportError(ctx, "Field declarations without explicit type ('variable' / 'value') must have an initializer: '" + fieldName + "'");
            }

            if (isInterfaceField && decl.expression() == null) {
                reportError(ctx, "Interface fields must have an initializer: '" + fieldName + "'");
            }

            if (isStatic && isFinal && decl.expression() == null && !isInterfaceField) {
                boolean isAssignedInStaticBlock = isFieldAssignedInStaticBlocks(ctx, fieldName);
                if (!isAssignedInStaticBlock) {
                    reportError(ctx, "'static value' field declarations must have an initializer or assignment in static initializer block: '" + fieldName + "'");
                }
            } else if (!isStatic && isFinal && decl.expression() == null && !isInterfaceField && !isInitializedInCtor) {
                reportError(ctx, "'value' field declarations must have an initializer or assignment in constructor: '" + fieldName + "'");
            }

            List<String> enumConsts = CompilerRegistry.globalEnumConstants.get(classPath);
            if (enumConsts == null && currentClassName != null) {
                enumConsts = CompilerRegistry.globalEnumConstants.get(currentClassName);
            }
            if (enumConsts != null && enumConsts.contains(fieldName)) {
                reportError(decl, "Enum field '" + fieldName + "' conflicts with enum constant of the same name.");
            } else {
                Map<String, String> fMap = CompilerRegistry.globalFieldRegistry.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>());
                if (fMap.containsKey(fieldName)) {
                    reportError(decl, "Duplicate field declaration: '" + fieldName + "' is already defined.");
                }
            }
            Map<String, String> fMap = CompilerRegistry.globalFieldRegistry.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>());
            fMap.put(fieldName, typeDesc);

            String genericFieldSig = null;
            if (typeText != null && !typeText.equals("value") && !typeText.equals("variable") && !typeText.equals("var")) {
                if (typeText.contains("<") || typeParamNames.contains(typeText)) {
                    genericFieldSig = resolveGenericSignaturePreservingTypeParams(typeText, importedClasses, typeParamNames);
                }
            }
            if (genericFieldSig != null) {
                if (typeText.endsWith("?") && !genericFieldSig.endsWith("?")) {
                    genericFieldSig = genericFieldSig + "?";
                }
                CompilerRegistry.globalFieldGenericSignatureRegistry.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                        .put(fieldName, genericFieldSig);
                String simpleName = OceanTypeSystem.findSimpleName(classPath);
                if (!simpleName.equals(classPath)) {
                    CompilerRegistry.globalFieldGenericSignatureRegistry.computeIfAbsent(simpleName, k -> new ConcurrentHashMap<>())
                            .put(fieldName, genericFieldSig);
                }
            }
            CompilerRegistry.globalFieldStaticity.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                    .put(fieldName, isStatic);
            CompilerRegistry.globalFieldAccess.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                    .put(fieldName, access);
            CompilerRegistry.globalFieldMutability.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>())
                    .put(fieldName, isFinal);

            ClassSymbol classSym = CompilerRegistry.getOrCreateClassSymbol(classPath);
            FieldSymbol fieldSym = new FieldSymbol(classPath, fieldName, typeDesc, genericFieldSig, access, isStatic, !isFinal);
            classSym.addField(fieldSym);

            if (insideDataClass) {
                String capName = fieldName.substring(0, 1).toUpperCase(Locale.ENGLISH) + fieldName.substring(1);
                Map<String, String> mReg = CompilerRegistry.globalMethodRegistry.get(classPath);
                mReg.put("get" + capName, "()" + typeDesc);
                CompilerRegistry.globalMethodAccess.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>()).put("get" + capName, Opcodes.ACC_PUBLIC);
                CompilerRegistry.globalMethodStaticity.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>()).put("get" + capName, false);

                if (!isFinal) {
                    mReg.put("set" + capName, "(" + typeDesc + ")V");
                    CompilerRegistry.globalMethodAccess.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>()).put("set" + capName, Opcodes.ACC_PUBLIC);
                    CompilerRegistry.globalMethodStaticity.computeIfAbsent(classPath, k -> new ConcurrentHashMap<>()).put("set" + capName, false);
                }
            }
        }

        return null;
    }

    private static final Map<String, String> resolvedTypeCache = new ConcurrentHashMap<>();

    public static void clearCaches() {
        resolvedTypeCache.clear();
    }

    private String resolveInternalPath(String id) {
        if (id == null) return null;
        while (id.endsWith("?")) {
            id = id.substring(0, id.length() - 1).trim();
        }
        if (id.contains("<")) {
            id = id.substring(0, id.indexOf("<")).trim();
        }
        if (id.contains(".")) {
            return id.replace('.', '/');
        }
        if (currentMethodTypeParameters.contains(id)) {
            return OceanTypeSystem.OBJECT_DESC;
        }

        String effPkg = getEffectivePackage();
        String key = effPkg + "|" + (currentClassName != null ? currentClassName : "") + "|" + id + "|" + importedWildcards;
        String cached = resolvedTypeCache.get(key);
        if (cached != null) return cached;

        String prefix = effPkg.isEmpty() ? "" : (effPkg + "/");

        if (currentClassName != null) {
            String curr = currentClassName.replace('.', '/');
            while (!curr.isEmpty()) {
                String chain = curr + "$" + id.replace('.', '$');
                String fullChain = (!prefix.isEmpty() && !chain.startsWith(prefix)) ? (prefix + chain) : chain;
                if (definedClasses.contains(chain) || definedClasses.contains(fullChain) ||
                    CompilerRegistry.globalMethodRegistry.containsKey(chain) || CompilerRegistry.globalMethodRegistry.containsKey(fullChain) ||
                    CompilerRegistry.globalSuperClassRegistry.containsKey(chain) || CompilerRegistry.globalSuperClassRegistry.containsKey(fullChain) ||
                    CompilerRegistry.globalIsInterfaceSet.contains(chain) || CompilerRegistry.globalIsInterfaceSet.contains(fullChain)) {
                    resolvedTypeCache.put(key, fullChain);
                    return fullChain;
                }
                int lastDollar = curr.lastIndexOf('$');
                if (lastDollar != -1) {
                    curr = curr.substring(0, lastDollar);
                } else {
                    break;
                }
            }
        }

        String local = prefix + id;
        if (CompilerRegistry.globalMethodRegistry.containsKey(local) || definedClasses.contains(id) || definedClasses.contains(local)) {
            resolvedTypeCache.put(key, local);
            return local;
        }

        if (importedClasses.containsKey(id)) {
            String imp = importedClasses.get(id);
            resolvedTypeCache.put(key, imp);
            return imp;
        }

        List<String> samePkgMatches = new ArrayList<>();
        for (String c : definedClasses) {
            if (c.endsWith("/" + id) || c.equals(id)) {
                samePkgMatches.add(c);
            }
        }
        if (samePkgMatches.size() == 1) {
            String m = samePkgMatches.getFirst();
            if (!m.contains("/")) {
                m = prefix + m;
            }
            resolvedTypeCache.put(key, m);
            return m;
        }

        // java.lang.* ve ocean.stdlib.* otomatik çözümleme (standart kütüphane önceliği)
        String stdCandidate = OceanTypeSystem.resolveStandardClassPath(id);
        if (stdCandidate != null) {
            importedClasses.put(id, stdCandidate);
            resolvedTypeCache.put(key, stdCandidate);
            return stdCandidate;
        }

        List<String> matches = new ArrayList<>();
        for (String fqName : CompilerRegistry.globalMethodRegistry.keySet()) {
            if (fqName.endsWith("/" + id) || fqName.endsWith("$" + id)) {
                matches.add(fqName);
            }
        }
        if (!matches.isEmpty()) {
            if (matches.size() > 1) {
                Collections.sort(matches);
                StringBuilder sb = new StringBuilder();
                sb.append("Ambiguous class reference '").append(id).append("'. Multiple candidates found: ");
                for (int i = 0; i < matches.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(matches.get(i));
                }
                sb.append(". Please import the class explicitly.");
                reportError(sb.toString());
            }
            resolvedTypeCache.put(key, matches.getFirst());
            return matches.getFirst();
        }

        // Fallback to java/lang/Object or custom resolution
        /*String builtinInternal = OceanTypeSystem.getBuiltinInternalName(id);
        if (builtinInternal != null) {
            resolvedTypeCache.put(key, builtinInternal);
            return builtinInternal;
        }*/

        // Wildcard imports check
        for (String wild : importedWildcards) {
            try {
                OceanTypeSystem.forName(wild.replace('/', '.') + "." + id);
                String resolved = wild.replace('.', '/') + "/" + id;
                importedClasses.put(id, resolved);
                resolvedTypeCache.put(key, resolved);
                return resolved;
            } catch (ClassNotFoundException ignored) {
            }
        }

        // java.lang.* ve ocean.stdlib.* otomatik çözümleme
        String stdPath = OceanTypeSystem.resolveStandardClassPath(id);
        if (stdPath != null) {
            importedClasses.put(id, stdPath);
            resolvedTypeCache.put(key, stdPath);
            return stdPath;
        }

        String fallback = prefix + id;
        resolvedTypeCache.put(key, fallback);
        return fallback;
    }

    private void findReturnsRecursive(ParseTree tree, List<OceanParser.ReturnStatementContext> returns) {
        if (tree == null) return;
        if (tree instanceof OceanParser.ReturnStatementContext) {
            returns.add((OceanParser.ReturnStatementContext) tree);
        } else if (tree instanceof OceanParser.LambdaExprContext ||
                   tree instanceof OceanParser.ClassDeclarationContext ||
                   tree instanceof OceanParser.MethodDeclarationContext) {
            // Do not cross into lambdas, inner classes, or other methods
        } else {
            int childCount = tree.getChildCount();
            for (int i = 0; i < childCount; i++) {
                findReturnsRecursive(tree.getChild(i), returns);
            }
        }
    }

    private String inferSimpleType(OceanParser.ExpressionContext expr) {
        if (expr == null) return "V";

        if (expr instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext primary = ((OceanParser.PrimaryExprContext) expr).primary();
            if (primary instanceof OceanParser.ParenthesizedPrimaryContext) {
                return inferSimpleType(((OceanParser.ParenthesizedPrimaryContext) primary).expression());
            }
            if (primary instanceof OceanParser.TruePrimaryContext || primary instanceof OceanParser.FalsePrimaryContext) {
                return "Z";
            }
            if (primary instanceof OceanParser.NullPrimaryContext) {
                return OceanTypeSystem.OBJECT_DESC + "?";
            }
            if (primary instanceof OceanParser.StringPrimaryContext) {
                return OceanTypeSystem.STRING_DESC;
            }
            if (primary instanceof OceanParser.InterpolatedStringPrimaryContext) {
                return OceanTypeSystem.STRING_DESC;
            }
            if (primary instanceof OceanParser.NumberPrimaryContext) {
                String text = primary.getText().replace("_", "");
                if (text.endsWith("L") || text.endsWith("l")) return "J";
                if (text.endsWith("F") || text.endsWith("f")) return "F";
                if (text.endsWith("D") || text.endsWith("d") || text.contains(".") || text.contains("e") || text.contains("E") || text.contains("p") || text.contains("P")) return "D";
                return "I";
            }
            if (primary instanceof OceanParser.CharPrimaryContext) {
                return "C";
            }
            if (primary instanceof OceanParser.ListLiteralPrimaryContext) {
                return "Locean/stdlib/OceanList;";
            }
            if (primary instanceof OceanParser.SetLiteralPrimaryContext) {
                return "Locean/stdlib/OceanSet;";
            }
            if (primary instanceof OceanParser.MapLiteralPrimaryContext) {
                return "Locean/stdlib/OceanMap;";
            }
            if (primary instanceof OceanParser.ArrayLiteralPrimaryContext) {
                return "[Ljava/lang/Object;";
            }
        }

        if (expr instanceof OceanParser.CastExprContext castCtx) {
            if (castCtx.type() != null) {
                return getTypeDescriptor(castCtx.type().getText());
            }
        }

        if (expr instanceof OceanParser.NewObjectExprContext newCtx) {
            if (newCtx.type() != null) {
                String baseType = newCtx.type().getText();
                if (!newCtx.expression().isEmpty()) {
                    return "[".repeat(newCtx.expression().size()) +
                            getTypeDescriptor(baseType);
                }
                return getTypeDescriptor(baseType);
            }
        }

        if (expr instanceof OceanParser.AddSubExprContext addSub) {
            if (addSub.PLUS() != null) {
                String leftType = inferSimpleType(addSub.expression(0));
                String rightType = inferSimpleType(addSub.expression(1));
                if (TypeChecker.isStringType(leftType) || TypeChecker.isStringType(rightType)) {
                    return OceanTypeSystem.STRING_DESC;
                }
            }
        }

        if (expr instanceof OceanParser.TernaryExprContext ternary) {
            String tType = inferSimpleType(ternary.expression(1));
            String eType = inferSimpleType(ternary.expression(2));
            if (tType.equals(eType)) return tType;
            return OceanTypeSystem.OBJECT_DESC;
        }

        if (expr instanceof OceanParser.NullCoalescingExprContext nc) {
            String tType = inferSimpleType(nc.expression(0));
            String eType = inferSimpleType(nc.expression(1));
            if (tType.equals(eType)) return tType;
            return OceanTypeSystem.OBJECT_DESC;
        }

        if (expr instanceof OceanParser.LogicalAndExprContext ||
            expr instanceof OceanParser.LogicalOrExprContext ||
            expr instanceof OceanParser.EqualityExprContext ||
            expr instanceof OceanParser.ComparisonExprContext ||
            expr instanceof OceanParser.InstanceOfExprContext) {
            return "Z";
        }

        return OceanTypeSystem.OBJECT_DESC;
    }

    private String inferReturnTypeFromBlock(OceanParser.BlockContext ctx) {
        if (ctx == null) return "V";
        List<OceanParser.ReturnStatementContext> returns = new ArrayList<>();
        findReturnsRecursive(ctx, returns);
        if (returns.isEmpty()) {
            return "V";
        }
        String firstType = null;
        for (OceanParser.ReturnStatementContext ret : returns) {
            String type;
            if (ret.expression() == null) {
                type = "V";
            } else {
                type = inferSimpleType(ret.expression());
            }
            if (firstType == null) {
                firstType = type;
            } else if (!firstType.equals(type)) {
                return OceanTypeSystem.OBJECT_DESC;
            }
        }
        return firstType != null ? firstType : "V";
    }

    private void validateMainClassPresence() {
        if (currentFile == null) return;
        String expectedName = currentFile;
        int lastSlash = expectedName.lastIndexOf('/');
        int lastBackslash = expectedName.lastIndexOf('\\');
        int maxSlash = Math.max(lastSlash, lastBackslash);
        String simpleFileName = expectedName;
        if (maxSlash != -1) {
            simpleFileName = expectedName.substring(maxSlash + 1);
        }
        expectedName = simpleFileName;
        if (expectedName.endsWith(".ocean")) {
            expectedName = expectedName.substring(0, expectedName.length() - 6);
        }
        if (!definedClasses.contains(expectedName)) {
            String errorMsg = "A class, interface, or enum named '" + expectedName + "' must be declared in this file (file: '" + simpleFileName + "')";
            reportError(errorMsg);
        }
    }

    @Override
    public Void visitNewObjectExpr(OceanParser.NewObjectExprContext ctx) {
        if (ctx.argumentList() != null) {
            visit(ctx.argumentList());
        }
        if (ctx.expression() != null && !ctx.expression().isEmpty()) {
            if (ctx.type() != null && ctx.type().LBRACK() != null && !ctx.type().LBRACK().isEmpty()) {
                reportError(ctx, "Cannot specify an expression dimension after empty dimension '[]' in array creation: '" + ctx.getText() + "'");
            }
        }
        if (ctx.expression() != null && ctx.expression().size() > 255) {
            reportError(ctx, "Array dimension limit exceeded (maximum 255 dimensions supported, " + ctx.expression().size() + " specified).");
        }
        for (OceanParser.ExpressionContext expr : ctx.expression()) {
            visit(expr);
        }

        if (ctx.LBRACE() != null) {
            String targetTypeStr = ctx.type().getText();
            String resolvedTarget = resolveInternalPath(targetTypeStr);
            
            boolean isInterface = ClassMetadataCache.isInterface(resolvedTarget);
            
            int index = (session != null) ? session.getNextAnonClassIndex(currentClassName) : (anonClassCounter++);
            String anonClassName = currentClassName + "$Anon$" + index;
            String anonFullPathKey = getCurrentClassPath(anonClassName);
            
            ClassSymbol classSym = CompilerRegistry.getOrCreateClassSymbol(anonFullPathKey);
            CompilerRegistry.globalMethodRegistry.computeIfAbsent(anonFullPathKey, k -> new ConcurrentHashMap<>());
            if (isInterface) {
                CompilerRegistry.globalSuperClassRegistry.put(anonFullPathKey, "java/lang/Object");
                CompilerRegistry.globalInterfaceRegistry.put(anonFullPathKey, new String[]{resolvedTarget});
                classSym.setSuperClassName("java/lang/Object");
                classSym.addInterface(resolvedTarget);
            } else {
                CompilerRegistry.globalSuperClassRegistry.put(anonFullPathKey, resolvedTarget);
                CompilerRegistry.globalInterfaceRegistry.put(anonFullPathKey, new String[0]);
                classSym.setSuperClassName(resolvedTarget);
            }
            
            CompilerRegistry.globalClassAccess.put(anonFullPathKey, Opcodes.ACC_SUPER | Opcodes.ACC_FINAL);
            
            String oldClassName = currentClassName;
            String oldSuperName = currentSuperName;
            boolean oldHasConstructor = hasConstructor;
            
            currentClassName = anonClassName;
            currentSuperName = isInterface ? "java/lang/Object" : resolvedTarget;
            hasConstructor = false;
            
            if (ctx.memberDeclaration() != null) {
                for (OceanParser.MemberDeclarationContext m : ctx.memberDeclaration()) {
                    visit(m);
                }
            }
            if (ctx.statement() != null) {
                for (OceanParser.StatementContext s : ctx.statement()) {
                    visit(s);
                }
            }
            
            if (!hasConstructor) {
                CompilerRegistry.globalMethodRegistry.get(anonFullPathKey).put("<init>", "()V");
                CompilerRegistry.globalOverloadRegistry.computeIfAbsent(anonFullPathKey, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent("<init>", k -> new CopyOnWriteArrayList<>()).add("()V");
            }
            
            currentClassName = oldClassName;
            currentSuperName = oldSuperName;
            hasConstructor = oldHasConstructor;
        }
        return null;
    }

    private List<CompilerRegistry.TypeParameterInfo> extractTypeParameters(List<OceanParser.TypeParameterContext> tpList) {
        List<CompilerRegistry.TypeParameterInfo> typeParams = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (tpList != null && !tpList.isEmpty()) {
            for (OceanParser.TypeParameterContext tpCtx : tpList) {
                if (tpCtx == null || tpCtx.anyId() == null) continue;
                String tpName = tpCtx.anyId().getText();
                if (!seen.add(tpName)) {
                    reportError(tpCtx, "Duplicate type parameter: '" + tpName + "'");
                }
                CompilerRegistry.Variance var = CompilerRegistry.Variance.INVARIANT;
                if (tpCtx.PLUS() != null) var = CompilerRegistry.Variance.COVARIANT;
                else if (tpCtx.MINUS() != null) var = CompilerRegistry.Variance.CONTRAVARIANT;

                List<String> upperBounds = new ArrayList<>();
                String lowerBound = null;
                List<OceanParser.TypeContext> upperBoundsCtxList = new ArrayList<>();
                if (tpCtx.LOWER_BOUND() != null && tpCtx.type() != null && !tpCtx.type().isEmpty()) {
                    int count = tpCtx.type().size();
                    lowerBound = getTypeDescriptor(tpCtx.type(count - 1).getText());
                    for (int i = 0; i < count - 1; i++) {
                        upperBoundsCtxList.add(tpCtx.type(i));
                    }
                } else if (tpCtx.type() != null) {
                    upperBoundsCtxList.addAll(tpCtx.type());
                }

                // Split each TypeContext if it contains '&' (intersection bounds)
                List<String> rawBounds = new ArrayList<>();
                for (OceanParser.TypeContext tCtx : upperBoundsCtxList) {
                    String txt = tCtx.getText().trim();
                    if (txt.contains("&")) {
                        int depth = 0;
                        int last = 0;
                        for (int k = 0; k < txt.length(); k++) {
                            char ch = txt.charAt(k);
                            if (ch == '<') depth++;
                            else if (ch == '>') depth--;
                            else if (ch == '&' && depth == 0) {
                                rawBounds.add(txt.substring(last, k).trim());
                                last = k + 1;
                            }
                        }
                        rawBounds.add(txt.substring(last).trim());
                    } else {
                        rawBounds.add(txt);
                    }
                }

                Set<String> seenBounds = new HashSet<>();
                List<String> dependentUpperBoundParamNames = new ArrayList<>();
                String dependentLowerBoundParamName = null;

                // Lower bound başka bir tip parametresi mi?
                if (lowerBound != null) {
                    String lbBase = lowerBound;
                    if (lbBase.startsWith("L") && lbBase.endsWith(";")) {
                        lbBase = lbBase.substring(1, lbBase.length() - 1);
                    }
                    if (seen.contains(lbBase)) {
                        dependentLowerBoundParamName = lbBase;
                        lowerBound = null; // bytecode için Object kullan
                    } else {
                        for (OceanParser.TypeParameterContext otherTp : tpList) {
                            if (otherTp != null && otherTp.anyId() != null && lbBase.equals(otherTp.anyId().getText())) {
                                dependentLowerBoundParamName = lbBase;
                                lowerBound = null;
                                break;
                            }
                        }
                    }
                }

                for (int i = 0; i < rawBounds.size(); i++) {
                    String rawText = rawBounds.get(i).trim();
                    String baseName = rawText;
                    if (baseName.contains("<")) {
                        baseName = baseName.substring(0, baseName.indexOf('<')).trim();
                    }
                    while (baseName.endsWith("?")) {
                        baseName = baseName.substring(0, baseName.length() - 1).trim();
                    }

                    // 1A. Primitive bound check
                    if (OceanTypeSystem.isPrimitive(baseName) || "void".equals(baseName) || "V".equals(baseName)) {
                        reportError(tpCtx, "Type parameter bound cannot be a primitive type: '" + rawText + "'");
                        continue;
                    }

                    // 1E. Circular self-bound check
                    if (baseName.equals(tpName)) {
                        reportError(tpCtx, "Type parameter '" + tpName + "' cannot have itself as a bound (cyclic type dependency).");
                        continue;
                    }

                    // Bound başka bir tip parametresi mi? (örn. T <: U durumu)
                    // 'seen' kümesinde varsa ya da tpList'teki başka bir parametre adıysa:
                    // → dependentUpperBoundParams'a ham adını kaydet (semantik kontrol için)
                    // → upperBounds'a EKLEME (bytecode için erased type Object zaten getErasedType ile üretilir)
                    boolean boundIsTypeParam = seen.contains(baseName);
                    if (!boundIsTypeParam) {
                        for (OceanParser.TypeParameterContext otherTp : tpList) {
                            if (otherTp != null && otherTp.anyId() != null && baseName.equals(otherTp.anyId().getText())) {
                                boundIsTypeParam = true;
                                break;
                            }
                        }
                    }

                    if (boundIsTypeParam) {
                        // Ham parametre adını bağımlılık listesine ekle; upperBounds'a ekleme
                        dependentUpperBoundParamNames.add(baseName);
                        continue; // upperBounds'a hiçbir şey eklenmez → getErasedType → Object (doğru JVM davranışı)
                    }

                    String resolved = resolveInternalPath(baseName);
                    if (resolved == null) resolved = baseName;

                    // 1C. Duplicate bound check
                    if (!seenBounds.add(resolved)) {
                        reportError(tpCtx, "Duplicate bound for type parameter '" + tpName + "': '" + rawText + "'");
                        continue;
                    }

                    // 1D. Final class bound with additional interfaces check
                    if (i == 0 && rawBounds.size() > 1) {
                        if (ClassMetadataCache.isFinal(resolved)) {
                            reportError(tpCtx, "Cannot inherit from final class '" + rawText + "' or combine it with additional interface bounds.");
                        }
                    }

                    // 1B. Multiple class bounds check: only first bound can be class
                    if (i > 0) {
                        if (!ClassMetadataCache.isInterface(resolved) && ClassMetadataCache.isClass(resolved)) {
                            reportError(tpCtx, "Additional upper bound ('&') cannot be a class, only interface types are permitted: '" + rawText + "'");
                        }
                    }

                    upperBounds.add(getTypeDescriptor(rawText));
                }

                typeParams.add(new CompilerRegistry.TypeParameterInfo(tpName, var, upperBounds, lowerBound, false,
                        dependentUpperBoundParamNames, dependentLowerBoundParamName));
            }
        }
        return typeParams;
    }

    private void reportError(ParserRuleContext node, String message) {
        int line = node != null && node.getStart() != null ? node.getStart().getLine() : 0;
        int col = node != null && node.getStart() != null ? node.getStart().getCharPositionInLine() : 0;
        CompilerReporter.error(currentFile, line, col, message, "PreScanner");
    }

    private void reportError(String message) {
        reportError(null, message);
    }

    private void reportWarning(ParserRuleContext node, String message) {
        int line = node != null && node.getStart() != null ? node.getStart().getLine() : 0;
        int col = node != null && node.getStart() != null ? node.getStart().getCharPositionInLine() : 0;
        CompilerReporter.warning(currentFile, line, col, message, "PreScanner");
    }

    private void reportWarning(String message) {
        reportWarning(null, message);
    }

    private void checkAndRegisterContainerTypes(String fqcnKey, String superOrInterface) {
        if (superOrInterface == null || fqcnKey == null) return;
        String clean = superOrInterface.replace('.', '/');
        if (clean.equals("java/util/Collection") || clean.equals("java/util/List") ||
            clean.equals("java/util/Set") || clean.equals("java/lang/Iterable") ||
            clean.startsWith("ocean/stdlib/OceanList")) {
            CompilerRegistry.globalListLikeOwnerRegistry.add(fqcnKey);
        } else if (clean.equals("java/util/Map") || clean.startsWith("ocean/stdlib/OceanMap")) {
            CompilerRegistry.globalMapLikeOwnerRegistry.add(fqcnKey);
        }
    }

    private void checkVariance(ParserRuleContext ctx, String type, String ownerPath, boolean isParameter) {
        if (type == null || ownerPath == null) return;
        List<CompilerRegistry.TypeParameterInfo> classTypeParams = CompilerRegistry.globalTypeParameterRegistry.get(ownerPath);
        if (classTypeParams == null || classTypeParams.isEmpty())
            return;

        for (CompilerRegistry.TypeParameterInfo tp : classTypeParams) {
            String normType = type.replaceAll("\\s+", "").replace("?", "");
            if (normType.equals(tp.name)
                    || normType.contains("<" + tp.name + ">")
                    || normType.contains("," + tp.name + ">")
                    || normType.contains("<" + tp.name + ",")
                    || normType.contains("," + tp.name + ",")) {
                if (tp.variance == CompilerRegistry.Variance.COVARIANT && isParameter) {
                    reportError(ctx,
                            "Covariant type parameter '" + tp.name + "' cannot occur in contravariant position (method parameter) in class '" + ownerPath + "'");
                } else if (tp.variance == CompilerRegistry.Variance.CONTRAVARIANT && !isParameter) {
                    reportError(ctx,
                            "Contravariant type parameter '" + tp.name + "' cannot occur in covariant position (return type) in class '" + ownerPath + "'");
                }
            }
        }
    }

    private String resolveGenericSignaturePreservingTypeParams(String typeName, Map<String, String> imports, Set<String> typeParamNames) {
        if (typeName == null || typeName.isEmpty()) return OceanTypeSystem.OBJECT_DESC;
        typeName = typeName.trim();
        boolean nullable = false;
        while (typeName.endsWith("?")) {
            nullable = true;
            typeName = typeName.substring(0, typeName.length() - 1).trim();
        }
        if (typeName.endsWith("[]")) {
            String elem = typeName.substring(0, typeName.length() - 2).trim();
            return "[" + resolveGenericSignaturePreservingTypeParams(elem, imports, typeParamNames);
        }
        if (typeName.startsWith("? extends ") || typeName.startsWith("?extends")) {
            String sub = typeName.contains("extends") ? typeName.substring(typeName.indexOf("extends") + 7).trim() : typeName;
            return "+" + resolveGenericSignaturePreservingTypeParams(sub, imports, typeParamNames);
        }
        if (typeName.startsWith("? super ") || typeName.startsWith("?super")) {
            String sub = typeName.contains("super") ? typeName.substring(typeName.indexOf("super") + 5).trim() : typeName;
            return "-" + resolveGenericSignaturePreservingTypeParams(sub, imports, typeParamNames);
        }
        if (typeParamNames != null && typeParamNames.contains(typeName)) {
            return typeName;
        }
        if (typeName.contains("<") && typeName.endsWith(">")) {
            int ltIdx = typeName.indexOf('<');
            int gtIdx = typeName.lastIndexOf('>');
            String base = typeName.substring(0, ltIdx).trim();
            String inner = typeName.substring(ltIdx + 1, gtIdx).trim();
            List<String> args = splitGenericArgs(inner);
            String baseInternal = resolveInternalPath(base);
            if (baseInternal == null) baseInternal = base;
            if (baseInternal.startsWith("L") && baseInternal.endsWith(";")) {
                baseInternal = baseInternal.substring(1, baseInternal.length() - 1);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("L").append(baseInternal.replace('.', '/')).append("<");
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(",");
                String resolvedArg = resolveGenericSignaturePreservingTypeParams(args.get(i).trim(), imports, typeParamNames);
                if (resolvedArg.length() == 1) {
                    String boxed = TypeChecker.box(resolvedArg);
                    if (boxed != null) resolvedArg = boxed;
                }
                sb.append(resolvedArg);
            }
            sb.append(">;");
            return sb.toString();
        }
        if (OceanTypeSystem.isPrimitive(typeName) || TypeChecker.isPrimitive(typeName)) {
            String boxed = TypeChecker.box(typeName);
            return boxed != null ? boxed : "Ljava/lang/Object;";
        }
        String path = resolveInternalPath(typeName);
        if (path == null) path = typeName;
        if (path.startsWith("L") && path.endsWith(";")) {
            return path;
        } else if (path.startsWith("[")) {
            return path;
        } else {
            return "L" + path.replace('.', '/') + ";";
        }
    }

    private List<String> splitGenericArgs(String inner) {
        return TypeChecker.splitGenericArgs(inner);
    }

    private void inferConstructorFieldTypesFromAst(OceanParser.BlockContext blockCtx, Map<String, String> paramTypes, Map<String, String> fieldReg) {
        if (blockCtx == null || blockCtx.statement() == null || paramTypes.isEmpty() || fieldReg.isEmpty()) return;
        for (OceanParser.StatementContext stmt : blockCtx.statement()) {
            OceanParser.ExpressionContext left = null;
            OceanParser.ExpressionContext right = null;
            String op = null;
            if (stmt instanceof OceanParser.AssignmentStmtContext aStmt && aStmt.assignment() != null) {
                left = aStmt.assignment().expression(0);
                right = aStmt.assignment().expression(1);
                op = aStmt.assignment().op != null ? aStmt.assignment().op.getText() : null;
            } else if (stmt instanceof OceanParser.ExprStmtContext eStmt && eStmt.expressionStatement() != null) {
                if (eStmt.expressionStatement().expression() instanceof OceanParser.AssignmentExprContext aExpr) {
                    left = aExpr.expression(0);
                    right = aExpr.expression(1);
                    op = aExpr.op != null ? aExpr.op.getText() : null;
                }
            }
            if ("=".equals(op) && left != null && right != null) {
                String leftText = left.getText();
                String rightText = right.getText();
                String fName = leftText.startsWith("this.") ? leftText.substring(5) : leftText;
                if (paramTypes.containsKey(rightText) && fieldReg.containsKey(fName)) {
                    String currentFDesc = fieldReg.get(fName);
                    if (TypeChecker.isObjectType(currentFDesc)) {
                        fieldReg.put(fName, paramTypes.get(rightText));
                    }
                }
            }
        }
    }
}
