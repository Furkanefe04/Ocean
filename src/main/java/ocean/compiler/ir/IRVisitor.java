package ocean.compiler.ir;

/**
 * Ziyaretçi arayüzü (Visitor Pattern) - Tüm IR düğümleri için.
 */
public interface IRVisitor {
    // Structure
    void visitCompilationUnit(IRCompilationUnit node);
    void visitClass(IRClass node);
    void visitInterface(IRInterface node);
    void visitEnum(IREnum node);
    void visitMethod(IRMethod node);
    void visitField(IRField node);
    
    // Statements
    void visitBlock(IRBlock node);
    void visitAssignment(IRAssignment node);
    void visitIf(IRIfStatement node);
    void visitWhile(IRWhileStatement node);
    void visitReturn(IRReturnStatement node);
    void visitExprStatement(IRExprStatement node);
    void visitVariableDecl(IRVariableDecl node);
    void visitForStatement(IRForStatement node);
    void visitThrow(IRThrowStatement node);
    void visitTryCatch(IRTryCatchStatement node);
    void visitDoWhile(IRDoWhileStatement node);
    void visitSwitch(IRSwitchStatement node);
    void visitStop(IRStopStatement node);
    void visitSkip(IRSkipStatement node);
    void visitLockStatement(IRLockStatement node);
    void visitLabeled(IRLabeledStatement node);
    void visitVerify(IRVerifyStatement node);
    
    // Expressions
    void visitLiteral(IRLiteral node);
    void visitVariableAccess(IRVariableAccess node);
    void visitBinaryOp(IRBinaryOp node);
    void visitUnaryOp(IRUnaryOp node);
    void visitMethodCall(IRMethodCall node);
    void visitArrayAccess(IRArrayAccess node);
    void visitArrayLiteral(IRArrayLiteral node);
    void visitCast(IRCastExpression node);
    void visitInterpolatedString(IRInterpolatedString node);
    void visitTernary(IRTernaryExpression node);
    void visitNewObject(IRNewObject node);
    void visitArrayCreation(IRArrayCreation node);
    void visitInstanceof(IRInstanceof node);
    void visitOceanOutput(IROceanOutput node);
    void visitLambda(IRLambdaExpression node);
    
    // Generic fallbacks
    void visitStatement(IRStatement stmt);
    void visitExpression(IRExpression expr);
    
    void visitResultStatement(IRResultStatement node);
    void visitSwitchExpression(IRSwitchExpression node);
    void visitAwaitExpression(IRAwaitExpression node);
    void visitAnnotation(IRAnnotation node);
    void visitSwitchPattern(IRSwitchPattern node);
}
