package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class NullSafetyAndCoalescingComprehensiveTest {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    private Map<String, byte[]> compileToBytecodeMap(String code, String className) {
        return CompilerTestHelper.compileToBytecodeMap(code, className);
    }

    private String executeMainAndCaptureOutput(Map<String, byte[]> classes, String className) throws Exception {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                String pathKey = name.replace('.', '/');
                if (classes.containsKey(pathKey)) {
                    byte[] bytes = classes.get(pathKey);
                    return defineClass(name, bytes, 0, bytes.length);
                }
                return super.findClass(name);
            }
        };

        String fqName = classes.containsKey(className + "/" + className) ? className + "." + className : className;
        Class<?> clazz = loader.loadClass(fqName);
        Method mainMethod = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals("main")) {
                mainMethod = m;
                break;
            }
        }
        if (mainMethod == null) {
            throw new RuntimeException("No main method found in " + clazz.getName() + ", declared: " + Arrays.toString(clazz.getDeclaredMethods()));
        }
        mainMethod.setAccessible(true);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(baos));
        try {
            if (mainMethod.getParameterCount() == 1) {
                mainMethod.invoke(null, (Object) new String[0]);
            } else {
                mainMethod.invoke(null);
            }
        } finally {
            System.setOut(originalOut);
        }
        return baos.toString().trim();
    }

    @Test
    public void testSafeCallStringLengthWithBothOperators() throws Exception {
        String code = """
            class SafeCallStringLengthTest {
                static void function main() {
                    String? s1 = "Ocean";
                    String? s2 = null;
                    int len1 = s1?.length() ?? 0;
                    int len2 = s2?.length() ?: -1;
                    OceanOutput(len1);
                    OceanOutput(len2);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "SafeCallStringLengthTest");
        String output = executeMainAndCaptureOutput(classes, "SafeCallStringLengthTest");
        assertEquals("5\n-1", output.replace("\r\n", "\n"));
    }

    @Test
    public void testNullableIntCoalescing() throws Exception {
        String code = """
            class NullableIntTest {
                static void function main() {
                    int? a = 42;
                    int? b = null;
                    int res1 = a ?? 0;
                    int res2 = b ?: 100;
                    OceanOutput(res1);
                    OceanOutput(res2);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NullableIntTest");
        String output = executeMainAndCaptureOutput(classes, "NullableIntTest");
        assertEquals("42\n100", output.replace("\r\n", "\n"));
    }

    @Test
    public void testNullableDoubleCoalescing() throws Exception {
        String code = """
            class NullableDoubleTest {
                static void function main() {
                    double? d1 = 3.14;
                    double? d2 = null;
                    double res1 = d1 ?? 0.0;
                    double res2 = d2 ?: -1.5;
                    OceanOutput(res1);
                    OceanOutput(res2);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NullableDoubleTest");
        String output = executeMainAndCaptureOutput(classes, "NullableDoubleTest");
        assertEquals("3.14\n-1.5", output.replace("\r\n", "\n"));
    }

    @Test
    public void testNullableBoolCoalescing() throws Exception {
        String code = """
            class NullableBoolTest {
                static void function main() {
                    boolean? b1 = true;
                    boolean? b2 = null;
                    boolean res1 = b1 ?? false;
                    boolean res2 = b2 ?: true;
                    OceanOutput(res1);
                    OceanOutput(res2);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NullableBoolTest");
        String output = executeMainAndCaptureOutput(classes, "NullableBoolTest");
        assertEquals("true\ntrue", output.replace("\r\n", "\n"));
    }

    @Test
    public void testSafeCallReturningObject() throws Exception {
        String code = """
            class SafeCallObjectTest {
                static void function main() {
                    String? s1 = "ocean";
                    String? s2 = null;
                    String res1 = s1?.toUpperCase() ?? "EMPTY";
                    String res2 = s2?.toUpperCase() ?: "EMPTY";
                    OceanOutput(res1);
                    OceanOutput(res2);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "SafeCallObjectTest");
        String output = executeMainAndCaptureOutput(classes, "SafeCallObjectTest");
        assertEquals("OCEAN\nEMPTY", output.replace("\r\n", "\n"));
    }

    @Test
    public void testChainedSafeCalls() throws Exception {
        String code = """
            class ChainedSafeCallTest {
                static void function main() {
                    Person p1 = new Person(new Address(new City("Istanbul")));
                    Person p2 = new Person(new Address(null));
                    Person? p3 = null;

                    String c1 = p1?.getAddress()?.getCity()?.getName() ?? "Unknown";
                    String c2 = p2?.getAddress()?.getCity()?.getName() ?: "Unknown";
                    String c3 = p3?.getAddress()?.getCity()?.getName() ?: "Unknown";

                    OceanOutput(c1);
                    OceanOutput(c2);
                    OceanOutput(c3);
                }
            }
            class City {
                String name;
                function City(String name) { this.name = name; }
                String function getName() { return this.name; }
            }
            class Address {
                City? city;
                function Address(City? city) { this.city = city; }
                City? function getCity() { return this.city; }
            }
            class Person {
                Address? address;
                function Person(Address? address) { this.address = address; }
                Address? function getAddress() { return this.address; }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ChainedSafeCallTest");
        String output = executeMainAndCaptureOutput(classes, "ChainedSafeCallTest");
        assertEquals("Istanbul\nUnknown\nUnknown", output.replace("\r\n", "\n"));
    }

    @Test
    public void testSafeFieldAccess() throws Exception {
        String code = """
            class SafeFieldAccessTest {
                static void function main() {
                    Account a1 = new Account("Alice", 100);
                    Account? a2 = null;

                    String u1 = a1?.username ?? "Guest";
                    String u2 = a2?.username ?: "Guest";
                    int b1 = a1?.balance ?? 0;
                    int b2 = a2?.balance ?: 0;

                    OceanOutput(u1);
                    OceanOutput(u2);
                    OceanOutput(b1);
                    OceanOutput(b2);
                }
            }
            class Account {
                String username;
                int balance;
                function Account(String username, int balance) {
                    this.username = username;
                    this.balance = balance;
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "SafeFieldAccessTest");
        String output = executeMainAndCaptureOutput(classes, "SafeFieldAccessTest");
        assertEquals("Alice\nGuest\n100\n0", output.replace("\r\n", "\n"));
    }

    @Test
    public void testSafeCallOnVoidMethod() throws Exception {
        String code = """
            class SafeVoidCallTest {
                static void function main() {
                    Logger log1 = new Logger();
                    Logger? log2 = null;

                    log1?.log("msg1");
                    log2?.log("msg2");
                }
            }
            class Logger {
                function Logger() {}
                void function log(String msg) {
                    OceanOutput(msg);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "SafeVoidCallTest");
        String output = executeMainAndCaptureOutput(classes, "SafeVoidCallTest");
        assertEquals("msg1", output);
    }

    @Test
    public void testPrimitiveArithmeticWithNullCoalesce() throws Exception {
        String code = """
            class PrimitiveArithmeticCoalesceTest {
                static void function main() {
                    int? a = 10;
                    int? b = null;
                    int sum = (a ?? 0) + (b ?: 25);
                    OceanOutput(sum);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "PrimitiveArithmeticCoalesceTest");
        String output = executeMainAndCaptureOutput(classes, "PrimitiveArithmeticCoalesceTest");
        assertEquals("35", output);
    }

    @Test
    public void testTypePromotionWithNullCoalesce() throws Exception {
        String code = """
            class TypePromotionCoalesceTest {
                static void function main() {
                    int? val = null;
                    long res1 = val ?? 100L;
                    float? f = null;
                    double res2 = f ?: 2.5;
                    OceanOutput(res1);
                    OceanOutput(res2);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TypePromotionCoalesceTest");
        String output = executeMainAndCaptureOutput(classes, "TypePromotionCoalesceTest");
        assertEquals("100\n2.5", output.replace("\r\n", "\n"));
    }
}