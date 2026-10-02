package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ClassAndInterfaceHierarchyConstraintsTest {

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
                for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                    if (entry.getKey().endsWith("/" + name) || entry.getKey().equals(name)) {
                        byte[] bytes = entry.getValue();
                        return defineClass(name, bytes, 0, bytes.length);
                    }
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
    public void testClassExtendsOceanInterfaceThrowsError() {
        String code = """
            interface Displayable {
                void function display();
            }
            class Screen extends Displayable {
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Screen"));
        assertTrue(ex.getMessage().contains("interface") || ex.getMessage().contains("miras alamaz"),
                "Expected error for class extending an interface, got: " + ex.getMessage());
    }

    @Test
    public void testClassExtendsJdkInterfaceThrowsError() {
        String code = """
            class TaskWorker extends java.lang.Runnable {
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "TaskWorker"));
        assertTrue(ex.getMessage().contains("interface") || ex.getMessage().contains("miras alamaz"),
                "Expected error for class extending JDK interface java.lang.Runnable, got: " + ex.getMessage());
    }

    @Test
    public void testClassImplementsOceanClassThrowsError() {
        String code = """
            class BaseService {
                void function serve() {}
            }
            class CustomService implements BaseService {
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "CustomService"));
        assertTrue(ex.getMessage().contains("cannot implement"),
                "Expected error for class implementing a class, got: " + ex.getMessage());
    }

    @Test
    public void testClassImplementsJdkClassThrowsError() {
        String code = """
            class StringWrapper implements java.lang.String {
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "StringWrapper"));
        assertTrue(ex.getMessage().contains("cannot implement"),
                "Expected error for class implementing JDK class java.lang.String, got: " + ex.getMessage());
    }

    @Test
    public void testInterfaceExtendsOceanClassThrowsError() {
        String code = """
            class Animal {
                void function speak() {}
            }
            interface Pet extends Animal {
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "Pet"));
        assertTrue(ex.getMessage().contains("cannot extend"),
                "Expected error for interface extending a class, got: " + ex.getMessage());
    }

    @Test
    public void testInterfaceExtendsJdkClassThrowsError() {
        String code = """
            interface ThreadRunner extends java.lang.Thread {
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "ThreadRunner"));
        assertTrue(ex.getMessage().contains("cannot extend"),
                "Expected error for interface extending JDK class java.lang.Thread, got: " + ex.getMessage());
    }

    @Test
    public void testEnumImplementsOceanClassThrowsError() {
        String code = """
            class ConfigBase {
            }
            enum AppMode implements ConfigBase {
                DEV, PROD;
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "AppMode"));
        assertTrue(ex.getMessage().contains("cannot implement"),
                "Expected error for enum implementing a class, got: " + ex.getMessage());
    }

    @Test
    public void testEnumImplementsJdkClassThrowsError() {
        String code = """
            enum MathConstant implements java.lang.Number {
                PI, E;
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "MathConstant"));
        assertTrue(ex.getMessage().contains("cannot implement"),
                "Expected error for enum implementing JDK class java.lang.Number, got: " + ex.getMessage());
    }

    @Test
    public void testValidHierarchiesCompileAndRunSuccessfully() throws Exception {
        String code = """
            interface Printable {
                void function print();
            }
            interface Loggable extends Printable {
                void function log();
            }
            class BasePrinter implements Printable {
                void function print() {
                    OceanOutput("BasePrinter printing");
                }
            }
            class AdvancedLogger extends BasePrinter implements Loggable {
                void function log() {
                    OceanOutput("AdvancedLogger logging");
                }
            }
            enum Severity implements Printable {
                INFO, WARN, ERROR;
                void function print() {
                    OceanOutput("Severity: " + this.name());
                }
            }
            class ValidHierarchyTest {
                static void function main() {
                    AdvancedLogger logger = new AdvancedLogger();
                    logger.print();
                    logger.log();
                    Severity.INFO.print();
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ValidHierarchyTest");
        String output = executeMainAndCaptureOutput(classes, "ValidHierarchyTest");
        assertEquals("BasePrinter printing\nAdvancedLogger logging\nSeverity: INFO", output.replace("\r\n", "\n"));
    }
}
