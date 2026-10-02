package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class NonSealedAndCallSafetyTest extends CompilerTestHelper {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    @Test
    @DisplayName("Issue 1: String concatenation with chained integers compiles and evaluates correctly")
    public void testStringConcatWithMultipleInts() throws Exception {
        String code = """
            public class ConcatTest {
                public static String function getResult() {
                    return "result: " + 1 + 2;
                }
            }
            """;
        Map<String, Class<?>> classes = compileAndLoadAll("ConcatTest", code);
        Class<?> clazz = classes.get("ConcatTest");
        assertNotNull(clazz);

        Method getResult = clazz.getMethod("getResult");
        Object result = getResult.invoke(null);
        assertEquals("result: 12", result);
    }

    @Test
    @DisplayName("Issue 2: Interface with private helper method compiles and executes successfully")
    public void testInterfacePrivateMethod() throws Exception {
        String code = """
            public interface HelperIface {
                private String function secret() {
                    return "private-ok";
                }
                default String function getMessage() {
                    return secret();
                }
            }
            public class Impl implements HelperIface {}
            """;
        Map<String, Class<?>> classes = compileAndLoadAll("Impl", code);
        Class<?> implClass = classes.get("Impl");
        assertNotNull(implClass);

        Object instance = implClass.getDeclaredConstructor().newInstance();
        Method getMessageMethod = implClass.getMethod("getMessage");
        Object result = getMessageMethod.invoke(instance);
        assertEquals("private-ok", result);
    }

    @Test
    @DisplayName("Issue 3: non-sealed class without sealed superclass fails compilation")
    public void testNonSealedWithoutSealedSuperFails() {
        String code = """
            public class NormalBase {}
            public non-sealed class Child extends NormalBase {}
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileToBytecodeMap(code, "Child");
        });
        assertTrue(ex.getMessage().contains("non-sealed") && ex.getMessage().contains("is not sealed"),
                "Expected error message indicating superclass is not sealed, but was: " + ex.getMessage());
    }

    @Test
    @DisplayName("Issue 3: non-sealed interface without sealed superinterface fails compilation")
    public void testNonSealedWithoutSealedSuperInterfaceFails() {
        String code = """
            public interface NormalIface {}
            public non-sealed interface ChildIface extends NormalIface {}
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileToBytecodeMap(code, "ChildIface");
        });
        assertTrue(ex.getMessage().contains("non-sealed"),
                "Expected error message indicating superinterface is not sealed, but was: " + ex.getMessage());
    }

    @Test
    @DisplayName("Issue 3: non-sealed class extending a sealed class with restricts compiles successfully")
    public void testNonSealedWithSealedSuperCompiles() throws Exception {
        String code = """
            public sealed class SealedBase restricts Child {}
            public non-sealed class Child extends SealedBase {}
            """;
        Map<String, byte[]> bytecodes = compileToBytecodeMap(code, "Child");
        assertNotNull(bytecodes);
        assertTrue(bytecodes.containsKey("Child/Child") || bytecodes.containsKey("Child"));
    }
}
