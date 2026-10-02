public class JavaBench9VirtualDispatch {
    interface Computable { long compute(int n); }
    static class Alpha implements Computable { public long compute(int n) { return n * 2L; } }
    static class Beta  implements Computable { public long compute(int n) { return n * 3L + 1; } }
    static class Gamma implements Computable { public long compute(int n) { return (long)n * n; } }
    static class Delta implements Computable { public long compute(int n) { return (n + 1L) * 2; } }

    public static void main(String[] args) {
        int n = 10_000_000;
        Computable[] cs = { new Alpha(), new Beta(), new Gamma(), new Delta() };
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < n; i++) sum += cs[i % 4].compute(i);
        long end = System.nanoTime();
        System.out.println("sum=" + sum + " | time_ms=" + ((end - start) / 1_000_000));
    }
}
