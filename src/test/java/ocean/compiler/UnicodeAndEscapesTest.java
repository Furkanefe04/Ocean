package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class UnicodeAndEscapesTest extends CompilerTestHelper {

    @Test
    @DisplayName("Multi-u Unicode escape sequences in String and Char literals")
    public void testMultiUUnicodeEscapes() throws Exception {
        String code = """
                public class UnicodeRunner {
                    public static String function testString() {
                        return "\\uuuu0041\\uuuu0042\\uuuu0043";
                    }

                    public static char function testChar() {
                        return '\\uuuu0044';
                    }
                }
                """;

        Class<?> clazz = compileAndLoad("UnicodeRunner", code);
        Method strM = clazz.getMethod("testString");
        assertEquals("ABC", strM.invoke(null));

        Method charM = clazz.getMethod("testChar");
        assertEquals('D', charM.invoke(null));
    }

    @Test
    @DisplayName("Standard escape sequences in string literals")
    public void testStandardEscapeSequences() throws Exception {
        String code = """
                public class EscapeRunner {
                    public static String function getEscapes() {
                        return "Hello\\n\\t\\\"World\\\"\\\\";
                    }
                }
                """;

        Class<?> clazz = compileAndLoad("EscapeRunner", code);
        Method m = clazz.getMethod("getEscapes");
        assertEquals("Hello\n\t\"World\"\\", m.invoke(null));
    }

    @Test
    @DisplayName("Multiline text block with Unicode and indentation stripping")
    public void testMultilineTextBlockWithUnicode() throws Exception {
        String code = """
                public class TextBlockRunner {
                    public static String function getBlock() {
                        return \"\"\"
                               Line 1: \\uuuu0041
                               Line 2: \\uuuu0042
                               \"\"\";
                    }
                }
                """;

        Class<?> clazz = compileAndLoad("TextBlockRunner", code);
        Method m = clazz.getMethod("getBlock");
        assertEquals("Line 1: A\nLine 2: B", m.invoke(null));
    }
}