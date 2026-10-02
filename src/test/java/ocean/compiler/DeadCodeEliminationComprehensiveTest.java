package ocean.compiler;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class DeadCodeEliminationComprehensiveTest {

    @Test
    public void testUnusedPureVariablesElimination() throws Exception {
        String code = """
            public class DcePureVarTest {
                public static int function compute() {
                    int a = 100;
                    int b = 200;
                    String unusedStr = "Ocean Language";
                    int unusedMath = a + b;
                    return 42;
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("DcePureVarTest", code);
        Method m = clazz.getMethod("compute");
        m.setAccessible(true);
        Object res = m.invoke(null);
        assertEquals(42, res);
    }

    @Test
    public void testSideEffectPreservedWhenVariableUnused() throws Exception {
        String code = """
            public class CounterHolder {
                public static int counter = 0;
                public static int function increment() {
                    counter++;
                    return counter;
                }
            }

            public class DceSideEffectTest {
                public static int function run() {
                    CounterHolder.counter = 0;
                    int unused = CounterHolder.increment();
                    int unused2 = CounterHolder.increment();
                    return CounterHolder.counter;
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("DceSideEffectTest", code);
        Method m = clazz.getMethod("run");
        m.setAccessible(true);
        Object res = m.invoke(null);
        assertEquals(2, res);
    }

    @Test
    public void testDeadBranchAndUnreachableStatementsPruning() throws Exception {
        String code = """
            public class DceBranchTest {
                public static String function check() {
                    if (false) {
                        return "dead";
                    }
                    while (false) {
                        int x = 10;
                    }
                    return "alive";
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("DceBranchTest", code);
        Method m = clazz.getMethod("check");
        m.setAccessible(true);
        assertEquals("alive", m.invoke(null));
    }

    @Test
    public void testChainedUnusedVariablesFixedPoint() throws Exception {
        String code = """
            public class DceChainedTest {
                public static int function testChain() {
                    int x = 10;
                    int y = x + 5;
                    int z = y * 2;
                    int w = z - 3;
                    return 100;
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("DceChainedTest", code);
        Method m = clazz.getMethod("testChain");
        m.setAccessible(true);
        assertEquals(100, m.invoke(null));
    }
}