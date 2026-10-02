package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ForLoopTypeSafetyTest extends CompilerTestHelper {

    @Test
    @DisplayName("Non-numeric range loop iterator fails compilation")
    public void testNonNumericRangeIteratorFails() {
        String code = """
                public class RangeStrIterTest {
                    public static void function test() {
                        for (String s from 0 to 10 with increasing 1) {
                            OceanOutput(s);
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("RangeStrIterTest", code));
        assertTrue(ex.getMessage().contains("numeric types") || ex.getMessage().contains("Aralık tabanlı"),
                "Expected numeric type error for range iterator, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Boolean range loop iterator fails compilation")
    public void testBooleanRangeIteratorFails() {
        String code = """
                public class RangeBoolIterTest {
                    public static void function test() {
                        for (bool b from 0 to 10 with increasing 1) {
                            OceanOutput("" + b);
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("RangeBoolIterTest", code));
        assertTrue(ex.getMessage().contains("numeric types") || ex.getMessage().contains("Aralık tabanlı"),
                "Expected numeric type error for boolean range iterator, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Non-numeric range 'from' bound fails compilation")
    public void testNonNumericFromBoundFails() {
        String code = """
                public class RangeFromTest {
                    public static void function test() {
                        for (int i from "start" to 10 with increasing 1) {
                            OceanOutput("" + i);
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("RangeFromTest", code));
        assertTrue(ex.getMessage().contains("from") || ex.getMessage().contains("numeric type"),
                "Expected error on non-numeric from bound, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Non-numeric range 'to' bound fails compilation")
    public void testNonNumericToBoundFails() {
        String code = """
                public class RangeToTest {
                    public static void function test() {
                        for (int i from 0 to "end" with increasing 1) {
                            OceanOutput("" + i);
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("RangeToTest", code));
        assertTrue(ex.getMessage().contains("to") || ex.getMessage().contains("numeric type"),
                "Expected error on non-numeric to bound, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Non-numeric range step fails compilation")
    public void testNonNumericStepFails() {
        String code = """
                public class RangeStepTest {
                    public static void function test() {
                        for (int i from 0 to 10 with increasing "step") {
                            OceanOutput("" + i);
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("RangeStepTest", code));
        assertTrue(ex.getMessage().contains("step") || ex.getMessage().contains("numeric type"),
                "Expected error on non-numeric step, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Boolean range step fails compilation")
    public void testBooleanStepFails() {
        String code = """
                public class RangeBoolStepTest {
                    public static void function test() {
                        for (int i from 0 to 10 with increasing false) {
                            OceanOutput("" + i);
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("RangeBoolStepTest", code));
        assertTrue(ex.getMessage().contains("step") || ex.getMessage().contains("numeric type"),
                "Expected error on boolean step, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("For-in loop with incompatible array element type fails compilation")
    public void testForInArrayTypeMismatchFails() {
        String code = """
                public class ForInArrayMismatchTest {
                    public static void function test() {
                        int[] arr = new int[5];
                        for (String s in arr) {
                            OceanOutput(s);
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ForInArrayMismatchTest", code));
        assertTrue(ex.getMessage().contains("Incompatible") || ex.getMessage().contains("dizi eleman tipi"),
                "Expected array element type mismatch error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("For-in loop with incompatible generic collection type fails compilation")
    public void testForInCollectionTypeMismatchFails() {
        String code = """
                import java.util.ArrayList;
                public class ForInCollectionMismatchTest {
                    public static void function test() {
                        ArrayList<String> list = new ArrayList<String>();
                        list.add("hello");
                        for (int i in list) {
                            OceanOutput("" + i);
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ForInCollectionMismatchTest", code));
        assertTrue(ex.getMessage().contains("Incompatible") || ex.getMessage().contains("koleksiyon eleman tipi") || ex.getMessage().contains("E0003"),
                "Expected collection element type mismatch error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid range loops and for-in loops compile and execute correctly")
    public void testValidForLoopsWork() throws Exception {
        String code = """
                import java.util.ArrayList;
                public class ValidForLoopTest {
                    public static int function compute() {
                        int sum = 0;
                        for (int i from 0 to 5 with increasing 1) {
                            sum += i;
                        }

                        for (int j from 10 to 5 with decreasing 2) {
                            sum += j;
                        }

                        int[] arr = new int[3];
                        arr[0] = 10;
                        arr[1] = 20;
                        arr[2] = 30;
                        for (int x in arr) {
                            sum += x;
                        }

                        ArrayList<String> words = new ArrayList<String>();
                        words.add("a");
                        words.add("bc");
                        words.add("def");
                        int totalLen = 0;
                        for (String w in words) {
                            totalLen += w.length();
                        }

                        return sum + totalLen; // sum = (0+1+2+3+4) + (10+8+6) + (10+20+30) = 10 + 24 + 60 = 94. totalLen = 1+2+3 = 6. 94 + 6 = 100.
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ValidForLoopTest", code);
        Class<?> clazz = classes.get("ValidForLoopTest");
        assertNotNull(clazz);
        Method m = clazz.getMethod("compute");
        Object res = m.invoke(null);
        assertEquals(100, res);
    }
}
