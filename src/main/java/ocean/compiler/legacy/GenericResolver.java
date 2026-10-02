package ocean.compiler.legacy;

import ocean.compiler.CompilationSession;
import ocean.compiler.CompilerRegistry;
import ocean.compiler.OceanTypeSystem;
import ocean.compiler.TypeChecker;

import java.util.*;

/**
 * Jenerik tip parametrelerini Ã§Ã¶zÃ¼mleyen ve doÄŸrulayan yardÄ±mcÄ± sÄ±nÄ±f.
 * GÃ¶revleri:
 * 1. Bounded type parameters doÄŸrulama (T extends Number)
 * 2. Wildcard Ã§Ã¶zÃ¼mleme (? extends X, ? super X)
 * 3. Type erasure (jenerik â†’ somut tip) dÃ¶nÃ¼ÅŸÃ¼mÃ¼
 * 4. Jenerik instantiation takibi (Box<String> â†’ T=String)
 */
public class GenericResolver {

    /**
     * Aktif tip baÄŸlamalarÄ±: T â†’ String, E â†’ Integer vb.
     * Her jenerik sÄ±nÄ±f/metot Ã§aÄŸrÄ±sÄ±nda yeniden doldurulur.
     */
    private final Map<String, String> typeBindings = new HashMap<>();

    /**
     * SÄ±nÄ±fÄ±n tip parametre tanÄ±mlarÄ± (bounds dahil).
     */
    private final List<CompilerRegistry.TypeParameterInfo> typeParameters;

    public GenericResolver(List<CompilerRegistry.TypeParameterInfo> typeParameters,
                           Map<String, String> importedClasses) {
        this.typeParameters = typeParameters != null ? typeParameters : Collections.emptyList();
        Map<String, String> importedClasses1 = importedClasses != null ? importedClasses : Collections.emptyMap();
    }

    // ========== Tip BaÄŸlama (Binding) ==========

    /**
     * Tip argÃ¼manlarÄ±nÄ± parametrelere baÄŸlar.
     * Ã–rn: class Box<T extends Number> â†’ Box<Integer> â†’ T=Integer
     */
    public void bind(List<String> typeArguments) {
        typeBindings.clear();
        for (int i = 0; i < typeParameters.size() && i < typeArguments.size(); i++) {
            CompilerRegistry.TypeParameterInfo param = typeParameters.get(i);
            if (!param.isWildcard) {
                typeBindings.put(param.name, typeArguments.get(i));
            }
        }
    }

    /**
     * Tek tip parametresi baÄŸlar.
     */
    public void bindSingle(String paramName, String concreteType) {
        typeBindings.put(paramName, concreteType);
    }

    /**
     * BaÄŸlÄ± tipi Ã§Ã¶zer. BaÄŸlÄ± deÄŸilse orijinal tipi dÃ¶ndÃ¼rÃ¼r.
     * T â†’ String (baÄŸlÄ±ysa), T â†’ T (baÄŸlÄ± deÄŸilse)
     */
    public String resolve(String typeParam) {
        if (typeParam == null) return null;
        return typeBindings.getOrDefault(typeParam, typeParam);
    }

    // ========== Bound DoÄŸrulama ==========

    /**
     * Verilen somut tipin, tip parametresinin bound'larÄ±nÄ± karÅŸÄ±layÄ±p karÅŸÄ±lamadÄ±ÄŸÄ±nÄ± kontrol eder.
     *
     * @param paramName Tip parametre adÄ± (T, E, K, V vb.)
     * @param concreteType Somut tip descriptor'Ä±
     * @return null ise geÃ§erli, String ise hata mesajÄ±
     */
    public String validateBound(String paramName, String concreteType) {
        CompilerRegistry.TypeParameterInfo param = findParam(paramName);
        if (param == null) return null;

        // Upper bound kontrolü: T extends Number → concreteType Number veya alt sınıfı olmalı
        if (param.upperBound != null) {
            if (!TypeChecker.isAssignable(param.upperBound, concreteType, CompilationSession.getActiveSession())) {
                return "Tip '" + TypeChecker.humanReadable(concreteType)
                        + "', bound '" + TypeChecker.humanReadable(param.upperBound) + "' ile uyumlu değil";
            }
        }

        // Lower bound kontrolü: T super Integer → concreteType Integer veya üst sınıfı olmalı
        if (param.lowerBound != null) {
            if (!TypeChecker.isAssignable(concreteType, param.lowerBound, CompilationSession.getActiveSession())) {
                return "Tip '" + TypeChecker.humanReadable(concreteType)
                        + "', alt bound '" + TypeChecker.humanReadable(param.lowerBound) + "' ile uyumlu değil";
            }
        }

        // Primitif tipler jenerik olarak kullanılamaz
        if (TypeChecker.isPrimitive(concreteType)) {
            return "Primitif tip '" + TypeChecker.humanReadable(concreteType)
                    + "' jenerik parametre olarak kullanılamaz, boxed versiyonunu kullanın";
        }

        return null; // Geçerli
    }

    /**
     * Wildcard parametresinin bound uyumluluğunu kontrol eder.
     */
    public String validateWildcardBound(CompilerRegistry.TypeParameterInfo wildcardParam, String concreteType) {
        if (!wildcardParam.isWildcard) return null;

        if (wildcardParam.upperBound != null) {
            // ? extends Number → concreteType Number veya alt sınıfı olmalı
            if (!TypeChecker.isAssignable(wildcardParam.upperBound, concreteType, CompilationSession.getActiveSession())) {
                return "Tip '" + TypeChecker.humanReadable(concreteType)
                        + "', '? extends " + TypeChecker.humanReadable(wildcardParam.upperBound) + "' ile uyumlu değil";
            }
        }
        if (wildcardParam.lowerBound != null) {
            // ? super Integer → concreteType Integer veya üst sınıfı olmalı
            if (!TypeChecker.isAssignable(concreteType, wildcardParam.lowerBound, CompilationSession.getActiveSession())) {
                return "Tip '" + TypeChecker.humanReadable(concreteType)
                        + "', '? super " + TypeChecker.humanReadable(wildcardParam.lowerBound) + "' ile uyumlu değil";
            }
        }

        return null;
    }

    // ========== Type Erasure ==========

    /**
     * Tip parametresini erasure sonrasÄ± somut tipe dÃ¶nÃ¼ÅŸtÃ¼rÃ¼r.
     * T extends Number â†’ Number
     * T â†’ Object
     * ? extends Comparable â†’ Comparable
     */
    public String erase(String typeParamName) {
        // Ã–nce baÄŸlÄ± tipi kontrol et
        String bound = typeBindings.get(typeParamName);
        if (bound != null) return bound;

        // Parametre tanÄ±mÄ±ndan erasure
        CompilerRegistry.TypeParameterInfo param = findParam(typeParamName);
        if (param != null) {
            return param.getErasedType();
        }

        return OceanTypeSystem.OBJECT_DESC;
    }

    /**
     * TÃ¼m tip parametreleri iÃ§in erasure haritasÄ± oluÅŸturur.
     */
    public Map<String, String> getErasureMap() {
        Map<String, String> map = new HashMap<>();
        for (CompilerRegistry.TypeParameterInfo param : typeParameters) {
            if (!param.isWildcard) {
                String resolved = typeBindings.getOrDefault(param.name, param.getErasedType());
                map.put(param.name, resolved);
            }
        }
        return map;
    }

    // ========== JVM Signature Ãœretimi ==========

    /**
     * JVM generic signature Ã¼retir.
     * Ã–rn: <T:Ljava/lang/Number;>Ljava/lang/Object;
     */
    public String buildClassSignature(String superClass) {
        if (typeParameters.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("<");
        for (CompilerRegistry.TypeParameterInfo param : typeParameters) {
            if (param.isWildcard) continue;
            sb.append(param.name);
            sb.append(":");
            sb.append(Objects.requireNonNullElse(param.upperBound, OceanTypeSystem.OBJECT_DESC));
        }
        sb.append(">L");
        sb.append(superClass != null ? superClass : "java/lang/Object");
        sb.append(";");
        return sb.toString();
    }

    /**
     * Metot generic signature Ã¼retir.
     */
    public String buildMethodSignature(List<String> paramTypes, String returnType) {
        if (typeBindings.isEmpty() && typeParameters.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("(");
        for (String pt : paramTypes) {
            sb.append(resolveOrKeep(pt));
        }
        sb.append(")");
        sb.append(resolveOrKeep(returnType));
        return sb.toString();
    }

    // ========== YardÄ±mcÄ± ==========

    private CompilerRegistry.TypeParameterInfo findParam(String name) {
        for (CompilerRegistry.TypeParameterInfo param : typeParameters) {
            if (param.name.equals(name)) return param;
        }
        return null;
    }

    private String resolveOrKeep(String type) {
        if (type == null) return "V";
        String resolved = typeBindings.get(type);
        return resolved != null ? resolved : type;
    }

    /**
     * Verilen ismin bir tip parametresi olup olmadÄ±ÄŸÄ±nÄ± kontrol eder.
     */
    public boolean isTypeParameter(String name) {
        return findParam(name) != null;
    }

    /**
     * Aktif baÄŸlamalarÄ± dÃ¶ndÃ¼rÃ¼r.
     */
    public Map<String, String> getBindings() {
        return Collections.unmodifiableMap(typeBindings);
    }

    /**
     * BaÄŸlamalarÄ± temizler.
     */
    public void clearBindings() {
        typeBindings.clear();
    }
}
