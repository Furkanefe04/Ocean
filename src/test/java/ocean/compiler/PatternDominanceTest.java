package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PatternDominanceTest extends CompilerTestHelper {

    @Test
    @DisplayName("Switch with Object pattern dominating String pattern fails compilation")
    public void testObjectDominatesString() {
        String code = """
                public class DominatedStringRunner {
                    public static String function test(Object obj) {
                        return switch (obj) {
                            case Object o -> "all";
                            case String s -> "string";
                        };
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("DominatedStringRunner", code));
        assertTrue(ex.getMessage().contains("dominated pattern") || ex.getMessage().contains("dominated"),
                "Expected dominance error message, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch with Number pattern dominating Integer pattern fails compilation")
    public void testNumberDominatesInteger() {
        String code = """
                public class DominatedNumberRunner {
                    public static String function test(Object obj) {
                        return switch (obj) {
                            case Number n -> "number";
                            case Integer i -> "integer";
                            default -> "other";
                        };
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("DominatedNumberRunner", code));
        assertTrue(ex.getMessage().contains("dominated pattern") || ex.getMessage().contains("dominated"),
                "Expected dominance error message, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch with String pattern dominating literal 'hello' fails compilation")
    public void testStringDominatesLiteral() {
        String code = """
                public class DominatedLiteralRunner {
                    public static String function test(Object obj) {
                        return switch (obj) {
                            case String s -> "any string";
                            case "hello" -> "hello literal";
                            default -> "other";
                        };
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("DominatedLiteralRunner", code));
        assertTrue(ex.getMessage().contains("dominated pattern") || ex.getMessage().contains("dominated"),
                "Expected dominance error message, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Guarded pattern does not dominate subsequent pattern")
    public void testGuardedPatternDoesNotDominate() throws Exception {
        String code = """
                public class GuardedRunner {
                    public static String function test(Object obj) {
                        return switch (obj) {
                            case String s when s.length() > 5 -> "long string";
                            case String s -> "short string";
                            default -> "not string";
                        };
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("GuardedRunner", code);
        Class<?> clazz = classes.get("GuardedRunner");
        assertNotNull(clazz);

        Method m = clazz.getMethod("test", Object.class);
        assertEquals("long string", m.invoke(null, "hello world"));
        assertEquals("short string", m.invoke(null, "hi"));
        assertEquals("not string", m.invoke(null, 123));
    }

    @Test
    @DisplayName("Total pattern dominating default block fails compilation")
    public void testTotalPatternDominatesDefault() {
        String code = """
                public class TotalDominatesDefaultRunner {
                    public static String function test(Object obj) {
                        return switch (obj) {
                            case Object o -> "everything";
                            default -> "unreachable";
                        };
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("TotalDominatesDefaultRunner", code));
        assertTrue(ex.getMessage().contains("dominated") || ex.getMessage().contains("dominated"),
                "Expected dominance error for default, got: " + ex.getMessage());
    }
}
