package ocean.compiler.ir;

/**
 * Return ifadesini temsil eden IR düğümü.
 */
public class IRReturnStatement extends IRStatement {
    private final IRExpression expression; // null olabilir (void)

    public IRReturnStatement(IRExpression expression) {
        this.expression = expression;
    }

    public IRExpression getExpression() { return expression; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitReturn(this);
    }
}
