package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MethodReferenceAndLambdaComprehensiveTest {

    @BeforeEach
    public void setup() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    private Map<String, byte[]> compileToBytecodeMap(String code, String className) {
        CompilationSession session = new CompilationSession();
        CompilationSession.setActiveSession(session);
        try {
            var lexer = new ocean.compiler.OceanLexer(org.antlr.v4.runtime.CharStreams.fromString(code));
            lexer.removeErrorListeners();
            var parser = new ocean.compiler.OceanParser(ocean.compiler.OceanTokenStreamFactory.createTokenStream(lexer));
            parser.removeErrorListeners();

            var tree = parser.program();

            String fqName = className + "/" + className;
            CompilerRegistry.globalMethodRegistry.computeIfAbsent(fqName, k -> new java.util.HashMap<>());
            for (var cu : tree.compilationUnit()) {
                String simpleName = null;
                if (cu.classDeclaration() != null) simpleName = cu.classDeclaration().anyId().getText();
                else if (cu.interfaceDeclaration() != null) simpleName = cu.interfaceDeclaration().anyId().getText();
                else if (cu.enumDeclaration() != null) simpleName = cu.enumDeclaration().anyId().getText();

                if (simpleName != null) {
                    String fq = className + "/" + simpleName;
                    CompilerRegistry.globalMethodRegistry.computeIfAbsent(fq, k -> new java.util.HashMap<>());
                }
            }

            var scanner = new PreScanner(className + ".ocean", session);
            scanner.setCurrentFilePackage(null);
            scanner.visit(tree);

            var irGen = new IRGenerator(
                className + ".ocean",
                null,
                scanner.getImportedClasses(),
                scanner.getImportedWildcards(),
                scanner.getImportedStaticMembers(),
                scanner.getImportedStaticWildcards(),
                scanner.getSymbolTable()
            );
            var irTree = irGen.visit(tree);

            if (CompilerReporter.hasErrors()) {
                throw new CompilationException("Compilation failed during IR generation.");
            }

            var irAnalyzer = new IRSemanticAnalyzer(
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
                throw new CompilationException("Compilation failed during IR semantic analysis.");
            }

            var emitter = new IRToBytecodeEmitter();
            irTree.accept(emitter);
            return emitter.getGeneratedClasses();
        } finally {
            CompilationSession.clearActiveSession();
        }
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
        java.lang.reflect.Method mainMethod;
        try {
            mainMethod = clazz.getMethod("main", String[].class);
        } catch (NoSuchMethodException e) {
            mainMethod = clazz.getMethod("main");
        }
        mainMethod.setAccessible(true);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(out));
        try {
            if (mainMethod.getParameterCount() == 1) {
                mainMethod.invoke(null, (Object) new String[0]);
            } else {
                mainMethod.invoke(null);
            }
        } finally {
            System.setOut(originalOut);
        }
        return out.toString().trim();
    }

    @Test
    public void testLambdaGenericTypePropagation() throws Exception {
        // BiFunction<Integer, Integer, Integer> with untyped lambda parameters (x, y) -> Math.max(x, y)
        String code = """
                import java.util.function.BiFunction;
                class BiFunctionTest {
                    static main() {
                        variable deneme = (BiFunction<Integer, Integer, Integer>) (x, y) -> Math.max(x, y);
                        int val1 = deneme(10, 20);
                        int val2 = deneme.apply(30, 40);
                        OceanOutput(val1);
                        OceanOutput(val2);
                    }
                }""";
        Map<String, byte[]> map = compileToBytecodeMap(code, "BiFunctionTest");
        assertNotNull(map);
        String output = executeMainAndCaptureOutput(map, "BiFunctionTest");
        assertTrue(output.contains("20"));
        assertTrue(output.contains("40"));
    }

    @Test
    public void testLambdaVoidExpressionBody() throws Exception {
        // Consumer<String> with expression body (x) -> OceanOutput(x)
        String code = """
                import java.util.function.Consumer;
                class ConsumerTest {
                    static main() {
                        variable xyz = (Consumer<String>) (String x) -> OceanOutput(x);
                        xyz("hello");
                        xyz.accept("world");
                    }
                }""";
        Map<String, byte[]> map = compileToBytecodeMap(code, "ConsumerTest");
        assertNotNull(map);
        String output = executeMainAndCaptureOutput(map, "ConsumerTest");
        assertTrue(output.contains("hello"));
        assertTrue(output.contains("world"));
    }

    @Test
    public void testStaticMethodReference() throws Exception {
        // Math::max with BiFunction<Integer, Integer, Integer>
        String code = """
                import java.util.function.BiFunction;
                class StaticMethodRefTest {
                    static main() {
                        BiFunction<Integer, Integer, Integer> maxRef = Math::max;
                        variable maxVar = (BiFunction<Integer, Integer, Integer>) Math::max;
                        int val1 = maxRef(15, 25);
                        int val2 = maxVar.apply(50, 100);
                        OceanOutput(val1);
                        OceanOutput(val2);
                    }
                }""";
        Map<String, byte[]> map = compileToBytecodeMap(code, "StaticMethodRefTest");
        assertNotNull(map);
        String output = executeMainAndCaptureOutput(map, "StaticMethodRefTest");
        assertTrue(output.contains("25"));
        assertTrue(output.contains("100"));
    }

    @Test
    public void testUnboundInstanceMethodReference() throws Exception {
        // String::toUpperCase with Function<String, String>
        String code = """
                import java.util.function.Function;
                class UnboundMethodRefTest {
                    static main() {
                        Function<String, String> ref = String::toUpperCase;
                        variable refVar = (Function<String, String>) String::toUpperCase;
                        String val1 = ref("ocean");
                        String val2 = refVar.apply("runner");
                        OceanOutput(val1);
                        OceanOutput(val2);
                    }
                }""";
        Map<String, byte[]> map = compileToBytecodeMap(code, "UnboundMethodRefTest");
        assertNotNull(map);
        String output = executeMainAndCaptureOutput(map, "UnboundMethodRefTest");
        assertTrue(output.contains("OCEAN"));
        assertTrue(output.contains("RUNNER"));
    }

    @Test
    public void testBoundInstanceMethodReference() throws Exception {
        // "ocean"::toUpperCase or str::toUpperCase with Supplier<String>
        String code = """
                import java.util.function.Supplier;
                class BoundMethodRefTest {
                    static main() {
                        variable text = "ocean";
                        Supplier<String> sup = text::toUpperCase;
                        String val1 = sup();
                        String val2 = sup.get();
                        OceanOutput(val1);
                        OceanOutput(val2);
                    }
                }""";
        Map<String, byte[]> map = compileToBytecodeMap(code, "BoundMethodRefTest");
        assertNotNull(map);
        String output = executeMainAndCaptureOutput(map, "BoundMethodRefTest");
        assertTrue(output.contains("OCEAN"));
    }

    @Test
    public void testConstructorReference() throws Exception {
        // String::new with Function<String, String>
        String code = """
                import java.util.function.Function;
                class CtorRefTest {
                    static main() {
                        Function<String, String> strCtor = String::new;
                        String s1 = strCtor("hello");
                        String s2 = strCtor.apply("world");
                        OceanOutput(s1);
                        OceanOutput(s2);
                    }
                }""";
        Map<String, byte[]> map = compileToBytecodeMap(code, "CtorRefTest");
        assertNotNull(map);
        String output = executeMainAndCaptureOutput(map, "CtorRefTest");
        assertTrue(output.contains("hello"));
        assertTrue(output.contains("world"));
    }

    @Test
    public void testArrayConstructorReference() throws Exception {
        // String[]::new with IntFunction<String[]>
        String code = """
                import java.util.function.IntFunction;
                class ArrayCtorRefTest {
                    static main() {
                        IntFunction<String[]> arrCtor = String[]::new;
                        String[] arr = arrCtor(5);
                        OceanOutput(arr.length);
                    }
                }""";
        Map<String, byte[]> map = compileToBytecodeMap(code, "ArrayCtorRefTest");
        assertNotNull(map);
        String output = executeMainAndCaptureOutput(map, "ArrayCtorRefTest");
        assertTrue(output.contains("5"));
    }

    @Test
    public void testMethodReferenceUntypedFailsSemantic() {
        // variable x = String::toUpperCase; without target type must fail
        String code1 = """
                class Fail1 {
                    static main() {
                        variable x = String::toUpperCase;
                    }
                }""";
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code1, "Fail1"));

        // value x = Math::max; without target type must fail
        String code2 = """
                class Fail2 {
                    static main() {
                        value x = Math::max;
                    }
                }""";
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code2, "Fail2"));

        // Object x = String::toUpperCase; with non-SAM target must fail
        String code3 = """
                class Fail3 {
                    static main() {
                        Object x = String::toUpperCase;
                    }
                }""";
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code3, "Fail3"));
    }
}
