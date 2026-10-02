package benchmarks;

import java.util.ArrayList;

public class JavaArrayListBenchmark {
    public static void main(String[] args) {
        int N = 10000000;
        int ROUNDS = 5;

        System.out.println("=== Java ArrayList<Integer> Benchmark (N=" + N + ", Rounds=" + ROUNDS + ") ===");

        long totalInitStart = System.nanoTime();
        ArrayList<Integer> list = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            list.add(i % 1000);
        }
        long initTimeMs = (System.nanoTime() - totalInitStart) / 1_000_000;
        System.out.println("List Initialization Time: " + initTimeMs + " ms");

        long totalSum = 0;
        long totalExecStart = System.nanoTime();

        for (int r = 0; r < ROUNDS; r++) {
            long roundStart = System.nanoTime();

            // 1. Indexed updates
            for (int i = 0; i < N; i++) {
                int updated = (list.get(i) * 3 + 7) % 10000;
                list.set(i, updated);
            }

            // 2. Foreach iteration sum
            long sum = 0;
            for (int val : list) {
                sum += val;
            }
            totalSum += sum;

            long roundTimeMs = (System.nanoTime() - roundStart) / 1_000_000;
            System.out.println("  Round " + (r + 1) + ": " + roundTimeMs + " ms (Checksum: " + sum + ")");
        }

        long totalExecTimeMs = (System.nanoTime() - totalExecStart) / 1_000_000;
        System.out.println("----------------------------------------------");
        System.out.println("Total Execution Time: " + totalExecTimeMs + " ms (Avg: " + (totalExecTimeMs / ROUNDS) + " ms/round)");
        System.out.println("Final Checksum: " + totalSum);
    }
}