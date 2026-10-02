package ocean.compiler.ir;

import java.util.List;

/**
 * OceanOutput (ekrana yazdırma) işlemini temsil eden IR düğümü.
 */
public class IROceanOutput extends IRExpression {
    private final List<IRExpression> arguments;

    public IROceanOutput(List<IRExpression> arguments) {
        this.arguments = arguments;
    }

    public List<IRExpression> getArguments() {
        return arguments;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitOceanOutput(this);
    }
}
