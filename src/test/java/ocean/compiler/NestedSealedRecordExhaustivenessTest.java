package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class NestedSealedRecordExhaustivenessTest extends CompilerTestHelper {

    @Test
    @DisplayName("Switch expression over Container(Shape) is exhaustive when all sealed subtypes of Shape are covered")
    public void testExhaustiveNestedSealedRecordPattern() throws Exception {
        String code = """
                public sealed interface Shape restricts Circle, Square {
                }

                public data class Circle(double radius) implements Shape;
                public data class Square(double side) implements Shape;

                public data class Container(Shape item);

                public class ShapeEvaluator {
                    public static String function evaluate(Container c) {
                        return switch (c) {
                            case Container(Circle circ) -> "CircleRadius:" + circ.getRadius();
                            case Container(Square sq) -> "SquareSide:" + sq.getSide();
                        };
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("ShapeEvaluator", code);
        Class<?> evaluatorClass = classes.get("ShapeEvaluator");
        assertNotNull(evaluatorClass);

        Class<?> circleClass = classes.get("Circle");
        Class<?> squareClass = classes.get("Square");
        Class<?> containerClass = classes.get("Container");

        Object circle = circleClass.getConstructor(double.class).newInstance(5.5);
        Object square = squareClass.getConstructor(double.class).newInstance(10.0);

        Object containerWithCircle = containerClass.getConstructor(classes.get("Shape")).newInstance(circle);
        Object containerWithSquare = containerClass.getConstructor(classes.get("Shape")).newInstance(square);

        Method evalMethod = evaluatorClass.getMethod("evaluate", containerClass);

        assertEquals("CircleRadius:5.5", evalMethod.invoke(null, containerWithCircle));
        assertEquals("SquareSide:10.0", evalMethod.invoke(null, containerWithSquare));
    }

    @Test
    @DisplayName("Switch expression over Container(Shape) fails compilation when a sealed subtype is missing")
    public void testIncompleteNestedSealedRecordPatternFails() {
        String code = """
                public sealed interface Shape restricts Circle, Square {
                }

                public data class Circle(double radius) implements Shape;
                public data class Square(double side) implements Shape;

                public data class Container(Shape item);

                public class IncompleteEvaluator {
                    public static String function evaluate(Container c) {
                        return switch (c) {
                            case Container(Circle circ) -> "Circle";
                        };
                    }
                }
                """;

        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileAndLoadAll("IncompleteEvaluator", code);
        });

        assertTrue(ex.getMessage().contains("Container") || ex.getMessage().contains("Square") || ex.getMessage().contains("kapsam"),
                "Error message should mention missing subtype or exhaustiveness: " + ex.getMessage());
    }
}
