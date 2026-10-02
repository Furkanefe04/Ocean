package ocean.compiler.symbol;

import ocean.compiler.CompilerRegistry;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents metadata for a declared method or constructor in an Ocean or external class.
 */
public class MethodSymbol implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String ownerInternalName;
    private final String name;
    private final String descriptor;
    private String genericReturnType;
    private int accessFlags;
    private boolean isStatic;
    private boolean isAsync;
    private final List<String> typeParameters = new ArrayList<>();
    private final List<String> thrownExceptions = new ArrayList<>();
    private final List<CompilerRegistry.MethodParamInfo> paramInfos = new ArrayList<>();

    public MethodSymbol(String ownerInternalName, String name, String descriptor) {
        this(ownerInternalName, name, descriptor, null, 0, false, false);
    }

    public MethodSymbol(String ownerInternalName, String name, String descriptor, String genericReturnType, int accessFlags, boolean isStatic, boolean isAsync) {
        this.ownerInternalName = ownerInternalName;
        this.name = name;
        this.descriptor = descriptor;
        this.genericReturnType = genericReturnType;
        this.accessFlags = accessFlags;
        this.isStatic = isStatic;
        this.isAsync = isAsync;
    }

    public String getOwnerInternalName() {
        return ownerInternalName;
    }

    public String getName() {
        return name;
    }

    public String getDescriptor() {
        return descriptor;
    }

    public String getGenericReturnType() {
        return genericReturnType;
    }

    public void setGenericReturnType(String genericReturnType) {
        this.genericReturnType = genericReturnType;
    }

    public int getAccessFlags() {
        return accessFlags;
    }

    public void setAccessFlags(int accessFlags) {
        this.accessFlags = accessFlags;
    }

    public boolean isAbstract() {
        return (accessFlags & org.objectweb.asm.Opcodes.ACC_ABSTRACT) != 0;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean isStatic) {
        this.isStatic = isStatic;
    }

    public boolean isAsync() {
        return isAsync;
    }

    public void setAsync(boolean isAsync) {
        this.isAsync = isAsync;
    }

    public List<String> getTypeParameters() {
        return typeParameters;
    }

    public void setTypeParameters(List<String> typeParams) {
        this.typeParameters.clear();
        if (typeParams != null) {
            this.typeParameters.addAll(typeParams);
        }
    }

    public List<String> getThrownExceptions() {
        return thrownExceptions;
    }

    public void setThrownExceptions(List<String> exceptions) {
        this.thrownExceptions.clear();
        if (exceptions != null) {
            this.thrownExceptions.addAll(exceptions);
        }
    }

    public List<CompilerRegistry.MethodParamInfo> getParamInfos() {
        return paramInfos;
    }

    public void setParamInfos(List<CompilerRegistry.MethodParamInfo> params) {
        this.paramInfos.clear();
        if (params != null) {
            this.paramInfos.addAll(params);
        }
    }

    @Override
    public String toString() {
        return "MethodSymbol{" +
                "owner='" + ownerInternalName + '\'' +
                ", name='" + name + '\'' +
                ", descriptor='" + descriptor + '\'' +
                ", static=" + isStatic +
                ", async=" + isAsync +
                '}';
    }
}