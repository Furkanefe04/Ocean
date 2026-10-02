package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class UnnamedVariablesComprehensiveTest extends CompilerTestHelper {

    @Test
    @DisplayName("Unnamed variables in catch, for-in loop, and try-with-resources")
    public void testUnnamedVariablesInVariousStatements() throws Exception {
        String code = """
                import java.util.List;
                import java.util.ArrayList;

                public class ResourceTracker implements java.lang.AutoCloseable {
                    public static int closedCount = 0;
                    public void function close() {
                        closedCount = closedCount + 1;
                    }
                }

                public class UnnamedRunner {
                    public static int function runAll() {
                        variable counter = 0;

                        // 1. Unnamed variable in try-with-resources
                        trying (value _ = new ResourceTracker()) {
                            counter = counter + 10;
                        }

                        // 2. Unnamed variable in catch clause
                        trying {
                            Integer.parseInt("not-a-number");
                        } catch (java.lang.NumberFormatException _) {
                            counter = counter + 5;
                        }

                        // 3. Unnamed variable in for-in loop
                        List<String> items = new ArrayList<String>();
                        items.add("a");
                        items.add("b");
                        items.add("c");

                        for (value _ in items) {
                            counter = counter + 1;
                        }

                        return counter;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("UnnamedRunner", code);
        Class<?> runnerClass = classes.get("UnnamedRunner");
        assertNotNull(runnerClass);

        Method runAllMethod = runnerClass.getMethod("runAll");
        int result = (int) runAllMethod.invoke(null);
        // counter: 10 (try-with-resources) + 5 (catch) + 3 (for-in loop) = 18
        assertEquals(18, result);

        Class<?> trackerClass = classes.get("ResourceTracker");
        int closed = (int) trackerClass.getField("closedCount").get(null);
        assertEquals(1, closed, "AutoCloseable resource must be closed");
    }

    @Test
    @DisplayName("Referencing unnamed variable '_' in expression produces compilation error")
    public void testReferencingUnnamedVariableRejected() {
        String code = """
                public class InvalidUnnamedUsage {
                    public static void function test() {
                        trying {
                            int x = 10;
                        } catch (Exception _) {
                            System.out.println(_);
                        }
                    }
                }
                """;

        assertThrows(CompilationException.class, () -> {
            compileAndLoadAll("InvalidUnnamedUsage", code);
        });
    }
}
