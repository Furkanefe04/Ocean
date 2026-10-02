package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CatchRedundancyTest extends CompilerTestHelper {

    @Test
    @DisplayName("Multi-catch with subtype of another exception in same clause fails compilation")
    public void testMultiCatchSubtypeRedundancy() {
        String code = """
                import java.io.FileNotFoundException;
                import java.io.IOException;

                public class InvalidMultiCatchRunner {
                    public static void function test() {
                        trying {
                            int x = 1;
                        } catch (FileNotFoundException | IOException e) {
                            // Error: FileNotFoundException is already covered by IOException!
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("InvalidMultiCatchRunner", code));
        assertTrue(ex.getMessage().contains("is already caught"),
                "Expected subtype redundancy error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Multi-catch with reverse order subtype redundancy fails compilation")
    public void testMultiCatchSubtypeRedundancyReverse() {
        String code = """
                import java.io.FileNotFoundException;
                import java.io.IOException;

                public class ReverseMultiCatchRunner {
                    public static void function test() {
                        trying {
                            int x = 1;
                        } catch (IOException | FileNotFoundException e) {
                            // Error: FileNotFoundException is already covered by IOException!
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ReverseMultiCatchRunner", code));
        assertTrue(ex.getMessage().contains("is already caught"),
                "Expected subtype redundancy error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Subsequent catch clause dominated by earlier catch clause fails compilation")
    public void testUnreachableCatchClause() {
        String code = """
                import java.io.FileNotFoundException;
                import java.io.IOException;

                public class UnreachableCatchRunner {
                    public static void function test() {
                        trying {
                            int x = 1;
                        } catch (IOException e) {
                            int y = 2;
                        } catch (FileNotFoundException e) {
                            // Error: unreachable!
                            int z = 3;
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("UnreachableCatchRunner", code));
        assertTrue(ex.getMessage().contains("cannot be reached") || ex.getMessage().contains("has already been caught"),
                "Expected unreachable catch error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid disjoint multi-catch succeeds")
    public void testValidDisjointMultiCatch() throws Exception {
        String code = """
                import java.io.FileNotFoundException;
                import java.io.EOFException;

                public class ValidCatchRunner {
                    public static String function test(int val) {
                        trying {
                            if (val == 1) throw new FileNotFoundException("f1");
                            if (val == 2) throw new EOFException("eof");
                            return "ok";
                        } catch (FileNotFoundException | EOFException e) {
                            return e.getMessage();
                        }
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ValidCatchRunner", code);
        Class<?> clazz = classes.get("ValidCatchRunner");
        assertNotNull(clazz);

        Method m = clazz.getMethod("test", int.class);
        assertEquals("f1", m.invoke(null, 1));
        assertEquals("eof", m.invoke(null, 2));
        assertEquals("ok", m.invoke(null, 0));
    }

    @Test
    @DisplayName("Catching unthrown checked exception emits a compiler warning")
    public void testUnthrownCheckedExceptionWarning() {
        String code = """
                import java.io.IOException;

                public class UnthrownWarnTest {
                    public static void function test() {
                        trying {
                            int x = 1;
                        } catch (IOException e) {
                            int y = 2;
                        }
                    }
                }
                """;

        CompilerReporter.clear();
        assertDoesNotThrow(() -> compileAndLoadAll("UnthrownWarnTest", code));
        var msgs = CompilerReporter.getMessages();
        var warnings = msgs.stream()
                .filter(m -> m.level() == CompilerReporter.Level.WARNING)
                .toList();
        assertTrue(warnings.stream().anyMatch(w -> w.text().contains("is never thrown")),
                "Expected unthrown checked exception warning, but got: " + warnings);
    }

    @Test
    @DisplayName("Catching unchecked exception or Exception does not emit unthrown warning")
    public void testUncheckedOrGenericExceptionNoWarning() {
        String code = """
                public class GenericCatchTest {
                    public static void function test() {
                        trying {
                            int x = 1;
                        } catch (RuntimeException e) {
                            int y = 2;
                        } catch (Exception e) {
                            int z = 3;
                        }
                    }
                }
                """;

        CompilerReporter.clear();
        assertDoesNotThrow(() -> compileAndLoadAll("GenericCatchTest", code));
        var warnings = CompilerReporter.getMessages().stream()
                .filter(m -> m.level() == CompilerReporter.Level.WARNING)
                .toList();
        assertFalse(warnings.stream().anyMatch(w -> w.text().contains("is never thrown")),
                "Did not expect unthrown warning for RuntimeException/Exception, got: " + warnings);
    }
}
