package ocean.compiler;
/**
 * JVM üzerinde özyinelemeli (recursive) Fibonacci algoritması performansını ölçen kıyaslama sınıfı.
 */
public class PerformanceBenchmark {
    public static int fib(int n) {
        if (n <= 1) return n;
        return fib(n - 1) + fib(n - 2);
    }

    public static void main(String[] args) {
        System.out.println("--- Java Recursive Fibonacci(38) Benchmark Baslatiliyor ---");
        long start = System.currentTimeMillis();

        int result = fib(38);

        long end = System.currentTimeMillis();
        System.out.println("Sonuc (Fib 38): " + result);
        System.out.println("Java Calisma Suresi: " + (end - start) + "ms");
        System.out.println("--- Java Benchmark Tamamlandi ---");
    }
}
