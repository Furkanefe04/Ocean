package ocean.compiler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PackageCastResolutionTest {

    @BeforeEach
    public void setUp() {
        CompilerRegistry.clearAll();
        CompilationSession.clearActiveSession();
        CompilationSession session = new CompilationSession();
        CompilationSession.setActiveSession(session);
    }

    @AfterEach
    public void tearDown() {
        CompilerRegistry.clearAll();
        CompilationSession.clearActiveSession();
    }

    @Test
    public void testStandardClassPathResolution() {
        String res = OceanTypeSystem.resolveInternalClassName("String", "com/example/Service");
        assertEquals("java/lang/String", res);

        String resInt = OceanTypeSystem.resolveInternalClassName("Integer", "com/example/Service");
        assertEquals("java/lang/Integer", resInt);
    }

    @Test
    public void testAlreadyQualifiedNamePreserved() {
        String res = OceanTypeSystem.resolveInternalClassName("com/foo/Bar", "com/example/Service");
        assertEquals("com/foo/Bar", res);
    }

    @Test
    public void testSamePackageClassResolution() {
        CompilerRegistry.globalSuperClassRegistry.put("com/example/LocalHelper", "java/lang/Object");
        String res = OceanTypeSystem.resolveInternalClassName("LocalHelper", "com/example/Service");
        assertEquals("com/example/LocalHelper", res);
    }

    @Test
    public void testDefaultPackageClassNotPrependedWithCurrentPackage() {
        // Class exists in root/default package
        CompilerRegistry.globalSuperClassRegistry.put("RootHelper", "java/lang/Object");
        // Current class is in "com/example/Service"
        String res = OceanTypeSystem.resolveInternalClassName("RootHelper", "com/example/Service");
        // Must NOT be "com/example/RootHelper"
        assertEquals("RootHelper", res);
    }

    @Test
    public void testWildcardImportResolution() {
        CompilationSession session = CompilationSession.getActiveSession();
        assertNotNull(session);
        session.activeWildcards.add("com.external.tools");

        CompilerRegistry.globalSuperClassRegistry.put("com/external/tools/ExternalTool", "java/lang/Object");

        String res = OceanTypeSystem.resolveInternalClassName("ExternalTool", "com/example/Service");
        assertEquals("com/external/tools/ExternalTool", res);
    }

    @Test
    public void testSymbolRegistryResolution() {
        ocean.compiler.symbol.ClassSymbol sym = new ocean.compiler.symbol.ClassSymbol("org/legacy/OldSymbol");
        CompilerRegistry.registerClassSymbol(sym);

        String res = OceanTypeSystem.resolveInternalClassName("OldSymbol", "com/example/Service");
        assertEquals("org/legacy/OldSymbol", res);
    }

    @Test
    public void testFallbackWhenNotFound() {
        // If not found anywhere, for backward compatibility it prefixes the current package
        String res = OceanTypeSystem.resolveInternalClassName("UnknownClass", "com/example/Service");
        assertEquals("com/example/UnknownClass", res);

        // If current class has no package, it returns simple name
        String resRoot = OceanTypeSystem.resolveInternalClassName("UnknownClass", "RootService");
        assertEquals("UnknownClass", resRoot);
    }

    @Test
    public void testCompilerEndToEndCast() throws Exception {
        String code = """
            package com.test;

            public class Base {
                public String function greet() {
                    return "hello";
                }

                public String function testCast(Object obj) {
                    value b = (Base) obj;
                    return b.greet();
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("Base", code);
        assertNotNull(clazz);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        java.lang.reflect.Method m = clazz.getMethod("testCast", Object.class);
        Object result = m.invoke(instance, instance);
        assertEquals("hello", result);
    }
}
