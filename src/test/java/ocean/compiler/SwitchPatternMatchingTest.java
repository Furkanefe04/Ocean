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

public class SwitchPatternMatchingTest {

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
                throw new CompilationException("Compilation failed during pre-scan.");
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
                throw new CompilationException("Compilation failed during IR generation.");
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
                throw new CompilationException("Compilation failed during IR semantic analysis.");
            }

            IRToBytecodeEmitter emitter = new IRToBytecodeEmitter();
            irTree.accept(emitter);

            return emitter.getGeneratedClasses();
        } finally {
            CompilationSession.clearActiveSession();
        }
    }

    private String executeMainAndCaptureOutput(Map<String, byte[]> classes, String className) throws Exception {
        ClassLoader parent = getClass().getClassLoader();
        ClassLoader cl = new ClassLoader(parent) {
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
    public void testSwitchExprPatternMatchingWithTypeAndGuards() throws Exception {
        String code = """
            class SwitchExprPatternTest {
                public static String function test(Object? obj) {
                    return switch (obj) {
                        case null -> "NULL"
                        case String s when s.length() > 5 -> "LONG_STR:" + s
                        case String s -> "SHORT_STR:" + s
                        case Integer i when i > 100 -> "LARGE_INT:" + i
                        case Integer i -> "SMALL_INT:" + i
                        case Boolean b -> "BOOL:" + b
                        default -> "OTHER"
                    };
                }

                public static void function main(String[] args) {
                    OceanOutput(test(null));
                    OceanOutput(test("HelloWorld"));
                    OceanOutput(test("Hi"));
                    OceanOutput(test(Integer.valueOf(200)));
                    OceanOutput(test(Integer.valueOf(42)));
                    OceanOutput(test(Boolean.valueOf(true)));
                    OceanOutput(test(Double.valueOf(3.14)));
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "SwitchExprPatternTest");
        String output = executeMainAndCaptureOutput(classes, "SwitchExprPatternTest");
        assertEquals("NULL\nLONG_STR:HelloWorld\nSHORT_STR:Hi\nLARGE_INT:200\nSMALL_INT:42\nBOOL:true\nOTHER", output.replace("\r\n", "\n"));
    }

    @Test
    public void testSwitchStmtPatternMatchingWithTypeAndGuards() throws Exception {
        String code = """
            class SwitchStmtPatternTest {
                public static String function test(Object? obj) {
                    variable res = "";
                    switch (obj) {
                        case null:
                            res = "NULL";
                            stop;
                        case String s when s.startsWith("A"):
                            res = "STARTS_WITH_A:" + s;
                            stop;
                        case String s:
                            res = "STR:" + s;
                            stop;
                        case Integer i:
                            res = "INT:" + i;
                            stop;
                        default:
                            res = "DEFAULT";
                            stop;
                    }
                    return res;
                }

                public static void function main(String[] args) {
                    OceanOutput(test(null));
                    OceanOutput(test("Apple"));
                    OceanOutput(test("Banana"));
                    OceanOutput(test(Integer.valueOf(77)));
                    OceanOutput(test(Double.valueOf(9.99)));
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "SwitchStmtPatternTest");
        String output = executeMainAndCaptureOutput(classes, "SwitchStmtPatternTest");
        assertEquals("NULL\nSTARTS_WITH_A:Apple\nSTR:Banana\nINT:77\nDEFAULT", output.replace("\r\n", "\n"));
    }

    @Test
    public void testClassicSwitchNoRegression() throws Exception {
        String code = """
            class ClassicSwitchTest {
                public static String function testInt(int x) {
                    return switch (x) {
                        case 1 -> "ONE"
                        case 2 -> "TWO"
                        default -> "OTHER"
                    };
                }

                public static void function main(String[] args) {
                    OceanOutput(testInt(1));
                    OceanOutput(testInt(2));
                    OceanOutput(testInt(3));
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "ClassicSwitchTest");
        String output = executeMainAndCaptureOutput(classes, "ClassicSwitchTest");
        assertEquals("ONE\nTWO\nOTHER", output.replace("\r\n", "\n"));
    }
}
