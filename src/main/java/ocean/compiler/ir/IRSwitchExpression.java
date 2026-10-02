package ocean.compiler.ir;

import java.util.List;

/**
 * Değer döndüren Switch Expression yapısını temsil eden IR düğümü.
 */
public class IRSwitchExpression extends IRExpression {
    private final IRExpression expression;
    private final List<IRSwitchCase> cases;
    private final IRNode defaultBody;
    private final String typeDescriptor;

    public IRSwitchExpression(IRExpression expression, List<IRSwitchCase> cases, IRNode defaultBody, String typeDescriptor) {
        this.expression = expression;
        this.cases = cases;
        this.defaultBody = defaultBody;
        this.typeDescriptor = typeDescriptor;
    }

    public IRExpression getExpression() { return expression; }
    public List<IRSwitchCase> getCases() { return cases; }
    public IRNode getDefaultBody() { return defaultBody; }

    @Override
    public String getTypeDescriptor() {
        return typeDescriptor;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitSwitchExpression(this);
    }


}
