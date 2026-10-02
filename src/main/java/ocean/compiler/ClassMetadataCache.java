package ocean.compiler;

import ocean.compiler.symbol.ClassSymbol;
import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance compilation-level cache for Java reflection metadata.
 * Eliminates repetitive Class.forName, isAssignableFrom, getMethods(), and getInterfaces()
 * lookups across compilation sessions.
 */
public final class ClassMetadataCache {

    private ClassMetadataCache() {}

    private static final Map<String, Boolean> isAssignableCache = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> isInterfaceCache = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> isSamCache = new ConcurrentHashMap<>();
    private static final Map<String, Optional<OceanTypeSystem.SAMMethodInfo>> samInfoCache = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> classHierarchyCache = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> classInterfacesCache = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> methodDescriptorsCache = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> constructorDescriptorsCache = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> isAnnotationCache = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> annotationTargetsCache = new ConcurrentHashMap<>();
    private static final Map<String, String> annotationRetentionCache = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> annotationRepeatableCache = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, CompilerRegistry.AnnotationMemberInfo>> annotationMembersCache = new ConcurrentHashMap<>();

    static {
        preloadCommonClasses();
    }

    /**
     * Checks if subName is a subtype of targetName via cached reflection lookup and compiler registry.
     */
    public static boolean isSubtype(String subName, String targetName) {
        if (subName == null || targetName == null) return false;
        if (subName.equals(targetName)) return true;
        if ("java/lang/Object".equals(targetName) || "java.lang.Object".equals(targetName) || OceanTypeSystem.OBJECT_DESC.equals(targetName)) return true;

        String subClean = cleanTypeName(subName);
        String supClean = cleanTypeName(targetName);
        if (subClean.equals(supClean)) return true;

        String subSimple = getSimpleName(subClean);
        String supSimple = getSimpleName(supClean);



        // 1. Check local Ocean compiler registries
        String curr = subClean;
        for (int i = 0; i < 20; i++) {
            ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(curr);
            String parent = (sym != null && sym.getSuperClassName() != null) ? sym.getSuperClassName() : CompilerRegistry.globalSuperClassRegistry.get(curr);
            if (parent != null) {
                String cleanParent = cleanTypeName(parent);
                if (cleanParent.equals(supClean) || (!cleanParent.contains("/") && !subClean.contains("/") && (getSimpleName(cleanParent).equals(supSimple)))) return true;
                curr = cleanParent;
            } else {
                break;
            }
        }

        Set<String> visited = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(subClean);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (!visited.add(node)) continue;
            ClassSymbol sym = CompilerRegistry.getClassSymbol(node);
            List<String> nodeIfaces = new ArrayList<>();
            if (sym != null) {
                nodeIfaces.addAll(sym.getInterfaces());
            }
            String[] ifaces = CompilerRegistry.globalInterfaceRegistry.get(node);
            if (ifaces != null) {
                Collections.addAll(nodeIfaces, ifaces);
            }
            for (String iface : nodeIfaces) {
                String cleanIface = cleanTypeName(iface);
                if (cleanIface.equals(supClean) || (!cleanIface.contains("/") && !supClean.contains("/")) && (getSimpleName(cleanIface).equals(supSimple))) return true;
                queue.add(cleanIface);
            }
            String parent = (sym != null && sym.getSuperClassName() != null) ? sym.getSuperClassName() : CompilerRegistry.globalSuperClassRegistry.get(node);
            if (parent != null) queue.add(cleanTypeName(parent));
        }

        // 2. Reflection lookup for JDK / external types
        String key = subClean + "->" + supClean;
        return isAssignableCache.computeIfAbsent(key, k -> {
            try {
                Class<?> subCls = OceanTypeSystem.forName(subClean.replace('/', '.'));
                Class<?> supCls = OceanTypeSystem.forName(supClean.replace('/', '.'));
                return subCls != null && supCls != null && supCls.isAssignableFrom(subCls);
            } catch (Throwable ignored) {
                return false;
            }
        });
    }

    /**
     * Checks if the given internal name is an interface.
     */
    public static boolean isInterface(String internalName) {
        if (internalName == null || internalName.isEmpty()) return false;
        String clean = cleanTypeName(internalName);
        ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(clean);
        if (sym != null) return sym.isInterface();
        if (CompilerRegistry.globalIsInterfaceSet.contains(clean)) return true;
        String simple = OceanTypeSystem.findSimpleName(clean);
        ocean.compiler.symbol.ClassSymbol simpleSym = CompilerRegistry.getClassSymbol(simple);
        if (simpleSym != null) return simpleSym.isInterface();
        if (CompilerRegistry.globalIsInterfaceSet.contains(simple)) return true;
        return isInterfaceCache.computeIfAbsent(clean, k -> {
            try {
                Class<?> cls = OceanTypeSystem.forName(clean.replace('/', '.'));
                return cls != null && cls.isInterface();
            } catch (Throwable ignored) {
                return false;
            }
        });
    }

    /**
     * Checks if the given internal name is a final class.
     */
    public static boolean isFinal(String internalName) {
        if (internalName == null || internalName.isEmpty()) return false;
        String clean = cleanTypeName(internalName);
        if ("final".equals(CompilerRegistry.globalSubclassStatusRegistry.get(clean))) return true;
        String simple = OceanTypeSystem.findSimpleName(clean);
        if ("final".equals(CompilerRegistry.globalSubclassStatusRegistry.get(simple))) return true;
        Integer access = CompilerRegistry.globalClassAccess.get(clean);
        if (access != null && (access & Opcodes.ACC_FINAL) != 0) return true;
        Integer simpleAccess = CompilerRegistry.globalClassAccess.get(simple);
        if (simpleAccess != null && (simpleAccess & Opcodes.ACC_FINAL) != 0) return true;
        try {
            Class<?> cls = OceanTypeSystem.forName(clean.replace('/', '.'));
            return cls != null && Modifier.isFinal(cls.getModifiers());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Checks if the given internal name is a class (not an interface, not a primitive, not an array).
     */
    public static boolean isClass(String internalName) {
        if (internalName == null || internalName.isEmpty()) return false;
        if (isInterface(internalName)) return false;
        String clean = cleanTypeName(internalName);
        ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(clean);
        if (sym != null) return !sym.isInterface();
        if (CompilerRegistry.globalSuperClassRegistry.containsKey(clean) || CompilerRegistry.globalMethodRegistry.containsKey(clean)) {
            return true;
        }
        String simple = OceanTypeSystem.findSimpleName(clean);
        ocean.compiler.symbol.ClassSymbol simpleSym = CompilerRegistry.getClassSymbol(simple);
        if (simpleSym != null) return !simpleSym.isInterface();
        if (CompilerRegistry.globalSuperClassRegistry.containsKey(simple) || CompilerRegistry.globalMethodRegistry.containsKey(simple)) {
            return true;
        }
        if (CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(clean) || CompilerRegistry.globalInnerClassUsedOuterMap.containsKey(simple)) {
            return true;
        }
        try {
            Class<?> cls = OceanTypeSystem.forName(clean.replace('/', '.'));
            return cls != null && !cls.isInterface() && !cls.isPrimitive() && !cls.isArray();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Checks if the given internal class name is a Single Abstract Method (SAM) functional interface.
     */
    public static boolean hasSingleAbstractMethod(String internalName) {
        if (internalName == null || internalName.isEmpty()) return false;
        String clean = cleanTypeName(internalName);
        return isSamCache.computeIfAbsent(clean, k -> {
            OceanTypeSystem.SAMMethodInfo info = getSingleAbstractMethodInfo(clean);
            return info != null;
        });
    }

    /**
     * Retrieves the single abstract method info for a functional interface.
     */
    public static OceanTypeSystem.SAMMethodInfo getSingleAbstractMethodInfo(String internalName) {
        if (internalName == null || internalName.isEmpty()) return null;
        String clean = cleanTypeName(internalName);
        return samInfoCache.computeIfAbsent(clean, k -> {
            Map<String, String> absMethods = collectAbstractInterfaceMethods(clean, new HashSet<>());
            if (absMethods.size() == 1) {
                Map.Entry<String, String> single = absMethods.entrySet().iterator().next();
                String desc = single.getValue();
                String name = single.getKey().substring(0, single.getKey().indexOf('('));
                return Optional.of(new OceanTypeSystem.SAMMethodInfo(name, desc));
            }
            return Optional.empty();
        }).orElse(null);
    }

    public static Map<String, String> collectAbstractInterfaceMethods(String interfaceFqcn, Set<String> visited) {
        if (interfaceFqcn == null || !visited.add(interfaceFqcn)) return Collections.emptyMap();
        Map<String, String> abstractMethods = new LinkedHashMap<>();

        // 1. If it's a local Ocean interface:
        if (CompilerRegistry.globalIsInterfaceSet.contains(interfaceFqcn)) {
            // First collect from extended super-interfaces
            String[] superInterfaces = CompilerRegistry.globalInterfaceRegistry.get(interfaceFqcn);
            if (superInterfaces != null) {
                for (String superIface : superInterfaces) {
                    abstractMethods.putAll(collectAbstractInterfaceMethods(superIface, visited));
                }
            }

            Map<String, String> declaredMethods = CompilerRegistry.globalMethodRegistry.get(interfaceFqcn);
            Map<String, Integer> methodAccess = CompilerRegistry.globalMethodAccess.get(interfaceFqcn);
            Map<String, Boolean> methodStat = CompilerRegistry.globalMethodStaticity.get(interfaceFqcn);

            if (declaredMethods != null) {
                for (Map.Entry<String, String> entry : declaredMethods.entrySet()) {
                    String name = entry.getKey();
                    String desc = entry.getValue();
                    if (name.equals("<init>") || name.equals("<clinit>")) continue;

                    // Skip Object methods
                    if (isObjectPublicMethod(name, desc)) continue;

                    boolean isStat = methodStat != null && (Boolean.TRUE.equals(methodStat.get(name + desc)) || Boolean.TRUE.equals(methodStat.get(name)));
                    int acc = methodAccess != null ? methodAccess.getOrDefault(name + desc, methodAccess.getOrDefault(name, 0)) : 0;
                    boolean isPrivate = (acc & Opcodes.ACC_PRIVATE) != 0;
                    boolean isDefault = (acc & Opcodes.ACC_ABSTRACT) == 0 && !isStat && !isPrivate;

                    String methodKey = name + desc;
                    if (isStat || isPrivate) {
                        continue;
                    }
                    if (isDefault) {
                        abstractMethods.remove(methodKey);
                    } else {
                        abstractMethods.put(methodKey, desc);
                    }
                }
            }
        } else {
            // 2. Classpath / Reflection interface
            try {
                Class<?> cls = OceanTypeSystem.forName(interfaceFqcn.replace('/', '.'));
                if (cls != null && cls.isInterface()) {
                    for (Method m : cls.getMethods()) {
                        if (Modifier.isAbstract(m.getModifiers())) {
                            try {
                                Object.class.getMethod(m.getName(), m.getParameterTypes());
                            } catch (NoSuchMethodException ignored) {
                                abstractMethods.put(m.getName() + Type.getMethodDescriptor(m), Type.getMethodDescriptor(m));
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return abstractMethods;
    }

    private static boolean isObjectPublicMethod(String name, String desc) {
        if ("equals".equals(name) && "(Ljava/lang/Object;)Z".equals(desc)) return true;
        if ("hashCode".equals(name) && "()I".equals(desc)) return true;
        return "toString".equals(name) && "()Ljava/lang/String;".equals(desc);
    }

    /**
     * Retrieves the superclass chain of a class (excluding interfaces).
     */
    public static List<String> getClassHierarchy(String internalName) {
        if (internalName == null || internalName.isEmpty()) return Collections.emptyList();
        String clean = cleanTypeName(internalName);
        return classHierarchyCache.computeIfAbsent(clean, k -> {
            List<String> hierarchy = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            String current = clean;

            while (current != null && visited.add(current)) {
                hierarchy.add(current);
                ocean.compiler.symbol.ClassSymbol classSym = CompilerRegistry.getClassSymbol(current);
                String next = classSym != null ? classSym.getSuperClassName() : CompilerRegistry.globalSuperClassRegistry.get(current);
                if (next != null) {
                    current = cleanTypeName(next);
                } else if (!current.equals("java/lang/Object")) {
                    try {
                        Class<?> clazz = OceanTypeSystem.forName(current.replace('/', '.'));
                        Class<?> superClazz = clazz.getSuperclass();
                        if (superClazz != null) {
                            current = superClazz.getName().replace('.', '/');
                        } else {
                            current = "java/lang/Object";
                        }
                    } catch (Throwable t) {
                        current = "java/lang/Object";
                    }
                } else {
                    current = null;
                }
            }
            return Collections.unmodifiableList(hierarchy);
        });
    }

    /**
     * Retrieves all implemented interfaces for a class or interface.
     */
    public static List<String> getClassInterfaces(String internalName) {
        if (internalName == null || internalName.isEmpty()) return Collections.emptyList();
        String clean = cleanTypeName(internalName);
        return classInterfacesCache.computeIfAbsent(clean, k -> {
            List<String> ifaces = new ArrayList<>();
            ocean.compiler.symbol.ClassSymbol classSym = CompilerRegistry.getClassSymbol(clean);
            if (classSym != null) {
                ifaces.addAll(classSym.getInterfaces());
            } else {
                String[] regIfaces = CompilerRegistry.globalInterfaceRegistry.get(clean);
                if (regIfaces != null) {
                    Collections.addAll(ifaces, regIfaces);
                }
            }
            if (ifaces.isEmpty()) {
                try {
                    Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
                    for (Class<?> iface : clazz.getInterfaces()) {
                        ifaces.add(iface.getName().replace('.', '/'));
                    }
                } catch (Throwable ignored) {}
            }
            return Collections.unmodifiableList(ifaces);
        });
    }

    /**
     * Retrieves public method descriptors for class#methodName.
     */
    public static List<String> getPublicMethodDescriptors(String internalName, String methodName) {
        if (internalName == null || methodName == null) return Collections.emptyList();
        String clean = cleanTypeName(internalName);
        String key = clean + "#" + methodName;
        return methodDescriptorsCache.computeIfAbsent(key, k -> {
            List<String> descriptors = new ArrayList<>();
            try {
                Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
                for (Method m : clazz.getMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;
                    int mods = m.getModifiers();
                    if (Modifier.isPrivate(mods)) continue;
                    if (m.getName().equals(methodName)) {
                        descriptors.add(TypeChecker.cleanDescriptor(Type.getMethodDescriptor(m)));
                    }
                }
            } catch (Throwable ignored) {}
            return Collections.unmodifiableList(descriptors);
        });
    }

    /**
     * Retrieves constructor descriptors for a class.
     */
    public static List<String> getConstructorDescriptors(String internalName) {
        if (internalName == null) return Collections.emptyList();
        String clean = cleanTypeName(internalName);
        return constructorDescriptorsCache.computeIfAbsent(clean, k -> {
            List<String> descriptors = new ArrayList<>();
            try {
                Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
                for (Constructor<?> c : clazz.getConstructors()) {
                    StringBuilder sb = new StringBuilder("(");
                    for (Class<?> pType : c.getParameterTypes()) {
                        sb.append(Type.getDescriptor(pType));
                    }
                    sb.append(")V");
                    descriptors.add(TypeChecker.cleanDescriptor(sb.toString()));
                }
            } catch (Throwable ignored) {}
            return Collections.unmodifiableList(descriptors);
        });
    }

    private static final Map<String, Boolean> isVarargsMethodCache = new ConcurrentHashMap<>();

    /**
     * Checks if a reflected Java method or constructor is varargs (ACC_VARARGS).
     */
    public static boolean isVarargsMethod(String internalName, String methodName, String descriptor) {
        if (internalName == null || methodName == null || descriptor == null) return false;
        String clean = cleanTypeName(internalName);
        String cleanDesc = TypeChecker.cleanDescriptor(descriptor);
        String key = clean + "#" + methodName + "#" + cleanDesc;
        return isVarargsMethodCache.computeIfAbsent(key, k -> {
            try {
                Class<?> clazz = OceanTypeSystem.forName(clean.replace('/', '.'));
                if (clazz == null) return false;
                if ("<init>".equals(methodName)) {
                    for (Constructor<?> c : clazz.getDeclaredConstructors()) {
                        StringBuilder sb = new StringBuilder("(");
                        for (Class<?> pType : c.getParameterTypes()) {
                            sb.append(Type.getDescriptor(pType));
                        }
                        sb.append(")V");
                        if (TypeChecker.cleanDescriptor(sb.toString()).equals(cleanDesc)) {
                            return c.isVarArgs();
                        }
                    }
                } else {
                    for (Method m : clazz.getMethods()) {
                        if (m.getName().equals(methodName)) {
                            if (TypeChecker.cleanDescriptor(Type.getMethodDescriptor(m)).equals(cleanDesc)) {
                                return m.isVarArgs();
                            }
                        }
                    }
                    for (Method m : clazz.getDeclaredMethods()) {
                        if (m.getName().equals(methodName)) {
                            if (TypeChecker.cleanDescriptor(Type.getMethodDescriptor(m)).equals(cleanDesc)) {
                                return m.isVarArgs();
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return false;
        });
    }

    private static final Map<String, Boolean> isSealedCache = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> permittedSubclassesCache = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> enumConstantsCache = new ConcurrentHashMap<>();

    /**
     * Checks if the given internal class/interface name is sealed.
     */
    public static boolean isSealed(String internalName) {
        if (internalName == null || internalName.isEmpty()) return false;
        String clean = cleanTypeName(internalName);
        if (CompilerRegistry.globalSealedClassSet.contains(clean)) return true;
        return isSealedCache.computeIfAbsent(clean, k -> {
            try {
                Class<?> cls = OceanTypeSystem.forName(clean.replace('/', '.'));
                if (cls != null && cls.isSealed()) {
                    return true;
                }
            } catch (Throwable ignored) {}
            return false;
        });
    }

    /**
     * Retrieves the list of permitted subclass internal names for a sealed class/interface.
     */
    public static List<String> getPermittedSubclasses(String internalName) {
        if (internalName == null || internalName.isEmpty()) return Collections.emptyList();
        String clean = cleanTypeName(internalName);
        List<String> localPermitted = CompilerRegistry.globalPermittedSubclassesRegistry.get(clean);
        if (localPermitted != null && !localPermitted.isEmpty()) {
            return localPermitted;
        }
        return permittedSubclassesCache.computeIfAbsent(clean, k -> {
            List<String> list = new ArrayList<>();
            try {
                Class<?> cls = OceanTypeSystem.forName(clean.replace('/', '.'));
                if (cls != null && cls.isSealed()) {
                    Class<?>[] permitted = cls.getPermittedSubclasses();
                    if (permitted != null) {
                        for (Class<?> sub : permitted) {
                            list.add(sub.getName().replace('.', '/'));
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return Collections.unmodifiableList(list);
        });
    }

    /**
     * Retrieves the list of enum constant names for an enum type.
     */
    public static List<String> getEnumConstants(String internalName) {
        if (internalName == null || internalName.isEmpty()) return Collections.emptyList();
        String clean = cleanTypeName(internalName);
        List<String> localConstants = CompilerRegistry.globalEnumConstants.get(clean);
        if (localConstants == null || localConstants.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : CompilerRegistry.globalEnumConstants.entrySet()) {
                if (entry.getKey().endsWith("/" + clean) || entry.getKey().equals(clean)) {
                    localConstants = entry.getValue();
                    break;
                }
            }
        }
        if (localConstants != null && !localConstants.isEmpty()) {
            return localConstants;
        }
        return enumConstantsCache.computeIfAbsent(clean, k -> {
            List<String> list = new ArrayList<>();
            try {
                Class<?> cls = OceanTypeSystem.forName(clean.replace('/', '.'));
                if (cls != null && cls.isEnum()) {
                    Object[] constants = cls.getEnumConstants();
                    if (constants != null) {
                        for (Object c : constants) {
                            list.add(((Enum<?>) c).name());
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return Collections.unmodifiableList(list);
        });
    }

    /**
     * Checks if a class is an annotation type (@interface / annotation).
     */
    public static boolean isAnnotation(String internalName) {
        if (internalName == null || internalName.isEmpty()) return false;
        String clean = cleanTypeName(internalName);
        if (CompilerRegistry.globalClassAccess.containsKey(clean)) {
            int access = CompilerRegistry.globalClassAccess.get(clean);
            return (access & org.objectweb.asm.Opcodes.ACC_ANNOTATION) != 0;
        }
        if (CompilerRegistry.globalAnnotationTypeRegistry.containsKey(clean)) return true;
        String simple = getSimpleName(clean);
        if (CompilerRegistry.globalAnnotationTypeRegistry.containsKey(simple)) return true;
        if (CompilerRegistry.globalClassAccess.containsKey(simple)) {
            int access = CompilerRegistry.globalClassAccess.get(simple);
            return (access & org.objectweb.asm.Opcodes.ACC_ANNOTATION) != 0;
        }
        return isAnnotationCache.computeIfAbsent(clean, k -> {
            try {
                Class<?> cls = OceanTypeSystem.forName(clean.replace('/', '.'));
                return cls != null && cls.isAnnotation();
            } catch (Throwable ignored) {
                return false;
            }
        });
    }

    /**
     * Retrieves allowed ElementType target names (e.g. "TYPE", "METHOD", "FIELD", "CONSTRUCTOR", "PARAMETER") for an annotation.
     * Returns empty set if no @Target is defined (permitted on all targets).
     */
    public static Set<String> getAnnotationTargetTypes(String internalName) {
        if (internalName == null || internalName.isEmpty()) return Collections.emptySet();
        String clean = cleanTypeName(internalName);
        CompilerRegistry.AnnotationTypeInfo info = CompilerRegistry.globalAnnotationTypeRegistry.get(clean);
        if (info != null && info.targets() != null && !info.targets().isEmpty()) {
            return info.targets();
        }
        return annotationTargetsCache.computeIfAbsent(clean, k -> {
            Set<String> targets = new HashSet<>();
            try {
                Class<?> cls = OceanTypeSystem.forName(clean.replace('/', '.'));
                if (cls != null && cls.isAnnotation()) {
                    java.lang.annotation.Target target = cls.getAnnotation(java.lang.annotation.Target.class);
                    if (target != null) {
                        for (java.lang.annotation.ElementType et : target.value()) {
                            targets.add(et.name());
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return Collections.unmodifiableSet(targets);
        });
    }

    /**
     * Retrieves the retention policy ("RUNTIME", "CLASS", "SOURCE") for an annotation.
     */
    public static String getAnnotationRetention(String internalName) {
        if (internalName == null || internalName.isEmpty()) return "RUNTIME";
        String clean = cleanTypeName(internalName);
        CompilerRegistry.AnnotationTypeInfo info = CompilerRegistry.globalAnnotationTypeRegistry.get(clean);
        if (info != null && info.retention() != null && !info.retention().isEmpty()) {
            return info.retention();
        }
        return annotationRetentionCache.computeIfAbsent(clean, k -> {
            try {
                Class<?> cls = OceanTypeSystem.forName(clean.replace('/', '.'));
                if (cls != null && cls.isAnnotation()) {
                    java.lang.annotation.Retention ret = cls.getAnnotation(java.lang.annotation.Retention.class);
                    if (ret != null && ret.value() != null) {
                        return ret.value().name();
                    }
                }
            } catch (Throwable ignored) {}
            return "RUNTIME";
        });
    }

    /**
     * Checks if an annotation is marked with @Repeatable.
     */
    public static boolean isAnnotationRepeatable(String internalName) {
        if (internalName == null || internalName.isEmpty()) return false;
        String clean = cleanTypeName(internalName);
        CompilerRegistry.AnnotationTypeInfo info = CompilerRegistry.globalAnnotationTypeRegistry.get(clean);
        if (info != null) {
            return info.repeatable();
        }
        return annotationRepeatableCache.computeIfAbsent(clean, k -> {
            try {
                Class<?> cls = OceanTypeSystem.forName(clean.replace('/', '.'));
                if (cls != null && cls.isAnnotation()) {
                    return cls.isAnnotationPresent(java.lang.annotation.Repeatable.class);
                }
            } catch (Throwable ignored) {}
            return false;
        });
    }

    /**
     * Retrieves member metadata (return type descriptor and default availability) for an annotation.
     */
    public static Map<String, CompilerRegistry.AnnotationMemberInfo> getAnnotationMembers(String internalName) {
        if (internalName == null || internalName.isEmpty()) return Collections.emptyMap();
        String clean = cleanTypeName(internalName);
        CompilerRegistry.AnnotationTypeInfo info = CompilerRegistry.globalAnnotationTypeRegistry.get(clean);
        if (info != null && info.members() != null && !info.members().isEmpty()) {
            return info.members();
        }
        return annotationMembersCache.computeIfAbsent(clean, k -> {
            Map<String, CompilerRegistry.AnnotationMemberInfo> members = new LinkedHashMap<>();
            try {
                Class<?> cls = OceanTypeSystem.forName(clean.replace('/', '.'));
                if (cls != null && cls.isAnnotation()) {
                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers())) {
                            String retDesc = Type.getReturnType(m).getDescriptor();
                            boolean hasDefault = m.getDefaultValue() != null;
                            members.put(m.getName(), new CompilerRegistry.AnnotationMemberInfo(m.getName(), retDesc, hasDefault));
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return Collections.unmodifiableMap(members);
        });
    }

    private static String getSimpleName(String internalName) {
        if (internalName == null) return "";
        int slash = internalName.lastIndexOf('/');
        if (slash >= 0) return internalName.substring(slash + 1);
        int dot = internalName.lastIndexOf('.');
        if (dot >= 0) return internalName.substring(dot + 1);
        return internalName;
    }

    public static void clearCaches() {
        isAssignableCache.clear();
        isInterfaceCache.clear();
        isSamCache.clear();
        samInfoCache.clear();
        classHierarchyCache.clear();
        classInterfacesCache.clear();
        methodDescriptorsCache.clear();
        constructorDescriptorsCache.clear();
        isSealedCache.clear();
        permittedSubclassesCache.clear();
        enumConstantsCache.clear();
        isAnnotationCache.clear();
        annotationTargetsCache.clear();
        annotationRetentionCache.clear();
        annotationRepeatableCache.clear();
        annotationMembersCache.clear();
        isVarargsMethodCache.clear();
        preloadCommonClasses();
    }

    public static String cleanTypeName(String type) {
        if (type == null) return "";
        String s = type.trim();
        if (s.startsWith("L") && s.endsWith(";")) {
            s = s.substring(1, s.length() - 1);
        }
        int genericIdx = s.indexOf('<');
        if (genericIdx >= 0) {
            s = s.substring(0, genericIdx);
        }
        return s.replace('.', '/');
    }

    private static void preloadCommonClasses() {
        String[] common = {
            "java/lang/Object", "java/lang/String", "java/lang/Number",
            "java/lang/Integer", "java/lang/Long", "java/lang/Double",
            "java/lang/Float", "java/lang/Boolean", "java/lang/Byte",
            "java/lang/Short", "java/lang/Character", "java/lang/CharSequence",
            "java/lang/Math", "java/lang/System", "java/lang/Throwable",
            "java/lang/Exception", "java/lang/RuntimeException",
            "java/util/List", "java/util/ArrayList", "java/util/Map",
            "java/util/HashMap", "java/util/Set", "java/util/HashSet",
            "java/util/Collection", "java/util/Collections", "java/util/Arrays",
            "java/util/concurrent/CompletableFuture", "java/util/function/Function",
            "java/util/function/Consumer", "java/util/function/Supplier",
            "java/util/function/Predicate", "java/util/function/BiFunction"
        };
        for (String c : common) {
            isInterface(c);
            getClassHierarchy(c);
            getClassInterfaces(c);
        }
    }

    public static boolean isSystemClass(String className) {
        return className.startsWith("java/") || className.startsWith("javax/") ||
                className.startsWith("sun/") || className.startsWith("ocean/stdlib/") ||
                className.startsWith("jakarta/") || className.startsWith("jdk/");
    }

    public static boolean isOceanStdlibClass(String classKey) {
        return classKey.startsWith("ocean/stdlib/");
    }
}