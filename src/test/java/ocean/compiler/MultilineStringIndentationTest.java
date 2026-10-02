package ocean.compiler;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class MultilineStringIndentationTest extends CompilerTestHelper {

    @Test
    public void testCommonIndentStripping() throws Exception {
        String code = "public class IndentTest {\n" +
                "    public static String function getQuery() {\n" +
                "        return \"\"\"\n" +
                "            SELECT id, name\n" +
                "            FROM users\n" +
                "            WHERE active = true\n" +
                "            \"\"\";\n" +
                "    }\n" +
                "}\n";
        Class<?> cls = compileAndLoad("IndentTest", code);
        Method method = cls.getMethod("getQuery");
        String result = (String) method.invoke(null);

        String expected = "SELECT id, name\nFROM users\nWHERE active = true";
        assertEquals(expected, result);
    }

    @Test
    public void testLineContinuationEscape() throws Exception {
        String code = "public class LineContinuationTest {\n" +
                "    public static String function getSingleLine() {\n" +
                "        return \"\"\"\n" +
                "            This is line 1 \\\n" +
                "            and line 2\n" +
                "            \"\"\";\n" +
                "    }\n" +
                "}\n";
        Class<?> cls = compileAndLoad("LineContinuationTest", code);
        Method method = cls.getMethod("getSingleLine");
        String result = (String) method.invoke(null);

        String expected = "This is line 1 and line 2";
        assertEquals(expected, result);
    }

    @Test
    public void testSpaceEscape() throws Exception {
        String code = "public class SpaceEscapeTest {\n" +
                "    public static String function getWithSpace() {\n" +
                "        return \"\"\"\n" +
                "            trailing\\s\n" +
                "            spaces\\s\\s\n" +
                "            \"\"\";\n" +
                "    }\n" +
                "}\n";
        Class<?> cls = compileAndLoad("SpaceEscapeTest", code);
        Method method = cls.getMethod("getWithSpace");
        String result = (String) method.invoke(null);

        String expected = "trailing \nspaces  ";
        assertEquals(expected, result);
    }

    @Test
    public void testMultilineInterpolation() throws Exception {
        String code = "public class MultilineInterpTest {\n" +
                "    public static String function format(String name, int age) {\n" +
                "        return $\"\"\"\n" +
                "            User: {name}\n" +
                "            Age: {age}\n" +
                "            \"\"\";\n" +
                "    }\n" +
                "}\n";
        Class<?> cls = compileAndLoad("MultilineInterpTest", code);
        Method method = cls.getMethod("format", String.class, int.class);
        String result = (String) method.invoke(null, "Alice", 25);

        String expected = "User: Alice\nAge: 25";
        assertEquals(expected, result);
    }

    @Test
    public void testZeroIndentBackwardCompatibility() throws Exception {
        String code = "public class ZeroIndentTest {\n" +
                "    public static String function getJson() {\n" +
                "        return \"\"\"{\n" +
                "\"name\": \"Ocean\",\n" +
                "\"version\": \"1.0\"\n" +
                "}\"\"\";\n" +
                "    }\n" +
                "}\n";
        Class<?> cls = compileAndLoad("ZeroIndentTest", code);
        Method method = cls.getMethod("getJson");
        String result = (String) method.invoke(null);

        String expected = "{\n\"name\": \"Ocean\",\n\"version\": \"1.0\"\n}";
        assertEquals(expected, result);
    }
}
