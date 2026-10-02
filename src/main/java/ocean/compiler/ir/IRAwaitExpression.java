package ocean.compiler.ir;

/**
 * `await expression` sözdizimini temsil eden IR düğümü.
 * Bytecode üretimi sırasında CompletableFuture.join() çağrısına dönüştürülür.
 * Gerekirse primitive unbox (Integer.intValue() vb.) de eklenir.
 */
public class IRAwaitExpression extends IRExpression {

    /** await'in operandı — CompletableFuture döndüren bir ifade olmalı */
    private final IRExpression future;

    /**
     * @param future           Beklenen CompletableFuture ifadesi
     * @param resultTypeDescriptor  .join() çağrısından çıkacak değerin JVM descriptor'ı
     *                         (örn. "Ljava/lang/String;", "I", "V")
     */
    public IRAwaitExpression(IRExpression future, String resultTypeDescriptor) {
        this.future = future;
        setTypeDescriptor(resultTypeDescriptor);
    }

    public IRExpression getFuture() {
        return future;
    }

    @Override
    public void accept(IRVisitor visitor) {
        visitor.visitAwaitExpression(this);
    }
}
