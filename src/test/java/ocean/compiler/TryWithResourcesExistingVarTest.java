package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TryWithResourcesExistingVarTest extends CompilerTestHelper {

    @Test
    @DisplayName("Try-with-resources with existing pre-declared variable (Java 9 JEP 213)")
    public void testExistingResourceVariable() throws Exception {
        String code = """
                public class TrackedResource implements java.lang.AutoCloseable {
                    public static int closeCount = 0;
                    public static bool actionDone = false;

                    public void function close() {
                        closeCount = closeCount + 1;
                    }

                    public void function execute() {
                        actionDone = true;
                    }
                }

                public class ExistingResourceRunner {
                    public static int function runTest() {
                        TrackedResource.closeCount = 0;
                        TrackedResource.actionDone = false;

                        value res = new TrackedResource();
                        trying (res) {
                            res.execute();
                        }

                        return TrackedResource.closeCount;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ExistingResourceRunner", code);
        Class<?> runnerClass = classes.get("ExistingResourceRunner");
        assertNotNull(runnerClass);

        Method runMethod = runnerClass.getMethod("runTest");
        int closeCount = (int) runMethod.invoke(null);
        assertEquals(1, closeCount);
    }

    @Test
    @DisplayName("Try-with-resources with mixed pre-declared and newly declared resources")
    public void testMixedResources() throws Exception {
        String code = """
                public class MixedCloseable implements java.lang.AutoCloseable {
                    public static int totalCloses = 0;
                    public void function close() {
                        totalCloses = totalCloses + 1;
                    }
                }

                public class MixedRunner {
                    public static int function runMixed() {
                        MixedCloseable.totalCloses = 0;

                        value existing1 = new MixedCloseable();
                        trying (existing1; value newlyCreated = new MixedCloseable()) {
                            MixedCloseable.totalCloses = MixedCloseable.totalCloses + 10;
                        }

                        return MixedCloseable.totalCloses;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("MixedRunner", code);
        Class<?> runnerClass = classes.get("MixedRunner");
        assertNotNull(runnerClass);

        Method runMethod = runnerClass.getMethod("runMixed");
        int totalCloses = (int) runMethod.invoke(null);
        assertEquals(12, totalCloses);
    }

    @Test
    @DisplayName("Try-with-resources with non-AutoCloseable existing variable fails compilation")
    public void testNonAutoCloseableExistingResourceFails() {
        String code = """
                public class NonCloseable {
                    public int value = 42;
                }

                public class InvalidRunner {
                    public static void function test() {
                        value notCloseable = new NonCloseable();
                        trying (notCloseable) {
                            int x = notCloseable.value;
                        }
                    }
                }
                """;

        assertThrows(CompilationException.class, () -> compileAndLoadAll("InvalidRunner", code));
    }
}
