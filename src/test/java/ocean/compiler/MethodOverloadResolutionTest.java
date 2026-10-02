package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class MethodOverloadResolutionTest extends CompilerTestHelper {

    @Test
    @DisplayName("Phase 1 widening primitive is chosen over Phase 2 autoboxing")
    public void testStrictWideningOverBoxing() throws Exception {
        String code = """
            public class TestWideningOverBoxing {
                public static String function foo(long x) { return "long"; }
                public static String function foo(Integer x) { return "Integer"; }
                public static String function run() {
                    int x = 42;
                    return foo(x);
                }
            }
            """;
        Class<?> cls = compileAndLoad("TestWideningOverBoxing", code);
        Method m = cls.getMethod("run");
        assertEquals("long", m.invoke(null));
    }

    @Test
    @DisplayName("Exact primitive match is chosen over boxed wrapper")
    public void testPrimitiveOverBoxed() throws Exception {
        String code = """
            public class TestPrimitiveOverBoxed {
                public static String function foo(int x) { return "int"; }
                public static String function foo(Integer x) { return "Integer"; }
                public static String function run() {
                    int x = 10;
                    return foo(x);
                }
            }
            """;
        Class<?> cls = compileAndLoad("TestPrimitiveOverBoxed", code);
        Method m = cls.getMethod("run");
        assertEquals("int", m.invoke(null));
    }

    @Test
    @DisplayName("Most specific subtype is selected across reference hierarchy")
    public void testReferenceHierarchy() throws Exception {
        String code = """
            public class TestRefHierarchy {
                public static String function test(Object o) { return "Object"; }
                public static String function test(CharSequence cs) { return "CharSequence"; }
                public static String function test(String s) { return "String"; }
                public static String function run() {
                    return test("hello");
                }
            }
            """;
        Class<?> cls = compileAndLoad("TestRefHierarchy", code);
        Method m = cls.getMethod("run");
        assertEquals("String", m.invoke(null));
    }

    @Test
    @DisplayName("Ambiguous method call with overlapping signatures throws compilation error")
    public void testAmbiguousCallFails() {
        String code = """
            public class TestAmbigCall {
                public static String function ambig(String s, Object o) { return "1"; }
                public static String function ambig(Object o, String s) { return "2"; }
                public static String function run() {
                    return ambig("a", "b");
                }
            }
            """;
        assertThrows(CompilationException.class, () -> compileAndLoad("TestAmbigCall", code));
    }

    @Test
    @DisplayName("Normal array parameter int[] accepts array and is not varargs")
    public void testArrayParameterNotVarargs() throws Exception {
        String code = """
            public class TestArrayNotVarargs {
                public static int function sum(int[] arr) {
                    int total = 0;
                    for (int i in arr) {
                        total += i;
                    }
                    return total;
                }
                public static int function run() {
                    int[] arr = [10, 20, 30];
                    return sum(arr);
                }
            }
            """;
        Class<?> cls = compileAndLoad("TestArrayNotVarargs", code);
        Method m = cls.getMethod("run");
        assertEquals(60, m.invoke(null));
    }

    @Test
    @DisplayName("True varargs method accepts variable args and exact overload takes precedence")
    public void testTrueVarargsOverload() throws Exception {
        String code = """
            public class TestTrueVarargs {
                public static String function bar(int... x) { return "varargs:" + x.length; }
                public static String function bar(int a, int b) { return "exact:2"; }
                public static String function run() {
                    return bar(1, 2) + "|" + bar(1, 2, 3) + "|" + bar();
                }
            }
            """;
        Class<?> cls = compileAndLoad("TestTrueVarargs", code);
        Method m = cls.getMethod("run");
        assertEquals("exact:2|varargs:3|varargs:0", m.invoke(null));
    }

    @Test
    @DisplayName("Null literal argument selects most specific reference overload")
    public void testNullLiteralSelection() throws Exception {
        String code = """
            public class TestNullLiteralSelection {
                public static String function select(Object? o) { return "Object"; }
                public static String function select(String? s) { return "String"; }
                public static String function run() {
                    return select(null);
                }
            }
            """;
        Class<?> cls = compileAndLoad("TestNullLiteralSelection", code);
        Method m = cls.getMethod("run");
        assertEquals("String", m.invoke(null));
    }

    @Test
    @DisplayName("Null literal with unrelated reference overloads causes ambiguity compilation error")
    public void testNullLiteralAmbiguity() {
        String code = """
            public class TestNullLiteralAmbiguity {
                public static String function ambigNull(String s, Integer i) { return "1"; }
                public static String function ambigNull(Integer i, String s) { return "2"; }
                public static String function run() {
                    return ambigNull(null, null);
                }
            }
            """;
        assertThrows(CompilationException.class, () -> compileAndLoad("TestNullLiteralAmbiguity", code));
    }
}