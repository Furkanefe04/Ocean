package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class P1HighPriorityBugsTest {

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
    public void testP1_1_P1_2_CompoundAssignmentPreservation() throws Exception {
        String code = """
            class Holder {
                int value = 10;
            }

            class CompoundTest {
                static Holder h = new Holder();
                static int callCount = 0;

                static Holder function getHolder() {
                    callCount = callCount + 1;
                    return h;
                }

                static main() {
                    getHolder().value += 5;
                    OceanOutput("VAL:" + h.value + ",CALLS:" + callCount);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "CompoundTest");
        String output = executeMainAndCaptureOutput(classes, "CompoundTest");
        assertEquals("VAL:15,CALLS:1", output);
    }

    @Test
    public void testP1_3_ZeroPlusStringAndPureMul() throws Exception {
        String code = """
            class ConstantFolderTest {
                static int sideEffectCount = 0;

                static int function bump() {
                    sideEffectCount = sideEffectCount + 1;
                    return 5;
                }

                static main() {
                    String s1 = 0 + "abc";
                    String s2 = "xyz" + 0;
                    int r = bump() * 0;
                    OceanOutput(s1 + "," + s2 + ",R:" + r + ",SE:" + sideEffectCount);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ConstantFolderTest");
        String output = executeMainAndCaptureOutput(classes, "ConstantFolderTest");
        assertEquals("0abc,xyz0,R:0,SE:1", output);
    }

    @Test
    public void testP1_4_ShortCircuitSideEffectPreservation() throws Exception {
        String code = """
            class ShortCircuitTest {
                static int effectCount = 0;

                static boolean function trigger() {
                    effectCount = effectCount + 1;
                    return true;
                }

                static main() {
                    boolean b = trigger() && false;
                    OceanOutput("RES:" + b + ",EFFECTS:" + effectCount);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ShortCircuitTest");
        String output = executeMainAndCaptureOutput(classes, "ShortCircuitTest");
        assertEquals("RES:false,EFFECTS:1", output);
    }

    @Test
    public void testP1_5_RangeSliceSingleEvaluation() throws Exception {
        String code = """
            class RangeSliceSideEffectTest {
                static int counter = 0;

                static String function getWord() {
                    counter = counter + 1;
                    return "OceanLang";
                }

                static main() {
                    String sub = getWord()[5..];
                    OceanOutput("SUB:" + sub + ",COUNTER:" + counter);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "RangeSliceSideEffectTest");
        String output = executeMainAndCaptureOutput(classes, "RangeSliceSideEffectTest");
        assertEquals("SUB:Lang,COUNTER:1", output);
    }

    @Test
    public void testP1_7_HexIntegerTypeInference() throws Exception {
        String code = """
            class HexTypeTest {
                static main() {
                    variable a = 0x1E;
                    variable b = 0x0D;
                    variable c = 0xCAFE;
                    int sum = a + b + c;
                    OceanOutput("SUM:" + sum);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "HexTypeTest");
        String output = executeMainAndCaptureOutput(classes, "HexTypeTest");
        // 0x1E = 30, 0x0D = 13, 0xCAFE = 51966. sum = 52009
        assertEquals("SUM:52009", output);
    }

    @Test
    public void testP1_6_SealedRestrictsSamePackage() throws Exception {
        String code = """
            sealed class Shape restricts Circle, Square

            final class Circle extends Shape {}
            final class Square extends Shape {}

            class SealedTest {
                static main() {
                    Shape s = new Circle();
                    OceanOutput("SEALED_OK");
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "SealedTest");
        String output = executeMainAndCaptureOutput(classes, "SealedTest");
        assertEquals("SEALED_OK", output);
    }
}
