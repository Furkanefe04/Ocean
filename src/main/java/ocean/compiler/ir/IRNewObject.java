package ocean.compiler.ir;

import ocean.compiler.OceanTypeSystem;

import java.util.List;
/**
 * Yeni bir nesne örneği oluşturulmasını (new ClassName(...)) temsil eden IR ifade düğümü.
 */
public class IRNewObject extends IRExpression {
    private final String className;
    private final String descriptor;
    private final List<IRExpression> arguments;

    public IRNewObject(String className, String descriptor, List<IRExpression> arguments) {
        this.className = className;
        this.descriptor = descriptor;
        this.arguments = arguments;
    }

    public String getClassName() { return className; }
    public String getDescriptor() { return descriptor; }
    public List<IRExpression> getArguments() { return arguments; }

    @Override
    public String getTypeDescriptor() {
        String custom = super.getTypeDescriptor();
        if (custom != null && !custom.isEmpty()) {
            return custom;
        }
        return OceanTypeSystem.wrapObjectType(className);
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitNewObject(this);
    }
}
