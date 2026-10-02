package ocean.compiler.ir;

/**
 * Dizi erişimini (örn: dizi[index]) temsil eden IR düğümü.
 */
public class IRArrayAccess extends IRExpression {
    private final IRExpression array;
    private final IRExpression index;

    public IRArrayAccess(IRExpression array, IRExpression index, String typeDescriptor) {
        this.array = array;
        this.index = index;
        setTypeDescriptor(typeDescriptor);
    }

    public IRExpression getArray() { return array; }
    public IRExpression getIndex() { return index; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitArrayAccess(this);
    }
}
