package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class GenericBoundsAndHeuristicsCleanupsEmpiricalTest {

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

    // --- GENERIC BOUNDS: Method-Level Generic Bounds ---

    @Test
    public void testMethodLevelSingleBoundResolvesBoundMethod() throws Exception {
        String code = """
            class GenericMathTest {
                static <T <: Number> double function addAsDouble(T a, T b) {
                    return a.doubleValue() + b.doubleValue();
                }

                static void function main() {
                    double sum = addAsDouble(10.5, 20.5);
                    OceanOutput("sum=" + sum);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "GenericMathTest");
        String output = executeMainAndCaptureOutput(classes, "GenericMathTest");
        assertEquals("sum=31.0", output);
    }

    // --- GENERIC BOUNDS: Class Hierarchy Generic Field Bound ---

    @Test
    public void testGenericClassInheritedFieldBoundResolvesBoundMethod() throws Exception {
        String code = """
            abstract class Node<T> {
                protected T value;
                public function Node(T v) {
                    this.value = v;
                }
            }

            class NumberNode<T <: Number> extends Node<T> {
                public function NumberNode(T v) {
                    super(v);
                }

                public double function toDouble() {
                    return this.value.doubleValue();
                }
            }

            class InheritedGenericFieldTest {
                static void function main() {
                    NumberNode<Double> node = new NumberNode<Double>(42.5);
                    OceanOutput("val=" + node.toDouble());
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "InheritedGenericFieldTest");
        String output = executeMainAndCaptureOutput(classes, "InheritedGenericFieldTest");
        assertEquals("val=42.5", output);
    }

    // --- ISSUE 4: List / Collection Semantic Identification ---

    @Test
    public void testUserClassEndingWithListIsNotTreatedAsListType() {
        assertFalse(TypeChecker.isListOrCollectionType("PlayList"), "PlayList should not be considered a List type");
        assertFalse(TypeChecker.isListOrCollectionType("PriceList"), "PriceList should not be considered a List type");
        assertFalse(TypeChecker.isListOrCollectionType("WhiteList"), "WhiteList should not be considered a List type");
        assertFalse(TypeChecker.isListOrCollectionType("CheckList"), "CheckList should not be considered a List type");
    }

    @Test
    public void testStandardAndSpecializedListsAreCorrectlyIdentified() {
        assertTrue(TypeChecker.isListOrCollectionType("java/util/List"), "List must be identified");
        assertTrue(TypeChecker.isListOrCollectionType("java/util/ArrayList"), "ArrayList must be identified");
        assertTrue(TypeChecker.isListOrCollectionType("ocean/stdlib/OceanList"), "OceanList must be identified");
        assertTrue(TypeChecker.isListOrCollectionType("OceanList"), "OceanList must be identified");
        assertTrue(TypeChecker.isListOrCollectionType("ocean/stdlib/OceanIntList"), "OceanIntList must be identified");
        assertTrue(TypeChecker.isListOrCollectionType("OceanIntList"), "OceanIntList must be identified");
    }

    // --- ISSUE B: Mandatory Cast on Object ---

    @Test
    public void testCallingMethodDirectlyOnObjectWithoutCastThrowsCompileError() {
        String code = """
            class ObjectDirectCallTest {
                static void function main() {
                    variable list = [1, 2, 3];
                    Object obj = list;
                    obj.isSorted(); // Must throw compile error
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ObjectDirectCallTest"));
        assertTrue(ex.getMessage().contains("Cannot resolve") || ex.getMessage().contains("isSorted"),
                "Expected unresolved method error on Object without cast, got: " + ex.getMessage());
    }

    @Test
    public void testCallingMethodOnObjectWithExplicitCastSucceeds() throws Exception {
        String code = """
            class ObjectExplicitCastTest {
                static void function main() {
                    variable list = [1, 2, 3];
                    Object obj = list;
                    boolean sorted = (bool) ((OceanList) obj).isSorted();
                    OceanOutput("sorted=" + sorted);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ObjectExplicitCastTest");
        String output = executeMainAndCaptureOutput(classes, "ObjectExplicitCastTest");
        assertEquals("sorted=true", output);
    }
}
