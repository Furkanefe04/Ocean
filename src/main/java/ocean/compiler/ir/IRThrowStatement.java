package ocean.compiler.ir;

/**
 * İstisna fırlatmayı (throw x) temsil eden IR düğümü.
 */
public class IRThrowStatement extends IRStatement {
    private final IRExpression expression;

    public IRThrowStatement(IRExpression expression) {
        this.expression = expression;
    }

    public IRExpression getExpression() { return expression; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitThrow(this);
    }
}
