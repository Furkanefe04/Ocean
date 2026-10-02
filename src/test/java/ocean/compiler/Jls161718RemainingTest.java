package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class Jls161718RemainingTest extends CompilerTestHelper {

    // ========================================================
    //
    // ========================================================

    @Test
    @DisplayName("Variable definitely assigned in finally block is available after try-finally")
    public void testVariableAssignedInFinallyBlockSucceeds() throws Exception {
        String code = """
                public class FinallyDARunner {
                    public static int function run() {
                        int x;
                        trying {
                            // nothing
                        } finally {
                            x = 42;
                        }
                        return x;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("FinallyDARunner", code);
        Class<?> clazz = classes.get("FinallyDARunner");
        assertNotNull(clazz);

        Method m = clazz.getMethod("run");
        int res = (int) m.invoke(null);
        assertEquals(42, res);
    }

    @Test
    @DisplayName("Blank final field assigned in finally block succeeds")
    public void testBlankFinalAssignedInFinallySucceeds() throws Exception {
        String code = """
                public class BlankFinalFinallyRunner {
                    public final int val;

                    public BlankFinalFinallyRunner() {
                        trying {
                            // nothing
                        } finally {
                            this.val = 100;
                        }
                    }

                    public int function getVal() {
                        return this.val;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("BlankFinalFinallyRunner", code);
        Class<?> clazz = classes.get("BlankFinalFinallyRunner");
        assertNotNull(clazz);

        Object obj = clazz.getConstructor().newInstance();
        Method m = clazz.getMethod("getVal");
        int res = (int) m.invoke(obj);
        assertEquals(100, res);
    }

    // ========================================================
    //
    // ========================================================

    @Test
    @DisplayName("lock(null) literal fails compilation")
    public void testLockNullLiteralFailsCompilation() {
        String code = """
                public class LockNullRunner {
                    public static void function run() {
                        lock (null) {
                            int x = 1;
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("LockNullRunner", code));
        assertTrue(ex.getMessage().contains("Lock expression cannot be 'null'"),
                "Expected lock null error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid lock expression compiles and executes properly")
    public void testValidLockExpressionSucceeds() throws Exception {
        String code = """
                public class ValidLockRunner {
                    public static int function run() {
                        Object o = new Object();
                        int x = 0;
                        lock (o) {
                            x = 99;
                        }
                        return x;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ValidLockRunner", code);
        Class<?> clazz = classes.get("ValidLockRunner");
        assertNotNull(clazz);

        Method m = clazz.getMethod("run");
        int res = (int) m.invoke(null);
        assertEquals(99, res);
    }

    // ========================================================
    //
    // ========================================================

    @Test
    @DisplayName("Diamond operator new ArrayList<>() compiles and executes")
    public void testDiamondOperatorArrayListSucceeds() throws Exception {
        String code = """
                import java.util.ArrayList;
                import java.util.List;

                public class DiamondArrayListRunner {
                    public static int function run() {
                        List<String> list = new ArrayList<>();
                        list.add("hello");
                        list.add("ocean");
                        return list.size();
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("DiamondArrayListRunner", code);
        Class<?> clazz = classes.get("DiamondArrayListRunner");
        assertNotNull(clazz);

        Method m = clazz.getMethod("run");
        int res = (int) m.invoke(null);
        assertEquals(2, res);
    }

    @Test
    @DisplayName("Diamond operator with Map and multi-type arguments compiles")
    public void testDiamondOperatorMapSucceeds() throws Exception {
        String code = """
                import java.util.HashMap;
                import java.util.Map;

                public class DiamondMapRunner {
                    public static int function run() {
                        Map<String, int> map = new HashMap<>();
                        map.put("key1", 10);
                        map.put("key2", 20);
                        return map.size();
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("DiamondMapRunner", code);
        Class<?> clazz = classes.get("DiamondMapRunner");
        assertNotNull(clazz);

        Method m = clazz.getMethod("run");
        int res = (int) m.invoke(null);
        assertEquals(2, res);
    }
}
