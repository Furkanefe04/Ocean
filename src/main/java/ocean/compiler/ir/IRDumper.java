package ocean.compiler.ir;

import java.util.List;

/**
 * IR ağacını metin formatında döken (dump) yardımcı sınıf.
 */
public class IRDumper extends BaseIRVisitor {
    private int indent = 0;
    private final StringBuilder sb = new StringBuilder();

    public String dump(IRNode node) {
        sb.setLength(0);
        node.accept(this);
        return sb.toString();
    }

    private void print(String text) {
        sb.append("  ".repeat(Math.max(0, indent)));
        sb.append(text).append("\n");
    }
    
    @Override
    public void visitClass(IRClass node) {
        print("Class: " + node.getName() + " extends " + node.getSuperName());
        indent++;
        for (IRField field : node.getFields()) field.accept(this);
        for (IRMethod method : node.getMethods()) method.accept(this);
        for (IRBlock block : node.getStaticBlocks()) {
            print("StaticBlock:");
            indent++;
            block.accept(this);
            indent--;
        }
        indent--;
    }

    @Override
    public void visitMethod(IRMethod node) {
        print("Method: " + node.getName() + " " + node.getDescriptor());
        indent++;
        if (node.getBody() != null) node.getBody().accept(this);
        indent--;
    }

    @Override
    public void visitField(IRField node) {
        print("Field: " + node.getName() + " [" + node.getTypeDescriptor() + "]");
    }

    @Override
    public void visitBlock(IRBlock node) {
        print("Block {");
        indent++;
        for (IRStatement stmt : node.getStatements()) {
            stmt.accept(this);
        }
        indent--;
        print("}");
    }

    @Override
    public void visitAssignment(IRAssignment node) {
        print("Assign:");
        indent++;
        node.getTarget().accept(this);
        print("  =");
        node.getValue().accept(this);
        indent--;
    }

    @Override
    public void visitLiteral(IRLiteral node) {
        print("Literal: " + node.getValue() + " [" + node.getTypeDescriptor() + "]");
    }

    @Override
    public void visitVariableAccess(IRVariableAccess node) {
        print("Var: " + node.getName() + " [" + node.getTypeDescriptor() + "]");
    }

    @Override
    public void visitBinaryOp(IRBinaryOp node) {
        print("BinaryOp (" + node.getOperator() + "):");
        indent++;
        node.getLeft().accept(this);
        node.getRight().accept(this);
        indent--;
    }

    @Override
    public void visitIf(IRIfStatement node) {
        print("If:");
        indent++;
        print("Cond:");
        indent++;
        if (node.getCondition() != null) node.getCondition().accept(this);
        indent--;
        print("Then:");
        indent++;
        if (node.getThenBranch() != null) node.getThenBranch().accept(this);
        indent--;
        if (node.getElseBranch() != null) {
            print("Else:");
            indent++;
            node.getElseBranch().accept(this);
            indent--;
        }
        indent--;
    }

    @Override
    public void visitWhile(IRWhileStatement node) {
        print("While:");
        indent++;
        print("Cond:");
        indent++;
        node.getCondition().accept(this);
        indent--;
        print("Body:");
        indent++;
        node.getBody().accept(this);
        indent--;
        indent--;
    }

    @Override
    public void visitLockStatement(IRLockStatement node) {
        print("Lock:");
        indent++;
        print("Expr:");
        indent++;
        if (node.getLockExpression() != null) node.getLockExpression().accept(this);
        indent--;
        print("Body:");
        indent++;
        if (node.getBody() != null) node.getBody().accept(this);
        indent--;
        indent--;
    }

    @Override
    public void visitReturn(IRReturnStatement node) {
        print("Return:");
        if (node.getExpression() != null) {
            indent++;
            node.getExpression().accept(this);
            indent--;
        }
    }

    @Override
    public void visitExprStatement(IRExprStatement node) {
        print("ExprStmt:");
        indent++;
        node.getExpression().accept(this);
        indent--;
    }

    @Override
    public void visitVariableDecl(IRVariableDecl node) {
        print("VarDecl: " + node.getName() + " [" + node.getTypeDescriptor() + "]" + (node.isFinal() ? " (LOCK)" : ""));
        if (node.getInitialValue() != null) {
            indent++;
            print("Init:");
            indent++;
            node.getInitialValue().accept(this);
            indent--;
            indent--;
        }
    }

    @Override
    public void visitForStatement(IRForStatement node) {
        print("For: " + node.getIteratorName());
        indent++;
        if (node.isRange()) {
            print("From:");
            indent++; if (node.getFromExpr() != null) node.getFromExpr().accept(this); indent--;
            print("To:");
            indent++; if (node.getToExpr() != null) node.getToExpr().accept(this);
        } else {
            print("In:");
            indent++; if (node.getIterableExpr() != null) node.getIterableExpr().accept(this);
        }
        indent--;
        print("Body:");
        indent++; if (node.getBody() != null) node.getBody().accept(this); indent--;
        indent--;
    }

    @Override
    public void visitMethodCall(IRMethodCall node) {
        print("MethodCall: " + node.getName());
        indent++;
        print("Owner: " + node.getOwner());
        print("Static: " + node.isStatic());
        print("Descriptor: " + node.getDescriptor());
        for (IRExpression arg : node.getArguments()) arg.accept(this);
        indent--;
    }

    @Override
    public void visitOceanOutput(IROceanOutput node) {
        print("OceanOutput:");
        indent++;
        for (IRExpression arg : node.getArguments()) arg.accept(this);
        indent--;
    }

    @Override
    public void visitTernary(IRTernaryExpression node) {
        print("Ternary:");
        indent++;
        print("Cond:");
        indent++; node.getCondition().accept(this); indent--;
        print("True:");
        indent++; node.getTrueExpr().accept(this); indent--;
        print("False:");
        indent++; node.getFalseExpr().accept(this); indent--;
        indent--;
    }

    @Override
    public void visitStop(IRStopStatement node) {
        print("Stop (break)");
    }

    @Override
    public void visitSkip(IRSkipStatement node) {
        print("Skip (continue)");
    }

    @Override
    public void visitLambda(IRLambdaExpression node) {
        print("Lambda: " + node.getLambdaMethodName() + " implementing " + node.getTargetInterface() + " (" + node.getSamMethodName() + ")");
        indent++;
        if (node.getBody() != null) {
            print("Body:");
            indent++;
            node.getBody().accept(this);
            indent--;
        }
        indent--;
    }

    @Override
    public void visitResultStatement(IRResultStatement node) {
        print("Result:");
        indent++;
        if (node.getExpression() != null) {
            node.getExpression().accept(this);
        }
        indent--;
    }

    @Override
    public void visitSwitch(IRSwitchStatement node) {
        print("SwitchStmt:");
        printForSwitch(node.getExpression(), node.getCases(),node.getDefaultBlock());

    }

    @Override
    public void visitSwitchExpression(IRSwitchExpression node) {
        print("SwitchExpr [" + node.getTypeDescriptor() + "]:");
        printForSwitch(node.getExpression(), node.getCases(),node.getDefaultBody());
    }

    private void printForSwitch(IRExpression expression, List<IRSwitchCase> cases,IRNode defaultBlock) {
        indent++;
        print("Expr:");
        indent++;
        if (expression != null) expression.accept(this);
        indent--;
        print("Cases:");
        indent++;
        for (IRSwitchCase c : cases) {
            if (c.getPatterns() != null && !c.getPatterns().isEmpty()) {
                print("Patterns:");
                indent++;
                for (IRSwitchPattern p : c.getPatterns()) p.accept(this);
                indent--;
            }
            if (c.getValues() != null && !c.getValues().isEmpty()) {
                print("Values:");
                indent++;
                for (IRExpression v : c.getValues()) v.accept(this);
                indent--;
            }
            if (c.getGuard() != null) {
                print("Guard:");
                indent++;
                c.getGuard().accept(this);
                indent--;
            }
            if (c.getBody() != null) {
                print("Body:");
                indent++;
                c.getBody().accept(this);
                indent--;
            }
        }
        indent--;
        if (defaultBlock != null) {
            print("Default:");
            indent++;
            defaultBlock.accept(this);
            indent--;
        }
        indent--;
    }

    @Override
    public void visitSwitchPattern(IRSwitchPattern node) {
        print("Pattern (" + node.getKind() + "): " + (node.getVariableName() != null ? node.getVariableName() : "") + " [" + node.getTypeDescriptor() + "]");
        indent++;
        if (node.getExpression() != null) {
            print("Expr:");
            indent++;
            node.getExpression().accept(this);
            indent--;
        }
        if (node.getNestedPatterns() != null && !node.getNestedPatterns().isEmpty()) {
            print("Nested:");
            indent++;
            for (IRSwitchPattern np : node.getNestedPatterns()) np.accept(this);
            indent--;
        }
        if (node.getGuard() != null) {
            print("Guard:");
            indent++;
            node.getGuard().accept(this);
            indent--;
        }
        indent--;
    }

    @Override
    public void visitTryCatch(IRTryCatchStatement node) {
        print("TryCatch:");
        indent++;
        if (node.getTryBlock() != null) {
            print("Try:");
            indent++;
            node.getTryBlock().accept(this);
            indent--;
        }
        if (node.getCatchClauses() != null) {
            for (IRTryCatchStatement.IRCatchClause cc : node.getCatchClauses()) {
                print("Catch (" + cc.exceptionVar() + " : " + cc.exceptionTypes() + "):");
                indent++;
                if (cc.body() != null) cc.body().accept(this);
                indent--;
            }
        }
        if (node.getFinallyBlock() != null) {
            print("Finally:");
            indent++;
            node.getFinallyBlock().accept(this);
            indent--;
        }
        indent--;
    }

    @Override
    public void visitDoWhile(IRDoWhileStatement node) {
        print("DoWhile:");
        indent++;
        if (node.getBody() != null) {
            print("Body:");
            indent++;
            node.getBody().accept(this);
            indent--;
        }
        if (node.getCondition() != null) {
            print("Cond:");
            indent++;
            node.getCondition().accept(this);
            indent--;
        }
        indent--;
    }

    @Override
    public void visitCast(IRCastExpression node) {
        print("Cast -> [" + node.getTargetType() + "]:");
        indent++;
        if (node.getExpression() != null) node.getExpression().accept(this);
        indent--;
    }

    @Override
    public void visitInstanceof(IRInstanceof node) {
        print("Instanceof [" + node.getTypeDescriptor() + "]:");
        indent++;
        if (node.getExpression() != null) node.getExpression().accept(this);
        indent--;
    }

    @Override
    public void visitArrayCreation(IRArrayCreation node) {
        print("ArrayCreation (" + node.getBaseType() + ") [" + node.getTypeDescriptor() + "]:");
        indent++;
        if (node.getSizes() != null) {
            for (IRExpression s : node.getSizes()) s.accept(this);
        }
        indent--;
    }

    @Override
    public void visitArrayAccess(IRArrayAccess node) {
        print("ArrayAccess [" + node.getTypeDescriptor() + "]:");
        indent++;
        if (node.getArray() != null) {
            print("Array:");
            indent++;
            node.getArray().accept(this);
            indent--;
        }
        if (node.getIndex() != null) {
            print("Index:");
            indent++;
            node.getIndex().accept(this);
            indent--;
        }
        indent--;
    }

    @Override
    public void visitArrayLiteral(IRArrayLiteral node) {
        print("ArrayLiteral [" + node.getTypeDescriptor() + "]:");
        indent++;
        if (node.getElements() != null) {
            for (IRExpression e : node.getElements()) e.accept(this);
        }
        indent--;
    }

    @Override
    public void visitInterpolatedString(IRInterpolatedString node) {
        print("InterpolatedString:");
        indent++;
        if (node.getParts() != null) {
            for (IRNode p : node.getParts()) p.accept(this);
        }
        indent--;
    }

    @Override
    public void visitThrow(IRThrowStatement node) {
        print("Throw:");
        indent++;
        if (node.getExpression() != null) node.getExpression().accept(this);
        indent--;
    }

    @Override
    public void visitVerify(IRVerifyStatement node) {
        print("Verify:");
        indent++;
        if (node.getCondition() != null) {
            print("Cond:");
            indent++;
            node.getCondition().accept(this);
            indent--;
        }
        if (node.getDetailMessage() != null) {
            print("Detail:");
            indent++;
            node.getDetailMessage().accept(this);
            indent--;
        }
        indent--;
    }

    @Override
    public void visitUnaryOp(IRUnaryOp node) {
        print("UnaryOp (" + node.getOperator() + ") [" + node.getTypeDescriptor() + "]:");
        indent++;
        if (node.getExpression() != null) node.getExpression().accept(this);
        indent--;
    }

    @Override
    public void visitAnnotation(IRAnnotation node) {
        print("Annotation @" + node.getTypeDescriptor());
        indent++;
        if (node.getElements() != null) {
            for (java.util.Map.Entry<String, IRExpression> e : node.getElements().entrySet()) {
                print(e.getKey() + " =");
                indent++;
                if (e.getValue() != null) e.getValue().accept(this);
                indent--;
            }
        }
        indent--;
    }

    @Override
    public void visitAwaitExpression(IRAwaitExpression node) {
        print("AwaitExpr [" + node.getTypeDescriptor() + "]:");
        indent++;
        if (node.getFuture() != null) node.getFuture().accept(this);
        indent--;
    }

}

