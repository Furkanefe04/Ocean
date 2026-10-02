package ocean.compiler.ir;

/**
 * Atama işlemlerini temsil eden IR düğümü.
 */
public class IRAssignment extends IRExpression {
    private final IRExpression target;
    private final IRExpression value;
    private final boolean isStatic;
    private final boolean isCompound;

    public IRAssignment(IRExpression target, IRExpression value, boolean isStatic) {
        this(target, value, isStatic, false);
    }

    public IRAssignment(IRExpression target, IRExpression value, boolean isStatic, boolean isCompound) {
        this.target = target;
        this.value = value;
        this.isStatic = isStatic;
        this.isCompound = isCompound;
        if (target != null && target.getTypeDescriptor() != null) {
            setTypeDescriptor(target.getTypeDescriptor());
        } else if (value != null && value.getTypeDescriptor() != null) {
            setTypeDescriptor(value.getTypeDescriptor());
        }
    }

    public IRExpression getTarget() { return target; }
    public IRExpression getValue() { return value; }
    public boolean isStatic() { return isStatic; }
    public boolean isCompound() { return isCompound; }

    @Override
    public String getTypeDescriptor() {
        String desc = super.getTypeDescriptor();
        if (desc != null) return desc;
        if (target != null && target.getTypeDescriptor() != null) return target.getTypeDescriptor();
        if (value != null && value.getTypeDescriptor() != null) return value.getTypeDescriptor();
        return null;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitAssignment(this);
    }
}
