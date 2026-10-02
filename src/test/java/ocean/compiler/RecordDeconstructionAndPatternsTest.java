package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class RecordDeconstructionAndPatternsTest extends CompilerTestHelper {

    @Test
    @DisplayName("Data class deconstruction in switch using 'variable' and 'value' keywords and 'when' guard")
    public void testRecordDeconstructionWithVariableAndValue() throws Exception {
        String code = """
                public data class Point(int x, int y);

                public class PatternMatcher {
                    public static String function describe(Object obj) {
                        return switch (obj) {
                            case Point(variable px, value py) when px > 10 -> "BigX:" + (px + py);
                            case Point(value px, variable py) -> "Point:" + px + "," + py;
                            default -> "Other";
                        };
                    }
                }
                """;

        java.util.Map<String, Class<?>> classes = compileAndLoadAll("PatternMatcher", code);
        Class<?> pointClazz = classes.get("Point");
        Class<?> matcherClazz = classes.get("PatternMatcher");
        assertNotNull(pointClazz);
        assertNotNull(matcherClazz);

        Object pt1 = pointClazz.getConstructor(int.class, int.class).newInstance(15, 5);
        Object pt2 = pointClazz.getConstructor(int.class, int.class).newInstance(3, 4);

        Method describe = matcherClazz.getMethod("describe", Object.class);

        assertEquals("BigX:20", describe.invoke(null, pt1));
        assertEquals("Point:3,4", describe.invoke(null, pt2));
        assertEquals("Other", describe.invoke(null, "Just a string"));
    }

    @Test
    @DisplayName("Data class generates camelCase getters and deconstruction uses them")
    public void testRecordDeconstructionCamelCaseGetters() throws Exception {
        String code = """
                public data class UserProfile(String firstName, int age);

                public class ProfileInspector {
                    public static String function inspect(Object p) {
                        return switch (p) {
                            case UserProfile(value fn, variable a) when a >= 18 -> "Adult:" + fn;
                            case UserProfile(variable fn, value a) -> "Minor:" + fn;
                            default -> "Unknown";
                        };
                    }
                }
                """;

        java.util.Map<String, Class<?>> classes = compileAndLoadAll("ProfileInspector", code);
        Class<?> userClazz = classes.get("UserProfile");
        Class<?> inspClazz = classes.get("ProfileInspector");

        // Verify camelCase getters exist on UserProfile
        Method getFirstName = userClazz.getMethod("getFirstName");
        Method getAge = userClazz.getMethod("getAge");
        assertNotNull(getFirstName);
        assertNotNull(getAge);

        Object adult = userClazz.getConstructor(String.class, int.class).newInstance("Alice", 25);
        Object minor = userClazz.getConstructor(String.class, int.class).newInstance("Bob", 12);

        assertEquals("Alice", getFirstName.invoke(adult));
        assertEquals(25, getAge.invoke(adult));

        Method inspect = inspClazz.getMethod("inspect", Object.class);
        assertEquals("Adult:Alice", inspect.invoke(null, adult));
        assertEquals("Minor:Bob", inspect.invoke(null, minor));
    }

    @Test
    @DisplayName("Nested Record deconstruction with Point inside Circle")
    public void testNestedRecordDeconstruction() throws Exception {
        String code = """
                public data class Point(int x, int y);
                public data class Circle(Point center, int radius);

                public class Geometry {
                    public static int function sumCircle(Object obj) {
                        return switch (obj) {
                            case Circle(Point(variable cx, value cy), value r) -> cx + cy + r;
                            default -> -1;
                        };
                    }
                }
                """;

        java.util.Map<String, Class<?>> classes = compileAndLoadAll("Geometry", code);
        Class<?> ptClazz = classes.get("Point");
        Class<?> circleClazz = classes.get("Circle");
        Class<?> geomClazz = classes.get("Geometry");

        Object center = ptClazz.getConstructor(int.class, int.class).newInstance(10, 20);
        Object circle = circleClazz.getConstructor(ptClazz, int.class).newInstance(center, 5);

        Method sumCircle = geomClazz.getMethod("sumCircle", Object.class);
        assertEquals(35, sumCircle.invoke(null, circle));
        assertEquals(-1, sumCircle.invoke(null, "Not a circle"));
    }

    @Test
    @DisplayName("Lambda expression using variable and value parameters")
    public void testLambdaParameterVariableValue() throws Exception {
        String code = """
                public interface BinaryOp {
                    int function apply(int a, int b);
                }

                public class MathRunner {
                    public static int function compute(BinaryOp op, int x, int y) {
                        return op.apply(x, y);
                    }

                    public static int function runTest() {
                        BinaryOp op = (variable a, value b) -> a * 2 + b;
                        return compute(op, 5, 10);
                    }
                }
                """;

        Class<?> runnerClazz = compileAndLoad("MathRunner", code);
        Method runTest = runnerClazz.getMethod("runTest");
        assertEquals(20, runTest.invoke(null));
    }

    @Test
    @DisplayName("Instanceof with Record Pattern and value/variable")
    public void testInstanceofWithRecordPattern() throws Exception {
        String code = """
                public data class Point(int x, int y);

                public class CheckPoint {
                    public static int function getXIfPoint(Object o) {
                        if (o instanceof Point p) {
                            return p.getX();
                        }
                        return -1;
                    }
                }
                """;

        java.util.Map<String, Class<?>> classes = compileAndLoadAll("CheckPoint", code);
        Class<?> ptClazz = classes.get("Point");
        Class<?> chkClazz = classes.get("CheckPoint");

        Object pt = ptClazz.getConstructor(int.class, int.class).newInstance(42, 99);
        Method getXIfPoint = chkClazz.getMethod("getXIfPoint", Object.class);

        assertEquals(42, getXIfPoint.invoke(null, pt));
        assertEquals(-1, getXIfPoint.invoke(null, "other"));
    }
}
