package ocean.compiler.ir;

/**
 * Representing a lock statement block: lock (expr) { block }
 */
public class IRLockStatement extends IRStatement {
    private final IRExpression lockExpression;
    private final IRStatement body;

    public IRLockStatement(IRExpression lockExpression, IRStatement body) {
        this.lockExpression = lockExpression;
        this.body = body;
    }

    public IRExpression getLockExpression() {
        return lockExpression;
    }

    public IRStatement getBody() {
        return body;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitLockStatement(this);
    }
}
