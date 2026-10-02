package ocean.compiler.ir;

import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Bir sınıfı temsil eden üst düzey IR düğümü.
 */
public class IRClass extends IRNode {
    private final String name;
    private final String superName;
    private final boolean isAbstract;
    private final List<String> interfaces = new ArrayList<>();
    private final List<IRField> fields = new ArrayList<>();
    private final List<IRMethod> methods = new ArrayList<>();
    private final List<IRBlock> staticBlocks = new ArrayList<>();
    private final List<IRBlock> instanceBlocks = new ArrayList<>();
    private final List<IRNode> instanceInitializers = new ArrayList<>();
    private final List<IRAnnotation> annotations = new ArrayList<>();
    private final List<String> permittedSubclasses = new ArrayList<>();
    private boolean isSealed = false;
    private boolean isDataClass = false;
    private boolean isNonSealed = false;
    private int accessFlags = Opcodes.ACC_PUBLIC;  // Default to public
    private String enclosingClass = null;
    private String enclosingMethodName = null;
    private String enclosingMethodDesc = null;
    private boolean isAnonymous = false;
    private boolean isLocal = false;

    public String getEnclosingClass() { return enclosingClass; }
    public void setEnclosingClass(String enclosingClass) { this.enclosingClass = enclosingClass; }
    public String getEnclosingMethodName() { return enclosingMethodName; }
    public String getEnclosingMethodDesc() { return enclosingMethodDesc; }
    public void setEnclosingMethod(String enclosingClass, String name, String desc) {
        this.enclosingClass = enclosingClass;
        this.enclosingMethodName = name;
        this.enclosingMethodDesc = desc;
    }
    public boolean isAnonymous() { return isAnonymous; }
    public void setAnonymous(boolean anonymous) { this.isAnonymous = anonymous; }
    public boolean isLocal() { return isLocal; }
    public void setLocal(boolean local) { this.isLocal = local; }

    public void addAnnotation(IRAnnotation annotation) { annotations.add(annotation); }
    public List<IRAnnotation> getAnnotations() { return annotations; }

    public boolean isInlineValueClass() {
        if (fields.size() != 1) return false;
        for (IRAnnotation anno : annotations) {
            String desc = anno.getTypeDescriptor();
            if (desc != null && (desc.equalsIgnoreCase("Inline") || desc.equalsIgnoreCase("LInline;") || desc.equalsIgnoreCase("@Inline") || desc.endsWith("/Inline;"))) {
                return true;
            }
        }
        return false;
    }

    public IRField getInlineField() {
        if (isInlineValueClass()) {
            return fields.getFirst();
        }
        return null;
    }

    public boolean isDataClass() {return isDataClass;}
    public boolean isSealed() { return isSealed; }
    public void setDataClass(boolean isDataClass) {this.isDataClass = isDataClass;}
    public void setSealed(boolean isSealed) { this.isSealed = isSealed; }
    public boolean isNonSealed() { return isNonSealed; }
    public void setNonSealed(boolean isNonSealed) { this.isNonSealed = isNonSealed; }
    public List<String> getPermittedSubclasses() { return permittedSubclasses; }
    public void addPermittedSubclass(String subclass) { permittedSubclasses.add(subclass); }

    public int getAccessFlags() { return accessFlags; }
    public void setAccessFlags(int accessFlags) { this.accessFlags = accessFlags; }

    public IRClass(String name, String superName, boolean isAbstract) {
        this.name = name;
        this.superName = superName != null ? superName : "java/lang/Object";
        this.isAbstract = isAbstract;
    }

    public IRClass(String name, String superName, List<String> interfaces, boolean isAbstract) {
        this.name = name;
        this.superName = superName != null ? superName : "java/lang/Object";
        this.isAbstract = isAbstract;
        if (interfaces != null) this.interfaces.addAll(interfaces);
    }

    public String getName() { return name; }
    public String getSuperName() { return superName; }
    public boolean isAbstract() { return isAbstract; }
    public List<String> getInterfaces() { return interfaces; }
    public List<IRField> getFields() { return fields; }
    public List<IRMethod> getMethods() { return methods; }

    public void addField(IRField field) { fields.add(field); }
    public void addMethod(IRMethod method) { 
        methods.add(method); 
    }
    public List<IRBlock> getStaticBlocks() { return staticBlocks; }
    public void addStaticBlock(IRBlock block) { staticBlocks.add(block); }
    public List<IRBlock> getInstanceBlocks() { return instanceBlocks; }
    public void addInstanceBlock(IRBlock block) { instanceBlocks.add(block); }
    public List<IRNode> getInstanceInitializers() { return instanceInitializers; }
    public void addInstanceInitializer(IRNode node) { instanceInitializers.add(node); }
    private final List<IRNode> staticInitializers = new ArrayList<>();
    public List<IRNode> getStaticInitializers() { return staticInitializers; }
    public void addStaticInitializer(IRNode node) { staticInitializers.add(node); }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitClass(this);
    }
}
