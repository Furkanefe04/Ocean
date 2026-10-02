package ocean.compiler;

import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class UnnamedVariablesAndPatternsTest {

    private CompilationSession lastSession;

    @BeforeEach
    public void setup() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
        lastSession = null;
    }

    private Map<String, byte[]> compileToBytecodeMap(String code, String className) {
        CompilationSession session = new CompilationSession();
        lastSession = session;
        CompilationSession.setActiveSession(session);
        try {
            OceanLexer lexer = new OceanLexer(CharStreams.fromString(code));
            lexer.removeErrorListeners();
            OceanParser parser = new OceanParser(OceanTokenStreamFactory.createTokenStream(lexer));
            parser.removeErrorListeners();

            OceanParser.ProgramContext tree = parser.program();

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

            PreScanner scanner = new PreScanner(className + ".ocean", session);
            scanner.setCurrentFilePackage(null);
            scanner.visit(tree);

            if (CompilerReporter.hasErrors()) {
                System.err.println("PreScan errors: " + (session != null ? session.messages : "null"));
                throw new CompilationException("Compilation failed during pre-scan: " + (session != null ? session.messages : ""));
            }

            IRGenerator irGen = new IRGenerator(
                className + ".ocean",
                null,
                scanner.getImportedClasses(),
                scanner.getImportedWildcards(),
                scanner.getImportedStaticMembers(),
                scanner.getImportedStaticWildcards(),
                scanner.getSymbolTable()
            );
            ocean.compiler.ir.IRNode irTree = irGen.visit(tree);

            if (CompilerReporter.hasErrors()) {
                System.err.println("IRGen errors: " + (session != null ? session.messages : "null"));
                throw new CompilationException("Compilation failed during IR generation: " + (session != null ? session.messages : ""));
            }

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
                System.err.println("IRAnalyzer errors: " + (session != null ? session.messages : "null"));
                throw new CompilationException("Compilation failed during IR semantic analysis: " + (session != null ? session.messages : ""));
            }

            IRToBytecodeEmitter emitter = new IRToBytecodeEmitter();
            irTree.accept(emitter);

            return emitter.getGeneratedClasses();
        } finally {
            CompilationSession.clearActiveSession();
        }
    }

    private String compileAndExpectError(String code, String className) {
        try {
            compileToBytecodeMap(code, className);
            fail("Expected compilation error for negative test [" + className + "], but compilation succeeded.");
            return null;
        } catch (CompilationException e) {
            return (lastSession != null && !lastSession.messages.isEmpty()) ? lastSession.messages.toString() : e.getMessage();
        }
    }

    private String executeMainAndCaptureOutput(Map<String, byte[]> classes, String className) throws Exception {
        ClassLoader cl = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                String slashName = name.replace('.', '/');
                if (classes.containsKey(slashName)) {
                    byte[] bytes = classes.get(slashName);
                    return defineClass(name, bytes, 0, bytes.length);
                }
                return super.findClass(name);
            }
        };

        String mainClassFq = classes.keySet().iterator().next();
        for (String k : classes.keySet()) {
            if (k.endsWith("/" + className) || k.equals(className)) {
                mainClassFq = k;
                break;
            }
        }
        Class<?> clazz = cl.loadClass(mainClassFq.replace('/', '.'));
        Method mainMethod = clazz.getMethod("main", String[].class);
        mainMethod.setAccessible(true);

        PrintStream oldOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(baos));
            mainMethod.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(oldOut);
        }

        return baos.toString().trim();
    }

    @Test
    public void testUnnamedPatternInRecordDeconstruction() throws Exception {
        String code = """
            data class Point(int x, int y)
            
            class UnnamedRecordTest {
                public static String function describe(Object obj) {
                    return switch (obj) {
                        case Point(int x, _) when x > 50 -> "High X: " + x
                        case Point(_, int y) when y == 0 -> "On X-axis: y=0"
                        case Point(_, _) -> "Any Point"
                        default -> "Unknown"
                    };
                }
                
                public static void function main(String[] args) {
                    OceanOutput(describe(new Point(100, 20)));
                    OceanOutput(describe(new Point(10, 0)));
                    OceanOutput(describe(new Point(10, 20)));
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "UnnamedRecordTest");
        String output = executeMainAndCaptureOutput(classes, "UnnamedRecordTest");
        String[] lines = output.replace("\r\n", "\n").split("\n");
        assertEquals("High X: 100", lines[0]);
        assertEquals("On X-axis: y=0", lines[1]);
        assertEquals("Any Point", lines[2]);
    }

    @Test
    public void testNestedUnnamedPatterns() throws Exception {
        String code = """
            data class Point(int x, int y)
            data class ColoredPoint(Point p, String color)
            
            class NestedUnnamedTest {
                public static String function checkColor(Object obj) {
                    return switch (obj) {
                        case ColoredPoint(Point(_, int y), "red") -> "Red with y=" + y
                        case ColoredPoint(Point(int x, _), _) -> "Point with x=" + x
                        default -> "Other"
                    };
                }
                
                public static void function main(String[] args) {
                    OceanOutput(checkColor(new ColoredPoint(new Point(10, 25), "red")));
                    OceanOutput(checkColor(new ColoredPoint(new Point(42, 99), "blue")));
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NestedUnnamedTest");
        String output = executeMainAndCaptureOutput(classes, "NestedUnnamedTest");
        String[] lines = output.replace("\r\n", "\n").split("\n");
        assertEquals("Red with y=25", lines[0]);
        assertEquals("Point with x=42", lines[1]);
    }

    @Test
    public void testUnnamedTypePatternAndInstanceOf() throws Exception {
        String code = """
            data class Point(int x, int y)
            
            class UnnamedTypePatternTest {
                public static String function testTypes(Object obj) {
                    if (obj instanceof Point(int x, _)) {
                        return "Point x=" + x;
                    }
                    return switch (obj) {
                        case String _ -> "A String"
                        case Point _ -> "A Point"
                        case _ -> "Something else"
                    };
                }
                
                public static void function main(String[] args) {
                    OceanOutput(testTypes(new Point(77, 88)));
                    OceanOutput(testTypes("hello world"));
                    OceanOutput(testTypes(12345));
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "UnnamedTypePatternTest");
        String output = executeMainAndCaptureOutput(classes, "UnnamedTypePatternTest");
        String[] lines = output.replace("\r\n", "\n").split("\n");
        assertEquals("Point x=77", lines[0]);
        assertEquals("A String", lines[1]);
        assertEquals("Something else", lines[2]);
    }

    @Test
    public void testMultipleUnnamedLocalVariablesInSameScope() throws Exception {
        String code = """
            class MultiUnnamedLocal {
                static int counter = 0;
                public static int function inc() {
                    counter++;
                    return counter;
                }
                
                public static void function main(String[] args) {
                    variable _ = MultiUnnamedLocal.inc();
                    int _ = MultiUnnamedLocal.inc();
                    value _ = MultiUnnamedLocal.inc();
                    String _ = "ignored string";
                    
                    OceanOutput("Counter: " + MultiUnnamedLocal.counter);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "MultiUnnamedLocal");
        String output = executeMainAndCaptureOutput(classes, "MultiUnnamedLocal");
        assertEquals("Counter: 3", output);
    }

    @Test
    public void testUnnamedCatchVariables() throws Exception {
        String code = """
            class UnnamedCatchTest {
                public static int function parseIntSafely(String s) {
                    trying {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException _) {
                        return -1;
                    }
                }
                
                public static void function main(String[] args) {
                    OceanOutput(parseIntSafely("123"));
                    OceanOutput(parseIntSafely("abc"));
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "UnnamedCatchTest");
        String output = executeMainAndCaptureOutput(classes, "UnnamedCatchTest");
        String[] lines = output.replace("\r\n", "\n").split("\n");
        assertEquals("123", lines[0]);
        assertEquals("-1", lines[1]);
    }

    @Test
    public void testUnnamedForLoopVariables() throws Exception {
        String code = """
            class UnnamedForTest {
                public static void function main(String[] args) {
                    variable sum = 0;
                    for (variable _ from 0 to 5 with increasing 1) {
                        sum += 10;
                    }
                    OceanOutput("Sum: " + sum);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "UnnamedForTest");
        String output = executeMainAndCaptureOutput(classes, "UnnamedForTest");
        assertEquals("Sum: 50", output);
    }

    @Test
    public void testUnnamedLambdaParameters() throws Exception {
        String code = """
            import java.util.function.BiFunction;
            
            class UnnamedLambdaTest {
                public static void function main(String[] args) {
                    BiFunction<Integer, Integer, Integer> fn = (x, _) -> (x * 10);
                    BiFunction<Integer, Integer, Integer> constFn = (_, _) -> 999;
                    
                    OceanOutput("Result1: " + fn.apply(5, 100));
                    OceanOutput("Result2: " + constFn.apply(1, 2));
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "UnnamedLambdaTest");
        String output = executeMainAndCaptureOutput(classes, "UnnamedLambdaTest");
        String[] lines = output.replace("\r\n", "\n").split("\n");
        assertEquals("Result1: 50", lines[0]);
        assertEquals("Result2: 999", lines[1]);
    }

    @Test
    public void testReadingUnnamedVariableThrowsCompileError() {
        String code = """
            class IllegalReadUnnamed {
                public static void function main(String[] args) {
                    int _ = 10;
                    OceanOutput(_);
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "IllegalReadUnnamed");
        assertNotNull(errorMsg);
        assertTrue(errorMsg.toLowerCase().contains("unnamed") || errorMsg.toLowerCase().contains("cannot be read") || errorMsg.toLowerCase().contains("cannot be referenced") || errorMsg.toLowerCase().contains("Undefined"));
    }
}