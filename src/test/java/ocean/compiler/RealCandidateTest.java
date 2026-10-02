package ocean.compiler;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

public class RealCandidateTest extends CompilerTestHelper {

    @Test
    public void testIntToBoolCast() {
        String code = """
                public class TestIntToBool {
                    public static bool function toBool(int x) {
                        return (bool) x;
                    }
                }
                """;
        try {
            Class<?> clz = compileAndLoad("TestIntToBool", code);
            Method m = clz.getMethod("toBool", int.class);
            Object res0 = m.invoke(null, 0);
            Object res1 = m.invoke(null, 1);
            Object res2 = m.invoke(null, 2);
            System.out.println("res0=" + res0 + ", res1=" + res1 + ", res2=" + res2);
        } catch (Throwable t) {
            System.out.println("testIntToBool FAILED: " + t.getClass().getName() + ": " + t.getMessage());
        }
    }
}
