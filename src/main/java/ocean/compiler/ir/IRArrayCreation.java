package ocean.compiler.ir;

import java.util.List;
/**
 * Dizi oluşturma ifadelerini temsil eden IR düğümü (örn: new int[10] veya new String[5][2]).
 */
public class IRArrayCreation extends IRExpression {
    private final String baseType;
    private final List<IRExpression> sizes;
    private String rawType;

    public IRArrayCreation(String baseType, List<IRExpression> sizes) {
        this.baseType = baseType;
        this.sizes = sizes;
    }

    public String getRawType() { return rawType; }
    public void setRawType(String rawType) { this.rawType = rawType; }

    public String getBaseType() { return baseType; }
    public List<IRExpression> getSizes() { return sizes; }

    @Override
    public String getTypeDescriptor() {
        return "[".repeat(sizes.size()) +
                baseType;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitArrayCreation(this);
    }
}
