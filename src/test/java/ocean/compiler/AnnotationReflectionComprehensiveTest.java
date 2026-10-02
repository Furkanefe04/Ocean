package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

public class AnnotationReflectionComprehensiveTest extends CompilerTestHelper {

    @Test
    @DisplayName("Custom annotation with default values and reflection query")
    public void testCustomAnnotationDefaultsAndReflection() throws Exception {
        String code = """
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.RUNTIME)
                public annotation ServiceInfo {
                    String name() default "DefaultService";
                    int version() default 1;
                }

                @ServiceInfo(name = "OrderService", version = 2)
                public class OrderManager {
                    @ServiceInfo(name = "processOrderMethod", version = 3)
                    public String function process() {
                        return "processed";
                    }
                }
                """;

        Class<?> clazz = compileAndLoad("OrderManager", code);
        assertNotNull(clazz);

        // Class-level annotation
        Annotation[] classAnnos = clazz.getAnnotations();
        assertTrue(classAnnos.length > 0, "Class should have annotations");
        
        Annotation serviceInfo = null;
        for (Annotation a : classAnnos) {
            if (a.annotationType().getSimpleName().equals("ServiceInfo")) {
                serviceInfo = a;
                break;
            }
        }
        assertNotNull(serviceInfo, "ServiceInfo annotation must be present on class at runtime");
        Method nameMethod = serviceInfo.annotationType().getMethod("name");
        Method versionMethod = serviceInfo.annotationType().getMethod("version");
        assertEquals("OrderService", nameMethod.invoke(serviceInfo));
        assertEquals(2, versionMethod.invoke(serviceInfo));

        // Method-level annotation
        Method processMethod = clazz.getMethod("process");
        Annotation methodAnno = null;
        for (Annotation a : processMethod.getAnnotations()) {
            if (a.annotationType().getSimpleName().equals("ServiceInfo")) {
                methodAnno = a;
                break;
            }
        }
        assertNotNull(methodAnno, "ServiceInfo annotation must be present on method at runtime");
        assertEquals("processOrderMethod", nameMethod.invoke(methodAnno));
        assertEquals(3, versionMethod.invoke(methodAnno));
    }

    @Test
    @DisplayName("Annotation with Class literal parameter")
    public void testAnnotationWithClassLiteral() throws Exception {
        String code = """
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.RUNTIME)
                public annotation TargetType {
                    Class targetClass();
                }

                @TargetType(targetClass = String.class)
                public class StringConsumer {
                    public static String function run() {
                        return "OK";
                    }
                }
                """;

        Class<?> clazz = compileAndLoad("StringConsumer", code);
        assertNotNull(clazz);

        Annotation[] annos = clazz.getAnnotations();
        Annotation targetTypeAnno = null;
        for (Annotation a : annos) {
            if (a.annotationType().getSimpleName().equals("TargetType")) {
                targetTypeAnno = a;
                break;
            }
        }
        assertNotNull(targetTypeAnno, "TargetType annotation must be present on StringConsumer");
        Method targetClassMethod = targetTypeAnno.annotationType().getMethod("targetClass");
        Object result = targetClassMethod.invoke(targetTypeAnno);
        assertEquals(String.class, result);
    }

    @Test
    @DisplayName("Annotation with nested annotation")
    public void testNestedAnnotation() throws Exception {
        String code = """
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.RUNTIME)
                public annotation Tag {
                    String value();
                }

                @Retention(RetentionPolicy.RUNTIME)
                public annotation MetaContainer {
                    Tag tag();
                }

                @MetaContainer(tag = @Tag("alpha"))
                public class TaggedService {
                    public static String function run() {
                        return "OK";
                    }
                }
                """;

        Class<?> clazz = compileAndLoad("TaggedService", code);
        assertNotNull(clazz);

        Annotation metaContainer = null;
        for (Annotation a : clazz.getAnnotations()) {
            if (a.annotationType().getSimpleName().equals("MetaContainer")) {
                metaContainer = a;
                break;
            }
        }
        assertNotNull(metaContainer, "MetaContainer annotation must be present");
        Method tagMethod = metaContainer.annotationType().getMethod("tag");
        Annotation tagAnno = (Annotation) tagMethod.invoke(metaContainer);
        assertNotNull(tagAnno);
        assertEquals("Tag", tagAnno.annotationType().getSimpleName());
        Method valMethod = tagAnno.annotationType().getMethod("value");
        assertEquals("alpha", valMethod.invoke(tagAnno));
    }
}