package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class InnerClassOuterAccessTest extends CompilerTestHelper {

    @Test
    @DisplayName("OC-09: False positive - outer has field x, inner has max(), inner can be instantiated from static method")
    public void testInnerClassSubstringFalsePositive() throws Exception {
        String code = """
                public class SubstringTest {
                    public int x = 100;

                    public class Helper {
                        public int function max(int a, int b) {
                            return a > b ? a : b;
                        }
                    }

                    public static int function runTest() {
                        Helper h = new Helper();
                        return h.max(10, 20);
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("SubstringTest", code);
        Class<?> clazz = classes.get("SubstringTest");
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(null);
        assertEquals(20, res);
    }

    @Test
    @DisplayName("OC-09: False negative - inner class declared BEFORE outer instance field")
    public void testInnerClassDeclaredBeforeOuterField() throws Exception {
        String code = """
                public class DeclarationOrderTest {
                    public class Inner {
                        public int function getOuterCounter() {
                            return counter;
                        }
                    }

                    public int counter = 42;

                    public int function runTest() {
                        Inner i = new Inner();
                        return i.getOuterCounter();
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("DeclarationOrderTest", code);
        Class<?> clazz = classes.get("DeclarationOrderTest");
        assertNotNull(clazz);
        Object inst = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(inst);
        assertEquals(42, res);
    }

    @Test
    @DisplayName("OC-09: False negative - inner class accesses inherited outer field from superclass")
    public void testInnerClassAccessesInheritedOuterField() throws Exception {
        String code = """
                public class BaseOuter {
                    public int baseValue = 99;
                }

                public class ChildOuter extends BaseOuter {
                    public class Inner {
                        public int function getBase() {
                            return baseValue;
                        }
                    }

                    public int function runTest() {
                        Inner i = new Inner();
                        return i.getBase();
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("ChildOuter", code);
        Class<?> clazz = classes.get("ChildOuter");
        assertNotNull(clazz);
        Object inst = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(inst);
        assertEquals(99, res);
    }

    @Test
    @DisplayName("OC-10: Anonymous class accessing outer instance field")
    public void testAnonymousClassOuterFieldAccess() throws Exception {
        String code = """
                public interface Action {
                    public int function execute();
                }

                public class AnonOuterTest {
                    public int factor = 5;

                    public int function runTest() {
                        Action a = new Action() {
                            public int function execute() {
                                return factor * 10;
                            }
                        };
                        return a.execute();
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("AnonOuterTest", code);
        Class<?> clazz = classes.get("AnonOuterTest");
        assertNotNull(clazz);
        Object inst = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(inst);
        assertEquals(50, res);
    }

    @Test
    @DisplayName("OC-11: Inner class with explicit constructor arguments accessing outer field")
    public void testInnerClassExplicitCtor() throws Exception {
        String code = """
                public class ExplicitCtorTest {
                    public int multiplier = 3;

                    public class Calculator {
                        public int base;
                        public function Calculator(int base) {
                            this.base = base;
                        }
                        public int function calculate() {
                            return this.base * multiplier;
                        }
                    }

                    public int function runTest() {
                        Calculator c = new Calculator(7);
                        return c.calculate();
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("ExplicitCtorTest", code);
        Class<?> clazz = classes.get("ExplicitCtorTest");
        assertNotNull(clazz);
        Object inst = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(inst);
        assertEquals(21, res);
    }

    @Test
    @DisplayName("OC-12: Inner class extending another inner class in same outer")
    public void testInnerExtendsInner() throws Exception {
        String code = """
                public class HierarchyTest {
                    public int offset = 10;

                    public class BaseInner {
                        public int function getOffset() {
                            return offset;
                        }
                    }

                    public class SubInner extends BaseInner {
                        public int function compute(int val) {
                            return val + getOffset();
                        }
                    }

                    public int function runTest() {
                        SubInner sub = new SubInner();
                        return sub.compute(15);
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("HierarchyTest", code);
        Class<?> clazz = classes.get("HierarchyTest");
        assertNotNull(clazz);
        Object inst = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(inst);
        assertEquals(25, res);
    }

    @Test
    @DisplayName("OC-13: Labeled stop inside try with inline finally does not skip outer catch")
    public void testTryFinallyInlineExceptionCaught() throws Exception {
        String code = """
                public class TryFinallyTest {
                    public static int function runTest() {
                        int result = 0;
                        trying {
                            trying {
                                throw new java.lang.RuntimeException("inner error");
                            } finally {
                                result += 10;
                            }
                        } catch (java.lang.RuntimeException e) {
                            result += 5;
                        }
                        return result;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("TryFinallyTest", code);
        Class<?> clazz = classes.get("TryFinallyTest");
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        int res = (int) m.invoke(null);
        assertEquals(15, res);
    }

    @Test
    @DisplayName("OC-14: Nested enum accessing outer private static field")
    public void testNestedEnumOuterPrivateAccess() throws Exception {
        String code = """
                public class NestOuter {
                    private static int secret = 1234;

                    public enum Type {
                        FIRST, SECOND;

                        public int function getSecret() {
                            return secret;
                        }
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("NestOuter", code);
        Class<?> typeCls = classes.get("NestOuter$Type");
        assertNotNull(typeCls);
        Object first = Enum.valueOf((Class<Enum>) typeCls, "FIRST");
        Method m = typeCls.getMethod("getSecret");
        int res = (int) m.invoke(first);
        assertEquals(1234, res);
    }
}
