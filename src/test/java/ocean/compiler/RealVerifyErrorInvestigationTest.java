package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class RealVerifyErrorInvestigationTest extends CompilerTestHelper {

    // ─────────────────────────────────────────────────────────────────────────────
    // Madde 1: Switch Expression Guard'da Boolean unboxing eksikliği (VerifyError)
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Madde 1: Switch expression guard with Boolean-returning method — no VerifyError")
    public void testSwitchExprGuardBooleanUnboxing() throws Exception {
        String code = """
                public class GuardBooleanTest {
                    public static Boolean function check(int x) {
                        return x > 0;
                    }
                    public static String function test(int x) {
                        return switch (x) {
                            case 10 when check(x) -> "matched_true";
                            case 10 -> "matched_false";
                            default -> "other";
                        };
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("GuardBooleanTest", code);
        Method m = clazz.getMethod("test", int.class);
        assertEquals("matched_true", m.invoke(null, 10));
        assertEquals("other", m.invoke(null, 5));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Madde 2: Enum switch'te when guard yutulması — TABLESWITCH guard'ı atlatıyordu
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Madde 2: Enum switch expression with when guard — guard must not be swallowed")
    public void testEnumSwitchWithGuard() throws Exception {
        String code = """
                public enum Color { RED, GREEN, BLUE }
                public class EnumGuardTest {
                    public static String function test(Color c, boolean bright) {
                        return switch (c) {
                            case RED when bright -> "bright_red"
                            case RED -> "dark_red"
                            case GREEN -> "green"
                            default -> "other"
                        };
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("EnumGuardTest", code);
        Class<?> colorCls = clazz.getClassLoader().loadClass("EnumGuardTest.Color");
        Object red = Enum.valueOf((Class<Enum>) colorCls, "RED");
        Object green = Enum.valueOf((Class<Enum>) colorCls, "GREEN");
        Method m = clazz.getMethod("test", colorCls, boolean.class);
        assertEquals("bright_red", m.invoke(null, red, true));
        assertEquals("dark_red",   m.invoke(null, red, false));
        assertEquals("green",      m.invoke(null, green, true));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Madde 2b: String switch'te when guard yutulması
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Madde 2b: String switch with when guard — guard must not be swallowed")
    public void testStringSwitchWithGuard() throws Exception {
        String code = """
                public class StringGuardTest {
                    public static String function test(String s, int len) {
                        return switch (s) {
                            case "hello" when len > 3 -> "long_hello";
                            case "hello" -> "short_hello";
                            default -> "other";
                        };
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("StringGuardTest", code);
        Method m = clazz.getMethod("test", String.class, int.class);
        assertEquals("long_hello",  m.invoke(null, "hello", 5));
        assertEquals("short_hello", m.invoke(null, "hello", 2));
        assertEquals("other",       m.invoke(null, "world", 5));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Madde 5: Interface default metot super çağrısı — INVOKESPECIAL itf=true
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Madde 5: Interface default method super call — no IncompatibleClassChangeError")
    public void testInterfaceDefaultMethodSuperCall() throws Exception {
        String code = """
                public interface Greeter {
                    public String function greet() {
                        return "Hello from Greeter";
                    }
                }
                public class FriendlyGreeter implements Greeter {
                    public String function greet() {
                        return "Friendly: " + super.greet();
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("FriendlyGreeter", code);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("greet");
        String result = (String) m.invoke(instance);
        assertTrue(result.contains("Friendly:") && result.contains("Hello from Greeter"),
                "Expected 'Friendly: Hello from Greeter' but got: " + result);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Madde 9: String switch expression case null yönlendirmesi
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Madde 9: String switch expression with case null — must route to null case body")
    public void testStringSwitchCaseNull() throws Exception {
        String code = """
                public class NullSwitchTest {
                    public static String function test(String s) {
                        return switch (s) {
                            case null -> "was_null";
                            case "hello" -> "hello_case";
                            default -> "other";
                        };
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("NullSwitchTest", code);
        Method m = clazz.getMethod("test", String.class);
        assertEquals("was_null",   m.invoke(null, (Object) null));
        assertEquals("hello_case", m.invoke(null, "hello"));
        assertEquals("other",      m.invoke(null, "world"));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Madde 6: Safe access (?.) primitive dönen metodlarda stack frame uyuşmazlığı
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Madde 6: Safe access on object returning non-primitive — no VerifyError")
    public void testSafeAccessOnObject() throws Exception {
        String code = """
                public class Box {
                    public String function getValue() {
                        return "boxed";
                    }
                }
                public class SafeAccessTest {
                    public static String? function test(Box? b) {
                        return b?.getValue();
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("SafeAccessTest", code);
        Class<?> boxCls = clazz.getClassLoader().loadClass("SafeAccessTest.Box");
        Object box = boxCls.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("test", boxCls);
        assertEquals("boxed", m.invoke(null, box));
        assertNull(m.invoke(null, (Object) null));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Madde 3: Record pattern matching with long/double — slot kayması
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Madde 3: Pattern matching with long-carrying record — no slot drift")
    public void testPatternMatchingLongSlot() throws Exception {
        String code = """
                public data class Measurement(long value, String unit) {}
                public class PatternLongTest {
                    public static String function test(Object obj) {
                        return switch (obj) {
                            case Measurement(long v, String u) -> u + ":" + v;
                            default -> "unknown";
                        };
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("PatternLongTest", code);
        Class<?> measureCls = clazz.getClassLoader().loadClass("PatternLongTest.Measurement");
        Object measure = measureCls.getDeclaredConstructor(long.class, String.class)
                .newInstance(42L, "kg");
        Method m = clazz.getMethod("test", Object.class);
        assertEquals("kg:42", m.invoke(null, measure));
        assertEquals("unknown", m.invoke(null, "not a measurement"));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Madde 10: lock bloğu içinde labeled stop — monitor exit dengesi
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Madde 10: lock block with inner loop and labeled stop — monitor exit balance")
    public void testLockWithLabeledStop() throws Exception {
        String code = """
                public class LockStopTest {
                    public static int function test() {
                        Object lock = new Object();
                        int count = 0;
                        lock (lock) {
                            outer: while (count < 10) {
                                count++;
                                if (count == 5) {
                                    stop outer;
                                }
                            }
                        }
                        return count;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("LockStopTest", code);
        Method m = clazz.getMethod("test");
        assertEquals(5, m.invoke(null));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Madde 7: Lambda capture with long and double — slot kayması
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Madde 7: Lambda capturing long and double values — no slot drift")
    public void testLambdaCaptureLongDouble() throws Exception {
        String code = """
                import java.util.function.Supplier;
                
                public class LambdaSlotTest {
                    public static double function test(long base, double factor) {
                        Supplier compute = () -> base * factor;
                        return compute.get();
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("LambdaSlotTest", code);
        Method m = clazz.getMethod("test", long.class, double.class);
        double result = (Double) m.invoke(null, 3L, 2.5);
        assertEquals(7.5, result, 0.001);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Madde 4: Try-with-resources with interface AutoCloseable
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Madde 4: Try-with-resources using interface AutoCloseable — no IncompatibleClassChangeError")
    public void testTryWithResourcesInterface() throws Exception {
        String code = """
                public interface Resource extends AutoCloseable {
                    public String function read();
                }
                public class FileResource implements Resource {
                    private boolean closed = false;
                    public String function read() { return "data"; }
                    public void function close() { closed = true; }
                    public boolean function isClosed() { return closed; }
                }
                public class TryResourceTest {
                    public static String function test(Resource r) {
                        trying (r) {
                            return r.read();
                        }
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TryResourceTest", code);
        Class<?> resourceCls = clazz.getClassLoader().loadClass("TryResourceTest.Resource");
        Class<?> fileCls = clazz.getClassLoader().loadClass("TryResourceTest.FileResource");
        Object file = fileCls.getDeclaredConstructor().newInstance();
        Method testMethod = clazz.getMethod("test", resourceCls);
        String result = (String) testMethod.invoke(null, file);
        assertEquals("data", result);
        Method isClosed = fileCls.getMethod("isClosed");
        assertTrue((Boolean) isClosed.invoke(file), "Resource should be closed after try-with-resources");
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Madde 8: Multi-dimensional array access — stack balance
    // ─────────────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("Madde 8: Multi-dimensional array access — no stack overflow/underflow")
    public void testMultiDimArrayAccess() throws Exception {
        String code = """
                public class MultiArrayTest {
                    public static int function test() {
                        int[][] matrix = new int[3][3];
                        matrix[1][2] = 42;
                        return matrix[1][2];
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("MultiArrayTest", code);
        Method m = clazz.getMethod("test");
        assertEquals(42, m.invoke(null));
    }

    @Test
    public void testConstructorMethodReferenceInSuperCall() throws Exception {
        String code = """
                package verifyerrordenemesi;
                import java.util.function.Function;
                public class TempVerifyErrorDenemesi {
                    static class Super {
                        public Function creator;
                        Super(Function creator) {
                            this.creator = creator;
                        }
                    }
                    class Inner extends Super {
                        public Object? val;
                        Inner(Object? something) {
                            super(Inner::new);
                            this.val = something;
                        }
                    }
                    public static void function runTest() {
                        Inner i = new Inner(null);
                        Object created = i.creator.apply("test-val");
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TempVerifyErrorDenemesi", code);
        Method m = clazz.getMethod("runTest");
        m.invoke(null);
    }
}
