package ocean.compiler;

import ocean.stdlib.OceanList;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tip uyumluluk kontrolü yapan yardımcı sınıf.
 * JVM tip descriptor'ları arasında atanabilirlik, genişletme (widening),
 * boxing/unboxing ve aritmetik uyumluluk kontrollerini sağlar.
 */
public class TypeChecker {

    // ========== Primitif Genişletme (Widening) Tablosu ==========
    // byte → short → int → long → float → double
    // char → int

    private static final String[][] WIDENING_TABLE = {
            {"B", "S", "I", "J", "F", "D"},  // byte → ...
            {"S", "I", "J", "F", "D"},        // short → ...
            {"I", "J", "F", "D"},             // int → ...
            {"J", "F", "D"},                  // long → ...
            {"F", "D"},                       // float → ...
            {"C", "I", "J", "F", "D"},        // char → ...
    };

    /**
     * sourceDesc tipinin targetDesc tipine atanıp atanamayacağını kontrol eder.
     */
    public static boolean isNullable(String desc) {
        return desc != null && (desc.endsWith("?") || desc.equals("null"));
    }

    private static final Map<String, String> cleanDescriptorCache = new ConcurrentHashMap<>();

    public static void clearCaches() {
        cleanDescriptorCache.clear();
    }

    public static String cleanDescriptor(String desc) {
        if (desc == null) return null;
        if (desc.length() == 1) return desc;
        return cleanDescriptorCache.computeIfAbsent(desc, TypeChecker::cleanDescriptorImpl);
    }

    private static String cleanDescriptorImpl(String desc) {
        String cleaned = desc.replace("?", "");
        while (cleaned.contains("<") && cleaned.contains(">")) {
            int start = cleaned.indexOf('<');
            int end = -1;
            int depth = 0;
            for (int i = start; i < cleaned.length(); i++) {
                char c = cleaned.charAt(i);
                if (c == '<') {
                    depth++;
                } else if (c == '>') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
            if (end != -1) {
                cleaned = cleaned.substring(0, start) + (end + 1 < cleaned.length() ? cleaned.substring(end + 1) : "");
            } else {
                break;
            }
        }
        cleaned = cleaned.replace(">", "").replace("<", "");
        while (cleaned.endsWith("?")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        while (cleaned.contains(";;")) {
            cleaned = cleaned.replace(";;", ";");
        }
        boolean wasDescriptor = desc.endsWith(";") || desc.contains("<");
        if (wasDescriptor) {
            if (desc.startsWith("L") && !cleaned.endsWith(";")) {
                cleaned = cleaned + ";";
            } else if (desc.startsWith("[L") && !cleaned.endsWith(";")) {
                cleaned = cleaned + ";";
            }
        }
        return cleaned;
    }

    /**
     * Extracts internal class path (e.g. "java/lang/String") from object descriptors like "Ljava/lang/String;".
     */
    public static String getInternalName(String desc) {
        if (desc == null) return null;
        String cleaned = cleanDescriptor(desc);
        if (TypeChecker.isClassType(cleaned)) {
            return cleaned.substring(1, cleaned.length() - 1);
        }
        return cleaned;
    }

    private static final Map<String, String> COMMON_SUPER_CACHE = new ConcurrentHashMap<>();

    /**
     * İki sınıf/tip arasındaki en yakın ortak üst sınıfı (Lowest Upper Bound - LUB) bulur.
     */
    public static String getCommonSuperClass(String type1, String type2) {
        if (type1 == null || type2 == null) return "java/lang/Object";
        String t1 = getInternalName(type1).replace('.', '/');
        String t2 = getInternalName(type2).replace('.', '/');
        if (t1.equals(t2)) return t1;

        String cacheKey = t1 + "|" + t2;
        String cached = COMMON_SUPER_CACHE.get(cacheKey);
        if (cached != null) return cached;

        if (ClassMetadataCache.isSubtype(t1, t2)) {
            COMMON_SUPER_CACHE.put(cacheKey, t2);
            return t2;
        }
        if (ClassMetadataCache.isSubtype(t2, t1)) {
            COMMON_SUPER_CACHE.put(cacheKey, t1);
            return t1;
        }

        Set<String> supers1 = new LinkedHashSet<>();
        String curr = t1;
        while (curr != null && !curr.equals("java/lang/Object")) {
            supers1.add(curr);
            if (CompilerRegistry.globalSuperClassRegistry.containsKey(curr)) {
                curr = CompilerRegistry.globalSuperClassRegistry.get(curr);
            } else {
                try {
                    Class<?> c = OceanTypeSystem.forName(curr.replace('/', '.'));
                    curr = (c != null && c.getSuperclass() != null) ? c.getSuperclass().getName().replace('.', '/')
                            : "java/lang/Object";
                } catch (Throwable e) {
                    curr = "java/lang/Object";
                }
            }
        }
        supers1.add("java/lang/Object");

        String commonClass = null;
        curr = t2;
        while (curr != null && !curr.equals("java/lang/Object")) {
            if (supers1.contains(curr)) {
                commonClass = curr;
                break;
            }
            if (CompilerRegistry.globalSuperClassRegistry.containsKey(curr)) {
                curr = CompilerRegistry.globalSuperClassRegistry.get(curr);
            } else {
                try {
                    Class<?> c = OceanTypeSystem.forName(curr.replace('/', '.'));
                    curr = (c != null && c.getSuperclass() != null) ? c.getSuperclass().getName().replace('.', '/')
                            : "java/lang/Object";
                } catch (Throwable e) {
                    curr = "java/lang/Object";
                }
            }
        }
        if (commonClass != null) {
            COMMON_SUPER_CACHE.put(cacheKey, commonClass);
            return commonClass;
        }

        // Check common interfaces
        Set<String> ifaces1 = getAllInterfaces(t1);
        Set<String> ifaces2 = getAllInterfaces(t2);
        for (String iface : ifaces2) {
            if (ifaces1.contains(iface)) {
                COMMON_SUPER_CACHE.put(cacheKey, iface);
                return iface;
            }
        }

        COMMON_SUPER_CACHE.put(cacheKey, "java/lang/Object");
        return "java/lang/Object";
    }

    public static Set<String> getAllInterfaces(String typeName) {
        Set<String> result = new LinkedHashSet<>();
        if (typeName == null || typeName.isEmpty()) return result;
        String clean = getInternalName(typeName).replace('.', '/');
        Queue<String> queue = new ArrayDeque<>();
        queue.add(clean);
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (!visited.add(node)) continue;
            String[] ifaces = CompilerRegistry.globalInterfaceRegistry.get(node);
            if (ifaces != null) {
                for (String ifc : ifaces) {
                    if (ifc != null) {
                        String cleanIfc = getInternalName(ifc).replace('.', '/');
                        result.add(cleanIfc);
                        queue.add(cleanIfc);
                    }
                }
            }
            try {
                Class<?> cls = OceanTypeSystem.forName(node.replace('/', '.'));
                if (cls != null) {
                    for (Class<?> ifc : cls.getInterfaces()) {
                        String ifcName = ifc.getName().replace('.', '/');
                        result.add(ifcName);
                        queue.add(ifcName);
                    }
                    if (cls.getSuperclass() != null) {
                        queue.add(cls.getSuperclass().getName().replace('.', '/'));
                    }
                }
            } catch (Throwable ignored) {}
            String sup = CompilerRegistry.globalSuperClassRegistry.get(node);
            if (sup != null) queue.add(getInternalName(sup).replace('.', '/'));
        }
        return result;
    }

    /**
     * Verilen tip listesi arasındaki en yakın ortak üst sınıfı (LUB) bulur.
     */
    public static String getCommonSuperClass(List<String> types) {
        if (types == null || types.isEmpty()) return "java/lang/Object";
        String common = getInternalName(types.getFirst());
        for (int i = 1; i < types.size(); i++) {
            common = getCommonSuperClass(common, types.get(i));
        }
        return common;
    }

    /**
     * sourceDesc tipinin targetDesc tipine atanıp atanamayacağını kontrol eder.
     */
    public static boolean isAssignable(String targetDesc, String sourceDesc, CompilationSession session) {
        if (targetDesc == null || sourceDesc == null) return true;

        // Null safety checks
        boolean targetNullable = isNullable(targetDesc);
        boolean sourceNullable = isNullable(sourceDesc);

        String cleanTarget = cleanDescriptor(targetDesc);
        String cleanSource = cleanDescriptor(sourceDesc);

        if (cleanTarget.equals("V") || cleanSource.equals("V")) return cleanTarget.equals(cleanSource);

        if ("null".equals(cleanSource)){
            return !isPrimitive(cleanTarget) && targetNullable;
        }

        if (!targetNullable && sourceNullable) return false;
        if (isPrimitive(cleanTarget) && sourceNullable) return false;

        if ((targetDesc.contains("<") && targetDesc.contains(">")) || (sourceDesc.contains("<") && sourceDesc.contains(">"))) {
            return isAssignableGenericAware(targetDesc, sourceDesc,true, session);
        }

        if (cleanTarget.equals(cleanSource)) return true;

        //
        if (cleanSource.startsWith("+")) {
            return isAssignable(cleanTarget, cleanSource.substring(1), session);
        }
        if (cleanSource.equals("*") || cleanSource.startsWith("-")) {
            return isObjectType(cleanTarget);
        }

        // Object can hold anything (including boxed primitives)
        if (isObjectType(cleanTarget)) return true;

        // Primitif sayısal dönüşümler (Yalnızca güvenli genişletme - widening)
        if (isPrimitive(cleanTarget) && isPrimitive(cleanSource)) {
            return canWiden(cleanSource, cleanTarget);
        }

        // Boxing/Unboxing
        if (isBoxedEquivalent(cleanTarget, cleanSource) || isBoxedEquivalent(cleanSource, cleanTarget)) return true;

        // Referans tipleri hiyerarşisi
        if (!isPrimitive(cleanTarget) && !isPrimitive(cleanSource)) {
            // Arrays
            if (cleanSource.startsWith("[")) {
                if (cleanTarget.startsWith("[")) {
                    String elemTarget = cleanTarget.substring(1);
                    String elemSource = cleanSource.substring(1);

                    // Primitive array subtyping: If either element is primitive,
                    // they can only be assigned if both element types are exactly identical.
                    if (isPrimitive(elemTarget) || isPrimitive(elemSource)) {
                        return elemTarget.equals(elemSource);
                    }

                    if (cleanTarget.equals("[java/lang/Object") || cleanTarget.equals("[" + OceanTypeSystem.OBJECT_DESC)) {
                        return !isPrimitive(elemSource);
                    }

                    return isAssignable(elemTarget, elemSource, session);
                }
                String targetInternal = getInternalName(cleanTarget);
                return isObjectType(cleanTarget) ||
                        "java/lang/Cloneable".equals(targetInternal) ||
                        "java/io/Serializable".equals(targetInternal);
            }
            if (cleanTarget.startsWith("[")) {
                return false;
            }

            if (isCollectionCompatible(cleanSource, cleanTarget)) return true;

            if (session != null) return session.isSubType(cleanSource, cleanTarget);
            CompilationSession s = CompilationSession.getActiveSession();
            if (s != null) return s.isSubType(cleanSource, cleanTarget);
            return false;
        }

        // Primitif ↔ Referans
        if (isPrimitive(cleanTarget) && !isPrimitive(cleanSource)) {
            if (isObjectType(cleanSource)) return true;
            if (OceanTypeSystem.NUMBER_DESC.equals(cleanSource)) return isNumeric(cleanTarget);
            String unboxed = unbox(cleanSource);
            return unboxed != null && isAssignable(cleanTarget, unboxed, session);
        }
        if (!isPrimitive(cleanTarget) && isPrimitive(cleanSource)) {
            String boxed = box(cleanSource);
            return boxed != null && isAssignable(cleanTarget, boxed, session);
        }

        return false;
    }


    /**
     * İki tipin aritmetik operatörlerle kullanılıp kullanılamayacağını kontrol eder.
     */
    public static boolean isArithmeticCompatible(String leftDesc, String rightDesc) {
        return isNumeric(leftDesc) && isNumeric(rightDesc);
    }

    /**
     * İki tipin karşılaştırma operatörleriyle (<, >, <=, >=) kullanılabilirliğini kontrol eder.
     */
    public static boolean isComparable(String leftDesc, String rightDesc) {
        String left = cleanDescriptor(leftDesc);
        String right = cleanDescriptor(rightDesc);
        if (left == null || right == null) {
            if (left == null && isNumeric(right)) return true;
            if (right == null && isNumeric(left)) return true;
        }
        if (TypeChecker.isStringType(left) || TypeChecker.isStringType(right)) return false;
        return isNumeric(left) && isNumeric(right);
    }

    /**
     * Verilen tipin boolean olup olmadığını kontrol eder.
     */
    public static boolean isBoolean(String desc) {
        if (desc == null) return false;
        String clean = cleanDescriptor(desc);
        return "Z".equals(clean) || "Ljava/lang/Boolean;".equals(clean);
    }

    /**
     * Verilen tipin Enum olup olmadığını kontrol eder.
     */
    public static boolean isEnumType(String typeDesc) {
        if (!isClassType(typeDesc)) return false;
        String internalName = cleanDescriptor(typeDesc);
        if (internalName.startsWith("L") && internalName.endsWith(";")) {
            internalName = internalName.substring(1, internalName.length() - 1);
        }
        if (CompilerRegistry.globalSuperClassRegistry.containsKey(internalName)) {
            return "java/lang/Enum".equals(CompilerRegistry.globalSuperClassRegistry.get(internalName));
        }
        if (CompilerRegistry.globalEnumConstants.containsKey(internalName)) {
            return true;
        }
        try {
            Class<?> cls = OceanTypeSystem.forName(internalName.replace("/", "."));
            return cls != null && cls.isEnum();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * İki tip arasındaki ortak (promoted) tipi belirler.
     * Aynı ham tip ama farklı generic argümanlar için özyinelemeli common type hesaplar.
     * Örnek: getCommonType(OceanMap<String,Integer>, OceanMap<String,String>)
     *         → OceanMap<String,Object>
     */
    public static String getCommonType(String a, String b) {
        boolean anyNullable = isNullable(a) || isNullable(b);
        String cleanA = cleanDescriptor(a);
        String cleanB = cleanDescriptor(b);
        String result;
        if (cleanA.equals(cleanB)) {
            // Aynı ham tip: generic argümanları recursive olarak hesapla
            List<String> genA = extractGenericArguments(a);
            List<String> genB = extractGenericArguments(b);
            if (!genA.isEmpty() && !genB.isEmpty() && genA.size() == genB.size()) {
                List<String> commonGens = new ArrayList<>();
                for (int i = 0; i < genA.size(); i++) {
                    commonGens.add(getCommonType(genA.get(i), genB.get(i)));
                }
                // cleanA: "Locean/stdlib/OceanMap;" → base = "Locean/stdlib/OceanMap"
                String base = cleanA.endsWith(";") ? cleanA.substring(0, cleanA.length() - 1) : cleanA;
                result = base + "<" + String.join(",", commonGens) + ">;";
            } else {
                result = cleanA;
            }
        }
        else if ((cleanA.equals("null") && !cleanB.equals("null")) || (cleanB.equals("null") && !cleanA.equals("null"))) {
            boolean aIsNull = cleanA.equals("null");
            if (aIsNull) result = cleanB;
            else result = cleanA;
        }
        else if (cleanA.equals(OceanTypeSystem.BIGDECIMAL_DESC) || cleanB.equals(OceanTypeSystem.BIGDECIMAL_DESC)) {
            result = OceanTypeSystem.BIGDECIMAL_DESC;
        } else if (isBoolean(cleanA) && isBoolean(cleanB)) {
            result = "Z";
        } else if ((isNumeric(cleanA) || cleanA.equals(OceanTypeSystem.NUMBER_DESC)) &&
                   (isNumeric(cleanB) || cleanB.equals(OceanTypeSystem.NUMBER_DESC))) {
            if (cleanA.equals("D") || cleanB.equals("D") || cleanA.equals("Ljava/lang/Double;") || cleanB.equals("Ljava/lang/Double;")) {
                result = "D";
            } else if (cleanA.equals("F") || cleanB.equals("F") || cleanA.equals("Ljava/lang/Float;") || cleanB.equals("Ljava/lang/Float;")) {
                result = "F";
            } else if (cleanA.equals("J") || cleanB.equals("J") || cleanA.equals("Ljava/lang/Long;") || cleanB.equals("Ljava/lang/Long;")) {
                result = "J";
            } else {
                result = "I";
            }
        } else if (TypeChecker.isStringType(cleanA) && TypeChecker.isStringType(cleanB)) {
            result = OceanTypeSystem.STRING_DESC;
        } else {
            result = OceanTypeSystem.OBJECT_DESC;
        }
        if (anyNullable && !result.endsWith("?")) {
            if (isPrimitive(result)) {
                result = box(result);
            }
            result = result + "?";
        }
        return result;
    }

    /**
     * Conditional Operator (? :) Common Promoted Type.
     * Computes the unified type of the then- and else-expressions.
     */
    public static String getTernaryPromotedType(String t1, String t2, CompilationSession session) {
        if (t1 == null && t2 == null) return OceanTypeSystem.OBJECT_DESC;
        if (t1 == null) return t2;
        if (t2 == null) return t1;

        boolean anyNullable = isNullable(t1) || isNullable(t2);
        String clean1 = cleanDescriptor(t1);
        String clean2 = cleanDescriptor(t2);

        if (clean1.equals(clean2)) {
            return anyNullable && !clean1.equals("null") && !t1.endsWith("?") ? t1 + "?" : t1;
        }

        if ("null".equals(clean1)) {
            if (isPrimitive(clean2)) return box(clean2) + "?";
            return isNullable(t2) ? t2 : (t2.endsWith("?") ? t2 : t2 + "?");
        }
        if ("null".equals(clean2)) {
            if (isPrimitive(clean1)) return box(clean1) + "?";
            return isNullable(t1) ? t1 : (t1.endsWith("?") ? t1 : t1 + "?");
        }

        boolean prim1 = isPrimitive(clean1);
        boolean prim2 = isPrimitive(clean2);

        // Both boolean or boxed boolean
        if (isBoolean(clean1) && isBoolean(clean2)) {
            return "Z";
        }

        // Numeric promotion (both numeric, primitive or boxed)
        if (isNumeric(clean1) && isNumeric(clean2)) {
            return getBinaryNumericPromotedType(clean1, clean2);
        }

        CompilationSession activeSession = session != null ? session : CompilationSession.getActiveSession();

        // Primitive and reference (e.g. int and Object or Number)
        if (prim1 != prim2) {
            String primSide = prim1 ? clean1 : clean2;
            String refSide = prim1 ? clean2 : clean1;
            String boxed = box(primSide);
            if (boxed != null && isAssignable(refSide, boxed, activeSession)) {
                return anyNullable && !refSide.endsWith("?") ? refSide + "?" : refSide;
            }
            return OceanTypeSystem.OBJECT_DESC;
        }

        // Both reference types: check subtyping and common super class
        if (isAssignable(clean2, clean1, activeSession)) {
            return anyNullable && !t2.endsWith("?") ? t2 + "?" : t2;
        }
        if (isAssignable(clean1, clean2, activeSession)) {
            return anyNullable && !t1.endsWith("?") ? t1 + "?" : t1;
        }

        String commonSuper = getCommonSuperClass(clean1, clean2);
        String result = OceanTypeSystem.wrapObjectType(commonSuper);
        if (anyNullable && !result.endsWith("?")) {
            result = result + "?";
        }
        return result;
    }

    /**
     * Binary Numeric Promotion for arithmetic and bitwise expressions.
     * If either operand is BigDecimal -> BigDecimal
     * If either operand is String -> String (for concat)
     * If both operands are boolean -> boolean ("Z")
     * If either operand is double -> double ("D")
     * Else if either operand is float -> float ("F")
     * Else if either operand is long -> long ("J")
     * Else -> int ("I") (promotes byte, short, char, int)
     */
    public static String getBinaryNumericPromotedType(String a, String b) {
        if (a == null && b == null) return OceanTypeSystem.OBJECT_DESC;
        if (a == null) return getUnaryNumericPromotedType(b);
        if (b == null) return getUnaryNumericPromotedType(a);

        String cleanA = cleanDescriptor(a);
        String cleanB = cleanDescriptor(b);

        if (isStringType(cleanA) || isStringType(cleanB)) return OceanTypeSystem.STRING_DESC;
        if (cleanA.equals(OceanTypeSystem.BIGDECIMAL_DESC) || cleanB.equals(OceanTypeSystem.BIGDECIMAL_DESC)) {
            return OceanTypeSystem.BIGDECIMAL_DESC;
        }
        if (isBoolean(cleanA) && isBoolean(cleanB)) {
            return "Z";
        }

        String pA = isPrimitive(cleanA) ? cleanA : unbox(cleanA);
        String pB = isPrimitive(cleanB) ? cleanB : unbox(cleanB);

        if (pA != null && pB != null) {
            if (pA.equals("D") || pB.equals("D")) return "D";
            if (pA.equals("F") || pB.equals("F")) return "F";
            if (pA.equals("J") || pB.equals("J")) return "J";
            return "I";
        }

        // When either operand is a general reference type (Object, Number, generic T <: Number, etc.),
        // binary arithmetic produces a boxed Number via RuntimeUtils.
        return OceanTypeSystem.NUMBER_DESC;
    }

    /**
     * Unary Numeric Promotion.
     * Applied to operands of unary +, -, ~, and shift expressions.
     * byte, short, char -> int ("I")
     * int, long, float, double -> unchanged
     */
    public static String getUnaryNumericPromotedType(String a) {
        if (a == null) return "I";
        String clean = cleanDescriptor(a);
        String p = isPrimitive(clean) ? clean : unbox(clean);
        if (p != null) {
            if (p.equals("B") || p.equals("S") || p.equals("C") || p.equals("I")) return "I";
            if (p.equals("J") || p.equals("F") || p.equals("D")) return p;
        }
        return switch (clean) {
            case "Ljava/lang/Byte;", "Ljava/lang/Short;", "Ljava/lang/Character;", "Ljava/lang/Integer;" -> "I";
            case "Ljava/lang/Long;" -> "J";
            case "Ljava/lang/Float;" -> "F";
            case "Ljava/lang/Double;" -> "D";
            default -> clean;
        };
    }

    public static List<String> extractGenericArguments(String desc) {
        if (desc == null) return Collections.emptyList();
        int start = desc.indexOf('<');
        if (start < 0) return Collections.emptyList();
        String cleaned = desc;
        if (cleaned.endsWith("?")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        if (cleaned.endsWith(";")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        int end = cleaned.lastIndexOf('>');
        if (end <= start) return Collections.emptyList();

        String inner = cleaned.substring(start + 1, end).trim();
        return splitGenericArgs(inner);
    }

    public static List<String> splitGenericArgs(String inner) {
        if (inner == null || inner.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;

        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') {
                depth++;
                current.append(c);
            } else if (c == '>') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            result.add(current.toString().trim());
        }
        return result;
    }

    public static List<String> parseSequentialJvmSignatures(String s) {
        if (s == null || s.isEmpty()) return Collections.emptyList();
        List<String> list = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '*' || c == '?') {
                list.add(String.valueOf(c));
                i++;
            } else if (c == '+' || c == '-') {
                int start = i;
                i++;
                if (i < n) {
                    char bc = s.charAt(i);
                    if (bc == 'L' || bc == 'T') {
                        int depth = 0;
                        while (i < n) {
                            char sc = s.charAt(i);
                            if (sc == '<') depth++;
                            else if (sc == '>') depth--;
                            else if (sc == ';' && depth == 0) {
                                i++;
                                break;
                            }
                            i++;
                        }
                    } else if (bc == '[') {
                        while (i < n && s.charAt(i) == '[') i++;
                        if (i < n && (s.charAt(i) == 'L' || s.charAt(i) == 'T')) {
                            int depth = 0;
                            while (i < n) {
                                char sc = s.charAt(i);
                                if (sc == '<') depth++;
                                else if (sc == '>') depth--;
                                else if (sc == ';' && depth == 0) {
                                    i++;
                                    break;
                                }
                                i++;
                            }
                        } else if (i < n) {
                            i++;
                        }
                    } else {
                        i++;
                    }
                }
                list.add(s.substring(start, i));
            } else if (c == 'L' || c == 'T') {
                int start = i;
                int depth = 0;
                while (i < n) {
                    char sc = s.charAt(i);
                    if (sc == '<') depth++;
                    else if (sc == '>') depth--;
                    else if (sc == ';' && depth == 0) {
                        i++;
                        break;
                    }
                    i++;
                }
                list.add(s.substring(start, i));
            } else if (c == '[') {
                int start = i;
                while (i < n && s.charAt(i) == '[') i++;
                if (i < n && (s.charAt(i) == 'L' || s.charAt(i) == 'T')) {
                    int depth = 0;
                    while (i < n) {
                        char sc = s.charAt(i);
                        if (sc == '<') depth++;
                        else if (sc == '>') depth--;
                        else if (sc == ';' && depth == 0) {
                            i++;
                            break;
                        }
                        i++;
                    }
                } else if (i < n) {
                    i++;
                }
                list.add(s.substring(start, i));
            } else if ("ZBCSIJFDV".indexOf(c) >= 0 && (i + 1 == n || s.charAt(i + 1) == ';' || "ZBCSIJFDVL[*+-?".indexOf(s.charAt(i + 1)) >= 0)) {
                list.add(String.valueOf(c));
                i++;
            } else {
                int start = i;
                while (i < n && s.charAt(i) != ';' && s.charAt(i) != '<' && s.charAt(i) != '>') i++;
                if (i < n && s.charAt(i) == ';') i++;
                list.add(s.substring(start, i));
            }
        }
        return list;
    }

    /**
     * Generic-aware atanabilirlik kontrolü. cleanDescriptor ile erasure yapmaz;
     * generic argümanları özyinelemeli olarak kıyaslayarak declaration-site varyans (+/-) ve
     * invariant semantik uygular.
     */
    public static boolean isAssignableGenericAware(String targetDesc, String sourceDesc,boolean isHomegenous, CompilationSession session) {
        if (targetDesc == null || sourceDesc == null) return true;

        if (targetDesc.contains("?;")) targetDesc = targetDesc.replace("?;", ";?");
        if (sourceDesc.contains("?;")) sourceDesc = sourceDesc.replace("?;", ";?");

        if (targetDesc.contains("<") && !targetDesc.startsWith("L")) {
            targetDesc = SymbolTable.getDescriptor(targetDesc);
        }
        if (sourceDesc.contains("<") && !sourceDesc.startsWith("L")) {
            sourceDesc = SymbolTable.getDescriptor(sourceDesc);
        }
        String cleanTarget = cleanDescriptor(targetDesc);
        String cleanSource = cleanDescriptor(sourceDesc);


        if ("null".equals(cleanSource)) return true;

        if (specialoptimizationForPrimitiveOceanLists(targetDesc, sourceDesc)) return true;

        String rawTarget = cleanTarget.contains("<") ? cleanTarget.substring(0, cleanTarget.indexOf('<')) : cleanTarget;
        if (rawTarget.startsWith("L") && !rawTarget.endsWith(";")) rawTarget += ";";
        String rawSource = cleanSource.contains("<") ? cleanSource.substring(0, cleanSource.indexOf('<')) : cleanSource;
        if (rawSource.startsWith("L") && !rawSource.endsWith(";")) rawSource += ";";

        // Primitifleri boxing'le kıyasla
        String boxedTarget = isPrimitive(rawTarget) ? box(rawTarget) : rawTarget;
        String boxedSource = isPrimitive(rawSource) ? box(rawSource) : rawSource;
        // Ham tipler farklıysa — sınıf hiyerarşisini kontrol et
        if (!boxedTarget.equals(boxedSource)) {
            if (isObjectType(boxedTarget)) return true; // hedef Object: herşeyi kabul eder
            if (isCollectionCompatible(rawSource, rawTarget)) {
                // uyumlu
            } else {
                CompilationSession s = session != null ? session : CompilationSession.getActiveSession();
                if (s != null) {
                    if (!s.isSubType(boxedSource, boxedTarget)) {
                        return false;
                    }
                } else if (!isAssignable(boxedTarget, boxedSource,null)) {
                    return false;
                }
            }
        }

        List<String> targetArgs = extractGenericArguments(targetDesc);
        List<String> sourceArgs = extractGenericArguments(sourceDesc);

        if (targetArgs.isEmpty() && sourceArgs.isEmpty()) {
            if (boxedTarget.equals(boxedSource)) return true;
            if (isCollectionCompatible(cleanSource, cleanTarget)) return true;
            if (isObjectType(boxedSource) && !isObjectType(boxedTarget)) return false;
            CompilationSession s = session != null ? session : CompilationSession.getActiveSession();
            if (s != null) return s.isSubType(boxedSource, boxedTarget);
            return isAssignable(targetDesc, sourceDesc,null);
        }
        if (targetArgs.isEmpty()) return true;  // hedef raw tip, kısıtlama yok
        if (sourceArgs.isEmpty()) return true;  // kaynak raw tip, uyumlu
        if (targetArgs.size() != sourceArgs.size()) return specialoptimizationForPrimitiveOceanLists(targetDesc, sourceDesc);

        List<CompilerRegistry.TypeParameterInfo> tps = null;
        String internalOwner = getInternalName(cleanTarget);
        if (internalOwner != null) {
            tps = CompilerRegistry.globalTypeParameterRegistry.get(internalOwner);
            if (tps == null) {
                tps = CompilerRegistry.globalTypeParameterRegistry.get(internalOwner.replace('.', '/'));
            }
        }

        for (int i = 0; i < targetArgs.size(); i++) {
            CompilerRegistry.Variance var = (tps != null && i < tps.size()) ? tps.get(i).variance : CompilerRegistry.Variance.INVARIANT;
            if (!isGenericArgCompatible(targetArgs.get(i), sourceArgs.get(i), var,isHomegenous, session)) return false;
        }
        return true;
    }

    public static String getSpecializedPrimitiveListClass(String targetDesc) {
        if (targetDesc == null) return null;
        String clean = targetDesc.replace('.', '/').trim();
        while (clean.endsWith("?")) {
            clean = clean.substring(0, clean.length() - 1).trim();
        }
        if (clean.startsWith("L") && clean.endsWith(";")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        return switch (clean) {
            case "ocean/stdlib/OceanList<I>", "ocean/stdlib/OceanList<int>", "OceanList<I>", "OceanList<int>",
                 "ocean/stdlib/OceanList<Ljava/lang/Integer;>", "OceanList<Ljava/lang/Integer;>" ->
                    "ocean/stdlib/OceanIntList";
            case "ocean/stdlib/OceanList<J>", "ocean/stdlib/OceanList<long>", "OceanList<J>", "OceanList<long>",
                 "ocean/stdlib/OceanList<Ljava/lang/Long;>", "OceanList<Ljava/lang/Long;>" ->
                    "ocean/stdlib/OceanLongList";
            case "ocean/stdlib/OceanList<D>", "ocean/stdlib/OceanList<double>", "OceanList<D>",
                 "OceanList<double>", "ocean/stdlib/OceanList<Ljava/lang/Double;>",
                 "OceanList<Ljava/lang/Double;>" -> "ocean/stdlib/OceanDoubleList";
            case "ocean/stdlib/OceanList<F>", "ocean/stdlib/OceanList<float>", "OceanList<F>",
                 "OceanList<float>", "ocean/stdlib/OceanList<Ljava/lang/Float;>", "OceanList<Ljava/lang/Float;>" ->
                    "ocean/stdlib/OceanFloatList";
            case "ocean/stdlib/OceanList<Z>", "ocean/stdlib/OceanList<boolean>",
                 "ocean/stdlib/OceanList<bool>", "OceanList<Z>", "OceanList<boolean>", "OceanList<bool>",
                 "ocean/stdlib/OceanList<Ljava/lang/Boolean;>", "OceanList<Ljava/lang/Boolean;>" ->
                    "ocean/stdlib/OceanBooleanList";
            case "ocean/stdlib/OceanList<B>", "ocean/stdlib/OceanList<byte>", "OceanList<B>", "OceanList<byte>",
                 "ocean/stdlib/OceanList<Ljava/lang/Byte;>", "OceanList<Ljava/lang/Byte;>" ->
                    "ocean/stdlib/OceanByteList";
            case "ocean/stdlib/OceanList<S>", "ocean/stdlib/OceanList<short>", "OceanList<S>",
                 "OceanList<short>", "ocean/stdlib/OceanList<Ljava/lang/Short;>", "OceanList<Ljava/lang/Short;>" ->
                    "ocean/stdlib/OceanShortList";
            case "ocean/stdlib/OceanList<C>", "ocean/stdlib/OceanList<char>", "OceanList<C>", "OceanList<char>",
                 "ocean/stdlib/OceanList<Ljava/lang/Character;>", "OceanList<Ljava/lang/Character;>" ->
                    "ocean/stdlib/OceanCharList";
            default -> null;
        };
    }

    private static boolean specialoptimizationForPrimitiveOceanLists(String targetDesc, String sourceDesc) {
        if (targetDesc == null || sourceDesc == null) return false;

        // 1. int -> OceanIntList
        if ((targetDesc.equals("Locean/stdlib/OceanList<I>;") && sourceDesc.equals("Locean/stdlib/OceanIntList;"))
                || (sourceDesc.equals("Locean/stdlib/OceanList<I>;") && targetDesc.equals("Locean/stdlib/OceanIntList;"))) {
            return true;
        }

        // 2. long -> OceanLongList
        if ((targetDesc.equals("Locean/stdlib/OceanList<J>;") && sourceDesc.equals("Locean/stdlib/OceanLongList;"))
                || (sourceDesc.equals("Locean/stdlib/OceanList<J>;") && targetDesc.equals("Locean/stdlib/OceanLongList;"))) {
            return true;
        }

        // 3. double -> OceanDoubleList
        if ((targetDesc.equals("Locean/stdlib/OceanList<D>;") && sourceDesc.equals("Locean/stdlib/OceanDoubleList;"))
                || (sourceDesc.equals("Locean/stdlib/OceanList<D>;") && targetDesc.equals("Locean/stdlib/OceanDoubleList;"))) {
            return true;
        }

        // 4. float -> OceanFloatList
        if ((targetDesc.equals("Locean/stdlib/OceanList<F>;") && sourceDesc.equals("Locean/stdlib/OceanFloatList;"))
                || (sourceDesc.equals("Locean/stdlib/OceanList<F>;") && targetDesc.equals("Locean/stdlib/OceanFloatList;"))) {
            return true;
        }

        // 5. boolean -> OceanBooleanList
        if ((targetDesc.equals("Locean/stdlib/OceanList<Z>;") && sourceDesc.equals("Locean/stdlib/OceanBooleanList;"))
                || (sourceDesc.equals("Locean/stdlib/OceanList<Z>;") && targetDesc.equals("Locean/stdlib/OceanBooleanList;"))) {
            return true;
        }

        // 6. byte -> OceanByteList
        if ((targetDesc.equals("Locean/stdlib/OceanList<B>;") && sourceDesc.equals("Locean/stdlib/OceanByteList;"))
                || (sourceDesc.equals("Locean/stdlib/OceanList<B>;") && targetDesc.equals("Locean/stdlib/OceanByteList;"))) {
            return true;
        }

        // 7. short -> OceanShortList
        if ((targetDesc.equals("Locean/stdlib/OceanList<S>;") && sourceDesc.equals("Locean/stdlib/OceanShortList;"))
                || (sourceDesc.equals("Locean/stdlib/OceanList<S>;") && targetDesc.equals("Locean/stdlib/OceanShortList;"))) {
            return true;
        }

        // 8. char -> OceanCharList
        return (targetDesc.equals("Locean/stdlib/OceanList<C>;") && sourceDesc.equals("Locean/stdlib/OceanCharList;"))
                || (sourceDesc.equals("Locean/stdlib/OceanList<C>;") && targetDesc.equals("Locean/stdlib/OceanCharList;"));
    }

    /**
     * Tek bir generic argüman pozisyonu için varyans ve invariant kontrol.
     */
    private static boolean isGenericArgCompatible(String targetArg, String sourceArg, CompilerRegistry.Variance variance, boolean isHomegenous, CompilationSession session) {
        if (targetArg == null || sourceArg == null) return true;
        String rawTarget = targetArg.trim();
        String rawSource = sourceArg.trim();

        CompilerRegistry.Variance effVar = variance;
        if (rawTarget.startsWith("+")) {
            effVar = CompilerRegistry.Variance.COVARIANT;
            rawTarget = rawTarget.substring(1).trim();
        } else if (rawTarget.startsWith("-")) {
            effVar = CompilerRegistry.Variance.CONTRAVARIANT;
            rawTarget = rawTarget.substring(1).trim();
        } else if (rawTarget.startsWith("*") || rawTarget.equals("?")) {
            return true;
        }

        if (rawSource.startsWith("+")) {
            rawSource = rawSource.substring(1).trim();
        } else if (rawSource.startsWith("-")) {
            rawSource = rawSource.substring(1).trim();
        } else if (rawSource.startsWith("*") || rawSource.equals("?")) {
            return true;
        }

        String cleanT = cleanDescriptor(rawTarget);
        String cleanS = cleanDescriptor(rawSource);
        if (specialoptimizationForPrimitiveOceanLists(rawTarget, rawSource)) return true;
        if (isHomegenous && isObjectType(rawSource)) return true;

        // Primitifleri box'la
        String boxedT = isPrimitive(cleanT) ? box(cleanT) : cleanT;
        String boxedS = isPrimitive(cleanS) ? box(cleanS) : cleanS;

        if (boxedT == null) boxedT = cleanT;
        if (boxedS == null) boxedS = cleanS;

        if (cleanT.matches("^[A-Z][0-9A-Z]{0,2}$") || cleanS.matches("^[A-Z][0-9A-Z]{0,2}$")) {
            if (cleanT.equals(cleanS)) return true;
            if (isObjectType(cleanT) || isObjectType(cleanS)) return true;
        }

        // Aynı ham tip → özyinelemeli generic kontrol
        if (boxedT.equals(boxedS)) {
            List<String> subTArgs = extractGenericArguments(rawTarget);
            List<String> subSArgs = extractGenericArguments(rawSource);
            if (subTArgs.isEmpty() && subSArgs.isEmpty()) return true;
            return isAssignableGenericAware(rawTarget, rawSource, isHomegenous, session);
        }

        CompilationSession s = session != null ? session : CompilationSession.getActiveSession();

        if (effVar == CompilerRegistry.Variance.COVARIANT) {
            // +T: source <: target (e.g. Dog <: Animal)
            if (isObjectType(boxedT)) return true;
            if (isObjectType(boxedS) && !isObjectType(boxedT)) return false;
            if (s != null) return s.isSubType(boxedS, boxedT);
            return boxedT.equals(boxedS);
        } else if (effVar == CompilerRegistry.Variance.CONTRAVARIANT) {
            // -T: target <: source (e.g. Animal <: Dog for Consumer<Animal> -> Consumer<Dog>)
            if (isObjectType(boxedS)) return true;
            if (isObjectType(boxedT) && !isObjectType(boxedS)) return false;
            if (s != null) return s.isSubType(boxedT, boxedS);
            return boxedT.equals(boxedS);
        } else {
            // INVARIANT: strict equality
            return boxedT.equals(boxedS);
        }
    }

    private static boolean isGenericArgCompatible(String targetArg, String sourceArg,boolean isHomegenous, CompilationSession session) {
        return isGenericArgCompatible(targetArg, sourceArg, CompilerRegistry.Variance.INVARIANT,isHomegenous, session);
    }

    // ========== Yardımcı Metodlar ==========

    public static boolean isPrimitive(String desc) {
        return OceanTypeSystem.isPrimitive(desc);
    }

    public static boolean isArrayType(String desc) {
        if (desc == null) return false;
        String clean = cleanDescriptor(desc);
        return clean.startsWith("[") || clean.endsWith("[]");
    }


    // Well-known methods that return boolean
    private static final Set<String> BOOLEAN_METHOD_NAMES = Set.of(
            "equals", "equalsIgnoreCase",
            "contains", "containsKey", "containsValue",
            "isEmpty", "isBlank", "isPresent",
            "startsWith", "endsWith", "matches",
            "hasNext", "hasNextInt", "hasNextLine", "hasNextDouble",
            "exists", "canRead", "canWrite", "canExecute",
            "isFile", "isDirectory", "isAbsolute", "isHidden",
            "offer", "retainAll", "addAll", "removeAll",
            "compareAndSet", "tryLock",
            "delete", "renameTo", "createNewFile", "mkdir", "mkdirs");

    @Deprecated
    public static boolean isBooleanMethodName(String name) {
        if (name.startsWith("is") || name.startsWith("has")) return true;
        return BOOLEAN_METHOD_NAMES.contains(name);
    }

    public static boolean isObjectMethod(Method m) {
        String name = m.getName();
        Class<?>[] params = m.getParameterTypes();
        if (name.equals("toString") && params.length == 0) return true;
        if (name.equals("hashCode") && params.length == 0) return true;
        return name.equals("equals") && params.length == 1 && params[0] == Object.class;
    }

    /**
     * Returns true if the type is Object/unresolvable — meaning we couldn't
     * determine
     * the actual type, so we should not warn about boolean conditions.
     */
    public static boolean isObjectType(String desc) {
        if (desc == null) return false;
        return OceanTypeSystem.OBJECT_DESC.equals(cleanDescriptor(desc));
    }

    public static boolean isStringType(String desc) {
        if (desc == null) return false;
        return OceanTypeSystem.STRING_DESC.equals(cleanDescriptor(desc));
    }

    public static boolean isNumericPrimitive(String desc) {
        if (desc == null || desc.length() != 1) return false;
        return "BCSIJFD".indexOf(desc.charAt(0)) >= 0; // boolean hariç
    }

    /**
     * Sayısal tür kontrolü (İlkel ve Boxed sayısal türler + BigDecimal).
     */
    public static boolean isNumeric(String desc) {
        if (desc == null) return false;
        if (desc.endsWith("?")) desc = desc.substring(0, desc.length() - 1);
        if (desc.startsWith("L") && desc.endsWith(";")) {
            desc = desc.substring(1, desc.length() - 1);
        }
        return switch (desc) {
            case "B", "S", "I", "J", "F", "D", "C",
                 "java/lang/Byte", "java/lang/Short", "java/lang/Integer",
                 "java/lang/Long", "java/lang/Float", "java/lang/Double",
                 "java/math/BigDecimal", "BigDecimal" -> true;
            default -> false;
        };
    }

    /**
     * Tamsayı türü kontrolü.
     */
    public static boolean isIntegerType(String desc) {
        if (desc == null) return false;
        if (desc.endsWith("?")) desc = desc.substring(0, desc.length() - 1);
        if (desc.startsWith("L") && desc.endsWith(";")) {
            desc = desc.substring(1, desc.length() - 1);
        }
        return switch (desc) {
            case "I", "J", "B", "S", "C",
                 "java/lang/Integer", "java/lang/Long", "java/lang/Byte",
                 "java/lang/Short", "java/lang/Character" -> true;
            default -> false;
        };
    }

    /**
     * Dizi indeksi ve boyutu için geçerli tamsayı türü kontrolü.
     * Yalnızca int ve int'e dönüştürülebilen türler (int, byte, short, char ve wrapper karşılıkları).
     */
    public static boolean isValidArrayIndexType(String desc) {
        if (desc == null) return false;
        if (desc.endsWith("?")) desc = desc.substring(0, desc.length() - 1);
        if (desc.startsWith("L") && desc.endsWith(";")) {
            desc = desc.substring(1, desc.length() - 1);
        }
        return switch (desc) {
            case "I", "B", "S", "C",
                 "java/lang/Integer", "java/lang/Byte",
                 "java/lang/Short", "java/lang/Character" -> true;
            default -> false;
        };
    }

    public static boolean canWiden(String from, String to) {
        if (from.equals(to)) return true;
        return switch (from) {
            case "B" -> "SIJFD".contains(to);
            case "S", "C" -> "IJFD".contains(to);
            case "I" -> "JFD".contains(to);
            case "J" -> "FD".contains(to);
            case "F" -> "D".equals(to);
            default -> false;
        };
    }

    public static boolean isBoxedEquivalent(String a, String b) {
        if (a == null || b == null) return false;
        String boxedB = box(b);
        if (boxedB != null && boxedB.equals(a)) return true;
        String boxedA = box(a);
        return boxedA != null && boxedA.equals(b);
    }

    /**
     * Primitif → Boxed dönüşümü.
     */
    public static String box(String primitiveDesc) {
        return OceanTypeSystem.box(primitiveDesc);
    }

    /**
     * Boxed → Primitif dönüşümü.
     */
    public static String unbox(String boxedDesc) {
        return OceanTypeSystem.unbox(boxedDesc);
    }

    /**
     * JVM descriptor'ını insan okunabilir isime çevirir.
     */
    public static String humanReadable(String desc) {
        return OceanTypeSystem.humanReadable(cleanDescriptor(desc));
    }

    /**
     * Returns true when {@code desc} refers to a SAM (functional) interface.
     * Uses the dynamic registry populated by PreScanner + CompilerRegistry.seedDynamicDefaults(),
     * so that user-defined @FunctionalInterface types are also recognised.
     */
    public static boolean isFunctionalInterface(String desc) {
        if (desc == null) return false;
        if (isCommonFunctionalInterface(desc)) return true;
        String internalName = getInternalName(desc);
        if (internalName == null) return false;
        // 1. Check registry (seeded with JDK types + user-defined interfaces)
        if (CompilerRegistry.globalFunctionalInterfaceRegistry.contains(internalName)) return true;
        // 2. Reflection fallback for JDK types not yet in the registry
        return OceanTypeSystem.hasSingleAbstractMethod(internalName);
    }

    private static boolean isCommonFunctionalInterface(String desc) {
        if (desc == null || !desc.startsWith("L")) return false;
        String clean = TypeChecker.cleanDescriptor(desc);
        if (!TypeChecker.isClassType(clean)) return false;
        String internal = clean.substring(1, clean.length() - 1);
        if ("java/lang/Object".equals(internal) || "java/lang/String".equals(internal)) return false;
        switch (internal) {
            case "java/lang/Runnable",
                 "java/util/function/Supplier",
                 "java/util/function/Consumer",
                 "java/util/function/Function",
                 "java/util/function/BiConsumer",
                 "java/util/function/BiFunction",
                 "java/util/function/Predicate",
                 "java/util/function/BiPredicate",
                 "java/util/function/UnaryOperator",
                 "java/util/function/BinaryOperator",
                 "java/util/function/IntSupplier",
                 "java/util/function/IntConsumer",
                 "java/util/function/IntFunction",
                 "java/util/function/IntPredicate",
                 "java/util/function/IntUnaryOperator",
                 "java/util/function/IntBinaryOperator",
                 "java/util/function/LongSupplier",
                 "java/util/function/LongConsumer",
                 "java/util/function/LongFunction",
                 "java/util/function/LongPredicate",
                 "java/util/function/LongUnaryOperator",
                 "java/util/function/LongBinaryOperator",
                 "java/util/function/DoubleSupplier",
                 "java/util/function/DoubleConsumer",
                 "java/util/function/DoubleFunction",
                 "java/util/function/DoublePredicate",
                 "java/util/function/DoubleUnaryOperator",
                 "java/util/function/DoubleBinaryOperator" -> {
                return true;
            }
        }
        return OceanTypeSystem.getSingleAbstractMethodInfo(internal) != null
                || OceanTypeSystem.hasSingleAbstractMethod(internal);
    }


    /** @deprecated Preserved for binary compatibility; use isFunctionalInterface() internally. */
    @Deprecated
    @SuppressWarnings("unused")
    private static boolean isLambdaType(String desc) {
        return isFunctionalInterface(desc);
    }

    /**
     * Returns true when {@code source} and {@code target} are considered compatible
     * because they belong to the same collection family (both lists/collections or both maps).
     * Evaluated 100% dynamically via session type hierarchy and reflection without hardcoded class name lists.
     */
    public static boolean isCollectionCompatible(String source, String target) {

        String src = isClassType(source) ? source.substring(1, source.length() - 1) : source;
        String tgt = isClassType(target) ? target.substring(1, target.length() - 1) : target;

        CompilationSession s = CompilationSession.getActiveSession();

        boolean srcIsList = isListOrCollectionType(src, s);
        boolean tgtIsList = isListOrCollectionType(tgt, s);
        boolean bothLists = srcIsList && tgtIsList;

        boolean srcIsMap = isMapType(src, s);
        boolean tgtIsMap = isMapType(tgt, s);
        boolean bothMaps = srcIsMap && tgtIsMap;
        return s != null && s.isSubType(src, tgt) && (bothLists || bothMaps);// || isObjectType(source);
    }

    public static boolean isListOrCollectionType(String desc) {
        if (desc == null || desc.isEmpty()) return false;
        String clean = cleanDescriptor(desc);
        if (clean == null || clean.isEmpty()) return false;
        if (isClassType(clean)) {
            clean = clean.substring(1, clean.length() - 1);
        }
        return isListOrCollectionType(clean, CompilationSession.getActiveSession());
    }

    /*public static boolean isListOrCollectionType(String internalName, CompilationSession session) {
        if (internalName == null || internalName.isEmpty()) return false;
        String clean = cleanDescriptor(internalName);
        if (clean == null || clean.isEmpty()) return false;
        if (isClassType(clean)) {
            clean = clean.substring(1, clean.length() - 1);
        }
        String stdCandidate = OceanTypeSystem.resolveStandardClassPath(clean);
        if (stdCandidate != null) {
            clean = stdCandidate;
        }
        if (clean.equals("ocean/stdlib/OceanList") || clean.equals("OceanList") ||
            clean.equals("java/util/List") || clean.equals("List") ||
            clean.equals("java/util/ArrayList") || clean.equals("ArrayList") ||
            clean.equals("java/util/LinkedList") || clean.equals("LinkedList") ||
            clean.equals("java/util/Set") || clean.equals("Set") ||
            clean.equals("java/util/HashSet") || clean.equals("HashSet") ||
            clean.equals("java/util/LinkedHashSet") || clean.equals("LinkedHashSet") ||
            clean.equals("java/util/TreeSet") || clean.equals("TreeSet") ||
            clean.equals("java/util/Queue") || clean.equals("Queue") ||
            clean.equals("java/util/Deque") || clean.equals("Deque") ||
            clean.equals("java/util/ArrayDeque") || clean.equals("ArrayDeque") ||
            clean.equals("java/util/Collection") || clean.equals("Collection") ||
            (clean.startsWith("ocean/stdlib/Ocean") && clean.endsWith("List"))) {
            return true;
        }
        if (CompilerRegistry.globalListLikeOwnerRegistry.contains(clean)) return true;
        if (session != null) {
            if (session.isSubType(clean, "java/util/Collection") ||
                session.isSubType(clean, "java/util/Iterable") ||
                session.isSubType(clean, "ocean/stdlib/OceanList")) {
                return true;
            }
        }
        return OceanTypeSystem.isSubtypeOfReflection(clean, "java.util.Collection") ||
               OceanTypeSystem.isSubtypeOfReflection(clean, "java.lang.Iterable") ||
               OceanTypeSystem.isSubtypeOfReflection(clean, "ocean.stdlib.OceanList");
    }*/
    public static boolean isListOrCollectionType(String internalNameOrDesc, CompilationSession session) {

        if (internalNameOrDesc == null || internalNameOrDesc.isEmpty()) {
            return false;
        }

        String clean = cleanDescriptor(internalNameOrDesc);

        if (clean == null || clean.isEmpty()) {
            return false;
        }

        if (isClassType(clean)) {
            clean = clean.substring(1, clean.length() - 1);
        }

        String stdCandidate = OceanTypeSystem.resolveStandardClassPath(clean);
        if (stdCandidate != null) {
            clean = stdCandidate;
        }
        if (session != null) {
            if (session.isSubType(clean, "java/util/Collection")) return true;
        }

        return OceanTypeSystem.isSubtypeOfReflection(clean, "java.util.Collection");
    }

    public static boolean isMapType(String desc) {
        if (desc == null || desc.isEmpty()) return false;
        String clean = cleanDescriptor(desc);
        if (clean == null || clean.isEmpty()) return false;
        if (isClassType(clean)) {
            clean = clean.substring(1, clean.length() - 1);
        }
        return isMapType(clean, CompilationSession.getActiveSession());
    }

    public static boolean isMapType(String internalName, CompilationSession session) {
        if (internalName == null) return false;
        if (CompilerRegistry.globalMapLikeOwnerRegistry.contains(internalName)) return true;
        String stdCandidate = OceanTypeSystem.resolveStandardClassPath(internalName);
        if (stdCandidate != null) {
            internalName = stdCandidate;
        }
        if (session != null) {
            if (session.isSubType(internalName, "java/util/Map")) return true;
        }
        return OceanTypeSystem.isSubtypeOfReflection(internalName, "java.util.Map");
    }

    public static boolean isSetType(String desc) {
        if (desc == null || desc.isEmpty()) return false;
        String clean = cleanDescriptor(desc);
        if (clean == null || clean.isEmpty()) return false;
        if (isClassType(clean)) {
            clean = clean.substring(1, clean.length() - 1);
        }
        return isSetType(clean, CompilationSession.getActiveSession());
    }

    public static boolean isSetType(String internalName, CompilationSession session) {
        if (internalName == null) return false;
        String stdCandidate = OceanTypeSystem.resolveStandardClassPath(internalName);
        if (stdCandidate != null) {
            internalName = stdCandidate;
        }
        if (session != null) {
            if (session.isSubType(internalName, "java/util/Set")) return true;
        }
        return OceanTypeSystem.isSubtypeOfReflection(internalName, "java.util.Set");
    }

    public static boolean isSpecializedPrimitiveList(String desc) {
        if (desc == null || desc.isEmpty()) return false;
        String clean = cleanDescriptor(desc);
        if (clean == null || clean.isEmpty()) return false;
        if (isClassType(clean)) {
            clean = clean.substring(1, clean.length() - 1);
        }
        return clean.equals("ocean/stdlib/OceanIntList") || clean.equals("OceanIntList")
                || clean.equals("ocean/stdlib/OceanLongList") || clean.equals("OceanLongList")
                || clean.equals("ocean/stdlib/OceanDoubleList") || clean.equals("OceanDoubleList")
                || clean.equals("ocean/stdlib/OceanFloatList") || clean.equals("OceanFloatList")
                || clean.equals("ocean/stdlib/OceanBooleanList") || clean.equals("OceanBooleanList")
                || clean.equals("ocean/stdlib/OceanByteList") || clean.equals("OceanByteList")
                || clean.equals("ocean/stdlib/OceanShortList") || clean.equals("OceanShortList")
                || clean.equals("ocean/stdlib/OceanCharList") || clean.equals("OceanCharList");
    }

    public static String getSpecializedPrimitiveListElementType(String desc) {
        if (desc == null || desc.isEmpty()) return null;
        String clean = cleanDescriptor(desc);
        if (clean == null || clean.isEmpty()) return null;
        if (isClassType(clean)) {
            clean = clean.substring(1, clean.length() - 1);
        }
        return switch (clean) {
            case "ocean/stdlib/OceanIntList", "OceanIntList" -> "I";
            case "ocean/stdlib/OceanLongList", "OceanLongList" -> "J";
            case "ocean/stdlib/OceanDoubleList", "OceanDoubleList" -> "D";
            case "ocean/stdlib/OceanFloatList", "OceanFloatList" -> "F";
            case "ocean/stdlib/OceanBooleanList", "OceanBooleanList" -> "Z";
            case "ocean/stdlib/OceanByteList", "OceanByteList" -> "B";
            case "ocean/stdlib/OceanShortList", "OceanShortList" -> "S";
            case "ocean/stdlib/OceanCharList", "OceanCharList" -> "C";
            default -> null;
        };
    }

    public static boolean isBigDecimalType(String desc) {
        if (desc == null || desc.isEmpty()) return false;
        String clean = cleanDescriptor(desc);
        if (clean == null || clean.isEmpty()) return false;
        if (isClassType(clean)) {
            clean = clean.substring(1, clean.length() - 1);
        }
        return clean.equals("java/math/BigDecimal");
    }

    /**
     * Returns true if {@code desc} refers to {@code java.util.concurrent.CompletableFuture}
     * or {@code java.util.concurrent.Future} (or a subtype thereof).
     * Uses exact name matching first, then delegate subtype check, to avoid false positives
     * on user-defined classes whose names happen to contain "Future".
     */
    public static boolean isFutureType(String desc) {
        return isFutureType(desc, null);
    }

    public static boolean isFutureType(String desc, CompilationSession session) {
        if (desc == null || desc.isEmpty()) return false;
        String clean = cleanDescriptor(desc);
        if (clean == null || clean.isEmpty() || clean.startsWith("(") || isPrimitive(clean) || clean.startsWith("[")) return false;
        // Strip generic parameter if present (e.g. "java/util/concurrent/CompletableFuture<LFoo;>")
        int ltIdx = clean.indexOf('<');
        if (ltIdx >= 0) clean = clean.substring(0, ltIdx);
        if (isClassType(clean)) {
            clean = clean.substring(1, clean.length() - 1);
        }
        if (clean.equals("java/util/concurrent/CompletableFuture") || clean.equals("java/util/concurrent/Future")) {
            return true;
        }
        if (session != null) {
            if (session.isSubType(clean, "java/util/concurrent/Future") || session.isSubType(clean, "java/util/concurrent/CompletableFuture")) {
                return true;
            }
        }
        return OceanTypeSystem.isSubtypeOfReflection(clean, "java.util.concurrent.Future");
    }

    public static boolean isClassType(String desc) {
        if (desc == null) return false;
        return desc.startsWith("L") && desc.endsWith(";");
    }

    public static int getArrayDimensions(String desc) {
        if (desc == null) return 0;
        int dims = 0;
        while (dims < desc.length() && desc.charAt(dims) == '[') {
            dims++;
        }
        return dims;
    }

    public static boolean checkBoundForPrimary(String desc, String value) {
        if (desc == null || value == null) {
            return false;
        }

        switch (desc) {

            case "B","java/lang/Byte",
                 "java.lang.Byte"  -> {
                try {
                    Byte.parseByte(value);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            }

            case "S", "java/lang/Short", "java.lang.Short" -> {
                try {
                    Short.parseShort(value);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            }

            case "I", "java/lang/Integer", "java.lang.Integer" -> {
                try {
                    Integer.parseInt(value);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            }

            case "J", "java/lang/Long", "java.lang.Long" -> {
                try {
                    Long.parseLong(value);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            }

            case "C", "java/lang/Character", "java.lang.Character" -> {
                try {
                    int val = Integer.parseInt(value);
                    return val >= Character.MIN_VALUE
                            && val <= Character.MAX_VALUE;
                } catch (NumberFormatException e) {
                    return false;
                }
            }

            case "F", "java/lang/Float", "java.lang.Float" -> {
                try {
                    float val = Float.parseFloat(value);
                    return Float.isFinite(val);
                } catch (NumberFormatException e) {
                    return false;
                }
            }

            case "D", "java/lang/Double", "java.lang.Double" -> {
                try {
                    double val = Double.parseDouble(value);
                    return Double.isFinite(val);
                } catch (NumberFormatException e) {
                    return false;
                }
            }

            default -> {
                return false;
            }
        }
    }}
