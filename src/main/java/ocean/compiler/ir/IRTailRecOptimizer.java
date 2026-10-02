package ocean.compiler.ir;

import java.util.*;
import ocean.compiler.CompilerReporter;

/**
 * Tail Call Optimization (TCO) Optimizer for methods annotated with @TailRec.
 * Transforms self-recursive tail calls into iterative loop jumps, eliminating
 * JVM call stack growth and preventing StackOverflowError.
 */
public class IRTailRecOptimizer extends BaseIRVisitor {

    private String currentClassName = null;
    private int tcoVarCounter = 0;

    public IRNode optimize(IRNode node) {
        if (node == null) return null;
        node.accept(this);
        return node;
    }

    @Override
    public void visitCompilationUnit(IRCompilationUnit node) {
        for (IRNode type : node.getTypes()) {
            type.accept(this);
        }
    }

    @Override
    public void visitClass(IRClass node) {
        String oldClass = currentClassName;
        currentClassName = node.getName();
        try {
            for (IRMethod method : node.getMethods()) {
                optimizeMethod(method);
            }
        } finally {
            currentClassName = oldClass;
        }
    }

    @Override
    public void visitInterface(IRInterface node) {
        String oldClass = currentClassName;
        currentClassName = node.getName();
        try {
            for (IRMethod method : node.getMethods()) {
                optimizeMethod(method);
            }
        } finally {
            currentClassName = oldClass;
        }
    }

    @Override
    public void visitEnum(IREnum node) {
        String oldClass = currentClassName;
        currentClassName = node.getName();
        try {
            for (IRMethod method : node.getMethods()) {
                optimizeMethod(method);
            }
        } finally {
            currentClassName = oldClass;
        }
    }

    private void optimizeMethod(IRMethod method) {
        if (method == null || method.getBody() == null) return;
        if (!isTailRecAnnotated(method)) return;

        if (method.isAbstract() || method.isNative()) {
            reportWarning(method, "Method '" + method.getName() + "' is abstract or native and cannot be optimized with @TailRec.");
            return;
        }

        // Normalize returns containing ternary expressions: return cond ? a : b -> if (cond) return a; else return b;
        IRStatement normalizedBody = normalizeTernaryReturns(method.getBody());

        // Analyze tail-recursive calls in the normalized body
        List<IRMethodCall> tailCalls = new ArrayList<>();
        List<IRMethodCall> nonTailCalls = new ArrayList<>();
        analyzeRecursion(normalizedBody, method, true, tailCalls, nonTailCalls);

        if (tailCalls.isEmpty()) {
            reportWarning(method, "Method '" + method.getName() + "' is annotated with @TailRec but contains no tail-recursive calls.");
            return;
        }

        // Perform the TCO transformation
        String loopLabel = "_tailrec_" + method.getName() + "_" + (tcoVarCounter++);
        IRStatement transformedBody = transformBody(normalizedBody, method, loopLabel);

        // Wrap the transformed body into a while(true) loop labeled loopLabel
        IRWhileStatement whileLoop = new IRWhileStatement(new IRLiteral(true, "Z"), transformedBody);
        IRLabeledStatement labeledLoop = new IRLabeledStatement(loopLabel, whileLoop);
        labeledLoop.setLocation(method.getBody().getLineNumber(), method.getBody().getColumnNumber());

        method.setBody(labeledLoop);
    }

    private boolean isTailRecAnnotated(IRMethod method) {
        if (method.getAnnotations() == null) return false;
        for (IRAnnotation anno : method.getAnnotations()) {
            String desc = anno.getTypeDescriptor();
            if (desc != null) {
                String clean = desc.replace("/", ".").replace(";", "");
                if (clean.startsWith("L")) clean = clean.substring(1);
                String simpleName = clean.contains(".") ? clean.substring(clean.lastIndexOf('.') + 1) : clean;
                if (simpleName.equalsIgnoreCase("TailRec") || simpleName.equalsIgnoreCase("tailrec")) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isSelfCall(IRMethodCall call, IRMethod method) {
        if (call == null || method == null) return false;
        if (!call.getName().equals(method.getName())) return false;

        List<IRExpression> args = call.getArguments();
        List<IRMethod.IRParameter> params = method.getParameters();
        if (args.size() != params.size()) return false;

        // Check receiver
        if (method.isStatic()) {
            String owner = call.getOwner();
            if (owner != null && !owner.isEmpty()) {
                String cleanOwner = owner.replace(".", "/");
                String cleanCurrent = currentClassName != null ? currentClassName.replace(".", "/") : "";
                return cleanOwner.equals(cleanCurrent) || cleanOwner.endsWith("/" + cleanCurrent);
            }
            return true;
        } else {
            IRExpression receiver = call.getReceiver();
            if (receiver == null) return true;
            if (receiver instanceof IRVariableAccess va) {
                return va.getName().equals("this");
            }
            return false;
        }
    }

    private IRStatement normalizeTernaryReturns(IRStatement stmt) {
        switch (stmt) {
            case null -> {
                return null;
            }
            case IRReturnStatement ret when ret.getExpression() instanceof IRTernaryExpression ternary -> {
                IRStatement thenBranch = normalizeTernaryReturns(new IRReturnStatement(ternary.getTrueExpr()));
                IRStatement elseBranch = normalizeTernaryReturns(new IRReturnStatement(ternary.getFalseExpr()));
                IRIfStatement ifStmt = new IRIfStatement(ternary.getCondition(), thenBranch, elseBranch);
                ifStmt.setLocation(ret.getLineNumber(), ret.getColumnNumber());
                return ifStmt;
            }
            case IRIfStatement ifStmt -> {
                IRStatement newThen = normalizeTernaryReturns(ifStmt.getThenBranch());
                IRStatement newElse = ifStmt.getElseBranch() != null ? normalizeTernaryReturns(ifStmt.getElseBranch()) : null;
                IRIfStatement newIf = new IRIfStatement(ifStmt.getCondition(), newThen, newElse);
                newIf.setLocation(ifStmt.getLineNumber(), ifStmt.getColumnNumber());
                return newIf;
            }
            case IRBlock block -> {
                List<IRStatement> stmts = new ArrayList<>();
                for (IRStatement s : block.getStatements()) {
                    stmts.add(normalizeTernaryReturns(s));
                }
                IRBlock newBlock = new IRBlock(stmts);
                newBlock.setLocation(block.getLineNumber(), block.getColumnNumber());
                return newBlock;
            }
            case IRLabeledStatement labeled -> {
                IRLabeledStatement newLabeled = new IRLabeledStatement(labeled.getLabel(), normalizeTernaryReturns(labeled.getStatement()));
                newLabeled.setLocation(labeled.getLineNumber(), labeled.getColumnNumber());
                return newLabeled;
            }
            default -> {
            }
        }
        return stmt;
    }

    private void analyzeRecursion(IRStatement stmt, IRMethod method, boolean isTail, List<IRMethodCall> tailCalls, List<IRMethodCall> nonTailCalls) {
        switch (stmt) {
            case IRReturnStatement ret -> {
                if (ret.getExpression() instanceof IRMethodCall call && isSelfCall(call, method)) {
                    if (isTail) {
                        tailCalls.add(call);
                    } else {
                        nonTailCalls.add(call);
                    }
                    // Also scan arguments of the call for nested recursive calls (which are non-tail)
                    for (IRExpression arg : call.getArguments()) {
                        scanExpressionsForCalls(arg, method, nonTailCalls);
                    }
                } else if (ret.getExpression() != null) {
                    scanExpressionsForCalls(ret.getExpression(), method, nonTailCalls);
                }
            }
            case IRIfStatement ifStmt -> {
                scanExpressionsForCalls(ifStmt.getCondition(), method, nonTailCalls);
                analyzeRecursion(ifStmt.getThenBranch(), method, isTail, tailCalls, nonTailCalls);
                if (ifStmt.getElseBranch() != null) {
                    analyzeRecursion(ifStmt.getElseBranch(), method, isTail, tailCalls, nonTailCalls);
                }
            }
            case IRBlock block -> {
                List<IRStatement> stmts = block.getStatements();
                for (int i = 0; i < stmts.size(); i++) {
                    boolean statementIsTail = isTail && (i == stmts.size() - 1);
                    analyzeRecursion(stmts.get(i), method, statementIsTail, tailCalls, nonTailCalls);
                }
            }
            case IRLabeledStatement labeled ->
                    analyzeRecursion(labeled.getStatement(), method, isTail, tailCalls, nonTailCalls);
            case IRExprStatement exprStmt -> scanExpressionsForCalls(exprStmt.getExpression(), method, nonTailCalls);
            case IRVariableDecl varDecl -> {
                if (varDecl.getInitialValue() != null) {
                    scanExpressionsForCalls(varDecl.getInitialValue(), method, nonTailCalls);
                }
            }
            case IRWhileStatement whileStmt -> {
                scanExpressionsForCalls(whileStmt.getCondition(), method, nonTailCalls);
                analyzeRecursion(whileStmt.getBody(), method, false, tailCalls, nonTailCalls);
            }
            case IRDoWhileStatement doWhile -> {
                scanExpressionsForCalls(doWhile.getCondition(), method, nonTailCalls);
                analyzeRecursion(doWhile.getBody(), method, false, tailCalls, nonTailCalls);
            }
            case IRForStatement forStmt -> {
                if (forStmt.getFromExpr() != null) scanExpressionsForCalls(forStmt.getFromExpr(), method, nonTailCalls);
                if (forStmt.getToExpr() != null) scanExpressionsForCalls(forStmt.getToExpr(), method, nonTailCalls);
                if (forStmt.getStepExpr() != null) scanExpressionsForCalls(forStmt.getStepExpr(), method, nonTailCalls);
                if (forStmt.getIterableExpr() != null)
                    scanExpressionsForCalls(forStmt.getIterableExpr(), method, nonTailCalls);
                analyzeRecursion(forStmt.getBody(), method, false, tailCalls, nonTailCalls);
            }
            case IRTryCatchStatement tryCatch -> {
                // Recursive calls inside try-catch are non-tail because of exception handler frames
                if (tryCatch.getTryBlock() != null)
                    analyzeRecursion(tryCatch.getTryBlock(), method, false, tailCalls, nonTailCalls);
                for (IRTryCatchStatement.IRCatchClause cc : tryCatch.getCatchClauses()) {
                    if (cc.body() != null) analyzeRecursion(cc.body(), method, false, tailCalls, nonTailCalls);
                }
                if (tryCatch.getFinallyBlock() != null)
                    analyzeRecursion(tryCatch.getFinallyBlock(), method, false, tailCalls, nonTailCalls);
            }
            case IRSwitchStatement switchStmt -> {
                scanExpressionsForCalls(switchStmt.getExpression(), method, nonTailCalls);
                if (switchStmt.getCases() != null) {
                    for (IRSwitchCase sc : switchStmt.getCases()) {
                        scanSwitchCasePartForCalls(sc, method, nonTailCalls);
                        if (sc.getBody() != null && sc.getBody() instanceof IRStatement body) analyzeRecursion(body, method, isTail, tailCalls, nonTailCalls);
                    }
                }
                if (switchStmt.getDefaultBlock() != null) {
                    analyzeRecursion(switchStmt.getDefaultBlock(), method, isTail, tailCalls, nonTailCalls);
                }
            }
            case IRLockStatement lockStmt -> {
                scanExpressionsForCalls(lockStmt.getLockExpression(), method, nonTailCalls);
                if (lockStmt.getBody() != null) {
                    analyzeRecursion(lockStmt.getBody(), method, false, tailCalls, nonTailCalls);
                }
            }
            case IRThrowStatement throwStmt -> scanExpressionsForCalls(throwStmt.getExpression(), method, nonTailCalls);
            case IRResultStatement resStmt -> scanExpressionsForCalls(resStmt.getExpression(), method, nonTailCalls);
            case null, default -> {
            }
        }

    }

    private void scanExpressionsForCalls(IRExpression expr, IRMethod method, List<IRMethodCall> nonTailCalls) {
        switch (expr) {
            case IRMethodCall call -> {
                if (isSelfCall(call, method)) {
                    nonTailCalls.add(call);
                }
                if (call.getReceiver() != null) scanExpressionsForCalls(call.getReceiver(), method, nonTailCalls);
                for (IRExpression arg : call.getArguments()) scanExpressionsForCalls(arg, method, nonTailCalls);
            }
            case IRBinaryOp binOp -> {
                scanExpressionsForCalls(binOp.getLeft(), method, nonTailCalls);
                scanExpressionsForCalls(binOp.getRight(), method, nonTailCalls);
            }
            case IRUnaryOp unOp -> scanExpressionsForCalls(unOp.getExpression(), method, nonTailCalls);
            case IRCastExpression cast -> scanExpressionsForCalls(cast.getExpression(), method, nonTailCalls);
            case IRInstanceof inst -> scanExpressionsForCalls(inst.getExpression(), method, nonTailCalls);
            case IRTernaryExpression ternary -> {
                scanExpressionsForCalls(ternary.getCondition(), method, nonTailCalls);
                scanExpressionsForCalls(ternary.getTrueExpr(), method, nonTailCalls);
                scanExpressionsForCalls(ternary.getFalseExpr(), method, nonTailCalls);
            }
            case IRArrayAccess arrayAccess -> {
                scanExpressionsForCalls(arrayAccess.getArray(), method, nonTailCalls);
                scanExpressionsForCalls(arrayAccess.getIndex(), method, nonTailCalls);
            }
            case IRArrayCreation arrayCreation -> {
                for (IRExpression dim : arrayCreation.getSizes()) scanExpressionsForCalls(dim, method, nonTailCalls);
            }
            case IRArrayLiteral arrayLit -> {
                for (IRExpression elem : arrayLit.getElements()) scanExpressionsForCalls(elem, method, nonTailCalls);
            }
            case IRNewObject newObj -> {
                for (IRExpression arg : newObj.getArguments()) scanExpressionsForCalls(arg, method, nonTailCalls);
            }
            case IROceanOutput out -> {
                if (out.getArguments() != null) {
                    for (IRExpression a : out.getArguments()) scanExpressionsForCalls(a, method, nonTailCalls);
                }
            }
            case IRAwaitExpression await -> scanExpressionsForCalls(await.getFuture(), method, nonTailCalls);
            case IRInterpolatedString is -> {
                if (is.getParts() != null) {
                    for (IRNode p : is.getParts()) {
                        if (p instanceof IRExpression pe) scanExpressionsForCalls(pe, method, nonTailCalls);
                    }
                }
            }
            case IRSwitchExpression switchExpr -> {
                scanExpressionsForCalls(switchExpr.getExpression(), method, nonTailCalls);
                if (switchExpr.getCases() != null) {
                    for (IRSwitchCase sc : switchExpr.getCases()) {
                        scanSwitchCasePartForCalls(sc, method, nonTailCalls);
                        if (sc.getBody() instanceof IRExpression be) scanExpressionsForCalls(be, method, nonTailCalls);
                        else if (sc.getBody() instanceof IRStatement bs) analyzeRecursion(bs, method, false, new ArrayList<>(), nonTailCalls);

                    }
                }
                if (switchExpr.getDefaultBody() instanceof IRExpression dbe) {
                    scanExpressionsForCalls(dbe, method, nonTailCalls);
                } else if (switchExpr.getDefaultBody() instanceof IRStatement dbs) {
                    analyzeRecursion(dbs, method, false, new ArrayList<>(), nonTailCalls);
                }
            }
            case IRAssignment assign -> {
                if (assign.getTarget() != null) scanExpressionsForCalls(assign.getTarget(), method, nonTailCalls);
                if (assign.getValue() != null) scanExpressionsForCalls(assign.getValue(), method, nonTailCalls);
            }
            case null, default -> {
            }
        }
    }

    private void scanSwitchCasePartForCalls(IRSwitchCase sc, IRMethod method, List<IRMethodCall> nonTailCalls) {
        if (sc.getPatterns() != null) {
            for (IRSwitchPattern p : sc.getPatterns()) {
                if (p.getExpression() != null)
                    scanExpressionsForCalls(p.getExpression(), method, nonTailCalls);
                if (p.getGuard() != null) scanExpressionsForCalls(p.getGuard(), method, nonTailCalls);
            }
        }
        if (sc.getValues() != null) {
            for (IRExpression v : sc.getValues()) scanExpressionsForCalls(v, method, nonTailCalls);
        }
        if (sc.getGuard() != null) scanExpressionsForCalls(sc.getGuard(), method, nonTailCalls);
    }

    private IRStatement transformBody(IRStatement stmt, IRMethod method, String loopLabel) {
        switch (stmt) {
            case null -> {
                return null;
            }
            case IRReturnStatement ret -> {
                if (ret.getExpression() instanceof IRMethodCall call && isSelfCall(call, method)) {
                    List<IRStatement> replacementStmts = new ArrayList<>();
                    List<IRExpression> args = call.getArguments();
                    List<IRMethod.IRParameter> params = method.getParameters();

                    List<String> tempVarNames = new ArrayList<>();

                    // 1. Evaluate arguments into fresh temporary local variables
                    for (int i = 0; i < args.size(); i++) {
                        String tempName = "_tco_temp_" + i + "_" + (tcoVarCounter++);
                        String typeDesc = params.get(i).typeDescriptor();
                        IRVariableDecl tempDecl = new IRVariableDecl(tempName, typeDesc, args.get(i), false, false);
                        tempDecl.setLocation(ret.getLineNumber(), ret.getColumnNumber());
                        replacementStmts.add(tempDecl);
                        tempVarNames.add(tempName);
                    }

                    // 2. Assign temporary variable values to parameter variables
                    for (int i = 0; i < args.size(); i++) {
                        String paramName = params.get(i).name();
                        String typeDesc = params.get(i).typeDescriptor();
                        IRVariableAccess targetAccess = new IRVariableAccess(paramName, typeDesc, false, null, false);
                        IRVariableAccess tempAccess = new IRVariableAccess(tempVarNames.get(i), typeDesc, false, null, false);
                        IRAssignment assignment = new IRAssignment(targetAccess, tempAccess, false);
                        assignment.setLocation(ret.getLineNumber(), ret.getColumnNumber());
                        replacementStmts.add(new IRExprStatement(assignment));
                    }

                    // 3. Jump to the start of the while loop
                    IRSkipStatement skip = new IRSkipStatement(loopLabel);
                    skip.setLocation(ret.getLineNumber(), ret.getColumnNumber());
                    replacementStmts.add(skip);

                    IRBlock replacementBlock = new IRBlock(replacementStmts);
                    replacementBlock.setLocation(ret.getLineNumber(), ret.getColumnNumber());
                    return replacementBlock;
                }
                return ret;
            }
            case IRIfStatement ifStmt -> {
                IRStatement newThen = transformBody(ifStmt.getThenBranch(), method, loopLabel);
                IRStatement newElse = ifStmt.getElseBranch() != null ? transformBody(ifStmt.getElseBranch(), method, loopLabel) : null;
                IRIfStatement newIf = new IRIfStatement(ifStmt.getCondition(), newThen, newElse);
                newIf.setLocation(ifStmt.getLineNumber(), ifStmt.getColumnNumber());
                return newIf;
            }
            case IRBlock block -> {
                List<IRStatement> stmts = new ArrayList<>();
                for (IRStatement s : block.getStatements()) {
                    stmts.add(transformBody(s, method, loopLabel));
                }
                IRBlock newBlock = new IRBlock(stmts);
                newBlock.setLocation(block.getLineNumber(), block.getColumnNumber());
                return newBlock;
            }
            case IRLabeledStatement labeled -> {
                IRLabeledStatement newLabeled = new IRLabeledStatement(labeled.getLabel(), transformBody(labeled.getStatement(), method, loopLabel));
                newLabeled.setLocation(labeled.getLineNumber(), labeled.getColumnNumber());
                return newLabeled;
            }
            case IRSwitchStatement switchStmt -> {
                List<IRSwitchCase> newCases = new ArrayList<>();
                if (switchStmt.getCases() != null) {
                    for (IRSwitchCase sc : switchStmt.getCases()) {
                        IRStatement newBody = (sc.getBody() != null && sc.getBody() instanceof IRStatement body)? transformBody(body, method, loopLabel) : null;
                        newCases.add(new IRSwitchCase(sc.getPatterns(), sc.getValues(), sc.getGuard(), newBody, sc.isArrow()));
                    }
                }
                IRStatement newDef = switchStmt.getDefaultBlock() != null ? transformBody(switchStmt.getDefaultBlock(), method, loopLabel) : null;
                IRSwitchStatement newSwitch = new IRSwitchStatement(switchStmt.getExpression(), newCases, newDef, switchStmt.isDefaultArrow());
                newSwitch.setLocation(switchStmt.getLineNumber(), switchStmt.getColumnNumber());
                return newSwitch;
            }
            case IRLockStatement lockStmt -> {
                IRStatement newBody = lockStmt.getBody() != null ? transformBody(lockStmt.getBody(), method, loopLabel) : null;
                IRLockStatement newLock = new IRLockStatement(lockStmt.getLockExpression(), newBody);
                newLock.setLocation(lockStmt.getLineNumber(), lockStmt.getColumnNumber());
                return newLock;
            }
            default -> {
            }
        }

        return stmt;
    }

    private void reportWarning(IRNode node, String message) {
        int line = node != null ? node.getLineNumber() : 0;
        int col = node != null ? node.getColumnNumber() : 0;
        CompilerReporter.warning(null, line, col, message, "IRTailRecOptimizer");
    }
}
