package ocean.compiler.ir;

/**
 * If-Else yapısını temsil eden IR düğümü.
 */
public class IRIfStatement extends IRStatement {
    private final IRExpression condition;
    private final IRStatement thenBranch;
    private final IRStatement elseBranch; // null olabilir

    public IRIfStatement(IRExpression condition, IRStatement thenBranch, IRStatement elseBranch) {
        this.condition = condition;
        this.thenBranch = thenBranch;
        this.elseBranch = elseBranch;
    }

    public IRExpression getCondition() { return condition; }
    public IRStatement getThenBranch() { return thenBranch; }
    public IRStatement getElseBranch() { return elseBranch; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitIf(this);
    }
}
