package ocean.compiler;

import org.junit.jupiter.api.Test;

public class ProbeJLSGaps extends CompilerTestHelper {

    @Test
    public void probe_StopOutsideLoop() {
        String code = """
                public class StopTest {
                    public void function test() {
                        stop;
                    }
                }
                """;
        try {
            compileToBytecodeMap(code, "StopTest");
            System.out.println("GAP: StopOutsideLoop COMPILED without error!");
        } catch (Exception e) {
            System.out.println("OK: StopOutsideLoop rejected: " + e.getMessage());
        }
    }

    @Test
    public void probe_SkipOutsideLoop() {
        String code = """
                public class SkipTest {
                    public void function test() {
                        skip;
                    }
                }
                """;
        try {
            compileToBytecodeMap(code, "SkipTest");
            System.out.println("GAP: SkipOutsideLoop COMPILED without error!");
        } catch (Exception e) {
            System.out.println("OK: SkipOutsideLoop rejected: " + e.getMessage());
        }
    }

    @Test
    public void probe_LockOnPrimitive() {
        String code = """
                public class LockPrimTest {
                    public void function test() {
                        lock (42) {
                            int a = 1;
                        }
                    }
                }
                """;
        try {
            compileToBytecodeMap(code, "LockPrimTest");
            System.out.println("GAP: LockOnPrimitive COMPILED without error!");
        } catch (Exception e) {
            System.out.println("OK: LockOnPrimitive rejected: " + e.getMessage());
        }
    }

    @Test
    public void probe_ClassBothAbstractAndFinal() {
        String code = """
                public abstract final class AbsFinalClass {
                }
                """;
        try {
            compileToBytecodeMap(code, "AbsFinalClass");
            System.out.println("GAP: ClassBothAbstractAndFinal COMPILED without error!");
        } catch (Exception e) {
            System.out.println("OK: ClassBothAbstractAndFinal rejected: " + e.getMessage());
        }
    }

    @Test
    public void probe_InterfaceFinal() {
        String code = """
                public final interface FinalIface {
                }
                """;
        try {
            compileToBytecodeMap(code, "FinalIface");
            System.out.println("GAP: InterfaceFinal COMPILED without error!");
        } catch (Exception e) {
            System.out.println("OK: InterfaceFinal rejected: " + e.getMessage());
        }
    }

    @Test
    public void probe_InstanceMethodOverridingStaticMethod() {
        String code = """
                public class BaseClass {
                    public static void function foo() {}
                }
                public class SubClass extends BaseClass {
                    public void function foo() {}
                }
                """;
        try {
            compileToBytecodeMap(code, "SubClass");
            System.out.println("GAP: InstanceMethodOverridingStaticMethod COMPILED without error!");
        } catch (Exception e) {
            System.out.println("OK: InstanceMethodOverridingStaticMethod rejected: " + e.getMessage());
        }
    }

    @Test
    public void probe_StaticMethodHidingInstanceMethod() {
        String code = """
                public class BaseClass2 {
                    public void function bar() {}
                }
                public class SubClass2 extends BaseClass2 {
                    public static void function bar() {}
                }
                """;
        try {
            compileToBytecodeMap(code, "SubClass2");
            System.out.println("GAP: StaticMethodHidingInstanceMethod COMPILED without error!");
        } catch (Exception e) {
            System.out.println("OK: StaticMethodHidingInstanceMethod rejected: " + e.getMessage());
        }
    }

    @Test
    public void probe_AssignToExpression() {
        String code = """
                public class AssignExprTest {
                    public void function test() {
                        int x = 1;
                        int y = 2;
                        (x + y) = 10;
                    }
                }
                """;
        try {
            compileToBytecodeMap(code, "AssignExprTest");
            System.out.println("GAP: AssignToExpression COMPILED without error!");
        } catch (Exception e) {
            System.out.println("OK: AssignToExpression rejected: " + e.getMessage());
        }
    }

    @Test
    public void probe_UnreachableCodeAfterReturn() {
        String code = """
                public class UnreachableTest {
                    public int function test() {
                        return 1;
                        int x = 2;
                    }
                }
                """;
        try {
            compileToBytecodeMap(code, "UnreachableTest");
            System.out.println("GAP: UnreachableCodeAfterReturn COMPILED without error!");
        } catch (Exception e) {
            System.out.println("OK: UnreachableCodeAfterReturn rejected: " + e.getMessage());
        }
    }

    @Test
    public void probe_UnreachableCodeAfterStop() {
        String code = """
                public class UnreachableStopTest {
                    public void function test() {
                        while (true) {
                            stop;
                            int x = 2;
                        }
                    }
                }
                """;
        try {
            compileToBytecodeMap(code, "UnreachableStopTest");
            System.out.println("GAP: UnreachableCodeAfterStop COMPILED without error!");
        } catch (Exception e) {
            System.out.println("OK: UnreachableCodeAfterStop rejected: " + e.getMessage());
        }
    }

    @Test
    public void probe_ThisInStaticMethod() {
        String code = """
                public class ThisStaticTest {
                    int x = 10;
                    public static void function test() {
                        this.x = 20;
                    }
                }
                """;
        try {
            compileToBytecodeMap(code, "ThisStaticTest");
            System.out.println("GAP: ThisInStaticMethod COMPILED without error!");
        } catch (Exception e) {
            System.out.println("OK: ThisInStaticMethod rejected: " + e.getMessage());
        }
    }

    @Test
    public void probe_CtorModifiers() {
        String code = """
                public class CtorModTest {
                    public static CtorModTest() {}
                }
                """;
        try {
            compileToBytecodeMap(code, "CtorModTest");
            System.out.println("GAP: CtorModifiers COMPILED without error!");
        } catch (Exception e) {
            System.out.println("OK: CtorModifiers rejected: " + e.getMessage());
        }
    }
}