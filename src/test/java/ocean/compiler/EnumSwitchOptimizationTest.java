package ocean.compiler;

import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class EnumSwitchOptimizationTest {

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
        System.setOut(new PrintStream(baos));
        try {
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

    private boolean containsTableOrLookupSwitch(byte[] classBytes) {
        AtomicBoolean found = new AtomicBoolean(false);
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt, org.objectweb.asm.Label... labels) {
                        found.set(true);
                    }

                    @Override
                    public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys, org.objectweb.asm.Label[] labels) {
                        found.set(true);
                    }
                };
            }
        }, 0);
        return found.get();
    }

    @Test
    public void testEnumSwitchStatementExecutionAndBytecode() throws Exception {
        String code = """
            enum Direction { NORTH, SOUTH, EAST, WEST }

            class EnumSwitchStmtTest {
                public static void function testDirection(Direction d) {
                    switch (d) {
                        case Direction.NORTH -> OceanOutput("N");
                        case Direction.SOUTH -> OceanOutput("S");
                        case Direction.EAST  -> OceanOutput("E");
                        case Direction.WEST  -> OceanOutput("W");
                        default -> OceanOutput("UNKNOWN");
                    }
                }

                public static void function main(String[] args) {
                    testDirection(Direction.EAST);
                    testDirection(Direction.NORTH);
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "EnumSwitchStmtTest");
        String output = executeMainAndCaptureOutput(classes, "EnumSwitchStmtTest");
        assertEquals("E\nN", output.replace("\r\n", "\n"));

        byte[] mainBytes = classes.get("EnumSwitchStmtTest/EnumSwitchStmtTest");
        if (mainBytes == null) mainBytes = classes.get("EnumSwitchStmtTest");
        assertTrue(containsTableOrLookupSwitch(mainBytes), "Enum switch statement must emit TABLESWITCH / LOOKUPSWITCH (O(1))");
    }

    @Test
    public void testEnumSwitchExpressionExecutionAndBytecode() throws Exception {
        String code = """
            enum TrafficLight { RED, YELLOW, GREEN }

            class EnumSwitchExprTest {
                public static String function getAction(TrafficLight light) {
                    return switch (light) {
                        case TrafficLight.RED    -> "STOP"
                        case TrafficLight.YELLOW -> "WAIT"
                        case TrafficLight.GREEN  -> "GO"
                        default -> "NONE"
                    };
                }

                public static void function main(String[] args) {
                    OceanOutput(getAction(TrafficLight.GREEN) + ":" + getAction(TrafficLight.RED));
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "EnumSwitchExprTest");
        String output = executeMainAndCaptureOutput(classes, "EnumSwitchExprTest");
        assertEquals("GO:STOP", output);

        byte[] mainBytes = classes.get("EnumSwitchExprTest/EnumSwitchExprTest");
        if (mainBytes == null) mainBytes = classes.get("EnumSwitchExprTest");
        assertTrue(containsTableOrLookupSwitch(mainBytes), "Enum switch expression must emit TABLESWITCH / LOOKUPSWITCH (O(1))");
    }

    @Test
    public void testEnumSwitchWithColonFallthrough() throws Exception {
        String code = """
            enum Status { PENDING, RUNNING, FINISHED, FAILED }

            class EnumFallthroughTest {
                public static void function checkStatus(Status s) {
                    switch (s) {
                        case Status.PENDING:
                        case Status.RUNNING: {
                            OceanOutput("IN_PROGRESS");
                            stop;
                        }
                        case Status.FINISHED: {
                            OceanOutput("DONE");
                            stop;
                        }
                        default: {
                            OceanOutput("OTHER");
                        }
                    }
                }

                public static void function main(String[] args) {
                    checkStatus(Status.PENDING);
                    checkStatus(Status.RUNNING);
                    checkStatus(Status.FINISHED);
                    checkStatus(Status.FAILED);
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "EnumFallthroughTest");
        String output = executeMainAndCaptureOutput(classes, "EnumFallthroughTest");
        assertEquals("IN_PROGRESS\nIN_PROGRESS\nDONE\nOTHER", output.replace("\r\n", "\n"));
    }

    @Test
    public void testExternalJavaEnumSwitch() throws Exception {
        String code = """
            import java.lang.annotation.RetentionPolicy;

            class ExternalEnumSwitchTest {
                public static void function checkPolicy(RetentionPolicy p) {
                    switch (p) {
                        case RetentionPolicy.SOURCE  -> OceanOutput("SOURCE");
                        case RetentionPolicy.CLASS   -> OceanOutput("CLASS");
                        case RetentionPolicy.RUNTIME -> OceanOutput("RUNTIME");
                        default -> OceanOutput("OTHER");
                    }
                }

                public static void function main(String[] args) {
                    checkPolicy(RetentionPolicy.RUNTIME);
                    checkPolicy(RetentionPolicy.SOURCE);
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "ExternalEnumSwitchTest");
        String output = executeMainAndCaptureOutput(classes, "ExternalEnumSwitchTest");
        assertEquals("RUNTIME\nSOURCE", output.replace("\r\n", "\n"));

        byte[] mainBytes = classes.get("ExternalEnumSwitchTest/ExternalEnumSwitchTest");
        if (mainBytes == null) mainBytes = classes.get("ExternalEnumSwitchTest");
        assertTrue(containsTableOrLookupSwitch(mainBytes), "External Java enum switch must emit TABLESWITCH / LOOKUPSWITCH (O(1))");
    }
}