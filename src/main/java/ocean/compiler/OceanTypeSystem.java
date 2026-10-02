package ocean.compiler;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ocean dilinin tip sistemini (built-in tipler, ilkel türler, tip takma adları,
 * boxing/unboxing ve tip sorgulamaları) tek bir merkezden yöneten sınıf.
 */
public final class OceanTypeSystem {
    public static final String OBJECT_DESC = "Ljava/lang/Object;";
    public static final String STRING_DESC = "Ljava/lang/String;";
    public static final String NUMBER_DESC = "Ljava/lang/Number;";
    public static final String BIGDECIMAL_DESC = "Ljava/math/BigDecimal;";
    private OceanTypeSystem() {}

    private static final Map<String, String> standardClassPathCache = new ConcurrentHashMap<>();
    private static final Set<String> negativeStandardClassPathCache = ConcurrentHashMap.newKeySet();
    private static final Map<String, Class<?>> classForNameCache = new ConcurrentHashMap<>();
    private static final Set<String> negativeClassForNameCache = ConcurrentHashMap.newKeySet();

    public static void clearCaches() {
        standardClassPathCache.clear();
        negativeStandardClassPathCache.clear();
        classForNameCache.clear();
        negativeClassForNameCache.clear();
    }

    public static void removeNegativeCacheEntry(String fqcn) {
        if (fqcn != null) {
            negativeClassForNameCache.remove(fqcn);
            negativeClassForNameCache.remove(fqcn.replace('/', '.'));
            String simpleName = fqcn.contains("/") ? fqcn.substring(fqcn.lastIndexOf('/') + 1) : fqcn;
            negativeClassForNameCache.remove(simpleName);
            negativeStandardClassPathCache.remove(simpleName);
        }
    }

    public static String resolveStandardClassPath(String id) {
        if (id == null || id.isEmpty() || !Character.isUpperCase(id.charAt(0))) return null;
        if (negativeStandardClassPathCache.contains(id)) return null;
        String cached = standardClassPathCache.get(id);
        if (cached != null) {
            return cached;
        }

        try {
            Class<?> c = forName("java.lang." + id);
            if (c != null && c.getSimpleName().equals(id)) {
                String path = "java/lang/" + id;
                standardClassPathCache.put(id, path);
                return path;
            }
        } catch (Throwable ignored) {}

        try {
            Class<?> c = forName("ocean.stdlib." + id);
            if (c != null && c.getSimpleName().equals(id)) {
                String path = "ocean/stdlib/" + id;
                standardClassPathCache.put(id, path);
                return path;
            }
        } catch (Throwable ignored) {}

        negativeStandardClassPathCache.add(id);
        return null;
    }

    public static boolean hasClass(String name) {
        if (name == null || name.isEmpty()) return false;
        String dotName = name.replace('/', '.');
        if (negativeClassForNameCache.contains(dotName)) return false;
        if (classForNameCache.containsKey(dotName)) return true;

        if (!dotName.startsWith("java.") && !dotName.startsWith("javax.") && !dotName.startsWith("sun.")) {
            String resourcePath = dotName.replace('.', '/') + ".class";
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = OceanTypeSystem.class.getClassLoader();
            boolean found = cl.getResource(resourcePath) != null
                    || OceanTypeSystem.class.getClassLoader().getResource(resourcePath) != null;
            if (!found) {
                negativeClassForNameCache.add(dotName);
                return false;
            }
        }

        try {
            return forName(dotName) != null;
        } catch (Throwable ignored) {
            negativeClassForNameCache.add(dotName);
            return false;
        }
    }

    /**
     * Resolves an unqualified or qualified class name to its JVM internal name (e.g. "com/app/MyClass").
     * Avoids blindly prepending currentClassName's package if the class is actually in the default package,
     * imported via wildcards, or registered in CompilerRegistry.
     */
    public static String resolveInternalClassName(String name, String currentClassName) {
        if (name == null || name.isEmpty()) return name;
        if (name.contains("/")) return name;

        // 1. Standard library and java.lang
        String std = resolveStandardClassPath(name);
        if (std != null) {
            return std;
        }

        // 2. SymbolRegistry check
        ocean.compiler.symbol.ClassSymbol sym = CompilerRegistry.getClassSymbol(name);
        if (sym != null && sym.getInternalName() != null && !sym.getInternalName().equals(name)) {
            return sym.getInternalName();
        }

        // 3. Current package check (if currentClassName is in a package)
        if (currentClassName != null) {
            int slash = currentClassName.lastIndexOf('/');
            if (slash != -1) {
                String pkgCandidate = currentClassName.substring(0, slash + 1) + name;
                if (CompilerRegistry.hasClass(pkgCandidate) || hasClass(pkgCandidate)) {
                    return pkgCandidate;
                }
            }
        }

        // 4. Default / root package or direct classpath check
        if (CompilerRegistry.hasClass(name) || hasClass(name)) {
            return name;
        }

        // 5. Active wildcard imports check
        CompilationSession session = CompilationSession.getActiveSession();
        if (session != null && !session.activeWildcards.isEmpty()) {
            for (String wild : session.activeWildcards) {
                String wildCandidate = wild.replace('.', '/') + "/" + name;
                if (CompilerRegistry.hasClass(wildCandidate) || hasClass(wildCandidate)) {
                    return wildCandidate;
                }
            }
        }

        // 6. Fallback: prepend current package if present
        if (currentClassName != null) {
            int slash = currentClassName.lastIndexOf('/');
            if (slash != -1) {
                return currentClassName.substring(0, slash + 1) + name;
            }
        }

        return name;
    }


    /**
     * Verilen tip descriptor veya adının ilkel tür olup olmadığını kontrol eder.
     */
    public static boolean isPrimitive(String desc) {
        if (desc == null || desc.isEmpty()) return false;
        if (desc.length() == 1 && "ZBCSIJFD".indexOf(desc.charAt(0)) >= 0) return true;
        return switch (desc.trim()) {
            case "int", "boolean", "bool", "double", "float", "long", "short", "byte", "char" -> true;
            default -> false;
        };
    }

    public static String findStdlibListClassForPrimitive(String desc) {
        return switch (desc) {
            case "[I" -> "ocean/stdlib/OceanIntList";
            case "[J" -> "ocean/stdlib/OceanLongList";
            case "[D" -> "ocean/stdlib/OceanDoubleList";
            case "[F" -> "ocean/stdlib/OceanFloatList";
            case "[Z" -> "ocean/stdlib/OceanBooleanList";
            case "[B" -> "ocean/stdlib/OceanByteList";
            case "[S" -> "ocean/stdlib/OceanShortList";
            case "[C" -> "ocean/stdlib/OceanCharList";
            default -> "ocean/stdlib/OceanList";
        };
    }

    public static String word2TypeForPrimitive(String desc) {
        return switch (desc) {
            case "int" -> "I";
            case "long" -> "J";
            case "bool", "boolean" -> "Z";
            case "float" -> "F";
            case "double" -> "D";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "void" -> "V";
            default -> null;
        };
    }
    public static String getBaseMethodDescriptor(String desc) {
        if (desc == null) return "(Ljava/lang/Object;)V";
        return switch (desc) {
            case "I", "B", "S" -> "(I)V";
            case "Z" -> "(Z)V";
            case "C" -> "(C)V";
            case "J" -> "(J)V";
            case "F" -> "(F)V";
            case "D" -> "(D)V";
            case OceanTypeSystem.STRING_DESC -> "(Ljava/lang/String;)V";
            default -> "(Ljava/lang/Object;)V";
        };
    }

    public static String clearLInPrimitive(String desc) {
        if (desc == null) return null;
        return switch (desc) {
            case "LI;", "Lint;" -> "I";
            case "LZ;", "Lboolean;", "Lbool;" -> "Z";
            case "LJ;", "Llong;" -> "J";
            case "LF;", "Lfloat;" -> "F";
            case "LD;", "Ldouble;" -> "D";
            case "LB;", "Lbyte;" -> "B";
            case "LC;", "Lchar;" -> "C";
            case "LS;", "Lshort;" -> "S";
            case "LV;", "Lvoid;" -> "V";
            default -> desc;
        };
    }

    /**
     * Primitive -> Boxed dönüşümü yapar (Örn: "I" -> "Ljava/lang/Integer;").
     */
    public static String box(String primitiveDesc) {
        if (primitiveDesc == null) return null;
        return switch (primitiveDesc) {
            case "Z", "boolean", "bool" -> "Ljava/lang/Boolean;";
            case "B", "byte" -> "Ljava/lang/Byte;";
            case "C", "char" -> "Ljava/lang/Character;";
            case "S", "short" -> "Ljava/lang/Short;";
            case "I", "int" -> "Ljava/lang/Integer;";
            case "J", "long" -> "Ljava/lang/Long;";
            case "F", "float" -> "Ljava/lang/Float;";
            case "D", "double" -> "Ljava/lang/Double;";
            default -> null;
        };
    }

    public static String getBoxedDescriptor(String desc) {
        return switch (desc) {
            case "I" -> "Ljava/lang/Integer;";
            case "Z" -> "Ljava/lang/Boolean;";
            case "J" -> "Ljava/lang/Long;";
            case "F" -> "Ljava/lang/Float;";
            case "D" -> "Ljava/lang/Double;";
            case "C" -> "Ljava/lang/Character;";
            case "B" -> "Ljava/lang/Byte;";
            case "S" -> "Ljava/lang/Short;";
            default -> desc;
        };
    }

    public static String getDescriptorForConstant(Object value) {
        if (value instanceof Integer) return "I";
        if (value instanceof Character) return "C";
        if (value instanceof Byte) return "B";
        if (value instanceof Short) return "S";
        if (value instanceof Long) return "J";
        if (value instanceof Float) return "F";
        if (value instanceof Double) return "D";
        if (value instanceof Boolean) return "Z";
        if (value instanceof String) return OceanTypeSystem.STRING_DESC;
        if (value instanceof BigDecimal) return OceanTypeSystem.BIGDECIMAL_DESC;
        return OceanTypeSystem.OBJECT_DESC;
    }

    /**
     * Boxed -> Primitive dönüşümü yapar (Örn: "Ljava/lang/Integer;" -> "I").
     */
    public static String unbox(String boxedDesc) {
        if (boxedDesc == null) return null;
        return switch (boxedDesc) {
            case "Ljava/lang/Boolean;" -> "Z";
            case "Ljava/lang/Byte;" -> "B";
            case "Ljava/lang/Character;" -> "C";
            case "Ljava/lang/Short;" -> "S";
            case "Ljava/lang/Integer;" -> "I";
            case "Ljava/lang/Long;" -> "J";
            case "Ljava/lang/Float;" -> "F";
            case "Ljava/lang/Double;" -> "D";
            default -> null;
        };
    }


    /**
     * JVM descriptor'ını insan okunabilir representsasyona çevirir.
     */
    public static String humanReadable(String desc) {
        if (desc == null) return "unknown";
        return switch (desc) {
            case "I" -> "int";
            case "Z" -> "boolean";
            case "D" -> "double";
            case "F" -> "float";
            case "J" -> "long";
            case "B" -> "byte";
            case "C" -> "char";
            case "S" -> "short";
            case "V" -> "void";
            default -> {
                if (TypeChecker.isClassType(desc)) {
                    String internal = desc.substring(1, desc.length() - 1);
                    yield OceanTypeSystem.findSimpleName(internal);
                }
                yield desc;
            }
        };
    }

    /**
     * İsim tabanlı Class.forName çözümlemesi (primitifler dahil).
     */
    public static Class<?> forName(String name) throws ClassNotFoundException {
        if (name == null || name.isEmpty()) return null;
        name = name.trim();
        switch (name) {
            case "int": return int.class;
            case "boolean": case "bool": return boolean.class;
            case "double": return double.class;
            case "float": return float.class;
            case "long": return long.class;
            case "short": return short.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "void": return void.class;
        }
        if (name.endsWith("[]")) {
            String elemName = name.substring(0, name.length() - 2).trim();
            Class<?> elemClass = forName(elemName);
            if (elemClass != null) {
                return Array.newInstance(elemClass, 0).getClass();
            }
        }

        if (negativeClassForNameCache.contains(name)) {
            throw new ClassNotFoundException(name);
        }
        Class<?> cached = classForNameCache.get(name);
        if (cached != null) {
            return cached;
        }

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = OceanTypeSystem.class.getClassLoader();
        try {
            Class<?> clazz = Class.forName(name, false, cl);
            classForNameCache.put(name, clazz);
            return clazz;
        } catch (ClassNotFoundException | LinkageError e) {
            try {
                Class<?> clazz = Class.forName(name, false, OceanTypeSystem.class.getClassLoader());
                classForNameCache.put(name, clazz);
                return clazz;
            } catch (ClassNotFoundException | LinkageError e2) {
                negativeClassForNameCache.add(name);
                throw new ClassNotFoundException(name, e2);
            }
        }
    }

    public static String findFuncInterface(int paramCount,boolean hasReturnValue) {
        return switch (paramCount) {
            case 0 -> hasReturnValue ? "Ljava/util/function/Supplier;" : "Ljava/lang/Runnable;";
            case 1 -> hasReturnValue ? "Ljava/util/function/Function;" : "Ljava/util/function/Consumer;";
            case 2 -> hasReturnValue ? "Ljava/util/function/BiFunction;" : "Ljava/util/function/BiConsumer;";
            default -> "Ljava/util/function/Function;";
        };
    }

    public static String findSimpleName(String className) {
        return className.substring(className.lastIndexOf('/') + 1);
    }

    /**
     * Sınıf/nesne adı için JVM object descriptor formatı üretir ("L" + name + ";").
     */
    public static String wrapObjectType(String name) {
        if (name == null || name.isEmpty()) return null;
        if (TypeChecker.isClassType(name)) return name;
        return "L" + name + ";";
    }

    /**
     * Returns true if the given JVM internal class name refers to a SAM (functional) interface
     * that has exactly one abstract method.  Used as a reflection-based fallback by TypeChecker
     * when the interface has not been registered in globalFunctionalInterfaceRegistry.
     * Returns false whenever the class cannot be loaded (e.g. not on the compile-time classpath).
     */
    public static boolean hasSingleAbstractMethod(String internalName) {
        return ClassMetadataCache.hasSingleAbstractMethod(internalName);
    }

    public static boolean isSubtypeOfReflection(String internalName, String targetBinaryName) {
        return ClassMetadataCache.isSubtype(internalName, targetBinaryName);
    }

    public record SAMMethodInfo(String name, String descriptor) {
    }

    public static SAMMethodInfo getSingleAbstractMethodInfo(String internalName) {
        return ClassMetadataCache.getSingleAbstractMethodInfo(internalName);
    }


}
