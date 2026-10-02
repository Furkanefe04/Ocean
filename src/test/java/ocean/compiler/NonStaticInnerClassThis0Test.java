package ocean.compiler;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class NonStaticInnerClassThis0Test {

    @Test
    public void testNonStaticInnerClassExecution() throws Exception {
        Path path = Path.of("examples/NonStaticInnerClassThis0Test.ocean");
        String code = Files.readString(path);

        PrintStream origOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(baos));
            Class<?> cls = CompilerTestHelper.compileAndLoad("NonStaticInnerClassThis0Test", code);
            Method main = cls.getMethod("main", String[].class);
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(origOut);
        }

        String output = baos.toString();
        assertTrue(output.contains("STATUS: PASSED"), "NonStaticInnerClassThis0Test should pass but got: " + output);
    }

    @Test
    public void testNonStaticInnerFromStaticContextFails() throws Exception {
        Path path = Path.of("examples/NonStaticInnerFromStaticContextErrorTest.ocean");
        String code = Files.readString(path);

        assertThrows(Exception.class, () -> {
            CompilerTestHelper.compileAndLoad("NonStaticInnerFromStaticContextErrorTest", code);
        }, "Instantiating non-static inner class from static context should fail compilation");
    }
}