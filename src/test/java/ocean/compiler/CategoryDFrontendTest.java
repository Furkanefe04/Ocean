package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class CategoryDFrontendTest extends CompilerTestHelper {

    @Test
    @DisplayName("D1 (#46): for loop with literal step 0 fails compilation")
    public void testForLoopStepZeroFailsCompilation() {
        String code = """
                public class ForStepZeroTest {
                    public static void function run() {
                        for (int i from 0 to 10 with increasing 0) {
                            int x = i;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoad("ForStepZeroTest", code));
        assertTrue(ex.getMessage().contains("step") || ex.getMessage().contains("0"),
                "Expected step 0 error message, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("D4 (#49): String interpolation with braces in char literals")
    public void testStringInterpolationWithBracesInCharLiteral() throws Exception {
        String code = """
                public class InterpBraceCharTest {
                    public static String function testQuotes() {
                        char c = '}';
                        String s = $"{c}";
                        return s;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("InterpBraceCharTest", code);
        Method m = clazz.getMethod("testQuotes");
        Object result = m.invoke(null);
        assertEquals("}", result.toString());
    }

    @Test
    @DisplayName("D4 (#49): String interpolation with braces inside string literal expression")
    public void testStringInterpolationWithBracesInStringLiteral() throws Exception {
        String code = """
                public class InterpBraceStringTest {
                    public static String function testNested() {
                        String s = $"result: {"{hello}".toUpperCase()}";
                        return s;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("InterpBraceStringTest", code);
        Method m = clazz.getMethod("testNested");
        Object result = m.invoke(null);
        assertEquals("result: {HELLO}", result.toString());
    }

    @Test
    @DisplayName("D5 (#50): Compound array assignment evaluates side-effecting index only once")
    public void testCompoundArrayAssignmentSideEffectOnce() throws Exception {
        String code = """
                public class CompoundArraySideEffectTest {
                    public static int function testEffect() {
                        int[] arr = new int[5];
                        arr[0] = 10;
                        int i = 0;
                        arr[i++] += 5;
                        // i should now be 1 (incremented only once)
                        // arr[0] should be 15
                        return i * 100 + arr[0];
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("CompoundArraySideEffectTest", code);
        Method m = clazz.getMethod("testEffect");
        Object result = m.invoke(null);
        // i = 1, arr[0] = 15 -> 1 * 100 + 15 = 115
        assertEquals(115, ((Number) result).intValue(),
                "Index expression i++ should have been evaluated exactly once, resulting in i=1, arr[0]=15");
    }
}
