package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SuperAbstractMethodCallTest extends CompilerTestHelper {

    @Test
    @DisplayName("Calling abstract method on direct superclass via 'super' fails compilation")
    public void testSuperCallingAbstractMethodFails() {
        String code = """
                abstract class AbstractParent {
                    public abstract void function run();
                }
                public class SuperAbstractChild extends AbstractParent {
                    public void function run() {
                        super.run();
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("SuperAbstractChild", code));
        assertTrue(ex.getMessage().contains("Method 'run' invoked via 'super' is abstract"),
                "Expected abstract super method call error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Calling inherited abstract method via 'super' through multi-level abstract hierarchy fails compilation")
    public void testSuperCallingMultiLevelAbstractMethodFails() {
        String code = """
                abstract class GrandParent {
                    public abstract int function calculate(int a);
                }
                abstract class IntermediateParent extends GrandParent {
                }
                public class MultiLevelChild extends IntermediateParent {
                    public int function calculate(int a) {
                        return super.calculate(a) + 1;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("MultiLevelChild", code));
        assertTrue(ex.getMessage().contains("Method 'calculate' invoked via 'super' is abstract"),
                "Expected abstract super method call error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Calling concrete method on superclass via 'super' succeeds")
    public void testSuperCallingConcreteMethodSucceeds() throws Exception {
        String code = """
                class ConcreteParent {
                    public int function getValue() {
                        return 42;
                    }
                }
                public class ConcreteChild extends ConcreteParent {
                    public int function getValue() {
                        return super.getValue() + 10;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ConcreteChild", code);
        Class<?> clazz = classes.get("ConcreteChild");
        assertNotNull(clazz);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("getValue");
        assertEquals(52, m.invoke(instance));
    }

    @Test
    @DisplayName("Calling concrete method defined in an abstract superclass via 'super' succeeds")
    public void testSuperCallingOverriddenConcreteMethodInAbstractParentSucceeds() throws Exception {
        String code = """
                abstract class AbstractBase {
                    public abstract int function compute();
                    public int function baseValue() {
                        return 100;
                    }
                }
                public class ConcreteSub extends AbstractBase {
                    public int function compute() {
                        return super.baseValue() * 2;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ConcreteSub", code);
        Class<?> clazz = classes.get("ConcreteSub");
        assertNotNull(clazz);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("compute");
        assertEquals(200, m.invoke(instance));
    }

    @Test
    @DisplayName("Calling abstract method of external JDK class via 'super' fails compilation")
    public void testSuperCallingJdkAbstractMethodFails() {
        String code = """
                import java.io.InputStream;
                public class JdkSubStream extends InputStream {
                    public int function read() {
                        return super.read(); // InputStream.read() is abstract!
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("JdkSubStream", code));
        assertTrue(ex.getMessage().contains("Method 'read' invoked via 'super' is abstract"),
                "Expected abstract super method call error on JDK class, got: " + ex.getMessage());
    }
}
