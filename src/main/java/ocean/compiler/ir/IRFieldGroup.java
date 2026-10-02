package ocean.compiler.ir;

import java.util.Collections;
import java.util.List;

/**
 * Birden fazla alan tanımını (multi-field declaration) gruplayan IR düğümü.
 */
public class IRFieldGroup extends IRNode {
    private final List<IRField> fields;

    public IRFieldGroup(List<IRField> fields) {
        this.fields = fields != null ? fields : Collections.emptyList();
    }

    public List<IRField> getFields() {
        return fields;
    }

    @Override
    public void accept(IRVisitor visitor) {
        for (IRField f : fields) {
            f.accept(visitor);
        }
    }
}