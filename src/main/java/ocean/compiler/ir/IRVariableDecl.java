package ocean.compiler.ir;

/**
 * Yerel değişken ve sabit bildirimlerini (variable, value, final, int x = ...) temsil eden IR komut düğümü.
 */
public class IRVariableDecl extends IRStatement {
    private final String name;
    private final String typeDescriptor;
    private final IRExpression initialValue;
    private final boolean isFinal;
    private final boolean isExplicitType;

    public IRVariableDecl(String name, String typeDescriptor, IRExpression initialValue, boolean isFinal) {
        this(name, typeDescriptor, initialValue, isFinal, false);
    }

    public IRVariableDecl(String name, String typeDescriptor, IRExpression initialValue, boolean isFinal, boolean isExplicitType) {
        this.name = name;
        this.typeDescriptor = typeDescriptor;
        this.initialValue = initialValue;
        this.isFinal = isFinal;
        this.isExplicitType = isExplicitType;
    }

    public String getName() { return name; }
    public String getTypeDescriptor() { return typeDescriptor; }
    public IRExpression getInitialValue() { return initialValue; }
    public boolean isFinal() { return isFinal; }
    public boolean isExplicitType() { return isExplicitType; }
    @Override public void accept(IRVisitor visitor) { visitor.visitVariableDecl(this); }
}

