package ocean.compiler;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import static org.junit.jupiter.api.Assertions.*;

public class LongDoubleComprehensiveTest {

    @Test
    public void testLongDoubleStackSafetyExecution() throws Exception {
        String code = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("examples/LongDoubleStackSafetyTest.ocean")));
        
        PrintStream originalOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(baos));
            
            Class<?> clazz = CompilerTestHelper.compileAndLoad("LongDoubleStackSafetyTest", code);
            Method main = clazz.getMethod("main", String[].class);
            main.setAccessible(true);
            main.invoke(null, (Object) new String[]{});
        } finally {
            System.setOut(originalOut);
        }

        String output = baos.toString().trim();
        assertTrue(output.contains("STATUS: PASSED"), "Expected STATUS: PASSED in output, but got: " + output);
    }
}