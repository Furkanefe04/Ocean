package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ArithmeticOperatorTypeSafetyTest extends CompilerTestHelper {

    @Test
    @DisplayName("Adding boolean and int fails compilation")
    public void testBooleanAdditionFails() {
        String code = """
                public class BoolAddTest {
                    public static void function test() {
                        bool b = true;
                        int x = 5;
                        variable y = b + x;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("BoolAddTest", code));
        assertTrue(ex.getMessage().contains("cannot be applied"),
                "Expected numeric type error for boolean addition, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Multiplying boolean and int fails compilation")
    public void testBooleanMultiplicationFails() {
        String code = """
                public class BoolMulTest {
                    public static void function test() {
                        bool b = true;
                        variable y = b * 5;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("BoolMulTest", code));
        assertTrue(ex.getMessage().contains("numeric types"),
                "Expected numeric type error for boolean multiplication, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Subtracting boolean values fails compilation")
    public void testBooleanSubtractionFails() {
        String code = """
                public class BoolSubTest {
                    public static void function test() {
                        bool b1 = true;
                        bool b2 = false;
                        variable y = b1 - b2;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("BoolSubTest", code));
        assertTrue(ex.getMessage().contains("numeric types"),
                "Expected numeric type error for boolean subtraction, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Dividing by boolean fails compilation")
    public void testBooleanDivisionFails() {
        String code = """
                public class BoolDivTest {
                    public static void function test() {
                        int x = 10;
                        bool b = false;
                        variable y = x / b;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("BoolDivTest", code));
        assertTrue(ex.getMessage().contains("numeric types"),
                "Expected numeric type error for boolean division, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Modulo with boolean fails compilation")
    public void testBooleanModuloFails() {
        String code = """
                public class BoolModTest {
                    public static void function test() {
                        int x = 10;
                        bool b = true;
                        variable y = x % b;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("BoolModTest", code));
        assertTrue(ex.getMessage().contains("numeric types"),
                "Expected numeric type error for boolean modulo, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Arithmetic with non-numeric class fails compilation")
    public void testNonNumericClassArithmeticFails() {
        String code = """
                public class NonNumArithTest {
                    public static void function test() {
                        Thread t = new Thread();
                        variable y = t - 5;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("NonNumArithTest", code));
        assertTrue(ex.getMessage().contains("numeric types"),
                "Expected numeric type error for non-numeric class arithmetic, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Compound assignment int += String fails compilation")
    public void testIntPlusAssignStringFails() {
        String code = """
                public class IntPlusAssignStringTest {
                    public static void function test() {
                        int x = 5;
                        x += "hello";
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("IntPlusAssignStringTest", code));
        assertTrue(ex.getMessage().contains("Incompatible types"),
                "Expected incompatible types error for int += String, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Compound assignment bool += int fails compilation")
    public void testBooleanPlusAssignIntFails() {
        String code = """
                public class BoolPlusAssignIntTest {
                    public static void function test() {
                        bool b = true;
                        b += 5;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("BoolPlusAssignIntTest", code));
        assertTrue(ex.getMessage().contains("cannot be applied"),
                "Expected numeric type error for bool += int, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Compound assignment String -= String fails compilation")
    public void testStringMinusAssignFails() {
        String code = """
                public class StringMinusAssignTest {
                    public static void function test() {
                        String s = "hello";
                        s -= "world";
                    }
                }
                """;

        assertThrows(CompilationException.class,
                () -> compileAndLoadAll("StringMinusAssignTest", code));
    }

    @Test
    @DisplayName("Unary negation on boolean fails compilation")
    public void testUnaryNegationOnBooleanFails() {
        String code = """
                public class NegBoolTest {
                    public static void function test() {
                        bool b = true;
                        variable y = -b;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("NegBoolTest", code));
        assertTrue(ex.getMessage().contains("Unary minus"),
                "Expected unary minus error on boolean, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Unary negation on String fails compilation")
    public void testUnaryNegationOnStringFails() {
        String code = """
                public class NegStringTest {
                    public static void function test() {
                        String s = "hello";
                        variable y = -s;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("NegStringTest", code));
        assertTrue(ex.getMessage().contains("Unary minus"),
                "Expected unary minus error on String, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Bitwise NOT on boolean fails compilation")
    public void testBitwiseNotOnBooleanFails() {
        String code = """
                public class BitNotBoolTest {
                    public static void function test() {
                        bool b = true;
                        variable y = ~b;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("BitNotBoolTest", code));
        assertTrue(ex.getMessage().contains("Bitwise NOT"),
                "Expected bitwise not error on boolean, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Bitwise NOT on double fails compilation")
    public void testBitwiseNotOnDoubleFails() {
        String code = """
                public class BitNotDoubleTest {
                    public static void function test() {
                        double d = 3.14;
                        variable y = ~d;
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class,
                () -> compileAndLoadAll("BitNotDoubleTest", code));
        assertTrue(ex.getMessage().contains("Bitwise NOT"),
                "Expected bitwise not error on double, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid arithmetic expressions compile and execute correctly")
    public void testValidArithmeticSucceeds() throws Exception {
        String code = """
                public class ValidArithTest {
                    public static int function add() { return 10 + 20; }
                    public static int function sub() { return 50 - 15; }
                    public static int function mul() { return 6 * 7; }
                    public static int function div() { return 100 / 4; }
                    public static int function mod() { return 17 % 5; }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ValidArithTest", code);
        Class<?> clazz = classes.get("ValidArithTest");
        assertNotNull(clazz);
        assertEquals(30, clazz.getMethod("add").invoke(null));
        assertEquals(35, clazz.getMethod("sub").invoke(null));
        assertEquals(42, clazz.getMethod("mul").invoke(null));
        assertEquals(25, clazz.getMethod("div").invoke(null));
        assertEquals(2, clazz.getMethod("mod").invoke(null));
    }

    @Test
    @DisplayName("Valid string concatenation compiles and executes correctly")
    public void testValidStringConcatenationSucceeds() throws Exception {
        String code = """
                public class ValidConcatTest {
                    public static String function concat1() {
                        return "count: " + 5;
                    }
                    public static String function concat2() {
                        String s = "hello";
                        s += " world";
                        return s;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ValidConcatTest", code);
        Class<?> clazz = classes.get("ValidConcatTest");
        assertNotNull(clazz);
        assertEquals("count: 5", clazz.getMethod("concat1").invoke(null));
        assertEquals("hello world", clazz.getMethod("concat2").invoke(null));
    }

    @Test
    @DisplayName("Valid compound assignments compile and execute correctly")
    public void testValidCompoundAssignmentSucceeds() throws Exception {
        String code = """
                public class ValidCompoundTest {
                    public static int function testInt() {
                        int x = 10;
                        x += 5;
                        x -= 3;
                        x *= 2;
                        x /= 4;
                        return x;
                    }
                    public static short function testShort() {
                        short s = 1;
                        s += 2;
                        return s;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ValidCompoundTest", code);
        Class<?> clazz = classes.get("ValidCompoundTest");
        assertNotNull(clazz);
        assertEquals(6, clazz.getMethod("testInt").invoke(null));
        assertEquals((short) 3, clazz.getMethod("testShort").invoke(null));
    }

    @Test
    @DisplayName("Valid unary operations compile and execute correctly")
    public void testValidUnaryOpsSucceeds() throws Exception {
        String code = """
                public class ValidUnaryTest {
                    public static int function negInt() {
                        int x = 42;
                        return -x;
                    }
                    public static double function negDouble() {
                        double d = 2.5;
                        return -d;
                    }
                    public static int function bitNot() {
                        int x = 0;
                        return ~x;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ValidUnaryTest", code);
        Class<?> clazz = classes.get("ValidUnaryTest");
        assertNotNull(clazz);
        assertEquals(-42, clazz.getMethod("negInt").invoke(null));
        assertEquals(-2.5, clazz.getMethod("negDouble").invoke(null));
    }
}
