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

public class StrictNumericCastTest {

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
                throw new CompilationException("Compilation failed during IR generation: " );
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
                throw new CompilationException("Compilation failed during IR semantic analysis: " );
            }

            IRToBytecodeEmitter emitter = new IRToBytecodeEmitter();
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
        Method mainMethod;
        try {
            mainMethod = clazz.getMethod("main", String[].class);
        } catch (NoSuchMethodException e) {
            mainMethod = clazz.getMethod("main");
        }
        mainMethod.setAccessible(true);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(baos));
            if (mainMethod.getParameterCount() == 1) {
                mainMethod.invoke(null, (Object) new String[]{});
            } else {
                mainMethod.invoke(null);
            }
        } finally {
            System.setOut(originalOut);
        }

        return baos.toString().trim();
    }

    @Test
    public void testImplicitWideningPasses() throws Exception {
        String code = """
            class WideningTest {
                public static void function main(String[] args) {
                    byte b = 10;
                    short s = b;
                    int i = s;
                    long l = i;
                    float f = l;
                    double d = f;
                    OceanOutput(d);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "WideningTest");
        String output = executeMainAndCaptureOutput(classes, "WideningTest");
        assertEquals("10.0", output);
    }

    @Test
    public void testExplicitNarrowingPasses() throws Exception {
        String code = """
            class ExplicitNarrowingTest {
                public static void function main(String[] args) {
                    double d = 99.95;
                    int i = (int) d;
                    long l = 300L;
                    byte b = (byte) l;
                    OceanOutput(i + ":" + b);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ExplicitNarrowingTest");
        String output = executeMainAndCaptureOutput(classes, "ExplicitNarrowingTest");
        assertEquals("99:44", output);
    }

    @Test
    public void testCompileTimeConstantLiteralsPass() throws Exception {
        String code = """
            class ConstantLiteralTest {
                public static void function main(String[] args) {
                    byte b = 127;
                    short s = 32000;
                    char c = 65;
                    float f = 1.5;
                    OceanOutput(b + ":" + s + ":" + c + ":" + f);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ConstantLiteralTest");
        String output = executeMainAndCaptureOutput(classes, "ConstantLiteralTest");
        assertEquals("127:32000:A:1.5", output);
    }

    @Test
    public void testConstantLiteralOutOfBoundsFails() {
        String code = """
            class OutOfBoundsByteTest {
                public static void function main(String[] args) {
                    byte b = 200;
                }
            }
            """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "OutOfBoundsByteTest"));
    }

    @Test
    public void testVariableNarrowingWithoutCastFails() {
        String code = """
            class VariableNarrowingFailTest {
                public static void function main(String[] args) {
                    int x = 10;
                    byte b = x;
                }
            }
            """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "VariableNarrowingFailTest"));
    }

    @Test
    public void testDoubleToIntWithoutCastFails() {
        String code = """
            class DoubleToIntFailTest {
                public static void function main(String[] args) {
                    double d = 3.14;
                    int i = d;
                }
            }
            """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "DoubleToIntFailTest"));
    }

    @Test
    public void testLongToIntWithoutCastFails() {
        String code = """
            class LongToIntFailTest {
                public static void function main(String[] args) {
                    long l = 1000L;
                    int i = l;
                }
            }
            """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "LongToIntFailTest"));
    }

    @Test
    public void testReturnNarrowingWithoutCastFails() {
        String code = """
            class ReturnNarrowingFailTest {
                public static int function compute() {
                    double d = 4.2;
                    return d;
                }
                public static void function main(String[] args) {
                    compute();
                }
            }
            """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ReturnNarrowingFailTest"));
    }
}
