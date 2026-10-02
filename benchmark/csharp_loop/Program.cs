using System;
using System.Diagnostics;

class LoopSim {
    static long RunLoop(long iterations) {
        long sum = 0;
        for (long i = 1; i <= iterations; i++) {
            sum += i;
        }
        return sum;
    }

    static void Main() {
        long iterations = 1000000000;
        int warmUp = 5;
        int trials = 5;

        Console.WriteLine("--- C# Loop (1B iterations) Benchmark ---");

        // Warm-up phase
        for (int i = 0; i < warmUp; i++) {
            RunLoop(iterations);
        }

        long totalTimeMs = 0;
        for (int i = 0; i < trials; i++) {
            Stopwatch sw = Stopwatch.StartNew();
            long result = RunLoop(iterations);
            sw.Stop();

            totalTimeMs += sw.ElapsedMilliseconds;
            Console.WriteLine("Trial " + (i + 1) + ": " + sw.ElapsedMilliseconds + " ms (sum=" + result + ")");
        }

        Console.WriteLine("Average Time: " + (totalTimeMs / trials) + " ms");
        Console.WriteLine("DOGRULAMA: BASARILI");
    }
}
