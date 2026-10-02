package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class Jls14StatementsTest extends CompilerTestHelper {

    // ==========================================
    //
    // ==========================================

    @Test
    @DisplayName("Pure expression statements emit compile-time warning")
    public void testPureExpressionStatementEmitsWarning() throws Exception {
        CompilerReporter.clear();
        String code = """
                public class Jls14_8_WarningRunner {
                    public static int function run() {
                        int x = 5;
                        1 + 2;
                        "unused string literal";
                        x;
                        x == 5;
                        return x;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("Jls14_8_WarningRunner", code);
        Class<?> clazz = classes.get("Jls14_8_WarningRunner");
        assertNotNull(clazz);

        Method m = clazz.getMethod("run");
        int res = (int) m.invoke(null);
        assertEquals(5, res);

        List<CompilerReporter.Message> messages = CompilerReporter.getMessages();
        long warningCount = messages.stream()
                .filter(msg -> msg.level() == CompilerReporter.Level.WARNING && msg.text().contains("no side effect"))
                .count();

        assertTrue(warningCount >= 4, "Expected at least 4 warnings for pure expressions without side effects, got: " + warningCount);
    }

    @Test
    @DisplayName("Side-effecting expressions do NOT emit §14.8 warning")
    public void testSideEffectingStatementsDoNotEmitWarning() throws Exception {
        CompilerReporter.clear();
        String code = """
                public class Jls14_8_NoWarningRunner {
                    public static void function helper() {}

                    public static int function run() {
                        int x = 1;
                        x = 10;
                        x++;
                        ++x;
                        x--;
                        --x;
                        helper();
                        new Object();
                        return x;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("Jls14_8_NoWarningRunner", code);
        Class<?> clazz = classes.get("Jls14_8_NoWarningRunner");
        assertNotNull(clazz);

        List<CompilerReporter.Message> messages = CompilerReporter.getMessages();
        boolean has14_8Warning = messages.stream()
                .anyMatch(msg -> msg.text().contains("yan etkisi yoktur"));

        assertFalse(has14_8Warning, "Side-effecting expressions should not emit unused expression warning");
    }

    // ==========================================
    //
    // ==========================================

    @Test
    @DisplayName("'skip' targeting non-loop block fails compilation")
    public void testContinueTargetingNonLoopBlockFails() {
        String code = """
                public class Jls14_16_ContinueBlockRunner {
                    public static void function test() {
                        myBlock: {
                            skip myBlock;
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("Jls14_16_ContinueBlockRunner", code));
        assertTrue(ex.getMessage().contains("must be a loop"),
                "Expected loop target error for skip, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("'skip' targeting a switch statement fails compilation")
    public void testContinueTargetingSwitchFails() {
        String code = """
                public class Jls14_16_ContinueSwitchRunner {
                    public static void function test() {
                        int x = 1;
                        swLabel: switch (x) {
                            case 1:
                                skip swLabel;
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("Jls14_16_ContinueSwitchRunner", code));
        assertTrue(ex.getMessage().contains("must be a loop"),
                "Expected loop target error when skipping switch, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("'skip' outside of loop fails compilation")
    public void testContinueOutsideLoopFails() {
        String code = """
                public class Jls14_16_ContinueOutsideRunner {
                    public static void function test() {
                        skip;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("Jls14_16_ContinueOutsideRunner", code));
        assertTrue(ex.getMessage().contains("can only be used within a loop"),
                "Expected outside loop error for skip, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid labeled 'skip' and 'stop' work correctly")
    public void testValidContinueAndBreakKeywordsWork() throws Exception {
        String code = """
                public class Jls14_16_ValidContinueBreakRunner {
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

        Map<String, Class<?>> classes = compileAndLoadAll("Jls14_16_ValidContinueBreakRunner", code);
        Class<?> clazz = classes.get("Jls14_16_ValidContinueBreakRunner");
        assertNotNull(clazz);

        Method m = clazz.getMethod("runTest");
        int sum = (int) m.invoke(null);
        assertEquals(4, sum);
    }

    // ==========================================
    //
    // ==========================================

    @Test
    @DisplayName("Duplicate enclosing label in nested blocks fails compilation")
    public void testDuplicateEnclosingLabelInNestedBlocksFails() {
        String code = """
                public class Jls14_7_DuplicateLabelRunner {
                    public static void function test() {
                        myLabel: {
                            int x = 1;
                            if (x > 0) {
                                myLabel: while (true) {
                                    stop myLabel;
                                }
                            }
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("Jls14_7_DuplicateLabelRunner", code));
        assertTrue(ex.getMessage().contains("already defined"),
                "Expected duplicate enclosing label error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Non-enclosing sequential labels with same name succeed")
    public void testSequentialLabelsWithSameNameSucceed() throws Exception {
        String code = """
                public class Jls14_7_SequentialLabelsRunner {
                    public static int function run() {
                        int a = 0;
                        int i = 0;
                        lbl: while (i < 3) {
                            a = a + 1;
                            i = i + 1;
                        }
                        int j = 0;
                        lbl: while (j < 3) {
                            a = a + 1;
                            j = j + 1;
                        }
                        return a;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("Jls14_7_SequentialLabelsRunner", code);
        Class<?> clazz = classes.get("Jls14_7_SequentialLabelsRunner");
        assertNotNull(clazz);

        Method m = clazz.getMethod("run");
        int res = (int) m.invoke(null);
        assertEquals(6, res);
    }

    @Test
    @DisplayName("Same label name in different methods succeeds")
    public void testSameLabelInDifferentMethodsSucceeds() throws Exception {
        String code = """
                public class Jls14_7_DifferentMethodsLabelRunner {
                    public static int function m1() {
                        int x = 0;
                        loop: while (x < 2) {
                            x = x + 1;
                        }
                        return x;
                    }

                    public static int function m2() {
                        int y = 0;
                        loop: while (y < 3) {
                            y = y + 1;
                        }
                        return y;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("Jls14_7_DifferentMethodsLabelRunner", code);
        Class<?> clazz = classes.get("Jls14_7_DifferentMethodsLabelRunner");
        assertNotNull(clazz);

        Method m1 = clazz.getMethod("m1");
        Method m2 = clazz.getMethod("m2");
        assertEquals(2, m1.invoke(null));
        assertEquals(3, m2.invoke(null));
    }
}
