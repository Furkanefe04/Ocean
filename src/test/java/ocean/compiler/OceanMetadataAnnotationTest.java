package ocean.compiler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OceanMetadataAnnotationTest {

    @Test
    public void testClassEmitsOceanMetadata() throws Exception {
        String code = """
            class SampleService {
                public int function calculate(int a, int b) {
                    return a + b;
                }
            }
            """;
        Class<?> clazz = CompilerTestHelper.compileAndLoad("SampleService", code);
        assertNotNull(clazz);

        Metadata meta = clazz.getAnnotation(Metadata.class);
        assertNotNull(meta, "Generated class must have @ocean.compiler.Metadata annotation");
        assertEquals("ocean", meta.language());
        assertEquals(CompilerConfig.COMPILER_VERSION, meta.compilerVersion());
        assertEquals(CompilerConfig.LANGUAGE_VERSION, meta.languageVersion());
        assertEquals(Metadata.KIND_CLASS, meta.kind());
    }

    @Test
    public void testDataClassEmitsOceanMetadata() throws Exception {
        String code = """
            data class Person(String name, int age)
            """;
        Class<?> clazz = CompilerTestHelper.compileAndLoad("Person", code);
        assertNotNull(clazz);

        Metadata meta = clazz.getAnnotation(Metadata.class);
        assertNotNull(meta, "Generated data class must have @ocean.compiler.Metadata annotation");
        assertEquals("ocean", meta.language());
        assertEquals(Metadata.KIND_DATA_CLASS, meta.kind());
    }

    @Test
    public void testInterfaceEmitsOceanMetadata() throws Exception {
        String code = """
            interface Greeter {
                void function greet(String name);
            }
            """;
        Class<?> clazz = CompilerTestHelper.compileAndLoad("Greeter", code);
        assertNotNull(clazz);

        Metadata meta = clazz.getAnnotation(Metadata.class);
        assertNotNull(meta, "Generated interface must have @ocean.compiler.Metadata annotation");
        assertEquals("ocean", meta.language());
        assertEquals(Metadata.KIND_INTERFACE, meta.kind());
    }

    @Test
    public void testEnumEmitsOceanMetadata() throws Exception {
        String code = """
            enum Direction {
                NORTH, SOUTH, EAST, WEST
            }
            """;
        Class<?> clazz = CompilerTestHelper.compileAndLoad("Direction", code);
        assertNotNull(clazz);

        Metadata meta = clazz.getAnnotation(Metadata.class);
        assertNotNull(meta, "Generated enum must have @ocean.compiler.Metadata annotation");
        assertEquals("ocean", meta.language());
        assertEquals(Metadata.KIND_ENUM, meta.kind());
    }
}
