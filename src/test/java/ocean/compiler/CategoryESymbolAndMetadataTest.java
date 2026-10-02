package ocean.compiler;

import ocean.compiler.symbol.ClassSymbol;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CategoryESymbolAndMetadataTest extends CompilerTestHelper {

    @Test
    @DisplayName("E1 (#51): getVarargParamType with empty params list returns null safely")
    public void testGetVarargParamTypeEmptyList() throws Exception {
        Method m = OverloadResolver.class.getDeclaredMethod("getVarargParamType", List.class, int.class);
        m.setAccessible(true);
        Object result = m.invoke(null, Collections.emptyList(), 0);
        assertNull(result, "Empty params list must safely return null instead of throwing IndexOutOfBoundsException");

        Object negResult = m.invoke(null, Collections.emptyList(), -1);
        assertNull(negResult, "Negative index with empty params list must safely return null");
    }

    @Test
    @DisplayName("E2 (#52): isVarargs with null owner does not treat random array param methods as varargs")
    public void testIsVarargsNullOwnerDoesNotTreatNormalArrayAsVarargs() throws Exception {
        Method m = OverloadResolver.class.getDeclaredMethod("isVarargs", String.class, String.class, String.class);
        m.setAccessible(true);
        // Method with String[] param that is NOT declared with varargs
        boolean isVararg = (Boolean) m.invoke(null, null, "processItems", "([Ljava/lang/String;)V");
        assertFalse(isVararg, "Random array parameter method must not be falsely treated as varargs when owner is null");
    }

    @Test
    @DisplayName("E3 (#53): SymbolTable scope underflow throws CompilationException")
    public void testSymbolTableScopeUnderflowThrowsCompilationException() {
        SymbolTable table = new SymbolTable();
        // Default table has 1 scope. Calling exitScope() triggers underflow
        CompilationException ex = assertThrows(CompilationException.class, table::exitScope);
        assertTrue(ex.getMessage().contains("scope stack underflow"),
                "Expected scope underflow error message, got: " + ex.getMessage());
    }

    @Test
    @DisplayName("E4 (#54): ClassMetadataCache.isClass and isInterface query ClassSymbol for in-memory types")
    public void testClassMetadataCacheResolvesFromSymbols() {
        String testClassName = "ocean/test/InMemoryClass";
        String testInterfaceName = "ocean/test/InMemoryInterface";

        ClassSymbol classSym = new ClassSymbol(testClassName);
        classSym.setInterface(false);

        ClassSymbol ifaceSym = new ClassSymbol(testInterfaceName);
        ifaceSym.setInterface(true);

        CompilerRegistry.registerClassSymbol(classSym);
        CompilerRegistry.registerClassSymbol(ifaceSym);

        try {
            assertTrue(ClassMetadataCache.isClass(testClassName),
                    "InMemoryClass should be recognized as a class via ClassSymbol without ClassNotFoundException");
            assertFalse(ClassMetadataCache.isInterface(testClassName),
                    "InMemoryClass should not be recognized as an interface");

            assertTrue(ClassMetadataCache.isInterface(testInterfaceName),
                    "InMemoryInterface should be recognized as an interface via ClassSymbol without ClassNotFoundException");
            assertFalse(ClassMetadataCache.isClass(testInterfaceName),
                    "InMemoryInterface should not be recognized as a class");
        } finally {
            CompilerRegistry.clearAll();
        }
    }
}
