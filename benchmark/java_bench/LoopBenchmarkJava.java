public class LoopBenchmarkJava {
    public static void main(String[] args) {
        System.out.println("--- Java Loop (100M Iterations) Benchmark Baslatiliyor ---");
        int limit = 100000000;
        long sum = 0;
        long start = System.currentTimeMillis();
        
        for (int i = 1; i <= limit; i++) {
            sum = sum + 1;
        }
        
        long end = System.currentTimeMillis();
        System.out.println("Sonuc: " + sum);
        System.out.println("Java Calisma Suresi: " + (end - start) + "ms");
        System.out.println("--- Java Loop Benchmark Tamamlandi ---");

    }
}
