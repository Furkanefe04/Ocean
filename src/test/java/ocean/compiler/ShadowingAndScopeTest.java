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

public class ShadowingAndScopeTest {

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

    private String compileAndExpectError(String code, String className) {
        try {
            compileToBytecodeMap(code, className);
            fail("Expected compilation error for negative test [" + className + "], but compilation succeeded.");
            return null;
        } catch (CompilationException e) {
            String allMsgs = (lastSession != null && !lastSession.messages.isEmpty()) ? lastSession.messages.toString() : e.getMessage();
            return allMsgs;
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

    // ==========================================
    // POSITIVE TESTS
    // ==========================================

    @Test
    public void testConstructorAndMethodParamMatchingFieldSuccess() throws Exception {
        String code = """
            class User {
                String name;
                int age;

                public function User(String name, int age) {
                    this.name = name;
                    this.age = age;
                }

                public void function setName(String name) {
                    this.name = name;
                }

                public static void function main(String[] args) {
                    User u = new User("Alice", 30);
                    OceanOutput(u.name + ":" + u.age);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "User");
        String output = executeMainAndCaptureOutput(classes, "User");
        assertEquals("Alice:30", output);
    }

    @Test
    public void testSiblingBlocksCanUseSameVariableName() throws Exception {
        String code = """
            class SiblingBlocksTest {
                public static void function main(String[] args) {
                    int choice = 1;
                    if (choice == 1) {
                        int temp = 100;
                        OceanOutput(temp);
                    } else {
                        int temp = 200;
                        OceanOutput(temp);
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "SiblingBlocksTest");
        String output = executeMainAndCaptureOutput(classes, "SiblingBlocksTest");
        assertEquals("100", output);
    }

    @Test
    public void testLocalVariableShadowingFieldSucceeds() throws Exception {
        String code = """
            class TestLocalShadow {
                int value = 50;

                public function TestLocalShadow() {
                    this.value = 50;
                }

                public void function calculate() {
                    int value = 100;
                    OceanOutput(value + ":" + this.value);
                }

                public static void function main(String[] args) {
                    TestLocalShadow t = new TestLocalShadow();
                    t.calculate();
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TestLocalShadow");
        String output = executeMainAndCaptureOutput(classes, "TestLocalShadow");
        assertEquals("100:50", output);
    }

    // ==========================================
    // NEGATIVE TESTS (SHADOWING & REDECLARATION)
    // ==========================================

    @Test
    public void testDuplicateLocalVariableSameScopeFails() {
        String code = """
            class DuplicateSameScopeTest {
                public static void function main(String[] args) {
                    int x = 10;
                    int x = 20;
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "DuplicateSameScopeTest");
        assertTrue(errorMsg.contains("x") && (errorMsg.contains("is already defined in this scope") || errorMsg.contains("already defined")),
            "Expected duplicate variable error, got: " + errorMsg);
    }

    @Test
    public void testInnerBlockShadowingOuterLocalFails() {
        String code = """
            class BlockShadowTest {
                public static void function main(String[] args) {
                    int x = 10;
                    if (true) {
                        int x = 20;
                    }
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "BlockShadowTest");
        assertTrue(errorMsg.contains("x") && errorMsg.contains("is already defined in this method scope"),
            "Expected method scope redeclaration error, got: " + errorMsg);
    }

    @Test
    public void testMethodParamShadowedByInnerBlockVariableFails() {
        String code = """
            class ParamShadowBlockTest {
                public void function process(String name) {
                    if (true) {
                        String name = "other";
                    }
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "ParamShadowBlockTest");
        assertTrue(errorMsg.contains("name") && errorMsg.contains("is already defined in this method scope"),
            "Expected parameter shadow error in inner block, got: " + errorMsg);
    }

    @Test
    public void testMethodParamShadowedByLoopVariableFails() {
        String code = """
            class ParamShadowLoopTest {
                public void function loop(int idx) {
                    for (int idx from 1 to 5 with increasing 1) {
                    }
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "ParamShadowLoopTest");
        assertTrue(errorMsg.contains("idx") && errorMsg.contains("is already defined in this method scope"),
            "Expected parameter shadow error in loop variable, got: " + errorMsg);
    }

    @Test
    public void testMethodParamShadowedByCatchVariableFails() {
        String code = """
            class ParamShadowCatchTest {
                public void function handle(Exception ex) {
                    trying {
                        throw new Exception("Err");
                    } catch (Exception ex) {
                        OceanOutput(ex);
                    }
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "ParamShadowCatchTest");
        assertTrue(errorMsg.contains("ex") && errorMsg.contains("is already defined in this method scope"),
            "Expected parameter shadow error in catch clause, got: " + errorMsg);
    }

    @Test
    public void testMethodParamShadowedByLambdaParamFails() {
        String code = """
            import java.util.function.Consumer;
            class ParamShadowLambdaTest {
                public void function doWork(int val) {
                    Consumer c = (Consumer) (int val) -> { OceanOutput(val); };
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "ParamShadowLambdaTest");
        assertTrue(errorMsg.contains("val") && errorMsg.contains("is already defined in this scope"),
            "Expected parameter shadow error in lambda param, got: " + errorMsg);
    }

    @Test
    public void testLocalVariableShadowedByLambdaParamFails() {
        String code = """
            import java.util.function.Consumer;
            class LambdaShadowTest {
                public static void function main(String[] args) {
                    int outerVar = 10;
                    Consumer c = (Consumer) (int outerVar) -> { OceanOutput(outerVar); };
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "LambdaShadowTest");
        assertTrue(errorMsg.contains("outerVar") && errorMsg.contains("is already defined in this scope."),
            "Expected local variable shadow error in lambda param, got: " + errorMsg);
    }

    @Test
    public void testDeepNestedBlockShadowingOuterLocalFails() {
        String code = """
            class DeepNestedShadowTest {
                public static void function main(String[] args) {
                    int depth = 1;
                    {
                        {
                            {
                                int depth = 4;
                            }
                        }
                    }
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "DeepNestedShadowTest");
        assertTrue(errorMsg.contains("depth") && errorMsg.contains("is already defined in this method scope"),
            "Expected deep nested shadow error, got: " + errorMsg);
    }

    @Test
    public void testWhileLoopShadowingOuterLocalFails() {
        String code = """
            class WhileShadowTest {
                public static void function main(String[] args) {
                    int counter = 0;
                    while (counter < 10) {
                        int counter = 1;
                        counter++;
                    }
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "WhileShadowTest");
        assertTrue(errorMsg.contains("counter") && errorMsg.contains("is already defined in this method scope"),
            "Expected while loop body shadow error, got: " + errorMsg);
    }

    @Test
    public void testDuplicateMethodParametersFails() {
        String code = """
            class DuplicateParamTest {
                public void function duplicate(int x, int x) {
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "DuplicateParamTest");
        assertTrue(errorMsg.contains("x") && errorMsg.contains("is already defined in this scope."),
            "Expected duplicate method parameter error, got: " + errorMsg);
    }

    @Test
    public void testDuplicateLambdaParametersFails() {
        String code = """
            import java.util.function.BiConsumer;
            class DuplicateLambdaParamTest {
                public static void function main(String[] args) {
                    BiConsumer bc = (BiConsumer) (int p, int p) -> {};
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "DuplicateLambdaParamTest");
        assertTrue(errorMsg.contains("p") && errorMsg.contains("is already defined in this scope."),
            "Expected duplicate lambda parameter error, got: " + errorMsg);
    }

    @Test
    public void testDuplicateFieldInSameClassFails() {
        String code = """
            class DuplicateFieldTest {
                int fieldX = 10;
                String fieldX = "dup";
            }
            """;
        String errorMsg = compileAndExpectError(code, "DuplicateFieldTest");
        assertTrue(errorMsg.contains("fieldX") && errorMsg.contains("Duplicate field declaration"),
            "Expected duplicate class field error, got: " + errorMsg);
    }
}