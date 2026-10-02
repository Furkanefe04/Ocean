package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class MultiVariableDeclarationTest extends CompilerTestHelper {

    @Test
    @DisplayName("Typed multi-variable declarations: int a = 10, b = 20, c = 30;")
    public void testTypedMultiVariable() throws Exception {
        String code = """
                public class TypedMultiVarRunner {
                    public static int function run() {
                        int a = 10, b = 20, c = 30;
                        return a + b + c;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TypedMultiVarRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals(60, m.invoke(null));
    }

    @Test
    @DisplayName("Uninitialized mixed with initialized declarators: int x, y = 5, z;")
    public void testUninitializedMixed() throws Exception {
        String code = """
                public class MixedMultiVarRunner {
                    public static int function run() {
                        int x, y = 5, z;
                        x = 10;
                        z = x + y;
                        return z;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("MixedMultiVarRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals(15, m.invoke(null));
    }

    @Test
    @DisplayName("Sequential reference in same multi-variable line: int a = 5, b = a * 2, c = b + a;")
    public void testSequentialReference() throws Exception {
        String code = """
                public class SequentialRefRunner {
                    public static int function run() {
                        int a = 5, b = a * 2, c = b + a;
                        return a + b + c;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("SequentialRefRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals(30, m.invoke(null));
    }

    @Test
    @DisplayName("Value (type-inferred final) multi-variable: value x = 100, y = 200;")
    public void testValueMultiVariable() throws Exception {
        String code = """
                public class ValueMultiVarRunner {
                    public static int function run() {
                        value x = 100, y = 200;
                        return x + y;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("ValueMultiVarRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals(300, m.invoke(null));
    }

    @Test
    @DisplayName("Variable (type-inferred mutable) multi-variable: variable p = 1, q = 2;")
    public void testVariableMultiVariable() throws Exception {
        String code = """
                public class VarMultiVarRunner {
                    public static int function run() {
                        variable p = 1, q = 2;
                        p = 10;
                        q = 20;
                        return p + q;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("VarMultiVarRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals(30, m.invoke(null));
    }

    @Test
    @DisplayName("Final multi-variable declarations: final String s1 = \"hello\", s2 = \"world\";")
    public void testFinalMultiVariable() throws Exception {
        String code = """
                public class FinalMultiVarRunner {
                    public static String function run() {
                        final String s1 = "hello", s2 = "world";
                        return s1 + " " + s2;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("FinalMultiVarRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals("hello world", m.invoke(null));
    }

    @Test
    @DisplayName("Class multi-field declarations: public int x = 10, y = 20;")
    public void testMultiFieldDeclaration() throws Exception {
        String code = """
                public class PointHolder {
                    public int x = 10, y = 20;
                    public String label = "Origin", tag = "2D";
                    
                    public static int function getSum() {
                        PointHolder p = new PointHolder();
                        return p.x + p.y;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("PointHolder", code);
        Method m = clazz.getMethod("getSum");
        assertEquals(30, m.invoke(null));
    }

    @Test
    @DisplayName("Duplicate variable in same multi-variable line must fail compilation")
    public void testDuplicateMultiVariableFails() {
        String code = """
                public class DuplicateMultiVarFail {
                    public static void function run() {
                        int a = 1, a = 2;
                    }
                }
                """;
        assertThrows(Exception.class, () -> compileAndLoad("DuplicateMultiVarFail", code));
    }
}