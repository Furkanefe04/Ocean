package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SafeCallNullSafetyCompileErrorTest {

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
            throw new RuntimeException("No main method found in " + clazz.getName());
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
    public void testSafeCallToNonNullablePrimitiveThrowsCompileError() {
        String code = """
            class SafeCallNonNullablePrimitiveTest {
                static void function main() {
                    String? s = "hello";
                    int len = s?.length();
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SafeCallNonNullablePrimitiveTest"));
        assertTrue(ex.getMessage().contains("Incompatible types"),
                "Expected incompatible types error for assigning safe call directly to primitive int, got: " + ex.getMessage());
    }

    @Test
    public void testSafeCallWithNullCoalescingToPrimitiveSucceeds() throws Exception {
        String code = """
            class SafeCallCoalescingTest {
                static void function main() {
                    String s = "hello";
                    int len = s?.length() ?? 0;
                    String? n = null;
                    int nlen = n?.length() ?? 0;
                    OceanOutput("len=" + len + ", nlen=" + nlen);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "SafeCallCoalescingTest");
        String output = executeMainAndCaptureOutput(classes, "SafeCallCoalescingTest");
        assertEquals("len=5, nlen=0", output);
    }

    @Test
    public void testSafeCallToNullablePrimitiveVariableSucceeds() throws Exception {
        String code = """
            class SafeCallNullablePrimitiveVarTest {
                static void function main() {
                    String? n = null;
                    int? nlen = n?.length();
                    OceanOutput("nlen is null: " + (nlen == null));
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "SafeCallNullablePrimitiveVarTest");
        String output = executeMainAndCaptureOutput(classes, "SafeCallNullablePrimitiveVarTest");
        assertEquals("nlen is null: true", output);
    }

    @Test
    public void testSafeCallToNonNullableReferenceThrowsCompileError() {
        String code = """
            class SafeCallNonNullableRefTest {
                static void function main() {
                    String? s = "hello";
                    String sub = s?.substring(1);
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SafeCallNonNullableRefTest"));
        assertTrue(ex.getMessage().contains("Incompatible types"),
                "Expected incompatible types error for assigning safe call directly to non-nullable String, got: " + ex.getMessage());
    }
}
