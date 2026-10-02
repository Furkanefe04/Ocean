package ocean.compiler.ir;

import java.util.ArrayList;
import java.util.List;

/**
 * Switch yapısının her bir case yapısını temsil eden IR düğümü.
 */
public class IRSwitchCase {
    private final List<IRSwitchPattern> patterns;
    private final List<IRExpression> values;
    private final IRExpression guard;
    private final IRNode body;
    private final boolean arrow;

    public IRSwitchCase(List<IRSwitchPattern> patterns, List<IRExpression> values, IRExpression guard, IRNode body, boolean arrow) {
        this.patterns = patterns != null ? patterns : new ArrayList<>();
        this.values = values != null ? values : new ArrayList<>();
        this.guard = guard;
        this.body = body;
        this.arrow = arrow;
    }

    public IRSwitchCase(List<IRExpression> values, IRStatement body, boolean arrow) {
        this(null, values, null, body, arrow);
    }

    public IRSwitchCase(List<IRExpression> values, IRStatement body) {
        this(null, values, null, body, false);
    }

    public List<IRSwitchPattern> getPatterns() { return patterns; }
    public List<IRExpression> getValues() { return values; }
    public IRExpression getGuard() { return guard; }
    public IRNode getBody() { return body; }
    public boolean isArrow() { return arrow; }

    public boolean hasPattern() {
        if (!patterns.isEmpty()) {
            for (IRSwitchPattern p : patterns) {
                if (p.getKind() == IRSwitchPattern.Kind.TYPE || p.getKind() == IRSwitchPattern.Kind.NULL || p.getGuard() != null) {
                    return true;
                }
            }
        }
        return guard != null;
    }
}
