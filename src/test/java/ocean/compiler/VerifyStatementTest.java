package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class VerifyStatementTest extends CompilerTestHelper {

    @Test
    @DisplayName("verify true passes without exception")
    public void testVerifyTruePasses() throws Exception {
        String code = """
            public class TestVerifyTrue {
                public static int function run() {
                    int x = 10;
                    verify x == 10;
                    verify true;
                    return x * 2;
                }
            }
            """;
        Class<?> cls = compileAndLoad("TestVerifyTrue", code);
        Method m = cls.getMethod("run");
        Object res = m.invoke(null);
        assertEquals(20, res);
    }

    @Test
    @DisplayName("verify false throws java.lang.AssertionError")
    public void testVerifyFalseThrowsAssertionError() throws Exception {
        String code = """
            public class TestVerifyFalse {
                public static void function run() {
                    int x = 5;
                    verify x > 10;
                }
            }
            """;
        Class<?> cls = compileAndLoad("TestVerifyFalse", code);
        Method m = cls.getMethod("run");
        InvocationTargetException ex = assertThrows(InvocationTargetException.class, () -> m.invoke(null));
        assertInstanceOf(AssertionError.class, ex.getCause());
    }

    @Test
    @DisplayName("verify false with string detail message carries error message")
    public void testVerifyWithDetailMessage() throws Exception {
        String code = """
            public class TestVerifyDetail {
                public static void function run() {
                    int age = -1;
                    verify age >= 0 : "Age cannot be negative: " + age;
                }
            }
            """;
        Class<?> cls = compileAndLoad("TestVerifyDetail", code);
        Method m = cls.getMethod("run");
        InvocationTargetException ex = assertThrows(InvocationTargetException.class, () -> m.invoke(null));
        Throwable cause = ex.getCause();
        assertInstanceOf(AssertionError.class, cause);
        assertEquals("Age cannot be negative: -1", cause.getMessage());
    }

    @Test
    @DisplayName("verify false with primitive detail payload")
    public void testVerifyWithPrimitiveDetails() throws Exception {
        String code = """
            public class TestVerifyPrimitives {
                public static void function runInt() {
                    verify false : 404;
                }
                public static void function runDouble() {
                    verify false : 3.14;
                }
                public static void function runBool() {
                    verify false : true;
                }
            }
            """;
        Class<?> cls = compileAndLoad("TestVerifyPrimitives", code);

        Method mInt = cls.getMethod("runInt");
        InvocationTargetException exInt = assertThrows(InvocationTargetException.class, () -> mInt.invoke(null));
        assertEquals("404", exInt.getCause().getMessage());

        Method mDouble = cls.getMethod("runDouble");
        InvocationTargetException exDouble = assertThrows(InvocationTargetException.class, () -> mDouble.invoke(null));
        assertEquals("3.14", exDouble.getCause().getMessage());

        Method mBool = cls.getMethod("runBool");
        InvocationTargetException exBool = assertThrows(InvocationTargetException.class, () -> mBool.invoke(null));
        assertEquals("true", exBool.getCause().getMessage());
    }

    @Test
    @DisplayName("verify keyword used as identifier in existing code continues to work seamlessly")
    public void testVerifyAsIdentifierCompatibility() throws Exception {
        String code = """
            public class TestVerifyAsIdentifier {
                public static String function run() {
                    String verify = "hello";
                    verify = verify + " world";
                    return verify;
                }
            }
            """;
        Class<?> cls = compileAndLoad("TestVerifyAsIdentifier", code);
        Method m = cls.getMethod("run");
        Object res = m.invoke(null);
        assertEquals("hello world", res);
    }

    @Test
    @DisplayName("verify with non-boolean expression fails compilation")
    public void testVerifyNonBooleanConditionFails() {
        String code = """
            public class TestNonBooleanVerify {
                public static void function run() {
                    verify "invalid condition";
                }
            }
            """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestNonBooleanVerify"));
    }
}