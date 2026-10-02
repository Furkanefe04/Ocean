package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MultiCatchComprehensiveTest extends CompilerTestHelper {

    @Test
    @DisplayName("Multi-catch resolves to Least Upper Bound (LUB) exception type")
    public void testMultiCatchLeastUpperBoundType() throws Exception {
        String code = """
                import java.io.FileNotFoundException;
                import java.io.EOFException;
                import java.io.IOException;

                public class MultiCatchRunner {
                    public static String function handleException(int code) {
                        trying {
                            if (code == 1) {
                                throw new FileNotFoundException("File missing");
                            } else if (code == 2) {
                                throw new EOFException("End of file");
                            }
                            return "No error";
                        } catch (FileNotFoundException | EOFException e) {
                            // Both are subclasses of IOException. e should have IOException as LUB
                            return processIoException(e);
                        }
                    }

                    public static String function processIoException(IOException io) {
                        return "Caught IO: " + io.getMessage();
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("MultiCatchRunner", code);
        Class<?> runnerClass = classes.get("MultiCatchRunner");
        assertNotNull(runnerClass);

        Method handleMethod = runnerClass.getMethod("handleException", int.class);
        assertEquals("Caught IO: File missing", handleMethod.invoke(null, 1));
        assertEquals("Caught IO: End of file", handleMethod.invoke(null, 2));
        assertEquals("No error", handleMethod.invoke(null, 0));
    }

    @Test
    @DisplayName("Multi-catch variable is implicitly final and cannot be reassigned")
    public void testMultiCatchVariableReassignmentFails() {
        String code = """
                import java.io.FileNotFoundException;
                import java.io.EOFException;

                public class InvalidMultiCatch {
                    public static void function test() {
                        trying {
                            int x = 1;
                        } catch (FileNotFoundException | EOFException e) {
                            e = new FileNotFoundException("new");
                        }
                    }
                }
                """;

        assertThrows(CompilationException.class, () -> compileAndLoadAll("InvalidMultiCatch", code));
    }

    @Test
    @DisplayName("Multi-catch with RuntimeException subclasses resolves to RuntimeException")
    public void testMultiCatchRuntimeExceptions() throws Exception {
        String code = """
                public class RuntimeCatchRunner {
                    public static String function test(int mode) {
                        trying {
                            if (mode == 1) {
                                throw new IllegalArgumentException("Bad arg");
                            } else if (mode == 2) {
                                throw new IllegalStateException("Bad state");
                            }
                            return "OK";
                        } catch (IllegalArgumentException | IllegalStateException e) {
                            return "Caught Runtime: " + e.getMessage();
                        }
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("RuntimeCatchRunner", code);
        Class<?> runnerClass = classes.get("RuntimeCatchRunner");
        assertNotNull(runnerClass);

        Method testMethod = runnerClass.getMethod("test", int.class);
        assertEquals("Caught Runtime: Bad arg", testMethod.invoke(null, 1));
        assertEquals("Caught Runtime: Bad state", testMethod.invoke(null, 2));
        assertEquals("OK", testMethod.invoke(null, 0));
    }
}
