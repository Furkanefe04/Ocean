package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CompoundBitwiseShiftAssignTest extends CompilerTestHelper {

    // ==========================================
    // 1. Integer Bitwise & Shift Compound Ops
    // ==========================================

    @Test
    @DisplayName("int &= bitwise AND assignment")
    public void testIntAndAssign() throws Exception {
        String code = """
                public class TestIntAnd {
                    public static int function test() {
                        int x = 15;
                        x &= 6;
                        return x;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("TestIntAnd", code);
        Object r = classes.get("TestIntAnd").getMethod("test").invoke(null);
        assertEquals(6, r);
    }

    @Test
    @DisplayName("int |= bitwise OR assignment")
    public void testIntOrAssign() throws Exception {
        String code = """
                public class TestIntOr {
                    public static int function test() {
                        int x = 5;
                        x |= 3;
                        return x;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("TestIntOr", code);
        Object r = classes.get("TestIntOr").getMethod("test").invoke(null);
        assertEquals(7, r);
    }

    @Test
    @DisplayName("int ^= bitwise XOR assignment")
    public void testIntXorAssign() throws Exception {
        String code = """
                public class TestIntXor {
                    public static int function test() {
                        int x = 15;
                        x ^= 6;
                        return x;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("TestIntXor", code);
        Object r = classes.get("TestIntXor").getMethod("test").invoke(null);
        assertEquals(9, r);
    }

    @Test
    @DisplayName("int <<= left shift assignment")
    public void testIntLShiftAssign() throws Exception {
        String code = """
                public class TestIntLsh {
                    public static int function test() {
                        int x = 1;
                        x <<= 3;
                        return x;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("TestIntLsh", code);
        Object r = classes.get("TestIntLsh").getMethod("test").invoke(null);
        assertEquals(8, r);
    }

    @Test
    @DisplayName("int >>= signed right shift assignment")
    public void testIntRShiftAssign() throws Exception {
        String code = """
                public class TestIntRsh {
                    public static int function test() {
                        int x = 16;
                        x >>= 2;
                        return x;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("TestIntRsh", code);
        Object r = classes.get("TestIntRsh").getMethod("test").invoke(null);
        assertEquals(4, r);
    }

    @Test
    @DisplayName("int >>>= unsigned right shift assignment")
    public void testIntURShiftAssign() throws Exception {
        String code = """
                public class TestIntURsh {
                    public static int function test() {
                        int x = -8;
                        x >>>= 1;
                        return x;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("TestIntURsh", code);
        Object r = classes.get("TestIntURsh").getMethod("test").invoke(null);
        assertEquals(2147483644, r);
    }

    // ==========================================
    // 2. Boolean Bitwise Compound Ops
    // ==========================================

    @Test
    @DisplayName("boolean &= logical AND assignment")
    public void testBoolAndAssign() throws Exception {
        String code = """
                public class TestBoolAnd {
                    public static boolean function test() {
                        boolean x = true;
                        x &= false;
                        return x;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("TestBoolAnd", code);
        Object r = classes.get("TestBoolAnd").getMethod("test").invoke(null);
        assertEquals(false, r);
    }

    @Test
    @DisplayName("boolean |= logical OR assignment")
    public void testBoolOrAssign() throws Exception {
        String code = """
                public class TestBoolOr {
                    public static boolean function test() {
                        boolean x = false;
                        x |= true;
                        return x;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("TestBoolOr", code);
        Object r = classes.get("TestBoolOr").getMethod("test").invoke(null);
        assertEquals(true, r);
    }

    @Test
    @DisplayName("boolean ^= logical XOR assignment")
    public void testBoolXorAssign() throws Exception {
        String code = """
                public class TestBoolXor {
                    public static boolean function test() {
                        boolean x = true;
                        x ^= true;
                        return x;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("TestBoolXor", code);
        Object r = classes.get("TestBoolXor").getMethod("test").invoke(null);
        assertEquals(false, r);
    }

    // ==========================================
    // 3. Long Bitwise & Shift Compound Ops
    // ==========================================

    @Test
    @DisplayName("long &= and |= assignments")
    public void testLongCompoundOps() throws Exception {
        String code = """
                public class TestLongOps {
                    public static long function testAnd() {
                        long x = 255L;
                        x &= 15L;
                        return x;
                    }
                    public static long function testOr() {
                        long x = 0L;
                        x |= 7L;
                        return x;
                    }
                    public static long function testLsh() {
                        long x = 1L;
                        x <<= 32;
                        return x;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("TestLongOps", code);
        Class<?> clazz = classes.get("TestLongOps");
        assertEquals(15L, clazz.getMethod("testAnd").invoke(null));
        assertEquals(7L, clazz.getMethod("testOr").invoke(null));
        assertEquals(4294967296L, clazz.getMethod("testLsh").invoke(null));
    }

    // ==========================================
    // 4. Field Compound Assignment
    // ==========================================

    @Test
    @DisplayName("Field bitwise compound assignment")
    public void testFieldCompoundAssign() throws Exception {
        String code = """
                public class TestFieldAssign {
                    public int mask = 15;
                    public void function applyMask() {
                        this.mask &= 6;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("TestFieldAssign", code);
        Class<?> clazz = classes.get("TestFieldAssign");
        Object inst = clazz.getDeclaredConstructor().newInstance();
        clazz.getMethod("applyMask").invoke(inst);
        assertEquals(6, clazz.getField("mask").getInt(inst));
    }

    // ==========================================
    // 5. Identity Simplification (DCE / ConstantFolder)
    // ==========================================

    @Test
    @DisplayName("Identity operations with zero do not alter value")
    public void testIdentityBitwiseAssign() throws Exception {
        String code = """
                public class TestIdentity {
                    public static int function testOrZero() {
                        int x = 42;
                        x |= 0;
                        return x;
                    }
                    public static int function testXorZero() {
                        int x = 42;
                        x ^= 0;
                        return x;
                    }
                    public static int function testShiftZero() {
                        int x = 42;
                        x <<= 0;
                        x >>= 0;
                        x >>>= 0;
                        return x;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("TestIdentity", code);
        Class<?> clazz = classes.get("TestIdentity");
        assertEquals(42, clazz.getMethod("testOrZero").invoke(null));
        assertEquals(42, clazz.getMethod("testXorZero").invoke(null));
        assertEquals(42, clazz.getMethod("testShiftZero").invoke(null));
    }

    // ==========================================
    // 6. Negative Type Safety Tests (Must Fail)
    // ==========================================

    @Test
    @DisplayName("String &= String fails compilation")
    public void testStringAndFails() {
        String code = """
                public class FailStringAnd {
                    public static void function test() {
                        String s = "hello";
                        s &= "world";
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compileAndLoadAll("FailStringAnd", code));
    }

    @Test
    @DisplayName("double &= double fails compilation")
    public void testDoubleAndFails() {
        String code = """
                public class FailDoubleAnd {
                    public static void function test() {
                        double d = 3.14;
                        d &= 1.0;
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compileAndLoadAll("FailDoubleAnd", code));
    }

    @Test
    @DisplayName("int &= boolean fails compilation")
    public void testIntBoolAndFails() {
        String code = """
                public class FailIntBoolAnd {
                    public static void function test() {
                        int x = 5;
                        x &= true;
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compileAndLoadAll("FailIntBoolAnd", code));
    }

    @Test
    @DisplayName("int <<= float fails compilation")
    public void testShiftFloatFails() {
        String code = """
                public class FailShiftFloat {
                    public static void function test() {
                        int x = 1;
                        x <<= 1.5;
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compileAndLoadAll("FailShiftFloat", code));
    }

    @Test
    @DisplayName("String <<= int fails compilation")
    public void testStringShiftFails() {
        String code = """
                public class FailStringShift {
                    public static void function test() {
                        String s = "abc";
                        s <<= 2;
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compileAndLoadAll("FailStringShift", code));
    }
}
