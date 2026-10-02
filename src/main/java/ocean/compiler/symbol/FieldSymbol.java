package ocean.compiler.symbol;

import java.io.Serializable;

/**
 * Represents metadata for a declared field in an Ocean or external class.
 */
public class FieldSymbol implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String ownerInternalName;
    private final String name;
    private final String descriptor;
    private String genericSignature;
    private int accessFlags;
    private boolean isStatic;
    private boolean isMutable; // true if variable/var, false if value/val/final

    public FieldSymbol(String ownerInternalName, String name, String descriptor) {
        this(ownerInternalName, name, descriptor, null, 0, false, true);
    }

    public FieldSymbol(String ownerInternalName, String name, String descriptor, String genericSignature, int accessFlags, boolean isStatic, boolean isMutable) {
        this.ownerInternalName = ownerInternalName;
        this.name = name;
        this.descriptor = descriptor;
        this.genericSignature = genericSignature;
        this.accessFlags = accessFlags;
        this.isStatic = isStatic;
        this.isMutable = isMutable;
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

    public String getGenericSignature() {
        return genericSignature;
    }

    public void setGenericSignature(String genericSignature) {
        this.genericSignature = genericSignature;
    }

    public int getAccessFlags() {
        return accessFlags;
    }

    public void setAccessFlags(int accessFlags) {
        this.accessFlags = accessFlags;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean isStatic) {
        this.isStatic = isStatic;
    }

    public boolean isMutable() {
        return isMutable;
    }

    public void setMutable(boolean isMutable) {
        this.isMutable = isMutable;
    }

    @Override
    public String toString() {
        return "FieldSymbol{" +
                "owner='" + ownerInternalName + '\'' +
                ", name='" + name + '\'' +
                ", descriptor='" + descriptor + '\'' +
                ", static=" + isStatic +
                ", mutable=" + isMutable +
                '}';
    }
}