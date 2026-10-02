package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class GenericBoundsAndVarianceTest {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
        ClassMetadataCache.clearCaches();
    }

    private Map<String, byte[]> compile(String code, String className) {
        return CompilerTestHelper.compileToBytecodeMap(code, className);
    }

    private boolean hasBytecode(Map<String, byte[]> classes, String className) {
        if (classes.containsKey(className)) return true;
        if (classes.containsKey(className + "/" + className)) return true;
        for (String k : classes.keySet()) {
            if (k.endsWith("/" + className) || k.equals(className)) return true;
        }
        return false;
    }

    @Test
    public void testSingleUpperBoundSuccess() {
        String code = """
                import java.lang.Number;
                import java.lang.Integer;

                public class NumberBox<T <: Number> {
                    private T val;
                    public void function setVal(T v) { this.val = v; }
                    public T function getVal() { return this.val; }
                }

                public class SingleBoundSuccessTest {
                    main() {
                        NumberBox<Integer> box = new NumberBox<Integer>();
                        box.setVal(42);
                        OceanOutput("box ok: " + box.getVal());
                    }
                }
                """;
        Map<String, byte[]> bytecode = compile(code, "SingleBoundSuccessTest");
        assertNotNull(bytecode);
        assertTrue(hasBytecode(bytecode, "SingleBoundSuccessTest"));
    }

    @Test
    public void testSingleUpperBoundViolationThrowsError() {
        String code = """
                import java.lang.Number;

                public class NumberBox<T <: Number> {
                    private T val;
                }

                public class SingleBoundFailTest {
                    main() {
                        NumberBox<String> box = new NumberBox<String>();
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compile(code, "SingleBoundFailTest"));
    }

    @Test
    public void testMultipleIntersectionBoundsSuccess() {
        String code = """
                import java.lang.Number;
                import java.lang.Comparable;
                import java.lang.Integer;

                public class OrderedNumberStorage<T <: Number & Comparable<T>> {
                    private T value;
                    public void function set(T v) { this.value = v; }
                    public T function get() { return this.value; }
                }

                public class MultiBoundSuccessTest {
                    main() {
                        OrderedNumberStorage<Integer> storage = new OrderedNumberStorage<Integer>();
                        storage.set(100);
                        OceanOutput("multi bound ok: " + storage.get());
                    }
                }
                """;
        Map<String, byte[]> bytecode = compile(code, "MultiBoundSuccessTest");
        assertNotNull(bytecode);
        assertTrue(hasBytecode(bytecode, "MultiBoundSuccessTest"));
    }

    @Test
    public void testMultipleIntersectionBoundsViolationThrowsError() {
        String code = """
                import java.lang.Number;
                import java.lang.AutoCloseable;

                public class ClosableNumberStorage<T <: Number & AutoCloseable> {
                    private T value;
                }

                public class MultiBoundFailTest {
                    main() {
                        // Integer is a Number but NOT AutoCloseable!
                        ClosableNumberStorage<Integer> storage = new ClosableNumberStorage<Integer>();
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compile(code, "MultiBoundFailTest"));
    }

    @Test
    public void testUseSiteCovarianceAssignmentSuccess() {
        String code = """
                public class Animal {
                    public String function speak() { return "sound"; }
                }

                public class Dog extends Animal {
                    public String function speak() { return "woof"; }
                }

                public class Box<T> {
                    public T val;
                }

                public class UseSiteCovarianceSuccessTest {
                    main() {
                        Box<Dog> dogBox = new Box<Dog>();
                        Box<+Animal> animalBox = dogBox;
                    }
                }
                """;
        Map<String, byte[]> bytecode = compile(code, "UseSiteCovarianceSuccessTest");
        assertNotNull(bytecode);
        assertTrue(hasBytecode(bytecode, "UseSiteCovarianceSuccessTest"));
    }

    @Test
    public void testUseSiteCovarianceViolationThrowsError() {
        String code = """
                public class Animal {}
                public class Dog extends Animal {}

                public class Box<T> {
                    public T val;
                }

                public class UseSiteCovarianceFailTest {
                    main() {
                        Box<Animal> animalBox = new Box<Animal>();
                        // Animal is not subtype of Dog -> Box<Animal> cannot be assigned to Box<+Dog>
                        Box<+Dog> dogBox = animalBox;
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compile(code, "UseSiteCovarianceFailTest"));
    }

    @Test
    public void testUseSiteContravarianceAssignmentSuccess() {
        String code = """
                public class Animal {}
                public class Dog extends Animal {}

                public class Consumer<T> {
                    public void function accept(T item) {}
                }

                public class UseSiteContravarianceSuccessTest {
                    main() {
                        Consumer<Animal> animalConsumer = new Consumer<Animal>();
                        // Animal is supertype of Dog -> Consumer<Animal> can be assigned to Consumer<-Dog>
                        Consumer<-Dog> dogConsumer = animalConsumer;
                    }
                }
                """;
        Map<String, byte[]> bytecode = compile(code, "UseSiteContravarianceSuccessTest");
        assertNotNull(bytecode);
        assertTrue(hasBytecode(bytecode, "UseSiteContravarianceSuccessTest"));
    }

    @Test
    public void testLowerBoundSuccess() {
        String code = """
                import java.lang.Integer;
                import java.lang.Number;

                public class Producer<T >: Integer> {
                    private T val;
                }

                public class LowerBoundSuccessTest {
                    main() {
                        Producer<Number> p = new Producer<Number>();
                    }
                }
                """;
        Map<String, byte[]> bytecode = compile(code, "LowerBoundSuccessTest");
        assertNotNull(bytecode);
        assertTrue(hasBytecode(bytecode, "LowerBoundSuccessTest"));
    }

    @Test
    public void testLowerBoundViolationThrowsError() {
        String code = """
                import java.lang.Integer;
                import java.lang.Double;

                public class Producer<T >: Integer> {
                    private T val;
                }

                public class LowerBoundFailTest {
                    main() {
                        // Double is NOT a supertype of Integer!
                        Producer<Double> p = new Producer<Double>();
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compile(code, "LowerBoundFailTest"));
    }
}
