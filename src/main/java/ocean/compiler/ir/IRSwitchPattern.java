package ocean.compiler.ir;

import java.util.Collections;
import java.util.List;

/**
 * Represents a pattern in an Ocean pattern matching switch or match construct.
 */
public class IRSwitchPattern extends IRNode {

    public enum Kind {
        TYPE,
        NULL,
        EXPR,
        RECORD,
        UNNAMED
    }

    private final Kind kind;
    private String typeDescriptor; // for TYPE / RECORD / UNNAMED pattern (e.g. "Ljava/lang/String;")
    private final String variableName;   // for TYPE / RECORD pattern (e.g. "s" or "p")
    private final IRExpression expression; // for EXPR pattern
    private final List<IRSwitchPattern> nestedPatterns; // for RECORD pattern
    private final IRExpression guard;     // optional 'when' guard condition

    public IRSwitchPattern(Kind kind, String typeDescriptor, String variableName, IRExpression expression, List<IRSwitchPattern> nestedPatterns, IRExpression guard) {
        this.kind = kind;
        this.typeDescriptor = typeDescriptor;
        this.variableName = variableName;
        this.expression = expression;
        this.nestedPatterns = nestedPatterns != null ? nestedPatterns : Collections.emptyList();
        this.guard = guard;
    }

    public static IRSwitchPattern ofType(String typeDescriptor, String variableName, IRExpression guard) {
        return new IRSwitchPattern(Kind.TYPE, typeDescriptor, variableName, null, null, guard);
    }

    public static IRSwitchPattern ofNull(IRExpression guard) {
        return new IRSwitchPattern(Kind.NULL, null, null, null, null, guard);
    }

    public static IRSwitchPattern ofExpr(IRExpression expression, IRExpression guard) {
        return new IRSwitchPattern(Kind.EXPR, null, null, expression, null, guard);
    }

    public static IRSwitchPattern ofRecord(String typeDescriptor, String variableName, List<IRSwitchPattern> nestedPatterns, IRExpression guard) {
        return new IRSwitchPattern(Kind.RECORD, typeDescriptor, variableName, null, nestedPatterns, guard);
    }

    public static IRSwitchPattern ofUnnamed(String typeDescriptor, IRExpression guard) {
        return new IRSwitchPattern(Kind.UNNAMED, typeDescriptor, "_", null, null, guard);
    }

    public static IRSwitchPattern ofUnnamed(IRExpression guard) {
        return new IRSwitchPattern(Kind.UNNAMED, null, "_", null, null, guard);
    }

    public Kind getKind() {
        return kind;
    }

    public String getTypeDescriptor() {
        return typeDescriptor;
    }

    public void setTypeDescriptor(String typeDescriptor) {
        this.typeDescriptor = typeDescriptor;
    }

    public String getVariableName() {
        return variableName;
    }

    public IRExpression getExpression() {
        return expression;
    }

    public List<IRSwitchPattern> getNestedPatterns() {
        return nestedPatterns;
    }

    public IRExpression getGuard() {
        return guard;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitSwitchPattern(this);
    }
}
