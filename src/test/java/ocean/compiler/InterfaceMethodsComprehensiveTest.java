package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class InterfaceMethodsComprehensiveTest extends CompilerTestHelper {

    @Test
    @DisplayName("Interface default method inheritance without overriding")
    public void testDefaultMethodInheritance() throws Exception {
        String code = """
                public interface Greeter {
                    String function name();

                    String function greet() {
                        return "Hello, " + name();
                    }
                }

                public class EnglishGreeter implements Greeter {
                    public String function name() {
                        return "World";
                    }
                }

                public class TestRunner {
                    public static String function run() {
                        Greeter g = new EnglishGreeter();
                        return g.greet();
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals("Hello, World", m.invoke(null));
    }

    @Test
    @DisplayName("Interface default method overridden in implementing class")
    public void testDefaultMethodOverriding() throws Exception {
        String code = """
                public interface Greeter {
                    String function greet() {
                        return "Default Hello";
                    }
                }

                public class CustomGreeter implements Greeter {
                    public String function greet() {
                        return "Custom Greetings";
                    }
                }

                public class TestRunner {
                    public static String function run() {
                        Greeter g = new CustomGreeter();
                        return g.greet();
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals("Custom Greetings", m.invoke(null));
    }

    @Test
    @DisplayName("Interface static method call: MathOps.multiply(6, 7)")
    public void testStaticInterfaceMethod() throws Exception {
        String code = """
                public interface MathOps {
                    public static int function multiply(int a, int b) {
                        return a * b;
                    }
                }

                public class TestRunner {
                    public static int function run() {
                        return MathOps.multiply(6, 7);
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals(42, m.invoke(null));
    }

    @Test
    @DisplayName("Interface default method calling private helper in interface")
    public void testPrivateInterfaceHelperMethod() throws Exception {
        String code = """
                public interface Formatter {
                    private String function formatInternal(String msg) {
                        return "[" + msg + "]";
                    }

                    String function format(String msg) {
                        return formatInternal(msg);
                    }
                }

                public class SimpleFormatter implements Formatter {
                }

                public class TestRunner {
                    public static String function run() {
                        Formatter f = new SimpleFormatter();
                        return f.format("Ocean");
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals("[Ocean]", m.invoke(null));
    }

    @Test
    @DisplayName("Multiple interfaces with independent default methods")
    public void testMultipleInterfaceDefaultInheritance() throws Exception {
        String code = """
                public interface Alpha {
                    String function alphaMsg() {
                        return "A";
                    }
                }

                public interface Beta {
                    String function betaMsg() {
                        return "B";
                    }
                }

                public class DualClass implements Alpha, Beta {
                }

                public class TestRunner {
                    public static String function run() {
                        DualClass d = new DualClass();
                        return d.alphaMsg() + d.betaMsg();
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals("AB", m.invoke(null));
    }

    @Test
    @DisplayName("Functional interface with SAM + multiple default and static methods")
    public void testSAMWithDefaultAndStaticMethods() throws Exception {
        String code = """
                public interface Calculator {
                    int function compute(int x, int y);

                    int function computeWithBonus(int x, int y) {
                        return compute(x, y) + 10;
                    }

                    public static Calculator function ofPlus() {
                        return (a, b) -> a + b;
                    }
                }

                public class TestRunner {
                    public static int function run() {
                        Calculator calc = Calculator.ofPlus();
                        return calc.computeWithBonus(5, 5);
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals(20, m.invoke(null));
    }
}