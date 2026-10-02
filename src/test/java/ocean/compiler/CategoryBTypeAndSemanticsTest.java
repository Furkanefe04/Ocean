package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CategoryBTypeAndSemanticsTest extends CompilerTestHelper {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    @Test
    @DisplayName("B1: Generic arity mismatch (e.g. Map with 1 arg, List with 2 args) fails compilation")
    public void testGenericArityMismatchFails() {
        String code = """
                import java.util.Map;

                public class ArityTest {
                    public Map<String> map;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoad("ArityTest", code));
        assertTrue(ex.getMessage().contains("Wrong number of type arguments") || ex.getMessage().contains("parametre"),
                "Expected generic arity mismatch error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("B3: Concrete class failing to implement abstract method (or only defining an overload) fails compilation")
    public void testAbstractMethodMissingInConcreteClassFails() {
        String code = """
                public abstract class BaseService {
                    public abstract void function process(Object item);
                }

                public class MyService extends BaseService {
                    // This is an overload, NOT an implementation of process(Object)
                    public void function process(String item) {
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoad("MyService", code));
        assertTrue(ex.getMessage().contains("soyut") || ex.getMessage().contains("abstract") || ex.getMessage().contains("must implement"),
                "Expected missing abstract method implementation error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("B7: Unreachable catch block (subtype caught after supertype) fails compilation")
    public void testUnreachableCatchBlockFails() {
        String code = """
                import java.io.IOException;

                public class UnreachableCatchTest {
                    public static void function testCatch() {
                        trying {
                            int x = 1;
                        } catch (Exception e) {
                            int a = 1;
                        } catch (IOException e) {
                            int b = 2;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoad("UnreachableCatchTest", code));
        assertTrue(ex.getMessage().contains("has already been caught"),
                "Expected unreachable catch error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("B5: Returning a value from a void function fails compilation")
    public void testReturnExpressionInVoidMethodFails() {
        String code = """
                public class VoidReturnTest {
                    public static void function doWork() {
                        return 42;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoad("VoidReturnTest", code));
        assertTrue(ex.getMessage().contains("void metottan Cannot return a value") || ex.getMessage().contains("void"),
                "Expected void return error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("B6: Using 'this' keyword inside a static function fails compilation")
    public void testStaticContextThisAccessFails() {
        String code = """
                public class StaticThisTest {
                    public static void function doWork() {
                        Object o = this;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoad("StaticThisTest", code));
        assertTrue(ex.getMessage().contains("static context") || ex.getMessage().contains("statik") || ex.getMessage().contains("static"),
                "Expected static this access error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("B11: Direct assignment of nullable type to non-nullable primitive fails compilation")
    public void testNullableToPrimitiveAssignmentFails() {
        String code = """
                public class NullableToPrimTest {
                    public static void function test() {
                        Integer? opt = null;
                        int x = opt;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoad("NullableToPrimTest", code));
        assertNotNull(ex.getMessage());
    }

    @Test
    @DisplayName("B8: cleanDescriptor properly preserves class and array format without malformed semicolons")
    public void testCleanDescriptorPreservesFormatting() {
        String cleaned1 = TypeChecker.cleanDescriptor("Ljava/util/List<Ljava/lang/String;>;");
        assertEquals("Ljava/util/List;", cleaned1);

        String cleaned2 = TypeChecker.cleanDescriptor("[Ljava/util/Map<Ljava/lang/String;, Ljava/lang/Integer;>;");
        assertEquals("[Ljava/util/Map;", cleaned2);

        String cleaned3 = TypeChecker.cleanDescriptor("Ljava/util/List<I>");
        assertEquals("Ljava/util/List;", cleaned3);
    }
}
