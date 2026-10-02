package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PrimitivePatternMatchingTest {

    @BeforeEach
    public void setup() {
        CompilerRegistry.clearAll();
        CompilerReporter.clear();
        ClassMetadataCache.clearCaches();
    }

    private String executeMainAndCaptureOutput(String className, String code) throws Exception {
        Class<?> clazz = CompilerTestHelper.compileAndLoad(className, code);
        Method mainMethod = clazz.getMethod("main", String[].class);
        mainMethod.setAccessible(true);

        PrintStream oldOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(baos));
            mainMethod.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(oldOut);
        }
        return baos.toString().trim().replace("\r\n", "\n");
    }

    @Test
    public void testPrimitiveInstanceOfWithBoxedObjects() throws Exception {
        String code = """
            class PrimInstTest {
                static main() {
                    Object o1 = 42;
                    Object o2 = 100;
                    Object o3 = 3.14;
                    Object o4 = "hello";
                    Object? o5 = null;

                    if (o1 instanceof int i) {
                        OceanOutput("o1 is int: " + i);
                    }
                    if (o2 instanceof byte b) {
                        OceanOutput("o2 is byte: " + b);
                    }
                    if (o3 instanceof double d) {
                        OceanOutput("o3 is double: " + d);
                    }
                    if (o4 instanceof int) {
                        OceanOutput("o4 is int (BUG)");
                    } else {
                        OceanOutput("o4 is NOT int");
                    }
                    if (o5 instanceof int) {
                        OceanOutput("o5 is int (BUG)");
                    } else {
                        OceanOutput("o5 is null -> NOT int");
                    }
                }
            }
            """;

        String output = executeMainAndCaptureOutput("PrimInstTest", code);
        assertTrue(output.contains("o1 is int: 42"));
        assertTrue(output.contains("o2 is byte: 100"));
        assertTrue(output.contains("o3 is double: 3.14"));
        assertTrue(output.contains("o4 is NOT int"));
        assertTrue(output.contains("o5 is null -> NOT int"));
    }

    @Test
    public void testPrimitiveInstanceOfNarrowingAndWidening() throws Exception {
        String code = """
            class PrimRangeTest {
                static main() {
                    long l1 = 50L;
                    long l2 = 50000000000L;
                    int x1 = 120;
                    int x2 = 500;
                    double d1 = 42.0;
                    double d2 = 42.5;

                    if (l1 instanceof int i1) {
                        OceanOutput("l1 fits in int: " + i1);
                    }
                    if (l2 instanceof int i2) {
                        OceanOutput("l2 fits in int (BUG): " + i2);
                    } else {
                        OceanOutput("l2 does not fit in int");
                    }

                    if (x1 instanceof byte b1) {
                        OceanOutput("x1 fits in byte: " + b1);
                    }
                    if (x2 instanceof byte b2) {
                        OceanOutput("x2 fits in byte (BUG): " + b2);
                    } else {
                        OceanOutput("x2 does not fit in byte");
                    }

                    if (d1 instanceof int dInt1) {
                        OceanOutput("d1 is exact int: " + dInt1);
                    }
                    if (d2 instanceof int dInt2) {
                        OceanOutput("d2 is exact int (BUG): " + dInt2);
                    } else {
                        OceanOutput("d2 is fractional -> not int");
                    }
                }
            }
            """;

        String output = executeMainAndCaptureOutput("PrimRangeTest", code);
        assertTrue(output.contains("l1 fits in int: 50"));
        assertTrue(output.contains("l2 does not fit in int"));
        assertTrue(output.contains("x1 fits in byte: 120"));
        assertTrue(output.contains("x2 does not fit in byte"));
        assertTrue(output.contains("d1 is exact int: 42"));
        assertTrue(output.contains("d2 is fractional -> not int"));
    }

    @Test
    public void testPrimitiveSwitchStatement() throws Exception {
        String code = """
            class PrimSwitchStmtTest {
                static void function classify(Object val) {
                    switch (val) {
                        case byte b:
                            OceanOutput("Byte: " + b);
                            stop;
                        case int i when i > 1000:
                            OceanOutput("Large Int: " + i);
                            stop;
                        case int i:
                            OceanOutput("Normal Int: " + i);
                            stop;
                        case double d:
                            OceanOutput("Double: " + d);
                            stop;
                        case bool b:
                            OceanOutput("Bool: " + b);
                            stop;
                        default:
                            OceanOutput("Other");
                            stop;
                    }
                }

                static main() {
                    classify(50);
                    classify(500);
                    classify(5000);
                    classify(3.14159);
                    classify(true);
                    classify("text");
                }
            }
            """;

        String output = executeMainAndCaptureOutput("PrimSwitchStmtTest", code);
        assertTrue(output.contains("Byte: 50"));
        assertTrue(output.contains("Normal Int: 500"));
        assertTrue(output.contains("Large Int: 5000"));
        assertTrue(output.contains("Double: 3.14159"));
        assertTrue(output.contains("Bool: true"));
        assertTrue(output.contains("Other"));
    }

    @Test
    public void testPrimitiveSwitchExpression() throws Exception {
        String code = """
            class PrimSwitchExprTest {
                static String function describe(Object val) {
                    return switch (val) {
                        case byte b -> "B:" + b
                        case short s -> "S:" + s
                        case int i -> "I:" + i
                        case long l -> "L:" + l
                        case double d -> "D:" + d
                        default -> "UNKNOWN"
                    };
                }

                static main() {
                    OceanOutput(describe(10));
                    OceanOutput(describe(300));
                    OceanOutput(describe(70000));
                    OceanOutput(describe(99999999999L));
                    OceanOutput(describe(2.718));
                    OceanOutput(describe("other"));
                }
            }
            """;

        String output = executeMainAndCaptureOutput("PrimSwitchExprTest", code);
        assertTrue(output.contains("B:10"));
        assertTrue(output.contains("S:300"));
        assertTrue(output.contains("I:70000"));
        assertTrue(output.contains("L:99999999999"));
        assertTrue(output.contains("D:2.718"));
        assertTrue(output.contains("UNKNOWN"));
    }

    @Test
    public void testRecordDeconstructionWithPrimitivePatterns() throws Exception {
        String code = """
            data class Sensor(int id, double reading)

            class RecordPrimTest {
                static String function checkSensor(Sensor s) {
                    return switch (s) {
                        case Sensor(byte id, double r) when r > 100.0 -> "High alert byte sensor " + id + ": " + r
                        case Sensor(byte id, double r) -> "Normal byte sensor " + id + ": " + r
                        case Sensor(int id, double r) -> "Large ID sensor " + id + ": " + r
                        default -> "Default sensor"
                    };
                }

                static main() {
                    Sensor s1 = new Sensor(5, 150.5);
                    Sensor s2 = new Sensor(5, 50.0);
                    Sensor s3 = new Sensor(5000, 20.0);

                    OceanOutput(checkSensor(s1));
                    OceanOutput(checkSensor(s2));
                    OceanOutput(checkSensor(s3));
                }
            }
            """;

        String output = executeMainAndCaptureOutput("RecordPrimTest", code);
        assertTrue(output.contains("High alert byte sensor 5: 150.5"));
        assertTrue(output.contains("Normal byte sensor 5: 50.0"));
        assertTrue(output.contains("Large ID sensor 5000: 20.0"));
    }

    @Test
    public void testUnnamedPrimitivePatterns() throws Exception {
        String code = """
            class UnnamedPrimTest {
                static String function eval(Object o) {
                    return switch (o) {
                        case int _ -> "AN_INT"
                        case double _ -> "A_DOUBLE"
                        case bool _ -> "A_BOOLEAN"
                        default -> "OTHER"
                    };
                }

                static main() {
                    OceanOutput(eval(42));
                    OceanOutput(eval(3.14));
                    OceanOutput(eval(false));
                    OceanOutput(eval("str"));
                }
            }
            """;

        String output = executeMainAndCaptureOutput("UnnamedPrimTest", code);
        assertTrue(output.contains("AN_INT"));
        assertTrue(output.contains("A_DOUBLE"));
        assertTrue(output.contains("A_BOOLEAN"));
        assertTrue(output.contains("OTHER"));
    }
}
