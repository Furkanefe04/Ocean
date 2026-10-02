package ocean.compiler.ir;
/**
 * Etiketli blok ve döngü komutlarını temsil eden IR düğümü.
 * Etiketli break (stop) ve continue (skip) işlemlerinin hedef etiketini tanımlar.
 */
public class IRLabeledStatement extends IRStatement {
    private final String label;
    private final IRStatement statement;

    public IRLabeledStatement(String label, IRStatement statement) {
        this.label = label;
        this.statement = statement;
    }

    public String getLabel() {
        return label;
    }

    public IRStatement getStatement() {
        return statement;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitLabeled(this);
    }
}
