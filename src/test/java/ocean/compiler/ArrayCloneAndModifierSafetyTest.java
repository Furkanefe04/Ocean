package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class ArrayCloneAndModifierSafetyTest extends CompilerTestHelper {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    @Test
    @DisplayName("Primitive array clone() returns exact array type without cast and executes correctly")
    public void testPrimitiveArrayCloneWithoutCast() throws Exception {
        String code = """
            public class PrimArrayClone {
                public static int function runTest() {
                    int[] arr = new int[3];
                    arr[0] = 10;
                    arr[1] = 20;
                    arr[2] = 30;
                    int[] cloned = arr.clone();
                    if (cloned.length != 3) return -1;
                    if (cloned[0] != 10 || cloned[1] != 20 || cloned[2] != 30) return -2;
                    arr[0] = 99;
                    if (cloned[0] != 10) return -3; // Must be a separate copy
                    return 100;
                }
            }
            """;
        Class<?> clazz = compileAndLoad("PrimArrayClone", code);
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        Object res = m.invoke(null);
        assertEquals(100, res);
    }

    @Test
    @DisplayName("Reference array clone() returns exact array type without cast and executes correctly")
    public void testReferenceArrayCloneWithoutCast() throws Exception {
        String code = """
            public class RefArrayClone {
                public static int function runTest() {
                    String[] arr = new String[2];
                    arr[0] = "hello";
                    arr[1] = "world";
                    String[] cloned = arr.clone();
                    if (cloned.length != 2) return -1;
                    if (!"hello".equals(cloned[0]) || !"world".equals(cloned[1])) return -2;
                    arr[0] = "changed";
                    if (!"hello".equals(cloned[0])) return -3;
                    return 200;
                }
            }
            """;
        Class<?> clazz = compileAndLoad("RefArrayClone", code);
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        Object res = m.invoke(null);
        assertEquals(200, res);
    }

    @Test
    @DisplayName("@SafeVarargs on an overridable instance method produces compile error")
    public void testSafeVarargsOnOverridableMethodFails() {
        String code = """
            public class SafeVarargsOverridable {
                @SafeVarargs
                public void function doWork(String... items) {}
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileToBytecodeMap(code, "SafeVarargsOverridable");
        });
        assertTrue(ex.getMessage().contains("@SafeVarargs") && ex.getMessage().contains("overridable"),
                "Expected error for @SafeVarargs on overridable method, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("@SafeVarargs on a non-varargs method produces compile error")
    public void testSafeVarargsOnNonVarargsMethodFails() {
        String code = """
            public class SafeVarargsNonVarargs {
                @SafeVarargs
                public static void function doWork(int x) {}
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileToBytecodeMap(code, "SafeVarargsNonVarargs");
        });
        assertTrue(ex.getMessage().contains("@SafeVarargs") && ex.getMessage().contains("varargs"),
                "Expected error for @SafeVarargs on non-varargs method, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("@SafeVarargs on static, final, private methods and constructors succeeds")
    public void testSafeVarargsOnStaticOrFinalOrPrivateMethodSucceeds() {
        String code = """
            public class SafeVarargsValid {
                @SafeVarargs
                public SafeVarargsValid(String... items) {}

                @SafeVarargs
                public static void function staticWork(String... items) {}

                @SafeVarargs
                public final void function finalWork(String... items) {}

                @SafeVarargs
                private void function privateWork(String... items) {}
            }
            """;
        assertDoesNotThrow(() -> {
            Class<?> clazz = compileAndLoad("SafeVarargsValid", code);
            assertNotNull(clazz);
        });
    }

    @Test
    @DisplayName("non-sealed on standalone class without sealed superclass produces compile error")
    public void testNonSealedOnStandaloneClassFails() {
        String code = """
            public non-sealed class StandaloneNonSealed {}
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileToBytecodeMap(code, "StandaloneNonSealed");
        });
        assertTrue(ex.getMessage().contains("non-sealed") && ex.getMessage().contains("sealed"),
                "Expected error for non-sealed on standalone class, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("non-sealed on permitted subclass of sealed class succeeds")
    public void testNonSealedOnPermittedSubclassSucceeds() {
        String code = """
            public sealed class SealedParent restricts NonSealedChild {}
            public non-sealed class NonSealedChild extends SealedParent {}
            """;
        assertDoesNotThrow(() -> {
            Class<?> clazz = compileAndLoad("SealedParent", code);
            assertNotNull(clazz);
        });
    }
}
