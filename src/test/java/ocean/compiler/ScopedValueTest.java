package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class ScopedValueTest {

    @BeforeEach
    public void setup() {
        CompilerRegistry.clearAll();
        CompilerReporter.clear();
        ClassMetadataCache.clearCaches();
    }

    private String executeMainAndCaptureOutput(String mainClassName, String oceanSource) throws Exception {
        Class<?> clazz = CompilerTestHelper.compileAndLoad(mainClassName, oceanSource);
        Method mainMethod = clazz.getMethod("main", String[].class);
        mainMethod.setAccessible(true);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(outContent));
            mainMethod.invoke(null, (Object) new String[]{});
        } finally {
            System.setOut(originalOut);
        }
        return outContent.toString().trim();
    }

    @Test
    public void testBasicScopedValueBinding() throws Exception {
        String code = """
            import ocean.stdlib.ScopedValue;

            public class BasicScopedValueTest {
                public static ScopedValue USER = ScopedValue.newInstance();

                static main() {
                    OceanOutput("Before scope isBound: " + USER.isBound());
                    OceanOutput("Before scope fallback: " + USER.orElse("Guest"));

                    ScopedValue.where(USER, "Alice").run(() -> {
                        OceanOutput("Inside scope isBound: " + USER.isBound());
                        OceanOutput("Inside scope user: " + USER.get());
                    });

                    OceanOutput("After scope isBound: " + USER.isBound());
                }
            }
            """;

        String output = executeMainAndCaptureOutput("BasicScopedValueTest", code);
        assertTrue(output.contains("Before scope isBound: false"));
        assertTrue(output.contains("Before scope fallback: Guest"));
        assertTrue(output.contains("Inside scope isBound: true"));
        assertTrue(output.contains("Inside scope user: Alice"));
        assertTrue(output.contains("After scope isBound: false"));
    }

    @Test
    public void testMultipleScopedValuesWithCarrier() throws Exception {
        String code = """
            import ocean.stdlib.ScopedValue;

            public class MultiScopedValueTest {
                public static ScopedValue USER_ID = ScopedValue.newInstance();
                public static ScopedValue TRACE_ID = ScopedValue.newInstance();

                public static void function serviceLayer() {
                    OceanOutput("Service handling request for user " + USER_ID.get() + " [trace=" + TRACE_ID.get() + "]");
                }

                static main() {
                    ScopedValue.where(USER_ID, "usr_9988")
                               .where(TRACE_ID, "req-xyz-123")
                               .run(() -> {
                                   serviceLayer();
                               });
                }
            }
            """;

        String output = executeMainAndCaptureOutput("MultiScopedValueTest", code);
        assertTrue(output.contains("Service handling request for user usr_9988 [trace=req-xyz-123]"));
    }

    @Test
    public void testRebindingInNestedScope() throws Exception {
        String code = """
            import ocean.stdlib.ScopedValue;

            public class NestedScopeTest {
                public static ScopedValue CONTEXT = ScopedValue.newInstance();

                static main() {
                    ScopedValue.where(CONTEXT, "OUTER_VALUE").run(() -> {
                        OceanOutput("Outer level 1: " + CONTEXT.get());

                        ScopedValue.where(CONTEXT, "INNER_VALUE").run(() -> {
                            OceanOutput("Nested inner: " + CONTEXT.get());
                        });

                        OceanOutput("Outer level 2: " + CONTEXT.get());
                    });
                }
            }
            """;

        String output = executeMainAndCaptureOutput("NestedScopeTest", code);
        assertTrue(output.contains("Outer level 1: OUTER_VALUE"));
        assertTrue(output.contains("Nested inner: INNER_VALUE"));
        assertTrue(output.contains("Outer level 2: OUTER_VALUE"));
    }

    @Test
    public void testScopedValueWithVirtualThreads() throws Exception {
        String code = """
            import ocean.stdlib.ScopedValue;
            import ocean.stdlib.Task;
            import java.util.concurrent.CompletableFuture;

            public class VirtualThreadScopedValueTest {
                public static ScopedValue TRANSACTION_ID = ScopedValue.newInstance();

                public static async String function asyncWorker(String label) {
                    boolean isVirt = Task.isVirtual();
                    String tx = (String) TRANSACTION_ID.get();
                    return label + " -> TX:" + tx + ", Virtual:" + isVirt;
                }

                public static async void function runTest() {
                    ScopedValue.where(TRANSACTION_ID, "tx_secure_777").run(() -> {
                        CompletableFuture f1 = asyncWorker("Worker-1");
                        CompletableFuture f2 = asyncWorker("Worker-2");
                        
                        String r1 = (String) await f1;
                        String r2 = (String) await f2;

                        OceanOutput(r1);
                        OceanOutput(r2);
                    });
                }

                static main() {
                    runTest().join();
                }
            }
            """;

        String output = executeMainAndCaptureOutput("VirtualThreadScopedValueTest", code);
        assertTrue(output.contains("Worker-1 -> TX:tx_secure_777, Virtual:true"));
        assertTrue(output.contains("Worker-2 -> TX:tx_secure_777, Virtual:true"));
    }

    @Test
    public void testUnboundThrowsNoSuchElement() throws Exception {
        String code = """
            import ocean.stdlib.ScopedValue;

            public class UnboundScopedValueTest {
                public static ScopedValue UNBOUND = ScopedValue.newInstance();

                static main() {
                    trying {
                        UNBOUND.get();
                        OceanOutput("STATUS: FAILED");
                    } catch (Exception e) {
                        OceanOutput("Caught expected NoSuchElementException: " + e.getMessage());
                    }
                }
            }
            """;

        String output = executeMainAndCaptureOutput("UnboundScopedValueTest", code);
        assertTrue(output.contains("Caught expected NoSuchElementException"));
    }
}
