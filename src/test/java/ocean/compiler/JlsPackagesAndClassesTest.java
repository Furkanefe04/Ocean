package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JlsPackagesAndClassesTest extends CompilerTestHelper {

    @BeforeEach
    void setup() {
        CompilerRegistry.clearAll();
        CompilerReporter.clear();
        ClassMetadataCache.clearCaches();
    }

    @Test
    @DisplayName("Conflicting single-type imports with same simple name fail compilation")
    void testConflictingImports() {
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileAndLoad("ConflictImport", """
                    import java.util.List;
                    import java.awt.List;

                    public class ConflictImport {
                        public static void function test() {}
                    }
                    """);
        });
        assertTrue(ex.getMessage().contains("Conflicting import declaration"),
                "Expected conflicting import error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Duplicate identical single-type imports are permitted")
    void testIdenticalDuplicateImports() throws Exception {
        Class<?> clazz = compileAndLoad("DuplicateImportOk", """
                import java.util.List;
                import java.util.List;

                public class DuplicateImportOk {
                    public static int function test() {
                        return 100;
                    }
                }
                """);
        assertEquals(100, clazz.getMethod("test").invoke(null));
    }

    @Test
    @DisplayName("Single-type import conflicting with top-level class fails compilation")
    void testImportTopLevelShadowing() {
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileAndLoad("List", """
                    import java.util.List;

                    public class List {
                        public static void function test() {}
                    }
                    """);
        });
        assertTrue(ex.getMessage().contains("conflicts"),
                "Expected shadowing collision error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Direct constructor cycle (this() calling itself) fails compilation")
    void testDirectConstructorCycle() {
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileAndLoad("DirectCycle", """
                    public class DirectCycle {
                        function DirectCycle() {
                            this();
                        }
                    }
                    """);
        });
        assertTrue(ex.getMessage().contains("Recursive constructor") || ex.getMessage().contains("recursive constructor"),
                "Expected constructor cycle error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Indirect constructor cycle (A -> B -> A) fails compilation")
    void testIndirectConstructorCycle() {
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileAndLoad("IndirectCycle", """
                    public class IndirectCycle {
                        function IndirectCycle() {
                            this(10);
                        }
                        function IndirectCycle(int x) {
                            this();
                        }
                    }
                    """);
        });
        assertTrue(ex.getMessage().contains("Recursive constructor") || ex.getMessage().contains("recursive constructor"),
                "Expected constructor cycle error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid constructor delegation terminates and executes correctly")
    void testValidConstructorDelegation() throws Exception {
        Class<?> clazz = compileAndLoad("ValidCtorDelegation", """
                public class ValidCtorDelegation {
                    public int value;
                    function ValidCtorDelegation() {
                        this(42);
                    }
                    function ValidCtorDelegation(int x) {
                        this.value = x;
                    }
                    public static int function test() {
                        ValidCtorDelegation obj = new ValidCtorDelegation();
                        return obj.value;
                    }
                }
                """);
        assertEquals(42, clazz.getMethod("test").invoke(null));
    }

    @Test
    @DisplayName("Constructor with bare return; compiles and works")
    void testConstructorReturnBareOk() throws Exception {
        Class<?> clazz = compileAndLoad("BareReturnCtor", """
                public class BareReturnCtor {
                    public int value;
                    function BareReturnCtor(int x) {
                        if (x < 0) {
                            this.value = 0;
                            return;
                        }
                        this.value = x;
                    }
                    public static int function test() {
                        BareReturnCtor obj = new BareReturnCtor(-5);
                        return obj.value;
                    }
                }
                """);
        assertEquals(0, clazz.getMethod("test").invoke(null));
    }

    @Test
    @DisplayName("Constructor returning a value fails compilation")
    void testConstructorReturnValueFail() {
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileAndLoad("ReturnValCtor", """
                    public class ReturnValCtor {
                        function ReturnValCtor() {
                            return 42;
                        }
                    }
                    """);
        });
        assertTrue(ex.getMessage().contains("Constructor") || ex.getMessage().contains("Cannot return a value"),
                "Expected constructor return value error, got: " + ex.getMessage());
    }
}
