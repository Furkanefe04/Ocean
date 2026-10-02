package ocean.compiler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class SealedSwitchExhaustivenessTest {

    @BeforeEach
    public void setUp() {
        CompilerRegistry.clearAll();
        CompilerReporter.clear();
        ClassMetadataCache.clearCaches();
    }

    @AfterEach
    public void tearDown() {
        CompilationSession.clearActiveSession();
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

        return baos.toString().trim();
    }

    private String compileAndExpectError(String code, String className) {
        try {
            CompilerTestHelper.compileToBytecodeMap(code, className);
            fail("Expected compilation error for negative test [" + className + "], but compilation succeeded.");
            return null;
        } catch (CompilationException e) {
            return e.getMessage();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    @Test
    public void testSealedInterfaceExhaustiveWithoutDefault() throws Exception {
        String code = """
            sealed interface Shape restricts Circle, Square
            data class Circle(double radius) implements Shape
            data class Square(double side) implements Shape

            class SealedShapeTest {
                public static String function describe(Shape s) {
                    return switch (s) {
                        case Circle c -> "Circle r=" + c.radius
                        case Square sq -> "Square a=" + sq.side
                    };
                }

                public static void function main(String[] args) {
                    OceanOutput(describe(new Circle(5.0)));
                    OceanOutput(describe(new Square(4.0)));
                }
            }
            """;
        String output = executeMainAndCaptureOutput("SealedShapeTest", code);
        String[] lines = output.replace("\r\n", "\n").split("\n");
        assertEquals("Circle r=5.0", lines[0]);
        assertEquals("Square a=4.0", lines[1]);
    }

    @Test
    public void testNestedSealedHierarchyExhaustiveness() throws Exception {
        String code = """
            sealed interface Expr restricts Const, Binary
            data class Const(int v) implements Expr
            sealed interface Binary extends Expr restricts Add, Mul
            data class Add(Expr l, Expr r) implements Binary
            data class Mul(Expr l, Expr r) implements Binary

            class NestedSealedExprTest {
                public static int function eval(Expr e) {
                    return switch (e) {
                        case Const c -> c.v
                        case Add a -> eval(a.l) + eval(a.r)
                        case Mul m -> eval(m.l) * eval(m.r)
                    };
                }

                public static void function main(String[] args) {
                    Expr tree = new Add(new Const(10), new Mul(new Const(3), new Const(4)));
                    OceanOutput("Result: " + eval(tree));
                }
            }
            """;
        String output = executeMainAndCaptureOutput("NestedSealedExprTest", code);
        assertEquals("Result: 22", output);
    }

    @Test
    public void testSealedClassMissingPermittedSubtypeThrowsCompileError() {
        String code = """
            sealed interface Shape restricts Circle, Square
            data class Circle(double radius) implements Shape
            data class Square(double side) implements Shape

            class IncompleteShapeSwitch {
                public static String function describe(Shape s) {
                    return switch (s) {
                        case Circle c -> "Circle"
                    };
                }

                public static void function main(String[] args) {
                    OceanOutput(describe(new Circle(1.0)));
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "IncompleteShapeSwitch");
        assertNotNull(errorMsg);
        assertTrue(errorMsg.contains("does not cover") || errorMsg.contains("Square"));
    }

    @Test
    public void testEnumExhaustiveWithoutDefault() throws Exception {
        String code = """
            enum Direction {
                NORTH, SOUTH, EAST, WEST
            }

            class EnumExhaustiveTest {
                public static String function getShortName(Direction d) {
                    return switch (d) {
                        case NORTH -> "N"
                        case SOUTH -> "S"
                        case EAST -> "E"
                        case WEST -> "W"
                    };
                }

                public static void function main(String[] args) {
                    OceanOutput(getShortName(Direction.NORTH));
                    OceanOutput(getShortName(Direction.EAST));
                    OceanOutput(getShortName(Direction.SOUTH));
                    OceanOutput(getShortName(Direction.WEST));
                }
            }
            """;
        String output = executeMainAndCaptureOutput("EnumExhaustiveTest", code);
        String[] lines = output.replace("\r\n", "\n").split("\n");
        assertEquals("N", lines[0]);
        assertEquals("E", lines[1]);
        assertEquals("S", lines[2]);
        assertEquals("W", lines[3]);
    }

    @Test
    public void testEnumMissingConstantThrowsCompileError() {
        String code = """
            enum TrafficLight {
                RED, YELLOW, GREEN
            }

            class IncompleteEnumSwitch {
                public static String function action(TrafficLight light) {
                    return switch (light) {
                        case RED -> "STOP"
                        case GREEN -> "GO"
                    };
                }

                public static void function main(String[] args) {
                    OceanOutput(action(TrafficLight.RED));
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "IncompleteEnumSwitch");
        assertNotNull(errorMsg);
        assertTrue(errorMsg.contains("does not cover") || errorMsg.contains("YELLOW"));
    }

    @Test
    public void testGuardedPatternDoesNotSatisfyExhaustiveness() {
        String code = """
            sealed interface Shape restricts Circle, Square
            data class Circle(double radius) implements Shape
            data class Square(double side) implements Shape

            class GuardedSealedSwitch {
                public static String function describe(Shape s) {
                    return switch (s) {
                        case Circle c when c.radius > 0.0 -> "Positive Circle"
                        case Square sq -> "Square"
                    };
                }

                public static void function main(String[] args) {
                    OceanOutput(describe(new Square(2.0)));
                }
            }
            """;
        String errorMsg = compileAndExpectError(code, "GuardedSealedSwitch");
        assertNotNull(errorMsg);
        assertTrue(errorMsg.contains("does not cover") || errorMsg.contains("Circle"));
    }

    @Test
    public void testRecordPatternInSealedHierarchyExhaustiveness() throws Exception {
        String code = """
            sealed interface Node restricts Leaf, Branch
            data class Leaf(int val) implements Node
            data class Branch(Node left, Node right) implements Node

            class RecordSealedExhaustiveTest {
                public static int function countLeaves(Node node) {
                    return switch (node) {
                        case Leaf(int v) -> 1
                        case Branch(Node l, Node r) -> countLeaves(l) + countLeaves(r)
                    };
                }

                public static void function main(String[] args) {
                    Node tree = new Branch(new Leaf(10), new Branch(new Leaf(20), new Leaf(30)));
                    OceanOutput("Leaves: " + countLeaves(tree));
                }
            }
            """;
        String output = executeMainAndCaptureOutput("RecordSealedExhaustiveTest", code);
        assertEquals("Leaves: 3", output);
    }
}
