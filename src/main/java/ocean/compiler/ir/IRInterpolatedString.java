package ocean.compiler.ir;

import ocean.compiler.OceanTypeSystem;

import java.util.List;

/**
 * İnterpolasyonlu stringi ($"x = {x}") temsil eden IR düğümü.
 */
public class IRInterpolatedString extends IRExpression {
    private final List<IRNode> parts;

    public IRInterpolatedString(List<IRNode> parts) {
        this.parts = parts;
        setTypeDescriptor(OceanTypeSystem.STRING_DESC);
    }

    public List<IRNode> getParts() { return parts; }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitInterpolatedString(this);
    }
}
