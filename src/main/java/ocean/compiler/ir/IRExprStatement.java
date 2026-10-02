package ocean.compiler.ir;

/**
 * Yan etki (side-effect) üretmek amacıyla tek başına bir komut (statement) olarak çalıştırılan ifadeleri temsil eden IR düğümü.
 */
public class IRExprStatement extends IRStatement {
    private final IRExpression expression;
    public IRExprStatement(IRExpression expression) { 
        this.expression = expression; 
        if (expression != null) {
            setLocation(expression.getLineNumber(), expression.getColumnNumber());
        }
    }
    public IRExpression getExpression() { return expression; }
    @Override public void accept(IRVisitor visitor) { visitor.visitExprStatement(this); }
}
