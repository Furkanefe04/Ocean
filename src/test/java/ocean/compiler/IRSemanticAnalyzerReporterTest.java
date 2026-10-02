package ocean.compiler;

import ocean.compiler.ir.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class IRSemanticAnalyzerReporterTest {

    private IRSemanticAnalyzer analyzer;
    private CompilationSession session;

    @BeforeEach
    public void setUp() {
        CompilerRegistry.clearAll();
        session = new CompilationSession();
        CompilationSession.setActiveSession(session);
        CompilerReporter.clear();
        analyzer = new IRSemanticAnalyzer("TestFile.ocean", session);
    }

    private boolean hasErrorContaining(String substring) {
        return session.messages.stream().anyMatch(m -> m.level() == CompilerReporter.Level.ERROR && m.text().contains(substring));
    }

    private boolean hasWarningContaining(String substring) {
        return session.messages.stream().anyMatch(m -> m.level() == CompilerReporter.Level.WARNING && m.text().contains(substring));
    }

    @Test
    public void testAbstractFinalClassError() {
        IRClass clazz = new IRClass("com/example/MyClass", "java/lang/Object", false);
        clazz.setAccessFlags(Opcodes.ACC_ABSTRACT | Opcodes.ACC_FINAL);
        clazz.accept(analyzer);

        assertTrue(analyzer.hasErrors());
        assertTrue(hasErrorContaining("cannot be both 'abstract' and 'final'"));
    }

    @Test
    public void testInheritFromFinalClassError() {
        CompilerRegistry.globalClassAccess.put("com/example/FinalSuper", Opcodes.ACC_FINAL);
        IRClass clazz = new IRClass("com/example/SubClass", "com/example/FinalSuper", false);
        clazz.accept(analyzer);

        assertTrue(analyzer.hasErrors());
        assertTrue(hasErrorContaining("Cannot inherit from final class"));
    }

    @Test
    public void testEnumDuplicateConstantError() {
        IREnum enumNode = new IREnum("com/example/MyEnum", List.of("A", "B", "A"), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        enumNode.accept(analyzer);

        assertTrue(analyzer.hasErrors());
        assertTrue(hasErrorContaining("Duplicate enum constant: 'A'"));
    }

    @Test
    public void testThisReassignmentError() {
        IRVariableAccess thisAccess = new IRVariableAccess("this", "Lcom/example/MyClass;", false, null, false);
        IRLiteral val = new IRLiteral(10, "I");
        IRAssignment assignment = new IRAssignment(thisAccess, val, false);

        assignment.accept(analyzer);

        assertTrue(analyzer.hasErrors());
        assertTrue(hasErrorContaining("Cannot assign a value to 'this'"));
    }

    @Test
    public void testVoidReturnWithValueError() {
        List<IRStatement> stmts = new ArrayList<>();
        stmts.add(new IRReturnStatement(new IRLiteral(5, "I")));
        IRBlock body = new IRBlock(stmts);
        IRMethod method = new IRMethod("myMethod", "()V", false, false, body);

        method.accept(analyzer);

        assertTrue(analyzer.hasErrors());
        assertTrue(hasErrorContaining("Cannot return a value from a method with void"));
    }

    @Test
    public void testNonVoidReturnWithoutValueError() {
        List<IRStatement> stmts = new ArrayList<>();
        stmts.add(new IRReturnStatement(null));
        IRBlock body = new IRBlock(stmts);
        IRMethod method = new IRMethod("myMethod", "()I", false, false, body);

        method.accept(analyzer);

        assertTrue(analyzer.hasErrors());
        assertTrue(hasErrorContaining("non-void method must return a value"));
    }

    @Test
    public void testPrimitiveLockExpressionError() {
        IRLockStatement lockStmt = new IRLockStatement(new IRLiteral(123, "I"), new IRBlock(Collections.emptyList()));
        lockStmt.accept(analyzer);

        assertTrue(analyzer.hasErrors());
        assertTrue(hasErrorContaining("Lock expression must be a reference type"));
    }

    @Test
    public void testGenericArrayCreationError() {
        IRArrayCreation arrayCreation = new IRArrayCreation("T", List.of(new IRLiteral(10, "I")));
        arrayCreation.accept(analyzer);

        assertTrue(analyzer.hasErrors());
        assertTrue(hasErrorContaining("cannot create array of type parameter 'T'"));
    }

    @Test
    public void testInstantiateAbstractClassError() {
        CompilerRegistry.globalAbstractClassSet.add("com/example/AbstractShape");
        IRNewObject newObj = new IRNewObject("com.example.AbstractShape", "Lcom/example/AbstractShape;", Collections.emptyList());
        newObj.accept(analyzer);

        assertTrue(analyzer.hasErrors());
        assertTrue(hasErrorContaining("Abstract class 'com.example.AbstractShape' cannot be instantiated"));
    }

    @Test
    public void testAwaitOutsideAsyncError() {
        IRAwaitExpression awaitExpr = new IRAwaitExpression(new IRVariableAccess("fut", "Ljava/util/concurrent/CompletableFuture;", false, null, false), OceanTypeSystem.OBJECT_DESC);
        awaitExpr.accept(analyzer);

        assertTrue(analyzer.hasErrors());
        assertTrue(hasErrorContaining("'await' expression is only allowed in 'async' functions"));
    }

    @Test
    public void testUnreachableCodeWarning() {
        List<IRStatement> stmts = new ArrayList<>();
        stmts.add(new IRReturnStatement(null));
        stmts.add(new IRExprStatement(new IRLiteral(10, "I")));
        IRBlock block = new IRBlock(stmts);

        block.accept(analyzer);

        assertTrue(hasWarningContaining("Unreachable code"));
    }

    @Test
    public void testIfConditionNonBooleanWarning() {
        IRIfStatement ifStmt = new IRIfStatement(new IRLiteral("hello", OceanTypeSystem.STRING_DESC), new IRBlock(Collections.emptyList()), null);
        ifStmt.accept(analyzer);

        assertTrue(hasErrorContaining("Condition in 'if' statement must be boolean") || hasWarningContaining("Condition in 'if' statement must be boolean"));
    }

    @Test
    public void testDivisionByZeroWarning() {
        IRBinaryOp divOp = new IRBinaryOp(new IRLiteral(10, "I"), new IRLiteral(0, "I"), IRBinaryOp.Op.DIV, "I");
        divOp.accept(analyzer);

        assertTrue(hasWarningContaining("Division by zero"));
    }

    @Test
    public void testSelfAssignmentWarning() {
        IRVariableAccess target = new IRVariableAccess("x", "I", false, null, false);
        IRVariableAccess val = new IRVariableAccess("x", "I", false, null, false);
        IRAssignment assign = new IRAssignment(target, val, false);

        assign.accept(analyzer);

        assertTrue(hasWarningContaining("is assigned to itself"));
    }

    @Test
    public void testNegativeArraySizeError() {
        IRArrayCreation arrayCreation = new IRArrayCreation("I", List.of(new IRLiteral(-5, "I")));
        arrayCreation.accept(analyzer);

        assertTrue(analyzer.hasErrors());
        assertTrue(hasErrorContaining("Array dimension cannot be negative"));
    }

    @Test
    public void testIdenticalVariableComparisonWarning() {
        IRVariableAccess v1 = new IRVariableAccess("a", "I", false, null, false);
        IRVariableAccess v2 = new IRVariableAccess("a", "I", false, null, false);
        IRBinaryOp compOp = new IRBinaryOp(v1, v2, IRBinaryOp.Op.EQ, "Z");

        compOp.accept(analyzer);

        assertTrue(hasWarningContaining("Identical variable comparison"));
    }

    @Test
    public void testLambdaWithoutExplicitTargetTypeError() {
        IRLambdaExpression lambda = new IRLambdaExpression(
                "Ljava/util/function/Function;", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;",
                List.of("abc"), List.of("Ljava/lang/Object;"),
                new IRBinaryOp(new IRVariableAccess("abc", "I", false, null, false), new IRLiteral(2, "I"), IRBinaryOp.Op.MUL, "I"),
                Collections.emptyList(), Collections.emptyList(),
                "lambda$Test$0", true
        );
        IRVariableDecl untypedVar = new IRVariableDecl("x", "Ljava/util/function/Function;", lambda, false, false);
        untypedVar.accept(analyzer);

        assertTrue(analyzer.hasErrors());
        assertTrue(hasErrorContaining("Lambda expression requires an explicit target type"));
    }

    @Test
    public void testLambdaTargetTypeNotFunctionalInterfaceError() {
        IRLambdaExpression lambda = new IRLambdaExpression(
                "Ljava/lang/Object;", "apply", "(Ljava/lang/Object;)Ljava/lang/Object;",
                List.of("abc"), List.of("Ljava/lang/Object;"),
                new IRBinaryOp(new IRVariableAccess("abc", "I", false, null, false), new IRLiteral(2, "I"), IRBinaryOp.Op.MUL, "I"),
                Collections.emptyList(), Collections.emptyList(),
                "lambda$Test$0", true
        );
        IRVariableDecl typedObjVar = new IRVariableDecl("x", "Ljava/lang/Object;", lambda, false, true);
        typedObjVar.accept(analyzer);

        assertTrue(analyzer.hasErrors());
        assertTrue(hasErrorContaining("is not a functional interface"));
    }
}
