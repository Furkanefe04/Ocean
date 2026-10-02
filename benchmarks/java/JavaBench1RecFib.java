public class JavaBench1RecFib {
    static int fib(int n) {
        if (n <= 1) return n;
        return fib(n - 1) + fib(n - 2);
    }
    public static void main(String[] args) {
        long start = System.nanoTime();
        int result = fib(42);
        long end = System.nanoTime();
        System.out.println("fib(42)=" + result + " | time_ms=" + ((end - start) / 1_000_000));
    }
}
