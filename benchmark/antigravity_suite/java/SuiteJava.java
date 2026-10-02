package benchmark.antigravity_suite.java;

import java.util.Arrays;

public class SuiteJava {

    public static long fibPureIterative(int n) {
        if (n <= 1) return n;
        long a = 0, b = 1, c = 0;
        for (int i = 2; i <= n; i++) {
            c = a + b;
            a = b;
            b = c;
        }
        return b;
    }

    interface Speakable {
        void speak();
    }

    static class Entity implements Speakable {
        int val = 0;
        public void speak() { val++; }
    }

    public static void main(String[] args) {
        // 1. FIBONACCI
        long t0 = System.nanoTime();
        long fsum = 0;
        for (int i = 0; i < 50000; i++) {
            fsum += fibPureIterative(100);
        }
        double fibMs = (System.nanoTime() - t0) / 1e6;

        // 2. CONTIGUOUS 1D MATRIX MULTIPLICATION (500x500 flat double[])
        int N = 500;
        double[] A = new double[N * N];
        double[] B = new double[N * N];
        double[] C = new double[N * N];
        Arrays.fill(A, 1.0);
        Arrays.fill(B, 2.0);

        t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            for (int k = 0; k < N; k++) {
                double a_ik = A[i * N + k];
                for (int j = 0; j < N; j++) {
                    C[i * N + j] += a_ik * B[k * N + j];
                }
            }
        }
        double matrixMs = (System.nanoTime() - t0) / 1e6;

        // 3. PRIME SIEVE (10M elements)
        int limit = 10000000;
        boolean[] isPrime = new boolean[limit + 1];
        Arrays.fill(isPrime, true);
        t0 = System.nanoTime();
        for (int p = 2; p * p <= limit; p++) {
            if (isPrime[p]) {
                for (int i = p * p; i <= limit; i += p)
                    isPrime[i] = false;
            }
        }
        double sieveMs = (System.nanoTime() - t0) / 1e6;

        // 4. PURE VIRTUAL DISPATCH LATENCY (Pre-allocated persistent object)
        Speakable entity = new Entity();
        long iterations = 100000000; // 100M calls
        t0 = System.nanoTime();
        for (long i = 0; i < iterations; i++) {
            entity.speak();
        }
        double oopMs = (System.nanoTime() - t0) / 1e6;
        double dispatchNs = (oopMs * 1e6) / (double)iterations;

        System.out.println("DYNAMIC_BENCH_RESULTS");
        System.out.printf("FIB_MS:%.2f\n", fibMs);
        System.out.printf("MATRIX_MS:%.2f\n", matrixMs);
        System.out.printf("SIEVE_MS:%.2f\n", sieveMs);
        System.out.printf("OOP_DISPATCH_NS:%.3f\n", dispatchNs);
        System.out.printf("OOP_OVERHEAD_MS:%.2f\n", oopMs);
    }
}
