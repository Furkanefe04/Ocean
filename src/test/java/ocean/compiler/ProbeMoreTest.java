package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class ProbeMoreTest extends CompilerTestHelper {

    @BeforeEach
    void setup() {
        CompilerRegistry.clearAll();
        CompilerReporter.clear();
        ClassMetadataCache.clearCaches();
    }

    private void tryCompile(String name, String code) {
        try {
            compileAndLoad(name, code);
            System.out.println("[PROBE PASS - COMPILED] " + name);
        } catch (Throwable t) {
            System.out.println("[PROBE FAIL - ERROR] " + name + " -> " + t.getMessage());
        }
    }

    @Test
    void runProbes() {
        // 1. Duplicate label in enclosing scope 
        tryCompile("DuplicateEnclosingLabel", """
            public class DuplicateEnclosingLabel {
                public static void function test() {
                    loop1: for (int i = 0; i < 10; i++) {
                        loop1: for (int j = 0; j < 10; j++) {
                            stop loop1;
                        }
                    }
                }
            }
        """);

        // 2. Break to undefined label 
        tryCompile("BreakUndefinedLabel", """
            public class BreakUndefinedLabel {
                public static void function test() {
                    for (int i = 0; i < 10; i++) {
                        stop nonExistentLabel;
                    }
                }
            }
        """);

        // 3. Continue to undefined label 
        tryCompile("ContinueUndefinedLabel", """
            public class ContinueUndefinedLabel {
                public static void function test() {
                    for (int i = 0; i < 10; i++) {
                        skip nonExistentLabel;
                    }
                }
            }
        """);

        // 4. Yield outside switch expression 
        tryCompile("YieldOutsideSwitch", """
            public class YieldOutsideSwitch {
                public static void function test() {
                    yield 10;
                }
            }
        """);

        // 5. Array instanceof Cloneable / Serializable 
        tryCompile("ArrayInstanceofInterfaces", """
            public class ArrayInstanceofInterfaces {
                public static bool function test(int[] arr) {
                    return arr instanceof java.lang.Cloneable && arr instanceof java.io.Serializable;
                }
            }
        """);

        // 6. Bare expression statement 
        tryCompile("BareLiteralStatement", """
            public class BareLiteralStatement {
                public static void function test() {
                    "hello world";
                }
            }
        """);
    }
}
