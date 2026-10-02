package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CompilerCriticalFixesEmpiricalTest {

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
    public void testNaNComparisonLogic() throws Exception {
        String code = """
            class NaNTest {
                static main() {
                    double nan = Double.NaN;
                    boolean gt = nan > 1.0;
                    boolean gte = nan >= 1.0;
                    boolean lt = nan < 1.0;
                    boolean lte = nan <= 1.0;
                    boolean eq = nan == 1.0;
                    boolean neq = nan != 1.0;

                    OceanOutput(gt + "," + gte + "," + lt + "," + lte + "," + eq + "," + neq);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NaNTest");
        String output = executeMainAndCaptureOutput(classes, "NaNTest");
        // Per IEEE-754: any relational comparison with NaN (except !=) must be false
        assertEquals("false,false,false,false,false,true", output);
    }

    @Test
    public void testFinallyIntegrityWithImplicitReturn() throws Exception {
        String code = """
            class FinallyImplicitReturnTest {
                static int function testMethod() {
                    trying {
                        OceanOutput("TRY");
                    } finally {
                        OceanOutput("FINALLY");
                    }
                    return 42;
                }

                static main() {
                    int r = testMethod();
                    OceanOutput("RES:" + r);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "FinallyImplicitReturnTest");
        String output = executeMainAndCaptureOutput(classes, "FinallyImplicitReturnTest");
        assertEquals("TRY\nFINALLY\nRES:42", output.replace("\r\n", "\n"));
    }

    @Test
    public void testSwitchDeduplicationNoVerifyError() throws Exception {
        String code = """
            class SwitchDeduplicationTest {
                static int function eval(int x) {
                    return switch (x) {
                        case 1 -> 100;
                        case 2 -> 200;
                        default -> 999;
                    };
                }

                static main() {
                    OceanOutput(eval(1) + "," + eval(2) + "," + eval(5));
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "SwitchDeduplicationTest");
        String output = executeMainAndCaptureOutput(classes, "SwitchDeduplicationTest");
        assertEquals("100,200,999", output);
    }

    @Test
    public void testOceanDataClassPatternDeconstruction() throws Exception {
        String code = """
            data class Point(int x, int y)

            class DataClassPatternTest {
                static int function sum(Point p) {
                    if (p instanceof Point(int x, int y)) {
                        return x + y;
                    }
                    return -1;
                }

                static main() {
                    Point p = new Point(15, 27);
                    OceanOutput("SUM:" + sum(p));
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "DataClassPatternTest");
        String output = executeMainAndCaptureOutput(classes, "DataClassPatternTest");
        assertEquals("SUM:42", output);
    }

    @Test
    public void testInlinerSideEffectPreservation() throws Exception {
        String code = """
            class InlinerSideEffectTest {
                static int counter = 0;

                private static int function square(int x) {
                    return x * x;
                }

                private static int function next() {
                    counter = counter + 1;
                    return counter;
                }

                static main() {
                    // next() has side-effects; square(x) uses x twice (x * x)
                    // Inliner must not duplicate next() call
                    int res = square(next());
                    OceanOutput("RES:" + res + ",COUNTER:" + counter);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "InlinerSideEffectTest");
        String output = executeMainAndCaptureOutput(classes, "InlinerSideEffectTest");
        assertEquals("RES:1,COUNTER:1", output);
    }
}
