package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class EnhancedEnumComprehensiveTest {

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
    public void testBasicEnum() throws Exception {
        String code = """
            enum Direction {
                NORTH, SOUTH, EAST, WEST
            }
            class BasicEnumTest {
                static void function main() {
                    Direction d = Direction.NORTH;
                    OceanOutput(d.name());
                    OceanOutput(d.ordinal());
                    Color[] all = Direction.values();
                    OceanOutput(all.length);
                    Direction e = Direction.valueOf("EAST");
                    OceanOutput(e.name());
                }
            }
            """;
        // Note: replace Color[] with Direction[]
        String fixedCode = code.replace("Color[]", "Direction[]");
        Map<String, byte[]> classes = compileToBytecodeMap(fixedCode, "BasicEnumTest");
        String output = executeMainAndCaptureOutput(classes, "BasicEnumTest");
        assertEquals("NORTH\n0\n4\nEAST", output.replace("\r\n", "\n"));
    }

    @Test
    public void testEnumWithFieldsAndCustomConstructor() throws Exception {
        String code = """
            enum Planet {
                EARTH("Blue Planet", 1.0),
                MARS("Red Planet", 0.38);

                String description;
                double gravity;

                function Planet(String description, double gravity) {
                    this.description = description;
                    this.gravity = gravity;
                }

                String function getDescription() {
                    return this.description;
                }

                double function getGravity() {
                    return this.gravity;
                }
            }
            class EnumWithFieldsTest {
                static void function main() {
                    Planet p = Planet.EARTH;
                    OceanOutput(p.name());
                    OceanOutput(p.getDescription());
                    OceanOutput(p.getGravity());

                    Planet m = Planet.MARS;
                    OceanOutput(m.getDescription());
                    OceanOutput(m.gravity);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "EnumWithFieldsTest");
        String output = executeMainAndCaptureOutput(classes, "EnumWithFieldsTest");
        assertEquals("EARTH\nBlue Planet\n1.0\nRed Planet\n0.38", output.replace("\r\n", "\n"));
    }

    @Test
    public void testEnumWithOverloadedConstructors() throws Exception {
        String code = """
            enum Priority {
                DEFAULT,
                LOW(1),
                HIGH(10, "Critical");

                int level;
                String tag;

                function Priority() {
                    this.level = 0;
                    this.tag = "Normal";
                }

                function Priority(int level) {
                    this.level = level;
                    this.tag = "Standard";
                }

                function Priority(int level, String tag) {
                    this.level = level;
                    this.tag = tag;
                }

                int function getLevel() {
                    return this.level;
                }

                String function getTag() {
                    return this.tag;
                }
            }
            class EnumMultiCtorTest {
                static void function main() {
                    OceanOutput(Priority.DEFAULT.getLevel());
                    OceanOutput(Priority.DEFAULT.getTag());
                    OceanOutput(Priority.LOW.getLevel());
                    OceanOutput(Priority.LOW.getTag());
                    OceanOutput(Priority.HIGH.getLevel());
                    OceanOutput(Priority.HIGH.getTag());
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "EnumMultiCtorTest");
        String output = executeMainAndCaptureOutput(classes, "EnumMultiCtorTest");
        assertEquals("0\nNormal\n1\nStandard\n10\nCritical", output.replace("\r\n", "\n"));
    }

    @Test
    public void testEnumImplementsInterface() throws Exception {
        String code = """
            interface Displayable {
                String function display();
            }
            enum Status implements Displayable {
                PENDING("Waiting for approval"),
                APPROVED("Request granted"),
                REJECTED("Request denied");

                String message;

                function Status(String message) {
                    this.message = message;
                }

                String function display() {
                    return this.name() + ": " + this.message;
                }
            }
            class EnumInterfaceTest {
                static void function main() {
                    Status s1 = Status.PENDING;
                    Status s2 = Status.APPROVED;
                    OceanOutput(s1.display());
                    OceanOutput(s2.display());
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "EnumInterfaceTest");
        String output = executeMainAndCaptureOutput(classes, "EnumInterfaceTest");
        assertEquals("PENDING: Waiting for approval\nAPPROVED: Request granted", output.replace("\r\n", "\n"));
    }

    @Test
    public void testEnumStaticMethodsAndFields() throws Exception {
        String code = """
            enum Role {
                ADMIN, USER, GUEST;

                static String prefix = "ROLE_";

                static String function getRolesInfo() {
                    return prefix + "SYSTEM (count: 3)";
                }
            }
            class EnumStaticMethodTest {
                static void function main() {
                    OceanOutput(Role.getRolesInfo());
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "EnumStaticMethodTest");
        String output = executeMainAndCaptureOutput(classes, "EnumStaticMethodTest");
        assertEquals("ROLE_SYSTEM (count: 3)", output.replace("\r\n", "\n"));
    }

    @Test
    public void testEnumInSwitchStatement() throws Exception {
        String code = """
            enum TrafficLight {
                RED, YELLOW, GREEN
            }
            class EnumSwitchTest {
                static void function main() {
                    TrafficLight light = TrafficLight.GREEN;
                    String action = "UNKNOWN";
                    switch (light) {
                        case RED -> action = "STOP";
                        case YELLOW -> action = "WAIT";
                        case GREEN -> action = "GO";
                    }
                    OceanOutput(action);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "EnumSwitchTest");
        String output = executeMainAndCaptureOutput(classes, "EnumSwitchTest");
        assertEquals("GO", output.replace("\r\n", "\n"));
    }

    @Test
    public void testEnumInSwitchExpressionWithOptionalSemi() throws Exception {
        String code = """
            enum Day {
                MON, TUE, WED, THU, FRI, SAT, SUN
            }
            class EnumSwitchExprTest {
                static void function main() {
                    Day d = Day.SAT;
                    String type = switch (d) {
                        case SAT, SUN -> "WEEKEND";
                        default -> "WEEKDAY";
                    };
                    OceanOutput(type);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "EnumSwitchExprTest");
        String output = executeMainAndCaptureOutput(classes, "EnumSwitchExprTest");
        assertEquals("WEEKEND", output.replace("\r\n", "\n"));
    }

    @Test
    public void testEnumPolymorphicInterfaceCall() throws Exception {
        String code = """
            interface Printable {
                String function printValue();
            }
            enum OpCode implements Printable {
                ADD, SUB, MUL, DIV;

                String function printValue() {
                    return "OP:" + this.name();
                }
            }
            class EnumPolymorphismTest {
                static void function printInfo(Printable p) {
                    OceanOutput(p.printValue());
                }

                static void function main() {
                    printInfo(OpCode.ADD);
                    printInfo(OpCode.MUL);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "EnumPolymorphismTest");
        String output = executeMainAndCaptureOutput(classes, "EnumPolymorphismTest");
        assertEquals("OP:ADD\nOP:MUL", output.replace("\r\n", "\n"));
    }
}