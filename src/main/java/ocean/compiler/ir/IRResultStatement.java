package ocean.compiler.ir;

/**
 * Switch expression bloğundan değer döndüren 'result x' ifadesini temsil eden IR düğümü.
 */
public class IRResultStatement extends IRStatement {
    private final IRExpression expression;

    public IRResultStatement(IRExpression expression) {
        this.expression = expression;
    }

    public IRExpression getExpression() { return expression; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitResultStatement(this);
    }
}
