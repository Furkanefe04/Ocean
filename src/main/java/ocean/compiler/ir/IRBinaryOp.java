package ocean.compiler.ir;

/**
 * İkili işlemleri (+, -, *, /, &&, ||, == vb.) temsil eden IR düğümü.
 */
public class IRBinaryOp extends IRExpression {
    public enum Op { ADD, SUB, MUL, DIV, MOD, EQ, NE, LT, LE, GT, GE, AND, OR, LSHIFT, RSHIFT, URSHIFT, BIT_AND, BIT_OR, BIT_XOR }

    private final IRExpression left;
    private final IRExpression right;
    private final Op operator;

    public IRBinaryOp(IRExpression left, IRExpression right, Op operator, String typeDescriptor) {
        this.left = left;
        this.right = right;
        this.operator = operator;
        setTypeDescriptor(typeDescriptor);
    }

    public IRExpression getLeft() { return left; }
    public IRExpression getRight() { return right; }
    public Op getOperator() { return operator; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitBinaryOp(this);
    }
}
