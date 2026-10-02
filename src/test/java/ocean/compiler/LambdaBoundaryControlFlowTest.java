package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LambdaBoundaryControlFlowTest extends CompilerTestHelper {

    @Test
    @DisplayName("Unlabeled 'stop' inside lambda inside a loop fails compilation")
    public void testUnlabeledStopInsideLambdaFails() {
        String code = """
                import java.lang.Runnable;
                public class TestUnlabeledStop {
                    public static void function run() {
                        while (true) {
                            Runnable r = () -> {
                                stop;
                            };
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("TestUnlabeledStop", code));
        assertTrue(ex.getMessage().contains("cannot jump outside lambda boundary"),
                "Expected lambda boundary error for unlabeled stop, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Unlabeled 'skip' inside lambda inside a loop fails compilation")
    public void testUnlabeledSkipInsideLambdaFails() {
        String code = """
                import java.lang.Runnable;
                public class TestUnlabeledSkip {
                    public static void function run() {
                        while (true) {
                            Runnable r = () -> {
                                skip;
                            };
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("TestUnlabeledSkip", code));
        assertTrue(ex.getMessage().contains("cannot jump outside lambda boundary"),
                "Expected lambda boundary error for unlabeled skip, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Labeled 'stop outer' inside lambda targeting outer loop fails compilation")
    public void testLabeledStopInsideLambdaFails() {
        String code = """
                import java.lang.Runnable;
                public class TestLabeledStop {
                    public static void function run() {
                        outer: while (true) {
                            Runnable r = () -> {
                                stop outer;
                            };
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("TestLabeledStop", code));
        assertTrue(ex.getMessage().contains("cannot jump outside lambda boundary"),
                "Expected lambda boundary error for labeled stop, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Labeled 'skip outer' inside lambda targeting outer loop fails compilation")
    public void testLabeledSkipInsideLambdaFails() {
        String code = """
                import java.lang.Runnable;
                public class TestLabeledSkip {
                    public static void function run() {
                        outer: while (true) {
                            Runnable r = () -> {
                                skip outer;
                            };
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("TestLabeledSkip", code));
        assertTrue(ex.getMessage().contains("cannot jump outside lambda boundary"),
                "Expected lambda boundary error for labeled skip, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Labeled 'stop block' inside lambda targeting outer block fails compilation")
    public void testLabeledBlockStopInsideLambdaFails() {
        String code = """
                import java.lang.Runnable;
                public class TestLabeledBlockStop {
                    public static void function run() {
                        myBlock: {
                            Runnable r = () -> {
                                stop myBlock;
                            };
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("TestLabeledBlockStop", code));
        assertTrue(ex.getMessage().contains("cannot jump outside lambda boundary"),
                "Expected lambda boundary error for labeled block stop, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("'result' inside lambda inside a switch expression fails compilation")
    public void testResultInsideLambdaFails() {
        String code = """
                import java.lang.Runnable;
                public class TestResultInsideLambda {
                    public static int function run(int n) {
                        return switch (n) {
                            case 1 -> {
                                Runnable r = () -> {
                                    result 42;
                                };
                                result 10;
                            }
                            default -> 0;
                        };
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("TestResultInsideLambda", code));
        assertTrue(ex.getMessage().contains("cannot jump outside lambda boundary"),
                "Expected lambda boundary error for result statement, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Loop inside lambda using 'stop' and 'skip' succeeds")
    public void testLoopInsideLambdaSucceeds() throws Exception {
        String code = """
                import java.util.function.Supplier;
                public class TestLoopInsideLambda {
                    public static int function run() {
                        Supplier<int> s = () -> {
                            int count = 0;
                            int i = 0;
                            while (i < 10) {
                                i = i + 1;
                                if (i == 2) {
                                    skip;
                                }
                                if (i == 5) {
                                    stop;
                                }
                                count = count + 1;
                            }
                            return count;
                        };
                        return s.get();
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("TestLoopInsideLambda", code);
        Class<?> clazz = classes.get("TestLoopInsideLambda");
        assertNotNull(clazz);
        Method m = clazz.getMethod("run");
        // i=1: count=1
        // i=2: skip
        // i=3: count=2
        // i=4: count=3
        // i=5: stop
        // total count = 3
        assertEquals(3, m.invoke(null));
    }

    @Test
    @DisplayName("Labeled loop inside lambda using 'stop label' and 'skip label' succeeds")
    public void testLabeledLoopInsideLambdaSucceeds() throws Exception {
        String code = """
                import java.util.function.Supplier;
                public class TestLabeledInsideLambda {
                    public static int function run() {
                        Supplier<int> s = () -> {
                            int sum = 0;
                            int i = 0;
                            innerOuter: while (i < 5) {
                                int j = 0;
                                innerInner: while (j < 5) {
                                    if (i == 2 && j == 1) {
                                        stop innerOuter;
                                    }
                                    sum = sum + 1;
                                    j = j + 1;
                                }
                                i = i + 1;
                            }
                            return sum;
                        };
                        return s.get();
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("TestLabeledInsideLambda", code);
        Class<?> clazz = classes.get("TestLabeledInsideLambda");
        assertNotNull(clazz);
        Method m = clazz.getMethod("run");
        assertEquals(11, m.invoke(null));
    }

    @Test
    @DisplayName("Nested lambda attempting to jump to outer lambda's loop fails compilation")
    public void testNestedLambdaJumpingToOuterLambdaLoopFails() {
        String code = """
                import java.lang.Runnable;
                public class TestNestedLambdaJump {
                    public static void function run() {
                        Runnable r1 = () -> {
                            outerLoop: while (true) {
                                Runnable r2 = () -> {
                                    stop outerLoop;
                                };
                            }
                        };
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("TestNestedLambdaJump", code));
        assertTrue(ex.getMessage().contains("cannot jump outside lambda boundary"),
                "Expected lambda boundary error in nested lambda, got: " + ex.getMessage());
    }
}
