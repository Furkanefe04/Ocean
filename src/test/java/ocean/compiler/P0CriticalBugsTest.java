package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class P0CriticalBugsTest {

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
        assertNotNull(mainMethod, "No main method found in " + clazz.getName());
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
    public void testP0_1_ShiftOperatorTypes() throws Exception {
        String code = """
            class ShiftTest {
                static main() {
                    long a = 1L << 2;
                    long b = 8L >> 1;
                    int c = 1 << 2;
                    OceanOutput(a + "," + b + "," + c);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ShiftTest");
        String output = executeMainAndCaptureOutput(classes, "ShiftTest");
        assertEquals("4,4,4", output);
    }

    @Test
    public void testP0_2_StringConcatBinaryOp() throws Exception {
        String code = """
            class ConcatTest {
                static main() {
                    String s1 = "val: " + 123;
                    String s2 = 456 + " is number";
                    OceanOutput(s1 + ";" + s2);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ConcatTest");
        String output = executeMainAndCaptureOutput(classes, "ConcatTest");
        assertEquals("val: 123;456 is number", output);
    }

    @Test
    public void testP0_4_P0_5_TryFinallyReturnSemantics() throws Exception {
        String code = """
            class TryFinallyReturnTest {
                static int function testFinally() {
                    trying {
                        return 10;
                    } finally {
                        OceanOutput("FINALLY_RAN");
                    }
                }

                static main() {
                    int res = testFinally();
                    OceanOutput("RES:" + res);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TryFinallyReturnTest");
        String output = executeMainAndCaptureOutput(classes, "TryFinallyReturnTest");
        assertEquals("FINALLY_RAN\nRES:10", output.replace("\r\n", "\n"));
    }

    @Test
    public void testP0_6_ReferenceToPrimitiveUnboxing() throws Exception {
        String code = """
            class UnboxTest {
                static main() {
                    Object num = 99;
                    int i = num;
                    OceanOutput("UNBOX:" + i);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "UnboxTest");
        String output = executeMainAndCaptureOutput(classes, "UnboxTest");
        assertEquals("UNBOX:99", output);
    }

    @Test
    public void testP0_7_SafeNavigationFieldAccess() throws Exception {
        String code = """
            class Person {
                String name;
                Person(String name) {
                    this.name = name;
                }
            }

            class SafeNavTest {
                static main() {
                    Person p = new Person("Ocean");
                    String? n = p?.name;
                    Person? nullP = null;
                    String? nullN = nullP?.name;
                    OceanOutput(n + "," + nullN);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "SafeNavTest");
        String output = executeMainAndCaptureOutput(classes, "SafeNavTest");
        assertEquals("Ocean,null", output);
    }

    @Test
    public void testP0_8_P0_9_LookupSwitchUnsortedKeys() throws Exception {
        String code = """
            class UnsortedSwitchTest {
                static String function eval(int x) {
                    switch (x) {
                        case 100: return "hundred";
                        case 10: return "ten";
                        case 50: return "fifty";
                        default: return "other";
                    }
                }

                static main() {
                    OceanOutput(eval(10) + "," + eval(50) + "," + eval(100) + "," + eval(1));
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "UnsortedSwitchTest");
        String output = executeMainAndCaptureOutput(classes, "UnsortedSwitchTest");
        assertEquals("ten,fifty,hundred,other", output);
    }

    @Test
    public void testP0_10_NewObjectSimpleName() throws Exception {
        String code = """
            class Helper {
                int val;
                Helper(int val) {
                    this.val = val;
                }
            }

            class NewObjectTest {
                static main() {
                    Helper h = new Helper(42);
                    OceanOutput("VAL:" + h.val);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NewObjectTest");
        String output = executeMainAndCaptureOutput(classes, "NewObjectTest");
        assertEquals("VAL:42", output);
    }

    @Test
    public void testP0_12_ArrayStoreLongOpcode() throws Exception {
        String code = """
            class LongArrayTest {
                static main() {
                    long[] arr = new long[3];
                    arr[0] = 100L;
                    arr[1] = 200L;
                    arr[2] = arr[0] + arr[1];
                    OceanOutput(arr[0] + "," + arr[1] + "," + arr[2]);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "LongArrayTest");
        String output = executeMainAndCaptureOutput(classes, "LongArrayTest");
        assertEquals("100,200,300", output);
    }
}
