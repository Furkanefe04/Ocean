package ocean.compiler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ParameterAnnotationsReflectionTest extends CompilerTestHelper {

    @Test
    @DisplayName("Method and constructor parameter annotations emitted to bytecode and accessible via reflection")
    public void testMethodAndConstructorParameterAnnotationsReflection() throws Exception {
        String code = """
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.RUNTIME)
                public annotation NotEmpty {
                }

                @Retention(RetentionPolicy.RUNTIME)
                public annotation ParamConfig {
                    String alias() default "";
                    int level() default 1;
                }

                public class UserService {
                    public UserService(@NotEmpty String initialRole) {
                    }

                    public String function updateUser(@NotEmpty String userId, @ParamConfig(alias = "accessLvl", level = 9) int level) {
                        return userId + ":" + level;
                    }
                }
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("UserService", code);
        Class<?> userClass = classes.get("UserService");
        assertNotNull(userClass, "UserService class must be compiled and loaded");

        // 1. Method parameter annotations
        Method updateMethod = userClass.getMethod("updateUser", String.class, int.class);
        Annotation[][] methodParamAnnos = updateMethod.getParameterAnnotations();
        assertEquals(2, methodParamAnnos.length, "updateUser must have 2 parameter annotation slots");

        // Parameter 0: @NotEmpty
        assertTrue(methodParamAnnos[0].length >= 1, "Parameter 0 must have at least one annotation");
        boolean foundNotEmpty = false;
        for (Annotation a : methodParamAnnos[0]) {
            if ("NotEmpty".equals(a.annotationType().getSimpleName())) {
                foundNotEmpty = true;
                break;
            }
        }
        assertTrue(foundNotEmpty, "Parameter 0 must be annotated with @NotEmpty");

        // Parameter 1: @ParamConfig(alias = "accessLvl", level = 9)
        assertTrue(methodParamAnnos[1].length >= 1, "Parameter 1 must have at least one annotation");
        Annotation paramConfigAnno = null;
        for (Annotation a : methodParamAnnos[1]) {
            if ("ParamConfig".equals(a.annotationType().getSimpleName())) {
                paramConfigAnno = a;
                break;
            }
        }
        assertNotNull(paramConfigAnno, "Parameter 1 must be annotated with @ParamConfig");
        Method aliasMethod = paramConfigAnno.annotationType().getMethod("alias");
        Method levelMethod = paramConfigAnno.annotationType().getMethod("level");
        assertEquals("accessLvl", aliasMethod.invoke(paramConfigAnno));
        assertEquals(9, levelMethod.invoke(paramConfigAnno));

        // 2. Constructor parameter annotations
        Constructor<?> ctor = userClass.getConstructor(String.class);
        Annotation[][] ctorParamAnnos = ctor.getParameterAnnotations();
        assertEquals(1, ctorParamAnnos.length, "Constructor must have 1 parameter annotation slot");
        assertTrue(ctorParamAnnos[0].length >= 1, "Constructor param 0 must have an annotation");
        assertEquals("NotEmpty", ctorParamAnnos[0][0].annotationType().getSimpleName());
    }

    @Test
    @DisplayName("Data class constructor parameter annotations preserved in bytecode")
    public void testDataClassParameterAnnotationsReflection() throws Exception {
        String code = """
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.RUNTIME)
                public annotation RequiredField {
                    String message() default "must not be null";
                }

                public data class AccountDto(@RequiredField(message = "Account name required") String accountName, int balance);
                """;

        Map<String, Class<?>> classes = compileAndLoadAll("AccountDto", code);
        Class<?> dtoClass = classes.get("AccountDto");
        assertNotNull(dtoClass, "AccountDto must be compiled and loaded");

        Constructor<?> ctor = dtoClass.getConstructor(String.class, int.class);
        Annotation[][] ctorParamAnnos = ctor.getParameterAnnotations();
        assertEquals(2, ctorParamAnnos.length, "AccountDto constructor has 2 parameters");

        assertTrue(ctorParamAnnos[0].length >= 1, "Param 0 of AccountDto must have @RequiredField");
        Annotation reqAnno = ctorParamAnnos[0][0];
        assertEquals("RequiredField", reqAnno.annotationType().getSimpleName());
        Method msgMethod = reqAnno.annotationType().getMethod("message");
        assertEquals("Account name required", (String) msgMethod.invoke(reqAnno));
    }
}
