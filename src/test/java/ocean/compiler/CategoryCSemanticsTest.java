package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CategoryCSemanticsTest extends CompilerTestHelper {

    @Test
    @DisplayName("C1: throws clause rejecting primitive type (e.g. throws int)")
    public void testThrowsPrimitiveFailsCompilation() {
        String code = """
                public class ThrowsPrimTest {
                    public void function doWork() throws int {
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoad("ThrowsPrimTest", code));
        assertTrue(ex.getMessage().contains("İstisna türü ilkel") || ex.getMessage().contains("primitive"),
                "Expected primitive throws error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("C1: throws clause rejecting array type (e.g. throws int[])")
    public void testThrowsArrayFailsCompilation() {
        String code = """
                public class ThrowsArrayTest {
                    public void function doWork() throws int[] {
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoad("ThrowsArrayTest", code));
        assertTrue(ex.getMessage().contains("İstisna türü bir dizi") || ex.getMessage().contains("array") || ex.getMessage().contains("Throwable"),
                "Expected array throws error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("C2: Enum constructor with public modifier fails compilation")
    public void testPublicEnumConstructorFailsCompilation() {
        String code = """
                public enum Day {
                    MONDAY;
                    public Day() {
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoad("Day", code));
        assertTrue(ex.getMessage().contains("Enum constructor") || ex.getMessage().contains("public") || ex.getMessage().contains("private"),
                "Expected public enum constructor error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("C2: Enum constructor with protected modifier fails compilation")
    public void testProtectedEnumConstructorFailsCompilation() {
        String code = """
                public enum Status {
                    ACTIVE;
                    protected Status() {
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoad("Status", code));
        assertTrue(ex.getMessage().contains("Enum constructor") || ex.getMessage().contains("protected") || ex.getMessage().contains("private"),
                "Expected protected enum constructor error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("C6: super method call in class without explicit extends falls back to java/lang/Object")
    public void testSuperMethodCallWithoutExtendsCompiles() throws Exception {
        String code = """
                public class SuperFallbackTest {
                    public boolean function testEquals(Object other) {
                        return super.equals(other);
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("SuperFallbackTest", code);
        Object inst = clazz.getConstructor().newInstance();
        Object res = clazz.getMethod("testEquals", Object.class).invoke(inst, inst);
        assertEquals(true, res);
    }

    @Test
    @DisplayName("C7: Multi-catch clause duplicate exception types fails compilation")
    public void testMultiCatchDuplicateExceptionFailsCompilation() {
        String code = """
                import java.io.IOException;
                public class MultiCatchDupTest {
                    public void function doWork() {
                        trying {
                            int a = 1;
                        } catch (IOException | IOException e) {
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoad("MultiCatchDupTest", code));
        assertTrue(ex.getMessage().contains("Duplicate exception type"),
                "Expected duplicate catch type error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("C8: Duplicate non-repeatable annotation fails compilation")
    public void testDuplicateNonRepeatableAnnotationFailsCompilation() {
        String code = """
                @Deprecated
                @Deprecated
                public class DupAnnoTest {
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileAndLoad("DupAnnoTest", code));
        assertTrue(ex.getMessage().contains("tekrarlanamaz") || ex.getMessage().contains("Repeatable"),
                "Expected non-repeatable annotation error, got: " + ex.getMessage());
    }
}
