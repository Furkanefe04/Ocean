package ocean.compiler.ir;

import java.util.List;

/**
 * Bir kaynak dosyasındaki tüm tipleri (class, interface, enum) içeren kök IR düğümü.
 */
public class IRCompilationUnit extends IRNode {
    private final List<IRNode> types;

    public IRCompilationUnit(List<IRNode> types) {
        this.types = types;
    }

    public List<IRNode> getTypes() {
        return types;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitCompilationUnit(this);
    }
}
