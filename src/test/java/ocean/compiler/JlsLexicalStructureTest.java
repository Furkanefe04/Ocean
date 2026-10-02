package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JlsLexicalStructureTest extends CompilerTestHelper {

    @BeforeEach
    void setup() {
        CompilerRegistry.clearAll();
        CompilerReporter.clear();
        ClassMetadataCache.clearCaches();
    }

    @Test
    @DisplayName("Hexadecimal and Binary integer literals")
    void testHexAndBinaryIntegerLiterals() throws Exception {
        Class<?> clazz = compileAndLoad("HexBinTest", """
                public class HexBinTest {
                    public static int function getHex() {
                        return 0x1A;
                    }
                    public static int function getHexCapital() {
                        return 0XFF;
                    }
                    public static long function getHexLong() {
                        return 0xCAFE_BABEL;
                    }
                    public static int function getBin() {
                        return 0b1010;
                    }
                    public static long function getBinLong() {
                        return 0B1100_0011L;
                    }
                }
                """);
        assertEquals(26, clazz.getMethod("getHex").invoke(null));
        assertEquals(255, clazz.getMethod("getHexCapital").invoke(null));
        assertEquals(0xCAFE_BABEL, clazz.getMethod("getHexLong").invoke(null));
        assertEquals(10, clazz.getMethod("getBin").invoke(null));
        assertEquals(0b1100_0011L, clazz.getMethod("getBinLong").invoke(null));
    }

    @Test
    @DisplayName("Octal and underscore numeric literals")
    void testOctalAndUnderscoreLiterals() throws Exception {
        Class<?> clazz = compileAndLoad("OctalUnderscoreTest", """
                public class OctalUnderscoreTest {
                    public static int function getOctal() {
                        return 077;
                    }
                    public static int function getOctalUnderscore() {
                        return 0_123;
                    }
                    public static int function getDecimalUnderscore() {
                        return 1_000_000;
                    }
                }
                """);
        assertEquals(63, clazz.getMethod("getOctal").invoke(null));
        assertEquals(83, clazz.getMethod("getOctalUnderscore").invoke(null));
        assertEquals(1000000, clazz.getMethod("getDecimalUnderscore").invoke(null));
    }

    @Test
    @DisplayName("Scientific notation floating-point literals")
    void testScientificFloatingPointLiterals() throws Exception {
        Class<?> clazz = compileAndLoad("ScientificFloatTest", """
                public class ScientificFloatTest {
                    public static double function getExp1() {
                        return 1e5;
                    }
                    public static double function getExpNegative() {
                        return 1.5e-3;
                    }
                    public static float function getExpFloat() {
                        return 2.0e4f;
                    }
                    public static double function getFloatUnderscore() {
                        return 1_000.5_000;
                    }
                }
                """);
        assertEquals(100000.0, (double) clazz.getMethod("getExp1").invoke(null), 1e-6);
        assertEquals(0.0015, (double) clazz.getMethod("getExpNegative").invoke(null), 1e-6);
        assertEquals(20000.0f, (float) clazz.getMethod("getExpFloat").invoke(null), 1e-4);
        assertEquals(1000.5, (double) clazz.getMethod("getFloatUnderscore").invoke(null), 1e-6);
    }

    @Test
    @DisplayName("Hexadecimal floating-point literals")
    void testHexadecimalFloatingPointLiterals() throws Exception {
        Class<?> clazz = compileAndLoad("HexFloatTest", """
                public class HexFloatTest {
                    public static double function getHexFloat1() {
                        return 0x1.0p0;
                    }
                    public static double function getHexFloatExp() {
                        return 0x1.fp3;
                    }
                    public static float function getHexFloatF() {
                        return 0x1.0p-2f;
                    }
                }
                """);
        assertEquals(1.0, (double) clazz.getMethod("getHexFloat1").invoke(null), 1e-6);
        assertEquals(15.5, (double) clazz.getMethod("getHexFloatExp").invoke(null), 1e-6);
        assertEquals(0.25f, (float) clazz.getMethod("getHexFloatF").invoke(null), 1e-6);
    }

    @Test
    @DisplayName("§3.10.7: Octal escape sequences in character and string literals")
    void testOctalEscapesInCharAndString() throws Exception {
        Class<?> clazz = compileAndLoad("OctalEscapeTest", """
                public class OctalEscapeTest {
                    public static char function getCharOctalA() {
                        return '\\101';
                    }
                    public static char function getCharOctalQuestion() {
                        return '\\77';
                    }
                    public static String function getStringOctal() {
                        return "Hello \\101\\102\\103 World";
                    }
                }
                """);
        assertEquals('A', clazz.getMethod("getCharOctalA").invoke(null));
        assertEquals('?', clazz.getMethod("getCharOctalQuestion").invoke(null));
        assertEquals("Hello ABC World", clazz.getMethod("getStringOctal").invoke(null));
    }
}
