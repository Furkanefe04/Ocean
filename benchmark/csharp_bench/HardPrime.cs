using System;
using System.Diagnostics;

class HardPrime {
    static int CountPrimes(int max) {
        int count = 0;
        for (int i = 2; i <= max; i++) {
            int isPrime = 1;
            for (int j = 2; j * j <= i; j++) {
                if (i % j == 0) {
                    isPrime = 0;
                    break;
                }
            }
            if (isPrime == 1) count++;
        }
        return count;
    }

    static void Main() {
        Stopwatch sw = Stopwatch.StartNew();
        int count = CountPrimes(10000000);
        sw.Stop();
        Console.WriteLine("==============================================");
        Console.WriteLine("  C# HARD PRIME (10M) BENCHMARK");
        Console.WriteLine("  Sure: " + sw.ElapsedMilliseconds + " ms");
        Console.WriteLine("  Hesap: " + count);
        Console.WriteLine("==============================================");
    }
}
