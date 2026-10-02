package ocean.compiler.ir;

import java.util.*;

import ocean.compiler.CompilerReporter;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Opcodes;

/**
 * Single-Call-Site Private Method Inliner Pass.
 * Identifies private helper methods called exactly once, and inlines their body directly
 * into the call-site, eliminating JVM method call stack frame overhead.
 */
public class IRMethodInliner extends BaseIRVisitor {

    public IRNode optimize(IRNode node) {
        switch (node) {
            case null -> {
                return null;
            }
            case IRCompilationUnit unit -> {
                List<IRNode> types = new ArrayList<>();
                for (IRNode type : unit.getTypes()) {
                    types.add(optimize(type));
                }
                return new IRCompilationUnit(types);
            }
            case IRClass cls -> {
                return processClass(cls);
            }
            default -> {
            }
        }

        return node;
    }

    private boolean hasInlineAnnotation(IRMethod m) {
        if (m == null || m.getAnnotations() == null) return false;
        for (IRAnnotation anno : m.getAnnotations()) {
            String desc = anno.getTypeDescriptor();
            if (desc != null && (desc.equalsIgnoreCase("Inline") || desc.equalsIgnoreCase("LInline;") || desc.equalsIgnoreCase("@Inline") || desc.endsWith("/Inline;"))) {
                return true;
            }
        }
        return false;
    }

    private String currentClassName;
    private final Set<String> remainingCalls = new HashSet<>();

    private boolean isThisClassCall(IRMethodCall mc) {
        if (mc == null) return false;
        if (mc.isSuperCall()) return false;
        if (mc.getOwner() != null && !mc.getOwner().isEmpty() && currentClassName != null && !mc.getOwner().equals(currentClassName)) {
            return false;
        }
        if (mc.getReceiver() == null || mc.isThisCall()) {
            return true;
        }
        return mc.getReceiver() instanceof IRVariableAccess va && "this".equals(va.getName());
    }

    private IRClass processClass(IRClass cls) {
        this.currentClassName = cls.getName();
        this.remainingCalls.clear();
        Map<String, Integer> callCounts = new HashMap<>();
        Map<String, IRMethod> candidateMethods = new HashMap<>();
        Set<String> recursiveMethods = new HashSet<>();

        // 1. Scan for candidate methods (auto-private or explicit @Inline)
        for (IRMethod m : cls.getMethods()) {
            if (m.isAbstract() || m.getBody() == null) continue;
            boolean isPrivate = (m.getAccessFlags() & Opcodes.ACC_PRIVATE) != 0;
            boolean isExplicitInline = hasInlineAnnotation(m);

            if (isPrivate || isExplicitInline) {
                //record should be name + desc
                candidateMethods.put(m.getName()+"#"+m.getDescriptor(), m);
            }
        }

        if (candidateMethods.isEmpty()) {
            return cls;
        }

        // 2. Count method calls across all methods in class
        for (IRMethod m : cls.getMethods()) {
            countMethodCalls(m.getBody(), cls.getName(), callCounts, recursiveMethods, m.getName() + "#" + m.getDescriptor());
        }

        // 3. Identify inlinable methods (auto-private callCount == 1 OR explicit @Inline with warning fallback)
        Set<String> inlinableNames = new HashSet<>();
        Map<String, IRMethod> inlinableMap = new HashMap<>();

        for (Map.Entry<String, IRMethod> entry : candidateMethods.entrySet()) {
            String name = entry.getKey();
            IRMethod method = entry.getValue();
            int count = callCounts.getOrDefault(name, 0);
            boolean isPrivate = (method.getAccessFlags() & Opcodes.ACC_PRIVATE) != 0;
            boolean isExplicitInline = hasInlineAnnotation(method);

            boolean isRecursive = recursiveMethods.contains(name);
            boolean validBody = isInlinableBody(method.getBody());

            if (isExplicitInline) {
                if (isRecursive) {
                    CompilerReporter.warning(null, method.getLineNumber(), method.getColumnNumber(), "Cannot inline method '" + name + "': Method is recursive. Skipping @Inline annotation.", "IRMethodInliner");
                    continue;
                }
                if (!validBody) {
                    CompilerReporter.warning(null, method.getLineNumber(), method.getColumnNumber(), "Cannot inline method '" + name + "': Complex control-flow or multiple returns. Skipping @Inline annotation.", "IRMethodInliner");
                    continue;
                }
                inlinableNames.add(name);
                inlinableMap.put(name, method);
            } else if (isPrivate) {
                if (count == 1 && !isRecursive && validBody) {
                    inlinableNames.add(name);
                    inlinableMap.put(name, method);
                }
            }
        }

        if (inlinableNames.isEmpty()) {
            return cls;
        }

        // 4. Transform method bodies by inlining candidate calls
        List<IRMethod> transformedMethods = new ArrayList<>();
        for (IRMethod m : cls.getMethods()) {
            IRStatement newBody = (IRStatement) inlineCallsInNode(m.getBody(), inlinableMap);
            IRMethod newMethod = new IRMethod(m.getName(), m.getDescriptor(), m.isStatic(), m.isAbstract(), newBody);
            newMethod.setAccessFlags(m.getAccessFlags());
            newMethod.setAsync(m.isAsync());
            newMethod.setDefaultValue(m.getDefaultValue());
            for (String exc : m.getExceptions()) newMethod.addException(exc);
            for (IRAnnotation a : m.getAnnotations()) newMethod.addAnnotation(a);
            for (IRMethod.IRParameter p : m.getParameters()) newMethod.addParameter(p);
            transformedMethods.add(newMethod);
        }

        List<IRMethod> newMethods = new ArrayList<>();
        for (IRMethod m : transformedMethods) {
            String key = m.getName() + "#" + m.getDescriptor();
            if (inlinableNames.contains(key) && !remainingCalls.contains(key) && !hasInlineAnnotation(m)) {
                // Only omit private helper if ALL its calls were inlined and none remain
                continue;
            }
            newMethods.add(m);
        }

        IRClass optimized = new IRClass(cls.getName(), cls.getSuperName(), cls.getInterfaces(), cls.isAbstract());
        optimized.setAccessFlags(cls.getAccessFlags());
        optimized.setDataClass(cls.isDataClass());
        optimized.setSealed(cls.isSealed());
        optimized.setNonSealed(cls.isNonSealed());
        for (String p : cls.getPermittedSubclasses()) optimized.addPermittedSubclass(p);
        for (IRAnnotation a : cls.getAnnotations()) optimized.addAnnotation(a);
        for (IRField f : cls.getFields()) optimized.addField(f);
        for (IRMethod m : newMethods) optimized.addMethod(m);
        for (IRBlock sb : cls.getStaticBlocks()) optimized.addStaticBlock(sb);
        for (IRBlock ib : cls.getInstanceBlocks()) optimized.addInstanceBlock(ib);
        for (IRNode init : cls.getInstanceInitializers()) optimized.addInstanceInitializer(init);
        for (IRNode init : cls.getStaticInitializers()) optimized.addStaticInitializer(init);

        return optimized;
    }

    private boolean isInlinableBody(IRStatement body) {
        if (body == null) return false;
        if (body instanceof IRBlock) {
            List<IRStatement> stmts = ((IRBlock) body).getStatements();
            return stmts.size() == 1 && stmts.getFirst() instanceof IRReturnStatement;
        } else return body instanceof IRReturnStatement;
    }

    private void countMethodCalls(IRNode node, String className, Map<String, Integer> counts, Set<String> recursive, String currentMethod) {
        switch (node) {
            case null -> {}
            case IRMethodCall mc -> {
                String key = mc.getName() + "#" + mc.getDescriptor();
                if (isThisClassCall(mc)) {
                    counts.put(key, counts.getOrDefault(key, 0) + 1);
                    if (key.equals(currentMethod)) {
                        recursive.add(key);
                    }
                }
                if (mc.getReceiver() != null)
                    countMethodCalls(mc.getReceiver(), className, counts, recursive, currentMethod);
                if (mc.getArguments() != null) {
                    for (IRExpression arg : mc.getArguments()) countMethodCalls(arg, className, counts, recursive, currentMethod);
                }
            }
            case IRBlock irBlock -> {
                for (IRStatement stmt : irBlock.getStatements()) {
                    countMethodCalls(stmt, className, counts, recursive, currentMethod);
                }
            }
            case IRIfStatement stmt -> {
                countMethodCalls(stmt.getCondition(), className, counts, recursive, currentMethod);
                countMethodCalls(stmt.getThenBranch(), className, counts, recursive, currentMethod);
                countMethodCalls(stmt.getElseBranch(), className, counts, recursive, currentMethod);
            }
            case IRWhileStatement stmt -> {
                countMethodCalls(stmt.getCondition(), className, counts, recursive, currentMethod);
                countMethodCalls(stmt.getBody(), className, counts, recursive, currentMethod);
            }
            case IRDoWhileStatement stmt -> {
                countMethodCalls(stmt.getCondition(), className, counts, recursive, currentMethod);
                countMethodCalls(stmt.getBody(), className, counts, recursive, currentMethod);
            }
            case IRForStatement stmt -> {
                countMethodCalls(stmt.getFromExpr(), className, counts, recursive, currentMethod);
                countMethodCalls(stmt.getToExpr(), className, counts, recursive, currentMethod);
                countMethodCalls(stmt.getStepExpr(), className, counts, recursive, currentMethod);
                countMethodCalls(stmt.getIterableExpr(), className, counts, recursive, currentMethod);
                countMethodCalls(stmt.getBody(), className, counts, recursive, currentMethod);
            }
            case IRLockStatement stmt -> {
                countMethodCalls(stmt.getLockExpression(), className, counts, recursive, currentMethod);
                countMethodCalls(stmt.getBody(), className, counts, recursive, currentMethod);
            }
            case IRTryCatchStatement stmt -> {
                countMethodCalls(stmt.getTryBlock(), className, counts, recursive, currentMethod);
                if (stmt.getCatchClauses() != null) {
                    for (IRTryCatchStatement.IRCatchClause cc : stmt.getCatchClauses()) {
                        countMethodCalls(cc.body(), className, counts, recursive, currentMethod);
                    }
                }
                countMethodCalls(stmt.getFinallyBlock(), className, counts, recursive, currentMethod);
            }
            case IRSwitchStatement stmt -> countMethodCallsForSwitch(stmt.getExpression(),stmt.getCases(),stmt.getDefaultBlock(),className,counts,recursive,currentMethod);
            case IRSwitchExpression expr -> countMethodCallsForSwitch(expr.getExpression(),expr.getCases(),expr.getDefaultBody(),className, counts, recursive, currentMethod);
            case IRAssignment assign -> {
                countMethodCalls(assign.getTarget(), className, counts, recursive, currentMethod);
                countMethodCalls(assign.getValue(), className, counts, recursive, currentMethod);
            }
            case IRExprStatement irExprStatement ->
                    countMethodCalls(irExprStatement.getExpression(), className, counts, recursive, currentMethod);
            case IRVariableDecl irVariableDecl ->
                    countMethodCalls(irVariableDecl.getInitialValue(), className, counts, recursive, currentMethod);
            case IRReturnStatement irReturnStatement ->
                    countMethodCalls(irReturnStatement.getExpression(), className, counts, recursive, currentMethod);
            case IRBinaryOp bo -> {
                countMethodCalls(bo.getLeft(), className, counts, recursive, currentMethod);
                countMethodCalls(bo.getRight(), className, counts, recursive, currentMethod);
            }
            case IRUnaryOp uo -> countMethodCalls(uo.getExpression(), className, counts, recursive, currentMethod);
            case IRTernaryExpression te -> {
                countMethodCalls(te.getCondition(), className, counts, recursive, currentMethod);
                countMethodCalls(te.getTrueExpr(), className, counts, recursive, currentMethod);
                countMethodCalls(te.getFalseExpr(), className, counts, recursive, currentMethod);
            }
            case IRCastExpression ce -> countMethodCalls(ce.getExpression(), className, counts, recursive, currentMethod);
            case IRInstanceof inst -> countMethodCalls(inst.getExpression(), className, counts, recursive, currentMethod);
            case IRArrayAccess aa -> {
                countMethodCalls(aa.getArray(), className, counts, recursive, currentMethod);
                countMethodCalls(aa.getIndex(), className, counts, recursive, currentMethod);
            }
            case IRArrayCreation ac -> {
                if (ac.getSizes() != null) {
                    for (IRExpression d : ac.getSizes()) countMethodCalls(d, className, counts, recursive, currentMethod);
                }
            }
            case IRArrayLiteral al -> {
                if (al.getElements() != null) {
                    for (IRExpression e : al.getElements()) countMethodCalls(e, className, counts, recursive, currentMethod);
                }
            }
            case IRThrowStatement throwStmt -> countMethodCalls(throwStmt.getExpression(), className, counts, recursive, currentMethod);
            case IRResultStatement resStmt -> countMethodCalls(resStmt.getExpression(), className, counts, recursive, currentMethod);
            case IRLabeledStatement labStmt -> countMethodCalls(labStmt.getStatement(), className, counts, recursive, currentMethod);
            case IROceanOutput out -> {
                if (out.getArguments() != null) {
                    for (IRExpression arg : out.getArguments()) countMethodCalls(arg, className, counts, recursive, currentMethod);
                }
            }
            case IRAwaitExpression await -> countMethodCalls(await.getFuture(), className, counts, recursive, currentMethod);
            case IRInterpolatedString is -> {
                if (is.getParts() != null) {
                    for (IRNode p : is.getParts()) countMethodCalls(p, className, counts, recursive, currentMethod);
                }
            }
            default -> {
            }
        }
    }

    private void countMethodCallsForSwitch(IRExpression expression, List<IRSwitchCase> cases, IRNode defaultBlock, String className, Map<String,Integer> counts, Set<String> recursive, String currentMethod) {
        countMethodCalls(expression, className, counts, recursive, currentMethod);
        if (cases != null) {
            for (IRSwitchCase sc : cases) {
                if (sc.getPatterns() != null) {
                    for (IRSwitchPattern p : sc.getPatterns()) {
                        if (p.getExpression() != null) countMethodCalls(p.getExpression(), className, counts, recursive, currentMethod);
                        if (p.getGuard() != null) countMethodCalls(p.getGuard(), className, counts, recursive, currentMethod);
                    }
                }
                if (sc.getValues() != null) {
                    for (IRExpression v : sc.getValues()) countMethodCalls(v, className, counts, recursive, currentMethod);
                }
                if (sc.getGuard() != null) countMethodCalls(sc.getGuard(), className, counts, recursive, currentMethod);
                countMethodCalls(sc.getBody(), className, counts, recursive, currentMethod);
            }
        }
        countMethodCalls(defaultBlock, className, counts, recursive, currentMethod);
    }

    private IRNode inlineCallsInNode(IRNode node, Map<String, IRMethod> inlinableMap) {
        switch (node) {
            case null -> {
                return null;
            }
            case IRMethodCall mc -> {
                String key = mc.getName() + "#" + mc.getDescriptor();
                if (inlinableMap.containsKey(key) && isThisClassCall(mc)) {
                    IRMethod targetMethod = inlinableMap.get(key);
                    IRNode inlined = inlineMethodCall(mc, targetMethod);
                    if (inlined != mc) {
                        return inlined;
                    }
                }
                remainingCalls.add(key);
                IRExpression receiver = (IRExpression) inlineCallsInNode(mc.getReceiver(), inlinableMap);
                List<IRExpression> args = new ArrayList<>();
                if (mc.getArguments() != null) {
                    for (IRExpression arg : mc.getArguments()) {
                        args.add((IRExpression) inlineCallsInNode(arg, inlinableMap));
                    }
                }
                IRMethodCall newMc = new IRMethodCall(mc.getOwner(), mc.getName(), mc.getDescriptor(), args, mc.isStatic());
                newMc.setReceiver(receiver);
                newMc.setSafeAccess(mc.isSafeAccess());
                newMc.setSuperCall(mc.isSuperCall());
                newMc.setThisCall(mc.isThisCall());
                newMc.setTypeDescriptor(mc.getTypeDescriptor());
                newMc.setOriginalTypeDescriptor(mc.getOriginalTypeDescriptor());
                return newMc;
            }
            case IRBlock block -> {
                List<IRStatement> stmts = new ArrayList<>();
                for (IRStatement stmt : block.getStatements()) {
                    stmts.add((IRStatement) inlineCallsInNode(stmt, inlinableMap));
                }
                return new IRBlock(stmts);
            }
            case IRIfStatement stmt -> {
                return new IRIfStatement(
                        (IRExpression) inlineCallsInNode(stmt.getCondition(), inlinableMap),
                        (IRStatement) inlineCallsInNode(stmt.getThenBranch(), inlinableMap),
                        (IRStatement) inlineCallsInNode(stmt.getElseBranch(), inlinableMap)
                );
            }
            case IRWhileStatement stmt -> {
                return new IRWhileStatement(
                        (IRExpression) inlineCallsInNode(stmt.getCondition(), inlinableMap),
                        (IRStatement) inlineCallsInNode(stmt.getBody(), inlinableMap)
                );
            }
            case IRDoWhileStatement stmt -> {
                return new IRDoWhileStatement(
                        (IRExpression) inlineCallsInNode(stmt.getCondition(), inlinableMap),
                        (IRStatement) inlineCallsInNode(stmt.getBody(), inlinableMap)
                );
            }
            case IRForStatement stmt -> {
                if (stmt.isRange()) {
                    return new IRForStatement(
                            stmt.getIteratorName(), stmt.getTypeDescriptor(),
                            (IRExpression) inlineCallsInNode(stmt.getFromExpr(), inlinableMap),
                            (IRExpression) inlineCallsInNode(stmt.getToExpr(), inlinableMap),
                            (IRExpression) inlineCallsInNode(stmt.getStepExpr(), inlinableMap),
                            stmt.isIncreasing(),
                            (IRStatement) inlineCallsInNode(stmt.getBody(), inlinableMap)
                    );
                } else {
                    return new IRForStatement(
                            stmt.getIteratorName(), stmt.getTypeDescriptor(),
                            (IRExpression) inlineCallsInNode(stmt.getIterableExpr(), inlinableMap),
                            (IRStatement) inlineCallsInNode(stmt.getBody(), inlinableMap)
                    );
                }
            }
            case IRLockStatement stmt -> {
                return new IRLockStatement(
                        (IRExpression) inlineCallsInNode(stmt.getLockExpression(), inlinableMap),
                        (IRStatement) inlineCallsInNode(stmt.getBody(), inlinableMap)
                );
            }
            case IRTryCatchStatement stmt -> {
                IRStatement tryBlock = (IRStatement) inlineCallsInNode(stmt.getTryBlock(), inlinableMap);
                List<IRTryCatchStatement.IRCatchClause> catchClauses = new ArrayList<>();
                if (stmt.getCatchClauses() != null) {
                    for (IRTryCatchStatement.IRCatchClause cc : stmt.getCatchClauses()) {
                        catchClauses.add(new IRTryCatchStatement.IRCatchClause(
                                cc.exceptionVar(), cc.exceptionTypes(),
                                (IRStatement) inlineCallsInNode(cc.body(), inlinableMap)
                        ));
                    }
                }
                IRStatement finallyBlock = (IRStatement) inlineCallsInNode(stmt.getFinallyBlock(), inlinableMap);
                return new IRTryCatchStatement(tryBlock, catchClauses, finallyBlock);
            }
            case IRSwitchStatement stmt -> {
                IRExpression expr = (IRExpression) inlineCallsInNode(stmt.getExpression(), inlinableMap);
                List<IRSwitchCase> cases = new ArrayList<>();
                if (stmt.getCases() != null) {
                    for (IRSwitchCase sc : stmt.getCases()) {
                        inlineSwitchCase(sc,cases,inlinableMap);
                    }
                }
                IRStatement defaultBlock = (IRStatement) inlineCallsInNode(stmt.getDefaultBlock(), inlinableMap);
                return new IRSwitchStatement(expr, cases, defaultBlock, stmt.isDefaultArrow());
            }
            case IRSwitchExpression expr -> {
                IRExpression sel = (IRExpression) inlineCallsInNode(expr.getExpression(), inlinableMap);
                List<IRSwitchCase> cases = new ArrayList<>();
                if (expr.getCases() != null) {
                    for (IRSwitchCase sc : expr.getCases()) {
                        inlineSwitchCase(sc,cases,inlinableMap);
                    }
                }
                IRNode defBody = inlineCallsInNode(expr.getDefaultBody(), inlinableMap);
                return new IRSwitchExpression(sel, cases, defBody, expr.getTypeDescriptor());
            }
            case IRAssignment assign -> {
                return new IRAssignment(
                        (IRExpression) inlineCallsInNode(assign.getTarget(), inlinableMap),
                        (IRExpression) inlineCallsInNode(assign.getValue(), inlinableMap),
                        assign.isStatic(),
                        assign.isCompound()
                );
            }
            case IRExprStatement irExprStatement -> {
                return new IRExprStatement((IRExpression) inlineCallsInNode(irExprStatement.getExpression(), inlinableMap));
            }
            case IRVariableDecl decl -> {
                return new IRVariableDecl(
                        decl.getName(), decl.getTypeDescriptor(),
                        (IRExpression) inlineCallsInNode(decl.getInitialValue(), inlinableMap),
                        decl.isFinal()
                );
            }
            case IRReturnStatement ret -> {
                return new IRReturnStatement((IRExpression) inlineCallsInNode(ret.getExpression(), inlinableMap));
            }
            case IRBinaryOp bo -> {
                return new IRBinaryOp(
                        (IRExpression) inlineCallsInNode(bo.getLeft(), inlinableMap),
                        (IRExpression) inlineCallsInNode(bo.getRight(), inlinableMap),
                        bo.getOperator(), bo.getTypeDescriptor()
                );
            }
            case IRUnaryOp uo -> {
                return new IRUnaryOp((IRExpression) inlineCallsInNode(uo.getExpression(), inlinableMap), uo.getOperator(), uo.getTypeDescriptor());
            }
            case IRTernaryExpression te -> {
                return new IRTernaryExpression(
                        (IRExpression) inlineCallsInNode(te.getCondition(), inlinableMap),
                        (IRExpression) inlineCallsInNode(te.getTrueExpr(), inlinableMap),
                        (IRExpression) inlineCallsInNode(te.getFalseExpr(), inlinableMap),
                        te.getTypeDescriptor(),
                        te.isNullCoalescing()
                );
            }
            case IRCastExpression ce -> {
                return new IRCastExpression((IRExpression) inlineCallsInNode(ce.getExpression(), inlinableMap), ce.getTargetType(), ce.getAdditionalBounds());
            }
            case IRInstanceof inst -> {
                return new IRInstanceof((IRExpression) inlineCallsInNode(inst.getExpression(), inlinableMap), inst.getTypeDescriptor());
            }
            case IRArrayAccess aa -> {
                return new IRArrayAccess((IRExpression) inlineCallsInNode(aa.getArray(), inlinableMap), (IRExpression) inlineCallsInNode(aa.getIndex(), inlinableMap), aa.getTypeDescriptor());
            }
            case IRArrayCreation ac -> {
                List<IRExpression> sizes = new ArrayList<>();
                if (ac.getSizes() != null) {
                    for (IRExpression d : ac.getSizes()) sizes.add((IRExpression) inlineCallsInNode(d, inlinableMap));
                }
                IRArrayCreation newAc = new IRArrayCreation(ac.getBaseType(), sizes);
                newAc.setRawType(ac.getRawType());
                return newAc;
            }
            case IRArrayLiteral al -> {
                List<IRExpression> elems = new ArrayList<>();
                if (al.getElements() != null) {
                    for (IRExpression e : al.getElements())
                        elems.add((IRExpression) inlineCallsInNode(e, inlinableMap));
                }
                return new IRArrayLiteral(elems, al.getTypeDescriptor());
            }
            case IRThrowStatement throwStmt -> {
                return new IRThrowStatement((IRExpression) inlineCallsInNode(throwStmt.getExpression(), inlinableMap));
            }
            case IRResultStatement resStmt -> {
                return new IRResultStatement((IRExpression) inlineCallsInNode(resStmt.getExpression(), inlinableMap));
            }
            case IRLabeledStatement labStmt -> {
                return new IRLabeledStatement(labStmt.getLabel(), (IRStatement) inlineCallsInNode(labStmt.getStatement(), inlinableMap));
            }
            case IROceanOutput out -> {
                List<IRExpression> args = new ArrayList<>();
                if (out.getArguments() != null) {
                    for (IRExpression a : out.getArguments())
                        args.add((IRExpression) inlineCallsInNode(a, inlinableMap));
                }
                return new IROceanOutput(args);
            }
            case IRAwaitExpression await -> {
                return new IRAwaitExpression((IRExpression) inlineCallsInNode(await.getFuture(), inlinableMap), await.getTypeDescriptor());
            }
            case IRInterpolatedString is -> {
                List<IRNode> parts = new ArrayList<>();
                if (is.getParts() != null) {
                    for (IRNode p : is.getParts()) parts.add(inlineCallsInNode(p, inlinableMap));
                }
                return new IRInterpolatedString(parts);
            }
            default -> {
            }
        }

        return node;
    }

    private IRExpression inlineMethodCall(IRMethodCall mc, IRMethod targetMethod) {
        IRExpression returnExpr = determineReturnExpr(targetMethod);

        if (returnExpr == null) {
            return mc;
        }

        // Map parameters to arguments
        Map<String, IRExpression> paramMap = new HashMap<>();
        List<IRMethod.IRParameter> params = targetMethod.getParameters();
        List<IRExpression> args = mc.getArguments();

        if (params != null && args != null && params.size() == args.size()) {
            boolean anySideEffects = false;
            for (IRExpression arg : args) {
                if (!isSideEffectFree(arg)) {
                    anySideEffects = true;
                    break;
                }
            }

            for (int i = 0; i < params.size(); i++) {
                String pName = params.get(i).name();
                IRExpression arg = args.get(i);
                int usages = countParameterOccurrences(returnExpr, pName);
                if (usages == 0 && !isSideEffectFree(arg)) {
                    // Abort inlining: side-effecting argument would be silently dropped
                    return mc;
                }
                if (usages > 1 && !isSideEffectFree(arg)) {
                    // Abort inlining: evaluating argument with side-effects multiple times changes semantics
                    return mc;
                }
                paramMap.put(pName, arg);
            }

            if (anySideEffects && params.size() > 1) {
                List<String> evalOrder = new ArrayList<>();
                collectParameterOrder(returnExpr, evalOrder);
                int lastIdx = -1;
                for (String evaluatedParam : evalOrder) {
                    int pIdx = -1;
                    for (int k = 0; k < params.size(); k++) {
                        if (params.get(k).name().equals(evaluatedParam)) {
                            pIdx = k;
                            break;
                        }
                    }
                    if (pIdx != -1) {
                        if (pIdx < lastIdx && (!isSideEffectFree(args.get(pIdx)) || !isSideEffectFree(args.get(lastIdx)))) {
                            // Evaluation order of side effects altered
                            return mc;
                        }
                        if (!isSideEffectFree(args.get(pIdx))) {
                            lastIdx = Math.max(lastIdx, pIdx);
                        }
                    }
                }
            }
        }

        return substituteParameters(returnExpr, paramMap);
    }

    private static boolean isSideEffectFree(IRExpression expr) {
        return switch (expr) {
            case null -> true;
            case IRLiteral literal -> true;
            default -> expr instanceof IRVariableAccess;
        };
    }

    private static void collectParameterOrder(IRNode node, List<String> order) {
        if (node == null) return;
        switch (node) {
            case IRVariableAccess va -> order.add(va.getName());
            case IRBinaryOp bo -> {
                collectParameterOrder(bo.getLeft(), order);
                collectParameterOrder(bo.getRight(), order);
            }
            case IRUnaryOp uo -> collectParameterOrder(uo.getExpression(), order);
            case IRTernaryExpression te -> {
                collectParameterOrder(te.getCondition(), order);
                collectParameterOrder(te.getTrueExpr(), order);
                collectParameterOrder(te.getFalseExpr(), order);
            }
            case IRMethodCall mc -> {
                collectParameterOrder(mc.getReceiver(), order);
                if (mc.getArguments() != null) {
                    for (IRExpression arg : mc.getArguments()) collectParameterOrder(arg, order);
                }
            }
            case IRCastExpression cast -> collectParameterOrder(cast.getExpression(), order);
            case IRInterpolatedString is -> {
                if (is.getParts() != null) {
                    for (IRNode p : is.getParts()) collectParameterOrder(p, order);
                }
            }
            case IRArrayAccess aa -> {
                collectParameterOrder(aa.getArray(), order);
                collectParameterOrder(aa.getIndex(), order);
            }
            case IRNewObject no -> {
                if (no.getArguments() != null) {
                    for (IRExpression arg : no.getArguments()) collectParameterOrder(arg, order);
                }
            }
            case IRArrayLiteral al -> {
                if (al.getElements() != null) {
                    for (IRExpression el : al.getElements()) collectParameterOrder(el, order);
                }
            }
            default -> {}
        }
    }

    private static int countParameterOccurrences(IRNode node, String paramName) {
        if (node == null || paramName == null) return 0;
        switch (node) {
            case IRVariableAccess va -> {
                return paramName.equals(va.getName()) ? 1 : 0;
            }
            case IRBinaryOp bo -> {
                return countParameterOccurrences(bo.getLeft(), paramName) + countParameterOccurrences(bo.getRight(), paramName);
            }
            case IRUnaryOp uo -> {
                return countParameterOccurrences(uo.getExpression(), paramName);
            }
            case IRTernaryExpression te -> {
                return countParameterOccurrences(te.getCondition(), paramName)
                        + countParameterOccurrences(te.getTrueExpr(), paramName)
                        + countParameterOccurrences(te.getFalseExpr(), paramName);
            }
            case IRMethodCall mc -> {
                int count = countParameterOccurrences(mc.getReceiver(), paramName);
                if (mc.getArguments() != null) {
                    for (IRExpression arg : mc.getArguments()) {
                        count += countParameterOccurrences(arg, paramName);
                    }
                }
                return count;
            }
            case IRCastExpression cast -> {
                return countParameterOccurrences(cast.getExpression(), paramName);
            }
            case IRInterpolatedString is -> {
                int count = 0;
                if (is.getParts() != null) {
                    for (IRNode p : is.getParts()) count += countParameterOccurrences(p, paramName);
                }
                return count;
            }
            case IRArrayAccess aa -> {
                return countParameterOccurrences(aa.getArray(), paramName) + countParameterOccurrences(aa.getIndex(), paramName);
            }
            case IRNewObject no -> {
                int count = 0;
                if (no.getArguments() != null) {
                    for (IRExpression arg : no.getArguments()) count += countParameterOccurrences(arg, paramName);
                }
                return count;
            }
            case IRArrayLiteral al -> {
                int count = 0;
                if (al.getElements() != null) {
                    for (IRExpression el : al.getElements()) count += countParameterOccurrences(el, paramName);
                }
                return count;
            }
            default -> {
            }
        }
        return 0;
    }

    @Nullable
    private IRExpression determineReturnExpr(IRMethod targetMethod) {
        IRStatement body = targetMethod.getBody();
        IRExpression returnExpr = null;

        if (body instanceof IRBlock) {
            List<IRStatement> stmts = ((IRBlock) body).getStatements();
            if (stmts.size() == 1 && stmts.getFirst() instanceof IRReturnStatement) {
                returnExpr = ((IRReturnStatement) stmts.getFirst()).getExpression();
            }
        } else if (body instanceof IRReturnStatement) {
            returnExpr = ((IRReturnStatement) body).getExpression();
        }
        return returnExpr;
    }

    private IRExpression substituteParameters(IRExpression expr, Map<String, IRExpression> paramMap) {
        switch (expr) {
            case null -> {
                return null;
            }
            case IRVariableAccess irVariableAccess -> {
                String name = irVariableAccess.getName();
                if (paramMap.containsKey(name)) {
                    return paramMap.get(name);
                }
                return expr;
            }
            case IRBinaryOp bo -> {
                return new IRBinaryOp(
                        substituteParameters(bo.getLeft(), paramMap),
                        substituteParameters(bo.getRight(), paramMap),
                        bo.getOperator(), bo.getTypeDescriptor()
                );
            }
            case IRUnaryOp uo -> {
                return new IRUnaryOp(substituteParameters(uo.getExpression(), paramMap), uo.getOperator(), uo.getTypeDescriptor());
            }
            case IRTernaryExpression te -> {
                return new IRTernaryExpression(
                        substituteParameters(te.getCondition(), paramMap),
                        substituteParameters(te.getTrueExpr(), paramMap),
                        substituteParameters(te.getFalseExpr(), paramMap),
                        te.getTypeDescriptor(),
                        te.isNullCoalescing()
                );
            }
            case IRMethodCall mc -> {
                IRExpression receiver = substituteParameters(mc.getReceiver(), paramMap);
                List<IRExpression> args = new ArrayList<>();
                if (mc.getArguments() != null) {
                    for (IRExpression arg : mc.getArguments()) {
                        args.add(substituteParameters(arg, paramMap));
                    }
                }
                IRMethodCall newMc = new IRMethodCall(mc.getOwner(), mc.getName(), mc.getDescriptor(), args, mc.isStatic());
                newMc.setReceiver(receiver);
                newMc.setSafeAccess(mc.isSafeAccess());
                newMc.setSuperCall(mc.isSuperCall());
                newMc.setThisCall(mc.isThisCall());
                newMc.setTypeDescriptor(mc.getTypeDescriptor());
                newMc.setOriginalTypeDescriptor(mc.getOriginalTypeDescriptor());
                return newMc;
            }
            case IRCastExpression cast -> {
                return new IRCastExpression(substituteParameters(cast.getExpression(), paramMap), cast.getTypeDescriptor(), cast.getAdditionalBounds());
            }
            case IRInstanceof inst -> {
                return new IRInstanceof(substituteParameters(inst.getExpression(), paramMap), inst.getTypeDescriptor());
            }
            case IRArrayAccess aa -> {
                return new IRArrayAccess(
                        substituteParameters(aa.getArray(), paramMap),
                        substituteParameters(aa.getIndex(), paramMap),
                        aa.getTypeDescriptor()
                );
            }
            case IRArrayLiteral al -> {
                List<IRExpression> elems = new ArrayList<>();
                if (al.getElements() != null) {
                    for (IRExpression elem : al.getElements()) {
                        elems.add(substituteParameters(elem, paramMap));
                    }
                }
                return new IRArrayLiteral(elems, al.getTypeDescriptor());
            }
            case IRArrayCreation ac -> {
                List<IRExpression> sizes = new ArrayList<>();
                if (ac.getSizes() != null) {
                    for (IRExpression dim : ac.getSizes()) {
                        sizes.add(substituteParameters(dim, paramMap));
                    }
                }
                IRArrayCreation newAc = new IRArrayCreation(ac.getBaseType(), sizes);
                newAc.setRawType(ac.getRawType());
                return newAc;
            }
            case IROceanOutput out -> {
                List<IRExpression> args = new ArrayList<>();
                if (out.getArguments() != null) {
                    for (IRExpression arg : out.getArguments()) {
                        args.add(substituteParameters(arg, paramMap));
                    }
                }
                return new IROceanOutput(args);
            }
            case IRAwaitExpression await -> {
                return new IRAwaitExpression(substituteParameters(await.getFuture(), paramMap), await.getTypeDescriptor());
            }
            case IRInterpolatedString is -> {
                List<IRNode> parts = new ArrayList<>();
                if (is.getParts() != null) {
                    for (IRNode p : is.getParts()) {
                        if (p instanceof IRExpression pe) {
                            parts.add(substituteParameters(pe, paramMap));
                        } else {
                            parts.add(p);
                        }
                    }
                }
                return new IRInterpolatedString(parts);
            }
            default -> {
            }
        }

        return expr;
    }

    private void inlineSwitchCase(IRSwitchCase sc,List<IRSwitchCase> cases,Map<String,IRMethod> inlinableMap) {
        List<IRSwitchPattern> patterns = new ArrayList<>();
        if (sc.getPatterns() != null) {
            for (IRSwitchPattern p : sc.getPatterns()) {
                IRExpression pe = (IRExpression) inlineCallsInNode(p.getExpression(), inlinableMap);
                IRExpression pg = (IRExpression) inlineCallsInNode(p.getGuard(), inlinableMap);
                patterns.add(new IRSwitchPattern(p.getKind(), p.getTypeDescriptor(), p.getVariableName(), pe, p.getNestedPatterns(), pg));
            }
        }
        List<IRExpression> values = new ArrayList<>();
        if (sc.getValues() != null) {
            for (IRExpression v : sc.getValues()) {
                values.add((IRExpression) inlineCallsInNode(v, inlinableMap));
            }
        }
        IRExpression guard = (IRExpression) inlineCallsInNode(sc.getGuard(), inlinableMap);
        IRNode body = inlineCallsInNode(sc.getBody(), inlinableMap);
        cases.add(new IRSwitchCase(patterns, values, guard, body, sc.isArrow()));
    }
}
