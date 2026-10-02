package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class NegativeArraySizeTest extends CompilerTestHelper {

    @Test
    @DisplayName("Literal negative array size fails compilation")
    public void testLiteralNegativeArraySizeFails() {
        String code = """
                public class NegArray1 {
                    public static void function test() {
                        int[] arr = new int[-5];
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "NegArray1"));
        assertTrue(ex.getMessage().contains("Array dimension cannot be negative"),
                "Expected negative array size error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Constant expression negative array size fails compilation")
    public void testConstantExprNegativeArraySizeFails() {
        String code = """
                public class NegArray2 {
                    public static void function test() {
                        int[] arr = new int[2 - 5];
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "NegArray2"));
        assertTrue(ex.getMessage().contains("Array dimension cannot be negative"),
                "Expected negative array size error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Positive array size succeeds")
    public void testPositiveArraySizeSucceeds() {
        String code = """
                public class PosArray {
                    public static void function test() {
                        int[] arr = new int[10];
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "PosArray"));
    }
}