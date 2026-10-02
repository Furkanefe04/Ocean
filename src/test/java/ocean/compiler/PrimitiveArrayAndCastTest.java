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

public class PrimitiveArrayAndCastTest {

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
    public void testByteArrayLiteralAssignment() throws Exception {
        String code = """
            class ArrayTest {
                public static void function main(String[] args) {
                    byte[] arr = {10, 20, 30};
                    OceanOutput(arr[0] + arr[1] + arr[2]);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ArrayTest");
        String output = executeMainAndCaptureOutput(classes, "ArrayTest");
        assertEquals("60", output);
    }

    @Test
    public void testShortAndLongArrayLiteralAssignment() throws Exception {
        String code = """
            class ShortLongArrayTest {
                public static void function main(String[] args) {
                    short[] s = {100, 200, 300};
                    long[] l = {1000, 2000, 3000};
                    OceanOutput((s[0] + s[1]) + ":" + (l[0] + l[1]));
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ShortLongArrayTest");
        String output = executeMainAndCaptureOutput(classes, "ShortLongArrayTest");
        assertEquals("300:3000", output);
    }

    @Test
    public void testFloatAndDoubleArrayLiteralAssignment() throws Exception {
        String code = """
            class FloatDoubleArrayTest {
                public static void function main(String[] args) {
                    float[] f = {1.5, 2.5};
                    double[] d = {10.5, 20.5};
                    OceanOutput((f[0] + f[1]) + ":" + (d[0] + d[1]));
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "FloatDoubleArrayTest");
        String output = executeMainAndCaptureOutput(classes, "FloatDoubleArrayTest");
        assertEquals("4.0:31.0", output);
    }

    @Test
    public void testCharArrayLiteralAssignment() throws Exception {
        String code = """
            class CharArrayTest {
                public static void function main(String[] args) {
                    char[] c = {'O', 'c', 'e', 'a', 'n'};
                    OceanOutput(c[0] + "" + c[1] + "" + c[2] + "" + c[3] + "" + c[4]);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "CharArrayTest");
        String output = executeMainAndCaptureOutput(classes, "CharArrayTest");
        assertEquals("Ocean", output);
    }

    @Test
    public void testFinalPrimitiveArrayDeclaration() throws Exception {
        String code = """
            class FinalArrayTest {
                public static void function main(String[] args) {
                    final byte[] b = {1, 2, 3};
                    OceanOutput(b[0] + b[1] + b[2]);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "FinalArrayTest");
        String output = executeMainAndCaptureOutput(classes, "FinalArrayTest");
        assertEquals("6", output);
    }

    @Test
    public void testByteArrayLiteralOutOfBoundsFailsSemantic() {
        String code = """
            class OutOfBoundsTest {
                public static void function main(String[] args) {
                    byte[] arr = {10, 200, 30};
                }
            }
            """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "OutOfBoundsTest"));
    }

    @Test
    public void testIncompatiblePrimitiveArrayCastFailsSemantic() {
        String code = """
            class IncompatibleArrayCastTest {
                public static void function main(String[] args) {
                    int[] intArr = {1, 2, 3};
                    byte[] byteArr = (byte[]) intArr;
                }
            }
            """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "IncompatibleArrayCastTest"));
    }

    @Test
    public void testIncompatibleIntToDoubleArrayCastFailsSemantic() {
        String code = """
            class IncompatibleIntToDoubleArrayCastTest {
                public static void function main(String[] args) {
                    int[] intArr = {1, 2, 3};
                    double[] doubleArr = (double[]) intArr;
                }
            }
            """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "IncompatibleIntToDoubleArrayCastTest"));
    }

    @Test
    public void testSamePrimitiveArrayCastSucceeds() throws Exception {
        String code = """
            class SameArrayCastTest {
                public static void function main(String[] args) {
                    int[] intArr = {10, 20, 30};
                    int[] sameArr = (int[]) intArr;
                    OceanOutput(sameArr[0] + sameArr[1] + sameArr[2]);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "SameArrayCastTest");
        String output = executeMainAndCaptureOutput(classes, "SameArrayCastTest");
        assertEquals("60", output);
    }

    @Test
    public void testPrimitiveArrayToObjectAndBackCastSucceeds() throws Exception {
        String code = """
            class ObjectArrayCastTest {
                public static void function main(String[] args) {
                    int[] intArr = {5, 15, 25};
                    Object obj = (Object) intArr;
                    int[] backArr = (int[]) obj;
                    OceanOutput(backArr[0] + backArr[1] + backArr[2]);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ObjectArrayCastTest");
        String output = executeMainAndCaptureOutput(classes, "ObjectArrayCastTest");
        assertEquals("45", output);
    }

    @Test
    public void testCharLiteralCompilationAndExecution() throws Exception {
        String code = """
            class CharLiteralTest {
                public static void function main(String[] args) {
                    char c = 'A';
                    OceanOutput(c);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "CharLiteralTest");
        String output = executeMainAndCaptureOutput(classes, "CharLiteralTest");
        assertEquals("A", output);
    }
}