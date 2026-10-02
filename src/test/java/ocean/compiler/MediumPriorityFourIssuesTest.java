package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class MediumPriorityFourIssuesTest extends CompilerTestHelper {

    // =========================================================================
    // OC-25: InnerClasses and EnclosingMethod bytecode attributes
    // =========================================================================

    @Test
    @DisplayName("OC-25: Nested member class has valid InnerClasses attribute and reflection metadata")
    public void testInnerClassesAttributeAndReflection() throws Exception {
        String code = """
                public class OC25Outer {
                    public static class StaticInner {
                        public static int function getVal() {
                            return 100;
                        }
                    }

                    public class MemberInner {
                        public int function getVal() {
                            return 200;
                        }
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("OC25Outer", code);
        Class<?> outer = classes.get("OC25Outer");
        assertNotNull(outer);

        Class<?> staticInner = classes.get("StaticInner");
        assertNotNull(staticInner);
        assertEquals("StaticInner", staticInner.getSimpleName());
        assertTrue(staticInner.isMemberClass());
        assertEquals(outer, staticInner.getDeclaringClass());

        Class<?> memberInner = classes.get("MemberInner");
        assertNotNull(memberInner);
        assertEquals("MemberInner", memberInner.getSimpleName());
        assertTrue(memberInner.isMemberClass());
        assertEquals(outer, memberInner.getDeclaringClass());
    }

    @Test
    @DisplayName("OC-25: Local class and anonymous class have EnclosingMethod attribute")
    public void testEnclosingMethodAttribute() throws Exception {
        String code = """
                public class OC25EnclosingTest {
                    public int function runLocal() {
                        class MyLocalCalc {
                            public int function calculate() {
                                return 42;
                            }
                        }
                        MyLocalCalc calc = new MyLocalCalc();
                        return calc.calculate();
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("OC25EnclosingTest", code);
        Class<?> outer = classes.get("OC25EnclosingTest");
        assertNotNull(outer);

        Class<?> localClass = classes.get("MyLocalCalc");
        assertNotNull(localClass);
        assertTrue(localClass.isLocalClass());
        assertNotNull(localClass.getEnclosingMethod());
        assertEquals("runLocal", localClass.getEnclosingMethod().getName());
        assertEquals(outer, localClass.getEnclosingClass());
    }

    // =========================================================================
    // OC-26: Access flag incompatibilities (nested class header & sync field)
    // =========================================================================

    @Test
    @DisplayName("OC-26: Nested private static class has legal ClassFile header flags and InnerClasses flags")
    public void testNestedClassLegalHeaderAccessFlags() throws Exception {
        String code = """
                public class OC26Outer {
                    private static class HiddenInner {
                        public static int function getSecret() {
                            return 777;
                        }
                    }
                }
                """;
        Map<String, byte[]> bytecodes = compileToBytecodeMap(code, "OC26Outer");
        byte[] innerBytes = null;
        for (Map.Entry<String, byte[]> entry : bytecodes.entrySet()) {
            if (entry.getKey().contains("HiddenInner")) {
                innerBytes = entry.getValue();
                break;
            }
        }
        assertNotNull(innerBytes, "HiddenInner bytecode must exist");

        ClassReader cr = new ClassReader(innerBytes);
        int classAccess = cr.getAccess();
        // JVMS Table 4.1-B: ClassFile access_flags must NOT have ACC_PRIVATE (0x0002) or ACC_STATIC (0x0008)
        assertEquals(0, classAccess & Opcodes.ACC_PRIVATE, "Class header must not contain ACC_PRIVATE");
        assertEquals(0, classAccess & Opcodes.ACC_STATIC, "Class header must not contain ACC_STATIC");
    }

    @Test
    @DisplayName("OC-26: Field marked 'sync' maps to ACC_VOLATILE (0x0040) not ACC_SYNCHRONIZED (0x0020)")
    public void testSyncFieldMapsToVolatile() throws Exception {
        String code = """
                public class OC26SyncFieldTest {
                    public sync int count = 10;
                }
                """;
        Class<?> clazz = compileAndLoad("OC26SyncFieldTest", code);
        Field f = clazz.getDeclaredField("count");
        assertNotNull(f);
        int mods = f.getModifiers();
        assertTrue(Modifier.isVolatile(mods), "Field marked sync must have ACC_VOLATILE");
        assertFalse(Modifier.isSynchronized(mods), "Field must NOT have ACC_SYNCHRONIZED (0x20 is illegal on fields)");
    }

    // =========================================================================
    // OC-27: Unnamed package files work cleanly without synthetic package
    // =========================================================================

    @Test
    @DisplayName("OC-27: Unpackaged classes compile to unnamed package and resolve without package prefix")
    public void testUnnamedPackageClasses() throws Exception {
        String code = """
                public class OC27Helper {
                    public static int function add(int a, int b) {
                        return a + b;
                    }
                }
                """;
        Map<String, Class<?>> classes = compileAndLoadAll("OC27Helper", code);
        Class<?> helper = classes.get("OC27Helper");
        assertNotNull(helper);
        assertEquals("OC27Helper", helper.getName());
        assertEquals("", helper.getPackageName());

        Method m = helper.getMethod("add", int.class, int.class);
        assertEquals(30, (int) m.invoke(null, 10, 20));
    }

    // =========================================================================
    // OC-28: Duplicate nested class declaration detection
    // =========================================================================

    @Test
    @DisplayName("OC-28: Duplicate nested class declaration in same outer class is rejected")
    public void testDuplicateNestedClassRejected() {
        String code = """
                public class OC28DuplicateTest {
                    class NestedWorker {
                        public int function getVal() { return 1; }
                    }
                    class NestedWorker {
                        public int function getVal() { return 2; }
                    }
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileToBytecodeMap(code, "OC28DuplicateTest");
        });
        assertTrue(ex.getMessage().contains("already defined") || ex.getMessage().contains("Duplicate"),
                "Expected duplicate nested class error, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("OC-28: Duplicate top-level class declaration is rejected")
    public void testDuplicateTopLevelClassRejected() {
        String code = """
                public class DuplicateTop {
                }
                public class DuplicateTop {
                }
                """;
        CompilationException ex = assertThrows(CompilationException.class, () -> {
            compileToBytecodeMap(code, "DuplicateTop");
        });
        assertTrue(ex.getMessage().contains("already defined") || ex.getMessage().contains("Duplicate"),
                "Expected duplicate top level class error, got: " + ex.getMessage());
    }
}
