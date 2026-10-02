package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArrayAccessTypeResolutionTest extends CompilerTestHelper {

    @Test
    @DisplayName("Chained array access from method call: getMatrix()[0][0]")
    void testChainedArrayAccessFromMethod() throws Exception {
        Class<?> clazz = compileAndLoad("ChainedArrMethod", """
                public class ChainedArrMethod {
                    public static int[][] function getMatrix() {
                        int[][] m = new int[2][2];
                        m[0][0] = 77;
                        return m;
                    }
                    public static int function test() {
                        int val = getMatrix()[0][0];
                        return val;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(77, m.invoke(null));
    }

    @Test
    @DisplayName("Single dimension array access from method call: getNames()[0]")
    void testStringArrayFromMethod() throws Exception {
        Class<?> clazz = compileAndLoad("StringArrMethod", """
                public class StringArrMethod {
                    public static String[] function getNames() {
                        String[] s = new String[1];
                        s[0] = "hello";
                        return s;
                    }
                    public static String function test() {
                        String val = getNames()[0];
                        return val;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals("hello", m.invoke(null));
    }

    @Test
    @DisplayName("Primitive int array access from method call: getInts()[1]")
    void testIntArrayFromMethod() throws Exception {
        Class<?> clazz = compileAndLoad("IntArrMethod", """
                public class IntArrMethod {
                    public static int[] function getInts() {
                        int[] arr = new int[3];
                        arr[1] = 999;
                        return arr;
                    }
                    public static int function test() {
                        int val = getInts()[1];
                        return val;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(999, m.invoke(null));
    }

    @Test
    @DisplayName("Ternary expression array access: (flag ? arr1 : arr2)[0]")
    void testTernaryArrayAccess() throws Exception {
        Class<?> clazz = compileAndLoad("TernaryArr", """
                public class TernaryArr {
                    public static int function test(boolean flag) {
                        int[] a = new int[1];
                        int[] b = new int[1];
                        a[0] = 10;
                        b[0] = 20;
                        int val = (flag ? a : b)[0];
                        return val;
                    }
                }
                """);
        Method m = clazz.getMethod("test", boolean.class);
        assertEquals(10, m.invoke(null, true));
        assertEquals(20, m.invoke(null, false));
    }

    @Test
    @DisplayName("Cast expression array access: ((int[][]) obj)[0][0]")
    void testCastedArrayAccess() throws Exception {
        Class<?> clazz = compileAndLoad("CastArr", """
                public class CastArr {
                    public static int function test(Object obj) {
                        int val = ((int[][]) obj)[0][0];
                        return val;
                    }
                }
                """);
        Method m = clazz.getMethod("test", Object.class);
        int[][] mtx = new int[1][1];
        mtx[0][0] = 123;
        assertEquals(123, m.invoke(null, (Object) mtx));
    }

    @Test
    @DisplayName("Multi-dimensional array element assignment with method receiver: getMatrix()[0][0] = 42")
    void testMultiDimArrayAssignment() throws Exception {
        Class<?> clazz = compileAndLoad("MultiDimAssign", """
                public class MultiDimAssign {
                    private static int[][] matrix = new int[2][2];

                    public static int[][] function getMatrix() {
                        return matrix;
                    }

                    public static int function test() {
                        getMatrix()[0][0] = 42;
                        return matrix[0][0];
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(42, m.invoke(null));
    }

    @Test
    @DisplayName("Type safety: assigning incompatible type to array element fails compilation")
    void testIncompatibleArrayElementAssignmentFails() {
        Exception e = assertThrows(Exception.class, () -> {
            compileAndLoad("BadAssign", """
                    public class BadAssign {
                        public static int[] function getArr() {
                            return new int[1];
                        }
                        public static void function test() {
                            getArr()[0] = "not an int";
                        }
                    }
                    """);
        });
        assertTrue(e.getMessage().contains("Incompatible types"),
                "Expected type mismatch error, got: " + e.getMessage());
    }
}
