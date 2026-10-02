package ocean.compiler.ir;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IR Node representing an annotation usage on a class, method, or field.
 */
public class IRAnnotation extends IRExpression {
    private final String typeDescriptor;
    private final Map<String, IRExpression> elements = new LinkedHashMap<>();

    public IRAnnotation(String typeDescriptor) {
        this.typeDescriptor = typeDescriptor;
        setTypeDescriptor(typeDescriptor);
    }

    @Override
    public String getTypeDescriptor() {
        return typeDescriptor;
    }

    public Map<String, IRExpression> getElements() {
        return elements;
    }

    public void addElement(String name, IRExpression value) {
        elements.put(name, value);
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitAnnotation(this);
    }
}
