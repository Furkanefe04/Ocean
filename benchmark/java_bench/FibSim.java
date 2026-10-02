public class FibSim {
    static int fib(int n) {
        if (n <= 1) return n;
        return fib(n - 1) + fib(n - 2);
    }

    public static void main(String[] args) {
        long start = System.nanoTime();
        int result = fib(38);
        long dur = (System.nanoTime() - start) / 1000000;
        System.out.println("==============================================");
        System.out.println("  JAVA FIBONACCI(38) BENCHMARK");
        System.out.println("  Sure: " + dur + " ms");
        System.out.println("  Hesap: " + result);
        System.out.println("==============================================");
    }
}
