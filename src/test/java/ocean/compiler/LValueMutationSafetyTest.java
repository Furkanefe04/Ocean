package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LValueMutationSafetyTest extends CompilerTestHelper {

    @Test
    @DisplayName("Postfix increment on binary expression fails compilation")
    public void testPostfixIncrementOnBinaryExpressionFails() {
        String code = """
                public class BinaryIncTest {
                    public static void function test() {
                        int x = 5;
                        (x + 1)++;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("BinaryIncTest", code));
        assertTrue(ex.getMessage().contains("assignable variables") || ex.getMessage().contains("Artırma"),
                "Expected l-value error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Prefix decrement on binary expression fails compilation")
    public void testPrefixDecrementOnBinaryExpressionFails() {
        String code = """
                public class BinaryDecTest {
                    public static void function test() {
                        int x = 5;
                        --(x + 1);
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("BinaryDecTest", code));
        assertTrue(ex.getMessage().contains("assignable variables") || ex.getMessage().contains("azaltma"),
                "Expected l-value error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Increment on method call result fails compilation")
    public void testIncrementOnMethodCallFails() {
        String code = """
                public class MethodIncTest {
                    public static int function getValue() {
                        return 42;
                    }
                    public static void function test() {
                        getValue()++;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("MethodIncTest", code));
        assertTrue(ex.getMessage().contains("assignable variables") || ex.getMessage().contains("cannot be assigned") || ex.getMessage().contains("E0008"),
                "Expected error on method call increment, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Array length assignment fails compilation")
    public void testArrayLengthAssignmentFails() {
        String code = """
                public class ArrayLenAssignTest {
                    public static void function test() {
                        int[] arr = new int[5];
                        arr.length = 10;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ArrayLenAssignTest", code));
        assertTrue(ex.getMessage().contains("length") || ex.getMessage().contains("sabittir") || ex.getMessage().contains("Final"),
                "Expected final field error for array length assignment, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Array length increment fails compilation")
    public void testArrayLengthIncrementFails() {
        String code = """
                public class ArrayLenIncTest {
                    public static void function test() {
                        int[] arr = new int[5];
                        arr.length++;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ArrayLenIncTest", code));
        assertTrue(ex.getMessage().contains("length") || ex.getMessage().contains("sabittir") || ex.getMessage().contains("Final"),
                "Expected final field error for array length increment, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Array length compound assignment fails compilation")
    public void testArrayLengthCompoundAssignmentFails() {
        String code = """
                public class ArrayLenCompoundTest {
                    public static void function test() {
                        int[] arr = new int[5];
                        arr.length += 2;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ArrayLenCompoundTest", code));
        assertTrue(ex.getMessage().contains("length") || ex.getMessage().contains("sabittir") || ex.getMessage().contains("Final"),
                "Expected final field error for array length compound assignment, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Increment on final field fails compilation")
    public void testFinalFieldIncrementFails() {
        String code = """
                public class FinalFieldHolder {
                    public final int x = 10;
                }
                public class FinalFieldIncTest {
                    public static void function test() {
                        FinalFieldHolder h = new FinalFieldHolder();
                        h.x++;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("FinalFieldIncTest", code));
        assertTrue(ex.getMessage().contains("Cannot assign a value to final"),
                "Expected final field error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Decrement on final field fails compilation")
    public void testFinalFieldDecrementFails() {
        String code = """
                public class FinalFieldHolder2 {
                    public final int x = 10;
                }
                public class FinalFieldDecTest {
                    public static void function test() {
                        FinalFieldHolder2 h = new FinalFieldHolder2();
                        --h.x;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("FinalFieldDecTest", code));
        assertTrue(ex.getMessage().contains("Cannot assign a value to final"),
                "Expected final field error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Increment on this or super fails compilation")
    public void testThisSuperIncrementFails() {
        String code = """
                public class ThisIncTest {
                    public void function test() {
                        this++;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ThisIncTest", code));
        assertTrue(ex.getMessage().contains("Increment (++) and decrement (--) operations are only valid"),
                "Expected error on this increment, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid increments on local variables, fields and array elements compile and execute correctly")
    public void testValidIncrementsWork() throws Exception {
        String code = """
                public class Counter {
                    public int count = 10;
                }
                public class ValidIncTest {
                    public static int function test() {
                        int x = 5;
                        x++;
                        ++x;
                        x--;
                        --x;

                        Counter c = new Counter();
                        c.count++;
                        ++c.count;

                        int[] arr = new int[3];
                        arr[0] = 100;
                        arr[0]++;
                        ++arr[0];

                        return x + c.count + arr[0]; // 5 + 12 + 102 = 119
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ValidIncTest", code);
        Class<?> clazz = classes.get("ValidIncTest");
        assertNotNull(clazz);
        Method m = clazz.getMethod("test");
        Object res = m.invoke(null);
        assertEquals(119, res);
    }
}
