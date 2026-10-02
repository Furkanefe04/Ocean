package ocean.compiler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class SliceAndTypeLookupOptimizationTest {

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
    public void testStringSliceExecution() throws Exception {
        String code = """
            public class StringSliceTest {
                public String function doSlice(String s) {
                    return s[0..2];
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("StringSliceTest", code);
        assertNotNull(clazz);
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Method m = clazz.getMethod("doSlice", String.class);
        // Ocean [0..2] is inclusive of end index (0, 1, 2) so substring(0, 3) -> "hel"
        Object res = m.invoke(instance, "hello");
        assertEquals("hel", res);
    }

    @Test
    public void testUnsupportedTypeSliceFailsCompilation() {
        String code = """
            public class Dummy {
                public int value = 42;
            }

            public class BadSliceTest {
                public void function test(Dummy d) {
                    value x = d[0..2];
                }
            }
            """;

        CompilationException ex = assertThrows(CompilationException.class, () ->
            CompilerTestHelper.compileToBytecodeMap(code, "BadSliceTest")
        );
        assertTrue(ex.getMessage().contains("does not support range slicing")
                || ex.getMessage().contains("SliceResolver")
                || ex.getMessage().contains("Dummy"),
            "Expected slice error message, got: " + ex.getMessage());
    }

    @Test
    public void testOverloadResolverSlicePreferences() {
        // String -> preferred method is substring
        OverloadResolver.SliceMethodResolution strRes = OverloadResolver.findSliceMethod("java/lang/String");
        assertNotNull(strRes);
        assertEquals("substring", strRes.methodName());

        // List -> preferred method is subList
        OverloadResolver.SliceMethodResolution listRes = OverloadResolver.findSliceMethod("java/util/List");
        assertNotNull(listRes);
        assertEquals("subList", listRes.methodName());
    }

    @Test
    public void testHasClassOptimizationAndNegativeCache() {
        // Valid classes
        assertTrue(OceanTypeSystem.hasClass("java.lang.String"));
        assertTrue(OceanTypeSystem.hasClass("java.util.ArrayList"));
        assertTrue(OceanTypeSystem.hasClass("ocean.compiler.OceanTypeSystem"));

        // Slash format
        assertTrue(OceanTypeSystem.hasClass("java/lang/String"));
        assertTrue(OceanTypeSystem.hasClass("java/util/Map"));

        // Nonexistent classes -> returns false quickly without throwing
        assertFalse(OceanTypeSystem.hasClass("com.nonexistent.FakeClassXYZ123"));
        assertFalse(OceanTypeSystem.hasClass("org.fake.package.NoSuchClass"));
    }
}
