package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ArrayAssignabilityTest extends CompilerTestHelper {

    @Test
    @DisplayName("Heterogeneous array initializer with incompatible element type fails compilation")
    public void testHeterogeneousArrayInitializerFails() {
        String code = """
                public class HeteroArrayTest {
                    public static void function test() {
                        int[] a = { 1, "hello" };
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("HeteroArrayTest", code));
        assertTrue(ex.getMessage().contains("Incompatible types"),
                "Expected incompatible types error for heterogeneous array, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Assigning Object[] to int[] fails compilation")
    public void testObjectArrayToPrimitiveArrayAssignmentFails() {
        String code = """
                public class ObjectToPrimArrayTest {
                    public static void function test(Object[] objs) {
                        int[] a = objs;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ObjectToPrimArrayTest", code));
        assertTrue(ex.getMessage().contains("Incompatible types"),
                "Expected incompatible types error for Object[] to int[], got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Assigning Integer[] to int[] fails compilation")
    public void testBoxedArrayToPrimitiveArrayAssignmentFails() {
        String code = """
                public class BoxedToPrimArrayTest {
                    public static void function test(Integer[] arr) {
                        int[] a = arr;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("BoxedToPrimArrayTest", code));
        assertTrue(ex.getMessage().contains("Incompatible types"),
                "Expected incompatible types error for Integer[] to int[], got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Assigning int[] to Integer[] fails compilation")
    public void testPrimitiveArrayToBoxedArrayAssignmentFails() {
        String code = """
                public class PrimToBoxedArrayTest {
                    public static void function test(int[] arr) {
                        Integer[] a = arr;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("PrimToBoxedArrayTest", code));
        assertTrue(ex.getMessage().contains("Incompatible types"),
                "Expected incompatible types error for int[] to Integer[], got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Homogeneous primitive array initializer compiles and executes correctly")
    public void testHomogeneousArrayInitializerSucceeds() throws Exception {
        String code = """
                public class HomoArrayTest {
                    public static int function sum() {
                        int[] a = { 10, 20, 30 };
                        int total = 0;
                        for (int x in a) {
                            total = total + x;
                        }
                        return total;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("HomoArrayTest", code);
        Class<?> clazz = classes.get("HomoArrayTest");
        assertNotNull(clazz);
        Method m = clazz.getMethod("sum");
        assertEquals(60, m.invoke(null));
    }

    @Test
    @DisplayName("Object[] with mixed element types compiles and executes correctly")
    public void testObjectArrayWithMixedLiteralsSucceeds() throws Exception {
        String code = """
                public class MixedObjectArrayTest {
                    public static int function count() {
                        Object[] a = { 1, "hello", true };
                        return a.length;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("MixedObjectArrayTest", code);
        Class<?> clazz = classes.get("MixedObjectArrayTest");
        assertNotNull(clazz);
        Method m = clazz.getMethod("count");
        assertEquals(3, m.invoke(null));
    }

    @Test
    @DisplayName("Assigning primitive array to Object variable compiles and executes correctly")
    public void testArrayAssignmentToObjectVariableSucceeds() throws Exception {
        String code = """
                public class PrimArrayToObjectTest {
                    public static bool function test() {
                        int[] a = new int[5];
                        Object o = a;
                        return o != null;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("PrimArrayToObjectTest", code);
        Class<?> clazz = classes.get("PrimArrayToObjectTest");
        assertNotNull(clazz);
        Method m = clazz.getMethod("test");
        assertEquals(true, m.invoke(null));
    }

    @Test
    @DisplayName("Covariant reference array assignment (String[] to Object[]) succeeds")
    public void testCovariantReferenceArrayAssignmentSucceeds() throws Exception {
        String code = """
                public class CovariantArrayTest {
                    public static int function getLength() {
                        String[] s = new String[4];
                        Object[] o = s;
                        return o.length;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("CovariantArrayTest", code);
        Class<?> clazz = classes.get("CovariantArrayTest");
        assertNotNull(clazz);
        Method m = clazz.getMethod("getLength");
        assertEquals(4, m.invoke(null));
    }
}
