package ocean.compiler.ir;

/**
 * 'instanceof' işlemini temsil eden IR düğümü. Destekler: Type Pattern ve Record / Deconstruction Pattern.
 */
public class IRInstanceof extends IRExpression {
    private final IRExpression expression;
    private String targetType;
    private final String patternVarName;
    private final IRSwitchPattern pattern;

    public IRInstanceof(IRExpression expression, String targetType, String patternVarName) {
        this.expression = expression;
        this.targetType = targetType;
        this.patternVarName = patternVarName;
        this.pattern = targetType != null ? IRSwitchPattern.ofType(targetType, patternVarName, null) : null;
        setTypeDescriptor("Z");
    }

    public IRInstanceof(IRExpression expression, IRSwitchPattern pattern) {
        this.expression = expression;
        this.pattern = pattern;
        this.targetType = pattern != null ? pattern.getTypeDescriptor() : null;
        this.patternVarName = pattern != null ? pattern.getVariableName() : null;
        setTypeDescriptor("Z");
    }

    public IRInstanceof(IRExpression expression, String targetType) {
        this(expression, targetType, null);
    }

    public IRExpression getExpression() { return expression; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) {
        this.targetType = targetType;
        if (this.pattern != null && this.pattern.getKind() == IRSwitchPattern.Kind.TYPE) {
            String desc = targetType;
            if (desc != null && !desc.startsWith("L") && !desc.startsWith("[")) {
                desc = "L" + desc.replace('.', '/') + ";";
            }
            this.pattern.setTypeDescriptor(desc);
        }
    }
    public String getPatternVarName() { return patternVarName; }
    public IRSwitchPattern getPattern() { return pattern; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitInstanceof(this);
    }
}
