package ocean.compiler.ir;

import ocean.compiler.OceanTypeSystem;
import org.antlr.v4.runtime.tree.ParseTree;
import ocean.compiler.OceanParser;
import ocean.compiler.StringHelper;
import ocean.compiler.TypeChecker;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IR üzerinde Sabit Katlama (Constant Folding) optimizasyonu yapan temiz, modüler sınıf.
 */
public class IRConstantFolder extends BaseIRVisitor {

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
            case IRExpression irExpression -> {
                return foldExpression(irExpression);
            }
            case IRBlock block -> {
                List<IRStatement> optimized = new ArrayList<>();
                boolean changed = false;
                for (IRStatement stmt : block.getStatements()) {
                    IRNode opt = optimize(stmt);
                    if (opt instanceof IRStatement) {
                        optimized.add((IRStatement) opt);
                        if (opt != stmt) changed = true;
                    } else {
                        changed = true;
                    }
                }
                if (!changed) return block;
                return new IRBlock(optimized);
            }
            case IRIfStatement ifStmt -> {
                IRExpression cond = foldExpression(ifStmt.getCondition());
                IRStatement thenBranch = (IRStatement) optimize(ifStmt.getThenBranch());
                IRStatement elseBranch = (IRStatement) optimize(ifStmt.getElseBranch());
                if (cond == ifStmt.getCondition() && thenBranch == ifStmt.getThenBranch() && elseBranch == ifStmt.getElseBranch()) {
                    return ifStmt;
                }
                return new IRIfStatement(cond, thenBranch, elseBranch);
            }
            case IRWhileStatement whileStmt -> {
                IRExpression cond = foldExpression(whileStmt.getCondition());
                IRStatement body = (IRStatement) optimize(whileStmt.getBody());
                if (cond == whileStmt.getCondition() && body == whileStmt.getBody()) {
                    return whileStmt;
                }
                return new IRWhileStatement(cond, body);
            }
            case IRReturnStatement ret -> {
                IRExpression val = ret.getExpression() != null ? foldExpression(ret.getExpression()) : null;
                if (val == ret.getExpression()) {
                    return ret;
                }
                return new IRReturnStatement(val);
            }
            case IRExprStatement stmt -> {
                IRExpression expr = foldExpression(stmt.getExpression());
                if (expr == stmt.getExpression()) {
                    return stmt;
                }
                return new IRExprStatement(expr);
            }
            case IRVariableDecl decl -> {
                IRExpression init = decl.getInitialValue() != null ? foldExpression(decl.getInitialValue()) : null;
                if (init == decl.getInitialValue()) {
                    return decl;
                }
                return new IRVariableDecl(decl.getName(), decl.getTypeDescriptor(), init, decl.isFinal());
            }
            case IRForStatement forStmt -> {
                if (forStmt.isRange()) {
                    IRExpression from = foldExpression(forStmt.getFromExpr());
                    IRExpression to = foldExpression(forStmt.getToExpr());
                    IRExpression step = foldExpression(forStmt.getStepExpr());
                    IRStatement body = (IRStatement) optimize(forStmt.getBody());
                    if (from == forStmt.getFromExpr() && to == forStmt.getToExpr() && step == forStmt.getStepExpr() && body == forStmt.getBody()) {
                        return forStmt;
                    }
                    return new IRForStatement(forStmt.getIteratorName(), forStmt.getTypeDescriptor(), from, to, step, forStmt.isIncreasing(), body);
                } else {
                    IRExpression iterable = foldExpression(forStmt.getIterableExpr());
                    IRStatement body = (IRStatement) optimize(forStmt.getBody());
                    if (iterable == forStmt.getIterableExpr() && body == forStmt.getBody()) {
                        return forStmt;
                    }
                    return new IRForStatement(forStmt.getIteratorName(), forStmt.getTypeDescriptor(), iterable, body);
                }
            }
            case IRCompilationUnit unit -> {
                List<IRNode> types = new ArrayList<>();
                boolean changed = false;
                for (IRNode type : unit.getTypes()) {
                    IRNode opt = optimize(type);
                    types.add(opt);
                    if (opt != type) changed = true;
                }
                if (!changed) return unit;
                return new IRCompilationUnit(types);
            }
            case IRClass cls -> {
                List<IRField> fields = new ArrayList<>();
                boolean changed = false;
                for (IRField f : cls.getFields()) {
                    IRField opt = (IRField) optimize(f);
                    fields.add(opt);
                    if (opt != f) changed = true;
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
                for (IRAnnotation a : cls.getAnnotations()) optimized.addAnnotation(optimizeAnnotation(a));
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
                for (IRAnnotation a : inter.getAnnotations()) optInter.addAnnotation(optimizeAnnotation(a));
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
                for (IRAnnotation a : enu.getAnnotations()) optEnu.addAnnotation(optimizeAnnotation(a));
                return optEnu;
            }
            case IRMethod method -> {
                IRStatement body = (IRStatement) optimize(method.getBody());
                if (body == null) {
                    body = new IRBlock(new ArrayList<>());
                }
                IRExpression optDefaultVal = method.getDefaultValue() != null ? foldExpression(method.getDefaultValue()) : null;
                if (body == method.getBody() && optDefaultVal == method.getDefaultValue()) {
                    return method;
                }
                IRMethod optimized = new IRMethod(method.getName(), method.getDescriptor(), method.isStatic(), method.isAbstract(), body);
                optimized.setAccessFlags(method.getAccessFlags());
                optimized.setAsync(method.isAsync());
                optimized.setDefaultValue(optDefaultVal);
                for (IRAnnotation a : method.getAnnotations()) optimized.addAnnotation(optimizeAnnotation(a));
                for (String e : method.getExceptions()) optimized.addException(e);
                for (IRMethod.IRParameter p : method.getParameters()) optimized.addParameter(p);
                return optimized;
            }
            case IRField field -> {
                IRExpression init = field.getInitialValue() != null ? foldExpression(field.getInitialValue()) : null;
                if (init == field.getInitialValue()) {
                    return field;
                }
                IRField optimizedField = new IRField(field.getName(), field.getTypeDescriptor(), field.isStatic(), init);
                optimizedField.setAccessFlags(field.getAccessFlags());
                for (IRAnnotation a : field.getAnnotations()) optimizedField.addAnnotation(optimizeAnnotation(a));
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
                IRExpression cond = foldExpression(dw.getCondition());
                IRStatement body = (IRStatement) optimize(dw.getBody());
                if (cond == dw.getCondition() && body == dw.getBody()) {
                    return dw;
                }
                return new IRDoWhileStatement(cond, body);
            }
            case IRSwitchStatement sw -> {
                IRExpression expr = foldExpression(sw.getExpression());
                List<IRSwitchCase> cases = new ArrayList<>();
                boolean changed = false;
                if (sw.getCases() != null) {
                    for (IRSwitchCase c : sw.getCases()) {
                        List<IRSwitchPattern> patterns = new ArrayList<>();
                        if (c.getPatterns() != null) {
                            for (IRSwitchPattern p : c.getPatterns()) {
                                IRSwitchPattern optP = foldSwitchPattern(p);
                                patterns.add(optP);
                                if (optP != p) changed = true;
                            }
                        }
                        List<IRExpression> values = new ArrayList<>();
                        for (IRExpression val : c.getValues()) {
                            IRExpression opt = foldExpression(val);
                            values.add(opt);
                            if (opt != val) changed = true;
                        }
                        IRStatement cBody = (IRStatement) optimize(c.getBody());
                        IRExpression guardOpt = c.getGuard() != null ? foldExpression(c.getGuard()) : null;
                        if (guardOpt != c.getGuard()) changed = true;
                        cases.add(new IRSwitchCase(patterns, values, guardOpt, cBody, c.isArrow()));
                        if (cBody != c.getBody()) changed = true;
                    }
                }
                IRStatement def = (IRStatement) optimize(sw.getDefaultBlock());
                if (expr == sw.getExpression() && def == sw.getDefaultBlock() && !changed) {
                    return sw;
                }
                return new IRSwitchStatement(expr, cases, def, sw.isDefaultArrow());
            }
            case IRLockStatement lock -> {
                IRExpression expr = foldExpression(lock.getLockExpression());
                IRStatement body = (IRStatement) optimize(lock.getBody());
                if (expr == lock.getLockExpression() && body == lock.getBody()) {
                    return lock;
                }
                return new IRLockStatement(expr, body);
            }
            case IRThrowStatement throwStatement -> {
                IRExpression expr = foldExpression(throwStatement.getExpression());
                if (expr == throwStatement.getExpression()) {
                    return throwStatement;
                }
                return new IRThrowStatement(expr);
            }
            case IRVerifyStatement verifyStatement -> {
                IRExpression cond = foldExpression(verifyStatement.getCondition());
                IRExpression detail = verifyStatement.getDetailMessage() != null ? foldExpression(verifyStatement.getDetailMessage()) : null;
                if (cond == verifyStatement.getCondition() && detail == verifyStatement.getDetailMessage()) {
                    return verifyStatement;
                }
                return new IRVerifyStatement(cond, detail);
            }
            case IRLabeledStatement labeledStatement -> {
                IRStatement body = (IRStatement) optimize(labeledStatement.getStatement());
                if (body == labeledStatement.getStatement()) {
                    return labeledStatement;
                }
                return new IRLabeledStatement(labeledStatement.getLabel(), body);
            }
            case IRResultStatement resultStatement -> {
                IRExpression expr = foldExpression(resultStatement.getExpression());
                if (expr == resultStatement.getExpression()) {
                    return resultStatement;
                }
                return new IRResultStatement(expr);
            }
            default -> {
            }
        }

        return node;
    }

    private IRExpression foldExpression(IRExpression expr) {
        if (expr instanceof IRBinaryOp) {
            return foldBinaryOp((IRBinaryOp) expr);
        } else if (expr instanceof IRTernaryExpression) {
            return foldTernary((IRTernaryExpression) expr);
        } else if (expr instanceof IRMethodCall) {
            return foldMethodCall((IRMethodCall) expr);
        } else if (expr instanceof IRUnaryOp) {
            return foldUnaryOp((IRUnaryOp) expr);
        } else if (expr instanceof IRLambdaExpression lambda) {
            IRNode body = optimize(lambda.getBody());
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
        } else if (expr instanceof IRAwaitExpression awaitExpr) {
            IRExpression inner = foldExpression(awaitExpr.getFuture());
            if (inner == awaitExpr.getFuture()) return awaitExpr;
            return new IRAwaitExpression(inner, awaitExpr.getTypeDescriptor());
        } else if (expr instanceof IRArrayAccess arrayAccess) {
            IRExpression array = foldExpression(arrayAccess.getArray());
            IRExpression index = foldExpression(arrayAccess.getIndex());
            if (array == arrayAccess.getArray() && index == arrayAccess.getIndex()) return arrayAccess;
            return new IRArrayAccess(array, index, arrayAccess.getTypeDescriptor());
        }
        else if (expr instanceof IRArrayLiteral arrayLiteral) {
            List<IRExpression> elements = new ArrayList<>();
            boolean isSame = true;
            if (arrayLiteral.getElements() != null) {
                for (IRExpression element : arrayLiteral.getElements()) {
                    IRExpression last = foldExpression(element);
                    elements.add(last != null ? last : element);
                    if (last != element) isSame = false;
                }
            }
            if (isSame) return arrayLiteral;
            IRArrayLiteral newLit = new IRArrayLiteral(elements, arrayLiteral.getTypeDescriptor());
            newLit.setTypeDescriptor(arrayLiteral.getTypeDescriptor());
            return newLit;
        } else if (expr instanceof IRCastExpression castExpr) {
            IRExpression expression = foldExpression(castExpr.getExpression());
            if (expression == castExpr.getExpression()) return castExpr;
            return new IRCastExpression(expression, castExpr.getTypeDescriptor(), castExpr.getAdditionalBounds());
        } else if (expr instanceof IRInstanceof irInstanceof) {
            IRExpression expression = foldExpression(irInstanceof.getExpression());
            if (expression == irInstanceof.getExpression()) return irInstanceof;
            if (irInstanceof.getPattern() != null) {
                return new IRInstanceof(expression, irInstanceof.getPattern());
            }
            return new IRInstanceof(expression, irInstanceof.getTargetType(), irInstanceof.getPatternVarName());
        } else if (expr instanceof IRNewObject irNewObject) {
            List<IRExpression> arguments = new ArrayList<>();
            boolean isSame = true;
            if (irNewObject.getArguments() != null) {
                for (IRExpression argument : irNewObject.getArguments()) {
                    IRExpression last = foldExpression(argument);
                    arguments.add(last != null ? last : argument);
                    if (last != argument) isSame = false;
                }
            }
            if (isSame) return irNewObject;
            IRNewObject newObj = new IRNewObject(irNewObject.getClassName(), irNewObject.getDescriptor(), arguments);
            newObj.setTypeDescriptor(irNewObject.getTypeDescriptor());
            return newObj;
        } else if (expr instanceof IRArrayCreation arrayCreation) {
            List<IRExpression> sizes = new ArrayList<>();
            boolean isSame = true;
            if (arrayCreation.getSizes() != null) {
                for (IRExpression size : arrayCreation.getSizes()) {
                    IRExpression last = foldExpression(size);
                    sizes.add(last != null ? last : size);
                    if (last != size) isSame = false;
                }
            }
            if (isSame) return arrayCreation;
            IRArrayCreation created = new IRArrayCreation(arrayCreation.getBaseType(), sizes);
            created.setTypeDescriptor(arrayCreation.getTypeDescriptor());
            return created;
        } else if (expr instanceof IRSwitchExpression switchExpr) {
            IRExpression expression = foldExpression(switchExpr.getExpression());
            IRNode defaultBody = optimize(switchExpr.getDefaultBody());
            List<IRSwitchCase> cases = new ArrayList<>();
            boolean isSame = true;
            for (IRSwitchCase casex : switchExpr.getCases()) {
                List<IRSwitchPattern> patterns = new ArrayList<>();
                if (casex.getPatterns() != null) {
                    for (IRSwitchPattern p : casex.getPatterns()) {
                        IRSwitchPattern optP = foldSwitchPattern(p);
                        patterns.add(optP);
                        if (optP != p) isSame = false;
                    }
                }
                IRNode body = optimize(casex.getBody());
                List<IRExpression> values = new ArrayList<>();
                for (IRExpression value : casex.getValues()) {
                    IRExpression last = foldExpression(value);
                    values.add(last);
                    if (last != value) isSame = false;
                }
                IRExpression guardOpt = casex.getGuard() != null ? foldExpression(casex.getGuard()) : null;
                if (guardOpt != casex.getGuard()) isSame = false;
                if (body != casex.getBody()) isSame = false;
                IRSwitchCase caseNew = new IRSwitchCase(patterns, values, guardOpt, body,casex.isArrow());
                cases.add(caseNew);
            }
            if (expression != switchExpr.getExpression()) isSame = false;
            if (defaultBody != switchExpr.getDefaultBody()) isSame = false;
            if (isSame) return switchExpr;
            return new IRSwitchExpression(expression, cases, defaultBody, switchExpr.getTypeDescriptor());
        } else if (expr instanceof IROceanOutput irOceanOutput) {
            List<IRExpression> arguments = new ArrayList<>();
            boolean isSame = true;
            if (irOceanOutput.getArguments() != null) {
                for (IRExpression argument : irOceanOutput.getArguments()) {
                    IRExpression last = foldExpression(argument);
                    arguments.add(last != null ? last : argument);
                    if (last != argument) isSame = false;
                }
            }
            if (isSame) return irOceanOutput;
            IROceanOutput out = new IROceanOutput(arguments);
            out.setTypeDescriptor(irOceanOutput.getTypeDescriptor());
            return out;
        } else if (expr instanceof IRInterpolatedString interpolated) {
            List<IRNode> parts = new ArrayList<>();
            boolean isSame = true;
            if (interpolated.getParts() != null) {
                for (IRNode part : interpolated.getParts()) {
                    IRNode last = optimize(part);
                    parts.add(last != null ? last : part);
                    if (last != part) isSame = false;
                }
            }
            if (isSame) return interpolated;
            IRInterpolatedString newInter = new IRInterpolatedString(parts);
            newInter.setTypeDescriptor(interpolated.getTypeDescriptor());
            return newInter;
        } else if (expr instanceof IRAssignment assign) {
            IRExpression target = foldExpression(assign.getTarget());
            IRExpression value = foldExpression(assign.getValue());
            if (target == assign.getTarget() && value == assign.getValue()) {
                return assign;
            }
            IRAssignment opt = new IRAssignment(target != null ? target : assign.getTarget(), value != null ? value : assign.getValue(), assign.isStatic(), assign.isCompound());
            opt.setTypeDescriptor(assign.getTypeDescriptor());
            return opt;
        }
        return expr;
    }

    // ==========================================
    // REFACTORED MODULAR FOLDING HELPERS
    // ==========================================

    private IRExpression foldBinaryOp(IRBinaryOp op) {
        IRExpression left = foldExpression(op.getLeft());
        IRExpression right = foldExpression(op.getRight());
        if (left == null) left = op.getLeft();
        if (right == null) right = op.getRight();
        IRBinaryOp.Op operator = op.getOperator();

        // 1. Literal + Literal folding
        if (left instanceof IRLiteral && right instanceof IRLiteral) {
            Object v1 = ((IRLiteral) left).getValue();
            Object v2 = ((IRLiteral) right).getValue();

            if (v1 instanceof Number && v2 instanceof Number) {
                return foldNumericBinaryOp((Number) v1, (Number) v2, operator, op.getTypeDescriptor(), left, right);
            } else if (v1 instanceof String || v2 instanceof String) {
                if (operator == IRBinaryOp.Op.ADD) {
                    return new IRLiteral(v1 + String.valueOf(v2), OceanTypeSystem.STRING_DESC);
                }
            } else if (v1 instanceof Boolean && v2 instanceof Boolean) {
                return foldBooleanBinaryOp((Boolean) v1, (Boolean) v2, operator);
            }
        }

        // 2. Algebraic Identities (x + 0, x * 1, x && true, etc.)
        Boolean boolLeft = getLiteralBoolean(left);
        if (boolLeft != null) {
            if (operator == IRBinaryOp.Op.AND) return boolLeft ? right : new IRLiteral(false, "Z");
            if (operator == IRBinaryOp.Op.OR) return boolLeft ? new IRLiteral(true, "Z") : right;
        }
        Boolean boolRight = getLiteralBoolean(right);
        if (boolRight != null) {
            if (operator == IRBinaryOp.Op.AND) {
                if (boolRight) return left;
                if (isPure(left)) return new IRLiteral(false, "Z");
            }
            if (operator == IRBinaryOp.Op.OR) {
                if (!boolRight) return left;
                if (isPure(left)) return new IRLiteral(true, "Z");
            }
        }

        boolean isStringOp = TypeChecker.isStringType(left.getTypeDescriptor()) || 
                             TypeChecker.isStringType(right.getTypeDescriptor()) || 
                             TypeChecker.isStringType(op.getTypeDescriptor());

        if (isZero(left)) {
            if (operator == IRBinaryOp.Op.ADD && !isStringOp) return right;
            if (operator == IRBinaryOp.Op.MUL && isPure(right)) return left;
        }
        if (isOne(left) && operator == IRBinaryOp.Op.MUL) {
            return right;
        }

        if (isZero(right)) {
            if ((operator == IRBinaryOp.Op.ADD || operator == IRBinaryOp.Op.SUB) && !isStringOp) return left;
            if (operator == IRBinaryOp.Op.MUL && isPure(left)) return right;
        }
        if (isOne(right) && (operator == IRBinaryOp.Op.MUL || operator == IRBinaryOp.Op.DIV)) {
            return left;
        }

        // 3. Bitwise Identity Folding
        IRExpression bitwiseFolded = foldBitwiseIdentities(left, right, operator);
        if (bitwiseFolded != null) {
            return bitwiseFolded;
        }

        return new IRBinaryOp(left, right, operator, op.getTypeDescriptor());
    }

    private IRExpression foldNumericBinaryOp(Number n1, Number n2, IRBinaryOp.Op op, String typeDescriptor, IRExpression left, IRExpression right) {
        if (n1 instanceof BigDecimal || n2 instanceof BigDecimal) {
            BigDecimal bd1 = (n1 instanceof BigDecimal) ? (BigDecimal) n1 : new BigDecimal(n1.toString());
            BigDecimal bd2 = (n2 instanceof BigDecimal) ? (BigDecimal) n2 : new BigDecimal(n2.toString());
            return switch (op) {
                case ADD -> new IRLiteral(bd1.add(bd2), OceanTypeSystem.BIGDECIMAL_DESC);
                case SUB -> new IRLiteral(bd1.subtract(bd2), OceanTypeSystem.BIGDECIMAL_DESC);
                case MUL -> new IRLiteral(bd1.multiply(bd2), OceanTypeSystem.BIGDECIMAL_DESC);
                case DIV -> {
                    if (bd2.compareTo(BigDecimal.ZERO) == 0) yield new IRBinaryOp(left, right, op, typeDescriptor);
                    yield new IRLiteral(bd1.divide(bd2, MathContext.DECIMAL128), OceanTypeSystem.BIGDECIMAL_DESC);
                }
                case MOD -> {
                    if (bd2.compareTo(BigDecimal.ZERO) == 0) yield new IRBinaryOp(left, right, op, typeDescriptor);
                    yield new IRLiteral(bd1.remainder(bd2), OceanTypeSystem.BIGDECIMAL_DESC);
                }
                case EQ -> new IRLiteral(bd1.compareTo(bd2) == 0, "Z");
                case NE -> new IRLiteral(bd1.compareTo(bd2) != 0, "Z");
                case LT -> new IRLiteral(bd1.compareTo(bd2) < 0, "Z");
                case LE -> new IRLiteral(bd1.compareTo(bd2) <= 0, "Z");
                case GT -> new IRLiteral(bd1.compareTo(bd2) > 0, "Z");
                case GE -> new IRLiteral(bd1.compareTo(bd2) >= 0, "Z");
                default -> new IRBinaryOp(left, right, op, typeDescriptor);
            };
        }

        boolean isDouble = n1 instanceof Double || n2 instanceof Double;
        boolean isFloat = n1 instanceof Float || n2 instanceof Float;
        boolean isLong = n1 instanceof Long || n2 instanceof Long;

        return switch (op) {
            case ADD -> isDouble ? new IRLiteral(n1.doubleValue() + n2.doubleValue(), "D")
                    : isFloat ? new IRLiteral(n1.floatValue() + n2.floatValue(), "F")
                    : isLong ? new IRLiteral(n1.longValue() + n2.longValue(), "J")
                    : new IRLiteral(n1.intValue() + n2.intValue(), "I");
            case SUB -> isDouble ? new IRLiteral(n1.doubleValue() - n2.doubleValue(), "D")
                    : isFloat ? new IRLiteral(n1.floatValue() - n2.floatValue(), "F")
                    : isLong ? new IRLiteral(n1.longValue() - n2.longValue(), "J")
                    : new IRLiteral(n1.intValue() - n2.intValue(), "I");
            case MUL -> isDouble ? new IRLiteral(n1.doubleValue() * n2.doubleValue(), "D")
                    : isFloat ? new IRLiteral(n1.floatValue() * n2.floatValue(), "F")
                    : isLong ? new IRLiteral(n1.longValue() * n2.longValue(), "J")
                    : new IRLiteral(n1.intValue() * n2.intValue(), "I");
            case DIV -> {
                if (n2.doubleValue() == 0) yield new IRBinaryOp(left, right, op, typeDescriptor);
                yield isDouble ? new IRLiteral(n1.doubleValue() / n2.doubleValue(), "D")
                        : isFloat ? new IRLiteral(n1.floatValue() / n2.floatValue(), "F")
                        : isLong ? new IRLiteral(n1.longValue() / n2.longValue(), "J")
                        : new IRLiteral(n1.intValue() / n2.intValue(), "I");
            }
            case MOD -> {
                if (n2.doubleValue() == 0) yield new IRBinaryOp(left, right, op, typeDescriptor);
                yield isDouble ? new IRLiteral(n1.doubleValue() % n2.doubleValue(), "D")
                        : isFloat ? new IRLiteral(n1.floatValue() % n2.floatValue(), "F")
                        : isLong ? new IRLiteral(n1.longValue() % n2.longValue(), "J")
                        : new IRLiteral(n1.intValue() % n2.intValue(), "I");
            }
            case EQ -> new IRLiteral(n1.doubleValue() == n2.doubleValue(), "Z");
            case NE -> new IRLiteral(n1.doubleValue() != n2.doubleValue(), "Z");
            case LT -> new IRLiteral(n1.doubleValue() < n2.doubleValue(), "Z");
            case LE -> new IRLiteral(n1.doubleValue() <= n2.doubleValue(), "Z");
            case GT -> new IRLiteral(n1.doubleValue() > n2.doubleValue(), "Z");
            case GE -> new IRLiteral(n1.doubleValue() >= n2.doubleValue(), "Z");
            case BIT_AND -> isLong ? new IRLiteral(n1.longValue() & n2.longValue(), "J") : new IRLiteral(n1.intValue() & n2.intValue(), "I");
            case BIT_OR -> isLong ? new IRLiteral(n1.longValue() | n2.longValue(), "J") : new IRLiteral(n1.intValue() | n2.intValue(), "I");
            case BIT_XOR -> isLong ? new IRLiteral(n1.longValue() ^ n2.longValue(), "J") : new IRLiteral(n1.intValue() ^ n2.intValue(), "I");
            case LSHIFT -> isLong ? new IRLiteral(n1.longValue() << n2.intValue(), "J") : new IRLiteral(n1.intValue() << n2.intValue(), "I");
            case RSHIFT -> isLong ? new IRLiteral(n1.longValue() >> n2.intValue(), "J") : new IRLiteral(n1.intValue() >> n2.intValue(), "I");
            case URSHIFT -> isLong ? new IRLiteral(n1.longValue() >>> n2.intValue(), "J") : new IRLiteral(n1.intValue() >>> n2.intValue(), "I");
            default -> null;
        };
    }

    private IRExpression foldBooleanBinaryOp(boolean b1, boolean b2, IRBinaryOp.Op op) {
        return switch (op) {
            case AND, BIT_AND -> new IRLiteral(b1 && b2, "Z");
            case OR, BIT_OR -> new IRLiteral(b1 || b2, "Z");
            case BIT_XOR -> new IRLiteral(b1 ^ b2, "Z");
            case EQ -> new IRLiteral(b1 == b2, "Z");
            case NE -> new IRLiteral(b1 != b2, "Z");
            default -> null;
        };
    }

    private IRExpression foldBitwiseIdentities(IRExpression left, IRExpression right, IRBinaryOp.Op op) {
        if (op == IRBinaryOp.Op.BIT_AND) {
            if (isZero(left) || isZero(right)) {
                return new IRLiteral(0, "I");
            }
        } else if (op == IRBinaryOp.Op.BIT_OR || op == IRBinaryOp.Op.BIT_XOR) {
            if (isZero(left)) return right;
            if (isZero(right)) return left;
        } else if (op == IRBinaryOp.Op.LSHIFT || op == IRBinaryOp.Op.RSHIFT || op == IRBinaryOp.Op.URSHIFT) {
            if (isZero(right)) return left;
        }
        return null;
    }

    private IRExpression foldTernary(IRTernaryExpression ternary) {
        IRExpression cond = foldExpression(ternary.getCondition());
        Boolean boolCond = getLiteralBoolean(cond);
        if (boolCond != null) {
            return boolCond ? foldExpression(ternary.getTrueExpr()) : foldExpression(ternary.getFalseExpr());
        }
        return new IRTernaryExpression(cond, foldExpression(ternary.getTrueExpr()), foldExpression(ternary.getFalseExpr()), ternary.getTypeDescriptor());    }

    private IRExpression foldMethodCall(IRMethodCall mc) {
        String owner = mc.getOwner();
        String name = mc.getName();
        List<IRExpression> args = mc.getArguments();

        // 1. Math Constant Folding
        if ("java/lang/Math".equals(owner) || "Math".equals(owner)) {
            IRExpression mathFolded = foldMathMethod(name, args, mc.getTypeDescriptor());
            if (mathFolded != null) return mathFolded;
        }

        // 2. String Constant Method Folding
        IRExpression receiver = mc.getReceiver() != null ? foldExpression(mc.getReceiver()) : null;
        String strVal = getLiteralString(receiver);
        if (strVal != null) {
            IRExpression stringFolded = foldStringMethod(strVal, name, args);
            if (stringFolded != null) return stringFolded;
        }

        // 3. Array Constant Length Folding
        if ("length".equals(name) || "size".equals(name)) {
            IRExpression arrayLenFolded = foldArrayLength(receiver);
            if (arrayLenFolded != null) return arrayLenFolded;
        }

        return mc;
    }

    private IRExpression foldMathMethod(String name, List<IRExpression> args, String typeDescriptor) {
        if ("pow".equals(name) && args != null && args.size() == 2) {
            IRExpression base = foldExpression(args.get(0));
            IRExpression exp = foldExpression(args.get(1));
            Number expNum = getLiteralNumber(exp);
            if (expNum != null) {
                double expVal = expNum.doubleValue();
                String type = typeDescriptor != null ? typeDescriptor : "D";
                if (expVal == 2.0) {
                    return new IRBinaryOp(base, base, IRBinaryOp.Op.MUL, type);
                } else if (expVal == 3.0) {
                    IRBinaryOp square = new IRBinaryOp(base, base, IRBinaryOp.Op.MUL, type);
                    return new IRBinaryOp(square, base, IRBinaryOp.Op.MUL, type);
                }
            }
        } else if ("abs".equals(name) && args != null && args.size() == 1) {
            Number n = getLiteralNumber(foldExpression(args.getFirst()));
            if (n != null) {
                switch (n) {
                    case Integer i -> {
                        return new IRLiteral(Math.abs(i), "I");
                    }
                    case Long l -> {
                        return new IRLiteral(Math.abs(l), "J");
                    }
                    case Double v -> {
                        return new IRLiteral(Math.abs(v), "D");
                    }
                    default -> {
                    }
                }
            }
        } else if ("min".equals(name) && args != null && args.size() == 2) {
            Number n1 = getLiteralNumber(foldExpression(args.get(0)));
            Number n2 = getLiteralNumber(foldExpression(args.get(1)));
            if (n1 != null && n2 != null) {
                if (n1 instanceof Long || n2 instanceof Long) {
                    if (!(n1 instanceof Double || n2 instanceof Double || n1 instanceof Float || n2 instanceof Float)) {
                        return new IRLiteral(Math.min(n1.longValue(), n2.longValue()), "J");
                    }
                }
                if (n1 instanceof Integer && n2 instanceof Integer)
                    return new IRLiteral(Math.min(n1.intValue(), n2.intValue()), "I");
                return new IRLiteral(Math.min(n1.doubleValue(), n2.doubleValue()), "D");
            }
        } else if ("max".equals(name) && args != null && args.size() == 2) {
            Number n1 = getLiteralNumber(foldExpression(args.get(0)));
            Number n2 = getLiteralNumber(foldExpression(args.get(1)));
            if (n1 != null && n2 != null) {
                if (n1 instanceof Long || n2 instanceof Long) {
                    if (!(n1 instanceof Double || n2 instanceof Double || n1 instanceof Float || n2 instanceof Float)) {
                        return new IRLiteral(Math.min(n1.longValue(), n2.longValue()), "J");
                    }
                }
                if (n1 instanceof Integer && n2 instanceof Integer)
                    return new IRLiteral(Math.max(n1.intValue(), n2.intValue()), "I");
                return new IRLiteral(Math.max(n1.doubleValue(), n2.doubleValue()), "D");
            }
        } else if ("sqrt".equals(name) && args != null && args.size() == 1) {
            Number n = getLiteralNumber(foldExpression(args.getFirst()));
            if (n != null) {
                return new IRLiteral(Math.sqrt(n.doubleValue()), "D");
            }
        } else if ("round".equals(name) && args != null && args.size() == 1) {
            Number n = getLiteralNumber(foldExpression(args.getFirst()));
            if (n != null) {
                return new IRLiteral(Math.round(n.doubleValue()), "J");
            }
        } else if ("floor".equals(name) && args != null && args.size() == 1) {
            Number n = getLiteralNumber(foldExpression(args.getFirst()));
            if (n != null) {
                return new IRLiteral(Math.floor(n.doubleValue()), "D");
            }
        } else if ("ceil".equals(name) && args != null && args.size() == 1) {
            Number n = getLiteralNumber(foldExpression(args.getFirst()));
            if (n != null) {
                return new IRLiteral(Math.ceil(n.doubleValue()), "D");
            }
        } else if ("log".equals(name) && args != null && args.size() == 1) {
            Number n = getLiteralNumber(foldExpression(args.getFirst()));
            if (n != null) {
                return new IRLiteral(Math.log(n.doubleValue()), "D");
            }
        } else if ("log10".equals(name) && args != null && args.size() == 1) {
            Number n = getLiteralNumber(foldExpression(args.getFirst()));
            if (n != null) {
                return new IRLiteral(Math.log10(n.doubleValue()), "D");
            }
        } else if ("exp".equals(name) && args != null && args.size() == 1) {
            Number n = getLiteralNumber(foldExpression(args.getFirst()));
            if (n != null) {
                return new IRLiteral(Math.exp(n.doubleValue()), "D");
            }
        } else if ("sin".equals(name) && args != null && args.size() == 1) {
            Number n = getLiteralNumber(foldExpression(args.getFirst()));
            if (n != null) {
                return new IRLiteral(Math.sin(n.doubleValue()), "D");
            }
        } else if ("cos".equals(name) && args != null && args.size() == 1) {
            Number n = getLiteralNumber(foldExpression(args.getFirst()));
            if (n != null) {
                return new IRLiteral(Math.cos(n.doubleValue()), "D");
            }
        } else if ("tan".equals(name) && args != null && args.size() == 1) {
            Number n = getLiteralNumber(foldExpression(args.getFirst()));
            if (n != null) {
                return new IRLiteral(Math.tan(n.doubleValue()), "D");
            }
        }
        return null;
    }

    private IRExpression foldStringMethod(String str, String name, List<IRExpression> args) {
        if ("length".equals(name) && (args == null || args.isEmpty())) {
            return new IRLiteral(str.length(), "I");
        } else if ("trim".equals(name) && (args == null || args.isEmpty())) {
            return new IRLiteral(str.trim(), OceanTypeSystem.STRING_DESC);
        } else if ("strip".equals(name) && (args == null || args.isEmpty())) {
            return new IRLiteral(str.strip(), OceanTypeSystem.STRING_DESC);
        } else if ("stripLeading".equals(name) && (args == null || args.isEmpty())) {
            return new IRLiteral(str.stripLeading(), OceanTypeSystem.STRING_DESC);
        } else if ("stripTrailing".equals(name) && (args == null || args.isEmpty())) {
            return new IRLiteral(str.stripTrailing(), OceanTypeSystem.STRING_DESC);
        } else if ("isEmpty".equals(name) && (args == null || args.isEmpty())) {
            return new IRLiteral(str.isEmpty(), "Z");
        } else if ("isBlank".equals(name) && (args == null || args.isEmpty())) {
            return new IRLiteral(str.isBlank(), "Z");
        } else if ("toUpperCase".equals(name)) {
            return new IRLiteral(str.toUpperCase(Locale.ROOT), OceanTypeSystem.STRING_DESC);
        } else if ("toLowerCase".equals(name)) {
            return new IRLiteral(str.toLowerCase(Locale.ROOT), OceanTypeSystem.STRING_DESC);
        } else if ("substring".equals(name) && args != null) {
            if (args.size() == 1) {
                Number startNum = getLiteralNumber(foldExpression(args.getFirst()));
                if (startNum != null) {
                    int start = startNum.intValue();
                    if (start >= 0 && start <= str.length()) {
                        return new IRLiteral(str.substring(start), OceanTypeSystem.STRING_DESC);
                    }
                }
            } else if (args.size() == 2) {
                Number startNum = getLiteralNumber(foldExpression(args.get(0)));
                Number endNum = getLiteralNumber(foldExpression(args.get(1)));
                if (startNum != null && endNum != null) {
                    int start = startNum.intValue();
                    int end = endNum.intValue();
                    if (start >= 0 && end >= start && end <= str.length()) {
                        return new IRLiteral(str.substring(start, end), OceanTypeSystem.STRING_DESC);
                    }
                }
            }
        } else if ("equalsIgnoreCase".equals(name) && args != null && args.size() == 1) {
            String argStr = getLiteralString(foldExpression(args.getFirst()));
            if (argStr != null) {
                return new IRLiteral(str.equalsIgnoreCase(argStr), "Z");
            }
        } else if ("equals".equals(name) && args != null && args.size() == 1) {
            String argStr = getLiteralString(foldExpression(args.getFirst()));
            if (argStr != null) {
                return new IRLiteral(str.equals(argStr), "Z");
            }
        } else if ("contains".equals(name) && args != null && args.size() == 1) {
            String argStr = getLiteralString(foldExpression(args.getFirst()));
            if (argStr != null) {
                return new IRLiteral(str.contains(argStr), "Z");
            }
        } else if ("indexOf".equals(name) && args != null && args.size() == 1) {
            String argStr = getLiteralString(foldExpression(args.getFirst()));
            if (argStr != null) {
                return new IRLiteral(str.indexOf(argStr), "I");
            }
        } else if ("lastIndexOf".equals(name) && args != null && args.size() == 1) {
            String argStr = getLiteralString(foldExpression(args.getFirst()));
            if (argStr != null) {
                return new IRLiteral(str.lastIndexOf(argStr), "I");
            }
        } else if ("charAt".equals(name) && args != null && args.size() == 1) {
            Number idxNum = getLiteralNumber(foldExpression(args.getFirst()));
            if (idxNum != null) {
                int idx = idxNum.intValue();
                if (idx >= 0 && idx < str.length()) {
                    return new IRLiteral(str.charAt(idx), "C");
                }
            }
        } else if ("startsWith".equals(name) && args != null && args.size() == 1) {
            String prefix = getLiteralString(foldExpression(args.getFirst()));
            if (prefix != null) {
                return new IRLiteral(str.startsWith(prefix), "Z");
            }
        } else if ("endsWith".equals(name) && args != null && args.size() == 1) {
            String suffix = getLiteralString(foldExpression(args.getFirst()));
            if (suffix != null) {
                return new IRLiteral(str.endsWith(suffix), "Z");
            }
        } else if ("replace".equals(name) && args != null && args.size() == 2) {
            String target = getLiteralString(foldExpression(args.get(0)));
            String replacement = getLiteralString(foldExpression(args.get(1)));
            if (target != null && replacement != null) {
                return new IRLiteral(str.replace(target, replacement), OceanTypeSystem.STRING_DESC);
            }
        } else if ("repeat".equals(name) && args != null && args.size() == 1) {
            Number countNum = getLiteralNumber(foldExpression(args.getFirst()));
            if (countNum != null) {
                int count = countNum.intValue();
                if (count >= 0 && count <= 10000) {
                    return new IRLiteral(str.repeat(count), OceanTypeSystem.STRING_DESC);
                }
            }
        }
        return null;
    }

    private IRExpression foldArrayLength(IRExpression receiver) {
        if (receiver instanceof IRArrayLiteral arrLit) {
            if (arrLit.getElements() != null) {
                return new IRLiteral(arrLit.getElements().size(), "I");
            }
        } else if (receiver instanceof IRArrayCreation arrCreation) {
            if (arrCreation.getSizes() != null && !arrCreation.getSizes().isEmpty()) {
                Number szNum = getLiteralNumber(foldExpression(arrCreation.getSizes().getFirst()));
                if (szNum != null) {
                    return new IRLiteral(szNum.intValue(), "I");
                }
            }
        }
        return null;
    }

    private IRExpression foldUnaryOp(IRUnaryOp unary) {
        IRExpression inner = foldExpression(unary.getExpression());
        if (unary.getOperator() == IRUnaryOp.Op.NOT) {
            if (inner instanceof IRUnaryOp && ((IRUnaryOp) inner).getOperator() == IRUnaryOp.Op.NOT) {
                return ((IRUnaryOp) inner).getExpression();
            }
            Boolean b = getLiteralBoolean(inner);
            if (b != null) {
                return new IRLiteral(!b, "Z");
            }
        } else if (unary.getOperator() == IRUnaryOp.Op.NEG) {
            Number n = getLiteralNumber(inner);
            if (n != null) {
                switch (n) {
                    case Double v -> {
                        return new IRLiteral(-v, "D");
                    }
                    case Float f -> {
                        return new IRLiteral(-f, "F");
                    }
                    case Long l -> {
                        return new IRLiteral(-l, "J");
                    }
                    case Integer i -> {
                        return new IRLiteral(-i, "I");
                    }
                    default -> {
                    }
                }
            }
        } else if (unary.getOperator() == IRUnaryOp.Op.BIT_NOT) {
            Number n = getLiteralNumber(inner);
            if (n != null) {
                switch (n) {
                    case Long l -> {
                        return new IRLiteral(~l, "J");
                    }
                    case Integer i -> {
                        return new IRLiteral(~i, "I");
                    }
                    case Short s -> {
                        return new IRLiteral(~s.intValue(), "I");
                    }
                    case Byte b -> {
                        return new IRLiteral(~b.intValue(), "I");
                    }
                    default -> {
                    }
                }
            }
        }
        return new IRUnaryOp(inner, unary.getOperator(), unary.getTypeDescriptor());
    }

    // ==========================================
    // UTILITY HELPER METHODS
    // ==========================================

    private boolean isZero(IRExpression expr) {
        Number n = getLiteralNumber(expr);
        return n != null && n.doubleValue() == 0.0;
    }

    private boolean isOne(IRExpression expr) {
        Number n = getLiteralNumber(expr);
        return n != null && n.doubleValue() == 1.0;
    }

    private boolean isPure(IRExpression expr) {
        return switch (expr) {
            case null -> true;
            case IRLiteral literal -> true;
            case IRVariableAccess va -> va.getReceiver() == null || isPure(va.getReceiver());
            case IRBinaryOp bin -> isPure(bin.getLeft()) && isPure(bin.getRight());
            case IRUnaryOp un -> isPure(un.getExpression());
            default -> false;
        };
    }

    private Number getLiteralNumber(IRExpression expr) {
        if (expr instanceof IRLiteral) {
            Object val = ((IRLiteral) expr).getValue();
            if (val instanceof Number) {
                return (Number) val;
            }
        }
        return null;
    }

    private String getLiteralString(IRExpression expr) {
        if (expr instanceof IRLiteral) {
            Object val = ((IRLiteral) expr).getValue();
            if (val instanceof String) {
                return (String) val;
            }
        }
        return null;
    }

    private Boolean getLiteralBoolean(IRExpression expr) {
        if (expr instanceof IRLiteral) {
            Object val = ((IRLiteral) expr).getValue();
            if (val instanceof Boolean) {
                return (Boolean) val;
            }
        }
        return null;
    }

    // =========================================================================
    // AST ParseTree Constant Folding Utilities (Pure V3 Implementation)
    // =========================================================================

    private static final Object UNRESOLVED = new Object();
    private static final ConcurrentHashMap<String, Object> CONSTANT_CACHE =
            new ConcurrentHashMap<>();

    public static void clearCaches() {
        CONSTANT_CACHE.clear();
    }

    public static Object fold(ParseTree ctx) {
        if (ctx == null) {
            return null;
        }

        Object result;

        switch (ctx) {
            case OceanParser.PrimaryExprContext primaryExprContext -> {
                result = foldPrimary(primaryExprContext.primary());
                return result;
            }

            case OceanParser.AddSubExprContext addSubExprContext -> {
                result = foldAddSub(addSubExprContext);
                return result;
            }

            case OceanParser.MulDivModExprContext mulDivModExprContext -> {
                result = foldMulDivMod(mulDivModExprContext);
                return result;
            }

            case OceanParser.LogicalAndExprContext logicalAndExprContext -> {
                result = foldLogicalAnd(logicalAndExprContext);
                return result;
            }

            case OceanParser.LogicalOrExprContext logicalOrExprContext -> {
                result = foldLogicalOr(logicalOrExprContext);
                return result;
            }

            case OceanParser.ComparisonExprContext comparisonExprContext -> {
                result = foldComparison(comparisonExprContext);
                return result;
            }

            case OceanParser.EqualityExprContext equalityExprContext -> {
                result = foldEquality(equalityExprContext);
                return result;
            }

            case OceanParser.UnaryExprContext unaryExprContext -> {
                result = foldUnary(unaryExprContext);
                return result;
            }

            case OceanParser.ShiftExprContext shiftExprContext -> {
                result = foldShift(shiftExprContext);
                return result;
            }

            case OceanParser.BitAndExprContext bitAndExprContext -> {
                result = foldBitwise(bitAndExprContext);
                return result;
            }

            case OceanParser.BitOrExprContext bitOrExprContext -> {
                result = foldBitwise(bitOrExprContext);
                return result;
            }

            case OceanParser.BitXorExprContext bitXorExprContext -> {
                result = foldBitwise(bitXorExprContext);
                return result;
            }

            case OceanParser.TernaryExprContext ternaryExprContext -> {
                result = foldTernary(ternaryExprContext);
                return result;
            }

            case OceanParser.CastExprContext castExprContext -> {
                result = foldCast(castExprContext);
                return result;
            }

            default -> {}
        }

        if (ctx instanceof OceanParser.MemberCallExprContext ||
                ctx instanceof OceanParser.PrimaryContext) {

            String text = ctx.getText().replace(" ", "");
            Object resolved = resolveJavaConstant(text);

            if (resolved != UNRESOLVED) {
                return resolved;
            }
        }

        return null;
    }

    private static Object resolveJavaConstant(String expr) {
        Object cached = CONSTANT_CACHE.get(expr);
        if (cached != null) return cached;

        int dot = expr.lastIndexOf('.');
        if (dot <= 0 || dot >= expr.length() - 1) {
            CONSTANT_CACHE.put(expr, UNRESOLVED);
            return UNRESOLVED;
        }
        String simpleName = expr.substring(0, dot);
        String fieldName = expr.substring(dot + 1);

        String[] prefixes = {"java.lang.", "java.math.", "java.util.", "ocean.stdlib.", ""};
        for (String prefix : prefixes) {
            try {
                Class<?> cls = OceanTypeSystem.forName(prefix + simpleName);
                if (cls != null) {
                    Field f = cls.getField(fieldName);
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    Object value = f.get(null);
                    if (value instanceof Byte || value instanceof Short)
                        value = ((Number) value).intValue();
                    if (value instanceof Integer || value instanceof Long || value instanceof Float ||
                            value instanceof Double || value instanceof Boolean || value instanceof Character ||
                            value instanceof String || value instanceof BigDecimal) {
                        CONSTANT_CACHE.put(expr, value);
                        return value;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        CONSTANT_CACHE.put(expr, UNRESOLVED);
        return UNRESOLVED;
    }

    private static Object foldPrimary(OceanParser.PrimaryContext p) {
        if (p instanceof OceanParser.NumberPrimaryContext) {
            String raw = ((OceanParser.NumberPrimaryContext) p).NUMBER().getText();
            String val = raw.replace("_", "");
            String lower = val.toLowerCase();

            // Hex float
            if (lower.startsWith("0x") && (lower.contains("p") || lower.contains("."))) {
                if (lower.endsWith("f")) {
                    return Float.parseFloat(lower);
                } else {
                    return Double.parseDouble(lower);
                }
            }
            // Hex int
            if (lower.startsWith("0x")) {
                String digits = lower.substring(2);
                boolean isLong = digits.endsWith("l");
                if (isLong) digits = digits.substring(0, digits.length() - 1);
                if (isLong) {
                    return Long.parseUnsignedLong(digits, 16);
                } else {
                    try {
                        return Integer.parseUnsignedInt(digits, 16);
                    } catch (NumberFormatException e) {
                        return Long.parseUnsignedLong(digits, 16);
                    }
                }
            }
            // Bin int
            if (lower.startsWith("0b")) {
                String digits = lower.substring(2);
                boolean isLong = digits.endsWith("l");
                if (isLong) digits = digits.substring(0, digits.length() - 1);
                if (isLong) {
                    return Long.parseUnsignedLong(digits, 2);
                } else {
                    try {
                        return Integer.parseUnsignedInt(digits, 2);
                    } catch (NumberFormatException e) {
                        return Long.parseUnsignedLong(digits, 2);
                    }
                }
            }

            char last = Character.toUpperCase(val.charAt(val.length() - 1));
            if (last == 'F') {
                return Float.parseFloat(val.substring(0, val.length() - 1));
            }
            if (last == 'D') {
                return Double.parseDouble(val.substring(0, val.length() - 1));
            }
            if (last == 'L') {
                return Long.parseLong(val.substring(0, val.length() - 1));
            }
            if (val.contains(".") || val.contains("e") || val.contains("E")) {
                return Double.parseDouble(val);
            }
            // Octal int
            if (lower.startsWith("0") && lower.length() > 1) {
                boolean isOctal = true;
                for (int i = 1; i < lower.length(); i++) {
                    char c = lower.charAt(i);
                    if (c < '0' || c > '7') {
                        isOctal = false;
                        break;
                    }
                }
                if (isOctal) {
                    try {
                        return Integer.parseUnsignedInt(lower, 8);
                    } catch (NumberFormatException e) {
                        return Long.parseUnsignedLong(lower, 8);
                    }
                }
            }
            try {
                long n = Long.parseLong(val);
                if (n > Integer.MAX_VALUE || n < Integer.MIN_VALUE) return n;
                return (int) n;
            } catch (NumberFormatException e) {
                return new BigDecimal(val);
            }
        }
        if (p instanceof OceanParser.StringPrimaryContext sp) {
            if (sp.STRING_LITERAL() != null) {
                String s = sp.STRING_LITERAL().getText();
                return StringHelper.unescapeString(s.substring(1, s.length() - 1));
            } else {
                String s = sp.MULTILINE_STRING().getText();
                String raw = s.substring(3, s.length() - 3);
                return StringHelper.unescapeTextBlock(StringHelper.stripIndent(raw));
            }
        }
        if (p instanceof OceanParser.CharPrimaryContext) {
            String s = ((OceanParser.CharPrimaryContext) p).CHAR_LITERAL().getText();
            String inner = s.substring(1, s.length() - 1);
            if (inner.isEmpty()) return '\0';
            if (inner.startsWith("\\")) {
                String unescaped = StringHelper.unescapeString(inner);
                return unescaped.isEmpty() ? '\0' : unescaped.charAt(0);
            }
            return inner.charAt(0);
        }
        if (p instanceof OceanParser.TruePrimaryContext) return true;
        if (p instanceof OceanParser.FalsePrimaryContext) return false;
        if (p instanceof OceanParser.ParenthesizedPrimaryContext)
            return fold(((OceanParser.ParenthesizedPrimaryContext) p).expression());

        return null;
    }

    private static Object foldAddSub(OceanParser.AddSubExprContext ctx) {
        Object left = fold(ctx.expression(0));
        Object right = fold(ctx.expression(1));
        if (left == null || right == null) return null;
        String op = ctx.op.getText();

        if (op.equals("+") && (left instanceof String || right instanceof String)) {
            return left + String.valueOf(right);
        }

        if ((left instanceof Integer || left instanceof Long) && (right instanceof Integer || right instanceof Long)) {
            long l = ((Number) left).longValue();
            long r = ((Number) right).longValue();
            if (op.equals("+")) {
                long res;
                try {
                    res = Math.addExact(l, r);
                } catch (ArithmeticException e) {
                    return new BigDecimal(l).add(new BigDecimal(r));
                }
                if (left instanceof Integer && right instanceof Integer) {
                    if (res > Integer.MAX_VALUE || res < Integer.MIN_VALUE) return res;
                    return (int) res;
                }
                return res;
            } else {
                long res;
                try {
                    res = Math.subtractExact(l, r);
                } catch (ArithmeticException e) {
                    return new BigDecimal(l).subtract(new BigDecimal(r));
                }
                if (left instanceof Integer && right instanceof Integer) {
                    if (res > Integer.MAX_VALUE || res < Integer.MIN_VALUE) return res;
                    return (int) res;
                }
                return res;
            }
        }
        if (left instanceof BigDecimal || right instanceof BigDecimal) {
            BigDecimal l = (left instanceof BigDecimal) ? (BigDecimal) left : new BigDecimal(left.toString());
            BigDecimal r = (right instanceof BigDecimal) ? (BigDecimal) right : new BigDecimal(right.toString());
            return op.equals("+") ? l.add(r) : l.subtract(r);
        }
        if (left instanceof Number && right instanceof Number) {
            double l = ((Number) left).doubleValue(), r = ((Number) right).doubleValue();
            double res = op.equals("+") ? l + r : l - r;
            if (Double.isInfinite(res) || Double.isNaN(res)) {
                try {
                    BigDecimal bl = new BigDecimal(left.toString());
                    BigDecimal br = new BigDecimal(right.toString());
                    return op.equals("+") ? bl.add(br) : bl.subtract(br);
                } catch (Exception ignored) {
                }
            }
            return res;
        }
        return null;
    }

    private static Object foldMulDivMod(OceanParser.MulDivModExprContext ctx) {
        Object left = fold(ctx.expression(0));
        Object right = fold(ctx.expression(1));
        if (left == null || right == null) return null;
        String op = ctx.op.getText();

        if ((left instanceof Integer || left instanceof Long) && (right instanceof Integer || right instanceof Long)) {
            long l = ((Number) left).longValue();
            long r = ((Number) right).longValue();
            if (r == 0 && !op.equals("*")) return null;

            if (op.equals("*")) {
                long res;
                try {
                    res = Math.multiplyExact(l, r);
                } catch (ArithmeticException e) {
                    return new BigDecimal(l).multiply(new BigDecimal(r));
                }
                if (left instanceof Integer && right instanceof Integer) {
                    if (res > Integer.MAX_VALUE || res < Integer.MIN_VALUE) return res;
                    return (int) res;
                }
                return res;
            }

            long res = op.equals("/") ? (l / r) : (l % r);
            if (left instanceof Integer && right instanceof Integer) return (int) res;
            return res;
        }
        if (left instanceof BigDecimal || right instanceof BigDecimal) {
            BigDecimal l = (left instanceof BigDecimal) ? (BigDecimal) left : new BigDecimal(left.toString());
            BigDecimal r = (right instanceof BigDecimal) ? (BigDecimal) right : new BigDecimal(right.toString());
            if (r.signum() == 0 && !op.equals("*")) return null;
            switch (op) {
                case "*":
                    return l.multiply(r);
                case "/":
                    try {
                        return l.divide(r, MathContext.DECIMAL128);
                    } catch (Exception e) {
                        return null;
                    }
                case "%":
                    try {
                        return l.remainder(r);
                    } catch (Exception e) {
                        return null;
                    }
            }
        }
        if (left instanceof Number && right instanceof Number) {
            double l = ((Number) left).doubleValue(), r = ((Number) right).doubleValue();
            if (r == 0 && !op.equals("*")) return null;
            double res = switch (op) {
                case "*" -> l * r;
                case "/" -> l / r;
                case "%" -> l % r;
                default -> 0;
            };
            if (Double.isInfinite(res) || Double.isNaN(res)) {
                try {
                    BigDecimal bl = new BigDecimal(left.toString());
                    BigDecimal br = new BigDecimal(right.toString());
                    switch (op) {
                        case "*":
                            return bl.multiply(br);
                        case "/":
                            return bl.divide(br, MathContext.DECIMAL128);
                        case "%":
                            return bl.remainder(br);
                    }
                } catch (Exception ignored) {
                }
            }
            return res;
        }
        return null;
    }

    private static Object foldLogicalAnd(OceanParser.LogicalAndExprContext ctx) {
        Object left = fold(ctx.expression(0));
        if (left instanceof Boolean && !(Boolean) left) return false;
        Object right = fold(ctx.expression(1));
        if (left instanceof Boolean && right instanceof Boolean) return right;
        return null;
    }

    private static Object foldLogicalOr(OceanParser.LogicalOrExprContext ctx) {
        Object left = fold(ctx.expression(0));
        if (left instanceof Boolean && (Boolean) left) return true;
        Object right = fold(ctx.expression(1));
        if (left instanceof Boolean && right instanceof Boolean) return right;
        return null;
    }

    private static Object foldComparison(OceanParser.ComparisonExprContext ctx) {
        Object left = fold(ctx.expression(0));
        Object right = fold(ctx.expression(1));
        if (left instanceof Number && right instanceof Number) {
            double l = ((Number) left).doubleValue(), r = ((Number) right).doubleValue();
            switch (ctx.op.getText()) {
                case "<":
                    return l < r;
                case ">":
                    return l > r;
                case "<=":
                    return l <= r;
                case ">=":
                    return l >= r;
            }
        }
        return null;
    }

    private static Object foldEquality(OceanParser.EqualityExprContext ctx) {
        Object left = fold(ctx.expression(0));
        Object right = fold(ctx.expression(1));
        if (left != null && right != null) {
            boolean eq;
            switch (left) {
                case Number number when right instanceof Number -> {
                    if (left instanceof BigDecimal || right instanceof BigDecimal) {
                        BigDecimal bl = new BigDecimal(left.toString());
                        BigDecimal br = new BigDecimal(right.toString());
                        eq = (bl.compareTo(br) == 0);
                    } else {
                        double l = number.doubleValue();
                        double r = ((Number) right).doubleValue();
                        eq = (l == r);
                    }
                }
                case Character c when right instanceof Character -> eq = left.equals(right);
                case Character c when right instanceof Number -> {
                    double l = (int) c;
                    double r = ((Number) right).doubleValue();
                    eq = (l == r);
                }
                case Number number when right instanceof Character -> {
                    double l = number.doubleValue();
                    double r = (int) (Character) right;
                    eq = (l == r);
                }
                case Boolean b when right instanceof Boolean -> eq = left.equals(right);
                case String s when right instanceof String -> eq = left.equals(right);
                default -> {
                    return null;
                }
            }
            return ctx.op.getText().equals("==") == eq;
        }
        return null;
    }

    private static Object foldUnary(OceanParser.UnaryExprContext ctx) {
        Object val = fold(ctx.expression());
        if (val == null) return null;
        switch (ctx.op.getText()) {
            case "!":
                if (val instanceof Boolean) return !(Boolean) val;
                break;
            case "-":
                switch (val) {
                    case Integer i -> {
                        return -i;
                    }
                    case Long l -> {
                        return -l;
                    }
                    case Double v -> {
                        return -v;
                    }
                    case BigDecimal decimal -> {
                        return decimal.negate();
                    }
                    default -> {
                    }
                }
                break;
            case "~":
                if (val instanceof Integer) return ~(Integer) val;
                if (val instanceof Long) return ~(Long) val;
                break;
        }
        return null;
    }

    private static Object foldShift(OceanParser.ShiftExprContext ctx) {
        Object left = fold(ctx.expression(0));
        Object right = fold(ctx.expression(1));

        if (!(left instanceof Number) || !(right instanceof Number)) {
            return null;
        }

        if (left instanceof Float || left instanceof Double || left instanceof BigDecimal
                || right instanceof Float || right instanceof Double || right instanceof BigDecimal) {
            return null;
        }

        long r = ((Number) right).longValue();

        return switch (ctx.op.getText()) {
            case "<<" -> {
                if (left instanceof Long) {
                    yield ((Long) left) << r;
                }
                yield ((Number) left).intValue() << r;
            }
            case ">>" -> {
                if (left instanceof Long) {
                    yield ((Long) left) >> r;
                }
                yield ((Number) left).intValue() >> r;
            }
            case ">>>" -> {
                if (left instanceof Long) {
                    yield ((Long) left) >>> r;
                }
                yield ((Number) left).intValue() >>> r;
            }
            default -> null;
        };
    }

    private static Object foldBitwise(ParseTree ctx) {
        OceanParser.ExpressionContext left, right;
        String op;

        switch (ctx) {
            case OceanParser.BitAndExprContext c -> {
                left = c.expression(0);
                right = c.expression(1);
                op = "&";
            }
            case OceanParser.BitOrExprContext c -> {
                left = c.expression(0);
                right = c.expression(1);
                op = "|";
            }
            case OceanParser.BitXorExprContext c -> {
                left = c.expression(0);
                right = c.expression(1);
                op = "^";
            }
            default -> {
                return null;
            }
        }

        Object lv = fold(left);
        Object rv = fold(right);

        if (lv instanceof Boolean && rv instanceof Boolean) {
            boolean l = (Boolean) lv;
            boolean r = (Boolean) rv;
            return switch (op) {
                case "&" -> l & r;
                case "|" -> l | r;
                case "^" -> l ^ r;
                default -> false;
            };
        }

        if (lv instanceof Number && rv instanceof Number) {
            if (lv instanceof Float || lv instanceof Double || lv instanceof BigDecimal
                    || rv instanceof Float || rv instanceof Double || rv instanceof BigDecimal) {
                return null;
            }
            if (lv instanceof Long || rv instanceof Long) {
                long l = ((Number) lv).longValue();
                long r = ((Number) rv).longValue();
                return switch (op) {
                    case "&" -> l & r;
                    case "|" -> l | r;
                    case "^" -> l ^ r;
                    default -> 0L;
                };
            }
            int l = ((Number) lv).intValue();
            int r = ((Number) rv).intValue();

            return switch (op) {
                case "&" -> l & r;
                case "|" -> l | r;
                case "^" -> l ^ r;
                default -> 0;
            };
        }

        return null;
    }

    private static Object foldTernary(OceanParser.TernaryExprContext ctx) {
        // Ternary expressions should not be folded before semantic analysis (IRSemanticAnalyzer),
        // otherwise incompatible branch types (e.g. true ? "hello" : 42) would bypass type checking.
        // Constant folding of ternary expressions is performed post-analysis in IROptimizerPipeline via foldTernary(IRTernaryExpression).
        return null;
    }

    private static Object foldCast(OceanParser.CastExprContext ctx) {
        Object val = fold(ctx.expression());
        if (val == null) return null;
        String targetType = ctx.type().getText();
        if (val instanceof Number) {
            switch (targetType) {
                case "int":
                    return ((Number) val).intValue();
                case "long":
                    return ((Number) val).longValue();
                case "double":
                    return ((Number) val).doubleValue();
                case "float":
                    return ((Number) val).floatValue();
                case "byte":
                    return ((Number) val).byteValue();
                case "short":
                    return ((Number) val).shortValue();
                case "char":
                    return (char) ((Number) val).intValue();
            }
        } else if (val instanceof Character) {
            char c = (Character) val;
            switch (targetType) {
                case "int":
                    return (int) c;
                case "long":
                    return (long) c;
                case "double":
                    return (double) c;
                case "float":
                    return (float) c;
                case "byte":
                    return (byte) c;
                case "short":
                    return (short) c;
                case "char":
                    return c;
            }
        }
        return null;
    }

    private IRAnnotation optimizeAnnotation(IRAnnotation anno) {
        if (anno == null) return null;
        IRAnnotation opt = new IRAnnotation(anno.getTypeDescriptor());
        boolean changed = false;
        for (java.util.Map.Entry<String, IRExpression> entry : anno.getElements().entrySet()) {
            IRExpression val = entry.getValue() != null ? foldExpression(entry.getValue()) : null;
            opt.addElement(entry.getKey(), val != null ? val : entry.getValue());
            if (val != entry.getValue()) changed = true;
        }
        return changed ? opt : anno;
    }

    private IRSwitchPattern foldSwitchPattern(IRSwitchPattern p) {
        if (p == null) return null;
        IRExpression optExpr = p.getExpression() != null ? foldExpression(p.getExpression()) : null;
        IRExpression optGuard = p.getGuard() != null ? foldExpression(p.getGuard()) : null;
        List<IRSwitchPattern> optNested = new ArrayList<>();
        boolean nestedChanged = false;
        if (p.getNestedPatterns() != null) {
            for (IRSwitchPattern np : p.getNestedPatterns()) {
                IRSwitchPattern optNp = foldSwitchPattern(np);
                optNested.add(optNp);
                if (optNp != np) nestedChanged = true;
            }
        }
        if (optExpr == p.getExpression() && optGuard == p.getGuard() && !nestedChanged) {
            return p;
        }
        return new IRSwitchPattern(p.getKind(), p.getTypeDescriptor(), p.getVariableName(), optExpr, optNested, optGuard);
    }
}
