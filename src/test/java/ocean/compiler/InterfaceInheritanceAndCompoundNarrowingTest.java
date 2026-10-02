package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class InterfaceInheritanceAndCompoundNarrowingTest extends CompilerTestHelper {

    /** Returns true if the current CompilerReporter session has an error whose text contains ALL given fragments. */
    private boolean hasErrorContaining(String... fragments) {
        List<CompilerReporter.Message> msgs = CompilerReporter.getMessages();
        return msgs.stream()
                .filter(m -> m.level() == CompilerReporter.Level.ERROR)
                .anyMatch(m -> {
                    for (String frag : fragments) {
                        if (!m.text().contains(frag)) return false;
                    }
                    return true;
                });
    }

    /** Runs compileToBytecodeMap, swallowing any CompilationException (errors remain in CompilerReporter). */
    private Map<String, byte[]> tryCompile(String code, String className) {
        try {
            return compileToBytecodeMap(code, className);
        } catch (CompilationException e) {
            return java.util.Collections.emptyMap();
        }
    }

    // =========================================================================
    // 1. INTERFACE IMPLEMENTS & EXTENDS PARITY TESTS
    // =========================================================================

    @Test
    @DisplayName("Interface using 'implements' inherits superinterface and generates correct bytecode")
    public void testInterfaceWithImplementsSucceeds() throws Exception {
        String code = """
                public interface BaseGreeting {
                    String function greet();
                }
                public interface ChildGreeting implements BaseGreeting {
                    int function code();
                }
                public class GreetingImpl implements ChildGreeting {
                    public String function greet() {
                        return "Hello from Ocean";
                    }
                    public int function code() {
                        return 200;
                    }
                }
                """;

        CompilerReporter.clear();
        Map<String, Class<?>> classes = compileAndLoadAll("GreetingImpl", code);
        assertNotNull(classes);

        Class<?> baseIface = classes.get("BaseGreeting");
        Class<?> childIface = classes.get("ChildGreeting");
        Class<?> implClass = classes.get("GreetingImpl");

        assertNotNull(baseIface, "BaseGreeting should be compiled and loaded");
        assertNotNull(childIface, "ChildGreeting should be compiled and loaded");
        assertNotNull(implClass, "GreetingImpl should be compiled and loaded");

        assertTrue(baseIface.isAssignableFrom(childIface),
                "ChildGreeting interface should extend BaseGreeting at JVM level when 'implements' is used");
        assertTrue(baseIface.isAssignableFrom(implClass),
                "GreetingImpl should implement BaseGreeting indirectly");

        Object instance = implClass.getDeclaredConstructor().newInstance();
        Method greetMethod = implClass.getMethod("greet");
        Method codeMethod = implClass.getMethod("code");

        assertEquals("Hello from Ocean", greetMethod.invoke(instance));
        assertEquals(200, codeMethod.invoke(instance));
    }

    @Test
    @DisplayName("Interface using both 'extends' and 'implements' inherits all superinterfaces")
    public void testInterfaceWithExtendsAndImplementsCombinedSucceeds() throws Exception {
        String code = """
                public interface SourceA {
                    int function getA();
                }
                public interface SourceB {
                    int function getB();
                }
                public interface CombinedTarget implements SourceA, SourceB {
                    int function getC();
                }
                public class CombinedImpl implements CombinedTarget {
                    public int function getA() { return 1; }
                    public int function getB() { return 2; }
                    public int function getC() { return 3; }
                }
                """;

        CompilerReporter.clear();
        Map<String, Class<?>> classes = compileAndLoadAll("CombinedImpl", code);
        assertNotNull(classes);

        Class<?> srcA = classes.get("SourceA");
        Class<?> srcB = classes.get("SourceB");
        Class<?> combined = classes.get("CombinedTarget");
        Class<?> implClass = classes.get("CombinedImpl");

        assertNotNull(srcA);
        assertNotNull(srcB);
        assertNotNull(combined);
        assertNotNull(implClass);

        assertTrue(srcA.isAssignableFrom(combined), "CombinedTarget must inherit SourceA");
        assertTrue(srcB.isAssignableFrom(combined), "CombinedTarget must inherit SourceB");
        assertTrue(combined.isAssignableFrom(implClass), "CombinedImpl must implement CombinedTarget");

        Object instance = implClass.getDeclaredConstructor().newInstance();
        assertEquals(1, implClass.getMethod("getA").invoke(instance));
        assertEquals(2, implClass.getMethod("getB").invoke(instance));
        assertEquals(3, implClass.getMethod("getC").invoke(instance));
    }

    @Test
    @DisplayName("Interface attempting to implement a class fails compilation")
    public void testInterfaceImplementingClassFails() {
        String code = """
                public class ParentConcreteClass {}
                public interface BadInterface implements ParentConcreteClass {}
                """;

        CompilerReporter.clear();
        tryCompile(code, "BadInterface");
        assertTrue(CompilerReporter.hasErrors(),
                "Interface implementing a concrete class should fail compilation");
        assertTrue(hasErrorContaining("Interfaces can only extend other interfaces"),
                "Expected error rejecting interface extending/implementing a class");
    }

    // =========================================================================
    // 2. COMPOUND ASSIGNMENT NARROWING SAFETY TESTS
    // =========================================================================

    @Test
    @DisplayName("Compound assignment double to int without explicit cast fails compilation")
    public void testCompoundAssignmentDoubleToIntFails() {
        String code = """
                public class TestDoubleToInt {
                    public static void function test() {
                        int x = 1;
                        x += 2.5;
                    }
                }
                """;

        CompilerReporter.clear();
        tryCompile(code, "TestDoubleToInt");
        assertTrue(CompilerReporter.hasErrors(),
                "Compound assignment from double to int should fail without explicit cast");
        assertTrue(hasErrorContaining("Narrowing compound assignment"),
                "Expected narrowing error message mentioning daraltıcı tip dönüşümü");
    }

    @Test
    @DisplayName("Compound assignment double to short without explicit cast fails compilation")
    public void testCompoundAssignmentDoubleToShortFails() {
        String code = """
                public class TestDoubleToShort {
                    public static void function test() {
                        short s = 1;
                        s += 1.5;
                    }
                }
                """;

        CompilerReporter.clear();
        tryCompile(code, "TestDoubleToShort");
        assertTrue(CompilerReporter.hasErrors(),
                "Compound assignment from double to short should fail without explicit cast");
        assertTrue(hasErrorContaining("Narrowing compound assignment"),
                "Expected narrowing error message");
    }

    @Test
    @DisplayName("Compound assignment long to int without explicit cast fails compilation")
    public void testCompoundAssignmentLongToIntFails() {
        String code = """
                public class TestLongToInt {
                    public static void function test() {
                        int x = 1;
                        x += 10L;
                    }
                }
                """;

        CompilerReporter.clear();
        tryCompile(code, "TestLongToInt");
        assertTrue(CompilerReporter.hasErrors(),
                "Compound assignment from long to int should fail without explicit cast");
        assertTrue(hasErrorContaining("Narrowing compound assignment"),
                "Expected narrowing error message");
    }

    @Test
    @DisplayName("Compound assignment with explicit cast succeeds and executes accurately")
    public void testCompoundAssignmentWithExplicitCastSucceeds() throws Exception {
        String code = """
                public class TestExplicitCast {
                    public static int function runTest() {
                        int x = 10;
                        x += (int) 2.5;
                        short s = 5;
                        s += (short) 1.5;
                        return x + (int) s;
                    }
                }
                """;

        CompilerReporter.clear();
        Class<?> clazz = compileAndLoad("TestExplicitCast", code);
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(null);
        // x = 10 + 2 = 12; s = 5 + 1 = 6; res = 12 + 6 = 18
        assertEquals(18, res);
    }

    @Test
    @DisplayName("Safe compound assignments with widening or same types succeed cleanly")
    public void testCompoundAssignmentSafeWideningSucceeds() throws Exception {
        String code = """
                public class TestSafeWidening {
                    public static double function runTest() {
                        double d = 1.0;
                        d += 2;      // int widens to double
                        d += 1.5;    // double to double
                        long l = 10L;
                        l += 5;      // int widens to long
                        byte b = 1;
                        b += 2;      // natural integer addition within byte
                        return d + (double) l + (double) b;
                    }
                }
                """;

        CompilerReporter.clear();
        Class<?> clazz = compileAndLoad("TestSafeWidening", code);
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        double res = (double) m.invoke(null);
        // d = 1.0 + 2 + 1.5 = 4.5; l = 10 + 5 = 15; b = 1 + 2 = 3; total = 4.5 + 15 + 3 = 22.5
        assertEquals(22.5, res, 0.0001);
    }
}