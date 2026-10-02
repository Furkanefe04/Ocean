package ocean.compiler;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

public class JavaStreamAndGenericSAMTest {

    @Test
    public void testStreamFilterAndCount() throws Exception {
        String code = """
            import java.util.List;
            import java.util.ArrayList;

            public class StreamFilterTest {
                public static long function runTest() {
                    List<String> list = new ArrayList<String>();
                    list.add("apple");
                    list.add("banana");
                    list.add("cherry");
                    list.add("blueberry");

                    return list.stream().filter((s) -> s.startsWith("b")).count();
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("StreamFilterTest", code);
        Method m = clazz.getMethod("runTest");
        m.setAccessible(true);
        assertEquals(2L, m.invoke(null));
    }

    @Test
    public void testStreamMapAndToList() throws Exception {
        String code = """
            import java.util.List;
            import java.util.ArrayList;

            public class StreamMapTest {
                public static String function runTest() {
                    List<String> list = new ArrayList<String>();
                    list.add("apple");
                    list.add("banana");

                    variable upper = list.stream().map((s) -> s.toUpperCase()).toList();
                    return upper.get(1);
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("StreamMapTest", code);
        Method m = clazz.getMethod("runTest");
        m.setAccessible(true);
        assertEquals("BANANA", m.invoke(null));
    }

    @Test
    public void testStreamFilterMapAndCount() throws Exception {
        String code = """
            import java.util.List;
            import java.util.ArrayList;

            public class StreamChainedTest {
                public static String function runTest() {
                    List<String> list = new ArrayList<String>();
                    list.add("cat");
                    list.add("elephant");
                    list.add("dog");

                    variable result = list.stream()
                        .filter((s) -> s.length() > 3)
                        .map((s) -> "BIG_" + s)
                        .toList();

                    return result.get(0);
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("StreamChainedTest", code);
        Method m = clazz.getMethod("runTest");
        m.setAccessible(true);
        assertEquals("BIG_elephant", m.invoke(null));
    }

    @Test
    public void testOptionalMapAndOrElse() throws Exception {
        String code = """
            import java.util.Optional;

            public class OptionalTest {
                public static String function runTest(String input) {
                    Optional<String> opt = Optional.ofNullable(input);
                    return opt.map((s) -> s.trim()).orElse("EMPTY");
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("OptionalTest", code);
        Method m = clazz.getMethod("runTest", String.class);
        m.setAccessible(true);
        assertEquals("HELLO", m.invoke(null, "  HELLO  "));
        assertEquals("EMPTY", m.invoke(null, (String) null));
    }
}
