package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PatternVariableScopingTest extends CompilerTestHelper {

    @Test
    @DisplayName("Pattern variable on RHS of || is out of scope and fails compilation")
    public void testPatternVarOnRhsOfOrFails() {
        String code = """
                public class PatternOrTest {
                    public static bool function test(Object obj) {
                        if (obj instanceof String s || s.isEmpty()) {
                            return true;
                        }
                        return false;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "PatternOrTest"));
        assertTrue(ex.getMessage().contains("not in scope") || ex.getMessage().contains("scope"),
                "Expected out of scope error for pattern variable, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Pattern variable from || condition in then branch is out of scope and fails compilation")
    public void testPatternVarInThenBranchOfOrFails() {
        String code = """
                public class PatternOrThenTest {
                    public static int function test(Object obj, bool flag) {
                        if (obj instanceof String s || flag) {
                            return s.length();
                        }
                        return 0;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "PatternOrThenTest"));
        assertTrue(ex.getMessage().contains("not in scope") || ex.getMessage().contains("scope"),
                "Expected out of scope error for pattern variable in then branch, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Pattern variable on RHS and then branch of && succeeds")
    public void testPatternVarInAndSucceeds() throws Exception {
        String code = """
                public class PatternAndTest {
                    public static int function getLengthIfNonEmpty(Object obj) {
                        if (obj instanceof String s && s.length() > 0) {
                            return s.length();
                        }
                        return -1;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("PatternAndTest", code);
        Class<?> clazz = classes.get("PatternAndTest");
        assertNotNull(clazz);
        Method m = clazz.getMethod("getLengthIfNonEmpty", Object.class);
        assertEquals(5, m.invoke(null, "Ocean"));
        assertEquals(-1, m.invoke(null, ""));
        assertEquals(-1, m.invoke(null, 123));
    }

    @Test
    @DisplayName("Inverted pattern in guard clause allows variable access after if statement")
    public void testGuardClausePatternScopingSucceeds() throws Exception {
        String code = """
                public class GuardPatternTest {
                    public static int function process(Object obj) {
                        if (!(obj instanceof String s)) {
                            return -1;
                        }
                        return s.length();
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("GuardPatternTest", code);
        Class<?> clazz = classes.get("GuardPatternTest");
        assertNotNull(clazz);
        Method m = clazz.getMethod("process", Object.class);
        assertEquals(4, m.invoke(null, "Java"));
        assertEquals(-1, m.invoke(null, 42));
    }
}