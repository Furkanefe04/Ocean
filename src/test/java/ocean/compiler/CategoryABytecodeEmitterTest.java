package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;

import static org.junit.jupiter.api.Assertions.*;

public class CategoryABytecodeEmitterTest extends CompilerTestHelper {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    @Test
    @DisplayName("A1 & A2: Enum values() array clone is invoked correctly and returns elements")
    public void testEnumValuesClone() throws Exception {
        String code = """
                public enum Direction {
                    NORTH, SOUTH, EAST, WEST
                }

                public class EnumValuesTest {
                    public static int function runTest() {
                        Direction[] dirs = Direction.values();
                        return dirs.length;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("EnumValuesTest", code);
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        Object res = m.invoke(null);
        assertEquals(4, res);
    }

    @Test
    @DisplayName("A5: Primitive to String cast emits String.valueOf rather than Integer.valueOf")
    public void testPrimitiveToStringConversion() throws Exception {
        IRToBytecodeEmitter emitter = new IRToBytecodeEmitter();
        org.objectweb.asm.tree.MethodNode mn = new org.objectweb.asm.tree.MethodNode();
        Field mvField = IRToBytecodeEmitter.class.getDeclaredField("methodVisitor");
        mvField.setAccessible(true);
        mvField.set(emitter, mn);

        Method emitConvMethod = IRToBytecodeEmitter.class.getDeclaredMethod("emitConversion", String.class, String.class);
        emitConvMethod.setAccessible(true);

        // 1. Primitive int to String
        emitConvMethod.invoke(emitter, "I", "Ljava/lang/String;");
        assertTrue(mn.instructions.size() > 0, "Instructions should have been emitted");
        org.objectweb.asm.tree.AbstractInsnNode insn = mn.instructions.get(0);
        assertTrue(insn instanceof org.objectweb.asm.tree.MethodInsnNode);
        org.objectweb.asm.tree.MethodInsnNode methodInsn = (org.objectweb.asm.tree.MethodInsnNode) insn;
        assertEquals("java/lang/String", methodInsn.owner);
        assertEquals("valueOf", methodInsn.name);
        assertEquals("(I)Ljava/lang/String;", methodInsn.desc);

        // 2. Primitive bool to String
        mn.instructions.clear();
        emitConvMethod.invoke(emitter, "Z", "Ljava/lang/String;");
        assertTrue(mn.instructions.size() > 0);
        org.objectweb.asm.tree.MethodInsnNode boolInsn = (org.objectweb.asm.tree.MethodInsnNode) mn.instructions.get(0);
        assertEquals("java/lang/String", boolInsn.owner);
        assertEquals("valueOf", boolInsn.name);
        assertEquals("(Z)Ljava/lang/String;", boolInsn.desc);

        // 3. Null-safety checks (should not throw NPE)
        assertDoesNotThrow(() -> emitConvMethod.invoke(emitter, null, "I"));
        assertDoesNotThrow(() -> emitConvMethod.invoke(emitter, "I", null));
        assertDoesNotThrow(() -> emitConvMethod.invoke(emitter, null, null));
    }

    @Test
    @DisplayName("A4: Generic field signature is preserved in class file bytecode")
    public void testFieldGenericSignaturePreserved() throws Exception {
        String code = """
                import java.util.List;

                public class GenericFieldTest {
                    public List<String> items;
                }
                """;
        Class<?> clazz = compileAndLoad("GenericFieldTest", code);
        assertNotNull(clazz);
        Field field = clazz.getDeclaredField("items");
        assertNotNull(field);
        assertTrue(field.getGenericType() instanceof ParameterizedType,
                "Field generic type should be ParameterizedType, got: " + field.getGenericType());
        ParameterizedType pt = (ParameterizedType) field.getGenericType();
        assertEquals(String.class, pt.getActualTypeArguments()[0]);
    }

    @Test
    @DisplayName("A9: Pattern instanceof does not use SWAP and properly binds pattern variable")
    public void testPatternInstanceofNoSwap() throws Exception {
        String code = """
                public class PatternTest {
                    public static String function check(Object obj) {
                        if (obj instanceof String s) {
                            return "str:" + s;
                        }
                        return "other";
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("PatternTest", code);
        assertNotNull(clazz);
        Method m = clazz.getMethod("check", Object.class);
        assertEquals("str:ocean", m.invoke(null, "ocean"));
        assertEquals("other", m.invoke(null, 123));
    }

    @Test
    @DisplayName("A13: Byte array post-increment correctly truncates with I2B")
    public void testByteArrayIncrementTruncation() throws Exception {
        String code = """
                public class ByteIncTest {
                    public static byte function runTest() {
                        byte[] arr = new byte[1];
                        arr[0] = (byte) 127;
                        arr[0]++;
                        return arr[0];
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("ByteIncTest", code);
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        assertEquals((byte) -128, m.invoke(null));
    }

    @Test
    @DisplayName("A3: Enum constructor access flags are forced to ACC_PRIVATE")
    public void testEnumConstructorIsPrivate() throws Exception {
        String code = """
                public enum Role {
                    ADMIN(1), USER(2);

                    int code;

                    function Role(int code) {
                        this.code = code;
                    }

                    public int function getCode() {
                        return this.code;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("Role", code);
        assertNotNull(clazz);
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        assertTrue(constructors.length > 0, "Enum should have constructors");
        for (Constructor<?> ctor : constructors) {
            assertTrue(Modifier.isPrivate(ctor.getModifiers()),
                    "Enum constructor must be private, got modifiers: " + ctor.getModifiers());
        }
    }

    @Test
    @DisplayName("A14: Try-catch block emits normalized exception internal name and catches properly")
    public void testTryCatchExecution() throws Exception {
        String code = """
                public class TryCatchTest {
                    public static int function runTest() {
                        trying {
                            throw new RuntimeException("boom");
                        } catch (RuntimeException e) {
                            return 42;
                        }
                        return 0;
                    }
                }
                """;
        Class<?> clazz = compileAndLoad("TryCatchTest", code);
        assertNotNull(clazz);
        Method m = clazz.getMethod("runTest");
        assertEquals(42, m.invoke(null));
    }
}
