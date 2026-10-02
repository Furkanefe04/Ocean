package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class VirtualThreadsAndConcurrencyTest {

    @BeforeEach
    public void setup() {
        CompilerRegistry.clearAll();
        CompilerReporter.clear();
        ClassMetadataCache.clearCaches();
    }

    private String executeMainAndCaptureOutput(String className, String code) throws Exception {
        Class<?> clazz = CompilerTestHelper.compileAndLoad(className, code);
        Method mainMethod = clazz.getMethod("main", String[].class);
        mainMethod.setAccessible(true);

        PrintStream oldOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(baos));
            mainMethod.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(oldOut);
        }
        return baos.toString().trim().replace("\r\n", "\n");
    }

    @Test
    public void testAsyncMethodRunsOnVirtualThread() throws Exception {
        String code = """
            class VirtualThreadCheckTest {
                public static async bool function checkIsVirtual() {
                    return Task.isVirtual();
                }

                public static async String function computeMessage(String name) {
                    bool v = Task.isVirtual();
                    return "Hello " + name + " (isVirtual: " + v + ")";
                }

                public static async void function runTests() {
                    bool isVirt = await checkIsVirtual();
                    OceanOutput("Async isVirtual: " + isVirt);

                    String msg = await computeMessage("Ocean21");
                    OceanOutput(msg);
                }

                static main() {
                    runTests().join();
                }
            }
            """;

        String output = executeMainAndCaptureOutput("VirtualThreadCheckTest", code);
        assertTrue(output.contains("Async isVirtual: true"));
        assertTrue(output.contains("Hello Ocean21 (isVirtual: true)"));
    }

    @Test
    public void testMassiveVirtualThreadConcurrency() throws Exception {
        String code = """
            import java.util.concurrent.atomic.AtomicInteger;
            import java.util.concurrent.CompletableFuture;
            import java.util.ArrayList;
            import java.util.List;

            class MassiveVirtualThreadsTest {
                public static async void function incrementTask(AtomicInteger counter) {
                    counter.incrementAndGet();
                }

                public static async void function runStress() {
                    AtomicInteger counter = new AtomicInteger(0);
                    int totalTasks = 2000;
                    List futures = new ArrayList();

                    for (int i from 0 to totalTasks with increasing 1) {
                        futures.add(incrementTask(counter));
                    }

                    for (int j from 0 to totalTasks with increasing 1) {
                        CompletableFuture f = (CompletableFuture) futures.get(j);
                        await f;
                    }

                    OceanOutput("Completed concurrent tasks: " + counter.get());
                }

                static main() {
                    runStress().join();
                }
            }
            """;

        String output = executeMainAndCaptureOutput("MassiveVirtualThreadsTest", code);
        System.err.println("OUTPUT = [\n" + output + "\n]");
        assertTrue(output.contains("Completed concurrent tasks: 2000"));
    }

    @Test
    public void testTaskAllAndRace() throws Exception {
        String code = """
            import java.util.concurrent.CompletableFuture;

            class TaskAllAndRaceTest {
                public static async int function taskFast() {
                    Task.sleep(10);
                    return 100;
                }

                public static async int function taskSlow() {
                    Task.sleep(50);
                    return 200;
                }

                public static async void function runAll() {
                    CompletableFuture f1 = taskFast();
                    CompletableFuture f2 = taskSlow();

                    await Task.all(f1, f2);
                    OceanOutput("All finished: f1=" + (await f1) + ", f2=" + (await f2));
                }

                static main() {
                    runAll().join();
                }
            }
            """;

        String output = executeMainAndCaptureOutput("TaskAllAndRaceTest", code);
        assertTrue(output.contains("All finished: f1=100, f2=200"));
    }

    @Test
    public void testTaskSleepAndNonBlockingWorkflow() throws Exception {
        String code = """
            class TaskSleepTest {
                public static async String function delayedEcho(String word, int delayMs) {
                    Task.sleep(delayMs);
                    return "ECHO: " + word;
                }

                public static async void function runEcho() {
                    String r1 = await delayedEcho("Fast", 10);
                    String r2 = await delayedEcho("Medium", 20);
                    OceanOutput(r1);
                    OceanOutput(r2);
                }

                static main() {
                    runEcho().join();
                }
            }
            """;

        String output = executeMainAndCaptureOutput("TaskSleepTest", code);
        assertTrue(output.contains("ECHO: Fast"));
        assertTrue(output.contains("ECHO: Medium"));
    }
}
