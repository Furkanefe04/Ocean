package ocean.compiler.ir;
/**
 * Çalıştırıldığında bir değer ve tip tanımlayıcısı (typeDescriptor) üreten tüm IR ifadelerinin temel soyut sınıfı.
 */
public abstract class IRExpression extends IRNode {
    private String typeDescriptor;

    public String getTypeDescriptor() {
        return typeDescriptor;
    }

    public void setTypeDescriptor(String typeDescriptor) {
        this.typeDescriptor = typeDescriptor;
    }
}