package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LambdaCheckedExceptionTest extends CompilerTestHelper {

    @Test
    @DisplayName("Lambda in Runnable throwing checked exception without try-catch fails compilation")
    public void testRunnableThrowingCheckedExceptionFails() {
        String code = """
                import java.io.IOException;

                public class LambdaExcTest {
                    public static void function runTask() throws IOException {
                        Runnable r = () -> {
                            throw new IOException("Test error");
                        };
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "LambdaExcTest"));
        assertTrue(ex.getMessage().contains("checked exception") || ex.getMessage().contains("Unreported exception"),
                "Expected unhandled checked exception error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Lambda in Callable declaring throws Exception compiles successfully")
    public void testCallableThrowingExceptionSucceeds() {
        String code = """
                import java.util.concurrent.Callable;

                public class CallableExcTest {
                    public static void function testCallable() {
                        Callable<String> c = () -> {
                            throw new Exception("Callable error");
                        };
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "CallableExcTest"));
    }

    @Test
    @DisplayName("Lambda in Runnable with internal try-catch compiles successfully")
    public void testRunnableWithInternalTryCatchSucceeds() {
        String code = """
                import java.io.IOException;

                public class SafeRunnableTest {
                    public static void function safeRun() {
                        Runnable r = () -> {
                            trying {
                                throw new IOException("Caught inside");
                            } catch (IOException e) {
                                OceanOutput("Caught");
                            }
                        };
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "SafeRunnableTest"));
    }
}