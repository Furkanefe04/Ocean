package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TailRecOptimizationTest {

    @Test
    @DisplayName("Verify @TailRec optimizes 1,000,000 deep recursion into a loop with zero StackOverflow")
    void testDeepRecursionExecution() throws Exception {
        String code = """
            package test;
            
            public annotation TailRec {}
            
            public class DeepRecTest {
                @TailRec
                public static long function sum(long n, long acc) {
                    if (n <= 0) {
                        return acc;
                    }
                    return sum(n - 1, acc + 1);
                }
            }
            """;

        Map<String, byte[]> classes = CompilerTestHelper.compileToBytecodeMap(code, "DeepRecTest");
        assertNotNull(classes);

        // Bytecode assertion: sum method must NOT contain recursive INVOKESTATIC to DeepRecTest.sum
        byte[] classBytes = classes.get("test/DeepRecTest");
        assertNotNull(classBytes);

        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        MethodNode sumMethod = null;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("sum")) {
                sumMethod = mn;
                break;
            }
        }
        assertNotNull(sumMethod, "Method 'sum' must exist in DeepRecTest");

        for (AbstractInsnNode insn : sumMethod.instructions) {
            if (insn instanceof MethodInsnNode minsn) {
                assertFalse(minsn.owner.equals("test/DeepRecTest") && minsn.name.equals("sum"),
                        "sum() bytecode must NOT contain self recursive call to sum()");
            }
        }

        // Execution assertion: 1,000,000 calls must compute in ms
        Class<?> clazz = CompilerTestHelper.compileAndLoad("DeepRecTest", code);
        Method sum = clazz.getMethod("sum", long.class, long.class);
        Object result = sum.invoke(null, 1000000L, 0L);
        assertEquals(1000000L, result);
    }

    @Test
    @DisplayName("Verify @TailRec Euclidean GCD parallel parameter update")
    void testGcdParallelAssignment() throws Exception {
        String code = """
            package test;
            
            public annotation TailRec {}
            
            public class GcdTest {
                @TailRec
                public static int function gcd(int a, int b) {
                    if (b == 0) return a;
                    return gcd(b, a % b);
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("GcdTest", code);
        Method gcd = clazz.getMethod("gcd", int.class, int.class);

        assertEquals(6, gcd.invoke(null, 48, 18));
        assertEquals(21, gcd.invoke(null, 1071, 462));
    }

    @Test
    @DisplayName("Verify @TailRec instance method with 'this' receiver")
    void testInstanceMethodTailRec() throws Exception {
        String code = """
            package test;
            
            public annotation TailRec {}
            
            public class InstanceTailRec {
                public int mult = 5;
                
                @TailRec
                public long function multiplyAcc(long n, long acc) {
                    if (n <= 0) return acc;
                    return this.multiplyAcc(n - 1, acc + this.mult);
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("InstanceTailRec", code);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("multiplyAcc", long.class, long.class);

        assertEquals(50000L, m.invoke(instance, 10000L, 0L));
    }

    @Test
    @DisplayName("Verify @TailRec ternary return expression transformation")
    void testTernaryTailRec() throws Exception {
        String code = """
            package test;
            
            public annotation TailRec {}
            
            public class TernaryTest {
                @TailRec
                public static long function fact(long n, long acc) {
                    return n <= 1 ? acc : fact(n - 1, n * acc);
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("TernaryTest", code);
        Method fact = clazz.getMethod("fact", long.class, long.class);

        assertEquals(120L, fact.invoke(null, 5L, 1L));
        assertEquals(720L, fact.invoke(null, 6L, 1L));
    }
}
