package ocean.compiler.ir;

import java.util.List;

/**
 * Dizi literalini (örn: [1, 2, 3]) temsil eden IR düğümü.
 *
 * <p>{@code homogeneous} flag'i, literal'deki tüm elemanların (ve varsa iç
 * literallerin) aynı tam generic tipte olup olmadığını gösterir.
 * Heterojen literaller için generic-aware tip kontrolü uygulanır.</p>
 */
public class IRArrayLiteral extends IRExpression {
    private final List<IRExpression> elements;
    /** true → tüm elemanlar (recursive) aynı tam tipte; false → karışık tip var. */
    private boolean homogeneous = true;

    public IRArrayLiteral(List<IRExpression> elements, String typeDescriptor) {
        this.elements = elements;
        setTypeDescriptor(typeDescriptor);
    }

    public List<IRExpression> getElements() { return elements; }

    public boolean isHomogeneous() { return homogeneous; }
    public void setHomogeneous(boolean homogeneous) { this.homogeneous = homogeneous; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitArrayLiteral(this);
    }
}
