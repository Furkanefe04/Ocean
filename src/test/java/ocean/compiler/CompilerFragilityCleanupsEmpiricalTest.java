package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CompilerFragilityCleanupsEmpiricalTest {

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

    /**
     * Issue 3: Nested generic element access (List<OceanMap<String, Integer>>)
     * ensures IRSemanticAnalyzer doesn't corrupt element type with lastIndexOf(',').
     */
    @Test
    public void testIssue3NestedGenericElementAccess() throws Exception {
        String code = """
            class NestedGenTest {
                static void function main() {
                    OceanList<OceanMap<String, Integer>> list = [];
                    OceanMap<String, Integer> m = new OceanMap();
                    m.put("score", 42);
                    list.add(m);
                    OceanMap<String, Integer> item = (OceanMap<String, Integer>) list[0];
                    OceanOutput("score=" + item.get("score"));
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NestedGenTest");
        assertNotNull(classes, "Compilation should succeed for nested generic list access");
        String output = executeMainAndCaptureOutput(classes, "NestedGenTest");
        assertEquals("score=42", output);
    }

    /**
     * Issue 4: IRGenerator.getGenericValueType and TypeInferenceEngine
     * should not mistake Bitmap<W, H> or Mapper<A, B> for a Map, and should correctly resolve OceanMap.
     */
    @Test
    public void testIssue4GenericValueTypeExtraction() {
        // Bitmap has "Map" in its name but is not a Map -> returns 1st argument (Width), not 2nd (Height)
        String bitmapType = "Lcom/graphics/Bitmap<Ljava/lang/Integer;,Ljava/lang/String;>;";
        String bitmapVal = IRGenerator.getGenericValueType(bitmapType);
        assertEquals("Ljava/lang/Integer;", bitmapVal);

        // OceanMap is a real Map -> returns 2nd argument (Value)
        String mapType = "Locean/stdlib/OceanMap<Ljava/lang/String;,Ljava/lang/Integer;>;";
        String mapVal = IRGenerator.getGenericValueType(mapType);
        assertEquals("Ljava/lang/Integer;", mapVal);

        // Standard java.util.Map -> returns 2nd argument (Value)
        String javaMapType = "Ljava/util/Map<Ljava/lang/String;,Ljava/lang/Double;>;";
        String javaMapVal = IRGenerator.getGenericValueType(javaMapType);
        assertEquals("Ljava/lang/Double;", javaMapVal);
    }

    /**
     * Issue 5: Custom annotations like @CustomOverride or @TargetPlatform
     * must not be falsely treated as @Override or @Target.
     */
    @Test
    public void testIssue5CustomAnnotationsNotConfusedWithStandardMetaAnnotations() throws Exception {
        String code = """
            public annotation CustomOverride {
            }

            class AnnotationCustomTest {
                @CustomOverride
                static void function helper() {
                    OceanOutput("custom-anno-ok");
                }

                static void function main() {
                    helper();
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "AnnotationCustomTest");
        assertNotNull(classes, "Custom @CustomOverride on static method should compile without false @Override error");
        String output = executeMainAndCaptureOutput(classes, "AnnotationCustomTest");
        assertEquals("custom-anno-ok", output);
    }

    /**
     * Issue 6: TypeChecker.isSetType recognizes standard Set implementations
     * and does not falsely match BitSet, DataSet, RuleSet.
     */
    @Test
    public void testIssue6SetTypeRecognitionNotOverbroad() {
        assertTrue(TypeChecker.isSetType("Locean/stdlib/OceanSet<Ljava/lang/String;>;"));
        assertTrue(TypeChecker.isSetType("Ljava/util/Set<Ljava/lang/String;>;"));
        assertTrue(TypeChecker.isSetType("Ljava/util/HashSet;"));

        // Domain classes ending in "Set" must not be recognized as Sets
        assertFalse(TypeChecker.isSetType("Ljava/util/BitSet;"));
        assertFalse(TypeChecker.isSetType("BitSet"));
        assertFalse(TypeChecker.isSetType("RuleSet"));
        assertFalse(TypeChecker.isSetType("DataSet"));
    }

    /**
     * Issue 7: TypeChecker.isSpecializedPrimitiveList recognizes Ocean primitive lists
     * and does not falsely match PrintList or PointList.
     */
    @Test
    public void testIssue7SpecializedPrimitiveListChecks() {
        assertTrue(TypeChecker.isSpecializedPrimitiveList("Locean/stdlib/OceanIntList;"));
        assertTrue(TypeChecker.isSpecializedPrimitiveList("OceanIntList"));
        assertTrue(TypeChecker.isSpecializedPrimitiveList("Locean/stdlib/OceanDoubleList;"));

        // User classes containing "IntList" must not be recognized as specialized primitive list
        assertFalse(TypeChecker.isSpecializedPrimitiveList("PrintList"));
        assertFalse(TypeChecker.isSpecializedPrimitiveList("Lcom/app/PrintList;"));
        assertFalse(TypeChecker.isSpecializedPrimitiveList("PointList"));
        assertFalse(TypeChecker.isSpecializedPrimitiveList("Lcom/app/PointList;"));
    }

    /**
     * Issue 8: TypeChecker.isBigDecimalType accurately identifies BigDecimal
     * and does not match helper classes like BigDecimalFormatter.
     */
    @Test
    public void testIssue8BigDecimalTypeExactChecks() {
        assertTrue(TypeChecker.isBigDecimalType("Ljava/math/BigDecimal;"));
        assertTrue(TypeChecker.isBigDecimalType("java/math/BigDecimal"));

        assertFalse(TypeChecker.isBigDecimalType("BigDecimalFormatter"));
        assertFalse(TypeChecker.isBigDecimalType("Lcom/util/BigDecimalFormatter;"));
        assertFalse(TypeChecker.isBigDecimalType("BigDecimalParser"));
    }
}
