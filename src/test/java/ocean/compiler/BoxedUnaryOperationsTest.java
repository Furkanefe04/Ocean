package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoxedUnaryOperationsTest extends CompilerTestHelper {

    @Test
    @DisplayName("Unary negation on Boxed Integer (-Integer)")
    void testNegateBoxedInteger() throws Exception {
        Class<?> clazz = compileAndLoad("NegBoxedInt", """
                public class NegBoxedInt {
                    public static int function test() {
                        Integer a = 42;
                        variable b = -a;
                        return b;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(-42, m.invoke(null));
    }

    @Test
    @DisplayName("Bitwise NOT on Boxed Integer (~Integer)")
    void testBitNotBoxedInteger() throws Exception {
        Class<?> clazz = compileAndLoad("BitNotBoxedInt", """
                public class BitNotBoxedInt {
                    public static int function test() {
                        Integer a = 0;
                        variable b = ~a;
                        return b;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(-1, m.invoke(null));
    }

    @Test
    @DisplayName("Unary negation on Boxed Long (-Long)")
    void testNegateBoxedLong() throws Exception {
        Class<?> clazz = compileAndLoad("NegBoxedLong", """
                public class NegBoxedLong {
                    public static long function test() {
                        Long a = 1234567890123L;
                        variable b = -a;
                        return b;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(-1234567890123L, m.invoke(null));
    }

    @Test
    @DisplayName("Bitwise NOT on Boxed Long (~Long)")
    void testBitNotBoxedLong() throws Exception {
        Class<?> clazz = compileAndLoad("BitNotBoxedLong", """
                public class BitNotBoxedLong {
                    public static long function test() {
                        Long a = 0L;
                        variable b = ~a;
                        return b;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(-1L, m.invoke(null));
    }

    @Test
    @DisplayName("Unary negation on Boxed Double (-Double)")
    void testNegateBoxedDouble() throws Exception {
        Class<?> clazz = compileAndLoad("NegBoxedDouble", """
                public class NegBoxedDouble {
                    public static double function test() {
                        Double a = 3.14159;
                        variable b = -a;
                        return b;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(-3.14159, (double) m.invoke(null), 1e-5);
    }

    @Test
    @DisplayName("Unary negation on Boxed Float (-Float)")
    void testNegateBoxedFloat() throws Exception {
        Class<?> clazz = compileAndLoad("NegBoxedFloat", """
                public class NegBoxedFloat {
                    public static float function test() {
                        Float a = 2.5f;
                        variable b = -a;
                        return b;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(-2.5f, (float) m.invoke(null), 1e-5);
    }

    @Test
    @DisplayName("Unary negation on Boxed Byte (-Byte promotes to int)")
    void testNegateBoxedByte() throws Exception {
        Class<?> clazz = compileAndLoad("NegBoxedByte", """
                public class NegBoxedByte {
                    public static int function test() {
                        Byte a = (byte) 10;
                        variable b = -a;
                        return b;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(-10, m.invoke(null));
    }

    @Test
    @DisplayName("Bitwise NOT on Boxed Byte (~Byte promotes to int)")
    void testBitNotBoxedByte() throws Exception {
        Class<?> clazz = compileAndLoad("BitNotBoxedByte", """
                public class BitNotBoxedByte {
                    public static int function test() {
                        Byte a = (byte) 0;
                        variable b = ~a;
                        return b;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(-1, m.invoke(null));
    }

    @Test
    @DisplayName("Unary negation on Boxed Short (-Short promotes to int)")
    void testNegateBoxedShort() throws Exception {
        Class<?> clazz = compileAndLoad("NegBoxedShort", """
                public class NegBoxedShort {
                    public static int function test() {
                        Short a = (short) 100;
                        variable b = -a;
                        return b;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(-100, m.invoke(null));
    }

    @Test
    @DisplayName("Bitwise NOT on Boxed Short (~Short promotes to int)")
    void testBitNotBoxedShort() throws Exception {
        Class<?> clazz = compileAndLoad("BitNotBoxedShort", """
                public class BitNotBoxedShort {
                    public static int function test() {
                        Short a = (short) 0;
                        variable b = ~a;
                        return b;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(-1, m.invoke(null));
    }

    @Test
    @DisplayName("Unary negation on method call returning Boxed Integer")
    void testNegateBoxedMethodCall() throws Exception {
        Class<?> clazz = compileAndLoad("NegBoxedCall", """
                public class NegBoxedCall {
                    public static Integer function getVal() {
                        return 50;
                    }

                    public static int function test() {
                        variable b = -getVal();
                        return b;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(-50, m.invoke(null));
    }
}
