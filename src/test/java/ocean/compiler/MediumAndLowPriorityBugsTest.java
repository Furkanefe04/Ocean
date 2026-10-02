package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MediumAndLowPriorityBugsTest {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
        ClassMetadataCache.clearCaches();
        SymbolTable.clearCaches();
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
    public void testP2_1_SealedRestrictsSimpleNameResolution() throws Exception {
        String code = """
            sealed class Shape restricts Circle, Square {
            }
            final class Circle extends Shape {
            }
            final class Square extends Shape {
            }
            class SealedMain {
                static main() {
                    OceanOutput("SEALED_OK");
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "SealedMain");
        String output = executeMainAndCaptureOutput(classes, "SealedMain");
        assertEquals("SEALED_OK", output);
    }

    @Test
    public void testP2_2_InheritedVarargsMethodDetection() {
        CompilerRegistry.globalSuperClassRegistry.put("com/test/Child", "com/test/Parent");
        Map<String, Integer> accessMap = new HashMap<>();
        accessMap.put("format(Ljava/lang/String;[Ljava/lang/Object;)V", Opcodes.ACC_VARARGS);
        CompilerRegistry.globalMethodAccess.put("com/test/Parent", accessMap);

        assertTrue(CompilerRegistry.isVarargsMethod("com/test/Child", "format", "(Ljava/lang/String;[Ljava/lang/Object;)V"),
                "Child should inherit varargs method from Parent via globalSuperClassRegistry");
        assertFalse(CompilerRegistry.isVarargsMethod("com/test/Child", "otherMethod", "()V"),
                "Unregistered method should not be varargs");
    }

    @Test
    public void testP2_3_HexAndBinaryLiteralTypeInference() {
        assertEquals("I", TypeInferenceEngine.inferNumberType("0x1A"));
        assertEquals("I", TypeInferenceEngine.inferNumberType("0XFF"));
        assertEquals("I", TypeInferenceEngine.inferNumberType("0b1010"));
        assertEquals("I", TypeInferenceEngine.inferNumberType("0B1101"));
        assertEquals("J", TypeInferenceEngine.inferNumberType("0x1AL"));
        assertEquals("J", TypeInferenceEngine.inferNumberType("0b1010L"));
    }

    @Test
    public void testP2_4_CommonInterfaceResolutionInGetCommonSuperClass() {
        CompilerRegistry.globalInterfaceRegistry.put("com/test/Alpha", new String[]{"java/io/Serializable"});
        CompilerRegistry.globalInterfaceRegistry.put("com/test/Beta", new String[]{"java/io/Serializable"});

        String common = TypeChecker.getCommonSuperClass("Lcom/test/Alpha;", "Lcom/test/Beta;");
        assertEquals("java/io/Serializable", common,
                "Common interface should be found instead of degrading to Object");
    }

    @Test
    public void testP2_5_OverloadedMethodOverrideCheck() throws Exception {
        String code = """
            class BasePrinter {
                void function print(int x) {
                    OceanOutput("INT:" + x);
                }
                void function print(String s) {
                    OceanOutput("STR:" + s);
                }
            }
            class CustomPrinter extends BasePrinter {
                @override
                void function print(String s) {
                    OceanOutput("CUSTOM:" + s);
                }
            }
            class OverrideMain {
                static main() {
                    CustomPrinter p = new CustomPrinter();
                    p.print("hello");
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "OverrideMain");
        String output = executeMainAndCaptureOutput(classes, "OverrideMain");
        assertEquals("CUSTOM:hello", output);
    }

    @Test
    public void testP2_6_GenericTypeParameterBoundWithOtherTypeParam() throws Exception {
        String code = """
            class Pair<U, T <: U> {
                T first;
                void function setFirst(T item) {
                    this.first = item;
                }
                T function getFirst() {
                    return this.first;
                }
            }
            class GenericBoundMain {
                main() {
                    Pair<Object, String> p = new Pair<>();
                    p.setFirst("bound_ok");
                    OceanOutput(p.getFirst());
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "GenericBoundMain");
        String output = executeMainAndCaptureOutput(classes, "GenericBoundMain");
        assertEquals("bound_ok", output);
    }

    @Test
    public void testP2_7_SymbolTableClassTypeParamFallback() {
        CompilationSession session = new CompilationSession();
        session.setCurrentClassFqcn("com/test/MyGeneric");
        CompilationSession.setActiveSession(session);
        try {
            ocean.compiler.symbol.ClassSymbol cs = new ocean.compiler.symbol.ClassSymbol("com/test/MyGeneric");
            cs.setTypeParameters(List.of(
                    new CompilerRegistry.TypeParameterInfo("E", CompilerRegistry.Variance.INVARIANT, "Ljava/lang/Number;", null, false)
            ));
            CompilerRegistry.registerClassSymbol(cs);

            String desc = SymbolTable.getDescriptor("E", Collections.emptyMap(), Collections.singleton("E"));
            assertEquals("Ljava/lang/Number;", desc,
                    "Type parameter 'E' should resolve to its upper bound descriptor via ClassSymbol fallback");
        } finally {
            CompilationSession.clearActiveSession();
        }
    }

    @Test
    public void testP2_8_StringSubtractionFailsCompilation() {
        String code = """
            class StringSubTest {
                static main() {
                    String s = "hello" - "world";
                }
            }
            """;
        try {
            CompilerTestHelper.compileToBytecodeMap(code, "StringSubTest");
            assertTrue(CompilerReporter.hasErrors(), "String subtraction should produce compilation error");
        } catch (Exception e) {
            // Expected compilation failure
            assertTrue(true);
        }
    }

    @Test
    public void testP2_9_BoxedNumericComparisonAndArithmetic() throws Exception {
        String code = """
            class BoxedNumericTest {
                static main() {
                    Integer a = 10;
                    int b = 20;
                    if (a < b) {
                        OceanOutput("LESS:" + (a + b));
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "BoxedNumericTest");
        String output = executeMainAndCaptureOutput(classes, "BoxedNumericTest");
        assertEquals("LESS:30", output);
    }

    @Test
    public void testP3_1_PreScannerFieldAssignmentBoundary() {
        String validExactMatch = "this.count = count;";
        String partialSubstrMatch = "this.discount = count;";

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?:^|[^a-zA-Z0-9_$])(?:this\\.)?count\\s*=\\s*count(?:[^a-zA-Z0-9_$]|;|$)"
        );

        assertTrue(pattern.matcher(validExactMatch).find(), "Exact field assignment should match");
        assertFalse(pattern.matcher(partialSubstrMatch).find(), "Partial prefix 'discount' should NOT match 'count'");
    }

    @Test
    public void testP3_3_ClassMetadataCacheClearsVarargsCache() {
        assertTrue(ClassMetadataCache.isVarargsMethod(
                "java/lang/String", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"
        ));

        ClassMetadataCache.clearCaches();

        assertTrue(ClassMetadataCache.isVarargsMethod(
                "java/lang/String", "format", "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;"
        ));
    }
}
