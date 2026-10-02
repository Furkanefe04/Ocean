package ocean.compiler.ir;

import java.util.List;

/**
 * Blokları temsil eden IR düğümü.
 */
public class IRBlock extends IRStatement {
    private final List<IRStatement> statements;
    private boolean isStatic = false;

    public IRBlock(List<IRStatement> statements) {
        this.statements = statements;
    }

    public List<IRStatement> getStatements() {
        return statements;
    }

    public boolean isStatic() { return isStatic; }
    public void setStatic(boolean isStatic) { this.isStatic = isStatic; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitBlock(this);
    }
}
