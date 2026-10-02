package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JlsConversionsAndNamesTest extends CompilerTestHelper {

    @BeforeEach
    void setup() {
        CompilerRegistry.clearAll();
        CompilerReporter.clear();
        ClassMetadataCache.clearCaches();
    }

    @Test
    @DisplayName("Unbounded wildcard receiver (List<?>) rejects non-null mutation but accepts null")
    void testUnboundedWildcardCapture() throws Exception {
        // Non-null write must fail
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileAndLoad("UnboundedFail", """
                    import java.util.List;
                    import java.util.ArrayList;

                    public class UnboundedFail {
                        public static void function test() {
                            List<?> list = new ArrayList<String>();
                            list.add("hello");
                        }
                    }
                    """);
        });
        assertTrue(ex.getMessage().contains("Cannot pass argument other than 'null'"),
                "Expected capture conversion error message, got: " + ex.getMessage());

        // Null write must succeed
        Class<?> okClass = compileAndLoad("UnboundedNullOk", """
                import java.util.List;
                import java.util.ArrayList;

                public class UnboundedNullOk {
                    public static int function test() {
                        List<?> list = new ArrayList<String>();
                        list.add(null);
                        return list.size();
                    }
                }
                """);
        assertEquals(1, okClass.getMethod("test").invoke(null));
    }

    @Test
    @DisplayName("Upper bounded wildcard receiver (List<? extends Number>) rejects non-null mutation but accepts null")
    void testUpperBoundWildcardCapture() throws Exception {
        // Non-null write must fail
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileAndLoad("UpperFail", """
                    import java.util.List;
                    import java.util.ArrayList;

                    public class UpperFail {
                        public static void function test() {
                            List<? extends Number> list = new ArrayList<Integer>();
                            list.add(10);
                        }
                    }
                    """);
        });
        assertTrue(ex.getMessage().contains("Cannot pass argument other than 'null'"),
                "Expected capture conversion error message, got: " + ex.getMessage());

        // Null write must succeed
        Class<?> okClass = compileAndLoad("UpperNullOk", """
                import java.util.List;
                import java.util.ArrayList;

                public class UpperNullOk {
                    public static int function test() {
                        List<? extends Number> list = new ArrayList<Integer>();
                        list.add(null);
                        return list.size();
                    }
                }
                """);
        assertEquals(1, okClass.getMethod("test").invoke(null));
    }

    @Test
    @DisplayName("Lower bounded wildcard receiver (List<? super Number>) accepts subtypes and null, rejects incompatible types")
    void testLowerBoundWildcardCapture() throws Exception {
        // Legal writes: subtypes of Number and null
        Class<?> okClass = compileAndLoad("LowerOk", """
                import java.util.List;
                import java.util.ArrayList;

                public class LowerOk {
                    public static int function test() {
                        List<? super Number> list = new ArrayList<Object>();
                        list.add(10);
                        list.add(3.14);
                        list.add(null);
                        return list.size();
                    }
                }
                """);
        assertEquals(3, okClass.getMethod("test").invoke(null));

        // Illegal write: String is not a subtype of Number
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileAndLoad("LowerFail", """
                    import java.util.List;
                    import java.util.ArrayList;

                    public class LowerFail {
                        public static void function test() {
                            List<? super Number> list = new ArrayList<Object>();
                            list.add("incompatible string");
                        }
                    }
                    """);
        });
        assertTrue(ex.getMessage().contains("Uyumsuz") || ex.getMessage().contains("lower bound"),
                "Expected incompatibility error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("User-defined generic class wildcard receiver capture conversion")
    void testUserDefinedGenericCapture() {
        assertThrows(CompilationException.class, () -> {
            compileAndLoad("UserGenericFail", """
                    class Box<T> {
                        public void function put(T item) {}
                    }

                    public class UserGenericFail {
                        public static void function test() {
                            Box<?> b = new Box<String>();
                            b.put("hello");
                        }
                    }
                    """);
        });
    }

    @Test
    @DisplayName("Wildcard return assignment to Object?")
    void testWildcardReturnAssignment() throws Exception {
        Class<?> okClass = compileAndLoad("WildcardReturnOk", """
                import java.util.List;
                import java.util.ArrayList;

                public class WildcardReturnOk {
                    public static int function test() {
                        List<String> src = new ArrayList<String>();
                        src.add("test_value");
                        List<?> list = src;
                        Object? val = list.get(0);
                        return list.size();
                    }
                }
                """);
        assertEquals(1, okClass.getMethod("test").invoke(null));
    }

    @Test
    @DisplayName("§6.5: Obscuring - local variable shadows class of same name")
    void testObscuringVariableShadowsType() throws Exception {
        Class<?> clazz = compileAndLoad("ObscureTest", """
                class Point {
                    public int x = 42;
                }

                public class ObscureTest {
                    public static int function test() {
                        Point Point = new Point();
                        return Point.x;
                    }
                }
                """);
        assertEquals(42, clazz.getMethod("test").invoke(null));
    }
}
