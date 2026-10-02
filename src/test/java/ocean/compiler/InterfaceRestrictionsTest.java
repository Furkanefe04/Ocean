package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class InterfaceRestrictionsTest extends CompilerTestHelper {

    @Test
    @DisplayName("Interface with constructor declaration fails compilation")
    public void testInterfaceWithConstructorFails() {
        String code = """
                public interface MyInterface {
                    public function MyInterface() {
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "MyInterface"));
        assertTrue(ex.getMessage().contains("yapıcı metot") || ex.getMessage().contains("constructor"),
                "Expected interface constructor rejection, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Interface method with final modifier fails compilation")
    public void testInterfaceMethodWithFinalFails() {
        String code = """
                public interface FinalMethodInterface {
                    public final void function doWork();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "FinalMethodInterface"));
        assertTrue(ex.getMessage().contains("final") || ex.getMessage().contains("Arayüz"),
                "Expected interface final method error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Interface method with sync modifier fails compilation")
    public void testInterfaceMethodWithSyncFails() {
        String code = """
                public interface SyncMethodInterface {
                    public sync void function doWork();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SyncMethodInterface"));
        assertTrue(ex.getMessage().contains("sync") || ex.getMessage().contains("Arayüz"),
                "Expected interface sync method error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Interface method declared abstract with body fails compilation")
    public void testInterfaceAbstractMethodWithBodyFails() {
        String code = """
                public interface AbstractBodyInterface {
                    public abstract void function doWork() {
                        int x = 10;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "AbstractBodyInterface"));
        assertTrue(ex.getMessage().contains("Abstract method 'doWork' cannot have a body"),
                "Expected abstract body error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Interface default and static methods with body succeed")
    public void testInterfaceDefaultAndStaticMethodsSucceed() {
        String code = """
                public interface ValidInterface {
                    void function abstractMethod();

                    public default int function defaultCalc(int a, int b) {
                        return a + b;
                    }

                    public static String function getGreeting() {
                        return "Hello from interface";
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "ValidInterface"));
    }
}