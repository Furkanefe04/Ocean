package ocean.compiler.ir;

import java.util.List;

/**
 * Varsayılan (boş) IR ziyaretçi gerçeklemesi.
 */
public class BaseIRVisitor implements IRVisitor {

    @Override public void visitCompilationUnit(IRCompilationUnit node) {for (IRNode type : node.getTypes()) type.accept(this);}
    @Override public void visitClass(IRClass node) {
        for (IRAnnotation anno : node.getAnnotations()) anno.accept(this);
        for (IRField field : node.getFields()) field.accept(this);
        for (IRMethod method : node.getMethods()) method.accept(this);
        for (IRBlock block : node.getStaticBlocks()) block.accept(this);
        for (IRBlock block : node.getInstanceBlocks()) block.accept(this);
    }
    @Override public void visitInterface(IRInterface node) {
        for (IRAnnotation anno : node.getAnnotations()) anno.accept(this);
        for (IRMethod method : node.getMethods()) method.accept(this);
    }
    @Override public void visitEnum(IREnum node) {
        for (IRAnnotation anno : node.getAnnotations()) anno.accept(this);
        if (node.getConstantArguments() != null) {
            for (List<IRExpression> args : node.getConstantArguments()) {
                if (args != null) {
                    for (IRExpression arg : args) arg.accept(this);
                }
            }
        }
        for (IRField field : node.getFields()) field.accept(this);
        for (IRMethod method : node.getMethods()) method.accept(this);
    }
    @Override public void visitMethod(IRMethod node) {
        for (IRAnnotation anno : node.getAnnotations()) anno.accept(this);
        if (node.getBody() != null) node.getBody().accept(this);
    }
    @Override public void visitField(IRField node) {
        for (IRAnnotation anno : node.getAnnotations()) anno.accept(this);
        if (node.getInitialValue() != null) node.getInitialValue().accept(this);
    }
    @Override public void visitBlock(IRBlock node) {
        for (IRStatement stmt : node.getStatements()) stmt.accept(this);
    }
    @Override public void visitAssignment(IRAssignment node) {
        if (node.getTarget() != null) node.getTarget().accept(this);
        if (node.getValue() != null) node.getValue().accept(this);
    }
    @Override public void visitIf(IRIfStatement node) {
        if (node.getCondition() != null) node.getCondition().accept(this);
        if (node.getThenBranch() != null) node.getThenBranch().accept(this);
        if (node.getElseBranch() != null) node.getElseBranch().accept(this);
    }
    @Override public void visitWhile(IRWhileStatement node) {
        if (node.getCondition() != null) node.getCondition().accept(this);
        if (node.getBody() != null) node.getBody().accept(this);
    }
    @Override public void visitReturn(IRReturnStatement node) {
        if (node.getExpression() != null) node.getExpression().accept(this);
        visitStatement(node);
    }
    @Override public void visitInstanceof(IRInstanceof node) {
        if (node.getExpression() != null) node.getExpression().accept(this);
        visitExpression(node);
    }
    @Override public void visitTernary(IRTernaryExpression node) {
        if (node.getCondition() != null) node.getCondition().accept(this);
        if (node.getTrueExpr() != null) node.getTrueExpr().accept(this);
        if (node.getFalseExpr() != null) node.getFalseExpr().accept(this);
        visitExpression(node);
    }
    @Override public void visitNewObject(IRNewObject node) {
        for (IRExpression arg : node.getArguments()) arg.accept(this);
        visitExpression(node);
    }
    @Override public void visitArrayCreation(IRArrayCreation node) {
        for (IRExpression size : node.getSizes()) size.accept(this);
        visitExpression(node);
    }
    @Override public void visitExprStatement(IRExprStatement node) {
        if (node.getExpression() != null) node.getExpression().accept(this);
        visitStatement(node);
    }
    @Override public void visitVariableDecl(IRVariableDecl node) {
        if (node.getInitialValue() != null) node.getInitialValue().accept(this);
        visitStatement(node);
    }
    @Override public void visitArrayAccess(IRArrayAccess node) {
        if (node.getArray() != null) node.getArray().accept(this);
        if (node.getIndex() != null) node.getIndex().accept(this);
        visitExpression(node);
    }
    @Override public void visitArrayLiteral(IRArrayLiteral node) {
        for (IRExpression e : node.getElements()) e.accept(this);
        visitExpression(node);
    }
    @Override public void visitCast(IRCastExpression node) {
        if (node.getExpression() != null) node.getExpression().accept(this);
        visitExpression(node);
    }
    @Override public void visitThrow(IRThrowStatement node) {
        if (node.getExpression() != null) node.getExpression().accept(this);
        visitStatement(node);
    }
    @Override public void visitVerify(IRVerifyStatement node) {
        if (node.getCondition() != null) node.getCondition().accept(this);
        if (node.getDetailMessage() != null) node.getDetailMessage().accept(this);
        visitStatement(node);
    }
    @Override public void visitTryCatch(IRTryCatchStatement node) {
        if (node.getTryBlock() != null) node.getTryBlock().accept(this);
        for (IRTryCatchStatement.IRCatchClause cc : node.getCatchClauses()) {
            if (cc.body() != null) cc.body().accept(this);
        }
        if (node.getFinallyBlock() != null) node.getFinallyBlock().accept(this);
        visitStatement(node);
    }
    @Override public void visitDoWhile(IRDoWhileStatement node) {
        if (node.getBody() != null) node.getBody().accept(this);
        if (node.getCondition() != null) node.getCondition().accept(this);
        visitStatement(node);
    }
    @Override public void visitSwitch(IRSwitchStatement node) {
        visitSwitchPart(node.getExpression(),node.getCases(),node.getDefaultBlock());
        visitStatement(node);
    }
    @Override public void visitStop(IRStopStatement node) { visitStatement(node); }
    @Override public void visitSkip(IRSkipStatement node) { visitStatement(node); }
    @Override public void visitLockStatement(IRLockStatement node) {
        if (node.getLockExpression() != null) node.getLockExpression().accept(this);
        if (node.getBody() != null) node.getBody().accept(this);
        visitStatement(node);
    }
    @Override public void visitLabeled(IRLabeledStatement node) {
        if (node.getStatement() != null) node.getStatement().accept(this);
        visitStatement(node);
    }
    @Override public void visitUnaryOp(IRUnaryOp node) {
        if (node.getExpression() != null) node.getExpression().accept(this);
        visitExpression(node);
    }
    @Override public void visitForStatement(IRForStatement node) {
        if (node.getFromExpr() != null) node.getFromExpr().accept(this);
        if (node.getToExpr() != null) node.getToExpr().accept(this);
        if (node.getStepExpr() != null) node.getStepExpr().accept(this);
        if (node.getIterableExpr() != null) node.getIterableExpr().accept(this);
        if (node.getBody() != null) node.getBody().accept(this);
        visitStatement(node);
    }
    @Override public void visitOceanOutput(IROceanOutput node) {
        for (IRExpression arg : node.getArguments()) arg.accept(this);
        visitExpression(node);
    }

    @Override public void visitLiteral(IRLiteral node) { visitExpression(node); }
    @Override public void visitVariableAccess(IRVariableAccess node) { visitExpression(node); }
    @Override public void visitBinaryOp(IRBinaryOp node) {
        if (node.getLeft() != null) node.getLeft().accept(this);
        if (node.getRight() != null) node.getRight().accept(this);
        visitExpression(node);
    }
    @Override public void visitMethodCall(IRMethodCall node) {
        if (node.getReceiver() != null) node.getReceiver().accept(this);
        for (IRExpression arg : node.getArguments()) arg.accept(this);
        visitExpression(node);
    }

    @Override public void visitStatement(IRStatement stmt) {}
    @Override public void visitInterpolatedString(IRInterpolatedString node) {
        for (IRNode part : node.getParts()) part.accept(this);
        visitExpression(node);
    }
    @Override public void visitExpression(IRExpression expr) {}
    @Override public void visitLambda(IRLambdaExpression node) {
        if (node.getBody() != null) node.getBody().accept(this);
        visitExpression(node);
    }
    @Override public void visitResultStatement(IRResultStatement node) {
        if (node.getExpression() != null) node.getExpression().accept(this);
        visitStatement(node);
    }
    @Override public void visitSwitchExpression(IRSwitchExpression node) {
        visitSwitchPart(node.getExpression(),node.getCases(),node.getDefaultBody());
        visitExpression(node);
    }
    @Override public void visitAwaitExpression(IRAwaitExpression node) {
        if (node.getFuture() != null) node.getFuture().accept(this);
        visitExpression(node);
    }
    @Override public void visitAnnotation(IRAnnotation node) {
        if (node.getElements() != null) {
            for (IRExpression expr : node.getElements().values()) {
                if (expr != null) expr.accept(this);
            }
        }
    }
    @Override public void visitSwitchPattern(IRSwitchPattern node) {
        if (node.getExpression() != null) node.getExpression().accept(this);
        if (node.getGuard() != null) node.getGuard().accept(this);
        if (node.getNestedPatterns() != null) {
            for (IRSwitchPattern nested : node.getNestedPatterns()) {
                if (nested != null) nested.accept(this);
            }
        }
    }

    private void visitSwitchPart(IRExpression expression,List<IRSwitchCase> cases,IRNode defaultBody) {
        if (expression != null) expression.accept(this);
        for (IRSwitchCase c : cases) {
            if (c.getPatterns() != null) {
                for (IRSwitchPattern p : c.getPatterns()) p.accept(this);
            }
            for (IRExpression val : c.getValues()) val.accept(this);
            if (c.getGuard() != null) c.getGuard().accept(this);
            if (c.getBody() != null) c.getBody().accept(this);
        }
        if (defaultBody != null) {
            defaultBody.accept(this);
        }
    }
}
