package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JlsPackagesClassesInterfacesMediumLowTest extends CompilerTestHelper {

    @BeforeEach
    void setup() {
        CompilerRegistry.clearAll();
        CompilerReporter.clear();
        ClassMetadataCache.clearCaches();
    }

    @Test
    @DisplayName("Class cannot extend a type variable")
    void testClassExtendingTypeVariable() {
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileAndLoad("ExtendsTypeVar", """
                    public class ExtendsTypeVar<T> extends T {
                    }
                    """);
        });
        assertTrue(ex.getMessage().contains("Cannot extend a type parameter"),
                "Expected extending type variable error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Class extending a regular generic type succeeds")
    void testValidClassExtendingGeneric() throws Exception {
        Class<?> clazz = compileAndLoad("ValidExtendsGeneric", """
                import java.util.ArrayList;

                public class ValidExtendsGeneric<T> extends ArrayList<T> {
                    public int function getAnswer() {
                        return 42;
                    }
                }
                """);
        Object inst = clazz.getDeclaredConstructor().newInstance();
        assertEquals(42, clazz.getMethod("getAnswer").invoke(inst));
    }

    @Test
    @DisplayName("Interface default method without a body fails compilation")
    void testInterfaceDefaultMethodWithoutBody() {
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileAndLoad("InterfaceDefaultNoBody", """
                    public interface InterfaceDefaultNoBody {
                        default void function test();
                    }
                    """);
        });
        assertTrue(ex.getMessage().contains("gövde") || ex.getMessage().contains("default"),
                "Expected default method body error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Interface default and static methods with body succeed")
    void testInterfaceDefaultAndStaticMethods() throws Exception {
        Class<?> iface = compileAndLoad("InterfaceStaticValid", """
                public interface InterfaceStaticValid {
                    static int function statMethod() {
                        return 20;
                    }
                }
                """);
        assertEquals(20, iface.getMethod("statMethod").invoke(null));

        Class<?> clazz = compileAndLoad("ImplValid", """
                public interface InterfaceDef {
                    default int function defMethod() {
                        return 10;
                    }
                }
                public class ImplValid implements InterfaceDef {
                }
                """);
        Object inst = clazz.getDeclaredConstructor().newInstance();
        assertEquals(10, clazz.getMethod("defMethod").invoke(inst));
    }

    @Test
    @DisplayName("Illegal field modifiers in class (abstract, native, default) fail compilation")
    void testIllegalFieldModifiersInClass() {
        assertThrows(CompilationException.class, () -> {
            compileAndLoad("FieldAbstract", """
                    public class FieldAbstract {
                        public abstract int x;
                    }
                    """);
        });

        assertThrows(CompilationException.class, () -> {
            compileAndLoad("FieldNative", """
                    public class FieldNative {
                        public native int x;
                    }
                    """);
        });

        assertThrows(CompilationException.class, () -> {
            compileAndLoad("FieldDefault", """
                    public class FieldDefault {
                        public default int x;
                    }
                    """);
        });
    }

    @Test
    @DisplayName("Conflicting static single-imports from different classes fail compilation")
    void testConflictingStaticImports() {
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileAndLoad("StaticConflict", """
                    import static java.lang.Integer.MAX_VALUE;
                    import static java.lang.Long.MAX_VALUE;

                    public class StaticConflict {
                        public static void function test() {}
                    }
                    """);
        });
        assertTrue(ex.getMessage().contains("Conflicting static import"),
                "Expected conflicting static import error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Identical duplicate static single-imports succeed")
    void testIdenticalStaticImports() throws Exception {
        Class<?> clazz = compileAndLoad("StaticDuplicateOk", """
                import static java.lang.Integer.MAX_VALUE;
                import static java.lang.Integer.MAX_VALUE;

                public class StaticDuplicateOk {
                    public static int function getMax() {
                        return MAX_VALUE;
                    }
                }
                """);
        assertEquals(Integer.MAX_VALUE, clazz.getMethod("getMax").invoke(null));
    }

    @Test
    @DisplayName("Single static import clashing with single-type import fails compilation")
    void testStaticImportClashingWithTypeImport() {
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileAndLoad("StaticClashType", """
                    import java.util.List;
                    import static java.awt.List;

                    public class StaticClashType {
                        public static void function test() {}
                    }
                    """);
        });
        assertTrue(ex.getMessage().contains("conflicts"),
                "Expected clash error, got: " + ex.getMessage());
    }
}
