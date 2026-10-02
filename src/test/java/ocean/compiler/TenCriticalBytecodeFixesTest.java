package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TenCriticalBytecodeFixesTest extends CompilerTestHelper {

    @Test
    @DisplayName("Madde 1: BigDecimal vs null comparison does not throw NullPointerException")
    public void testBigDecimalNullComparison() throws Exception {
        String code = """
                import java.math.BigDecimal;
                public class TestBigDecimalNull {
                    public static boolean function isNull(BigDecimal val) {
                        return val == null;
                    }
                    public static boolean function isNotNull(BigDecimal val) {
                        return val != null;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestBigDecimalNull", code);
        Method isNullMethod = clazz.getMethod("isNull", BigDecimal.class);
        Method isNotNullMethod = clazz.getMethod("isNotNull", BigDecimal.class);

        assertTrue((boolean) isNullMethod.invoke(null, (Object) null));
        assertFalse((boolean) isNullMethod.invoke(null, new BigDecimal("10.5")));

        assertFalse((boolean) isNotNullMethod.invoke(null, (Object) null));
        assertTrue((boolean) isNotNullMethod.invoke(null, new BigDecimal("10.5")));
    }

    @Test
    @DisplayName("Madde 2: Safe access on object length produces null-safe boxed Integer")
    public void testSafeAccessLength() throws Exception {
        String code = """
                public class TestSafeAccess {
                    public static int? function getLength(String? str) {
                        return str?.length();
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestSafeAccess", code);
        Method m = clazz.getMethod("getLength", String.class);
        assertNull(m.invoke(null, (Object) null));
        assertEquals(5, m.invoke(null, "hello"));
    }

    @Test
    @DisplayName("Madde 3: Lock statement releases monitor properly even across exceptions")
    public void testLockStatementRelease() throws Exception {
        String code = """
                public class TestLockStatement {
                    private static Object lockObj = new Object();
                    public static int function syncMethod(int x) {
                        lock (lockObj) {
                            if (x < 0) {
                                throw new IllegalArgumentException("negative");
                            }
                            return x * 2;
                        }
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestLockStatement", code);
        Method m = clazz.getMethod("syncMethod", int.class);
        assertEquals(20, m.invoke(null, 10));

        try {
            m.invoke(null, -1);
            fail("Should throw exception");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof IllegalArgumentException);
        }

        // Verify monitor is not deadlocked or corrupted by calling again
        assertEquals(40, m.invoke(null, 20));
    }

    @Test
    @DisplayName("Madde 4: emitConversion handles from 'null' to primitive by throwing NPE (standard unboxing per OC-16)")
    public void testNullToPrimitiveConversion() throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "TestEmitConvNull", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "test", "()I", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);

        IRToBytecodeEmitter emitter = new IRToBytecodeEmitter();
        Field mvField = IRToBytecodeEmitter.class.getDeclaredField("methodVisitor");
        mvField.setAccessible(true);
        mvField.set(emitter, mv);

        Method emitConvMethod = IRToBytecodeEmitter.class.getDeclaredMethod("emitConversion", String.class, String.class);
        emitConvMethod.setAccessible(true);
        emitConvMethod.invoke(emitter, "null", "I");

        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] bytes = cw.toByteArray();
        ClassLoader cl = new ClassLoader(getClass().getClassLoader()) {
            public Class<?> define() {
                return defineClass("TestEmitConvNull", bytes, 0, bytes.length);
            }
        };
        Method defineM = cl.getClass().getMethod("define");
        Class<?> clz = (Class<?>) defineM.invoke(cl);
        Method testM = clz.getMethod("test");
        java.lang.reflect.InvocationTargetException ex = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> testM.invoke(null));
        assertInstanceOf(NullPointerException.class, ex.getCause());
    }

    @Test
    @DisplayName("Madde 5: Calling Object methods (toString, hashCode) on interface references succeeds")
    public void testInterfaceObjectMethods() throws Exception {
        String code = """
                import java.util.List;
                public class TestInterfaceObjectMethods {
                    public static String function getListString(List list) {
                        return list.toString();
                    }
                    public static int function getListHash(List list) {
                        return list.hashCode();
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestInterfaceObjectMethods", code);
        Method toStringM = clazz.getMethod("getListString", List.class);
        Method hashM = clazz.getMethod("getListHash", List.class);

        List<String> list = List.of("a", "b");
        assertEquals(list.toString(), toStringM.invoke(null, list));
        assertEquals(list.hashCode(), hashM.invoke(null, list));
    }

    @Test
    @DisplayName("Madde 6: Specialized list literal initialization compiles and runs properly")
    public void testSpecializedListLiteral() throws Exception {
        String code = """
                import java.util.List;
                public class TestListLiteral {
                    public static List function getNumbers() {
                        return [1, 2, 3];
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestListLiteral", code);
        Method m = clazz.getMethod("getNumbers");
        Object result = m.invoke(null);
        assertTrue(result instanceof List);
        assertEquals(List.of(1, 2, 3), result);
    }

    @Test
    @DisplayName("Madde 7: String interpolation and valueOf handle various types cleanly")
    public void testStringValueOfHandling() throws Exception {
        String code = """
                public class TestStringConcat {
                    public static String function concat(int num, Object obj) {
                        return "Value: " + num + ", Obj: " + obj;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestStringConcat", code);
        Method m = clazz.getMethod("concat", int.class, Object.class);
        assertEquals("Value: 42, Obj: test", m.invoke(null, 42, "test"));
        assertEquals("Value: 0, Obj: null", m.invoke(null, 0, null));
    }

    @Test
    @DisplayName("Madde 8: Switch statement and expression correctly match case null")
    public void testSwitchCaseNull() throws Exception {
        String code = """
                public class TestSwitchNull {
                    public static String function check(String str) {
                        return switch (str) {
                            case null -> "is_null";
                            case "hello" -> "is_hello";
                            default -> "other";
                        };
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestSwitchNull", code);
        Method m = clazz.getMethod("check", String.class);
        assertEquals("is_null", m.invoke(null, (Object) null));
        assertEquals("is_hello", m.invoke(null, "hello"));
        assertEquals("other", m.invoke(null, "world"));
    }

    @Test
    @DisplayName("Madde 9: Null-coalescing (??) evaluates RHS when LHS is nullable int or safe-access null")
    public void testNullCoalescingWithNullablePrimitive() throws Exception {
        String code = """
                public class TestNullCoalesce {
                    public static int function fallback(int? val, int defVal) {
                        return val ?? defVal;
                    }
                    public static int function safeAccessFallback(String? str, int defVal) {
                        return str?.length() ?? defVal;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TestNullCoalesce", code);
        Method mFallback = null;
        Method mSafe = null;
        for (Method m : clazz.getMethods()) {
            if ("fallback".equals(m.getName())) mFallback = m;
            if ("safeAccessFallback".equals(m.getName())) mSafe = m;
        }
        assertNotNull(mFallback, "mFallback must exist");
        assertNotNull(mSafe, "mSafe must exist");

        assertEquals(99, mFallback.invoke(null, null, 99));
        assertEquals(42, mFallback.invoke(null, 42, 99));

        assertEquals(0, mSafe.invoke(null, null, 0));
        assertEquals(5, mSafe.invoke(null, "ocean", 0));
    }

    @Test
    @DisplayName("Madde 10: String relational comparisons (<, <=, >, >=) compare lexicographically")
    public void testStringRelationalComparison() throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "TestEmitStringRelational", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isLess", "(Ljava/lang/String;Ljava/lang/String;)Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);

        IRToBytecodeEmitter emitter = new IRToBytecodeEmitter();
        Field mvField = IRToBytecodeEmitter.class.getDeclaredField("methodVisitor");
        mvField.setAccessible(true);
        mvField.set(emitter, mv);

        Method emitCompMethod = IRToBytecodeEmitter.class.getDeclaredMethod("emitComparison", int.class, int.class, String.class);
        emitCompMethod.setAccessible(true);
        emitCompMethod.invoke(emitter, Opcodes.IF_ICMPLT, Opcodes.IFLT, OceanTypeSystem.STRING_DESC);

        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] bytes = cw.toByteArray();
        ClassLoader cl = new ClassLoader(getClass().getClassLoader()) {
            public Class<?> define() {
                return defineClass("TestEmitStringRelational", bytes, 0, bytes.length);
            }
        };
        Method defineM = cl.getClass().getMethod("define");
        Class<?> clz = (Class<?>) defineM.invoke(cl);
        Method isLessM = clz.getMethod("isLess", String.class, String.class);

        assertTrue((boolean) isLessM.invoke(null, "apple", "banana"));
        assertFalse((boolean) isLessM.invoke(null, "banana", "apple"));
        assertFalse((boolean) isLessM.invoke(null, "apple", "apple"));
    }
}
