package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MultiLevelNestedLambdaTest {

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
                CompilerReporter.printSummary();
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
                CompilerReporter.printSummary();
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
                for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                    if (e.getKey().endsWith("/" + name) || e.getKey().equals(name)) {
                        return defineClass(name, e.getValue(), 0, e.getValue().length);
                    }
                }
                return super.findClass(name);
            }
        };

        String fqName = classes.containsKey(className + "/" + className) ? className + "." + className : className;
        Class<?> clazz;
        try {
            clazz = loader.loadClass(fqName);
        } catch (ClassNotFoundException e) {
            clazz = loader.loadClass(className);
        }
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
    public void testTwoLevelCurriedLambdaExecution() throws Exception {
        String code = """
            import java.util.function.Function;

            class CurriedTest {
                static main() {
                    int x = 10;
                    int y = 20;
                    Function<Integer, Function<Integer, Integer>> curried = (a) -> (b) -> a + b + x + y;
                    Function<Integer, Integer> step1 = curried.apply(5);
                    Integer res = step1.apply(3);
                    OceanOutput("RES:" + res);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "CurriedTest");
        String output = executeMainAndCaptureOutput(classes, "CurriedTest");
        assertEquals("RES:38", output);
    }

    @Test
    public void testThreeLevelCurriedWithInstanceCapture() throws Exception {
        String code = """
            import java.util.function.Function;

            class InstanceCurriedTest {
                private int multiplier = 10;

                public Function<Integer, Function<Integer, Integer>> function getCurried(int base) {
                    return (a) -> (b) -> (a + b + base) * this.multiplier;
                }

                static main() {
                    InstanceCurriedTest inst = new InstanceCurriedTest();
                    Function<Integer, Function<Integer, Integer>> fn = inst.getCurried(5);
                    Integer res = fn.apply(2).apply(3);
                    OceanOutput("RES:" + res);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "InstanceCurriedTest");
        String output = executeMainAndCaptureOutput(classes, "InstanceCurriedTest");
        assertEquals("RES:100", output);
    }

    @Test
    public void testFourLevelDeepNestedLambda() throws Exception {
        String code = """
            import java.util.function.Function;

            class FourLevelTest {
                static main() {
                    int offset = 7;
                    Function<Integer, Function<Integer, Function<Integer, Function<Integer, Integer>>>> curried4 =
                        (a) -> (b) -> (c) -> (d) -> (a * 1000) + (b * 100) + (c * 10) + d + offset;
                    Integer res = curried4.apply(1).apply(2).apply(3).apply(4);
                    OceanOutput("RES:" + res);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "FourLevelTest");
        String output = executeMainAndCaptureOutput(classes, "FourLevelTest");
        assertEquals("RES:1241", output);
    }

    @Test
    public void testCustomOceanInterfaceCurrying() throws Exception {
        String code = """
            interface OceanPipeline<In, Out> {
                Out function process(In data);
            }

            class CustomPipelineTest {
                static main() {
                    int bonus = 500;
                    OceanPipeline<Integer, OceanPipeline<String, OceanPipeline<Integer, String>>> pipeline =
                        (count) -> (prefix) -> (multiplier) -> prefix + ": " + ((count * multiplier) + bonus);

                    String result = pipeline.process(5).process("Total").process(20);
                    OceanOutput("RES:" + result);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "CustomPipelineTest");
        String output = executeMainAndCaptureOutput(classes, "CustomPipelineTest");
        assertEquals("RES:Total: 600", output);
    }

    @Test
    public void testReverseTypeParameterOrderCurrying() throws Exception {
        String code = """
            interface ReverseCurried<R, T> {
                R function applyCurried(T input);
            }

            class ReverseCurriedTest {
                static main() {
                    int base = 50;
                    ReverseCurried<ReverseCurried<Integer, Integer>, Integer> rev =
                        (a) -> (b) -> a + b + base;

                    Integer result = rev.applyCurried(10).applyCurried(20);
                    OceanOutput("RES:" + result);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ReverseCurriedTest");
        String output = executeMainAndCaptureOutput(classes, "ReverseCurriedTest");
        assertEquals("RES:80", output);
    }
}