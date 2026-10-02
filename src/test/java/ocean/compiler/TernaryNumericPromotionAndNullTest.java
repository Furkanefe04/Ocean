package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TernaryNumericPromotionAndNullTest extends CompilerTestHelper {

    // ==================== POSITIVE RUNTIME TESTS ====================

    @Test
    @DisplayName("Ternary with int and double branches promotes to double and runs correctly")
    public void testIntAndDoublePromotion() throws Exception {
        String code = """
                public class TIntDouble {
                    public static double function choose(boolean cond, int a, double b) {
                        return cond ? a : b;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TIntDouble", code);
        Method m = clazz.getMethod("choose", boolean.class, int.class, double.class);
        assertEquals(10.0, (double) m.invoke(null, true, 10, 20.5), 0.001);
        assertEquals(20.5, (double) m.invoke(null, false, 10, 20.5), 0.001);
    }

    @Test
    @DisplayName("Ternary with int and long branches promotes to long and runs correctly")
    public void testIntAndLongPromotion() throws Exception {
        String code = """
                public class TIntLong {
                    public static long function choose(boolean cond, int a, long b) {
                        return cond ? a : b;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TIntLong", code);
        Method m = clazz.getMethod("choose", boolean.class, int.class, long.class);
        assertEquals(42L, m.invoke(null, true, 42, 100L));
        assertEquals(100L, m.invoke(null, false, 42, 100L));
    }

    @Test
    @DisplayName("Ternary with boxed Integer and primitive int unboxes to int")
    public void testBoxedIntegerAndPrimitiveInt() throws Exception {
        String code = """
                public class TBoxedInt {
                    public static int function choose(boolean cond, Integer a, int b) {
                        return cond ? a : b;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TBoxedInt", code);
        Method m = clazz.getMethod("choose", boolean.class, Integer.class, int.class);
        assertEquals(15, m.invoke(null, true, 15, 30));
        assertEquals(30, m.invoke(null, false, 15, 30));
    }

    @Test
    @DisplayName("Ternary with primitive int and boxed Integer unboxes to int")
    public void testPrimitiveIntAndBoxedInteger() throws Exception {
        String code = """
                public class TPrimBoxedInt {
                    public static int function choose(boolean cond, int a, Integer b) {
                        return cond ? a : b;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TPrimBoxedInt", code);
        Method m = clazz.getMethod("choose", boolean.class, int.class, Integer.class);
        assertEquals(50, m.invoke(null, true, 50, 99));
        assertEquals(99, m.invoke(null, false, 50, 99));
    }

    @Test
    @DisplayName("Ternary with boxed Integer and primitive double unboxes and widens to double")
    public void testBoxedIntegerAndPrimitiveDouble() throws Exception {
        String code = """
                public class TBoxedIntDouble {
                    public static double function choose(boolean cond, Integer a, double b) {
                        return cond ? a : b;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TBoxedIntDouble", code);
        Method m = clazz.getMethod("choose", boolean.class, Integer.class, double.class);
        assertEquals(10.0, (double) m.invoke(null, true, 10, 25.5), 0.001);
        assertEquals(25.5, (double) m.invoke(null, false, 10, 25.5), 0.001);
    }

    @Test
    @DisplayName("Ternary with sibling classes unifies to common superclass")
    public void testCommonSuperclassPolymorphism() throws Exception {
        String code = """
                public class Shape {
                    public int function sides() { return 0; }
                }
                public class Triangle extends Shape {
                    public int function sides() { return 3; }
                }
                public class Square extends Shape {
                    public int function sides() { return 4; }
                }
                public class TestShape {
                    public static Shape function getShape(boolean triangle) {
                        return triangle ? new Triangle() : new Square();
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("TestShape", code);
        Class<?> testShapeClass = classes.get("TestShape");
        Method getShape = testShapeClass.getMethod("getShape", boolean.class);
        Object s1 = getShape.invoke(null, true);
        Object s2 = getShape.invoke(null, false);
        Method sides1 = s1.getClass().getMethod("sides");
        Method sides2 = s2.getClass().getMethod("sides");
        assertEquals(3, sides1.invoke(s1));
        assertEquals(4, sides2.invoke(s2));
    }

    @Test
    @DisplayName("Null coalescing unwraps nullable type to non-null")
    public void testNullCoalescingUnwrapsNullability() throws Exception {
        String code = """
                public class TestCoalesce {
                    public static String function getVal(String? s) {
                        String res = s ?? "default";
                        return res;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestCoalesce", code);
        Method m = clazz.getMethod("getVal", String.class);
        assertEquals("hello", m.invoke(null, "hello"));
        assertEquals("default", m.invoke(null, (String) null));
    }

    // ==================== NULL + PRİMİTİVE TERNARY TESTS ====================
    // NOT: Yeni semantikte null + primitive → box+? (nullable boxing) ile tip belirleniyor.
    // variable? x = cond ? null : 42  →  Integer? (doğru çıkarım, hata vermez)

    @Test
    @DisplayName("Ternary with null true-branch and primitive int false-branch resolves to Integer?")
    public void testNullAndPrimitiveIntFails() throws Exception {
        String code = """
                public class TNullPrim {
                    public static Integer? function test(boolean cond) {
                        variable? x = cond ? null : 42;
                        return x;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TNullPrim", code);
        Method m = clazz.getMethod("test", boolean.class);
        assertNull(m.invoke(null, true));
        assertEquals(42, m.invoke(null, false));
    }

    @Test
    @DisplayName("Ternary with primitive int true-branch and null false-branch resolves to Integer?")
    public void testPrimitiveIntAndNullFails() throws Exception {
        String code = """
                public class TPrimNull {
                    public static Integer? function test(boolean cond) {
                        variable? x = cond ? 42 : null;
                        return x;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TPrimNull", code);
        Method m = clazz.getMethod("test", boolean.class);
        assertEquals(42, m.invoke(null, true));
        assertNull(m.invoke(null, false));
    }

    @Test
    @DisplayName("Ternary with null and boolean primitive resolves to Boolean?")
    public void testNullAndPrimitiveBooleanFails() throws Exception {
        String code = """
                public class TNullBool {
                    public static Boolean? function test(boolean cond) {
                        variable? x = cond ? null : true;
                        return x;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TNullBool", code);
        Method m = clazz.getMethod("test", boolean.class);
        assertNull(m.invoke(null, true));
        assertEquals(Boolean.TRUE, m.invoke(null, false));
    }
}
