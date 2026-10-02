package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MediumPriorityThreeIssuesTest extends CompilerTestHelper {

    // =========================================================================
    // OC-29: Java generic varargs interop
    // =========================================================================

    @Test
    @DisplayName("OC-29: Java generic varargs interop: calling getMethod with 0 varargs arguments succeeds")
    void testJavaGenericVarargsZeroArguments() throws Exception {
        String code = """
                package com.test.oc29;
                
                import java.lang.reflect.Method;
                
                public class VarargsInteropTarget {
                    public static void function all() {}
                    public static void function single(String s) {}
                    
                    public static String function runTest() {
                        trying {
                            VarargsInteropTarget target = new VarargsInteropTarget();
                            variable c = target.getClass();
                            Method m1 = c.getMethod("all");
                            return m1.getName();
                        } catch (Exception e) {
                            return "error: " + e.getMessage();
                        }
                    }
                }
                """;
        Class<?> cls = compileAndLoad("VarargsInteropTarget", code);
        Method m = cls.getMethod("runTest");
        Object res = m.invoke(null);
        assertEquals("all", res);
    }

    @Test
    @DisplayName("OC-29: Java generic varargs interop: calling getMethod with non-zero arguments succeeds")
    void testJavaGenericVarargsWithArguments() throws Exception {
        String code = """
                package com.test.oc29b;
                
                import java.lang.reflect.Method;
                
                public class VarargsInteropTarget2 {
                    public static void function single(String s) {}
                    
                    public static String function runTest() {
                        trying {
                            VarargsInteropTarget2 target = new VarargsInteropTarget2();
                            variable c = target.getClass();
                            Method m = c.getMethod("single", String.class);
                            return m.getName();
                        } catch (Exception e) {
                            return "error: " + e.getMessage();
                        }
                    }
                }
                """;
        Class<?> cls = compileAndLoad("VarargsInteropTarget2", code);
        Method m = cls.getMethod("runTest");
        Object res = m.invoke(null);
        assertEquals("single", res);
    }

    // =========================================================================
    // OC-30: Bytecode verification gate using ASM CheckClassAdapter
    // =========================================================================

    @Test
    @DisplayName("OC-30: Bytecode verifier verifies valid compiled class successfully")
    void testBytecodeVerifierSuccess() throws Exception {
        String code = """
                package com.test.oc30;
                public class SimpleVerifierTarget {
                    public static int function add(int a, int b) {
                        return a + b;
                    }
                }
                """;
        Map<String, byte[]> results = compileToBytecodeMap(code, "SimpleVerifierTarget");
        assertFalse(results.isEmpty());
        byte[] bytes = results.get("com/test/oc30/SimpleVerifierTarget");
        assertNotNull(bytes);
        boolean verified = OceanRunnerV3.verifyBytecode("com.test.oc30.SimpleVerifierTarget", bytes, null);
        assertTrue(verified, "Valid class bytecode must pass CheckClassAdapter verification.");
    }

    @Test
    @DisplayName("OC-30: Bytecode verifier detects invalid bytecode")
    void testBytecodeVerifierFailure() {
        byte[] corrupted = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 50, 0, 0};
        boolean verified = OceanRunnerV3.verifyBytecode("CorruptedClass", corrupted, null);
        assertFalse(verified, "Corrupted bytecode must fail CheckClassAdapter verification.");
    }

    // =========================================================================
    // OC-32: Anonymous class enclosing local variable capture
    // =========================================================================

    @Test
    @DisplayName("OC-32: Anonymous class captures enclosing local variable and executes correctly")
    void testAnonymousClassLocalVariableCapture() throws Exception {
        String code = """
                package com.test.oc32;
                
                public interface Adder {
                    int function add(int x);
                }
                
                public class CaptureTestRunner {
                    public static int function runCapture(int initialVal) {
                        int base = 100;
                        Adder adder = new Adder() {
                            int function add(int x) {
                                return x + base;
                            }
                        };
                        return adder.add(initialVal);
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("CaptureTestRunner", code);
        Class<?> runner = classes.get("CaptureTestRunner");
        assertNotNull(runner);
        Method m = runner.getMethod("runCapture", int.class);
        int res = (int) m.invoke(null, 25);
        assertEquals(125, res, "Anonymous class must capture enclosing local variable 'base'.");
    }

    @Test
    @DisplayName("OC-32: Anonymous class captures multiple local variables of different types")
    void testAnonymousClassMultipleCaptures() throws Exception {
        String code = """
                package com.test.oc32b;
                
                public interface Formatter {
                    String function format(int val);
                }
                
                public class MultiCaptureRunner {
                    public static String function run(int num) {
                        String prefix = "Result: ";
                        int bonus = 50;
                        Formatter f = new Formatter() {
                            String function format(int val) {
                                return prefix + (val + bonus);
                            }
                        };
                        return f.format(num);
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("MultiCaptureRunner", code);
        Class<?> runner = classes.get("MultiCaptureRunner");
        assertNotNull(runner);
        Method m = runner.getMethod("run", int.class);
        Object res = m.invoke(null, 10);
        assertEquals("Result: 60", res);
    }
}
