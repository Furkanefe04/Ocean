using System;
using System.Diagnostics;

class SieveSim {
    static int CountPrimes(int max) {
        int[] isPrime = new int[max];
        isPrime[0] = 1; isPrime[1] = 1;
        int p = 2;
        while (p * p <= max) {
            if (isPrime[p] == 0) {
                int i = p * p;
                while (i < max) { isPrime[i] = 1; i += p; }
            }
            p++;
        }
        int count = 0;
        for (int i = 2; i < max; i++) { if (isPrime[i] == 0) count++; }
        return count;
    }

    static void Main() {
        Stopwatch sw = Stopwatch.StartNew();
        int count = CountPrimes(20000000);
        sw.Stop();
        Console.WriteLine("==============================================");
        Console.WriteLine("  C# SIEVE(20M) BENCHMARK");
        Console.WriteLine("  Sure: " + sw.ElapsedMilliseconds + " ms");
        Console.WriteLine("  Hesap: " + count);
        Console.WriteLine("==============================================");
    }
}
