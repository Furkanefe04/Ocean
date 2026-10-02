package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CriticalAndHighIssuesEmpiricalTest {

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

    // --- ISSUE 1: Object Method Resolution ---

    @Test
    public void testCallingNonObjectMethodOnObjectReferenceThrowsCompileError() {
        String code = """
            class ObjectMethodTest {
                static void function main() {
                    Object obj = "Hello";
                    obj.size(); // Should NOT fallback to OceanList.size() or crash with VerifyError
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ObjectMethodTest"));
        assertTrue(ex.getMessage().contains("Cannot resolve") || ex.getMessage().contains("size"),
                "Expected unresolved method error on Object, got: " + ex.getMessage());
    }

    @Test
    public void testCallingLegitimateObjectMethodsOnObjectSucceeds() throws Exception {
        String code = """
            class ValidObjectMethodTest {
                static void function main() {
                    Object obj = "Hello World";
                    String str = obj.toString();
                    int hash = obj.hashCode();
                    boolean eq = obj.equals("Hello World");
                    OceanOutput("str=" + str + ", eq=" + eq);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ValidObjectMethodTest");
        String output = executeMainAndCaptureOutput(classes, "ValidObjectMethodTest");
        assertEquals("str=Hello World, eq=true", output);
    }

    // --- ISSUE 2: Map Type Identification ---

    @Test
    public void testClassStartingWithMapIsNotTreatedAsMapType() {
        // Classes like Mapper, MapService should NOT be classified as Map
        assertFalse(TypeChecker.isMapType("Mapper"), "Mapper should not be considered a Map type");
        assertFalse(TypeChecker.isMapType("MapService"), "MapService should not be considered a Map type");
        assertFalse(TypeChecker.isMapType("MappingStrategy"), "MappingStrategy should not be considered a Map type");
        assertFalse(TypeChecker.isMapType("MapEntry"), "MapEntry should not be considered a Map type");
    }

    @Test
    public void testStandardMapTypesAreCorrectlyIdentified() {
        assertTrue(TypeChecker.isMapType("java/util/Map"), "java/util/Map must be identified as Map");
        assertTrue(TypeChecker.isMapType("java/util/HashMap"), "java/util/HashMap must be identified as Map");
        assertTrue(TypeChecker.isMapType("ocean/stdlib/OceanMap"), "OceanMap must be identified as Map");
    }

    // --- ISSUE 3: Unknown Type Resolution & Typo Detection ---

    @Test
    public void testTypoInOceanStdlibTypeThrowsCompileError() {
        String code = """
            class TypoOceanTypeTest {
                static void function main() {
                    OceanLisst invalidList = null;
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TypoOceanTypeTest"));
        assertTrue(ex.getMessage().contains("Unknown type") || ex.getMessage().contains("OceanLisst"),
                "Expected unknown type error for misspelled OceanLisst, got: " + ex.getMessage());
    }

    @Test
    public void testValidOceanStdlibTypesResolveCleanly() throws Exception {
        String code = """
            class ValidOceanStdlibTypesTest {
                static void function main() {
                    OceanList list = new OceanList();
                    list.add("item");
                    OceanMap map = new OceanMap();
                    map.put("k", "v");
                    OceanOutput("list=" + list.size() + ", map=" + map.size());
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ValidOceanStdlibTypesTest");
        String output = executeMainAndCaptureOutput(classes, "ValidOceanStdlibTypesTest");
        assertEquals("list=1, map=1", output);
    }
}
