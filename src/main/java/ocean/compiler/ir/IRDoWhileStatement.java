package ocean.compiler.ir;

/**
 * Do-While döngüsünü temsil eden IR düğümü.
 */
public class IRDoWhileStatement extends IRStatement {
    private final IRExpression condition;
    private final IRStatement body;

    public IRDoWhileStatement(IRExpression condition, IRStatement body) {
        this.condition = condition;
        this.body = body;
    }

    public IRExpression getCondition() { return condition; }
    public IRStatement getBody() { return body; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitDoWhile(this);
    }
}
