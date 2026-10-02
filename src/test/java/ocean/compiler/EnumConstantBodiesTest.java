package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class EnumConstantBodiesTest extends CompilerTestHelper {

    @Test
    @DisplayName("Basic Enum Constant Body overriding virtual method")
    public void testBasicEnumConstantBody() throws Exception {
        String code = """
                public enum Status {
                    NORMAL {
                        public String function getLabel() {
                            return "NORMAL_LABEL";
                        }
                    },
                    ALERT {
                        public String function getLabel() {
                            return "ALERT_LABEL";
                        }
                    },
                    DEFAULT;

                    public String function getLabel() {
                        return "BASE_LABEL";
                    }
                }
                
                public class TestRunner {
                    public static String function run() {
                        return Status.NORMAL.getLabel() + "," + Status.ALERT.getLabel() + "," + Status.DEFAULT.getLabel();
                    }
                }
                """;

        Class<?> clazz = compileAndLoad("TestRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals("NORMAL_LABEL,ALERT_LABEL,BASE_LABEL", m.invoke(null));
    }

    @Test
    @DisplayName("Enum with Abstract Method implemented by all constant bodies")
    public void testEnumWithAbstractMethod() throws Exception {
        String code = """
                public enum Operation {
                    PLUS("+") {
                        public int function apply(int a, int b) {
                            return a + b;
                        }
                    },
                    MINUS("-") {
                        public int function apply(int a, int b) {
                            return a - b;
                        }
                    },
                    TIMES("*") {
                        public int function apply(int a, int b) {
                            return a * b;
                        }
                    };

                    private final String symbol;

                    function Operation(String symbolx) {
                        this.symbol = symbolx;
                    }

                    public String function getSymbol() {
                        return this.symbol;
                    }

                    public abstract int function apply(int a, int b);
                }

                public class TestRunner {
                    public static String function run() {
                        Operation op1 = Operation.PLUS;
                        Operation op2 = Operation.MINUS;
                        Operation op3 = Operation.TIMES;

                        return op1.getSymbol() + ":" + op1.apply(10, 5) + "|" +
                               op2.getSymbol() + ":" + op2.apply(10, 5) + "|" +
                               op3.getSymbol() + ":" + op3.apply(10, 5);
                    }
                }
                """;

        Class<?> clazz = compileAndLoad("TestRunner", code);
        Method m = clazz.getMethod("run");

        assertEquals("+:15|-:5|*:50", m.invoke(null));
    }

    @Test
    @DisplayName("Enum Constant Polymorphism via direct constant access")
    public void testEnumConstantPolymorphism() throws Exception {
        String code = """
                public enum Grade {
                    A {
                        public String function describe() { return "Excellent"; }
                    },
                    B {
                        public String function describe() { return "Good"; }
                    },
                    C {
                        public String function describe() { return "Passing"; }
                    };

                    public abstract String function describe();
                }

                public class TestRunner {
                    public static String function run() {
                        Grade g1 = Grade.A;
                        Grade g2 = Grade.B;
                        Grade g3 = Grade.C;
                        return g1.name() + "->" + g1.describe() + ";" +
                               g2.name() + "->" + g2.describe() + ";" +
                               g3.name() + "->" + g3.describe();
                    }
                }
                """;

        Class<?> clazz = compileAndLoad("TestRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals("A->Excellent;B->Good;C->Passing", m.invoke(null));
    }

    @Test
    @DisplayName("Enum values() array indexing")
    public void testEnumValuesArray() throws Exception {
        String code = """
                public enum Grade {
                    A {
                        public String function describe() { return "Excellent"; }
                    },
                    B {
                        public String function describe() { return "Good"; }
                    },
                    C {
                        public String function describe() { return "Passing"; }
                    };

                    public abstract String function describe();
                }

                public class TestRunner {
                    public static String function run() {
                        Grade[] all = Grade.values();
                        Grade g0 = all[0];
                        Grade g1 = all[1];
                        Grade g2 = all[2];
                        return g0.name() + "->" + g0.describe() + ";" +
                               g1.name() + "->" + g1.describe() + ";" +
                               g2.name() + "->" + g2.describe();
                    }
                }
                """;

        Class<?> clazz = compileAndLoad("TestRunner", code);
        Method m = clazz.getMethod("run");
        assertEquals("A->Excellent;B->Good;C->Passing", m.invoke(null));
    }
}