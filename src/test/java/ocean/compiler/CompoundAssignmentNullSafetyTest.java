package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompoundAssignmentNullSafetyTest extends CompilerTestHelper {

    @Test
    @DisplayName("Instance field compound assignment where field is null")
    void testFieldInitiallyNull() throws Exception {
        Class<?> clazz = compileAndLoad("FieldNullConcat", """
                public class FieldNullConcat {
                    public String? text = null;

                    public void function append() {
                        this.text += "hello";
                    }
                }
                """);

        Object instance = clazz.getDeclaredConstructor().newInstance();
        Method append = clazz.getMethod("append");
        append.invoke(instance);

        Field text = clazz.getField("text");
        assertEquals("nullhello", text.get(instance));
    }

    @Test
    @DisplayName("Instance field compound assignment appending null")
    void testFieldAppendNull() throws Exception {
        Class<?> clazz = compileAndLoad("FieldAppendNull", """
                public class FieldAppendNull {
                    public String? text = "hello";

                    public void function append(String? other) {
                        this.text += other;
                    }
                }
                """);

        Object instance = clazz.getDeclaredConstructor().newInstance();
        Method append = clazz.getMethod("append", String.class);
        append.invoke(instance, new Object[]{null});

        Field text = clazz.getField("text");
        assertEquals("hellonull", text.get(instance));
    }

    @Test
    @DisplayName("Instance field compound assignment appending primitive to null field")
    void testFieldAppendPrimitive() throws Exception {
        Class<?> clazz = compileAndLoad("FieldAppendPrimitive", """
                public class FieldAppendPrimitive {
                    public String? text = null;

                    public void function append() {
                        this.text += 42;
                    }
                }
                """);

        Object instance = clazz.getDeclaredConstructor().newInstance();
        Method append = clazz.getMethod("append");
        append.invoke(instance);

        Field text = clazz.getField("text");
        assertEquals("null42", text.get(instance));
    }

    @Test
    @DisplayName("Array element compound assignment where element is null")
    void testArrayInitiallyNull() throws Exception {
        Class<?> clazz = compileAndLoad("ArrayNullConcat", """
                public class ArrayNullConcat {
                    public static String function testArray() {
                        String[] arr = new String[2];
                        arr[0] += "world";
                        return arr[0];
                    }
                }
                """);

        Method testArray = clazz.getMethod("testArray");
        assertEquals("nullworld", testArray.invoke(null));
    }

    @Test
    @DisplayName("Array element compound assignment appending null")
    void testArrayAppendNull() throws Exception {
        Class<?> clazz = compileAndLoad("ArrayAppendNull", """
                public class ArrayAppendNull {
                    public static String function testArray() {
                        String[] arr = new String[1];
                        arr[0] = "hi";
                        String? n = null;
                        arr[0] += n;
                        return arr[0];
                    }
                }
                """);

        Method testArray = clazz.getMethod("testArray");
        assertEquals("hinull", testArray.invoke(null));
    }

    @Test
    @DisplayName("Array element compound assignment appending primitive to null element")
    void testArrayAppendPrimitive() throws Exception {
        Class<?> clazz = compileAndLoad("ArrayAppendPrimitive", """
                public class ArrayAppendPrimitive {
                    public static String function testArray() {
                        String[] arr = new String[2];
                        arr[1] += 99;
                        return arr[1];
                    }
                }
                """);

        Method testArray = clazz.getMethod("testArray");
        assertEquals("null99", testArray.invoke(null));
    }

    @Test
    @DisplayName("Instance field compound assignment used as expression value")
    void testFieldExprValue() throws Exception {
        Class<?> clazz = compileAndLoad("FieldExprValue", """
                public class FieldExprValue {
                    public String? text = null;

                    public String? function test() {
                        variable res = (this.text += "world");
                        return res;
                    }
                }
                """);

        Object instance = clazz.getDeclaredConstructor().newInstance();
        Method test = clazz.getMethod("test");
        Object result = test.invoke(instance);

        assertEquals("nullworld", result);
        Field text = clazz.getField("text");
        assertEquals("nullworld", text.get(instance));
    }
}