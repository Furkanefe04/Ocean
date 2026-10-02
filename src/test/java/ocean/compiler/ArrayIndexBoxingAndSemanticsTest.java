package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArrayIndexBoxingAndSemanticsTest extends CompilerTestHelper {

    @Test
    @DisplayName("Boxed Integer as array index for read and write")
    void testBoxedIntegerArrayIndexReadAndWrite() throws Exception {
        Class<?> clazz = compileAndLoad("BoxedIntIdx", """
                public class BoxedIntIdx {
                    public static int function test() {
                        int[] arr = new int[5];
                        Integer idx = 2;
                        arr[idx] = 42;
                        return arr[idx];
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(42, m.invoke(null));
    }

    @Test
    @DisplayName("Boxed Byte and Short as array indices")
    void testBoxedByteAndShortArrayIndex() throws Exception {
        Class<?> clazz = compileAndLoad("ByteShortIdx", """
                public class ByteShortIdx {
                    public static int function test() {
                        int[] arr = new int[5];
                        Byte b = (byte) 1;
                        Short s = (short) 3;
                        arr[b] = 100;
                        arr[s] = 200;
                        return arr[b] + arr[s];
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(300, m.invoke(null));
    }

    @Test
    @DisplayName("Boxed Character as array index")
    void testBoxedCharacterArrayIndex() throws Exception {
        Class<?> clazz = compileAndLoad("CharIdx", """
                public class CharIdx {
                    public static int function test() {
                        int[] arr = new int[5];
                        Character c = (char) 2;
                        arr[c] = 77;
                        return arr[c];
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(77, m.invoke(null));
    }

    @Test
    @DisplayName("Boxed Integer in array element compound assignment and increment")
    void testBoxedIntegerCompoundAssignmentAndIncrement() throws Exception {
        Class<?> clazz = compileAndLoad("BoxedIncArr", """
                public class BoxedIncArr {
                    public static int function test() {
                        int[] arr = new int[5];
                        Integer idx = 1;
                        arr[idx] = 10;
                        arr[idx] += 5;
                        arr[idx]++;
                        ++arr[idx];
                        return arr[idx];
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(17, m.invoke(null));
    }

    @Test
    @DisplayName("Boxed Integer as array creation size (new int[Integer])")
    void testBoxedArrayCreationSize() throws Exception {
        Class<?> clazz = compileAndLoad("BoxedArrayCreation", """
                public class BoxedArrayCreation {
                    public static int function test() {
                        Integer sz = 7;
                        int[] arr = new int[sz];
                        return arr.length;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(7, m.invoke(null));
    }

    @Test
    @DisplayName("Multidimensional array creation and indexing with boxed integers")
    void testMultiDimensionalArrayWithBoxedIntegers() throws Exception {
        Class<?> clazz = compileAndLoad("MultiDimBoxed", """
                public class MultiDimBoxed {
                    public static int function test() {
                        Integer r = 3;
                        Integer c = 4;
                        int[][] grid = new int[r][c];
                        Integer i = 1;
                        Integer j = 2;
                        grid[i][j] = 99;
                        return grid[i][j];
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(99, m.invoke(null));
    }

    @Test
    @DisplayName("Invalid index type 'long' rejected at compile-time")
    void testInvalidLongIndexFailsCompilation() {
        assertThrows(CompilationException.class, () -> compileToBytecodeMap("""
                public class LongIndexFail {
                    public static void function test() {
                        int[] arr = new int[5];
                        long l = 2L;
                        arr[l] = 10;
                    }
                }
                """, "LongIndexFail"));
    }

    @Test
    @DisplayName("Invalid index type 'double' rejected at compile-time")
    void testInvalidDoubleIndexFailsCompilation() {
        assertThrows(CompilationException.class, () -> compileToBytecodeMap("""
                public class DoubleIndexFail {
                    public static void function test() {
                        int[] arr = new int[5];
                        arr[1.5] = 10;
                    }
                }
                """, "DoubleIndexFail"));
    }

    @Test
    @DisplayName("Invalid array creation size type 'long' rejected at compile-time")
    void testInvalidLongArrayCreationSizeFailsCompilation() {
        assertThrows(CompilationException.class, () -> compileToBytecodeMap("""
                public class LongSizeFail {
                    public static void function test() {
                        long sz = 10L;
                        int[] arr = new int[sz];
                    }
                }
                """, "LongSizeFail"));
    }
}
