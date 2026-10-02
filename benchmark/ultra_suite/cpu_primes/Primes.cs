using System;
using System.Diagnostics;

class Primes {
    static int CountPrimes(int limit) {
        int count = 0;
        for (int i = 2; i <= limit; i++) {
            bool isPrime = true;
            for (int j = 2; j * j <= i; j++) {
                if (i % j == 0) {
                    isPrime = false;
                    break;
                }
            }
            if (isPrime) count++;
        }
        return count;
    }

    static void Main() {
        int limit = 2000000;
        Stopwatch sw = Stopwatch.StartNew();
        int count = CountPrimes(limit);
        sw.Stop();
        Console.WriteLine("CSHARP Count: " + count + ", Time: " + sw.ElapsedMilliseconds + " ms");
    }
}
