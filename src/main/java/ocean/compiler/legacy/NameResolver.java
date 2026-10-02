// WARNING: Bu sınıf sadece V2 Runner'a (OceanRunnerV2) aittir ve V3 derleyicisinde kullanılmaz. Lütfen silmeyiniz veya değiştirmeyiniz.
package ocean.compiler.legacy;

import ocean.compiler.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import ocean.compiler.OceanParser;
import ocean.compiler.OceanBaseVisitor;

/**
 * [DEPRECATED / LEGACY]
 * Bu sınıf eski derleyici mimarisine (V2 / AST-based) aittir.
 * Aktif olarak kullanılan V3 (IR tabanlı) derleyici hattında kullanılmamaktadır.
 * İsim çözümleme ve sembol kayıt işlemleri V3 mimarisinde PreScanner ve SymbolTable sınıfları tarafından üstlenilmiştir.
 * İkinci Geçiş (Pass 2): İsim Çözümleme ve Sembol Kaydı (Name Resolution).
 */
public class NameResolver extends OceanBaseVisitor<Void> {
    private String currentPackage = "default";
    private String currentClassName;
    private final Map<String, String> importedClasses = new HashMap<>();

    public NameResolver(String currentFile) {
        if (currentFile != null && currentFile.endsWith(".ocean")) {
            // Mutlak yoldan yalın dosya adını çıkar (dizin ayırıcıları varsa)
            String name = currentFile;
            int lastSep = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
            if (lastSep >= 0) {
                name = name.substring(lastSep + 1);
            }
            this.currentPackage = name.substring(0, name.length() - 6);
        }
    }


    @Override
    public Void visitPackageDeclaration(OceanParser.PackageDeclarationContext ctx) {
        StringBuilder pkgName = new StringBuilder();
        for (int i = 0; i < ctx.anyId().size(); i++) {
            if (i > 0) pkgName.append("/");
            pkgName.append(ctx.anyId(i).getText());
        }
        this.currentPackage = pkgName.toString();
        return null;
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
        String oldClass = currentClassName;
        currentClassName = ctx.anyId().getText();
        String fullPath = "ocean/stdlib/" + currentPackage + "/" + currentClassName;
        
        CompilerRegistry.globalMethodRegistry.computeIfAbsent(fullPath, k -> new ConcurrentHashMap<>());
        CompilerRegistry.globalFieldRegistry.computeIfAbsent(fullPath, k -> new ConcurrentHashMap<>());

        visitChildren(ctx);
        
        currentClassName = oldClass;
        return null;
    }

    @Override
    public Void visitFieldDeclaration(OceanParser.FieldDeclarationContext ctx) {
        if (currentClassName == null) return null;
        String type = ctx.type() != null ? ctx.type().getText() : "variable";
        String desc = SymbolTable.getDescriptor(type, importedClasses);
        
        String fullPath = "ocean/stdlib/" + currentPackage + "/" + currentClassName;
        for (OceanParser.VariableDeclaratorContext d : ctx.variableDeclarator()) {
            String name = d.anyId().getText();
            CompilerRegistry.globalFieldRegistry.get(fullPath).put(name, desc);
        }
        return null;
    }

    @Override
    public Void visitNormalMethod(OceanParser.NormalMethodContext ctx) {
        if (currentClassName == null) return null;
        String name = ctx.anyId().getText();
        
        // Return type
        String rType = "V";
        if (ctx.VOID() == null && !ctx.type().isEmpty()) {
            rType = SymbolTable.getDescriptor(ctx.type(0).getText(), importedClasses);
        }
        
        // Parameters
        StringBuilder sb = new StringBuilder("(");
        if (ctx.parameterList() != null) {
            for (OceanParser.ParameterContext p : ctx.parameterList().parameter()) {
                sb.append(SymbolTable.getDescriptor(p.type().getText(), importedClasses));
            }
        }
        sb.append(")").append(rType);
        
        String fullPath = "ocean/stdlib/" + currentPackage + "/" + currentClassName;
        CompilerRegistry.globalMethodRegistry.get(fullPath).put(name, sb.toString());
        return null;
    }
}
