package ocean.compiler.ir;
/**
 * Döngünün bir sonraki yinelemesine atlamayı sağlayan `continue` / `skip` komutunu temsil eden IR düğümü.
 */
public class IRSkipStatement extends IRStatement {
    private final String targetLabel;

    public IRSkipStatement() {
        this(null);
    }

    public IRSkipStatement(String targetLabel) {
        this.targetLabel = targetLabel;
    }

    public String getTargetLabel() {
        return targetLabel;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitSkip(this);
    }
}
