package ocean.compiler;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ClassTypeParamAndRecordValidationTest extends CompilerTestHelper {

    /** Returns true if the current CompilerReporter session has an error whose text contains ALL given fragments. */
    private boolean hasErrorContaining(String... fragments) {
        List<CompilerReporter.Message> msgs = CompilerReporter.getMessages();
        return msgs.stream()
                .filter(m -> m.level() == CompilerReporter.Level.ERROR)
                .anyMatch(m -> {
                    for (String frag : fragments) {
                        if (!m.text().contains(frag)) return false;
                    }
                    return true;
                });
    }

    /** Runs compileToBytecodeMap, swallowing any CompilationException (errors are in CompilerReporter). */
    private Map<String, byte[]> tryCompile(String code, String className) {
        try {
            return compileToBytecodeMap(code, className);
        } catch (CompilationException e) {
            return java.util.Collections.emptyMap();
        }
    }

    @Test
    public void testStaticMethodUsingClassTypeParameterFails() {
        // In Ocean, methods require the 'function' keyword
        String code = """
            class GenMethodTest<T> {
                public static void function test(T x) {
                }
            }
            """;
        CompilerReporter.clear();
        tryCompile(code, "GenMethodTest");
        assertTrue(CompilerReporter.hasErrors(),
                "Static method using class generic type parameter T should produce a compiler error, messages: " +
                        CompilerReporter.getMessages().stream()
                                .map(m -> "[" + m.level() + "] " + m.text())
                                .reduce("", (a,b) -> a + "; " + b));
        assertTrue(hasErrorContaining("Static"),
                "Expected error mentioning static method and class type parameter, got: " +
                        CompilerReporter.getMessages().stream()
                                .filter(m -> m.level() == CompilerReporter.Level.ERROR)
                                .map(CompilerReporter.Message::text)
                                .reduce("", (a,b) -> a + "; " + b));
    }

    @Test
    public void testStaticMethodReturningClassTypeParameterFails() {
        String code = """
            class GenReturnTest<T> {
                public static T function get() {
                    return null;
                }
            }
            """;
        CompilerReporter.clear();
        tryCompile(code, "GenReturnTest");
        assertTrue(CompilerReporter.hasErrors(),
                "Static method returning class generic type parameter T should produce a compiler error, messages: " +
                        CompilerReporter.getMessages().stream()
                                .map(m -> "[" + m.level() + "] " + m.text())
                                .reduce("", (a,b) -> a + "; " + b));
        assertTrue(hasErrorContaining("Static"),
                "Expected error mentioning static and class generic type parameter");
    }

    @Test
    public void testStaticFieldUsingClassTypeParameterFails() {
        String code = """
            class GenFieldTest<T> {
                public static T item;
            }
            """;
        CompilerReporter.clear();
        tryCompile(code, "GenFieldTest");
        assertTrue(CompilerReporter.hasErrors(),
                "Static field of class generic type parameter T should produce a compiler error");
        assertTrue(hasErrorContaining("Static"),
                "Expected error mentioning static field and class generic type parameter");
    }

    @Test
    public void testGenericStaticMethodDeclaringOwnTypeParameterSucceeds() {
        // A static method that declares its OWN <T> shadowing the class <T> is valid
        String code = """
            class GenShadowTest<T> {
                public static <R> void function test(R x) {
                }
            }
            """;
        CompilerReporter.clear();
        Map<String, byte[]> result = tryCompile(code, "GenShadowTest");
        String errMsg = CompilerReporter.hasErrors()
                ? CompilerReporter.getMessages().stream()
                        .filter(m -> m.level() == CompilerReporter.Level.ERROR)
                        .map(CompilerReporter.Message::text)
                        .reduce("", (a, b) -> a + " | " + b)
                : "";
        assertFalse(CompilerReporter.hasErrors(),
                "Static method declaring its own <R> should be valid: " + errMsg);
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    public void testDataClassForbiddenNameCloneFails() {
        String code = """
            data class BadCloneRecord(int clone, String name)
            """;
        CompilerReporter.clear();
        tryCompile(code, "BadCloneRecord");
        assertTrue(CompilerReporter.hasErrors(),
                "Data class component named 'clone' should fail compilation");
        assertTrue(hasErrorContaining("clone"),
                "Expected error mentioning forbidden component 'clone'");
    }

    @Test
    public void testDataClassForbiddenNameToStringFails() {
        String code = """
            data class BadToStringRecord(String toString)
            """;
        CompilerReporter.clear();
        tryCompile(code, "BadToStringRecord");
        assertTrue(CompilerReporter.hasErrors(),
                "Data class component named 'toString' should fail compilation");
        assertTrue(hasErrorContaining("toString"),
                "Expected error mentioning forbidden component 'toString'");
    }

    @Test
    public void testDataClassForbiddenNameHashCodeFails() {
        String code = """
            data class BadHashCodeRecord(int hashCode)
            """;
        CompilerReporter.clear();
        tryCompile(code, "BadHashCodeRecord");
        assertTrue(CompilerReporter.hasErrors(),
                "Data class component named 'hashCode' should fail compilation");
        assertTrue(hasErrorContaining("hashCode"),
                "Expected error mentioning forbidden component 'hashCode'");
    }

    @Test
    public void testDataClassValidNamesSucceeds() throws Exception {
        String code = """
            data class GoodPerson(int id, String name, double score)
            """;
        CompilerReporter.clear();
        Class<?> clazz = compileAndLoad("GoodPerson", code);
        assertNotNull(clazz);
        assertFalse(CompilerReporter.hasErrors(),
                "Valid data class should compile without errors but got: " +
                        CompilerReporter.getMessages().stream()
                                .filter(m -> m.level() == CompilerReporter.Level.ERROR)
                                .map(CompilerReporter.Message::text)
                                .reduce("", (a, b) -> a + "; " + b));
    }

    @Test
    public void testSyntaxErrorCaughtInCompilerTestHelper() {
        String code = """
            class SyntaxErrorTest {
                public void function foo() {
                    static int x = 10;
                }
            }
            """;
        CompilerReporter.clear();
        tryCompile(code, "SyntaxErrorTest");
        assertTrue(CompilerReporter.hasErrors(),
                "Syntax error inside method body should be caught by CompilerTestHelper");
    }
}