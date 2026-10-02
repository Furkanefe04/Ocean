public class JavaBench4SumLoop {
    public static void main(String[] args) {
        // Warmup
        for (int w = 0; w < 5; w++) {
            long s = 0;
            for (int i = 1; i <= 1_000_000; i++) s += i;
        }
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 1; i <= 1_000_000_000; i++) sum += i;
        long end = System.nanoTime();
        System.out.println("sum(1B)=" + sum + " | time_ms=" + ((end - start) / 1_000_000));
    }
}
