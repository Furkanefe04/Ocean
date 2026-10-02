package ocean.compiler.ir;

import java.util.List;

/**
 * Try-Catch bloğunu temsil eden IR düğümü.
 */
public class IRTryCatchStatement extends IRStatement {
    private final IRStatement tryBlock;
    private final List<IRCatchClause> catchClauses;
    private final IRStatement finallyBlock;
    private final List<String> resourceNames;

    public IRTryCatchStatement(IRStatement tryBlock, List<IRCatchClause> catchClauses, IRStatement finallyBlock, List<String> resourceNames) {
        this.tryBlock = tryBlock;
        this.catchClauses = catchClauses;
        this.finallyBlock = finallyBlock;
        this.resourceNames = resourceNames != null ? resourceNames : java.util.Collections.emptyList();
    }

    public IRTryCatchStatement(IRStatement tryBlock, List<IRCatchClause> catchClauses, IRStatement finallyBlock) {
        this(tryBlock, catchClauses, finallyBlock, java.util.Collections.emptyList());
    }

    public IRStatement getTryBlock() { return tryBlock; }
    public List<IRCatchClause> getCatchClauses() { return catchClauses; }
    public IRStatement getFinallyBlock() { return finallyBlock; }
    public List<String> getResourceNames() { return resourceNames; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitTryCatch(this);
    }

    public record IRCatchClause(String exceptionVar, List<String> exceptionTypes, IRStatement body) { }
}
