package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class SwitchBooleanExhaustivenessTest extends CompilerTestHelper {

    @Test
    @DisplayName("Switch expression over boolean missing false branch fails compilation")
    public void testBooleanSwitchExpressionMissingFalseFails() {
        String code = """
                public class BooleanSwitchTest {
                    public String function test(boolean flag) {
                        return switch (flag) {
                            case true -> "YES";
                        };
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "BooleanSwitchTest"));
        assertTrue(ex.getMessage().contains("boolean") && ex.getMessage().contains("false"),
                "Expected missing false branch error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Switch expression over boolean with both true and false branches succeeds")
    public void testBooleanSwitchExpressionCompleteSucceeds() {
        String code = """
                public class BooleanSwitchCompleteTest {
                    public String function test(boolean flag) {
                        return switch (flag) {
                            case true -> "YES";
                            case false -> "NO";
                        };
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "BooleanSwitchCompleteTest"));
    }

    @Test
    @DisplayName("Switch expression over boolean with default branch succeeds")
    public void testBooleanSwitchExpressionWithDefaultSucceeds() {
        String code = """
                public class BooleanSwitchDefaultTest {
                    public String function test(boolean flag) {
                        return switch (flag) {
                            case true -> "YES";
                            default -> "OTHER";
                        };
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "BooleanSwitchDefaultTest"));
    }
}