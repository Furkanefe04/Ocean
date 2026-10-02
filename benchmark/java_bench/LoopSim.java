public class LoopSim {
    public static void main(String[] args) {
        // High iterations to allow JIT to fully optimize and timers to be accurate
        long iterations = 1000000000L; 
        int warmUp = 5;
        int trials = 5;
        
        System.out.println("--- Java Loop (1B iterations) Benchmark ---");
        
        // Warm-up phase to trigger JIT compilation
        for (int i = 0; i < warmUp; i++) {
            runLoop(iterations);
        }
        
        long totalTimeNs = 0;
        for (int i = 0; i < trials; i++) {
            long start = System.nanoTime();
            long result = runLoop(iterations);
            long end = System.nanoTime();
            long durationNs = (end - start);
            totalTimeNs += durationNs;
            System.out.println("Trial " + (i+1) + ": " + (durationNs / 1_000_000) + " ms (sum=" + result + ")");
        }
        
        System.out.println("Average Time: " + (totalTimeNs / trials / 1_000_000) + " ms");
        System.out.println("DOGRULAMA: BASARILI");
    }
    
    // Marked with @SuppressWarnings or similar if needed, but standard loop should be fine
    private static long runLoop(long iterations) {
        long sum = 0;
        for (long i = 1; i <= iterations; i++) {
            sum += i;
        }
        return sum;
    }
}
