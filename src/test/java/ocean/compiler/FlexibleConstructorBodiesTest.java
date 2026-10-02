package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class FlexibleConstructorBodiesTest {

    @BeforeEach
    public void setup() {
        CompilerRegistry.clearAll();
        CompilerReporter.clear();
        ClassMetadataCache.clearCaches();
    }

    private String executeMainAndCaptureOutput(String mainClassName, String oceanSource) throws Exception {
        Class<?> clazz = CompilerTestHelper.compileAndLoad(mainClassName, oceanSource);
        Method mainMethod = clazz.getMethod("main", String[].class);
        mainMethod.setAccessible(true);

        PrintStream originalOut = System.out;
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(outContent));
            mainMethod.invoke(null, (Object) new String[]{});
        } finally {
            System.setOut(originalOut);
        }
        return outContent.toString().trim();
    }

    @Test
    public void testStatementsBeforeSuperWithValidation() throws Exception {
        String code = """
            class BaseAccount {
                int balance;
                function BaseAccount(int initialBalance) {
                    this.balance = initialBalance;
                }
            }

            class PositiveAccount extends BaseAccount {
                function PositiveAccount(int amount) {
                    if (amount < 0) {
                        throw new IllegalArgumentException("Balance cannot be negative!");
                    }
                    int bonus = 50;
                    super(amount + bonus);
                    OceanOutput("Account created with balance: " + this.balance);
                }
            }

            public class FlexibleCtorValidationTest {
                static main() {
                    PositiveAccount acc = new PositiveAccount(100);
                }
            }
            """;

        String output = executeMainAndCaptureOutput("FlexibleCtorValidationTest", code);
        assertTrue(output.contains("Account created with balance: 150"));
    }

    @Test
    public void testStatementsBeforeThisDelegation() throws Exception {
        String code = """
            class UserProfile {
                String username;
                int age;

                function UserProfile(String username, int age) {
                    this.username = username;
                    this.age = age;
                }

                function UserProfile(String rawInput) {
                    String clean = rawInput.trim();
                    int defaultAge = 21;
                    this(clean, defaultAge);
                }

                public String function getDetails() {
                    return this.username + " (" + this.age + ")";
                }
            }

            public class FlexibleThisDelegationTest {
                static main() {
                    UserProfile profile = new UserProfile("  ocean_dev   ");
                    OceanOutput("User: " + profile.getDetails());
                }
            }
            """;

        String output = executeMainAndCaptureOutput("FlexibleThisDelegationTest", code);
        assertTrue(output.contains("User: ocean_dev (21)"));
    }

    @Test
    public void testComplexPrologueWithLoopsAndStaticCalls() throws Exception {
        String code = """
            class BaseProcessor {
                int totalItems;
                function BaseProcessor(int count) {
                    this.totalItems = count;
                }
            }

            class BatchProcessor extends BaseProcessor {
                int processedSum;

                function BatchProcessor(int[] items) {
                    int sum = 0;
                    for (int i from 0 to items.length with increasing 1) {
                        sum = sum + items[i];
                    }
                    super(items.length);
                    this.processedSum = sum;
                    OceanOutput("BatchProcessor items=" + this.totalItems + ", sum=" + this.processedSum);
                }
            }

            public class FlexibleCtorLoopTest {
                static main() {
                    int[] data = [10, 20, 30, 40];
                    BatchProcessor bp = new BatchProcessor(data);
                }
            }
            """;

        String output = executeMainAndCaptureOutput("FlexibleCtorLoopTest", code);
        assertTrue(output.contains("BatchProcessor items=4, sum=100"));
    }

    @Test
    public void testEarlyThisFieldAccessFailsSemantic() {
        String code = """
            class Parent {
                int x;
                function Parent(int val) {
                    this.x = val;
                }
            }

            class BadChild extends Parent {
                int myField;
                function BadChild(int a) {
                    this.myField = a * 2;
                    super(a);
                }
            }

            public class EarlyThisTest {
                static main() {
                    BadChild bc = new BadChild(10);
                }
            }
            """;

        Exception exception = assertThrows(CompilationException.class, () -> {
            CompilerTestHelper.compileAndLoad("EarlyThisTest", code);
        });
        assertTrue(exception.getMessage().contains("before supertype constructor has been called") || exception.getMessage().contains("super()/this()"));
    }

    @Test
    public void testEarlyInstanceMethodCallFailsSemantic() {
        String code = """
            class Parent {
                int x;
                function Parent(int val) {
                    this.x = val;
                }
            }

            class BadChild extends Parent {
                int function calculateBonus(int a) {
                    return a * 10;
                }

                function BadChild(int a) {
                    int bonus = calculateBonus(a);
                    super(bonus);
                }
            }

            public class EarlyMethodTest {
                static main() {
                    BadChild bc = new BadChild(10);
                }
            }
            """;

        Exception exception = assertThrows(CompilationException.class, () -> {
            CompilerTestHelper.compileAndLoad("EarlyMethodTest", code);
        });
        assertTrue(exception.getMessage().contains("before supertype constructor has been called") || exception.getMessage().contains("super()/this()"));
    }

    @Test
    public void testMultipleSuperCallsFailsSemantic() {
        String code = """
            class Parent {
                int x;
                function Parent(int val) {
                    this.x = val;
                }
            }

            class BadChild extends Parent {
                function BadChild(int a) {
                    super(a);
                    super(a + 1);
                }
            }

            public class MultiSuperTest {
                static main() {
                    BadChild bc = new BadChild(10);
                }
            }
            """;

        Exception exception = assertThrows(CompilationException.class, () -> {
            CompilerTestHelper.compileAndLoad("MultiSuperTest", code);
        });
        assertTrue(exception.getMessage().contains("Constructor cannot contain multiple 'super()'"));
    }
}
