package ocean.compiler.legacy;

import ocean.compiler.*;
import ocean.compiler.OceanBaseVisitor;
import ocean.compiler.OceanParser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;

import java.io.Closeable;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;


/**
 * Bytecode üretiminden ÖNCE çalışan bağımsız semantik analiz geçişi.
 * AST üzerinde yürüyerek tip güvenliği, kapsam doğruluğu ve erişilebilirlik
 * kontrollerini yapar. Hata bulursa CompilerReporter'a bildirir.
 * Bu sınıf hiçbir bytecode üretmez — sadece doğrulama yapar.
 */
public class SemanticAnalyzer extends OceanBaseVisitor<String> implements TypeInferenceEngine.InferenceContext {

    private final AnalysisContext ctx;
    private final SymbolTable symbolTable;
    private int errorCount = 0;
    private final Map<String, String> importedClasses = new HashMap<>();
    private final List<String> importedWildcards = new ArrayList<>();
    private final Map<String, String> importedStaticMembers = new HashMap<>();
    private final List<String> importedStaticWildcards = new ArrayList<>();
    private int switchExpressionDepth = 0;
    private final Deque<List<String>> switchExpressionTypesStack = new ArrayDeque<>();
    private String currentPackageName = null;
    private final CompilationSession session;
    private int anonClassCounter = 0;
    private final Deque<List<String>> caughtExceptionsStack = new ArrayDeque<>();
    private final Deque<Map<String, Boolean>> nullabilityScopes = new ArrayDeque<>() {{push(new HashMap<>());}};

    // [LEGACY] Old constructor (Kept for compatibility)
    @Deprecated
    public SemanticAnalyzer(String currentFile) {
        this(currentFile, null);
    }

    public SemanticAnalyzer(String currentFile, CompilationSession session) {
        this.session = session;
        this.symbolTable = new SymbolTable(false, this.importedClasses, new HashSet<>(), session);
        this.ctx = new AnalysisContext(currentFile, symbolTable);
    }

    public boolean hasErrors() {
        // errorCount covers only our own reportError() calls.
        // CompilerReporter also receives errors from SymbolTable and other helpers.
        return errorCount > 0 || CompilerReporter.hasErrors();
    }

    public int getErrorCount() {
        return errorCount;
    }

    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    public Map<String, String> getImportedClasses() {
        return importedClasses;
    }

    // ========== Hata Raporlama ==========

    private void reportError(ParserRuleContext node, String message) {
        int line = node != null ? node.start.getLine() : 0;
        int col = node != null ? node.start.getCharPositionInLine() : 0;
        CompilerReporter.error(ctx.getCurrentFile(), line, col, message, ctx.getContextString());
        errorCount++;
    }

    private void reportError(String message) {
        reportError(null, message);
    }

    private void reportWarning(ParserRuleContext node, String message) {
        int line = node != null ? node.start.getLine() : 0;
        int col = node != null ? node.start.getCharPositionInLine() : 0;
        CompilerReporter.warning(ctx.getCurrentFile(), line, col, message, ctx.getContextString());
    }

    private void reportWarning(String message) {
        reportWarning(null, message);
    }

    /**
     * Geçerli dosyanın çözümlenen bir sınıfa (resolvedFqcn) bağımlı olduğunu
     * CompilationSession.classDependencies map'ine kaydeder.
     * java.lang.* bağımlılıkları kaydedilmez (kirlilik önlenir).
     */
    private void recordDependency(String resolvedFqcn) {
        if (resolvedFqcn == null) return;
        // Strip descriptor wrappers (L...;)
        if (TypeChecker.isClassType(resolvedFqcn)) resolvedFqcn = resolvedFqcn.substring(1, resolvedFqcn.length() - 1);

        // Skip JDK built-ins — only track Ocean source dependencies
        if (CompilationSession.isSystemPackage(resolvedFqcn)) {
            return;
        }
        CompilationSession active = CompilationSession.getActiveSession();
        if (active == null) return;
        String file = ctx.getCurrentFile();
        if (file == null) return;
        active.classDependencies
                .computeIfAbsent(file, k -> ConcurrentHashMap.newKeySet())
                .add(resolvedFqcn);
    }

    // ========== Program & Compilation Unit ==========

    @Override
    public String visitProgram(OceanParser.ProgramContext ctxNode) {
        return visitChildren(ctxNode);
    }

    @Override
    public String visitCompilationUnit(OceanParser.CompilationUnitContext ctxNode) {
        CompilationSession session = CompilationSession.getActiveSession();
        if (session != null) {
            session.setCurrentFile(ctx.getCurrentFile());
        }
        if (ctxNode.packageDeclaration() != null) {
            visitPackageDeclaration(ctxNode.packageDeclaration());
        }
        if (session != null) {
            session.setCurrentPackage(getFilePackage());
        }
        anonClassCounter = 0;
        try {
            return visitChildren(ctxNode);
        } finally {
            if (session != null) {
                session.setCurrentFile(null);
                session.setCurrentPackage(null);
            }
        }
    }

    @Override
    public String visitImportStatement(OceanParser.ImportStatementContext ctxNode) {
        boolean isStatic = ctxNode.STATIC() != null;
        List<OceanParser.AnyIdContext> parts = ctxNode.anyId();
        if (parts != null && !parts.isEmpty()) {
            if (isStatic) {
                if (ctxNode.STAR() != null) {
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
                    importedStaticMembers.put(memberName, classPath.toString());
                }
            } else {
                if (ctxNode.STAR() != null) {
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
                        if (i > 0)
                            fullPath.append("/");
                        fullPath.append(parts.get(i).getText());
                    }
                    importedClasses.put(simpleName, fullPath.toString());
                }
            }
        }
        return null;
    }

    public List<String> getImportedWildcards() { return importedWildcards; }
    public Map<String, String> getImportedStaticMembers() { return importedStaticMembers; }
    public List<String> getImportedStaticWildcards() { return importedStaticWildcards; }

    // ========== Sınıf Tanımı ==========

    @Override
    public String visitClassDeclaration(OceanParser.ClassDeclarationContext ctxNode) {
        List<String> classParams = new ArrayList<>();
        if (ctxNode.typeParameter() != null) {
            for (OceanParser.TypeParameterContext tp : ctxNode.typeParameter()) {
                classParams.add(tp.anyId().getText());
            }
        }
        this.symbolTable.getTypeParams().addAll(classParams);

        String className = ctxNode.anyId().getText();
        String parentClass = ctx.getCurrentClass();
        if (parentClass != null) {
            className = parentClass + "$" + className;
        }
        String fullPath = getFilePackage() + "/" + className;
        CompilationSession session = CompilationSession.getActiveSession();
        String oldClassFqcn = null;
        if (session != null) {
            oldClassFqcn = session.getCurrentClassFqcn();
            session.setCurrentClassFqcn(fullPath);
        }

        // Circular dependency check
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();
        if (hasCycle(fullPath, visited, stack)) {
            reportError(ctxNode, "Sınıf '" + className + "' için döngüsel kalıtım (circular dependency) tespit edildi.");
        }

        // Duplicate fields and methods check
        Set<String> declaredFields = new HashSet<>();
        Set<String> declaredMethodSignatures = new HashSet<>();

        for (ParseTree child : ctxNode.children) {
            if (child instanceof OceanParser.MemberDeclarationContext member) {
                if (member.fieldDeclaration() != null) {
                    for (OceanParser.VariableDeclaratorContext d : member.fieldDeclaration().variableDeclarator()) {
                        String fName = d.anyId().getText();
                        if (!declaredFields.add(fName)) {
                            reportError(member.fieldDeclaration(), "Alan '" + fName + "' bu sınıfta zaten tanımlı.");
                        }
                    }
                } else if (member.methodDeclaration() != null) {
                    OceanParser.MethodDeclarationContext md = member.methodDeclaration();
                    if (md instanceof OceanParser.NormalMethodContext nmc) {
                        String mName = nmc.anyId().getText();
                        StringBuilder paramSb = new StringBuilder("(");
                        if (nmc.parameterList() != null) {
                            for (OceanParser.ParameterContext p : nmc.parameterList().parameter()) {
                                String pType = p.type().getText();
                                if (pType.equals("variable") || pType.equals("var") || pType.equals("value")) {
                                    reportError(p, "Metot ve yapıcı metot parametrelerinde 'variable' / 'value' türü kullanılamaz: '" + p.anyId().getText() + "'");
                                }
                                paramSb.append(resolveTypeAndCheckAccess(pType, p));
                            }
                        }
                        paramSb.append(")");
                        String sig = mName + paramSb;
                        if (!declaredMethodSignatures.add(sig)) {
                            reportError(nmc, "Metot '" + mName + "' aynı parametre imzasıyla bu sınıfta zaten tanımlı.");
                        }
                    }
                }
            }
        }

        // Global registry'den sınıf bilgilerini al
        Map<String, String> fields = new HashMap<>(
                CompilerRegistry.globalFieldRegistry.getOrDefault(fullPath, Collections.emptyMap()));
        Map<String, Boolean> fieldStatic = new HashMap<>(getGlobalFieldStaticity(fullPath));
        Map<String, String> methods = new HashMap<>(
                CompilerRegistry.globalMethodRegistry.getOrDefault(fullPath, Collections.emptyMap()));
        Map<String, Boolean> methodStatic = new HashMap<>(getGlobalMethodStaticity(fullPath));

        // AST'den de alan ve metot bilgilerini topla (registry eksikse diye)
        for (ParseTree child : ctxNode.children) {
            if (child instanceof OceanParser.MemberDeclarationContext member) {
                if (member.fieldDeclaration() != null) {
                    OceanParser.FieldDeclarationContext fd = member.fieldDeclaration();
                    boolean fStatic = ModifierHelper.isStatic(fd.modifier());
                    String fType = fd.type() != null
                            ? resolveTypeAndCheckAccess(fd.type().getText(), fd)
                            : OceanTypeSystem.OBJECT_DESC;
                    for (OceanParser.VariableDeclaratorContext d : fd.variableDeclarator()) {
                        String fName = d.anyId().getText();
                        fields.putIfAbsent(fName, fType);
                        fieldStatic.putIfAbsent(fName, fStatic);
                    }
                } else if (member.methodDeclaration() != null) {
                    OceanParser.MethodDeclarationContext md = member.methodDeclaration();
                    if (md instanceof OceanParser.NormalMethodContext nmc) {
                        String mName = nmc.anyId().getText();
                        boolean mStatic = nmc.extType != null || ModifierHelper.isStatic(nmc.modifier());

                        String rType = "V";
                        if (nmc.VOID() == null) {
                            if (!nmc.type().isEmpty()) {
                                    rType = resolveTypeAndCheckAccess(nmc.type(0).getText(), nmc);
                            }
                        }

                        methods.putIfAbsent(mName, "()" + rType); // Simple descriptor for analysis
                        methodStatic.putIfAbsent(mName, mStatic);
                    }
                } else if (member.constructorDeclaration() != null) {
                    String cName = member.constructorDeclaration().anyId().getText();
                    // Constructor as method (Ocean allows pseudo-method via constructor syntax)
                    methods.putIfAbsent(cName, "()V");
                    methodStatic.putIfAbsent(cName, false);
                }
            }
        }

        // Üst sınıf alanlarını da ekle
        String superClass = null;
        String superPath = null;
        if (ctxNode.type() != null) {
            superClass = ctxNode.type().getText();
        }
        if (superClass != null) {
            String superDesc = resolveTypeAndCheckAccess(superClass, ctxNode);
            if (TypeChecker.isClassType(superDesc)) {
                superPath = superDesc.substring(1, superDesc.length() - 1);
            } else {
                superPath = superDesc;
            }
            // Record extends dependency for incremental build tracking
            recordDependency(superPath);

            if (superPath.equals("java/lang/Enum")) {
                reportError(ctxNode, "Sınıf '" + className + "' doğrudan java.lang.Enum sınıfından miras alamaz.");
            }

            boolean isSuperInterface = isInterface(superPath);
            if (isSuperInterface) {
                reportError(ctxNode, "Sınıf '" + className + "' arayüz olan '" + superClass + "' sınıfından miras alamaz.");
            }

            // ACC_FINAL check for final class inheritance
            boolean isSuperFinal = false;
            if (CompilerRegistry.globalClassAccess.containsKey(superPath)) {
                int accessFlags = CompilerRegistry.globalClassAccess.get(superPath);
                isSuperFinal = (accessFlags & Opcodes.ACC_FINAL) != 0;
            } else {
                try {
                    Class<?> clazz = OceanTypeSystem.forName(superPath.replace('/', '.'));
                    isSuperFinal = Modifier.isFinal(clazz.getModifiers());
                } catch (ClassNotFoundException | SecurityException ignored) {}
            }
            if (isSuperFinal) {
                reportError(ctxNode, "Sınıf '" + className + "', final olan '" + superClass + "' sınıfından miras alamaz.");
            }

            // Sealed class inheritance check
            if (CompilerRegistry.globalSealedClassSet.contains(superPath)) {
                List<String> permitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(superPath);
                if (permitted != null && !permitted.contains(fullPath)) {
                    reportError(ctxNode, "Sınıf '" + className + "', sealed olan '" + superClass + "' sınıfını türetemez. İzin verilen sınıflar (restricts) arasında yer almıyor.");
                }
            }

            Map<String, String> superFields = CompilerRegistry.globalFieldRegistry.getOrDefault(superPath,
                    Collections.emptyMap());
            for (Map.Entry<String, String> entry : superFields.entrySet()) {
                fields.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        boolean extendsOrImplementsSealed = (superClass != null && CompilerRegistry.globalSealedClassSet.contains(superPath));

        // implements list check
        if (ctxNode.typeList() != null) {
            for (OceanParser.TypeContext tc : ctxNode.typeList().type()) {
                String typeName = tc.getText();
                String typeDesc = resolveTypeAndCheckAccess(typeName, tc);
                String resolvedPath;
                if (TypeChecker.isClassType(typeDesc)) {
                    resolvedPath = typeDesc.substring(1, typeDesc.length() - 1);
                } else {
                    resolvedPath = typeDesc;
                }
                // Record implements dependency for incremental build tracking
                recordDependency(resolvedPath);

                boolean isInterface = isInterface(resolvedPath);
                if (!isInterface) {
                    reportError(tc, "Sınıf '" + className + "' arayüz olmayan '" + typeName + "' sınıfını implement edemez.");
                }

                if (CompilerRegistry.globalSealedClassSet.contains(resolvedPath)) {
                    extendsOrImplementsSealed = true;
                    List<String> permitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(resolvedPath);
                    if (permitted != null && !permitted.contains(fullPath)) {
                        reportError(tc, "Sınıf '" + className + "', sealed olan '" + typeName + "' arayüzünü uygulayamaz. İzin verilen sınıflar (restricts) arasında yer almıyor.");
                    }
                }
            }
        }

        if (extendsOrImplementsSealed) {
            String status = CompilerRegistry.globalSubclassStatusRegistry.get(fullPath);
            if (status == null) {
                reportError(ctxNode, "Sealed sınıf veya arayüzü türeten/uygulayan '" + className + "' sınıfı 'final', 'sealed' veya 'non-sealed' olarak bildirilmelidir.");
            }
        }

        boolean isAbstract = ModifierHelper.isAbstract(ctxNode.modifier());
        if (!isAbstract) {
            checkAbstractMethods(fullPath, className, ctxNode);
        }

        ctx.enterClass(className, fields, fieldStatic, methods, methodStatic);
        visitChildren(ctxNode);
        ctx.exitClass();
        classParams.forEach(this.symbolTable.getTypeParams()::remove);
        if (session != null) {
            session.setCurrentClassFqcn(oldClassFqcn);
        }
        return null;
    }

    @Override
    public String visitInterfaceDeclaration(OceanParser.InterfaceDeclarationContext ctxNode) {
        String interfaceName = ctxNode.anyId().getText();
        String parentClass = ctx.getCurrentClass();
        if (parentClass != null) {
            interfaceName = parentClass + "$" + interfaceName;
        }
        String fullPath = getFilePackage() + "/" + interfaceName;
        CompilationSession session = CompilationSession.getActiveSession();
        String oldClassFqcn = null;
        if (session != null) {
            oldClassFqcn = session.getCurrentClassFqcn();
            session.setCurrentClassFqcn(fullPath);
        }

        // Circular dependency check
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();
        if (hasCycle(fullPath, visited, stack)) {
            reportError(ctxNode, "Arayüz '" + interfaceName + "' için döngüsel kalıtım (circular dependency) tespit edildi.");
        }

        // extends list check for interfaces
        if (ctxNode.typeList() != null) {
            for (OceanParser.TypeContext tc : ctxNode.typeList().type()) {
                String typeName = tc.getText();
                String typeDesc = resolveTypeAndCheckAccess(typeName, tc);
                String resolvedPath;
                if (TypeChecker.isClassType(typeDesc)) {
                    resolvedPath = typeDesc.substring(1, typeDesc.length() - 1);
                } else {
                    resolvedPath = typeDesc;
                }
                // Record interface-extends dependency for incremental build tracking
                recordDependency(resolvedPath);

                boolean isInterface = isInterface(resolvedPath);
                if (!isInterface) {
                    reportError(tc, "Arayüz '" + interfaceName + "' arayüz olmayan '" + typeName + "' sınıfından türetilemez.");
                }
            }
        }

        ctx.enterClass(interfaceName, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        visitChildren(ctxNode);
        ctx.exitClass();
        if (session != null) {
            session.setCurrentClassFqcn(oldClassFqcn);
        }
        return null;
    }

    @Override
    public String visitAnnotationDeclaration(OceanParser.AnnotationDeclarationContext ctxNode) {
        String annoName = ctxNode.anyId().getText();
        String parentClass = ctx.getCurrentClass();
        if (parentClass != null) {
            annoName = parentClass + "$" + annoName;
        }
        String fullPath =getFilePackage() + "/" + annoName;
        CompilationSession session = CompilationSession.getActiveSession();
        String oldClassFqcn = null;
        if (session != null) {
            oldClassFqcn = session.getCurrentClassFqcn();
            session.setCurrentClassFqcn(fullPath);
        }

        ctx.enterClass(annoName, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
        visitChildren(ctxNode);
        ctx.exitClass();
        if (session != null) {
            session.setCurrentClassFqcn(oldClassFqcn);
        }
        return null;
    }

    @Override
    public String visitAnnotationMemberDeclaration(OceanParser.AnnotationMemberDeclarationContext ctxNode) {
        if (ctxNode.expression() != null) {
            ctxNode.expression().accept(this);
        }
        return null;
    }

    @Override
    public String visitEnumDeclaration(OceanParser.EnumDeclarationContext ctxNode) {
        String enumName = ctxNode.anyId().getText();
        String parentClass = ctx.getCurrentClass();
        if (parentClass != null) {
            enumName = parentClass + "$" + enumName;
        }
        String fullPath = getFilePackage() + "/" + enumName;
        CompilationSession session = CompilationSession.getActiveSession();
        String oldClassFqcn = null;
        if (session != null) {
            oldClassFqcn = session.getCurrentClassFqcn();
            session.setCurrentClassFqcn(fullPath);
        }

        // Duplicate fields and methods check, similar to class
        Set<String> declaredFields = new HashSet<>();
        Set<String> declaredMethodSignatures = new HashSet<>();

        if (ctxNode.enumConstants() != null) {
            for (OceanParser.EnumConstantContext ec : ctxNode.enumConstants().enumConstant()) {
                declaredFields.add(ec.anyId().getText());
            }
        }

        if (ctxNode.memberDeclaration() != null) {
            for (OceanParser.MemberDeclarationContext member : ctxNode.memberDeclaration()) {
                if (member.fieldDeclaration() != null) {
                    for (OceanParser.VariableDeclaratorContext d : member.fieldDeclaration().variableDeclarator()) {
                        String fName = d.anyId().getText();
                        if (!declaredFields.add(fName)) {
                            reportError(member.fieldDeclaration(), "Alan '" + fName + "' bu enum'da zaten tanımlı.");
                        }
                    }
                } else if (member.methodDeclaration() != null) {
                    OceanParser.MethodDeclarationContext md = member.methodDeclaration();
                    if (md instanceof OceanParser.NormalMethodContext nmc) {
                        String mName = nmc.anyId().getText();
                        StringBuilder paramSb = new StringBuilder("(");
                        if (nmc.parameterList() != null) {
                            for (OceanParser.ParameterContext p : nmc.parameterList().parameter()) {
                                String pType = p.type().getText();
                                if (pType.equals("variable") || pType.equals("var") || pType.equals("value")) {
                                    reportError(p, "Metot ve yapıcı metot parametrelerinde 'variable' / 'value' türü kullanılamaz: '" + p.anyId().getText() + "'");
                                }
                                paramSb.append(resolveTypeAndCheckAccess(pType, p));
                            }
                        }
                        paramSb.append(")");
                        String sig = mName + paramSb;
                        if (!declaredMethodSignatures.add(sig)) {
                            reportError(nmc, "Metot '" + mName + "' aynı parametre imzasıyla bu enum'da zaten tanımlı.");
                        }
                    }
                }
            }
        }

        // Global registry'den enum bilgilerini al
        Map<String, String> fields = new HashMap<>(
                CompilerRegistry.globalFieldRegistry.getOrDefault(fullPath, Collections.emptyMap()));
        Map<String, Boolean> fieldStatic = new HashMap<>(getGlobalFieldStaticity(fullPath));
        Map<String, String> methods = new HashMap<>(
                CompilerRegistry.globalMethodRegistry.getOrDefault(fullPath, Collections.emptyMap()));
        Map<String, Boolean> methodStatic = new HashMap<>(getGlobalMethodStaticity(fullPath));

        // AST'den de alan ve metot bilgilerini topla (registry eksikse diye)
        if (ctxNode.memberDeclaration() != null) {
            for (OceanParser.MemberDeclarationContext member : ctxNode.memberDeclaration()) {
                if (member.fieldDeclaration() != null) {
                    OceanParser.FieldDeclarationContext fd = member.fieldDeclaration();
                    boolean fStatic = ModifierHelper.isStatic(fd.modifier());
                    String fType = fd.type() != null
                            ? resolveTypeAndCheckAccess(fd.type().getText(), fd)
                            : OceanTypeSystem.OBJECT_DESC;
                    for (OceanParser.VariableDeclaratorContext d : fd.variableDeclarator()) {
                        String fName = d.anyId().getText();
                        fields.putIfAbsent(fName, fType);
                        fieldStatic.putIfAbsent(fName, fStatic);
                    }
                } else if (member.methodDeclaration() != null) {
                    OceanParser.MethodDeclarationContext md = member.methodDeclaration();
                    if (md instanceof OceanParser.NormalMethodContext nmc) {
                        String mName = nmc.anyId().getText();
                        boolean mStatic = nmc.extType != null || ModifierHelper.isStatic(nmc.modifier());

                        String rType = "V";
                        if (nmc.VOID() == null) {
                            if (!nmc.type().isEmpty()) {
                                rType = resolveTypeAndCheckAccess(nmc.type(0).getText(), nmc);
                            }
                        }

                        methods.putIfAbsent(mName, "()" + rType); // Simple descriptor for analysis
                        methodStatic.putIfAbsent(mName, mStatic);
                    }
                } else if (member.constructorDeclaration() != null) {
                    String cName = member.constructorDeclaration().anyId().getText();
                    methods.putIfAbsent(cName, "()V");
                    methodStatic.putIfAbsent(cName, false);
                }
            }
        }

        ctx.enterClass(enumName, fields, fieldStatic, methods, methodStatic);
        visitChildren(ctxNode);
        ctx.exitClass();
        if (session != null) {
            session.setCurrentClassFqcn(oldClassFqcn);
        }
        return null;
    }

    // ========== Yapıcı (Constructor) Tanımı ==========

    @Override
    public String visitConstructorDeclaration(OceanParser.ConstructorDeclarationContext ctxNode) {
        String ctorName = ctxNode.anyId().getText();
        // Ocean'da constructor syntax ile main() veya normal metot da tanımlanabiliyor
        boolean isMain = "main".equals(ctorName);
        boolean isRealConstructor = ctorName.equals(ctx.getCurrentClass()) || (ctx.getCurrentClass() != null && ctx.getCurrentClass().endsWith("$" + ctorName));

        boolean isAsync = ctxNode.modifier() != null && ctxNode.modifier().stream().anyMatch(m -> m.getText().equals("async"));
        if (isAsync && isRealConstructor) {
            reportError(ctxNode, "Yapıcı metotlar (constructors) 'async' olarak tanımlanamaz.");
        }

        // throws listesini oku
        List<String> ctorThrows = new ArrayList<>();
        if (ctxNode.typeList() != null) {
            for (OceanParser.TypeContext tc : ctxNode.typeList().type()) {
                String excType = resolveTypeAndCheckAccess(tc.getText(), tc);
                if (excType != null) ctorThrows.add(excType);
            }
        }
        ctx.enterMethod(ctorName, isMain, "V", ctorThrows);

        // main() için args parametresi
        if (isMain) {
            ctx.declareVariable("args", "[Ljava/lang/String;", false, true);
        }

        // Parametreleri scope'a ekle
        if (ctxNode.parameterList() != null) {
            for (OceanParser.ParameterContext param : ctxNode.parameterList().parameter()) {
                String pName = param.anyId().getText();
                String pType = resolveTypeAndCheckAccess(param.type().getText(), param);
                if (param.ELLIPSIS() != null) {
                    pType = "[" + pType;
                }
                ctx.declareVariable(pName, pType, false, true);
            }
        }

        // Gövdeyi analiz et
        if (ctxNode.block() != null) {
            if (isRealConstructor) {
                boolean hasSuperCall = false;
                if (ctxNode.block().statement() != null && !ctxNode.block().statement().isEmpty()) {
                    OceanParser.StatementContext firstStmt = ctxNode.block().statement(0);
                    if (firstStmt instanceof OceanParser.SuperStmtContext) {
                        hasSuperCall = true;
                    }
                }
                if (!hasSuperCall) {
                    String superName = getCurrentSuperName();
                    if (superName != null && !superName.equals("java/lang/Object") && !superName.equals("java/lang/Enum")) {
                        String resolved = OverloadResolver.resolve(superName.replace('.', '/'), "<init>", new ArrayList<>());
                        if (resolved == null) {
                            reportError(ctxNode, "Üst sınıf '" + superName.replace('/', '.') + "' varsayılan (parametresiz) yapıcı metoda sahip değil. Alt sınıf yapıcısı açıkça super(...) çağırmalıdır.");
                        }
                    }
                }
            }
            visitBlock(ctxNode.block());
        }

        ctx.exitMethod();
        return null;
    }

    @Override
    public String visitSuperStmt(OceanParser.SuperStmtContext ctxNode) {
        String currentClass = ctx.getCurrentClass();
        String currentMethod = ctx.getCurrentMethod();
        if (currentMethod == null || !currentMethod.equals(currentClass)) {
            reportError(ctxNode, "super(...) yalnızca yapıcı metotlar (constructor) içinde çağrılabilir.");
            return null;
        }

        ParseTree parent = ctxNode.getParent();
        if (parent instanceof OceanParser.BlockContext block) {
            ParseTree grandParent = parent.getParent();
            if (grandParent instanceof OceanParser.ConstructorDeclarationContext) {
                if (block.statement() != null && !block.statement().isEmpty() && block.statement(0) == ctxNode) {
                    List<String> argTypes = new ArrayList<>();
                    if (ctxNode.argumentList() != null) {
                        for (OceanParser.ExpressionContext expr : getArgumentExpressions(ctxNode.argumentList())) {
                            String type = inferExprType(expr);
                            argTypes.add(type != null ? type : OceanTypeSystem.OBJECT_DESC);
                        }
                    }
                    String superName = getCurrentSuperName();
                    if (superName != null) {
                        String resolved = OverloadResolver.resolve(superName.replace('.', '/'), "<init>", argTypes);
                        if (resolved == null) {
                            reportError(ctxNode, "Üst sınıf '" + superName.replace('/', '.') + "' üzerinde uygun yapıcı metot (constructor) bulunamadı. Parametre tipleri: " + argTypes);
                        }
                    }
                    return null;
                }
            }
        }

        reportError(ctxNode, "super(...) yalnızca yapıcı metodun (constructor) ilk ifadesi olarak çağrılabilir.");
        return null;
    }

    // ========== Metot Tanımı ==========

    @Override
    public String visitNormalMethod(OceanParser.NormalMethodContext ctxNode) {
        List<String> methodParams = new ArrayList<>();
        if (ctxNode.typeParameter() != null) {
            for (OceanParser.TypeParameterContext tp : ctxNode.typeParameter()) {
                methodParams.add(tp.anyId().getText());
            }
        }
        this.symbolTable.getTypeParams().addAll(methodParams);

        String methodName = ctxNode.anyId().getText();
        //String oldMethod = ctx.getCurrentMethod();
        boolean isStatic = ctxNode.extType != null || ModifierHelper.isStatic(ctxNode.modifier());
        boolean isAbstract = ModifierHelper.isAbstract(ctxNode.modifier());

        // Dönüş tipini belirle
        String returnType = null;
        if (ctxNode.extType != null) {
            // Extension method: return type is either type(0) if type().size() > 1, or null
            if (ctxNode.type().size() > 1) {
                returnType = resolveTypeAndCheckAccess(ctxNode.type(0).getText(), ctxNode);
            }
        } else {
            // Normal method: return type is type(0) if it exists
            if (!ctxNode.type().isEmpty()) {
                returnType = resolveTypeAndCheckAccess(ctxNode.type(0).getText(), ctxNode);
            }
        }

        if (ctxNode.VOID() != null || (returnType == null && "main".equals(methodName))) {
            returnType = "V";
        }

        // Ezme (override) uyumluluk kontrolü
        StringBuilder paramSb = new StringBuilder("(");
        if (ctxNode.parameterList() != null) {
            for (OceanParser.ParameterContext param : ctxNode.parameterList().parameter()) {
                paramSb.append(resolveTypeAndCheckAccess(param.type().getText(), param));
            }
        }
        paramSb.append(")");
        String paramDesc = paramSb.toString();
        boolean isAsyncMethod = false;
        if (ctxNode.modifier() != null) {
            for (OceanParser.ModifierContext m : ctxNode.modifier()) {
                if (m.getText().equals("async")) {
                    isAsyncMethod = true;
                    break;
                }
            }
        }
        String effectiveReturnType;
        if (isAsyncMethod) {
            String rawRet = returnType != null ? returnType : OceanTypeSystem.OBJECT_DESC;
            String boxedRawRet = TypeChecker.isPrimitive(rawRet) ? OceanTypeSystem.getBoxedDescriptor(rawRet) : rawRet;
            effectiveReturnType = "Ljava/util/concurrent/CompletableFuture<" + boxedRawRet + ">;";
        } else {
            effectiveReturnType = returnType != null ? returnType : OceanTypeSystem.OBJECT_DESC;
        }

        String currentClass = ctx.getCurrentClass();
        if (currentClass != null) {
            checkOverrideCompatibility(currentClass, methodName, paramDesc, effectiveReturnType, ctxNode);

            // Check @Override annotation
            boolean hasOverrideAnnotation = false;
            ParserRuleContext parent = ctxNode.getParent();
            if (parent instanceof OceanParser.MemberDeclarationContext) {
                List<OceanParser.AnnotationContext> annotations = ((OceanParser.MemberDeclarationContext) parent).annotation();
                if (annotations != null) {
                    for (OceanParser.AnnotationContext ann : annotations) {
                        String name = ann.typeName().getText();
                        if (name.equals("Override") || name.equals("java.lang.Override")) {
                            hasOverrideAnnotation = true;
                            break;
                        }
                    }
                }
            }
            if (hasOverrideAnnotation) {
                String classPath = getFilePackage() + "/" + currentClass;
                if (!hasMatchingOverride(classPath, methodName, paramDesc)) {
                    reportError(ctxNode, "Metot '" + methodName + "' @Override ile işaretlenmiş ancak üst sınıflarda eşleşen bir metot bulunamadı.");
                }
            }
        }

        // throws listesini oku
        List<String> methodThrows = new ArrayList<>();
        if (ctxNode.typeList() != null) {
            for (OceanParser.TypeContext tc : ctxNode.typeList().type()) {
                String excType = resolveTypeAndCheckAccess(tc.getText(), tc);
                if (excType != null) methodThrows.add(excType);
            }
        }

        ctx.enterMethod(methodName, isStatic, returnType, methodThrows);

        // Extension method: declare 'this' as the extended type
        if (ctxNode.extType != null) {
            String extendedType = resolveTypeAndCheckAccess(ctxNode.extType.getText(), ctxNode);
            ctx.declareVariable("this", extendedType, ctxNode.extType.getText(), true, true);
        }

        // Parametreleri scope'a ekle
        if (ctxNode.parameterList() != null) {
            for (OceanParser.ParameterContext param : ctxNode.parameterList().parameter()) {
                String pName = param.anyId().getText();
                String pType = resolveTypeAndCheckAccess(param.type().getText(), param);
                if (param.ELLIPSIS() != null) {
                    pType = "[" + pType;
                }
                String rawType = param.type().getText() + (param.ELLIPSIS() != null ? "[]" : "");
                ctx.declareVariable(pName, pType, rawType, false, true);
            }
        }

        if (ctx.getCurrentClass() != null) {
            String currentClassPath = getFilePackage() + "/" + ctx.getCurrentClass();
            if (CompilerRegistry.globalIsInterfaceSet.contains(currentClassPath)) {
                if (ctxNode.block() != null) {
                    reportError(ctxNode, "Arayüz metotları gövde (body) içeremez.");
                }
            }
        }

        // Gövdeyi analiz et
        if (ctxNode.block() != null && !isAbstract) {
            visitBlock(ctxNode.block());

            // Return yolu analizi (void olmayan metotlar için)
            if (!"V".equals(returnType)) {
                if (!allPathsReturn(ctxNode.block())) {
                    reportError(ctxNode, "'" + methodName + "' metodu tüm yollardan değer döndürmüyor");
                }
            }
        }

        ctx.exitMethod();
        methodParams.forEach(this.symbolTable.getTypeParams()::remove);
        return null;
    }

    @Override
    public String visitMainMethod(OceanParser.MainMethodContext ctxNode) {
        ctx.enterMethod("main", true, "V");
        ctx.declareVariable("args", "[Ljava/lang/String;", false, true);

        if (ctxNode.block() != null) {
            visitBlock(ctxNode.block());
        }

        ctx.exitMethod();
        return null;
    }

    // ========== Blok ve Kapsamlar ==========

    @Override
    public String visitBlock(OceanParser.BlockContext ctxNode) {
        enterScope();

        // Dead code tespiti: return/throw/stop/skip sonrası erişilemez kod
        boolean foundTerminator = false;
        for (OceanParser.StatementContext stmt : ctxNode.statement()) {
            if (foundTerminator) {
                reportWarning(stmt, "Erişilemez kod (Unreachable code)");
                break; // Bir uyarı yeterli
            }
            visit(stmt);
            if (alwaysTerminates(stmt)) {
                foundTerminator = true;
            }
        }

        exitScope();
        return null;
    }

    private boolean alwaysTerminates(ParseTree node) {
        switch (node) {
            case null -> {
                return false;
            }
            case OceanParser.BlockContext block -> {
                for (OceanParser.StatementContext stmt : block.statement()) {
                    if (alwaysTerminates(stmt)) return true;
                }
                return false;
            }
            case OceanParser.BlockStmtContext blockStmtContext -> {
                return alwaysTerminates(blockStmtContext.block());
            }
            default -> {
            }
        }

        if (node instanceof OceanParser.ReturnStmtContext ||
            node instanceof OceanParser.ThrowStmtContext ||
            node instanceof OceanParser.StopStmtContext ||
            node instanceof OceanParser.SkipStmtContext) {
            return true;
        }

        if (node instanceof OceanParser.IfStmtContext) {
            OceanParser.IfStatementContext ifCtx = ((OceanParser.IfStmtContext) node).ifStatement();
            boolean isAlwaysTrue = false;
            boolean isAlwaysFalse = false;
            if (ifCtx.expression() != null) {
                String cond = ifCtx.expression().getText();
                if (cond.equals("true")) isAlwaysTrue = true;
                else if (cond.equals("false")) isAlwaysFalse = true;
            }
            if (isAlwaysTrue) {
                return alwaysTerminates(ifCtx.statement(0));
            }
            if (isAlwaysFalse && ifCtx.statement().size() >= 2) {
                return alwaysTerminates(ifCtx.statement(1));
            }
            if (ifCtx.statement().size() == 2) {
                return alwaysTerminates(ifCtx.statement(0)) && alwaysTerminates(ifCtx.statement(1));
            }
            return false;
        }

        if (node instanceof OceanParser.WhileStmtContext) {
            OceanParser.WhileStatementContext whileCtx = ((OceanParser.WhileStmtContext) node).whileStatement();
            boolean isAlwaysTrue = false;
            if (whileCtx.expression() != null) {
                String condText = whileCtx.expression().getText();
                if (condText.equals("true")) {
                    isAlwaysTrue = true;
                }
            }
            if (isAlwaysTrue) {
                if (!hasSwitchStop(whileCtx.statement())) {
                    return true;
                }
            }
        }

        if (node instanceof OceanParser.DoWhileStmtContext) {
            OceanParser.DoWhileStatementContext doCtx = ((OceanParser.DoWhileStmtContext) node).doWhileStatement();
            boolean isAlwaysTrue = false;
            if (doCtx.expression() != null) {
                String condText = doCtx.expression().getText();
                if (condText.equals("true")) {
                    isAlwaysTrue = true;
                }
            }
            if (isAlwaysTrue) {
                if (!hasSwitchStop(doCtx.statement())) {
                    return true;
                }
            }
        }

        if (node instanceof OceanParser.SwitchStmtContext) {
            OceanParser.SwitchStatementContext swCtx = ((OceanParser.SwitchStmtContext) node).switchStatement();
            if (swCtx.defaultCase() != null) {
                boolean allCasesTerm = true;
                for (OceanParser.SwitchCaseContext caseCtx : swCtx.switchCase()) {
                    boolean caseTerm = false;
                    for (OceanParser.StatementContext s : caseCtx.statement()) {
                        if (alwaysTerminates(s)) { caseTerm = true; break; }
                    }
                    if (!caseTerm) { allCasesTerm = false; break; }
                }
                if (allCasesTerm) {
                    boolean defaultTerm = false;
                    for (OceanParser.StatementContext s : swCtx.defaultCase().statement()) {
                        if (alwaysTerminates(s)) { defaultTerm = true; break; }
                    }
                    return defaultTerm;
                }
            }
            return false;
        }

        if (node instanceof OceanParser.TryStmtContext) {
            OceanParser.TryStatementContext tryCtx = ((OceanParser.TryStmtContext) node).tryStatement();
            if (tryCtx.FINALLY() != null) {
                OceanParser.BlockContext finallyBlock = tryCtx.block(tryCtx.block().size() - 1);
                if (alwaysTerminates(finallyBlock)) {
                    return true;
                }
            }
            boolean tryTerm = alwaysTerminates(tryCtx.block(0));
            boolean allCatchesTerm = true;
            for (OceanParser.CatchClauseContext catchCtx : tryCtx.catchClause()) {
                if (!alwaysTerminates(catchCtx.block())) {
                    allCatchesTerm = false;
                    break;
                }
            }
            return tryTerm && allCatchesTerm;
        }

        if (node instanceof OceanParser.LockStmtContext) {
            return alwaysTerminates(((OceanParser.LockStmtContext) node).lockBlockStatement().block());
        }

        if (node instanceof OceanParser.LabeledStmtContext) {
            return alwaysTerminates(((OceanParser.LabeledStmtContext) node).statement());
        }

        return false;
    }

    // ========== Sabit Koşul Tespiti (Dead Branch) ==========

    private String getVariableName(OceanParser.ExpressionContext expr) {
        if (expr == null) return null;
        if (expr instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) expr).primary();
            if (p instanceof OceanParser.IdPrimaryContext) {
                return ((OceanParser.IdPrimaryContext) p).anyId().getText();
            } else if (p instanceof OceanParser.ParenthesizedPrimaryContext) {
                return getVariableName(((OceanParser.ParenthesizedPrimaryContext) p).expression());
            }
        }
        return null;
    }

    private void findInstanceOfExpr(OceanParser.ExpressionContext cond, Map<String, String> results) {
        switch (cond) {
            case null -> {}
            case OceanParser.InstanceOfExprContext ioe -> {
                String varName = getVariableName(ioe.expression());
                if (varName != null && ioe.instanceofPattern() != null) {
                    results.put(varName, ioe.instanceofPattern().getText());
                }
            }
            case OceanParser.LogicalAndExprContext lae -> {
                findInstanceOfExpr(lae.expression(0), results);
                findInstanceOfExpr(lae.expression(1), results);
            }
            case OceanParser.PrimaryExprContext primaryExprContext -> {
                OceanParser.PrimaryContext p = primaryExprContext.primary();
                if (p instanceof OceanParser.ParenthesizedPrimaryContext) {
                    findInstanceOfExpr(((OceanParser.ParenthesizedPrimaryContext) p).expression(), results);
                }
            }
            default -> {}
        }
    }

    @Override
    public String visitIfStmt(OceanParser.IfStmtContext ctxNode) {
        OceanParser.IfStatementContext ifCtx = ctxNode.ifStatement();

        enterScope();
        if (ifCtx.expression() != null) {
            visit(ifCtx.expression());
        }
        String condType = inferExprType(ifCtx.expression());
        Map<String, Boolean> stateBefore = ctx.getVariableInitializationState();

        if (condType != null && !TypeChecker.isBoolean(condType) && !TypeChecker.isObjectType(condType)) {
            reportWarning(ifCtx, "if koşulu boolean olmalı, '" + TypeChecker.humanReadable(condType) + "' verildi");
        }

        // Smart cast extraction
        Map<String, String> smartCasts = new HashMap<>();
        findInstanceOfExpr(ifCtx.expression(), smartCasts);

        // Apply smart casts
        Map<String, String> oldDescriptors = new HashMap<>();
        for (Map.Entry<String, String> entry : smartCasts.entrySet()) {
            String varName = entry.getKey();
            String targetType = entry.getValue();
            TypeInfo info = ctx.lookupVariable(varName);
            if (info != null) {
                oldDescriptors.put(varName, info.getDescriptor());
                String resolvedDesc = resolveTypeAndCheckAccess(targetType, ifCtx);
                info.setDescriptor(resolvedDesc);
                symbolTable.setType(varName, resolvedDesc);
            }
        }

        // Flow-sensitive null checks
        Map<String, Boolean> thenBranchNonNull = new HashMap<>();
        Map<String, Boolean> elseBranchNonNull = new HashMap<>();
        extractNullChecks(ifCtx.expression(), thenBranchNonNull, elseBranchNonNull);

        for (Map.Entry<String, Boolean> entry : thenBranchNonNull.entrySet()) {
            setVariableNullable(entry.getKey(), false);
        }

        // if ve else dallarını analiz et
        if (!ifCtx.statement().isEmpty()) {
            visit(ifCtx.statement(0));
        }

        // Restore smart casts
        for (Map.Entry<String, String> entry : oldDescriptors.entrySet()) {
            String varName = entry.getKey();
            String oldDesc = entry.getValue();
            TypeInfo info = ctx.lookupVariable(varName);
            if (info != null) {
                info.setDescriptor(oldDesc);
                symbolTable.setType(varName, oldDesc);
            }
        }

        exitScope();
        Map<String, Boolean> stateAfterThen = ctx.getVariableInitializationState();
        ctx.setVariableInitializationState(stateBefore);

        Map<String, Boolean> stateAfterElse;
        if (ifCtx.statement().size() > 1) {
            enterNullabilityScope();
            for (Map.Entry<String, Boolean> entry : elseBranchNonNull.entrySet()) {
                setVariableNullable(entry.getKey(), false);
            }
            visit(ifCtx.statement(1));
            exitNullabilityScope();
            stateAfterElse = ctx.getVariableInitializationState();
        } else {
            stateAfterElse = stateBefore;
        }

        Map<String, Boolean> stateMerged = mergeInitializationStates(stateAfterThen, stateAfterElse);
        ctx.setVariableInitializationState(stateMerged);

        return null;
    }

    @Override
    public String visitInstanceOfExpr(OceanParser.InstanceOfExprContext ctxNode) {
        visit(ctxNode.expression());
        if (ctxNode.instanceofPattern() instanceof OceanParser.TypeInstanceofPatternContext typePat && typePat.anyId() != null) {
            String varName = typePat.anyId().getText();
            String typeName = typePat.type() != null ? typePat.type().getText() : "Object";
            String resolvedDesc = resolveTypeAndCheckAccess(typeName, ctxNode);
            if (!ctx.isDefinedInCurrentScope(varName)) {
                ctx.declareVariable(varName, resolvedDesc, typeName, true, true);
            }
        }
        return "Z";
    }

    @Override
    public String visitMemberDeclaration(OceanParser.MemberDeclarationContext ctxNode) {
        if (ctxNode.STATIC() != null && ctxNode.block() != null) {
            ctx.enterMethod("<clinit>", true, "V");
            visit(ctxNode.block());
            ctx.exitMethod();
            return null;
        }
        return super.visitMemberDeclaration(ctxNode);
    }

    // ========== Değişken Tanımlama ==========

    @Override
    public String visitVariableDeclStmt(OceanParser.VariableDeclStmtContext ctxNode) {
        OceanParser.VariableDeclarationContext varCtx = ctxNode.variableDeclaration();

        List<OceanParser.VariableDeclaratorContext> decls = Collections.emptyList();
        String declaredType = null;
        String rawType = null;
        boolean isFinal = false;

        if (varCtx instanceof OceanParser.FinalVarDeclContext vd) {
            decls = vd.variableDeclarator();
            declaredType = resolveTypeAndCheckAccess(vd.type().getText(), vd);
            rawType = vd.type().getText();
            isFinal = true;
        } else if (varCtx instanceof OceanParser.TypedVarDeclContext vd) {
            decls = vd.variableDeclarator();
            declaredType = resolveTypeAndCheckAccess(vd.type().getText(), vd);
            rawType = vd.type().getText();
            isFinal = false;
        } else if (varCtx instanceof OceanParser.VariableDeclContext vd) {
            decls = vd.variableDeclarator();
            isFinal = false;
        } else if (varCtx instanceof OceanParser.ValueDeclContext vd) {
            decls = vd.variableDeclarator();
            isFinal = true;
        }

        for (OceanParser.VariableDeclaratorContext d : decls) {
            String varName = d.anyId().getText();
            OceanParser.ExpressionContext exprCtx = d.expression();

            if (isFinal && exprCtx == null) {
                reportError(ctxNode, "'value' (sabit) bildirimleri başlatıcı değer (initializer) içermelidir: '" + varName + "'");
            }

            // Aynı scope'ta yeniden tanımlama kontrolü
            if (ctx.isDefinedInCurrentScope(varName)) {
                reportError(ctxNode, "'" + varName + "' değişkeni bu kapsamda zaten tanımlı");
                continue;
            }

            if (exprCtx != null) {
                visit(exprCtx);
            }
            // İfade tipini çıkar
            String exprType = exprCtx != null ? inferExprType(exprCtx) : null;

            // Tip uyumluluk kontrolü
            String finalType;
            if (declaredType != null) {
                finalType = declaredType;
                checkPrimitiveLiteralBounds(declaredType, exprCtx, ctxNode);
                boolean compatible = declaredType.contains("<") 
                    ? TypeChecker.isAssignableGenericAware(declaredType, exprType, true, session)
                    : TypeChecker.isAssignable(declaredType, exprType, session);
                if (exprType != null && !compatible) {
                    reportError(ctxNode, "'" + TypeChecker.humanReadable(exprType) + "' tipi '"
                            + TypeChecker.humanReadable(declaredType) + "' tipine atanamaz");
                }
            } else {
                if (exprCtx == null) {
                    reportError(ctxNode, "Tipi belirtilmeyen 'variable' / 'value' değişken bildirimleri başlatıcı değer (initializer) içermelidir: '" + varName + "'");
                }
                finalType = exprType != null ? exprType : OceanTypeSystem.OBJECT_DESC;
            }
            String currentRawType = rawType != null ? rawType : finalType;

            ctx.declareVariable(varName, finalType, currentRawType, isFinal, exprCtx != null);
            boolean isNonNull = isExpressionNonNull(exprCtx);
            setVariableNullable(varName, !isNonNull);
        }
        return null;
    }

    private void checkPrimitiveLiteralBounds(String targetType, OceanParser.ExpressionContext exprCtx, ParserRuleContext ctxNode) {
        if (targetType == null || exprCtx == null) return;
        String cleanType = TypeChecker.cleanDescriptor(targetType);
        if (!"B".equals(cleanType) && !"S".equals(cleanType) && !"C".equals(cleanType) && !"I".equals(cleanType)) {
            return;
        }

        boolean isNegative = false;
        OceanParser.ExpressionContext subExpr = exprCtx;
        if (exprCtx instanceof OceanParser.UnaryExprContext unary && unary.op != null && unary.op.getType() == OceanParser.MINUS) {
            isNegative = true;
            subExpr = unary.expression();
        }

        if (subExpr instanceof OceanParser.PrimaryExprContext primaryExpr) {
            OceanParser.PrimaryContext p = primaryExpr.primary();
            if (p instanceof OceanParser.NumberPrimaryContext numPrimary) {
                String text = numPrimary.NUMBER().getText();
                if (text.endsWith("L") || text.endsWith("l") || text.endsWith("F") || text.endsWith("f") || text.endsWith("D") || text.endsWith("d")) {
                    text = text.substring(0, text.length() - 1);
                }
                try {
                    if (text.contains(".")) {
                        double doubleVal = Double.parseDouble(text);
                        if (isNegative) doubleVal = -doubleVal;
                        reportError(ctxNode, "Ondalıklı sabit değer (" + doubleVal + "), tamsayı tipine ('" + TypeChecker.humanReadable(cleanType) + "') atanamaz");
                    } else {
                        long longVal = Long.parseLong(text);
                        if (isNegative) longVal = -longVal;
                        validatePrimitiveBounds(cleanType, (isNegative ? "-" : "") + text, longVal, ctxNode);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private void validatePrimitiveBounds(String cleanType, String rawText, long longVal, ParserRuleContext ctxNode) {
        switch (cleanType) {
            case "B" -> {
                if (longVal < -128 || longVal > 127) {
                    reportError(ctxNode, "Sabit değer (" + rawText + "), 'byte' tipi aralığının [-128, 127] dışındadır");
                }
            }
            case "S" -> {
                if (longVal < -32768 || longVal > 32767) {
                    reportError(ctxNode, "Sabit değer (" + rawText + "), 'short' tipi aralığının [-32768, 32767] dışındadır");
                }
            }
            case "C" -> {
                if (longVal < 0 || longVal > 65535) {
                    reportError(ctxNode, "Sabit değer (" + rawText + "), 'char' tipi aralığının [0, 65535] dışındadır");
                }
            }
            case "I" -> {
                if (longVal < Integer.MIN_VALUE || longVal > Integer.MAX_VALUE) {
                    reportError(ctxNode, "Sabit değer (" + rawText + "), 'int' tipi aralığının [-2147483648, 2147483647] dışındadır");
                }
            }
        }
    }

    // ========== Atama ==========

    @Override
    public String visitAssignmentStmt(OceanParser.AssignmentStmtContext ctxNode) {
        OceanParser.AssignmentContext assignCtx = ctxNode.assignment();
        OceanParser.ExpressionContext lhs = assignCtx.expression(0);
        OceanParser.ExpressionContext rhs = assignCtx.expression(1);

        // Sol taraf bir değişken mi?
        if (lhs instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) lhs).primary();
            if (p instanceof OceanParser.ThisRefPrimaryContext) {
                reportError(ctxNode, "'this' anahtar kelimesi yeniden atanamaz");
                return null;
            }
            if (p instanceof OceanParser.IdPrimaryContext) {
                String varName = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                if ("this".equals(varName)) {
                    reportError(ctxNode, "'this' anahtar kelimesi yeniden atanamaz");
                    return null;
                }
                if ("super".equals(varName)) {
                    reportError(ctxNode, "'super' anahtar kelimesi yeniden atanamaz");
                    return null;
                }
                TypeInfo varInfo = ctx.lookupVariable(varName);

                if (varInfo == null && !ctx.hasField(varName)) {
                    reportError(ctxNode, "Tanımsız değişken: '" + varName + "'");
                    return null;
                }

                // Final ve captured kontrolü
                if (varInfo != null) {
                    if (ctx.isCapturedVariable(varName)) {
                        reportError(ctxNode, "Lambda veya anonim sınıf içinden erişilen yerel değişkenler final veya effectively final olmalıdır: '" + varName + "'");
                        return null;
                    }
                    if (varInfo.isCaptured()) {
                        reportError(ctxNode, "Lambda veya anonim sınıf içinden erişilen yerel değişkenler final veya effectively final olmalıdır: '" + varName + "'");
                        return null;
                    }
                    if (varInfo.isInitialized()) {
                        varInfo.setMutated(true);
                    }
                }

                if (varInfo != null && varInfo.isFinal() && varInfo.isInitialized()) {
                    reportError(ctxNode, "Final değişken '" + varName + "' yeniden atanamaz");
                    return null;
                }

                if (varInfo == null) {
                    String currentClass = ctx.getCurrentClass();
                    if (currentClass != null) {
                        String classPath = resolveClassName(currentClass);
                        boolean isFinal = CompilerRegistry.globalFieldMutability.getOrDefault(classPath, Collections.emptyMap())
                                .getOrDefault(varName, false);
                        if (isFinal) {
                            reportError(ctxNode, "Final değişken '" + varName + "' yeniden atanamaz");
                            return null;
                        }
                    }
                }

                // Tip uyumluluk kontrolü
                String targetType = varInfo != null ? varInfo.getDescriptor() : ctx.getFieldType(varName);
                String rhsType = inferExprType(rhs);
                if (targetType != null && rhsType != null && !TypeChecker.isAssignable(targetType, rhsType, session)) {
                    reportError(ctxNode, "'" + TypeChecker.humanReadable(rhsType) + "' tipi '"
                             + TypeChecker.humanReadable(targetType) + "' tipine atanamaz");
                }

                if (varInfo != null) {
                    ctx.markInitialized(varName);
                    boolean rhsNonNull = isExpressionNonNull(rhs);
                    setVariableNullable(varName, !rhsNonNull);
                }
            }
        } else if (lhs instanceof OceanParser.MemberCallExprContext mCtx) {
            String memberName = mCtx.anyId().getText();
            OceanParser.ExpressionContext ownerExpr = mCtx.expression();
            String ownerDesc = inferExprType(ownerExpr);
            if (TypeChecker.isClassType(ownerDesc)) {
                String internalOwner = ownerDesc.substring(1, ownerDesc.length() - 1);
                boolean isFinal = CompilerRegistry.globalFieldMutability.getOrDefault(internalOwner, Collections.emptyMap())
                        .getOrDefault(memberName, false);
                if (isFinal) {
                    reportError(ctxNode, "Final değişken '" + memberName + "' yeniden atanamaz");
                    return null;
                }
            }

            // Tip uyumluluk kontrol (Alan/Özellik ataması için)
            if (mCtx.LPAREN() == null) {
                String targetType = inferExprType(lhs);
                String rhsType = inferExprType(rhs);
                if (targetType != null && rhsType != null && !TypeChecker.isAssignable(targetType, rhsType, session)) {
                    reportError(ctxNode, "'" + TypeChecker.humanReadable(rhsType) + "' tipi '"
                            + TypeChecker.humanReadable(targetType) + "' tipine atanamaz");
                }
            }
        } else if (lhs instanceof OceanParser.ArrayAccessExprContext) {
            // Tip uyumluluk kontrol (Dizi eleman ataması için)
            String targetType = inferExprType(lhs);
            String rhsType = inferExprType(rhs);
            if (targetType != null && rhsType != null && !TypeChecker.isAssignable(targetType, rhsType, session)) {
                reportError(ctxNode, "'" + TypeChecker.humanReadable(rhsType) + "' tipi '"
                        + TypeChecker.humanReadable(targetType) + "' tipine atanamaz");
            }
        }

        // Sağ ve sol tarafı da analiz et (iç içe ifadeler için)
        if (lhs != null) {
            visit(lhs);
        }
        if (rhs != null) {
            visit(rhs);
        }
        return null;
    }

    @Override
    public String visitPrefixExpr(OceanParser.PrefixExprContext ctxNode) {
        checkMutability(ctxNode.expression(), ctxNode);
        visit(ctxNode.expression());
        // ++ ve -- için sayısal tip zorunluluğu kontrolü
        int opType = ctxNode.op.getType();
        if (opType == OceanParser.PLUS_PLUS || opType == OceanParser.MINUS_MINUS) {
            String exprType = inferExprType(ctxNode.expression());
            if (exprType != null && !TypeChecker.isNumeric(exprType)) {
                reportError(ctxNode,
                    "'" + ctxNode.op.getText() + "' operatörü yalnızca sayısal tipler (int, long, float, double) "
                    + "üzerinde kullanılabilir, '" + TypeChecker.humanReadable(exprType) + "' tipi geçerli değil");
            }
        }
        return null;
    }

    @Override
    public String visitPostfixExpr(OceanParser.PostfixExprContext ctxNode) {
        checkMutability(ctxNode.expression(), ctxNode);
        visit(ctxNode.expression());
        // PostfixExpr yalnızca ++ ve -- içerir — sayısal tip zorunluluğu kontrolü
        String exprType = inferExprType(ctxNode.expression());
        if (exprType != null && !TypeChecker.isNumeric(exprType)) {
            reportError(ctxNode,
                "'" + ctxNode.op.getText() + "' operatörü yalnızca sayısal tipler (int, long, float, double) "
                + "üzerinde kullanılabilir, '" + TypeChecker.humanReadable(exprType) + "' tipi geçerli değil");
        }
        return null;
    }

    private void checkMutability(OceanParser.ExpressionContext expr, ParserRuleContext ctxNode) {
        if (expr instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) expr).primary();
            if (p instanceof OceanParser.IdPrimaryContext) {
                String varName = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                TypeInfo varInfo = ctx.lookupVariable(varName);
                if (varInfo != null) {
                    if (ctx.isCapturedVariable(varName)) {
                        reportError(ctxNode, "Lambda veya anonim sınıf içinden erişilen yerel değişkenler final veya effectively final olmalıdır: '" + varName + "'");
                        return;
                    }
                    if (varInfo.isCaptured()) {
                        reportError(ctxNode, "Lambda veya anonim sınıf içinden erişilen yerel değişkenler final veya effectively final olmalıdır: '" + varName + "'");
                        return;
                    }
                    varInfo.setMutated(true);
                    if (varInfo.isFinal() && varInfo.isInitialized()) {
                        reportError(ctxNode, "Final değişken '" + varName + "' yeniden atanamaz");
                    }
                } else {
                    String currentClass = ctx.getCurrentClass();
                    if (currentClass != null) {
                        String classPath = resolveClassName(currentClass);
                        boolean isFinal = CompilerRegistry.globalFieldMutability.getOrDefault(classPath, Collections.emptyMap())
                                .getOrDefault(varName, false);
                        if (isFinal) {
                            reportError(ctxNode, "Final değişken '" + varName + "' yeniden atanamaz");
                        }
                    }
                }
            }
        } else if (expr instanceof OceanParser.MemberCallExprContext mCtx) {
            String memberName = mCtx.anyId().getText();
            OceanParser.ExpressionContext ownerExpr = mCtx.expression();
            String ownerDesc = inferExprType(ownerExpr);
            if (TypeChecker.isClassType(ownerDesc)) {
                String internalOwner = ownerDesc.substring(1, ownerDesc.length() - 1);
                boolean isFinal = CompilerRegistry.globalFieldMutability.getOrDefault(internalOwner, Collections.emptyMap())
                        .getOrDefault(memberName, false);
                if (isFinal) {
                    reportError(ctxNode, "Final değişken '" + memberName + "' yeniden atanamaz");
                }
            }
        }
    }

    // ========== While / For ==========

    @Override
    public String visitWhileStmt(OceanParser.WhileStmtContext ctxNode) {
        OceanParser.WhileStatementContext whileCtx = ctxNode.whileStatement();
        if (whileCtx.expression() != null) {
            visit(whileCtx.expression());
        }
        String condType = inferExprType(whileCtx.expression());

        if (condType != null && !TypeChecker.isBoolean(condType) && !TypeChecker.isObjectType(condType)) {
            reportWarning(whileCtx,
                    "while koşulu boolean olmalı, '" + TypeChecker.humanReadable(condType) + "' verildi");
        }

        Map<String, Boolean> stateBefore = ctx.getVariableInitializationState();

        ctx.enterLoop();
        if (whileCtx.statement() != null) {
            visit(whileCtx.statement());
        }
        ctx.exitLoop();

        ctx.setVariableInitializationState(stateBefore);
        return null;
    }

    @Override
    public String visitForStmt(OceanParser.ForStmtContext ctxNode) {
        ctx.enterLoop();
        Map<String, Boolean> stateBefore = ctx.getVariableInitializationState();
        enterScope();
        OceanParser.ForStatementContext forStmtCtx = ctxNode.forStatement();
        OceanParser.ForControlContext forCtx = forStmtCtx.forControl();

        if (forCtx != null) {
            // from-to loop (forControl rule)
            String varName = forCtx.anyId().getText();
            String varType = OceanTypeSystem.OBJECT_DESC;
            String typeName = (forCtx.type() != null) ? forCtx.type().getText() : (forCtx.VARIABLE() != null ? "variable" : null);

            if (typeName != null && (typeName.equals("variable") || typeName.equals("var") || typeName.equals("value"))) {
                varType = inferExprType(forCtx.expression(0));
            } else if (typeName != null) {
                varType = resolveTypeAndCheckAccess(typeName, ctxNode);
            }
            String rawType = (forCtx.type() != null ? forCtx.type().getText() : varType);
            if (ctx.lookupVariable(varName) != null) {
                reportWarning(ctxNode, "Döngü sayaç değişkeni '" + varName + "' üst kapsamdaki bir değişkeni gölgeliyor.");
            }
            ctx.declareVariable(varName, varType, rawType, false, true);
        } else if (forStmtCtx.anyId() != null) {
            // in loop (directly in forStatement: FOR LPAREN type anyId IN expression RPAREN)
            String varName = forStmtCtx.anyId().getText();
            String varType = OceanTypeSystem.OBJECT_DESC;
            String typeName = (forStmtCtx.type() != null) ? forStmtCtx.type().getText() : (forStmtCtx.VARIABLE() != null ? "variable" : null);

            if (typeName != null && (typeName.equals("variable") || typeName.equals("var") || typeName.equals("value"))) {
                String collType = inferExprType(forStmtCtx.expression());
                if (collType != null && collType.startsWith("[")) {
                    varType = collType.substring(1);
                } else if (collType != null) {
                    // Try to resolve the element type of generic iterable Collection<T>
                    String rawCollType = getRawExprType(forStmtCtx.expression());
                    String arg = getGenericTypeArgument(rawCollType);
                    varType = resolveTypeAndCheckAccess(arg, ctxNode);
                }
            } else if (typeName != null) {
                varType = resolveTypeAndCheckAccess(typeName, ctxNode);
            }
            String rawType = (forStmtCtx.type() != null ? forStmtCtx.type().getText() : varType);
            if (ctx.lookupVariable(varName) != null) {
                reportWarning(ctxNode, "Döngü sayaç değişkeni '" + varName + "' üst kapsamdaki bir değişkeni gölgeliyor.");
            }
            ctx.declareVariable(varName, varType, rawType, false, true);
        }

        visitChildren(ctxNode);
        exitScope();
        ctx.exitLoop();
        ctx.setVariableInitializationState(stateBefore);
        return null;
    }

    @Override
    public String visitDoWhileStmt(OceanParser.DoWhileStmtContext ctxNode) {
        OceanParser.DoWhileStatementContext doWhileCtx = ctxNode.doWhileStatement();
        ctx.enterLoop();
        if (doWhileCtx.statement() != null) {
            visit(doWhileCtx.statement());
        }
        if (doWhileCtx.expression() != null) {
            visit(doWhileCtx.expression());
        }
        // Check condition type
        String condType = inferExprType(doWhileCtx.expression());
        if (condType != null && !TypeChecker.isBoolean(condType) && !TypeChecker.isObjectType(condType)) {
            reportWarning(doWhileCtx,
                    "do-while koşulu boolean olmalı, '" + TypeChecker.humanReadable(condType) + "' verildi");
        }
        ctx.exitLoop();
        return null;
    }

    @Override
    public String visitThrowStmt(OceanParser.ThrowStmtContext ctxNode) {
        OceanParser.ExpressionContext expr = ctxNode.expression();
        if (expr != null) {
            visit(expr);
            String thrownType = inferExprType(expr);
            if (thrownType != null) {
                boolean isThrowable = session != null ? session.isSubType(thrownType, "Ljava/lang/Throwable;")
                        : CompilationSession.getFallbackSession().isSubType(thrownType, "Ljava/lang/Throwable;");
                if (!isThrowable) {
                    reportError(ctxNode, "throw ifadesi Throwable tipinde olmalıdır, '" + TypeChecker.humanReadable(thrownType) + "' verildi");
                } else if (!isExceptionHandled(thrownType)) {
                    // Checked exception ne catch'lenmiş ne de metodun throws listesinde — hata!
                    reportError(ctxNode, "Yakalanmamış checked exception: '" + TypeChecker.humanReadable(thrownType) + "'. "
                            + "Bu istisnayı ya yakalayın (trying/catch) ya da metot imzasına 'throws "
                            + TypeChecker.humanReadable(thrownType) + "' ekleyin.");
                }
            }
        }
        return null;
    }

    // ========== Return ==========

    @Override
    public String visitReturnStmt(OceanParser.ReturnStmtContext ctxNode) {
        OceanParser.ReturnStatementContext retCtx = ctxNode.returnStatement();
        String expectedReturn = ctx.getCurrentMethodReturnType();

        if (retCtx.expression() != null) {
            visit(retCtx.expression());
            String actualType = inferExprType(retCtx.expression());
            if ("V".equals(expectedReturn)) {
                reportError(retCtx, "void metottan değer döndürülemez");
            } else if (actualType != null && expectedReturn != null
                    && !TypeChecker.isAssignable(expectedReturn, actualType, session)) {
                reportError(retCtx, "'" + TypeChecker.humanReadable(actualType) + "' tipi '"
                        + TypeChecker.humanReadable(expectedReturn) + "' dönüş tipine uyumsuz");
            }
        } else {
            if (!"V".equals(expectedReturn) && expectedReturn != null) {
                reportError(retCtx, "void olmayan metot bir değer döndürmelidir");
            }
        }
        return null;
    }

    // ========== Stop / Skip (break / continue) ==========

    @Override
    public String visitSwitchStmt(OceanParser.SwitchStmtContext ctxNode) {
        OceanParser.SwitchStatementContext swCtx = ctxNode.switchStatement();
        if (swCtx.expression() != null) {
            visit(swCtx.expression());
        }

        ctx.enterSwitch();

        Map<String, Boolean> stateBefore = ctx.getVariableInitializationState();
        List<Map<String, Boolean>> branchStates = new ArrayList<>();

        Set<String> seenCases = new HashSet<>();
        // Analyze each case
        for (OceanParser.SwitchCaseContext caseCtx : swCtx.switchCase()) {
            ctx.setVariableInitializationState(stateBefore);
            if (caseCtx.switchLabel() != null) {
                String caseVal = caseCtx.switchLabel().getText();
                if (!seenCases.add(caseVal)) {
                    reportError(caseCtx, "Tekrarlayan switch case değeri: '" + caseVal + "'");
                }
            }
            if (caseCtx.block() != null) {
                visit(caseCtx.block());
            } else if (caseCtx.statement() != null) {
                for (OceanParser.StatementContext s : caseCtx.statement()) {
                    visit(s);
                }
            }
            branchStates.add(ctx.getVariableInitializationState());
        }

        // Analyze default case if present
        boolean hasDefault = swCtx.defaultCase() != null;
        if (hasDefault) {
            ctx.setVariableInitializationState(stateBefore);
            for (OceanParser.StatementContext s : swCtx.defaultCase().statement()) {
                visit(s);
            }
            branchStates.add(ctx.getVariableInitializationState());
        } else {
            // If no default case, the switch can be skipped entirely
            branchStates.add(stateBefore);
        }

        // Merge all branch states
        Map<String, Boolean> mergedState = branchStates.getFirst();
        for (int i = 1; i < branchStates.size(); i++) {
            mergedState = mergeInitializationStates(mergedState, branchStates.get(i));
        }
        ctx.setVariableInitializationState(mergedState);

        ctx.exitSwitch();
        return null;
    }

    @Override
    public String visitStopStmt(OceanParser.StopStmtContext ctxNode) {
        if (!ctx.isBreakAllowed()) {
            reportError(ctxNode, "'stop' (break) yalnızca döngü veya switch içinde kullanılabilir");
        }
        return null;
    }

    @Override
    public String visitSkipStmt(OceanParser.SkipStmtContext ctxNode) {
        if (!ctx.isInLoop()) {
            reportError(ctxNode, "'skip' (continue) yalnızca döngü içinde kullanılabilir");
        }
        return null;
    }

    // ========== İfade Analizi (Identifier) ==========

    private boolean isLhsOfAssignment(OceanParser.IdPrimaryContext idCtx) {
        ParseTree parent = idCtx.getParent();
        if (parent instanceof OceanParser.PrimaryExprContext) {
            ParseTree grandParent = parent.getParent();
            if (grandParent instanceof OceanParser.AssignmentContext assign) {
                return assign.expression(0) == parent && assign.op.getType() == OceanParser.ASSIGN;
            }
        }
        return false;
    }

    @Override
    public String visitIdPrimary(OceanParser.IdPrimaryContext ctxNode) {
        String name = ctxNode.anyId().getText();
        TypeInfo info = ctx.lookupVariable(name);
        if (info != null) {
            if (ctx.isCapturedVariable(name)) {
                info.setCaptured(true);
                if (info.isMutated()) {
                    reportError(ctxNode, "Lambda veya anonim sınıf içinden erişilen yerel değişkenler final veya effectively final olmalıdır: '" + name + "'");
                }
            }
            if (!info.isInitialized() && !isLhsOfAssignment(ctxNode)) {
                reportError(ctxNode, "Değişken '" + name + "' başlatılmamış olabilir.");
            }
            info.incrementUsage();
            return info.getDescriptor();
        }

        // Statik import mu?
        String staticImportFieldType = resolveStaticImportField(name);
        if (staticImportFieldType != null) {
            return staticImportFieldType;
        }

        // Sınıf alanı mı?
        if (ctx.hasField(name)) {
            // Statik bağlamdan instance alan erişimi kontrolü
            if (ctx.isInStaticContext() && !ctx.isFieldStatic(name)) {
                reportError(ctxNode, "Statik bağlamdan instance alan '" + name + "' erişilemez");
            }
            return ctx.getFieldType(name);
        }

        // Aynı sınıftaki metot mu? (MethodCallExpr tarafından çözümlenir)
        if (ctx.hasMethod(name)) {
            return OceanTypeSystem.OBJECT_DESC; // Metot referansı — hata değil
        }

        // Eğer doğrudan bir metot çağrısı ise (yani MethodCallExpr'in callee'si ise),
        // bunu bir tip/sınıf ismi olarak çözümlemeye çalışma.
        boolean isDirectMethodCall = false;
        if (ctxNode.getParent() instanceof OceanParser.PrimaryExprContext) {
            if (ctxNode.getParent().getParent() instanceof OceanParser.MethodCallExprContext) {
                isDirectMethodCall = true;
            }
        }
        if (isDirectMethodCall) {
            return OceanTypeSystem.OBJECT_DESC;
        }

        // Sınıf ismi mi? (static çağrı için)
        if (isKnownClass(name)) {
            String fqName = resolveClassName(name);
            checkClassAccess(fqName, ctxNode);
            return OceanTypeSystem.wrapObjectType(fqName);
        }

        // Bilinen Ocean tipi mi? (OceanOutput, OceanInput, variable, value vb.)
        String typeDesc = resolveTypeAndCheckAccess(name, ctxNode);
        if (!typeDesc.equals("L" + name + "/" + name + ";")) {
            return typeDesc; // Bilinen tip — hata değil
        }

        // Tanımsız — ama sadece bu bir metot çağrısının parçası DEĞİLSE hata ver
        // (MethodCallExpr/MemberCallExpr bağlamında çözümlenebilir)
        if (!isPartOfMethodCall(ctxNode)) {
            reportError(ctxNode, "Tanımsız tanımlayıcı: '" + name + "'");
        }
        return OceanTypeSystem.OBJECT_DESC;
    }

    @Override
    public String visitThisRefPrimary(OceanParser.ThisRefPrimaryContext ctxNode) {
        if (ctx.isInStaticContext() && ctx.lookupVariable("this") == null) {
            reportError(ctxNode, "Statik bağlamda 'this' anahtar kelimesi kullanılamaz");
        }
        TypeInfo info = ctx.lookupVariable("this");
        if (info != null) {

            return info.getDescriptor();
        }

        return OceanTypeSystem.wrapObjectType(ctx.getCurrentClass());
    }

    @Override
    public String visitSuperRefPrimary(OceanParser.SuperRefPrimaryContext ctxNode) {
        if (ctx.isInStaticContext()) {
            reportError(ctxNode, "Statik bağlamda 'super' anahtar kelimesi kullanılamaz");
        }
        String sup = getCurrentSuperName();
        if (sup != null) {
            return OceanTypeSystem.wrapObjectType(sup.replace(".", "/"));
        }
        return OceanTypeSystem.OBJECT_DESC;
    }

    // ========== Tip Çıkarım Yardımcıları ==========

    /**
     * Bir ifadenin tipini semantik analiz bağlamında çıkarır.
     * Delege edilmiştir: TypeInferenceEngine.inferType(expr, this)
     */
    private String inferExprType(OceanParser.ExpressionContext expr) {
        return TypeInferenceEngine.inferType(expr, this);
    }

    // [LEGACY] Eski AST tabanlı tip çıkarım mantığı (korunmuştur)
    /*
    private String inferExprTypeLegacy(OceanParser.ExpressionContext expr) {
        if (expr == null)
            return null;

        try {
            // Basit durumlar için hızlı çıkarım
            if (expr instanceof OceanParser.PrimaryExprContext) {
                OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) expr).primary();
                if (p instanceof OceanParser.NumberPrimaryContext) {
                    String val = ((OceanParser.NumberPrimaryContext) p).NUMBER().getText();
                    if (val.contains("."))
                        return "D";
                    char last = Character.toUpperCase(val.charAt(val.length() - 1));
                    if (last == 'F')
                        return "F";
                    if (last == 'D')
                        return "D";
                    if (last == 'L')
                        return "J";
                    return "I";
                }
                if (p instanceof OceanParser.StringPrimaryContext
                        || p instanceof OceanParser.InterpolatedStringPrimaryContext) {
                    return OceanTypeSystem.STRING_DESC;
                }
                if (p instanceof OceanParser.TruePrimaryContext
                        || p instanceof OceanParser.FalsePrimaryContext) {
                    return "Z";
                }
                if (p instanceof OceanParser.NullPrimaryContext) {
                    return "null";
                }
                if (p instanceof OceanParser.IdPrimaryContext) {
                    String name = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                    TypeInfo info = ctx.lookupVariable(name);
                    if (info != null)
                        return info.getDescriptor();
                    if (ctx.hasField(name))
                        return ctx.getFieldType(name);
                    return OceanTypeSystem.STRING_DESC;
                }
                if (p instanceof OceanParser.ThisRefPrimaryContext) {
                    TypeInfo info = ctx.lookupVariable("this");
                    if (info != null)
                        return info.getDescriptor();
                    return ctx.getCurrentClass() != null
                            ? "L" + getFilePackage() + "/" + ctx.getCurrentClass() + ";"
                            : OceanTypeSystem.STRING_DESC;
                }
            }

            if (expr instanceof OceanParser.AddSubExprContext) {
                String left = inferExprTypeLegacy(((OceanParser.AddSubExprContext) expr).expression(0));
                String right = inferExprTypeLegacy(((OceanParser.AddSubExprContext) expr).expression(1));
                if (TypeChecker.isStringType(left) || TypeChecker.isStringType(right))
                    return OceanTypeSystem.STRING_DESC;
                return TypeChecker.getCommonType(
                        left != null ? left : "I",
                        right != null ? right : "I");
            }

            if (expr instanceof OceanParser.MulDivModExprContext) {
                String left = inferExprTypeLegacy(((OceanParser.MulDivModExprContext) expr).expression(0));
                String right = inferExprTypeLegacy(((OceanParser.MulDivModExprContext) expr).expression(1));
                return TypeChecker.getCommonType(
                        left != null ? left : "I",
                        right != null ? right : "I");
            }

            if (expr instanceof OceanParser.ComparisonExprContext
                    || expr instanceof OceanParser.EqualityExprContext
                    || expr instanceof OceanParser.LogicalAndExprContext
                    || expr instanceof OceanParser.LogicalOrExprContext) {
                return "Z";
            }

            if (expr instanceof OceanParser.UnaryExprContext) {
                OceanParser.UnaryExprContext uCtx = (OceanParser.UnaryExprContext) expr;
                if ("!".equals(uCtx.op.getText()))
                    return "Z";
                return inferExprTypeLegacy(uCtx.expression());
            }

            if (expr instanceof OceanParser.CastExprContext) {
                OceanParser.CastExprContext castCtx = (OceanParser.CastExprContext) expr;
                return resolveTypeAndCheckAccess(castCtx.type().getText(), castCtx);
            }

            if (expr instanceof OceanParser.NewObjectExprContext) {
                OceanParser.NewObjectExprContext newCtx = (OceanParser.NewObjectExprContext) expr;
                String baseType = resolveTypeAndCheckAccess(newCtx.type().getText(), newCtx);
                if (newCtx.expression() != null && !newCtx.expression().isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < newCtx.expression().size(); i++) {
                        sb.append("[");
                    }
                    return sb.append(baseType).toString();
                }
                return baseType;
            }

            if (expr instanceof OceanParser.TernaryExprContext) {
                return inferExprTypeLegacy(((OceanParser.TernaryExprContext) expr).expression(1));
            }

            // Member call: obj.method(args) or obj.field
            if (expr instanceof OceanParser.MemberCallExprContext mcc) {
                String memberName = mcc.anyId().getText();
                if (mcc.LPAREN() != null) {
                    // Try to resolve specific return type from registry
                    String ownerType = inferExprTypeLegacy(mcc.expression());
                    if (ownerType != null) {
                        // Try extension method first
                        Map<String, List<CompilerRegistry.ExtensionMethodInfo>> extMethods = CompilerRegistry.globalExtensionMethodRegistry.get(TypeChecker.cleanDescriptor(ownerType));
                        if (extMethods != null && extMethods.containsKey(memberName)) {
                            List<CompilerRegistry.ExtensionMethodInfo> candidates = extMethods.get(memberName);
                            if (!candidates.isEmpty()) {
                                String desc = candidates.get(0).descriptor;
                                if (desc != null && desc.contains(")")) {
                                    return desc.substring(desc.lastIndexOf(')') + 1);
                                }
                            }
                        }

                        // Try standard class method
                        if (ownerType.startsWith("L")) {
                            String internalOwner = ownerType.substring(1, ownerType.length() - 1);
                            Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(internalOwner);
                            if (methods != null && methods.containsKey(memberName)) {
                                String desc = methods.get(memberName);
                                if (desc != null && desc.contains(")")) {
                                    return desc.substring(desc.lastIndexOf(')') + 1);
                                }
                            }
                        }
                    }

                    if (isBooleanMethodName(memberName))
                        return "Z";
                    if (memberName.startsWith("is") || memberName.startsWith("has"))
                        return "Z";
                    return OceanTypeSystem.STRING_DESC;
                }
                // It's a field access — check registry
                String ownerType = inferExprTypeLegacy(mcc.expression());
                if (ownerType != null && ownerType.startsWith("L")) {
                    String internalOwner = ownerType.substring(1, ownerType.length() - 1);
                    Map<String, String> fields = CompilerRegistry.globalFieldRegistry.get(internalOwner);
                    if (fields != null && fields.containsKey(memberName)) {
                        return fields.get(memberName);
                    }
                }
                return OceanTypeSystem.STRING_DESC;
            }

            // Safe member call: obj?.method(args) or obj?.field
            if (expr instanceof OceanParser.SafeMemberCallExprContext smcc) {
                String memberName = smcc.anyId().getText();
                if (smcc.LPAREN() != null) {
                    String ownerType = inferExprTypeLegacy(smcc.expression());
                    if (ownerType != null) {
                        // Try extension method first
                        Map<String, List<CompilerRegistry.ExtensionMethodInfo>> extMethods = CompilerRegistry.globalExtensionMethodRegistry.get(TypeChecker.cleanDescriptor(ownerType));
                        if (extMethods != null && extMethods.containsKey(memberName)) {
                            List<CompilerRegistry.ExtensionMethodInfo> candidates = extMethods.get(memberName);
                            if (!candidates.isEmpty()) {
                                String desc = candidates.get(0).descriptor;
                                if (desc != null && desc.contains(")")) {
                                    return desc.substring(desc.lastIndexOf(')') + 1);
                                }
                            }
                        }

                        // Try standard class method
                        if (ownerType.startsWith("L")) {
                            String internalOwner = ownerType.substring(1, ownerType.length() - 1);
                            Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(internalOwner);
                            if (methods != null && methods.containsKey(memberName)) {
                                String desc = methods.get(memberName);
                                if (desc != null && desc.contains(")")) {
                                    return desc.substring(desc.lastIndexOf(')') + 1);
                                }
                            }
                        }
                    }
                    if (isBooleanMethodName(memberName))
                        return "Z";
                    if (memberName.startsWith("is") || memberName.startsWith("has"))
                        return "Z";
                } else {
                    String ownerType = inferExprTypeLegacy(smcc.expression());
                    if (ownerType != null && ownerType.startsWith("L")) {
                        String internalOwner = ownerType.substring(1, ownerType.length() - 1);
                        Map<String, String> fields = CompilerRegistry.globalFieldRegistry.get(internalOwner);
                        if (fields != null && fields.containsKey(memberName)) {
                            return fields.get(memberName);
                        }
                    }
                }
                return OceanTypeSystem.STRING_DESC;
            }

            // Direct method call: method(args)
            if (expr instanceof OceanParser.MethodCallExprContext mcExpr) {
                // Try to resolve the callee expression
                OceanParser.ExpressionContext callee = mcExpr.expression();
                if (callee instanceof OceanParser.PrimaryExprContext pec) {
                    OceanParser.PrimaryContext p = pec.primary();
                    if (p instanceof OceanParser.IdPrimaryContext ipc) {
                        String name = ipc.anyId().getText();
                        
                        // Check local class methods first
                        String currentOwner = getFilePackage() + "/" + ctx.getCurrentClass();
                        Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(currentOwner);
                        if (methods != null && methods.containsKey(name)) {
                             String desc = methods.get(name);
                             if (desc != null && desc.contains(")")) {
                                 return desc.substring(desc.lastIndexOf(')') + 1);
                             }
                        }

                        if (isBooleanMethodName(name))
                            return "Z";
                        if (name.startsWith("is") || name.startsWith("has"))
                            return "Z";
                    }
                }
                return OceanTypeSystem.STRING_DESC;
            }

            // Array/index access: expr[index]
            if (expr instanceof OceanParser.ArrayAccessExprContext) {
                return OceanTypeSystem.STRING_DESC;
            }

            // Null coalescing: expr ?? fallback
            if (expr instanceof OceanParser.NullCoalescingExprContext ncExpr) {
                return inferExprTypeLegacy(ncExpr.expression(0));
            }

            // Shift, bit operations → int
            if (expr instanceof OceanParser.ShiftExprContext
                    || expr instanceof OceanParser.BitAndExprContext
                    || expr instanceof OceanParser.BitOrExprContext
                    || expr instanceof OceanParser.BitXorExprContext) {
                return "I";
            }

            // Lambda → functional type
            if (expr instanceof OceanParser.LambdaExprContext) {
                return OceanTypeSystem.STRING_DESC;
            }

            // instanceof → always boolean
            if (expr instanceof OceanParser.InstanceOfExprContext) {
                return "Z";
            }

            // Postfix (x++, x--) → preserve operand type
            if (expr instanceof OceanParser.PostfixExprContext) {
                return inferExprTypeLegacy(((OceanParser.PostfixExprContext) expr).expression());
            }

            // Prefix (++x, --x) → preserve operand type
            if (expr instanceof OceanParser.PrefixExprContext) {
                return inferExprTypeLegacy(((OceanParser.PrefixExprContext) expr).expression());
            }

        } catch (Exception e) {
            // Çıkarım başarısız — genel tip döndür
        }

        return OceanTypeSystem.STRING_DESC;
    }
    */

    // ========== TypeInferenceEngine.InferenceContext Implementation ==========

    @Override
    public String lookupVariableType(String name) {
        TypeInfo info = ctx.lookupVariable(name);
        if (info != null) {
            String desc = info.getDescriptor();
            if (desc != null && desc.endsWith("?") && !isVariableNullable(name)) {
                return desc.substring(0, desc.length() - 1);
            }
            return desc;
        }
        return null;
    }

    @Override
    public String lookupVariableRawType(String name) {
        TypeInfo info = ctx.lookupVariable(name);
        if (info != null) {
            String rawType = info.getRawType();
            if (rawType != null && rawType.endsWith("?") && !isVariableNullable(name)) {
                return rawType.substring(0, rawType.length() - 1);
            }
            return rawType;
        }
        return null;
    }

    @Override
    public String lookupVariableOriginalType(String name) {
        TypeInfo info = ctx.lookupVariable(name);
        if (info != null) {
            String originalDesc = info.getOriginalDescriptor();
            if (originalDesc != null && originalDesc.endsWith("?") && !isVariableNullable(name)) {
                return originalDesc.substring(0, originalDesc.length() - 1);
            }
            return originalDesc;
        }
        return null;
    }

    @Override
    public String getFieldType(String ownerDesc, String fieldName) {
        if (ownerDesc == null) {
            String localFieldType = ctx.getFieldType(fieldName);
            if (localFieldType != null) return localFieldType;
            return resolveStaticImportField(fieldName);
        }
        if (TypeChecker.isClassType(ownerDesc)) {
            String internalOwner = ownerDesc.substring(1, ownerDesc.length() - 1);
            Map<String, String> fields = CompilerRegistry.globalFieldRegistry.get(internalOwner);
            if (fields != null && fields.containsKey(fieldName)) {
                return fields.get(fieldName);
            }
            try {
                Class<?> clazz = OceanTypeSystem.forName(internalOwner.replace('/', '.'));
                Field f = findReflectedField(clazz, fieldName);
                if (f != null) return Type.getDescriptor(f.getType());
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private String resolveOverloadedMethodDesc(String internalOwner, String methodName, List<String> argTypes) {
        return OverloadResolver.resolve(internalOwner, methodName, argTypes);
    }

    @Override
    public String resolveMethodReturnType(String ownerDesc, String methodName, List<String> argTypes) {
        if (ownerDesc != null) {
            // Try extension method first
            Map<String, List<CompilerRegistry.ExtensionMethodInfo>> extMethods = CompilerRegistry.globalExtensionMethodRegistry.get(TypeChecker.cleanDescriptor(ownerDesc));
            if (extMethods != null && extMethods.containsKey(methodName)) {
                List<CompilerRegistry.ExtensionMethodInfo> candidates = extMethods.get(methodName);
                if (!candidates.isEmpty()) {
                    CompilerRegistry.ExtensionMethodInfo emi = candidates.getFirst();
                    String desc = emi.descriptor();
                    if (desc != null && desc.contains(")")) {
                        String rawRet = desc.substring(desc.lastIndexOf(')') + 1);
                        // If this extension method is async, wrap return type in CompletableFuture
                        String emiOwnerClean = emi.owner();
                        if (CompilerRegistry.globalAsyncMethodSet.contains(emiOwnerClean + "#" + methodName)) {
                            return "Ljava/util/concurrent/CompletableFuture<" + rawRet + ">;"
                                    .replace("<<", "<").replace(">>", ">");
                        }
                        return rawRet;
                    }
                }
            }

            // Try standard class method
            if (TypeChecker.isClassType(ownerDesc)) {
                String internalOwner = ownerDesc.substring(1, ownerDesc.length() - 1);
                String desc = resolveOverloadedMethodDesc(internalOwner, methodName, argTypes);
                if (desc != null && desc.contains(")")) {
                    return desc.substring(desc.lastIndexOf(')') + 1);
                }
            }
        } else {
            // Check current class methods first
            String currentOwner = getCurrentClassName();
            String desc = resolveOverloadedMethodDesc(currentOwner, methodName, argTypes);
            if (desc != null && desc.contains(")")) {
                return desc.substring(desc.lastIndexOf(')') + 1);
            }

            // Check static imports!
            String staticOwner = resolveStaticImportMethodOwner(methodName);
            if (staticOwner != null) {
                desc = resolveOverloadedMethodDesc(staticOwner, methodName, argTypes);
                if (desc != null && desc.contains(")")) {
                    return desc.substring(desc.lastIndexOf(')') + 1);
                }
            }
        }
        //if (isBooleanMethodName(methodName)) return "Z";

        return OceanTypeSystem.OBJECT_DESC;
    }

    @Override
    public String getTypeDescriptor(String typeName) {
        return resolveTypeAndCheckAccess(typeName, null);
    }

    @Override
    public String getCurrentClassName() {
        return getFilePackage() + "/" + ctx.getCurrentClass();
    }

    @Override
    public String getCurrentSuperName() {
        String currentClass = getFilePackage() + "/" + ctx.getCurrentClass();
        return CompilerRegistry.globalSuperClassRegistry.get(currentClass);
    }

    // ========== Return Yolu Analizi ==========

    /**
     * Bir blok içindeki tüm yolların return ile bitip bitmediğini kontrol eder.
     */
    private boolean allPathsReturn(OceanParser.BlockContext block) {
        if (block == null || block.statement() == null)
            return false;

        List<OceanParser.StatementContext> stmts = block.statement();
        if (stmts.isEmpty())
            return false;

        // Herhangi bir statement return ise blok return ediyor
        for (OceanParser.StatementContext stmt : stmts) {
            if (statementReturns(stmt)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasSwitchStop(ParseTree tree) {
        if (tree == null) return false;
        if (tree instanceof OceanParser.StopStmtContext) {
            return true;
        }
        if (tree instanceof OceanParser.ForStmtContext ||
            tree instanceof OceanParser.WhileStmtContext ||
            tree instanceof OceanParser.DoWhileStmtContext ||
            tree instanceof OceanParser.SwitchStmtContext ||
            tree instanceof OceanParser.SwitchExprContext) {
            return false; // breaks inside these target the nested construct
        }
        int childCount = tree.getChildCount();
        for (int i = 0; i < childCount; i++) {
            if (hasSwitchStop(tree.getChild(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean caseBranchReturns(List<OceanParser.StatementContext> stmts) {
        if (stmts == null || stmts.isEmpty()) return false;
        boolean returns = false;
        for (OceanParser.StatementContext stmt : stmts) {
            if (statementReturns(stmt)) {
                returns = true;
                break;
            }
        }
        if (!returns) return false;
        for (OceanParser.StatementContext stmt : stmts) {
            if (hasSwitchStop(stmt)) {
                return false;
            }
        }
        return true;
    }

    private boolean statementReturns(OceanParser.StatementContext stmt) {
        if (stmt instanceof OceanParser.ReturnStmtContext)
            return true;
        if (stmt instanceof OceanParser.ThrowStmtContext)
            return true;
        if (stmt instanceof OceanParser.BlockStmtContext) {
            return allPathsReturn(((OceanParser.BlockStmtContext) stmt).block());
        }
        if (stmt instanceof OceanParser.IfStmtContext) {
            OceanParser.IfStatementContext ifCtx = ((OceanParser.IfStmtContext) stmt).ifStatement();
            boolean isAlwaysTrue = false;
            boolean isAlwaysFalse = false;
            if (ifCtx.expression() != null) {
                String cond = ifCtx.expression().getText();
                if (cond.equals("true")) isAlwaysTrue = true;
                else if (cond.equals("false")) isAlwaysFalse = true;
            }
            if (isAlwaysTrue) {
                return statementReturns(ifCtx.statement(0));
            }
            if (isAlwaysFalse && ifCtx.statement().size() >= 2) {
                return statementReturns(ifCtx.statement(1));
            }
            if (ifCtx.statement().size() >= 2) {
                return statementReturns(ifCtx.statement(0)) && statementReturns(ifCtx.statement(1));
            }
        }
        if (stmt instanceof OceanParser.WhileStmtContext) {
            OceanParser.WhileStatementContext whileCtx = ((OceanParser.WhileStmtContext) stmt).whileStatement();
            boolean isAlwaysTrue = false;
            if (whileCtx.expression() != null) {
                String condText = whileCtx.expression().getText();
                if (condText.equals("true")) {
                    isAlwaysTrue = true;
                }
            }
            if (isAlwaysTrue) {
                if (!hasSwitchStop(whileCtx.statement())) {
                    return true;
                }
            }
        }
        if (stmt instanceof OceanParser.DoWhileStmtContext) {
            OceanParser.DoWhileStatementContext doCtx = ((OceanParser.DoWhileStmtContext) stmt).doWhileStatement();
            boolean isAlwaysTrue = false;
            if (doCtx.expression() != null) {
                String condText = doCtx.expression().getText();
                if (condText.equals("true")) {
                    isAlwaysTrue = true;
                }
            }
            if (isAlwaysTrue) {
                if (!hasSwitchStop(doCtx.statement())) {
                    return true;
                }
            }
        }
        if (stmt instanceof OceanParser.TryStmtContext) {
            OceanParser.TryStatementContext tryCtx = ((OceanParser.TryStmtContext) stmt).tryStatement();
            boolean tryReturns = allPathsReturn(tryCtx.block(0));
            boolean allCatchesReturn = true;
            if (tryCtx.catchClause() != null) {
                for (OceanParser.CatchClauseContext catchCtx : tryCtx.catchClause()) {
                    if (!allPathsReturn(catchCtx.block())) {
                        allCatchesReturn = false;
                        break;
                    }
                }
            }
            boolean finallyReturns = false;
            if (tryCtx.FINALLY() != null) {
                finallyReturns = allPathsReturn(tryCtx.block(tryCtx.block().size() - 1));
            }
            return finallyReturns || (tryReturns && allCatchesReturn);
        }
        if (stmt instanceof OceanParser.LockStmtContext lockStmt) {
            if (lockStmt.lockBlockStatement() != null && lockStmt.lockBlockStatement().block() != null) {
                return allPathsReturn(lockStmt.lockBlockStatement().block());
            }
        }
        if (stmt instanceof OceanParser.SwitchStmtContext) {
            OceanParser.SwitchStatementContext swCtx = ((OceanParser.SwitchStmtContext) stmt).switchStatement();
            if (swCtx.defaultCase() == null) {
                return false;
            }
            for (OceanParser.SwitchCaseContext caseCtx : swCtx.switchCase()) {
                if (!caseBranchReturns(caseCtx.statement())) {
                    return false;
                }
            }
            return caseBranchReturns(swCtx.defaultCase().statement());
        }
        return false;
    }

    @Override
    public String visitTryStmt(OceanParser.TryStmtContext ctxNode) {
        return visitTryStatement(ctxNode.tryStatement());
    }

    @Override
    public String visitTryStatement(OceanParser.TryStatementContext ctxNode) {
        boolean hasResources = ctxNode.resourceList() != null;

        Map<String, Boolean> stateBefore = ctx.getVariableInitializationState();

        if (hasResources) {
            enterScope();
            for (OceanParser.ResourceContext resCtx : ctxNode.resourceList().resource()) {
                String resName = resCtx.anyId().getText();
                String resType = resolveTypeAndCheckAccess(resCtx.type().getText(), resCtx);
                String exprType = inferExprType(resCtx.expression());

                if (exprType != null && !TypeChecker.isAssignable(resType, exprType, session)) {
                    reportError(resCtx, "Uyumsuz tipler: '" + TypeChecker.humanReadable(exprType) + "' tipi '" + TypeChecker.humanReadable(resType) + "' tipine atanamaz");
                }

                if (resType != null) {
                    boolean isAutoCloseable;
                    if (session != null) {
                        isAutoCloseable = session.isSubType(resType, "Ljava/lang/AutoCloseable;") || session.isSubType(resType, "Ljava/io/Closeable;");
                    } else {
                        try {
                            Class<?> cls = OceanTypeSystem.forName(resType.substring(1, resType.length() - 1).replace('/', '.'));
                            isAutoCloseable = AutoCloseable.class.isAssignableFrom(cls) || Closeable.class.isAssignableFrom(cls);
                        } catch (Exception ignored) {
                            isAutoCloseable = true;
                        }
                    }
                    if (!isAutoCloseable) {
                        reportError(resCtx, "Tip '" + TypeChecker.humanReadable(resType) + "' AutoCloseable arayüzünü gerçeklemelidir");
                    }
                }

                ctx.declareVariable(resName, resType, false, true);
            }
        }

        List<String> tryCatches = new ArrayList<>();
        if (ctxNode.catchClause() != null) {
            for (OceanParser.CatchClauseContext catchCtx : ctxNode.catchClause()) {
                for (OceanParser.TypeContext tc : catchCtx.type()) {
                    String excType = resolveTypeAndCheckAccess(tc.getText(), tc);
                    if (excType != null) {
                        tryCatches.add(excType);
                    }
                }
            }
        }

        caughtExceptionsStack.push(tryCatches);
        visit(ctxNode.block(0));
        caughtExceptionsStack.pop();

        if (hasResources) {
            exitScope();
        }

        Map<String, Boolean> stateAfterTry = ctx.getVariableInitializationState();
        List<Map<String, Boolean>> catchStates = new ArrayList<>();

        if (ctxNode.catchClause() != null) {
            for (OceanParser.CatchClauseContext catchCtx : ctxNode.catchClause()) {
                ctx.setVariableInitializationState(stateBefore);

                String varName = catchCtx.anyId().getText();
                List<String> types = new ArrayList<>();
                for (OceanParser.TypeContext tc : catchCtx.type()) {
                    String resolvedType = resolveTypeAndCheckAccess(tc.getText(), tc);
                    boolean isThrowable = session != null ? session.isSubType(resolvedType, "Ljava/lang/Throwable;")
                            : CompilationSession.getFallbackSession().isSubType(resolvedType, "Ljava/lang/Throwable;");
                    if (!isThrowable) {
                        reportError(tc, "Yakalama (catch) bloğundaki tip '" + TypeChecker.humanReadable(resolvedType) + "' java.lang.Throwable sınıfından türetilmelidir");
                    }
                    types.add(resolvedType);
                }
                String declType = types.getFirst();
                if (types.size() > 1) {
                    declType = "Ljava/lang/Exception;";
                }
                enterScope();
                ctx.declareVariable(varName, declType, false, true);
                visitBlock(catchCtx.block());
                exitScope();

                catchStates.add(ctx.getVariableInitializationState());
            }
        }

        // Merge try and catch states
        Map<String, Boolean> stateAfterTryCatch = stateAfterTry;
        for (Map<String, Boolean> catchState : catchStates) {
            stateAfterTryCatch = mergeInitializationStates(stateAfterTryCatch, catchState);
        }
        ctx.setVariableInitializationState(stateAfterTryCatch);

        if (ctxNode.FINALLY() != null) {
            visitBlock(ctxNode.block(ctxNode.block().size() - 1));
        }

        return null;
    }

    @Override
    public String visitLockStmt(OceanParser.LockStmtContext ctxNode) {
        OceanParser.LockBlockStatementContext lockBlock = ctxNode.lockBlockStatement();
        if (lockBlock != null) {
            if (lockBlock.expression() != null) {
                visit(lockBlock.expression());
                String lockType = inferExprType(lockBlock.expression());
                if (TypeChecker.isPrimitive(lockType)) {
                    reportError(lockBlock.expression(), "lock ifadesi referans tipinde olmalıdır, '" + TypeChecker.humanReadable(lockType) + "' verildi");
                }
            }
            if (lockBlock.block() != null) {
                visitBlock(lockBlock.block());
            }
        }
        return null;
    }

    // ========== Yardımcı ==========

    /**
     * Bir IdPrimary'nin bir metot çağrısının hedefi olup olmadığını kontrol eder.
     * Örneğin: foo() → foo bir metot çağrısının parçasıdır.
     */
    private boolean isPartOfMethodCall(OceanParser.IdPrimaryContext idCtx) {
        // IdPrimary → PrimaryExpr → MethodCallExpr zincirini kontrol et
        if (idCtx.getParent() instanceof OceanParser.PrimaryExprContext) {
            ParseTree grandParent = idCtx.getParent().getParent();
            if (grandParent instanceof OceanParser.MethodCallExprContext) {
                return true;
            }
            return grandParent instanceof OceanParser.MemberCallExprContext;
        }
        return false;
    }

    @Override
    public String visitPackageDeclaration(OceanParser.PackageDeclarationContext ctxNode) {
        StringBuilder pkgName = new StringBuilder();
        for (int i = 0; i < ctxNode.anyId().size(); i++) {
            if (i > 0) pkgName.append("/");
            pkgName.append(ctxNode.anyId(i).getText());
        }
        this.currentPackageName = pkgName.toString();
        return null;
    }

    private void checkClassAccess(String targetClass, ParserRuleContext ctxNode) {
        if (targetClass == null) return;
        if (CompilationSession.isSystemPackage(targetClass)) {
            return;
        }

        String currentPackage = getFilePackage().replace('.', '/');

        String targetPackage = "";
        int lastSlash = targetClass.lastIndexOf('/');
        if (lastSlash != -1) {
            targetPackage = targetClass.substring(0, lastSlash);
        }

        int classAccess = 0;
        if (CompilerRegistry.globalClassAccess.containsKey(targetClass)) {
            classAccess = CompilerRegistry.globalClassAccess.get(targetClass);
        } else {
            try {
                Class<?> clazz = OceanTypeSystem.forName(targetClass.replace('/', '.'));
                classAccess = clazz.getModifiers();
            } catch (Throwable ignored) {}
        }

        if (!targetPackage.equals(currentPackage)) {
            if ((classAccess & Opcodes.ACC_PUBLIC) == 0) {
                reportError(ctxNode, "Sınıf '" + targetClass + "' paket-özel (package-private) olduğu için farklı paketten erişilemez");
            }
        }
    }

    private void checkAccess(String targetOwner, String memberName, boolean isMethod, ParserRuleContext ctxNode) {
        if (targetOwner == null || memberName == null) return;
        if (CompilationSession.isSystemPackage(targetOwner)) {
            return;
        }

        String currentClass = getFilePackage() + "/" + ctx.getCurrentClass();
        if (currentClass.equals(targetOwner)) {
            return;
        }

        // Inner/Enclosing class nestmates check
        String outerCurrent = currentClass.contains("$") ? currentClass.substring(0, currentClass.indexOf('$')) : currentClass;
        String outerTarget = targetOwner.contains("$") ? targetOwner.substring(0, targetOwner.indexOf('$')) : targetOwner;
        if (outerCurrent.equals(outerTarget)) {
            return;
        }

        int accessFlags = -1;
        String resolvedOwner = targetOwner;
        Set<String> visited = new HashSet<>();

        while (resolvedOwner != null && visited.add(resolvedOwner)) {
            if (isMethod) {
                Map<String, Integer> methodAccessMap = CompilerRegistry.globalMethodAccess.get(resolvedOwner);
                if (methodAccessMap != null && methodAccessMap.containsKey(memberName)) {
                    accessFlags = methodAccessMap.get(memberName);
                    break;
                }
            } else {
                Map<String, Integer> fieldAccessMap = CompilerRegistry.globalFieldAccess.get(resolvedOwner);
                if (fieldAccessMap != null && fieldAccessMap.containsKey(memberName)) {
                    accessFlags = fieldAccessMap.get(memberName);
                    break;
                }
            }
            resolvedOwner = CompilerRegistry.globalSuperClassRegistry.get(resolvedOwner);
        }

        if (accessFlags == -1) {
            try {
                Class<?> clazz = OceanTypeSystem.forName(targetOwner.replace('/', '.'));
                if (isMethod) {
                    for (Method m : clazz.getMethods()) {
                        if (m.getName().equals(memberName)) {
                            accessFlags = m.getModifiers();
                            break;
                        }
                    }
                    if (accessFlags == -1) {
                        for (Method m : clazz.getMethods()) {
                            if (m.getName().equals(memberName)) {
                                accessFlags = m.getModifiers();
                                break;
                            }
                        }
                    }
                } else {
                    for (Field f : clazz.getDeclaredFields()) {
                        if (f.getName().equals(memberName)) {
                            accessFlags = f.getModifiers();
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (accessFlags == -1) {
            accessFlags = Opcodes.ACC_PUBLIC;
        }

        if ((accessFlags & Opcodes.ACC_PRIVATE) != 0) {
            reportError(ctxNode, (isMethod ? "Metot '" : "Alan '") + memberName + "' private olduğu için '" + targetOwner + "' dışından erişilemez");
            return;
        }

        boolean isPublic = (accessFlags & Opcodes.ACC_PUBLIC) != 0;
        boolean isProtected = (accessFlags & Opcodes.ACC_PROTECTED) != 0;
        boolean isPrivate = (accessFlags & Opcodes.ACC_PRIVATE) != 0;

        String targetPackage = "";
        int lastSlash = targetOwner.lastIndexOf('/');
        if (lastSlash != -1) {
            targetPackage = targetOwner.substring(0, lastSlash);
        }
        //String currentPackage = CompilerConfig.GENERATED_PREFIX + getFilePackage().replace('.', '/');

        if (!isPublic && !isProtected && !isPrivate) {
            String cleanCurrentPkg = getFilePackage().replace('.', '/');
            if (!targetPackage.equals(cleanCurrentPkg)) {
                reportError(ctxNode, (isMethod ? "Metot '" : "Alan '") + memberName + "' paket-özel (package-private) olduğu için farklı paketten erişilemez");
                return;
            }
        }

        if (isProtected) {
            String normalizedCurrentPkg = getFilePackage().replace('.', '/');

            if (!targetPackage.equals(normalizedCurrentPkg)) {
                boolean isSubclass = false;
                Set<String> subVisited = new HashSet<>();
                String parent = CompilerRegistry.globalSuperClassRegistry.get(currentClass);
                while (parent != null && subVisited.add(parent)) {
                    if (parent.equals(targetOwner)) {
                        isSubclass = true;
                        break;
                    }
                    parent = CompilerRegistry.globalSuperClassRegistry.get(parent);
                }
                if (!isSubclass) {
                    reportError(ctxNode, (isMethod ? "Metot '" : "Alan '") + memberName + "' protected olduğu için miras alınmayan farklı paketten erişilemez");
                }
            }
        }
    }

    private String resolveTypeAndCheckAccess(String typeName, ParserRuleContext ctxNode) {
        if (typeName == null) return null;
        String desc = SymbolTable.getDescriptor(typeName, importedClasses, this.symbolTable.getTypeParams());
        if (TypeChecker.isClassType(desc)) {
            // Generic argümanları temizleyip ham sınıf ismini kullan (checkClassAccess generics anlayamaz)
            String internalClass = TypeChecker.cleanDescriptor(desc);
            if (TypeChecker.isClassType(internalClass)) {
                internalClass = internalClass.substring(1, internalClass.length() - 1);
            }
            checkClassAccess(internalClass, ctxNode);
        }
        return desc;
    }

    private String getFilePackage() {
        if (this.currentPackageName != null) {
            return this.currentPackageName;
        }
        String file = ctx.getCurrentFile();
        if (file != null && file.endsWith(".ocean")) {
            return file.substring(0, file.length() - 6);
        }
        return "default";
    }

    private boolean isKnownClass(String name) {
        if (importedClasses.containsKey(name)) return true;
        for (String fqName : CompilerRegistry.globalMethodRegistry.keySet()) {
            if (fqName.endsWith("/" + name) || fqName.endsWith("$" + name)) return true;
        }
        try {
            OceanTypeSystem.forName("java.lang." + name);
            return true;
        } catch (ClassNotFoundException ignored) {}

        try {
            OceanTypeSystem.forName("ocean.stdlib." + name);
            return true;
        } catch (ClassNotFoundException ignored) {}

        for (String wild : importedWildcards) {
            try {
                OceanTypeSystem.forName(wild.replace('/', '.') + "." + name);
                return true;
            } catch (ClassNotFoundException ignored) {}
        }

        return false;
    }

    public String resolveClassNameImpl(String name) {
        if (importedClasses.containsKey(name)) {
            String path = importedClasses.get(name).replace('.', '/');
            if (CompilerRegistry.globalMethodRegistry.containsKey(path) ||
                OceanRunnerV3.currentRunClasses.contains(path)) {
                return path;
            }
            try {
                OceanTypeSystem.forName(path.replace('/', '.'));
                return path;
            } catch (Throwable ignored) {}
            return path;
        }

        // Current package check
        String currentPkg = getFilePackage();
        String currentPkgPath = currentPkg != null && !currentPkg.equals("default") ? currentPkg.replace('.', '/') : "";
        String localClassPath = (currentPkgPath.isEmpty() ? "" : currentPkgPath + "/") + name;
        if (CompilerRegistry.globalMethodRegistry.containsKey(localClassPath) ||
            CompilerRegistry.globalIsInterfaceSet.contains(localClassPath) ||
            CompilerRegistry.globalSuperClassRegistry.containsKey(localClassPath) ||
            CompilerRegistry.globalClassAccess.containsKey(localClassPath)) {
            return localClassPath;
        }

        // Wildcard imports check
        for (String wild : importedWildcards) {
            String testFq = wild.replace('.', '/') + "/" + name;
            if (CompilerRegistry.globalMethodRegistry.containsKey(testFq) ||
                CompilerRegistry.globalIsInterfaceSet.contains(testFq) ||
                CompilerRegistry.globalSuperClassRegistry.containsKey(testFq) ||
                CompilerRegistry.globalClassAccess.containsKey(testFq) ||
                OceanRunnerV3.currentRunClasses.contains(testFq)) {
                return testFq;
            }
            try {
                OceanTypeSystem.forName(wild.replace('/', '.') + "." + name);
                return testFq;
            } catch (Throwable ignored) {
            }
        }

        // Auto-resolve ocean.stdlib
        try {
            OceanTypeSystem.forName("ocean.stdlib." + name);
            return "ocean/stdlib/" + name;
        } catch (Throwable ignored) {}

        // Deterministik fallback: tüm adayları topla, sırala, ilkini döndür
        List<String> candidates = new ArrayList<>();
        Set<String> allKnown = new HashSet<>();
        allKnown.addAll(CompilerRegistry.globalMethodRegistry.keySet());
        allKnown.addAll(CompilerRegistry.globalIsInterfaceSet);
        allKnown.addAll(CompilerRegistry.globalSuperClassRegistry.keySet());
        allKnown.addAll(CompilerRegistry.globalClassAccess.keySet());

        for (String fqName : allKnown) {
            if (fqName.endsWith("/" + name) || fqName.endsWith("$" + name)) candidates.add(fqName);
        }
        if (!candidates.isEmpty()) {
            if (candidates.size() > 1) {
                Collections.sort(candidates);
                String file = ctx.getCurrentFile();
                StringBuilder sb = new StringBuilder();
                sb.append("Sınıf referansı belirsiz '").append(name).append("'. Birden fazla aday bulundu: ");
                for (int i = 0; i < candidates.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(candidates.get(i));
                }
                sb.append(". Lütfen sınıfı açıkça import edin.");
                reportError(null, sb.toString());
            }
            return candidates.getFirst();
        }
        return "java/lang/" + name;
    }

    @Override
    public String resolveClassName(String name) {
        String resolved = resolveClassNameImpl(name);
        if (resolved != null) {
            recordDependency(resolved);
        }
        return resolved;
    }

    private Map<String, Boolean> getGlobalFieldStaticity(String classPath) {
        return CompilerRegistry.globalFieldStaticity.getOrDefault(classPath, Collections.emptyMap());
    }

    private Map<String, Boolean> getGlobalMethodStaticity(String classPath) {
        return CompilerRegistry.globalMethodStaticity.getOrDefault(classPath, Collections.emptyMap());
    }

    @Override
    public String visitMemberCallExpr(OceanParser.MemberCallExprContext ctxNode) {
        visitChildren(ctxNode);
        String memberName = ctxNode.anyId().getText();
        String ownerType = inferExprType(ctxNode.expression());

        // Null safety check: receiver must be non-null for direct dot member calls
        if (ctxNode.expression() != null && ownerType != null && !TypeChecker.isPrimitive(ownerType)) {
            if (!isExpressionNonNull(ctxNode.expression())) {
                reportError(ctxNode, "Nullable değer üzerinde doğrudan üye erişimi yapılamaz. '?.' veya null kontrolü kullanın.");
            }
        }

        // Allow .length on OceanList
        if (ownerType != null && TypeChecker.cleanDescriptor(ownerType).equals("Locean/stdlib/OceanList;") && memberName.equals("length") && ctxNode.LPAREN() == null) {
            return "I";
        }

        if (ownerType != null) {
            String cleanOwnerType = TypeChecker.cleanDescriptor(ownerType);
            if (TypeChecker.isClassType(cleanOwnerType)) {
                String internalOwner = cleanOwnerType.substring(1, cleanOwnerType.length() - 1);
                boolean isMethod = ctxNode.LPAREN() != null;
                checkAccess(internalOwner, memberName, isMethod, ctxNode);

                if (isMethod) {
                    List<String> argTypes = new ArrayList<>();
                    if (ctxNode.argumentList() != null) {
                        for (OceanParser.ExpressionContext expr : getArgumentExpressions(ctxNode.argumentList())) {
                            String argType = inferExprType(expr);
                            argTypes.add(argType != null ? argType : OceanTypeSystem.OBJECT_DESC);
                        }
                    }
                    checkExceptionForReflected(internalOwner, memberName, argTypes, ctxNode);
                }
            }
        }
        return null;
    }

    @Override
    public String visitSafeMemberCallExpr(OceanParser.SafeMemberCallExprContext ctxNode) {
        visitChildren(ctxNode);
        String memberName = ctxNode.anyId().getText();
        String ownerType = inferExprType(ctxNode.expression());
        if (ownerType != null) {
            String cleanOwnerType = TypeChecker.cleanDescriptor(ownerType);
            if (TypeChecker.isClassType(cleanOwnerType)) {
                String internalOwner = cleanOwnerType.substring(1, cleanOwnerType.length() - 1);
                boolean isMethod = ctxNode.LPAREN() != null;
                checkAccess(internalOwner, memberName, isMethod, ctxNode);

                if (isMethod) {
                    List<String> argTypes = new ArrayList<>();
                    if (ctxNode.argumentList() != null) {
                        for (OceanParser.ExpressionContext expr : getArgumentExpressions(ctxNode.argumentList())) {
                            String argType = inferExprType(expr);
                            argTypes.add(argType != null ? argType : OceanTypeSystem.OBJECT_DESC);
                        }
                    }
                    checkExceptionForReflected(internalOwner, memberName, argTypes, ctxNode);
                }
            }
        }
        return null;
    }

    @Override
    public String visitMethodCallExpr(OceanParser.MethodCallExprContext ctxNode) {
        visitChildren(ctxNode);
        String methodName = "unknown";
        if (ctxNode.expression() instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) ctxNode.expression()).primary();
            if (p instanceof OceanParser.IdPrimaryContext) {
                methodName = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                if (methodName.equals("OceanOutput") || methodName.equals("Output")) {
                    return null;
                }

                // If it is a SAM call on a local variable:
                TypeInfo varInfo = ctx.lookupVariable(methodName);
                if (varInfo != null) {
                    String varType = varInfo.getDescriptor();
                    String[] sam = resolveSAM(varType);
                    if (sam != null) {
                        String samName = sam[0];
                        String owner = TypeChecker.isClassType(varType)
                            ? varType.substring(1, varType.length() - 1)
                            : "java/lang/Object";
                        List<String> argTypes = new ArrayList<>();
                        if (ctxNode.argumentList() != null) {
                            for (OceanParser.ExpressionContext expr : getArgumentExpressions(ctxNode.argumentList())) {
                                String argType = inferExprType(expr);
                                argTypes.add(argType != null ? argType : OceanTypeSystem.OBJECT_DESC);
                            }
                        }
                        checkExceptionForReflected(owner, samName, argTypes, ctxNode);
                        return null;
                    }
                }
            } else if (p instanceof OceanParser.SuperRefPrimaryContext) {
                String owner = getCurrentSuperName();
                if (owner == null) owner = "java/lang/Object";
                List<String> argTypes = new ArrayList<>();
                if (ctxNode.argumentList() != null) {
                    for (OceanParser.ExpressionContext expr : getArgumentExpressions(ctxNode.argumentList())) {
                        String argType = inferExprType(expr);
                        argTypes.add(argType != null ? argType : OceanTypeSystem.OBJECT_DESC);
                    }
                }
                checkExceptionForReflected(owner, "<init>", argTypes, ctxNode);
                return null;
            } else if (p instanceof OceanParser.ThisRefPrimaryContext) {
                String owner = getCurrentClassName();
                if (TypeChecker.isClassType(owner)) {
                    owner = owner.substring(1, owner.length() - 1);
                }
                List<String> argTypes = new ArrayList<>();
                if (ctxNode.argumentList() != null) {
                    for (OceanParser.ExpressionContext expr : getArgumentExpressions(ctxNode.argumentList())) {
                        String argType = inferExprType(expr);
                        argTypes.add(argType != null ? argType : OceanTypeSystem.OBJECT_DESC);
                    }
                }
                checkExceptionForReflected(owner, "<init>", argTypes, ctxNode);
                return null;
            }
        }

        // Resolve target method owner (current class or static import)
        String owner = getCurrentClassName();
        if (TypeChecker.isClassType(owner)) {
            owner = owner.substring(1, owner.length() - 1);
        }
        boolean hasMethod = classHasMethod(owner, methodName);
        if (!hasMethod) {
            String staticOwner = resolveStaticImportMethodOwner(methodName);
            if (staticOwner != null) {
                owner = staticOwner;
            }
        } else {
            if (ctx.isInStaticContext() && !isMethodStatic(owner, methodName)) {
                reportError(ctxNode, "Statik bağlamdan static olmayan metot '" + methodName + "' çağrılamaz");
            }
        }

        List<String> argTypes = new ArrayList<>();
        if (ctxNode.argumentList() != null) {
            for (OceanParser.ExpressionContext expr : getArgumentExpressions(ctxNode.argumentList())) {
                String argType = inferExprType(expr);
                argTypes.add(argType != null ? argType : OceanTypeSystem.OBJECT_DESC);
            }
        }
        if (hasMethod) {
            String bestDesc = findBestOceanDescriptor(owner, methodName, argTypes);
            if (bestDesc == null) {
                Map<String, String> mReg = CompilerRegistry.globalMethodRegistry.get(owner);
                if (mReg != null && mReg.containsKey(methodName)) {
                    String singleDesc = mReg.get(methodName);
                    List<String> pTypes = parseParameterTypes(singleDesc);
                    if (pTypes.size() == argTypes.size()) {
                        for (int i = 0; i < argTypes.size(); i++) {
                            if (!TypeChecker.isAssignable(pTypes.get(i), argTypes.get(i), session)) {
                                reportError(ctxNode, "'" + TypeChecker.humanReadable(argTypes.get(i)) + "' tipi '"
                                        + TypeChecker.humanReadable(pTypes.get(i)) + "' parametre tipine atanamaz (metot: " + methodName + ")");
                                break;
                            }
                        }
                    }
                }
            }
        }
        checkExceptionForReflected(owner, methodName, argTypes, ctxNode);
        return null;
    }

    @Override
    public String visitArrayAccessExpr(OceanParser.ArrayAccessExprContext ctxNode) {
        visitChildren(ctxNode);
        if (ctxNode.expression(0) != null) {
            String arrayType = inferExprType(ctxNode.expression(0));
            if (arrayType != null) {
                String cleanType = TypeChecker.cleanDescriptor(arrayType);
                boolean isIndexable = arrayType.startsWith("[") ||
                        TypeChecker.isObjectType(cleanType) || cleanType.equals("java/lang/Object") ||
                OverloadResolver.resolve(cleanType, "get", Collections.singletonList("I")) != null ||
                    OverloadResolver.resolve(cleanType, "getItem", Collections.singletonList("I")) != null ||
                    OverloadResolver.resolve(cleanType, "at", Collections.singletonList("I")) != null;
                if (!isIndexable) {
                    reportError(ctxNode, "Dizi veya indeks erişimi '" + TypeChecker.humanReadable(arrayType) + "' tipi üzerinde desteklenmiyor. 'get(int)' metodu tanımlanmalıdır.");
                } else if (!isExpressionNonNull(ctxNode.expression(0))) {
                    reportError(ctxNode, "Nullable obje/dizi üzerinde doğrudan eleman erişimi yapılamaz.");
                }
            }
        }
        if (ctxNode.expression(1) != null) {
            String indexType = inferExprType(ctxNode.expression(1));
            if (indexType != null && !indexType.equals("I") && !indexType.equals("S") && !indexType.equals("B") && !indexType.equals("C")) {
                reportError(ctxNode, "Dizi indeksi int tipinde olmalıdır, '" + TypeChecker.humanReadable(indexType) + "' verildi.");
            }
        }
        return null;
    }

    @Override
    public String visitRangeSliceExpr(OceanParser.RangeSliceExprContext ctxNode) {
        visitChildren(ctxNode);
        if (ctxNode.expression(0) != null) {
            String targetType = inferExprType(ctxNode.expression(0));
            if (targetType != null) {
                String cleanType = TypeChecker.cleanDescriptor(targetType);
                boolean isSliceable = targetType.startsWith("[") ||
                        TypeChecker.isObjectType(cleanType) || cleanType.equals("java/lang/Object") ||
                    OverloadResolver.findSliceMethod(cleanType) != null;
                if (!isSliceable) {
                    reportError(ctxNode, "Dilimleme (slice) '" + TypeChecker.humanReadable(targetType) + "' tipi üzerinde desteklenmiyor. 'slice(int, int)', 'subList(int, int)' veya 'substring(int, int)' metodu tanımlanmalıdır.");
                }
            }
        }
        OceanParser.ExpressionContext startExpr = ctxNode.start != null ? ctxNode.start : (ctxNode.expression().size() > 1 ? ctxNode.expression(1) : null);
        if (startExpr != null) {
            String startType = inferExprType(startExpr);
            if (startType != null && !TypeChecker.isNumeric(startType)) {
                reportError(ctxNode, "Dilimleme başlangıç indeksi sayısal olmalıdır, '" + TypeChecker.humanReadable(startType) + "' verildi.");
            }
        }
        OceanParser.ExpressionContext endExpr = ctxNode.end != null ? ctxNode.end : (ctxNode.expression().size() > 2 ? ctxNode.expression(2) : null);
        if (endExpr != null) {
            String endType = inferExprType(endExpr);
            if (endType != null && !TypeChecker.isNumeric(endType)) {
                reportError(ctxNode, "Dilimleme bitiş indeksi sayısal olmalıdır, '" + TypeChecker.humanReadable(endType) + "' verildi.");
            }
        }
        return null;
    }

    private List<String> parseParameterTypes(String desc) {
        List<String> types = new ArrayList<>();
        if (desc == null || !desc.startsWith("(") || !desc.contains(")")) return types;
        String params = desc.substring(1, desc.lastIndexOf(')'));
        int i = 0;
        while (i < params.length()) {
            char c = params.charAt(i);
            if (c == 'L') {
                int end = params.indexOf(';', i);
                if (end < 0) break;
                types.add(params.substring(i, end + 1));
                i = end + 1;
            } else if (c == '[') {
                int start = i;
                while (i < params.length() && params.charAt(i) == '[') i++;
                if (i < params.length() && params.charAt(i) == 'L') {
                    int end = params.indexOf(';', i);
                    if (end < 0) break;
                    types.add(params.substring(start, end + 1));
                    i = end + 1;
                } else if (i < params.length()) {
                    types.add(params.substring(start, i + 1));
                    i++;
                }
            } else {
                types.add(String.valueOf(c));
                i++;
            }
        }
        return types;
    }

    private String findBestOceanDescriptor(String internalOwner, String methodName, List<String> argTypes) {
        return OverloadResolver.resolve(internalOwner, methodName, argTypes);
    }

    private void checkExceptionForReflected(String internalOwner, String methodName, List<String> argTypes, ParserRuleContext ctxNode) {
        if (internalOwner == null) return;
        internalOwner = TypeChecker.getInternalName(internalOwner);

        List<String> thrownExceptions = new ArrayList<>();
        boolean isOceanClass = CompilerRegistry.globalMethodRegistry.containsKey(internalOwner)
                || CompilerRegistry.globalOverloadRegistry.containsKey(internalOwner);

        if (isOceanClass) {
            // Ocean metodu: throws registry'e bak
            Map<String, List<String>> throwsMap = CompilerRegistry.globalMethodThrowsRegistry.get(internalOwner);
            if (throwsMap != null) {
                String bestDesc = findBestOceanDescriptor(internalOwner, methodName, argTypes);
                if (bestDesc != null) {
                    List<String> excs = throwsMap.get(methodName + bestDesc);
                    if (excs != null) thrownExceptions.addAll(excs);
                }
            }
        } else {
            // Yansıma (reflection) ile Java standart kütüphanesi metotları
            try {
                Class<?> clazz = OceanTypeSystem.forName(internalOwner.replace("/", "."));
                Class<?>[] exceptions = null;

                if (methodName.equals("<init>")) {
                    Constructor<?> bestCtor = null;
                    int bestScore = -1;
                    for (Constructor<?> c : clazz.getConstructors()) {
                        Class<?>[] paramTypes = c.getParameterTypes();
                        boolean isVarargs = c.isVarArgs();
                        if (isVarargs) {
                            int normalParamCount = paramTypes.length - 1;
                            if (argTypes.size() >= normalParamCount) {
                                int score = 5;
                                boolean compatible = true;
                                for (int i = 0; i < normalParamCount; i++) {
                                    String argDesc = argTypes.get(i);
                                    String paramDesc = Type.getDescriptor(paramTypes[i]);
                                    if (argDesc.equals(paramDesc)) score += 10;
                                    else if (TypeChecker.isAssignable(paramDesc, argDesc, session)) score += 1;
                                    else { compatible = false; break; }
                                }
                                if (compatible && score > bestScore) { bestScore = score; bestCtor = c; }
                            }
                        } else if (c.getParameterCount() == argTypes.size()) {
                            int score = 0;
                            boolean compatible = true;
                            for (int i = 0; i < argTypes.size(); i++) {
                                String argDesc = argTypes.get(i);
                                String paramDesc = Type.getDescriptor(paramTypes[i]);
                                if (argDesc.equals(paramDesc)) score += 10;
                                else if (TypeChecker.isAssignable(paramDesc, argDesc, session)) score += 1;
                                else { compatible = false; break; }
                            }
                            if (compatible && score > bestScore) { bestScore = score; bestCtor = c; }
                        }
                    }
                    if (bestCtor != null) exceptions = bestCtor.getExceptionTypes();
                } else {
                    Method bestMethod = null;
                    int bestScore = -1;
                    for (Method m : clazz.getMethods()) {
                        if (m.getName().equals(methodName)) {
                            Class<?>[] paramTypes = m.getParameterTypes();
                            boolean isVarargs = m.isVarArgs();
                            if (isVarargs) {
                                int normalParamCount = paramTypes.length - 1;
                                if (argTypes.size() >= normalParamCount) {
                                    int score = 5;
                                    boolean compatible = true;
                                    for (int i = 0; i < normalParamCount; i++) {
                                        String argDesc = argTypes.get(i);
                                        String paramDesc = Type.getDescriptor(paramTypes[i]);
                                        if (argDesc.equals(paramDesc)) score += 10;
                                        else if (TypeChecker.isAssignable(paramDesc, argDesc, session)) score += 1;
                                        else { compatible = false; break; }
                                    }
                                    if (compatible && score > bestScore) { bestScore = score; bestMethod = m; }
                                }
                            } else if (m.getParameterCount() == argTypes.size()) {
                                int score = 0;
                                boolean compatible = true;
                                for (int i = 0; i < argTypes.size(); i++) {
                                    String argDesc = argTypes.get(i);
                                    String paramDesc = Type.getDescriptor(paramTypes[i]);
                                    if (argDesc.equals(paramDesc)) score += 10;
                                    else if (TypeChecker.isAssignable(paramDesc, argDesc, session)) score += 1;
                                    else { compatible = false; break; }
                                }
                                if (compatible && score > bestScore) { bestScore = score; bestMethod = m; }
                            }
                        }
                    }
                    if (bestMethod != null) exceptions = bestMethod.getExceptionTypes();
                }

                if (exceptions != null) {
                    for (Class<?> exc : exceptions) {
                        thrownExceptions.add(Type.getDescriptor(exc));
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Tüm fırlatılan istisnalar için kontrol
        for (String excDesc : thrownExceptions) {
            // Hem "Ljava/io/IOException;" hem de "java/io/IOException" formatı gelebilir
            String cleanExc = TypeChecker.isClassType(excDesc)
                    ? excDesc.substring(1, excDesc.length() - 1) : excDesc;
            if (isCheckedException(cleanExc)) {
                if (!isExceptionHandled(cleanExc)) {
                    reportError(ctxNode, "Yakalanmamış checked exception: '" + TypeChecker.humanReadable(excDesc) + "'. "
                            + "Bu istisnayı ya yakalayın (trying/catch) ya da metot imzasına throws ekleyin.");
                }
            }
        }
    }

    private String currentCastType = null;

    private boolean isCastCompatible(String targetType, String sourceType) {
        if (targetType == null || sourceType == null) return true;

        String cleanTarget = TypeChecker.cleanDescriptor(targetType);
        String cleanSource = TypeChecker.cleanDescriptor(sourceType);

        if (TypeChecker.isObjectType(cleanTarget) || TypeChecker.isObjectType(cleanSource)) return true;

        if (cleanSource.equals("null")) return !TypeChecker.isPrimitive(cleanTarget);

        if (cleanTarget.equals("null")) return !TypeChecker.isPrimitive(cleanSource);

        boolean isTargetPrimitive = TypeChecker.isPrimitive(cleanTarget);
        boolean isSourcePrimitive = TypeChecker.isPrimitive(cleanSource);

        if (isTargetPrimitive && isSourcePrimitive) {
            boolean isTargetNumeric = TypeChecker.isNumericPrimitive(cleanTarget);
            boolean isSourceNumeric = TypeChecker.isNumericPrimitive(cleanSource);
            if (isTargetNumeric && isSourceNumeric) {
                return true;
            }
            return cleanTarget.equals("Z") && cleanSource.equals("Z");
        }

        if (isTargetPrimitive) {
            if (OceanTypeSystem.NUMBER_DESC.equals(cleanSource)) {
                return true;
            }
            String unboxed = TypeChecker.unbox(cleanSource);
            if (unboxed != null) {
                return isCastCompatible(cleanTarget, unboxed);
            }
            return false;
        }
        if (isSourcePrimitive) {
            String boxed = TypeChecker.box(cleanSource);
            if (boxed != null) {
                return isCastCompatible(cleanTarget, boxed);
            }
            return false;
        }

        boolean isTargetArray = cleanTarget.startsWith("[");
        boolean isSourceArray = cleanSource.startsWith("[");
        if (isTargetArray || isSourceArray) {
            if (isTargetArray && isSourceArray) {
                return isCastCompatible(cleanTarget.substring(1), cleanSource.substring(1));
            }
            return false;
        }

        String targetPath = TypeChecker.isClassType(cleanTarget)
                ? cleanTarget.substring(1, cleanTarget.length() - 1) : cleanTarget;
        String sourcePath = TypeChecker.isClassType(cleanSource)
                ? cleanSource.substring(1, cleanSource.length() - 1) : cleanSource;

        if (session.isSubType(OceanTypeSystem.wrapObjectType(sourcePath), OceanTypeSystem.wrapObjectType(targetPath)) ||
            session.isSubType(OceanTypeSystem.wrapObjectType(targetPath), OceanTypeSystem.wrapObjectType(sourcePath))) {
            return true;
        }

        boolean isTargetInterface = isInterface(targetPath);
        boolean isSourceInterface = isInterface(sourcePath);

        if (!isTargetInterface && !isSourceInterface) {
            return false;
        }

        if (isTargetInterface && !isSourceInterface) {
            if (isFinalClass(sourcePath)) {
                return session.isSubType(OceanTypeSystem.wrapObjectType(sourcePath), OceanTypeSystem.wrapObjectType(targetPath));
            }
        }
        if (isSourceInterface && !isTargetInterface) {
            if (isFinalClass(targetPath)) {
                return session.isSubType(OceanTypeSystem.wrapObjectType(targetPath), OceanTypeSystem.wrapObjectType(sourcePath));
            }
        }

        return true;
    }

    private boolean isInterface(String path) {
        if (path == null) return false;
        if (path.contains("<")) {
            path = path.substring(0, path.indexOf('<'));
        }
        if (CompilerRegistry.globalIsInterfaceSet.contains(path)) {
            return true;
        }
        try {
            Class<?> clazz = OceanTypeSystem.forName(path.replace('/', '.'));
            return clazz.isInterface();
        } catch (ClassNotFoundException | SecurityException ignored) {}
        return false;
    }

    private boolean isFinalClass(String path) {
        if (CompilerRegistry.globalClassAccess.containsKey(path)) {
            int access = CompilerRegistry.globalClassAccess.get(path);
            return (access & Opcodes.ACC_FINAL) != 0;
        }
        try {
            Class<?> clazz = OceanTypeSystem.forName(path.replace('/', '.'));
            return Modifier.isFinal(clazz.getModifiers());
        } catch (ClassNotFoundException | SecurityException ignored) {}
        return false;
    }

    @Override
    public String visitCastExpr(OceanParser.CastExprContext ctxNode) {
        String targetType = resolveTypeAndCheckAccess(ctxNode.type().getText(), ctxNode);
        String oldCast = currentCastType;
        currentCastType = targetType;
        visit(ctxNode.expression());
        currentCastType = oldCast;

        String sourceType = inferExprType(ctxNode.expression());
        if (sourceType != null && !isCastCompatible(targetType, sourceType)) {
            reportError(ctxNode, "Uyumsuz tipler: '" + TypeChecker.humanReadable(sourceType)
                    + "' tipi '" + TypeChecker.humanReadable(targetType) + "' tipine dönüştürülemez.");
        }

        return targetType;
    }

    private boolean isInsideAsyncMethod() {
        if (ctx.getCurrentClass() == null || ctx.getCurrentMethod() == null) {
            return false;
        }
        String classKey = getFilePackage() + "/" + ctx.getCurrentClass();
        return CompilerRegistry.globalAsyncMethodSet.contains(classKey + "#" + ctx.getCurrentMethod());
    }

    @Override
    public String visitAwaitExpr(OceanParser.AwaitExprContext ctxNode) {
        if (!isInsideAsyncMethod()) {
            reportError(ctxNode, "'await' ifadesi yalnızca 'async' olarak tanımlanmış fonksiyon gövdelerinde kullanılabilir.");
            return OceanTypeSystem.OBJECT_DESC;
        }
        String futureDesc = visit(ctxNode.expression());
        if (futureDesc == null) {
            return OceanTypeSystem.OBJECT_DESC;
        }
        String cleanPath = TypeChecker.cleanDescriptor(futureDesc);
        if (TypeChecker.isClassType(cleanPath)) {
            cleanPath = cleanPath.substring(1, cleanPath.length() - 1);
        }
        if (!cleanPath.equals("java/util/concurrent/CompletableFuture")) {
            reportError(ctxNode, "'await' ifadesinin sağ tarafı bir CompletableFuture olmalıdır. Bulunan: " + TypeChecker.humanReadable(futureDesc));
            return OceanTypeSystem.OBJECT_DESC;
        }

        if (futureDesc.contains("<") && futureDesc.contains(">")) {
            int start = futureDesc.indexOf('<') + 1;
            int end = futureDesc.lastIndexOf('>');
            if (start < end) {
                return futureDesc.substring(start, end).trim();
            }
        }
        return OceanTypeSystem.OBJECT_DESC;
    }

    @Override
    public String visitNewObjectExpr(OceanParser.NewObjectExprContext ctxNode) {
        if (ctxNode.type() != null && ctxNode.expression() != null && !ctxNode.expression().isEmpty()) {
            String elemTypeName = ctxNode.type().getText();
            if (this.symbolTable.getTypeParams() != null && this.symbolTable.getTypeParams().contains(elemTypeName)) {
                reportError(ctxNode, "Jenerik tip parametresi '" + elemTypeName + "' türünde dizi oluşturulamaz (Generic array creation)");
            }
        }
        // Size expressions (if array) or constructor arguments are visited in the enclosing context
        if (ctxNode.argumentList() != null) {
            visit(ctxNode.argumentList());
        }
        for (OceanParser.ExpressionContext expr : ctxNode.expression()) {
            visit(expr);
        }

        // Eğer isimsiz sınıf (anonymous class) gövdesi varsa
        if (ctxNode.LBRACE() != null) {
            String enclosing = ctx.getCurrentClass();
            String anonClassName = (enclosing != null ? enclosing : "GlobalClass") + "$Anon$" + (anonClassCounter++);
            String fullPath = getFilePackage() + "/" + anonClassName;

            Map<String, String> fields = new HashMap<>(
                    CompilerRegistry.globalFieldRegistry.getOrDefault(fullPath, Collections.emptyMap()));
            Map<String, Boolean> fieldStatic = new HashMap<>();
            Map<String, String> methods = new HashMap<>(
                    CompilerRegistry.globalMethodRegistry.getOrDefault(fullPath, Collections.emptyMap()));
            Map<String, Boolean> methodStatic = new HashMap<>();

            ctx.enterClass(anonClassName, fields, fieldStatic, methods, methodStatic);

            if (ctxNode.memberDeclaration() != null) {
                for (OceanParser.MemberDeclarationContext m : ctxNode.memberDeclaration()) {
                    visit(m);
                }
            }
            if (ctxNode.statement() != null) {
                for (OceanParser.StatementContext s : ctxNode.statement()) {
                    visit(s);
                }
            }

            ctx.exitClass();
            return null;
        }

        // Eğer dizi oluşturmaysa, her bir boyut ifadesinin integer olduğunu kontrol et
        if (!ctxNode.expression().isEmpty()) {
            for (OceanParser.ExpressionContext sizeExpr : ctxNode.expression()) {
                String sizeType = inferExprType(sizeExpr);
                if (sizeType != null && !sizeType.equals("I") && !sizeType.equals("S") && !sizeType.equals("B") && !sizeType.equals("C")) {
                    reportError(ctxNode, "Dizi boyutu int tipinde olmalıdır, '" + TypeChecker.humanReadable(sizeType) + "' verildi.");
                }
            }
            return null;
        }

        String typeName = ctxNode.type().getText();
        String desc = resolveTypeAndCheckAccess(typeName, ctxNode);
        if (desc == null || !desc.startsWith("L") || !desc.endsWith(";")) {
            return null;
        }

        String classPath = desc.substring(1, desc.length() - 1);
        checkAccess(classPath, "<init>", true, ctxNode);
        boolean isInterface = false;
        boolean isAbstract = false;

        // Önce derlenen sınıfları/arayüzleri kontrol et
        if (CompilerRegistry.globalIsInterfaceSet.contains(classPath)) {
            isInterface = true;
        } else if (CompilerRegistry.globalClassAccess.containsKey(classPath)) {
            int accessFlags = CompilerRegistry.globalClassAccess.get(classPath);
            isAbstract = (accessFlags & Opcodes.ACC_ABSTRACT) != 0;
            isInterface = (accessFlags & Opcodes.ACC_INTERFACE) != 0;
        } else {
            // JDK sınıflarını kontrol et
            try {
                Class<?> clazz = OceanTypeSystem.forName(classPath.replace('/', '.'));
                isInterface = clazz.isInterface();
                isAbstract = Modifier.isAbstract(clazz.getModifiers());
            } catch (ClassNotFoundException | SecurityException ignored) {}
        }

        if (isInterface) {
            reportError(ctxNode, "Arayüz olan '" + typeName + "' doğrudan instantiate edilemez.");
        } else if (isAbstract) {
            reportError(ctxNode, "Soyut sınıf olan '" + typeName + "' doğrudan instantiate edilemez.");
        }

        List<String> argTypes = new ArrayList<>();
        if (ctxNode.argumentList() != null) {
            for (OceanParser.ExpressionContext expr : getArgumentExpressions(ctxNode.argumentList())) {
                String argType = inferExprType(expr);
                argTypes.add(argType != null ? argType : OceanTypeSystem.OBJECT_DESC);
            }
        }
        checkExceptionForReflected(classPath, "<init>", argTypes, ctxNode);
        return null;
    }

    @Override
    public String visitLambdaExpr(OceanParser.LambdaExprContext ctxNode) {
        String targetType = currentCastType;
        if (targetType == null) {
            int paramCount = 0;
            if (ctxNode.parameterList() != null) {
                paramCount = ctxNode.parameterList().parameter().size();
            } else if (ctxNode.identifierList() != null) {
                paramCount = ctxNode.identifierList().anyId().size();
            }
            boolean hasReturnValue = false;
            if (ctxNode.expression() != null) {
                hasReturnValue = true;
            } else if (ctxNode.block() != null) {
                hasReturnValue = blockHasReturn(ctxNode.block());
            }
            targetType = OceanTypeSystem.findFuncInterface(paramCount, hasReturnValue);
        }

        String[] sam = resolveSAM(targetType);
        String samDesc = (sam != null) ? sam[1] : "()V";

        List<String> samParamTypes = getParameterDescriptors(samDesc);
        List<String> paramNames = new ArrayList<>();
        List<String> paramTypes = new ArrayList<>();

        if (ctxNode.parameterList() != null) {
            for (int i = 0; i < ctxNode.parameterList().parameter().size(); i++) {
                OceanParser.ParameterContext p = ctxNode.parameterList().parameter(i);
                paramNames.add(p.anyId().getText());
                if (p.type() != null) {
                    paramTypes.add(resolveTypeAndCheckAccess(p.type().getText(), p));
                } else if (i < samParamTypes.size()) {
                    paramTypes.add(samParamTypes.get(i));
                } else {
                    paramTypes.add(OceanTypeSystem.OBJECT_DESC);
                }
            }
        } else if (ctxNode.identifierList() != null) {
            for (int i = 0; i < ctxNode.identifierList().anyId().size(); i++) {
                paramNames.add(ctxNode.identifierList().anyId(i).getText());
                if (i < samParamTypes.size()) {
                    paramTypes.add(samParamTypes.get(i));
                } else {
                    paramTypes.add(OceanTypeSystem.OBJECT_DESC);
                }
            }
        }

        String samReturn = samDesc.substring(samDesc.lastIndexOf(')') + 1);

        ctx.enterMethod("lambda", false, samReturn);

        for (int i = 0; i < paramNames.size(); i++) {
            ctx.declareVariable(paramNames.get(i), paramTypes.get(i), false, true);
        }

        if (ctxNode.block() != null) {
            visitBlock(ctxNode.block());
        } else if (ctxNode.expression() != null) {
            visit(ctxNode.expression());
        }

        ctx.exitMethod();
        return targetType;
    }

    @Override
    public String visitMethodRefExpr(OceanParser.MethodRefExprContext ctxNode) {
        String targetType = currentCastType;
        if (targetType == null) {
            if (ctxNode.expression() instanceof OceanParser.CastExprContext) {
                targetType = resolveTypeAndCheckAccess(((OceanParser.CastExprContext) ctxNode.expression()).type().getText(), ctxNode);
            }
        }
        if (targetType == null) {
            ParseTree parent = ctxNode.getParent();
            while ((parent instanceof OceanParser.ParenthesizedPrimaryContext
                    || parent instanceof OceanParser.ExpressionContext)) {
                if (parent instanceof OceanParser.CastExprContext) {
                    targetType = resolveTypeAndCheckAccess(((OceanParser.CastExprContext) parent).type().getText(), ctxNode);
                    break;
                }
                parent = parent.getParent();
            }
        }

        String receiverType = null;
        if (ctxNode.expression() != null) {
            visit(ctxNode.expression());
            receiverType = inferExprType(ctxNode.expression());
        }
        String owner = (receiverType != null && receiverType.startsWith("L")) ? receiverType.substring(1, receiverType.length() - 1) : "java/lang/Object";
        String methodName = (ctxNode.anyId() != null) ? ctxNode.anyId().getText() : "new";

        if (targetType == null || resolveSAM(targetType) == null) {
            int paramCount = 0;
            boolean hasReturnValue = false;
            String methodDesc = null;
            if (!methodName.equals("new") && !methodName.equals("<init>")) {
                Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(owner);
                if (methods != null && methods.containsKey(methodName)) {
                    methodDesc = methods.get(methodName);
                } else {
                    Map<String, List<String>> overloads = CompilerRegistry.globalOverloadRegistry.get(owner);
                    if (overloads != null && overloads.containsKey(methodName) && !overloads.get(methodName).isEmpty()) {
                        methodDesc = overloads.get(methodName).getFirst();
                    }
                }
                if (methodDesc == null) {
                    try {
                        Class<?> clazz = OceanTypeSystem.forName(owner.replace("/", "."));
                        for (Method m : clazz.getMethods()) {
                            if (m.getName().equals(methodName)) {
                                methodDesc = Type.getMethodDescriptor(m);
                                break;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (methodName.equals("new") || methodName.equals("<init>")) {
                hasReturnValue = true;
            } else if (methodDesc != null) {
                List<String> params = getParameterDescriptors(methodDesc);
                paramCount = params.size();
                String ret = methodDesc.substring(methodDesc.lastIndexOf(')') + 1);
                hasReturnValue = !ret.equals("V");
            }
            targetType = OceanTypeSystem.findFuncInterface(paramCount, hasReturnValue);
        }

        return targetType;
    }

    private String[] resolveSAM(String descriptor) {
        if (descriptor == null || !descriptor.startsWith("L")) return null;
        String internalName = descriptor.substring(1, descriptor.length() - 1);

        OceanTypeSystem.SAMMethodInfo samInfo = OceanTypeSystem.getSingleAbstractMethodInfo(internalName);
        if (samInfo != null) {
            return new String[]{samInfo.name(), samInfo.descriptor()};
        }

        String resolvedInternal = internalName;
        Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(resolvedInternal);

        if (methods == null) {
            String simpleName = OceanTypeSystem.findSimpleName(resolvedInternal);
            for (Map.Entry<String, Map<String, String>> entry : CompilerRegistry.globalMethodRegistry.entrySet()) {
                String fqName = entry.getKey();
                if (fqName.endsWith("/" + simpleName) || fqName.endsWith("$" + simpleName)) {
                    resolvedInternal = fqName;
                    methods = entry.getValue();
                    break;
                }
            }
        }

        if (methods != null) {
            boolean isInterface = ClassMetadataCache.isInterface(resolvedInternal);

            if (isInterface) {
                String samName = null;
                String samDesc = null;
                int abstractCount = 0;
                Map<String, Integer> accessMap = CompilerRegistry.globalMethodAccess.get(resolvedInternal);
                Map<String, Boolean> staticMap = CompilerRegistry.globalMethodStaticity.get(resolvedInternal);

                for (Map.Entry<String, String> entry : methods.entrySet()) {
                    String name = entry.getKey();
                    if (name.equals("<init>") || name.equals("<clinit>") || name.startsWith("lambda$")) continue;

                    boolean isStatic = staticMap != null && Boolean.TRUE.equals(staticMap.get(name));
                    if (isStatic) continue;

                    int acc = accessMap != null ? accessMap.getOrDefault(name, 0) : 0;
                    if (acc != 0 && (acc & Opcodes.ACC_ABSTRACT) == 0) {
                        // Concrete default method - not abstract!
                        continue;
                    }

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

        try {
            Class<?> cls = OceanTypeSystem.forName(internalName.replace("/", "."));
            Method sam = null;
            for (Method m : cls.getMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) {
                    if (TypeChecker.isObjectMethod(m)) continue;
                    if (sam != null) return null;
                    sam = m;
                }
            }
            if (sam == null) return null;
            return new String[] { sam.getName(), Type.getMethodDescriptor(sam) };
        } catch (Exception e) {
            return null;
        }
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
                        if (sc.statement() != null && !sc.statement().isEmpty()) {
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
                    if (dc.statement() != null && !dc.statement().isEmpty()) {
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

    // ========== JVM Hata Önleme Yardımcı Metotları ==========

    private record MethodSignature(String name, String descriptor) {
        @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof MethodSignature(String name1, String descriptor1))) return false;
                return name.equals(name1) && descriptor.equals(descriptor1);
            }
    }

    private void checkAbstractMethods(String classPath, String className, ParserRuleContext ctxNode) {
        if (CompilerRegistry.globalIsInterfaceSet.contains(classPath)) return;

        Set<MethodSignature> abstractMethods = new HashSet<>();
        Set<MethodSignature> concreteMethods = new HashSet<>();

        // Mevcut sınıfın kendi metotlarını concreteMethods'a ekle
        Map<String, String> localMethods = CompilerRegistry.globalMethodRegistry.getOrDefault(classPath, Collections.emptyMap());
        Map<String, Integer> localAccess = CompilerRegistry.globalMethodAccess.getOrDefault(classPath, Collections.emptyMap());
        for (Map.Entry<String, String> entry : localMethods.entrySet()) {
            String mName = entry.getKey();
            int access = localAccess.getOrDefault(mName, 0);
            if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                reportError(ctxNode, "Sınıf '" + className + "' soyut (abstract) değildir ve '" + mName + entry.getValue() + "' soyut metodunu barındıramaz.");
            } else if (!mName.equals("<init>") && !mName.equals("<clinit>")) {
                concreteMethods.add(new MethodSignature(mName, entry.getValue()));
            }
        }

        // Hiyerarşiden soyut ve somut metotları topla
        collectMethodsHierarchy(classPath, abstractMethods, concreteMethods);

        // Somut sınıfta gerçekleştirilmemiş soyut metot var mı?
        for (MethodSignature sig : abstractMethods) {
            boolean implemented = false;
            for (MethodSignature concrete : concreteMethods) {
                if (concrete.name.equals(sig.name)) {
                    List<String> concreteParams = getParameterDescriptors(concrete.descriptor);
                    List<String> sigParams = getParameterDescriptors(sig.descriptor);
                    if (concreteParams.size() == sigParams.size()) {
                        boolean paramsCompatible = true;
                        for (int i = 0; i < concreteParams.size(); i++) {
                            String cp = concreteParams.get(i);
                            String sp = sigParams.get(i);
                            if (!cp.equals(sp) && !TypeChecker.isObjectType(cp) && !TypeChecker.isObjectType(sp) && !TypeChecker.isAssignable(sp, cp, session)) {
                                paramsCompatible = false;
                                break;
                            }
                        }
                        if (paramsCompatible) {
                            implemented = true;
                            break;
                        }
                    }
                }
            }
            if (!implemented) {
                reportError(ctxNode, "Sınıf '" + className + "' soyut (abstract) değildir ve '"
                        + sig.name + sig.descriptor + "' metodunu gerçekleştirmelidir.");
            }
        }
    }

    private void collectMethodsHierarchy(String classPath, Set<MethodSignature> abstractMethods, Set<MethodSignature> concreteMethods) {
        collectMethodsHierarchy(classPath, abstractMethods, concreteMethods, new HashSet<>());
    }

    private void collectMethodsHierarchy(String classPath, Set<MethodSignature> abstractMethods, Set<MethodSignature> concreteMethods, Set<String> visited) {
        if (!visited.add(classPath)) {
            return;
        }
        // Üst sınıfı kontrol et
        String parent = CompilerRegistry.globalSuperClassRegistry.get(classPath);
        if (parent != null) {
            collectClassMethods(parent, abstractMethods, concreteMethods);
            collectMethodsHierarchy(parent, abstractMethods, concreteMethods, visited);
        } else if (!classPath.equals("java/lang/Object")) {
            collectClassMethods("java/lang/Object", abstractMethods, concreteMethods);
        }

        // Arayüzleri kontrol et
        String[] interfaces = CompilerRegistry.globalInterfaceRegistry.get(classPath);
        if (interfaces != null) {
            for (String inter : interfaces) {
                collectClassMethods(inter, abstractMethods, concreteMethods);
                collectMethodsHierarchy(inter, abstractMethods, concreteMethods, visited);
            }
        }
    }

    private void collectClassMethods(String classPath, Set<MethodSignature> abstractMethods, Set<MethodSignature> concreteMethods) {
        if (classPath == null) return;
        if (CompilerRegistry.globalMethodRegistry.containsKey(classPath)) {
            Map<String, String> methods = CompilerRegistry.globalMethodRegistry.getOrDefault(classPath, Collections.emptyMap());
            Map<String, Integer> accessMap = CompilerRegistry.globalMethodAccess.getOrDefault(classPath, Collections.emptyMap());
            for (Map.Entry<String, String> entry : methods.entrySet()) {
                String mName = entry.getKey();
                String desc = entry.getValue();
                if (mName.equals("<init>") || mName.equals("<clinit>")) continue;
                int access = accessMap.getOrDefault(mName, 0);
                MethodSignature sig = new MethodSignature(mName, desc);
                if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                    abstractMethods.add(sig);
                } else {
                    concreteMethods.add(sig);
                }
            }
        } else {
            try {
                Class<?> clazz = OceanTypeSystem.forName(classPath.replace('/', '.'));
                for (Method m : clazz.getMethods()) {
                    String desc = Type.getMethodDescriptor(m);
                    MethodSignature sig = new MethodSignature(m.getName(), desc);
                    if (Modifier.isAbstract(m.getModifiers())) {
                        abstractMethods.add(sig);
                    } else {
                        concreteMethods.add(sig);
                    }
                }
            } catch (ClassNotFoundException | SecurityException ignored) {}
        }
    }

    private void checkOverrideCompatibility(String className, String methodName, String paramDesc, String returnType, ParserRuleContext ctxNode) {
        if (className == null || methodName == null || paramDesc == null || returnType == null) return;
        String classPath = getFilePackage() + "/" + className;

        // Üst sınıflarda ezilen metot var mı?
        String parent = CompilerRegistry.globalSuperClassRegistry.get(classPath);
        Set<String> visited = new HashSet<>();
        while (parent != null && visited.add(parent)) {
            checkMethodOverride(parent, methodName, paramDesc, returnType, ctxNode);
            parent = CompilerRegistry.globalSuperClassRegistry.get(parent);
        }

        // Arayüzlerde ezilen metot var mı? (Transitif/Recursive)
        String[] interfaces = CompilerRegistry.globalInterfaceRegistry.get(classPath);
        if (interfaces != null) {
            Set<String> visitedInterfaces = new HashSet<>();
            for (String inter : interfaces) {
                checkInterfaceOverridesRecursive(inter, methodName, paramDesc, returnType, ctxNode, visitedInterfaces);
            }
        }
    }

    private void checkInterfaceOverridesRecursive(String interfacePath, String methodName, String paramDesc, String returnType, ParserRuleContext ctxNode, Set<String> visited) {
        if (interfacePath == null || !visited.add(interfacePath)) return;

        // Check this interface
        checkMethodOverride(interfacePath, methodName, paramDesc, returnType, ctxNode);

        // Check its parent interfaces
        String[] parentInterfaces = CompilerRegistry.globalInterfaceRegistry.get(interfacePath);
        if (parentInterfaces != null) {
            for (String parentInter : parentInterfaces) {
                checkInterfaceOverridesRecursive(parentInter, methodName, paramDesc, returnType, ctxNode, visited);
            }
        }
    }

    private void checkMethodOverride(String parentPath, String methodName, String paramDesc, String returnType, ParserRuleContext ctxNode) {
        if (!CompilerRegistry.globalMethodRegistry.containsKey(parentPath)) {
            try {
                Class<?> clazz = OceanTypeSystem.forName(parentPath.replace('/', '.'));
                for (Method m : clazz.getMethods()) {
                    if (m.getName().equals(methodName)) {
                        String parentDesc = Type.getMethodDescriptor(m);
                        String parentParams = parentDesc.substring(0, parentDesc.indexOf(')') + 1);
                        if (parentParams.equals(paramDesc)) {
                            if (Modifier.isFinal(m.getModifiers())) {
                                reportError(ctxNode, "Cannot override final method '" + methodName + "' from class '" + parentPath + "'.");
                            }
                            String parentReturn = parentDesc.substring(parentDesc.indexOf(')') + 1);
                            if (!isOverrideCompatible(parentReturn, returnType)) {
                                reportError(ctxNode, "Metot '" + methodName + "' ezilirken dönüş tipi '"
                                        + TypeChecker.humanReadable(returnType) + "', üst sınıftaki '"
                                        + TypeChecker.humanReadable(parentReturn) + "' ile uyumsuz.");
                            }
                        }
                    }
                }
            } catch (ClassNotFoundException | SecurityException ignored) {}
        } else {
            Map<String, List<String>> overloads = CompilerRegistry.globalOverloadRegistry.get(parentPath);
            if (overloads != null && overloads.containsKey(methodName)) {
                for (String parentDesc : overloads.get(methodName)) {
                    if (areParamsCompatible(parentDesc, paramDesc)) {
                        Map<String, Integer> parentAccessMap = CompilerRegistry.globalMethodAccess.get(parentPath);
                        if (parentAccessMap != null) {
                            int parentAccess = parentAccessMap.getOrDefault(methodName + parentDesc, parentAccessMap.getOrDefault(methodName, 0));
                            if ((parentAccess & Opcodes.ACC_FINAL) != 0) {
                                reportError(ctxNode, "Cannot override final method '" + methodName + "' from class '" + parentPath + "'.");
                            }
                        }
                        String parentReturn = parentDesc.substring(parentDesc.indexOf(')') + 1);
                        if (!isOverrideCompatible(parentReturn, returnType)) {
                            reportError(ctxNode, "Metot '" + methodName + "' ezilirken dönüş tipi '"
                                    + TypeChecker.humanReadable(returnType) + "', üst sınıftaki '"
                                    + TypeChecker.humanReadable(parentReturn) + "' ile uyumsuz.");
                        }
                    }
                }
            }
        }
    }

    private boolean isOverrideCompatible(String parentReturn, String childReturn) {
        if (parentReturn.equals(childReturn)) return true;
        if (parentReturn.equals("Ljava/util/concurrent/CompletableFuture;") || childReturn.equals("Ljava/util/concurrent/CompletableFuture;")) return true;
        if (TypeChecker.isPrimitive(parentReturn) || TypeChecker.isPrimitive(childReturn)) return false;
        if (TypeChecker.isObjectType(parentReturn)) return true;
        if (TypeChecker.isObjectType(childReturn)) return false;
        if (session != null) return session.isSubType(childReturn, parentReturn);
        return false;
    }

    // ========== Static Import & Switch Expression Helpers ==========

    private String resolveStaticImportField(String name) {
        if (importedStaticMembers.containsKey(name)) {
            String owner = importedStaticMembers.get(name);
            String type = getStaticFieldType(owner, name);
            if (type != null) return type;
        }
        List<String> matchingTypes = new ArrayList<>();
        List<String> matchingOwners = new ArrayList<>();
        for (String owner : importedStaticWildcards) {
            String type = getStaticFieldType(owner, name);
            if (type != null) {
                matchingTypes.add(type);
                matchingOwners.add(owner);
            }
        }
        if (matchingOwners.size() > 1) {
            reportError(null, "Ambiguous static field reference '" + name + "'. Matches multiple static wildcard imports: " + matchingOwners);
            return matchingTypes.getFirst();
        }
        return matchingTypes.isEmpty() ? null : matchingTypes.getFirst();
    }

    private String getStaticFieldType(String owner, String name) {
        Map<String, String> fields = CompilerRegistry.globalFieldRegistry.get(owner);
        if (fields != null && fields.containsKey(name)) {
            Map<String, Boolean> staticity = CompilerRegistry.globalFieldStaticity.get(owner);
            if (staticity != null && staticity.getOrDefault(name, false)) {
                return fields.get(name);
            }
        }
        try {
            Class<?> clazz = OceanTypeSystem.forName(owner.replace("/", "."));
            Field f = findReflectedField(clazz, name);
            if (f != null && Modifier.isStatic(f.getModifiers())) {
                return Type.getDescriptor(f.getType());
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static final Map<String, Field> reflectedFieldCache = new ConcurrentHashMap<>();

    private static Field findReflectedField(Class<?> clazz, String fieldName) {
        if (clazz == null || fieldName == null) return null;
        String key = clazz.getName() + "#" + fieldName;
        Field cached = reflectedFieldCache.get(key);
        if (cached != null) return cached;

        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field f = current.getDeclaredField(fieldName);
                reflectedFieldCache.put(key, f);
                return f;
            } catch (NoSuchFieldException ignored) {}
            current = current.getSuperclass();
        }
        try {
            Field f = clazz.getField(fieldName);
            reflectedFieldCache.put(key, f);
            return f;
        } catch (NoSuchFieldException ignored) {}
        return null;
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
            reportError(null, "Ambiguous static method reference '" + methodName + "'. Matches multiple static wildcard imports: " + candidates);
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
        try {
            Class<?> clazz = OceanTypeSystem.forName(owner.replace("/", "."));
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(methodName) && Modifier.isStatic(m.getModifiers())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    public String visitResultStmt(OceanParser.ResultStmtContext ctxNode) {
        if (switchExpressionDepth <= 0) {
            reportError(ctxNode, "result ifadesi sadece switch expression bloğu içinde kullanılabilir");
            return null;
        }
        String type = inferExprType(ctxNode.expression());
        if (type != null && !switchExpressionTypesStack.isEmpty()) {
            switchExpressionTypesStack.peek().add(type);
        }
        return null;
    }

    @Override
    public String visitSwitchExpr(OceanParser.SwitchExprContext ctxNode) {
        visit(ctxNode.expression()); // Condition

        switchExpressionDepth++;
        List<String> branchTypes = new ArrayList<>();
        switchExpressionTypesStack.push(branchTypes);

        Map<String, Boolean> stateBefore = ctx.getVariableInitializationState();
        List<Map<String, Boolean>> branchStates = new ArrayList<>();

        if (ctxNode.switchExpressionCase() != null) {
            for (OceanParser.SwitchExpressionCaseContext caseCtx : ctxNode.switchExpressionCase()) {
                ctx.setVariableInitializationState(stateBefore);
                if (caseCtx.block() != null) {
                    visitBlock(caseCtx.block());
                    if (!blockHasResult(caseCtx.block())) {
                        reportError(caseCtx, "Switch expression bloğu içindeki tüm yollar bir result değeri döndürmelidir");
                    }
                } else if (caseCtx.expression() != null) {
                    visit(caseCtx.expression());
                    String type = inferExprType(caseCtx.expression());
                    if (type != null) {
                        branchTypes.add(type);
                    }
                }
                branchStates.add(ctx.getVariableInitializationState());
            }
        }

        if (ctxNode.defaultExpressionCase() != null) {
            OceanParser.DefaultExpressionCaseContext defaultCtx = ctxNode.defaultExpressionCase();
            ctx.setVariableInitializationState(stateBefore);
            if (defaultCtx.block() != null) {
                visitBlock(defaultCtx.block());
                if (!blockHasResult(defaultCtx.block())) {
                    reportError(defaultCtx, "Switch expression varsayılan bloğu içindeki tüm yollar bir result değeri döndürmelidir");
                }
            } else if (defaultCtx.expression() != null) {
                String type = inferExprType(defaultCtx.expression());
                if (type != null) {
                    branchTypes.add(type);
                }
            }
            branchStates.add(ctx.getVariableInitializationState());
        } else {
            reportError(ctxNode, "Switch ifadesi bir değer döndürdüğü için varsayılan (default) kolu barındırmalıdır");
            branchStates.add(stateBefore);
        }

        // Merge all branch states
        Map<String, Boolean> mergedState = branchStates.getFirst();
        for (int i = 1; i < branchStates.size(); i++) {
            mergedState = mergeInitializationStates(mergedState, branchStates.get(i));
        }
        ctx.setVariableInitializationState(mergedState);

        switchExpressionTypesStack.pop();
        switchExpressionDepth--;

        if (branchTypes.isEmpty()) {
            return OceanTypeSystem.OBJECT_DESC;
        }
        String commonType = branchTypes.getFirst();
        for (int i = 1; i < branchTypes.size(); i++) {
            commonType = TypeChecker.getCommonType(commonType, branchTypes.get(i));
        }
        return commonType;
    }

    private boolean blockHasResult(OceanParser.BlockContext block) {
        if (block == null || block.statement() == null)
            return false;
        List<OceanParser.StatementContext> stmts = block.statement();
        if (stmts.isEmpty())
            return false;
        for (OceanParser.StatementContext stmt : stmts) {
            if (statementHasResult(stmt)) {
                return true;
            }
        }
        return false;
    }

    private boolean statementHasResult(OceanParser.StatementContext stmt) {
        if (stmt instanceof OceanParser.ResultStmtContext)
            return true;
        if (stmt instanceof OceanParser.BlockStmtContext) {
            return blockHasResult(((OceanParser.BlockStmtContext) stmt).block());
        }
        if (stmt instanceof OceanParser.IfStmtContext) {
            OceanParser.IfStatementContext ifCtx = ((OceanParser.IfStmtContext) stmt).ifStatement();
            if (ifCtx.statement().size() >= 2) {
                return statementHasResult(ifCtx.statement(0)) && statementHasResult(ifCtx.statement(1));
            }
        }
        if (stmt instanceof OceanParser.TryStmtContext) {
            OceanParser.TryStatementContext tryCtx = ((OceanParser.TryStmtContext) stmt).tryStatement();
            boolean tryHas = blockHasResult(tryCtx.block(0));
            boolean catchesHas = true;
            if (tryCtx.catchClause() != null) {
                for (OceanParser.CatchClauseContext catchCtx : tryCtx.catchClause()) {
                    if (!blockHasResult(catchCtx.block())) {
                        catchesHas = false;
                        break;
                    }
                }
            }
            return tryHas && catchesHas;
        }
        return false;
    }

    private boolean areParamsCompatible(String parentDesc, String childDesc) {
        parentDesc = TypeChecker.cleanDescriptor(parentDesc);
        childDesc = TypeChecker.cleanDescriptor(childDesc);
        List<String> parentParams = getParameterDescriptors(parentDesc);
        List<String> childParams = getParameterDescriptors(childDesc);
        if (parentParams.size() != childParams.size()) {
            return false;
        }
        for (int i = 0; i < parentParams.size(); i++) {
            String p = TypeChecker.cleanDescriptor(parentParams.get(i));
            String c = TypeChecker.cleanDescriptor(childParams.get(i));
            if (p.equals(c)) continue;
            if (TypeChecker.isObjectType(p)|| TypeChecker.isObjectType(c)) continue;
            if (TypeChecker.isAssignable(p, c, session) || TypeChecker.isAssignable(c, p, session)) continue;
            return false;
        }
        return true;
    }

    private boolean hasMatchingOverride(String classPath, String methodName, String paramDesc) {
        Set<String> visited = new HashSet<>();
        String parent = CompilerRegistry.globalSuperClassRegistry.get(classPath);
        while (parent != null) {
            if (hasMethodInParentRecursive(parent, methodName, paramDesc, visited)) return true;
            parent = CompilerRegistry.globalSuperClassRegistry.get(parent);
        }
        String[] interfaces = CompilerRegistry.globalInterfaceRegistry.get(classPath);
        if (interfaces != null) {
            for (String inter : interfaces) {
                if (hasMethodInParentRecursive(inter, methodName, paramDesc, visited)) return true;
            }
        }
        return false;
    }

    private boolean hasMethodInParentRecursive(String parentPath, String methodName, String paramDesc, Set<String> visited) {
        if (parentPath == null || !visited.add(parentPath)) return false;
        if (hasMethodInParent(parentPath, methodName, paramDesc)) return true;
        String[] interfaces = CompilerRegistry.globalInterfaceRegistry.get(parentPath);
        if (interfaces != null) {
            for (String inter : interfaces) {
                if (hasMethodInParentRecursive(inter, methodName, paramDesc, visited)) return true;
            }
        }
        return false;
    }

    private boolean hasMethodInParent(String parentPath, String methodName, String paramDesc) {
        if (!CompilerRegistry.globalMethodRegistry.containsKey(parentPath)) {
            try {
                Class<?> clazz = OceanTypeSystem.forName(parentPath.replace('/', '.'));
                for (Method m : clazz.getMethods()) {
                    if (m.getName().equals(methodName)) {
                        String parentDesc = Type.getMethodDescriptor(m);
                        if (areParamsCompatible(parentDesc, paramDesc)) {
                            return true;
                        }
                    }
                }
            } catch (ClassNotFoundException | SecurityException ignored) {}
        } else {
            Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(parentPath);
            if (methods != null && methods.containsKey(methodName)) {
                String parentDesc = methods.get(methodName);
                if (areParamsCompatible(parentDesc, paramDesc)) {
                    return true;
                }
            }
            Map<String, List<String>> overloads = CompilerRegistry.globalOverloadRegistry.get(parentPath);
            if (overloads != null && overloads.containsKey(methodName)) {
                for (String parentDesc : overloads.get(methodName)) {
                    if (areParamsCompatible(parentDesc, paramDesc)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ========== Checked Exception & Null Safety & Circular Dependency Helpers ==========

    private boolean isCheckedException(String exceptionType) {
        if (exceptionType == null) return false;
        boolean isThrowable = session != null ? session.isSubType(exceptionType, "Ljava/lang/Throwable;")
                : CompilationSession.getFallbackSession().isSubType(exceptionType, "Ljava/lang/Throwable;");
        boolean isRuntimeException = session != null ? session.isSubType(exceptionType, "Ljava/lang/RuntimeException;")
                : CompilationSession.getFallbackSession().isSubType(exceptionType, "Ljava/lang/RuntimeException;");
        boolean isError = session != null ? session.isSubType(exceptionType, "Ljava/lang/Error;")
                : CompilationSession.getFallbackSession().isSubType(exceptionType, "Ljava/lang/Error;");
        return isThrowable && !isRuntimeException && !isError;
    }

    private boolean isExceptionHandled(String thrownType) {
        if (!isCheckedException(thrownType)) {
            return true;
        }
        // 1. try-catch bloğu içinde yakalanıyor mu?
        for (List<String> catches : caughtExceptionsStack) {
            for (String caughtType : catches) {
                boolean isSub = session != null ? session.isSubType(thrownType, caughtType)
                        : CompilationSession.getFallbackSession().isSubType(thrownType, caughtType);
                if (isSub) {
                    return true;
                }
            }
        }
        // 2. Mevcut metodun 'throws' listesinde beyan edilmiş mi?
        List<String> declaredThrows = ctx.getCurrentMethodThrownExceptions();
        if (declaredThrows != null) {
            for (String declaredType : declaredThrows) {
                boolean isSub = session != null ? session.isSubType(thrownType, declaredType)
                        : CompilationSession.getFallbackSession().isSubType(thrownType, declaredType);
                if (isSub) {
                    return true;
                }
            }
        }
        return false;
    }


    private void enterNullabilityScope() {
        nullabilityScopes.push(new HashMap<>());
    }

    private void exitNullabilityScope() {
        if (!nullabilityScopes.isEmpty()) {
            nullabilityScopes.pop();
        }
    }

    private void setVariableNullable(String name, boolean nullable) {
        if (!nullabilityScopes.isEmpty()) {
            nullabilityScopes.peek().put(name, nullable);
        }
    }

    private boolean isVariableNullable(String name) {
        for (Map<String, Boolean> scope : nullabilityScopes) {
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        TypeInfo info = ctx.lookupVariable(name);
        if (info != null) {
            String desc = info.getDescriptor();
            return TypeChecker.isNullable(desc);
        }
        return false;
    }

    private boolean isExpressionNonNull(OceanParser.ExpressionContext expr) {
        if (expr == null) return false;

        String desc = inferExprType(expr);
        if (TypeChecker.isPrimitive(desc)) {
            return true;
        }

        if (expr instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) expr).primary();
            if (p instanceof OceanParser.NumberPrimaryContext ||
                p instanceof OceanParser.CharPrimaryContext ||
                p instanceof OceanParser.StringPrimaryContext ||
                p instanceof OceanParser.TruePrimaryContext ||
                p instanceof OceanParser.FalsePrimaryContext ||
                p instanceof OceanParser.ThisRefPrimaryContext ||
                p instanceof OceanParser.SuperRefPrimaryContext) {
                return true;
            }
            if (p instanceof OceanParser.NullPrimaryContext) {
                return false;
            }
            if (p instanceof OceanParser.IdPrimaryContext) {
                String varName = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                return !isVariableNullable(varName);
            }
            if (p instanceof OceanParser.ParenthesizedPrimaryContext) {
                return isExpressionNonNull(((OceanParser.ParenthesizedPrimaryContext) p).expression());
            }
        }

        if (expr instanceof OceanParser.NewObjectExprContext) {
            return true;
        }

        if (expr instanceof OceanParser.CastExprContext) {
            return isExpressionNonNull(((OceanParser.CastExprContext) expr).expression());
        }

        if (expr instanceof OceanParser.TernaryExprContext ternary) {
            return isExpressionNonNull(ternary.expression(1)) && isExpressionNonNull(ternary.expression(2));
        }

        if (expr instanceof OceanParser.NullCoalescingExprContext coalescing) {
            return isExpressionNonNull(coalescing.expression(0)) || isExpressionNonNull(coalescing.expression(1));
        }

        return true;
    }

    private boolean isMethodStatic(String owner, String methodName) {
        return isMethodStatic(owner, methodName, new HashSet<>());
    }

    private boolean isMethodStatic(String owner, String methodName, Set<String> visited) {
        if (owner == null || !visited.add(owner)) return false;
        Map<String, Boolean> staticity = CompilerRegistry.globalMethodStaticity.get(owner);
        if (staticity != null && staticity.containsKey(methodName)) {
            return staticity.get(methodName);
        }
        String sup = CompilerRegistry.globalSuperClassRegistry.get(owner);
        if (sup != null) {
            return isMethodStatic(sup, methodName, visited);
        }
        try {
            Class<?> clazz = OceanTypeSystem.forName(owner.replace("/", "."));
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(methodName)) {
                    return Modifier.isStatic(m.getModifiers());
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void extractNullChecks(OceanParser.ExpressionContext cond, Map<String, Boolean> thenBranchNonNull, Map<String, Boolean> elseBranchNonNull) {
        switch (cond) {
            case null -> {
                return;
            }
            case OceanParser.PrimaryExprContext primaryExprContext -> {
                OceanParser.PrimaryContext p = primaryExprContext.primary();
                if (p instanceof OceanParser.ParenthesizedPrimaryContext) {
                    extractNullChecks(((OceanParser.ParenthesizedPrimaryContext) p).expression(), thenBranchNonNull, elseBranchNonNull);
                }
            }
            case OceanParser.LogicalAndExprContext and -> {
                extractNullChecks(and.expression(0), thenBranchNonNull, elseBranchNonNull);
                extractNullChecks(and.expression(1), thenBranchNonNull, elseBranchNonNull);
            }
            case OceanParser.EqualityExprContext eq -> {
                boolean isEquals = eq.op.getType() == OceanParser.EQUALS;
                boolean isNotEquals = eq.op.getType() == OceanParser.NOT_EQUALS;

                String varName = null;
                boolean hasNull = false;

                if (eq.expression(0) instanceof OceanParser.PrimaryExprContext) {
                    OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) eq.expression(0)).primary();
                    if (p instanceof OceanParser.IdPrimaryContext) {
                        varName = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                    } else if (p instanceof OceanParser.NullPrimaryContext) {
                        hasNull = true;
                    }
                }

                if (eq.expression(1) instanceof OceanParser.PrimaryExprContext) {
                    OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) eq.expression(1)).primary();
                    if (p instanceof OceanParser.IdPrimaryContext) {
                        varName = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                    } else if (p instanceof OceanParser.NullPrimaryContext) {
                        hasNull = true;
                    }
                }

                if (varName != null && hasNull) {
                    if (isNotEquals) {
                        thenBranchNonNull.put(varName, true);
                    } else if (isEquals) {
                        elseBranchNonNull.put(varName, true);
                    }
                }
            }
            default -> {
            }
        }

    }

    private void enterScope() {
        ctx.enterScope();
        enterNullabilityScope();
    }

    private void exitScope() {
        ctx.exitScope();
        exitNullabilityScope();
    }

    private boolean classHasMethod(String owner, String methodName) {
        return classHasMethod(owner, methodName, new HashSet<>());
    }

    private boolean classHasMethod(String owner, String methodName, Set<String> visited) {
        if (owner == null || !visited.add(owner)) return false;
        Map<String, List<String>> overloads = CompilerRegistry.globalOverloadRegistry.get(owner);
        if (overloads != null && overloads.containsKey(methodName)) return true;
        Map<String, String> methods = CompilerRegistry.globalMethodRegistry.get(owner);
        if (methods != null && methods.containsKey(methodName)) return true;
        String sup = CompilerRegistry.globalSuperClassRegistry.get(owner);
        if (sup != null && !sup.equals(owner)) return classHasMethod(sup, methodName, visited);
        return false;
    }

    private boolean hasCycle(String current, Set<String> visited, Set<String> stack) {
        if (stack.contains(current)) {
            return true;
        }
        if (visited.contains(current)) {
            return false;
        }
        visited.add(current);
        stack.add(current);

        String superName = CompilerRegistry.globalSuperClassRegistry.get(current);
        if (superName != null && !superName.equals("java/lang/Object")) {
            if (hasCycle(superName, visited, stack)) {
                return true;
            }
        }

        String[] interfaces = CompilerRegistry.globalInterfaceRegistry.get(current);
        if (interfaces != null) {
            for (String inter : interfaces) {
                if (hasCycle(inter, visited, stack)) {
                    return true;
                }
            }
        }

        stack.remove(current);
        return false;
    }

    @Override
    public String visitComparisonExpr(OceanParser.ComparisonExprContext ctxNode) {
        String left = inferExprType(ctxNode.expression(0));
        String right = inferExprType(ctxNode.expression(1));

        visit(ctxNode.expression(0));
        visit(ctxNode.expression(1));

        if (left != null && right != null) {
            if (!TypeChecker.isComparable(left, right)) {
                reportError(ctxNode, "Karşılaştırma operatörleri (" + ctxNode.op.getText() + ") uyumsuz tipler arasında kullanılamaz: '" + TypeChecker.humanReadable(left) + "' ve '" + TypeChecker.humanReadable(right) + "'");
            }
        }
        return "Z";
    }

    @Override
    public String visitEqualityExpr(OceanParser.EqualityExprContext ctxNode) {
        String left = inferExprType(ctxNode.expression(0));
        String right = inferExprType(ctxNode.expression(1));

        visit(ctxNode.expression(0));
        visit(ctxNode.expression(1));

        if (left != null && right != null) {
            boolean isLeftPrim = TypeChecker.isPrimitive(left);
            boolean isRightPrim = TypeChecker.isPrimitive(right);

            if (isLeftPrim != isRightPrim) {
                // primitive vs reference: check if assignable
                if (!TypeChecker.isAssignable(left, right, session) && !TypeChecker.isAssignable(right, left, session)) {
                    reportError(ctxNode, "Eşitlik operatörleri (" + ctxNode.op.getText() + ") uyumsuz tipler arasında kullanılamaz: '" + TypeChecker.humanReadable(left) + "' ve '" + TypeChecker.humanReadable(right) + "'");
                }
            } else if (isLeftPrim) {
                boolean leftNumeric = TypeChecker.isNumeric(left);
                boolean rightNumeric = TypeChecker.isNumeric(right);
                if (leftNumeric != rightNumeric) {
                    reportError(ctxNode, "Eşitlik operatörleri (" + ctxNode.op.getText() + ") uyumsuz tipler arasında kullanılamaz: '" + TypeChecker.humanReadable(left) + "' ve '" + TypeChecker.humanReadable(right) + "'");
                }
            }
        }
        return "Z";
    }

    @Override
    public String visitShiftExpr(OceanParser.ShiftExprContext ctxNode) {
        String left = inferExprType(ctxNode.expression(0));
        String right = inferExprType(ctxNode.expression(1));

        visit(ctxNode.expression(0));
        visit(ctxNode.expression(1));

        if (left != null && !TypeChecker.isIntegerType(left)) {
            reportError(ctxNode, "Kaydırma operatörleri (" + ctxNode.op.getText() + ") yalnızca tam sayı tipleri üzerinde kullanılabilir: '" + TypeChecker.humanReadable(left) + "'");
        }
        if (right != null && !TypeChecker.isIntegerType(right)) {
            reportError(ctxNode, "Kaydırma miktarı yalnızca tam sayı tipleri olabilir: '" + TypeChecker.humanReadable(right) + "'");
        }
        return left != null ? left : "I";
    }

    @Override
    public String visitBitAndExpr(OceanParser.BitAndExprContext ctxNode) {
        return checkBitwise(ctxNode.expression(0), ctxNode.expression(1), "&", ctxNode);
    }

    @Override
    public String visitBitXorExpr(OceanParser.BitXorExprContext ctxNode) {
        return checkBitwise(ctxNode.expression(0), ctxNode.expression(1), "^", ctxNode);
    }

    @Override
    public String visitBitOrExpr(OceanParser.BitOrExprContext ctxNode) {
        return checkBitwise(ctxNode.expression(0), ctxNode.expression(1), "|", ctxNode);
    }

    private String checkBitwise(OceanParser.ExpressionContext e1, OceanParser.ExpressionContext e2, String op, ParserRuleContext ctx) {
        String left = inferExprType(e1);
        String right = inferExprType(e2);

        visit(e1);
        visit(e2);

        if (left != null && !TypeChecker.isIntegerType(left) && !"Z".equals(left) && !"Ljava/lang/Boolean;".equals(left)) {
            reportError(ctx, "Bit düzeyinde operatörler (" + op + ") yalnızca tam sayı veya boolean tipleri üzerinde kullanılabilir: '" + TypeChecker.humanReadable(left) + "'");
        }
        if (right != null && !TypeChecker.isIntegerType(right) && !"Z".equals(right) && !"Ljava/lang/Boolean;".equals(right)) {
            reportError(ctx, "Bit düzeyinde operatörler (" + op + ") yalnızca tam sayı veya boolean tipleri üzerinde kullanılabilir: '" + TypeChecker.humanReadable(right) + "'");
        }
        return TypeChecker.getCommonType(left != null ? left : "I", right != null ? right : "I");
    }

    private Map<String, Boolean> mergeInitializationStates(Map<String, Boolean> state1, Map<String, Boolean> state2) {
        Map<String, Boolean> merged = new HashMap<>();
        for (String varName : state1.keySet()) {
            if (state2.containsKey(varName)) {
                merged.put(varName, state1.get(varName) && state2.get(varName));
            }
        }
        return merged;
    }

    private String getGenericTypeArgument(String typeStr) {
        if (typeStr != null && typeStr.contains("<") && typeStr.contains(">")) {
            int start = typeStr.indexOf("<") + 1;
            int end = typeStr.lastIndexOf(">");
            if (start < end) {
                return typeStr.substring(start, end);
            }
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

    private String getRawExprType(OceanParser.ExpressionContext expr) {
        if (expr instanceof OceanParser.PrimaryExprContext) {
            OceanParser.PrimaryContext p = ((OceanParser.PrimaryExprContext) expr).primary();
            if (p instanceof OceanParser.IdPrimaryContext) {
                String name = ((OceanParser.IdPrimaryContext) p).anyId().getText();
                TypeInfo info = ctx.lookupVariable(name);
                if (info != null) {
                    return info.getRawType();
                }
            }
        }
        return inferExprType(expr);
    }
}
