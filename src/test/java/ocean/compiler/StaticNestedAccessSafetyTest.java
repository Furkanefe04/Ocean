package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class StaticNestedAccessSafetyTest extends CompilerTestHelper {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    @Test
    @DisplayName("§8.5.1: Static nested class accessing outer instance field fails compilation")
    public void testStaticNestedAccessOuterInstanceFieldFails() {
        String code = """
            public class Outer {
                int instanceField = 42;
                public static class Nested {
                    public int function getVal() {
                        return instanceField;
                    }
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileToBytecodeMap(code, "Outer");
        });
        assertTrue(ex.getMessage().contains("Statik olmayan alan") || ex.getMessage().contains("without an enclosing instance"),
                "Expected error for non-static field access from static nested class, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("§8.5.1: Static nested class accessing outer instance method fails compilation")
    public void testStaticNestedAccessOuterInstanceMethodFails() {
        String code = """
            public class Outer {
                public void function instanceMethod() {}
                public static class Nested {
                    public void function callIt() {
                        instanceMethod();
                    }
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileToBytecodeMap(code, "Outer");
        });
        assertTrue(ex.getMessage().contains("Statik olmayan metot") || ex.getMessage().contains("without an enclosing instance"),
                "Expected error for non-static method access from static nested class, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Static nested class accessing outer static field and static method succeeds")
    public void testStaticNestedAccessOuterStaticFieldAndMethodSucceeds() throws Exception {
        String code = """
            public class Outer {
                public static int sField = 100;
                public static int function sMethod() {
                    return 200;
                }
                public static class Nested {
                    public int function compute() {
                        return sField + sMethod();
                    }
                }
            }
            """;
        Map<String, Class<?>> classes = compileAndLoadAll("Outer", code);
        Class<?> nestedClass = classes.get("Outer$Nested");
        assertNotNull(nestedClass);

        Object inst = nestedClass.getDeclaredConstructor().newInstance();
        Method compute = nestedClass.getMethod("compute");
        Object result = compute.invoke(inst);
        assertEquals(300, result);
    }

    @Test
    @DisplayName("Non-static inner class accessing outer instance field and method succeeds")
    public void testNonStaticInnerClassAccessOuterInstanceMembersSucceeds() throws Exception {
        String code = """
            public class Outer {
                public int instanceField = 40;
                public int function instanceMethod() {
                    return 2;
                }
                public class Inner {
                    public int function compute() {
                        return instanceField + instanceMethod();
                    }
                }
            }
            """;
        Map<String, byte[]> bytecodes = compileToBytecodeMap(code, "Outer");
        assertNotNull(bytecodes);
        assertTrue(bytecodes.containsKey("Outer$Inner") || bytecodes.containsKey("Outer/Outer$Inner"));
    }
}
