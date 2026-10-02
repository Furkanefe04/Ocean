package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SmartCastComprehensiveTest {

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
    public void testBasicIfSmartCast() throws Exception {
        String code = """
            class BasicSmartCastTest {
                static function main() {
                    Object obj = "Hello World";
                    if (obj instanceof String) {
                        OceanOutput(obj.length());
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "BasicSmartCastTest");
        String output = executeMainAndCaptureOutput(classes, "BasicSmartCastTest");
        assertEquals("11", output);
    }

    @Test
    public void testGuardClauseEarlyExitSmartCast() throws Exception {
        String code = """
            class GuardClauseSmartCastTest {
                static void function check(Object obj) {
                    if (!(obj instanceof String)) {
                        return;
                    }
                    OceanOutput(obj.length());
                }
                static function main() {
                    check("Guard Clause");
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "GuardClauseSmartCastTest");
        String output = executeMainAndCaptureOutput(classes, "GuardClauseSmartCastTest");
        assertEquals("12", output);
    }

    @Test
    public void testLogicalAndSmartCast() throws Exception {
        String code = """
            class LogicalAndSmartCastTest {
                static function main() {
                    Object obj = "Ocean";
                    if (obj instanceof String && obj.length() > 3) {
                        OceanOutput("Long: " + obj.toUpperCase());
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "LogicalAndSmartCastTest");
        String output = executeMainAndCaptureOutput(classes, "LogicalAndSmartCastTest");
        assertEquals("Long: OCEAN", output);
    }

    @Test
    public void testTernarySmartCast() throws Exception {
        String code = """
            class TernarySmartCastTest {
                static function main() {
                    Object obj = "Ternary";
                    int len = obj instanceof String ? obj.length() : 0;
                    OceanOutput(len);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "TernarySmartCastTest");
        String output = executeMainAndCaptureOutput(classes, "TernarySmartCastTest");
        assertEquals("7", output);
    }

    @Test
    public void testWhileLoopSmartCast() throws Exception {
        String code = """
            class WhileSmartCastTest {
                static function main() {
                    Object obj = "WhileLoop";
                    while (obj instanceof String && obj.length() > 0) {
                        OceanOutput(obj.length());
                        stop;
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "WhileSmartCastTest");
        String output = executeMainAndCaptureOutput(classes, "WhileSmartCastTest");
        assertEquals("9", output);
    }

    @Test
    public void testSmartCastWithCustomClass() throws Exception {
        String code = """
            class Animal { }
            class Dog extends Animal {
                String function bark() {
                    return "Woof";
                }
            }
            class CustomClassSmartCastTest {
                static function main() {
                    Animal a = new Dog();
                    if (a instanceof Dog) {
                        OceanOutput(a.bark());
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "CustomClassSmartCastTest");
        String output = executeMainAndCaptureOutput(classes, "CustomClassSmartCastTest");
        assertEquals("Woof", output);
    }

    @Test
    public void testSmartCastInElseBranchForNegativeCondition() throws Exception {
        String code = """
            class ElseBranchSmartCastTest {
                static function main() {
                    Object obj = "InElse";
                    if (!(obj instanceof String)) {
                        OceanOutput("Not String");
                    } else {
                        OceanOutput(obj.length());
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ElseBranchSmartCastTest");
        String output = executeMainAndCaptureOutput(classes, "ElseBranchSmartCastTest");
        assertEquals("6", output);
    }

    @Test
    public void testSmartCastWithNullCheck() throws Exception {
        String code = """
            class NullCheckSmartCastTest {
                static function main() {
                    String? s = "NullableString";
                    if (s != null) {
                        OceanOutput(s.length());
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NullCheckSmartCastTest");
        String output = executeMainAndCaptureOutput(classes, "NullCheckSmartCastTest");
        assertEquals("14", output);
    }

    @Test
    public void testSmartCastNestedIf() throws Exception {
        String code = """
            class Vehicle { }
            class Car extends Vehicle {
                String function drive() { return "Driving Car"; }
            }
            class ElectricCar extends Car {
                String function charge() { return "Charging Battery"; }
            }
            class NestedIfSmartCastTest {
                static function main() {
                    Vehicle v = new ElectricCar();
                    if (v instanceof Car) {
                        OceanOutput(v.drive());
                        if (v instanceof ElectricCar) {
                            OceanOutput(v.charge());
                        }
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "NestedIfSmartCastTest");
        String output = executeMainAndCaptureOutput(classes, "NestedIfSmartCastTest");
        assertEquals("Driving Car\nCharging Battery", output.replace("\r\n", "\n"));
    }

    @Test
    public void testReassignmentInSmartCastBlock() throws Exception {
        String code = """
            class ReassignmentSmartCastTest {
                static function main() {
                    Object obj = "Initial";
                    if (obj instanceof String) {
                        OceanOutput(obj.length());
                        obj = "ReassignedLongerString";
                        OceanOutput(obj.length());
                    }
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ReassignmentSmartCastTest");
        String output = executeMainAndCaptureOutput(classes, "ReassignmentSmartCastTest");
        assertEquals("7\n22", output.replace("\r\n", "\n"));
    }
}