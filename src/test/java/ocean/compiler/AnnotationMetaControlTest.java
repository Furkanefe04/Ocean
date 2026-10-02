package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AnnotationMetaControlTest {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
        ClassMetadataCache.clearCaches();
    }

    private Map<String, byte[]> compile(String code, String className) {
        try {
            return CompilerTestHelper.compileToBytecodeMap(code, className);
        } catch (Exception e) {
            for (var err : CompilerReporter.getMessages()) {
                System.err.println("COMPILER ERROR: " + err.toString());
            }
            throw e;
        }
    }

    private boolean hasBytecode(Map<String, byte[]> classes, String className) {
        if (classes.containsKey(className)) return true;
        if (classes.containsKey(className + "/" + className)) return true;
        for (String k : classes.keySet()) {
            if (k.endsWith("/" + className) || k.equals(className)) return true;
        }
        return false;
    }

    private Class<?> loadClass(Map<String, byte[]> classes, String className) throws Exception {
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
        return loader.loadClass(fqName);
    }

    @Test
    public void testValidAnnotationTargetMethodOnMethodSucceeds() {
        String code = """
                import java.lang.annotation.Target;
                import java.lang.annotation.ElementType;

                @Target(ElementType.METHOD)
                public annotation OnlyMethod {
                    String desc() default "ok";
                }

                public class TargetSuccessTest {
                    @OnlyMethod(desc = "hello")
                    public void function run() {
                        OceanOutput("method ok");
                    }

                    main() {
                        new TargetSuccessTest().run();
                    }
                }
                """;
        Map<String, byte[]> bytecode = compile(code, "TargetSuccessTest");
        assertNotNull(bytecode);
        assertTrue(hasBytecode(bytecode, "TargetSuccessTest"));
    }

    @Test
    public void testInvalidAnnotationTargetMethodOnClassThrowsError() {
        String code = """
                import java.lang.annotation.Target;
                import java.lang.annotation.ElementType;

                @Target(ElementType.METHOD)
                public annotation OnlyMethod {
                    String desc() default "ok";
                }

                @OnlyMethod(desc = "bad")
                public class TargetClassFailTest {
                    main() {}
                }
                """;
        assertThrows(CompilationException.class, () -> compile(code, "TargetClassFailTest"));
    }

    @Test
    public void testInvalidAnnotationTargetFieldOnMethodThrowsError() {
        String code = """
                import java.lang.annotation.Target;
                import java.lang.annotation.ElementType;

                @Target(ElementType.FIELD)
                public annotation OnlyField {
                    int id() default 1;
                }

                public class TargetMethodFailTest {
                    @OnlyField(id = 5)
                    public void function run() {}

                    main() {}
                }
                """;
        assertThrows(CompilationException.class, () -> compile(code, "TargetMethodFailTest"));
    }

    @Test
    public void testInvalidAnnotationMemberTypeThrowsError() {
        String code = """
                public annotation BadMemberTypeAnno {
                    Object badMethod();
                }

                public class InvalidMemberTypeTest {
                    main() {}
                }
                """;
        assertThrows(CompilationException.class, () -> compile(code, "InvalidMemberTypeTest"));
    }

    @Test
    public void testMultidimensionalArrayMemberTypeThrowsError() {
        String code = """
                public annotation Bad2DArrayAnno {
                    int[][] grid();
                }

                public class Invalid2DArrayTest {
                    main() {}
                }
                """;
        assertThrows(CompilationException.class, () -> compile(code, "Invalid2DArrayTest"));
    }

    @Test
    public void testValidAnnotationMemberTypesSucceeds() {
        String code = """
                public enum Priority {
                    LOW, MEDIUM, HIGH
                }

                public annotation ValidTypesAnno {
                    int count() default 10;
                    double factor() default 2.5;
                    bool active() default true;
                    String label() default "defaultLabel";
                    Priority priority() default Priority.MEDIUM;
                    String[] tags() default {"tag1", "tag2"};
                }

                @ValidTypesAnno(count = 42, label = "custom", priority = Priority.HIGH, tags = {"alpha", "beta"})
                public class ValidTypesTest {
                    main() {
                        OceanOutput("valid types ok");
                    }
                }
                """;
        Map<String, byte[]> bytecode = compile(code, "ValidTypesTest");
        assertNotNull(bytecode);
        assertTrue(hasBytecode(bytecode, "ValidTypesTest"));
    }

    @Test
    public void testMissingRequiredAnnotationParameterThrowsError() {
        String code = """
                public annotation RequiredParamAnno {
                    String author();
                    int version() default 1;
                }

                @RequiredParamAnno(version = 2)
                public class MissingParamTest {
                    main() {}
                }
                """;
        assertThrows(CompilationException.class, () -> compile(code, "MissingParamTest"));
    }

    @Test
    public void testUnknownAnnotationParameterThrowsError() {
        String code = """
                public annotation SimpleAnno {
                    String name() default "none";
                }

                @SimpleAnno(name = "test", nonExistentParam = 123)
                public class UnknownParamTest {
                    main() {}
                }
                """;
        assertThrows(CompilationException.class, () -> compile(code, "UnknownParamTest"));
    }

    @Test
    public void testDuplicateNonRepeatableAnnotationThrowsError() {
        String code = """
                public annotation SingleOnly {
                    String value() default "x";
                }

                @SingleOnly("first")
                @SingleOnly("second")
                public class DuplicateAnnoTest {
                    main() {}
                }
                """;
        assertThrows(CompilationException.class, () -> compile(code, "DuplicateAnnoTest"));
    }

    @Test
    public void testRetentionSourceNotPresentInBytecodeReflection() throws Exception {
        String code = """
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.SOURCE)
                public annotation SourceOnlyAnno {
                    String value() default "source_only";
                }

                @SourceOnlyAnno("invisible_at_runtime")
                public class SourceRetentionTest {
                    main() {}
                }
                """;
        Map<String, byte[]> bytecode = compile(code, "SourceRetentionTest");
        assertNotNull(bytecode);

        Class<?> clazz = loadClass(bytecode, "SourceRetentionTest");
        Annotation[] annotations = clazz.getAnnotations();
        boolean found = false;
        for (Annotation a : annotations) {
            if (a.annotationType().getName().contains("SourceOnlyAnno")) {
                found = true;
                break;
            }
        }
        assertFalse(found, "SOURCE retention annotation should not be visible at runtime");
    }
}
