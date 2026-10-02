package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ConstructorPlacementSafetyTest extends CompilerTestHelper {

    @Test
    @DisplayName("super() inside if-block fails compilation")
    public void testSuperInsideIfFails() {
        String code = """
                public class SuperInIfTest {
                    public SuperInIfTest(boolean cond) {
                        if (cond) {
                            super();
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("SuperInIfTest", code));
        assertTrue(ex.getMessage().contains("cannot be nested in conditional blocks"),
                "Expected placement error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("this() inside if-block fails compilation")
    public void testThisInsideIfFails() {
        String code = """
                public class ThisInIfTest {
                    public ThisInIfTest() {
                        this(1);
                    }
                    public ThisInIfTest(int x) {
                        if (x > 0) {
                            this();
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("ThisInIfTest", code));
        assertTrue(ex.getMessage().contains("cannot be nested in conditional blocks"),
                "Expected placement error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("super() inside while-loop fails compilation")
    public void testSuperInsideWhileFails() {
        String code = """
                public class SuperInWhileTest {
                    public SuperInWhileTest() {
                        while (true) {
                            super();
                            stop;
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("SuperInWhileTest", code));
        assertTrue(ex.getMessage().contains("cannot be nested in conditional blocks"),
                "Expected placement error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("super() inside try-catch block fails compilation")
    public void testSuperInsideTryCatchFails() {
        String code = """
                import java.lang.Exception;
                public class SuperInTryCatchTest {
                    public SuperInTryCatchTest() {
                        trying {
                            super();
                        } catch (Exception e) {
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("SuperInTryCatchTest", code));
        assertTrue(ex.getMessage().contains("cannot be nested in conditional blocks"),
                "Expected placement error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("super() inside nested local block fails compilation")
    public void testSuperInsideNestedBlockFails() {
        String code = """
                public class SuperInNestedBlockTest {
                    public SuperInNestedBlockTest() {
                        {
                            super();
                        }
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("SuperInNestedBlockTest", code));
        assertTrue(ex.getMessage().contains("cannot be nested in conditional blocks"),
                "Expected placement error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Multiple this() invocations in constructor fails compilation")
    public void testMultipleThisCallsFails() {
        String code = """
                public class MultiThisTest {
                    public MultiThisTest() {
                        this(1);
                        this(2);
                    }
                    public MultiThisTest(int x) {}
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("MultiThisTest", code));
        assertTrue(ex.getMessage().contains("multiple 'super()' or 'this()'"),
                "Expected multiple ctor error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("super() followed by this() fails compilation")
    public void testSuperFollowedByThisFails() {
        String code = """
                public class SuperFollowedByThisTest {
                    public SuperFollowedByThisTest() {
                        super();
                        this(1);
                    }
                    public SuperFollowedByThisTest(int x) {}
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("SuperFollowedByThisTest", code));
        assertTrue(ex.getMessage().contains("multiple 'super()' or 'this()'"),
                "Expected multiple ctor error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid top-level super() and this() constructors compile and run cleanly")
    public void testValidConstructorPlacementCompilesAndRuns() throws Exception {
        String code = """
                public class BaseEntity {
                    public int id;
                    public BaseEntity(int id) {
                        this.id = id;
                    }
                }
                public class ChildEntity extends BaseEntity {
                    public String name;
                    public ChildEntity(int id, String name) {
                        super(id);
                        this.name = name;
                    }
                    public ChildEntity(int id) {
                        this(id, "default");
                    }
                    public int function getId() {
                        return this.id;
                    }
                    public String function getName() {
                        return this.name;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ChildEntity", code);
        Class<?> childClass = classes.get("ChildEntity");
        assertNotNull(childClass);

        Object obj1 = childClass.getConstructor(int.class, String.class).newInstance(42, "Ocean");
        Method getId = childClass.getMethod("getId");
        Method getName = childClass.getMethod("getName");
        assertEquals(42, getId.invoke(obj1));
        assertEquals("Ocean", getName.invoke(obj1));

        Object obj2 = childClass.getConstructor(int.class).newInstance(100);
        assertEquals(100, getId.invoke(obj2));
        assertEquals("default", getName.invoke(obj2));
    }
}
