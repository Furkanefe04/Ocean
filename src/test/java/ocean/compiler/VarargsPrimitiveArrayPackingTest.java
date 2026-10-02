package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VarargsPrimitiveArrayPackingTest extends CompilerTestHelper {

    @Test
    @DisplayName("Passing primitive int[] to Object... varargs packs into new Object[]{ intArr }")
    void testPrimitiveIntArrayToVarargsObject() throws Exception {
        Class<?> clazz = compileAndLoad("VarargsIntArray", """
                public class VarargsIntArray {
                    public static int function countArgs(Object... args) {
                        return args.length;
                    }

                    public static Object function getFirstArg(Object... args) {
                        return args[0];
                    }

                    public static int function test() {
                        int[] myArr = new int[3];
                        return countArgs(myArr);
                    }

                    public static boolean function testFirstIsIntArray() {
                        int[] myArr = new int[3];
                        return getFirstArg(myArr) instanceof int[];
                    }
                }
                """);
        Method countM = clazz.getMethod("test");
        assertEquals(1, countM.invoke(null));

        Method checkM = clazz.getMethod("testFirstIsIntArray");
        assertTrue((boolean) checkM.invoke(null));
    }

    @Test
    @DisplayName("Passing primitive double[] and boolean[] to Object... varargs packs into single element array")
    void testPrimitiveDoubleAndBooleanArraysToVarargsObject() throws Exception {
        Class<?> clazz = compileAndLoad("VarargsDoubleBool", """
                public class VarargsDoubleBool {
                    public static int function count(Object... args) {
                        return args.length;
                    }

                    public static int function testDouble() {
                        double[] d = new double[4];
                        return count(d);
                    }

                    public static int function testBool() {
                        boolean[] b = new boolean[2];
                        return count(b);
                    }
                }
                """);
        Method testDoubleM = clazz.getMethod("testDouble");
        assertEquals(1, testDoubleM.invoke(null));

        Method testBoolM = clazz.getMethod("testBool");
        assertEquals(1, testBoolM.invoke(null));
    }

    @Test
    @DisplayName("Passing reference String[] to Object... varargs is passed directly without repacking (array covariance)")
    void testReferenceStringArrayToVarargsObjectDirect() throws Exception {
        Class<?> clazz = compileAndLoad("VarargsStringArray", """
                public class VarargsStringArray {
                    public static int function count(Object... args) {
                        return args.length;
                    }

                    public static int function test() {
                        String[] s = new String[5];
                        return count(s);
                    }
                }
                """);
        Method testM = clazz.getMethod("test");
        assertEquals(5, testM.invoke(null));
    }

    @Test
    @DisplayName("Passing primitive array to varargs constructor packs into Object[]")
    void testPrimitiveArrayToVarargsConstructor() throws Exception {
        Class<?> clazz = compileAndLoad("VarargsCtor", """
                public class VarargsCtor {
                    public int argCount = 0;

                    public function VarargsCtor(Object... args) {
                        this.argCount = args.length;
                    }

                    public static int function test() {
                        int[] arr = new int[10];
                        VarargsCtor obj = new VarargsCtor(arr);
                        return obj.argCount;
                    }
                }
                """);
        Method testM = clazz.getMethod("test");
        assertEquals(1, testM.invoke(null));
    }

    @Test
    @DisplayName("Passing multiple arguments mixed with primitive array to Object... varargs")
    void testMultipleArgsWithPrimitiveArray() throws Exception {
        Class<?> clazz = compileAndLoad("VarargsMixed", """
                public class VarargsMixed {
                    public static int function count(Object... args) {
                        return args.length;
                    }

                    public static int function test() {
                        int[] arr = new int[3];
                        return count("tag", arr, 42);
                    }
                }
                """);
        Method testM = clazz.getMethod("test");
        assertEquals(3, testM.invoke(null));
    }
}
