package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SevenCriticalCompilerBugsEmpiricalTest {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    private Map<String, byte[]> compileToBytecodeMap(String code, String className) {
        return CompilerTestHelper.compileToBytecodeMap(code, className);
    }

    private String executeMainAndCaptureOutput(Map<String, byte[]> classes, String className) throws Exception {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                String pathKey = name.replace('.', '/');
                if (classes.containsKey(pathKey)) {
                    byte[] bytes = classes.get(pathKey);
                    return defineClass(name, bytes, 0, bytes.length);
                }
                for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                    if (entry.getKey().endsWith("/" + name) || entry.getKey().equals(name)) {
                        byte[] bytes = entry.getValue();
                        return defineClass(name, bytes, 0, bytes.length);
                    }
                }
                return super.findClass(name);
            }
        };

        String fqName = classes.containsKey(className + "/" + className) ? className + "." + className : className;
        Class<?> clazz = loader.loadClass(fqName);
        Method mainMethod = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals("main")) {
                mainMethod = m;
                break;
            }
        }
        if (mainMethod == null) {
            throw new RuntimeException("No main method found in " + clazz.getName());
        }
        mainMethod.setAccessible(true);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(baos));
        try {
            if (mainMethod.getParameterCount() == 1) {
                mainMethod.invoke(null, (Object) new String[0]);
            } else {
                mainMethod.invoke(null);
            }
        } finally {
            System.setOut(originalOut);
        }
        return baos.toString().trim();
    }

    // =========================================================================
    // TASK 1: Array Assignment (Compound vs Non-Compound)
    // =========================================================================

    @Test
    public void testTask1_ArrayAssignmentNonCompoundAndCompound_Positive() throws Exception {
        String code = """
            class ArrayAssignPositive {
                main() {
                    int[] a = new int[3];
                    a[0] = 10;
                    a[1] = 20;
                    int[] b = new int[3];
                    b[0] = 100;
                    b[1] = 200;

                    // Non-compound assignment from different array
                    a[0] = b[1] + 5; // Should be 205
                    // Compound assignment to same array
                    a[0] += 10;      // Should be 215

                    OceanOutput("RESULT=" + a[0]);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ArrayAssignPositive");
        String output = executeMainAndCaptureOutput(classes, "ArrayAssignPositive");
        assertEquals("RESULT=215", output);
    }

    @Test
    public void testTask1_ArrayAssignment_NegativeOldBugVerification() throws Exception {
        // In the old bug, a[0] = b[1] + 5 was miscompiled as compound assignment,
        // resulting in a[0] = a[0] + 5 = 15 instead of b[1] + 5 = 205.
        String code = """
            class ArrayAssignNegativeCheck {
                main() {
                    int[] a = new int[2];
                    a[0] = 10;
                    int[] b = new int[2];
                    b[1] = 200;

                    a[0] = b[1] + 5;
                    OceanOutput("a[0]=" + a[0]);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ArrayAssignNegativeCheck");
        String output = executeMainAndCaptureOutput(classes, "ArrayAssignNegativeCheck");
        assertNotEquals("a[0]=15", output, "LHS a[0] must NOT be read when RHS has different array b[1]");
        assertEquals("a[0]=205", output);
    }

    // =========================================================================
    // TASK 2: Field Assignment (Compound vs Non-Compound)
    // =========================================================================

    @Test
    public void testTask2_FieldAssignmentNonCompoundAndCompound_Positive() throws Exception {
        String code = """
            class FieldAssignPositive {
                static class Point {
                    int x;
                    int y;
                    Point(int x, int y) {
                        this.x = x;
                        this.y = y;
                    }
                }

                main() {
                    Point p1 = new Point(10, 20);
                    Point p2 = new Point(100, 200);

                    // Non-compound assignment with different receiver
                    p1.x = p2.x + 1; // 100 + 1 = 101
                    // Compound assignment with same receiver
                    p1.x += 10;      // 101 + 10 = 111

                    OceanOutput("RESULT=" + p1.x);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "FieldAssignPositive");
        String output = executeMainAndCaptureOutput(classes, "FieldAssignPositive");
        assertEquals("RESULT=111", output);
    }

    @Test
    public void testTask2_FieldAssignment_NegativeOldBugVerification() throws Exception {
        // In the old bug, p1.x = p2.x + 1 was miscompiled as compound assignment,
        // duplicating p1 instead of reading p2, resulting in p1.x = p1.x + 1 = 11 instead of 101.
        String code = """
            class FieldAssignNegativeCheck {
                static class Point {
                    int x;
                    Point(int x) { this.x = x; }
                }

                main() {
                    Point p1 = new Point(10);
                    Point p2 = new Point(100);

                    p1.x = p2.x + 1;
                    OceanOutput("p1.x=" + p1.x);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "FieldAssignNegativeCheck");
        String output = executeMainAndCaptureOutput(classes, "FieldAssignNegativeCheck");
        assertNotEquals("p1.x=11", output, "Receiver p1 must NOT be read when RHS specifies p2.x");
        assertEquals("p1.x=101", output);
    }

    // =========================================================================
    // TASK 3: Pattern Switch Multi-Value Matching
    // =========================================================================

    @Test
    public void testTask3_PatternSwitchMultiValueMatching_Positive() throws Exception {
        String code = """
            class PatternSwitchPositive {
                static int function testMatch(Object val) {
                    switch (val) {
                        case "alpha", "beta":
                            return 2;
                        case "gamma":
                            return 3;
                        default:
                            return 0;
                    }
                }

                main() {
                    int r1 = testMatch("alpha");
                    int r2 = testMatch("beta");
                    int r3 = testMatch("gamma");
                    int r4 = testMatch("unknown");
                    OceanOutput("r1=" + r1 + ", r2=" + r2 + ", r3=" + r3 + ", r4=" + r4);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "PatternSwitchPositive");
        String output = executeMainAndCaptureOutput(classes, "PatternSwitchPositive");
        assertEquals("r1=2, r2=2, r3=3, r4=0", output);
    }

    @Test
    public void testTask3_PatternSwitchMultiValueMatching_NegativeNoFallthrough() throws Exception {
        // In the old bug, matching the 2nd value "beta" did not jump to bodyLabel,
        // falling into the next case or default.
        String code = """
            class PatternSwitchNegativeCheck {
                main() {
                    Object val = "beta";
                    int result = -1;
                    switch (val) {
                        case "alpha", "beta":
                            result = 10;
                            stop;
                        default:
                            result = 99;
                            stop;
                    }
                    OceanOutput("result=" + result);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "PatternSwitchNegativeCheck");
        String output = executeMainAndCaptureOutput(classes, "PatternSwitchNegativeCheck");
        assertNotEquals("result=99", output, "Matching second case value must NOT fall through to default");
        assertEquals("result=10", output);
    }

    // =========================================================================
    // TASK 4: Switch Expression (Long / Double 2-Slot Local Variables)
    // =========================================================================

    @Test
    public void testTask4_SwitchExpressionLongAndDoubleTwoSlots_Positive() throws Exception {
        String code = """
            class SwitchExprSlotsPositive {
                static long function evalLong(int code) {
                    long res = switch (code) {
                        case 1 -> 10000000000L;
                        case 2 -> 20000000000L;
                        default -> 0L;
                    };
                    long extra = 5L;
                    return res + extra;
                }

                static double function evalDouble(int code) {
                    double res = switch (code) {
                        case 1 -> 123.456;
                        case 2 -> 789.012;
                        default -> 0.0;
                    };
                    double extra = 1.0;
                    return res + extra;
                }

                main() {
                    long lVal = evalLong(1);
                    double dVal = evalDouble(2);
                    OceanOutput("long=" + lVal + ", double=" + (int) dVal);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "SwitchExprSlotsPositive");
        String output = executeMainAndCaptureOutput(classes, "SwitchExprSlotsPositive");
        assertEquals("long=10000000005, double=790", output);
    }

    @Test
    public void testTask4_SwitchExpressionIncompatibleBranchTypes_Negative() {
        String code = """
            class SwitchExprTypeMismatchNegative {
                main() {
                    int x = 1;
                    long res = switch (x) {
                        case 1 -> "StringResult";
                        default -> 100L;
                    };
                }
            }
            """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchExprTypeMismatchNegative"));
    }

    // =========================================================================
    // TASK 5: Field Increment on Reference Number Types (BigDecimal, Integer)
    // =========================================================================

    @Test
    public void testTask5_FieldIncrementOnReferenceNumbers_Positive() throws Exception {
        String code = """
            import java.math.BigDecimal;

            class FieldIncPositive {
                static class Data {
                    BigDecimal balance;
                    Integer count;
                    Data(BigDecimal balance, Integer count) {
                        this.balance = balance;
                        this.count = count;
                    }
                }

                main() {
                    Data d = new Data(new BigDecimal("100.50"), 10);
                    d.balance++;
                    d.count++;
                    OceanOutput("balance=" + d.balance + ", count=" + d.count);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "FieldIncPositive");
        String output = executeMainAndCaptureOutput(classes, "FieldIncPositive");
        assertEquals("balance=101.50, count=11", output);
    }

    @Test
    public void testTask5_FieldIncrementOnNonNumberReference_Negative() {
        String code = """
            class FieldIncNonNumberNegative {
                static class Data {
                    String text;
                    Data(String text) { this.text = text; }
                }

                main() {
                    Data d = new Data("hello");
                    d.text++;
                }
            }
            """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "FieldIncNonNumberNegative"));
    }

    // =========================================================================
    // TASK 6: Foreach Loop on Custom Iterable
    // =========================================================================

    @Test
    public void testTask6_ForeachOnCustomIterable_Positive() throws Exception {
        String code = """
            import java.util.ArrayList;
            import java.lang.Iterable;

            class ForeachIterablePositive {
                static class CustomContainer implements Iterable {
                    ArrayList list;
                    CustomContainer() {
                        this.list = new ArrayList();
                    }
                    void function add(Object item) {
                        this.list.add(item);
                    }
                    java.util.Iterator function iterator() {
                        return this.list.iterator();
                    }
                }

                main() {
                    CustomContainer container = new CustomContainer();
                    container.add("Alpha");
                    container.add("Beta");
                    container.add("Gamma");

                    int count = 0;
                    for (item in container) {
                        count++;
                    }
                    OceanOutput("count=" + count);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ForeachIterablePositive");
        String output = executeMainAndCaptureOutput(classes, "ForeachIterablePositive");
        assertEquals("count=3", output);
    }

    @Test
    public void testTask6_ForeachOnNonIterableAndNonArray_Negative() {
        String code = """
            class ForeachNonIterableNegative {
                main() {
                    Object notAnIterable = "simple string";
                    for (item in notAnIterable) {
                        OceanOutput(item);
                    }
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileToBytecodeMap(code, "ForeachNonIterableNegative"));
        assertTrue(ex.getMessage().contains("Iterable") || ex.getMessage().contains("array"),
                "Expected Iterable or array requirement error, got: " + ex.getMessage());
    }

    // =========================================================================
    // TASK 7: Null-Coalescing (??) Operator
    // =========================================================================

    @Test
    public void testTask7_NullCoalescingPrimitiveAndNullable_Positive() throws Exception {
        String code = """
            class NullCoalescingPositive {
                main() {
                    // Primitive with ??
                    long x = 100L;
                    long y = x ?? 200L;

                    // Nullable object with ??
                    Long? nullVal = null;
                    Object z = nullVal ?? 300L;

                    Long? notNullVal = 400L;
                    Object w = notNullVal ?? 500L;

                    OceanOutput("y=" + y + ", z=" + z + ", w=" + w);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NullCoalescingPositive");
        String output = executeMainAndCaptureOutput(classes, "NullCoalescingPositive");
        assertEquals("y=100, z=300, w=400", output);
    }

    @Test
    public void testTask7_NullCoalescingPrimitiveEmitsWarning_Negative() {
        String code = """
            class NullCoalescingWarningCheck {
                main() {
                    long x = 100L;
                    long y = x ?? 200L;
                }
            }
            """;
        compileToBytecodeMap(code, "NullCoalescingWarningCheck");
        boolean hasWarning = CompilerReporter.getMessages().stream()
                .anyMatch(m -> m.level() == CompilerReporter.Level.WARNING && m.text().contains("is primitive type"));
        assertTrue(hasWarning, "Expected compiler warning when ?? is applied to primitive type");
    }

    @Test
    public void testTask7_NullCoalescingNonNullableNullAssignment_Negative() {
        String code = """
            class NullAssignmentNegative {
                main() {
                    Long nonNullVal = null; // Error: null cannot be assigned to non-nullable Long
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileToBytecodeMap(code, "NullAssignmentNegative"));
        assertTrue(ex.getMessage().contains("Incompatible types"),
                "Expected incompatible types error for assigning null to non-nullable type, got: " + ex.getMessage());
    }
}
