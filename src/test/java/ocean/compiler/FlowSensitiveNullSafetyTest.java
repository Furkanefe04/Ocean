package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FlowSensitiveNullSafetyTest {

    @BeforeEach
    public void setup() {
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

        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(baos));
            if (mainMethod.getParameterCount() == 1) {
                mainMethod.invoke(null, (Object) new String[]{});
            } else {
                mainMethod.invoke(null);
            }
        } finally {
            System.setOut(originalOut);
        }

        return baos.toString().trim();
    }

    @Test
    public void testPositiveNullCheckSmartCast() throws Exception {
        String code = """
            class PositiveNullCheckTest {
                public static void function main(String[] args) {
                    String? s = "OceanLanguage";
                    if (s != null) {
                        int len = s.length();
                        OceanOutput("LEN: " + len);
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "PositiveNullCheckTest");
        String output = executeMainAndCaptureOutput(classes, "PositiveNullCheckTest");
        assertEquals("LEN: 13", output);
    }

    @Test
    public void testNegativeNullCheckElseSmartCast() throws Exception {
        String code = """
            class NegativeNullCheckElseTest {
                public static void function main(String[] args) {
                    String? s = "FastOcean";
                    if (s == null) {
                        OceanOutput("NULL");
                    } else {
                        int len = s.length();
                        OceanOutput("ELSE_LEN: " + len);
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NegativeNullCheckElseTest");
        String output = executeMainAndCaptureOutput(classes, "NegativeNullCheckElseTest");
        assertEquals("ELSE_LEN: 9", output);
    }

    @Test
    public void testGuardClauseReturnSmartCast() throws Exception {
        String code = """
            class GuardClauseReturnTest {
                public int function getLength(String? s) {
                    if (s == null) {
                        return -1;
                    }
                    return s.length();
                }
                public static void function main(String[] args) {
                    GuardClauseReturnTest t = new GuardClauseReturnTest();
                    OceanOutput(t.getLength("GuardClause") + ":" + t.getLength(null));
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "GuardClauseReturnTest");
        String output = executeMainAndCaptureOutput(classes, "GuardClauseReturnTest");
        assertEquals("11:-1", output);
    }

    @Test
    public void testCompoundLogicalAndSmartCast() throws Exception {
        String code = """
            class CompoundLogicalAndTest {
                public static void function main(String[] args) {
                    String? s = "HelloOcean";
                    if (s != null && s.length() > 5) {
                        OceanOutput("LONG_STR: " + s.toUpperCase());
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "CompoundLogicalAndTest");
        String output = executeMainAndCaptureOutput(classes, "CompoundLogicalAndTest");
        assertEquals("LONG_STR: HELLOOCEAN", output);
    }

    @Test
    public void testTernarySmartCast() throws Exception {
        String code = """
            class TernarySmartCastTest {
                public static void function main(String[] args) {
                    String? s = "TernaryFlow";
                    String res = (s != null) ? s.toUpperCase() : "FALLBACK";
                    OceanOutput(res);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TernarySmartCastTest");
        String output = executeMainAndCaptureOutput(classes, "TernarySmartCastTest");
        assertEquals("TERNARYFLOW", output);
    }

    @Test
    public void testWhileLoopSmartCast() throws Exception {
        String code = """
            class WhileLoopSmartCastTest {
                public static void function main(String[] args) {
                    String? s = "LoopOnce";
                    int count = 0;
                    while (s != null) {
                        count = count + s.length();
                        s = null;
                    }
                    OceanOutput("COUNT: " + count);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "WhileLoopSmartCastTest");
        String output = executeMainAndCaptureOutput(classes, "WhileLoopSmartCastTest");
        assertEquals("COUNT: 8", output);
    }

    @Test
    public void testGuardClauseThrowSmartCast() throws Exception {
        String code = """
            class GuardClauseThrowTest {
                public String function process(String? s) {
                    if (s == null) {
                        throw new RuntimeException("Null value");
                    }
                    return s.toUpperCase();
                }
                public static void function main(String[] args) {
                    GuardClauseThrowTest t = new GuardClauseThrowTest();
                    OceanOutput(t.process("SafeOcean"));
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "GuardClauseThrowTest");
        String output = executeMainAndCaptureOutput(classes, "GuardClauseThrowTest");
        assertEquals("SAFEOCEAN", output);
    }

    @Test
    public void testNestedIfSmartCast() throws Exception {
        String code = """
            class NestedIfSmartCastTest {
                public static void function main(String[] args) {
                    String? a = "First";
                    String? b = "Second";
                    if (a != null) {
                        if (b != null) {
                            OceanOutput(a.length() + ":" + b.length());
                        }
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NestedIfSmartCastTest");
        String output = executeMainAndCaptureOutput(classes, "NestedIfSmartCastTest");
        assertEquals("5:6", output);
    }

    @Test
    public void testLogicalOrNegationSmartCast() throws Exception {
        String code = """
            class LogicalOrNegationTest {
                public static void function main(String[] args) {
                    String? s = "NegationCheck";
                    if (!(s == null)) {
                        OceanOutput("VALID: " + s.length());
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "LogicalOrNegationTest");
        String output = executeMainAndCaptureOutput(classes, "LogicalOrNegationTest");
        assertEquals("VALID: 13", output);
    }
}