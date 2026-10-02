// WARNING: Bu sınıf sadece V2 Runner'a (OceanRunnerV2) aittir ve V3 derleyicisinde kullanılmaz. Lütfen silmeyiniz veya değiştirmeyiniz.
package ocean.compiler.legacy;

import ocean.compiler.*;

import org.antlr.v4.runtime.ParserRuleContext;
import ocean.compiler.OceanBaseVisitor;
import ocean.compiler.OceanParser;
import java.util.*;

/**
 * [DEPRECATED / LEGACY]
 * Bu sınıf eski derleyici mimarisine (V2 / AST-based) aittir.
 * Aktif olarak kullanılan V3 (IR tabanlı) derleyici hattında kullanılmamaktadır.
 * Sınıf/arayüz hiyerarşisi ve sirküler kalıtım doğrulamaları V3 mimarisinde CompilationSession, TypeChecker ve SemanticAnalyzer sınıfları tarafından yapılmaktadır.
 * İlk geçiş (Pass 1): Sınıf ve arayüz (interface) hiyerarşisini kurar.
 * Sirküler bağımlılıkları (A extends B, B extends A) tespit eder
 * ve SemanticAnalyzer'dan önce hiyerarşi grafiğini doğrular.
 */
public class HierarchyAnalyzer extends OceanBaseVisitor<Void> {
    private final String currentFile;
    private final Map<String, String> importedClasses = new HashMap<>();
    
    // Geçici hiyerarşi ağacı
    private final Map<String, String> classToSuper = new HashMap<>();
    private final Map<String, List<String>> classToInterfaces = new HashMap<>();
    private int errorCount = 0;

    public HierarchyAnalyzer(String currentFile) {
        this.currentFile = currentFile;
    }

    public boolean hasErrors() {
        return errorCount > 0;
    }

    private void reportError(ParserRuleContext node, String message) {
        int line = node != null ? node.start.getLine() : 0;
        int col = node != null ? node.start.getCharPositionInLine() : 0;
        CompilerReporter.error(currentFile, line, col, message, "HierarchyAnalyzer");
        errorCount++;
    }

    private void reportError(String message) {
        reportError(null, message);
    }

    @Override
    public Void visitImportStatement(OceanParser.ImportStatementContext ctx) {
        List<OceanParser.AnyIdContext> parts = ctx.anyId();
        if (parts != null && !parts.isEmpty()) {
            String simpleName = parts.getLast().getText();
            StringBuilder fullPath = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) fullPath.append("/");
                fullPath.append(parts.get(i).getText());
            }
            importedClasses.put(simpleName, fullPath.toString());
        }
        return null;
    }

    @Override
    public Void visitClassDeclaration(OceanParser.ClassDeclarationContext ctx) {
        String className = ctx.anyId().getText();
        String fullPath = "ocean/stdlib/" + extractPackage() + "/" + className;
        
        String superName = "java/lang/Object";
        if (ctx.type() != null) {
            String superText = ctx.type().getText();
            superName = SymbolTable.getDescriptor(superText, importedClasses, Collections.emptySet());
            if (TypeChecker.isClassType(superName)) {
                superName = superName.substring(1, superName.length() - 1);
            }
        }
        classToSuper.put(fullPath, superName);
        CompilerRegistry.globalSuperClassRegistry.put(fullPath, superName);
        
        List<String> interfaces = new ArrayList<>();
        if (ctx.typeList() != null) {
            for (OceanParser.TypeContext tCtx : ctx.typeList().type()) {
                String interName = SymbolTable.getDescriptor(tCtx.getText(), importedClasses, Collections.emptySet());
                if (TypeChecker.isObjectType(interName)) {
                    interfaces.add(interName.substring(1, interName.length() - 1));
                }
            }
        }
        classToInterfaces.put(fullPath, interfaces);
        CompilerRegistry.globalInterfaceRegistry.put(fullPath, interfaces.toArray(new String[0]));
        
        // Sadece class/interface tanımlarına bak, gövdelerine girme
        return null;
    }
    
    @Override
    public Void visitInterfaceDeclaration(OceanParser.InterfaceDeclarationContext ctx) {
        String className = ctx.anyId().getText();
        String fullPath = "ocean/stdlib/" + extractPackage() + "/" + className;
        
        CompilerRegistry.globalSuperClassRegistry.put(fullPath, "java/lang/Object");
        CompilerRegistry.globalIsInterfaceSet.add(fullPath);
        
        List<String> interfaces = new ArrayList<>();
        if (ctx.typeList() != null) {
            for (OceanParser.TypeContext tCtx : ctx.typeList().type()) {
                String interName = SymbolTable.getDescriptor(tCtx.getText(), importedClasses, Collections.emptySet());
                if (TypeChecker.isClassType(interName)) {
                    interfaces.add(interName.substring(1, interName.length() - 1));
                }
            }
        }
        classToInterfaces.put(fullPath, interfaces);
        CompilerRegistry.globalInterfaceRegistry.put(fullPath, interfaces.toArray(new String[0]));
        
        return null;
    }

    /**
     * Ziyaret sonrası tüm hiyerarşiyi döngü (cycle) açısından kontrol eder.
     */
    public void validateHierarchy() {
        for (String clazz : classToSuper.keySet()) {
            Set<String> visited = new HashSet<>();
            String current = clazz;
            while (current != null && !current.equals("java/lang/Object")) {
                if (!visited.add(current)) {
                    reportError("Circular inheritance detected involving class: " + current);
                    break;
                }
                current = classToSuper.get(current);
            }
        }
    }
    
    /**
     * Dosya adından paket kısmını çıkarır.
     * Mutlak yollarda (örn. C:/Project/src/Main.ocean) sadece yalın dosya adı
     * döndürülür (Main). Tam yolun paket olarak kullanılması geçersiz sınıf adlarına yol açar.
     */
    private String extractPackage() {
        String name = currentFile;
        // Dizin ayırıcıları varsa sadece son kısmı al
        int lastSep = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSep >= 0) {
            name = name.substring(lastSep + 1);
        }
        // .ocean uzantısını kaldır
        if (name.endsWith(".ocean")) {
            name = name.substring(0, name.length() - 6);
        }
        return name;
    }
}
