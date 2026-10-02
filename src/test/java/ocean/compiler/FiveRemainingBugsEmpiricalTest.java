package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class FiveRemainingBugsEmpiricalTest {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    private Map<String, byte[]> compileToBytecodeMap(String code, String className) {
        return CompilerTestHelper.compileToBytecodeMap(code, className);
    }

    private String executeMainAndCaptureOutput(Map<String, byte[]> classes, String className) throws Exception {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                String pathKey = name.replace('.', '/');
                if (classes.containsKey(pathKey)) {
                    byte[] bytes = classes.get(pathKey);
                    return defineClass(name, bytes, 0, bytes.length);
                }
                for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                    if (entry.getKey().endsWith("/" + name) || entry.getKey().equals(name)) {
                        byte[] bytes = entry.getValue();
                        return defineClass(name, bytes, 0, bytes.length);
                    }
                }
                return super.findClass(name);
            }
        };

        String fqName = classes.containsKey(className + "/" + className) ? className + "." + className : className;
        Class<?> clazz = loader.loadClass(fqName);
        Method mainMethod = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals("main")) {
                mainMethod = m;
                break;
            }
        }
        if (mainMethod == null) {
            throw new RuntimeException("No main method found in " + clazz.getName());
        }
        mainMethod.setAccessible(true);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(baos));
        try {
            if (mainMethod.getParameterCount() == 1) {
                mainMethod.invoke(null, (Object) new String[0]);
            } else {
                mainMethod.invoke(null);
            }
        } finally {
            System.setOut(originalOut);
        }
        return baos.toString().trim();
    }

    // =========================================================================
    // TASK 1 (Bug 9): Multi-Catch Common Superclass Binding
    // =========================================================================

    @Test
    public void testTask1_MultiCatchSecondExceptionTypeHandledCleanly_Positive() throws Exception {
        String code = """
            import java.lang.IllegalArgumentException;
            import java.lang.IllegalStateException;

            class MultiCatchPositive {
                static void function throwSecond() {
                    throw new IllegalStateException("state_err");
                }

                main() {
                    String caught = "none";
                    trying {
                        throwSecond();
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        caught = e.getMessage();
                    }
                    OceanOutput("CAUGHT=" + caught);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "MultiCatchPositive");
        String output = executeMainAndCaptureOutput(classes, "MultiCatchPositive");
        assertEquals("CAUGHT=state_err", output);
    }

    @Test
    public void testTask1_MultiCatchFirstExceptionTypeHandledCleanly_Positive() throws Exception {
        String code = """
            import java.lang.IllegalArgumentException;
            import java.lang.IllegalStateException;

            class MultiCatchFirstPositive {
                static void function throwFirst() {
                    throw new IllegalArgumentException("arg_err");
                }

                main() {
                    String caught = "none";
                    trying {
                        throwFirst();
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        caught = e.getMessage();
                    }
                    OceanOutput("CAUGHT=" + caught);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "MultiCatchFirstPositive");
        String output = executeMainAndCaptureOutput(classes, "MultiCatchFirstPositive");
        assertEquals("CAUGHT=arg_err", output);
    }

    // =========================================================================
    // TASK 2 (Bug 10): Array Cast (CHECKCAST [Ljava/lang/String;)
    // =========================================================================

    @Test
    public void testTask2_ArrayCastStringArrayNoClassFormatError_Positive() throws Exception {
        String code = """
            class ArrayCastPositive {
                main() {
                    Object raw = new String[2];
                    String[] arr = (String[]) raw;
                    arr[0] = "OceanLang";
                    OceanOutput("VAL=" + arr[0]);
                }
            }
            """;
        // Must compile, load into JVM classloader, and execute without ClassFormatError: Illegal class name "String[]"
        Map<String, byte[]> classes = compileToBytecodeMap(code, "ArrayCastPositive");
        String output = executeMainAndCaptureOutput(classes, "ArrayCastPositive");
        assertEquals("VAL=OceanLang", output);
    }

    @Test
    public void testTask2_ArrayCastPrimitiveMultiDimArray_Positive() throws Exception {
        String code = """
            class MultiDimArrayCastPositive {
                main() {
                    Object raw = new int[2];
                    int[] arr = (int[]) raw;
                    arr[0] = 42;
                    OceanOutput("NUM=" + arr[0]);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "MultiDimArrayCastPositive");
        String output = executeMainAndCaptureOutput(classes, "MultiDimArrayCastPositive");
        assertEquals("NUM=42", output);
    }

    @Test
    public void testTask2_ArrayCastIncompatibleTypeThrowsClassCastException_Negative() throws Exception {
        String code = """
            class IncompatibleArrayCastNegative {
                main() {
                    Object raw = new String[2];
                    Integer[] badArr = (Integer[]) raw;
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "IncompatibleArrayCastNegative");
        // Verify it produces valid bytecode that properly throws ClassCastException at runtime
        // instead of invalid bytecode crashing the JVM with ClassFormatError
        Exception ex = assertThrows(Exception.class, () -> executeMainAndCaptureOutput(classes, "IncompatibleArrayCastNegative"));
        Throwable cause = ex instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : ex;
        assertTrue(cause instanceof ClassCastException, "Expected ClassCastException, got: " + cause);
    }

    // =========================================================================
    // TASK 3 (Bug 11): Varargs Array Direct Passing vs Double-Wrapping
    // =========================================================================

    @Test
    public void testTask3_VarargsDirectArrayPassingNotDoubleWrapped_Positive() throws Exception {
        String code = """
            class VarargsUnwrapPositive {
                static int function countItems(String... items) {
                    return items.length;
                }

                main() {
                    String[] myArgs = new String[3];
                    myArgs[0] = "a";
                    myArgs[1] = "b";
                    myArgs[2] = "c";

                    int count = countItems(myArgs);
                    OceanOutput("COUNT=" + count);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "VarargsUnwrapPositive");
        String output = executeMainAndCaptureOutput(classes, "VarargsUnwrapPositive");
        // If double-wrapped, count would be 1 (single-element array containing myArgs)
        assertNotEquals("COUNT=1", output, "Array must NOT be double-wrapped inside another array");
        assertEquals("COUNT=3", output);
    }

    @Test
    public void testTask3_VarargsScalarElementsProperlyPacked_Positive() throws Exception {
        String code = """
            class VarargsScalarPositive {
                static int function countItems(String... items) {
                    return items.length;
                }

                main() {
                    int count = countItems("hello", "world");
                    OceanOutput("COUNT=" + count);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "VarargsScalarPositive");
        String output = executeMainAndCaptureOutput(classes, "VarargsScalarPositive");
        assertEquals("COUNT=2", output);
    }

    // =========================================================================
    // TASK 4 (Bug 12): Generic Type Parameter Cast ((T) obj)
    // =========================================================================

    @Test
    public void testTask4_GenericTypeParameterCastNoClassDefFoundError_Positive() throws Exception {
        String code = """
            class GenericCastPositive<T> {
                T function castItem(Object obj) {
                    return (T) obj;
                }

                main() {
                    GenericCastPositive<String> g = new GenericCastPositive<>();
                    Object raw = "SuccessCast";
                    String res = g.castItem(raw);
                    OceanOutput("RES=" + res);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "GenericCastPositive");
        String output = executeMainAndCaptureOutput(classes, "GenericCastPositive");
        assertEquals("RES=SuccessCast", output);
    }

    @Test
    public void testTask4_GenericBoundedTypeParameterCast_Positive() throws Exception {
        String code = """
            import java.lang.Number;

            class BoundedGenericCastPositive<T <: Number> {
                T function castNumber(Object obj) {
                    return (T) obj;
                }

                main() {
                    BoundedGenericCastPositive<Integer> b = new BoundedGenericCastPositive<>();
                    Object raw = 12345;
                    Number num = b.castNumber(raw);
                    OceanOutput("NUM=" + num);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "BoundedGenericCastPositive");
        String output = executeMainAndCaptureOutput(classes, "BoundedGenericCastPositive");
        assertEquals("NUM=12345", output);
    }

    // =========================================================================
    // TASK 5 (Bug 13): Generic Receiver Field Access Owner Stripping
    // =========================================================================

    @Test
    public void testTask5_GenericReceiverFieldAccessOwnerClean_Positive() throws Exception {
        String code = """
            class GenericBox<T> {
                T value;
                GenericBox(T value) {
                    this.value = value;
                }
            }

            class GenericReceiverFieldPositive {
                main() {
                    GenericBox<String> box = new GenericBox<>("BoxedString");
                    String val = box.value;
                    OceanOutput("VAL=" + val);
                }
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "GenericReceiverFieldPositive");
        String output = executeMainAndCaptureOutput(classes, "GenericReceiverFieldPositive");
        assertEquals("VAL=BoxedString", output);
    }

    @Test
    public void testTask5_GenericReceiverNonExistentField_Negative() {
        String code = """
            class GenericBoxNeg<T> {
                T value;
            }

            class GenericReceiverFieldNegative {
                main() {
                    GenericBoxNeg<String> box = new GenericBoxNeg<>();
                    var bad = box.nonExistentField;
                }
            }
            """;
        assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "GenericReceiverFieldNegative"));
    }
}
