package ocean.compiler;

import ocean.compiler.ir.IRClass;
import ocean.compiler.ir.IRDeadCodeEliminator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CompilerHighPriorityFixesEmpiricalTest {

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
    public void testArraySubtypingSerializableAndCloneable() {
        assertTrue(TypeChecker.isAssignable("Ljava/io/Serializable;", "[I", null));
        assertTrue(TypeChecker.isAssignable("Ljava/lang/Cloneable;", "[I", null));
        assertTrue(TypeChecker.isAssignable("Ljava/io/Serializable;", "[Ljava/lang/String;", null));
        assertTrue(TypeChecker.isAssignable("Ljava/lang/Cloneable;", "[Ljava/lang/String;", null));
    }

    @Test
    public void testIRDeadCodeEliminatorPreservesDataClassFlag() {
        IRClass cls = new IRClass("com/example/MyData", "java/lang/Object", false);
        cls.setDataClass(true);
        assertTrue(cls.isDataClass());

        IRDeadCodeEliminator dce = new IRDeadCodeEliminator();
        IRClass optimized = (IRClass) dce.optimize(cls);

        assertTrue(optimized.isDataClass());
    }

    @Test
    public void testClearLInPrimitiveRobustness() {
        assertEquals("I", OceanTypeSystem.clearLInPrimitive("Lint;"));
        assertEquals("I", OceanTypeSystem.clearLInPrimitive("LI;"));
        assertEquals("Z", OceanTypeSystem.clearLInPrimitive("Lboolean;"));
        assertEquals("Z", OceanTypeSystem.clearLInPrimitive("LZ;"));
        assertEquals("J", OceanTypeSystem.clearLInPrimitive("Llong;"));
        assertEquals("D", OceanTypeSystem.clearLInPrimitive("Ldouble;"));
        assertEquals("V", OceanTypeSystem.clearLInPrimitive("Lvoid;"));
        assertEquals("Ljava/lang/String;", OceanTypeSystem.clearLInPrimitive("Ljava/lang/String;"));
    }

    @Test
    public void testLengthFieldAccessOnCustomObject() throws Exception {
        String code = """
                class Meter {
                    public int length = 42;
                }

                class LengthTest {
                    static main() {
                        Meter m = new Meter();
                        int len = m.length;
                        int? safeLen = m?.length;
                        OceanOutput(len + safeLen);
                    }
                }
                """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "LengthTest");
        assertNotNull(classes);
        assertFalse(CompilerReporter.hasErrors());
        String out = executeMainAndCaptureOutput(classes, "LengthTest");
        assertEquals("84", out);
    }
}
