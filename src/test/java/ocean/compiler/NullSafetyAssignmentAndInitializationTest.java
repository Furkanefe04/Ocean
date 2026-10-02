package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class NullSafetyAssignmentAndInitializationTest {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    private Map<String, byte[]> compileToBytecodeMap(String code, String className) {
        return CompilerTestHelper.compileToBytecodeMap(code, className);
    }

    private String executeMainAndCaptureOutput(Map<String, byte[]> classes, String className) throws Exception {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                String pathKey = name.replace('.', '/');
                if (classes.containsKey(pathKey)) {
                    byte[] bytes = classes.get(pathKey);
                    return defineClass(name, bytes, 0, bytes.length);
                }
                for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                    if (entry.getKey().endsWith("/" + name) || entry.getKey().equals(name)) {
                        byte[] bytes = entry.getValue();
                        return defineClass(name, bytes, 0, bytes.length);
                    }
                }
                return super.findClass(name);
            }
        };

        String fqName = classes.containsKey(className + "/" + className) ? className + "." + className : className;
        Class<?> clazz = loader.loadClass(fqName);
        Method mainMethod = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals("main")) {
                mainMethod = m;
                break;
            }
        }
        if (mainMethod == null) {
            throw new RuntimeException("No main method found in " + clazz.getName() + ", declared: " + Arrays.toString(clazz.getDeclaredMethods()));
        }
        mainMethod.setAccessible(true);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(baos));
        try {
            if (mainMethod.getParameterCount() == 1) {
                mainMethod.invoke(null, (Object) new String[0]);
            } else {
                mainMethod.invoke(null);
            }
        } finally {
            System.setOut(originalOut);
        }
        return baos.toString().trim();
    }

    @Test
    public void testExplicitNonNullVariableAssignedNullThrowsError() {
        String code = """
            class NonNullVarTest {
                static void function main() {
                    String nonNull = null;
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "NonNullVarTest"));
        assertTrue(ex.getMessage().contains("Uyumsuz tipler") || ex.getMessage().contains("null"),
                "Expected error for assigning null to non-nullable String, got: " + ex.getMessage());
    }

    @Test
    public void testExplicitNonNullVariableAssignedNullableVarThrowsError() {
        String code = """
            class NullableToNonNullVarTest {
                static void function main() {
                    String? maybeStr = "hello";
                    String nonNull = maybeStr;
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "NullableToNonNullVarTest"));
        assertTrue(ex.getMessage().contains("Incompatible types"),
                "Expected error for assigning String? to String, got: " + ex.getMessage());
    }

    @Test
    public void testExplicitNonNullFieldAssignedNullThrowsError() {
        String code = """
            class NonNullFieldTest {
                String name = null;
                static void function main() {
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "NonNullFieldTest"));
        assertTrue(ex.getMessage().contains("Uyumsuz tipler") || ex.getMessage().contains("null"),
                "Expected error for assigning null to non-nullable field, got: " + ex.getMessage());
    }

    @Test
    public void testExplicitNonNullMethodReturnNullThrowsError() {
        String code = """
            class NonNullReturnTest {
                String function getStr() {
                    return null;
                }
                static void function main() {
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "NonNullReturnTest"));
        assertTrue(ex.getMessage().contains("Incompatible return type") || ex.getMessage().contains("null"),
                "Expected error for returning null from non-nullable method, got: " + ex.getMessage());
    }

    @Test
    public void testExplicitNullableVariableAssignedNullSucceeds() throws Exception {
        String code = """
            class NullableVarSuccessTest {
                static void function main() {
                    String? maybeStr = null;
                    OceanOutput("Assigned null successfully");
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NullableVarSuccessTest");
        String output = executeMainAndCaptureOutput(classes, "NullableVarSuccessTest");
        assertEquals("Assigned null successfully", output);
    }

    @Test
    public void testUntypedVariableAssignedNullSucceeds() throws Exception {
        String code = """
            class UntypedVarNullSuccessTest {
                static void function main() {
                    variable maybeVar = null;
                    OceanOutput("Untyped null success");
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "UntypedVarNullSuccessTest");
        String output = executeMainAndCaptureOutput(classes, "UntypedVarNullSuccessTest");
        assertEquals("Untyped null success", output);
    }

    @Test
    public void testSmartCastNarrowsToNonNullSucceeds() throws Exception {
        String code = """
            class SmartCastNonNullTest {
                static void function main() {
                    String? maybeStr = "OceanLanguage";
                    if (maybeStr != null) {
                        String nonNull = maybeStr;
                        OceanOutput(nonNull);
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "SmartCastNonNullTest");
        String output = executeMainAndCaptureOutput(classes, "SmartCastNonNullTest");
        assertEquals("OceanLanguage", output);
    }

    @Test
    public void testNullCoalescingToNonNullSucceeds() throws Exception {
        String code = """
            class CoalescingNonNullTest {
                static void function main() {
                    String? maybeStr = null;
                    String nonNull = maybeStr ?? "FallbackValue";
                    OceanOutput(nonNull);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "CoalescingNonNullTest");
        String output = executeMainAndCaptureOutput(classes, "CoalescingNonNullTest");
        assertEquals("FallbackValue", output);
    }

    @Test
    public void testPrimitiveNullableTypes() throws Exception {
        String code = """
            class PrimitiveNullableTest {
                static void function main() {
                    int? maybeInt = null;
                    int result = maybeInt ?? 42;
                    OceanOutput(result);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "PrimitiveNullableTest");
        String output = executeMainAndCaptureOutput(classes, "PrimitiveNullableTest");
        assertEquals("42", output);
    }
}
