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

public class PrimitiveListBoxingTest {

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

    private boolean containsMethodCall(byte[] classBytes, String owner, String methodName, String desc) {
        AtomicBoolean found = new AtomicBoolean(false);
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String mOwner, String mName, String mDescriptor, boolean isInterface) {
                        if (mOwner.equals(owner) && mName.equals(methodName) && mDescriptor.equals(desc)) {
                            found.set(true);
                        }
                    }
                };
            }
        }, 0);
        return found.get();
    }

    private boolean containsCheckcast(byte[] classBytes, String castTarget) {
        AtomicBoolean found = new AtomicBoolean(false);
        ClassReader cr = new ClassReader(classBytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (opcode == Opcodes.CHECKCAST && type.equals(castTarget)) {
                            found.set(true);
                        }
                    }
                };
            }
        }, 0);
        return found.get();
    }

    @Test
    public void testOceanIntListDirectPrimitiveGetAndSet() throws Exception {
        String code = """
            class IntListOptTest {
                public static void function main(String[] args) {
                    variable list = [10, 20, 30];
                    int first = list[0];
                    list[1] = 99;
                    int second = list[1];
                    OceanOutput(first + ":" + second);
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "IntListOptTest");
        String output = executeMainAndCaptureOutput(classes, "IntListOptTest");
        assertEquals("10:99", output);

        byte[] mainBytes = classes.get("IntListOptTest/IntListOptTest");
        if (mainBytes == null) mainBytes = classes.get("IntListOptTest");
        assertNotNull(mainBytes);
        assertTrue(containsMethodCall(mainBytes, "ocean/stdlib/OceanIntList", "getInt", "(I)I"),
            "OceanIntList access must call primitive getInt(I)I directly without boxing");
        assertFalse(containsCheckcast(mainBytes, "java/lang/Number"),
            "No CHECKCAST java/lang/Number should be emitted for primitive list access");
    }

    @Test
    public void testOceanDoubleListDirectPrimitiveGet() throws Exception {
        String code = """
            class DoubleListOptTest {
                public static void function main(String[] args) {
                    variable list = [1.5, 2.5, 3.5];
                    double val = list[1];
                    OceanOutput("val=" + val);
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "DoubleListOptTest");
        String output = executeMainAndCaptureOutput(classes, "DoubleListOptTest");
        assertEquals("val=2.5", output);

        byte[] mainBytes = classes.get("DoubleListOptTest/DoubleListOptTest");
        if (mainBytes == null) mainBytes = classes.get("DoubleListOptTest");
        assertNotNull(mainBytes);
        assertTrue(containsMethodCall(mainBytes, "ocean/stdlib/OceanDoubleList", "getDouble", "(I)D"),
            "OceanDoubleList access must call primitive getDouble(I)D directly without boxing");
    }

    @Test
    public void testOceanIntListForeachLoopDirectPrimitiveIteration() throws Exception {
        String code = """
            import ocean.stdlib.OceanIntList;

            class ForeachOptTest {
                public static void function main(String[] args) {
                    OceanIntList list = [10, 20, 30];
                    int sum = 0;
                    for (int val in list) {
                        sum = sum + val;
                    }
                    OceanOutput(sum);
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "ForeachOptTest");
        String output = executeMainAndCaptureOutput(classes, "ForeachOptTest");
        assertEquals("60", output);

        byte[] mainBytes = classes.get("ForeachOptTest/ForeachOptTest");
        if (mainBytes == null) mainBytes = classes.get("ForeachOptTest");
        assertNotNull(mainBytes);

        // Assert direct getInt(I)I call in loop
        assertTrue(containsMethodCall(mainBytes, "ocean/stdlib/OceanIntList", "getInt", "(I)I"),
            "Foreach on OceanIntList must call primitive getInt(I)I directly");

        // Assert NO iterator() or CHECKCAST java/lang/Number
        assertFalse(containsMethodCall(mainBytes, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;"),
            "Foreach on primitive list must NOT allocate or call iterator()");
        assertFalse(containsCheckcast(mainBytes, "java/lang/Number"),
            "Foreach on primitive list must NOT emit CHECKCAST java/lang/Number");
    }
}