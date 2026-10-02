package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class FinallyAbruptCompletionTest extends CompilerTestHelper {

    @Test
    @DisplayName("Return inside finally block fails compilation")
    public void testReturnInFinallyFailsCompilation() {
        String code = """
                public class FinallyReturnTest {
                    public static int function test() {
                        trying {
                            return 1;
                        } finally {
                            return 2;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "FinallyReturnTest"));
        assertTrue(ex.getMessage().contains("'return' statement cannot be used inside finally block"),
                "Expected abrupt completion error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Stop (break) inside finally block fails compilation")
    public void testStopInFinallyFailsCompilation() {
        String code = """
                public class FinallyStopTest {
                    public static void function test() {
                        while (true) {
                            trying {
                                OceanOutput("in try");
                            } finally {
                                stop;
                            }
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "FinallyStopTest"));
        assertTrue(ex.getMessage().contains("'stop' (break) statement cannot be used inside finally block"),
                "Expected abrupt completion error, got: " + ex.getMessage());
    }
}