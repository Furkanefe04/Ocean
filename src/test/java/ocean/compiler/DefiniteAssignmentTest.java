package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DefiniteAssignmentTest extends CompilerTestHelper {

    @Test
    @DisplayName("Reading an uninitialized local variable fails compilation")
    public void testUninitializedVariableReadFails() {
        String code = """
                public class UninitTest {
                    public static int function test() {
                        int x;
                        int y = x + 1;
                        return y;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "UninitTest"));
        assertTrue(ex.getMessage().contains("henüz might not have been initialized") || ex.getMessage().contains("might not have been initialized"),
                "Expected uninitialized variable error message, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Variable initialized in both if-else branches succeeds")
    public void testInitializedInBothBranchesSucceeds() throws Exception {
        String code = """
                public class BranchInitTest {
                    public static int function choose(bool flag) {
                        int x;
                        if (flag) {
                            x = 10;
                        } else {
                            x = 20;
                        }
                        return x;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("BranchInitTest", code);
        Class<?> clazz = classes.get("BranchInitTest");
        assertNotNull(clazz);
        Method choose = clazz.getMethod("choose", boolean.class);
        assertEquals(10, choose.invoke(null, true));
        assertEquals(20, choose.invoke(null, false));
    }

    @Test
    @DisplayName("Variable initialized in only one branch fails compilation")
    public void testInitializedInOnlyOneBranchFails() {
        String code = """
                public class PartialInitTest {
                    public static int function test(bool flag) {
                        int x;
                        if (flag) {
                            x = 10;
                        }
                        return x;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "PartialInitTest"));
        assertTrue(ex.getMessage().contains("henüz might not have been initialized") || ex.getMessage().contains("might not have been initialized"),
                "Expected uninitialized variable error message, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Variable initialized after terminating guard clause succeeds")
    public void testInitializedAfterTerminatingBranchSucceeds() throws Exception {
        String code = """
                public class GuardInitTest {
                    public static int function compute(bool failEarly) {
                        int x;
                        if (failEarly) {
                            return -1;
                        } else {
                            x = 42;
                        }
                        return x;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("GuardInitTest", code);
        Class<?> clazz = classes.get("GuardInitTest");
        assertNotNull(clazz);
        Method compute = clazz.getMethod("compute", boolean.class);
        assertEquals(-1, compute.invoke(null, true));
        assertEquals(42, compute.invoke(null, false));
    }
}