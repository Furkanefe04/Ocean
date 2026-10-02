package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SwitchSemanticsSafetyTest extends CompilerTestHelper {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    @Test
    @DisplayName("Switch with String selector rejects int case constant")
    public void testStringSwitchWithIntCaseThrowsError() {
        String code = """
            public class SwitchTest {
                public static void function test(String s) {
                    switch (s) {
                        case 123:
                            stop;
                        default:
                            stop;
                    }
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchTest"));
        assertTrue(ex.getMessage().contains("Switch selector tipi") || ex.getMessage().contains("Incompatible"),
                "Expected switch selector type incompatibility error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch with int selector rejects String case constant")
    public void testIntSwitchWithStringCaseThrowsError() {
        String code = """
            public class SwitchTest {
                public static void function test(int x) {
                    switch (x) {
                        case "bad":
                            stop;
                        default:
                            stop;
                    }
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchTest"));
        assertTrue(ex.getMessage().contains("Switch selector tipi") || ex.getMessage().contains("Incompatible"),
                "Expected switch selector type incompatibility error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch with boolean selector rejects non-boolean case constant")
    public void testBooleanSwitchWithIntCaseThrowsError() {
        String code = """
            public class SwitchTest {
                public static void function test(boolean b) {
                    switch (b) {
                        case 1:
                            stop;
                        default:
                            stop;
                    }
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchTest"));
        assertTrue(ex.getMessage().contains("Switch selector tipi") || ex.getMessage().contains("Incompatible") || ex.getMessage().contains("boolean"),
                "Expected switch selector type incompatibility error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch case label with non-final local variable is rejected")
    public void testSwitchWithMutableLocalVariableInCaseThrowsError() {
        String code = """
            public class SwitchTest {
                public static void function test(int x) {
                    int target = 42;
                    switch (x) {
                        case target:
                            stop;
                        default:
                            stop;
                    }
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchTest"));
        assertTrue(ex.getMessage().contains("Constant expression required"),
                "Expected compile-time constant error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch case label with method call is rejected")
    public void testSwitchWithMethodCallInCaseThrowsError() {
        String code = """
            public class SwitchTest {
                public static int function getConst() {
                    return 42;
                }
                public static void function test(int x) {
                    switch (x) {
                        case getConst():
                            stop;
                        default:
                            stop;
                    }
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchTest"));
        assertTrue(ex.getMessage().contains("Constant expression required"),
                "Expected compile-time constant error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch with constant binary expression compiles and executes correctly")
    public void testSwitchWithConstantBinaryExprSucceeds() throws Exception {
        String code = """
            public class SwitchTest {
                public static int function evaluate(int x) {
                    return switch (x) {
                        case 1 + 2 -> 100;
                        case 2 * 5 -> 200;
                        default -> 0;
                    };
                }
            }
            """;
        Map<String, Class<?>> classes = compileAndLoadAll("SwitchTest", code);
        Class<?> clazz = classes.get("SwitchTest");
        assertNotNull(clazz);
        Method evalMethod = clazz.getMethod("evaluate", int.class);
        assertEquals(100, evalMethod.invoke(null, 3));
        assertEquals(200, evalMethod.invoke(null, 10));
        assertEquals(0, evalMethod.invoke(null, 7));
    }

    @Test
    @DisplayName("Valid String switch compiles and executes correctly")
    public void testValidStringSwitchSucceeds() throws Exception {
        String code = """
            public class Runner {
                public static String function categorize(String category) {
                    return switch (category) {
                        case "books" -> "Reading";
                        case "food" -> "Groceries";
                        default -> "Other";
                    };
                }
            }
            """;
        Map<String, Class<?>> classes = compileAndLoadAll("Runner", code);
        Class<?> runnerClass = classes.get("Runner");
        assertNotNull(runnerClass);
        Method catMethod = runnerClass.getMethod("categorize", String.class);
        assertEquals("Reading", catMethod.invoke(null, "books"));
        assertEquals("Groceries", catMethod.invoke(null, "food"));
        assertEquals("Other", catMethod.invoke(null, "tools"));
    }
}
