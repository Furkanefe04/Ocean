package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EffectivelyFinalScopeTest extends CompilerTestHelper {

    private boolean compileSource(String code, String className) {
        try {
            compileToBytecodeMap(code, className);
            return !CompilerReporter.hasErrors();
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    @DisplayName("Valid effectively final local variable captured by lambda compiles successfully")
    public void testEffectivelyFinalLocalVariableCapturedSuccessfully() {
        String code = """
            class TestEffectivelyFinal {
                public static void function run() {
                    int a = 10;
                    java.lang.Runnable r = () -> {
                        OceanOutput(a);
                    };
                    r.run();
                }
            }
            """;
        assertTrue(compileSource(code, "TestEffectivelyFinal"), "Effectively final variable capture should compile without error");
    }

    @Test
    @DisplayName("Reassigning local variable after lambda capture should trigger effectively final compile error")
    public void testReassignmentAfterLambdaThrowsError() {
        String code = """
            class TestReassignAfterLambda {
                public static void function run() {
                    int a = 10;
                    java.lang.Runnable r = () -> {
                        OceanOutput(a);
                    };
                    a = 20;
                    r.run();
                }
            }
            """;
        assertFalse(compileSource(code, "TestReassignAfterLambda"), "Reassigning variable after lambda capture must fail compilation");
    }

    @Test
    @DisplayName("Incrementing local variable after lambda capture should trigger effectively final compile error")
    public void testIncrementAfterLambdaThrowsError() {
        String code = """
            class TestIncrementAfterLambda {
                public static void function run() {
                    int a = 10;
                    java.lang.Runnable r = () -> {
                        OceanOutput(a);
                    };
                    a++;
                    r.run();
                }
            }
            """;
        assertFalse(compileSource(code, "TestIncrementAfterLambda"), "Incrementing variable after lambda capture must fail compilation");
    }

    @Test
    @DisplayName("Mutating variable before lambda capture should trigger effectively final compile error")
    public void testReassignmentBeforeLambdaThrowsError() {
        String code = """
            class TestReassignBeforeLambda {
                public static void function run() {
                    int a = 10;
                    a = 20;
                    java.lang.Runnable r = () -> {
                        OceanOutput(a);
                    };
                    r.run();
                }
            }
            """;
        assertFalse(compileSource(code, "TestReassignBeforeLambda"), "Mutating variable before lambda capture must fail compilation");
    }

    @Test
    @DisplayName("Capturing for loop iterator directly in lambda should trigger effectively final compile error")
    public void testForLoopIteratorCapturedThrowsError() {
        String code = """
            class TestForLoopIteratorCaptured {
                public static void function run() {
                    for (int i from 0 to 5 with increasing 1) {
                        java.lang.Runnable r = () -> {
                            OceanOutput(i);
                        };
                    }
                }
            }
            """;
        assertFalse(compileSource(code, "TestForLoopIteratorCaptured"), "Directly capturing loop iterator in lambda must fail compilation");
    }

    @Test
    @DisplayName("Capturing local copy inside for loop in lambda should compile successfully")
    public void testForLoopLocalCopyCapturedSuccessfully() {
        String code = """
            class TestForLoopLocalCopy {
                public static void function run() {
                    for (int i from 0 to 5 with increasing 1) {
                        int copy = i;
                        java.lang.Runnable r = () -> {
                            OceanOutput(copy);
                        };
                        r.run();
                    }
                }
            }
            """;
        assertTrue(compileSource(code, "TestForLoopLocalCopy"), "Capturing loop local copy should compile successfully");
    }
}

