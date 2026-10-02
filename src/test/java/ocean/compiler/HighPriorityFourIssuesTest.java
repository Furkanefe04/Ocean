package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class HighPriorityFourIssuesTest extends CompilerTestHelper {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
        ClassMetadataCache.clearCaches();
    }

    // =========================================================================
    // OC-15: Invalid Signature attribute & Missing Type Arguments
    // =========================================================================

    @Test
    @DisplayName("OC-15: Generic class and field bytecode signatures are valid and readable via reflection")
    public void testOC15_GenericClassAndFieldSignatures() throws Exception {
        String code = """
            public class GenericContainer<T> {
                T item;
                void function setItem(T val) {
                    item = val;
                }
                T function getItem() {
                    return item;
                }
            }
            """;
        Map<String, Class<?>> classes = compileAndLoadAll("GenericContainer", code);
        Class<?> containerClass = classes.get("GenericContainer");
        assertNotNull(containerClass, "GenericContainer class should be loaded");

        // Verify class type parameters
        TypeVariable<?>[] typeParams = containerClass.getTypeParameters();
        assertEquals(1, typeParams.length, "Should have 1 type parameter");
        assertEquals("T", typeParams[0].getName());

        // Verify field generic signature
        Field itemField = containerClass.getDeclaredField("item");
        Type genericFieldType = itemField.getGenericType();
        assertInstanceOf(TypeVariable.class, genericFieldType, "Field generic type should be TypeVariable");
        assertEquals("T", ((TypeVariable<?>) genericFieldType).getName());
    }

    @Test
    @DisplayName("OC-15: Generic subclass preserves generic superclass signature")
    public void testOC15_GenericSubclassSignature() throws Exception {
        String code = """
            import java.lang.String;

            public class BaseBox<T> {
                T content;
            }

            public class StringBox extends BaseBox<String> {
            }
            """;
        Map<String, Class<?>> classes = compileAndLoadAll("StringBox", code);
        Class<?> stringBoxClass = classes.get("StringBox");
        assertNotNull(stringBoxClass, "StringBox class should be loaded");

        Type superclass = stringBoxClass.getGenericSuperclass();
        assertInstanceOf(ParameterizedType.class, superclass, "Superclass should be ParameterizedType");
        ParameterizedType pt = (ParameterizedType) superclass;
        assertEquals(1, pt.getActualTypeArguments().length);
        assertEquals(String.class, pt.getActualTypeArguments()[0]);
    }

    // =========================================================================
    // OC-16: Null to primitive conversion throws NPE instead of silent 0
    // =========================================================================

    @Test
    @DisplayName("OC-16: Unboxing null to primitive int throws NullPointerException")
    public void testOC16_UnboxingNullThrowsNPE() throws Exception {
        String code = """
            import java.lang.Integer;

            public class UnboxNpeTest {
                public static int function unbox(Integer? val) {
                    int x = (int) val;
                    return x;
                }
            }
            """;
        Class<?> clazz = compileAndLoad("UnboxNpeTest", code);
        Method unboxMethod = clazz.getMethod("unbox", Integer.class);

        // Calling unbox with non-null succeeds
        assertEquals(42, unboxMethod.invoke(null, 42));

        // Calling unbox with null throws NullPointerException
        InvocationTargetException ex = assertThrows(InvocationTargetException.class, () -> unboxMethod.invoke(null, new Object[]{null}));
        assertInstanceOf(NullPointerException.class, ex.getCause(), "Unboxing null should throw NullPointerException");
    }

    @Test
    @DisplayName("OC-16: Using ?? 0 safely handles null without throwing NPE")
    public void testOC16_NullCoalescingDefaultSafe() throws Exception {
        String code = """
            import java.lang.Integer;

            public class SafeUnboxTest {
                public static int function safeUnbox(Integer? val) {
                    int x = val ?? 0;
                    return x;
                }
            }
            """;
        Class<?> clazz = compileAndLoad("SafeUnboxTest", code);
        Method safeMethod = clazz.getMethod("safeUnbox", Integer.class);

        assertEquals(10, safeMethod.invoke(null, 10));
        assertEquals(0, safeMethod.invoke(null, new Object[]{null}));
    }

    // =========================================================================
    // OC-17: Sealed hierarchy permitted subclass package mismatch check
    // =========================================================================

    @Test
    @DisplayName("OC-17: Sealed class with permitted subclass in different package reports compile error")
    public void testOC17_SealedPackageMismatch_ReportsError() {
        String code = """
            package com.alpha;

            public sealed class Shape restricts com.beta.Circle {
            }

            package com.beta;

            public final class Circle extends com.alpha.Shape {
            }
            """;
        CompilationException ex = assertThrows(CompilationException.class, () -> compileToBytecodeMap(code, "com/alpha/Shape"));
        assertTrue(ex.getMessage().contains("must belong to the same package")
                || CompilerReporter.getMessages().stream().anyMatch(m -> m.text().contains("must belong to the same package")),
                "Should report package mismatch error for sealed class permitted subclass");
    }

    @Test
    @DisplayName("OC-17: Sealed class with permitted subclass in same package compiles successfully")
    public void testOC17_SealedSamePackage_Succeeds() throws Exception {
        String code = """
            package com.geometry;

            public sealed class Shape restricts Circle {
            }

            public final class Circle extends Shape {
            }
            """;
        Map<String, byte[]> classes = compileToBytecodeMap(code, "com/geometry/Shape");
        assertNotNull(classes.get("com/geometry/Shape"));
        assertNotNull(classes.get("com/geometry/Circle"));
    }

    // =========================================================================
    // OC-18: Constant folder literal preservation
    // =========================================================================

    @Test
    @DisplayName("OC-18: Float literal arithmetic produces float, not double")
    public void testOC18_FloatLiteralPreservesType() throws Exception {
        String code = """
            public class FloatMathTest {
                public static float function compute() {
                    float f = 1.5F + 2.5F;
                    return f;
                }
            }
            """;
        Class<?> clazz = compileAndLoad("FloatMathTest", code);
        Method m = clazz.getMethod("compute");
        Object result = m.invoke(null);
        assertInstanceOf(Float.class, result);
        assertEquals(4.0F, (Float) result, 0.0001F);
    }

    @Test
    @DisplayName("OC-18: 32-bit integer arithmetic wraps around without eager promotion to Long/BigDecimal")
    public void testOC18_IntArithmeticOverflowWrapsAround() throws Exception {
        String code = """
            public class IntOverflowTest {
                public static int function overflow() {
                    int x = 2147483647 + 1;
                    return x;
                }
            }
            """;
        Class<?> clazz = compileAndLoad("IntOverflowTest", code);
        Method m = clazz.getMethod("overflow");
        Object result = m.invoke(null);
        assertInstanceOf(Integer.class, result);
        assertEquals(-2147483648, (Integer) result, "2147483647 + 1 in 32-bit int arithmetic wraps to -2147483648");
    }
}
