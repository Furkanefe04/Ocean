package ocean.compiler.ir;

import java.util.List;

/**
 * Metot çağrılarını temsil eden IR düğümü.
 */
public class IRMethodCall extends IRExpression {
    private final String owner;
    private final String name;
    private String descriptor;
    private final List<IRExpression> arguments;
    private final boolean isStatic;
    private IRExpression receiver;
    private boolean isSafeAccess = false;
    private boolean isSuperCall = false;
    private boolean isThisCall = false;
    private boolean isSynthetic = false;
    private boolean isExplicitClassTarget = false;
    private String originalTypeDescriptor;

    public IRMethodCall(String owner, String name, String descriptor, List<IRExpression> arguments, boolean isStatic) {
        this.owner = owner;
        this.name = name;
        this.descriptor = descriptor;
        this.arguments = arguments;
        this.isStatic = isStatic;
        String retDesc = descriptor.substring(descriptor.lastIndexOf(')') + 1);
        setTypeDescriptor(retDesc);
        this.originalTypeDescriptor = retDesc;
    }

    public String getOwner() { return owner; }
    public String getName() { return name; }
    public String getDescriptor() { return descriptor; }
    public List<IRExpression> getArguments() { return arguments; }
    public boolean isStatic() { return isStatic; }

    public IRExpression getReceiver() { return receiver; }
    public void setReceiver(IRExpression receiver) { this.receiver = receiver; }
    
    public boolean isSafeAccess() { return isSafeAccess; }
    public void setSafeAccess(boolean safeAccess) { isSafeAccess = safeAccess; }

    public boolean isSuperCall() { return isSuperCall; }
    public void setSuperCall(boolean superCall) { isSuperCall = superCall; }

    public boolean isThisCall() { return isThisCall; }
    public void setThisCall(boolean thisCall) { isThisCall = thisCall; }

    public boolean isSynthetic() { return isSynthetic; }
    public void setSynthetic(boolean synthetic) { this.isSynthetic = synthetic; }

    public boolean isExplicitClassTarget() { return isExplicitClassTarget; }
    public void setExplicitClassTarget(boolean explicitClassTarget) { this.isExplicitClassTarget = explicitClassTarget; }

    public String getOriginalTypeDescriptor() {
        return originalTypeDescriptor != null ? originalTypeDescriptor : getTypeDescriptor();
    }
    public void setOriginalTypeDescriptor(String originalTypeDescriptor) {
        this.originalTypeDescriptor = originalTypeDescriptor;
    }

    public void setDescriptor(String descriptor) {
        this.descriptor = descriptor;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitMethodCall(this);
    }
}
