package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LowPriorityFourIssuesTest extends CompilerTestHelper {

    // =========================================================================
    // OC-33: Console output pollution and --json flag isolation
    // =========================================================================

    @Test
    @DisplayName("OC-33: --json flag activates isJsonMode and prevents [INFO] logs to stdout")
    void testJsonModeSuppressesInfoLogs() {
        boolean prevMode = OceanRunnerV3.isJsonMode();
        PrintStream originalOut = System.out;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        try {
            OceanRunnerV3.setJsonMode(true);
            assertTrue(OceanRunnerV3.isJsonMode());

            System.setOut(new PrintStream(capturedOut));

            String code = """
                    public class SimpleJsonTarget {
                        public static int function answer() {
                            return 42;
                        }
                    }
                    """;
            Map<String, byte[]> classes = compileToBytecodeMap(code, "SimpleJsonTarget");
            assertFalse(classes.isEmpty());

            String stdout = capturedOut.toString();
            assertFalse(stdout.contains("[INFO]"), "stdout must not contain [INFO] logs when json mode is enabled");
            assertFalse(stdout.contains("[DEBUG]"), "stdout must not contain [DEBUG] logs when json mode is enabled");
        } finally {
            System.setOut(originalOut);
            OceanRunnerV3.setJsonMode(prevMode);
        }
    }

    // =========================================================================
    // OC-34: IRToBytecodeEmitter silent fallbacks
    // =========================================================================

    @Test
    @DisplayName("OC-34: Referencing 'this' in a static context produces compile-time error")
    void testStaticThisProducesError() {
        String code = """
                public class StaticThisTarget {
                    public static void function doWork() {
                        variable x = this;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileToBytecodeMap(code, "StaticThisTarget");
        });
        assertTrue(ex.getMessage().contains("this") || ex.getMessage().contains("static"),
                "Expected error referencing 'this' in static context. Actual: " + ex.getMessage());
    }

    // =========================================================================
    // OC-35: PreScanner constructor AST-based assignment inspection
    // =========================================================================

    @Test
    @DisplayName("OC-35: Constructor with comments and string literals does not corrupt field type via regex")
    void testConstructorCommentsDoNotCorruptFieldType() throws Exception {
        String code = """
                public class RegexSafeTarget {
                    variable age; // untyped field, initial type Object
                    
                    RegexSafeTarget(int ageParam) {
                        // this.age = ageParam in comment
                        String dummy = "this.age=ageParam in string";
                        this.age = ageParam;
                    }
                    
                    public int function getAge() {
                        return (int) this.age;
                    }
                }
                """;
        Class<?> cls = compileAndLoad("RegexSafeTarget", code);
        Object inst = cls.getConstructor(int.class).newInstance(35);
        Method m = cls.getMethod("getAge");
        int age = (int) m.invoke(inst);
        assertEquals(35, age);
    }

    // =========================================================================
    // OC-36: FQCN registry lookup priority
    // =========================================================================

    @Test
    @DisplayName("OC-36: Sibling classes across different packages resolve parameters without cross-package collision")
    void testRegistryLookupFqcnPriority() throws Exception {
        String codePkgA = """
                package com.pkg.a;
                public class Worker {
                    public static int function compute(int a, int b) {
                        return a + b;
                    }
                }
                """;
        Class<?> clsA = compileAndLoad("Worker", codePkgA);
        assertNotNull(clsA);

        String codePkgB = """
                package com.pkg.b;
                public class Worker {
                    public static String function compute(String a, String b) {
                        return a + b;
                    }
                }
                """;
        Class<?> clsB = compileAndLoad("Worker", codePkgB);
        assertNotNull(clsB);

        Method mA = clsA.getMethod("compute", int.class, int.class);
        assertEquals(30, (int) mA.invoke(null, 10, 20));

        Method mB = clsB.getMethod("compute", String.class, String.class);
        assertEquals("HelloWorld", mB.invoke(null, "Hello", "World"));
    }


    @Test
    @DisplayName("OC-05: Syntax error inside class body throws CompilationException")
    void testClassBodySyntaxErrorThrows() {
        String code1 = """
                class SynErr1 {
                    dddd else if;
                    public static void function main() {}
                }
                """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code1, "SynErr1"),
                "Invalid syntax 'dddd else if;' inside class body must throw CompilationException");

        String code2 = """
                class SynErr2 {
                    int x = 5
                    public static void function main() {}
                }
                """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code2, "SynErr2"),
                "Missing semicolon 'int x = 5' inside class body must throw CompilationException");
    }

    // =========================================================================
    // F13: Enum constructor mismatch detection
    // =========================================================================

    @Test
    @DisplayName("F13: Enum with missing or mismatched constructor arguments throws CompilationException")
    void testEnumMismatchedConstructorThrows() {
        String code1 = """
                enum Color1 {
                    RED(100),
                    GREEN("invalid");
                    Color1(int x) {}
                }
                """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code1, "Color1"),
                "Enum constant with incompatible argument type must throw CompilationException");

        String code2 = """
                enum Color2 {
                    RED(100),
                    GREEN;
                }
                """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code2, "Color2"),
                "Enum constant with arguments when no constructor is declared must throw CompilationException");
    }

    // =========================================================================
    // F4, F11, F12: Hardcoded logic tests
    // =========================================================================

    @Test
    @DisplayName("F12: User defined class with length field is not hijacked by OceanList size")
    void testUserFieldLengthNotHijacked() throws Exception {
        String code = """
                public class Article {
                    public int length = 250;
                    public static int function getArticleLength() {
                        Article a = new Article();
                        return a.length;
                    }
                }
                """;
        Class<?> cls = compileAndLoad("Article", code);
        Method m = cls.getMethod("getArticleLength");
        assertEquals(250, (int) m.invoke(null));
    }

    @Test
    @DisplayName("F4: Implicit super constructor call resolves correctly")
    void testImplicitSuperConstructorCall() throws Exception {
        String code = """
                public class Animal {
                    public String sound = "generic";
                }
                public class Dog extends Animal {
                    public String name;
                    public Dog(String name) {
                        this.name = name;
                    }
                    public static String function getSound() {
                        Dog d = new Dog("Rex");
                        return d.sound + "_" + d.name;
                    }
                }
                """;
        Class<?> cls = compileAndLoad("Dog", code);
        Method m = cls.getMethod("getSound");
        assertEquals("generic_Rex", m.invoke(null));
    }

    @Test
    @DisplayName("F11: Centralized emitBox boxes primitives correctly")
    void testBoxingPrimitives() throws Exception {
        String code = """
                public class BoxTester {
                    public static Object function boxInt(int x) {
                        return (Object) x;
                    }
                    public static Object function boxDouble(double d) {
                        return (Object) d;
                    }
                }
                """;
        Class<?> cls = compileAndLoad("BoxTester", code);
        Method m1 = cls.getMethod("boxInt", int.class);
        assertEquals(42, m1.invoke(null, 42));
        Method m2 = cls.getMethod("boxDouble", double.class);
        assertEquals(3.14, (double) m2.invoke(null, 3.14), 0.001);
    }
}



