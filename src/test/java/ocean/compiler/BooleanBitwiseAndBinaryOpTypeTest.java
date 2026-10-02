package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class BooleanBitwiseAndBinaryOpTypeTest extends CompilerTestHelper {

    // ==================== POSITIVE RUNTIME TESTS  ====================

    @Test
    @DisplayName("Boolean & operator returns boolean and executes logical AND")
    public void testBooleanBitAnd() throws Exception {
        String code = """
                public class BoolBitAndTest {
                    public static boolean function testAnd(boolean a, boolean b) {
                        return a & b;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("BoolBitAndTest", code);
        Method m = clazz.getMethod("testAnd", boolean.class, boolean.class);
        assertEquals(true, m.invoke(null, true, true));
        assertEquals(false, m.invoke(null, true, false));
        assertEquals(false, m.invoke(null, false, true));
        assertEquals(false, m.invoke(null, false, false));
    }

    @Test
    @DisplayName("Boolean | operator returns boolean and executes logical OR")
    public void testBooleanBitOr() throws Exception {
        String code = """
                public class BoolBitOrTest {
                    public static boolean function testOr(boolean a, boolean b) {
                        return a | b;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("BoolBitOrTest", code);
        Method m = clazz.getMethod("testOr", boolean.class, boolean.class);
        assertEquals(true, m.invoke(null, true, true));
        assertEquals(true, m.invoke(null, true, false));
        assertEquals(true, m.invoke(null, false, true));
        assertEquals(false, m.invoke(null, false, false));
    }

    @Test
    @DisplayName("Boolean ^ operator returns boolean and executes logical XOR")
    public void testBooleanBitXor() throws Exception {
        String code = """
                public class BoolBitXorTest {
                    public static boolean function testXor(boolean a, boolean b) {
                        return a ^ b;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("BoolBitXorTest", code);
        Method m = clazz.getMethod("testXor", boolean.class, boolean.class);
        assertEquals(false, m.invoke(null, true, true));
        assertEquals(true, m.invoke(null, true, false));
        assertEquals(true, m.invoke(null, false, true));
        assertEquals(false, m.invoke(null, false, false));
    }

    @Test
    @DisplayName("Boolean bitwise operators used in local variable assignments")
    public void testBooleanBitwiseAssignments() throws Exception {
        String code = """
                public class BoolBitwiseAssign {
                    public static boolean function testAll(boolean a, boolean b) {
                        boolean andRes = a & b;
                        boolean orRes = a | b;
                        boolean xorRes = a ^ b;
                        return andRes || orRes || xorRes;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("BoolBitwiseAssign", code);
        Method m = clazz.getMethod("testAll", boolean.class, boolean.class);
        assertEquals(true, m.invoke(null, true, false));
        assertEquals(false, m.invoke(null, false, false));
    }

    @Test
    @DisplayName("Boolean bitwise operator in if condition compiles and executes")
    public void testBooleanBitwiseInCondition() throws Exception {
        String code = """
                public class BoolBitwiseCond {
                    public static int function check(boolean a, boolean b) {
                        if (a & b) {
                            return 1;
                        } else if (a | b) {
                            return 2;
                        } else {
                            return 3;
                        }
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("BoolBitwiseCond", code);
        Method m = clazz.getMethod("check", boolean.class, boolean.class);
        assertEquals(1, m.invoke(null, true, true));
        assertEquals(2, m.invoke(null, true, false));
        assertEquals(3, m.invoke(null, false, false));
    }

    @Test
    @DisplayName("Boxed Boolean bitwise unboxes and evaluates to boolean")
    public void testBoxedBooleanBitwise() throws Exception {
        String code = """
                public class BoxedBoolBitwise {
                    public static boolean function testBoxed(Boolean a, Boolean b) {
                        return a & b;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("BoxedBoolBitwise", code);
        Method m = clazz.getMethod("testBoxed", Boolean.class, Boolean.class);
        assertEquals(true, m.invoke(null, Boolean.TRUE, Boolean.TRUE));
        assertEquals(false, m.invoke(null, Boolean.TRUE, Boolean.FALSE));
    }

    @Test
    @DisplayName("Numeric bitwise promotion between int and long yields long")
    public void testNumericBitwisePromotion() throws Exception {
        String code = """
                public class NumBitwiseTest {
                    public static long function bitAnd(long a, int b) {
                        return a & b;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("NumBitwiseTest", code);
        Method m = clazz.getMethod("bitAnd", long.class, int.class);
        assertEquals(2L, m.invoke(null, 3L, 2));
    }

    @Test
    @DisplayName("Shift operator returns unary promoted type of left operand")
    public void testShiftUnaryPromotion() throws Exception {
        String code = """
                public class ShiftPromotionTest {
                    public static int function lshift(byte a, int b) {
                        return a << b;
                    }
                    public static long function lshiftLong(long a, int b) {
                        return a << b;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("ShiftPromotionTest", code);
        Method m1 = clazz.getMethod("lshift", byte.class, int.class);
        assertEquals(8, m1.invoke(null, (byte) 2, 2));
        Method m2 = clazz.getMethod("lshiftLong", long.class, int.class);
        assertEquals(8L, m2.invoke(null, 2L, 2));
    }

    // ==================== NEGATIVE COMPILE-TIME TESTS ====================

    @Test
    @DisplayName("Bitwise operator between boolean and int fails compilation")
    public void testIncompatibleBitwiseFails() {
        String code = """
                public class IncompatibleBitwise {
                    public static void function test() {
                        boolean a = true;
                        int b = 1;
                        variable c = a & b;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoad("IncompatibleBitwise", code));
        String msg = ex.getMessage();
        assertTrue(msg.contains("Bitwise operator & cannot be applied to incompatible types") ||
                   msg.contains("integer or boolean"),
                   "Beklenen hata mesajı üretilmedi: " + msg);
    }
}
