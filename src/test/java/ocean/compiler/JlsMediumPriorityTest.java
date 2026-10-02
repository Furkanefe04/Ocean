package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class JlsMediumPriorityTest extends CompilerTestHelper {

    // -------------------------------------------------------------
    //
    // -------------------------------------------------------------

    @Test
    @DisplayName("Local class declared in static method compiles and executes")
    public void testLocalClassInStaticMethod() throws Exception {
        String code = """
                public class LocalStaticOuter {
                    public static int function runTest() {
                        class LocalCalculator {
                            public int function compute(int val) {
                                return val * 2;
                            }
                        }
                        LocalCalculator calc = new LocalCalculator();
                        return calc.compute(21);
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("LocalStaticOuter", code);
        Class<?> clazz = classes.get("LocalStaticOuter");
        assertNotNull(clazz, "LocalStaticOuter class must be generated");
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(null);
        assertEquals(42, res);
    }

    @Test
    @DisplayName("Local class declared in instance method compiles and executes")
    public void testLocalClassInInstanceMethod() throws Exception {
        String code = """
                public class LocalInstanceOuter {
                    public int function runTest() {
                        class LocalGreeter {
                            public int function calculate(int a, int b) {
                                return a + b;
                            }
                        }
                        LocalGreeter greeter = new LocalGreeter();
                        return greeter.calculate(15, 27);
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("LocalInstanceOuter", code);
        Class<?> clazz = classes.get("LocalInstanceOuter");
        assertNotNull(clazz, "LocalInstanceOuter class must be generated");
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(instance);
        assertEquals(42, res);
    }

    @Test
    @DisplayName("Local class with explicit constructor parameters")
    public void testLocalClassWithExplicitConstructor() throws Exception {
        String code = """
                public class LocalCtorOuter {
                    public static int function runTest() {
                        class LocalMultiplier {
                            public int factor;
                            public function LocalMultiplier(int factor) {
                                this.factor = factor;
                            }
                            public int function multiply(int x) {
                                return x * this.factor;
                            }
                        }
                        LocalMultiplier m = new LocalMultiplier(7);
                        return m.multiply(6);
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("LocalCtorOuter", code);
        Class<?> clazz = classes.get("LocalCtorOuter");
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(null);
        assertEquals(42, res);
    }

    @Test
    @DisplayName("Local class implementing an interface")
    public void testLocalClassImplementsInterface() throws Exception {
        String code = """
                public interface Worker {
                    public int function work();
                }

                public class LocalInterfaceOuter {
                    public static int function runTest() {
                        class FastWorker implements Worker {
                            public int function work() {
                                return 100;
                            }
                        }
                        Worker w = new FastWorker();
                        return w.work();
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("LocalInterfaceOuter", code);
        Class<?> clazz = classes.get("LocalInterfaceOuter");
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(null);
        assertEquals(100, res);
    }

    @Test
    @DisplayName("Local class extending a base class")
    public void testLocalClassExtendsBaseClass() throws Exception {
        String code = """
                public class BaseEntity {
                    public int id;
                    public function BaseEntity(int id) {
                        this.id = id;
                    }
                    public int function getId() {
                        return this.id;
                    }
                }

                public class LocalExtendsOuter {
                    public static int function runTest() {
                        class ExtendedEntity extends BaseEntity {
                            public function ExtendedEntity(int id) {
                                super(id);
                            }
                        }
                        ExtendedEntity entity = new ExtendedEntity(77);
                        return entity.getId();
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("LocalExtendsOuter", code);
        Class<?> clazz = classes.get("LocalExtendsOuter");
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(null);
        assertEquals(77, res);
    }

    @Test
    @DisplayName("Local class with 'final' modifier is permitted")
    public void testLocalClassWithFinalModifierAllowed() throws Exception {
        String code = """
                public class LocalFinalOuter {
                    public static int function runTest() {
                        final class FinalLocal {
                            public int function value() {
                                return 42;
                            }
                        }
                        FinalLocal fl = new FinalLocal();
                        return fl.value();
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("LocalFinalOuter", code);
        Class<?> clazz = classes.get("LocalFinalOuter");
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(null);
        assertEquals(42, res);
    }

    @Test
    @DisplayName("Local class with 'abstract' modifier is permitted and subclassable locally")
    public void testLocalClassWithAbstractModifierAllowed() throws Exception {
        String code = """
                public class LocalAbstractOuter {
                    public static int function runTest() {
                        abstract class AbstractLocal {
                            public abstract int function score();
                        }
                        class ConcreteLocal extends AbstractLocal {
                            public int function score() {
                                return 88;
                            }
                        }
                        ConcreteLocal cl = new ConcreteLocal();
                        return cl.score();
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("LocalAbstractOuter", code);
        Class<?> clazz = classes.get("LocalAbstractOuter");
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(null);
        assertEquals(88, res);
    }

    @Test
    @DisplayName("Local class declared 'public' must fail compilation")
    public void testLocalClassWithPublicModifierFails() {
        String code = """
                public class LocalPublicOuter {
                    public static int function runTest() {
                        public class InvalidLocal {
                            public int function compute() { return 1; }
                        }
                        return 0;
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compileAndLoadAll("LocalPublicOuter", code));
    }

    @Test
    @DisplayName("Local class declared 'static' must fail compilation")
    public void testLocalClassWithStaticModifierFails() {
        String code = """
                public class LocalStaticOuter {
                    public static int function runTest() {
                        static class InvalidLocal {
                            public int function compute() { return 1; }
                        }
                        return 0;
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compileAndLoadAll("LocalStaticOuter", code));
    }

    @Test
    @DisplayName("Local class declared 'private' must fail compilation")
    public void testLocalClassWithPrivateModifierFails() {
        String code = """
                public class LocalPrivateOuter {
                    public static int function runTest() {
                        private class InvalidLocal {
                            public int function compute() { return 1; }
                        }
                        return 0;
                    }
                }
                """;
        assertThrows(CompilationException.class, () -> compileAndLoadAll("LocalPrivateOuter", code));
    }

    // --------------------------------------------------------------------------
    //
    // --------------------------------------------------------------------------

    @Test
    @DisplayName("Anonymous class implementing interface with diamond operator")
    public void testAnonymousClassDiamondInterface() throws Exception {
        String code = """
                import java.util.function.Function;

                public class AnonDiamondInterfaceOuter {
                    public static int function runTest() {
                        Function<String, Integer> fn = new Function<>() {
                            public Integer function apply(String s) {
                                return s.length();
                            }
                        };
                        return fn.apply("Hello Diamond");
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("AnonDiamondInterfaceOuter", code);
        Class<?> clazz = classes.get("AnonDiamondInterfaceOuter");
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(null);
        assertEquals(13, res);
    }

    @Test
    @DisplayName("Anonymous class extending class with diamond operator")
    public void testAnonymousClassDiamondClass() throws Exception {
        String code = """
                import java.util.ArrayList;

                public class AnonDiamondClassOuter {
                    public static int function runTest() {
                        ArrayList<String> list = new ArrayList<>() {
                            public int function size() {
                                return 99;
                            }
                        };
                        return list.size();
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("AnonDiamondClassOuter", code);
        Class<?> clazz = classes.get("AnonDiamondClassOuter");
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(null);
        assertEquals(99, res);
    }
}
