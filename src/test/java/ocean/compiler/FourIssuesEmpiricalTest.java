package ocean.compiler;

import org.junit.jupiter.api.Test;

public class FourIssuesEmpiricalTest {

    private boolean testCompiles(String className, String code) {
        try {
            CompilerTestHelper.compileToBytecodeMap(code, className);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String getCompileError(String className, String code) {
        try {
            CompilerTestHelper.compileToBytecodeMap(code, className);
            return null;
        } catch (Throwable t) {
            return t.getMessage();
        }
    }

    @Test
    public void testAllFourIssues() {
        System.out.println("=== ISSUE 1: EQUALITY OPERATOR (==, !=) INCOMPARABLE TYPES ===");
        
        // 1a: Primitive int == String
        String code1a = "class Test1A { main() { variable b = 5 == \"hello\"; } }";
        boolean c1a = testCompiles("Test1A", code1a);
        System.out.println("1a: '5 == \"hello\"' compiles? " + c1a + " (Error: " + getCompileError("Test1A", code1a) + ")");

        // 1b: Primitive boolean == String
        String code1b = "class Test1B { main() { variable b = true == \"hello\"; } }";
        boolean c1b = testCompiles("Test1B", code1b);
        System.out.println("1b: 'true == \"hello\"' compiles? " + c1b + " (Error: " + getCompileError("Test1B", code1b) + ")");

        // 1c: String == Integer
        String code1c = "class Test1C { main() { String s = \"a\"; Integer i = 1; variable b = s == i; } }";
        boolean c1c = testCompiles("Test1C", code1c);
        System.out.println("1c: 'String == Integer' compiles? " + c1c + " (Error: " + getCompileError("Test1C", code1c) + ")");

        // 1d: Incompatible Final Classes
        String code1d = "final class Cat {} final class Dog {} class Test1D { main() { Cat c = new Cat(); Dog d = new Dog(); variable b = c == d; } }";
        boolean c1d = testCompiles("Test1D", code1d);
        System.out.println("1d: 'Cat == Dog' compiles? " + c1d + " (Error: " + getCompileError("Test1D", code1d) + ")");

        // 1e: Primitive int == Object
        String code1e = "class Test1E { main() { Object o = \"hello\"; variable b = 5 == o; } }";
        boolean c1e = testCompiles("Test1E", code1e);
        System.out.println("1e: '5 == Object' compiles? " + c1e + " (Error: " + getCompileError("Test1E", code1e) + ")");

        System.out.println("\n=== ISSUE 2: BITWISE OPERATORS (&, |, ^) TYPE MIXING ===");
        
        // 2a: int & boolean
        String code2a = "class Test2A { main() { variable x = 5 & true; } }";
        boolean c2a = testCompiles("Test2A", code2a);
        System.out.println("2a: '5 & true' compiles? " + c2a + " (Error: " + getCompileError("Test2A", code2a) + ")");

        // 2b: boolean | int
        String code2b = "class Test2B { main() { variable x = false | 10; } }";
        boolean c2b = testCompiles("Test2B", code2b);
        System.out.println("2b: 'false | 10' compiles? " + c2b + " (Error: " + getCompileError("Test2B", code2b) + ")");

        // 2c: int ^ boolean
        String code2c = "class Test2C { main() { variable x = 5 ^ false; } }";
        boolean c2c = testCompiles("Test2C", code2c);
        System.out.println("2c: '5 ^ false' compiles? " + c2c + " (Error: " + getCompileError("Test2C", code2c) + ")");

        System.out.println("\n=== ISSUE 3: PRIMITIVE ARRAY ASSIGNED TO OBJECT[] ===");
        
        // 3a: Object[] arr = new int[5]
        String code3a = "class Test3A { main() { Object[] arr = new int[5]; } }";
        boolean c3a = testCompiles("Test3A", code3a);
        System.out.println("3a: 'Object[] arr = new int[5]' compiles? " + c3a + " (Error: " + getCompileError("Test3A", code3a) + ")");
        if (c3a) {
            try {
                CompilerTestHelper.compileAndLoad("Test3A", code3a).getMethod("main").invoke(null);
                System.out.println("3a: Runtime execution succeeded unexpectedly!");
            } catch (Throwable t) {
                System.out.println("3a: Runtime execution failed with: " + (t.getCause() != null ? t.getCause() : t));
            }
        }

        // 3b: int[] ints = new int[5]; Object[] arr = ints;
        String code3b = "class Test3B { main() { int[] ints = new int[5]; Object[] arr = ints; } }";
        boolean c3b = testCompiles("Test3B", code3b);
        System.out.println("3b: 'Object[] arr = ints (int[])' compiles? " + c3b + " (Error: " + getCompileError("Test3B", code3b) + ")");
        if (c3b) {
            try {
                CompilerTestHelper.compileAndLoad("Test3B", code3b).getMethod("main").invoke(null);
                System.out.println("3b: Runtime execution succeeded unexpectedly!");
            } catch (Throwable t) {
                System.out.println("3b: Runtime execution failed with: " + (t.getCause() != null ? t.getCause() : t));
            }
        }

        System.out.println("\n=== ISSUE 4: SHIFT OPERATORS (<<, >>, >>>) TYPE CHECKING ===");
        
        // 4a: double << int
        String code4a = "class Test4A { main() { variable x = 5.5 << 2; } }";
        boolean c4a = testCompiles("Test4A", code4a);
        System.out.println("4a: '5.5 << 2' compiles? " + c4a + " (Error: " + getCompileError("Test4A", code4a) + ")");

        // 4b: int >> double
        String code4b = "class Test4B { main() { variable x = 5 >> 2.5; } }";
        boolean c4b = testCompiles("Test4B", code4b);
        System.out.println("4b: '5 >> 2.5' compiles? " + c4b + " (Error: " + getCompileError("Test4B", code4b) + ")");

        // 4c: int << boolean
        String code4c = "class Test4C { main() { variable x = 5 << true; } }";
        boolean c4c = testCompiles("Test4C", code4c);
        System.out.println("4c: '5 << true' compiles? " + c4c + " (Error: " + getCompileError("Test4C", code4c) + ")");

        // 4d: int << String
        String code4d = "class Test4D { main() { variable x = 5 << \"2\"; } }";
        boolean c4d = testCompiles("Test4D", code4d);
        System.out.println("4d: '5 << \"2\"' compiles? " + c4d + " (Error: " + getCompileError("Test4D", code4d) + ")");
    }
}
