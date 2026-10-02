package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Map;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.*;

public class CompilerEdgeCasesTest extends CompilerTestHelper {

    // ==========================================
    // 1. VOID EXPRESSION RESTRICTION TESTS
    // ==========================================

    @Test
    @DisplayName("variable x = doNothing() fails compilation")
    public void testVoidAssignedToVariableFails() {
        String code = """
                public class VoidVarTest {
                    public static void function doNothing() {}
                    public static void function test() {
                        variable x = doNothing();
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "VoidVarTest"));
        assertTrue(ex.getMessage().contains("void"), "Expected void error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Object x = doNothing() fails compilation")
    public void testVoidAssignedToObjectFails() {
        String code = """
                public class VoidObjTest {
                    public static void function doNothing() {}
                    public static void function test() {
                        Object x = doNothing();
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "VoidObjTest"));
        assertTrue(ex.getMessage().contains("void"), "Expected void error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("x = doNothing() re-assignment fails compilation")
    public void testVoidReassignmentFails() {
        String code = """
                public class VoidReassignTest {
                    public static void function doNothing() {}
                    public static void function test() {
                        Object? obj = null;
                        obj = doNothing();
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "VoidReassignTest"));
        assertTrue(ex.getMessage().contains("void"), "Expected void error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Passing doNothing() as method argument fails compilation")
    public void testVoidPassedAsArgumentFails() {
        String code = """
                public class VoidArgTest {
                    public static void function doNothing() {}
                    public static void function consume(Object o) {}
                    public static void function test() {
                        consume(doNothing());
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "VoidArgTest"));
        assertTrue(ex.getMessage().contains("void"), "Expected void argument error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Passing doNothing() as constructor argument fails compilation")
    public void testVoidPassedAsCtorArgumentFails() {
        String code = """
                public class Item {
                    public Item(Object o) {}
                }
                public class VoidCtorArgTest {
                    public static void function doNothing() {}
                    public static void function test() {
                        variable item = new Item(doNothing());
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "VoidCtorArgTest"));
        assertTrue(ex.getMessage().contains("void"), "Expected void argument error, got: " + ex.getMessage());
    }

    // ==========================================
    // 2. DIVISION BY ZERO CONSTANT FOLDING TESTS
    // ==========================================

    @Test
    @DisplayName("Addition and multiplication with zero do not cause division by zero warnings")
    public void testAdditionMultiplicationWithZeroSucceeds() {
        String code = """
                public class AddMulZeroTest {
                    public static int function test() {
                        int a = 5 + 0;
                        int b = 10 * 0;
                        int c = 8 - 0;
                        int[] arr = new int[5 + 0];
                        return a + b + c + arr.length;
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "AddMulZeroTest"));
    }

    @Test
    @DisplayName("10 / (5 - 5) compiles without error (warning emitted)")
    public void testDivZeroConstantExpressionCompiles() {
        String code = """
                public class DivZeroConstTest {
                    public static int function test() {
                        int x = 10 / (5 - 5);
                        return x;
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "DivZeroConstTest"));
    }

    // ==========================================
    // 3. WHILE / DO-WHILE CONDITION TYPE TESTS
    // ==========================================

    @Test
    @DisplayName("while (5) fails compilation")
    public void testWhileIntConditionFails() {
        String code = """
                public class WhileIntTest {
                    public static void function test() {
                        while (5) {
                            stop;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "WhileIntTest"));
        assertTrue(ex.getMessage().contains("Condition in 'while' loop must be boolean"), "Expected while boolean condition error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("do-while (5) fails compilation")
    public void testDoWhileIntConditionFails() {
        String code = """
                public class DoWhileIntTest {
                    public static void function test() {
                        do {
                            stop;
                        } while (5);
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "DoWhileIntTest"));
        assertTrue(ex.getMessage().contains("Condition in 'do-while' loop must be boolean"), "Expected do-while boolean condition error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("while (true) and do-while (false) succeed compilation")
    public void testWhileBooleanConditionsPass() {
        String code = """
                public class WhileBoolTest {
                    public static void function test() {
                        while (false) {
                            stop;
                        }
                        do {
                            stop;
                        } while (false);
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "WhileBoolTest"));
    }

    // ==========================================
    // 4. ARRAY SIZE INTEGER TYPE RESTRICTION TESTS
    // ==========================================

    @Test
    @DisplayName("new int[5.5] fails compilation (double size)")
    public void testDoubleArraySizeFails() {
        String code = """
                public class ArrayDoubleTest {
                    public static void function test() {
                        int[] a = new int[5.5];
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ArrayDoubleTest"));
        assertTrue(ex.getMessage().contains("Array dimension must be of type int"), "Expected int size error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("new int[10L] fails compilation (long size)")
    public void testLongArraySizeFails() {
        String code = """
                public class ArrayLongTest {
                    public static void function test() {
                        int[] a = new int[10L];
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ArrayLongTest"));
        assertTrue(ex.getMessage().contains("Array dimension must be of type int"), "Expected int size error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("new int[Long] fails compilation (boxed Long size)")
    public void testBoxedLongArraySizeFails() {
        String code = """
                public class ArrayBoxedLongTest {
                    public static void function test() {
                        Long len = 10L;
                        int[] a = new int[len];
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ArrayBoxedLongTest"));
        assertTrue(ex.getMessage().contains("Array dimension must be of type int"), "Expected int size error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("new int[\"abc\"] fails compilation (string size)")
    public void testStringArraySizeFails() {
        String code = """
                public class ArrayStringTest {
                    public static void function test() {
                        int[] a = new int["abc"];
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ArrayStringTest"));
        assertTrue(ex.getMessage().contains("Array dimension must be of type int"), "Expected int size error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("new int[5] and byte/short size succeed compilation")
    public void testValidArraySizesPass() {
        String code = """
                public class ArrayValidTest {
                    public static void function test() {
                        byte b = (byte) 2;
                        short s = (short) 3;
                        int[] a1 = new int[5];
                        int[] a2 = new int[b];
                        int[] a3 = new int[s];
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "ArrayValidTest"));
    }

    // ==========================================
    // 5. VOID IN TERNARY RESTRICTION TESTS
    // ==========================================

    @Test
    @DisplayName("trueExpr with void method in ternary fails compilation")
    public void testVoidTrueExprInTernaryFails() {
        String code = """
                public class TernaryVoid1Test {
                    public static void function doNothing() {}
                    public static void function test(boolean flag) {
                        variable x = flag ? doNothing() : 5;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TernaryVoid1Test"));
        assertTrue(ex.getMessage().contains("void"), "Expected void error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("falseExpr with void method in ternary fails compilation")
    public void testVoidFalseExprInTernaryFails() {
        String code = """
                public class TernaryVoid2Test {
                    public static void function doNothing() {}
                    public static void function test(boolean flag) {
                        variable x = flag ? 5 : doNothing();
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TernaryVoid2Test"));
        assertTrue(ex.getMessage().contains("void"), "Expected void error, got: " + ex.getMessage());
    }

    // ==========================================
    // 6. FINAL AND VALUE METHOD PARAMETER PROTECTION TESTS
    // ==========================================

    @Test
    @DisplayName("Reassignment to final method parameter fails compilation")
    public void testFinalMethodParameterReassignmentFails() {
        String code = """
                public class FinalParamTest {
                    public static void function test(final int x) {
                        x = 20;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "FinalParamTest"));
        assertTrue(ex.getMessage().contains("Cannot assign a value to final variable 'x'"), "Expected final parameter error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Using value keyword as method parameter fails compilation")
    public void testValueMethodParameterRejected() {
        String code = """
                public class ValueParamTest {
                    public static void function test(value s) {
                        s = "new";
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ValueParamTest"));
        assertTrue(ex.getMessage().contains("Cannot use 'variable' / 'value' type for method or constructor parameter"), "Expected value rejection error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Reassignment to final constructor parameter fails compilation")
    public void testFinalConstructorParameterReassignmentFails() {
        String code = """
                public class FinalCtorParamTest {
                    public FinalCtorParamTest(final int id) {
                        id = 100;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "FinalCtorParamTest"));
        assertTrue(ex.getMessage().contains("Cannot assign a value to final variable 'id'"), "Expected final parameter error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Unary increment on final method parameter fails compilation")
    public void testFinalMethodParameterUnaryOpFails() {
        String code = """
                public class FinalParamUnaryTest {
                    public static void function test(final int x) {
                        x++;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "FinalParamUnaryTest"));
        assertTrue(ex.getMessage().contains("Cannot assign a value to final variable 'x'"), "Expected final parameter error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Normal (non-final) method parameter reassignment succeeds")
    public void testNormalMethodParameterReassignmentPasses() {
        String code = """
                public class NormalParamTest {
                    public static int function test(int x) {
                        x = x + 10;
                        x++;
                        return x;
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "NormalParamTest"));
    }

    // ==========================================
    // 7. OVERRIDE INHERITED JAVA ANCESTOR METHODS
    // ==========================================

    @Test
    @DisplayName("Overriding getMessage() on a class extending Exception succeeds")
    public void testOverrideInheritedJavaMethodSucceeds() {
        String code = """
                public class CustomException extends Exception {
                    public CustomException(String msg) {
                        super(msg);
                    }

                    @Override
                    public String function getMessage() {
                        return super.getMessage() + " [custom]";
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "CustomException"));
    }

    // ==========================================
    // 8. DUPLICATE METHOD & CONSTRUCTOR TESTS
    // ==========================================

    @Test
    @DisplayName("Duplicate methods with same parameters in class fail compilation")
    public void testDuplicateMethodSameSignatureFails() {
        String code = """
                public class DupMethodTest {
                    public void function test(int x) {}
                    public void function test(int x) {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "DupMethodTest"));
        assertTrue(ex.getMessage().contains("is already defined with the same parameter signature"), "Expected duplicate method error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Duplicate methods with same parameters but different return type fail compilation")
    public void testDuplicateMethodDifferentReturnTypeFails() {
        String code = """
                public class DupMethodRetTest {
                    public int function test(int x) { return 1; }
                    public String function test(int x) { return "a"; }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "DupMethodRetTest"));
        assertTrue(ex.getMessage().contains("is already defined with the same parameter signature"), "Expected duplicate method error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Duplicate constructors with same parameters in class fail compilation")
    public void testDuplicateConstructorFails() {
        String code = """
                public class DupCtorTest {
                    public DupCtorTest(int x) {}
                    public DupCtorTest(int x) {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "DupCtorTest"));
        assertTrue(ex.getMessage().contains("is already defined with the same parameter signature"), "Expected duplicate ctor error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Overloaded methods with different parameters succeed compilation")
    public void testOverloadedMethodsWithDifferentParamsPass() {
        String code = """
                public class OverloadTest {
                    public void function test() {}
                    public void function test(int x) {}
                    public void function test(int x, String s) {}
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "OverloadTest"));
    }

    // ==========================================
    // 9. JAVA SUPERCLASS FINAL & RETURN TYPE TESTS
    // ==========================================

    @Test
    @DisplayName("Overriding final method from java.lang.Object (wait) fails compilation")
    public void testOverridingFinalObjectMethodFails() {
        String code = """
                public class OverrideFinalObjectTest {
                    public void function wait() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "OverrideFinalObjectTest"));
        assertTrue(ex.getMessage().contains("Cannot override final method 'wait'"), "Expected final override error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Overriding Java method with incompatible return type fails compilation")
    public void testOverridingJavaMethodWithIncompatibleReturnFails() {
        String code = """
                public class BadReturnException extends Exception {
                    public BadReturnException(String msg) {
                        super(msg);
                    }
                    public int function getMessage() {
                        return 42;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "BadReturnException"));
        assertTrue(ex.getMessage().contains("Incompatible return type when overriding"), "Expected return type error, got: " + ex.getMessage());
    }

    // ==========================================
    // 10. RECURSIVE CONSTRUCTOR INVOCATION TESTS
    // ==========================================

    @Test
    @DisplayName("Direct recursive constructor invocation (this()) fails compilation")
    public void testDirectConstructorCycleFails() {
        String code = """
                public class DirectCycleTest {
                    public DirectCycleTest() {
                        this();
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "DirectCycleTest"));
        assertTrue(ex.getMessage().contains("Recursive constructor"), "Expected constructor cycle error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Indirect recursive constructor invocation (A -> B -> A) fails compilation")
    public void testIndirectConstructorCycleFails() {
        String code = """
                public class IndirectCycleTest {
                    public IndirectCycleTest() {
                        this(1);
                    }
                    public IndirectCycleTest(int x) {
                        this();
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "IndirectCycleTest"));
        assertTrue(ex.getMessage().contains("Recursive constructor"), "Expected constructor cycle error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid constructor chaining (this(1) -> super()) succeeds compilation")
    public void testValidChainedConstructorPasses() {
        String code = """
                public class ValidChainTest {
                    public ValidChainTest() {
                        this(1);
                    }
                    public ValidChainTest(int x) {
                        super();
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "ValidChainTest"));
    }

    // ==========================================
    // 11. INTERFACE METHOD RETURN TYPE CHECKS
    // ==========================================

    @Test
    @DisplayName("Implementing interface method with incompatible return type fails compilation")
    public void testIncompatibleInterfaceMethodReturnFails() {
        String code = """
                interface Greeter {
                    public String function greet();
                }
                public class BadGreeter implements Greeter {
                    public int function greet() {
                        return 42;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "BadGreeter"));
        assertTrue(ex.getMessage().contains("with incompatible return type"), "Expected incompatible return error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Implementing interface method with covariant return type succeeds compilation")
    public void testCovariantInterfaceMethodReturnPasses() {
        String code = """
                interface Provider {
                    public CharSequence function provide();
                }
                public class StringProvider implements Provider {
                    public String function provide() {
                        return "hello";
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "StringProvider"));
    }

    // ==========================================
    // 12. PROTECTED INTERFACE METHOD CHECKS
    // ==========================================

    @Test
    @DisplayName("Declaring protected method in interface fails compilation")
    public void testProtectedInterfaceMethodFails() {
        String code = """
                interface ProtectedIface {
                    protected void function test();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ProtectedIface"));
        assertTrue(ex.getMessage().contains("Interface methods cannot be declared 'protected'"), "Expected protected method error, got: " + ex.getMessage());
    }

    // ==========================================
    // 13. CONFLICTING RETURN TYPES IN INTERFACES
    // ==========================================

    @Test
    @DisplayName("Extending interfaces with conflicting method return types fails compilation")
    public void testConflictingInterfaceMethodReturnsFails() {
        String code = """
                interface Reader {
                    public int function read();
                }
                interface CharReader {
                    public String function read();
                }
                interface MultiReader extends Reader, CharReader {}
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "MultiReader"));
        assertTrue(ex.getMessage().contains("conflicting return types"), "Expected conflicting return types error, got: " + ex.getMessage());
    }

    // ==========================================
    // 14. ENUM INSTANTIATION CHECKS 
    // ==========================================

    @Test
    @DisplayName("Direct instantiation of enum via new fails compilation")
    public void testDirectEnumInstantiationFails() {
        String code = """
                public enum Day {
                    MONDAY, TUESDAY;
                }
                public class Main {
                    public static void function main(String[] args) {
                        Day d = new Day();
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Main"));
        assertTrue(ex.getMessage().contains("Cannot instantiate enum type"), "Expected enum instantiation error, got: " + ex.getMessage());
    }

    // ==========================================
    // 15. ENUM CONSTRUCTOR MODIFIER CHECKS 
    // ==========================================

    @Test
    @DisplayName("Declaring public constructor in enum fails compilation")
    public void testPublicEnumConstructorFails() {
        String code = """
                public enum Color {
                    RED;
                    public function Color() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Color"));
        assertTrue(ex.getMessage().contains("Enum constructor cannot be declared 'public' or 'protected'"), "Expected enum constructor modifier error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Declaring protected constructor in enum fails compilation")
    public void testProtectedEnumConstructorFails() {
        String code = """
                public enum Color {
                    RED;
                    protected function Color() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Color"));
        assertTrue(ex.getMessage().contains("Enum constructor cannot be declared 'public' or 'protected'"), "Expected enum constructor modifier error, got: " + ex.getMessage());
    }

    // ==========================================
    // 16. ENUM DECLARATION MODIFIER CHECKS 
    // ==========================================

    @Test
    @DisplayName("Declaring abstract enum fails compilation")
    public void testAbstractEnumFails() {
        String code = """
                public abstract enum Color {
                    RED;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Color"));
        assertTrue(ex.getMessage().contains("cannot be declared"), "Expected abstract enum error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Declaring final enum fails compilation")
    public void testFinalEnumFails() {
        String code = """
                public final enum Color {
                    RED;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Color"));
        assertTrue(ex.getMessage().contains("cannot be declared"), "Expected final enum error, got: " + ex.getMessage());
    }

    // ==========================================
    // 17. TOP-LEVEL PRIVATE / PROTECTED CHECKS (, §8.9, §9.1.1)
    // ==========================================

    @Test
    @DisplayName("Declaring top-level private class fails compilation")
    public void testTopLevelPrivateClassFails() {
        String code = """
                private class Secret {
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Secret"));
        assertTrue(ex.getMessage().contains("cannot be declared"), "Expected top-level error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Declaring top-level protected class fails compilation")
    public void testTopLevelProtectedClassFails() {
        String code = """
                protected class Secret {
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Secret"));
        assertTrue(ex.getMessage().contains("cannot be declared"), "Expected top-level error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Declaring top-level private interface fails compilation")
    public void testTopLevelPrivateInterfaceFails() {
        String code = """
                private interface SecretI {
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SecretI"));
        assertTrue(ex.getMessage().contains("cannot be declared"), "Expected top-level error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Declaring top-level protected enum fails compilation")
    public void testTopLevelProtectedEnumFails() {
        String code = """
                protected enum SecretE {
                    A, B;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SecretE"));
        assertTrue(ex.getMessage().contains("cannot be declared"), "Expected top-level error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Declaring inner private class inside a class succeeds")
    public void testInnerPrivateClassSucceeds() {
        String code = """
                public class Outer {
                    private class Inner {
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "Outer"));
    }

    // ==========================================
    // 18. CONFLICTING & DUPLICATE MODIFIERS (JVM Spec §4.1, §4.5, §4.6)
    // ==========================================

    @Test
    @DisplayName("Conflicting visibility modifiers on class fail compilation")
    public void testConflictingClassModifiersFails() {
        String code = """
                public private class Conflicted {
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Conflicted"));
        assertTrue(ex.getMessage().contains("Illegal combination of modifiers"), "Expected conflicting access error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Conflicting visibility modifiers on method fail compilation")
    public void testConflictingMethodModifiersFails() {
        String code = """
                public class Main {
                    public protected void function foo() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Main"));
        assertTrue(ex.getMessage().contains("Illegal combination of modifiers"), "Expected conflicting access error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Conflicting visibility modifiers on field fail compilation")
    public void testConflictingFieldModifiersFails() {
        String code = """
                public class Main {
                    public private int x = 1;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Main"));
        assertTrue(ex.getMessage().contains("Illegal combination of modifiers"), "Expected conflicting access error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Duplicate modifiers fail compilation")
    public void testDuplicateModifiersFails() {
        String code = """
                public public class Dup {
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Dup"));
        assertTrue(ex.getMessage().contains("Repeated modifier"), "Expected duplicate modifier error, got: " + ex.getMessage());
    }

    // ==========================================
    // 19. VOID METHOD, CONSTRUCTOR & STATIC INITIALIZER RETURN CHECKS (, §8.7)
    // ==========================================

    @Test
    @DisplayName("Return null in void method fails compilation")
    public void testVoidMethodReturnNullFails() {
        String code = """
                public class Main {
                    public void function testVoid() {
                        return null;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Main"));
        assertTrue(ex.getMessage().contains("Cannot return a value"), "Expected void return error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Return with value or null in constructor fails compilation")
    public void testConstructorReturnNullFails() {
        String code = """
                public class Main {
                    public function Main() {
                        return null;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Main"));
        assertTrue(ex.getMessage().contains("Cannot return a value"), "Expected constructor return error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Return statement in static initializer fails compilation")
    public void testStaticInitializerReturnFails() {
        String code = """
                public class Main {
                    static {
                        return;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Main"));
        assertTrue(ex.getMessage().contains("static initializer"), "Expected static initializer return error, got: " + ex.getMessage());
    }

    // ==========================================
    // 20. INTERFACE FIELD MODIFIER CHECKS 
    // ==========================================

    @Test
    @DisplayName("Declaring private field in interface fails compilation")
    public void testInterfacePrivateFieldFails() {
        String code = """
                public interface I {
                    private int x = 1;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "I"));
        assertTrue(ex.getMessage().contains("Interface fields cannot be declared 'private' or 'protected'"), "Expected interface field modifier error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Declaring protected field in interface fails compilation")
    public void testInterfaceProtectedFieldFails() {
        String code = """
                public interface I {
                    protected int x = 1;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "I"));
        assertTrue(ex.getMessage().contains("Interface fields cannot be declared 'private' or 'protected'"), "Expected interface field modifier error, got: " + ex.getMessage());
    }

    // ==========================================
    // 21. INTERFACE FINAL MODIFIER CHECKS 
    // ==========================================

    @Test
    @DisplayName("Declaring final interface fails compilation")
    public void testFinalInterfaceFails() {
        String code = """
                public final interface FinalInterface {
                    void function test();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "FinalInterface"));
        assertTrue(ex.getMessage().contains("cannot be declared"), "Expected final interface error, got: " + ex.getMessage());
    }

    // ==========================================
    // 22. ABSTRACT METHOD BODY CHECKS 
    // ==========================================

    @Test
    @DisplayName("Abstract method with body in abstract class fails compilation")
    public void testAbstractMethodWithBodyFails() {
        String code = """
                public abstract class Animal {
                    public abstract void function speak() {
                        int x = 1;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Animal"));
        assertTrue(ex.getMessage().contains("cannot have a body"), "Expected abstract method body error, got: " + ex.getMessage());
    }

    // ==========================================
    // 23. ABSTRACT METHOD SYNC/LOCK CHECKS 
    // ==========================================

    @Test
    @DisplayName("Abstract method with sync/lock fails compilation")
    public void testAbstractMethodWithSyncFails() {
        String code = """
                public abstract class BaseSync {
                    public abstract sync void function doSync();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "BaseSync"));
        assertTrue(ex.getMessage().contains("lock/sync"), "Expected abstract sync error, got: " + ex.getMessage());
    }

    // ==========================================
    // 24. INTERFACE METHOD NATIVE CHECKS 
    // ==========================================

    @Test
    @DisplayName("Interface with native method fails compilation")
    public void testInterfaceMethodWithNativeFails() {
        String code = """
                public interface NativeInterface {
                    native void function nativeMethod();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "NativeInterface"));
        assertTrue(ex.getMessage().contains("cannot be declared"), "Expected interface native method error, got: " + ex.getMessage());
    }

    // ==========================================
    // 25. CONSTRUCTOR ILLEGAL MODIFIERS CHECKS 
    // ==========================================

    @Test
    @DisplayName("Static constructor fails compilation")
    public void testStaticConstructorFails() {
        String code = """
                public class StaticCtor {
                    public static function StaticCtor() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "StaticCtor"));
        assertTrue(ex.getMessage().contains("Constructors can only have"), "Expected static constructor error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Final constructor fails compilation")
    public void testFinalConstructorFails() {
        String code = """
                public class FinalCtor {
                    public final function FinalCtor() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "FinalCtor"));
        assertTrue(ex.getMessage().contains("Constructors can only have"), "Expected final constructor error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Abstract constructor fails compilation")
    public void testAbstractConstructorFails() {
        String code = """
                public class AbstractCtor {
                    public abstract function AbstractCtor() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "AbstractCtor"));
        assertTrue(ex.getMessage().contains("Constructors can only have"), "Expected abstract constructor error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Sync constructor fails compilation")
    public void testSyncConstructorFails() {
        String code = """
                public class SyncCtor {
                    public sync function SyncCtor() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SyncCtor"));
        assertTrue(ex.getMessage().contains("Constructors can only have"), "Expected sync constructor error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Native constructor fails compilation")
    public void testNativeConstructorFails() {
        String code = """
                public class NativeCtor {
                    public native function NativeCtor() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "NativeCtor"));
        assertTrue(ex.getMessage().contains("Constructors can only have"), "Expected native constructor error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Overriding public method with private method fails compilation")
    public void testOverridingPublicMethodWithPrivateFails() {
        String code = """
                public class Parent {
                    public void function doWork() {}
                }
                public class Child extends Parent {
                    private void function doWork() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Child"));
        assertTrue(ex.getMessage().contains("Cannot reduce visibility"),
                "Expected weaker access error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Overriding protected method with private method fails compilation")
    public void testOverridingProtectedMethodWithPrivateFails() {
        String code = """
                public class Parent {
                    protected void function doWork() {}
                }
                public class Child extends Parent {
                    private void function doWork() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Child"));
        assertTrue(ex.getMessage().contains("Cannot reduce visibility"),
                "Expected weaker access error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Overriding protected method with public method succeeds")
    public void testOverridingProtectedMethodWithPublicPasses() {
        String code = """
                public class Parent {
                    protected void function doWork() {}
                }
                public class Child extends Parent {
                    public void function doWork() {}
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "Child"));
    }

    @Test
    @DisplayName("Implementing interface method with private method fails compilation")
    public void testImplementingInterfaceMethodWithPrivateFails() {
        String code = """
                public interface Greeter {
                    void function greet();
                }
                public class Hello implements Greeter {
                    private void function greet() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Hello"));
        assertTrue(ex.getMessage().contains("arayüz") || ex.getMessage().contains("public") || ex.getMessage().contains("Cannot reduce visibility"),
                "Expected interface method public access error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Implementing interface method with protected method fails compilation")
    public void testImplementingInterfaceMethodWithProtectedFails() {
        String code = """
                public interface Greeter {
                    void function greet();
                }
                public class Hello implements Greeter {
                    protected void function greet() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Hello"));
        assertTrue(ex.getMessage().contains("arayüz") || ex.getMessage().contains("public") || ex.getMessage().contains("Cannot reduce visibility"),
                "Expected interface method public access error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Duplicate class type parameters fail compilation")
    public void testDuplicateClassTypeParametersFails() {
        String code = """
                public class Box<T, T> {
                    public T value;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Box"));
        assertTrue(ex.getMessage().contains("Duplicate type parameter"),
                "Expected duplicate type parameter error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Duplicate method type parameters fail compilation")
    public void testDuplicateMethodTypeParametersFails() {
        String code = """
                public class Utils {
                    public <T, T> void function process(T item) {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Utils"));
        assertTrue(ex.getMessage().contains("Duplicate type parameter"),
                "Expected duplicate method type parameter error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Instance method overriding static method fails compilation")
    public void testInstanceMethodOverridingStaticMethodFails() {
        String code = """
                public class Parent {
                    public static void function doWork() {}
                }
                public class Child extends Parent {
                    public void function doWork() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Child"));
        assertTrue(ex.getMessage().contains("Instance method 'doWork' cannot"),
                "Expected instance overriding static error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Static method hiding instance method fails compilation")
    public void testStaticMethodHidingInstanceMethodFails() {
        String code = """
                public class Parent {
                    public void function doWork() {}
                }
                public class Child extends Parent {
                    public static void function doWork() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Child"));
        assertTrue(ex.getMessage().contains("instance method") || ex.getMessage().contains("gizleyemez"),
                "Expected static hiding instance error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Non-Throwable in throws clause fails compilation")
    public void testThrowsNonThrowableFails() {
        String code = """
                public class TestThrows {
                    public void function doWork() throws String {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestThrows"));
        assertTrue(ex.getMessage().contains("Throwable"),
                "Expected throws Throwable error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Reassigning interface field directly fails compilation")
    public void testInterfaceFieldReassignmentFails() {
        String code = """
                public interface Config {
                    public int MAX = 100;
                }
                public class App {
                    public void function run() {
                        Config.MAX = 200;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "App"));
        assertTrue(ex.getMessage().contains("Cannot assign"),
                "Expected interface field final reassignment error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Reassigning interface field via implementing class fails compilation")
    public void testImplementingClassFieldReassignmentFails() {
        String code = """
                interface Config {
                    int MAX = 100;
                }
                class AppConfig implements Config {}
                public class App {
                    public void function run() {
                        AppConfig.MAX = 200;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "App"));
        assertTrue(ex.getMessage().contains("Cannot assign"),
                "Expected interface field reassignment error via class, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Calling interface static method via implementing class fails compilation")
    public void testCallingInterfaceStaticMethodViaClassFails() {
        String code = """
                interface Greeter {
                    static void function greet() {}
                }
                class EnglishGreeter implements Greeter {}
                public class App {
                    public void function run() {
                        EnglishGreeter.greet();
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "App"));
        assertTrue(ex.getMessage().contains("cannot be invoked"),
                "Expected interface static method inheritance error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Calling interface static method via instance receiver fails compilation")
    public void testCallingInterfaceStaticMethodViaInstanceFails() {
        String code = """
                interface Greeter {
                    static void function greet() {}
                }
                public class App {
                    public void function run(Greeter g) {
                        g.greet();
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "App"));
        assertTrue(ex.getMessage().contains("Static method") || ex.getMessage().contains("object reference"),
                "Expected interface static method via instance error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Calling interface static method directly via interface name succeeds")
    public void testCallingInterfaceStaticMethodDirectlySucceeds() {
        String code = """
                interface Greeter {
                    static String function getGreeting() {
                        return "Hello";
                    }
                }
                public class App {
                    public String function run() {
                        return Greeter.getGreeting();
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "App"));
    }

    // ==========================================
    // 14. INTERFACE ADVANCED & DIAMOND CONFLICT TESTS
    // ==========================================

    @Test
    @DisplayName("Duplicate interface in implements clause fails compilation")
    public void testDuplicateInterfaceInImplementsFails() {
        String code = """
                interface Worker {
                    void function work();
                }
                public class MyWorker implements Worker, Worker {
                    public void function work() {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "MyWorker"));
        assertTrue(ex.getMessage().contains("Duplicate interface"),
                "Expected duplicate interface error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Duplicate interface in interface extends clause fails compilation")
    public void testDuplicateInterfaceInExtendsFails() {
        String code = """
                interface BaseA {}
                public interface SubWorker extends BaseA, BaseA {}
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SubWorker"));
        assertTrue(ex.getMessage().contains("Duplicate interface"),
                "Expected duplicate interface error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Duplicate field in interface fails compilation")
    public void testDuplicateFieldInInterfaceFails() {
        String code = """
                public interface Constants {
                    int VALUE = 10;
                    int VALUE = 20;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Constants"));
        assertTrue(ex.getMessage().contains("already defined"),
                "Expected duplicate field in interface error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Conflicting default methods in class without override fails compilation")
    public void testConflictingDefaultMethodsInClassFails() {
        String code = """
                interface A {
                    default String function hello() { return "A"; }
                }
                interface B {
                    default String function hello() { return "B"; }
                }
                public class App implements A, B {}
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "App"));
        assertTrue(ex.getMessage().contains("Class 'App' inherits unrelated"),
                "Expected diamond conflict error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Conflicting default methods resolved by explicit class override succeeds")
    public void testConflictingDefaultMethodsResolvedByOverridePasses() {
        String code = """
                interface A {
                    default String function hello() { return "A"; }
                }
                interface B {
                    default String function hello() { return "B"; }
                }
                public class App implements A, B {
                    public String function hello() {
                        return "Resolved";
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "App"));
    }

    @Test
    @DisplayName("Sub-interface default method dominates super-interface default method (Rule 2)")
    public void testDominantDefaultMethodPasses() {
        String code = """
                interface Base {
                    default String function hello() { return "Base"; }
                }
                interface Sub extends Base {
                    default String function hello() { return "Sub"; }
                }
                public class App implements Base, Sub {}
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "App"));
    }

    @Test
    @DisplayName("Superclass concrete method overrides interface default method (Rule 1)")
    public void testClassWinsOverDefaultMethodPasses() {
        String code = """
                interface Greeter {
                    default String function hello() { return "Interface"; }
                }
                public class Parent {
                    public String function hello() { return "Parent"; }
                }
                public class Child extends Parent implements Greeter {}
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "Child"));
    }

    @Test
    @DisplayName("Conflicting default methods in sub-interface without override fails compilation")
    public void testConflictingDefaultMethodsInSubInterfaceFails() {
        String code = """
                interface A {
                    default String function hello() { return "A"; }
                }
                interface B {
                    default String function hello() { return "B"; }
                }
                public interface SubInterface extends A, B {}
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SubInterface"));
        assertTrue(ex.getMessage().contains("Interface 'SubInterface' inherits unrelated"),
                "Expected diamond conflict in sub-interface error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Conflicting default methods in sub-interface resolved by default override succeeds")
    public void testConflictingDefaultMethodsInSubInterfaceResolvedByOverridePasses() {
        String code = """
                interface A {
                    default String function hello() { return "A"; }
                }
                interface B {
                    default String function hello() { return "B"; }
                }
                public interface SubInterface extends A, B {
                    default String function hello() { return "ResolvedInSub"; }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "SubInterface"));
    }

    // ==========================================
    // 15. INTERFACE MODIFIERS & AMBIGUOUS FIELDS TESTS
    // ==========================================

    @Test
    @DisplayName("Declaring private default method in interface fails compilation")
    public void testPrivateDefaultMethodInInterfaceFails() {
        String code = """
                interface Calculator {
                    private default int function add(int a, int b) {
                        return a + b;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Calculator"));
        assertTrue(ex.getMessage().contains("Interface method cannot be"),
                "Expected private default error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Declaring variable (mutable) field in interface fails compilation")
    public void testVariableFieldInInterfaceFails() {
        String code = """
                public interface Config {
                    variable x = 10;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Config"));
        assertTrue(ex.getMessage().contains("Cannot declare mutable ('variable') field in interface"),
                "Expected variable field in interface error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Ambiguous field access via implementing class fails compilation")
    public void testAmbiguousInterfaceFieldAccessViaClassFails() {
        String code = """
                interface Alpha { int TIMEOUT = 10; }
                interface Beta  { int TIMEOUT = 20; }
                public class Client implements Alpha, Beta {}
                public class App {
                    public int function run() {
                        return Client.TIMEOUT;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "App"));
        assertTrue(ex.getMessage().contains("Ambiguous field reference"),
                "Expected ambiguous field error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Ambiguous field access directly within implementing class fails compilation")
    public void testAmbiguousInterfaceFieldAccessDirectFails() {
        String code = """
                interface Alpha { int TIMEOUT = 10; }
                interface Beta  { int TIMEOUT = 20; }
                public class Client implements Alpha, Beta {
                    public int function getTimeout() {
                        return TIMEOUT;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Client"));
        assertTrue(ex.getMessage().contains("Ambiguous field reference"),
                "Expected ambiguous field error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Ambiguous interface field resolved by class explicit shadowing succeeds")
    public void testAmbiguousInterfaceFieldResolvedByClassShadowingPasses() {
        String code = """
                interface Alpha { int TIMEOUT = 10; }
                interface Beta  { int TIMEOUT = 20; }
                public class Client implements Alpha, Beta {
                    public static int TIMEOUT = 99;
                }
                public class App {
                    public int function run() {
                        return Client.TIMEOUT;
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "App"));
    }

    @Test
    @DisplayName("Single inherited interface constant via class succeeds and executes correctly")
    public void testSingleInterfaceFieldViaClassSucceedsAndExecutes() throws Exception {
        String code = """
                interface Alpha { int TIMEOUT = 10; }
                public class Client implements Alpha {}
                public class App {
                    public static int function run() {
                        return Client.TIMEOUT;
                    }
                }
                """;
        Map<String, Class<?>> loaded = compileAndLoadAll("App", code);
        Class<?> appCls = null;
        for (Map.Entry<String, Class<?>> e : loaded.entrySet()) {
            if (e.getKey().endsWith("App")) appCls = e.getValue();
        }
        assertNotNull(appCls, "App class should be loaded");
        Object result = appCls.getMethod("run").invoke(null);
        assertEquals(10, result, "Client.TIMEOUT should resolve to Alpha.TIMEOUT value 10");
    }

    @Test
    @DisplayName("Declaring instance initializer block in interface fails compilation")
    public void testInterfaceInitializerBlockFails() {
        String code = """
                interface Service {
                    {
                        println("init");
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Service"));
        assertTrue(ex.getMessage().contains("Initializer blocks ({ ... }) cannot be defined in interfaces") || ex.getMessage().contains("Interfaces cannot declare constructors"),
                "Expected interface initializer block error, got: " + ex.getMessage());
    }

    // ==========================================
    // SECTION 16: INTERFACE MEMBER TYPES, FIELD TYPES, IMPLICIT STATIC & ENUM ABSTRACT METHODS
    // ==========================================

    @Test
    @DisplayName("Interface field initializer type mismatch fails compilation")
    public void testInterfaceFieldTypeMismatchFails() {
        String code = """
                public interface Config {
                    int MAX = "not an int";
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Config"));
        assertTrue(ex.getMessage().contains("Incompatible types"),
                "Expected type mismatch error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Private member class inside interface fails compilation")
    public void testPrivateClassInInterfaceFails() {
        String code = """
                public interface Service {
                    private class Helper {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Service"));
        assertTrue(ex.getMessage().contains("Interface member classes cannot be declared 'private' or 'protected'"),
                "Expected private class in interface error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Protected member interface inside interface fails compilation")
    public void testProtectedInterfaceInInterfaceFails() {
        String code = """
                public interface Service {
                    protected interface Inner {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Service"));
        assertTrue(ex.getMessage().contains("Interface member interfaces cannot be declared 'private' or 'protected'"),
                "Expected protected interface in interface error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Private member enum inside interface fails compilation")
    public void testPrivateEnumInInterfaceFails() {
        String code = """
                public interface Service {
                    private enum Mode { FAST, SLOW }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Service"));
        assertTrue(ex.getMessage().contains("Interface member enums cannot be declared 'private' or 'protected'"),
                "Expected private enum in interface error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Nested member types inside interface have ACC_STATIC and ACC_PUBLIC")
    public void testNestedTypesInInterfaceHaveAccStatic() {
        String code = """
                public interface OuterInterface {
                    class InnerClass {}
                    interface InnerInterface {}
                    enum InnerEnum { A, B }
                }
                """;
        Map<String, byte[]> bytecodes = compileToBytecodeMap(code, "OuterInterface");
        for (Map.Entry<String, byte[]> entry : bytecodes.entrySet()) {
            if (entry.getKey().contains("$")) {
                org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(entry.getValue());
                assertTrue((cr.getAccess() & Opcodes.ACC_STATIC) != 0,
                        "Expected ACC_STATIC on " + entry.getKey() + " but got: " + cr.getAccess());
                assertTrue((cr.getAccess() & Opcodes.ACC_PUBLIC) != 0,
                        "Expected ACC_PUBLIC on " + entry.getKey() + " but got: " + cr.getAccess());
            }
        }
    }

    @Test
    @DisplayName("Nested interface and enum inside class have implicit ACC_STATIC")
    public void testNestedInterfaceAndEnumInClassHaveAccStatic() {
        String code = """
                public class EnclosingClass {
                    interface NestedInterface {}
                    enum NestedEnum { ONE, TWO }
                }
                """;
        Map<String, byte[]> bytecodes = compileToBytecodeMap(code, "EnclosingClass");
        for (Map.Entry<String, byte[]> entry : bytecodes.entrySet()) {
            if (entry.getKey().contains("NestedInterface") || entry.getKey().contains("NestedEnum")) {
                org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(entry.getValue());
                assertTrue((cr.getAccess() & Opcodes.ACC_STATIC) != 0,
                        "Expected ACC_STATIC on " + entry.getKey() + " but got: " + cr.getAccess());
            }
        }
    }

    @Test
    @DisplayName("Enum with abstract method but no constant bodies fails compilation")
    public void testEnumWithUnimplementedAbstractMethodFails() {
        String code = """
                public enum Operation {
                    PLUS, MINUS;
                    public abstract int function apply(int a, int b);
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Operation"));
        assertTrue(ex.getMessage().contains("Enum constant 'PLUS' must"),
                "Expected unimplemented abstract method error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Enum with abstract method implemented by all constant bodies compiles and runs")
    public void testEnumWithImplementedAbstractMethodPasses() throws Exception {
        String code = """
                public enum Operation {
                    PLUS {
                        public int function apply(int a, int b) {
                            return a + b;
                        }
                    },
                    MINUS {
                        public int function apply(int a, int b) {
                            return a - b;
                        }
                    };
                    public abstract int function apply(int a, int b);
                }
                public class App {
                    public static int function run() {
                        return Operation.PLUS.apply(10, 5) + Operation.MINUS.apply(10, 5);
                    }
                }
                """;
        Map<String, Class<?>> loaded = compileAndLoadAll("App", code);
        Class<?> appCls = null;
        for (Map.Entry<String, Class<?>> e : loaded.entrySet()) {
            if (e.getKey().endsWith("App")) appCls = e.getValue();
        }
        assertNotNull(appCls, "App class should be loaded");
        Object result = appCls.getMethod("run").invoke(null);
        assertEquals(20, result, "PLUS(10,5) + MINUS(10,5) should be 15 + 5 = 20");
    }

    // ==========================================
    // SECTION 17: INTERFACE STATIC/PRIVATE METHOD BODIES, DEFAULT OVERRIDE OF OBJECT & ANNOTATION DEFAULTS
    // ==========================================

    @Test
    @DisplayName("Static method in interface without body fails compilation")
    public void testInterfaceStaticMethodWithoutBodyFails() {
        String code = """
                public interface Service {
                    public static void function doWork();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Service"));
        assertTrue(ex.getMessage().contains("Method 'doWork' cannot be"),
                "Expected static method in interface body error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Private method in interface without body fails compilation")
    public void testInterfacePrivateMethodWithoutBodyFails() {
        String code = """
                public interface Service {
                    private void function helper();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Service"));
        assertTrue(ex.getMessage().contains("Method 'helper' cannot be"),
                "Expected private method in interface body error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Default method in interface overriding java.lang.Object method fails compilation")
    public void testInterfaceDefaultMethodOverridingObjectMethodsFails() {
        String code = """
                public interface Formatter {
                    public default String function toString() {
                        return "formatted";
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Formatter"));
        assertTrue(ex.getMessage().contains("java.lang.Object") && ex.getMessage().contains("cannot override"),
                "Expected default method overriding Object error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Default method in interface overriding equals(Object) fails compilation")
    public void testInterfaceDefaultMethodOverridingEqualsFails() {
        String code = """
                public interface Equatable {
                    public default boolean function equals(Object other) {
                        return true;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Equatable"));
        assertTrue(ex.getMessage().contains("java.lang.Object") && ex.getMessage().contains("cannot override"),
                "Expected default method overriding equals error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Annotation default value type mismatch fails compilation")
    public void testAnnotationDefaultValueTypeMismatchFails() {
        String code = """
                public annotation Config {
                    int timeout() default "100";
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Config"));
        assertTrue(ex.getMessage().contains("Incompatible type for annotation"),
                "Expected annotation default type mismatch, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Annotation default value literal out of bounds fails compilation")
    public void testAnnotationDefaultValueLiteralOutOfBoundsFails() {
        String code = """
                public annotation Config {
                    byte val() default 999;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Config"));
        assertTrue(ex.getMessage().contains("Constant value") && ex.getMessage().contains("out of range"),
                "Expected literal out of bounds error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Annotation method with static modifier fails compilation")
    public void testAnnotationMethodWithInvalidModifiersFails() {
        String code = """
                public annotation Ann {
                    static int count();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Ann"));
        assertTrue(ex.getMessage().contains("Modifier 'static' not allowed on annotation method")
                        || ex.getMessage().contains("Annotation methods cannot be declared 'static'"),
                "Expected invalid modifier on annotation method error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Annotation method with valid public abstract and matching default succeeds")
    public void testAnnotationMethodWithValidModifiersAndDefaultPasses() {
        String code = """
                public annotation WebConfig {
                    public abstract int port() default 8080;
                    String path() default "/api";
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "WebConfig"));
    }

    // ==========================================
    // 18. VARARGS, SEALED INTERFACES, MODIFIER CONFLICTS, STATIC FINAL & TRY-WITH-RESOURCES (JVM SPEC)
    // ==========================================

    @Test
    @DisplayName("Varargs parameter not in last position in method fails compilation")
    public void testVarargsNotLastParameterInMethodFails() {
        String code = """
                public class VarargsTest {
                    public void function test(int... a, String b) {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "VarargsTest"));
        assertTrue(ex.getMessage().contains("Varargs parameter"),
                "Expected varargs not last error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Multiple varargs parameters in method fails compilation")
    public void testMultipleVarargsInMethodFails() {
        String code = """
                public class MultiVarargsTest {
                    public void function test(int... a, String... b) {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "MultiVarargsTest"));
        assertTrue(ex.getMessage().contains("Varargs parameter"),
                "Expected multiple varargs error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Varargs parameter not in last position in constructor fails compilation")
    public void testVarargsNotLastParameterInConstructorFails() {
        String code = """
                public class CtorVarargsTest {
                    public function CtorVarargsTest(int... a, String b) {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "CtorVarargsTest"));
        assertTrue(ex.getMessage().contains("Varargs parameter"),
                "Expected varargs not last in constructor error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Unpermitted class implementing sealed interface fails compilation")
    public void testSealedInterfaceUnpermittedClassImplementsFails() {
        String code = """
                sealed interface Shape restricts Circle
                final class Circle implements Shape {}
                final class Triangle implements Shape {}
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Triangle"));
        assertTrue(ex.getMessage().contains("Class 'Triangle' is not"),
                "Expected unpermitted class implements sealed interface error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Permitted class implementing sealed interface without final/sealed/non-sealed fails compilation")
    public void testSealedInterfacePermittedClassMissingSubclassStatusFails() {
        String code = """
                sealed interface Shape restricts Circle
                class Circle implements Shape {}
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Circle"));
        assertTrue(ex.getMessage().contains("final") && ex.getMessage().contains("sealed"),
                "Expected missing subclass status error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Permitted final class implementing sealed interface succeeds")
    public void testSealedInterfacePermittedClassWithFinalPasses() {
        String code = """
                sealed interface Shape restricts Circle
                final class Circle implements Shape {}
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "Circle"));
    }

    @Test
    @DisplayName("Unpermitted interface extending sealed interface fails compilation")
    public void testSealedInterfaceUnpermittedInterfaceExtendsFails() {
        String code = """
                sealed interface Shape restricts SubShape
                non-sealed interface SubShape extends Shape {}
                non-sealed interface OtherShape extends Shape {}
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "OtherShape"));
        assertTrue(ex.getMessage().contains("Interface 'OtherShape' is not"),
                "Expected unpermitted interface extends sealed interface error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Permitted subinterface extending sealed interface without sealed/non-sealed fails compilation")
    public void testSealedInterfacePermittedInterfaceMissingSealedOrNonSealedFails() {
        String code = """
                sealed interface Shape restricts SubShape
                interface SubShape extends Shape {}
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SubShape"));
        assertTrue(ex.getMessage().contains("sealed") && ex.getMessage().contains("non-sealed"),
                "Expected missing sealed or non-sealed modifier on interface, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Permitted non-sealed subinterface extending sealed interface succeeds")
    public void testSealedInterfacePermittedInterfaceWithNonSealedPasses() {
        String code = """
                sealed interface Shape restricts SubShape
                non-sealed interface SubShape extends Shape {}
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "SubShape"));
    }

    @Test
    @DisplayName("Conflicting inheritance modifiers on class fail compilation")
    public void testConflictingInheritanceModifiersOnClassFails() {
        String code = """
                final sealed class ConflictClass {}
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ConflictClass"));
        assertTrue(ex.getMessage().contains("Illegal combination of modifiers"),
                "Expected conflicting modifiers error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Conflicting sealed and non-sealed modifiers on interface fail compilation")
    public void testConflictingInheritanceModifiersOnInterfaceFails() {
        String code = """
                sealed non-sealed interface ConflictIface {}
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ConflictIface"));
        assertTrue(ex.getMessage().contains("sealed") && ex.getMessage().contains("non-sealed"),
                "Expected conflicting sealed/non-sealed error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Declaring annotation with final/sealed/non-sealed fails compilation")
    public void testAnnotationWithInvalidModifiersFails() {
        String code = """
                final annotation FinalAnno {}
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "FinalAnno"));
        assertTrue(ex.getMessage().contains("cannot be declared"),
                "Expected invalid modifier on annotation error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Static final field without initializer or static block fails compilation")
    public void testStaticFinalFieldUninitializedFails() {
        String code = """
                public class StaticFinalClass {
                    public static final int CONST_VAL;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "StaticFinalClass"));
        assertTrue(ex.getMessage().contains("'static value' field declarations"),
                "Expected static final uninitialized error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Static final field initialized in static block succeeds")
    public void testStaticFinalFieldInitializedInStaticBlockPasses() {
        String code = """
                public class StaticFinalBlockClass {
                    public static final int CONST_VAL;
                    static {
                        CONST_VAL = 42;
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "StaticFinalBlockClass"));
    }

    @Test
    @DisplayName("Try-with-resources resource variable reassignment fails compilation")
    public void testTryWithResourcesVariableReassignmentFails() {
        String code = """
                import java.io.ByteArrayInputStream;
                public class TryResTest {
                    public void function run() {
                        trying (ByteArrayInputStream in = new ByteArrayInputStream(new byte[0])) {
                            in = new ByteArrayInputStream(new byte[0]);
                        } catch (Exception e) {}
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TryResTest"));
        assertTrue(ex.getMessage().contains("Cannot assign a value to final"),
                "Expected final variable reassignment error, got: " + ex.getMessage());
    }

    // ==========================================
    // 19. JVMS STRICT SPECIFICATION COMPLIANCE TESTS
    // ==========================================

    // Item 1: super.staticMethod() invocation prohibition (JVMS §6.5 invokespecial)
    @Test
    @DisplayName("super.staticMethod() fails compilation")
    public void testSuperStaticMethodCallFails() {
        String code = """
                class ParentClass {
                    public static void function doStatic() {}
                }
                public class ChildClass extends ParentClass {
                    public void function test() {
                        super.doStatic();
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ChildClass"));
        assertTrue(ex.getMessage().contains("cannot be invoked using 'super'"),
                "Expected super.staticMethod error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Calling static method via class name or instance method via super succeeds")
    public void testValidStaticOrSuperInstanceCallPasses() {
        String code = """
                class ParentClass {
                    public static void function doStatic() {}
                    public void function doInstance() {}
                }
                public class ChildClass extends ParentClass {
                    public void function test() {
                        ParentClass.doStatic();
                        super.doInstance();
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "ChildClass"));
    }

    // Item 2: Overriding method cannot declare broader/uncovered checked exceptions in throws 
    @Test
    @DisplayName("Overriding method declaring broader checked exception fails compilation")
    public void testOverridingMethodBroaderCheckedExceptionFails() {
        String code = """
                import java.io.IOException;
                class ParentService {
                    public void function execute() {}
                }
                public class ChildService extends ParentService {
                    public void function execute() throws IOException {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ChildService"));
        assertTrue(ex.getMessage().contains("Overridden method in 'ParentService'"),
                "Expected throws checked exception mismatch error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Implementing interface method declaring undeclared checked exception fails compilation")
    public void testInterfaceMethodImplementationBroaderCheckedExceptionFails() {
        String code = """
                import java.io.IOException;
                interface Action {
                    void function run();
                }
                public class ActionImpl implements Action {
                    public void function run() throws IOException {}
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ActionImpl"));
        assertTrue(ex.getMessage().contains("Overridden method in 'Action'"),
                "Expected interface throws checked exception mismatch error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Overriding method declaring narrower or unchecked exception succeeds")
    public void testOverridingMethodNarrowerOrUncheckedExceptionPasses() {
        String code = """
                import java.io.IOException;
                import java.io.FileNotFoundException;
                class ParentService {
                    public void function execute() throws IOException {}
                }
                public class ChildService extends ParentService {
                    public void function execute() throws FileNotFoundException, RuntimeException {}
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "ChildService"));
    }

    // Item 3: Classic switch selector expression cannot be primitive long, float, or double 
    @Test
    @DisplayName("Classic switch with long selector fails compilation")
    public void testClassicSwitchWithLongFails() {
        String code = """
                public class SwitchLongTest {
                    public void function test(long x) {
                        switch (x) {
                            case 1L: {}
                            default: {}
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchLongTest"));
        assertTrue(ex.getMessage().contains("Selector expression in switch cannot be of type 'long', 'float', or 'double'"),
                "Expected switch selector type error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Classic switch with double selector fails compilation")
    public void testClassicSwitchWithDoubleFails() {
        String code = """
                public class SwitchDoubleTest {
                    public void function test(double x) {
                        switch (x) {
                            case 1.0: {}
                            default: {}
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SwitchDoubleTest"));
        assertTrue(ex.getMessage().contains("Selector expression in switch cannot be of type 'long', 'float', or 'double'"),
                "Expected switch selector type error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Pattern matching switch on Object with Long case succeeds")
    public void testPatternMatchingSwitchWithLongPasses() {
        String code = """
                public class SwitchPatternTest {
                    public String function test(Object obj) {
                        return switch (obj) {
                            case Long l -> "is long";
                            default -> "other";
                        };
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "SwitchPatternTest"));
    }

    // Item 4: Method & constructor parameter slot limit (max 255 slots, JVMS §4.3.3)
    @Test
    @DisplayName("Method exceeding 255 parameter slots fails compilation")
    public void testMethodExceeding255SlotsFails() {
        StringBuilder sb = new StringBuilder("public class SlotLimitTest {\n");
        sb.append("    public void function bigMethod(");
        for (int i = 0; i < 255; i++) {
            if (i > 0) sb.append(", ");
            sb.append("int p").append(i);
        }
        sb.append(") {}\n}\n");
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(sb.toString(), "SlotLimitTest"));
        assertTrue(ex.getMessage().contains("parameter slots limit exceeded"),
                "Expected parameter slot limit error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Constructor exceeding 255 parameter slots fails compilation")
    public void testConstructorExceeding255SlotsFails() {
        StringBuilder sb = new StringBuilder("public class CtorSlotLimitTest {\n");
        sb.append("    public CtorSlotLimitTest(");
        for (int i = 0; i < 128; i++) {
            if (i > 0) sb.append(", ");
            sb.append("long p").append(i);
        }
        sb.append(") {}\n}\n");
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(sb.toString(), "CtorSlotLimitTest"));
        assertTrue(ex.getMessage().contains("parameter slots limit exceeded"),
                "Expected ctor parameter slot limit error, got: " + ex.getMessage());
    }

    // Item 5: Array dimension count limit (max 255 dimensions, JVMS §4.3.2)
    @Test
    @DisplayName("Array type exceeding 255 dimensions fails compilation")
    public void testArrayDimensionsExceeding255Fails() {
        StringBuilder sb = new StringBuilder("public class ArrayDimLimitTest {\n");
        sb.append("    int").append("[]".repeat(256)).append(" arr;\n");
        sb.append("}\n");
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(sb.toString(), "ArrayDimLimitTest"));
        assertTrue(ex.getMessage().contains("Array dimension limit exceeded"),
                "Expected array dimensions limit error, got: " + ex.getMessage());
    }

    // Item 6: instanceof with inconvertible reference types (§5.5)
    @Test
    @DisplayName("instanceof with inconvertible reference types (String instanceof Integer) fails compilation")
    public void testInstanceofInconvertibleTypesFails() {
        String code = """
                public class InstanceofTest {
                    public void function test(String s) {
                        if (s instanceof Integer) {}
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "InstanceofTest"));
        assertTrue(ex.getMessage().contains("Inconvertible types"),
                "Expected inconvertible instanceof error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("instanceof with final class and unrelated interface (String instanceof List) fails compilation")
    public void testInstanceofFinalClassUnrelatedInterfaceFails() {
        String code = """
                import java.util.List;
                public class InstanceofInterfaceTest {
                    public void function test(String s) {
                        if (s instanceof List) {}
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "InstanceofInterfaceTest"));
        assertTrue(ex.getMessage().contains("Inconvertible types"),
                "Expected inconvertible instanceof error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("instanceof on primitive fails compilation")
    public void testInstanceofPrimitiveFails() {
        String code = """
                public class InstanceofPrimTest {
                    public void function test(int x) {
                        if (x instanceof String) {}
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "InstanceofPrimTest"));
        assertTrue(ex.getMessage().contains("Inconvertible types"),
                "Expected inconvertible instanceof error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("instanceof with convertible types succeeds")
    public void testInstanceofConvertibleTypesPasses() {
        String code = """
                public class InstanceofValidTest {
                    public boolean function test(Object obj) {
                        return obj instanceof String;
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "InstanceofValidTest"));
    }

    // Item 7: Illegal forward reference in field initializers 
    @Test
    @DisplayName("Illegal forward reference in field initializer fails compilation")
    public void testIllegalForwardReferenceInFieldInitializerFails() {
        String code = """
                public class ForwardRefTest {
                    int a = b + 1;
                    int b = 10;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ForwardRefTest"));
        assertTrue(ex.getMessage().contains("Illegal forward reference") && ex.getMessage().contains("has not been declared yet"),
                "Expected illegal forward reference error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Self reference in field initializer fails compilation")
    public void testSelfReferenceInFieldInitializerFails() {
        String code = """
                public class SelfRefTest {
                    int x = x + 1;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SelfRefTest"));
        assertTrue(ex.getMessage().contains("Illegal forward reference") && ex.getMessage().contains("cannot be referenced in its own initializer"),
                "Expected self reference in field initializer error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid backward reference and this qualifier in field initializer succeeds")
    public void testValidFieldInitializerReferencesPasses() {
        String code = """
                public class ValidFieldInitTest {
                    int first = 10;
                    int second = first + 5;
                    int third = this.fourth;
                    int fourth = 20;
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "ValidFieldInitTest"));
    }

    // ==========================================
    // Section 20: Phase 17 - JVMS Strict Specification Compliance
    // ==========================================

    // Item 1: Generic class extending Throwable 
    @Test
    @DisplayName("Generic class extending Throwable or Exception fails compilation")
    public void testGenericClassExtendingThrowableFails() {
        String code = """
                public class MyGenericException<T> extends java.lang.Exception {
                    T data;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "MyGenericException"));
        assertTrue(ex.getMessage().contains("Generic class") && ex.getMessage().contains("Throwable"),
                "Expected generic class extending Throwable error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Non-generic class extending Exception succeeds compilation")
    public void testNonGenericClassExtendingExceptionPasses() {
        String code = """
                public class MyValidException extends java.lang.Exception {
                    public function MyValidException() {
                        super();
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "MyValidException"));
    }

    // Item 2: Directly extending java.lang.Record (JVMS §4.1)
    @Test
    @DisplayName("Normal class extending java.lang.Record fails compilation")
    public void testNormalClassExtendingRecordFails() {
        String code = """
                public class NormalClass extends java.lang.Record {
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "NormalClass"));
        assertTrue(ex.getMessage().contains("cannot directly inherit from java.lang.Record"),
                "Expected extending Record error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Data class implicitly extending Record succeeds compilation")
    public void testDataClassPasses() {
        String code = """
                public data class Point(int x, int y)
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "Point"));
    }

    // Item 3: Constructor argument passing this / uninitializedThis (JVMS §4.10.1.9)
    @Test
    @DisplayName("super(...) passing this fails compilation")
    public void testSuperPassingThisFails() {
        String code = """
                public class Base {
                    public function Base(Object obj) {}
                }
                public class Sub extends Base {
                    public function Sub() {
                        super(this);
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Sub"));
        assertTrue(ex.getMessage().contains("Cannot reference 'this' or 'super' before supertype constructor"),
                "Expected uninitializedThis error in super(this), got: " + ex.getMessage());
    }

    @Test
    @DisplayName("this(...) passing this fails compilation")
    public void testThisPassingThisFails() {
        String code = """
                public class DelegateCtor {
                    public function DelegateCtor(Object obj) {}
                    public function DelegateCtor() {
                        this(this);
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "DelegateCtor"));
        assertTrue(ex.getMessage().contains("Cannot reference 'this' or 'super' before supertype constructor"),
                "Expected uninitializedThis error in this(this), got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Valid constructor calls passing ordinary arguments succeed compilation")
    public void testValidConstructorCallsPass() {
        String code = """
                public class Base {
                    public function Base(int x, String msg) {}
                }
                public class Sub extends Base {
                    public function Sub() {
                        super(42, "hello");
                    }
                    public function Sub(int a) {
                        this();
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "Sub"));
    }

    // Item 4: Concrete class declaring abstract method (JVMS §4.1)
    @Test
    @DisplayName("Concrete class declaring abstract method fails compilation")
    public void testAbstractMethodInConcreteClassFails() {
        String code = """
                public class ConcreteClass {
                    public abstract void function doWork();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ConcreteClass"));
        assertTrue(ex.getMessage().contains("Non-abstract class"),
                "Expected abstract method in concrete class error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Abstract class declaring abstract method succeeds compilation")
    public void testAbstractMethodInAbstractClassPasses() {
        String code = """
                public abstract class AbstractBase {
                    public abstract void function doWork();
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "AbstractBase"));
    }

    // =========================================================================
    // SECTION 21: Phase 18 Strict JVMS SE 25 Compliance Rules
    // =========================================================================

    // Item 1: Mutually Exclusive Field Modifiers: final and volatile/sync (JVMS §4.5 & )
    @Test
    @DisplayName("Field declaring both final and sync fails compilation")
    public void testFieldFinalAndSyncFails() {
        String code = """
                public class TestFieldFinalSync {
                    sync final int counter = 0;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestFieldFinalSync"));
        assertTrue(ex.getMessage().contains("cannot be declared"),
                "Expected final & sync conflict error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Field declaring both value and sync fails compilation")
    public void testFieldValueAndSyncFails() {
        String code = """
                public class TestFieldValueSync {
                    sync value counter = 0;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestFieldValueSync"));
        assertTrue(ex.getMessage().contains("cannot be declared"),
                "Expected value & sync conflict error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Field declaring only final or only sync succeeds compilation")
    public void testFieldFinalOrSyncAlonePasses() {
        String code = """
                public class TestFieldAlone {
                    final int x = 1;
                    sync int y = 2;
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "TestFieldAlone"));
    }

    // Item 2: Static Initializers in Non-Static Inner Classes 
    @Test
    @DisplayName("Non-static inner class declaring static block fails compilation")
    public void testStaticBlockInNonStaticInnerClassFails() {
        String code = """
                public class Outer {
                    public class Inner {
                        static {
                            int x = 1;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Outer"));
        assertTrue(ex.getMessage().contains("Non-static inner classes cannot declare static initializer blocks"),
                "Expected inner class static block error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Static nested class declaring static block succeeds compilation")
    public void testStaticBlockInStaticNestedClassPasses() {
        String code = """
                public class Outer {
                    public static class Nested {
                        static {
                            int x = 1;
                        }
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "Outer"));
    }

    // Item 3: Enhanced For-Loop Expression Array or Iterable 
    @Test
    @DisplayName("Enhanced for loop iterating over non-iterable non-array primitive fails compilation")
    public void testForEachOverIntFails() {
        String code = """
                public class TestForInt {
                    public void function test() {
                        for (x in 42) {
                            int a = x;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestForInt"));
        assertTrue(ex.getMessage().contains("Loop expression must be an array or java.lang.Iterable"),
                "Expected non-iterable error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Enhanced for loop iterating over non-iterable custom object fails compilation")
    public void testForEachOverNonIterableObjectFails() {
        String code = """
                public class CustomNonIterable {}
                public class TestForCustom {
                    public void function test(CustomNonIterable obj) {
                        for (x in obj) {
                            int a = 1;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestForCustom"));
        assertTrue(ex.getMessage().contains("Loop expression must be an array or java.lang.Iterable"),
                "Expected non-iterable error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Enhanced for loop iterating over array and java.util.List succeeds compilation")
    public void testForEachOverArrayAndListPasses() {
        String code = """
                import java.util.List;
                public class TestForValid {
                    public void function testArray(int[] arr) {
                        for (x in arr) {
                            int a = x;
                        }
                    }
                    public void function testList(List<String> list) {
                        for (s in list) {
                            String str = s;
                        }
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "TestForValid"));
    }

    // Item 4: Try-With-Resources Expression AutoCloseable Check (JVMS §4.10.1)
    @Test
    @DisplayName("Try-with-resources with raw Object fails compilation")
    public void testTryWithResourcesRawObjectFails() {
        String code = """
                public class TestTwrObject {
                    public void function test(Object obj) {
                        trying (Object o = obj) {
                            int x = 1;
                        }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TestTwrObject"));
        assertTrue(ex.getMessage().contains("AutoCloseable"),
                "Expected AutoCloseable error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Try-with-resources with AutoCloseable succeeds compilation")
    public void testTryWithResourcesAutoCloseablePasses() {
        String code = """
                public class MyResource implements java.lang.AutoCloseable {
                    public void function close() {}
                }
                public class TestTwrValid {
                    public void function test() {
                        trying (MyResource r = new MyResource()) {
                            int x = 1;
                        }
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "TestTwrValid"));
    }

    // =========================================================================
    // SECTION 22: Phase 19 Strict JVMS SE 25 Compliance Rules
    // =========================================================================

    // Item 1: Inconvertible Cast Validation (§5.5.1)
    @Test
    @DisplayName("Cast String to int fails compilation")
    public void testCastStringToIntFails() {
        String code = """
                public class CastStringToIntTest {
                    public void function test() {
                        int x = (int) "abc";
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "CastStringToIntTest"));
        assertTrue(ex.getMessage().contains("Inconvertible types"),
                "Expected invalid cast error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Cast boolean to int fails compilation")
    public void testCastBooleanToIntFails() {
        String code = """
                public class CastBoolToIntTest {
                    public void function test() {
                        int x = (int) true;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "CastBoolToIntTest"));
        assertTrue(ex.getMessage().contains("Inconvertible types"),
                "Expected invalid cast error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Cast int to boolean fails compilation")
    public void testCastIntToBooleanFails() {
        String code = """
                public class CastIntToBoolTest {
                    public void function test() {
                        boolean b = (boolean) 1;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "CastIntToBoolTest"));
        assertTrue(ex.getMessage().contains("Inconvertible types"),
                "Expected invalid cast error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Cast disjoint final classes fails compilation")
    public void testCastDisjointFinalClassesFails() {
        String code = """
                public class CastDisjointTest {
                    public void function test() {
                        Integer i = 5;
                        String s = (String) i;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "CastDisjointTest"));
        assertTrue(ex.getMessage().contains("Inconvertible types"),
                "Expected invalid cast error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Cast unboxable Object succeeds compilation")
    public void testCastUnboxableObjectPasses() {
        String code = """
                public class CastUnboxableObjTest {
                    public void function test(Object obj) {
                        int x = (int) obj;
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "CastUnboxableObjTest"));
    }

    // Item 2: Class extending an enum type fails compilation (JVMS §4.1)
    @Test
    @DisplayName("Class extending user enum fails compilation")
    public void testClassExtendingUserEnumFails() {
        String code = """
                enum Color {
                    RED, GREEN, BLUE;
                }
                public class SubColor extends Color {
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SubColor"));
        assertTrue(ex.getMessage().contains("cannot inherit from enum"),
                "Expected class extending enum error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Class extending user enum with constant body fails compilation")
    public void testClassExtendingUserEnumWithBodyFails() {
        String code = """
                enum State {
                    RUNNING {
                        public String function info() { return "run"; }
                    },
                    STOPPED;
                }
                public class SubState extends State {
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SubState"));
        assertTrue(ex.getMessage().contains("cannot inherit from enum"),
                "Expected class extending enum error, got: " + ex.getMessage());
    }

    // Item 3: Interface declaring native method fails compilation (JVMS §4.6 & )
    @Test
    @DisplayName("Interface declaring native method fails compilation")
    public void testInterfaceDeclaringNativeMethodFails() {
        String code = """
                public interface NativeIface {
                    native void function nativeAction();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "NativeIface"));
        assertTrue(ex.getMessage().contains("Interface methods cannot be declared 'native'"),
                "Expected interface native method error, got: " + ex.getMessage());
    }

    // Item 4: Top-level interface declared static fails compilation 
    @Test
    @DisplayName("Top-level interface declared static fails compilation")
    public void testTopLevelInterfaceDeclaredStaticFails() {
        String code = """
                public static interface StaticTopIface {
                    void function action();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "StaticTopIface"));
        assertTrue(ex.getMessage().contains("cannot be declared"),
                "Expected top-level static interface error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Member interface declared static succeeds compilation")
    public void testMemberInterfaceDeclaredStaticPasses() {
        String code = """
                public class OuterContainer {
                    public static interface NestedIface {
                        void function action();
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "OuterContainer"));
    }

    // Item 5: Interface field declared sync fails compilation (JVMS §4.5)
    @Test
    @DisplayName("Interface field declared sync fails compilation")
    public void testInterfaceFieldDeclaredSyncFails() {
        String code = """
                public interface SyncFieldIface {
                    sync int TIMEOUT = 10;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SyncFieldIface"));
        assertTrue(ex.getMessage().contains("Interface fields cannot be declared 'sync' (volatile)"),
                "Expected interface field sync error, got: " + ex.getMessage());
    }

    // ==========================================
    // SECTION 23: THROWS VALIDATION, ENUM MEMBER RESTRICTIONS & INSTANCEOF REIFICATION WITH OCEANLIST SPECIALIZATION
    // ==========================================

    @Test
    @DisplayName("Method throws clause with primitive type fails compilation")
    public void testMethodThrowsPrimitiveFails() {
        String code = """
                public class ThrowsPrim {
                    public void function test() throws int {
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ThrowsPrim"));
        assertTrue(ex.getMessage().contains("Exception type cannot be a primitive type"),
                "Expected throws primitive error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Method throws clause with non-Throwable class fails compilation")
    public void testMethodThrowsNonThrowableFails() {
        String code = """
                public class ThrowsNonThrowable {
                    public void function test() throws String {
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ThrowsNonThrowable"));
        assertTrue(ex.getMessage().contains("must extend java.lang.Throwable"),
                "Expected non-throwable error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Method throws clause with duplicate exception types fails compilation")
    public void testMethodThrowsDuplicateFails() {
        String code = """
                import java.io.IOException;
                public class ThrowsDuplicate {
                    public void function test() throws IOException, IOException {
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ThrowsDuplicate"));
        assertTrue(ex.getMessage().contains("Duplicate exception type"),
                "Expected duplicate exception error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Method throws clause with valid multiple exceptions compiles successfully")
    public void testMethodThrowsValidThrowablePasses() {
        String code = """
                import java.io.IOException;
                public class ThrowsValid {
                    public void function test() throws IOException, RuntimeException {
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "ThrowsValid"));
    }

    @Test
    @DisplayName("Enum declaring clone method fails compilation")
    public void testEnumDeclaringCloneFails() {
        String code = """
                public enum MyEnum {
                    A, B;
                    public Object function clone() {
                        return null;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "MyEnum"));
        assertTrue(ex.getMessage().contains("Enums cannot declare a 'clone' method"),
                "Expected enum clone error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Enum overriding final method of java.lang.Enum fails compilation")
    public void testEnumOverridingFinalNameFails() {
        String code = """
                public enum BadEnum {
                    X, Y;
                    public String function name() {
                        return "custom";
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "BadEnum"));
        assertTrue(ex.getMessage().contains("cannot override final method from java.lang.Enum"),
                "Expected enum final method error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Enum field with same name as enum constant fails compilation")
    public void testEnumFieldCollidingWithConstantFails() {
        String code = """
                public enum ColorEnum {
                    RED, GREEN;
                    int RED = 10;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ColorEnum"));
        assertTrue(ex.getMessage().contains("conflicts with enum constant of the same name"),
                "Expected enum constant collision error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("instanceof with non-reifiable generic type arguments fails compilation")
    public void testInstanceOfNonReifiableGenericFails() {
        String code = """
                import java.util.List;
                public class GenericCheck {
                    public boolean function check(Object o) {
                        return o instanceof List<String>;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "GenericCheck"));
        assertTrue(ex.getMessage().contains("Cannot use non-reifiable type"),
                "Expected non-reifiable generic error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("instanceof with OceanList<int> is automatically converted to OceanIntList and passes")
    public void testInstanceOfOceanListPrimitiveOptimizedPasses() {
        String code = """
                import ocean.stdlib.OceanList;
                public class PrimListCheck {
                    public static boolean function check(Object o) {
                        return o instanceof OceanList<int>;
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "PrimListCheck"));
    }

    @Test
    @DisplayName("instanceof with raw type passes compilation")
    public void testInstanceOfRawTypePasses() {
        String code = """
                import java.util.List;
                public class RawCheck {
                    public static boolean function check(Object o) {
                        return o instanceof List;
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "RawCheck"));
    }

    // ==========================================
    // SECTION 24: Generic Bounds, Enum Constructor super(), Interface Instance Initializer
    // ==========================================

    @Test
    @DisplayName("Generic type parameter with primitive bound fails compilation")
    public void testTypeParameterPrimitiveBoundFails() {
        String code = """
                public class Box<T extends int> {
                    T val;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Box"));
        assertTrue(ex.getMessage().contains("Type parameter bound cannot be a primitive type"),
                "Expected primitive bound error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Generic type parameter with circular self-bound fails compilation")
    public void testTypeParameterSelfBoundFails() {
        String code = """
                public class Box<T extends T> {
                    T val;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Box"));
        assertTrue(ex.getMessage().contains("cannot have itself as a bound"),
                "Expected circular self-bound error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Generic type parameter with duplicate bounds fails compilation")
    public void testTypeParameterDuplicateBoundsFails() {
        String code = """
                public class Box<T extends Comparable & Comparable> {
                    T val;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Box"));
        assertTrue(ex.getMessage().contains("Duplicate bound"),
                "Expected duplicate bounds error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Generic type parameter with multiple class bounds fails compilation")
    public void testTypeParameterMultipleClassBoundsFails() {
        String code = """
                public class Box<T extends Number & String> {
                    T val;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Box"));
        assertTrue(ex.getMessage().contains("Additional upper bound ('&') cannot be a class, only interface types are permitted"),
                "Expected multiple class bounds error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Generic type parameter with final class and additional interfaces fails compilation")
    public void testTypeParameterFinalClassWithInterfacesFails() {
        String code = """
                public class Box<T extends String & Runnable> {
                    T val;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Box"));
        assertTrue(ex.getMessage().contains("Cannot inherit from final class 'String' or combine it with additional interface bounds"),
                "Expected final class bound error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Generic type parameter with valid class and interface bounds passes compilation")
    public void testTypeParameterValidClassAndInterfacePasses() {
        String code = """
                public class Box<T extends Number & Comparable> {
                    T val;
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "Box"));
    }

    @Test
    @DisplayName("Enum constructor explicit super() call fails compilation")
    public void testEnumConstructorExplicitSuperFails() {
        String code = """
                public enum Color {
                    RED(1), GREEN(2);
                    int code;
                    Color(int c) {
                        super();
                        this.code = c;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Color"));
        assertTrue(ex.getMessage().contains("enum constructor") && ex.getMessage().contains("super()"),
                "Expected enum super() prohibition error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Enum constructor this() chaining passes compilation")
    public void testEnumConstructorThisChainingPasses() {
        String code = """
                public enum Status {
                    ACTIVE, INACTIVE;
                    int code;
                    Status() {
                        this(1);
                    }
                    Status(int c) {
                        this.code = c;
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "Status"));
    }

    @Test
    @DisplayName("Interface with instance initializer block fails compilation")
    public void testInterfaceInstanceInitializerBlockFails() {
        String code = """
                public interface MyInterface {
                    {
                        int x = 1;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "MyInterface"));
        assertTrue(ex.getMessage().contains("Initializer blocks ({ ... }) cannot be defined in interfaces") || ex.getMessage().contains("Initializer blocks ({ ... }) cannot be defined in interfaces"),
                "Expected interface instance initializer error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Interface with static initializer block passes compilation")
    public void testInterfaceStaticInitializerBlockPasses() {
        String code = """
                public interface MyInterface {
                    static {
                        int x = 1;
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "MyInterface"));
    }

    // ==========================================
    // 25. PHASE 22: ANNOTATION VALIDATIONS, SEALED RESTRICTS COMPLETENESS & WILDCARD INSTANTIATION
    // ==========================================

    @Test
    @DisplayName("Annotation method with void return type fails compilation")
    public void testAnnotationVoidReturnTypeFails() {
        String code = """
                public annotation BadAnno {
                    void run();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "BadAnno"));
        assertTrue(ex.getMessage().contains("Invalid annotation member return type") || ex.getMessage().contains("Unknown type 'run'"),
                "Expected invalid annotation return type error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Annotation method with Object return type fails compilation")
    public void testAnnotationObjectReturnTypeFails() {
        String code = """
                public annotation BadAnno {
                    Object data();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "BadAnno"));
        assertTrue(ex.getMessage().contains("Invalid annotation member return type"),
                "Expected invalid annotation return type error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Annotation method with multi-dimensional array return type fails compilation")
    public void testAnnotationMultiDimArrayReturnTypeFails() {
        String code = """
                public annotation BadAnno {
                    int[][] matrix();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "BadAnno"));
        assertTrue(ex.getMessage().contains("Invalid annotation member return type"),
                "Expected invalid annotation return type error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Annotation method with valid return types passes compilation")
    public void testAnnotationValidReturnTypesPass() {
        String code = """
                public annotation ValidAnno {
                    int id();
                    String name();
                    bool active() default true;
                    String[] tags() default {};
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "ValidAnno"));
    }

    @Test
    @DisplayName("Annotation declaration with duplicate member name fails compilation")
    public void testAnnotationDuplicateMemberNameFails() {
        String code = """
                public annotation DuplicateAnno {
                    int id();
                    String id();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "DuplicateAnno"));
        assertTrue(ex.getMessage().contains("Duplicate member name in annotation"),
                "Expected duplicate annotation member error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Annotation member default value of null fails compilation")
    public void testAnnotationDefaultNullFails() {
        String code = """
                public annotation NullDefAnno {
                    String name() default null;
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "NullDefAnno"));
        assertTrue(ex.getMessage().contains("Annotation default value cannot be 'null'"),
                "Expected annotation default null error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Annotation usage with null element value fails compilation")
    public void testAnnotationUsageNullArgFails() {
        String code = """
                public annotation TestAnno {
                    String value();
                }
                @TestAnno(null)
                public class App {}
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "App"));
        assertTrue(ex.getMessage().contains("cannot have 'null' value"),
                "Expected annotation null value error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Sealed class with restricts mentioning class that does not extend it fails compilation")
    public void testSealedClassRestrictsNonSubclassFails() {
        String code = """
                public sealed class Shape restricts Circle, Square {}
                public final class Circle extends Shape {}
                public class Square {}
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Shape"));
        assertTrue(ex.getMessage().contains("does not directly extend sealed class"),
                "Expected sealed class restricts completeness error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Sealed interface with restricts mentioning class that does not implement it fails compilation")
    public void testSealedInterfaceRestrictsNonImplementerFails() {
        String code = """
                public sealed interface Payment restricts CreditCard, Cash {}
                public final class CreditCard implements Payment {}
                public class Cash {}
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Payment"));
        assertTrue(ex.getMessage().contains("does not directly extend or implement sealed interface"),
                "Expected sealed interface restricts completeness error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Direct instantiation with wildcard type argument fails compilation")
    public void testNewWithWildcardFails() {
        String code = """
                import java.util.ArrayList;
                public class App {
                    main() {
                        variable list = new ArrayList<?>();
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "App"));
        assertTrue(ex.getMessage().contains("Wildcard type argument"),
                "Expected wildcard instantiation error, got: " + ex.getMessage());
    }

    // ==========================================
    // 26. JVMS SE 25 COMPLIANCE TESTS
    // ==========================================

    @Test
    @DisplayName("Class implementing annotation type fails compilation")
    public void testClassImplementsAnnotationFails() {
        String code = """
                public annotation MyAnno {
                    String value() default "val";
                }
                public class InvalidImpl implements MyAnno {
                    public String function value() { return "test"; }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "InvalidImpl"));
        assertTrue(ex.getMessage().contains("cannot implement"),
                "Expected annotation implements error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Class implementing java.lang.annotation.Annotation directly fails compilation")
    public void testClassImplementsJavaAnnotationFails() {
        String code = """
                import java.lang.annotation.Annotation;
                public class InvalidJavaAnnoImpl implements Annotation {
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "InvalidJavaAnnoImpl"));
        assertTrue(ex.getMessage().contains("cannot implement"),
                "Expected annotation implements error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Enum implementing annotation type fails compilation")
    public void testEnumImplementsAnnotationFails() {
        String code = """
                public annotation TagAnno {
                    String tag() default "none";
                }
                public enum Status implements TagAnno {
                    ACTIVE, INACTIVE
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Status"));
        assertTrue(ex.getMessage().contains("cannot implement"),
                "Expected enum annotation implements error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Interface extending annotation type fails compilation")
    public void testInterfaceExtendsAnnotationFails() {
        String code = """
                public annotation BaseAnno {
                    int priority() default 0;
                }
                public interface SubInterface extends BaseAnno {
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SubInterface"));
        assertTrue(ex.getMessage().contains("cannot extend annotation 'BaseAnno'"),
                "Expected interface extends annotation error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Interface extending java.lang.annotation.Annotation directly fails compilation")
    public void testInterfaceExtendsJavaAnnotationFails() {
        String code = """
                import java.lang.annotation.Annotation;
                public interface BadInterface extends Annotation {
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "BadInterface"));
        assertTrue(ex.getMessage().contains("cannot extend annotation 'Annotation'"),
                "Expected interface extends annotation error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Class static method shadowing interface instance method fails compilation")
    public void testClassStaticMethodShadowingInterfaceInstanceMethodFails() {
        String code = """
                public interface Service {
                    void function execute();
                }
                public class ServiceImpl implements Service {
                    public static void function execute() {
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ServiceImpl"));
        assertTrue(ex.getMessage().contains("cannot hide instance method"),
                "Expected static method shadowing interface instance method error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Annotation self-dependency directly fails compilation")
    public void testAnnotationDirectSelfDependencyFails() {
        String code = """
                public annotation SelfRecursiveAnno {
                    SelfRecursiveAnno next();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "SelfRecursiveAnno"));
        assertTrue(ex.getMessage().contains("Cyclic dependency"),
                "Expected circular annotation dependency error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Annotation circular dependency indirectly fails compilation")
    public void testAnnotationIndirectCircularDependencyFails() {
        String code = """
                public annotation CycleAnnoA {
                    CycleAnnoB b();
                }
                public annotation CycleAnnoB {
                    CycleAnnoA a();
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "CycleAnnoA"));
        assertTrue(ex.getMessage().contains("Cyclic dependency"),
                "Expected circular annotation dependency error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Class directly extending java.lang.Record fails compilation")
    public void testClassExtendsRecordFails() {
        String code = """
                public class CustomRecord extends java.lang.Record {
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "CustomRecord"));
        assertTrue(ex.getMessage().contains("Class 'CustomRecord' cannot directly"),
                "Expected class extends Record error, got: " + ex.getMessage());
    }

    // ==========================================
    // 46. SEMANTIC TYPE SAFETY: EQUALITY, BITWISE, SHIFT, ARRAY
    // ==========================================

    @Test
    @DisplayName("Equality (==, !=) between incomparable types fails compilation")
    public void testIncomparableEqualityFails() {
        String code1 = """
                public class EqTest1 {
                    public static void function test() {
                        boolean b = (5 == "hello");
                    }
                }
                """;
        CompilationException ex1 = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code1, "EqTest1"));
        assertTrue(ex1.getMessage().contains("Equality operator") || ex1.getMessage().contains("Incomparable types"), "Expected equality error, got: " + ex1.getMessage());

        String code2 = """
                public class EqTest2 {
                    public static void function test() {
                        boolean b = (true == 10);
                    }
                }
                """;
        CompilationException ex2 = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code2, "EqTest2"));
        assertTrue(ex2.getMessage().contains("Equality operator"), "Expected equality error, got: " + ex2.getMessage());

        String code3 = """
                public class EqTest3 {
                    public static void function test() {
                        boolean b = (null == 5);
                    }
                }
                """;
        CompilationException ex3 = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code3, "EqTest3"));
        assertTrue(ex3.getMessage().contains("Equality operator"), "Expected equality error, got: " + ex3.getMessage());

        String code4 = """
                class Cat {}
                class Dog {}
                public class EqTest4 {
                    public static void function test() {
                        Cat c = new Cat();
                        Dog d = new Dog();
                        boolean b = (c == d);
                    }
                }
                """;
        CompilationException ex4 = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code4, "EqTest4"));
        assertTrue(ex4.getMessage().contains("Incomparable types") || ex4.getMessage().contains("Equality operator"), "Expected equality error, got: " + ex4.getMessage());
    }

    @Test
    @DisplayName("Bitwise operators (&, |, ^) with mixed int/bool or float operands fail compilation")
    public void testBitwiseMismatchedOperandsFails() {
        String code1 = """
                public class BitTest1 {
                    public static void function test() {
                        variable x = 5 & true;
                    }
                }
                """;
        CompilationException ex1 = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code1, "BitTest1"));
        assertTrue(ex1.getMessage().contains("Bitwise operator") || ex1.getMessage().contains("Bitsel"), "Expected bitwise error, got: " + ex1.getMessage());

        String code2 = """
                public class BitTest2 {
                    public static void function test() {
                        variable x = false | 10;
                    }
                }
                """;
        CompilationException ex2 = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code2, "BitTest2"));
        assertTrue(ex2.getMessage().contains("Bitwise operator") || ex2.getMessage().contains("Bitsel"), "Expected bitwise error, got: " + ex2.getMessage());

        String code3 = """
                public class BitTest3 {
                    public static void function test() {
                        variable x = 5.5 & 2;
                    }
                }
                """;
        CompilationException ex3 = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code3, "BitTest3"));
        assertTrue(ex3.getMessage().contains("Bitwise operator") || ex3.getMessage().contains("Bitsel"), "Expected bitwise error, got: " + ex3.getMessage());
    }

    @Test
    @DisplayName("Assigning primitive array to Object[] fails compilation")
    public void testPrimitiveArrayToObjectArrayAssignmentFails() {
        String code = """
                public class ArrTest {
                    public static void function test() {
                        Object[] arr = new int[5];
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ArrTest"));
        assertTrue(ex.getMessage().contains("Incompatible types"), "Expected incompatible types error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Shift operators (<<, >>, >>>) with floating-point operands fail compilation")
    public void testShiftFloatingPointOperandsFails() {
        String code1 = """
                public class ShiftTest1 {
                    public static void function test() {
                        variable x = 5.5 << 2;
                    }
                }
                """;
        CompilationException ex1 = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code1, "ShiftTest1"));
        assertTrue(ex1.getMessage().contains("Shift"), "Expected shift error, got: " + ex1.getMessage());

        String code2 = """
                public class ShiftTest2 {
                    public static void function test() {
                        variable x = 5 >> 2.5;
                    }
                }
                """;
        CompilationException ex2 = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code2, "ShiftTest2"));
        assertTrue(ex2.getMessage().contains("Shift"), "Expected shift error, got: " + ex2.getMessage());
    }

    @Test
    @DisplayName("Calling instance method on external class statically fails compilation")
    public void testExplicitClassCallingInstanceMethodFails() {
        String code = """
                public class App {
                    main() {
                        Math.toString();
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "App"));
        assertTrue(ex.getMessage().contains("cannot be invoked"),
                "Expected static call on instance method error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Calling String instance method statically fails compilation")
    public void testExplicitClassCallingStringInstanceMethodFails() {
        String code = """
                public class App {
                    main() {
                        String.charAt(0);
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "App"));
        assertTrue(ex.getMessage().contains("cannot be invoked"),
                "Expected static call on instance method error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Calling instance method from static context without receiver fails compilation")
    public void testStaticContextCallingUnqualifiedInstanceMethodFails() {
        String code = """
                public class App {
                    public void function myInstanceMethod() {}
                    main() {
                        myInstanceMethod();
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "App"));
        assertTrue(ex.getMessage().contains("Non-static method"),
                "Expected static context instance method error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Calling valid static method on class succeeds")
    public void testValidStaticMethodOnClassSucceeds() {
        String code = """
                public class App {
                    main() {
                        variable x = Math.abs(-42);
                    }
                }
                """;
        Map<String, byte[]> bytecode = compileToBytecodeMap(code, "App");
        assertNotNull(bytecode);
        assertFalse(bytecode.isEmpty());
    }

    @Test
    @DisplayName("Calling static method on instance receiver compiles successfully")
    public void testStaticMethodOnInstanceReceiverSucceeds() {
        String code = """
                public class App {
                    public static int function getStaticValue() {
                        return 42;
                    }
                    main() {
                        variable app = new App();
                        variable v = app.getStaticValue();
                    }
                }
                """;
        Map<String, byte[]> bytecode = compileToBytecodeMap(code, "App");
        assertNotNull(bytecode);
        assertFalse(bytecode.isEmpty());
    }

    // ========================================================
    // INSTANCE INITIALIZER BLOCK TESTS (, )
    // ========================================================

    @Test
    @DisplayName("Instance initializer block modifies field on instantiation")
    public void testInstanceInitializerBlockExecutes() throws Exception {
        String code = """
                public class InstanceBlockTest {
                    public int x = 10;
                    {
                        x = 42;
                    }
                    public int function getX() {
                        return x;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("InstanceBlockTest", code);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Object result = clazz.getMethod("getX").invoke(instance);
        assertEquals(42, result);
    }

    @Test
    @DisplayName("Instance block and field initializers execute in textual order")
    public void testInstanceBlockAndFieldOrdering() throws Exception {
        String code = """
                public class OrderTest {
                    public int a = 1;
                    {
                        a = a + 10;
                    }
                    public int b = a * 2;
                    {
                        b = b + 5;
                    }
                    public int function getA() { return a; }
                    public int function getB() { return b; }
                }
                """;
        Class<?> clazz = compileAndLoad("OrderTest", code);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        assertEquals(11, clazz.getMethod("getA").invoke(instance));
        assertEquals(27, clazz.getMethod("getB").invoke(instance));
    }

    @Test
    @DisplayName("Instance block runs once with this(...) delegation")
    public void testInstanceBlockWithDelegatingConstructor() throws Exception {
        String code = """
                public class ChainedCtor {
                    public int count = 0;
                    {
                        count = count + 1;
                    }
                    public function ChainedCtor(int x) {
                        count = count + x;
                    }
                    public function ChainedCtor() {
                        this(10);
                    }
                    public int function getCount() { return count; }
                }
                """;
        Class<?> clazz = compileAndLoad("ChainedCtor", code);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        assertEquals(11, clazz.getMethod("getCount").invoke(instance));
    }

    @Test
    @DisplayName("Return inside instance initializer block fails compilation")
    public void testReturnInsideInstanceBlockFails() {
        String code = """
                public class RetInBlock {
                    {
                        return;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "RetInBlock"));
        assertTrue(ex.getMessage().contains("'return' statement cannot be"),
                "Expected return in instance block error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Instance initializer block in interface fails compilation")
    public void testInterfaceInstanceBlockFails() {
        String code = """
                public interface BadInterface {
                    {
                        variable x = 1;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "BadInterface"));
        assertTrue(ex.getMessage().contains("Initializer blocks ({ ... }) cannot be defined in interfaces"),
                "Expected interface instance block error, got: " + ex.getMessage());
    }
}