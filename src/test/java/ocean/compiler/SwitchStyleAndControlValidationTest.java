package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class SwitchStyleAndControlValidationTest extends CompilerTestHelper {

    @Test
    @DisplayName("Switch statement with mixed arrow and colon cases fails compilation")
    public void testMixedArrowAndColonCasesFails() {
        String code = """
                public class SwitchMixedCases {
                    public void function test(int x) {
                        switch (x) {
                            case 1 -> {}
                            case 2:
                                stop;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchMixedCases"));
        assertTrue(ex.getMessage().contains("cannot be mixed"),
                "Expected mixed switch style error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch statement with arrow case and colon default fails compilation")
    public void testMixedArrowCaseAndColonDefaultFails() {
        String code = """
                public class SwitchMixedArrowDefaultColon {
                    public void function test(int x) {
                        switch (x) {
                            case 1 -> {}
                            default:
                                stop;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchMixedArrowDefaultColon"));
        assertTrue(ex.getMessage().contains("cannot be mixed"),
                "Expected mixed switch style error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch statement with colon case and arrow default fails compilation")
    public void testMixedColonCaseAndArrowDefaultFails() {
        String code = """
                public class SwitchMixedColonDefaultArrow {
                    public void function test(int x) {
                        switch (x) {
                            case 1:
                                stop;
                            default -> {}
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchMixedColonDefaultArrow"));
        assertTrue(ex.getMessage().contains("cannot be mixed"),
                "Expected mixed switch style error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Arrow switch rule containing stop targeting the switch fails compilation")
    public void testArrowRuleWithStopFails() {
        String code = """
                public class SwitchArrowStop {
                    public void function test(int x) {
                        switch (x) {
                            case 1 -> {
                                stop;
                            }
                            default -> {}
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchArrowStop"));
        assertTrue(ex.getMessage().contains("'stop' (break) statement targeting switch is not allowed in arrow ('->') rule"),
                "Expected stop in arrow rule error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Arrow switch rule containing loop with stop succeeds (stop targets inner loop)")
    public void testArrowRuleWithNestedLoopStopPasses() throws Exception {
        String code = """
                public class SwitchArrowLoopStop {
                    public int function test(int x) {
                        int sum = 0;
                        switch (x) {
                            case 1 -> {
                                for (int i from 0 to 10 with increasing 1) {
                                    if (i == 5) {
                                        stop;
                                    }
                                    sum = sum + 1;
                                }
                            }
                            default -> {
                                sum = -1;
                            }
                        }
                        return sum;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("SwitchArrowLoopStop", code);
        Object inst = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("test", int.class);
        Object res1 = m.invoke(inst, 1);
        assertEquals(5, res1);
        Object res2 = m.invoke(inst, 2);
        assertEquals(-1, res2);
    }

    @Test
    @DisplayName("Switch expression containing stop fails compilation")
    public void testSwitchExpressionWithStopFails() {
        String code = """
                public class SwitchExprStop {
                    public int function test(int x) {
                        return switch (x) {
                            case 1 -> {
                                stop;
                                result 1;
                            }
                            default -> 0;
                        };
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchExprStop"));
        assertTrue(ex.getMessage().contains("Cannot 'stop' (break) out of a switch expression"),
                "Expected stop in switch expression error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid arrow switch statement compiles and executes properly")
    public void testValidArrowSwitchStatementPasses() throws Exception {
        String code = """
                public class ValidArrowSwitch {
                    public int function test(int x) {
                        int r = 0;
                        switch (x) {
                            case 1 -> r = 10;
                            case 2 -> { r = 20; }
                            default -> r = 30;
                        }
                        return r;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("ValidArrowSwitch", code);
        Object inst = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("test", int.class);
        assertEquals(10, m.invoke(inst, 1));
        assertEquals(20, m.invoke(inst, 2));
        assertEquals(30, m.invoke(inst, 99));
    }

    @Test
    @DisplayName("Valid colon switch statement compiles and executes properly")
    public void testValidColonSwitchStatementPasses() throws Exception {
        String code = """
                public class ValidColonSwitch {
                    public int function test(int x) {
                        int r = 0;
                        switch (x) {
                            case 1:
                                r = 10;
                                stop;
                            case 2:
                                r = 20;
                                stop;
                            default:
                                r = 30;
                                stop;
                        }
                        return r;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("ValidColonSwitch", code);
        Object inst = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("test", int.class);
        assertEquals(10, m.invoke(inst, 1));
        assertEquals(20, m.invoke(inst, 2));
        assertEquals(30, m.invoke(inst, 99));
    }
}
