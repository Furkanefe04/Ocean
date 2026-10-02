package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class BlankFinalDefiniteAssignmentTest extends CompilerTestHelper {

    @Test
    @DisplayName("Valid if-else assigning blank final field compiles and executes")
    public void testValidIfElseAssignmentCompilesAndRuns() throws Exception {
        String code = """
                public class ValidIfElseTest {
                    public final int x;
                    public ValidIfElseTest(boolean cond) {
                        if (cond) {
                            this.x = 10;
                        } else {
                            this.x = 20;
                        }
                    }
                    public int function getX() {
                        return this.x;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ValidIfElseTest", code);
        Class<?> clazz = classes.get("ValidIfElseTest");
        Method getX = clazz.getMethod("getX");

        Object objTrue = clazz.getConstructor(boolean.class).newInstance(true);
        assertEquals(10, getX.invoke(objTrue));

        Object objFalse = clazz.getConstructor(boolean.class).newInstance(false);
        assertEquals(20, getX.invoke(objFalse));
    }

    @Test
    @DisplayName("Partial if without else leaves blank final unassigned and fails compilation")
    public void testPartialIfWithoutElseFails() {
        String code = """
                public class PartialIfTest {
                    public final int x;
                    public PartialIfTest(boolean cond) {
                        if (cond) {
                            this.x = 10;
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("PartialIfTest", code));
        assertTrue(ex.getMessage().contains("Final field 'x' might not have been initialized"),
                "Expected uninitialized blank final error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("If-else where else branch does not assign blank final fails compilation")
    public void testIfElseWhereOnlyOneBranchAssignsFails() {
        String code = """
                public class IfElseMissingTest {
                    public final int x;
                    public IfElseMissingTest(boolean cond) {
                        if (cond) {
                            this.x = 10;
                        } else {
                            int y = 5;
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("IfElseMissingTest", code));
        assertTrue(ex.getMessage().contains("Final field 'x' might not have been initialized"),
                "Expected uninitialized blank final error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Blank final assignment inside while loop fails compilation")
    public void testAssignmentInWhileLoopFails() {
        String code = """
                public class WhileLoopAssignTest {
                    public final int x;
                    public WhileLoopAssignTest(boolean cond) {
                        while (cond) {
                            this.x = 10;
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("WhileLoopAssignTest", code));
        assertTrue(ex.getMessage().contains("Cannot assign final"),
                "Expected loop assignment error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Blank final assignment inside for loop fails compilation")
    public void testAssignmentInForLoopFails() {
        String code = """
                public class ForLoopAssignTest {
                    public final int x;
                    public ForLoopAssignTest() {
                        for (int i from 0 to 10 with increasing 1) {
                            this.x = i;
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ForLoopAssignTest", code));
        assertTrue(ex.getMessage().contains("Cannot assign final"),
                "Expected loop assignment error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Reassignment after if-else fails compilation")
    public void testReassignmentAfterIfElseFails() {
        String code = """
                public class ReassignAfterIfElseTest {
                    public final int x;
                    public ReassignAfterIfElseTest(boolean cond) {
                        if (cond) {
                            this.x = 10;
                        } else {
                            this.x = 20;
                        }
                        this.x = 30;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ReassignAfterIfElseTest", code));
        assertTrue(ex.getMessage().contains("Cannot assign a value to final"),
                "Expected reassignment error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Reassignment after partial if fails compilation due to potential assignment")
    public void testReassignmentAfterPartialIfFails() {
        String code = """
                public class ReassignAfterPartialIfTest {
                    public final int x;
                    public ReassignAfterPartialIfTest(boolean cond) {
                        if (cond) {
                            this.x = 10;
                        }
                        this.x = 20;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ReassignAfterPartialIfTest", code));
        assertTrue(ex.getMessage().contains("Cannot assign a value to final"),
                "Expected potential reassignment error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Guard clause in constructor allows definite assignment and executes")
    public void testGuardClauseDefiniteAssignmentCompilesAndRuns() throws Exception {
        String code = """
                public class GuardClauseTest {
                    public final int x;
                    public GuardClauseTest(boolean early) {
                        if (early) {
                            this.x = 10;
                            return;
                        }
                        this.x = 20;
                    }
                    public int function getX() {
                        return this.x;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("GuardClauseTest", code);
        Class<?> clazz = classes.get("GuardClauseTest");
        Method getX = clazz.getMethod("getX");

        Object objTrue = clazz.getConstructor(boolean.class).newInstance(true);
        assertEquals(10, getX.invoke(objTrue));

        Object objFalse = clazz.getConstructor(boolean.class).newInstance(false);
        assertEquals(20, getX.invoke(objFalse));
    }

    @Test
    @DisplayName("Blank final assignment inside lambda inside constructor fails compilation")
    public void testBlankFinalAssignmentInsideLambdaFails() {
        String code = """
                public class LambdaAssignTest {
                    public final int x;
                    public LambdaAssignTest() {
                        Runnable r = () -> {
                            this.x = 42;
                        };
                        this.x = 10;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("LambdaAssignTest", code));
        assertTrue(ex.getMessage().contains("Cannot assign a value to final field 'x'"),
                "Expected error rejecting blank final assignment inside lambda, got: " + ex.getMessage());
    }
}
