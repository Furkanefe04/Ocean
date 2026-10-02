package ocean.compiler;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class TryWithResourcesComprehensiveTest {

    public static List<String> closeEvents = new ArrayList<>();

    public static void recordClose(String name) {
        closeEvents.add("closed:" + name);
    }

    @Test
    public void testMultiResourceLifoCloseOrder() throws Exception {
        closeEvents.clear();

        String code = """
            import java.lang.AutoCloseable;
            import ocean.compiler.TryWithResourcesComprehensiveTest;

            public class MockResource implements AutoCloseable {
                public String name;
                public function MockResource(String name) {
                    this.name = name;
                }
                public String function read() {
                    return "data from " + this.name;
                }
                public void function close() {
                    TryWithResourcesComprehensiveTest.recordClose(this.name);
                }
            }

            public class MultiResTest {
                public static String function runTest() {
                    trying (MockResource r1 = new MockResource("R1"); MockResource r2 = new MockResource("R2")) {
                        return r1.read() + " & " + r2.read();
                    }
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("MultiResTest", code);
        Method m = clazz.getMethod("runTest");
        m.setAccessible(true);
        String res = (String) m.invoke(null);

        assertEquals("data from R1 & data from R2", res);
        assertEquals(2, closeEvents.size());
        // LIFO order: r2 closed first, then r1
        assertEquals("closed:R2", closeEvents.get(0));
        assertEquals("closed:R1", closeEvents.get(1));
    }

    @Test
    public void testNonAutoCloseableThrowsCompileError() {
        String code = """
            class NonCloseableObject {
                public String value = "test";
            }

            class InvalidResourceTest {
                public static void function runTest() {
                    trying (NonCloseableObject obj = new NonCloseableObject()) {
                        OceanOutput("invalid");
                    }
                }
            }
            """;

        assertThrows(CompilationException.class, () -> {
            CompilerTestHelper.compileAndLoad("InvalidResourceTest", code);
        });
    }
}