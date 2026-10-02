package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JlsTypesAndVariablesTest extends CompilerTestHelper {

    @BeforeEach
    void setup() {
        CompilerRegistry.clearAll();
        CompilerReporter.clear();
        ClassMetadataCache.clearCaches();
    }

    @Test
    @DisplayName("§4.10.5: Unbounded and bounded wildcards (?, ? extends T, ? super T)")
    void testWildcards() throws Exception {
        Class<?> clazz = compileAndLoad("WildcardTest", """
                import java.util.List;
                import java.util.ArrayList;

                public class WildcardTest {
                    public static int function testUnbounded() {
                        List<?> list = new ArrayList<String>();
                        return list.size();
                    }

                    public static int function testUpperBounded() {
                        List<? extends Number> list = new ArrayList<Integer>();
                        return list.size();
                    }

                    public static int function testLowerBounded() {
                        List<? super Integer> list = new ArrayList<Number>();
                        return list.size();
                    }
                }
                """);

        assertEquals(0, clazz.getMethod("testUnbounded").invoke(null));
        assertEquals(0, clazz.getMethod("testUpperBounded").invoke(null));
        assertEquals(0, clazz.getMethod("testLowerBounded").invoke(null));
    }

    @Test
    @DisplayName("Generic invariance check (List<Number> != List<Integer>)")
    void testGenericInvariance() {
        assertThrows(CompilationException.class, () -> {
            compileAndLoad("InvarianceTest", """
                    import java.util.List;
                    import java.util.ArrayList;

                    public class InvarianceTest {
                        public static void function fail() {
                            List<Number> list = new ArrayList<Integer>();
                        }
                    }
                    """);
        });
    }

    @Test
    @DisplayName("§15.16: Intersection type cast expressions (Type & Interface)")
    void testIntersectionCast() throws Exception {
        Class<?> clazz = compileAndLoad("IntersectionCastTest", """
                public class IntersectionCastTest {
                    public static int function testIntersection() {
                        Object o = "hello world";
                        CharSequence s = (CharSequence & java.io.Serializable) o;
                        return s.length();
                    }
                }
                """);

        assertEquals(11, clazz.getMethod("testIntersection").invoke(null));
    }

    @Test
    @DisplayName("Array subtyping to Cloneable, Serializable, and Object")
    void testArraySubtyping() throws Exception {
        Class<?> clazz = compileAndLoad("ArraySubtypingTest", """
                public class ArraySubtypingTest {
                    public static boolean function testArraySubtypes() {
                        int[] arr = new int[3];
                        Cloneable c = arr;
                        java.io.Serializable s = arr;
                        Object o = arr;
                        return c != null && s != null && o != null;
                    }
                }
                """);

        assertEquals(true, clazz.getMethod("testArraySubtypes").invoke(null));
    }

    @Test
    @DisplayName("Default values for uninitialized fields")
    void testFieldDefaultValues() throws Exception {
        Class<?> clazz = compileAndLoad("DefaultFieldTest", """
                public class DefaultFieldTest {
                    public int i;
                    public double d;
                    public boolean b;
                    public char c;
                    public String? s;

                    public static DefaultFieldTest function create() {
                        return new DefaultFieldTest();
                    }
                }
                """);

        Object instance = clazz.getMethod("create").invoke(null);
        assertEquals(0, clazz.getField("i").getInt(instance));
        assertEquals(0.0, clazz.getField("d").getDouble(instance));
        assertEquals(false, clazz.getField("b").getBoolean(instance));
        assertEquals((char) 0, clazz.getField("c").getChar(instance));
        assertEquals(null, clazz.getField("s").get(instance));
    }
}
