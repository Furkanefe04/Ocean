package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LambdaTypeSafetyTest extends CompilerTestHelper {

    @Test
    @DisplayName("Lambda with too many parameters fails compilation")
    public void testLambdaTooManyParametersFails() {
        String code = """
                public interface MyConsumer {
                    void function accept(String s);
                }
                public class LambdaTooManyParamsTest {
                    public static void function test() {
                        MyConsumer c = (a, b) -> {
                            OceanOutput(a);
                        };
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("LambdaTooManyParamsTest", code));
        assertTrue(ex.getMessage().contains("Lambda parameter count mismatch"),
                "Expected parameter count error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Lambda with too few parameters fails compilation")
    public void testLambdaTooFewParametersFails() {
        String code = """
                public interface MyBiConsumer {
                    void function accept(String a, int b);
                }
                public class LambdaTooFewParamsTest {
                    public static void function test() {
                        MyBiConsumer c = (a) -> {
                            OceanOutput(a);
                        };
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("LambdaTooFewParamsTest", code));
        assertTrue(ex.getMessage().contains("Lambda parameter count mismatch"),
                "Expected parameter count error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Lambda with incompatible explicit parameter type fails compilation")
    public void testLambdaIncompatibleExplicitParamTypeFails() {
        String code = """
                public interface MyConsumer {
                    void function accept(String s);
                }
                public class LambdaIncompatibleParamTypeTest {
                    public static void function test() {
                        MyConsumer c = (int x) -> {
                            OceanOutput("" + x);
                        };
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("LambdaIncompatibleParamTypeTest", code));
        assertTrue(ex.getMessage().contains("Lambda parameter type mismatch"),
                "Expected parameter type error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Lambda with incompatible primitive parameter fails compilation")
    public void testLambdaIncompatiblePrimitiveParamFails() {
        String code = """
                public interface MyIntConsumer {
                    void function accept(int x);
                }
                public class LambdaIncompatiblePrimitiveTest {
                    public static void function test() {
                        MyIntConsumer c = (String s) -> {
                            OceanOutput(s);
                        };
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("LambdaIncompatiblePrimitiveTest", code));
        assertTrue(ex.getMessage().contains("Lambda parameter type mismatch"),
                "Expected parameter type error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Generic SAM lambda with incompatible expression return type fails compilation")
    public void testLambdaGenericReturnTypeMismatchFails() {
        String code = """
                public interface MySupplier<T> {
                    T function get();
                }
                public class LambdaGenericRetMismatchTest {
                    public static void function test() {
                        MySupplier<String> s = () -> 123;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("LambdaGenericRetMismatchTest", code));
        assertTrue(ex.getMessage().contains("Incompatible return type"),
                "Expected return type error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Generic SAM lambda with incompatible block return type fails compilation")
    public void testLambdaGenericBlockReturnTypeMismatchFails() {
        String code = """
                public interface MySupplier<T> {
                    T function get();
                }
                public class LambdaGenericBlockRetMismatchTest {
                    public static void function test() {
                        MySupplier<String> s = () -> {
                            return 123;
                        };
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("LambdaGenericBlockRetMismatchTest", code));
        assertTrue(ex.getMessage().contains("Incompatible return type"),
                "Expected return type error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Non-generic SAM lambda with incompatible return type fails compilation")
    public void testLambdaNonGenericReturnTypeMismatchFails() {
        String code = """
                public interface MyIntSupplier {
                    int function get();
                }
                public class LambdaNonGenericRetMismatchTest {
                    public static void function test() {
                        MyIntSupplier s = () -> "hello";
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("LambdaNonGenericRetMismatchTest", code));
        assertTrue(ex.getMessage().contains("Incompatible return type"),
                "Expected return type error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid lambdas with inferred and explicit types compile and run successfully")
    public void testValidLambdasCompileAndRun() throws Exception {
        String code = """
                public interface MyFunc<T, R> {
                    R function apply(T t);
                }
                public class ValidLambdaTest {
                    public static String function test() {
                        MyFunc<String, String> f1 = (s) -> s + " world";
                        MyFunc<String, String> f2 = (String s) -> {
                            return s + " ocean";
                        };
                        return f1.apply("hello") + " & " + f2.apply("cool");
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ValidLambdaTest", code);
        Class<?> clazz = classes.get("ValidLambdaTest");
        Method method = clazz.getMethod("test");
        Object result = method.invoke(null);
        assertEquals("hello world & cool ocean", result);
    }
}
