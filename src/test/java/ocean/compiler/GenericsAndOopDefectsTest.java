package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenericsAndOopDefectsTest extends CompilerTestHelper {

    @BeforeEach
    void setup() {
        CompilerRegistry.clearAll();
        CompilerReporter.clear();
        ClassMetadataCache.clearCaches();
    }

    @Test
    @DisplayName("Defect 1: Generics multiple intersection bounds method resolution")
    void testGenericsMultipleBounds() throws Exception {
        Class<?> clazz = compileAndLoad("GenericsBoundsProbe", """
                interface Reader { String function read(); }
                interface Writer { String function write(); }
                class Processor<T extends Reader & Writer> {
                    public String function process(T item) {
                        return item.read() + "-" + item.write();
                    }
                }
                class DualStream implements Reader, Writer {
                    public String function read() { return "data_read"; }
                    public String function write() { return "data_written"; }
                }
                public class GenericsBoundsProbe {
                    public static String function test() {
                        Processor<DualStream> p = new Processor<DualStream>();
                        return p.process(new DualStream());
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals("data_read-data_written", m.invoke(null));
    }

    @Test
    @DisplayName("Defect 2: Interface explicit super call invokes default method")
    void testInterfaceExplicitSuperCall() throws Exception {
        Class<?> clazz = compileAndLoad("InterfaceSuperProbe", """
                interface Alpha {
                    default String function greet() { return "Alpha"; }
                }
                interface Beta extends Alpha {
                    default String function greet() {
                        return "Beta -> " + Alpha.super.greet();
                    }
                }
                class Gamma implements Beta {}
                public class InterfaceSuperProbe {
                    public static String function test() {
                        return new Gamma().greet();
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals("Beta -> Alpha", m.invoke(null));
    }

    @Test
    @DisplayName("Defect 3: Inner class inheriting from sibling inner class in enclosing scope")
    void testInnerClassSiblingInheritance() throws Exception {
        Class<?> clazz = compileAndLoad("InnerInheritProbe", """
                public class InnerInheritProbe {
                    static class Base {
                        public String function msg() { return "base"; }
                    }
                    static class Derived extends Base {
                        @Override
                        public String function msg() { return "derived"; }
                    }
                    public static String function test() {
                        Derived d = new Derived();
                        Base b = d;
                        return b.msg();
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals("derived", m.invoke(null));
    }

    @Test
    @DisplayName("Defect 4: Explicit generic method type arguments")
    void testExplicitGenericMethodTypeArguments() throws Exception {
        Class<?> clazz = compileAndLoad("ExplicitTypeArgsProbe", """
                class GenericHelper {
                    public static <T> T function identity(T val) { return val; }
                }
                public class ExplicitTypeArgsProbe {
                    public static String function test() {
                        String s = GenericHelper.<String>identity("hello_explicit");
                        return s;
                    }
                }
                """);
        Method m = clazz.getMethod("test");
        assertEquals("hello_explicit", m.invoke(null));
    }
}
