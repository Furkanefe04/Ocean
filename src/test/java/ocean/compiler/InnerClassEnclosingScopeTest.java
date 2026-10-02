package ocean.compiler;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class InnerClassEnclosingScopeTest {

    @Test
    public void testInnerClassEnclosingScopeExecution() throws Exception {
        Path path = Path.of("examples/InnerClassEnclosingScopeTest.ocean");
        String code = Files.readString(path);

        PrintStream origOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(baos));
            Class<?> cls = CompilerTestHelper.compileAndLoad("InnerClassEnclosingScopeTest", code);
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(origOut);
        }

        String output = baos.toString();
        assertTrue(output.contains("STATUS: PASSED"), "InnerClassEnclosingScopeTest should pass but output was: " + output);
    }
}