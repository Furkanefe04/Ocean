package ocean.utils;
/**
 * Sabit metin (literal) değerlerinden primitif ve nesne tiplerini çıkaran statik yardımcı sınıf.
 */
public class TypeInference {

    public static String inferType(String value) {
        if (value == null) return "Object";

        if (value.matches("^\".*\"$")) return "String";           // String literal
        if (value.matches("^[0-9]+$")) return "int";              // Tam sayı
        if (value.matches("^[0-9]*\\.[0-9]+$")) return "double";  // Ondalıklı sayı
        if (value.equals("true") || value.equals("false")) return "boolean"; // Boolean
        if (value.matches("^'.'$")) return "char";                // Char literal

        // Varsayılan
        return "Object";
    }
}