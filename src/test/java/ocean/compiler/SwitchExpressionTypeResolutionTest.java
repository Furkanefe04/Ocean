package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwitchExpressionTypeResolutionTest extends CompilerTestHelper {

    @Test
    @DisplayName("Switch expression with method calls evaluates and resolves to int")
    void testMethodCallSwitchExpressionAssignedToInt() throws Exception {
        Class<?> clazz = compileAndLoad("SwitchMethodExpr", """
                public class SwitchMethodExpr {
                    public static int function getA() { return 100; }
                    public static int function getB() { return 200; }

                    public static int function test(int x) {
                        int res = switch (x) {
                            case 1 -> getA();
                            case 2 -> getB();
                            default -> 0;
                        };
                        return res;
                    }
                }
                """);
        Method m = clazz.getMethod("test", int.class);
        assertEquals(100, m.invoke(null, 1));
        assertEquals(200, m.invoke(null, 2));
        assertEquals(0, m.invoke(null, 3));
    }

    @Test
    @DisplayName("Block switch expression with result statements resolves correctly")
    void testBlockSwitchExpressionWithResult() throws Exception {
        Class<?> clazz = compileAndLoad("SwitchBlockExpr", """
                public class SwitchBlockExpr {
                    public static int function test(int x) {
                        int res = switch (x) {
                            case 1 -> {
                                int a = 50;
                                result a * 2;
                            }
                            default -> {
                                result 999;
                            }
                        };
                        return res;
                    }
                }
                """);
        Method m = clazz.getMethod("test", int.class);
        assertEquals(100, m.invoke(null, 1));
        assertEquals(999, m.invoke(null, 2));
    }

    @Test
    @DisplayName("Switch expression numeric promotion int and long produces long")
    void testNumericPromotionSwitchExpression() throws Exception {
        Class<?> clazz = compileAndLoad("SwitchNumPromo", """
                public class SwitchNumPromo {
                    public static long function test(int x) {
                        long res = switch (x) {
                            case 1 -> 10;
                            default -> 20L;
                        };
                        return res;
                    }
                }
                """);
        Method m = clazz.getMethod("test", int.class);
        assertEquals(10L, m.invoke(null, 1));
        assertEquals(20L, m.invoke(null, 2));
    }

    @Test
    @DisplayName("Switch expression with void branch fails compilation")
    void testVoidBranchSwitchExpressionFails() {
        Exception e = assertThrows(Exception.class, () -> {
            compileAndLoad("BadVoidSwitch", """
                    public class BadVoidSwitch {
                        public static void function doNothing() {}
                        public static int function test(int x) {
                            int res = switch (x) {
                                case 1 -> doNothing();
                                default -> 0;
                            };
                            return res;
                        }
                    }
                    """);
        });
        assertTrue(e.getMessage().contains("void") && e.getMessage().contains("Switch expression"),
                "Expected void branch rejection error, got: " + e.getMessage());
    }

    @Test
    @DisplayName("Switch expression combining null with primitive fails compilation")
    void testNullWithPrimitiveSwitchExpressionFails() {
        Exception e = assertThrows(Exception.class, () -> {
            compileAndLoad("BadNullSwitch", """
                    public class BadNullSwitch {
                        public static int function test(int x) {
                            int res = switch (x) {
                                case 1 -> null;
                                default -> 42;
                            };
                            return res;
                        }
                    }
                    """);
        });
        assertTrue(e.getMessage().contains("Switch expression cannot combine"),
                "Expected null with primitive error, got: " + e.getMessage());
    }

    @Test
    @DisplayName("Switch expression with incompatible branch types (boolean vs int) fails compilation")
    void testIncompatibleBranchTypesSwitchExpressionFails() {
        Exception e = assertThrows(Exception.class, () -> {
            compileAndLoad("BadIncompatSwitch", """
                    public class BadIncompatSwitch {
                        public static Object function test(int x) {
                            Object res = switch (x) {
                                case 1 -> true;
                                default -> 10;
                            };
                            return res;
                        }
                    }
                    """);
        });
        assertTrue(e.getMessage().contains("Incompatible branch types"),
                "Expected incompatible branch types error, got: " + e.getMessage());
    }
}
