package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ArrayFormattingPrintTest {

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
        return baos.toString().trim().replace("\r\n", "\n");
    }

    @Test
    public void testPrimitiveIntArrayPrint() throws Exception {
        String code = """
                class TestIntArr {
                    static main() {
                        int[] nums = [1, 2, 3];
                        OceanOutput(nums);
                    }
                }
                """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TestIntArr");
        String output = executeMainAndCaptureOutput(classes, "TestIntArr");
        assertEquals("[1, 2, 3]", output);
    }

    @Test
    public void testPrimitiveDoubleAndBoolArrayPrint() throws Exception {
        String code = """
                class TestDoubleArr {
                    static main() {
                        double[] d = new double[2];
                        d[0] = 1.5;
                        d[1] = 2.5;
                        boolean[] b = new boolean[2];
                        b[0] = true;
                        b[1] = false;
                        OceanOutput(d);
                        OceanOutput(b);
                    }
                }
                """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TestDoubleArr");
        String output = executeMainAndCaptureOutput(classes, "TestDoubleArr");
        assertEquals("[1.5, 2.5]\n[true, false]", output);
    }

    @Test
    public void testMultiDimensionalArrayPrint() throws Exception {
        String code = """
                class TestMatrix {
                    static main() {
                        int[][] matrix = new int[2][2];
                        matrix[0][0] = 1;
                        matrix[0][1] = 2;
                        matrix[1][0] = 3;
                        matrix[1][1] = 4;
                        OceanOutput(matrix);
                    }
                }
                """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TestMatrix");
        String output = executeMainAndCaptureOutput(classes, "TestMatrix");
        assertEquals("[[1, 2], [3, 4]]", output);
    }

    @Test
    public void testObjectArrayPrint() throws Exception {
        String code = """
                class TestStrArr {
                    static main() {
                        String[] words = new String[2];
                        words[0] = "apple";
                        words[1] = "banana";
                        OceanOutput(words);
                    }
                }
                """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TestStrArr");
        String output = executeMainAndCaptureOutput(classes, "TestStrArr");
        assertEquals("[apple, banana]", output);
    }

    @Test
    public void testMultiArgOceanOutputWithArray() throws Exception {
        String code = """
                class TestMultiArg {
                    static main() {
                        int[] nums = [10, 20, 30];
                        OceanOutput("Sayilar:", nums);
                    }
                }
                """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TestMultiArg");
        String output = executeMainAndCaptureOutput(classes, "TestMultiArg");
        assertEquals("Sayilar: [10, 20, 30]", output);
    }

    @Test
    public void testStringInterpolationWithArray() throws Exception {
        String code = """
                class TestInterp {
                    static main() {
                        int[] nums = [7, 8, 9];
                        OceanOutput($"Deger: {nums}");
                    }
                }
                """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TestInterp");
        String output = executeMainAndCaptureOutput(classes, "TestInterp");
        assertEquals("Deger: [7, 8, 9]", output);
    }

    @Test
    public void testStringConcatWithArray() throws Exception {
        String code = """
                class TestConcat {
                    static main() {
                        int[] nums = [4, 5, 6];
                        String s = "Array: " + nums;
                        OceanOutput(s);
                    }
                }
                """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TestConcat");
        String output = executeMainAndCaptureOutput(classes, "TestConcat");
        assertEquals("Array: [4, 5, 6]", output);
    }

    @Test
    public void testArrayToStringMethodCall() throws Exception {
        String code = """
                class TestToString {
                    static main() {
                        int[] nums = [100, 200];
                        String s = nums.toString();
                        OceanOutput(s);
                    }
                }
                """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TestToString");
        String output = executeMainAndCaptureOutput(classes, "TestToString");
        assertEquals("[100, 200]", output);
    }

    @Test
    public void testObjectTypedArrayPrint() throws Exception {
        String code = """
                class TestObjTyped {
                    static main() {
                        Object o = [11, 22];
                        OceanOutput(o);
                    }
                }
                """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TestObjTyped");
        String output = executeMainAndCaptureOutput(classes, "TestObjTyped");
        assertEquals("[11, 22]", output);
    }

    @Test
    public void testDirectArrayInOceanOutput() throws Exception {
        String code = """
                class TestDirect {
                    static main() {
                        OceanOutput([1, 2, 3]);
                        OceanOutput(new int[3]);
                        Output([4, 5]);
                        Output(new int[2]);
                        OceanOutput({1, 2, 3});
                    }
                }
                """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TestDirect");
        String output = executeMainAndCaptureOutput(classes, "TestDirect");
        assertEquals("[1, 2, 3]\n[0, 0, 0]\n[4, 5]\n[0, 0]\n[1, 2, 3]", output);
    }
}

