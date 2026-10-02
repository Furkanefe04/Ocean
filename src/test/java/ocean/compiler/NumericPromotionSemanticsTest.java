package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NumericPromotionSemanticsTest extends CompilerTestHelper {

    @Test
    @DisplayName("byte + byte promotes to int  and avoids silent truncation")
    void testByteAddPromotesToInt() throws Exception {
        Class<?> clazz = compileAndLoad("ByteAddPromotion", """
                public class ByteAddPromotion {
                    public static int function add() {
                        byte a = 100;
                        byte b = 100;
                        variable c = a + b;
                        return c;
                    }
                }
                """);

        Method add = clazz.getMethod("add");
        assertEquals(200, add.invoke(null));
    }

    @Test
    @DisplayName("short + short promotes to int and avoids silent truncation")
    void testShortAddPromotesToInt() throws Exception {
        Class<?> clazz = compileAndLoad("ShortAddPromotion", """
                public class ShortAddPromotion {
                    public static int function add() {
                        short s1 = 30000;
                        short s2 = 30000;
                        variable s3 = s1 + s2;
                        return s3;
                    }
                }
                """);

        Method add = clazz.getMethod("add");
        assertEquals(60000, add.invoke(null));
    }

    @Test
    @DisplayName("char + char promotes to int")
    void testCharAddPromotesToInt() throws Exception {
        Class<?> clazz = compileAndLoad("CharAddPromotion", """
                public class CharAddPromotion {
                    public static int function add() {
                        char c1 = 'A';
                        char c2 = (char) 1;
                        variable c3 = c1 + c2;
                        return c3;
                    }
                }
                """);

        Method add = clazz.getMethod("add");
        assertEquals(66, add.invoke(null));
    }

    @Test
    @DisplayName("byte shift left promotes left operand to int")
    void testByteShiftPromotesToInt() throws Exception {
        Class<?> clazz = compileAndLoad("ByteShiftPromotion", """
                public class ByteShiftPromotion {
                    public static int function shift() {
                        byte b = 64;
                        variable r = b << 2;
                        return r;
                    }
                }
                """);

        Method shift = clazz.getMethod("shift");
        assertEquals(256, shift.invoke(null));
    }

    @Test
    @DisplayName("Unary minus and bitwise not on byte promotes to int")
    void testByteUnaryPromotesToInt() throws Exception {
        Class<?> clazz = compileAndLoad("ByteUnaryPromotion", """
                public class ByteUnaryPromotion {
                    public static int function neg() {
                        byte b = 10;
                        variable r = -b;
                        return r;
                    }

                    public static int function not() {
                        byte b = 0;
                        variable r = ~b;
                        return r;
                    }
                }
                """);

        Method neg = clazz.getMethod("neg");
        assertEquals(-10, neg.invoke(null));

        Method not = clazz.getMethod("not");
        assertEquals(-1, not.invoke(null));
    }

    @Test
    @DisplayName("Bitwise AND, OR, XOR on byte promotes to int")
    void testByteBitwisePromotesToInt() throws Exception {
        Class<?> clazz = compileAndLoad("ByteBitwisePromotion", """
                public class ByteBitwisePromotion {
                    public static int function testAnd() {
                        byte a = (byte) 255;
                        byte b = (byte) 15;
                        variable c = a & b;
                        return c;
                    }

                    public static int function testOr() {
                        byte a = 1;
                        byte b = 2;
                        variable c = a | b;
                        return c;
                    }

                    public static int function testXor() {
                        byte a = 3;
                        byte b = 2;
                        variable c = a ^ b;
                        return c;
                    }
                }
                """);

        assertEquals(15, clazz.getMethod("testAnd").invoke(null));
        assertEquals(3, clazz.getMethod("testOr").invoke(null));
        assertEquals(1, clazz.getMethod("testXor").invoke(null));
    }

    @Test
    @DisplayName("Assigning uncast int result of byte + byte to byte is rejected as lossy conversion")
    void testLossyConversionFromPromotedIntToByteRejected() {
        CompilationException ex = assertThrows(CompilationException.class, () ->
                compileAndLoad("LossyAssignReject", """
                        public class LossyAssignReject {
                            public static void function test() {
                                byte a = 10;
                                byte b = 20;
                                byte c = a + b;
                            }
                        }
                        """));

        assertTrue(ex.getMessage().contains("Incompatible types"),
                "Expected lossy conversion error but got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Explicit cast of (byte)(a + b) is accepted and compiles successfully")
    void testExplicitCastOfByteAddAccepted() throws Exception {
        Class<?> clazz = compileAndLoad("ExplicitCastByteAdd", """
                public class ExplicitCastByteAdd {
                    public static int function test() {
                        byte a = 10;
                        byte b = 20;
                        byte c = (byte) (a + b);
                        return (int) c;
                    }
                }
                """);

        Method m = clazz.getMethod("test");
        assertEquals(30, m.invoke(null));
    }

    @Test
    @DisplayName("Compound assignment byte += int still works via implicit narrowing")
    void testByteCompoundAssignmentWorks() throws Exception {
        Class<?> clazz = compileAndLoad("ByteCompoundAdd", """
                public class ByteCompoundAdd {
                    public static int function test() {
                        byte b = 10;
                        b += 5;
                        return (int) b;
                    }
                }
                """);

        Method m = clazz.getMethod("test");
        assertEquals(15, m.invoke(null));
    }
}
