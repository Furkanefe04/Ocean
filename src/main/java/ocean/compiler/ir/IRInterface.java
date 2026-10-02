package ocean.compiler.ir;

import java.util.ArrayList;
import java.util.List;
/**
 * Arayüz (interface) tanımlarını temsil eden IR düğümü.
 * Üst arayüzleri, soyut ve gövdeli (default) metotları ve sabit alanları içerir.
 */
public class IRInterface extends IRNode {
    private final String name;
    private final List<String> superInterfaces;
    private final List<IRMethod> methods;
    private final List<IRField> fields;

    public IRInterface(String name, List<String> superInterfaces, List<IRMethod> methods) {
        this(name, superInterfaces, methods, new ArrayList<>());
    }

    public IRInterface(String name, List<String> superInterfaces, List<IRMethod> methods, List<IRField> fields) {
        this.name = name;
        this.superInterfaces = superInterfaces;
        this.methods = methods;
        this.fields = fields != null ? fields : new ArrayList<>();
    }

    public String getName() { return name; }
    public List<String> getSuperInterfaces() { return superInterfaces; }
    public List<IRMethod> getMethods() { return methods; }
    public List<IRField> getFields() { return fields; }

    private final List<IRBlock> staticBlocks = new ArrayList<>();
    public List<IRBlock> getStaticBlocks() { return staticBlocks; }
    public void addStaticBlock(IRBlock block) { staticBlocks.add(block); }

    private final List<String> permittedSubclasses = new ArrayList<>();
    private final List<IRAnnotation> annotations = new ArrayList<>();
    private boolean isSealed = false;
    private boolean isNonSealed = false;

    public void addAnnotation(IRAnnotation annotation) { annotations.add(annotation); }
    public List<IRAnnotation> getAnnotations() { return annotations; }

    public boolean isSealed() { return isSealed; }
    public void setSealed(boolean isSealed) { this.isSealed = isSealed; }
    public boolean isNonSealed() { return isNonSealed; }
    public void setNonSealed(boolean isNonSealed) { this.isNonSealed = isNonSealed; }
    public List<String> getPermittedSubclasses() { return permittedSubclasses; }
    public void addPermittedSubclass(String subclass) { permittedSubclasses.add(subclass); }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitInterface(this);
    }
}
