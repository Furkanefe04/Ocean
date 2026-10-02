package ocean.compiler;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

public class DeepGenericInferenceTest {

    @Test
    public void testNestedMapListGenericInference() throws Exception {
        String code = """
          
            public class Person {
                public String name;
                public function Person(String name) {
                    this.name = name;
                }
                public String function getName() {
                    return this.name;
                }
            }

            public class NestedTest {
                public static String function testChained() {
                    OceanMap<String, OceanList<Person>> map = new OceanMap();
                    OceanList<Person> team = new OceanList();
                    team.add(new Person("Alice"));
                    team.add(new Person("Bob"));
                    map.put("devs", team);

                    // Chained access without manual cast: map.get("devs").get(1).getName()
                    return map.get("devs").get(1).getName();
                }

                public static String function testListIndex() {
                    OceanMap<String, OceanList<Person>> map = new OceanMap();
                    OceanList<Person> team = new OceanList();
                    team.add(new Person("Charlie"));
                    map.put("ops", team);

                    // List index access: map.get("ops")[0].getName()
                    return map.get("ops")[0].getName();
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("NestedTest", code);
        Method m1 = clazz.getMethod("testChained");
        m1.setAccessible(true);
        assertEquals("Bob", m1.invoke(null));

        Method m2 = clazz.getMethod("testListIndex");
        m2.setAccessible(true);
        assertEquals("Charlie", m2.invoke(null));
    }

    @Test
    public void testNestedMapMapGenericInference() throws Exception {
        String code = """
            import ocean.stdlib.OceanMap;

            public class NestedMapTest {
                public static int function testNestedMap() {
                    OceanMap<String, OceanMap<String, Integer>> scoreBoard = new OceanMap();
                    OceanMap<String, Integer> studentScores = new OceanMap();
                    studentScores.put("math", 95);
                    studentScores.put("physics", 90);
                    scoreBoard.put("Furkan", studentScores);

                    // Chained map access: scoreBoard.get("Furkan").get("math")
                    return scoreBoard.get("Furkan").get("math") + 5;
                }
            }
            """;

        Class<?> clazz = CompilerTestHelper.compileAndLoad("NestedMapTest", code);
        Method m = clazz.getMethod("testNestedMap");
        m.setAccessible(true);
        assertEquals(100, m.invoke(null));
    }
}