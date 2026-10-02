package ocean.compiler.ir;

import java.util.List;
/**
 * Numaralandırma (Enum) tanımlarını temsil eden IR düğümü.
 * Enum sabitlerini, alanlarını, metotlarını ve arayüz uygulamalarını içerir.
 */
public class IREnum extends IRNode {
    private final String name;
    private final List<String> constants;
    private final List<List<IRExpression>> constantArguments;
    private final List<IRField> fields;
    private final List<IRMethod> methods;
    private final List<String> interfaces;
    private final List<String> constantClassNames;

    public IREnum(String name, List<String> constants, List<List<IRExpression>> constantArguments, List<IRField> fields, List<IRMethod> methods) {
        this(name, constants, constantArguments, fields, methods, new java.util.ArrayList<>(), new java.util.ArrayList<>());
    }

    public IREnum(String name, List<String> constants, List<List<IRExpression>> constantArguments, List<IRField> fields, List<IRMethod> methods, List<String> interfaces) {
        this(name, constants, constantArguments, fields, methods, interfaces, new java.util.ArrayList<>());
    }

    public IREnum(String name, List<String> constants, List<List<IRExpression>> constantArguments, List<IRField> fields, List<IRMethod> methods, List<String> interfaces, List<String> constantClassNames) {
        this.name = name;
        this.constants = constants;
        this.constantArguments = constantArguments;
        this.fields = fields;
        this.methods = methods;
        this.interfaces = (interfaces != null) ? interfaces : new java.util.ArrayList<>();
        this.constantClassNames = (constantClassNames != null) ? constantClassNames : new java.util.ArrayList<>();
    }

    private final List<IRAnnotation> annotations = new java.util.ArrayList<>();

    public void addAnnotation(IRAnnotation annotation) { annotations.add(annotation); }
    public List<IRAnnotation> getAnnotations() { return annotations; }

    public String getName() { return name; }
    public List<String> getConstants() { return constants; }
    public List<List<IRExpression>> getConstantArguments() { return constantArguments; }
    public List<IRField> getFields() { return fields; }
    public List<IRMethod> getMethods() { return methods; }
    public List<String> getInterfaces() { return interfaces; }
    public List<String> getConstantClassNames() { return constantClassNames; }
    public String getConstantClass(int index) {
        if (constantClassNames != null && index >= 0 && index < constantClassNames.size()) {
            return constantClassNames.get(index);
        }
        return null;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitEnum(this);
    }
}
