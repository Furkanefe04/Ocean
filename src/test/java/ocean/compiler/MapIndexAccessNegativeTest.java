package ocean.compiler;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

public class MapIndexAccessNegativeTest {

    @Test
    public void testOceanMapBracketGetFailsSemantic() {
        String code = """
            import ocean.stdlib.OceanMap;

            public class MapBracketGetTest {
                public static int function run() {
                    OceanMap<String, int> map = new OceanMap();
                    map.put("a", 10);
                    return map["a"];
                }
            }
            """;

        CompilationException ex = assertThrows(CompilationException.class, () -> {
            CompilerTestHelper.compileAndLoad("MapBracketGetTest", code);
        });
        assertTrue(ex.getMessage().contains("Index access '[]' is not supported on Map"),
                   "Expected map bracket index access error message but got: " + ex.getMessage());
    }

    @Test
    public void testOceanMapBracketSetFailsSemantic() {
        String code = """
            import ocean.stdlib.OceanMap;

            public class MapBracketSetTest {
                public static void function run() {
                    OceanMap<String, int> map = new OceanMap();
                    map["a"] = 20;
                }
            }
            """;

        CompilationException ex = assertThrows(CompilationException.class, () -> {
            CompilerTestHelper.compileAndLoad("MapBracketSetTest", code);
        });
        assertTrue(ex.getMessage().contains("Index access '[]' is not supported on Map"),
                   "Expected map bracket index access error message but got: " + ex.getMessage());
    }

    @Test
    public void testJavaMapBracketAccessFailsSemantic() {
        String code = """
            import java.util.Map;
            import java.util.HashMap;

            public class JavaMapBracketTest {
                public static String function run() {
                    Map<String, String> map = new HashMap();
                    map.put("k", "v");
                    return map["k"];
                }
            }
            """;

        CompilationException ex = assertThrows(CompilationException.class, () -> {
            CompilerTestHelper.compileAndLoad("JavaMapBracketTest", code);
        });
        assertTrue(ex.getMessage().contains("Index access '[]' is not supported on Map"),
                   "Expected map bracket index access error message but got: " + ex.getMessage());
    }

    @Test
    public void testOceanListBracketAccessSucceeds() throws Exception {
        String code = """
            import ocean.stdlib.OceanList;

            public class ListBracketSuccessTest {
                public static String function run() {
                    OceanList<String> list = new OceanList();
                    list.add("First");
                    list.add("Second");
                    list[0] = "UpdatedFirst";
                    return list[0];
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("ListBracketSuccessTest", code);
        Method m = clazz.getMethod("run");
        m.setAccessible(true);
        assertEquals("UpdatedFirst", m.invoke(null));
    }

    @Test
    public void testOceanMapGetAndPutSucceeds() throws Exception {
        String code = """
            import ocean.stdlib.OceanMap;

            public class MapGetPutSuccessTest {
                public static int function run() {
                    OceanMap<String, int> map = new OceanMap();
                    map.put("score", 100);
                    return map.get("score");
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("MapGetPutSuccessTest", code);
        Method m = clazz.getMethod("run");
        m.setAccessible(true);
        assertEquals(100, m.invoke(null));
    }
}