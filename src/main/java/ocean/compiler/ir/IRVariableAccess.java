package ocean.compiler.ir;

/**
 * Değişken veya alan erişimini temsil eden IR düğümü.
 */
public class IRVariableAccess extends IRExpression {
    private String name;
    private boolean isField;
    private String owner;
    private boolean isStatic;
    private IRExpression receiver;
    private boolean isSafeAccess = false;
    private boolean isClassReference = false;
    private boolean isExplicitClassTarget = false;
    private String originalTypeDescriptor;

    public IRVariableAccess(String name, String typeDescriptor, boolean isField, String owner, boolean isStatic) {
        this.name = name;
        this.isField = isField;
        this.owner = owner;
        this.isStatic = isStatic;
        setTypeDescriptor(typeDescriptor);
        this.originalTypeDescriptor = typeDescriptor;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isField() { return isField; }
    public void setField(boolean field) { this.isField = field; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public boolean isStatic() { return isStatic; }
    public void setStatic(boolean isStatic) { this.isStatic = isStatic; }
    
    public IRExpression getReceiver() { return receiver; }
    public void setReceiver(IRExpression receiver) { this.receiver = receiver; }
    
    public boolean isSafeAccess() { return isSafeAccess; }
    public void setSafeAccess(boolean safeAccess) { isSafeAccess = safeAccess; }
    
    public boolean isClassReference() { return isClassReference; }
    public void setClassReference(boolean classReference) { isClassReference = classReference; }

    public boolean isExplicitClassTarget() { return isExplicitClassTarget; }
    public void setExplicitClassTarget(boolean explicitClassTarget) { this.isExplicitClassTarget = explicitClassTarget; }

    public String getOriginalTypeDescriptor() {
        return originalTypeDescriptor != null ? originalTypeDescriptor : getTypeDescriptor();
    }
    public void setOriginalTypeDescriptor(String originalTypeDescriptor) {
        this.originalTypeDescriptor = originalTypeDescriptor;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitVariableAccess(this);
    }
}
