package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DefiniteAssignmentFieldSafetyTest extends CompilerTestHelper {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    @Test
    @DisplayName("§8.3.1.2: Reading blank final field via 'this.x' before assignment in constructor fails")
    public void testBlankFinalReadBeforeAssignmentThisAccessFails() {
        String code = """
            public class BlankFinalReadThis {
                final int x;
                public BlankFinalReadThis() {
                    int y = this.x;
                    this.x = 10;
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileToBytecodeMap(code, "BlankFinalReadThis");
        });
        assertTrue(ex.getMessage().contains("cannot be read before initialization"),
                "Expected error for blank final field read before assignment, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("§8.3.1.2: Reading blank final field via simple name before assignment in constructor fails")
    public void testBlankFinalReadBeforeAssignmentSimpleNameFails() {
        String code = """
            public class BlankFinalReadSimple {
                final int x;
                public BlankFinalReadSimple() {
                    int y = x;
                    this.x = 10;
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileToBytecodeMap(code, "BlankFinalReadSimple");
        });
        assertTrue(ex.getMessage().contains("cannot be read before initialization"),
                "Expected error for blank final field read before assignment, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("§8.3.1.2: Reading blank final field after assignment in constructor succeeds")
    public void testBlankFinalReadAfterAssignmentSucceeds() {
        String code = """
            public class BlankFinalReadAfter {
                final int x;
                public BlankFinalReadAfter() {
                    this.x = 10;
                    int y = this.x;
                    int z = x;
                }
                public int function getX() {
                    return this.x;
                }
            }
            """;
        assertDoesNotThrow(() -> {
            Class<?> cls = compileAndLoad("BlankFinalReadAfter", code);
            assertNotNull(cls);
        });
    }

    @Test
    @DisplayName("Constructor parameter shadowing blank final field can be read on RHS of assignment")
    public void testConstructorParamShadowingSucceeds() {
        String code = """
            public class ParamShadow {
                final int x;
                public ParamShadow(int x) {
                    this.x = x;
                }
                public int function getX() {
                    return this.x;
                }
            }
            """;
        assertDoesNotThrow(() -> {
            Class<?> cls = compileAndLoad("ParamShadow", code);
            assertNotNull(cls);
        });
    }

    @Test
    @DisplayName("§8.3.1.2: Reading blank static final field before assignment in static block fails")
    public void testBlankStaticFinalReadBeforeAssignmentFails() {
        String code = """
            public class StaticBlankFinalRead {
                public static final int S_VAL;
                static {
                    int y = S_VAL;
                    S_VAL = 42;
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileToBytecodeMap(code, "StaticBlankFinalRead");
        });
        assertTrue(ex.getMessage().contains("cannot be read before initialization"),
                "Expected error for static blank final field read before assignment, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("§8.3.1.2: Reading blank static final field after assignment in static block succeeds")
    public void testBlankStaticFinalReadAfterAssignmentSucceeds() {
        String code = """
            public class StaticBlankFinalAfter {
                public static final int S_VAL;
                static {
                    S_VAL = 42;
                    int y = S_VAL;
                }
                public static int function getVal() {
                    return S_VAL;
                }
            }
            """;
        assertDoesNotThrow(() -> {
            Class<?> cls = compileAndLoad("StaticBlankFinalAfter", code);
            assertNotNull(cls);
        });
    }

    @Test
    @DisplayName("§8.4.8.2: Marking static method with @Override fails compilation")
    public void testStaticMethodOverrideAnnotationFails() {
        String code = """
            public class SuperClass {
                public static void function doWork() {}
            }
            public class SubClass extends SuperClass {
                @Override
                public static void function doWork() {}
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileToBytecodeMap(code, "SubClass");
        });
        assertTrue(ex.getMessage().contains("Static methods cannot be annotated with '@Override'"),
                "Expected error for static method marked @Override, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Marking instance method overriding superclass method with @Override succeeds")
    public void testInstanceMethodOverrideAnnotationSucceeds() {
        String code = """
            public class SuperClass2 {
                public void function doWork() {}
            }
            public class SubClass2 extends SuperClass2 {
                @Override
                public void function doWork() {}
            }
            """;
        assertDoesNotThrow(() -> {
            Class<?> cls = compileAndLoad("SubClass2", code);
            assertNotNull(cls);
        });
    }
}
