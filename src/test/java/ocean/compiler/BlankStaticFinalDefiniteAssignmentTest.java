package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class BlankStaticFinalDefiniteAssignmentTest extends CompilerTestHelper {

    @Test
    @DisplayName("Valid static block assigning blank static final field compiles and executes")
    public void testValidStaticBlockAssignmentCompilesAndRuns() throws Exception {
        String code = """
                public class ValidStaticBlockTest {
                    public static final int CONST;
                    static {
                        CONST = 42;
                    }
                    public static int function getConst() {
                        return CONST;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ValidStaticBlockTest", code);
        Class<?> clazz = classes.get("ValidStaticBlockTest");
        Method getConst = clazz.getMethod("getConst");

        assertEquals(42, getConst.invoke(null));
    }

    @Test
    @DisplayName("Valid if-else in static block assigning blank static final field compiles and executes")
    public void testValidIfElseAssignmentInStaticBlockCompilesAndRuns() throws Exception {
        String code = """
                public class ValidIfElseStaticTest {
                    public static final int VAL;
                    static {
                        boolean b = true;
                        if (b) {
                            VAL = 100;
                        } else {
                            VAL = 200;
                        }
                    }
                    public static int function getVal() {
                        return VAL;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ValidIfElseStaticTest", code);
        Class<?> clazz = classes.get("ValidIfElseStaticTest");
        Method getVal = clazz.getMethod("getVal");

        assertEquals(100, getVal.invoke(null));
    }

    @Test
    @DisplayName("Uninitialized blank static final field fails compilation")
    public void testUninitializedBlankStaticFinalFails() {
        String code = """
                public class UninitStaticTest {
                    public static final int CONST;
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("UninitStaticTest", code));
        assertTrue(ex.getMessage().contains("Static final field 'CONST' might not have been initialized") ||
                   ex.getMessage().contains("must have an initializer or assignment in static initializer block"),
                "Expected uninitialized static final error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Reassigning inline-initialized static final field in static block fails compilation")
    public void testReassignInlineInitializedStaticFinalFails() {
        String code = """
                public class ReassignInlineStaticTest {
                    public static final int CONST = 10;
                    static {
                        CONST = 20;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ReassignInlineStaticTest", code));
        assertTrue(ex.getMessage().contains("Cannot assign a value to static final field 'CONST'"),
                "Expected reassign error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Reassigning blank static final field multiple times in static block fails compilation")
    public void testReassignBlankStaticFinalMultipleTimesFails() {
        String code = """
                public class MultiAssignStaticTest {
                    public static final int CONST;
                    static {
                        CONST = 10;
                        CONST = 20;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("MultiAssignStaticTest", code));
        assertTrue(ex.getMessage().contains("Cannot assign a value to static final field 'CONST'"),
                "Expected multiple assignment error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Blank static final assignment inside while loop fails compilation")
    public void testAssignmentInWhileLoopFails() {
        String code = """
                public class WhileLoopStaticTest {
                    public static final int CONST;
                    static {
                        boolean cond = true;
                        while (cond) {
                            CONST = 10;
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("WhileLoopStaticTest", code));
        assertTrue(ex.getMessage().contains("Cannot assign static final"),
                "Expected loop assignment error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Blank static final assignment inside for loop fails compilation")
    public void testAssignmentInForLoopFails() {
        String code = """
                public class ForLoopStaticTest {
                    public static final int CONST;
                    static {
                        for (int i from 0 to 10 with increasing 1) {
                            CONST = i;
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ForLoopStaticTest", code));
        assertTrue(ex.getMessage().contains("Cannot assign static final"),
                "Expected loop assignment error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Partial if without else in static block leaves blank static final unassigned and fails compilation")
    public void testPartialIfWithoutElseLeavesBlankStaticFinalUnassignedFails() {
        String code = """
                public class PartialIfStaticTest {
                    public static final int CONST;
                    static {
                        boolean b = true;
                        if (b) {
                            CONST = 10;
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("PartialIfStaticTest", code));
        assertTrue(ex.getMessage().contains("Static final field 'CONST' might not have been initialized"),
                "Expected uninitialized static final error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Reassignment after if-else in static block fails compilation")
    public void testReassignmentAfterIfElseFails() {
        String code = """
                public class ReassignAfterIfElseStaticTest {
                    public static final int CONST;
                    static {
                        boolean b = true;
                        if (b) {
                            CONST = 10;
                        } else {
                            CONST = 20;
                        }
                        CONST = 30;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ReassignAfterIfElseStaticTest", code));
        assertTrue(ex.getMessage().contains("Cannot assign a value to static final field 'CONST'"),
                "Expected potential reassignment error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Multiple static blocks: assigning in first, reassigning in second fails compilation")
    public void testMultipleStaticBlocksSequentialDefiniteAssignmentFailsOnReassign() {
        String code = """
                public class MultiBlockStaticTest {
                    public static final int CONST;
                    static {
                        CONST = 10;
                    }
                    static {
                        CONST = 20;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("MultiBlockStaticTest", code));
        assertTrue(ex.getMessage().contains("Cannot assign a value to static final field 'CONST'"),
                "Expected reassignment error across static blocks, got: " + ex.getMessage());
    }
}
