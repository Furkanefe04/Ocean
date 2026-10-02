package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GenericSignatureVerificationTest extends CompilerTestHelper {

    @Test
    @DisplayName("OC-15: Class type parameters are reflected at runtime via cls.getTypeParameters()")
    void testClassTypeParametersReflection() throws Exception {
        String code = """
                package com.test.oc15;
                
                public class GenericBox<T> {
                    T value;
                    public void function setValue(T v) {
                        this.value = v;
                    }
                    public T function getValue() {
                        return this.value;
                    }
                }
                """;
        Class<?> cls = compileAndLoad("GenericBox", code);
        TypeVariable<?>[] tps = cls.getTypeParameters();
        assertEquals(1, tps.length, "Class should have exactly 1 type parameter");
        assertEquals("T", tps[0].getName());
    }

    @Test
    @DisplayName("OC-15: Generic field signature is reflected at runtime without GenericSignatureFormatError")
    void testFieldGenericTypeReflection() throws Exception {
        String code = """
                package com.test.oc15;
                
                import java.util.List;
                
                public class GenericRepo<T> {
                    List<T> items;
                    T singleItem;
                }
                """;
        Class<?> cls = compileAndLoad("GenericRepo", code);

        // 1. Parameterized field: List<T>
        Field itemsField = cls.getDeclaredField("items");
        Type itemsType = itemsField.getGenericType();
        assertInstanceOf(ParameterizedType.class, itemsType, "List<T> must be a ParameterizedType");
        ParameterizedType pt = (ParameterizedType) itemsType;
        assertEquals(List.class, pt.getRawType());
        Type[] args = pt.getActualTypeArguments();
        assertEquals(1, args.length);
        assertInstanceOf(TypeVariable.class, args[0]);
        assertEquals("T", ((TypeVariable<?>) args[0]).getName());

        // 2. Type variable field: T
        Field singleField = cls.getDeclaredField("singleItem");
        Type singleType = singleField.getGenericType();
        assertInstanceOf(TypeVariable.class, singleType, "T must be a TypeVariable");
        assertEquals("T", ((TypeVariable<?>) singleType).getName());
    }

    @Test
    @DisplayName("OC-15: Method generic return type and parameter types are reflected at runtime")
    void testMethodGenericSignatureReflection() throws Exception {
        String code = """
                package com.test.oc15;
                
                import java.util.List;
                
                public class GenericService<T> {
                    public void function process(T item) {
                    }
                    public List<T>? function listAll() {
                        return null;
                    }
                }
                """;
        Class<?> cls = compileAndLoad("GenericService", code);

        // 1. Parameter: process(T)
        Method processMethod = cls.getDeclaredMethod("process", Object.class);
        Type[] paramTypes = processMethod.getGenericParameterTypes();
        assertEquals(1, paramTypes.length);
        assertInstanceOf(TypeVariable.class, paramTypes[0], "Parameter must be TypeVariable T");
        assertEquals("T", ((TypeVariable<?>) paramTypes[0]).getName());

        // 2. Return: listAll() -> List<T>
        Method listMethod = cls.getDeclaredMethod("listAll");
        Type returnType = listMethod.getGenericReturnType();
        assertInstanceOf(ParameterizedType.class, returnType, "Return type must be ParameterizedType List<T>");
        ParameterizedType pt = (ParameterizedType) returnType;
        assertEquals(List.class, pt.getRawType());
        assertEquals("T", ((TypeVariable<?>) pt.getActualTypeArguments()[0]).getName());
    }

    @Test
    @DisplayName("OC-15: Nested generic types and wildcards produce valid JVM signatures")
    void testNestedGenericsAndWildcardsReflection() throws Exception {
        String code = """
                package com.test.oc15;
                
                import java.util.Map;
                import java.util.List;
                
                public class ComplexRegistry<T> {
                    Map<String, List<T>> cache;
                    List<? extends Number> numbers;
                }
                """;
        Class<?> cls = compileAndLoad("ComplexRegistry", code);

        // 1. Nested: Map<String, List<T>>
        Field cacheField = cls.getDeclaredField("cache");
        Type cacheType = cacheField.getGenericType();
        assertInstanceOf(ParameterizedType.class, cacheType);
        ParameterizedType cachePt = (ParameterizedType) cacheType;
        Type[] cacheArgs = cachePt.getActualTypeArguments();
        assertEquals(2, cacheArgs.length);
        assertEquals(String.class, cacheArgs[0]);
        assertInstanceOf(ParameterizedType.class, cacheArgs[1]);
        ParameterizedType listPt = (ParameterizedType) cacheArgs[1];
        assertEquals(List.class, listPt.getRawType());
        assertEquals("T", ((TypeVariable<?>) listPt.getActualTypeArguments()[0]).getName());

        // 2. Wildcard: List<? extends Number>
        Field numbersField = cls.getDeclaredField("numbers");
        Type numbersType = numbersField.getGenericType();
        assertInstanceOf(ParameterizedType.class, numbersType);
        ParameterizedType numPt = (ParameterizedType) numbersType;
        Type wildcardArg = numPt.getActualTypeArguments()[0];
        assertInstanceOf(WildcardType.class, wildcardArg);
        WildcardType wt = (WildcardType) wildcardArg;
        assertEquals(Number.class, wt.getUpperBounds()[0]);
    }
}
