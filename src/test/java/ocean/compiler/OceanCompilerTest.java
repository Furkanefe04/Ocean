package ocean.compiler;

import ocean.compiler.ir.IRNode;
import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.HashMap;

import ocean.compiler.OceanLexer;
import ocean.compiler.OceanParser;

import static org.junit.jupiter.api.Assertions.*;

public class OceanCompilerTest {

    @BeforeEach
    public void setup() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    private Map<String, byte[]> compileToBytecodeMap(String code, String className) {
        CompilationSession session = new CompilationSession();
        CompilationSession.setActiveSession(session);
        try {
            OceanLexer lexer = new OceanLexer(CharStreams.fromString(code));
            lexer.removeErrorListeners();
            OceanParser parser = new OceanParser(OceanTokenStreamFactory.createTokenStream(lexer));
            parser.removeErrorListeners();

            OceanParser.ProgramContext tree = parser.program();

            // Pre-register all top-level types
            String fqName = className + "/" + className;
            CompilerRegistry.globalMethodRegistry.computeIfAbsent(fqName, k -> new HashMap<>());
            for (OceanParser.CompilationUnitContext cu : tree.compilationUnit()) {
                String simpleName = null;
                if (cu.classDeclaration() != null) simpleName = cu.classDeclaration().anyId().getText();
                else if (cu.interfaceDeclaration() != null) simpleName = cu.interfaceDeclaration().anyId().getText();
                else if (cu.enumDeclaration() != null) simpleName = cu.enumDeclaration().anyId().getText();

                if (simpleName != null) {
                    String fq = className + "/" + simpleName;
                    CompilerRegistry.globalMethodRegistry.computeIfAbsent(fq, k -> new HashMap<>());
                }
            }

            // Pre-scan
            PreScanner scanner = new PreScanner(className + ".ocean", session);
            scanner.setCurrentFilePackage(className);
            scanner.visit(tree);


            // IR Generation
            IRGenerator irGen = new IRGenerator(
                className + ".ocean",
                scanner.getCurrentFilePackage(),
                scanner.getImportedClasses(),
                scanner.getImportedWildcards(),
                scanner.getImportedStaticMembers(),
                scanner.getImportedStaticWildcards(),
                scanner.getSymbolTable()
            );
            IRNode irTree = irGen.visit(tree);

            if (CompilerReporter.hasErrors()) {
                throw new CompilationException("Compilation failed during IR generation.");
            }

            // IR Semantic Analysis
            IRSemanticAnalyzer irAnalyzer = new IRSemanticAnalyzer(
                className + ".ocean",
                session,
                irGen.getImportedClasses(),
                irGen.getImportedWildcards(),
                irGen.getImportedStaticMembers(),
                irGen.getImportedStaticWildcards(),
                irGen.getSymbolTable()
            );
            irTree.accept(irAnalyzer);

            if (irAnalyzer.hasErrors() || CompilerReporter.hasErrors()) {
                if (irAnalyzer.hasErrors()) CompilerReporter.printSummary();
                throw new CompilationException("Compilation failed during IR semantic analysis.");
            }

            // Bytecode Emission
            IRToBytecodeEmitter emitter = new IRToBytecodeEmitter();
            irTree.accept(emitter);

            Map<String, byte[]> resultClasses = emitter.getGeneratedClasses();
            System.err.println("[DEBUG-GEN-CLASSES] generated keys=" + resultClasses.keySet());
            return resultClasses;
        } finally {
            CompilationSession.clearActiveSession();
        }
    }

    private byte[] compileToBytecode(String code, String className) {
        Map<String, byte[]> classes = compileToBytecodeMap(code, className);
        String fqName = classes.containsKey(className + "/" + className) ? className + "/" + className : className;
        assertTrue(classes.containsKey(fqName), "Generated class missing: " + fqName);
        return classes.get(fqName);
    }

    private String executeMainAndCaptureOutput(byte[] bytecode, String className) throws Exception {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(className + "/" + className, bytecode);
        return executeMainAndCaptureOutput(classes, className);
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
                if (name.contains(".")) {
                    String simple = name.substring(name.lastIndexOf('.') + 1);
                    if (classes.containsKey(simple)) {
                        return loadClass(simple);
                    }
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
        System.setOut(new PrintStream(baos));
        try {
            if (mainMethod.getParameterCount() == 1) {
                mainMethod.invoke(null, (Object) new String[0]);
            } else {
                mainMethod.invoke(null);
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            baos.write(("[EXC: " + cause + "]").getBytes());
            cause.printStackTrace(new PrintStream(baos));
        } catch (Throwable e) {
            baos.write(("[EXC: " + e + "]").getBytes());
            e.printStackTrace(new PrintStream(baos));
        } finally {
            System.setOut(originalOut);
        }

        return baos.toString();
    }

    @Test
    public void testBasicHelloWorld() throws Exception {
        String code = """
                class TestHello {
                    static main() {
                        OceanOutput("Hello from Ocean!");
                    }
                }""";

        Map<String, byte[]> bytecode = compileToBytecodeMap(code, "TestHello");
        String output = executeMainAndCaptureOutput(bytecode, "TestHello");

        assertTrue(output.contains("Hello from Ocean!"), "Output should contain string literal");
    }

    @Test
    public void testInterfaceCompilation() throws Exception {
        String code = """
                interface Drawable {
                    void function draw();
                }
                class Shape implements Drawable {
                    void function draw() {
                        OceanOutput("Drawing!");
                    }
                }""";
        
        Map<String, byte[]> classes = compileToBytecodeMap(code, "Shape");
        assertTrue(classes.containsKey("Shape/Drawable"), "Generated interface missing");
        assertTrue(classes.containsKey("Shape/Shape"), "Generated class missing");

        // Verify that Shape is loadable and has draw() method
        byte[] shapeBytecode = classes.get("Shape/Shape");
        byte[] drawableBytecode = classes.get("Shape/Drawable");

        ClassLoader loader = new ClassLoader() {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals("Shape.Drawable")) {
                    return defineClass(name, drawableBytecode, 0, drawableBytecode.length);
                }
                if (name.equals("Shape.Shape")) {
                    return defineClass(name, shapeBytecode, 0, shapeBytecode.length);
                }
                return super.findClass(name);
            }
        };

        Class<?> shapeClass = loader.loadClass("Shape.Shape");
        Class<?> drawableClass = loader.loadClass("Shape.Drawable");
        
        assertTrue(drawableClass.isInterface(), "Drawable should be an interface");
        assertTrue(drawableClass.isAssignableFrom(shapeClass), "Shape should implement Drawable");
    }

    @Test
    public void testEnumCompilation() throws Exception {
        String code = """
                enum Color {
                    RED, GREEN, BLUE
                }""";
        
        Map<String, byte[]> classes = compileToBytecodeMap(code, "Color");
        assertTrue(classes.containsKey("Color/Color"), "Generated enum missing");

        byte[] enumBytecode = classes.get("Color/Color");

        ClassLoader loader = new ClassLoader() {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals("Color.Color")) {
                    return defineClass(name, enumBytecode, 0, enumBytecode.length);
                }
                return super.findClass(name);
            }
        };

        Class<?> colorClass = loader.loadClass("Color.Color");
        assertTrue(colorClass.isEnum(), "Color should be an enum");
        
        Object[] enumConstants = colorClass.getEnumConstants();
        assertNotNull(enumConstants);
        assertEquals(3, enumConstants.length);
        assertEquals("RED", enumConstants[0].toString());
        assertEquals("GREEN", enumConstants[1].toString());
        assertEquals("BLUE", enumConstants[2].toString());
    }

    @Test
    public void testDataClassCompilation() throws Exception {
        String code = """
                data class Point(int x, String name) {}
                class DataClassTest {
                    static main() {
                        Point p1 = new Point(10, "hello");
                        Point p2 = new Point(10, "hello");
                        Point p3 = new Point(20, "world");
                       \s
                        OceanOutput("x: " + p1.getX());
                        OceanOutput("name: " + p1.getName());
                       \s
                        p1.setX(15);
                        OceanOutput("new_x: " + p1.getX());
                       \s
                        OceanOutput("eq1: " + p1.equals(p2));
                        OceanOutput("eq2: " + p2.equals(new Point(10, "hello")));
                       \s
                        OceanOutput("toString: " + p2.toString());
                        OceanOutput("hashCode: " + p2.hashCode());
                    }
                }""";

        Map<String, byte[]> classes = compileToBytecodeMap(code, "DataClassTest");
        assertTrue(classes.containsKey("DataClassTest/Point"), "Generated Point class missing");
        assertTrue(classes.containsKey("DataClassTest/DataClassTest"), "Generated DataClassTest class missing");

        byte[] pointBytecode = classes.get("DataClassTest/Point");
        byte[] testBytecode = classes.get("DataClassTest/DataClassTest");

        ClassLoader loader = new ClassLoader() {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals("DataClassTest.Point")) {
                    return defineClass(name, pointBytecode, 0, pointBytecode.length);
                }
                if (name.equals("DataClassTest.DataClassTest")) {
                    return defineClass(name, testBytecode, 0, testBytecode.length);
                }
                return super.findClass(name);
            }
        };

        Class<?> pointClass = loader.loadClass("DataClassTest.Point");
        Class<?> testClass = loader.loadClass("DataClassTest.DataClassTest");

        // Verify getters exist
        Method getX = pointClass.getMethod("getX");
        Method getName = pointClass.getMethod("getName");
        assertNotNull(getX);
        assertNotNull(getName);

        // Run main and capture output
        Method mainMethod = testClass.getMethod("main", String[].class);
        mainMethod.setAccessible(true);
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            mainMethod.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(originalOut);
        }

        String output = baos.toString();
        assertTrue(output.contains("x: 10"), "Output mismatch");
        assertTrue(output.contains("name: hello"), "Output mismatch");
        assertTrue(output.contains("new_x: 15"), "Output mismatch");
        assertTrue(output.contains("eq1: false"), "Output mismatch");
        assertTrue(output.contains("eq2: true"), "Output mismatch");
        assertTrue(output.contains("toString: Point(x=10, name=hello)"), "Output mismatch: " + output);
    }

    @Test
    public void testUnresolvedMethodThrowsCompilationException() {
        String code = """
                class TestUnresolvedMethod {
                    main() {
                        this.nonExistentMethod();
                    }
                }""";
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestUnresolvedMethod"));
    }

    @Test
    public void testUnresolvedLocalVariableThrowsCompilationException() {
        String code = """
                class TestUnresolvedLocal {
                    static main() {
                        nonExistentVar = 10;
                    }
                }""";
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestUnresolvedLocal"));
    }

    @Test
    public void testMissingReturnThrowsCompilationException() {
        String code = """
                class TestMissingReturn {
                    int function compute() {
                        // missing return
                    }
                }""";
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestMissingReturn"));
    }

    @Test
    public void testAmbiguousClassThrowsCompilationException() {
        String code = """
                import java.util.*;
                import java.sql.*;
                class TestAmbiguous {
                    static main() {
                        Date d = null;
                    }
                }""";
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestAmbiguous"));
    }

    @Test
    public void testBeanMapCompilationAndUsage() throws Exception {
        String code = """
                import org.apache.commons.beanutils.BeanMap;
                public class SimpleBean {
                    private String name;
                    function SimpleBean(String name) {
                        this.name = name;
                    }
                    String function getName() {
                        return this.name;
                    }
                    void function setName(String n) {
                        this.name = n;
                    }
                }
                class TestBeanMap {
                    static main() {
                        SimpleBean bean = new SimpleBean("Ocean");
                        BeanMap map = new BeanMap(bean);
                        OceanOutput("name: " + map.get("name"));
                    }
                }""";
        
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TestBeanMap");
        assertTrue(classes.containsKey("TestBeanMap/SimpleBean"));
        assertTrue(classes.containsKey("TestBeanMap/TestBeanMap"));
        
        byte[] beanBytecode = classes.get("TestBeanMap/SimpleBean");
        byte[] testBytecode = classes.get("TestBeanMap/TestBeanMap");
        
        ClassLoader loader = new ClassLoader() {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals("TestBeanMap.SimpleBean")) {
                    return defineClass(name, beanBytecode, 0, beanBytecode.length);
                }
                if (name.equals("TestBeanMap.TestBeanMap")) {
                    return defineClass(name, testBytecode, 0, testBytecode.length);
                }
                return super.findClass(name);
            }
        };
        
        Class<?> testClass = loader.loadClass("TestBeanMap.TestBeanMap");
        Method mainMethod = testClass.getMethod("main", String[].class);
        mainMethod.setAccessible(true);
        
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            mainMethod.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(originalOut);
        }
        
        String output = baos.toString();
        assertTrue(output.contains("name: Ocean"), "Output mismatch: " + output);
    }

    @Test
    public void testChainedGetCallsOnHeterogeneousMapListLiterals() throws Exception {
        String code = """
                class CLiteralTest {
                    static main() {
                        variable records = [
                            {"id": 101, "scores": [10, 20, 30]},
                            {"id": 202, "scores": [40, 50, 60]},
                            {"id": 303, "scores": [70, 80, 90]}
                        ];
                        variable recordScore = ((OceanIntList)records.get(1).get("scores")).get(2);
                        if (recordScore == 60) {
                            OceanOutput("STATUS: PASSED");
                        } else {
                            OceanOutput("STATUS: FAILED");
                        }
                    }
                }""";

        byte[] bytecode = compileToBytecode(code, "CLiteralTest");
        String output = executeMainAndCaptureOutput(bytecode, "CLiteralTest");
        assertTrue(output.contains("STATUS: PASSED"), "Output mismatch: " + output);
    }

    /**
     * Heterojen iç içe generic literal OceanList<OceanMap<String,Integer>> hedefine atanırken
     * semantik analizde hata verilmeli (runtime ClassCastException yerine derleme zamanı hatası).
     * Örnek:
     *   OceanList<OceanMap<String, Integer>> datax = [
     *       {"user": 25},
     *       {"user": "twenty"}   // String ≠ Integer → derleme hatası
     *   ];
     */
    @Test
    public void testGenericTypeMismatchInNestedCollectionLiteralThrowsError() {
        String code = """
                class GenericTypeErrorTest {
                    static main() {
                        OceanList<OceanMap<String, Integer>> datax = [
                            {"user": 25},
                            {"user": "twenty"}
                        ];
                    }
                }""";

        org.junit.jupiter.api.Assertions.assertThrows(
            CompilationException.class,
            () -> compileToBytecode(code, "GenericTypeErrorTest"),
            "Heterojen generic literal OceanList<OceanMap<String,Integer>> hedefine atanınca CompilationException bekleniyor"
        );
    }

    /**
     * Homojen generic literaller hatasız derlenmeli.
     */
    @Test
    public void testHomogeneousGenericCollectionAssignmentPasses() throws Exception {
        String code = """
                class HomogenTest {
                    static main() {
                        OceanList<OceanMap<String, int>> data = [
                            {"a": 1},
                            {"b": 2}
                        ];
                        OceanOutput("STATUS: PASSED");
                    }
                }""";
        byte[] bytecode = compileToBytecode(code, "HomogenTest");
        String output = executeMainAndCaptureOutput(bytecode,"HomogenTest");
        assertTrue(output.contains("STATUS: PASSED"), "Homojen generic literal hatasız derlenmeli. Output: " + output);
    }

    @Test
    public void testAllPrimitiveUnaryIncrementOperators() throws Exception {
        String code = """
                class UnaryOpTest {
                    static main() {
                        int i = 10;
                        i++; ++i; i--; --i;
                        OceanOutput("i: " + i);
                        long l = 100L;
                        l++; ++l; l--; --l;
                        OceanOutput("l: " + l);
                        float f = 1.5f;
                        f++; ++f; f--; --f;
                        OceanOutput("f: " + f);
                        double d = 2.5;
                        d++; ++d; d--; --d;
                        OceanOutput("d: " + d);
                        variable x = 50;
                        x++; ++x; x--; --x;
                        OceanOutput("x: " + x);
                    }
                }""";

        byte[] bytecode = compileToBytecode(code, "UnaryOpTest");
        String output = executeMainAndCaptureOutput(bytecode, "UnaryOpTest");
        assertTrue(output.contains("i: 10"), "int unary output mismatch: " + output);
        assertTrue(output.contains("l: 100"), "long unary output mismatch: " + output);
        assertTrue(output.contains("f: 1.5"), "float unary output mismatch: " + output);
        assertTrue(output.contains("d: 2.5"), "double unary output mismatch: " + output);
        assertTrue(output.contains("x: 50"), "variable unary output mismatch: " + output);
    }

    @Test
    public void testBooleanIncrementThrowsCompilationException() {
        String code = """
                class BoolIncTest {
                    static main() {
                        boolean b = true;
                        b++;
                    }
                }""";

        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "BoolIncTest"));
    }

    @Test
    public void testPrimitiveLiteralBoundsNegativeTests() {
        // Byte positive overflow (200 > 127)
        String codeByteOver = "class Test1 { static main() { byte b = 200; } }";
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(codeByteOver, "Test1"));

        // Byte negative underflow (-150 < -128)
        String codeByteUnder = "class Test2 { static main() { byte b = -150; } }";
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(codeByteUnder, "Test2"));

        // Short overflow (50000 > 32767)
        String codeShortOver = "class Test3 { static main() { short s = 50000; } }";
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(codeShortOver, "Test3"));

        // Char underflow (-1 < 0)
        String codeCharUnder = "class Test4 { static main() { char c = -1; } }";
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(codeCharUnder, "Test4"));
    }

    @Test
    public void testAsyncReturnTypeMismatchThrowsCompilationException() {
        String code = """
                class AsyncTypeMismatchTest {
                    async String function fetchString() { return "hello"; }
                    async main() {
                        int x = await fetchString();
                    }
                }""";

        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "AsyncTypeMismatchTest"));
    }

    @Test
    public void testLambdaWithoutExplicitTargetTypeThrowsError() {
        // variable x = (abc) -> abc * 2;
        String codeVar = """
                class LambdaVarTest {
                    static main() {
                        variable x = (abc) -> abc * 2;
                    }
                }""";
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(codeVar, "LambdaVarTest"));

        // value x = (abc) -> abc * 2;
        String codeVal = """
                class LambdaValTest {
                    static main() {
                        value x = (abc) -> abc * 2;
                    }
                }""";
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(codeVal, "LambdaValTest"));

        // Object x = (abc) -> abc * 2;
        String codeObj = """
                class LambdaObjTest {
                    static main() {
                        Object x = (abc) -> abc * 2;
                    }
                }""";
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(codeObj, "LambdaObjTest"));
    }

    @Test
    public void testLambdaWithExplicitTargetTypeOrCastSucceeds() throws Exception {
        // Explicit functional interface declaration: Function<int, int> f = (int abc) -> abc * 2;
        String codeTyped = """
                import java.util.function.Function;
                class LambdaTypedTest {
                    static main() {
                        Function<int, int> f = (int abc) -> abc * 2;
                        OceanOutput(f(21));
                    }
                }""";
        Map<String, byte[]> map1 = compileToBytecodeMap(codeTyped, "LambdaTypedTest");
        assertNotNull(map1);

        // Untyped variable declaration with explicit cast: variable f = (Function<int, int>) ((int abc) -> abc * 2);
        String codeCastVar = """
                import java.util.function.Function;
                class LambdaCastVarTest {
                    static main() {
                        variable f = (Function<int, int>) ((int abc) -> abc * 2);
                        OceanOutput(f(21));
                    }
                }""";
        Map<String, byte[]> map2 = compileToBytecodeMap(codeCastVar, "LambdaCastVarTest");
        assertNotNull(map2);
    }
}
