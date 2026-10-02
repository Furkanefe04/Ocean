package ocean.compiler;

/**
 * Semantik analiz sırasında bir değişkenin tip bilgisini tutar.
 * Bytecode descriptor'ı, final durumu ve ilk değer atanıp atanmadığını takip eder.
 */
public class TypeInfo {
    private String descriptor;   // JVM tip descriptor'ı: "I", "Ljava/lang/String;" vb.
    private final String rawType;      // Ham tip bilgisi (generics içeren)
    private final String originalDescriptor; // Orijinal deklare edilen tip descriptor'ı
    private final boolean isFinal;     // value ile tanımlandıysa true
    private boolean isInitialized;     // İlk değer atandı mı?
    private int usageCount;            // Kaç kez okundu (dead code tespiti için)

    public TypeInfo(String descriptor, String rawType, boolean isFinal, boolean isInitialized) {
        this.descriptor = descriptor;
        this.rawType = rawType;
        this.originalDescriptor = descriptor;
        this.isFinal = isFinal;
        this.isInitialized = isInitialized;
        this.usageCount = 0;
    }

    public String getOriginalDescriptor() {
        return originalDescriptor;
    }

    public String getDescriptor() {
        return descriptor;
    }

    public String getRawType() {
        return rawType != null ? rawType : descriptor;
    }

    public void setDescriptor(String descriptor) {
        this.descriptor = descriptor;
    }

    public boolean isFinal() {
        return isFinal;
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public void setInitialized(boolean isInitialized) {
        this.isInitialized = isInitialized;
    }

    public void markInitialized() {
        this.isInitialized = true;
    }

    public void incrementUsage() {
        this.usageCount++;
    }

    public int getUsageCount() {
        return usageCount;
    }

    public boolean isUnused() {
        return usageCount == 0;
    }

    /**
     * İnsan tarafından okunabilir tip adı döndürür.
     * Örnek: "I" → "int", "Ljava/lang/String;" → "String"
     */
    public String humanReadable() {
        return TypeChecker.humanReadable(descriptor);
    }

    private boolean isCaptured = false;
    private boolean isMutated = false;

    public boolean isCaptured() {
        return isCaptured;
    }

    public void setCaptured(boolean captured) {
        this.isCaptured = captured;
    }

    public boolean isMutated() {
        return isMutated;
    }

    public void setMutated(boolean mutated) {
        this.isMutated = mutated;
    }

    @Override
    public String toString() {
        return "TypeInfo{" + humanReadable() + (isFinal ? ", final" : "") +
                (isInitialized ? ", initialized" : ", uninitialized") +
                ", usage=" + usageCount + ", captured=" + isCaptured + ", mutated=" + isMutated + "}";
    }
}
