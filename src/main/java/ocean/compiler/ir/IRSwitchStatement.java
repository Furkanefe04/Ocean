package ocean.compiler.ir;

import java.util.List;

/**
 * Switch yapısını temsil eden IR düğümü.
 */
public class IRSwitchStatement extends IRStatement {
    private final IRExpression expression;
    private final List<IRSwitchCase> cases;
    private final IRStatement defaultBlock;
    private final boolean defaultArrow;

    public IRSwitchStatement(IRExpression expression, List<IRSwitchCase> cases, IRStatement defaultBlock, boolean defaultArrow) {
        this.expression = expression;
        this.cases = cases;
        this.defaultBlock = defaultBlock;
        this.defaultArrow = defaultArrow;
    }

    public IRSwitchStatement(IRExpression expression, List<IRSwitchCase> cases, IRStatement defaultBlock) {
        this(expression, cases, defaultBlock, false);
    }

    public IRExpression getExpression() { return expression; }
    public List<IRSwitchCase> getCases() { return cases; }
    public IRStatement getDefaultBlock() { return defaultBlock; }
    public boolean isDefaultArrow() { return defaultArrow; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitSwitch(this);
    }

}
