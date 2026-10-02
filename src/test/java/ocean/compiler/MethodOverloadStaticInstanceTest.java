package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

public class MethodOverloadStaticInstanceTest extends CompilerTestHelper {

    @Test
    @DisplayName("Instance method calling static overload with 'this' compiles and executes without VerifyError")
    public void testInstanceCallsStaticOverloadWithThis() throws Exception {
        String code = """
            public class TestThisCall {
                public bool function isSorted() {
                    return isSorted(this);
                }
                public static bool function isSorted(TestThisCall target) {
                    return target != null;
                }
            }
            """;
        Class<?> cls = compileAndLoad("TestThisCall", code);
        Method instanceM = cls.getMethod("isSorted");
        assertFalse(Modifier.isStatic(instanceM.getModifiers()), "isSorted() must be an instance method");

        Method staticM = cls.getMethod("isSorted", cls);
        assertTrue(Modifier.isStatic(staticM.getModifiers()), "isSorted(TestThisCall) must be a static method");

        Object instance = cls.getDeclaredConstructor().newInstance();
        Object result = instanceM.invoke(instance);
        assertEquals(Boolean.TRUE, result);
    }

    @Test
    @DisplayName("Static overload declared before instance method: flags preserved and 'this' call works")
    public void testStaticDeclaredBeforeInstance() throws Exception {
        String code = """
            public class TestReverseOrder {
                public static int function compute(TestReverseOrder target) {
                    return 42;
                }
                public int function compute() {
                    return compute(this);
                }
            }
            """;
        Class<?> cls = compileAndLoad("TestReverseOrder", code);
        Method staticM = cls.getMethod("compute", cls);
        assertTrue(Modifier.isStatic(staticM.getModifiers()), "compute(TestReverseOrder) must be static");

        Method instanceM = cls.getMethod("compute");
        assertFalse(Modifier.isStatic(instanceM.getModifiers()), "compute() must be instance");

        Object instance = cls.getDeclaredConstructor().newInstance();
        Object result = instanceM.invoke(instance);
        assertEquals(42, result);
    }

    @Test
    @DisplayName("Multiple mixed static and instance overloads resolve correctly")
    public void testMultipleMixedOverloads() throws Exception {
        String code = """
            public class TestMixedOverloads {
                public String function getLabel() {
                    return "instance-zero";
                }
                public static String function getLabel(TestMixedOverloads target) {
                    return "static-target:" + target.getLabel();
                }
                public String function getLabel(String prefix) {
                    return prefix + ":" + getLabel();
                }
                public String function runAll() {
                    return getLabel("prefix") + " | " + getLabel(this);
                }
            }
            """;
        Class<?> cls = compileAndLoad("TestMixedOverloads", code);
        Object instance = cls.getDeclaredConstructor().newInstance();
        Method runAll = cls.getMethod("runAll");
        Object result = runAll.invoke(instance);
        assertEquals("prefix:instance-zero | static-target:instance-zero", result);
    }

    @Test
    @DisplayName("External callers can call both instance and static overloads appropriately")
    public void testExternalCallers() throws Exception {
        String code = """
            public class Helper {
                public bool function check() {
                    return check(this);
                }
                public static bool function check(Helper h) {
                    return true;
                }
            }
            public class Consumer {
                public static bool function run() {
                    variable h = new Helper();
                    variable r1 = h.check();
                    variable r2 = Helper.check(h);
                    return r1 && r2;
                }
            }
            """;
        Class<?> cls = compileAndLoad("Consumer", code);
        Method runM = cls.getMethod("run");
        assertEquals(Boolean.TRUE, runM.invoke(null));
    }
}
