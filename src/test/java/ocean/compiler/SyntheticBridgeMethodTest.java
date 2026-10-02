package ocean.compiler;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SyntheticBridgeMethodTest {

    @Test
    public void testSyntheticBridgeMethodsReflectionAndBytecode() throws Exception {
        Path path = Path.of("examples/SyntheticBridgeMethodTest.ocean");
        String code = Files.readString(path);

        Map<String, byte[]> classMap = CompilerTestHelper.compileToBytecodeMap(code, "SyntheticBridgeMethodTest");
        assertNotNull(classMap);

        // Load classes via custom class loader
        ClassLoader cl = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                String internal = name.replace('.', '/');
                if (classMap.containsKey(internal)) {
                    byte[] bytes = classMap.get(internal);
                    return defineClass(name, bytes, 0, bytes.length);
                }
                return super.findClass(name);
            }
        };

        // 1. Verify SubClass has a synthetic bridge getPayload() -> Object
        Class<?> subClass = cl.loadClass("com.resources.SubClass");
        Method[] subMethods = subClass.getDeclaredMethods();
        boolean foundNormalGetPayload = false;
        boolean foundBridgeGetPayload = false;

        for (Method m : subMethods) {
            if (m.getName().equals("getPayload")) {
                if (m.getReturnType().equals(String.class) && !m.isBridge()) {
                    foundNormalGetPayload = true;
                } else if (m.getReturnType().equals(Object.class) && m.isBridge() && m.isSynthetic()) {
                    foundBridgeGetPayload = true;
                }
            }
        }
        assertTrue(foundNormalGetPayload, "SubClass must have direct String getPayload()");
        assertTrue(foundBridgeGetPayload, "SubClass must have synthetic bridge Object getPayload()");

        // 2. Verify StringLengthTransformer has synthetic bridge transform(Object) -> Object
        Class<?> transformerClass = cl.loadClass("com.resources.StringLengthTransformer");
        boolean foundBridgeTransform = false;
        for (Method m : transformerClass.getDeclaredMethods()) {
            if (m.getName().equals("transform") && m.isBridge() && m.isSynthetic()) {
                if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0].equals(Object.class)) {
                    foundBridgeTransform = true;
                    // Test reflection invocation on bridge method
                    Object instance = transformerClass.getDeclaredConstructor().newInstance();
                    Object bridgeRes = m.invoke(instance, "ReflectionTest");
                    assertEquals(14, bridgeRes, "Bridge transform method invocation result");
                }
            }
        }
        assertTrue(foundBridgeTransform, "StringLengthTransformer must have synthetic bridge transform(Object)");

        // 3. Verify NonEmptyStringValidator has synthetic bridge validate(Object) -> boolean
        Class<?> validatorClass = cl.loadClass("com.resources.NonEmptyStringValidator");
        boolean foundBridgeValidate = false;
        for (Method m : validatorClass.getDeclaredMethods()) {
            if (m.getName().equals("validate") && m.isBridge() && m.isSynthetic()) {
                if (m.getParameterTypes().length == 1 && m.getParameterTypes()[0].equals(Object.class)) {
                    foundBridgeValidate = true;
                    Object instance = validatorClass.getDeclaredConstructor().newInstance();
                    Object ok = m.invoke(instance, "Ocean");
                    assertEquals(true, ok, "Bridge validate method invocation result");
                }
            }
        }
        assertTrue(foundBridgeValidate, "NonEmptyStringValidator must have synthetic bridge validate(Object)");

        // 4. Verify FastIntSupplier has synthetic bridge supply() -> Object
        Class<?> supplierClass = cl.loadClass("com.resources.FastIntSupplier");
        boolean foundBridgeSupply = false;
        for (Method m : supplierClass.getDeclaredMethods()) {
            if (m.getName().equals("supply") && m.isBridge() && m.isSynthetic()) {
                if (m.getReturnType().equals(Object.class)) {
                    foundBridgeSupply = true;
                    Object instance = supplierClass.getDeclaredConstructor().newInstance();
                    Object supplied = m.invoke(instance);
                    assertEquals(999, supplied, "Bridge supply method invocation result");
                }
            }
        }
        assertTrue(foundBridgeSupply, "FastIntSupplier must have synthetic bridge supply() -> Object");

        // 5. Verify runtime execution of SyntheticBridgeMethodTest.main
        Class<?> testClass = cl.loadClass("com.resources.SyntheticBridgeMethodTest");
        Method main = testClass.getMethod("main", String[].class);

        PrintStream origOut = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(baos));
            main.invoke(null, (Object) new String[0]);
        } finally {
            System.setOut(origOut);
        }

        String output = baos.toString();
        assertTrue(output.contains("STATUS: PASSED"), "Expected STATUS: PASSED but got: " + output);
    }
}