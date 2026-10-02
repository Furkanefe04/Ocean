package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompoundAssignmentPromotionTest extends CompilerTestHelper {

    @Test
    @DisplayName("Implicit narrowing compound multiplication int *= double is rejected without explicit cast")
    void testLocalMultiplyByHalf() {
        assertThrows(CompilationException.class, () -> compileAndLoad("LocalMulHalf", """
                public class LocalMulHalf {
                    public static int function test() {
                        int x = 10;
                        x *= 0.5;
                        return x;
                    }
                }
                """));
    }

    @Test
    @DisplayName("Implicit narrowing compound division int /= double is rejected without explicit cast")
    void testLocalDivideByFraction() {
        assertThrows(CompilationException.class, () -> compileAndLoad("LocalDivFrac", """
                public class LocalDivFrac {
                    public static int function test() {
                        int x = 10;
                        x /= 2.5;
                        return x;
                    }
                }
                """));
    }

    @Test
    @DisplayName("Implicit narrowing instance field compound multiplication this.val *= 0.5 is rejected without explicit cast")
    void testFieldMultiplyByHalf() {
        assertThrows(CompilationException.class, () -> compileAndLoad("FieldMulHalf", """
                public class FieldMulHalf {
                    public int val = 10;

                    public void function compute() {
                        this.val *= 0.5;
                    }
                }
                """));
    }

    @Test
    @DisplayName("Instance field compound addition with byte overflow: this.b += 100")
    void testFieldByteOverflowNarrowing() throws Exception {
        Class<?> clazz = compileAndLoad("FieldByteOverflow", """
                public class FieldByteOverflow {
                    public byte b = (byte) 100;

                    public void function compute() {
                        this.b += 100;
                    }
                }
                """);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("compute");
        m.invoke(instance);

        Field f = clazz.getField("b");
        assertEquals((byte) -56, f.get(instance));
    }

    @Test
    @DisplayName("Implicit narrowing array element compound multiplication arr[0] *= 0.5 is rejected without explicit cast")
    void testArrayMultiplyByHalf() {
        assertThrows(CompilationException.class, () -> compileAndLoad("ArrayMulHalf", """
                public class ArrayMulHalf {
                    public static int function test() {
                        int[] arr = new int[1];
                        arr[0] = 10;
                        arr[0] *= 0.5;
                        return arr[0];
                    }
                }
                """));
    }

    @Test
    @DisplayName("Array element compound addition with byte overflow: arr[0] += 100")
    void testArrayByteOverflowNarrowing() throws Exception {
        Class<?> clazz = compileAndLoad("ArrayByteOverflow", """
                public class ArrayByteOverflow {
                    public static byte function test() {
                        byte[] arr = new byte[1];
                        arr[0] = (byte) 100;
                        arr[0] += 100;
                        return arr[0];
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals((byte) -56, m.invoke(null));
    }

    @Test
    @DisplayName("Implicit narrowing float compound addition float += double is rejected without explicit cast")
    void testFloatAdditionWithDouble() {
        assertThrows(CompilationException.class, () -> compileAndLoad("FloatAddDouble", """
                public class FloatAddDouble {
                    public static float function test() {
                        float f = 1.5f;
                        f += 2.25;
                        return f;
                    }
                }
                """));
    }

    @Test
    @DisplayName("Implicit narrowing long compound multiplication long *= float is rejected without explicit cast")
    void testLongMultiplicationWithFloat() {
        assertThrows(CompilationException.class, () -> compileAndLoad("LongMulFloat", """
                public class LongMulFloat {
                    public static long function test() {
                        long l = 10L;
                        l *= 0.5f;
                        return l;
                    }
                }
                """));
    }

    @Test
    @DisplayName("Incompatible compound assignment int += String is rejected")
    void testInvalidTypeRejected() {
        assertThrows(CompilationException.class, () -> compileAndLoad("InvalidIntPlusString", """
                public class InvalidIntPlusString {
                    public static void function test() {
                        int x = 10;
                        x += "test";
                    }
                }
                """));
    }

    @Test
    @DisplayName("Incompatible compound assignment boolean += int is rejected")
    void testBooleanArithmeticRejected() {
        assertThrows(CompilationException.class, () -> compileAndLoad("InvalidBoolPlusInt", """
                public class InvalidBoolPlusInt {
                    public static void function test() {
                        boolean b = true;
                        b += 1;
                    }
                }
                """));
    }
}