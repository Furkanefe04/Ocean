package ocean.compiler.ir;

import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Bir sınıf alanını (field) temsil eden IR düğümü.
 */
public class IRField extends IRNode {
    private final String name;
    private final String typeDescriptor;
    private final boolean isStatic;
    private final IRExpression initialValue;
    private int accessFlags = Opcodes.ACC_PUBLIC;  // Default to public
    private final List<IRAnnotation> annotations = new ArrayList<>();

    public void addAnnotation(IRAnnotation annotation) { annotations.add(annotation); }
    public List<IRAnnotation> getAnnotations() { return annotations; }

    private String genericSignature;
    public String getGenericSignature() { return genericSignature; }
    public void setGenericSignature(String genericSignature) { this.genericSignature = genericSignature; }


    public int getAccessFlags() { return accessFlags; }
    public void setAccessFlags(int accessFlags) { this.accessFlags = accessFlags; }
    public boolean isFinal() { return (accessFlags & Opcodes.ACC_FINAL) != 0; }
    public void setFinal(boolean isFinal) {
        if (isFinal) accessFlags |= Opcodes.ACC_FINAL;
        else accessFlags &= ~Opcodes.ACC_FINAL;
    }

    public IRField(String name, String typeDescriptor, boolean isStatic, IRExpression initialValue) {
        this.name = name;
        this.typeDescriptor = typeDescriptor;
        this.isStatic = isStatic;
        this.initialValue = initialValue;
    }

    public String getName() { return name; }
    public String getTypeDescriptor() { return typeDescriptor; }
    public boolean isStatic() { return isStatic; }
    public IRExpression getInitialValue() { return initialValue; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitField(this);
    }
}
