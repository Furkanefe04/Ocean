package ocean.compiler.symbol;

import ocean.compiler.CompilerRegistry;

import java.io.Serializable;
import java.util.*;

/**
 * Single source of truth representing all structural metadata for a class, interface, enum, or record.
 */
public class ClassSymbol implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String internalName;
    private final String simpleName;
    private final String packageName;

    private String superClassName = "java/lang/Object";
    private String superClassGenericSignature;
    private final List<String> interfaces = new ArrayList<>();
    private final List<String> interfaceGenericSignatures = new ArrayList<>();

    private int accessFlags;
    private boolean isAbstract;
    private boolean isInterface;
    private boolean isSealed;
    private boolean hasExplicitRestricts;
    private boolean isDataClass;
    private boolean isEnum;
    private boolean isRecord;
    private boolean isFunctionalInterface;
    private String outerClassName;
    private String subclassStatus;

    private final List<CompilerRegistry.TypeParameterInfo> typeParameters = new ArrayList<>();
    private final List<CompilerRegistry.RecordComponentInfo> recordComponents = new ArrayList<>();
    private final List<String> enumConstants = new ArrayList<>();
    private final List<String> permittedSubclasses = new ArrayList<>();
    private final List<CompilerRegistry.ExtensionMethodInfo> extensionMethods = new ArrayList<>();

    private final Map<String, FieldSymbol> fields = new LinkedHashMap<>();
    private final Map<String, List<MethodSymbol>> methods = new LinkedHashMap<>();

    public ClassSymbol(String internalName) {
        this.internalName = internalName != null ? internalName.replace('.', '/') : "";
        int lastSlash = this.internalName.lastIndexOf('/');
        if (lastSlash != -1) {
            this.packageName = this.internalName.substring(0, lastSlash);
            this.simpleName = this.internalName.substring(lastSlash + 1);
        } else {
            this.packageName = "";
            this.simpleName = this.internalName;
        }
    }

    public ClassSymbol(String internalName, String superClassName, int accessFlags) {
        this(internalName);
        if (superClassName != null) {
            this.superClassName = superClassName.replace('.', '/');
        }
        this.accessFlags = accessFlags;
    }

    public String getInternalName() {
        return internalName;
    }

    public String getSimpleName() {
        return simpleName;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getSuperClassName() {
        return superClassName;
    }

    public void setSuperClassName(String superClassName) {
        this.superClassName = superClassName != null ? superClassName.replace('.', '/') : "java/lang/Object";
    }

    public String getSuperClassGenericSignature() {
        return superClassGenericSignature;
    }

    public void setSuperClassGenericSignature(String signature) {
        this.superClassGenericSignature = signature;
    }

    public List<String> getInterfaces() {
        return interfaces;
    }

    public void setInterfaces(List<String> ifaces) {
        this.interfaces.clear();
        if (ifaces != null) {
            for (String i : ifaces) {
                this.interfaces.add(i.replace('.', '/'));
            }
        }
    }

    public void addInterface(String iface) {
        if (iface != null) {
            this.interfaces.add(iface.replace('.', '/'));
        }
    }

    public List<String> getInterfaceGenericSignatures() {
        return interfaceGenericSignatures;
    }

    public void setInterfaceGenericSignatures(List<String> sigs) {
        this.interfaceGenericSignatures.clear();
        if (sigs != null) {
            this.interfaceGenericSignatures.addAll(sigs);
        }
    }

    public int getAccessFlags() {
        return accessFlags;
    }

    public void setAccessFlags(int accessFlags) {
        this.accessFlags = accessFlags;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public void setAbstract(boolean anAbstract) {
        isAbstract = anAbstract;
    }

    public boolean isInterface() {
        return isInterface;
    }

    public void setInterface(boolean anInterface) {
        isInterface = anInterface;
    }

    public boolean isSealed() {
        return isSealed;
    }

    public void setSealed(boolean sealed) {
        isSealed = sealed;
    }

    public boolean hasExplicitRestricts() {
        return hasExplicitRestricts;
    }

    public void setHasExplicitRestricts(boolean hasExplicitRestricts) {
        this.hasExplicitRestricts = hasExplicitRestricts;
    }

    public boolean isDataClass() {
        return isDataClass;
    }

    public void setDataClass(boolean dataClass) {
        isDataClass = dataClass;
    }

    public boolean isEnum() {
        return isEnum;
    }

    public void setEnum(boolean anEnum) {
        isEnum = anEnum;
    }

    public boolean isRecord() {
        return isRecord;
    }

    public void setRecord(boolean record) {
        isRecord = record;
    }

    public boolean isFunctionalInterface() {
        return isFunctionalInterface;
    }

    public void setFunctionalInterface(boolean functionalInterface) {
        isFunctionalInterface = functionalInterface;
    }

    public String getOuterClassName() {
        return outerClassName;
    }

    public void setOuterClassName(String outerClassName) {
        this.outerClassName = outerClassName != null ? outerClassName.replace('.', '/') : null;
    }

    public String getSubclassStatus() {
        return subclassStatus;
    }

    public void setSubclassStatus(String subclassStatus) {
        this.subclassStatus = subclassStatus;
    }

    public List<CompilerRegistry.TypeParameterInfo> getTypeParameters() {
        return typeParameters;
    }

    public void setTypeParameters(List<CompilerRegistry.TypeParameterInfo> typeParams) {
        this.typeParameters.clear();
        if (typeParams != null) {
            this.typeParameters.addAll(typeParams);
        }
    }

    public List<CompilerRegistry.RecordComponentInfo> getRecordComponents() {
        return recordComponents;
    }

    public void setRecordComponents(List<CompilerRegistry.RecordComponentInfo> components) {
        this.recordComponents.clear();
        if (components != null) {
            this.recordComponents.addAll(components);
        }
    }

    public List<String> getEnumConstants() {
        return enumConstants;
    }

    public void setEnumConstants(List<String> constants) {
        this.enumConstants.clear();
        if (constants != null) {
            this.enumConstants.addAll(constants);
        }
    }

    public List<String> getPermittedSubclasses() {
        return permittedSubclasses;
    }

    public void setPermittedSubclasses(List<String> permitted) {
        this.permittedSubclasses.clear();
        if (permitted != null) {
            for (String p : permitted) {
                this.permittedSubclasses.add(p.replace('.', '/'));
            }
        }
    }

    public List<CompilerRegistry.ExtensionMethodInfo> getExtensionMethods() {
        return extensionMethods;
    }

    public void addExtensionMethod(CompilerRegistry.ExtensionMethodInfo info) {
        if (info != null) {
            this.extensionMethods.add(info);
        }
    }

    // ── Field operations ──────────────────────────────────────────────────────

    public synchronized void addField(FieldSymbol field) {
        if (field != null) {
            fields.put(field.getName(), field);
        }
    }

    public synchronized FieldSymbol getField(String name) {
        return fields.get(name);
    }

    public synchronized Map<String, FieldSymbol> getFields() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    // ── Method operations ─────────────────────────────────────────────────────

    public synchronized void addMethod(MethodSymbol method) {
        if (method != null) {
            List<MethodSymbol> overloads = methods.computeIfAbsent(method.getName(), k -> new ArrayList<>());
            // Overload with same descriptor replaces existing
            overloads.removeIf(m -> m.getDescriptor().equals(method.getDescriptor()));
            overloads.add(method);
        }
    }

    public synchronized List<MethodSymbol> getMethods(String name) {
        List<MethodSymbol> list = methods.get(name);
        if (list == null) return Collections.emptyList();
        return List.copyOf(list);
    }

    public synchronized MethodSymbol getMethod(String name, String descriptor) {
        List<MethodSymbol> list = methods.get(name);
        if (list == null) return null;
        for (MethodSymbol m : list) {
            if (m.getDescriptor().equals(descriptor)) {
                return m;
            }
        }
        return null;
    }

    public synchronized Map<String, List<MethodSymbol>> getMethods() {
        Map<String, List<MethodSymbol>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<MethodSymbol>> e : methods.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public String toString() {
        return "ClassSymbol{" +
                "name='" + internalName + '\'' +
                ", super='" + superClassName + '\'' +
                ", fields=" + fields.size() +
                ", methods=" + methods.size() +
                '}';
    }
}