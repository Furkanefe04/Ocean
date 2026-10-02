package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CollectionCompoundAssignmentTest {

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
                return super.findClass(name);
            }
        };

        String fqName = classes.containsKey(className + "/" + className) ? className + "." + className : className;
        Class<?> clazz = loader.loadClass(fqName);
        Method mainMethod;
        try {
            mainMethod = clazz.getMethod("main", String[].class);
        } catch (NoSuchMethodException e) {
            mainMethod = clazz.getMethod("main");
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
    public void testOceanIntListCompoundAssignment() throws Exception {
        String code = """
            class IntListCompoundTest {
                static main() {
                    OceanIntList list = [10, 20, 30, 40, 50];
                    list[0] += 5;
                    list[1] -= 3;
                    list[2] *= 4;
                    list[3] /= 2;
                    list[4] %= 3;
                    OceanOutput(list[0] + "," + list[1] + "," + list[2] + "," + list[3] + "," + list[4]);
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "IntListCompoundTest");
        String output = executeMainAndCaptureOutput(classes, "IntListCompoundTest");
        assertEquals("15,17,120,20,2", output);
    }

    @Test
    public void testOceanDoubleListCompoundAssignment() throws Exception {
        String code = """
            class DoubleListCompoundTest {
                static main() {
                    OceanDoubleList list = [10.0, 20.0];
                    list[0] += 2.5;
                    list[1] *= 1.5;
                    OceanOutput(list[0] + "," + list[1]);
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "DoubleListCompoundTest");
        String output = executeMainAndCaptureOutput(classes, "DoubleListCompoundTest");
        assertEquals("12.5,30.0", output);
    }

    @Test
    public void testGenericOceanListStringCompoundAssignment() throws Exception {
        String code = """
            class GenericListCompoundTest {
                static main() {
                    OceanList<String> words = ["Hello"];
                    words[0] += " World";
                    OceanOutput(words[0]);
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "GenericListCompoundTest");
        String output = executeMainAndCaptureOutput(classes, "GenericListCompoundTest");
        assertEquals("Hello World", output);
    }

    @Test
    public void testNativeArrayCompoundAssignmentNoRegression() throws Exception {
        String code = """
            class NativeArrayCompoundTest {
                static main() {
                    int[] arr = [10, 20, 30];
                    arr[0] += 5;
                    arr[1] *= 2;
                    arr[2] -= 10;
                    OceanOutput(arr[0] + "," + arr[1] + "," + arr[2]);
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "NativeArrayCompoundTest");
        String output = executeMainAndCaptureOutput(classes, "NativeArrayCompoundTest");
        assertEquals("15,40,20", output);
    }
}