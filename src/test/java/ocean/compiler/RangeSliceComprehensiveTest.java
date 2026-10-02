package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class RangeSliceComprehensiveTest {

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
    public void testNativeIntArrayFullAndOpenEnded() throws Exception {
        String code = """
            class NativeIntArraySliceTest {
                static function main() {
                    int[] arr = [10, 20, 30, 40, 50];
                    int[] sub1 = arr[1..3];
                    int[] sub2 = arr[..2];
                    int[] sub3 = arr[3..];
                    int[] sub4 = arr[..];

                    for (int x in sub1) OceanOutput(x);
                    OceanOutput("---");
                    for (int x in sub2) OceanOutput(x);
                    OceanOutput("---");
                    for (int x in sub3) OceanOutput(x);
                    OceanOutput("---");
                    for (int x in sub4) OceanOutput(x);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NativeIntArraySliceTest");
        String output = executeMainAndCaptureOutput(classes, "NativeIntArraySliceTest");
        String expected = """
            20
            30
            40
            ---
            10
            20
            30
            ---
            40
            50
            ---
            10
            20
            30
            40
            50""".replace("\r\n", "\n");
        assertEquals(expected, output.replace("\r\n", "\n"));
    }

    @Test
    public void testNativeStringArrayFullAndOpenEnded() throws Exception {
        String code = """
            class NativeStringArraySliceTest {
                static function main() {
                    String[] arr = new String[4];
                    arr[0] = "apple";
                    arr[1] = "banana";
                    arr[2] = "cherry";
                    arr[3] = "date";

                    String[] sub1 = arr[1..2];
                    String[] sub2 = arr[..1];
                    String[] sub3 = arr[2..];
                    String[] sub4 = arr[..];

                    for (String s in sub1) OceanOutput(s);
                    OceanOutput("---");
                    for (String s in sub2) OceanOutput(s);
                    OceanOutput("---");
                    for (String s in sub3) OceanOutput(s);
                    OceanOutput("---");
                    for (String s in sub4) OceanOutput(s);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NativeStringArraySliceTest");
        String output = executeMainAndCaptureOutput(classes, "NativeStringArraySliceTest");
        String expected = """
            banana
            cherry
            ---
            apple
            banana
            ---
            cherry
            date
            ---
            apple
            banana
            cherry
            date""".replace("\r\n", "\n");
        assertEquals(expected, output.replace("\r\n", "\n"));
    }

    @Test
    public void testStringSliceFullAndOpenEnded() throws Exception {
        String code = """
            class StringSliceFullTest {
                static function main() {
                    String s = "Hello Ocean";
                    OceanOutput(s[6..10]);
                    OceanOutput(s[..4]);
                    OceanOutput(s[6..]);
                    OceanOutput(s[..]);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "StringSliceFullTest");
        String output = executeMainAndCaptureOutput(classes, "StringSliceFullTest");
        String expected = """
            Ocean
            Hello
            Ocean
            Hello Ocean""".replace("\r\n", "\n");
        assertEquals(expected, output.replace("\r\n", "\n"));
    }

    @Test
    public void testOceanListSliceFullAndOpenEnded() throws Exception {
        String code = """
            import java.util.List;
            class OceanListSliceFullTest {
                static function main() {
                    List<String> list = ["alpha", "beta", "gamma", "delta"];
                    List<String> sub1 = list[1..2];
                    List<String> sub2 = list[..1];
                    List<String> sub3 = list[2..];

                    for (String s in sub1) OceanOutput(s);
                    OceanOutput("---");
                    for (String s in sub2) OceanOutput(s);
                    OceanOutput("---");
                    for (String s in sub3) OceanOutput(s);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "OceanListSliceFullTest");
        String output = executeMainAndCaptureOutput(classes, "OceanListSliceFullTest");
        String expected = """
            beta
            gamma
            ---
            alpha
            beta
            ---
            gamma
            delta""".replace("\r\n", "\n");
        assertEquals(expected, output.replace("\r\n", "\n"));
    }

    @Test
    public void testOceanIntListSpecializedSlice() throws Exception {
        String code = """
            import ocean.stdlib.OceanIntList;
            class OceanIntListSpecializedSliceTest {
                static function main() {
                    OceanIntList list = [100, 200, 300, 400, 500];
                    OceanIntList sub1 = list[1..3];
                    OceanIntList sub2 = list[..2];
                    OceanIntList sub3 = list[3..];

                    for (int x in sub1) OceanOutput(x);
                    OceanOutput("---");
                    for (int x in sub2) OceanOutput(x);
                    OceanOutput("---");
                    for (int x in sub3) OceanOutput(x);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "OceanIntListSpecializedSliceTest");
        String output = executeMainAndCaptureOutput(classes, "OceanIntListSpecializedSliceTest");
        String expected = """
            200
            300
            400
            ---
            100
            200
            300
            ---
            400
            500""".replace("\r\n", "\n");
        assertEquals(expected, output.replace("\r\n", "\n"));
    }

    @Test
    public void testOceanDoubleListSpecializedSlice() throws Exception {
        String code = """
            import ocean.stdlib.OceanDoubleList;
            class OceanDoubleListSpecializedSliceTest {
                static function main() {
                    OceanDoubleList list = [1.5, 2.5, 3.5, 4.5];
                    OceanDoubleList sub1 = list[1..2];
                    OceanDoubleList sub2 = list[2..];

                    for (double d in sub1) OceanOutput(d);
                    OceanOutput("---");
                    for (double d in sub2) OceanOutput(d);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "OceanDoubleListSpecializedSliceTest");
        String output = executeMainAndCaptureOutput(classes, "OceanDoubleListSpecializedSliceTest");
        String expected = """
            2.5
            3.5
            ---
            3.5
            4.5""".replace("\r\n", "\n");
        assertEquals(expected, output.replace("\r\n", "\n"));
    }

    @Test
    public void testExpressionEndpoints() throws Exception {
        String code = """
            class ExpressionEndpointsTest {
                static function main() {
                    int[] arr = [1, 2, 3, 4, 5, 6, 7, 8];
                    int a = 1;
                    int b = 3;
                    int[] sub = arr[a * 2 .. b + 2];
                    for (int x in sub) {
                        OceanOutput(x);
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ExpressionEndpointsTest");
        String output = executeMainAndCaptureOutput(classes, "ExpressionEndpointsTest");
        String expected = """
            3
            4
            5
            6""".replace("\r\n", "\n");
        assertEquals(expected, output.replace("\r\n", "\n"));
    }

    @Test
    public void testChainedSlicing() throws Exception {
        String code = """
            class ChainedSlicingTest {
                static function main() {
                    String s = "0123456789";
                    String sub = s[2..8][1..4];
                    OceanOutput(sub);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ChainedSlicingTest");
        String output = executeMainAndCaptureOutput(classes, "ChainedSlicingTest");
        assertEquals("3456", output);
    }
}