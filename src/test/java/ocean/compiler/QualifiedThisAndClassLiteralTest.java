package ocean.compiler;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

public class QualifiedThisAndClassLiteralTest {

    @Test
    public void testClassLiteralReferenceType() throws Exception {
        String code = """
            public class TestClassLit {
                public static String function getStringClassName() {
                    return String.class.getName();
                }
                public static String function getSelfClassName() {
                    return TestClassLit.class.getName();
                }
                public static String function getSelfSimpleName() {
                    return TestClassLit.class.getSimpleName();
                }
            }
            """;
        Class<?> cls = CompilerTestHelper.compileAndLoad("TestClassLit", code);
        Method m1 = cls.getMethod("getStringClassName");
        assertEquals("java.lang.String", m1.invoke(null));

        Method m2 = cls.getMethod("getSelfClassName");
        assertEquals(cls.getName(), m2.invoke(null));

        Method m3 = cls.getMethod("getSelfSimpleName");
        assertEquals("TestClassLit", m3.invoke(null));
    }

    @Test
    public void testClassLiteralPrimitives() throws Exception {
        String code = """
            public class TestPrimClassLit {
                public static String function getIntName() { return int.class.getName(); }
                public static String function getBoolName() { return bool.class.getName(); }
                public static String function getBooleanName() { return boolean.class.getName(); }
                public static String function getVoidName() { return void.class.getName(); }
                public static String function getLongName() { return long.class.getName(); }
                public static String function getDoubleName() { return double.class.getName(); }
                public static String function getByteName() { return byte.class.getName(); }
                public static String function getShortName() { return short.class.getName(); }
                public static String function getCharName() { return char.class.getName(); }
                public static String function getFloatName() { return float.class.getName(); }
            }
            """;
        Class<?> cls = CompilerTestHelper.compileAndLoad("TestPrimClassLit", code);
        assertEquals("int", cls.getMethod("getIntName").invoke(null));
        assertEquals("boolean", cls.getMethod("getBoolName").invoke(null));
        assertEquals("boolean", cls.getMethod("getBooleanName").invoke(null));
        assertEquals("void", cls.getMethod("getVoidName").invoke(null));
        assertEquals("long", cls.getMethod("getLongName").invoke(null));
        assertEquals("double", cls.getMethod("getDoubleName").invoke(null));
        assertEquals("byte", cls.getMethod("getByteName").invoke(null));
        assertEquals("short", cls.getMethod("getShortName").invoke(null));
        assertEquals("char", cls.getMethod("getCharName").invoke(null));
        assertEquals("float", cls.getMethod("getFloatName").invoke(null));
    }

    @Test
    public void testClassLiteralArrays() throws Exception {
        String code = """
            public class TestArrayClassLit {
                public static String function getIntArrayName() { return int[].class.getName(); }
                public static String function getStringArrayName() { return String[].class.getName(); }
                public static String function getMultiDimArrayName() { return int[][].class.getName(); }
            }
            """;
        Class<?> cls = CompilerTestHelper.compileAndLoad("TestArrayClassLit", code);
        assertEquals("[I", cls.getMethod("getIntArrayName").invoke(null));
        assertEquals("[Ljava.lang.String;", cls.getMethod("getStringArrayName").invoke(null));
        assertEquals("[[I", cls.getMethod("getMultiDimArrayName").invoke(null));
    }

    @Test
    public void testQualifiedThisEnclosingFieldAndMethod() throws Exception {
        String code = """
            public class OuterClass {
                public int outerField = 42;

                public int function outerMethod() {
                    return outerField * 2;
                }

                public class InnerClass {
                    public int function getOuterFieldViaQualifiedThis() {
                        return OuterClass.this.outerField;
                    }

                    public int function callOuterMethodViaQualifiedThis() {
                        return OuterClass.this.outerMethod();
                    }
                }

                public int function test() {
                    InnerClass inner = new InnerClass();
                    return inner.getOuterFieldViaQualifiedThis() + inner.callOuterMethodViaQualifiedThis();
                }
            }
            """;
        Class<?> cls = CompilerTestHelper.compileAndLoad("OuterClass", code);
        Object outer = cls.getDeclaredConstructor().newInstance();
        Method testMethod = cls.getMethod("test");
        int result = (int) testMethod.invoke(outer);
        // 42 + 84 = 126
        assertEquals(126, result);
    }

    @Test
    public void testQualifiedThisShadowingResolution() throws Exception {
        String code = """
            public class ShadowOuter {
                public int x = 10;

                public class ShadowInner {
                    public int x = 20;

                    public int function compute(int x) {
                        return ShadowOuter.this.x + this.x + x;
                    }
                }

                public int function run() {
                    ShadowInner inner = new ShadowInner();
                    return inner.compute(30);
                }
            }
            """;
        Class<?> cls = CompilerTestHelper.compileAndLoad("ShadowOuter", code);
        Object outer = cls.getDeclaredConstructor().newInstance();
        Method runMethod = cls.getMethod("run");
        int result = (int) runMethod.invoke(outer);
        // 10 + 20 + 30 = 60
        assertEquals(60, result);
    }

    @Test
    public void testQualifiedThisDeeplyNested() throws Exception {
        String code = """
            public class Level1 {
                public int val1 = 100;

                public class Level2 {
                    public int val2 = 200;

                    public class Level3 {
                        public int function sum() {
                            return Level1.this.val1 + Level2.this.val2;
                        }
                    }

                    public int function test() {
                        Level3 l3 = new Level3();
                        return l3.sum();
                    }
                }

                public int function run() {
                    Level2 l2 = new Level2();
                    return l2.test();
                }
            }
            """;
        Class<?> cls = CompilerTestHelper.compileAndLoad("Level1", code);
        Object l1 = cls.getDeclaredConstructor().newInstance();
        Method runMethod = cls.getMethod("run");
        int result = (int) runMethod.invoke(l1);
        // 100 + 200 = 300
        assertEquals(300, result);
    }

    @Test
    public void testQualifiedThisPassingInstance() throws Exception {
        String code = """
            public class Host {
                public int tag = 99;

                public int function acceptHost(Host h) {
                    return h.tag;
                }

                public class Client {
                    public int function passHost() {
                        return Host.this.acceptHost(Host.this);
                    }
                }

                public int function run() {
                    Client c = new Client();
                    return c.passHost();
                }
            }
            """;
        Class<?> cls = CompilerTestHelper.compileAndLoad("Host", code);
        Object host = cls.getDeclaredConstructor().newInstance();
        Method runMethod = cls.getMethod("run");
        int result = (int) runMethod.invoke(host);
        assertEquals(99, result);
    }

    @Test
    public void testQualifiedThisStaticContextFails() {
        String code = """
            public class StaticOuter {
                public static void function foo() {
                    Object o = StaticOuter.this;
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            CompilerTestHelper.compileToBytecodeMap(code, "StaticOuter");
        });
        assertTrue(ex.getMessage().contains("static context"),
                "Expected static context error, got: " + ex.getMessage());
    }

    @Test
    public void testQualifiedThisUnrelatedClassFails() {
        String code = """
            public class UnrelatedOuter {
                public class Inner {
                    public void function bar() {
                        Object o = SomeOtherClass.this;
                    }
                }
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            CompilerTestHelper.compileToBytecodeMap(code, "UnrelatedOuter");
        });
        assertTrue(ex.getMessage().contains("No enclosing instance of type"),
                "Expected enclosing class not found error, got: " + ex.getMessage());
    }
}