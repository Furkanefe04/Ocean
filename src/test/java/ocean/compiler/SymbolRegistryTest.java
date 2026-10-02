package ocean.compiler;

import ocean.compiler.symbol.ClassSymbol;
import ocean.compiler.symbol.FieldSymbol;
import ocean.compiler.symbol.MethodSymbol;
import ocean.compiler.symbol.SymbolRegistry;
import org.antlr.v4.runtime.CharStreams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SymbolRegistryTest {

    private SymbolRegistry registry;

    @BeforeEach
    public void setUp() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
        registry = CompilerRegistry.getSymbolRegistry();
    }

    @Test
    public void testClassSymbolRegistrationAndLookup() {
        ClassSymbol classSym = new ClassSymbol("com/ocean/test/User", "java/lang/Object", Opcodes.ACC_PUBLIC);
        classSym.addInterface("java/io/Serializable");
        classSym.addInterface("java/lang/Comparable");

        FieldSymbol idField = new FieldSymbol("com/ocean/test/User", "id", "I", null, Opcodes.ACC_PRIVATE, false, false);
        FieldSymbol nameField = new FieldSymbol("com/ocean/test/User", "name", "Ljava/lang/String;", null, Opcodes.ACC_PRIVATE, false, true);
        classSym.addField(idField);
        classSym.addField(nameField);

        MethodSymbol getIdMethod = new MethodSymbol("com/ocean/test/User", "getId", "()I", "int", Opcodes.ACC_PUBLIC, false, false);
        MethodSymbol setNameMethod = new MethodSymbol("com/ocean/test/User", "setName", "(Ljava/lang/String;)V", "void", Opcodes.ACC_PUBLIC, false, false);
        classSym.addMethod(getIdMethod);
        classSym.addMethod(setNameMethod);

        registry.registerClassSymbol(classSym);

        // 1. Exact match
        ClassSymbol resolved = registry.getClassSymbol("com/ocean/test/User");
        assertNotNull(resolved);
        assertEquals("User", resolved.getSimpleName());
        assertEquals("com/ocean/test", resolved.getPackageName());
        assertEquals("java/lang/Object", resolved.getSuperClassName());
        assertEquals(2, resolved.getInterfaces().size());

        // 2. Lookup with L...; descriptor format
        ClassSymbol descResolved = registry.getClassSymbol("Lcom/ocean/test/User;");
        assertNotNull(descResolved);
        assertSame(resolved, descResolved);

        // 3. Lookup with simple name
        ClassSymbol simpleResolved = registry.getClassSymbol("User");
        assertNotNull(simpleResolved);
        assertSame(resolved, simpleResolved);

        // 4. Field resolution
        FieldSymbol f1 = registry.findField("com/ocean/test/User", "id");
        assertNotNull(f1);
        assertEquals("I", f1.getDescriptor());
        assertFalse(f1.isMutable());

        // 5. Method resolution
        MethodSymbol m1 = registry.findMethod("com/ocean/test/User", "getId", "()I");
        assertNotNull(m1);
        assertEquals("int", m1.getGenericReturnType());
        assertFalse(m1.isStatic());

        List<MethodSymbol> methods = registry.findMethods("com/ocean/test/User", "setName");
        assertEquals(1, methods.size());
        assertEquals("(Ljava/lang/String;)V", methods.get(0).getDescriptor());
    }

    @Test
    public void testInheritedFieldLookup() {
        ClassSymbol baseSym = new ClassSymbol("com/ocean/test/Base", "java/lang/Object", Opcodes.ACC_PUBLIC);
        baseSym.addField(new FieldSymbol("com/ocean/test/Base", "baseField", "Ljava/lang/String;"));
        registry.registerClassSymbol(baseSym);

        ClassSymbol childSym = new ClassSymbol("com/ocean/test/Child", "com/ocean/test/Base", Opcodes.ACC_PUBLIC);
        childSym.addField(new FieldSymbol("com/ocean/test/Child", "childField", "I"));
        registry.registerClassSymbol(childSym);

        FieldSymbol childF = registry.findField("com/ocean/test/Child", "childField");
        assertNotNull(childF);
        assertEquals("I", childF.getDescriptor());

        FieldSymbol inheritedF = registry.findField("com/ocean/test/Child", "baseField");
        assertNotNull(inheritedF);
        assertEquals("Ljava/lang/String;", inheritedF.getDescriptor());
        assertEquals("com/ocean/test/Base", inheritedF.getOwnerInternalName());
    }

    @Test
    public void testClassHierarchyResolution() {
        ClassSymbol grandParent = new ClassSymbol("com/ocean/test/GP", "java/lang/Object", Opcodes.ACC_PUBLIC);
        ClassSymbol parent = new ClassSymbol("com/ocean/test/P", "com/ocean/test/GP", Opcodes.ACC_PUBLIC);
        ClassSymbol child = new ClassSymbol("com/ocean/test/C", "com/ocean/test/P", Opcodes.ACC_PUBLIC);

        registry.registerClassSymbol(grandParent);
        registry.registerClassSymbol(parent);
        registry.registerClassSymbol(child);

        List<String> hierarchy = registry.getClassHierarchy("com/ocean/test/C");
        assertEquals(List.of("com/ocean/test/C", "com/ocean/test/P", "com/ocean/test/GP", "java/lang/Object"), hierarchy);
    }

    @Test
    public void testCompilationIntegrationPopulatesSymbolRegistry() {
        String source = """
            class SymbolTestClass {
                int count = 10;
                public String function computeName(String prefix) {
                    return prefix + "_" + this.count;
                }
            }
            """;

        CompilationSession session = new CompilationSession();
        CompilationSession.setActiveSession(session);
        try {
            OceanLexer lexer = new OceanLexer(CharStreams.fromString(source));
            OceanParser parser = new OceanParser(OceanTokenStreamFactory.createTokenStream(lexer));
            OceanParser.ProgramContext tree = parser.program();

            PreScanner scanner = new PreScanner("SymbolTestClass.ocean", session);
            scanner.visit(tree);

            assertFalse(CompilerReporter.hasErrors(), "PreScanner should have no errors");

            // Verify SymbolTestClass symbol was populated in session's SymbolRegistry
            ClassSymbol classSym = session.symbolRegistry.getClassSymbol("SymbolTestClass");
            assertNotNull(classSym, "SymbolTestClass ClassSymbol must be present");
            assertNotNull(classSym.getField("count"), "SymbolTestClass must have 'count' field");
            assertNotNull(classSym.getMethod("computeName", "(Ljava/lang/String;)Ljava/lang/String;"), "SymbolTestClass must have computeName()");
        } finally {
            CompilationSession.clearActiveSession();
        }
    }
}