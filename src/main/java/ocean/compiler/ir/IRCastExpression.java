package ocean.compiler.ir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tip dönüşümünü (örn: (int) x veya (A & B) x) temsil eden IR düğümü.
 */
public class IRCastExpression extends IRExpression {
    private final IRExpression expression;
    private final String targetType;
    private final List<String> additionalBounds;

    public IRCastExpression(IRExpression expression, String targetType) {
        this(expression, targetType, Collections.emptyList());
    }

    public IRCastExpression(IRExpression expression, String targetType, List<String> additionalBounds) {
        this.expression = expression;
        this.targetType = targetType;
        this.additionalBounds = additionalBounds != null ? new ArrayList<>(additionalBounds) : Collections.emptyList();
        setTypeDescriptor(targetType);
    }

    public IRExpression getExpression() { return expression; }
    public String getTargetType() { return targetType; }
    public List<String> getAdditionalBounds() { return additionalBounds; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitCast(this);
    }
}
