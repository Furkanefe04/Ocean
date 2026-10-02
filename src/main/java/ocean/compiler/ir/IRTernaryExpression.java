package ocean.compiler.ir;

/**
 * Ternary (üçlü) operatörü temsil eden IR düğümü.
 * isNullCoalescing = true ise bu düğüm `a ?? b` genişletmesinden gelir;
 * emitter bu durumda LHS'i yalnızca bir kez değerlendirir (DUP + IFNONNULL + POP).
 */
public class IRTernaryExpression extends IRExpression {
    private final IRExpression condition;
    private final IRExpression trueExpr;
    private final IRExpression falseExpr;
    private final boolean isNullCoalescing;

    public IRTernaryExpression(IRExpression condition, IRExpression trueExpr, IRExpression falseExpr, String typeDescriptor) {
        this(condition, trueExpr, falseExpr, typeDescriptor, false);
    }

    public IRTernaryExpression(IRExpression condition, IRExpression trueExpr, IRExpression falseExpr, String typeDescriptor, boolean isNullCoalescing) {
        this.condition = condition;
        this.trueExpr = trueExpr;
        this.falseExpr = falseExpr;
        this.isNullCoalescing = isNullCoalescing;
        setTypeDescriptor(typeDescriptor);
    }

    public IRExpression getCondition() { return condition; }
    public IRExpression getTrueExpr() { return trueExpr; }
    public IRExpression getFalseExpr() { return falseExpr; }
    public boolean isNullCoalescing() { return isNullCoalescing; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitTernary(this);
    }
}
