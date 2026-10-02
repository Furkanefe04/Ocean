package ocean.compiler.ir;

import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Bir metodu temsil eden IR düğümü.
 */
public class IRMethod extends IRNode {
    private final String name;
    private final String descriptor;
    private final List<IRParameter> parameters = new ArrayList<>();
    private IRStatement body;
    private final boolean isStatic;
    private final boolean isAbstract;
    private boolean isAsync = false;
    private int accessFlags = Opcodes.ACC_PUBLIC; // Default to public
    private final List<String> exceptions = new ArrayList<>(); // JVM internal class names (e.g. "java/io/IOException")
    private final List<IRAnnotation> annotations = new ArrayList<>();
    private IRExpression defaultValue = null;

    public void addAnnotation(IRAnnotation annotation) { annotations.add(annotation); }
    public List<IRAnnotation> getAnnotations() { return annotations; }

    public IRExpression getDefaultValue() { return defaultValue; }
    public void setDefaultValue(IRExpression defaultValue) { this.defaultValue = defaultValue; }


    public int getAccessFlags() { return accessFlags; }
    public void setAccessFlags(int accessFlags) { this.accessFlags = accessFlags; }

    public IRMethod(String name, String descriptor, boolean isStatic, boolean isAbstract, IRStatement body) {
        this.name = name;
        this.descriptor = descriptor;
        this.isStatic = isStatic;
        this.isAbstract = isAbstract;
        this.body = body;
    }

    public String getName() { return name; }
    public String getDescriptor() { return descriptor; }
    public boolean isStatic() { return isStatic; }
    public boolean isAbstract() { return isAbstract; }
    public boolean isNative() { return (accessFlags & Opcodes.ACC_NATIVE) != 0; }
    public boolean isAsync() { return isAsync; }
    public void setAsync(boolean async) { this.isAsync = async; }
    public List<IRParameter> getParameters() { return parameters; }
    public IRStatement getBody() { return body; }
    public void setBody(IRStatement body) { this.body = body; }
    public List<String> getExceptions() { return exceptions; }
    public void addException(String exception) { exceptions.add(exception); }

    public void addParameter(IRParameter param) { parameters.add(param); }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitMethod(this);
    }

    public record IRParameter(String name, String typeDescriptor, List<IRAnnotation> annotations, boolean isFinal) {
        public IRParameter(String name, String typeDescriptor, List<IRAnnotation> annotations) {
            this(name, typeDescriptor, annotations, false);
        }
        public IRParameter(String name, String typeDescriptor) {
            this(name, typeDescriptor, java.util.Collections.emptyList(), false);
        }
        public IRParameter(String name, String typeDescriptor, boolean isFinal) {
            this(name, typeDescriptor, java.util.Collections.emptyList(), isFinal);
        }
    }
}
