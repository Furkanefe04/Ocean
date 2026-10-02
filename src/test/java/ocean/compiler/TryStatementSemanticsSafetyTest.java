package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TryStatementSemanticsSafetyTest extends CompilerTestHelper {

    @Test
    @DisplayName("Empty trying block without catch, finally, or resources fails compilation")
    public void testEmptyTryWithoutCatchOrFinallyOrResourcesFails() {
        String code = """
                public class EmptyTryTest {
                    public static void function foo() {
                        trying {
                            int x = 1;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoadAll("EmptyTryTest", code));
        assertTrue(ex.getMessage().contains("trying"),
                "Expected empty try error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Try-with-resources resource variable reassignment inside try block fails")
    public void testResourceReassignmentInsideTryFails() {
        String code = """
                public class TrackedResource implements java.lang.AutoCloseable {
                    public void function close() {}
                }
                public class ResReassignInsideTest {
                    public static void function foo() {
                        variable res = new TrackedResource();
                        trying (res) {
                            res = new TrackedResource();
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoadAll("ResReassignInsideTest", code));
        assertTrue(ex.getMessage().contains("Resource 'res' in try-with-resources statement must be final"),
                "Expected resource reassignment error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Try-with-resources resource variable mutated before try block fails effectively final rule")
    public void testResourceMutatedBeforeTryFails() {
        String code = """
                public class TrackedResource implements java.lang.AutoCloseable {
                    public void function close() {}
                }
                public class ResMutatedBeforeTest {
                    public static void function foo() {
                        variable res = new TrackedResource();
                        res = new TrackedResource();
                        trying (res) {
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoadAll("ResMutatedBeforeTest", code));
        assertTrue(ex.getMessage().contains("Resource 'res' in try-with-resources statement must be final"),
                "Expected effectively final error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Try-with-resources resource variable mutated after try block fails")
    public void testResourceMutatedAfterTryFails() {
        String code = """
                public class TrackedResource implements java.lang.AutoCloseable {
                    public void function close() {}
                }
                public class ResMutatedAfterTest {
                    public static void function foo() {
                        variable res = new TrackedResource();
                        trying (res) {
                        }
                        res = new TrackedResource();
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoadAll("ResMutatedAfterTest", code));
        assertTrue(ex.getMessage().contains("Resource 'res' in try-with-resources statement must be final"),
                "Expected reassignment after try error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid effectively final resource in try-with-resources compiles and executes successfully")
    public void testValidEffectivelyFinalResourcePasses() throws Exception {
        String code = """
                public class TrackedResource implements java.lang.AutoCloseable {
                    public static int closeCount = 0;
                    public static bool executed = false;
                    public void function close() {
                        closeCount = closeCount + 1;
                    }
                    public void function run() {
                        executed = true;
                    }
                }
                public class ValidResourceRunner {
                    public static int function test() {
                        TrackedResource.closeCount = 0;
                        TrackedResource.executed = false;
                        variable res = new TrackedResource();
                        trying (res) {
                            res.run();
                        }
                        return TrackedResource.closeCount;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("ValidResourceRunner", code);
        Class<?> runner = classes.get("ValidResourceRunner");
        Method testMethod = runner.getMethod("test");
        int closes = (int) testMethod.invoke(null);
        assertEquals(1, closes);
    }

    @Test
    @DisplayName("Valid try-catch compiles and executes successfully")
    public void testValidTryCatchPasses() throws Exception {
        String code = """
                public class ValidTryCatch {
                    public static int function test() {
                        int r = 0;
                        trying {
                            r = 10;
                        } catch (Exception e) {
                            r = 20;
                        }
                        return r;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("ValidTryCatch", code);
        Class<?> runner = classes.get("ValidTryCatch");
        Method testMethod = runner.getMethod("test");
        int res = (int) testMethod.invoke(null);
        assertEquals(10, res);
    }

    @Test
    @DisplayName("Valid try-finally compiles and executes successfully")
    public void testValidTryFinallyPasses() throws Exception {
        String code = """
                public class ValidTryFinally {
                    public static int function test() {
                        int r = 0;
                        trying {
                            r = 10;
                        } finally {
                            r = 30;
                        }
                        return r;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("ValidTryFinally", code);
        Class<?> runner = classes.get("ValidTryFinally");
        Method testMethod = runner.getMethod("test");
        int res = (int) testMethod.invoke(null);
        assertEquals(30, res);
    }

    @Test
    @DisplayName("Valid try-catch-finally compiles and executes successfully")
    public void testValidTryCatchFinallyPasses() throws Exception {
        String code = """
                public class ValidTryCatchFinally {
                    public static int function test() {
                        int r = 0;
                        trying {
                            r = 10;
                        } catch (Exception e) {
                            r = 20;
                        } finally {
                            r = 40;
                        }
                        return r;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("ValidTryCatchFinally", code);
        Class<?> runner = classes.get("ValidTryCatchFinally");
        Method testMethod = runner.getMethod("test");
        int res = (int) testMethod.invoke(null);
        assertEquals(40, res);
    }
}
