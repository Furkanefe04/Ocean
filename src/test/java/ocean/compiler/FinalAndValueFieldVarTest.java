package ocean.compiler;

import ocean.compiler.ir.IRCompilationUnit;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ocean.compiler.OceanLexer;
import ocean.compiler.OceanParser;
import static org.junit.jupiter.api.Assertions.*;

public class FinalAndValueFieldVarTest {

    @BeforeEach
    public void setup() {
        CompilerReporter.clear();
        CompilerRegistry.clearAll();
    }

    private CompilationSession lastSession;

    private boolean compileCode(String code) {
        lastSession = new CompilationSession();
        CompilationSession.setActiveSession(lastSession);
        try {
            OceanLexer lexer = new OceanLexer(CharStreams.fromString(code));
            lexer.removeErrorListeners();
            OceanParser parser = new OceanParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();

            ParseTree tree = parser.program();

            PreScanner scanner = new PreScanner("Test.ocean", lastSession);
            scanner.visit(tree);

            SymbolTable symTable = new SymbolTable();
            IRGenerator generator = new IRGenerator("Test.ocean", null, java.util.Collections.emptyMap(), symTable);
            IRCompilationUnit cu = (IRCompilationUnit) generator.visit(tree);

            IRSemanticAnalyzer analyzer = new IRSemanticAnalyzer("Test.ocean", lastSession, java.util.Collections.emptyMap(), java.util.Collections.emptyList(), java.util.Collections.emptyMap(), java.util.Collections.emptyList(), new SymbolTable());
            cu.accept(analyzer);
            return parser.getNumberOfSyntaxErrors() > 0 || analyzer.hasErrors() || CompilerReporter.hasErrors();
        } finally {
            CompilationSession.clearActiveSession();
        }
    }

    @Test
    public void testFinalTypeLocalVariableAndFieldSuccess() {
        String code = """
            class Test {
                final int fieldConst = 42;
                value fieldVal = 100;

                void function test() {
                    final int a = 10;
                    value b = 20;
                    int c = 30;
                    variable d = 40;
                }
            }
            """;
        assertFalse(compileCode(code), "Should compile without errors. Messages: " + (lastSession != null ? lastSession.messages : "none"));
    }

    @Test
    public void testReassignFinalLocalVariableFails() {
        String code = """
            class Test {
                function test() {
                    final int a = 10;
                    a = 20;
                }
            }
            """;
        assertTrue(compileCode(code), "Reassigning final variable 'a' should fail");
    }

    @Test
    public void testReassignValueLocalVariableFails() {
        String code = """
            class Test {
                function test() {
                    value b = 50;
                    b = 60;
                }
            }
            """;
        assertTrue(compileCode(code), "Reassigning value variable 'b' should fail");
    }

    @Test
    public void testFinalWithoutTypeFailsParser() {
        String code = """
            class Test {
                function test() {
                    final a = 10;
                }
            }
            """;
        assertTrue(compileCode(code), "'final' without explicit type should fail to parse/compile");
    }

    @Test
    public void testValueWithTypeFailsParser() {
        String code = """
            class Test {
                function test() {
                    value int b = 50;
                }
            }
            """;
        assertTrue(compileCode(code), "'value' with explicit type should fail to parse/compile");
    }

    @Test
    public void testVariableWithTypeFailsParser() {
        String code = """
            class Test {
                function test() {
                    variable int c = 30;
                }
            }
            """;
        assertTrue(compileCode(code), "'variable' with explicit type should fail to parse/compile");
    }
}
