package ocean.compiler.ir;

/**
 * Adımlı sayısal döngüleri (from ... to ... with increasing/decreasing) ve
 * koleksiyon üzerinde yineleme yapan foreach döngülerini temsil eden IR düğümü.
 */
public class IRForStatement extends IRStatement {
    private final String iteratorName;
    private final String typeDescriptor;
    private final IRExpression fromExpr;
    private final IRExpression toExpr;
    private final IRExpression stepExpr;
    private final boolean increasing;
    private final IRExpression iterableExpr;
    private final IRStatement body;
    private final boolean isRange;

    public IRForStatement(String iteratorName, String typeDescriptor, IRExpression fromExpr, IRExpression toExpr, IRExpression stepExpr, boolean increasing, IRStatement body) {
        this.iteratorName = iteratorName; this.typeDescriptor = typeDescriptor; this.fromExpr = fromExpr; this.toExpr = toExpr; this.stepExpr = stepExpr; this.increasing = increasing; this.iterableExpr = null; this.body = body; this.isRange = true;
    }

    public IRForStatement(String iteratorName, String typeDescriptor, IRExpression iterableExpr, IRStatement body) {
        this.iteratorName = iteratorName; this.typeDescriptor = typeDescriptor; this.fromExpr = null; this.toExpr = null; this.stepExpr = null; this.increasing = true; this.iterableExpr = iterableExpr; this.body = body; this.isRange = false;
    }

    public String getIteratorName() { return iteratorName; }
    public String getTypeDescriptor() { return typeDescriptor; }
    public IRExpression getFromExpr() { return fromExpr; }
    public IRExpression getToExpr() { return toExpr; }
    public IRExpression getStepExpr() { return stepExpr; }
    public boolean isIncreasing() { return increasing; }
    public IRExpression getIterableExpr() { return iterableExpr; }
    public IRStatement getBody() { return body; }
    public boolean isRange() { return isRange; }

    @Override public void accept(IRVisitor visitor) { visitor.visitForStatement(this); }
}
