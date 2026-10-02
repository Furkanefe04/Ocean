package ocean.compiler.ir;
/**
 * Döngülerden veya switch yapılarından çıkışı sağlayan `break` / `stop` komutunu temsil eden IR düğümü.
 */
public class IRStopStatement extends IRStatement {
    private final String targetLabel;

    public IRStopStatement() {
        this(null);
    }

    public IRStopStatement(String targetLabel) {
        this.targetLabel = targetLabel;
    }

    public String getTargetLabel() {
        return targetLabel;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitStop(this);
    }
}
