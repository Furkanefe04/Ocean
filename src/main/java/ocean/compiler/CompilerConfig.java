package ocean.compiler;

import org.objectweb.asm.Opcodes;

import java.util.Map;

/**
 * Derleyici genelindeki sabit konfigürasyon değerlerini tutan merkezi sınıf.
 * <p>
 * Hardcoded string ve sayısal değerlerin <b>tek kaynağıdır (single source of truth)</b>.
 * Paket yapısı, JVM hedef sürümü veya tip takma adları değiştirilmek istendiğinde
 * yalnızca bu sınıfın güncellenmesi yeterlidir.
 */
public final class CompilerConfig {

    private CompilerConfig() {}

    // ═══════════════════════════════════════════════════════════════════════
    //  JVM Bytecode Hedef Sürümü
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Dil ve derleyici tanımlayıcı bilgileri.
     */
    public static final String LANGUAGE_NAME = "ocean";
    public static final String COMPILER_VERSION = "1.0.0";
    public static final int LANGUAGE_VERSION = 1;

    /**
     * Üretilen .class dosyaları için JVM bytecode hedef sürümü.
     * Varsayılan: {@code Opcodes.V17} (JVM 17 baytkodu, Java 17, 21 ve üzeri ile uyumlu).
     */
    public static final int BYTECODE_VERSION = Opcodes.V17;

    // ═══════════════════════════════════════════════════════════════════════
    //  Ocean Tip Takma Adları
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Ocean diline özgü tip takma adlarının JVM iç yollarına eşlemesi.
     * <p>
     * Bu harita hem {@link IRGenerator} hem de {@link SymbolTable} tarafından
     * kullanılır; tanımın tek bir yerde olması tutarsızlık riskini ortadan kaldırır.
     *
     * <pre>
     *   OceanMap    → java/util/HashMap
     *   OceanOutput → java/io/PrintStream
     *   OceanInput  → java/util/Scanner
     *   variable    → java/lang/Object
     *   value       → java/lang/Object
     *   var         → java/lang/Object
     *   ...
     * </pre>
     * varsayılan olarak devre dışı
     */
    public static final Map<String, String> OCEAN_TYPE_ALIASES;

    static {
        OCEAN_TYPE_ALIASES = Map.of("variable", "java/lang/Object", "value", "java/lang/Object", "OceanOutput", "java/io/PrintStream", "OceanInput", "java/util/Scanner");
    }
}
