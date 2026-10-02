package ocean.compiler.ir;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ocean.compiler.CompilerRegistry;
import ocean.compiler.OceanTypeSystem;


/**
 * IR üzerinde Ölü Kod Ayıklama (Dead Code Elimination) yapan sınıf.
 */
public class IRDeadCodeEliminator extends BaseIRVisitor {

    public IRNode optimize(IRNode node) {
        if (node == null) return null;
        IRNode result = doOptimize(node);
        if (result != null && result != node) {
            result.setLocation(node.getLineNumber(), node.getColumnNumber());
        }
        return result;
    }

    private IRNode doOptimize(IRNode node) {
        switch (node) {
            case null -> {
                return null;
            }
            case IRBlock irBlock -> {
                return simplifyBlock(irBlock);
            }
            case IRIfStatement ifStmt -> {
                if (ifStmt.getCondition() instanceof IRLiteral && ((IRLiteral) ifStmt.getCondition()).getValue() instanceof Boolean) {
                    boolean cond = (Boolean) ((IRLiteral) ifStmt.getCondition()).getValue();
                    return cond ? optimize(ifStmt.getThenBranch()) : optimize(ifStmt.getElseBranch());
                }
                IRExpression condExpr = (IRExpression) optimize(ifStmt.getCondition());
                IRStatement thenBranch = (IRStatement) optimize(ifStmt.getThenBranch());
                IRStatement elseBranch = (IRStatement) optimize(ifStmt.getElseBranch());

                if (condExpr == ifStmt.getCondition() && thenBranch == ifStmt.getThenBranch() && elseBranch == ifStmt.getElseBranch()) {
                    return ifStmt;
                }
                return new IRIfStatement(condExpr != null ? condExpr : ifStmt.getCondition(), thenBranch, elseBranch);
            }
            case IRWhileStatement whileStmt -> {
                if (whileStmt.getCondition() instanceof IRLiteral && ((IRLiteral) whileStmt.getCondition()).getValue() instanceof Boolean) {
                    boolean cond = (Boolean) ((IRLiteral) whileStmt.getCondition()).getValue();
                    if (!cond) return null; // while(false) -> remove loop
                }
                IRExpression optCond = (IRExpression) optimize(whileStmt.getCondition());
                IRStatement optimizedBody = (IRStatement) optimize(whileStmt.getBody());
                if (optimizedBody instanceof IRBlock block) {
                    List<IRStatement> statements = block.getStatements();
                    List<IRStatement> hoistedDecls = new ArrayList<>();
                    List<IRStatement> remainingStmts = new ArrayList<>();
                    List<String> mutatedVars = new ArrayList<>();
                    collectMutations(block, mutatedVars);

                    for (IRStatement stmt : statements) {
                        if (stmt instanceof IRVariableDecl decl) {
                            if (decl.getInitialValue() instanceof IRNewObject newObj) {
                                String classPath = newObj.getClassName();
                                boolean argsLoopInvariant = true;
                                if (newObj.getArguments() != null) {
                                    for (IRExpression arg : newObj.getArguments()) {
                                        if (!isLoopInvariant(arg, null, mutatedVars)) {
                                            argsLoopInvariant = false;
                                            break;
                                        }
                                    }
                                }
                                if (tryHoistLoopInvariantDecl(block, hoistedDecls, decl, classPath, argsLoopInvariant)) continue;
                            }
                        }
                        remainingStmts.add(stmt);
                    }

                    if (!hoistedDecls.isEmpty()) {
                        IRWhileStatement newWhile = new IRWhileStatement(optCond != null ? optCond : whileStmt.getCondition(), new IRBlock(remainingStmts));
                        List<IRStatement> outerStmts = new ArrayList<>(hoistedDecls);
                        outerStmts.add(newWhile);
                        return simplifyBlock(new IRBlock(outerStmts));
                    }
                }
                if (optimizedBody == whileStmt.getBody() && optCond == whileStmt.getCondition()) {
                    return whileStmt;
                }
                return new IRWhileStatement(optCond != null ? optCond : whileStmt.getCondition(), optimizedBody);
            }
            case IRForStatement forStmt -> {
                IRExpression optFrom = (IRExpression) optimize(forStmt.getFromExpr());
                IRExpression optTo = (IRExpression) optimize(forStmt.getToExpr());
                IRExpression optStep = (IRExpression) optimize(forStmt.getStepExpr());
                IRExpression optIterable = (IRExpression) optimize(forStmt.getIterableExpr());
                IRStatement optimizedBody = (IRStatement) optimize(forStmt.getBody());
                if (optimizedBody instanceof IRBlock block) {
                    List<IRStatement> statements = block.getStatements();
                    List<IRStatement> hoistedDecls = new ArrayList<>();
                    List<IRStatement> remainingStmts = new ArrayList<>();
                    List<String> mutatedVars = new ArrayList<>();
                    collectMutations(block, mutatedVars);

                    for (IRStatement stmt : statements) {
                        if (stmt instanceof IRVariableDecl decl) {
                            if (decl.getInitialValue() instanceof IRNewObject newObj) {
                                String classPath = newObj.getClassName();
                                boolean argsLoopInvariant = true;
                                if (newObj.getArguments() != null) {
                                    for (IRExpression arg : newObj.getArguments()) {
                                        if (!isLoopInvariant(arg, forStmt.getIteratorName(), mutatedVars)) {
                                            argsLoopInvariant = false;
                                            break;
                                        }
                                    }
                                }
                                if (tryHoistLoopInvariantDecl(block, hoistedDecls, decl, classPath, argsLoopInvariant)) continue;
                            }
                        }
                        remainingStmts.add(stmt);
                    }

                    if (!hoistedDecls.isEmpty()) {
                        IRForStatement newFor;
                        if (forStmt.isRange()) {
                            newFor = new IRForStatement(forStmt.getIteratorName(), forStmt.getTypeDescriptor(),
                                    optFrom != null ? optFrom : forStmt.getFromExpr(),
                                    optTo != null ? optTo : forStmt.getToExpr(),
                                    optStep != null ? optStep : forStmt.getStepExpr(),
                                    forStmt.isIncreasing(), new IRBlock(remainingStmts));
                        } else {
                            newFor = new IRForStatement(forStmt.getIteratorName(), forStmt.getTypeDescriptor(),
                                    optIterable != null ? optIterable : forStmt.getIterableExpr(), new IRBlock(remainingStmts));
                        }
                        List<IRStatement> outerStmts = new ArrayList<>(hoistedDecls);
                        outerStmts.add(newFor);
                        return simplifyBlock(new IRBlock(outerStmts));
                    }
                }
                if (optimizedBody == forStmt.getBody() && optFrom == forStmt.getFromExpr() && optTo == forStmt.getToExpr() && optStep == forStmt.getStepExpr() && optIterable == forStmt.getIterableExpr()) {
                    return forStmt;
                }
                if (forStmt.isRange()) {
                    return new IRForStatement(forStmt.getIteratorName(), forStmt.getTypeDescriptor(),
                            optFrom != null ? optFrom : forStmt.getFromExpr(),
                            optTo != null ? optTo : forStmt.getToExpr(),
                            optStep != null ? optStep : forStmt.getStepExpr(),
                            forStmt.isIncreasing(), optimizedBody);
                } else {
                    return new IRForStatement(forStmt.getIteratorName(), forStmt.getTypeDescriptor(),
                            optIterable != null ? optIterable : forStmt.getIterableExpr(), optimizedBody);
                }
            }
            case IRCompilationUnit unit -> {
                List<IRNode> types = new ArrayList<>();
                boolean changed = false;
                for (IRNode type : unit.getTypes()) {
                    IRNode opt = optimize(type);
                    if (opt != null) {
                        types.add(opt);
                        if (opt != type) changed = true;
                    } else {
                        changed = true;
                    }
                }
                if (!changed) return unit;
                return new IRCompilationUnit(types);
            }
            case IRClass cls -> {
                boolean changed = false;
                List<IRField> fields = new ArrayList<>();
                for (IRField field : cls.getFields()) {
                    IRField opt = (IRField) optimize(field);
                    fields.add(opt);
                    if (opt != field) changed = true;
                }
                List<IRMethod> methods = new ArrayList<>();
                for (IRMethod method : cls.getMethods()) {
                    IRMethod opt = (IRMethod) optimize(method);
                    methods.add(opt);
                    if (opt != method) changed = true;
                }
                List<IRBlock> staticBlocks = new ArrayList<>();
                for (IRBlock staticBlock : cls.getStaticBlocks()) {
                    IRBlock opt = (IRBlock) optimize(staticBlock);
                    staticBlocks.add(opt);
                    if (opt != staticBlock) changed = true;
                }
                List<IRBlock> instanceBlocks = new ArrayList<>();
                for (IRBlock instanceBlock : cls.getInstanceBlocks()) {
                    IRBlock opt = (IRBlock) optimize(instanceBlock);
                    instanceBlocks.add(opt);
                    if (opt != instanceBlock) changed = true;
                }
                List<IRNode> instanceInitializers = new ArrayList<>();
                for (IRNode initNode : cls.getInstanceInitializers()) {
                    if (initNode instanceof IRField field) {
                        IRField opt = (IRField) optimize(field);
                        instanceInitializers.add(opt);
                        if (opt != field) changed = true;
                    } else if (initNode instanceof IRBlock block) {
                        IRBlock opt = (IRBlock) optimize(block);
                        instanceInitializers.add(opt);
                        if (opt != block) changed = true;
                    }
                }
                List<IRNode> staticInitializers = new ArrayList<>();
                for (IRNode initNode : cls.getStaticInitializers()) {
                    if (initNode instanceof IRField field) {
                        IRField opt = (IRField) optimize(field);
                        staticInitializers.add(opt);
                        if (opt != field) changed = true;
                    } else if (initNode instanceof IRBlock block) {
                        IRBlock opt = (IRBlock) optimize(block);
                        staticInitializers.add(opt);
                        if (opt != block) changed = true;
                    }
                }
                if (!changed) return cls;
                IRClass optimized = new IRClass(cls.getName(), cls.getSuperName(), cls.getInterfaces(), cls.isAbstract());
                optimized.setAccessFlags(cls.getAccessFlags());
                optimized.setDataClass(cls.isDataClass());
                optimized.setSealed(cls.isSealed());
                optimized.setNonSealed(cls.isNonSealed());
                for (String p : cls.getPermittedSubclasses()) optimized.addPermittedSubclass(p);
                for (IRAnnotation a : cls.getAnnotations()) optimized.addAnnotation(a);
                for (IRField f : fields) optimized.addField(f);
                for (IRMethod m : methods) optimized.addMethod(m);
                for (IRBlock sb : staticBlocks) optimized.addStaticBlock(sb);
                for (IRBlock ib : instanceBlocks) optimized.addInstanceBlock(ib);
                for (IRNode init : instanceInitializers) optimized.addInstanceInitializer(init);
                for (IRNode init : staticInitializers) optimized.addStaticInitializer(init);
                return optimized;
            }
            case IRInterface inter -> {
                List<IRMethod> methods = new ArrayList<>();
                boolean changed = false;
                for (IRMethod m : inter.getMethods()) {
                    IRMethod opt = (IRMethod) optimize(m);
                    methods.add(opt);
                    if (opt != m) changed = true;
                }
                if (!changed) return inter;
                IRInterface optInter = new IRInterface(inter.getName(), inter.getSuperInterfaces(), methods);
                optInter.setSealed(inter.isSealed());
                optInter.setNonSealed(inter.isNonSealed());
                for (String p : inter.getPermittedSubclasses()) optInter.addPermittedSubclass(p);
                for (IRAnnotation a : inter.getAnnotations()) optInter.addAnnotation(a);
                return optInter;
            }
            case IREnum enu -> {
                List<IRField> fields = new ArrayList<>();
                boolean changed = false;
                for (IRField f : enu.getFields()) {
                    IRField opt = (IRField) optimize(f);
                    fields.add(opt);
                    if (opt != f) changed = true;
                }
                List<IRMethod> methods = new ArrayList<>();
                for (IRMethod m : enu.getMethods()) {
                    IRMethod opt = (IRMethod) optimize(m);
                    methods.add(opt);
                    if (opt != m) changed = true;
                }
                List<List<IRExpression>> foldedArgs = new ArrayList<>();
                if (enu.getConstantArguments() != null) {
                    for (List<IRExpression> args : enu.getConstantArguments()) {
                        List<IRExpression> fArgs = new ArrayList<>();
                        for (IRExpression arg : args) {
                            IRExpression opt = (IRExpression) optimize(arg);
                            fArgs.add(opt);
                            if (opt != arg) changed = true;
                        }
                        foldedArgs.add(fArgs);
                    }
                } else {
                    foldedArgs = null;
                }
                if (!changed) return enu;
                IREnum optEnu = new IREnum(enu.getName(), enu.getConstants(), foldedArgs, fields, methods, enu.getInterfaces(), enu.getConstantClassNames());
                for (IRAnnotation a : enu.getAnnotations()) optEnu.addAnnotation(a);
                return optEnu;
            }
            case IRMethod method -> {
                IRStatement body = (IRStatement) optimize(method.getBody());
                if (body == null) {
                    body = new IRBlock(new ArrayList<>());
                }
                IRExpression optDefaultVal = method.getDefaultValue() != null ? (IRExpression) optimize(method.getDefaultValue()) : null;
                if (body == method.getBody() && optDefaultVal == method.getDefaultValue()) {
                    return method;
                }
                IRMethod optimized = new IRMethod(method.getName(), method.getDescriptor(), method.isStatic(), method.isAbstract(), body);
                optimized.setAccessFlags(method.getAccessFlags());
                optimized.setAsync(method.isAsync());
                optimized.setDefaultValue(optDefaultVal);
                for (IRAnnotation a : method.getAnnotations()) optimized.addAnnotation(a);
                for (String e : method.getExceptions()) optimized.addException(e);
                for (IRMethod.IRParameter p : method.getParameters()) optimized.addParameter(p);
                return optimized;
            }
            case IRField field -> {
                IRExpression init = field.getInitialValue() != null ? (IRExpression) optimize(field.getInitialValue()) : null;
                if (init == field.getInitialValue()) {
                    return field;
                }
                IRField optimizedField = new IRField(field.getName(), field.getTypeDescriptor(), field.isStatic(), init);
                optimizedField.setAccessFlags(field.getAccessFlags());
                for (IRAnnotation a : field.getAnnotations()) optimizedField.addAnnotation(a);
                return optimizedField;
            }
            case IRTryCatchStatement tc -> {
                IRStatement tryBlock = (IRStatement) optimize(tc.getTryBlock());
                List<IRTryCatchStatement.IRCatchClause> catchClauses = new ArrayList<>();
                boolean changed = false;
                if (tc.getCatchClauses() != null) {
                    for (IRTryCatchStatement.IRCatchClause cc : tc.getCatchClauses()) {
                        IRStatement cBody = (IRStatement) optimize(cc.body());
                        catchClauses.add(new IRTryCatchStatement.IRCatchClause(
                                cc.exceptionVar(), cc.exceptionTypes(), cBody
                        ));
                        if (cBody != cc.body()) changed = true;
                    }
                }
                IRStatement finallyBlock = (IRStatement) optimize(tc.getFinallyBlock());
                if (tryBlock == tc.getTryBlock() && finallyBlock == tc.getFinallyBlock() && !changed) {
                    return tc;
                }
                return new IRTryCatchStatement(tryBlock, catchClauses, finallyBlock);
            }
            case IRDoWhileStatement dw -> {
                IRExpression cond = (IRExpression) optimize(dw.getCondition());
                IRStatement body = (IRStatement) optimize(dw.getBody());
                if (cond == dw.getCondition() && body == dw.getBody()) {
                    return dw;
                }
                return new IRDoWhileStatement(cond, body);
            }
            case IRSwitchStatement sw -> {
                IRExpression expr = (IRExpression) optimize(sw.getExpression());
                List<IRSwitchCase> cases = new ArrayList<>();
                boolean changed = false;
                if (sw.getCases() != null) {
                    for (IRSwitchCase c : sw.getCases()) {
                        List<IRSwitchPattern> patterns = new ArrayList<>();
                        changed = optimizeSwitchPatterns(patterns,c,changed);
                        IRStatement cBody = (IRStatement) optimize(c.getBody());
                        IRExpression guard = (IRExpression) optimize(c.getGuard());
                        List<IRExpression> values = new ArrayList<>();
                        changed = optimizeSwitchValues(values,c,changed);
                        cases.add(new IRSwitchCase(patterns, values, guard, cBody, c.isArrow()));
                        if (cBody != c.getBody() || guard != c.getGuard()) changed = true;
                    }
                }
                IRStatement def = (IRStatement) optimize(sw.getDefaultBlock());
                if (expr == sw.getExpression() && def == sw.getDefaultBlock() && !changed) {
                    return sw;
                }
                return new IRSwitchStatement(expr != null ? expr : sw.getExpression(), cases, def, sw.isDefaultArrow());
            }
            case IRSwitchExpression swExpr -> {
                IRExpression expr = (IRExpression) optimize(swExpr.getExpression());
                List<IRSwitchCase> cases = new ArrayList<>();
                boolean changed = false;
                if (swExpr.getCases() != null) {
                    for (IRSwitchCase c : swExpr.getCases()) {
                        List<IRSwitchPattern> patterns = new ArrayList<>();
                        changed = optimizeSwitchPatterns(patterns,c,changed);
                        IRNode cBody = optimize(c.getBody());
                        IRExpression guard = (IRExpression) optimize(c.getGuard());
                        List<IRExpression> values = new ArrayList<>();
                        changed = optimizeSwitchValues(values,c,changed);
                        cases.add(new IRSwitchCase(patterns, values, guard, cBody,c.isArrow()));
                        if (cBody != c.getBody() || guard != c.getGuard()) changed = true;
                    }
                }
                IRNode def = optimize(swExpr.getDefaultBody());
                if (expr == swExpr.getExpression() && def == swExpr.getDefaultBody() && !changed) {
                    return swExpr;
                }
                return new IRSwitchExpression(expr != null ? expr : swExpr.getExpression(), cases, def, swExpr.getTypeDescriptor());
            }
            case IRTernaryExpression te -> {
                if (te.getCondition() instanceof IRLiteral lit && lit.getValue() instanceof Boolean b) {
                    return b ? optimize(te.getTrueExpr()) : optimize(te.getFalseExpr());
                }
                IRExpression cond = (IRExpression) optimize(te.getCondition());
                IRExpression trueE = (IRExpression) optimize(te.getTrueExpr());
                IRExpression falseE = (IRExpression) optimize(te.getFalseExpr());
                if (cond == te.getCondition() && trueE == te.getTrueExpr() && falseE == te.getFalseExpr()) {
                    return te;
                }
                return new IRTernaryExpression(
                        cond != null ? cond : te.getCondition(),
                        trueE != null ? trueE : te.getTrueExpr(),
                        falseE != null ? falseE : te.getFalseExpr(),
                        te.getTypeDescriptor(),
                        te.isNullCoalescing()
                );
            }
            case IRAssignment assign -> {
                IRExpression target = (IRExpression) optimize(assign.getTarget());
                IRExpression value = (IRExpression) optimize(assign.getValue());

                if (value instanceof IRBinaryOp bin && isLiteralZero(bin.getRight())) {
                    IRBinaryOp.Op op = bin.getOperator();
                    if (op == IRBinaryOp.Op.BIT_OR || op == IRBinaryOp.Op.BIT_XOR ||
                        op == IRBinaryOp.Op.LSHIFT || op == IRBinaryOp.Op.RSHIFT || op == IRBinaryOp.Op.URSHIFT) {
                        value = bin.getLeft();
                    }
                }

                if (target == assign.getTarget() && value == assign.getValue()) {
                    return assign;
                }
                return new IRAssignment(target, value, assign.isStatic(), assign.isCompound());
            }
            case IRExprStatement stmt -> {
                IRExpression expr = (IRExpression) optimize(stmt.getExpression());

                if (expr == stmt.getExpression()) {
                    return stmt;
                }
                return new IRExprStatement(expr);
            }
            case IRVariableDecl decl -> {
                IRExpression init = decl.getInitialValue() != null ? (IRExpression) optimize(decl.getInitialValue()) : null;
                if (init == decl.getInitialValue()) {
                    return decl;
                }
                return new IRVariableDecl(decl.getName(), decl.getTypeDescriptor(), init, decl.isFinal());
            }
            case IRReturnStatement ret -> {
                IRExpression val = ret.getExpression() != null ? (IRExpression) optimize(ret.getExpression()) : null;
                if (val == ret.getExpression()) {
                    return ret;
                }
                return new IRReturnStatement(val);
            }
            case IRLambdaExpression lambda -> {
                IRNode body = optimize(lambda.getBody());
                if (body == lambda.getBody()) {
                    return lambda;
                }
                return new IRLambdaExpression(
                        lambda.getTargetInterface(),
                        lambda.getSamMethodName(),
                        lambda.getSamMethodDesc(),
                        lambda.getParameterNames(),
                        lambda.getParameterTypes(),
                        body,
                        lambda.getCapturedNames(),
                        lambda.getCapturedTypes(),
                        lambda.getLambdaMethodName(),
                        lambda.isStatic()
                );
            }
            case IRLockStatement lock -> {
                IRExpression expr = (IRExpression) optimize(lock.getLockExpression());
                IRStatement body = (IRStatement) optimize(lock.getBody());
                if (expr == lock.getLockExpression() && body == lock.getBody()) {
                    return lock;
                }
                return new IRLockStatement(expr, body);
            }
            case IRLabeledStatement labeled -> {
                IRStatement body = (IRStatement) optimize(labeled.getStatement());
                if (body == labeled.getStatement()) {
                    return labeled;
                }
                return new IRLabeledStatement(labeled.getLabel(), body);
            }
            case IRVerifyStatement verifyStatement -> {
                IRExpression cond = (IRExpression) optimize(verifyStatement.getCondition());
                IRExpression detail = verifyStatement.getDetailMessage() != null ? (IRExpression) optimize(verifyStatement.getDetailMessage()) : null;
                if (cond == verifyStatement.getCondition() && detail == verifyStatement.getDetailMessage()) {
                    return verifyStatement;
                }
                return new IRVerifyStatement(cond != null ? cond : verifyStatement.getCondition(), detail);
            }
            default -> {
            }
        }

        return node;
    }

    private boolean optimizeSwitchPatterns(List<IRSwitchPattern> patterns,IRSwitchCase c ,boolean changed) {
        if (c.getPatterns() != null) {
            for (IRSwitchPattern p : c.getPatterns()) {
                IRSwitchPattern optP = optimizeSwitchPattern(p);
                patterns.add(optP);
                if (optP != p) changed = true;
            }
        }
        return changed;
    }

    private boolean optimizeSwitchValues(List<IRExpression> values,IRSwitchCase c ,boolean changed) {
        for (IRExpression v : c.getValues()) {
            IRExpression optV = (IRExpression) optimize(v);
            values.add(optV);
            if (optV != v) changed = true;
        }
        return changed;
    }

    private boolean tryHoistLoopInvariantDecl(IRBlock block, List<IRStatement> hoistedDecls, IRVariableDecl decl, String classPath, boolean argsLoopInvariant) {
        // Disallow hoisting object allocations (new) out of loops to preserve constructor side-effects and instance identity
        return false;
    }

    private IRBlock simplifyBlock(IRBlock block) {
        List<IRStatement> simplified = new ArrayList<>();

        boolean changed = false;
        for (IRStatement stmt : block.getStatements()) {
            IRNode opt = optimize(stmt);
            if (opt == null) {
                changed = true;
                continue;
            }
            if (opt instanceof IRStatement) {
                simplified.add((IRStatement) opt);
                if (opt != stmt) changed = true;
                if (alwaysTerminates(opt)) {
                    if (simplified.size() < block.getStatements().size()) {
                        changed = true;
                    }
                    if (!changed) return block;
                    return new IRBlock(simplified);
                }
            } else {
                changed = true;
            }
        }
        if (!changed) return block;
        return new IRBlock(simplified);
    }

    private boolean alwaysTerminates(IRNode node) {
        if (node == null) return false;
        if (node instanceof IRReturnStatement
                || node instanceof IRThrowStatement
                || node instanceof IRStopStatement
                || node instanceof IRSkipStatement) {
            return true;
        }
        switch (node) {
            case IRBlock irBlock -> {
                for (IRStatement stmt : irBlock.getStatements()) {
                    if (alwaysTerminates(stmt)) return true;
                }
                return false;
            }
            case IRIfStatement ifStmt -> {
                return ifStmt.getThenBranch() != null && ifStmt.getElseBranch() != null
                        && alwaysTerminates(ifStmt.getThenBranch())
                        && alwaysTerminates(ifStmt.getElseBranch());
            }
            case IRTryCatchStatement tryCatch -> {
                boolean tryTerminates = alwaysTerminates(tryCatch.getTryBlock());
                if (tryTerminates) {
                    boolean allCatchesTerminate = true;
                    if (tryCatch.getCatchClauses() != null) {
                        for (IRTryCatchStatement.IRCatchClause cc : tryCatch.getCatchClauses()) {
                            if (!alwaysTerminates(cc.body())) {
                                allCatchesTerminate = false;
                                break;
                            }
                        }
                    }
                    if (allCatchesTerminate) return true;
                }
                if (alwaysTerminates(tryCatch.getFinallyBlock())) return true;
            }
            default -> {
            }
        }
        if (node instanceof IRLockStatement) {
            return alwaysTerminates(((IRLockStatement) node).getBody());
        }
        return false;
    }

    private boolean hasInstanceFields(String classPath) {
        if (classPath == null || "java/lang/Object".equals(classPath)) {
            return false;
        }
        Map<String, String> fields = CompilerRegistry.globalFieldRegistry.get(classPath);
        if (fields != null) {
            Map<String, Boolean> staticity = CompilerRegistry.globalFieldStaticity.get(classPath);
            for (String fieldName : fields.keySet()) {
                boolean isStatic = (staticity != null) && staticity.getOrDefault(fieldName, false);
                if (!isStatic) {
                    return true;
                }
            }
        }
        String superName = CompilerRegistry.globalSuperClassRegistry.get(classPath);
        if (superName != null) {
            return hasInstanceFields(superName);
        }
        try {
            Class<?> clazz = OceanTypeSystem.forName(classPath.replace("/", "."));
            if (clazz != null) {
                for (Field f : clazz.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) {
                        return true;
                    }
                }
                Class<?> sup = clazz.getSuperclass();
                if (sup != null && !sup.getName().equals("java.lang.Object")) {
                    return hasInstanceFields(sup.getName().replace(".", "/"));
                }
                return false;
            }
        } catch (Throwable ignored) {}
        return true;
    }

    private boolean isLoopInvariant(IRNode expr, String loopVar, List<String> mutatedVars) {
        switch (expr) {
            case null -> {
                return true;
            }
            case IRLiteral irLiteral -> {
                return true;
            }
            case IRVariableAccess irVariableAccess -> {
                String name = irVariableAccess.getName();
                return !name.equals(loopVar) && !mutatedVars.contains(name);
            }
            default -> {
                return !containsVariableOrMutated(expr, loopVar, mutatedVars);
            }
        }
    }

    private boolean containsVariableOrMutated(IRNode node, String varName, List<String> mutatedVars) {
        switch (node) {
            case null -> {
                return false;
            }
            case IRVariableAccess irVariableAccess -> {
                String name = irVariableAccess.getName();
                return name.equals(varName) || mutatedVars.contains(name);
            }
            case IRAssignment assign -> {
                return containsVariableOrMutated(assign.getTarget(), varName, mutatedVars)
                        || containsVariableOrMutated(assign.getValue(), varName, mutatedVars);
            }
            case IRBlock irBlock -> {
                for (IRStatement stmt : irBlock.getStatements()) {
                    if (containsVariableOrMutated(stmt, varName, mutatedVars)) return true;
                }
                return false;
            }
            case IRIfStatement ifStmt -> {
                return containsVariableOrMutated(ifStmt.getCondition(), varName, mutatedVars)
                        || containsVariableOrMutated(ifStmt.getThenBranch(), varName, mutatedVars)
                        || containsVariableOrMutated(ifStmt.getElseBranch(), varName, mutatedVars);
            }
            case IRWhileStatement whileStmt -> {
                return containsVariableOrMutated(whileStmt.getCondition(), varName, mutatedVars)
                        || containsVariableOrMutated(whileStmt.getBody(), varName, mutatedVars);
            }
            case IRDoWhileStatement dw -> {
                return containsVariableOrMutated(dw.getCondition(), varName, mutatedVars)
                        || containsVariableOrMutated(dw.getBody(), varName, mutatedVars);
            }
            case IRForStatement forStmt -> {
                return containsVariableOrMutated(forStmt.getFromExpr(), varName, mutatedVars)
                        || containsVariableOrMutated(forStmt.getToExpr(), varName, mutatedVars)
                        || containsVariableOrMutated(forStmt.getStepExpr(), varName, mutatedVars)
                        || containsVariableOrMutated(forStmt.getIterableExpr(), varName, mutatedVars)
                        || containsVariableOrMutated(forStmt.getBody(), varName, mutatedVars);
            }
            case IRMethodCall mc -> {
                if (containsVariableOrMutated(mc.getReceiver(), varName, mutatedVars)) return true;
                if (mc.getArguments() != null) {
                    for (IRExpression arg : mc.getArguments()) {
                        if (containsVariableOrMutated(arg, varName, mutatedVars)) return true;
                    }
                }
                return false;
            }
            case IRNewObject no -> {
                if (no.getArguments() != null) {
                    for (IRExpression arg : no.getArguments()) {
                        if (containsVariableOrMutated(arg, varName, mutatedVars)) return true;
                    }
                }
                return false;
            }
            case IRUnaryOp irUnaryOp -> {
                return containsVariableOrMutated(irUnaryOp.getExpression(), varName, mutatedVars);
            }
            case IRBinaryOp bo -> {
                return containsVariableOrMutated(bo.getLeft(), varName, mutatedVars)
                        || containsVariableOrMutated(bo.getRight(), varName, mutatedVars);
            }
            case IRCastExpression irCastExpression -> {
                return containsVariableOrMutated(irCastExpression.getExpression(), varName, mutatedVars);
            }
            case IRReturnStatement irReturnStatement -> {
                return containsVariableOrMutated(irReturnStatement.getExpression(), varName, mutatedVars);
            }
            case IRThrowStatement irThrowStatement -> {
                return containsVariableOrMutated(irThrowStatement.getExpression(), varName, mutatedVars);
            }
            case IRExprStatement irExprStatement -> {
                return containsVariableOrMutated(irExprStatement.getExpression(), varName, mutatedVars);
            }
            case IRVariableDecl irVariableDecl -> {
                return containsVariableOrMutated(irVariableDecl.getInitialValue(), varName, mutatedVars);
            }
            case IRTernaryExpression te -> {
                return containsVariableOrMutated(te.getCondition(), varName, mutatedVars)
                        || containsVariableOrMutated(te.getTrueExpr(), varName, mutatedVars)
                        || containsVariableOrMutated(te.getFalseExpr(), varName, mutatedVars);
            }
            case IRLambdaExpression irLambdaExpression -> {
                return containsVariableOrMutated(irLambdaExpression.getBody(), varName, mutatedVars);
            }
            case IRTryCatchStatement tc -> {
                if (containsVariableOrMutated(tc.getTryBlock(), varName, mutatedVars)) return true;
                if (tc.getCatchClauses() != null) {
                    for (IRTryCatchStatement.IRCatchClause cc : tc.getCatchClauses()) {
                        if (containsVariableOrMutated(cc.body(), varName, mutatedVars)) return true;
                    }
                }
                return containsVariableOrMutated(tc.getFinallyBlock(), varName, mutatedVars);
            }
            case IRSwitchStatement sw -> {
                if (controlSwitchContainsOrMutated(varName, mutatedVars, sw.getExpression(), sw.getCases())) return true;
                return containsVariableOrMutated(sw.getDefaultBlock(), varName, mutatedVars);
            }
            case IRSwitchExpression swExpr -> {
                if (controlSwitchContainsOrMutated(varName, mutatedVars, swExpr.getExpression(), swExpr.getCases()))
                    return true;
                return containsVariableOrMutated(swExpr.getDefaultBody(), varName, mutatedVars);
            }
            case IRLockStatement lock -> {
                return containsVariableOrMutated(lock.getLockExpression(), varName, mutatedVars)
                        || containsVariableOrMutated(lock.getBody(), varName, mutatedVars);
            }
            default -> {
            }
        }
        return false;
    }

    private boolean controlSwitchContainsOrMutated(String varName, List<String> mutatedVars, IRExpression expression, List<IRSwitchCase> cases) {
        if (containsVariableOrMutated(expression, varName, mutatedVars)) return true;
        if (cases != null) {
            for (IRSwitchCase c : cases) {
                if (c.getValues() != null) {
                    for (IRExpression v : c.getValues()) {
                        if (containsVariableOrMutated(v, varName, mutatedVars)) return true;
                    }
                }
                if (containsVariableOrMutated(c.getGuard(), varName, mutatedVars)) return true;
                if (containsVariableOrMutated(c.getBody(), varName, mutatedVars)) return true;
            }
        }
        return false;
    }

    private boolean containsVariable(IRNode node, String varName) {
        switch (node) {
            case null -> {
                return false;
            }
            case IRVariableAccess irVariableAccess -> {
                return varName.equals(irVariableAccess.getName());
            }
            case IRAssignment assign -> {
                return containsVariable(assign.getTarget(), varName) || containsVariable(assign.getValue(), varName);
            }
            case IRBlock irBlock -> {
                for (IRStatement stmt : irBlock.getStatements()) {
                    if (containsVariable(stmt, varName)) return true;
                }
                return false;
            }
            case IRIfStatement ifStmt -> {
                return containsVariable(ifStmt.getCondition(), varName)
                        || containsVariable(ifStmt.getThenBranch(), varName)
                        || containsVariable(ifStmt.getElseBranch(), varName);
            }
            case IRWhileStatement whileStmt -> {
                return containsVariable(whileStmt.getCondition(), varName)
                        || containsVariable(whileStmt.getBody(), varName);
            }
            case IRDoWhileStatement dw -> {
                return containsVariable(dw.getCondition(), varName)
                        || containsVariable(dw.getBody(), varName);
            }
            case IRForStatement forStmt -> {
                return containsVariable(forStmt.getFromExpr(), varName)
                        || containsVariable(forStmt.getToExpr(), varName)
                        || containsVariable(forStmt.getStepExpr(), varName)
                        || containsVariable(forStmt.getIterableExpr(), varName)
                        || containsVariable(forStmt.getBody(), varName);
            }
            case IRMethodCall mc -> {
                if (containsVariable(mc.getReceiver(), varName)) return true;
                if (mc.getArguments() != null) {
                    for (IRExpression arg : mc.getArguments()) {
                        if (containsVariable(arg, varName)) return true;
                    }
                }
                return false;
            }
            case IRNewObject no -> {
                if (no.getArguments() != null) {
                    for (IRExpression arg : no.getArguments()) {
                        if (containsVariable(arg, varName)) return true;
                    }
                }
                return false;
            }
            case IRUnaryOp irUnaryOp -> {
                return containsVariable(irUnaryOp.getExpression(), varName);
            }
            case IRBinaryOp bo -> {
                return containsVariable(bo.getLeft(), varName) || containsVariable(bo.getRight(), varName);
            }
            case IRCastExpression irCastExpression -> {
                return containsVariable(irCastExpression.getExpression(), varName);
            }
            case IRReturnStatement irReturnStatement -> {
                return containsVariable(irReturnStatement.getExpression(), varName);
            }
            case IRThrowStatement irThrowStatement -> {
                return containsVariable(irThrowStatement.getExpression(), varName);
            }
            case IRExprStatement irExprStatement -> {
                return containsVariable(irExprStatement.getExpression(), varName);
            }
            case IRVariableDecl irVariableDecl -> {
                return containsVariable(irVariableDecl.getInitialValue(), varName);
            }
            case IRTernaryExpression te -> {
                return containsVariable(te.getCondition(), varName)
                        || containsVariable(te.getTrueExpr(), varName)
                        || containsVariable(te.getFalseExpr(), varName);
            }
            case IRLambdaExpression irLambdaExpression -> {
                return containsVariable(irLambdaExpression.getBody(), varName);
            }
            case IRTryCatchStatement tc -> {
                if (containsVariable(tc.getTryBlock(), varName)) return true;
                if (tc.getCatchClauses() != null) {
                    for (IRTryCatchStatement.IRCatchClause cc : tc.getCatchClauses()) {
                        if (containsVariable(cc.body(), varName)) return true;
                    }
                }
                return containsVariable(tc.getFinallyBlock(), varName);
            }
            case IRSwitchStatement sw -> {
                if (controlSwitchContainsVairable(varName, sw.getExpression(), sw.getCases())) return true;
                return containsVariable(sw.getDefaultBlock(), varName);
            }
            case IRSwitchExpression swExpr -> {
                if (controlSwitchContainsVairable(varName, swExpr.getExpression(), swExpr.getCases())) return true;
                return containsVariable(swExpr.getDefaultBody(), varName);
            }
            case IRLockStatement lock -> {
                return containsVariable(lock.getLockExpression(), varName)
                        || containsVariable(lock.getBody(), varName);
            }
            case IRArrayLiteral al -> {
                if (al.getElements() != null) {
                    for (IRExpression e : al.getElements()) {
                        if (containsVariable(e, varName)) return true;
                    }
                }
                return false;
            }
            case IRArrayAccess aa -> {
                return containsVariable(aa.getArray(), varName) || containsVariable(aa.getIndex(), varName);
            }
            case IRArrayCreation ac -> {
                if (ac.getSizes() != null) {
                    for (IRExpression s : ac.getSizes()) {
                        if (containsVariable(s, varName)) return true;
                    }
                }
                return false;
            }
            case IRInterpolatedString is -> {
                if (is.getParts() != null) {
                    for (IRNode p : is.getParts()) {
                        if (containsVariable(p, varName)) return true;
                    }
                }
                return false;
            }
            case IRAwaitExpression await -> {
                return containsVariable(await.getFuture(), varName);
            }
            case IROceanOutput out -> {
                if (out.getArguments() != null) {
                    for (IRExpression a : out.getArguments()) {
                        if (containsVariable(a, varName)) return true;
                    }
                }
                return false;
            }
            case IRInstanceof inst -> {
                return containsVariable(inst.getExpression(), varName);
            }
            case IRLabeledStatement labeled -> {
                return containsVariable(labeled.getStatement(), varName);
            }
            case IRResultStatement res -> {
                return containsVariable(res.getExpression(), varName);
            }
            default -> {
            }
        }
        return false;
    }

    private boolean controlSwitchContainsVairable(String varName, IRExpression expression, List<IRSwitchCase> cases) {
        if (containsVariable(expression, varName)) return true;
        if (cases != null) {
            for (IRSwitchCase c : cases) {
                if (c.getValues() != null) {
                    for (IRExpression v : c.getValues()) {
                        if (containsVariable(v, varName)) return true;
                    }
                }
                if (containsVariable(c.getGuard(), varName)) return true;
                if (containsVariable(c.getBody(), varName)) return true;
            }
        }
        return false;
    }

    private void collectMutations(IRNode node, List<String> mutated) {
        switch (node) {
            case IRExprStatement irExprStatement -> collectMutations(irExprStatement.getExpression(), mutated);
            case IRVariableDecl irVariableDecl -> {
                mutated.add(irVariableDecl.getName());
                collectMutations(irVariableDecl.getInitialValue(), mutated);
            }
            case IRUnaryOp unary -> {
                if (unary.getOperator() == IRUnaryOp.Op.PRE_INC || unary.getOperator() == IRUnaryOp.Op.POST_INC
                        || unary.getOperator() == IRUnaryOp.Op.PRE_DEC || unary.getOperator() == IRUnaryOp.Op.POST_DEC) {
                    if (unary.getExpression() instanceof IRVariableAccess) {
                        mutated.add(((IRVariableAccess) unary.getExpression()).getName());
                    }
                }
            }
            case IRAssignment assign -> {
                if (assign.getTarget() instanceof IRVariableAccess) {
                    mutated.add(((IRVariableAccess) assign.getTarget()).getName());
                }
                collectMutations(assign.getTarget(), mutated);
                collectMutations(assign.getValue(), mutated);
            }
            case IRBlock irBlock -> {
                for (IRStatement stmt : irBlock.getStatements()) {
                    collectMutations(stmt, mutated);
                }
            }
            case IRIfStatement ifStmt -> {
                collectMutations(ifStmt.getThenBranch(), mutated);
                collectMutations(ifStmt.getElseBranch(), mutated);
            }
            case IRWhileStatement irWhileStatement -> collectMutations(irWhileStatement.getBody(), mutated);
            case IRDoWhileStatement irDoWhileStatement -> collectMutations(irDoWhileStatement.getBody(), mutated);
            case IRForStatement irForStatement -> collectMutations(irForStatement.getBody(), mutated);
            case IRTryCatchStatement tc -> {
                collectMutations(tc.getTryBlock(), mutated);
                if (tc.getCatchClauses() != null) {
                    for (IRTryCatchStatement.IRCatchClause cc : tc.getCatchClauses()) {
                        collectMutations(cc.body(), mutated);
                    }
                }
                collectMutations(tc.getFinallyBlock(), mutated);
            }
            case IRSwitchStatement sw -> {
                collectMutations(sw.getExpression(), mutated);
                if (sw.getCases() != null) {
                    for (IRSwitchCase c : sw.getCases()) {
                        collectMutations(c.getGuard(), mutated);
                        collectMutations(c.getBody(), mutated);
                    }
                }
                collectMutations(sw.getDefaultBlock(), mutated);
            }
            case IRSwitchExpression swExpr -> {
                collectMutations(swExpr.getExpression(), mutated);
                if (swExpr.getCases() != null) {
                    for (IRSwitchCase c : swExpr.getCases()) {
                        collectMutations(c.getGuard(), mutated);
                        collectMutations(c.getBody(), mutated);
                    }
                }
                collectMutations(swExpr.getDefaultBody(), mutated);
            }
            case IRLockStatement irLockStatement -> collectMutations(irLockStatement.getBody(), mutated);
            case IRLabeledStatement labeled -> collectMutations(labeled.getStatement(), mutated);
            case null, default -> {
            }
        }
    }

    private static class LoopUsageInfo {
        boolean reassigned = false;
        boolean escapes = false;
    }

    private void analyzeUsage(IRNode node, String varName, LoopUsageInfo info) {
        switch (node) {
            case IRExprStatement irExprStatement -> analyzeUsage(irExprStatement.getExpression(), varName, info);
            case IRVariableDecl irVariableDecl -> analyzeUsage(irVariableDecl.getInitialValue(), varName, info);
            case IRThrowStatement irThrowStatement -> {
                if (containsVariable(irThrowStatement.getExpression(), varName)) {
                    info.escapes = true;
                }
            }
            case IRUnaryOp unary -> {
                if (unary.getOperator() == IRUnaryOp.Op.PRE_INC || unary.getOperator() == IRUnaryOp.Op.POST_INC
                        || unary.getOperator() == IRUnaryOp.Op.PRE_DEC || unary.getOperator() == IRUnaryOp.Op.POST_DEC) {
                    if (unary.getExpression() instanceof IRVariableAccess) {
                        if (varName.equals(((IRVariableAccess) unary.getExpression()).getName())) {
                            info.reassigned = true;
                        }
                    }
                }
            }
            case IRAssignment assign -> {
                if (assign.getTarget() instanceof IRVariableAccess varAccess) {
                    if (varName.equals(varAccess.getName())) {
                        info.reassigned = true;
                    }
                }
                if (!(assign.getTarget() instanceof IRVariableAccess)) {
                    if (containsVariable(assign.getTarget(), varName)) {
                        info.escapes = true;
                    }
                }
                if (containsVariable(assign.getValue(), varName)) {
                    info.escapes = true;
                }
            }
            case IRMethodCall mc -> {
                analyzeUsage(mc.getReceiver(), varName, info);
                if (mc.getArguments() != null) {
                    for (IRExpression arg : mc.getArguments()) {
                        if (containsVariable(arg, varName)) {
                            info.escapes = true;
                        }
                    }
                }
            }
            case IRNewObject no -> {
                if (no.getArguments() != null) {
                    for (IRExpression arg : no.getArguments()) {
                        if (containsVariable(arg, varName)) {
                            info.escapes = true;
                        }
                    }
                }
            }
            case IRReturnStatement ret -> {
                if (containsVariable(ret.getExpression(), varName)) {
                    info.escapes = true;
                }
            }
            case IRBlock irBlock -> {
                for (IRStatement stmt : irBlock.getStatements()) {
                    analyzeUsage(stmt, varName, info);
                }
            }
            case IRIfStatement ifStmt -> {
                analyzeUsage(ifStmt.getThenBranch(), varName, info);
                analyzeUsage(ifStmt.getElseBranch(), varName, info);
            }
            case IRWhileStatement irWhileStatement -> analyzeUsage(irWhileStatement.getBody(), varName, info);
            case IRDoWhileStatement irDoWhileStatement -> analyzeUsage(irDoWhileStatement.getBody(), varName, info);
            case IRForStatement irForStatement -> analyzeUsage(irForStatement.getBody(), varName, info);
            case IRTryCatchStatement tc -> {
                analyzeUsage(tc.getTryBlock(), varName, info);
                if (tc.getCatchClauses() != null) {
                    for (IRTryCatchStatement.IRCatchClause cc : tc.getCatchClauses()) {
                        analyzeUsage(cc.body(), varName, info);
                    }
                }
                analyzeUsage(tc.getFinallyBlock(), varName, info);
            }
            case IRSwitchStatement sw -> {
                analyzeUsage(sw.getExpression(), varName, info);
                if (sw.getCases() != null) {
                    for (IRSwitchCase c : sw.getCases()) {
                        analyzeUsage(c.getGuard(), varName, info);
                        analyzeUsage(c.getBody(), varName, info);
                    }
                }
                analyzeUsage(sw.getDefaultBlock(), varName, info);
            }
            case IRSwitchExpression swExpr -> {
                analyzeUsage(swExpr.getExpression(), varName, info);
                if (swExpr.getCases() != null) {
                    for (IRSwitchCase c : swExpr.getCases()) {
                        analyzeUsage(c.getGuard(), varName, info);
                        analyzeUsage(c.getBody(), varName, info);
                    }
                }
                analyzeUsage(swExpr.getDefaultBody(), varName, info);
            }
            case IRLockStatement lock -> {
                if (containsVariable(lock.getLockExpression(), varName)) {
                    info.escapes = true;
                }
                analyzeUsage(lock.getBody(), varName, info);
            }
            case IRArrayLiteral al -> {
                if (al.getElements() != null) {
                    for (IRExpression e : al.getElements()) {
                        if (containsVariable(e, varName)) info.escapes = true;
                    }
                }
            }
            case IRArrayAccess aa -> {
                if (containsVariable(aa.getArray(), varName) || containsVariable(aa.getIndex(), varName)) info.escapes = true;
            }
            case IRArrayCreation ac -> {
                if (ac.getSizes() != null) {
                    for (IRExpression s : ac.getSizes()) {
                        if (containsVariable(s, varName)) info.escapes = true;
                    }
                }
            }
            case IRInterpolatedString is -> {
                if (is.getParts() != null) {
                    for (IRNode p : is.getParts()) {
                        if (containsVariable(p, varName)) info.escapes = true;
                    }
                }
            }
            case IRAwaitExpression await -> {
                if (containsVariable(await.getFuture(), varName)) info.escapes = true;
            }
            case IROceanOutput out -> {
                if (out.getArguments() != null) {
                    for (IRExpression a : out.getArguments()) {
                        if (containsVariable(a, varName)) info.escapes = true;
                    }
                }
            }
            case IRInstanceof inst -> {
                if (containsVariable(inst.getExpression(), varName)) info.escapes = true;
            }
            case IRLabeledStatement labeled -> analyzeUsage(labeled.getStatement(), varName, info);
            case IRResultStatement res -> {
                if (containsVariable(res.getExpression(), varName)) info.escapes = true;
            }
            case null, default -> {
            }
        }
    }

    private IRSwitchPattern optimizeSwitchPattern(IRSwitchPattern p) {
        if (p == null) return null;
        IRExpression optExpr = p.getExpression() != null ? (IRExpression) optimize(p.getExpression()) : null;
        IRExpression optGuard = p.getGuard() != null ? (IRExpression) optimize(p.getGuard()) : null;
        List<IRSwitchPattern> optNested = new ArrayList<>();
        boolean nestedChanged = false;
        if (p.getNestedPatterns() != null) {
            for (IRSwitchPattern np : p.getNestedPatterns()) {
                IRSwitchPattern optNp = optimizeSwitchPattern(np);
                optNested.add(optNp);
                if (optNp != np) nestedChanged = true;
            }
        }
        if (optExpr == p.getExpression() && optGuard == p.getGuard() && !nestedChanged) {
            return p;
        }
        return new IRSwitchPattern(p.getKind(), p.getTypeDescriptor(), p.getVariableName(), optExpr, optNested, optGuard);
    }

    private boolean isLiteralZero(IRExpression expr) {
        if (expr instanceof IRLiteral lit && lit.getValue() instanceof Number num) {
            return num.longValue() == 0 && num.doubleValue() == 0.0;
        }
        return false;
    }
}
