package ocean.compiler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ExtensionMethodHierarchyTest {

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    private Map<String, byte[]> compileToBytecodeMap(String code, String className) {
        return CompilerTestHelper.compileToBytecodeMap(code, className);
    }

    private String executeMainAndCaptureOutput(Map<String, byte[]> classes, String className) throws Exception {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                String pathKey = name.replace('.', '/');
                if (classes.containsKey(pathKey)) {
                    byte[] bytes = classes.get(pathKey);
                    return defineClass(name, bytes, 0, bytes.length);
                }
                return super.findClass(name);
            }
        };

        String fqName = classes.containsKey(className + "/" + className) ? className + "." + className : className;
        Class<?> clazz = loader.loadClass(fqName);
        Method mainMethod;
        try {
            mainMethod = clazz.getMethod("main", String[].class);
        } catch (NoSuchMethodException e) {
            mainMethod = clazz.getMethod("main");
        }
        mainMethod.setAccessible(true);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.PrintStream originalOut = System.out;
        System.setOut(new java.io.PrintStream(baos));
        try {
            if (mainMethod.getParameterCount() == 1) {
                mainMethod.invoke(null, (Object) new String[0]);
            } else {
                mainMethod.invoke(null);
            }
        } finally {
            System.setOut(originalOut);
        }
        return baos.toString().trim();
    }

    @Test
    public void testInterfaceExtensionMethodOnImplementingClass() throws Exception {
        String code = """
            public interface Describable {
                public String function describe();
            }

            public class Item implements Describable {
                public variable name = "Sword";
                public function Item(String n) {
                    this.name = n;
                }
                @Override
                public String function describe() {
                    return this.name;
                }
            }

            public class Extensions {
                public String Describable.function fancyDescribe() {
                    return "*** " + this.describe() + " ***";
                }
            }

            class InterfaceExtTest {
                static main() {
                    Item item = new Item("Sword");
                    String res = item.fancyDescribe();
                    OceanOutput(res);
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "InterfaceExtTest");
        String output = executeMainAndCaptureOutput(classes, "InterfaceExtTest");
        assertEquals("*** Sword ***", output);
    }

    @Test
    public void testSuperclassExtensionMethodOnSubclass() throws Exception {
        String code = """
            public class Animal {
                public String function kind() {
                    return "Animal";
                }
            }

            public class Dog extends Animal {
                @Override
                public String function kind() {
                    return "Dog";
                }
            }

            public class AnimalExtensions {
                public String Animal.function shout() {
                    return this.kind().toUpperCase() + "!";
                }
            }

            class SuperclassExtTest {
                static main() {
                    Dog dog = new Dog();
                    String res = dog.shout();
                    OceanOutput(res);
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "SuperclassExtTest");
        String output = executeMainAndCaptureOutput(classes, "SuperclassExtTest");
        assertEquals("DOG!", output);
    }

    @Test
    public void testObjectExtensionMethodOnAnyType() throws Exception {
        String code = """
            public class ObjectExtensions {
                public String Object.function tag() {
                    return "[TAG:" + this.toString() + "]";
                }
            }

            public class Box {
                @Override
                public String function toString() {
                    return "MyBox";
                }
            }

            class ObjectExtTest {
                static main() {
                    Box box = new Box();
                    String res1 = box.tag();
                    String res2 = "Hello".tag();
                    OceanOutput(res1 + " | " + res2);
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "ObjectExtTest");
        String output = executeMainAndCaptureOutput(classes, "ObjectExtTest");
        assertEquals("[TAG:MyBox] | [TAG:Hello]", output);
    }

    @Test
    public void testSubclassExtensionOverridesSuperclassExtension() throws Exception {
        String code = """
            public class BaseNode {
                public String function name() { return "Base"; }
            }

            public class LeafNode extends BaseNode {
                @Override
                public String function name() { return "Leaf"; }
            }

            public class ExtDefinitions {
                public String BaseNode.function render() {
                    return "BASE_EXT(" + this.name() + ")";
                }

                public String LeafNode.function render() {
                    return "LEAF_EXT(" + this.name() + ")";
                }
            }

            class ExtOverrideTest {
                static main() {
                    LeafNode leaf = new LeafNode();
                    BaseNode base = new BaseNode();
                    OceanOutput(leaf.render() + " & " + base.render());
                }
            }
            """;

        Map<String, byte[]> classes = compileToBytecodeMap(code, "ExtOverrideTest");
        String output = executeMainAndCaptureOutput(classes, "ExtOverrideTest");
        assertEquals("LEAF_EXT(Leaf) & BASE_EXT(Base)", output);
    }
}