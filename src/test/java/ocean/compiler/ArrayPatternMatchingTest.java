package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ArrayPatternMatchingTest extends CompilerTestHelper {

    @Test
    @DisplayName("Switch pattern matching with array types (String[], int[], Object[])")
    public void testArrayPatternMatchingInSwitch() throws Exception {
        String code = """
                public class ArrayMatcher {
                    public static String function describe(Object obj) {
                        return switch (obj) {
                            case String[] arr -> "StringArray:" + arr.length;
                            case int[] arr -> "IntArray:" + arr.length;
                            case Object[] arr -> "ObjectArray:" + arr.length;
                            default -> "Other";
                        };
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ArrayMatcher", code);
        Class<?> clazz = classes.get("ArrayMatcher");
        assertNotNull(clazz);

        Method describeMethod = clazz.getMethod("describe", Object.class);

        String[] strArr = new String[] { "a", "b", "c" };
        assertEquals("StringArray:3", describeMethod.invoke(null, (Object) strArr));

        int[] intArr = new int[] { 1, 2 };
        assertEquals("IntArray:2", describeMethod.invoke(null, (Object) intArr));

        Object[] objArr = new Object[] { 1.5, true, "hello", 10 };
        assertEquals("ObjectArray:4", describeMethod.invoke(null, (Object) objArr));

        assertEquals("Other", describeMethod.invoke(null, "Just a string"));
    }

    @Test
    @DisplayName("Instanceof with array pattern matching")
    public void testArrayInstanceOfPattern() throws Exception {
        String code = """
                public class InstanceofArrayChecker {
                    public static int function checkStringArray(Object obj) {
                        if (obj instanceof String[] arr) {
                            return arr.length;
                        }
                        return -1;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("InstanceofArrayChecker", code);
        Class<?> clazz = classes.get("InstanceofArrayChecker");
        assertNotNull(clazz);

        Method m = clazz.getMethod("checkStringArray", Object.class);
        assertEquals(2, m.invoke(null, (Object) new String[] { "x", "y" }));
        assertEquals(-1, m.invoke(null, "Not an array"));
    }
}
