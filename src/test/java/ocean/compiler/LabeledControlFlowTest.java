package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LabeledControlFlowTest extends CompilerTestHelper {

    @Test
    @DisplayName("Labeled while loop break with 'stop outer'")
    public void testLabeledStopBreaksOuterLoop() throws Exception {
        String code = """
                public class LabeledStopRunner {
                    public static int function runTest() {
                        int sum = 0;
                        int i = 0;
                        outer: while (i < 5) {
                            int j = 0;
                            while (j < 5) {
                                if (i == 2 && j == 1) {
                                    stop outer;
                                }
                                sum = sum + 1;
                                j = j + 1;
                            }
                            i = i + 1;
                        }
                        return sum;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("LabeledStopRunner", code);
        Class<?> clazz = classes.get("LabeledStopRunner");
        assertNotNull(clazz);

        Method m = clazz.getMethod("runTest");
        int sum = (int) m.invoke(null);
        // i=0: 5 iterations, i=1: 5 iterations, i=2: j=0 is 1 iteration, then stop outer. Total: 11
        assertEquals(11, sum);
    }

    @Test
    @DisplayName("Labeled while loop continue with 'skip outer'")
    public void testLabeledSkipContinuesOuterLoop() throws Exception {
        String code = """
                public class LabeledSkipRunner {
                    public static int function runTest() {
                        int sum = 0;
                        int i = 0;
                        outer: while (i < 4) {
                            i = i + 1;
                            int j = 0;
                            while (j < 4) {
                                j = j + 1;
                                if (j == 2) {
                                    skip outer;
                                }
                                sum = sum + 1;
                            }
                        }
                        return sum;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("LabeledSkipRunner", code);
        Class<?> clazz = classes.get("LabeledSkipRunner");
        assertNotNull(clazz);

        Method m = clazz.getMethod("runTest");
        int sum = (int) m.invoke(null);
        // Each outer iteration reaches j=1 (sum + 1), then j=2 (skip outer).
        // 4 outer iterations * 1 = 4
        assertEquals(4, sum);
    }

    @Test
    @DisplayName("Labeled block statement break with 'stop myBlock'")
    public void testLabeledBlockBreak() throws Exception {
        String code = """
                public class LabeledBlockRunner {
                    public static int function runTest(bool escape) {
                        int val = 10;
                        myBlock: {
                            val = val + 5;
                            if (escape) {
                                stop myBlock;
                            }
                            val = val + 20;
                        }
                        return val;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("LabeledBlockRunner", code);
        Class<?> clazz = classes.get("LabeledBlockRunner");
        assertNotNull(clazz);

        Method m = clazz.getMethod("runTest", boolean.class);
        assertEquals(15, m.invoke(null, true));
        assertEquals(35, m.invoke(null, false));
    }

    @Test
    @DisplayName("Undefined label in stop statement fails compilation")
    public void testUndefinedLabelInStopFails() {
        String code = """
                public class BadLabelRunner {
                    public static void function test() {
                        while (true) {
                            stop nonexistent;
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("BadLabelRunner", code));
        assertTrue(ex.getMessage().contains("Undefined label"),
                "Expected undefined label error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("skip statement targeting non-loop block fails compilation")
    public void testSkipTargetingNonLoopFails() {
        String code = """
                public class BadSkipRunner {
                    public static void function test() {
                        myBlock: {
                            skip myBlock;
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("BadSkipRunner", code));
        assertTrue(ex.getMessage().contains("must be a loop"),
                "Expected loop required error for skip, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Duplicate label in enclosing scope fails compilation")
    public void testDuplicateLabelFails() {
        String code = """
                public class DuplicateLabelRunner {
                    public static void function test() {
                        loop1: while (true) {
                            loop1: while (true) {
                                stop loop1;
                            }
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("DuplicateLabelRunner", code));
        assertTrue(ex.getMessage().contains("already defined"),
                "Expected duplicate label error, got: " + ex.getMessage());
    }
}
