package ocean.compiler;

/**
 * Derleme sırasında oluşan ve sürecin hemen sonlandırılmasını gerektiren
 * yapısal/semantik hatalar için fırlatılan özel istisna sınıfı.
 */
public class CompilationException extends RuntimeException {
    public CompilationException(String message) {
        super(message);
    }
}
