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

public class RecordPatternMatchingTest {

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
    public void testDataClassDeconstructionInSwitchExpr() throws Exception {
        String code = """
            data class Point(int x, int y)

            class RecordPatternTest1 {
                public static String function describe(Object? obj) {
                    return switch (obj) {
                        case null -> "NULL"
                        case Point(int x, int y) when x == y -> "DIAGONAL:" + x
                        case Point(int x, int y) -> "POINT(" + x + ", " + y + ")"
                        default -> "OTHER"
                    };
                }

                public static void function main(String[] args) {
                    OceanOutput(describe(null));
                    OceanOutput(describe(new Point(5, 5)));
                    OceanOutput(describe(new Point(3, 7)));
                    OceanOutput(describe("HelloWorld"));
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "RecordPatternTest1");
        String output = executeMainAndCaptureOutput(classes, "RecordPatternTest1");
        assertEquals("NULL\nDIAGONAL:5\nPOINT(3, 7)\nOTHER", output.replace("\r\n", "\n"));
    }

    @Test
    public void testNestedDataClassDeconstruction() throws Exception {
        String code = """
            data class Point(int x, int y)
            data class Circle(Point center, double radius)

            class RecordPatternTest2 {
                public static String function describeShape(Object obj) {
                    return switch (obj) {
                        case Circle(Point(int x, int y), double r) when x == 0 && y == 0 -> "ORIGIN_CIRCLE_R:" + r
                        case Circle(Point(int x, int y), double r) -> "CIRCLE_AT(" + x + "," + y + ")_R:" + r
                        case Point(int x, int y) -> "JUST_POINT(" + x + "," + y + ")"
                        default -> "UNKNOWN"
                    };
                }

                public static void function main(String[] args) {
                    OceanOutput(describeShape(new Circle(new Point(0, 0), 5.0)));
                    OceanOutput(describeShape(new Circle(new Point(10, 20), 2.5)));
                    OceanOutput(describeShape(new Point(1, 2)));
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "RecordPatternTest2");
        String output = executeMainAndCaptureOutput(classes, "RecordPatternTest2");
        assertEquals("ORIGIN_CIRCLE_R:5.0\nCIRCLE_AT(10,20)_R:2.5\nJUST_POINT(1,2)", output.replace("\r\n", "\n"));
    }

    @Test
    public void testRecordPatternInSwitchStatement() throws Exception {
        String code = """
            data class Point(int x, int y)

            class RecordPatternTest3 {
                public static String function testStmt(Object? obj) {
                    variable res = "";
                    switch (obj) {
                        case null:
                            res = "NULL";
                            stop;
                        case Point(int x, int y) when x > 10:
                            res = "BIG_X:" + x;
                            stop;
                        case Point(int x, int y):
                            res = "POINT:" + (x + y);
                            stop;
                        default:
                            res = "OTHER";
                            stop;
                    }
                    return res;
                }

                public static void function main(String[] args) {
                    OceanOutput(testStmt(null));
                    OceanOutput(testStmt(new Point(15, 2)));
                    OceanOutput(testStmt(new Point(3, 4)));
                    OceanOutput(testStmt("Test"));
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "RecordPatternTest3");
        String output = executeMainAndCaptureOutput(classes, "RecordPatternTest3");
        assertEquals("NULL\nBIG_X:15\nPOINT:7\nOTHER", output.replace("\r\n", "\n"));
    }

    @Test
    public void testRecordPatternInInstanceOf() throws Exception {
        String code = """
            data class Point(int x, int y)

            class RecordPatternTest4 {
                public static String function testInstanceof(Object? obj) {
                    if (obj instanceof Point(int x, int y)) {
                        return "MATCHED:" + x + "," + y;
                    }
                    return "NOT_MATCHED";
                }

                public static void function main(String[] args) {
                    OceanOutput(testInstanceof(new Point(42, 99)));
                    OceanOutput(testInstanceof("Not a point"));
                    OceanOutput(testInstanceof(null));
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "RecordPatternTest4");
        String output = executeMainAndCaptureOutput(classes, "RecordPatternTest4");
        assertEquals("MATCHED:42,99\nNOT_MATCHED\nNOT_MATCHED", output.replace("\r\n", "\n"));
    }

    @Test
    public void testFullRecordBindingAndMixedValuePattern() throws Exception {
        String code = """
            data class Point(int x, int y)

            class RecordPatternTest5 {
                public static String function classify(Object obj) {
                    return switch (obj) {
                        case Point(int x, 0) -> "X_AXIS:" + x
                        case Point(0, int y) -> "Y_AXIS:" + y
                        case Point(int x, int y) p -> "POINT_OBJECT:" + (p.getX() + p.getY())
                        default -> "OTHER"
                    };
                }

                public static void function main(String[] args) {
                    OceanOutput(classify(new Point(7, 0)));
                    OceanOutput(classify(new Point(0, 9)));
                    OceanOutput(classify(new Point(10, 20)));
                    OceanOutput(classify("Hello"));
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "RecordPatternTest5");
        String output = executeMainAndCaptureOutput(classes, "RecordPatternTest5");
        assertEquals("X_AXIS:7\nY_AXIS:9\nPOINT_OBJECT:30\nOTHER", output.replace("\r\n", "\n"));
    }

    @Test
    public void testDeeplyNestedRecords() throws Exception {
        String code = """
            data class Point(int x, int y)
            data class Rectangle(Point topLeft, Point bottomRight)
            data class Window(Rectangle bounds, String title)

            class RecordPatternTest6 {
                public static String function describeWindow(Object? obj) {
                    return switch (obj) {
                        case Window(Rectangle(Point(int x1, int y1), Point(int x2, int y2)), String title) when x1 == 0 && y1 == 0 ->
                            "TOPLEFT_ZERO_WINDOW:" + title + " width=" + (x2 - x1) + " height=" + (y2 - y1)
                        case Window(Rectangle(Point(int x1, int y1), Point(int x2, int y2)), String title) ->
                            "WINDOW:" + title + " at (" + x1 + "," + y1 + ")"
                        default -> "UNKNOWN"
                    };
                }

                public static void function main(String[] args) {
                    OceanOutput(describeWindow(new Window(new Rectangle(new Point(0, 0), new Point(800, 600)), "MainApp")));
                    OceanOutput(describeWindow(new Window(new Rectangle(new Point(100, 100), new Point(500, 400)), "Popup")));
                    OceanOutput(describeWindow(null));
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "RecordPatternTest6");
        String output = executeMainAndCaptureOutput(classes, "RecordPatternTest6");
        assertEquals("TOPLEFT_ZERO_WINDOW:MainApp width=800 height=600\nWINDOW:Popup at (100,100)\nUNKNOWN", output.replace("\r\n", "\n"));
    }
}