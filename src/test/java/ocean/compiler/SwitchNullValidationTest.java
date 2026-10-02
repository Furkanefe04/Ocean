package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class SwitchNullValidationTest extends CompilerTestHelper {

    @Test
    @DisplayName("Switch statement with primitive int selector and case null fails compilation")
    public void testPrimitiveIntSwitchWithNullCaseFails() {
        String code = """
                public class PrimitiveIntNullSwitch {
                    public void function test(int x) {
                        switch (x) {
                            case null -> {}
                            default -> {}
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "PrimitiveIntNullSwitch"));
        assertTrue(ex.getMessage().contains("primitive"),
                "Expected primitive switch with null error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch statement with primitive boolean selector and case null fails compilation")
    public void testPrimitiveBoolSwitchWithNullCaseFails() {
        String code = """
                public class PrimitiveBoolNullSwitch {
                    public void function test(boolean b) {
                        switch (b) {
                            case null -> {}
                            case true -> {}
                            case false -> {}
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "PrimitiveBoolNullSwitch"));
        assertTrue(ex.getMessage().contains("primitive"),
                "Expected primitive switch with null error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch statement with primitive char selector and case null fails compilation")
    public void testPrimitiveCharSwitchWithNullCaseFails() {
        String code = """
                public class PrimitiveCharNullSwitch {
                    public void function test(char c) {
                        switch (c) {
                            case null:
                                stop;
                            default:
                                stop;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "PrimitiveCharNullSwitch"));
        assertTrue(ex.getMessage().contains("primitive"),
                "Expected primitive switch with null error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch expression with primitive int selector and case null fails compilation")
    public void testPrimitiveSwitchExprWithNullCaseFails() {
        String code = """
                public class PrimitiveSwitchExprNull {
                    public int function test(int x) {
                        return switch (x) {
                            case null -> 0;
                            default -> 1;
                        };
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "PrimitiveSwitchExprNull"));
        assertTrue(ex.getMessage().contains("primitive"),
                "Expected primitive switch expr with null error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Case null with when guard fails compilation")
    public void testGuardedNullCaseFails() {
        String code = """
                public class GuardedNullSwitch {
                    public void function test(Object obj) {
                        switch (obj) {
                            case null when true -> {}
                            default -> {}
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "GuardedNullSwitch"));
        assertTrue(ex.getMessage().contains("cannot have a 'when' guard"),
                "Expected guarded null error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch with duplicate case null fails compilation")
    public void testDuplicateNullCaseFails() {
        String code = """
                public class DuplicateNullSwitch {
                    public void function test(Object obj) {
                        switch (obj) {
                            case null -> {}
                            case null -> {}
                            default -> {}
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "DuplicateNullSwitch"));
        assertTrue(ex.getMessage().contains("Duplicate 'null' label"),
                "Expected duplicate null error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Case null placed after unguarded total pattern fails compilation")
    public void testNullAfterTotalPatternFails() {
        String code = """
                public class NullAfterTotalPatternSwitch {
                    public void function test(Object obj) {
                        switch (obj) {
                            case Object o -> {}
                            case null -> {}
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "NullAfterTotalPatternSwitch"));
        assertTrue(ex.getMessage().contains("genel tip deseninden"),
                "Expected null after total pattern error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid switch expression with case null compiles and executes correctly")
    public void testValidObjectSwitchWithNullCasePasses() throws Exception {
        String code = """
                public class ValidObjectNullSwitch {
                    public String function test(Object? obj) {
                        return switch (obj) {
                            case null -> "NULL";
                            case String s -> "STRING:" + s;
                            default -> "OTHER";
                        };
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("ValidObjectNullSwitch", code);
        Object inst = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("test", Object.class);
        assertEquals("NULL", m.invoke(inst, new Object[]{null}));
        assertEquals("STRING:hello", m.invoke(inst, "hello"));
        assertEquals("OTHER", m.invoke(inst, 42));
    }

    @Test
    @DisplayName("Valid switch statement with boxed Integer and case null compiles and executes correctly")
    public void testValidBoxedIntegerSwitchWithNullCasePasses() throws Exception {
        String code = """
                public class ValidBoxedIntegerNullSwitch {
                    public int function test(Integer? num) {
                        int r = 0;
                        switch (num) {
                            case null -> r = -1;
                            case 10 -> r = 100;
                            default -> r = 1;
                        }
                        return r;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("ValidBoxedIntegerNullSwitch", code);
        Object inst = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("test", Integer.class);
        assertEquals(-1, m.invoke(inst, new Object[]{null}));
        assertEquals(100, m.invoke(inst, Integer.valueOf(10)));
        assertEquals(1, m.invoke(inst, Integer.valueOf(5)));
    }
}
