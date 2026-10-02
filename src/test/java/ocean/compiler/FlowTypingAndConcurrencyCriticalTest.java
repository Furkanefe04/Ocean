package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowTypingAndConcurrencyCriticalTest extends CompilerTestHelper {

    @BeforeEach
    void setup() {
        CompilerRegistry.clearAll();
        CompilerReporter.clear();
        ClassMetadataCache.clearCaches();
    }

    @Test
    @DisplayName("Assignment of non-null expression to nullable variable enables direct member access")
    void testAssignmentNonNullFlowTyping() throws Exception {
        Class<?> clazz = compileAndLoad("AssignFlowTest", """
                public class AssignFlowTest {
                    public static int function test() {
                        String? s = null;
                        s = "hello";
                        return s.length();
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals(5, m.invoke(null));
    }

    @Test
    @DisplayName("verify s != null statement narrows variable to non-null and enables member access")
    void testVerifyNullCheckSmartCast() throws Exception {
        Class<?> clazz = compileAndLoad("VerifyNullTest", """
                public class VerifyNullTest {
                    public static int function test(String? s) {
                        verify s != null;
                        return s.length();
                    }
                }
                """);
        Method m = clazz.getMethod("test", String.class);
        assertEquals(5, m.invoke(null, "world"));

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, () -> m.invoke(null, (Object) null));
        assertTrue(ex.getCause() instanceof AssertionError);
    }

    @Test
    @DisplayName("verify a instanceof Dog statement smart-casts interface to concrete type")
    void testVerifyInstanceofSmartCast() throws Exception {
        String code = """
                public interface Animal {}
                public class Dog implements Animal {
                    public int function bark() { return 42; }
                }
                public class VerifyInstanceofTest {
                    public static int function test(Animal a) {
                        verify a instanceof Dog;
                        return a.bark();
                    }
                }
                """;
        Map<String, Class<?>> loaded = compileAndLoadAll("VerifyInstanceofTest", code);
        Class<?> dogClass = loaded.get("Dog");
        Object dogInstance = dogClass.getDeclaredConstructor().newInstance();

        Class<?> animalClass = loaded.get("Animal");
        Class<?> testClass = loaded.get("VerifyInstanceofTest");
        Method m = testClass.getMethod("test", animalClass);
        assertEquals(42, m.invoke(null, dogInstance));
    }

    @Test
    @DisplayName("await operator has unary prefix precedence enabling await a + await b naturally")
    void testAwaitOperatorPrecedence() throws Exception {
        Class<?> clazz = compileAndLoad("AwaitPrecedenceTest", """
                public class AwaitPrecedenceTest {
                    public static async int function getA() { return 10; }
                    public static async int function getB() { return 20; }
                    public static async int function compute() {
                        int sum = await getA() + await getB();
                        return sum;
                    }
                }
                """);
        Method m = clazz.getMethod("compute");
        CompletableFuture<?> future = (CompletableFuture<?>) m.invoke(null);
        assertEquals(30, future.join());
    }

    @Test
    @DisplayName("await unwraps CompletionException and rethrows exact underlying exception for typed catch blocks")
    void testAwaitExceptionUnwrapping() throws Exception {
        Class<?> clazz = compileAndLoad("AwaitUnwrapTest", """
                public class AwaitUnwrapTest {
                    public static async String function failAsync() {
                        throw new IllegalStateException("ConnectionReset");
                    }
                    public static async String function test() {
                        trying {
                            return await failAsync();
                        } catch (IllegalStateException e) {
                            return "CAUGHT_ISE: " + e.getMessage();
                        }
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        CompletableFuture<?> future = (CompletableFuture<?>) m.invoke(null);
        assertEquals("CAUGHT_ISE: ConnectionReset", future.join());
    }
}
