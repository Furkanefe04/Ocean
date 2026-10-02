package ocean.compiler;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultiDimArrayStackSafetyTest {

    @Test
    public void testMultiDimArrayStackSafetyExecution() throws Exception {
        Path path = Path.of("examples/MultiDimArrayStackSafetyTest.ocean");
        String code = Files.readString(path);

        PrintStream origOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(baos));
            Class<?> cls = CompilerTestHelper.compileAndLoad("MultiDimArrayStackSafetyTest", code);
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(origOut);
        }

        String output = baos.toString();
        assertTrue(output.contains("STATUS: PASSED"), "MultiDimArrayStackSafetyTest should pass but output was: " + output);
    }
}