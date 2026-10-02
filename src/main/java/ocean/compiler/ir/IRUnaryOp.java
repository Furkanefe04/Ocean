package ocean.compiler.ir;

/**
 * Tekli operatörleri (örn: !, -, ++, --) temsil eden IR düğümü.
 */
public class IRUnaryOp extends IRExpression {
    public enum Op {
        NOT, NEG, PRE_INC, PRE_DEC, POST_INC, POST_DEC, BIT_NOT
    }

    private final IRExpression expression;
    private final Op operator;

    public IRUnaryOp(IRExpression expression, Op operator, String typeDescriptor) {
        this.expression = expression;
        this.operator = operator;
        setTypeDescriptor(typeDescriptor);
    }

    public IRExpression getExpression() { return expression; }
    public Op getOperator() { return operator; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitUnaryOp(this);
    }
}
