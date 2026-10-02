package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ValueFieldProtectionTest extends CompilerTestHelper {

    @Test
    @DisplayName("Assigning to a final field inside a method fails compilation")
    public void testFinalFieldReassignmentInMethodFails() {
        String code = """
                public class FinalFieldMethodTest {
                    final int x = 10;

                    public void function update() {
                        this.x = 20;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "FinalFieldMethodTest"));
        assertTrue(ex.getMessage().contains("Cannot assign a value to final"),
                "Expected final field reassignment error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Assigning to a value field inside a method fails compilation")
    public void testValueFieldReassignmentInMethodFails() {
        String code = """
                public class ValueFieldMethodTest {
                    value x = 10;

                    public void function update() {
                        this.x = 20;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ValueFieldMethodTest"));
        assertTrue(ex.getMessage().contains("Cannot assign a value to final"),
                "Expected final field reassignment error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Assigning to a final field on an object reference fails compilation")
    public void testFinalFieldReassignmentOnObjectFails() {
        String code = """
                public class TargetObj {
                    public final int id = 42;
                }

                public class AccessorTest {
                    public static void function modify(TargetObj obj) {
                        obj.id = 99;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "AccessorTest"));
        assertTrue(ex.getMessage().contains("Cannot assign a value to final"),
                "Expected final field reassignment error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Blank final field assigned once in constructor succeeds")
    public void testBlankFinalAssignedInConstructorSucceeds() {
        String code = """
                public class BlankFinalSuccess {
                    final int x;

                    public function BlankFinalSuccess(int val) {
                        this.x = val;
                    }
                }
                """;
        assertDoesNotThrow(() -> compileToBytecodeMap(code, "BlankFinalSuccess"));
    }

    @Test
    @DisplayName("Blank final field unassigned in constructor fails compilation")
    public void testBlankFinalUnassignedInConstructorFails() {
        String code = """
                public class BlankFinalMissing {
                    final int x;

                    public function BlankFinalMissing() {
                        // x is not assigned!
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "BlankFinalMissing"));
        assertTrue(ex.getMessage().contains("'value' field declarations"),
                "Expected uninitialized blank final field error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Blank final field assigned twice in constructor fails compilation")
    public void testBlankFinalReassignedInConstructorFails() {
        String code = """
                public class BlankFinalReassign {
                    final int x;

                    public function BlankFinalReassign() {
                        this.x = 10;
                        this.x = 20;
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "BlankFinalReassign"));
        assertTrue(ex.getMessage().contains("already initialized") || ex.getMessage().contains("yeniden cannot be assigned"),
                "Expected double assignment to final field error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("Data class with final typed field has getter but no setter, while normal field has both")
    public void testDataClassFinalFieldHasNoSetter() throws Exception {
        String code = """
                public data class Person(final String ssn, String name)
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("Person", code);
        Class<?> clazz = classes.get("Person");
        assertNotNull(clazz);

        // getSsn should exist
        assertNotNull(clazz.getMethod("getSsn"));

        // getName and setName should exist
        assertNotNull(clazz.getMethod("getName"));
        assertNotNull(clazz.getMethod("setName", String.class));

        // setSsn should NOT exist
        assertThrows(NoSuchMethodException.class, () -> clazz.getMethod("setSsn", String.class));
    }

    @Test
    @DisplayName("Data class constructor using value or variable keyword fails compilation")
    public void testDataClassValueOrVariableParameterFailsCompilation() {
        String codeVal = """
                public data class InvalidPerson(value ssn, String name)
                """;
        CompilationException exVal = assertThrows(CompilationException.class, () -> compileToBytecodeMap(codeVal, "InvalidPerson"));
        assertTrue(exVal.getMessage().contains("cannot be used") || exVal.getMessage().contains("explicit type"),
                "Expected error rejecting value in data class, got: " + exVal.getMessage());

        String codeVar = """
                public data class InvalidEmployee(variable ssn, String name)
                """;
        CompilationException exVar = assertThrows(CompilationException.class, () -> compileToBytecodeMap(codeVar, "InvalidEmployee"));
        assertTrue(exVar.getMessage().contains("cannot be used") || exVar.getMessage().contains("explicit type"),
                "Expected error rejecting variable in data class, got: " + exVar.getMessage());
    }
}