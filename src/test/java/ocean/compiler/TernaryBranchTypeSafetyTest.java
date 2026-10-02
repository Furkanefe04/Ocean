package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TernaryBranchTypeSafetyTest extends CompilerTestHelper {

    // ==================== NEGATIVE (should FAIL) ====================

    @Test
    @DisplayName("Ternary arms String vs int without context fails")
    public void testTernaryStringVsIntFails() {
        String code = """
                public class T1 {
                    public static void function test() {
                        variable x = true ? "hello" : 42;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoadAll("T1", code));
        String msg = ex.getMessage();
        assertTrue(msg.contains("Ternary") || msg.contains("Incompatible") || msg.contains("atama"),
                "Expected ternary type mismatch error, got: " + msg);
    }

    @Test
    @DisplayName("Ternary arms int vs boolean fails")
    public void testTernaryIntVsBooleanFails() {
        String code = """
                public class T2 {
                    public static void function test() {
                        variable x = true ? 42 : false;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoadAll("T2", code));
        String msg = ex.getMessage();
        assertTrue(msg.contains("Ternary") || msg.contains("Incompatible") || msg.contains("atama"),
                "Expected ternary type mismatch error, got: " + msg);
    }

    @Test
    @DisplayName("Ternary used as String assignment with int false-arm fails")
    public void testTernaryStringAssignWithIntFails() {
        String code = """
                public class T3 {
                    public static String function test() {
                        String x = true ? "hello" : 42;
                        return x;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoadAll("T3", code));
        String msg = ex.getMessage();
        assertTrue(msg.contains("Incompatible") || msg.contains("atama") || msg.contains("Ternary"),
                "Expected type mismatch error, got: " + msg);
    }

    // ==================== POSITIVE (should PASS) ====================

    @Test
    @DisplayName("Ternary with both int arms compiles correctly")
    public void testTernaryBothIntArmsOk() throws Exception {
        String code = """
                public class T4 {
                    public static int function test() {
                        return true ? 10 : 20;
                    }
                }
                """;
        assertDoesNotThrow(() -> compileAndLoadAll("T4", code),
                "Both int arms should compile fine");
    }

    @Test
    @DisplayName("Ternary with both String arms compiles correctly")
    public void testTernaryBothStringArmsOk() throws Exception {
        String code = """
                public class T5 {
                    public static String function test() {
                        return true ? "yes" : "no";
                    }
                }
                """;
        assertDoesNotThrow(() -> compileAndLoadAll("T5", code),
                "Both String arms should compile fine");
    }

    @Test
    @DisplayName("Ternary with int and long arms compiles (numeric widening)")
    public void testTernaryIntAndLongOk() throws Exception {
        String code = """
                public class T6 {
                    public static long function test() {
                        return true ? 10 : 20L;
                    }
                }
                """;
        assertDoesNotThrow(() -> compileAndLoadAll("T6", code),
                "int and long arms should compile (widening)");
    }

    @Test
    @DisplayName("Ternary with subclass arms compiles (polymorphism)")
    public void testTernarySubclassArmsOk() throws Exception {
        String code = """
                public class Animal {}
                public class Dog extends Animal {}
                public class Cat extends Animal {}
                public class T7 {
                    public static Animal function test() {
                        return true ? new Dog() : new Cat();
                    }
                }
                """;
        assertDoesNotThrow(() -> compileAndLoadAll("T7", code),
                "Subclass arms should compile (common supertype)");
    }

    @Test
    @DisplayName("Ternary with null and reference type compiles")
    public void testTernaryNullAndStringOk() throws Exception {
        String code = """
                public class T8 {
                    public static String? function test() {
                        return true ? null : "default";
                    }
                }
                """;
        assertDoesNotThrow(() -> compileAndLoadAll("T8", code),
                "null and String arms should compile fine");
    }
}
