package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SwitchDuplicateCaseTest extends CompilerTestHelper {

    @Test
    @DisplayName("Switch statement with duplicate enum cases fails compilation")
    public void testDuplicateEnumCasesFails() {
        String code = """
                public enum Color {
                    RED, GREEN, BLUE
                }

                public class SwitchEnumTest {
                    public void function test(Color c) {
                        switch (c) {
                            case RED:
                                stop;
                            case GREEN:
                                stop;
                            case RED:
                                stop;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchEnumTest"));
        assertTrue(ex.getMessage().contains("Duplicate case label"),
                "Expected duplicate enum case error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch statement with duplicate constant expressions fails compilation")
    public void testDuplicateConstantExpressionCasesFails() {
        String code = """
                public class SwitchConstExprTest {
                    public void function test(int x) {
                        switch (x) {
                            case 5:
                                stop;
                            case 2 + 3:
                                stop;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchConstExprTest"));
        assertTrue(ex.getMessage().contains("Duplicate case label"),
                "Expected duplicate constant expression case error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch statement with duplicate type patterns without guards fails compilation")
    public void testDuplicatePatternWithoutGuardFails() {
        String code = """
                public class SwitchDuplicatePatternTest {
                    public void function test(Object obj) {
                        switch (obj) {
                            case String s1:
                                stop;
                            case String s2:
                                stop;
                            default:
                                stop;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchDuplicatePatternTest"));
        assertTrue(ex.getMessage().contains("Tekrarlayan") || ex.getMessage().contains("dominated") || ex.getMessage().contains("dominated pattern"),
                "Expected duplicate pattern or dominance error, got: " + ex.getMessage());
    }
}