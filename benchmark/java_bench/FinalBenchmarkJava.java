class Base {
    public int increment(int n) { return n + 1; }
}
class Sub extends Base {
    @Override
    public int increment(int n) { return n + 2; }
}

public class FinalBenchmarkJava {
    public static int fib(int n) {
        if (n <= 1) return n;
        return fib(n - 1) + fib(n - 2);
    }

    public static void main(String[] args) {
        System.out.println("--- Java Final Benchmark Suite ---");

        // 1. Turbo Loop (100M)
        System.out.println("\n[Test 1: Turbo Loop 100M]");
        long start1 = System.currentTimeMillis();
        int sum = 0;
        for (int i = 1; i <= 100000000; i++) {
            sum += 1;
        }
        long end1 = System.currentTimeMillis();
        System.out.println("Result: " + sum);
        System.out.println("Süre: " + (end1 - start1) + "ms");

        // 2. OOP Dispatch (3M)
        System.out.println("\n[Test 2: OOP Dynamic Dispatch 3M]");
        long start2 = System.currentTimeMillis();
        int total = 0;
        for (int i = 0; i < 3000000; i++) {
            Base s = new Sub();
            total += s.increment(1);
        }
        long end2 = System.currentTimeMillis();
        System.out.println("Result: " + total);
        System.out.println("Süre: " + (end2 - start2) + "ms");

        // 3. Logic (Fib 38)
        System.out.println("\n[Test 3: Recursive Fibonacci 38]");
        long start3 = System.currentTimeMillis();
        int resFib = fib(38);
        long end3 = System.currentTimeMillis();
        System.out.println("Result: " + resFib);
        System.out.println("Süre: " + (end3 - start3) + "ms");

        System.out.println("\n--- Java Benchmark Suite Tamamlandı ---");
    }
}
