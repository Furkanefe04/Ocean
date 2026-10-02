package ocean.compiler.ir;
/**
 * Koşul doğrulama ve assert mantığını yürüten `verify` komutunu temsil eden IR düğümü.
 */
public class IRVerifyStatement extends IRStatement {
    private final IRExpression condition;
    private final IRExpression detailMessage;

    public IRVerifyStatement(IRExpression condition, IRExpression detailMessage) {
        this.condition = condition;
        this.detailMessage = detailMessage;
    }

    public IRExpression getCondition() { return condition; }
    public IRExpression getDetailMessage() { return detailMessage; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitVerify(this);
    }
}
