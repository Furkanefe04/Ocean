package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class StringInterpolationAdvancedTest extends CompilerTestHelper {

    @Test
    @DisplayName("String interpolation with ternary and string literals containing quotes")
    public void testTernaryAndQuotesInInterpolation() throws Exception {
        String code = """
                public class InterpAdvancedRunner {
                    public static String function describe(int val) {
                        return $"Status: {val > 0 ? "positive" : "non-positive"}, Number: {val}";
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("InterpAdvancedRunner", code);
        Class<?> runnerClass = classes.get("InterpAdvancedRunner");
        assertNotNull(runnerClass);

        Method m = runnerClass.getMethod("describe", int.class);
        assertEquals("Status: positive, Number: 10", m.invoke(null, 10));
        assertEquals("Status: non-positive, Number: -5", m.invoke(null, -5));
    }

    @Test
    @DisplayName("String interpolation with Elvis operator and safe navigation")
    public void testElvisAndSafeNavInInterpolation() throws Exception {
        String code = """
                public class User {
                    public String name;
                    public User(String n) {
                        this.name = n;
                    }
                    public String function getName() {
                        return this.name;
                    }
                }

                public class SafeNavRunner {
                    public static String function greet(User u) {
                        return $"Hello {u?.getName() ?? "Guest"}!";
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("SafeNavRunner", code);
        Class<?> runnerClass = classes.get("SafeNavRunner");
        assertNotNull(runnerClass);

        Method greetMethod = runnerClass.getMethod("greet", classes.get("User"));
        Object user = classes.get("User").getConstructor(String.class).newInstance("Alice");
        assertEquals("Hello Alice!", greetMethod.invoke(null, user));
        assertEquals("Hello Guest!", greetMethod.invoke(null, new Object[]{null}));
    }

    @Test
    @DisplayName("String interpolation with character literals and expressions")
    public void testCharLiteralInInterpolation() throws Exception {
        String code = """
                public class CharInterpRunner {
                    public static String function formatLetter(char c) {
                        return $"Code of '{c}' is {c == 'A' ? 65 : 0}";
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("CharInterpRunner", code);
        Class<?> runnerClass = classes.get("CharInterpRunner");
        assertNotNull(runnerClass);

        Method m = runnerClass.getMethod("formatLetter", char.class);
        assertEquals("Code of 'A' is 65", m.invoke(null, 'A'));
        assertEquals("Code of 'B' is 0", m.invoke(null, 'B'));
    }

    @Test
    @DisplayName("Syntax error in interpolation expression throws CompilationException")
    public void testInvalidSyntaxInInterpolationThrows() {
        String code = """
                public class BadInterp {
                    public static String function test() {
                        return $"Invalid: {1 + }";
                    }
                }
                """;

        assertThrows(CompilationException.class, () -> compileAndLoadAll("BadInterp", code));
    }
}
