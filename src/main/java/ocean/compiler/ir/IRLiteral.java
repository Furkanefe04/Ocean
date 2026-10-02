package ocean.compiler.ir;

/**
 * Sabit değerleri (Literal) temsil eden IR düğümü.
 */
public class IRLiteral extends IRExpression {
    private final Object value;

    public IRLiteral(Object value, String typeDescriptor) {
        this.value = value;
        setTypeDescriptor(typeDescriptor);
    }

    public Object getValue() {
        return value;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitLiteral(this);
    }
}
